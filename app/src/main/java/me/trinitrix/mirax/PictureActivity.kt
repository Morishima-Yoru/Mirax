package me.trinitrix.mirax

import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import me.trinitrix.mirax.session.PicturePlacement
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
 * System Back and the bottom handle are decided by [me.trinitrix.mirax.session.MiraxSession];
 * this activity only renders [SessionSnapshot] outputs and forwards actions.
 */
class PictureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: ImageView
    private var endConnectionDialog: AlertDialog? = null
    // Control panel fields
    private lateinit var controlPanelRoot: FrameLayout
    private lateinit var controlPanelScrim: View
    private lateinit var controlPanelCard: LinearLayout
    private lateinit var controlPanelSourceName: TextView
    private lateinit var controlPanelResolutionValue: TextView
    private lateinit var controlPanelRefreshValue: TextView
    private lateinit var controlPanelOrientationLockSwitch: MaterialSwitch
    private lateinit var controlPanelDebugOverlaySwitch: MaterialSwitch
    private lateinit var controlPanelTouchEnabledSwitch: MaterialSwitch
    private lateinit var controlPanelCameraCutoutSwitch: MaterialSwitch
    private lateinit var controlPanelEndButton: MaterialButton
    /** Decoder buffer size for [SurfaceHolder.setFixedSize]; may be padded. */
    private var bufferW: Int = 0
    private var bufferH: Int = 0
    private var currentFixedW: Int = 0
    private var currentFixedH: Int = 0
    /** Pointer id to UIBC contact slot. */
    private val touchSlots = LinkedHashMap<Int, Int>()
    private var cursorPictureX: Int = 0
    private var cursorPictureY: Int = 0
    private var cursorHotspotX: Int = 0
    private var cursorHotspotY: Int = 0
    private var cursorBitmapW: Int = 0
    private var cursorBitmapH: Int = 0
    private lateinit var debugOverlay: DebugOverlayView
    private val debugOverlayHandler = Handler(Looper.getMainLooper())
    private val debugOverlayRunnable = Runnable { updateDebugOverlayPeriodic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = WeakReference(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

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
        stage.setOnClickListener {
            applySession(SessionHost.toggleControlPanel(this@PictureActivity))
        }
        stage.setOnLongClickListener { true }

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
        stage.addView(debugOverlay)

        surfaceView.holder.addCallback(this)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPicture() }
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

        installImmersiveBackBridge()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    applySession(SessionHost.onSystemBack(this@PictureActivity))
                }
            },
        )

        val mockSession = intent.getBooleanExtra("mirax_mock_session", false) && isDebuggable
        if (mockSession) {
            val mockSnapshot = SessionSnapshot(
                phase = ScreenPhase.CONNECTED,
                wfdOwner = me.trinitrix.mirax.session.WfdOwner.SHIZUKU,
                tileState = me.trinitrix.mirax.session.TileState.CONNECTED,
                widgetStatus = me.trinitrix.mirax.session.WidgetStatus.CONNECTED,
                advertisingEnabled = true,
                shouldRequestShizukuPermission = false,
                canReadWmSize = true,
                showCompatibilityNotice = false,
                rootAvailable = false,
                effects = emptyList(),
                languagePreference = me.trinitrix.mirax.session.LanguagePreference.FOLLOW_SYSTEM,
                appLanguage = me.trinitrix.mirax.session.AppLanguage.TRADITIONAL_CHINESE,
                effectiveBroadcastName = "Mock Device",
                displayNameOverride = null,
                displayNameFollowsDevice = true,
                displayNameFieldHint = "",
                preferredMode = me.trinitrix.mirax.session.VideoMode(1920, 1080, 60),
                preferredModeText = "1920×1080@60",
                canUseThisScreen = true,
                standardModes = emptyList(),
                nextAdvertisementModes = setOf(me.trinitrix.mirax.session.VideoMode(1920, 1080, 60)),
                currentResolutionText = "1920×1080",
                miraxDisplayId = 0,
                maxVideoBitrateBps = 15000000,
                wfdAdvertise = null,
                selectedMode = me.trinitrix.mirax.session.VideoMode(1920, 1080, 60),
                showPicture = true,
                handleResolutionText = "1920×1080",
                handleRefreshRateHz = 60,
                floatingBallEnabled = true,
                showOverlayPermissionReminder = false,
                showFloatingBall = false,
                pictureScale = me.trinitrix.mirax.session.PictureScale.PROPORTIONAL,
                connectionRuns = emptyList(),
                customModes = emptyList(),
                autoAddWmSizeOnConnect = false,
                touchEnabled = true,
                showDebugMessages = false,
                showDebugOverlay = false,
                cameraCutoutAffectsLayout = false,
                broadcastAutoStopMinutes = 1,
                controlPanelExpanded = false,
                orientationLockEnabled = false,
                sourceDeviceName = "Mock Device",
                handshakeLog = "",
                connectionOfferModes = null,
                connectionPreferredMode = null,
            )
            applySession(mockSnapshot)
        } else {
            hideSystemBars()
            applySession(MiraxApp.instance.session.snapshot())
            startDebugOverlayUpdates()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Projection is foreground again — hide the ball without ending the connection.
        val session = MiraxApp.instance.session
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

    override fun onDestroy() {
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
        SinkConnectionController.decoder.attachSurface(null)
    }

    private fun applySession(snapshot: SessionSnapshot) {
        if (!snapshot.showPicture) {
            finish()
            return
        }
        renderControlPanel(snapshot)
        ViewCompat.requestApplyInsets(stage)
        layoutPicture()

        debugOverlay.visibility = if (snapshot.showDebugOverlay) View.VISIBLE else View.GONE
        if (snapshot.showDebugOverlay) {
            startDebugOverlayUpdates()
            updateDebugOverlay(snapshot)
        } else {
            stopDebugOverlayUpdates()
        }
    }

    /**
     * Applies orientation lock to the activity based on the user preference.
     * When enabled, locks the screen to its current orientation.
     * When disabled, allows the screen to rotate freely.
     */
    private fun applyOrientationLock(orientationLockEnabled: Boolean) {
        requestedOrientation = if (orientationLockEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyProjectionCutoutInsets(view: View, insets: WindowInsetsCompat) {
        val avoidCutout = MiraxApp.instance.session.snapshot().cameraCutoutAffectsLayout
        if (avoidCutout) {
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
        } else {
            view.setPadding(0, 0, 0, 0)
        }
    }

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

        controlPanelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_surface, 16, R.color.mirax_stroke, 1)
            setPadding(dp(16), dp(18), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        }

        val statusHeader = TextView(this).apply {
            text = "連線狀態"
            textSize = 16f
            typeface = MiraxUi.weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        controlPanelCard.addView(statusHeader)

        controlPanelSourceName = TextView(this).apply {
            text = "來源名稱未知"
            textSize = 17f
            typeface = MiraxUi.weight(650)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        controlPanelCard.addView(controlPanelSourceName)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_chip, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            weightSum = 2f
        }

        val resolutionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        resolutionContainer.addView(frameLabel("解析度"))
        controlPanelResolutionValue = valueText()
        resolutionContainer.addView(controlPanelResolutionValue)
        stats.addView(resolutionContainer)

        val refreshContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        refreshContainer.addView(frameLabel("重新整理率"))
        controlPanelRefreshValue = valueText()
        refreshContainer.addView(controlPanelRefreshValue)
        stats.addView(refreshContainer)

        controlPanelCard.addView(stats)

        val quickHeader = TextView(this).apply {
            text = "快速控制"
            textSize = 16f
            typeface = MiraxUi.weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        }
        controlPanelCard.addView(quickHeader)

        val scaleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val scales = listOf(
            "等比" to PictureScale.PROPORTIONAL,
            "鋪滿" to PictureScale.CENTER_CROP,
            "拉伸" to PictureScale.MATCH_EDGES,
            "原寸" to PictureScale.ACTUAL,
        )
        for ((label, scale) in scales) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { rightMargin = if (label != "原寸") dp(4) else 0 }
                setOnClickListener {
                    val session = MiraxApp.instance.session
                    session.handle(me.trinitrix.mirax.session.SessionAction.SetPictureScale(scale))
                    val snapshot = SessionHost.commit(this@PictureActivity)
                    applySession(snapshot)
                }
            }
            scaleContainer.addView(btn)
        }
        controlPanelCard.addView(scaleContainer)

        controlPanelOrientationLockSwitch = MaterialSwitch(this).apply {
            text = "鎖定方向"
            textSize = 17f
            minHeight = dp(56)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnCheckedChangeListener { _, isChecked ->
                val snapshot = SessionHost.toggleOrientationLock(this@PictureActivity, isChecked)
                applySession(snapshot)
            }
        }
        controlPanelCard.addView(controlPanelOrientationLockSwitch)

        controlPanelDebugOverlaySwitch = MaterialSwitch(this).apply {
            text = "除錯疊加層"
            textSize = 17f
            minHeight = dp(56)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(me.trinitrix.mirax.session.SessionAction.SetShowDebugOverlay(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        }
        controlPanelCard.addView(controlPanelDebugOverlaySwitch)

        val secondaryHeader = TextView(this).apply {
            text = "其他設定"
            textSize = 16f
            typeface = MiraxUi.weight(700)
            letterSpacing = 0.04f
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        }
        controlPanelCard.addView(secondaryHeader)

        controlPanelTouchEnabledSwitch = MaterialSwitch(this).apply {
            text = "觸控輸入"
            textSize = 17f
            minHeight = dp(56)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(me.trinitrix.mirax.session.SessionAction.SetTouchEnabled(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        }
        controlPanelCard.addView(controlPanelTouchEnabledSwitch)

        controlPanelCameraCutoutSwitch = MaterialSwitch(this).apply {
            text = "鏡頭開孔影響版面"
            textSize = 17f
            minHeight = dp(56)
            setTextColor(getColor(R.color.mirax_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(me.trinitrix.mirax.session.SessionAction.SetCameraCutoutAffectsLayout(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        }
        controlPanelCard.addView(controlPanelCameraCutoutSwitch)

        controlPanelEndButton = MiraxUi.stopActionButton(this, "結束連線") {
            applySession(SessionHost.requestEndConnection(this@PictureActivity))
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
        }
        controlPanelCard.addView(controlPanelEndButton)

        controlPanelRoot.addView(controlPanelScrim)
        controlPanelRoot.addView(controlPanelCard)
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
        endConnectionDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.end_connection_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.end_connection_confirm_action) { _, _ ->
                onConfirmed()
            }
            .create()
        endConnectionDialog?.show()
    }

    private fun renderControlPanel(snapshot: SessionSnapshot) {
        if (!snapshot.controlPanelExpanded) {
            controlPanelRoot.visibility = View.GONE
            return
        }
        val sourceName = snapshot.sourceDeviceName ?: "來源名稱未知"
        controlPanelSourceName.text = sourceName
        controlPanelResolutionValue.text = snapshot.handleResolutionText
        val refresh = snapshot.handleRefreshRateHz
        controlPanelRefreshValue.text = if (refresh != null) {
            getString(R.string.handle_refresh_value, refresh)
        } else {
            ""
        }
        if (::controlPanelOrientationLockSwitch.isInitialized && controlPanelOrientationLockSwitch.isChecked != snapshot.orientationLockEnabled) {
            controlPanelOrientationLockSwitch.isChecked = snapshot.orientationLockEnabled
        }
        applyOrientationLock(snapshot.orientationLockEnabled)
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
    }

    private fun layoutPicture() {
        if (stage.width <= 0 || stage.height <= 0) {
            return
        }
        val snapshot = MiraxApp.instance.session.snapshot()
        val picture = snapshot.selectedMode ?: return
        val pictureW = picture.width
        val pictureH = picture.height
        if (pictureW <= 0 || pictureH <= 0) {
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
        val snapshot = MiraxApp.instance.session.snapshot()
        val mode = snapshot.selectedMode
        if (!snapshot.touchEnabled || mode == null) {
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
        val snapshot = MiraxApp.instance.session.snapshot()
        val mode = snapshot.selectedMode
        if (!snapshot.touchEnabled || mode == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                applySession(SessionHost.toggleControlPanel(this@PictureActivity))
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                touchSlots.clear()
                UibcTouchChannel.liftAll()
            }
            return
        }

        var hasStylus = false
        for (index in 0 until event.pointerCount) {
            if (isStylus(event, index)) {
                hasStylus = true
                forwardStylusTouch(event, mode, index)
            }
        }
        if (hasStylus && event.pointerCount == 1) {
            return
        }

        Log.v("PictureTouch", "touch: action=${event.actionMasked} count=${event.pointerCount}")
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!isStylus(event, index)) {
                    assignSlot(event.getPointerId(index))
                }
                submitContacts(event, mode, liftingPointerId = null)
            }
            MotionEvent.ACTION_MOVE -> submitContacts(event, mode, liftingPointerId = null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                submitContacts(event, mode, liftingPointerId = pointerId)
                touchSlots.remove(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> {
                UibcTouchChannel.liftAll()
                touchSlots.clear()
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
            val slot = touchSlots[pointerId] ?: continue
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
        UibcTouchChannel.submit(contacts)
    }

    private fun assignSlot(pointerId: Int) {
        if (touchSlots.containsKey(pointerId)) {
            return
        }
        val used = touchSlots.values.toSet()
        for (slot in 0 until UibcPackets.MAX_CONTACTS) {
            if (slot !in used) {
                touchSlots[pointerId] = slot
                return
            }
        }
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun installImmersiveBackBridge() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val barsVisible = insets.isVisible(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
            )
            if (barsVisible) {
                window.decorView.post { hideSystemBars() }
            }
            insets
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
    }
}