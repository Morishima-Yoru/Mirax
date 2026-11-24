package me.trinitrix.mirax

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.view.animation.PathInterpolator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.Button
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.ui.standby.settings.LanguagePersistenceException
import me.trinitrix.mirax.ui.standby.settings.StandbySettingsPanelModel
import me.trinitrix.mirax.ui.standby.settings.StandbySettingsPanelView
import me.trinitrix.mirax.ui.standby.settings.StandbySettingsSubmission
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.host.SessionPlatformBinding
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
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.StandbyMediaCleanupPendingException
import me.trinitrix.mirax.ui.standby.StandbyMediaCleanupResult
import me.trinitrix.mirax.ui.standby.StandbyMediaEdit
import me.trinitrix.mirax.ui.standby.StandbyMediaEditStatus
import me.trinitrix.mirax.ui.standby.StandbyMediaHostCallbacks
import me.trinitrix.mirax.ui.standby.framing.StandbyFramingEditorView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.runtime.AndroidStandbyMotion
import me.trinitrix.mirax.ui.standby.runtime.StandbyVisibilityState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import me.trinitrix.mirax.session.RootExitConfirmation
import me.trinitrix.mirax.session.StandbyDestination
import me.trinitrix.mirax.session.StandbyNavigationState
import me.trinitrix.mirax.session.WfdOwner

