package me.trinitrix.mirax

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.animation.doOnEnd
import kotlin.math.abs

private const val HANDLE_ALPHA_REST = 144
private const val HANDLE_ALPHA_PRESSED = 184
private const val HANDLE_SETTLE_DURATION_MS = 180L

/**
 * A translucent edge handle with an accessible touch target and draggable docking.
 *
 * @param context projection activity supplying the control theme
 */
internal class ProjectionControlButton(context: Context) : AppCompatImageButton(context) {
    var onPositionChanged: (() -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleDrawable = EdgeHandleDrawable(context)
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dockedToRight = true
    private var pressedDockToRight = true
    private var phase = DragPhase.IDLE
    private var settling: ValueAnimator? = null

    private enum class DragPhase { IDLE, PRESSED, DRAGGING, SETTLING }

    init {
        contentDescription = context.getString(R.string.control_panel_open)
        tooltipText = context.getString(R.string.control_panel_entry_hint)
        setPadding(0, 0, 0, 0)
        setImageDrawable(null)
        elevation = 0f
        isClickable = true
        isFocusable = true
        backgroundTintList = null
        background = handleDrawable
    }

    /** End an interrupted gesture and place the handle inside the resized viewport. */
    fun constrainToParent() {
        val container = parent as? View ?: return
        if (width <= 0 || height <= 0) return
        stopSettling()
        pointerId = MotionEvent.INVALID_POINTER_ID
        phase = DragPhase.IDLE
        isPressed = false
        handleDrawable.alpha = HANDLE_ALPHA_REST
        handleDrawable.dockFraction = if (dockedToRight) 1f else 0f
        moveInsideParent(dockX(container, dockedToRight), y)
    }

    /** @return the hit target's X coordinate when its visible strip touches the chosen edge */
    private fun dockX(container: View, right: Boolean): Float = if (right) {
        (container.width - container.paddingRight - width).toFloat().coerceAtLeast(container.paddingLeft.toFloat())
    } else {
        container.paddingLeft.toFloat()
    }

    /** Follow the grab point and clamp the visible strip, not its transparent accessibility padding. */
    private fun moveInsideParent(nextX: Float, nextY: Float) {
        val container = parent as? View ?: return
        val left = container.paddingLeft.toFloat()
        val top = container.paddingTop.toFloat()
        val inset = (width - handleDrawable.stripWidth) * handleDrawable.dockFraction
        val minX = left - inset
        val maxX = (container.width - container.paddingRight - handleDrawable.stripWidth - inset)
            .coerceAtLeast(minX)
        val boundedX = nextX.coerceIn(minX, maxX)
        val boundedY = nextY.coerceIn(top,
            (container.height - container.paddingBottom - height).toFloat().coerceAtLeast(top))
        if (x == boundedX && y == boundedY) return
        x = boundedX
        y = boundedY
        onPositionChanged?.invoke()
    }

    /** Cancel without running an obsolete animator's final-position callback. */
    private fun stopSettling() {
        val previous = settling
        settling = null
        previous?.cancel()
        if (phase == DragPhase.SETTLING) phase = DragPhase.IDLE
    }

    /** Animate both the hit target and its internal strip so changing sides never jumps 40dp. */
    private fun settleAtEdge(right: Boolean, nextY: Float = y) {
        val container = parent as? View ?: return
        stopSettling()
        dockedToRight = right
        val startX = x
        val startY = y
        val startFraction = handleDrawable.dockFraction
        val targetX = dockX(container, right)
        val targetFraction = if (right) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() ||
            (abs(startX - targetX) < 0.5f && abs(startY - nextY) < 0.5f && startFraction == targetFraction)
        ) {
            handleDrawable.dockFraction = targetFraction
            moveInsideParent(targetX, nextY)
            phase = DragPhase.IDLE
            return
        }
        phase = DragPhase.SETTLING
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HANDLE_SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedFraction
                handleDrawable.dockFraction = startFraction + (targetFraction - startFraction) * progress
                moveInsideParent(startX + (targetX - startX) * progress, startY + (nextY - startY) * progress)
            }
            doOnEnd {
                if (settling === it) {
                    settling = null
                    phase = DragPhase.IDLE
                    handleDrawable.dockFraction = targetFraction
                    moveInsideParent(targetX, nextY)
                }
            }
        }
        settling = animator
        animator.start()
    }

    /** Cancel a contact without making a second pointer inherit its gesture or activate the panel. */
    private fun cancelContact() {
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return
        pointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = false
        handleDrawable.alpha = HANDLE_ALPHA_REST
        settleAtEdge(pressedDockToRight, downY)
    }

    /** @return whether a raw screen sample makes this contact ineligible for a tap */
    private fun crossedTapSlop(rawX: Float, rawY: Float): Boolean =
        abs(rawX - downRawX) > touchSlop || abs(rawY - downRawY) > touchSlop

    /** @return false if the tracked pointer is missing; otherwise inspect every sample before a tap */
    private fun updateContactMovement(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return false
        val rawX = event.getRawX(index)
        val rawY = event.getRawY(index)
        if (phase == DragPhase.PRESSED) {
            val rawOffsetX = rawX - event.getX(index)
            val rawOffsetY = rawY - event.getY(index)
            val crossedHistory = (0 until event.historySize).any { history ->
                crossedTapSlop(event.getHistoricalX(index, history) + rawOffsetX,
                    event.getHistoricalY(index, history) + rawOffsetY)
            }
            if (crossedHistory || crossedTapSlop(rawX, rawY)) {
                phase = DragPhase.DRAGGING
                isPressed = false
            }
        }
        if (phase == DragPhase.DRAGGING) {
            moveInsideParent(downX + rawX - downRawX, downY + rawY - downRawY)
        }
        return true
    }

    /**
     * Keep ownership until release, even when a drag leaves the original button bounds.
     *
     * @param event Android pointer event in button coordinates
     * @return true because this control never forwards its contact to the picture
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
            cancelContact()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopSettling()
                pointerId = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                downX = x
                downY = y
                pressedDockToRight = dockedToRight
                phase = DragPhase.PRESSED
                isPressed = true
                handleDrawable.alpha = HANDLE_ALPHA_PRESSED
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!updateContactMovement(event)) cancelContact()
            MotionEvent.ACTION_POINTER_DOWN -> cancelContact()
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) cancelContact()
            }
            MotionEvent.ACTION_UP -> {
                if (pointerId == MotionEvent.INVALID_POINTER_ID) return true
                if (!updateContactMovement(event)) {
                    cancelContact()
                    return true
                }
                val clicked = phase == DragPhase.PRESSED && event.x >= 0 && event.x < width &&
                    event.y >= 0 && event.y < height
                pointerId = MotionEvent.INVALID_POINTER_ID
                isPressed = false
                handleDrawable.alpha = HANDLE_ALPHA_REST
                val container = parent as? View
                if (clicked) {
                    phase = DragPhase.IDLE
                    performClick()
                } else if (container != null) {
                    val visibleCenter = x + (width - handleDrawable.stripWidth) * handleDrawable.dockFraction +
                        handleDrawable.stripWidth / 2f
                    settleAtEdge(visibleCenter >=
                        (container.paddingLeft + container.width - container.paddingRight) / 2f)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelContact()
        }
        return true
    }

    /** @return whether the click listener handled the accessible activation. */
    override fun performClick(): Boolean = super.performClick()

    /** Hidden controls cannot keep a drag or docking animation alive behind another page. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) constrainToParent()
    }

    /** Release animation callbacks when the projection view is removed. */
    override fun onDetachedFromWindow() {
        stopSettling()
        pointerId = MotionEvent.INVALID_POINTER_ID
        super.onDetachedFromWindow()
    }
}

