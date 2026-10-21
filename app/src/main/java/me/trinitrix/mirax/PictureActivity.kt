package me.trinitrix.mirax

import android.util.Log
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Rect
import me.trinitrix.mirax.session.PicturePlacement
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.PictureTouchMap
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.connectionRunHtml
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
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var controlPanelRoot: FrameLayout
    private lateinit var controlPanelScrim: View
    private lateinit var controlPanelCardScrollView: ScrollView
    private lateinit var controlPanelCard: LinearLayout
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
    private var pendingDiagnosticsHtml: String? = null
    private val diagnosticsExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) {
            pendingDiagnosticsHtml = null
            return@registerForActivityResult
        }
        val html = pendingDiagnosticsHtml
        pendingDiagnosticsHtml = null
        if (html == null) {
            // shortcut: process death during the picker loses the export; persist it if that becomes common.
            Log.w("PictureActivity", "Diagnostics export lost while choosing a document")
            Toast.makeText(this, R.string.control_panel_export_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IOException("No output stream for diagnostics export")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(html) }
            } catch (e: Exception) {
                Log.e("PictureActivity", "Failed to export connection diagnostics", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PictureActivity, R.string.control_panel_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = WeakReference(this)
        lockedDirectionIsLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        stage.bringChildToFront(controlPanelRoot)

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!MiraxApp.instance.session.snapshot().orientationLockEnabled) {
            lockedDirectionIsLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        hideSystemBars()
        MiraxApp.instance.session.report(
            PictureRotationReport(SessionHost.pictureRotationDegrees(this, displayId = null)),
        )
        layoutPicture()
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

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cardWidth = if (isLandscape) {
            minOf(dp(840), resources.displayMetrics.widthPixels - dp(32))
        } else {
            minOf(dp(360), resources.displayMetrics.widthPixels - dp(32))
        }
        val cardHeight = if (isLandscape) {
            FrameLayout.LayoutParams.MATCH_PARENT
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
            textSize = 15f
            minWidth = dp(36)
            minHeight = dp(36)
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.mirax_muted))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
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
        val scaleRows = if (isLandscape && cardWidth >= dp(800)) listOf(scaleList) else scaleList.chunked(2)
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
            textSize = 18f
            minWidth = dp(36)
            minHeight = dp(36)
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.mirax_accent))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
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
        controlsColumn.addView(orientationRow)

        controlsColumn.addView(createSwitchRow(getString(R.string.control_panel_debug_overlay)) { switch ->
            controlPanelDebugOverlaySwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetShowDebugOverlay(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        controlsColumn.addView(createSwitchRow(getString(R.string.control_panel_touch_input)) { switch ->
            controlPanelTouchEnabledSwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetTouchEnabled(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        controlsColumn.addView(createSwitchRow(getString(R.string.control_panel_avoid_cutout)) { switch ->
            controlPanelCameraCutoutSwitch = switch
            switch.setOnCheckedChangeListener { _, isChecked ->
                val session = MiraxApp.instance.session
                session.handle(SessionAction.SetCameraCutoutAffectsLayout(isChecked))
                val snapshot = SessionHost.commit(this@PictureActivity)
                applySession(snapshot)
            }
        })

        controlsColumn.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.control_panel_export_html)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                val session = MiraxApp.instance.session
                val activeRun = session.activeConnectionRun()
                val run = activeRun ?: session.snapshot().connectionRuns.firstOrNull()
                if (run == null) {
                    Toast.makeText(this@PictureActivity, R.string.control_panel_no_diagnostics, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingDiagnosticsHtml = connectionRunHtml(run, active = activeRun != null)
                diagnosticsExport.launch("mirax-diagnostics.html")
            }
        })

        columnsLayout.addView(controlsColumn)
        controlPanelCard.addView(columnsLayout)

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
        applyOrientationLock(snapshot.orientationLockEnabled)
        if (!snapshot.controlPanelExpanded) {
            controlPanelRoot.visibility = View.GONE
            return
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

        /** Refresh the panel after an asynchronous source-name lookup. */
        fun refreshIfShowing() {
            showing?.get()?.runOnUiThread {
                showing?.get()?.applySession(MiraxApp.instance.session.snapshot())
            }
        }
    }
}
