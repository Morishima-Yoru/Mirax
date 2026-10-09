package me.trinitrix.mirax

import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Rect
import me.trinitrix.mirax.session.PicturePlacement
import me.trinitrix.mirax.session.PictureTouchMap
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.wfd.DebugOverlayView
import me.trinitrix.mirax.wfd.MicrosoftCursorChannel
import me.trinitrix.mirax.wfd.SinkConnectionController
import me.trinitrix.mirax.wfd.UibcContact
import me.trinitrix.mirax.wfd.UibcPackets
import me.trinitrix.mirax.wfd.UibcPenContact
import me.trinitrix.mirax.wfd.UibcTouchChannel
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import java.lang.ref.WeakReference

/**
 * Fullscreen Miracast picture. Placement follows the session [me.trinitrix.mirax.session.PictureScale]
 * via [PicturePlacement]; views only render. Contacts on the picture are general
 * touch or S Pen when that switch is on. They do not open settings.
 *
 * System Back and the bottom handle are decided by [me.trinitrix.mirax.session.MiraxSession];
 * this activity only renders [SessionSnapshot] outputs and forwards actions.
 */
class PictureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: ImageView
    private lateinit var handleRoot: FrameLayout
    private lateinit var handleScrim: View
    private lateinit var handlePanel: LinearLayout
    private var endConnectionDialog: AlertDialog? = null
    private lateinit var handleEndButton: MaterialButton
    private lateinit var handleResolutionValue: TextView
    private lateinit var handleRefreshValue: TextView
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
        stage.setOnClickListener { /* intentionally empty */ }
        stage.setOnLongClickListener { true }

        buildHandle()
        stage.addView(
            handleRoot,
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

        hideSystemBars()
        applySession(MiraxApp.instance.session.snapshot())
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
        renderHandle(snapshot)
        ViewCompat.requestApplyInsets(stage)
        layoutPicture()
        
        // Update debug overlay
        debugOverlay.visibility = if (snapshot.showDebugOverlay) View.VISIBLE else View.GONE
        if (snapshot.showDebugOverlay) {
            updateDebugOverlay(snapshot)
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

    private fun buildHandle() {
        fun dp(value: Int): Int = MiraxUi.dp(this, value)

        handleRoot = FrameLayout(this).apply {
            visibility = View.GONE
        }
        handleScrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(MiraxUi.scrimColor(this@PictureActivity))
            isClickable = true
            setOnClickListener {
                applySession(SessionHost.collapseBottomHandleExpanded(this@PictureActivity))
            }
        }

        handlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { /* keep taps on the card from dismissing via the scrim */ }
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_frame, 16, R.color.mirax_stroke, 1)
            setPadding(dp(16), dp(18), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(16)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(handlePanel) { view, insets ->
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(dp(16), dp(18), dp(16), dp(16) + nav.bottom)
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = dp(16) + nav.bottom
            view.layoutParams = params
            insets
        }

        handleEndButton = MiraxUi.stopActionButton(this, getString(R.string.handle_end_connection)) {
            applySession(SessionHost.requestEndConnection(this@PictureActivity))
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(16)
        }
        handlePanel.addView(handleEndButton)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MiraxUi.rounded(this@PictureActivity, R.color.mirax_chip, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        stats.addView(frameLabel(getString(R.string.handle_resolution_label)))
        handleResolutionValue = valueText()
        stats.addView(handleResolutionValue)
        stats.addView(frameLabel(getString(R.string.handle_refresh_label), topPadDp = 14))
        handleRefreshValue = valueText()
        stats.addView(handleRefreshValue)
        handlePanel.addView(stats)

        handleRoot.addView(handleScrim)
        handleRoot.addView(handlePanel)
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

    private fun renderHandle(snapshot: SessionSnapshot) {
        if (!snapshot.showBottomHandle) {
            handleRoot.visibility = View.GONE
            return
        }
        handleResolutionValue.text = snapshot.handleResolutionText
        val refresh = snapshot.handleRefreshRateHz
        handleRefreshValue.text = if (refresh != null) {
            getString(R.string.handle_refresh_value, refresh)
        } else {
            ""
        }
        val expanded = snapshot.bottomHandleExpanded
        handleRoot.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            ViewCompat.requestApplyInsets(handlePanel)
        }
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
        val decoder = SinkConnectionController.decoder
        val stats = DebugOverlayView.DecoderStats(
            decoderName = decoder.decoderName,
            inputWidth = decoder.inputWidth,
            inputHeight = decoder.inputHeight,
            inputFps = decoder.inputFps,
            outputWidth = bufferW,
            outputHeight = bufferH,
            framesDecoded = decoder.framesDecoded,
            framesDropped = 0, // TODO: track dropped frames
            pendingFrames = decoder.pendingFrames,
            awaitingKeyframe = decoder.isAwaitingKeyframe,
            currentBitrateKbps = 0, // TODO: calculate bitrate
        )
        debugOverlay.updateDecoderStats(stats)
        
        // Build frame tree from decoder state
        val frameTree = listOf(
            DebugOverlayView.FrameNode("Decoder", bufferW, bufferH, listOf(
                DebugOverlayView.FrameNode("Input", decoder.inputWidth, decoder.inputHeight),
                DebugOverlayView.FrameNode("Surface", surfaceView.width, surfaceView.height),
                DebugOverlayView.FrameNode("Panel", stage.width, stage.height),
            ))
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
