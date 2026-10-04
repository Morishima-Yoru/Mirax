package me.trinitrix.mirax

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.view.animation.PathInterpolator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.host.PrivilegeProbe
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.BroadcastNameRules
import me.trinitrix.mirax.session.ConnectionRun
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.MiraxDisplayReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.StandardModeGroups
import me.trinitrix.mirax.session.StandardVideoModes
import me.trinitrix.mirax.session.SystemLocaleReport
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] and forwards user actions.
 *
 * Narrow windows replace the page. Wide windows keep a 50/50 split: the start
 * pane is the section, the end pane is the open subpage. Spacing, chips, and
 * the hamburger drawer follow the dashboard visual system.
 */
class MainActivity : AppCompatActivity() {
    private enum class Page {
        DASHBOARD,
        HISTORY,
        GENERAL,
        LANGUAGE,
        PICTURE,
        CUSTOM,
        MODES,
        SCALE,
        ADVANCED,
        COMPATIBILITY,
        ABOUT,
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private enum class PageMotion { NONE, FORWARD, BACK, FADE }

    private var page: Page = Page.DASHBOARD
    private var drawerOpen: Boolean = false
    private var drawerPresented: Boolean = false
    private var drawerSwipePointer = MotionEvent.INVALID_POINTER_ID
    private var drawerSwipeX = 0f
    private var drawerSwipeY = 0f
    private var drawerSwipeClaimed = false
    private var drawerSwipeRejected = false
    private val drawerSwipeVelocity = VelocityTracker.obtain()
    private var shownStart: Page? = null
    private var shownEnd: Page? = null
    private val motionInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var updatingControls: Boolean = false
    private var enableBallWhenOverlayGranted: Boolean = false
    private var appliedAppLanguage: AppLanguage? = null
    private val historyOpen = mutableSetOf<Int>()
    private var customDragFrom: Int? = null
    private var modesRatio: String = "16:9"
    private var modesSize: Pair<Int, Int>? = null
    private var leaveAppPending: Boolean = false
    private val clearLeaveAppPending = Runnable { leaveAppPending = false }
    /** Avoid [commitBroadcastName] re-entering [render] while [openPage] is clearing focus. */
    private var suppressBroadcastNameCommit: Boolean = false

    /** Avoid [commitMaxBitrate] re-entering [render] while navigation is clearing focus. */
    private var suppressMaxBitrateCommit: Boolean = false

    private val autoWaitRunnable = object : Runnable {
        override fun run() {
            val session = MiraxApp.instance.session
            session.handle(SessionAction.AutoWait)
            refreshPrivilege()
            render(session.snapshot())
            if (session.snapshot().phase == ScreenPhase.FROZEN) {
                handler.postDelayed(this, AUTO_WAIT_MS)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onDrawerSwipe(ev)) {
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            finishBroadcastNameEditingIfTapOutside(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Tap outside the name field (and outside the IME) commits like keyboard Done.
     *
     * Persist and clear focus only — do not [render] / rebuild the drawer here.
     * A synchronous rebuild on ACTION_DOWN destroys the view under the finger
     * (Menu, drawer rows), so the click never lands and navigation sticks on
     * General. The click target's own handler will [render] afterwards.
     */
    private fun finishBroadcastNameEditingIfTapOutside(ev: MotionEvent) {
        val field = currentFocus as? EditText ?: return
        if (field.tag != "name") {
            return
        }
        if (touchHitsView(ev, field)) {
            return
        }
        suppressBroadcastNameCommit = true
        try {
            persistBroadcastName(field)
            field.clearFocus()
        } finally {
            suppressBroadcastNameCommit = false
        }
        binding.root.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun touchHitsView(ev: MotionEvent, view: View): Boolean {
        val onScreen = IntArray(2)
        view.getLocationOnScreen(onScreen)
        val x = ev.rawX
        val y = ev.rawY
        return x >= onScreen[0] && x < onScreen[0] + view.width &&
            y >= onScreen[1] && y < onScreen[1] + view.height
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepDashboardBelowSystemBars()
        savedInstanceState?.getString(STATE_PAGE)?.let { name ->
            page = Page.entries.firstOrNull { it.name == name } ?: Page.DASHBOARD
        }
        binding.menuButton.setOnClickListener { openDrawer() }
        binding.startBack.setOnClickListener { navigateUp() }
        binding.endBack.setOnClickListener { navigateUp() }
        binding.drawerClose.setOnClickListener { closeDrawer() }
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDashboardBack(this)
                }
            },
        )

        val session = MiraxApp.instance.session
        refreshEnvironmentInputs()
        refreshPrivilege()
        session.handle(SessionAction.OpenApp)
        SessionHost.commit(this)
        maybeRequestShizuku(session.snapshot())
        render(session.snapshot())
    }

    /**
     * The status bar stays visible on the dashboard. Content is padded by the
     * system bars. The camera cutout only insets the layout when the Advanced
     * setting asks for it; the window still draws into short-edge cutouts.
     */
    private fun keepDashboardBelowSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.statusBars())
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            applyDashboardInsets(view, insets)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyDashboardInsets(view: View, insets: WindowInsetsCompat) {
        val types = if (MiraxApp.instance.session.snapshot().cameraCutoutAffectsLayout) {
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        } else {
            WindowInsetsCompat.Type.systemBars()
        }
        val bars = insets.getInsets(types)
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    }

    private fun refreshDashboardInsets() {
        if (!::binding.isInitialized) {
            return
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStart() {
        super.onStart()
        instance = this
        showing = true
        refreshEnvironmentInputs()
        refreshPrivilege()
        val session = MiraxApp.instance.session
        if (enableBallWhenOverlayGranted && !session.snapshot().showOverlayPermissionReminder) {
            enableBallWhenOverlayGranted = false
            session.handle(SessionAction.SetFloatingBallEnabled(true))
        }
        render(SessionHost.openedMiraxDashboard(this))
        scheduleAutoWaitIfFrozen()
    }

    override fun onStop() {
        handler.removeCallbacks(autoWaitRunnable)
        if (instance === this) {
            instance = null
        }
        showing = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
    }

    private fun renderFromHost() {
        if (!showing || !::binding.isInitialized) {
            return
        }
        render(MiraxApp.instance.session.snapshot())
    }

    private fun onRetry() {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.Retry)
        refreshEnvironmentInputs()
        refreshPrivilege()
        render(session.snapshot())
    }

    private fun refreshEnvironmentInputs() {
        val session = MiraxApp.instance.session
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese()))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        session.report(MiraxDisplayReport(binding.root.display?.displayId ?: 0))
        applyResolvedLanguage(session.snapshot().appLanguage, recreateUi = true)
    }

    private fun applyResolvedLanguage(language: AppLanguage, recreateUi: Boolean) {
        if (appliedAppLanguage == language) {
            return
        }
        HostEnvironment.applyAppLanguage(language)
        val previous = appliedAppLanguage
        appliedAppLanguage = language
        if (recreateUi && previous != null) {
            recreate()
        }
    }

    private fun refreshPrivilege() {
        SessionHost.refreshPrivilegeAndSurfaces(this)
    }

    private fun maybeRequestShizuku(snapshot: SessionSnapshot) {
        if (snapshot.shouldRequestShizukuPermission) {
            PrivilegeProbe.requestShizukuPermission(this)
        }
        if (snapshot.wfdOwner == WfdOwner.PLATFORM) {
            PrivilegeProbe.requestLocationForPlatformWfd(this)
        }
    }

    private fun scheduleAutoWaitIfFrozen() {
        handler.removeCallbacks(autoWaitRunnable)
        if (MiraxApp.instance.session.snapshot().phase == ScreenPhase.FROZEN) {
            handler.postDelayed(autoWaitRunnable, AUTO_WAIT_MS)
        }
    }

    private fun wideWindow(): Boolean = resources.configuration.screenWidthDp >= WIDE_BREAKPOINT_DP

    private fun parentOf(target: Page): Page? {
        return when (target) {
            Page.HISTORY -> Page.DASHBOARD
            Page.LANGUAGE -> Page.GENERAL
            Page.CUSTOM, Page.MODES, Page.SCALE -> Page.PICTURE
            else -> null
        }
    }

    private fun backContentDescription(destination: Page?): String {
        val name = when (destination) {
            Page.DASHBOARD -> getString(R.string.nav_dashboard)
            Page.GENERAL -> getString(R.string.nav_general)
            Page.PICTURE -> getString(R.string.nav_picture)
            Page.COMPATIBILITY -> getString(R.string.nav_compatibility)
            else -> return getString(R.string.nav_back)
        }
        return getString(R.string.nav_back_to, name)
    }

    private fun openPage(target: Page) {
        finishEditingBeforeNavigation()
        page = target
        leaveAppPending = false
        handler.removeCallbacks(clearLeaveAppPending)
        closeDrawer()
        render(MiraxApp.instance.session.snapshot())
    }