private typealias Page = StandbyDestination

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] and forwards user actions.
 *
 * Narrow windows replace the page. Wide windows keep a 50/50 split for settings,
 * while Standby occupies the full window. Spacing, chips, and the hamburger
 * drawer follow the dashboard visual system.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var activityStarted = false
    private var standbyHost: BigStandbyHost? = null
    private var standbyTheme: StandbyThemeId? = null
    private var standbyCopyMotionAllowed: Boolean? = null
    private var standbyThemeJob: Job? = null
    private var boundThemePanel: StandbySettingsPanelView? = null
    private var mediaLease: Long? = null
    private var mediaAcquireGeneration = 0L
    private var mediaCleanupCancel: (() -> Unit)? = null
    private var standbyMediaReady = false
    private var mediaEdit: StandbyMediaEdit? = null
    private var mediaEditorEdit: StandbyMediaEdit? = null
    private var pendingMediaPresentation: MediaPresentation? = null
    private var renderingMediaPresentation = false
    private var mediaEditor: StandbyFramingEditorView? = null
    private var mediaOverlay: FrameLayout? = null
    private var mediaRetry: Button? = null
    private var mediaBackgroundAccessibility = emptyList<Pair<View, Int>>()
    private var mediaBackgroundFocus = emptyList<Pair<ViewGroup, Int>>()
    private var mediaScrimFocusable: Boolean? = null
    private var splitAccessibility: Int? = null
    private var splitDescendantFocus: Int? = null
    private var splitPreviousFocus: View? = null
    private var mediaStaging = false
    private var pendingMediaUri: Uri? = null
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            consumePendingMediaUri()
        }
    }
    private val mediaCallbacks = object : StandbyMediaHostCallbacks {
        override fun onMediaPresentation(ready: Boolean, decodedSize: Size?, edit: StandbyMediaEdit?, staging: Boolean) {
            if (activityStarted && instance === this@MainActivity) {
                renderMediaPresentation(ready, decodedSize, edit, staging)
            }
        }

        override fun onMediaNotice(message: Int, failedFile: java.io.File?, token: Long?) {
            if (failedFile != null) Log.w("MainActivity", "Standby media failed: $failedFile, owner=$token")
            if (activityStarted && instance === this@MainActivity) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    private var standbyInsets: Insets = Insets.NONE
    private var insetsForStandby: Boolean? = null
    private val standbyMotion by lazy {
        AndroidStandbyMotion(applicationContext, { standbyHost }, ::standbyDisplayDegrees)
    }
    private val standbyRuntime get() = standbyMotion.runtime
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayChanged(displayId: Int) {
            if (::binding.isInitialized && (binding.root.display == null || binding.root.display?.displayId == displayId)) {
                standbyMotion.notifyDisplayChanged()
            }
        }
    }
    private enum class PageMotion { NONE, FORWARD, BACK, FADE }

    private var navState = StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)
    private val rootExit = RootExitConfirmation(
        timeoutMs = LEAVE_APP_CONFIRM_MS,
        clock = SystemClock::elapsedRealtime,
    )

    private val page: Page
        get() = navState.current
    private val homeOrigin: Page
        get() = navState.homeOrigin
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
    private var languageChoiceGroup: LinearLayout? = null
    private var languageReady = false
    private var languageInputEnabled = true
    private var languageBindingRevision = 0L
    private var languageRestoreJob: Job? = null
    private val languageSubmission = StandbySettingsSubmission(
        dispatcher = Dispatchers.Main,
        render = ::renderLanguageChoice,
        setInputEnabled = ::setLanguageInputEnabled,
        logFailure = { Log.e("MainActivity", "Cannot persist language preference", it) },
    )
    private val historyOpen = mutableSetOf<Int>()
    private var customDragFrom: Int? = null
    private var modesRatio: String = "16:9"
    private var modesSize: Pair<Int, Int>? = null
    private var frozenDialog: androidx.appcompat.app.AlertDialog? = null
    /** Avoid [commitBroadcastName] re-entering [render] while [openPage] is clearing focus. */
    private var suppressBroadcastNameCommit: Boolean = false

    /** Avoid [commitMaxBitrate] re-entering [render] while navigation is clearing focus. */
    private var suppressMaxBitrateCommit: Boolean = false

    /** True until the first [onStart] right after [onCreate] skips the redundant privilege refresh. */
    private var justCreated: Boolean = false

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

    private val standbyClockTick = Runnable { syncStandbyClockTick() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (mediaEditor == null && onDrawerSwipe(ev)) {
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !finishBroadcastNameEditingIfTapOutside(ev)) {
            drawerSwipeRejected = true
            drawerSwipeClaimed = false
            drawerSwipePointer = MotionEvent.INVALID_POINTER_ID
            return true
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
     * @param ev Original touch event.
     * @return Whether dispatch can continue without a failed editing handoff.
     */
    private fun finishBroadcastNameEditingIfTapOutside(ev: MotionEvent): Boolean {
        val field = currentFocus as? EditText ?: return true
        if (field.tag != "name" || touchHitsView(ev, field)) {
            return true
        }
        return finishEditingBeforeNavigation()
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
        val restoredPage = savedInstanceState?.getString(STATE_PAGE)?.let { name ->
            StandbyDestination.entries.firstOrNull { it.name == name }
        } ?: StandbyDestination.STANDBY
        val restoredOrigin = savedInstanceState?.getString(STATE_HOME)?.let { name ->
            StandbyDestination.entries.firstOrNull { it.name == name }
        }?.takeIf { it == StandbyDestination.STANDBY || it == StandbyDestination.DASHBOARD }
            ?: if (restoredPage == StandbyDestination.DASHBOARD) StandbyDestination.DASHBOARD else StandbyDestination.STANDBY

        navState = if (restoredPage == StandbyDestination.STANDBY || restoredPage == StandbyDestination.DASHBOARD) {
            StandbyNavigationState(restoredPage, restoredPage)
        } else {
            StandbyNavigationState(restoredPage, restoredOrigin)
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
        session.handle(SessionAction.OpenApp)
        refreshPrivilege()
        justCreated = true
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
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        syncSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            applyDashboardInsets(view, insets)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Immersive fullscreen in Big Standby: hides both status bar and navigation bar (multitasking).
     * Restores system bars when drawer opens or when navigating to other pages.
     */
    private fun syncSystemBars() {
        val window = window ?: return
        val controller = WindowCompat.getInsetsController(window, binding.root)
        val isBigStandby = page == Page.STANDBY && !drawerOpen && mediaEditor == null
        if (isBigStandby) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncSystemBars()
        }
    }

    private fun applyDashboardInsets(view: View, insets: WindowInsetsCompat) {
        val types = if (page == Page.STANDBY || MiraxApp.instance.session.snapshot().cameraCutoutAffectsLayout) {
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        } else {
            WindowInsetsCompat.Type.systemBars()
        }
        val bars = insets.getInsets(types)
        standbyInsets = bars
        if (page == Page.STANDBY) {
            view.setPadding(0, 0, 0, 0)
            binding.drawer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            standbyHost?.applySafeInsets(bars)
            mediaOverlay?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            binding.drawer.setPadding(0, 0, 0, 0)
            mediaOverlay?.setPadding(0, 0, 0, 0)
        }
    }

    private fun standbyDisplayDegrees(): Int = when (binding.root.display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            standbyMotion.notifyDisplayChanged()
            refreshDashboardInsets()
        }
    }

    private fun refreshDashboardInsets() {
        if (!::binding.isInitialized) {
            return
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        instance = this
        showing = true
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        observeStandbyTheme()
        rootExit.reset()
        refreshEnvironmentInputs()
        if (!justCreated) {
            refreshPrivilege()
        } else {
            justCreated = false
        }
        val session = MiraxApp.instance.session
        if (enableBallWhenOverlayGranted && !session.snapshot().showOverlayPermissionReminder) {
            enableBallWhenOverlayGranted = false
            session.handle(SessionAction.SetFloatingBallEnabled(true))
        }
        val snapshot = SessionHost.openedMiraxDashboard(this)
        render(snapshot)
        if (standbyHost == null) buildBigStandby(snapshot)
        mediaAcquireGeneration += 1
        acquireStandbyMedia(mediaAcquireGeneration, waitForCleanup = true)
        syncStandbyForeground(snapshot)
        scheduleAutoWaitIfFrozen()
        bindLanguagePreference()
    }

    override fun onStop() {
        activityStarted = false
        mediaAcquireGeneration += 1
        mediaCleanupCancel?.invoke()
        mediaCleanupCancel = null
        standbyMediaReady = false
        standbyHost?.setMediaVisible(false)
        val retiringLease = mediaLease
        mediaLease = null
        if (retiringLease != null) {
            try {
                MiraxApp.instance.standbyMediaOwner.release(retiringLease)
            } catch (cancelled: CancellationException) {
                Log.w("MainActivity", "Standby media release was cancelled", cancelled)
            } catch (failure: Exception) {
                Log.e("MainActivity", "Cannot release standby media", failure)
            }
        }
        removeMediaEditor(sync = false)
        standbyThemeJob?.cancel()
        standbyThemeJob = null
        boundThemePanel?.unbind()
        boundThemePanel?.visibility = View.GONE
        boundThemePanel = null
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        standbyHost?.setRuntimeVisible(false)
        val snapshot = MiraxApp.instance.session.snapshot()
        standbyRuntime.onVisibilityChanged(StandbyVisibilityState.Background, snapshot.phase)
        if (standbyCopyMotionAllowed == true) standbyHost?.let { updateStandby(it, snapshot) }
        languageBindingRevision++
        languageRestoreJob?.cancel()
        languageRestoreJob = null
        languageSubmission.unbind()
        languageReady = false
        handler.removeCallbacks(autoWaitRunnable)
        handler.removeCallbacks(standbyClockTick)
        rootExit.reset()
        frozenDialog?.dismiss()
        frozenDialog = null
        updateStandbyAwake(false)
        if (instance === this) {
            SessionHost.onStandbyForegroundVisible(false)
            instance = null
            showing = false
        }
        super.onStop()
    }

    private fun observeStandbyTheme() {
        standbyThemeJob?.cancel()
        standbyThemeJob = lifecycleScope.launch {
            try {
                MiraxApp.instance.standbyThemeGateway.themeFlow.collect { selected ->
                    standbyTheme = selected
                    standbyHost?.let { updateStandby(it, MiraxApp.instance.session.snapshot()) }
                    refreshThemePanel()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("MainActivity", "Cannot observe standby theme", failure)
                if (activityStarted) {
                    Toast.makeText(this@MainActivity, R.string.standby_settings_theme_read_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, navState.current.name)
        outState.putString(STATE_HOME, navState.homeOrigin.name)
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
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese(this)))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        session.report(MiraxDisplayReport(binding.root.display?.displayId ?: 0))
        applyResolvedLanguage(session.snapshot().appLanguage)
    }

    private fun applyResolvedLanguage(language: AppLanguage) {
        if (appliedAppLanguage == language) {
            return
        }
        HostEnvironment.applyAppLanguage(language)
        appliedAppLanguage = language
    }

    private fun refreshPrivilege() {
        SessionHost.refreshPrivilegeAndSurfaces(this)
    }

    private fun maybeRequestShizuku(snapshot: SessionSnapshot) {
        if (snapshot.shouldRequestShizukuPermission) {
            SessionPlatformBinding.platform.requestShizukuPermission(this)
        }
    }

    private fun scheduleAutoWaitIfFrozen() {
        handler.removeCallbacks(autoWaitRunnable)
        if (MiraxApp.instance.session.snapshot().phase == ScreenPhase.FROZEN) {
            handler.postDelayed(autoWaitRunnable, AUTO_WAIT_MS)
        }
    }

    /**
     * Sync clock and keep-awake only when Standby is actually visible in the
     * foreground and not showing a live projection stream.
     *
     * Foreground Big Standby always holds keep-awake, including READY and
     * FROZEN; projecting, leaving, or backgrounding releases it. Animated
     * motion ignores system animation preferences and FROZEN does not pause it.
     * Matter sensors additionally require its attached foreground layer.
     *
     * @param snapshot Current session snapshot.
     */
    private fun syncStandbyForeground(snapshot: SessionSnapshot) {
        val visibility = standbyVisibilityFor(snapshot)
        val visible = visibility == StandbyVisibilityState.Visible
        standbyHost?.setRuntimeVisible(visible)
        standbyRuntime.onVisibilityChanged(visibility, snapshot.phase)
        if (standbyCopyMotionAllowed != isStandbyCopyMotionAllowed(visible)) {
            standbyHost?.let { updateStandby(it, snapshot) }
        }
        if (instance === this) {
            SessionHost.onStandbyForegroundVisible(visible)
            refreshPrePlayFailureViews()
        }
        updateStandbyAwake(standbyRuntime.shouldHoldKeepAwake())
        syncMediaVisibility(snapshot)
        handler.removeCallbacks(standbyClockTick)
        val isStandbyForeground = visibility == StandbyVisibilityState.Visible
        if (!isStandbyForeground) {
            return
        }
        renderStandbyClockIfVisible(snapshot)
        val now = System.currentTimeMillis()
        val delayMs = (1000L - (now % 1000L) + CLOCK_SKEW_MS).coerceIn(MIN_CLOCK_DELAY_MS, 1000L)
        handler.postDelayed(standbyClockTick, delayMs)
    }

    /**
     * Maps Activity truth to the standby visibility gate.
     *
     * FROZEN stays [StandbyVisibilityState.Visible] so approved frozen motion
     * keeps running; only leaving standby, projecting, or backgrounding
     * pauses the pipeline.
     *
     * @param snapshot Current session snapshot.
     * @return Mapped visibility.
     */
    private fun standbyVisibilityFor(snapshot: SessionSnapshot): StandbyVisibilityState {
        if (!activityStarted || instance !== this) return StandbyVisibilityState.Background
        if (page != Page.STANDBY) return StandbyVisibilityState.Hidden
        if (snapshot.phase == ScreenPhase.CONNECTED) return StandbyVisibilityState.Projecting
        if (mediaEditor != null) return StandbyVisibilityState.Hidden
        return StandbyVisibilityState.Visible
    }

    private fun syncStandbyClockTick() {
        if (!activityStarted || instance !== this || page != Page.STANDBY) {
            updateStandbyAwake(false)
            return
        }
        val snapshot = MiraxApp.instance.session.snapshot()
        if (snapshot.phase == ScreenPhase.CONNECTED) {
            updateStandbyAwake(false)
            return
        }
        renderStandbyClockIfVisible(snapshot)
        val now = System.currentTimeMillis()
        val delayMs = (1000L - (now % 1000L) + CLOCK_SKEW_MS).coerceIn(MIN_CLOCK_DELAY_MS, 1000L)
        handler.postDelayed(standbyClockTick, delayMs)
    }

    private fun renderStandbyClockIfVisible(snapshot: SessionSnapshot) {
        standbyHost?.updateClock(standbyClockText(snapshot))
    }

    /** Binds the output, waiting once only for a known asynchronous cleanup. */
    private fun acquireStandbyMedia(generation: Long, waitForCleanup: Boolean) {
        if (!isCurrentMediaRequest(generation)) return
        val owner = MiraxApp.instance.standbyMediaOwner
        val host = standbyHost ?: return
        try {
            val lease = owner.acquire(host.mediaUnderlay, mediaCallbacks)
            if (!isCurrentMediaRequest(generation)) {
                owner.release(lease)
                return
            }
            mediaLease = lease
            owner.synchronize(lease)
            if (activityStarted && instance === this && mediaAcquireGeneration == generation && mediaLease == lease) {
                syncMediaVisibility(MiraxApp.instance.session.snapshot())
                if (activityStarted && instance === this && mediaAcquireGeneration == generation && mediaLease == lease) {
                    consumePendingMediaUri()
                }
            }
        } catch (pending: StandbyMediaCleanupPendingException) {
            if (!waitForCleanup || !isCurrentMediaRequest(generation)) {
                Log.e("MainActivity", "Cannot bind standby media after cleanup", pending)
                if (isCurrentMediaRequest(generation)) {
                    Toast.makeText(this, R.string.standby_media_read_failed, Toast.LENGTH_LONG).show()
                }
                return
            }
            val cancel = owner.awaitCleanup(pending) { result ->
                if (isCurrentMediaRequest(generation)) {
                    mediaCleanupCancel = null
                    when (result) {
                        StandbyMediaCleanupResult.READY -> acquireStandbyMedia(generation, waitForCleanup = false)
                        StandbyMediaCleanupResult.FAILED -> {
                            Log.e("MainActivity", "Standby media cleanup failed")
                            Toast.makeText(this, R.string.standby_media_read_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            if (cancel == null) {
                Log.w("MainActivity", "Standby media cleanup was superseded", pending)
                if (isCurrentMediaRequest(generation)) {
                    Toast.makeText(this, R.string.standby_media_read_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                mediaCleanupCancel = cancel
            }
        } catch (cancelled: CancellationException) {
            Log.w("MainActivity", "Standby media binding was cancelled", cancelled)
        } catch (failure: Exception) {
            Log.e("MainActivity", "Cannot bind standby media", failure)
            Toast.makeText(this, R.string.standby_media_read_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Checks that the started activity still owns this acquisition request. */
    private fun isCurrentMediaRequest(generation: Long): Boolean =
        activityStarted && instance === this && mediaAcquireGeneration == generation && mediaLease == null

    private fun consumePendingMediaUri() {
        val lease = mediaLease ?: return
        val uri = pendingMediaUri ?: return
        pendingMediaUri = null
        MiraxApp.instance.standbyMediaOwner.importDocument(lease, uri)
    }

    private fun syncMediaVisibility(snapshot: SessionSnapshot) {
        val lease = mediaLease ?: return
        val visible = activityStarted && instance === this && snapshot.phase != ScreenPhase.CONNECTED &&
            (standbyVisibilityFor(snapshot) == StandbyVisibilityState.Visible || mediaEditor != null)
        try {
            MiraxApp.instance.standbyMediaOwner.setVisible(lease, visible)
        } catch (cancelled: CancellationException) {
            Log.w("MainActivity", "Standby media visibility change was cancelled", cancelled)
        } catch (failure: Exception) {
            Log.e("MainActivity", "Cannot change standby media visibility", failure)
            Toast.makeText(this, R.string.standby_media_playback_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Retains the latest owner publication during synchronous preview handoff.
     *
     * @param ready Whether the current output is ready.
     * @param size Current decoded dimensions.
     * @param edit Current retained transaction, or null after retirement.
     * @param staging Whether an import is being staged.
     */
    private data class MediaPresentation(
        val ready: Boolean,
        val size: Size?,
        val edit: StandbyMediaEdit?,
        val staging: Boolean,
    )

    /**
     * Serializes editor ownership changes and drains synchronous owner updates.
     *
     * @param ready Whether the current output is ready.
     * @param size Current decoded dimensions.
     * @param edit Current retained transaction, or null after retirement.
     * @param staging Whether an import is being staged.
     */
    private fun renderMediaPresentation(ready: Boolean, size: Size?, edit: StandbyMediaEdit?, staging: Boolean) {
        pendingMediaPresentation = MediaPresentation(ready, size, edit, staging)
        if (renderingMediaPresentation) return
        renderingMediaPresentation = true
        try {
            while (activityStarted && instance === this) {
                val presentation = pendingMediaPresentation ?: break
                pendingMediaPresentation = null
                val currentEdit = presentation.edit
                mediaEdit = currentEdit
                mediaStaging = presentation.staging
                val readinessChanged = standbyMediaReady != presentation.ready
                standbyMediaReady = presentation.ready
                standbyHost?.setMediaVisible(presentation.ready)
                if (readinessChanged) {
                    val snapshot = MiraxApp.instance.session.snapshot()
                    val visible = standbyVisibilityFor(snapshot) == StandbyVisibilityState.Visible
                    if (standbyCopyMotionAllowed != isStandbyCopyMotionAllowed(visible)) {
                        standbyHost?.let { updateStandby(it, snapshot) }
                    }
                }
                updateMediaImportControls()
                if (pendingMediaPresentation != null) continue
                if (currentEdit == null) {
                    if (mediaEditorEdit != null) {
                        removeMediaEditor(sync = true)
                    }
                    mediaEditorEdit = null
                    continue
                }
                if (mediaEditor == null || mediaEditorEdit?.candidate != currentEdit.candidate) {
                    removeMediaEditor(sync = false)
                    mediaEditorEdit = null
                    if (pendingMediaPresentation != null) continue
                    showMediaEditor(currentEdit)
                    mediaEditorEdit = currentEdit
                }
                // Preview detach/attach can publish a newer readiness before show returns.
                if (pendingMediaPresentation != null) continue
                presentation.size?.let { mediaEditor?.setContentSize(it.width.toFloat(), it.height.toFloat()) }
                if (pendingMediaPresentation != null) continue
                mediaEditor?.setPlaybackReady(presentation.ready)
                mediaRetry?.visibility = if (currentEdit.status == StandbyMediaEditStatus.UNKNOWN) View.VISIBLE else View.GONE
                if (currentEdit.status == StandbyMediaEditStatus.COMMITTED && mediaEditor?.completeCommit() == true) {
                    mediaLease?.let { MiraxApp.instance.standbyMediaOwner.acknowledgeCommit(it) }
                }
            }
        } finally {
            renderingMediaPresentation = false
        }
    }

    private inline fun runMediaPresentationLocked(block: () -> Unit) {
        val ownsPresentation = !renderingMediaPresentation
        if (ownsPresentation) renderingMediaPresentation = true
        var completed = false
        try {
            block()
            completed = true
        } finally {
            if (ownsPresentation) {
                renderingMediaPresentation = false
                if (completed) {
                    val queued = pendingMediaPresentation
                    pendingMediaPresentation = null
                    if (queued != null) {
                        renderMediaPresentation(queued.ready, queued.size, queued.edit, queued.staging)
                    }
                }
            }
        }
    }

    private fun showMediaEditor(edit: StandbyMediaEdit) {
        runMediaPresentationLocked {
            closeDrawer()
            val host = standbyHost ?: buildBigStandby(MiraxApp.instance.session.snapshot())
            val preview = host.takeMediaPreview()
            try {
                val editor = StandbyFramingEditorView(
                    context = this,
                    preview = preview,
                    model = edit.model,
                    onPreview = { framing -> mediaLease?.let { MiraxApp.instance.standbyMediaOwner.preview(it, framing) } },
                    onConfirm = { framing -> mediaLease?.let { MiraxApp.instance.standbyMediaOwner.confirm(it, framing) } },
                    onCancel = { mediaLease?.let { MiraxApp.instance.standbyMediaOwner.cancel(it) } },
                    commitPending = edit.status != StandbyMediaEditStatus.EDITING,
                )
                val retry = Button(this).apply {
                    setText(R.string.standby_media_retry_confirmation)
                    minHeight = dp(48)
                    visibility = View.GONE
                    setOnClickListener { mediaLease?.let { MiraxApp.instance.standbyMediaOwner.retryUnknown(it) } }
                }
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    addView(retry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                val overlay = FrameLayout(this).apply {
                    isClickable = true
                    isFocusableInTouchMode = true
                    elevation = binding.drawer.elevation + 1f
                    ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.standby_framing_title))
                    if (page == Page.STANDBY) {
                        setPadding(standbyInsets.left, standbyInsets.top, standbyInsets.right, standbyInsets.bottom)
                    }
                    addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                mediaEditor = editor
                mediaRetry = retry
                mediaOverlay = overlay
                binding.root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                syncSplitIsolation()
                val background = listOf(binding.drawer, binding.drawerScrim)
                mediaBackgroundAccessibility = background.map { it to it.importantForAccessibility }
                mediaBackgroundFocus = listOf(binding.drawer).map { it to it.descendantFocusability }
                mediaScrimFocusable = binding.drawerScrim.isFocusable
                background.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                mediaBackgroundFocus.forEach { (view, _) -> view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
                binding.drawerScrim.isFocusable = false
                overlay.requestFocus()
                syncStandbyForeground(MiraxApp.instance.session.snapshot())
            } catch (failure: Throwable) {
                if (mediaOverlay != null) {
                    removeMediaEditor(sync = false)
                } else {
                    host.restoreMediaPreview()
                }
                throw failure
            }
        }
    }

    private fun removeMediaEditor(sync: Boolean) {
        runMediaPresentationLocked {
            mediaEditorEdit = null
            val overlay = mediaOverlay ?: return@runMediaPresentationLocked
            mediaOverlay = null
            mediaEditor = null
            mediaRetry = null
            try {
                standbyHost?.restoreMediaPreview()
            } finally {
                try {
                    binding.root.removeView(overlay)
                } finally {
                    mediaBackgroundAccessibility.forEach { (view, value) -> view.importantForAccessibility = value }
                    mediaBackgroundFocus.forEach { (view, value) -> view.descendantFocusability = value }
                    mediaScrimFocusable?.let { binding.drawerScrim.isFocusable = it }
                    mediaBackgroundAccessibility = emptyList()
                    mediaBackgroundFocus = emptyList()
                    mediaScrimFocusable = null
                    syncSplitIsolation()
                }
            }
            if (sync && activityStarted && instance === this) {
                syncStandbyForeground(MiraxApp.instance.session.snapshot())
            }
        }
    }

    private fun updateMediaImportControls() {
        if (!::binding.isInitialized) return
        val enabled = activityStarted && mediaLease != null && !mediaStaging && mediaEdit == null
        listOf(binding.pageStart, binding.pageEnd).forEach { pane ->
            pane.findViewWithTag<View>("standby-media-import")?.isEnabled = enabled
            pane.findViewWithTag<View>("standby-media-importing")?.visibility =
                if (mediaStaging) View.VISIBLE else View.GONE
        }
    }

    /**
     * Holds or releases the window keep-awake flag for Big Standby.
     *
     * Foreground standby holds it in every phase (including READY and
     * FROZEN); leaving, projecting, or backgrounding releases it. Never
     * changes brightness or animation preferences.
     *
     * @param keepAwake True while standby is foreground-visible.
     */
    private fun updateStandbyAwake(keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun standbyClockText(snapshot: SessionSnapshot): String {
        val locale = when (snapshot.appLanguage) {
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.ENGLISH -> Locale.ENGLISH
        }
        return SimpleDateFormat("HH:mm:ss", locale).format(Date(System.currentTimeMillis()))
    }

    private fun wideWindow(): Boolean = resources.configuration.screenWidthDp >= WIDE_BREAKPOINT_DP

    /**
     * Subpage parent for wide-window 50/50 split rendering.
     * Standby, Dashboard, and top-level sections remain full-width.
     *
     * @param target Target screen.
     * @return Subpage parent or null.
     */
    private fun visualSectionParent(target: Page): Page? {
        return when (target) {
            Page.LANGUAGE -> Page.GENERAL
            Page.CUSTOM, Page.MODES, Page.SCALE -> Page.PICTURE
            else -> null
        }
    }

    private fun parentOf(target: Page): Page? {
        return StandbyNavigationState(target, homeOrigin).parent()
    }

    private fun backContentDescription(destination: Page?): String {
        val name = when (destination) {
            Page.STANDBY -> getString(R.string.nav_standby)
            Page.DASHBOARD -> getString(R.string.nav_dashboard_legacy)
            Page.GENERAL -> getString(R.string.nav_general)
            Page.PICTURE -> getString(R.string.nav_picture)
            else -> return getString(R.string.nav_back)
        }
        return getString(R.string.nav_back_to, name)
    }

    /**
     * Transfers focus once to the existing noninteractive root, without taking modal ownership.
     * @return Whether the root itself owns window/input focus rather than a descendant.
     */
    private fun parkNonEditingFocus(): Boolean {
        if (drawerOpen || mediaEditor != null || frozenDialog?.isShowing == true ||
            binding.drawer.visibility != View.GONE || binding.drawerScrim.visibility != View.GONE
        ) {
            Log.w("MainActivity", "Editing focus handoff blocked by overlay ownership")
            return false
        }
        val root = binding.root
        // Prefer the root for explicit parking, without blocking descendants or exposing an empty accessibility control.
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        // A fullscreen parking root has no spatial neighbour; DPAD exits through normal child traversal.
        root.setOnKeyListener { _, keyCode, event ->
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_FORWARD
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_BACKWARD
                else -> null
            }
            if (root.isFocused && event.action == KeyEvent.ACTION_DOWN && direction != null) {
                val next = root.focusSearch(direction)
                next != null && next !== root && next.requestFocus() && !root.isFocused
            } else {
                false
            }
        }
        root.requestFocus()
        val focused = currentFocus
        val parked = root.isFocused && focused !is EditText && focused === root && root.hasWindowFocus()
        if (!parked) Log.w("MainActivity", "Editing focus handoff did not reach the main root")
        return parked
    }

    /**
     * Commits inline edits and parks editable focus before navigation changes pane or drawer.
     * @return Whether editing ended; existing noneditable focus needs no parking.
     */
    private fun finishEditingBeforeNavigation(): Boolean {
        val focused = currentFocus
        val prepared = when {
            focused is EditText && focused.tag == "name" -> {
                suppressBroadcastNameCommit = true
                try {
                    persistBroadcastName(focused)
                    parkNonEditingFocus()
                } finally {
                    suppressBroadcastNameCommit = false
                }
            }
            focused is EditText && focused.tag == "max-bitrate" -> {
                suppressMaxBitrateCommit = true
                try {
                    persistMaxBitrate(focused)
                    parkNonEditingFocus()
                } finally {
                    suppressMaxBitrateCommit = false
                }
            }
            focused is EditText -> parkNonEditingFocus()
            else -> true
        }
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        return prepared
    }

    private fun openPage(target: Page) {
        val prepared = finishEditingBeforeNavigation()
        rootExit.reset()
        if (!prepared) return
        navState = navState.navigateTo(target)
        closeDrawer()
        render(MiraxApp.instance.session.snapshot())
    }

    private fun openHome(home: Page) {
        val prepared = finishEditingBeforeNavigation()
        rootExit.reset()
        if (!prepared) return
        navState = navState.openHome(home)
        closeDrawer()
        render(MiraxApp.instance.session.snapshot())
    }

    /** Steps to [parentOf]; failed editing preparation consumes the attempt. Returns false only at a root. */
    private fun navigateUp(): Boolean {
        val next = navState.stepUp() ?: return false
        val prepared = finishEditingBeforeNavigation()
        rootExit.reset()
        if (!prepared) return true
        navState = next
        closeDrawer()
        render(MiraxApp.instance.session.snapshot())
        return true
    }

    /** Consumes editable Back even when parking or the optional IME service is unavailable. @return Whether an EditText owned focus. */
    private fun dismissKeyboardFirst(): Boolean {
        val focused = currentFocus ?: return false
        if (focused !is EditText) {
            return false
        }
        finishEditingBeforeNavigation()
        return true
    }

    /**
     * Same path as the top-left / end-pane Back control: dismiss keyboard, close
     * the drawer, step to parent page, or confirm leaving when at a root home.
     *
     * @param callback System back callback.
     */
    private fun onDashboardBack(callback: OnBackPressedCallback) {
        if (frozenDialog?.isShowing == true) {
            frozenDialog?.dismiss()
            rootExit.reset()
            return
        }
        if (mediaEditor != null) {
            rootExit.reset()
            val lease = mediaLease
            if (mediaEdit?.status == StandbyMediaEditStatus.EDITING && lease != null) {
                MiraxApp.instance.standbyMediaOwner.cancel(lease)
            } else {
                Toast.makeText(this, R.string.standby_media_waiting_confirmation, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (currentFocus is EditText && dismissKeyboardFirst()) {
            rootExit.reset()
            return
        }
        if (drawerOpen) {
            rootExit.reset()
            closeDrawer()
            return
        }
        if (navigateUp()) {
            rootExit.reset()
            return
        }
        if (rootExit.onPress()) {
            callback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            callback.isEnabled = true
        } else {
            Toast.makeText(
                this,
                getString(R.string.press_back_again_to_leave),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openDrawer() {
        if (drawerOpen || mediaEditor != null) {
            return
        }
        rootExit.reset()
        val previousSplitFocus = binding.split.findFocus()
        if (!finishEditingBeforeNavigation()) return
        val rootOwnedFocus = binding.root.isFocused && currentFocus === binding.root && binding.root.hasWindowFocus()
        drawerOpen = true
        if (splitAccessibility == null) splitPreviousFocus = previousSplitFocus
        val snapshot = MiraxApp.instance.session.snapshot()
        render(snapshot)
        syncStandbyForeground(snapshot)
        // A parked root is outside split, so split isolation cannot hand off its focus.
        if (rootOwnedFocus && drawerOpen && mediaEditor == null && binding.drawer.isVisible &&
            binding.drawer.hasWindowFocus()
        ) {
            binding.drawerClose.isFocusableInTouchMode = true
            if (!binding.drawerClose.requestFocus()) {
                Log.w("MainActivity", "Drawer opening did not accept parked-root focus")
            }
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) {
            return
        }
        rootExit.reset()
        drawerOpen = false
        syncDrawer()
        syncSystemBars()
        syncStandbyForeground(MiraxApp.instance.session.snapshot())
    }

    private fun render(snapshot: SessionSnapshot) {
        val wide = wideWindow()
        val visualParent = visualSectionParent(page)
        val startPage = if (wide && visualParent != null) visualParent else page
        val endPage = if (wide && visualParent != null) page else null
        val focusedHost = when {
            hostHasFocus(binding.pageStart) -> binding.pageStart
            hostHasFocus(binding.pageEnd) -> binding.pageEnd
            else -> null
        }
        val focusedTarget = if (focusedHost === binding.pageStart) startPage else endPage
        if (focusedHost != null && (focusedTarget == null || focusedHost.getChildAt(0)?.tag != focusedTarget)) {
            // Refuse the whole render before layout changes or either pane operation.
            if (!finishEditingBeforeNavigation()) return
            // Replacing a noneditable control does not require a successful editing handoff.
            if (hostHasFocus(focusedHost)) parkNonEditingFocus()
        }
        val showSplit = wide && endPage != null
        val fullStandby = page == Page.STANDBY
        binding.startBar.visibility = if (fullStandby) View.GONE else View.VISIBLE
        if (insetsForStandby != fullStandby) {
            insetsForStandby = fullStandby
            val currentInsets = ViewCompat.getRootWindowInsets(binding.root)
            if (currentInsets != null) {
                applyDashboardInsets(binding.root, currentInsets)
            } else if (fullStandby) {
                binding.root.setPadding(0, 0, 0, 0)
            }
            refreshDashboardInsets()
        }

        binding.paneEnd.visibility = if (showSplit) View.VISIBLE else View.GONE
        binding.paneDivider.visibility = if (showSplit) View.VISIBLE else View.GONE

        val drawerParams = binding.drawer.layoutParams
        drawerParams.width = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.86f).toInt())
        binding.drawer.layoutParams = drawerParams
        binding.startBack.contentDescription = backContentDescription(parentOf(startPage))
        if (endPage != null) {
            binding.endBack.contentDescription = backContentDescription(parentOf(page))
        }

        val startParams = binding.paneStart.layoutParams as LinearLayout.LayoutParams
        if (showSplit) {
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
        syncStandbyForeground(snapshot)
        syncSystemBars()
        if (snapshot.phase != ScreenPhase.FROZEN) {
            frozenDialog?.dismiss()
        } else if (frozenDialog?.isShowing == true) {
            frozenDialog?.setMessage(frozenFactsText(snapshot))
        }
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

    /** Shares the split's original focus boundary across overlapping drawer and media lifetimes. */
    private fun syncSplitIsolation() {
        val split = binding.split
        val blocked = drawerOpen || mediaEditor != null || binding.drawer.visibility != View.GONE ||
            binding.drawerScrim.visibility != View.GONE
        if (blocked) {
            if (splitAccessibility == null) {
                splitAccessibility = split.importantForAccessibility
                splitDescendantFocus = split.descendantFocusability
                // openDrawer captures focus before committing edits; drag and media capture it here.
                if (!drawerOpen) splitPreviousFocus = split.findFocus()
            }
            val backgroundFocus = split.findFocus()
            split.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            split.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // The parking root must not become a focus stop outside the active drawer/editor.
            binding.root.isFocusable = false
            backgroundFocus?.clearFocus()
            if (backgroundFocus != null && mediaEditor == null) binding.drawerClose.requestFocus()
        } else {
            splitAccessibility?.let { split.importantForAccessibility = it }
            splitDescendantFocus?.let { split.descendantFocusability = it }
            val previousFocus = splitPreviousFocus
            splitAccessibility = null
            splitDescendantFocus = null
            splitPreviousFocus = null
            if (previousFocus != null && previousFocus.isAttachedToWindow && previousFocus.isShown &&
                previousFocus.isFocusable && previousFocus.isEnabled
            ) previousFocus.requestFocus()
        }
    }

    private fun beginDrawerDrag() {
        val drawer = binding.drawer
        val scrim = binding.drawerScrim
        drawer.animate().cancel()
        scrim.animate().cancel()
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        syncSplitIsolation()
        drawer.translationX = drawerOffscreen()
        scrim.alpha = 0f
        syncStandbyForeground(MiraxApp.instance.session.snapshot())
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
        syncStandbyForeground(MiraxApp.instance.session.snapshot())
    }

    private fun drawerWidth(): Float {
        val measured = binding.drawer.width
        return (if (measured > 0) measured else binding.drawer.layoutParams.width).toFloat()
    }

    private fun syncDrawer() {
        syncSplitIsolation()
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
            syncSplitIsolation()
            return
        }
        val offscreen = drawerOffscreen()
        if (open) {
            val wasHidden = drawer.visibility != View.VISIBLE
            drawer.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            syncSplitIsolation()
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
                    syncSplitIsolation()
                    syncStandbyForeground(MiraxApp.instance.session.snapshot())
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
                    syncSplitIsolation()
                    syncStandbyForeground(MiraxApp.instance.session.snapshot())
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
            Page.STANDBY -> "standby"
            Page.DASHBOARD -> "${snapshot.phase}|${snapshot.advertisingEnabled}|${snapshot.connectionRuns.size}"
            Page.PICTURE -> "${customSummary(snapshot)}|${modesSummary(snapshot)}|${scaleLabel(snapshot.pictureScale)}|${snapshot.touchEnabled}|${snapshot.maxVideoBitrateBps}"
            Page.CUSTOM -> "${snapshot.autoAddWmSizeOnConnect}|${snapshot.customModes.joinToString { it.format() }}"
            Page.GENERAL -> "${snapshot.displayNameOverride}|${ballOn(snapshot)}|${snapshot.languagePreference}"
            Page.MODES -> snapshot.standardModes.joinToString { "${it.mode.format()}=${it.checked}" }
            Page.SCALE -> snapshot.pictureScale.name
            Page.LANGUAGE -> snapshot.languagePreference.name
            Page.ADVANCED -> "${snapshot.showDebugMessages}|${snapshot.cameraCutoutAffectsLayout}|${snapshot.broadcastAutoStopMinutes}"
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
            Page.STANDBY -> return buildBigStandby(snapshot).apply { tag = target }
            Page.DASHBOARD -> buildDashboard(snapshot)
            Page.HISTORY -> buildHistory(snapshot)
            Page.GENERAL -> buildGeneral(snapshot)
            Page.LANGUAGE -> buildLanguage(snapshot)
            Page.PICTURE -> buildPicture(snapshot)
            Page.CUSTOM -> buildCustom(snapshot)
            Page.MODES -> buildModes(snapshot)
            Page.SCALE -> buildScale(snapshot)
            Page.ADVANCED -> buildAdvanced(snapshot)
            Page.ABOUT -> buildAbout()
        }
        return scroll(content).apply { tag = target }
    }

    private fun updatePage(root: View, target: Page, snapshot: SessionSnapshot) {
        updatingControls = true
        when (target) {
            Page.STANDBY -> updateStandby(root, snapshot)
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
            Page.ABOUT -> Unit
        }
        updatingControls = false
    }

    private fun standbySignature(snapshot: SessionSnapshot): String =
        "${snapshot.phase}|${snapshot.advertisingEnabled}|${snapshot.effectiveBroadcastName}|${snapshot.connectionRuns.size}|"

    /**
     * Returns current FAILED cause only while standby foreground-visible.
     *
     * @param snapshot Current session state.
     * @return Latest failure cause, null when hidden superseded.
     */
    private fun prePlayFailureCause(snapshot: SessionSnapshot): me.trinitrix.mirax.session.PrePlayFailureCause? {
        val episode = me.trinitrix.mirax.session.PrePlayFailureEpisodeOwner.getInstance().snapshot()
        val presentation = me.trinitrix.mirax.session.prePlayFailurePresentation(
            episode,
            SessionHost.latestPrePlayAttempt(),
            standbyVisibilityFor(snapshot) == StandbyVisibilityState.Visible,
            snapshot.phase,
        )
        return (presentation as? me.trinitrix.mirax.session.PrePlayFailurePresentation.Shown)?.cause
    }

    private fun isPrePlayFailureVisible(): Boolean =
        prePlayFailureCause(MiraxApp.instance.session.snapshot()) != null

    /**
     * Formats only provable FAILED facts: no invented codes or reasons.
     *
     * @return Localized FAILED line for the current episode, or empty.
     */
    private fun prePlayFailureText(
        cause: me.trinitrix.mirax.session.PrePlayFailureCause? =
            prePlayFailureCause(MiraxApp.instance.session.snapshot()),
    ): String {
        return when (cause) {
            null -> ""
            me.trinitrix.mirax.session.PrePlayFailureCause.PRE_PLAY_TIMEOUT ->
                getString(R.string.preplay_failed_timeout)
            me.trinitrix.mirax.session.PrePlayFailureCause.ESTABLISHMENT_FAILED ->
                getString(R.string.preplay_failed_establishment)
            me.trinitrix.mirax.session.PrePlayFailureCause.NEGOTIATION_FAILED ->
                getString(R.string.preplay_failed_negotiation)
        }
    }

    /**
     * Refreshes resolved standby copy and scene flags through the normal presentation binding.
     * Runs no Activity/session render, commit, broadcast, idle, or navigation work.
     *
     * @param snapshot current host truth used by the same binding as a normal standby update.
     */
    private fun refreshPrePlayFailureViews(
        snapshot: SessionSnapshot = MiraxApp.instance.session.snapshot(),
    ) {
        if (!::binding.isInitialized || !activityStarted || instance !== this || page != Page.STANDBY) return
        standbyHost?.let { updateStandby(it, snapshot) }
    }

    /**
     * Formats localized status facts for the frozen dialog.
     *
     * @param snapshot Current session state to read live facts from.
     * @return Formatted notice with WFD owner and root availability.
     */
    private fun frozenFactsText(snapshot: SessionSnapshot): String {
        val ownerText = when (snapshot.wfdOwner) {
            WfdOwner.NONE -> getString(R.string.fact_none)
            WfdOwner.HELPER -> getString(R.string.wfd_owner_helper)
            WfdOwner.SHIZUKU -> getString(R.string.wfd_owner_shizuku)
        }
        val rootText = if (snapshot.rootAvailable) {
            getString(R.string.fact_available)
        } else {
            getString(R.string.fact_none)
        }
        return buildString {
            append(getString(R.string.frozen_fact_wfd_owner, ownerText))
            append("\n")
            append(getString(R.string.frozen_fact_root, rootText))
        }
    }

    /**
     * Shows a dialog explaining why broadcast is unavailable and what privilege paths exist.
     *
     * @param snapshot Current session state to inspect.
     */
    private fun showFrozenInfoDialog(snapshot: SessionSnapshot) {
        if (snapshot.phase != ScreenPhase.FROZEN) {
            frozenDialog?.dismiss()
            return
        }
        frozenDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.frozen_dialog_title)
            .setMessage(frozenFactsText(snapshot))
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(R.string.waiting_retry) { _, _ ->
                if (MiraxApp.instance.session.snapshot().phase == ScreenPhase.FROZEN) onRetry()
            }
        if (snapshot.shouldRequestShizukuPermission) {
            builder.setNeutralButton(R.string.frozen_open_shizuku) { _, _ ->
                val current = MiraxApp.instance.session.snapshot()
                if (current.shouldRequestShizukuPermission) {
                    maybeRequestShizuku(current)
                }
            }
        }
        builder.setOnCancelListener {
            rootExit.reset()
        }
        builder.setOnDismissListener {
            frozenDialog = null
        }
        frozenDialog = builder.show()
    }

    private fun buildBigStandby(snapshot: SessionSnapshot): BigStandbyHost {
        val existing = standbyHost
        if (existing != null) {
            updateStandby(existing, snapshot)
            return existing
        }
        val callbacks = StandbyForegroundCallbacks(
            onToggleBroadcast = {
                val live = MiraxApp.instance.session.snapshot()
                if (live.phase != ScreenPhase.FROZEN) {
                    val enable = live.phase != ScreenPhase.WIFI_PAUSED && !live.advertisingEnabled
                    render(SessionHost.setAdvertising(this, enable))
                }
            },
            onDismissFailure = { SessionHost.dismissPrePlayFailure() },
            onOpenShizuku = { maybeRequestShizuku(MiraxApp.instance.session.snapshot()) },
            onShowFrozenInfo = { showFrozenInfoDialog(MiraxApp.instance.session.snapshot()) },
            onOpenSettings = { openDrawer() },
            onStopBroadcastWhilePaused = {
                if (MiraxApp.instance.session.snapshot().phase == ScreenPhase.WIFI_PAUSED) {
                    render(SessionHost.setAdvertising(this, false))
                }
            },
        )
        val host = BigStandbyHost(this, callbacks)
        standbyHost = host
        host.onViewportChanged = { standbyMotion.notifyDisplayChanged() }
        host.onMatterMotionAvailabilityChanged = standbyRuntime::onMatterLayerAvailabilityChanged
        host.applySafeInsets(standbyInsets)
        updateStandby(host, snapshot)
        return host
    }

    private fun buildStandby(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        val frozen = snapshot.phase == ScreenPhase.FROZEN
        column.addView(phaseTitle(getString(R.string.standby_title)).apply { tag = "phase" })
        column.addView(hint(getString(R.string.standby_status, standbyStatusText(snapshot))).apply { tag = "hint" })
        column.addView(clockText(standbyClockText(snapshot)).apply { tag = "clock" })
        column.addView(hint(standbyFactsText(snapshot)).apply { tag = "facts" })
        column.addView(linkButton(getString(R.string.frozen_dialog_title)) {
            showFrozenInfoDialog(MiraxApp.instance.session.snapshot())
        }.apply {
            tag = "frozen_info"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            visibility = if (frozen) View.VISIBLE else View.GONE
        })
        column.addView(linkButton(getString(R.string.waiting_retry)) { onRetry() }.apply {
            tag = "retry"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            visibility = if (frozen) View.VISIBLE else View.GONE
        })
        column.addView(hint(prePlayFailureText()).apply {
            tag = "preplay_failed"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            visibility = if (isPrePlayFailureVisible()) View.VISIBLE else View.GONE
        })
        column.addView(linkButton(getString(R.string.preplay_failed_dismiss)) {
            // Purely presentational: changes no broadcast, idle clock, or navigation.
            SessionHost.dismissPrePlayFailure()
        }.apply {
            tag = "preplay_failed_dismiss"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            visibility = if (isPrePlayFailureVisible()) View.VISIBLE else View.GONE
        })
        val isPausedBuild = snapshot.phase == ScreenPhase.WIFI_PAUSED
        column.addView(hint(getString(R.string.standby_wifi_warning)).apply {
            tag = "wifi_warning"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            visibility = if (isPausedBuild) View.VISIBLE else View.GONE
        })
        column.addView(broadcastButton(
            getString(R.string.standby_wifi_warning_stop),
            enabled = true,
            advertising = false,
        ) {
            // Warning switch: stops advertising; intent resumes on Wi-Fi return
            // subject to readiness/privilege. Distinct from the FROZEN info action.
            render(SessionHost.setAdvertising(this, false))
        }.apply {
            tag = "wifi_warning_stop"
            visibility = if (isPausedBuild) View.VISIBLE else View.GONE
        })
        column.addView(broadcastButton(
            broadcastLabel(snapshot),
            enabled = !frozen,
            advertising = snapshot.advertisingEnabled || isPausedBuild,
        ) {
            val live = MiraxApp.instance.session.snapshot()
            if (live.phase == ScreenPhase.FROZEN) {
                return@broadcastButton
            }
            if (live.phase == ScreenPhase.WIFI_PAUSED || live.advertisingEnabled) {
                render(SessionHost.setAdvertising(this, false))
            } else {
                render(SessionHost.setAdvertising(this, true))
            }
        }.apply { tag = "broadcast" })
        column.addView(linkButton(getString(R.string.standby_open_dashboard)) {
            openHome(Page.DASHBOARD)
        }.apply {
            tag = "legacy"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
        column.addView(jump(getString(R.string.history_entry), historyCount(snapshot), topDp = 20) {
            openPage(Page.HISTORY)
        })
        return column
    }

    /** Applies the selected theme's copy-motion gate to real media readiness. */
    private fun isStandbyCopyMotionAllowed(visible: Boolean): Boolean =
        visible && standbyTheme != StandbyThemeId.MATTER &&
            (standbyTheme != StandbyThemeId.HARDLINE || !standbyMediaReady)

    private fun updateStandby(root: View, snapshot: SessionSnapshot) {
        val host = root as? BigStandbyHost ?: return
        if (standbyTheme == StandbyThemeId.LEGACY) {
            // Legacy dashboard is a standby theme now, not its own page: build its
            // card column once inside the theme host, then keep it updated in place.
            if (host.legacyHost.childCount == 0) {
                host.legacyHost.addView(
                    scroll(buildDashboard(snapshot)),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            updateDashboard(host.legacyHost, snapshot)
        }
        val cause = prePlayFailureCause(snapshot)
        val projecting = snapshot.phase == ScreenPhase.CONNECTED
        val copyMotionAllowed = isStandbyCopyMotionAllowed(
            standbyVisibilityFor(snapshot) == StandbyVisibilityState.Visible,
        )
        standbyCopyMotionAllowed = copyMotionAllowed
        val state = StandbyForegroundState(
            phase = snapshot.phase,
            advertisingEnabled = snapshot.advertisingEnabled,
            shouldOfferShizuku = snapshot.shouldRequestShizukuPermission,
            receiverName = snapshot.effectiveBroadcastName,
            clockText = standbyClockText(snapshot),
            failureCause = cause,
            failureText = prePlayFailureText(cause),
            copyMotionAllowed = copyMotionAllowed,
            isProjecting = projecting,
        )
        host.render(
            standbyTheme,
            state,
            standbyPhaseCopyTitle(snapshot, cause),
            standbyPhaseCopyEyebrow(snapshot, cause),
            standbyPhaseCopyDetail(snapshot, cause),
        )
    }

    /** Prototype phase title for the current phase, or the FAILED episode title. */
    private fun standbyPhaseCopyTitle(
        snapshot: SessionSnapshot,
        cause: me.trinitrix.mirax.session.PrePlayFailureCause?,
    ): String = if (cause != null) {
        getString(R.string.standby_foreground_phase_failed_title)
    } else {
        when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.standby_foreground_phase_off_title)
            ScreenPhase.ARMING -> getString(R.string.standby_foreground_phase_arming_title)
            ScreenPhase.ADVERTISING -> getString(R.string.standby_foreground_phase_waiting_title)
            ScreenPhase.CONNECTING -> getString(R.string.standby_foreground_phase_connecting_title)
            ScreenPhase.CONNECTED -> getString(R.string.standby_foreground_phase_projecting_title)
            ScreenPhase.WIFI_PAUSED -> getString(R.string.standby_foreground_phase_wifi_paused_title)
            ScreenPhase.FROZEN -> getString(R.string.standby_foreground_phase_frozen_title)
        }
    }

    /** Prototype phase eyebrow for the current phase, or the FAILED episode eyebrow. */
    private fun standbyPhaseCopyEyebrow(
        snapshot: SessionSnapshot,
        cause: me.trinitrix.mirax.session.PrePlayFailureCause?,
    ): String = if (cause != null) {
        getString(R.string.standby_foreground_phase_failed_eyebrow)
    } else {
        when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.standby_foreground_phase_off_eyebrow)
            ScreenPhase.ARMING -> getString(R.string.standby_foreground_phase_arming_eyebrow)
            ScreenPhase.ADVERTISING -> getString(R.string.standby_foreground_phase_waiting_eyebrow)
            ScreenPhase.CONNECTING -> getString(R.string.standby_foreground_phase_connecting_eyebrow)
            ScreenPhase.CONNECTED -> getString(R.string.standby_foreground_phase_projecting_eyebrow)
            ScreenPhase.WIFI_PAUSED -> getString(R.string.standby_foreground_phase_wifi_paused_eyebrow)
            ScreenPhase.FROZEN -> getString(R.string.standby_foreground_phase_frozen_eyebrow)
        }
    }

    /** Prototype phase detail for the current phase, or the provable FAILED text. */
    private fun standbyPhaseCopyDetail(
        snapshot: SessionSnapshot,
        cause: me.trinitrix.mirax.session.PrePlayFailureCause?,
    ): String = if (cause != null) {
        prePlayFailureText(cause)
    } else {
        when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.standby_foreground_phase_off_detail)
            ScreenPhase.ARMING -> getString(R.string.standby_foreground_phase_arming_detail)
            ScreenPhase.ADVERTISING -> getString(R.string.standby_foreground_phase_waiting_detail)
            ScreenPhase.CONNECTING -> getString(R.string.standby_foreground_phase_connecting_detail)
            ScreenPhase.CONNECTED -> ""
            ScreenPhase.WIFI_PAUSED -> getString(R.string.standby_foreground_phase_wifi_paused_detail)
            ScreenPhase.FROZEN -> getString(R.string.standby_foreground_phase_frozen_detail)
        }
    }

    private fun standbyStatusText(snapshot: SessionSnapshot): String {
        return when (snapshot.phase) {
            ScreenPhase.FROZEN -> getString(R.string.standby_phase_frozen)
            ScreenPhase.READY -> getString(R.string.standby_phase_ready)
            ScreenPhase.ARMING -> getString(R.string.standby_phase_arming)
            ScreenPhase.ADVERTISING -> getString(R.string.standby_phase_advertising)
            ScreenPhase.CONNECTING -> getString(R.string.standby_phase_connecting)
            ScreenPhase.CONNECTED -> getString(R.string.standby_phase_connected)
            ScreenPhase.WIFI_PAUSED -> getString(R.string.standby_phase_wifi_paused)
        }
    }

    private fun standbyFactsText(snapshot: SessionSnapshot): String {
        return getString(
            R.string.standby_facts,
            snapshot.effectiveBroadcastName.ifBlank { getString(R.string.fact_none) },
            standbyStatusText(snapshot),
        )
    }

    private fun clockText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 44f
            typeface = weight(700)
            setTextColor(getColor(R.color.mirax_on_surface))
            setLineSpacing(0f, 1.1f)
            contentDescription = getString(R.string.standby_clock_description, value)
        }
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
        column.addView(pageTitle(getString(R.string.nav_dashboard_legacy)).apply { tag = "legacyIdentity" })
        column.addView(phaseTitle(if (frozen) getString(R.string.waiting_title) else dashboardTitle(snapshot)).apply {
            tag = "phase"
        })
        column.addView(hint(if (frozen) getString(R.string.waiting_body) else dashboardHint(snapshot)).apply {
            tag = "hint"
        })
        if (frozen) {
            column.addView(linkButton(getString(R.string.frozen_dialog_title)) {
                showFrozenInfoDialog(MiraxApp.instance.session.snapshot())
            }.apply {
                tag = "frozenInfo"
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            })
            column.addView(linkButton(getString(R.string.waiting_retry)) { onRetry() }.apply {
                tag = "retry"
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            })
        }
        val isPaused = snapshot.phase == ScreenPhase.WIFI_PAUSED
        column.addView(broadcastButton(
            broadcastLabel(snapshot),
            enabled = !frozen,
            advertising = snapshot.advertisingEnabled || isPaused,
        ) {
            val live = MiraxApp.instance.session.snapshot()
            if (live.phase == ScreenPhase.FROZEN) {
                return@broadcastButton
            }
            if (live.phase == ScreenPhase.WIFI_PAUSED || live.advertisingEnabled) {
                render(SessionHost.setAdvertising(this, false))
            } else {
                render(SessionHost.setAdvertising(this, true))
            }
        }.apply { tag = "broadcast" })
        column.addView(linkButton(getString(R.string.dashboard_open_standby)) {
            openHome(Page.STANDBY)
        }.apply {
            tag = "openStandby"
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
        column.addView(jump(getString(R.string.history_entry), historyCount(snapshot), topDp = 24) {
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
            button.isEnabled = snapshot.phase != ScreenPhase.FROZEN
            styleBroadcast(button, snapshot.advertisingEnabled || snapshot.phase == ScreenPhase.WIFI_PAUSED)
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
            ScreenPhase.FROZEN -> getString(R.string.waiting_body)
            else -> getString(R.string.dashboard_hint_active)
        }
    }

    private fun broadcastLabel(snapshot: SessionSnapshot): String {
        return getString(
            if (snapshot.advertisingEnabled || snapshot.phase == ScreenPhase.WIFI_PAUSED) {
                R.string.stop_broadcast
            } else {
                R.string.start_broadcast
            },
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
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", resources.configuration.locales[0])
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
            label == "broadcast name" -> value
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
                    if (currentFocus === view && finishEditingBeforeNavigation()) {
                        render(MiraxApp.instance.session.snapshot())
                    }
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
        val themes = StandbySettingsPanelView(this).apply {
            tag = "standby-theme-settings"
            visibility = View.GONE
            showThemesOnly()
            onError = {
                Toast.makeText(this@MainActivity, R.string.standby_settings_theme_save_failed, Toast.LENGTH_LONG).show()
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    bindThemePanel(this@apply)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    if (boundThemePanel === this@apply) boundThemePanel = null
                }
            })
        }
        column.addView(themes, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(jump(getString(R.string.standby_media_import_title), "") {
            mediaPicker.launch(arrayOf("image/*", "video/*"))
        }.apply {
            tag = "standby-media-import"
            isEnabled = !mediaStaging && mediaEdit == null && mediaLease != null
        })
        column.addView(hint(getString(R.string.standby_media_importing)).apply {
            tag = "standby-media-importing"
            visibility = if (mediaStaging) View.VISIBLE else View.GONE
        })
        return column
    }

    private fun refreshThemePanel() {
        if (!::binding.isInitialized) return
        listOf(binding.pageStart, binding.pageEnd).forEach { pane ->
            pane.findViewWithTag<StandbySettingsPanelView>("standby-theme-settings")?.let(::bindThemePanel)
        }
    }

    private fun bindThemePanel(panel: StandbySettingsPanelView) {
        val selected = standbyTheme ?: return
        if (!activityStarted || instance !== this || !panel.isAttachedToWindow) return
        panel.render(StandbySettingsPanelModel(theme = selected))
        if (boundThemePanel !== panel) {
            boundThemePanel?.unbind()
            panel.bind(
                MiraxApp.instance.standbyThemeGateway,
                MiraxApp.instance.standbyLanguageGateway,
                lifecycleScope,
            )
            boundThemePanel = panel
        }
        panel.visibility = View.VISIBLE
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

    private fun bindLanguagePreference() {
        languageReady = false
        val revision = ++languageBindingRevision
        languageSubmission.bind(
            scope = lifecycleScope,
            onStateChanged = { model ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetLanguagePreference(model.language.toSessionPreference()))
                val current = session.snapshot()
                if (appliedAppLanguage == current.appLanguage) render(current)
            },
            onLocaleChange = {
                applyResolvedLanguage(MiraxApp.instance.session.snapshot().appLanguage)
            },
            onError = { failure ->
                if (failure !is LanguagePersistenceException || failure.uncertain) {
                    languageReady = false
                    renderLanguageChoice(languageSubmission.confirmed)
                }
                Toast.makeText(this, R.string.standby_settings_language_failed, Toast.LENGTH_SHORT).show()
            },
        )
        languageRestoreJob = lifecycleScope.launch {
            try {
                val choice = MiraxApp.instance.standbyLanguageGateway.currentChoice()
                coroutineContext.ensureActive()
                if (revision != languageBindingRevision || !activityStarted || instance !== this@MainActivity) return@launch
                languageReady = true
                languageSubmission.updateConfirmed(languageSubmission.confirmed.withLanguage(choice))
                val session = MiraxApp.instance.session
                val preference = choice.toSessionPreference()
                if (session.snapshot().languagePreference != preference) {
                    session.handle(SessionAction.SetLanguagePreference(preference))
                }
                val current = session.snapshot()
                if (appliedAppLanguage == current.appLanguage) {
                    render(current)
                } else {
                    applyResolvedLanguage(current.appLanguage)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("MainActivity", "Cannot restore language preference", failure)
                if (revision == languageBindingRevision && activityStarted && instance === this@MainActivity) {
                    languageReady = false
                    renderLanguageChoice(languageSubmission.confirmed)
                    Toast.makeText(this@MainActivity, R.string.standby_settings_language_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLanguageInputEnabled(enabled: Boolean) {
        languageInputEnabled = enabled
        renderLanguageChoice(languageSubmission.confirmed)
    }

    private fun renderLanguageChoice(model: StandbySettingsPanelModel) {
        val group = languageChoiceGroup ?: return
        val selected = if (languageReady) model.language.toSessionPreference().name else null
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? TextView ?: continue
            chip.isEnabled = languageReady && languageInputEnabled
            styleChip(chip, chip.tag == selected)
        }
    }

    private fun StandbyLanguage?.toSessionPreference(): LanguagePreference = when (this) {
        StandbyLanguage.TRADITIONAL_CHINESE -> LanguagePreference.TRADITIONAL_CHINESE
        StandbyLanguage.ENGLISH -> LanguagePreference.ENGLISH
        null -> LanguagePreference.FOLLOW_SYSTEM
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
                if (updatingControls || !languageReady ||
                    preference == MiraxApp.instance.session.snapshot().languagePreference
                ) {
                    return@choiceChip
                }
                val choice = when (preference) {
                    LanguagePreference.TRADITIONAL_CHINESE -> StandbyLanguage.TRADITIONAL_CHINESE
                    LanguagePreference.ENGLISH -> StandbyLanguage.ENGLISH
                    LanguagePreference.FOLLOW_SYSTEM -> null
                }
                languageSubmission.submit(
                    languageSubmission.confirmed.withLanguage(choice),
                    changesLocale = true,
                ) {
                    MiraxApp.instance.standbyLanguageGateway.selectLanguage(choice)
                }
            })
        }
        languageChoiceGroup = group
        renderLanguageChoice(languageSubmission.confirmed)
        column.addView(group)
        return column
    }

    private fun updateLanguage(root: View, snapshot: SessionSnapshot) {
        restyleChoices(root, "language", if (languageReady) snapshot.languagePreference.name else "")
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
                    if (currentFocus === view && finishEditingBeforeNavigation()) {
                        render(MiraxApp.instance.session.snapshot())
                    }
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

    /** Exact drawer inputs; the configuration is copied rather than retained from Resources. */
    private data class DrawerSignature(
        val content: List<String>,
        val selected: List<Boolean>,
        val configuration: Configuration,
        val density: Float,
        val direction: Int,
        val palette: List<Int>,
        val rippleStyle: List<DrawerStyleValue>,
    )

    /** Resolved theme values, without Drawable or mutable Theme identity in equality. */
    private data class DrawerStyleValue(
        val resolved: Boolean,
        val valueType: Int,
        val resourceId: Int,
        val data: Int,
    )

    /**
     * @param snapshot Current session data used by the unchanged drawer builders.
     * @return Exact content, ancestor-aware selection, and resolved rendering inputs.
     */
    private fun drawerSignature(snapshot: SessionSnapshot): DrawerSignature = DrawerSignature(
        content = listOf(
            getString(R.string.nav_standby), dashboardTitle(snapshot),
            getString(R.string.nav_dashboard_legacy), dashboardTitle(snapshot),
            getString(R.string.history_title), historyCount(snapshot),
            getString(R.string.nav_settings),
            getString(R.string.nav_general), snapshot.effectiveBroadcastName,
            getString(R.string.nav_picture), pictureDetail(snapshot),
            getString(R.string.nav_advanced), "",
            getString(R.string.nav_about_group),
            getString(R.string.nav_about), versionName(),
        ),
        selected = listOf(Page.STANDBY, Page.HISTORY, Page.GENERAL,
            Page.PICTURE, Page.ADVANCED, Page.ABOUT).map(::menuSelected),
        configuration = Configuration(resources.configuration),
        density = resources.displayMetrics.density,
        direction = binding.drawerBody.layoutDirection,
        palette = listOf(R.color.mirax_chip_on, R.color.mirax_chip, R.color.mirax_on_surface,
            R.color.mirax_hint, R.color.mirax_muted).map { getColor(it) },
        rippleStyle = listOf(android.R.attr.selectableItemBackground,
            android.R.attr.colorControlHighlight, androidx.appcompat.R.attr.colorControlHighlight).map { attribute ->
            val value = TypedValue()
            val resolved = theme.resolveAttribute(attribute, value, true)
            DrawerStyleValue(resolved, value.type, value.resourceId, value.data)
        },
    )

    /** @param snapshot Current drawer content; changed inputs rebuild immediately, even with focus. */
    private fun renderDrawer(snapshot: SessionSnapshot) {
        val drawer = binding.drawerBody
        val signature = drawerSignature(snapshot)
        if (drawer.getTag(R.id.page_signature) == signature) return
        drawer.setTag(R.id.page_signature, null)
        drawer.removeAllViews()
        drawer.addView(menuCard(Page.STANDBY, getString(R.string.nav_standby), dashboardTitle(snapshot), topDp = 0))
        drawer.addView(menuCard(Page.HISTORY, getString(R.string.history_title), historyCount(snapshot)))
        drawer.addView(drawerLabel(getString(R.string.nav_settings)))
        drawer.addView(menuCard(Page.GENERAL, getString(R.string.nav_general), snapshot.effectiveBroadcastName))
        drawer.addView(menuCard(Page.PICTURE, getString(R.string.nav_picture), pictureDetail(snapshot)))
        drawer.addView(menuCard(Page.ADVANCED, getString(R.string.nav_advanced), ""))
        drawer.addView(drawerLabel(getString(R.string.nav_about_group)))
        drawer.addView(menuCard(Page.ABOUT, getString(R.string.nav_about), versionName()))
        drawer.setTag(R.id.page_signature, signature)
    }

    private fun menuSelected(target: Page): Boolean {
        if (target == Page.STANDBY || target == Page.DASHBOARD) {
            return page == target
        }
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
        val isHome = target == Page.STANDBY || target == Page.DASHBOARD
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = rounded(if (selected) R.color.mirax_chip_on else R.color.mirax_chip, 16)
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = if (detail.isEmpty()) label else "$label, $detail"
            setOnClickListener { if (isHome) openHome(target) else openPage(target) }
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
        val count = snapshot.connectionRuns.size
        return resources.getQuantityString(R.plurals.history_count, count, count)
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
        private const val CLOCK_SKEW_MS = 50L
        private const val MIN_CLOCK_DELAY_MS = 1_000L
        private const val WIDE_BREAKPOINT_DP = 600
        private const val STATE_PAGE = "dashboard_page"
        private const val STATE_HOME = "dashboard_home"

        @Volatile
        private var showing: Boolean = false

        @Volatile
        private var instance: MainActivity? = null

        fun isShowing(): Boolean = showing

        fun refreshIfShowing() {
            val activity = instance ?: return
            activity.runOnUiThread { activity.renderFromHost() }
        }

        /**
         * Refreshes the current resolved failure presentation, including primary copy and scene flags.
         * Never re-runs Activity/session render, commit, broadcast, idle, or navigation work.
         */
        fun refreshPrePlayFailureOnly() {
            val activity = instance ?: return
            val view = activity as? AppCompatActivity ?: return
            if (view.isFinishing || view.isDestroyed) return
            view.runOnUiThread {
                if (view.isFinishing || view.isDestroyed) return@runOnUiThread
                activity.refreshPrePlayFailureViews()
            }
        }

        internal fun dialogHost(): MainActivity? {
            val activity = instance
            return if (activity != null && !activity.isFinishing) activity else null
        }
    }
}