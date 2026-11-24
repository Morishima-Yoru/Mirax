package me.trinitrix.mirax.ui.standby.framing

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel

/**
 * Edits framing over a caller-owned, already-created media preview.
 *
 * All methods and callbacks run on the main thread. The host owns the preview,
 * transaction, and lifecycle; detach the preview from its old parent first.
 * Retain [model] and the pending transaction across view recreation. For a
 * recreated editor, pass `commitPending = true` while the host is still waiting
 * for that transaction. Only report a definite failure as a failed commit.
 *
 * @param context themed UI context.
 * @param preview existing, unparented media view; no decoder is created here.
 * @param model existing framing model, retained by the host across recreation.
 * @param onPreview applies the visible framing to that same media view.
 * @param onConfirm starts persistence of the supplied draft; it must not close the editor as success.
 * @param onCancel discards the candidate after the baseline preview is restored.
 * @param commitPending whether the retained transaction is still unresolved.
 */
class StandbyFramingEditorView(
    context: Context,
    preview: View,
    model: StandbyFramingControlsModel,
    private val onPreview: (StandbyFraming) -> Unit,
    private val onConfirm: (StandbyFraming) -> Unit,
    private val onCancel: () -> Unit,
    commitPending: Boolean = false,
) : FrameLayout(context) {
    private val gesture = StandbyFramingEditorGesture(model, commitPending)
    private val zoomOut = Button(context)
    private val zoomIn = Button(context)
    private val cancel = Button(context)
    private val confirm = Button(context)
    private val status = TextView(context)
    private val interaction = PreviewInteractionView(context)
    private var contentW = 0f
    private var contentH = 0f
    private var playbackReady = false
    private var errorMessage: CharSequence? = null

    /** Visible draft, or the original baseline before an edit. */
    val visibleFraming: StandbyFraming get() = gesture.visible

    /** True while the host owns an unresolved commit. */
    val isCommitPending: Boolean get() = gesture.isBusy

    /** True only after the host reports persistence success. */
    val isConfirmed: Boolean get() = gesture.isConfirmed

    init {
        require(preview.parent == null) { "The preview must be detached from its previous parent" }
        setBackgroundColor(context.getColor(R.color.mirax_surface))
        val padding = dp(16)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(context).apply { isFillViewport = true }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(TextView(context).apply {
            setText(R.string.standby_framing_title)
            setTextColor(context.getColor(R.color.mirax_on_surface))
            textSize = 20f
            isAccessibilityHeading = true
        })
        column.addView(TextView(context).apply {
            setText(R.string.standby_framing_instructions)
            setTextColor(context.getColor(R.color.mirax_hint))
        })
        val viewport = FrameLayout(context).apply { minimumHeight = dp(160) }
        viewport.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        viewport.addView(interaction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(viewport, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttonRow(zoomOut, zoomIn))
        column.addView(buttonRow(cancel, confirm))
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(status)

        zoomOut.setText(R.string.standby_framing_zoom_out)
        zoomIn.setText(R.string.standby_framing_zoom_in)
        cancel.setText(R.string.standby_media_cancel)
        confirm.setText(R.string.standby_media_confirm)
        zoomOut.setOnClickListener { changeZoom(gesture.visible.zoom / ZOOM_STEP) }
        zoomIn.setOnClickListener { changeZoom(gesture.visible.zoom * ZOOM_STEP) }
        cancel.setOnClickListener {
            gesture.cancel { baseline ->
                renderState()
                try {
                    onPreview(baseline)
                } finally {
                    onCancel()
                }
            }
        }
        confirm.setOnClickListener {
            if (playbackReady && hasGeometry()) {
                gesture.requestCommit { draft ->
                    errorMessage = null
                    renderState()
                    onConfirm(draft)
                }
            }
        }
        renderState()
    }

    /**
     * Supplies the real decoded dimensions when the existing player is prepared.
     * Invalid values leave the previous dimensions and draft unchanged.
     *
     * @param width decoded content width in pixels.
     * @param height decoded content height in pixels.
     */
    fun setContentSize(width: Float, height: Float) {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return
        if (width == contentW && height == contentH) return
        contentW = width
        contentH = height
        geometryChanged()
    }

    /**
     * Enables confirmation only while the host reports the current preview ready.
     *
     * @param ready whether the current candidate has rendered successfully.
     */
    fun setPlaybackReady(ready: Boolean) {
        playbackReady = ready
        renderState()
    }

    /**
     * Publishes the draft only after persistence succeeds; this does not close the view.
     *
     * @return true if an outstanding commit was completed.
     */
    fun completeCommit(): Boolean {
        if (!gesture.completeCommit()) return false
        renderState()
        return true
    }

    /**
     * Shows a definite persistence failure without discarding the draft.
     *
     * @param message localized error; empty text uses the default error message.
     * @return true if an outstanding commit was returned to editable state.
     */
    fun failCommit(message: CharSequence): Boolean {
        if (!gesture.failCommit()) return false
        errorMessage = message.takeUnless { it.isBlank() }
            ?: context.getString(R.string.standby_framing_save_failed)
        renderState()
        return true
    }

    private fun buttonRow(first: Button, second: Button): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (button in listOf(first, second)) {
                button.minHeight = dp(48)
                button.minimumWidth = dp(48)
                button.isAllCaps = false
                addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun hasGeometry(): Boolean = gesture.hasGeometry(
        contentW, contentH, interaction.width.toFloat(), interaction.height.toFloat(),
    )

    private fun geometryChanged() {
        renderState()
        if ((gesture.isEditing || gesture.isBusy) && hasGeometry()) onPreview(gesture.visible)
    }

    private fun changeZoom(zoom: Float) {
        if (hasGeometry()) publishPreview(gesture.zoom(zoom))
    }

    private fun moveBy(dx: Float, dy: Float): Boolean {
        val next = gesture.pan(
            dx, dy, contentW, contentH, interaction.width.toFloat(), interaction.height.toFloat(),
        ) ?: return false
        publishPreview(next)
        return true
    }

    private fun canMove(dx: Float, dy: Float): Boolean = gesture.canPan(
        dx, dy, contentW, contentH, interaction.width.toFloat(), interaction.height.toFloat(),
    )

    private fun publishPreview(next: StandbyFraming?) {
        if (next == null) return
        errorMessage = null
        renderState()
        onPreview(next)
    }

    private fun renderState() {
        val ready = gesture.isEditing && hasGeometry()
        zoomOut.isEnabled = ready && gesture.visible.zoom > StandbyFramingControlsModel.MIN_ZOOM
        zoomIn.isEnabled = ready && gesture.visible.zoom < StandbyFramingControlsModel.MAX_ZOOM
        cancel.isEnabled = gesture.isEditing
        confirm.isEnabled = ready && playbackReady
        val text: CharSequence? = when {
            gesture.isBusy -> context.getString(R.string.standby_framing_saving)
            gesture.isConfirmed -> context.getString(R.string.standby_framing_saved)
            gesture.isCanceled -> context.getString(R.string.standby_framing_canceled)
            errorMessage != null -> errorMessage
            !hasGeometry() || !playbackReady -> context.getString(R.string.standby_framing_waiting)
            else -> null
        }
        status.text = text
        status.setTextColor(context.getColor(if (errorMessage != null) R.color.mirax_bad else R.color.mirax_hint))
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class PreviewInteractionView(context: Context) : View(context) {
        private var activePointer = MotionEvent.INVALID_POINTER_ID
        private var lastX = 0f
        private var lastY = 0f
        private var acceptScale = false
        private var touching = false
        private var ignoreUntilNextDown = false
        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (acceptScale) changeZoom(gesture.visible.zoom * detector.scaleFactor)
                    return true
                }
            },
        ).apply {
            isQuickScaleEnabled = false
            isStylusScaleEnabled = false
        }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.standby_framing_preview_description)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (touching) {
                activePointer = MotionEvent.INVALID_POINTER_ID
                ignoreUntilNextDown = true
            }
            geometryChanged()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touching = true
                ignoreUntilNextDown = false
            } else if (ignoreUntilNextDown) {
                acceptScale = false
                scaleDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            acceptScale = event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount >= 2
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    requestFocus()
                    activePointer = event.getPointerId(0)
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
                MotionEvent.ACTION_POINTER_DOWN -> activePointer = MotionEvent.INVALID_POINTER_ID
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val index = event.findPointerIndex(activePointer)
                        if (index < 0) {
                            activePointer = event.getPointerId(0)
                            lastX = event.getX(0)
                            lastY = event.getY(0)
                        } else {
                            val x = event.getX(index)
                            val y = event.getY(index)
                            moveBy(x - lastX, y - lastY)
                            lastX = x
                            lastY = y
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    activePointer = MotionEvent.INVALID_POINTER_ID
                    if (event.pointerCount == 2) {
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        activePointer = event.getPointerId(remaining)
                        lastX = event.getX(remaining)
                        lastY = event.getY(remaining)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    activePointer = MotionEvent.INVALID_POINTER_ID
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val next = when {
                    zoomIn.isEnabled -> zoomIn
                    zoomOut.isEnabled -> zoomOut
                    else -> cancel
                }
                return next.requestFocus() || super.onKeyDown(keyCode, event)
            }
            val dx = width * PAN_STEP
            val dy = height * PAN_STEP
            val moved = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-dx, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(dx, 0f)
                KeyEvent.KEYCODE_DPAD_UP -> moveBy(0f, -dy)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0f, dy)
                else -> false
            }
            return moved || super.onKeyDown(keyCode, event)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            addMoveAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                R.string.standby_framing_move_left, -width * PAN_STEP, 0f)
            addMoveAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                R.string.standby_framing_move_right, width * PAN_STEP, 0f)
            addMoveAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                R.string.standby_framing_move_up, 0f, -height * PAN_STEP)
            addMoveAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                R.string.standby_framing_move_down, 0f, height * PAN_STEP)
        }

        private fun addMoveAction(info: AccessibilityNodeInfo, id: Int, label: Int, dx: Float, dy: Float) {
            if (canMove(dx, dy)) {
                info.isScrollable = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, context.getString(label)))
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val moved = when (action) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> moveBy(-width * PAN_STEP, 0f)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> moveBy(width * PAN_STEP, 0f)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> moveBy(0f, -height * PAN_STEP)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> moveBy(0f, height * PAN_STEP)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            if (moved) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
            return moved
        }
    }

    private companion object {
        const val PAN_STEP = 0.1f
        const val ZOOM_STEP = 1.2f
    }
}