    /** Commit inline edits and drop focus so [showPage] can replace the pane. */
    private fun finishEditingBeforeNavigation() {
        val focused = currentFocus
        when {
            focused is EditText && focused.tag == "name" -> {
                suppressBroadcastNameCommit = true
                try {
                    persistBroadcastName(focused)
                    focused.clearFocus()
                } finally {
                    suppressBroadcastNameCommit = false
                }
            }
            focused is EditText && focused.tag == "max-bitrate" -> {
                suppressMaxBitrateCommit = true
                try {
                    persistMaxBitrate(focused)
                    focused.clearFocus()
                } finally {
                    suppressMaxBitrateCommit = false
                }
            }
            else -> focused?.clearFocus()
        }
        binding.root.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /**
     * Same path as the top-left / end-pane Back control: close the drawer, step
     * to the parent page, or confirm leaving when already at a root page.
     */
    private fun onDashboardBack(callback: OnBackPressedCallback) {
        if (drawerOpen) {
            leaveAppPending = false
            handler.removeCallbacks(clearLeaveAppPending)
            closeDrawer()
            return
        }
        if (navigateUp()) {
            return
        }
        if (!leaveAppPending) {
            leaveAppPending = true
            handler.removeCallbacks(clearLeaveAppPending)
            handler.postDelayed(clearLeaveAppPending, LEAVE_APP_CONFIRM_MS)
            Toast.makeText(
                this,
                getString(R.string.press_back_again_to_leave),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        leaveAppPending = false
        handler.removeCallbacks(clearLeaveAppPending)
        callback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        callback.isEnabled = true
    }

    /** Step to [parentOf] the current page. Returns false when already at a root. */
    private fun navigateUp(): Boolean {
        val parent = parentOf(page) ?: return false
        openPage(parent)
        return true
    }

    private fun openDrawer() {
        if (drawerOpen) {
            return
        }
        leaveAppPending = false
        handler.removeCallbacks(clearLeaveAppPending)
        finishEditingBeforeNavigation()
        drawerOpen = true
        render(MiraxApp.instance.session.snapshot())
    }

    private fun closeDrawer() {
        if (!drawerOpen) {
            return
        }
        drawerOpen = false
        syncDrawer()
    }

    private fun render(snapshot: SessionSnapshot) {
        val wide = wideWindow()
        val parent = parentOf(page)
        val startPage = if (wide && parent != null) parent else page
        val endPage = if (wide) parent?.let { page } else null
        binding.paneEnd.visibility = if (wide) View.VISIBLE else View.GONE
        binding.paneDivider.visibility = if (wide) View.VISIBLE else View.GONE
        val drawerParams = binding.drawer.layoutParams
        drawerParams.width = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.86f).toInt())
        binding.drawer.layoutParams = drawerParams
        binding.startBack.contentDescription = backContentDescription(parentOf(startPage))
        if (endPage != null) {
            binding.endBack.contentDescription = backContentDescription(parentOf(page))
        }
        val startParams = binding.paneStart.layoutParams as LinearLayout.LayoutParams
        if (wide) {
            startParams.width = 0
            startParams.weight = 1f
        } else {
            startParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            startParams.weight = 0f
        }
        binding.paneStart.layoutParams = startParams

        val startHasParent = parentOf(startPage) != null
        revealBarControl(binding.menuButton, !startHasParent)
        revealBarControl(binding.startBack, startHasParent)
        revealBarControl(binding.endBar, endPage != null)

        val startMotion = motionBetween(shownStart, startPage)
        val endMotion = when {
            endPage == null && shownEnd != null -> PageMotion.BACK
            endPage != null && shownEnd == null -> PageMotion.FORWARD
            endPage != null -> motionBetween(shownEnd, endPage)
            else -> PageMotion.NONE
        }
        showPage(binding.pageStart, startPage, snapshot, startMotion)
        if (endPage != null) {
            showPage(binding.pageEnd, endPage, snapshot, endMotion)
        } else {
            clearEndPage(endMotion == PageMotion.BACK)
        }
        shownStart = startPage
        shownEnd = endPage
        renderDrawer(snapshot)
        syncDrawer()
        scheduleAutoWaitIfFrozen()
    }

    private fun motionBetween(from: Page?, to: Page): PageMotion {
        if (from == null || from == to) {
            return PageMotion.NONE
        }
        var cursor: Page? = to
        while (cursor != null) {
            if (parentOf(cursor) == from) {
                return PageMotion.FORWARD
            }
            cursor = parentOf(cursor)
        }
        cursor = from
        while (cursor != null) {
            if (parentOf(cursor) == to) {
                return PageMotion.BACK
            }
            cursor = parentOf(cursor)
        }
        return PageMotion.FADE
    }

    private fun showPage(
        host: android.widget.FrameLayout,
        target: Page,
        snapshot: SessionSnapshot,
        motion: PageMotion,
    ) {
        val signature = pageSignature(target, snapshot)
        val existing = host.getChildAt(0)
        val pageChanged = existing?.tag != target
        val samePage = existing?.tag == target && existing.getTag(R.id.page_signature) == signature
        val switchingPage = existing != null && existing.tag != target
        if (!samePage && (!hostHasFocus(host) || switchingPage)) {
            if (switchingPage && hostHasFocus(host)) {
                host.clearFocus()
                binding.root.requestFocus()
            }
            host.removeAllViews()
            val built = buildPage(target, snapshot)
            built.setTag(R.id.page_signature, signature)
            host.addView(built)
            if (pageChanged && motion != PageMotion.NONE) {
                playPageEnter(host, built, motion)
            }
            return
        }
        if (existing != null) {
            updatePage(existing, target, snapshot)
        }
    }

    private fun playPageEnter(host: View, view: View, motion: PageMotion) {
        val duration = motionDuration(android.R.integer.config_mediumAnimTime)
        if (duration == 0L) {
            return
        }
        view.alpha = 0f
        view.post {
            if (view.parent == null) {
                return@post
            }
            view.animate().cancel()
            if (motion == PageMotion.FADE) {
                view.animate().alpha(1f).setDuration(duration).setInterpolator(motionInterpolator).start()
                return@post
            }
            val travel = host.width.coerceAtLeast(1) * 0.22f
            val fromEnd = if (isRtl()) motion != PageMotion.FORWARD else motion == PageMotion.FORWARD
            view.translationX = if (fromEnd) travel else -travel
            view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    private fun clearEndPage(animate: Boolean) {
        val host = binding.pageEnd
        val child = host.getChildAt(0) ?: return
        if (child.getTag(R.id.page_exiting) == true) {
            return
        }
        val duration = if (animate) motionDuration(android.R.integer.config_mediumAnimTime) else 0L
        if (duration == 0L) {
            child.animate().cancel()
            host.removeAllViews()
            return
        }
        child.setTag(R.id.page_exiting, true)
        val travel = host.width.coerceAtLeast(1) * 0.18f * if (isRtl()) -1f else 1f
        child.animate()
            .translationX(travel)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction { host.removeView(child) }
            .start()
    }

    private fun onDrawerSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerSwipePointer = ev.getPointerId(0)
                drawerSwipeX = ev.rawX
                drawerSwipeY = ev.rawY
                drawerSwipeClaimed = false
                drawerSwipeRejected = drawerOpen || inSystemBackZone(ev.rawX)
                drawerSwipeVelocity.clear()
                drawerSwipeVelocity.addMovement(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawerSwipeRejected || ev.findPointerIndex(drawerSwipePointer) < 0) {
                    return drawerSwipeClaimed
                }
                drawerSwipeVelocity.addMovement(ev)
                val dx = ev.rawX - drawerSwipeX
                val dy = ev.rawY - drawerSwipeY
                if (!drawerSwipeClaimed) {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(dx) < slop && abs(dy) < slop) {
                        return false
                    }
                    if (!isDrawerOpeningDelta(dx) || abs(dx) < abs(dy)) {
                        drawerSwipeRejected = true
                        return false
                    }
                    drawerSwipeClaimed = true
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    beginDrawerDrag()
                }
                dragDrawerBy(ev.rawX - drawerSwipeX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val claimed = drawerSwipeClaimed
                drawerSwipeClaimed = false
                drawerSwipeRejected = false
                drawerSwipePointer = MotionEvent.INVALID_POINTER_ID
                if (claimed) {
                    val dx = ev.rawX - drawerSwipeX
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        drawerSwipeVelocity.addMovement(ev)
                        drawerSwipeVelocity.computeCurrentVelocity(1000)
                        finishDrawerDrag(dx, drawerSwipeVelocity.xVelocity)
                    } else {
                        finishDrawerDrag(0f, 0f)
                    }
                }
                return claimed
            }
            else -> return drawerSwipeClaimed
        }
    }