/**
 * Translucent edge strip with rounded inner corners and no stroke on the attached edge.
 *
 * The fill stays translucent in every state and the thin border only runs along
 * the top, inner, and bottom sides so the docked edge never shows a bright line.
 *
 * @param context used for dp conversion and base colors
 */
private class EdgeHandleDrawable(context: Context) : Drawable() {
    val stripWidth = MiraxUi.dp(context, 8).toFloat()
    var dockFraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }
    private val radius = MiraxUi.dp(context, 4).toFloat()
    private val strokeWidth = MiraxUi.dp(context, 1).coerceAtLeast(1).toFloat()
    private val fillRgb = context.getColor(R.color.mirax_surface) and 0x00FFFFFF
    private val borderBase = context.getColor(R.color.mirax_stroke)
    private val borderBaseAlpha = borderBase ushr 24
    private var overallAlpha = HANDLE_ALPHA_REST
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@EdgeHandleDrawable.strokeWidth
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.BUTT
    }
    private val fillPath = Path()
    private val borderPath = Path()

    /**
     * Draw an open three-sided outline, mirrored for left-edge docking.
     *
     * @param canvas target canvas in the drawable owner's coordinate space
     */
    override fun draw(canvas: Canvas) {
        val boundsWidth = stripWidth.coerceAtMost(bounds.width().toFloat())
        val boundsHeight = bounds.height().toFloat()
        if (boundsWidth <= 0f || boundsHeight <= 0f) return
        val corner = radius.coerceAtMost(boundsWidth.coerceAtMost(boundsHeight) / 2f)
        val half = strokeWidth / 2f
        fillPaint.color = fillRgb or (overallAlpha.coerceIn(0, 255) shl 24)
        val borderAlpha = (borderBaseAlpha * overallAlpha / HANDLE_ALPHA_REST).coerceIn(0, 254)
        borderPaint.color = (borderBase and 0x00FFFFFF) or (borderAlpha shl 24)
        canvas.save()
        canvas.translate(bounds.left + (bounds.width() - boundsWidth) * dockFraction, bounds.top.toFloat())
        val attachedLeft = 1f - dockFraction
        val attachedRight = dockFraction
        fillPath.reset()
        val leftRadius = corner * attachedRight
        val rightRadius = corner * attachedLeft
        fillPath.moveTo(leftRadius, 0f)
        fillPath.lineTo(boundsWidth - rightRadius, 0f)
        fillPath.quadTo(boundsWidth, 0f, boundsWidth, rightRadius)
        fillPath.lineTo(boundsWidth, boundsHeight - rightRadius)
        fillPath.quadTo(boundsWidth, boundsHeight, boundsWidth - rightRadius, boundsHeight)
        fillPath.lineTo(leftRadius, boundsHeight)
        fillPath.quadTo(0f, boundsHeight, 0f, boundsHeight - leftRadius)
        fillPath.lineTo(0f, leftRadius)
        fillPath.quadTo(0f, 0f, leftRadius, 0f)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        repeat(2) { side ->
            val right = side == 0
            canvas.save()
            if (!right) canvas.scale(-1f, 1f, boundsWidth / 2f, boundsHeight / 2f)
            borderPaint.alpha = (borderAlpha * if (right) attachedRight else attachedLeft).toInt()
            borderPath.reset()
            borderPath.moveTo(boundsWidth, half)
            borderPath.lineTo(corner, half)
            borderPath.quadTo(half, half, half, corner)
            borderPath.lineTo(half, boundsHeight - corner)
            borderPath.quadTo(half, boundsHeight - half, corner, boundsHeight - half)
            borderPath.lineTo(boundsWidth, boundsHeight - half)
            canvas.drawPath(borderPath, borderPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    /** @param alpha overall handle opacity, always kept translucent by the caller */
    override fun setAlpha(alpha: Int) {
        overallAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    /** @return the last overall alpha supplied through [setAlpha] */
    override fun getAlpha(): Int = overallAlpha

    /** @return translucent because both fill and border keep an alpha below opaque */
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** @param colorFilter ignored to keep the fixed translucent palette */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
}
