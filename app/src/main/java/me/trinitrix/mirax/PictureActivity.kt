package me.trinitrix.mirax

import android.util.Log
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Rect
import android.graphics.RectF
import me.trinitrix.mirax.session.PicturePlacement
import me.trinitrix.mirax.session.ControlPanelEntry
import me.trinitrix.mirax.session.PictureContactState
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.PictureTouchMap
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.wfd.DebugOverlayView
import me.trinitrix.mirax.wfd.H264SurfaceDecoder
import me.trinitrix.mirax.wfd.MicrosoftCursorChannel
import me.trinitrix.mirax.wfd.SinkConnectionController
import me.trinitrix.mirax.wfd.UibcContact
import me.trinitrix.mirax.wfd.UibcPackets
import me.trinitrix.mirax.wfd.UibcPenContact
import me.trinitrix.mirax.wfd.UibcTouchChannel
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Fullscreen Miracast picture. Placement follows the session [me.trinitrix.mirax.session.PictureScale]
 * via [PicturePlacement]; views only render. Contacts on the picture are general
 * touch or S Pen when that switch is on. They do not open settings.
 *
 * System Back only closes the control panel; the selected explicit entry opens it.
 * This activity only renders [SessionSnapshot] outputs and forwards actions.
 */
class PictureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: ImageView
    private lateinit var controlButton: ProjectionControlButton
    private lateinit var phoneControls: ProjectionControlsLayer
    private var endConnectionDialog: AlertDialog? = null
    private lateinit var controlPanelRoot: FrameLayout
    private lateinit var controlPanelScrim: View
    private lateinit var controlPanelCardScrollView: ScrollView
    private lateinit var controlPanelCard: LinearLayout
    private lateinit var controlPanelPrimaryPage: LinearLayout
    private lateinit var controlPanelSettingsPage: LinearLayout
    private var showingSettingsPage = false
    private lateinit var controlPanelSourceName: TextView
    private lateinit var controlPanelResolutionValue: TextView
    private lateinit var controlPanelRefreshValue: TextView
    private lateinit var controlPanelOrientationSubtitle: TextView
    private lateinit var controlPanelRotateButton: MaterialButton
    private lateinit var controlPanelOrientationLockSwitch: MaterialSwitch
    private lateinit var controlPanelDebugOverlaySwitch: MaterialSwitch
    private lateinit var controlPanelTouchEnabledSwitch: MaterialSwitch
    private lateinit var controlPanelCameraCutoutSwitch: MaterialSwitch
    private lateinit var controlPanelEndButton: MaterialButton
    private val scaleButtons = mutableMapOf<PictureScale, MaterialButton>()
    private var lockedDirectionIsLandscape: Boolean = true
    /** Decoder buffer size for [SurfaceHolder.setFixedSize]; may be padded. */
    private var bufferW: Int = 0
    private var bufferH: Int = 0
    private var currentFixedW: Int = 0
    private var currentFixedH: Int = 0
    private val pictureContacts = PictureContactState(UibcPackets.MAX_CONTACTS, UibcTouchChannel.inputGeneration())
    private var lastPictureFrame: PictureFrame? = null
    private val visiblePictureBounds = Rect()
    private var lastInputAllowed = false

    /** Pixel and visible-viewport geometry for the current picture. */
    private data class PictureFrame(
        val pictureWidth: Int,
        val pictureHeight: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val visibleLeft: Int,
        val visibleTop: Int,
        val visibleRight: Int,
        val visibleBottom: Int,
    )
    private var cursorPictureX: Int = 0
    private var cursorPictureY: Int = 0
    private var cursorHotspotX: Int = 0
    private var cursorHotspotY: Int = 0
    private var cursorBitmapW: Int = 0
    private var cursorBitmapH: Int = 0
    private lateinit var debugOverlay: DebugOverlayView
    private val debugOverlayHandler = Handler(Looper.getMainLooper())
    private val debugOverlayRunnable = Runnable { updateDebugOverlayPeriodic() }
    private val pictureLayoutRunnable = Runnable { layoutPicture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = WeakReference(this)
        lockedDirectionIsLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            )
            isClickable = true
            setOnTouchListener { _, event ->
                forwardPictureTouch(event)
                true
            }
            setOnHoverListener { _, event ->
                forwardPictureHover(event)
                true
            }
        }
        stage.addView(surfaceView)
        cursorView = ImageView(this).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START)
        }
        stage.addView(cursorView)
        stage.isClickable = true

        phoneControls = ProjectionControlsLayer(this)
        stage.addView(phoneControls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        controlButton = ProjectionControlButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                MiraxUi.dp(this@PictureActivity, 48),
                MiraxUi.dp(this@PictureActivity, 56),
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = MiraxUi.dp(this@PictureActivity, 72)
            }
            setOnClickListener {
                if (MiraxApp.instance.session.snapshot().controlPanelEntry == ControlPanelEntry.FLOATING_BUTTON) {
                    openControlPanel()
                }
            }
            onPositionChanged = { endCoveredContacts() }
        }
        phoneControls.addView(controlButton)
        phoneControls.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                controlButton.constrainToParent()
            }
        }

        buildControlPanel()
        stage.addView(
            controlPanelRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(stage)

        debugOverlay = DebugOverlayView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                rightMargin = MiraxUi.dp(this@PictureActivity, 16)
                topMargin = MiraxUi.dp(this@PictureActivity, 16)
            }
            visibility = View.GONE
        }
        phoneControls.addView(debugOverlay)
        debugOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> endCoveredContacts() }
        debugOverlay.onPositionChanged = { endCoveredContacts() }
        phoneControls.bringChildToFront(controlButton)
        stage.bringChildToFront(controlPanelRoot)

        surfaceView.holder.addCallback(this)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutPicture()
            updateGestureExclusion()
        }
        ViewCompat.setOnApplyWindowInsetsListener(stage) { view, insets ->
            applyProjectionCutoutInsets(view, insets)
            insets
        }

        SinkConnectionController.decoder.onFormat = { w, h, _ ->
            runOnUiThread {
                bufferW = w
                bufferH = h
                layoutPicture()
            }
        }
        if (WfdCapabilityTable.ADVERTISE_HARDWARE_CURSOR) {
            MicrosoftCursorChannel.onPosition = { x, y ->
                cursorPictureX = x
                cursorPictureY = y
                layoutCursor()
            }
            MicrosoftCursorChannel.onBitmap = { bitmap, hotspotX, hotspotY ->
                applyCursorBitmap(bitmap, hotspotX, hotspotY)
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showingSettingsPage) {
                        showSettingsPage(false)
                    } else {
                        applySession(SessionHost.onSystemBack(this@PictureActivity))
                    }
                }
            },
        )

        val mockSession = intent.getBooleanExtra("mirax_mock_session", false) && isDebuggable
        if (mockSession) {
            SessionHost.enterMockConnected(this)
            MiraxApp.instance.session.handle(SessionAction.ToggleControlPanel)
            SessionHost.commit(this)
        }
        hideSystemBars()
        applySession(MiraxApp.instance.session.snapshot())
        if (!mockSession) {
            startDebugOverlayUpdates()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
        updateGestureExclusion()
    }

    /** Open the panel once; key repeats must not toggle it closed again. */
    private fun openControlPanel() {
        val snapshot = MiraxApp.instance.session.snapshot()
        if (snapshot.showPicture && !snapshot.controlPanelExpanded) {
            applySession(SessionHost.toggleControlPanel(this))
        }
    }

    /** @return whether this key belongs to the foreground projection's panel shortcut */
    private fun isProjectionVolumeKey(keyCode: Int): Boolean =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            MiraxApp.instance.session.snapshot().let {
                it.showPicture && it.controlPanelEntry == ControlPanelEntry.VOLUME_KEYS
            }

    /**
     * Defer volume adjustment until release so a long press changes no volume.
     *
     * @param keyCode Android key code
     * @param event key event used by Android's long-press tracking
     * @return true for a projection volume key, otherwise the platform result
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isProjectionVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) event.startTracking()
        return true
    }

    /**
     * Use Android's tracked long press rather than a second gesture timer.
     *
     * @param keyCode Android key code
     * @param event tracked long-press event
     * @return true when the panel shortcut consumes this press
     */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isProjectionVolumeKey(keyCode)) return super.onKeyLongPress(keyCode, event)
        openControlPanel()
        return true
    }

    /**
     * Preserve one ordinary volume step for a short, uncanceled key press.
     *
     * @param keyCode Android key code
     * @param event release marked canceled by Android after a handled long press
     * @return true for a projection volume key, otherwise the platform result
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isProjectionVolumeKey(keyCode)) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) {
            val audio = getSystemService(AudioManager::class.java)
            if (audio == null) {
                Log.w("PictureActivity", "Audio service unavailable for volume adjustment")
            } else {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                try {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                } catch (error: SecurityException) {
                    Log.w("PictureActivity", "System policy rejected volume adjustment", error)
                }
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Projection is foreground again — hide the ball without ending the connection.
        val session = MiraxApp.instance.session
        if (session.snapshot().orientationLockEnabled && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            lockedDirectionIsLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        if (session.snapshot().showFloatingBall) {
            applySession(SessionHost.openedMiraxDashboard(this))
        } else {
            applySession(session.snapshot())
        }
        startDebugOverlayUpdates()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home / Recents leave. Opening Mirax's own dashboard is reported from MainActivity.
        if (!isChangingConfigurations && !MainActivity.isShowing()) {
            SessionHost.leftProjectionToHome(this)
        }
    }

    override fun onStop() {
        stage.removeCallbacks(pictureLayoutRunnable)
        endPictureContacts()
        pictureContacts.cancelAll()
        stage.systemGestureExclusionRects = emptyList()
        super.onStop()
        stopDebugOverlayUpdates()
        if (isChangingConfigurations || isFinishing) {
            return
        }
        // Fallback when onUserLeaveHint did not run (some OEM home gestures).
        if (!MainActivity.isShowing()) {
            val snap = MiraxApp.instance.session.snapshot()
            if (snap.phase == ScreenPhase.CONNECTED && !snap.showFloatingBall) {
                SessionHost.leftProjectionToHome(this)
            }
        }
    }

    /** Rebuild the projection layout and controls after device rotation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!MiraxApp.instance.session.snapshot().orientationLockEnabled) {
            lockedDirectionIsLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        hideSystemBars()
        MiraxApp.instance.session.report(
            PictureRotationReport(SessionHost.pictureRotationDegrees(this, displayId = null)),
        )
        stage.removeView(controlPanelRoot)
        buildControlPanel()
        stage.addView(
            controlPanelRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        stage.bringChildToFront(controlPanelRoot)
        applySession(MiraxApp.instance.session.snapshot())
    }

    override fun onDestroy() {
        stage.removeCallbacks(pictureLayoutRunnable)
        endPictureContacts()
        debugOverlay.onPositionChanged = null
        controlButton.onPositionChanged = null
        stopDebugOverlayUpdates()
        endConnectionDialog?.dismiss()
        endConnectionDialog = null
        if (showing?.get() === this) {
            showing = null
        }
        if (WfdCapabilityTable.ADVERTISE_HARDWARE_CURSOR) {
            MicrosoftCursorChannel.onPosition = null
            MicrosoftCursorChannel.onBitmap = null
        }
        SinkConnectionController.decoder.attachSurface(null)
        SinkConnectionController.decoder.onFormat = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        SinkConnectionController.decoder.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        SinkConnectionController.decoder.attachSurface(holder.surface)
        layoutPicture()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        endPictureContacts()
        SinkConnectionController.decoder.attachSurface(null)
    }

    private fun applySession(snapshot: SessionSnapshot) {
        val inputAllowed = canForwardPictureInput(snapshot)
        if (!inputAllowed) {
            endPictureContacts()
        }
        lastInputAllowed = inputAllowed
        if (!snapshot.showPicture) {
            finish()
            return
        }
        renderControlPanel(snapshot)
        ViewCompat.requestApplyInsets(stage)
        layoutPicture()

        val showDebugOverlay = snapshot.showDebugOverlay
        debugOverlay.visibility = if (showDebugOverlay) View.VISIBLE else View.GONE
        if (showDebugOverlay) {
            endCoveredContacts()
            startDebugOverlayUpdates()
            updateDebugOverlay(snapshot)
        } else {
            stopDebugOverlayUpdates()
        }
    }

    /**
     * Applies orientation lock to the activity based on the user preference.
     * When enabled, locks the screen to the selected orientation (landscape or portrait).
     * When disabled, allows the screen to rotate freely following device sensor.
     */
    private fun applyOrientationLock(orientationLockEnabled: Boolean) {
        if (orientationLockEnabled) {
            requestedOrientation = if (lockedDirectionIsLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyProjectionCutoutInsets(view: View, insets: WindowInsetsCompat) {
        val avoidCutout = MiraxApp.instance.session.snapshot().cameraCutoutAffectsLayout
        val padding = if (avoidCutout) {
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            Rect(cutout.left, cutout.top, cutout.right, cutout.bottom)
        } else {
            Rect()
        }
        if (view.paddingLeft == padding.left && view.paddingTop == padding.top &&
            view.paddingRight == padding.right && view.paddingBottom == padding.bottom
        ) {
            return
        }
        endPictureContacts()
        view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        view.post { layoutPicture() }
    }

    /** Build the projection controls for the current screen orientation. */
    private fun buildControlPanel() {
        fun dp(value: Int): Int = MiraxUi.dp(this, value)

        controlPanelRoot = FrameLayout(this).apply {
            visibility = View.GONE
        }

        controlPanelScrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(MiraxUi.scrimColor(this@PictureActivity))
            isClickable = true
            setOnClickListener {
                applySession(SessionHost.collapseControlPanel(this@PictureActivity))
            }
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cardWidth = if (isLandscape) {
            minOf(dp(640), resources.displayMetrics.widthPixels * 4 / 5)
        } else {
            minOf(dp(360), resources.displayMetrics.widthPixels - dp(32))
        }
        val cardHeight = if (isLandscape) {
            minOf(dp(400), resources.displayMetrics.heightPixels * 3 / 4)
        } else {
            FrameLayout.LayoutParams.WRAP_CONTENT
        }

        controlPanelCardScrollView = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (!isLandscape) {
                background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_surface, 16, R.color.mirax_stroke, 1)
            }
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                cardHeight,
                Gravity.CENTER,
            ).apply {
                val margin = dp(16)
                leftMargin = margin
                rightMargin = margin
                topMargin = margin
                bottomMargin = margin
            }
        }

        controlPanelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), if (isLandscape) 0 else dp(18), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(8)
            }
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_accent, 4)
        }
        val titleText = TextView(this).apply {
            text = getString(R.string.control_panel_casting_status)
            textSize = 17f
            typeface = MiraxUi.weight(700)
            setTextColor(getColor(R.color.mirax_on_surface))
        }
        titleContainer.addView(dotView)
        titleContainer.addView(titleText)
        topRow.addView(titleContainer)

        val closeButton = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "✕"
            contentDescription = getString(R.string.control_panel_close)
            textSize = 15f
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                applySession(SessionHost.collapseControlPanel(this@PictureActivity))
            }
        }
        topRow.addView(closeButton)
        if (!isLandscape) {
            controlPanelCard.addView(topRow)
        }

        // Columns layout: Summary (left) + Controls (right) in landscape
        val columnsLayout = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Left: Summary column
        val summaryColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isLandscape) {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(16)
                }
            } else {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) }
            }
        }

        val statsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_chip, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        statsBox.addView(frameLabel(getString(R.string.control_panel_source_device)))
        controlPanelSourceName = valueText()
        statsBox.addView(controlPanelSourceName)

        statsBox.addView(frameLabel(getString(R.string.display_card_resolution_label), topPadDp = 12))
        controlPanelResolutionValue = valueText()
        statsBox.addView(controlPanelResolutionValue)

        statsBox.addView(frameLabel(getString(R.string.control_panel_refresh_rate), topPadDp = 12))
        controlPanelRefreshValue = valueText()
        statsBox.addView(controlPanelRefreshValue)

        summaryColumn.addView(statsBox)
        columnsLayout.addView(summaryColumn)

        val midDivider = View(this).apply {
            setBackgroundColor(getColor(R.color.mirax_stroke))
            if (isLandscape) {
                layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    rightMargin = dp(16)
                }
            } else {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    bottomMargin = dp(14)
                }
            }
        }
        columnsLayout.addView(midDivider)

        val controlsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isLandscape) {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            } else {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }

        val scaleHeader = TextView(this).apply {
            text = getString(R.string.control_panel_scale_title)
            textSize = 12f
            typeface = MiraxUi.weight(600)
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        controlsColumn.addView(scaleHeader)

        val scaleGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val scaleList = listOf(
            getString(R.string.control_panel_scale_fit) to PictureScale.PROPORTIONAL,
            getString(R.string.control_panel_scale_crop) to PictureScale.CENTER_CROP,
            getString(R.string.control_panel_scale_stretch) to PictureScale.MATCH_EDGES,
            getString(R.string.control_panel_scale_original) to PictureScale.ACTUAL,
        )
        val scaleRows = scaleList.chunked(2)
        scaleButtons.clear()
        for ((rowIndex, rowPairs) in scaleRows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) topMargin = dp(6)
                }
            }
            for ((colIndex, pair) in rowPairs.withIndex()) {
                val (label, scale) = pair
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    textSize = 13f
                    cornerRadius = dp(12)
                    minHeight = dp(40)
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (colIndex < rowPairs.lastIndex) rightMargin = dp(6)
                    }
                    setOnClickListener {
                        applySession(SessionHost.setPictureScale(this@PictureActivity, scale))
                    }
                }
                scaleButtons[scale] = btn
                row.addView(btn)
            }
            scaleGrid.addView(row)
        }
        controlsColumn.addView(scaleGrid)

        controlsColumn.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setBackgroundColor(getColor(R.color.mirax_stroke))
        })

        val orientationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        val orientationTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val orientationTitle = TextView(this).apply {
            text = getString(R.string.control_panel_lock_orientation)
            textSize = 15f
            setTextColor(getColor(R.color.mirax_on_surface))
        }
        controlPanelOrientationSubtitle = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
        }
        orientationTextContainer.addView(orientationTitle)
        orientationTextContainer.addView(controlPanelOrientationSubtitle)
        orientationRow.addView(orientationTextContainer)

        val orientationActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlPanelRotateButton = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "↻"
            contentDescription = getString(R.string.control_panel_rotate)
            textSize = 18f
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.mirax_accent))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                lockedDirectionIsLandscape = !lockedDirectionIsLandscape
                applyOrientationLock(true)
                renderControlPanel(MiraxApp.instance.session.snapshot())
            }
        }
        orientationActions.addView(controlPanelRotateButton)

        controlPanelOrientationLockSwitch = MaterialSwitch(this).apply {
            contentDescription = getString(R.string.control_panel_lock_orientation)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    lockedDirectionIsLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
                applyOrientationLock(isChecked)
                val snapshot = SessionHost.toggleOrientationLock(this@PictureActivity, isChecked)
                applySession(snapshot)
            }
        }
        orientationActions.addView(controlPanelOrientationLockSwitch)
        orientationRow.addView(orientationActions)
        val primarySettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        primarySettings.addView(orientationRow)
        primarySettings.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.control_panel_other_settings)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { showSettingsPage(true) }
        })
        val settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        settingsPage.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "← ${getString(R.string.nav_back)}"
            contentDescription = getString(R.string.nav_back)
            minHeight = dp(48)
            setOnClickListener { showSettingsPage(false) }
        })
        settingsPage.addView(frameLabel(getString(R.string.control_panel_other_settings)))

        settingsPage.addView(createSwitchRow(getString(R.string.control_panel_debug_overlay)) { switch ->
            controlPanelDebugOverlaySwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetShowDebugOverlay(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        settingsPage.addView(createSwitchRow(getString(R.string.control_panel_touch_input)) { switch ->
            controlPanelTouchEnabledSwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetTouchEnabled(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        settingsPage.addView(createSwitchRow(getString(R.string.control_panel_avoid_cutout)) { switch ->
            controlPanelCameraCutoutSwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetCameraCutoutAffectsLayout(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        controlsColumn.addView(primarySettings)
        if (!isLandscape) {
            columnsLayout.addView(controlsColumn)
        }
        val pages = FrameLayout(this).apply {
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        controlPanelPrimaryPage = if (isLandscape) controlsColumn else columnsLayout
        pages.addView(
            controlPanelPrimaryPage,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        pages.addView(
            settingsPage,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        if (isLandscape) {
            columnsLayout.addView(pages)
            controlPanelCard.addView(columnsLayout)
        } else {
            controlPanelCard.addView(pages)
        }
        controlPanelSettingsPage = settingsPage
        showSettingsPage(showingSettingsPage)

        controlPanelEndButton = MiraxUi.stopActionButton(this, getString(R.string.end_connection_confirm_action)) {
            applySession(SessionHost.requestEndConnection(this@PictureActivity))
        }
        if (!isLandscape) {
            controlPanelCard.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                }
                setBackgroundColor(getColor(R.color.mirax_stroke))
            })

            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            bottomRow.addView(controlPanelEndButton)
            controlPanelCard.addView(bottomRow)
        }

        controlPanelCardScrollView.addView(controlPanelCard)
        controlPanelRoot.addView(controlPanelScrim)
        if (isLandscape) {
            // Use the landscape width for actions; only the settings need to scroll.
            val cardContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = controlPanelCardScrollView.layoutParams
                background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_surface, 16, R.color.mirax_stroke, 1)
                clipToOutline = true
            }
            controlPanelEndButton.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(56),
            ).apply { marginEnd = dp(12) }
            topRow.addView(controlPanelEndButton, 1)
            topRow.setPadding(dp(20), dp(18), dp(20), 0)
            cardContainer.addView(topRow)
            controlPanelCardScrollView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            cardContainer.addView(controlPanelCardScrollView)
            controlPanelRoot.addView(cardContainer)
        } else {
            controlPanelRoot.addView(controlPanelCardScrollView)
        }
    }

    /**
     * Create a labeled switch row in the control panel.
     *
     * @param label visible switch label
     * @param onInit configures the switch before the row is returned
     * @return the switch and label container
     */
    private fun createSwitchRow(label: String, onInit: (MaterialSwitch) -> Unit): LinearLayout {
        fun dp(v: Int): Int = MiraxUi.dp(this, v)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            addView(TextView(this@PictureActivity).apply {
                text = label
                textSize = 15f
                setTextColor(getColor(R.color.mirax_on_surface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val switch = MaterialSwitch(this@PictureActivity)
            switch.contentDescription = label
            onInit(switch)
            addView(switch)
        }
    }

    private fun frameLabel(text: String, topPadDp: Int = 0): TextView {
        val topPad = MiraxUi.dp(this, topPadDp)
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = MiraxUi.weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_muted))
            setPadding(0, topPad, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = MiraxUi.dp(this@PictureActivity, 6) }
        }
    }

    private fun valueText(): TextView {
        return TextView(this).apply {
            textSize = 17f
            typeface = MiraxUi.weight(650)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    fun showEndConnectionConfirm(onConfirmed: () -> Unit) {
        if (isFinishing) {
            return
        }
        if (endConnectionDialog?.isShowing == true) {
            return
        }
        endPictureContacts()
        endConnectionDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.end_connection_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.end_connection_confirm_action) { _, _ ->
                onConfirmed()
            }
            .create()
        endConnectionDialog?.show()
    }

    /** Render control visibility and switch values from [snapshot]. */
    private fun renderControlPanel(snapshot: SessionSnapshot) {
        applyOrientationLock(snapshot.orientationLockEnabled)
        controlButton.visibility = if (snapshot.controlPanelExpanded || snapshot.controlPanelEntry != ControlPanelEntry.FLOATING_BUTTON) {
            View.GONE
        } else {
            View.VISIBLE
        }
        phoneControls.visibility = if (!snapshot.controlPanelExpanded &&
            (snapshot.controlPanelEntry == ControlPanelEntry.FLOATING_BUTTON || snapshot.showDebugOverlay)
        ) View.VISIBLE else View.GONE
        if (!snapshot.controlPanelExpanded) {
            showSettingsPage(false)
            controlPanelRoot.visibility = View.GONE
            endCoveredContacts()
            updateGestureExclusion()
            return
        }
        if (controlPanelRoot.visibility != View.VISIBLE) {
            endPictureContacts()
        }
        val sourceName = snapshot.sourceDeviceName ?: getString(R.string.control_panel_unknown_source)
        controlPanelSourceName.text = sourceName
        controlPanelResolutionValue.text = snapshot.handleResolutionText
        val refresh = snapshot.handleRefreshRateHz
        controlPanelRefreshValue.text = if (refresh != null) {
            getString(R.string.handle_refresh_value, refresh)
        } else {
            ""
        }
        for ((scale, button) in scaleButtons) {
            val active = scale == snapshot.pictureScale
            val bg = if (active) R.color.mirax_accent else android.R.color.transparent
            val fg = if (active) R.color.mirax_on_accent else R.color.mirax_on_surface
            button.backgroundTintList = ColorStateList.valueOf(getColor(bg))
            button.setTextColor(getColor(fg))
        }
        if (::controlPanelOrientationSubtitle.isInitialized) {
            val isLandscape = if (snapshot.orientationLockEnabled) {
                lockedDirectionIsLandscape
            } else {
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val direction = if (isLandscape) {
                getString(R.string.control_panel_landscape)
            } else {
                getString(R.string.control_panel_portrait)
            }
            controlPanelOrientationSubtitle.text = getString(
                if (snapshot.orientationLockEnabled) R.string.control_panel_orientation_locked
                else R.string.control_panel_orientation_following,
                direction,
            )
        }
        if (::controlPanelRotateButton.isInitialized) {
            controlPanelRotateButton.visibility =
                if (snapshot.orientationLockEnabled) View.VISIBLE else View.GONE
        }
        if (::controlPanelOrientationLockSwitch.isInitialized && controlPanelOrientationLockSwitch.isChecked != snapshot.orientationLockEnabled) {
            controlPanelOrientationLockSwitch.isChecked = snapshot.orientationLockEnabled
        }
        if (::controlPanelDebugOverlaySwitch.isInitialized && controlPanelDebugOverlaySwitch.isChecked != snapshot.showDebugOverlay) {
            controlPanelDebugOverlaySwitch.isChecked = snapshot.showDebugOverlay
        }
        if (::controlPanelTouchEnabledSwitch.isInitialized && controlPanelTouchEnabledSwitch.isChecked != snapshot.touchEnabled) {
            controlPanelTouchEnabledSwitch.isChecked = snapshot.touchEnabled
        }
        if (::controlPanelCameraCutoutSwitch.isInitialized && controlPanelCameraCutoutSwitch.isChecked != snapshot.cameraCutoutAffectsLayout) {
            controlPanelCameraCutoutSwitch.isChecked = snapshot.cameraCutoutAffectsLayout
        }
        controlPanelRoot.visibility = if (snapshot.controlPanelExpanded) View.VISIBLE else View.GONE
        updateGestureExclusion()
    }

    /** Switch the right-hand pane in landscape, or the whole body in portrait. */
    private fun showSettingsPage(show: Boolean) {
        controlPanelCardScrollView.scrollTo(0, 0)
        showingSettingsPage = show
        controlPanelPrimaryPage.visibility = if (show) View.INVISIBLE else View.VISIBLE
        controlPanelSettingsPage.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    private fun layoutPicture() {
        if (stage.isInLayout) {
            // Apply child parameters after the parent traversal so measured bounds can catch up.
            stage.removeCallbacks(pictureLayoutRunnable)
            stage.post(pictureLayoutRunnable)
            return
        }
        if (stage.width <= 0 || stage.height <= 0) {
            endPictureContacts()
            lastPictureFrame = null
            return
        }
        val snapshot = MiraxApp.instance.session.snapshot()
        val picture = snapshot.selectedMode ?: run {
            endPictureContacts()
            lastPictureFrame = null
            return
        }
        val pictureW = picture.width
        val pictureH = picture.height
        if (pictureW <= 0 || pictureH <= 0) {
            endPictureContacts()
            lastPictureFrame = null
            return
        }
        val rect = PicturePlacement.place(
            pictureWidth = pictureW,
            pictureHeight = pictureH,
            panelWidth = stage.width - stage.paddingLeft - stage.paddingRight,
            panelHeight = stage.height - stage.paddingTop - stage.paddingBottom,
            scale = snapshot.pictureScale,
        )
        val targetW = maxOf(1, rect.width)
        val targetH = maxOf(1, rect.height)
        val frame = PictureFrame(
            pictureWidth = pictureW,
            pictureHeight = pictureH,
            left = rect.left,
            top = rect.top,
            width = targetW,
            height = targetH,
            visibleLeft = stage.paddingLeft,
            visibleTop = stage.paddingTop,
            visibleRight = stage.width - stage.paddingRight,
            visibleBottom = stage.height - stage.paddingBottom,
        )
        if (lastPictureFrame != null && lastPictureFrame != frame) {
            endPictureContacts()
        }
        lastPictureFrame = frame
        val currentParams = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (currentParams == null ||
            currentParams.width != targetW ||
            currentParams.height != targetH ||
            currentParams.leftMargin != rect.left ||
            currentParams.topMargin != rect.top
        ) {
            val params = FrameLayout.LayoutParams(
                targetW,
                targetH,
                Gravity.TOP or Gravity.START,
            )
            params.leftMargin = rect.left
            params.topMargin = rect.top
            surfaceView.layoutParams = params
        }
        val fixedW = if (bufferW > 0) bufferW else pictureW
        val fixedH = if (bufferH > 0) bufferH else pictureH
        if (fixedW != currentFixedW || fixedH != currentFixedH) {
            currentFixedW = fixedW
            currentFixedH = fixedH
            surfaceView.holder.setFixedSize(fixedW, fixedH)
        }
        layoutCursor()
    }

    /** Release locally owned pointers before the picture or its input state changes. */
    private fun endPictureContacts() {
        if (pictureContacts.hasContacts() || pictureContacts.hasPenHover()) {
            UibcTouchChannel.liftAll()
        }
        pictureContacts.endAll()
    }

    /**
     * A completed Android gesture may restart after a phone-only input transition.
     *
     * @param event final Android event for the gesture or one lifted pointer
     */
    private fun finishPictureGesture(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pictureContacts.cancelAll()
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            pictureContacts.finishGesture()
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            val pointerId = event.getPointerId(event.actionIndex)
            pictureContacts.touchUp(pointerId)
            pictureContacts.penUp(pointerId)
        }
    }

    /** Observe a transport reset before accepting another Android motion sample. */
    private fun observeInputGeneration() {
        pictureContacts.observeInputGeneration(UibcTouchChannel.inputGeneration())
    }

    /**
     * Only an available HIDC socket can own new Android picture contacts.
     *
     * @param snapshot current session preferences and negotiated picture mode
     * @return true when new input can reach the current UIBC channel
     */
    private fun canForwardPictureInput(snapshot: SessionSnapshot): Boolean =
        snapshot.touchEnabled && snapshot.showPicture && snapshot.selectedMode != null &&
            UibcTouchChannel.canForwardInput()

    /** @return all visible phone control bounds in picture-view coordinates */
    private fun phoneControlBounds(): List<RectF> {
        val location = IntArray(2)
        surfaceView.getLocationInWindow(location)
        val surfaceX = location[0]
        val surfaceY = location[1]
        fun bounds(view: View): RectF? {
            if (!view.isShown || view.width <= 0) return null
            view.getLocationInWindow(location)
            val left = (location[0] - surfaceX).toFloat()
            val top = (location[1] - surfaceY).toFloat()
            return RectF(left, top, left + view.width, top + view.height)
        }
        return listOfNotNull(
            if (::debugOverlay.isInitialized) bounds(debugOverlay) else null,
            if (::controlButton.isInitialized) bounds(controlButton) else null,
        )
    }

    /** Only contacts underneath the moved phone control lose Windows ownership. */
    private fun endCoveredContacts() {
        val bounds = phoneControlBounds()
        if (bounds.isEmpty()) return
        val ended = pictureContacts.endCovered { x, y -> bounds.any { it.contains(x, y) } }
        if (ended.touchSlots.isNotEmpty() || ended.pen) {
            UibcTouchChannel.releaseCovered(ended.touchSlots, ended.pen)
        }
    }

    /**
     * A contact outside the visible viewport or under a phone control is not on the picture.
     *
     * @param x local X in picture-view coordinates
     * @param y local Y in picture-view coordinates
     * @return true only for an uncovered, visible picture sample
     */
    private fun isInsideVisiblePicture(x: Float, y: Float): Boolean {
        if (controlPanelRoot.visibility == View.VISIBLE || !surfaceView.getLocalVisibleRect(visiblePictureBounds)) {
            return false
        }
        if (x < visiblePictureBounds.left || x >= visiblePictureBounds.right ||
            y < visiblePictureBounds.top || y >= visiblePictureBounds.bottom
        ) {
            return false
        }
        return phoneControlBounds().none { it.contains(x, y) }
    }

    private fun applyCursorBitmap(bitmap: Bitmap?, hotspotX: Int, hotspotY: Int) {
        if (bitmap == null) {
            cursorBitmapW = 0
            cursorBitmapH = 0
            cursorView.setImageDrawable(null)
            cursorView.visibility = View.GONE
            return
        }
        cursorHotspotX = hotspotX
        cursorHotspotY = hotspotY
        cursorBitmapW = bitmap.width
        cursorBitmapH = bitmap.height
        cursorView.setImageBitmap(bitmap)
        cursorView.visibility = View.VISIBLE
        layoutCursor()
    }

    /**
     * Place the hardware-cursor overlay in panel space from picture-pixel coords.
     * Pointer motion arrives on UDP and must not wait for the next H.264 frame.
     */
    private fun layoutCursor() {
        if (!WfdCapabilityTable.ADVERTISE_HARDWARE_CURSOR) {
            return
        }
        if (cursorView.visibility != View.VISIBLE || cursorBitmapW <= 0 || cursorBitmapH <= 0) {
            return
        }
        val mode = MiraxApp.instance.session.snapshot().selectedMode ?: return
        val pictureW = mode.width
        val pictureH = mode.height
        val surfaceW = surfaceView.width
        val surfaceH = surfaceView.height
        if (pictureW <= 0 || pictureH <= 0 || surfaceW <= 0 || surfaceH <= 0) {
            return
        }
        val scaleX = surfaceW.toFloat() / pictureW.toFloat()
        val scaleY = surfaceH.toFloat() / pictureH.toFloat()
        val width = maxOf(1, (cursorBitmapW * scaleX).roundToInt())
        val height = maxOf(1, (cursorBitmapH * scaleY).roundToInt())
        val left = surfaceView.left +
            (cursorPictureX * scaleX - cursorHotspotX * scaleX).roundToInt()
        val top = surfaceView.top +
            (cursorPictureY * scaleY - cursorHotspotY * scaleY).roundToInt()
        val params = cursorView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START)
        if (params.width != width ||
            params.height != height ||
            params.leftMargin != left ||
            params.topMargin != top
        ) {
            params.width = width
            params.height = height
            params.leftMargin = left
            params.topMargin = top
            params.gravity = Gravity.TOP or Gravity.START
            cursorView.layoutParams = params
        }
    }

    private fun forwardPictureHover(event: MotionEvent) {
        observeInputGeneration()
        val snapshot = MiraxApp.instance.session.snapshot()
        val mode = snapshot.selectedMode
        val inputAllowed = canForwardPictureInput(snapshot)
        if (!lastInputAllowed && inputAllowed) {
            endPictureContacts()
        }
        lastInputAllowed = inputAllowed
        if (!inputAllowed || mode == null || controlPanelRoot.visibility == View.VISIBLE) {
            endPictureContacts()
            return
        }
        val index = event.actionIndex
        if (!isStylus(event, index)) {
            return
        }
        val isEraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val barrel = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        Log.v("PictureHover", "hover: action=${event.actionMasked} barrel=$barrel eraser=$isEraser")
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!pictureContacts.penHover(
                        isInsideVisiblePicture(event.getX(index), event.getY(index)),
                        event.getX(index), event.getY(index),
                        entering = event.actionMasked == MotionEvent.ACTION_HOVER_ENTER,
                    )) {
                    releaseOutsideContacts()
                    return
                }
                val x = PictureTouchMap.clampedPixel(event.getX(index), surfaceView.width, mode.width) ?: return
                val y = PictureTouchMap.clampedPixel(event.getY(index), surfaceView.height, mode.height) ?: return
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = x,
                        y = y,
                        tip = false,
                        inRange = true,
                        barrel = barrel,
                        eraser = isEraser,
                        pressure = 0,
                    ),
                )
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val wasHovering = pictureContacts.penHoverExit()
                if (!wasHovering) {
                    return
                }
                val x = PictureTouchMap.clampedPixel(event.getX(index), surfaceView.width, mode.width) ?: 0
                val y = PictureTouchMap.clampedPixel(event.getY(index), surfaceView.height, mode.height) ?: 0
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = x,
                        y = y,
                        tip = false,
                        inRange = false,
                        barrel = false,
                        eraser = false,
                        pressure = 0,
                    ),
                )
            }
        }
    }

    private fun forwardPictureTouch(event: MotionEvent) {
        observeInputGeneration()
        val snapshot = MiraxApp.instance.session.snapshot()
        val mode = snapshot.selectedMode
        val inputAllowed = canForwardPictureInput(snapshot)
        if (!lastInputAllowed && inputAllowed) {
            endPictureContacts()
        }
        lastInputAllowed = inputAllowed
        Log.d("MiraxTouch", "forwardPictureTouch: action=${event.actionMasked} inputAllowed=$inputAllowed mode=$mode")
        if (!inputAllowed || mode == null || controlPanelRoot.visibility == View.VISIBLE) {
            endPictureContacts()
            finishPictureGesture(event)
            return
        }

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            endPictureContacts()
            pictureContacts.cancelAll()
            return
        }

        var hasStylus = false
        for (index in 0 until event.pointerCount) {
            if (isStylus(event, index)) {
                hasStylus = true
                val pointerId = event.getPointerId(index)
                val isTarget = event.actionIndex == index
                val insidePicture = isInsideVisiblePicture(event.getX(index), event.getY(index))
                if (isTarget && (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
                    pictureContacts.penDown(pointerId, insidePicture)
                }
                if (pictureContacts.penMove(pointerId, insidePicture, event.getX(index), event.getY(index))) {
                    forwardStylusTouch(event, mode, index)
                }
                if (isTarget && (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_POINTER_UP)) {
                    pictureContacts.penUp(pointerId)
                }
            }
        }
        if (hasStylus && event.pointerCount == 1) {
            releaseOutsideContacts()
            finishPictureGesture(event)
            return
        }

        Log.v("PictureTouch", "touch: action=${event.actionMasked} count=${event.pointerCount}")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!isStylus(event, index)) {
                    pictureContacts.touchDown(
                        event.getPointerId(index), isInsideVisiblePicture(event.getX(index), event.getY(index)),
                        UibcTouchChannel.touchSlotLimit(),
                    )
                }
                submitContacts(event, mode, liftingPointerId = null)
            }
            MotionEvent.ACTION_MOVE -> submitContacts(event, mode, liftingPointerId = null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                submitContacts(event, mode, liftingPointerId = pointerId)
                pictureContacts.touchUp(pointerId)
                finishPictureGesture(event)
            }
        }
    }

    private fun forwardStylusTouch(event: MotionEvent, mode: VideoMode, index: Int) {
        val tool = event.getToolType(index)
        val isEraser = tool == MotionEvent.TOOL_TYPE_ERASER
        val barrel = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        val x = PictureTouchMap.clampedPixel(event.getX(index), surfaceView.width, mode.width)
        val y = PictureTouchMap.clampedPixel(event.getY(index), surfaceView.height, mode.height)

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            UibcTouchChannel.submitPen(
                UibcPenContact(
                    x = 0,
                    y = 0,
                    tip = false,
                    inRange = false,
                    barrel = false,
                    eraser = false,
                    pressure = 0,
                ),
            )
            return
        }

        val isTarget = event.actionIndex == index
        val isUp = event.actionMasked == MotionEvent.ACTION_UP ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && isTarget)

        if (isUp) {
            if (x != null && y != null) {
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = x,
                        y = y,
                        tip = false,
                        inRange = true,
                        barrel = barrel,
                        eraser = isEraser,
                        pressure = 0,
                    ),
                )
            } else {
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = 0,
                        y = 0,
                        tip = false,
                        inRange = false,
                        barrel = false,
                        eraser = false,
                        pressure = 0,
                    ),
                )
            }
        } else {
            // Down or Move
            if (x != null && y != null) {
                val raw = event.getPressure(index)
                val pressure = (raw * UibcPackets.MAX_PEN_PRESSURE).toInt().coerceIn(1, UibcPackets.MAX_PEN_PRESSURE)
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = x,
                        y = y,
                        tip = true,
                        inRange = true,
                        barrel = barrel,
                        eraser = isEraser,
                        pressure = pressure,
                    ),
                )
            } else {
                UibcTouchChannel.submitPen(
                    UibcPenContact(
                        x = 0,
                        y = 0,
                        tip = false,
                        inRange = false,
                        barrel = false,
                        eraser = false,
                        pressure = 0,
                    ),
                )
            }
        }
    }

    private fun submitContacts(event: MotionEvent, mode: VideoMode, liftingPointerId: Int?) {
        val contacts = ArrayList<UibcContact>()
        for (index in 0 until event.pointerCount) {
            if (isStylus(event, index)) {
                continue
            }
            val pointerId = event.getPointerId(index)
            val insidePicture = isInsideVisiblePicture(event.getX(index), event.getY(index))
            val slot = pictureContacts.touchMove(pointerId, insidePicture, event.getX(index), event.getY(index))
                ?: continue
            val x = PictureTouchMap.clampedPixel(event.getX(index), surfaceView.width, mode.width) ?: continue
            val y = PictureTouchMap.clampedPixel(event.getY(index), surfaceView.height, mode.height) ?: continue
            contacts.add(
                UibcContact(
                    id = slot,
                    x = x,
                    y = y,
                    tip = pointerId != liftingPointerId,
                ),
            )
        }
        releaseOutsideContacts()
        UibcTouchChannel.submit(contacts)
    }

    /** Send terminal reports before the next active frame drops the ended slots. */
    private fun releaseOutsideContacts() {
        val ended = pictureContacts.takeOutsideReleases()
        if (ended.touchSlots.isNotEmpty() || ended.pen) {
            UibcTouchChannel.releaseCovered(ended.touchSlots, ended.pen)
        }
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Request Back-gesture ownership only while the picture, not the panel, is active. */
    private fun updateGestureExclusion() {
        if (!::stage.isInitialized || !::controlPanelRoot.isInitialized) return
        val pictureActive = hasWindowFocus() && MiraxApp.instance.session.snapshot().showPicture &&
            controlPanelRoot.visibility != View.VISIBLE
        // Sticky immersive hides navigation; mandatory Home/Recents gestures remain system-owned.
        stage.systemGestureExclusionRects = if (pictureActive && stage.width > 0 && stage.height > 0) {
            listOf(Rect(0, 0, stage.width, stage.height))
        } else {
            emptyList()
        }
    }

    private fun hideSystemBars() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        if (::stage.isInitialized) {
            ViewCompat.requestApplyInsets(stage)
        }
    }

    private fun updateDebugOverlay(snapshot: SessionSnapshot) {
        // Show or hide the overlay view based on the session setting.
        debugOverlay.visibility = if (snapshot.showDebugOverlay) View.VISIBLE else View.GONE
        if (snapshot.showDebugOverlay) {
            endCoveredContacts()
        }

        val decoder = SinkConnectionController.decoder
        val outW = if (bufferW > 0) bufferW else decoder.inputWidth
        val outH = if (bufferH > 0) bufferH else decoder.inputHeight
        val stats = decoder.getDebugStats(outputWidth = outW, outputHeight = outH)
        debugOverlay.updateDecoderStats(stats)

        val frameTree = listOf(
            DebugOverlayView.FrameNode("Decoder", outW, outH, listOf(
                DebugOverlayView.FrameNode("Input", decoder.inputWidth, decoder.inputHeight),
                DebugOverlayView.FrameNode("Surface", surfaceView.width, surfaceView.height),
                DebugOverlayView.FrameNode("Panel", stage.width, stage.height),
            )),
        )
        debugOverlay.updateFrameTree(frameTree)
        debugOverlay.updateLayout(stage.width, stage.height, snapshot.pictureScale.name)

        // Crop rect from picture placement
        val picture = snapshot.selectedMode
        if (picture != null) {
            val rect = PicturePlacement.place(
                pictureWidth = picture.width,
                pictureHeight = picture.height,
                panelWidth = stage.width - stage.paddingLeft - stage.paddingRight,
                panelHeight = stage.height - stage.paddingTop - stage.paddingBottom,
                scale = snapshot.pictureScale,
            )
            debugOverlay.updateCrop(rect)
        }
    }

    private fun updateDebugOverlayPeriodic() {
        val snapshot = MiraxApp.instance.session.snapshot()
        if (snapshot.showDebugOverlay && snapshot.showPicture) {
            updateDebugOverlay(snapshot)
            // Schedule next update
            debugOverlayHandler.postDelayed(debugOverlayRunnable, 500)
        }
    }

    private fun startDebugOverlayUpdates() {
        debugOverlayHandler.removeCallbacks(debugOverlayRunnable)
        debugOverlayHandler.post(debugOverlayRunnable)
    }

    private fun stopDebugOverlayUpdates() {
        debugOverlayHandler.removeCallbacks(debugOverlayRunnable)
    }

    companion object {
        @Volatile
        private var showing: WeakReference<PictureActivity>? = null

        fun current(): PictureActivity? = showing?.get()

        fun finishIfShowing() {
            showing?.get()?.finish()
            showing = null
        }

        /** Re-apply placement after the session picture scale changes. */
        fun relayoutIfShowing() {
            showing?.get()?.runOnUiThread {
                showing?.get()?.layoutPicture()
            }
        }

        /** Refresh the panel after an asynchronous source-name lookup. */
        fun refreshIfShowing() {
            showing?.get()?.runOnUiThread {
                showing?.get()?.applySession(MiraxApp.instance.session.snapshot())
            }
        }
    }
}