    private fun inSystemBackZone(rawX: Float): Boolean {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemGestures())
            ?: return false
        val location = IntArray(2)
        binding.root.getLocationOnScreen(location)
        val x = rawX - location[0]
        return if (isRtl()) {
            x > binding.root.width - insets.right
        } else {
            x < insets.left
        }
    }

    private fun isDrawerOpeningDelta(dx: Float): Boolean = if (isRtl()) dx < 0f else dx > 0f

    private fun beginDrawerDrag() {
        val drawer = binding.drawer
        val scrim = binding.drawerScrim
        drawer.animate().cancel()
        scrim.animate().cancel()
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.translationX = drawerOffscreen()
        scrim.alpha = 0f
    }

    private fun dragDrawerBy(dx: Float) {
        val width = drawerWidth().coerceAtLeast(1f)
        val opened = if (isRtl()) -dx else dx
        val progress = (opened / width).coerceIn(0f, 1f)
        binding.drawer.translationX = drawerOffscreen() * (1f - progress)
        binding.drawerScrim.alpha = progress
    }

    private fun finishDrawerDrag(dx: Float, velocityX: Float) {
        val width = drawerWidth().coerceAtLeast(1f)
        val opened = if (isRtl()) -dx else dx
        val openingVelocity = if (isRtl()) -velocityX else velocityX
        val fling = ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat()
        val settleOpen = openingVelocity > fling || (openingVelocity > -fling && opened / width > 0.4f)
        if (settleOpen) {
            drawerOpen = true
            drawerPresented = false
        } else {
            drawerOpen = false
            drawerPresented = true
        }
        syncDrawer()
    }

    private fun drawerWidth(): Float {
        val measured = binding.drawer.width
        return (if (measured > 0) measured else binding.drawer.layoutParams.width).toFloat()
    }

    private fun syncDrawer() {
        if (drawerSwipeClaimed || drawerOpen == drawerPresented) {
            return
        }
        drawerPresented = drawerOpen
        animateDrawer(drawerOpen)
    }

    private fun animateDrawer(open: Boolean) {
        val drawer = binding.drawer
        val scrim = binding.drawerScrim
        drawer.animate().cancel()
        scrim.animate().cancel()
        val duration = motionDuration(android.R.integer.config_mediumAnimTime)
        if (duration == 0L) {
            drawer.translationX = 0f
            scrim.alpha = 1f
            drawer.visibility = if (open) View.VISIBLE else View.GONE
            scrim.visibility = if (open) View.VISIBLE else View.GONE
            return
        }
        val offscreen = drawerOffscreen()
        if (open) {
            val wasHidden = drawer.visibility != View.VISIBLE
            drawer.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            if (wasHidden) {
                drawer.translationX = offscreen
                scrim.alpha = 0f
            }
            drawer.animate().translationX(0f).setDuration(duration).setInterpolator(motionInterpolator).start()
            scrim.animate().alpha(1f).setDuration(duration).setInterpolator(motionInterpolator).start()
            return
        }
        drawer.animate()
            .translationX(offscreen)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (!drawerOpen) {
                    drawer.visibility = View.GONE
                    drawer.translationX = 0f
                }
            }
            .start()
        scrim.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (!drawerOpen) {
                    scrim.visibility = View.GONE
                    scrim.alpha = 1f
                }
            }
            .start()
    }

    private fun drawerOffscreen(): Float {
        val width = binding.drawer.width.takeIf { it > 0 } ?: binding.drawer.layoutParams.width
        return if (isRtl()) width.toFloat() else -width.toFloat()
    }

    private fun revealBarControl(view: View, show: Boolean) {
        val showing = view.visibility == View.VISIBLE
        if (showing == show) {
            return
        }
        view.animate().cancel()
        if (!show) {
            view.visibility = View.GONE
            view.alpha = 1f
            return
        }
        val duration = motionDuration(android.R.integer.config_shortAnimTime)
        if (duration == 0L) {
            view.alpha = 1f
            view.visibility = View.VISIBLE
            return
        }
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(duration).setInterpolator(motionInterpolator).start()
    }

    private fun motionDuration(systemDuration: Int): Long {
        val scale = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        )
        return (resources.getInteger(systemDuration) * scale).toLong()
    }

    private fun isRtl(): Boolean {
        return binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    private fun pageSignature(target: Page, snapshot: SessionSnapshot): String {
        return when (target) {
            Page.DASHBOARD -> "${snapshot.phase}|${snapshot.advertisingEnabled}|${snapshot.connectionRuns.size}"
            Page.PICTURE -> "${customSummary(snapshot)}|${modesSummary(snapshot)}|${scaleLabel(snapshot.pictureScale)}|${snapshot.touchEnabled}|${snapshot.maxVideoBitrateBps}"
            Page.CUSTOM -> "${snapshot.autoAddWmSizeOnConnect}|${snapshot.customModes.joinToString { it.format() }}"
            Page.GENERAL -> "${snapshot.displayNameOverride}|${ballOn(snapshot)}|${snapshot.languagePreference}"
            Page.MODES -> snapshot.standardModes.joinToString { "${it.mode.format()}=${it.checked}" }
            Page.SCALE -> snapshot.pictureScale.name
            Page.LANGUAGE -> snapshot.languagePreference.name
            Page.ADVANCED -> "${snapshot.showDebugMessages}|${snapshot.cameraCutoutAffectsLayout}|${snapshot.broadcastAutoStopMinutes}"
            Page.COMPATIBILITY ->
                "${snapshot.showCompatibilityNotice}|${snapshot.wfdOwner}|${snapshot.rootAvailable}"
            Page.HISTORY -> snapshot.connectionRuns.joinToString { "${it.startedAtEpochMs}:${it.succeeded}" } + historyOpen.joinToString()
            Page.ABOUT -> versionName()
        }
    }

    private fun hostHasFocus(host: View): Boolean {
        var focused = currentFocus
        while (focused != null) {
            if (focused === host) {
                return true
            }
            val parent = focused.parent
            focused = parent as? View
        }
        return false
    }

    private fun buildPage(target: Page, snapshot: SessionSnapshot): View {
        val content = when (target) {
            Page.DASHBOARD -> buildDashboard(snapshot)
            Page.HISTORY -> buildHistory(snapshot)
            Page.GENERAL -> buildGeneral(snapshot)
            Page.LANGUAGE -> buildLanguage(snapshot)
            Page.PICTURE -> buildPicture(snapshot)
            Page.CUSTOM -> buildCustom(snapshot)
            Page.MODES -> buildModes(snapshot)
            Page.SCALE -> buildScale(snapshot)
            Page.ADVANCED -> buildAdvanced(snapshot)
            Page.COMPATIBILITY -> buildCompatibility(snapshot)
            Page.ABOUT -> buildAbout()
        }
        return scroll(content).apply { tag = target }
    }

    private fun updatePage(root: View, target: Page, snapshot: SessionSnapshot) {
        updatingControls = true
        when (target) {
            Page.DASHBOARD -> updateDashboard(root, snapshot)
            Page.GENERAL -> updateGeneral(root, snapshot)
            Page.PICTURE -> updatePicture(root, snapshot)
            Page.CUSTOM -> {
                updateSwitch(root, "auto-wm", snapshot.autoAddWmSizeOnConnect)
                updateCustomList(root, snapshot)
            }
            Page.MODES -> updateModes(root, snapshot)
            Page.LANGUAGE -> updateLanguage(root, snapshot)
            Page.SCALE -> updateScale(root, snapshot)
            Page.ADVANCED -> {
                updateSwitch(root, "debug", snapshot.showDebugMessages)
                updateSwitch(root, "cutout", snapshot.cameraCutoutAffectsLayout)
                contentColumn(root).findViewWithTag<TextView>("auto-stop")?.text =
                    getString(R.string.broadcast_auto_stop_value, snapshot.broadcastAutoStopMinutes)
            }
            Page.HISTORY -> replacePage(root, target, snapshot)
            Page.COMPATIBILITY -> Unit
            Page.ABOUT -> Unit
        }
        updatingControls = false
    }

    private fun replacePage(root: View, target: Page, snapshot: SessionSnapshot) {
        val host = root.parent as? android.widget.FrameLayout ?: return
        updatingControls = false
        host.removeAllViews()
        host.addView(buildPage(target, snapshot))
    }

    private fun buildDashboard(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        val frozen = snapshot.phase == ScreenPhase.FROZEN
        if (frozen) {
            column.addView(phaseTitle(getString(R.string.waiting_title)).apply { tag = "phase" })
            column.addView(hint(getString(R.string.waiting_body)))
            column.addView(linkButton(getString(R.string.waiting_open_compatibility)) {
                openPage(Page.COMPATIBILITY)
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            })
            column.addView(linkButton(getString(R.string.waiting_retry)) { onRetry() }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            })
        } else {
            column.addView(phaseTitle(dashboardTitle(snapshot)).apply { tag = "phase" })
            column.addView(hint(dashboardHint(snapshot)).apply { tag = "hint" })
            if (snapshot.showCompatibilityNotice) {
                column.addView(hint(getString(R.string.compatibility_denied_banner)).apply {
                    tag = "compatBanner"
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
                })
                column.addView(linkButton(getString(R.string.waiting_open_compatibility)) {
                    openPage(Page.COMPATIBILITY)
                }.apply {
                    tag = "compatLink"
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
                })
            }
        }
        column.addView(broadcastButton(
            broadcastLabel(snapshot),
            enabled = !frozen,
            advertising = snapshot.advertisingEnabled,
        ) {
            val live = MiraxApp.instance.session.snapshot()
            if (live.phase == ScreenPhase.FROZEN) {
                return@broadcastButton
            }
            render(SessionHost.setAdvertising(this, !live.advertisingEnabled))
        }.apply { tag = "broadcast" })
        column.addView(jump(getString(R.string.history_entry), historyCount(snapshot), topDp = 36) {
            openPage(Page.HISTORY)
        })
        if (!frozen) {
            column.addView(handshakeScroller())
            updateDashboard(column, snapshot)
        }
        return column
    }

    private fun updateDashboard(root: View, snapshot: SessionSnapshot) {
        if (snapshot.phase == ScreenPhase.FROZEN) {
            return
        }
        val column = contentColumn(root)
        column.findViewWithTag<TextView>("phase")?.text = dashboardTitle(snapshot)
        column.findViewWithTag<TextView>("hint")?.text = dashboardHint(snapshot)
        val connecting = snapshot.phase == ScreenPhase.CONNECTING
        val showLog = connecting && snapshot.showDebugMessages
        column.findViewWithTag<TextView>("log")?.text = snapshot.handshakeLog
        column.findViewWithTag<ScrollView>("handshake")?.let { logScroll ->
            logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
            if (connecting) {
                logScroll.post {
                    fitHandshake(logScroll)
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        column.findViewWithTag<MaterialButton>("broadcast")?.let { button ->
            button.text = broadcastLabel(snapshot)
            styleBroadcast(button, snapshot.advertisingEnabled)
        }
    }

    private fun dashboardTitle(snapshot: SessionSnapshot): String {
        return when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.display_card_status_ready)
            ScreenPhase.ARMING -> getString(R.string.display_card_status_arming)
            ScreenPhase.CONNECTING -> getString(R.string.keepalive_status_connecting)
            ScreenPhase.WIFI_PAUSED -> getString(R.string.display_card_status_wifi_paused)
            ScreenPhase.ADVERTISING, ScreenPhase.CONNECTED ->
                getString(R.string.display_card_status_advertising)
            ScreenPhase.FROZEN -> getString(R.string.waiting_title)
        }
    }

    private fun dashboardHint(snapshot: SessionSnapshot): String {
        if (snapshot.phase == ScreenPhase.CONNECTING && snapshot.showDebugMessages) {
            return getString(R.string.connecting_hint)
        }
        return when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.dashboard_hint_ready)
            ScreenPhase.ARMING -> getString(R.string.dashboard_hint_arming)
            ScreenPhase.WIFI_PAUSED -> getString(R.string.dashboard_hint_wifi_paused)
            ScreenPhase.ADVERTISING, ScreenPhase.CONNECTED ->
                getString(R.string.dashboard_hint_active)
            else -> getString(R.string.dashboard_hint_active)
        }
    }

    private fun broadcastLabel(snapshot: SessionSnapshot): String {
        return getString(
            if (snapshot.advertisingEnabled) R.string.stop_broadcast else R.string.start_broadcast,
        )
    }

    private fun buildHistory(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.history_title)))
        column.addView(hint(getString(R.string.history_body)))
        if (snapshot.connectionRuns.isEmpty()) {
            column.addView(hint(getString(R.string.history_empty)).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
            })
            return column
        }
        snapshot.connectionRuns.forEachIndexed { index, run ->
            column.addView(historyRun(index, run, index == 0))
        }
        return column
    }

    private fun historyRun(index: Int, run: ConnectionRun, first: Boolean): LinearLayout {
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(run.startedAtEpochMs))
        val host = run.remoteHost.ifBlank { getString(R.string.history_unknown_host) }
        val result = getString(if (run.succeeded) R.string.history_success else R.string.history_failure)
        val open = index in historyOpen
        val frame = framed()
        frame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(if (first) 28 else 16) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "$host, $whenText, $result"
            setOnClickListener {
                if (index in historyOpen) historyOpen.remove(index) else historyOpen.add(index)
                render(MiraxApp.instance.session.snapshot())
            }
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(TextView(this).apply {
            text = host
            textSize = 17f
            typeface = weight(650)
            setTextColor(getColor(R.color.mirax_on_surface))
        })
        main.addView(TextView(this).apply {
            text = whenText
            textSize = 14f
            setTextColor(getColor(R.color.mirax_hint))
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = result
            textSize = 13f
            typeface = weight(700)
            setTextColor(getColor(if (run.succeeded) R.color.mirax_link else R.color.mirax_bad))
            setPadding(dp(12), 0, 0, 0)
        })
        frame.addView(header)
        if (!open) {
            return frame
        }
        val labelWidth = factLabelColumnWidth(
            (run.metadata + run.configuration).map { it.label },
        )
        frame.addView(frameLabel(getString(R.string.history_metadata), topDp = 16))
        frame.addView(kv(run.metadata.map { it.label to it.value }, labelWidth))
        frame.addView(frameLabel(getString(R.string.history_configuration), topDp = 16))
        frame.addView(kv(run.configuration.map { it.label to it.value }, labelWidth))
        frame.addView(frameLabel(getString(R.string.history_log), topDp = 16))
        frame.addView(logText(run.log.ifBlank { getString(R.string.fact_none) }))
        return frame
    }

    private fun kv(rows: List<Pair<String, String>>, labelWidth: Int): TableLayout {
        return TableLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setColumnShrinkable(1, true)
            setColumnStretchable(1, true)
            rows.forEachIndexed { index, (label, value) ->
                addView(
                    TableRow(context).apply {
                        gravity = Gravity.TOP
                        addView(
                            TextView(context).apply {
                                text = factLabel(label)
                                textSize = 14f
                                setTextColor(getColor(R.color.mirax_muted))
                                setLineSpacing(0f, 1.45f)
                            },
                            TableRow.LayoutParams(
                                labelWidth,
                                TableRow.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                marginEnd = dp(12)
                                bottomMargin = if (index == rows.lastIndex) 0 else dp(8)
                            },
                        )
                        addView(
                            TextView(context).apply {
                                text = factValue(label, value)
                                textSize = 14f
                                setTextColor(getColor(R.color.mirax_on_surface))
                                setLineSpacing(0f, 1.45f)
                            },
                            TableRow.LayoutParams(
                                0,
                                TableRow.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                bottomMargin = if (index == rows.lastIndex) 0 else dp(8)
                            },
                        )
                    },
                    TableLayout.LayoutParams(
                        TableLayout.LayoutParams.MATCH_PARENT,
                        TableLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
    }

    private fun factLabelColumnWidth(labels: List<String>): Int {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics,
            )
        }
        val widest = labels.maxOfOrNull { paint.measureText(factLabel(it)) } ?: 0f
        return widest.toInt().coerceAtLeast(dp(72))
    }

    private fun factLabel(label: String): String {
        return when (label) {
            "broadcast name" -> getString(R.string.fact_broadcast_name)
            "custom resolutions" -> getString(R.string.fact_custom_resolutions)
            "standard modes" -> getString(R.string.fact_standard_modes)
            "touch" -> getString(R.string.fact_touch)
            "selected mode" -> getString(R.string.fact_selected_mode)
            "outcome" -> getString(R.string.fact_outcome)
            else -> label
        }
    }

    private fun factValue(label: String, value: String): String {
        return when {
            value == "none" -> getString(R.string.fact_none)
            label == "touch" && (value == "on" || value == "touch") -> getString(R.string.fact_touch_on)
            label == "touch" && (value == "off" || value == "display only") ->
                getString(R.string.fact_touch_off)
            label == "standard modes" || label == "custom resolutions" ->
                value.replace(", ", "\n")
            else -> value
        }
    }

    private fun buildGeneral(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.nav_general)))
        column.addView(section(getString(R.string.section_broadcast_name)))
        val name = EditText(this).apply {
            tag = "name"
            setTextColor(getColor(R.color.mirax_on_surface))
            setHintTextColor(getColor(R.color.mirax_muted))
            textSize = 20f
            background = fieldLine(false)
            setPadding(0, dp(16), 0, dp(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = if (snapshot.displayNameFollowsDevice) {
                snapshot.displayNameFieldHint
            } else {
                getString(R.string.section_broadcast_name)
            }
            setText(if (snapshot.displayNameFollowsDevice) "" else snapshot.displayNameOverride.orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val cleaned = BroadcastNameRules.sanitize(s?.toString().orEmpty())
                    if (cleaned != s?.toString()) {
                        setText(cleaned)
                        setSelection(cleaned.length)
                    }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                background = fieldLine(hasFocus)
                if (!hasFocus && !suppressBroadcastNameCommit) {
                    commitBroadcastName(this)
                }
            }
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    view.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
        column.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(section(getString(R.string.section_leave_projection)))
        column.addView(switchRow(getString(R.string.show_floating_ball), ballOn(snapshot)) { enabled ->
            onFloatingBall(enabled)
        }.apply { tag = "ball" })
        column.addView(jump(
            getString(R.string.settings_language_title),
            languageLabel(snapshot.languagePreference),
            topDp = 36,
        ) {
            openPage(Page.LANGUAGE)
        })
        return column
    }

    private fun updateGeneral(root: View, snapshot: SessionSnapshot) {
        val column = contentColumn(root)
        val name = column.findViewWithTag<EditText>("name")
        if (name != null && !name.hasFocus()) {
            name.hint = if (snapshot.displayNameFollowsDevice) {
                snapshot.displayNameFieldHint
            } else {
                getString(R.string.section_broadcast_name)
            }
            val text = if (snapshot.displayNameFollowsDevice) "" else snapshot.displayNameOverride.orEmpty()
            if (name.text?.toString() != text) {
                name.setText(text)
            }
        }
        updateSwitch(column, "ball", ballOn(snapshot))
    }

    private fun ballOn(snapshot: SessionSnapshot): Boolean {
        return snapshot.floatingBallEnabled && !snapshot.showOverlayPermissionReminder
    }

    private fun onFloatingBall(enabled: Boolean) {
        if (!enabled) {
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetFloatingBallEnabled(false))
            SessionPreferences.saveFloatingBallEnabled(this, false)
            render(session.snapshot())
            return
        }
        val session = MiraxApp.instance.session
        if (!session.snapshot().showOverlayPermissionReminder) {
            session.handle(SessionAction.SetFloatingBallEnabled(true))
            SessionPreferences.saveFloatingBallEnabled(this, true)
            render(session.snapshot())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overlay_request_title)
            .setMessage(R.string.overlay_request_body)
            .setNegativeButton(R.string.overlay_deny) { _, _ ->
                enableBallWhenOverlayGranted = false
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.overlay_denied_title)
                    .setMessage(R.string.overlay_denied_body)
                    .setPositiveButton(R.string.overlay_dismiss, null)
                    .show()
                render(MiraxApp.instance.session.snapshot())
            }
            .setPositiveButton(R.string.overlay_grant) { _, _ ->
                enableBallWhenOverlayGranted = true
                render(SessionHost.requestOverlayPermission(this))
            }
            .setOnCancelListener { render(MiraxApp.instance.session.snapshot()) }
            .show()
    }

    /**
     * Persist the broadcast name and push it to the WFD owner when advertising.
     *
     * Must go through [SessionHost.commit] so [WfdOwnerBridge.sync] re-applies
     * [me.trinitrix.mirax.session.SessionSnapshot.wfdAdvertise] (including a
     * rename while already broadcasting). Updating the session alone leaves
     * the beacon on the old Wi-Fi Direct name until a later commit.
     */
    private fun persistBroadcastName(field: EditText): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetDisplayNameOverride(field.text?.toString().orEmpty()))
        SessionPreferences.saveDisplayNameOverride(
            this,
            session.snapshot().displayNameOverride,
        )
        return SessionHost.commit(this)
    }

    private fun commitBroadcastName(field: EditText) {
        render(persistBroadcastName(field))
    }

    private fun buildLanguage(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.settings_language_title)))
        val group = choiceStack("language")
        val options = listOf(
            LanguagePreference.FOLLOW_SYSTEM to getString(R.string.language_follow_system),
            LanguagePreference.TRADITIONAL_CHINESE to getString(R.string.language_traditional_chinese),
            LanguagePreference.ENGLISH to getString(R.string.language_english),
        )
        for ((preference, label) in options) {
            group.addView(choiceChip(label, preference.name, preference == snapshot.languagePreference) {
                if (updatingControls) {
                    return@choiceChip
                }
                if (preference == MiraxApp.instance.session.snapshot().languagePreference) {
                    return@choiceChip
                }
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetLanguagePreference(preference))
                SessionPreferences.saveLanguagePreference(this, preference)
                applyResolvedLanguage(session.snapshot().appLanguage, recreateUi = true)
            })
        }
        column.addView(group)
        return column
    }

    private fun updateLanguage(root: View, snapshot: SessionSnapshot) {
        restyleChoices(root, "language", snapshot.languagePreference.name)
    }

    private fun buildPicture(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.nav_picture)))
        column.addView(jump(getString(R.string.custom_resolution), customSummary(snapshot), topDp = 8) {
            openPage(Page.CUSTOM)
        })
        column.addView(jump(getString(R.string.standard_modes_label), modesSummary(snapshot)) {
            openPage(Page.MODES)
        })
        column.addView(jump(getString(R.string.settings_scale_title), scaleLabel(snapshot.pictureScale)) {
            openPage(Page.SCALE)
        })
        column.addView(switchRow(getString(R.string.section_touch), snapshot.touchEnabled) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetTouchEnabled(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "touch" })
        column.addView(section(getString(R.string.max_video_bitrate)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        column.addView(maxBitrateRow(snapshot.maxVideoBitrateBps))
        column.addView(hint(getString(R.string.max_video_bitrate_hint)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        return column
    }

    private fun updatePicture(root: View, snapshot: SessionSnapshot) {
        updateSwitch(root, "touch", snapshot.touchEnabled)
        val field = contentColumn(root).findViewWithTag<EditText>("max-bitrate") ?: return
        if (field.hasFocus()) {
            return
        }
        val text = StandardVideoModes.maxBitrateKbps(snapshot.maxVideoBitrateBps).toString()
        if (field.text?.toString() != text) {
            field.setText(text)
        }
    }

    private fun maxBitrateRow(bps: Long): LinearLayout {
        val field = EditText(this).apply {
            tag = "max-bitrate"
            setText(StandardVideoModes.maxBitrateKbps(bps).toString())
            setTextColor(getColor(R.color.mirax_on_surface))
            setHintTextColor(getColor(R.color.mirax_muted))
            textSize = 20f
            background = fieldLine(false)
            setPadding(0, dp(16), 0, dp(16))
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = getString(R.string.max_video_bitrate)
            setOnFocusChangeListener { _, hasFocus ->
                background = fieldLine(hasFocus)
                if (!hasFocus && !suppressMaxBitrateCommit) {
                    commitMaxBitrate(this)
                }
            }
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    view.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                field,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(context).apply {
                text = getString(R.string.max_video_bitrate_unit)
                textSize = 16f
                setTextColor(getColor(R.color.mirax_muted))
                setPadding(dp(12), 0, 0, 0)
            })
        }
    }

    private fun persistMaxBitrate(field: EditText): SessionSnapshot {
        val typed = field.text?.toString()?.trim().orEmpty()
        val kbps = typed.toIntOrNull()
        val session = MiraxApp.instance.session
        val bps = if (kbps == null) {
            session.snapshot().maxVideoBitrateBps
        } else {
            StandardVideoModes.maxBitrateBpsFromKbps(kbps)
        }
        session.handle(SessionAction.SetMaxVideoBitrateBps(bps))
        val applied = session.snapshot().maxVideoBitrateBps
        val shown = StandardVideoModes.maxBitrateKbps(applied).toString()
        if (field.text?.toString() != shown) {
            field.setText(shown)
            field.setSelection(shown.length)
        }
        return SessionHost.commit(this)
    }

    private fun commitMaxBitrate(field: EditText) {
        render(persistMaxBitrate(field))
    }

    private fun buildCustom(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.custom_resolution)))
        column.addView(hint(getString(R.string.custom_resolution_body)))
        column.addView(switchRow(getString(R.string.auto_wm_size), snapshot.autoAddWmSizeOnConnect) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetAutoAddWmSizeOnConnect(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "auto-wm" })
        column.addView(hint(getString(R.string.auto_wm_size_hint)))
        column.addView(section(getString(R.string.custom_new)))
        val width = numberField(getString(R.string.custom_width)).apply { tag = "w" }
        val height = numberField(getString(R.string.custom_height)).apply { tag = "h" }
        val refresh = numberField(getString(R.string.custom_refresh)).apply { tag = "hz" }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labeledField(getString(R.string.custom_width), width), weightedField(endGap = true))
            addView(labeledField(getString(R.string.custom_height), height), weightedField(endGap = true))
            addView(labeledField(getString(R.string.custom_refresh), refresh), weightedField(endGap = false))
        }
        column.addView(fields)
        column.addView(linkButton(getString(R.string.custom_add)) {
            addCustomFrom(width, height, refresh)
        })
        column.addView(section(getString(R.string.custom_order)).apply { tag = "custom-order" })
        column.addView(hint(getString(R.string.custom_drag_hint)).apply { tag = "custom-drag-hint" })
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "custom-list"
        })
        column.addView(hint(getString(R.string.custom_list_empty)).apply {
            tag = "custom-empty-label"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        column.addView(disclaimer(
            getString(R.string.custom_disclaimer_title),
            getString(R.string.custom_disclaimer_body),
        ))
        updateCustomList(column, snapshot)
        return column
    }

    private fun addCustomFrom(width: EditText, height: EditText, refresh: EditText) {
        val w = width.text?.toString()?.trim()?.toIntOrNull()
        val h = height.text?.toString()?.trim()?.toIntOrNull()
        val hz = refresh.text?.toString()?.trim()?.toIntOrNull()
        if (w == null || h == null || hz == null || w <= 0 || h <= 0 || hz <= 0) {
            return
        }
        val session = MiraxApp.instance.session
        session.handle(SessionAction.AddCustomMode(w, h, hz))
        persist(session)
        width.setText("")
        height.setText("")
        refresh.setText("")
        render(session.snapshot())
    }

    private fun updateCustomList(root: View, snapshot: SessionSnapshot) {
        val column = contentColumn(root)
        val list = column.findViewWithTag<LinearLayout>("custom-list") ?: return
        val hasRows = snapshot.customModes.isNotEmpty()
        column.findViewWithTag<View>("custom-order")?.visibility = if (hasRows) View.VISIBLE else View.GONE
        column.findViewWithTag<View>("custom-drag-hint")?.visibility = if (hasRows) View.VISIBLE else View.GONE
        column.findViewWithTag<View>("custom-empty-label")?.visibility = if (hasRows) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (!hasRows) {
            customDragFrom = null
            return
        }
        snapshot.customModes.forEachIndexed { index, mode ->
            list.addView(customModeRow(list, index, mode.format()) {
                val session = MiraxApp.instance.session
                session.handle(SessionAction.RemoveCustomMode(mode))
                persist(session)
                render(session.snapshot())
            })
        }
    }

    private fun customModeRow(
        list: LinearLayout,
        index: Int,
        label: String,
        onRemove: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(R.color.mirax_chip, 14)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val handle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_nav_menu)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = rounded(R.color.mirax_chip, 10)
            contentDescription = getString(R.string.custom_drag_handle)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        attachCustomDrag(handle, list, index)
        row.addView(handle)
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(getColor(R.color.mirax_on_surface))
            setPadding(dp(10), 0, dp(10), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = getString(R.string.custom_remove)
            textSize = 14f
            typeface = weight(700)
            setTextColor(getColor(R.color.mirax_link))
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { onRemove() }
        })
        ViewCompat.addAccessibilityAction(row, getString(R.string.custom_up)) { _, _ ->
            moveCustom(index, index - 1)
            true
        }
        ViewCompat.addAccessibilityAction(row, getString(R.string.custom_down)) { _, _ ->
            moveCustom(index, index + 1)
            true
        }
        return row
    }

    private fun attachCustomDrag(handle: View, list: LinearLayout, index: Int) {
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    customDragFrom = index
                    var parent = view.parent
                    while (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        parent = parent.parent
                    }
                    paintCustomDrag(list, index)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val over = rowIndexAt(list, event.rawY)
                    if (over != null) {
                        paintCustomDrag(list, over)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val from = customDragFrom
                    val over = if (event.actionMasked == MotionEvent.ACTION_UP) {
                        rowIndexAt(list, event.rawY)
                    } else {
                        from
                    }
                    customDragFrom = null
                    if (event.actionMasked == MotionEvent.ACTION_UP && from != null && over != null && from != over) {
                        moveCustom(from, over)
                    } else {
                        paintCustomDrag(list, null)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun paintCustomDrag(list: LinearLayout, over: Int?) {
        val from = customDragFrom
        for (childIndex in 0 until list.childCount) {
            val row = list.getChildAt(childIndex)
            val dragging = from == childIndex
            val targeted = over != null && over == childIndex && !dragging
            row.alpha = if (dragging) 0.78f else 1f
            row.background = rounded(
                if (dragging) R.color.mirax_chip_on else R.color.mirax_chip,
                14,
                if (targeted) R.color.mirax_accent else 0,
                2,
            )
        }
    }

    private fun rowIndexAt(list: LinearLayout, rawY: Float): Int? {
        for (childIndex in 0 until list.childCount) {
            val child = list.getChildAt(childIndex)
            val location = IntArray(2)
            child.getLocationOnScreen(location)
            if (rawY >= location[1] && rawY < location[1] + child.height) {
                return childIndex
            }
        }
        return null
    }

    private fun moveCustom(from: Int, to: Int) {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.MoveCustomMode(from, to))
        persist(session)
        render(session.snapshot())
    }

    private fun buildModes(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.standard_modes_label)))
        val frame = framed().apply { tag = "modes" }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "mode-tabs"
        }
        frame.addView(tabs)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "mode-body"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val scroller = ScrollView(this).apply {
            tag = "mode-scroll"
            isFillViewport = true
            isNestedScrollingEnabled = true
            addView(body)
        }
        frame.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        column.addView(frame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitModesFrame(frame) }
        updateModes(column, snapshot)
        return column
    }

    private fun updateModes(root: View, snapshot: SessionSnapshot) {
        val column = contentColumn(root)
        val frame = column.findViewWithTag<LinearLayout>("modes") ?: return
        val tabs = frame.findViewWithTag<LinearLayout>("mode-tabs") ?: return
        val body = frame.findViewWithTag<LinearLayout>("mode-body") ?: return
        val groups = StandardModeGroups.groups(snapshot.standardModes)
        if (groups.none { it.label == modesRatio }) {
            modesRatio = groups.firstOrNull()?.label ?: "16:9"
        }
        val group = groups.firstOrNull { it.label == modesRatio }
        val sizes = group?.let { sizesOf(it.rows) }.orEmpty()
        val openSize = modesSize?.takeIf { size -> sizes.any { it.width == size.first && it.height == size.second } }
        modesSize = openSize
        tabs.removeAllViews()
        groups.forEachIndexed { index, item ->
            tabs.addView(ratioTab(item.label, item.label == modesRatio, index == groups.lastIndex) {
                if (modesRatio != item.label) {
                    modesRatio = item.label
                    modesSize = null
                    updateModes(column, snapshot)
                }
            })
        }
        body.removeAllViews()
        if (group == null) {
            return
        }
        if (openSize == null) {
            sizes.forEachIndexed { index, size ->
                body.addView(modeSizeRow(size, index == 0) {
                    modesSize = size.width to size.height
                    updateModes(column, snapshot)
                })
            }
            return
        }
        val size = sizes.first { it.width == openSize.first && it.height == openSize.second }
        body.addView(modeDepthHeader("${size.width}×${size.height}") {
            modesSize = null
            updateModes(column, snapshot)
        })
        if (size.rows.size > 1) {
            val allChecked = size.rows.all { it.checked }
            body.addView(
                choiceChip(
                    getString(R.string.modes_all_refresh_rates),
                    "all-refresh",
                    allChecked,
                ) {
                    val session = MiraxApp.instance.session
                    session.handle(
                        SessionAction.SetStandardModesChecked(
                            modes = size.rows.map { it.mode },
                            checked = !allChecked,
                        ),
                    )
                    persist(session)
                    render(session.snapshot())
                },
            )
        }
        size.rows.forEachIndexed { index, row ->
            val chip = choiceChip("${row.mode.refreshHz} Hz", row.mode.format(), row.checked) {
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetStandardModeChecked(row.mode, !row.checked))
                persist(session)
                render(session.snapshot())
            }
            if (index == size.rows.lastIndex) {
                (chip.layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }
            body.addView(chip)
        }
    }

    private data class ModeSize(
        val width: Int,
        val height: Int,
        val rows: List<me.trinitrix.mirax.session.StandardModeRow>,
    )

    private fun sizesOf(rows: List<me.trinitrix.mirax.session.StandardModeRow>): List<ModeSize> {
        val ordered = ArrayList<ModeSize>()
        for (row in rows) {
            val last = ordered.lastOrNull()
            if (last != null && last.width == row.mode.width && last.height == row.mode.height) {
                ordered[ordered.lastIndex] = last.copy(rows = last.rows + row)
            } else {
                ordered.add(ModeSize(row.mode.width, row.mode.height, listOf(row)))
            }
        }
        return ordered
    }

    private fun ratioTab(label: String, selected: Boolean, last: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = label
            setOnClickListener { onClick() }
            clipRipple(this)
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = weight(if (selected) 700 else 500)
                setTextColor(getColor(if (selected) R.color.mirax_on_surface else R.color.mirax_hint))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            addView(View(context).apply {
                setBackgroundColor(getColor(if (selected) R.color.mirax_accent else android.R.color.transparent))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (last) 0 else dp(8)
                bottomMargin = dp(16)
            }
        }
    }

    private fun modeSizeRow(size: ModeSize, first: Boolean, onClick: () -> Unit): LinearLayout {
        val checked = size.rows.count { it.checked }
        val rates = size.rows.joinToString(" · ") { "${it.mode.refreshHz}" } + " Hz"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(if (checked > 0) R.color.mirax_chip_on else R.color.mirax_chip, 14)
            isClickable = true
            isFocusable = true
            isSelected = checked > 0
            contentDescription = "${size.width}×${size.height}, $rates"
            setOnClickListener { onClick() }
            clipRipple(this)
            addView(TextView(context).apply {
                text = "${size.width}×${size.height}"
                textSize = 16f
                typeface = weight(650)
                setTextColor(getColor(R.color.mirax_on_surface))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (first) 0 else dp(8) }
        }
    }

    private fun modeDepthHeader(label: String, onBack: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.nav_back)
            setOnClickListener { onBack() }
            clipRipple(this)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_nav_back)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            addView(TextView(context).apply {
                text = label
                textSize = 18f
                typeface = weight(650)
                setTextColor(getColor(R.color.mirax_on_surface))
                setPadding(dp(8), 0, 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun fitModesFrame(frame: View) {
        val outer = enclosingScroll(frame) ?: return
        val outerAt = IntArray(2)
        val frameAt = IntArray(2)
        outer.getLocationOnScreen(outerAt)
        frame.getLocationOnScreen(frameAt)
        if (outer.height <= 0) {
            return
        }
        val columnPad = (frame.parent as? View)?.paddingBottom ?: 0
        val available = outer.height - (frameAt[1] - outerAt[1]) - columnPad
        val target = available.coerceAtLeast(dp(240))
        val params = frame.layoutParams ?: return
        if (params.height != target) {
            params.height = target
            frame.layoutParams = params
        }
    }

    private fun buildScale(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.settings_scale_title)))
        val group = choiceStack("scale")
        val options = listOf(
            PictureScale.PROPORTIONAL to getString(R.string.scale_proportional),
            PictureScale.CENTER_CROP to getString(R.string.scale_center_crop),
            PictureScale.MATCH_EDGES to getString(R.string.scale_match_edges),
            PictureScale.ACTUAL to getString(R.string.scale_actual),
        )
        for ((scale, label) in options) {
            group.addView(choiceChip(label, scale.name, scale == snapshot.pictureScale) {
                if (updatingControls) {
                    return@choiceChip
                }
                if (scale == MiraxApp.instance.session.snapshot().pictureScale) {
                    return@choiceChip
                }
                render(SessionHost.setPictureScale(this, scale))
            })
        }
        column.addView(group)
        return column
    }

    private fun updateScale(root: View, snapshot: SessionSnapshot) {
        restyleChoices(root, "scale", snapshot.pictureScale.name)
    }

    private fun buildCompatibility(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.compatibility_title)))
        if (snapshot.showCompatibilityNotice) {
            column.addView(hint(getString(R.string.compatibility_denied_banner)).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            })
        }
        column.addView(hint(getString(R.string.compatibility_intro)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        })
        column.addView(section(getString(R.string.compatibility_supported_title)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        column.addView(hint(getString(R.string.compatibility_supported_body)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        column.addView(section(getString(R.string.compatibility_unsupported_title)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        column.addView(hint(getString(R.string.compatibility_unsupported_body)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        return column
    }

    private fun buildAdvanced(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.nav_advanced)))
        column.addView(switchRow(getString(R.string.debug_messages), snapshot.showDebugMessages) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetShowDebugMessages(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "debug" })
        column.addView(switchRow(
            getString(R.string.camera_cutout_affects_layout),
            snapshot.cameraCutoutAffectsLayout,
        ) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetCameraCutoutAffectsLayout(enabled))
            persist(session)
            render(session.snapshot())
            refreshDashboardInsets()
        }.apply {
            tag = "cutout"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        column.addView(hint(getString(R.string.camera_cutout_affects_layout_hint)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        column.addView(section(getString(R.string.broadcast_auto_stop)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        column.addView(autoStopRow(snapshot.broadcastAutoStopMinutes))
        column.addView(hint(getString(R.string.broadcast_auto_stop_hint)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })
        return column
    }

    private fun autoStopRow(minutes: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepButton("−") { changeAutoStopMinutes(-1) })
            addView(TextView(context).apply {
                tag = "auto-stop"
                text = getString(R.string.broadcast_auto_stop_value, minutes)
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.mirax_on_surface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(stepButton("+") { changeAutoStopMinutes(1) })
        }
    }

    private fun stepButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.mirax_on_surface))
            background = rounded(R.color.mirax_chip, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(48))
        }
    }

    private fun changeAutoStopMinutes(delta: Int) {
        val session = MiraxApp.instance.session
        val next = session.snapshot().broadcastAutoStopMinutes + delta
        session.handle(SessionAction.SetBroadcastAutoStopMinutes(next))
        render(SessionHost.commit(this))
    }

    private fun buildAbout(): LinearLayout {
        val column = column()
        column.addView(pageTitle(getString(R.string.nav_about)))
        column.addView(kicker(getString(R.string.about_app_label)))
        column.addView(valueText(getString(R.string.app_name)))
        column.addView(kicker(getString(R.string.about_version)))
        column.addView(valueText(versionName()))
        column.addView(hint(getString(R.string.about_body)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        return column
    }

    private fun renderDrawer(snapshot: SessionSnapshot) {
        val drawer = binding.drawerBody
        drawer.removeAllViews()
        drawer.addView(menuCard(Page.DASHBOARD, getString(R.string.nav_dashboard), dashboardTitle(snapshot), topDp = 0))
        drawer.addView(drawerLabel(getString(R.string.nav_settings)))
        drawer.addView(menuCard(Page.GENERAL, getString(R.string.nav_general), snapshot.effectiveBroadcastName))
        drawer.addView(menuCard(Page.PICTURE, getString(R.string.nav_picture), pictureDetail(snapshot)))
        drawer.addView(menuCard(Page.ADVANCED, getString(R.string.nav_advanced), ""))
        drawer.addView(drawerLabel(getString(R.string.nav_about_group)))
        drawer.addView(menuCard(Page.COMPATIBILITY, getString(R.string.nav_compatibility), ""))
        drawer.addView(menuCard(Page.ABOUT, getString(R.string.nav_about), versionName()))
    }

    private fun menuSelected(target: Page): Boolean {
        var cursor: Page? = page
        while (cursor != null) {
            if (cursor == target) {
                return true
            }
            cursor = parentOf(cursor)
        }
        return false
    }

    private fun menuCard(target: Page, label: String, detail: String, topDp: Int = 12): LinearLayout {
        val selected = menuSelected(target)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = rounded(if (selected) R.color.mirax_chip_on else R.color.mirax_chip, 16)
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = if (detail.isEmpty()) label else "$label, $detail"
            setOnClickListener { openPage(target) }
            clipRipple(this)
            addView(TextView(context).apply {
                text = label
                textSize = 18f
                typeface = weight(650)
                setTextColor(getColor(R.color.mirax_on_surface))
            })
            if (detail.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(getColor(R.color.mirax_hint))
                    setLineSpacing(0f, 1.4f)
                    setPadding(0, dp(6), 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topDp) }
        }
    }

    private fun pictureDetail(snapshot: SessionSnapshot): String {
        val labels = snapshot.customModes.map { it.format() } +
            snapshot.standardModes.filter { it.checked }.map { it.mode.format() }
        val first = labels.firstOrNull() ?: getString(R.string.custom_empty)
        val others = (labels.size - 1).coerceAtLeast(0)
        val scale = scaleLabel(snapshot.pictureScale)
        if (others == 0) {
            return "$scale · $first"
        }
        return getString(R.string.picture_menu_detail, scale, first, others)
    }

    private fun customSummary(snapshot: SessionSnapshot): String {
        val modes = snapshot.customModes
        if (modes.isEmpty()) {
            return getString(R.string.custom_empty)
        }
        if (modes.size == 1) {
            return modes[0].format()
        }
        return getString(R.string.custom_summary_many, modes.size, modes[0].format())
    }

    private fun modesSummary(snapshot: SessionSnapshot): String {
        val checked = snapshot.standardModes.count { it.checked }
        return getString(R.string.modes_checked_count, checked)
    }

    private fun historyCount(snapshot: SessionSnapshot): String {
        return getString(R.string.history_count, snapshot.connectionRuns.size)
    }

    private fun languageLabel(preference: LanguagePreference): String {
        return when (preference) {
            LanguagePreference.FOLLOW_SYSTEM -> getString(R.string.language_follow_system)
            LanguagePreference.TRADITIONAL_CHINESE -> getString(R.string.language_traditional_chinese)
            LanguagePreference.ENGLISH -> getString(R.string.language_english)
        }
    }

    private fun scaleLabel(scale: PictureScale): String {
        return when (scale) {
            PictureScale.PROPORTIONAL -> getString(R.string.scale_proportional)
            PictureScale.CENTER_CROP -> getString(R.string.scale_center_crop)
            PictureScale.MATCH_EDGES -> getString(R.string.scale_match_edges)
            PictureScale.ACTUAL -> getString(R.string.scale_actual)
        }
    }

    private fun persist(session: me.trinitrix.mirax.session.MiraxSession) {
        SessionPreferences.saveResolutionSettings(this, session.exportSettings())
    }


    private fun contentColumn(root: View): LinearLayout {
        var current: View = if (root is ScrollView) root.getChildAt(0) else root
        if (current is FrameLayout && current.childCount > 0) {
            current = current.getChildAt(0)
        }
        return current as LinearLayout
    }

    private fun enclosingScroll(view: View): ScrollView? {
        var cursor: View? = view
        while (cursor != null) {
            if (cursor is ScrollView) {
                return cursor
            }
            cursor = cursor.parent as? View
        }
        return null
    }

    private fun updateSwitch(root: View, tag: String, checked: Boolean) {
        val column = if (root is LinearLayout && root.findViewWithTag<View>(tag) != null) {
            root
        } else {
            contentColumn(root)
        }
        val switch = column.findViewWithTag<MaterialSwitch>(tag) ?: return
        if (switch.isChecked != checked) {
            switch.isChecked = checked
        }
    }

    private fun scroll(content: LinearLayout): ScrollView {
        val host = FrameLayout(this)
        host.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val maxWidth = dp(480)
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val available = host.width
            if (available <= 0) {
                return@addOnLayoutChangeListener
            }
            val target = minOf(available, maxWidth)
            val params = content.layoutParams
            if (params.width != target) {
                params.width = target
                content.layoutParams = params
            }
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                host,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun column(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, dp(28), dp(56))
        }
    }

    private fun phaseTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 40f
            typeface = weight(560)
            letterSpacing = -0.03f
            setTextColor(getColor(R.color.mirax_on_surface))
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(12)
            }
        }
    }

    private fun pageTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 26f
            typeface = weight(650)
            letterSpacing = -0.02f
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(28)
            }
        }
    }

    private fun hint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(getColor(R.color.mirax_hint))
            setLineSpacing(0f, 1.45f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(40)
                bottomMargin = dp(14)
            }
        }
    }

    private fun drawerLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = weight(700)
            setTextColor(getColor(R.color.mirax_muted))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) }
        }
    }

    private fun frameLabel(text: String, topDp: Int = 0): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(topDp)
                bottomMargin = dp(14)
            }
        }
    }

    private fun kicker(text: String, topDp: Int = 36): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topDp) }
        }
    }

    private fun valueText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    private fun logText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.mirax_log))
            setLineSpacing(0f, 1.5f)
            setTextIsSelectable(true)
        }
    }

    private fun jump(label: String, detail: String, topDp: Int = 12, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.mirax_chip, 16)
            isClickable = true
            isFocusable = true
            contentDescription = if (detail.isEmpty()) label else "$label, $detail"
            setOnClickListener { onClick() }
            clipRipple(this)
            addView(TextView(context).apply {
                text = label
                textSize = 17f
                typeface = weight(650)
                setTextColor(getColor(R.color.mirax_on_surface))
                maxLines = 2
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (detail.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(getColor(R.color.mirax_hint))
                    gravity = Gravity.END
                    maxLines = 2
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(16) })
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topDp) }
        }
    }

    private fun broadcastButton(
        label: String,
        enabled: Boolean,
        advertising: Boolean,
        onClick: () -> Unit,
    ): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            textSize = 16f
            typeface = weight(700)
            isAllCaps = false
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(56)
            minimumHeight = dp(56)
            stateListAnimator = null
            elevation = 0f
            isEnabled = enabled
            styleBroadcast(this, advertising)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            ).apply { topMargin = dp(32) }
        }
    }

    private fun styleBroadcast(button: MaterialButton, advertising: Boolean) {
        val fill = getColor(if (advertising) R.color.mirax_stop else R.color.mirax_accent)
        val ink = getColor(if (advertising) R.color.mirax_on_surface else R.color.mirax_on_accent)
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.setTextColor(ink)
    }

    private fun linkButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            typeface = weight(700)
            setTextColor(getColor(R.color.mirax_link))
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        return MaterialSwitch(this).apply {
            text = label
            isChecked = checked
            textSize = 17f
            minHeight = dp(72)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingControls) {
                    return@setOnCheckedChangeListener
                }
                onChange(isChecked)
            }
        }
    }

    private fun choiceStack(tag: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.tag = tag
        }
    }

    private fun choiceChip(
        label: String,
        value: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView {
        return TextView(this).apply {
            text = label
            tag = value
            textSize = 16f
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            styleChip(this, selected)
            setOnClickListener { onClick() }
            clipRipple(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun restyleChoices(root: View, groupTag: String, selected: String) {
        val group = contentColumn(root).findViewWithTag<LinearLayout>(groupTag) ?: return
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? TextView ?: continue
            styleChip(chip, chip.tag == selected)
        }
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.setTextColor(getColor(if (selected) R.color.mirax_chip_text else R.color.mirax_on_surface))
        view.background = rounded(if (selected) R.color.mirax_chip_on else R.color.mirax_chip, 14)
        view.isSelected = selected
    }

    private fun numberField(label: String): EditText {
        return EditText(this).apply {
            contentDescription = label
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            setTextColor(getColor(R.color.mirax_on_surface))
            setHintTextColor(getColor(R.color.mirax_muted))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(R.color.mirax_chip, 12, R.color.mirax_stroke, 1)
            setOnFocusChangeListener { field, hasFocus ->
                field.background = rounded(
                    R.color.mirax_chip,
                    12,
                    if (hasFocus) R.color.mirax_accent else R.color.mirax_stroke,
                    if (hasFocus) 2 else 1,
                )
            }
        }
    }

    private fun labeledField(label: String, field: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 13f
                typeface = weight(700)
                setTextColor(getColor(R.color.mirax_muted))
            })
            addView(field, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
        }
    }

    private fun weightedField(endGap: Boolean): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (endGap) {
                marginEnd = dp(10)
            }
        }
    }

    private fun handshakeScroller(): ScrollView {
        val log = logText("")
        log.tag = "log"
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.mirax_frame, 16, R.color.mirax_stroke, 1)
            setPadding(dp(16), dp(18), dp(16), dp(16))
            addView(frameLabel(getString(R.string.handshake_label)))
            addView(log)
        }
        return ScrollView(this).apply {
            tag = "handshake"
            isFillViewport = true
            isNestedScrollingEnabled = true
            visibility = View.GONE
            addView(
                frame,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(160),
            ).apply { topMargin = dp(28) }
        }
    }

    private fun fitHandshake(logScroll: ScrollView) {
        val outer = enclosingScroll(logScroll) ?: return
        val outerAt = IntArray(2)
        val logAt = IntArray(2)
        outer.getLocationOnScreen(outerAt)
        logScroll.getLocationOnScreen(logAt)
        val columnPad = (logScroll.parent as? View)?.paddingBottom ?: 0
        val available = outer.height - (logAt[1] - outerAt[1]) - columnPad
        val target = available.coerceAtLeast(dp(96))
        val params = logScroll.layoutParams ?: return
        if (params.height != target) {
            params.height = target
            logScroll.layoutParams = params
        }
    }

    private fun framed(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.mirax_frame, 16, R.color.mirax_stroke, 1)
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
    }

    private fun disclaimer(title: String, bodyText: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.mirax_frame, 14, R.color.mirax_stroke, 1)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) }
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                typeface = weight(700)
                letterSpacing = 0.04f
                setTextColor(getColor(R.color.mirax_muted))
            })
            addView(TextView(context).apply {
                text = bodyText
                textSize = 14f
                setTextColor(getColor(R.color.mirax_hint))
                setLineSpacing(0f, 1.55f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            })
        }
    }

    private fun fieldLine(focused: Boolean): LayerDrawable {
        val line = GradientDrawable().apply {
            setColor(getColor(if (focused) R.color.mirax_accent else R.color.mirax_field_line))
        }
        return LayerDrawable(arrayOf(line)).apply {
            setLayerHeight(0, dp(if (focused) 2 else 1).coerceAtLeast(1))
            setLayerGravity(0, Gravity.BOTTOM)
        }
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(getColor(fill))
            if (stroke != 0) {
                setStroke(dp(strokeDp).coerceAtLeast(1), getColor(stroke))
            }
        }
    }

    private fun clipRipple(view: View) {
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        val typed = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)) {
            view.foreground = getDrawable(typed.resourceId)
        }
    }

    private fun weight(value: Int): Typeface = Typeface.create(Typeface.DEFAULT, value, false)

    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_WAIT_MS = 2_000L
        private const val LEAVE_APP_CONFIRM_MS = 2_000L
        private const val WIDE_BREAKPOINT_DP = 600
        private const val STATE_PAGE = "dashboard_page"

        @Volatile
        private var showing: Boolean = false

        @Volatile
        private var instance: MainActivity? = null

        fun isShowing(): Boolean = showing

        fun refreshIfShowing() {
            val activity = instance ?: return
            activity.runOnUiThread { activity.renderFromHost() }
        }

        internal fun dialogHost(): MainActivity? {
            val activity = instance
            return if (activity != null && !activity.isFinishing) activity else null
        }
    }
}
