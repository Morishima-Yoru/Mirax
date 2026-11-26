package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import me.trinitrix.mirax.R

/**
 * Hardline (D) foreground candidate.
 *
 * Displays host-provided text and controls without manufacturing session facts.
 * Its current foreground layout is a candidate, not an accepted prototype port.
 * Measured rectangles are exposed separately in view pixels and stage units.
 *
 * Prototype dimensions use the shared measured-stage viewport scale.
 */
class HardlineStandbyForegroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : StandbyForegroundView(context, attrs, defStyleAttr) {
    override val useCssPixels = true
    private val brand = StandbyForegroundKit.brandView(context)
    private val receiverLabel = TextView(context).apply {
        tag = StandbyForegroundTags.RECEIVER_LABEL
        text = context.getString(R.string.standby_foreground_receiver_label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        letterSpacing = 0.22f
        setTextColor(Color.parseColor("#CBD0CB"))
        gravity = Gravity.LEFT
    }
    private val receiverName: TextView = StandbyForegroundKit.receiverNameView(context).apply {
        gravity = Gravity.LEFT
        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        letterSpacing = 0.10f
        setTextColor(0xFFF1F1EC.toInt())
    }
    private val eyebrow = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_EYEBROW
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        letterSpacing = 0.12f
        typeface = Typeface.create(Typeface.DEFAULT, 450, false)
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.parseColor("#E0E4DF"))
    }
    private val title = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_TITLE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, StandbyForegroundKit.TITLE_TEXT_SP)
        letterSpacing = 0.12f
        gravity = Gravity.CENTER_HORIZONTAL
        typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        setTextColor(Color.parseColor("#F1F1EC"))
    }
    private val detail = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_DETAIL
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.06f
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.parseColor("#E1E3DE"))
    }
    private val controls = StandbyForegroundControls(context, StandbyControlTheme.HARDLINE)
    private val statusDot = StandbyStatusDot(context)
    private val statusRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(statusDot, LinearLayout.LayoutParams(dp(6f), dp(6f)))
        addView(eyebrow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(9f)
        })
    }
    private var lastGeometry: HardlineForegroundPixelRects? = null
    private var geometryDelivered = false
    private var sourceReceiverWidth = 0
    private var ambientX = 0f
    private var ambientY = 0f

    /** The two fixed rows and the independent footer slots in foreground pixels. */
    private data class InsetLanes(val band: Rect, val header: Rect, val receiver: Rect?)

    private var insetLanes: InsetLanes? = null

    /** Receives changed visible rectangles, or null when the geometry becomes unavailable. */
    var onForegroundGeometryChanged: ((HardlineForegroundPixelRects?) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            geometryDelivered = false
            if (value != null && !isMotionContentVisible() && !isInLayout && !foregroundLayoutInProgress) {
                onForegroundGeometryCheck()
            } else {
                invalidate()
            }
        }

    private val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF090909.toInt())
        addView(View(context).apply { setBackgroundColor(0xFFEEEEEE.toInt()) }, LinearLayout.LayoutParams(dp(2f), 0))
        addView(brand, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10f) })
    }
    private val receiver = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF090909.toInt())
        addView(View(context).apply { setBackgroundColor(0xFFEEEEEE.toInt()) }, LinearLayout.LayoutParams(dp(2f), 0))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(receiverLabel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(receiverName, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10f) })
    }
    private val centerBand = object : LinearLayout(context) {
        private val rule = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEEEEA.toInt()
            strokeWidth = dp(1f).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            rule.strokeWidth = unit(1f)
            canvas.drawLine(0f, rule.strokeWidth / 2f, width.toFloat(), rule.strokeWidth / 2f, rule)
            canvas.drawLine(0f, height.toFloat() - rule.strokeWidth / 2f, width.toFloat(), height.toFloat() - rule.strokeWidth / 2f, rule)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setWillNotDraw(false)
        setBackgroundColor(0xFF090909.toInt())
        setPadding(dp(52f), dp(14f), dp(52f), dp(14f))
        addView(statusRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14f)
        })
        addView(title)
        addView(detail, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })
    }

    private fun dp(value: Float): Int = viewport.roundedPx(value)

    init {
        clipToPadding = false
        motionLayer.addView(headerRow)
        motionLayer.addView(centerBand)
        motionLayer.addView(receiver)
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** @param centerPx Host-computed handle center for the pending child layout, in stage pixels. */
    internal fun prepareLegacyHandleForResize(centerPx: Float?) {
        controls.setHardlineHandleCenter(centerPx, layoutPending = true)
    }

    /** Applies host-owned drift and the full-stage handle center without moving dock or notice anchors. */
    internal fun setLegacyAmbientOffset(xPx: Float, yPx: Float, handleCenterPx: Float?) {
        val moved = if (insetLanes == null) motionLayer.translationX != xPx || motionLayer.translationY != yPx
            else ambientX != xPx || ambientY != yPx
        ambientX = xPx
        ambientY = yPx
        val handleMoved = controls.setHardlineHandleCenter(handleCenterPx)
        if (moved || handleMoved) applyAmbientOffset()
        if ((moved || handleMoved) && isLaidOut && !isLayoutRequested && !foregroundLayoutInProgress) {
            controls.avoidForegroundObstacles(listOf(statusRow, title, detail), listOf(headerRow, receiver))
            onForegroundGeometryCheck()
        }
    }

    private fun updateScaledChildren() {
        StandbyForegroundKit.updateBrand(brand, viewport)
        StandbyForegroundKit.updateCssText(receiverLabel, 8f, viewport)
        StandbyForegroundKit.updateCssText(receiverName, StandbyForegroundKit.RECEIVER_TEXT_SP, viewport)
        StandbyForegroundKit.updateCssText(eyebrow, 9f, viewport)
        StandbyForegroundKit.updateCssText(title, StandbyForegroundKit.TITLE_TEXT_SP, viewport, 1.2f)
        StandbyForegroundKit.updateCssText(detail, 11f, viewport)
        fun size(view: View, width: Int, height: Int) {
            val params = view.layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                view.layoutParams = params
            }
        }
        fun margin(view: View, left: Int) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            if (params.leftMargin != left) {
                params.leftMargin = left
                view.layoutParams = params
            }
        }
        size(statusDot, dp(6f), dp(6f))
        statusDot.setOutlineWidth(dp(1f))
        margin(eyebrow, dp(9f))
        size(headerRow.getChildAt(0), dp(2f), 0)
        margin(brand, dp(10f))
        headerRow.setPadding(0, 0, dp(12f), 0)
        size(receiver.getChildAt(0), dp(2f), 0)
        margin(receiver.getChildAt(1), dp(10f))
        val labelParams = receiverLabel.layoutParams as LinearLayout.LayoutParams
        if (labelParams.bottomMargin != dp(3f)) {
            labelParams.bottomMargin = dp(3f)
            receiverLabel.layoutParams = labelParams
        }
        val statusParams = statusRow.layoutParams as LinearLayout.LayoutParams
        val bottomMargin = dp(14f)
        if (statusParams.bottomMargin != bottomMargin) {
            statusParams.bottomMargin = bottomMargin
            statusRow.layoutParams = statusParams
        }
        centerBand.invalidate()
    }

    override fun render(
        state: StandbyForegroundState,
        title: String,
        eyebrow: String,
        detail: String,
        callbacks: StandbyForegroundCallbacks,
    ) {
        super.render(state, title, eyebrow, detail, callbacks)
        receiverLabel.text = context.getString(R.string.standby_foreground_receiver_label)
        receiverName.text = state.receiverName
        receiverName.visibility = if (state.receiverName.isBlank()) View.GONE else View.VISIBLE
        receiver.visibility = receiverName.visibility
        receiverName.contentDescription = state.receiverName
        statusDot.setAdvertising(state.advertisingEnabled)
        replaceCopy(this.eyebrow, eyebrow, hardline = true)
        replaceCopy(this.title, title, hardline = true)
        this.detail.text = ""
        this.detail.visibility = View.GONE
        controls.update(state, callbacks)
        onFailureChanged(state.failureCause, state.failureText)
    }

    override fun onFailureChanged(cause: me.trinitrix.mirax.session.PrePlayFailureCause?, text: String) {
        controls.updateFailure(cause, text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        updateViewport(viewportWidth, viewportHeight)
        updateScaledChildren()
        controls.safeBottomInset = safeBottom
        controls.updateViewport(viewportWidth, viewportHeight)
        val narrow = viewport.narrow
        receiverName.letterSpacing = if (narrow) 0.03f else 0.10f
        receiverLabel.letterSpacing = if (narrow) 0.10f else 0.22f
        eyebrow.letterSpacing = if (narrow) 0.08f else 0.12f
        val centerInset = dp(if (narrow) 16f else 52f) + maxOf(paddingLeft, paddingRight)
        val centerVertical = dp(if (viewport.shortLandscape) 14f else 18f) + dp(1f)
        if (centerBand.paddingLeft != centerInset || centerBand.paddingTop != centerVertical) {
            centerBand.setPadding(centerInset, centerVertical, centerInset, centerVertical)
        }
        val motionParams = motionLayer.layoutParams as LayoutParams
        motionParams.leftMargin = -paddingLeft
        motionParams.rightMargin = -paddingRight
        val detailParams = detail.layoutParams as LinearLayout.LayoutParams
        val detailMargin = dp(if (viewport.logicalWidth <= 1000f || viewport.compact) 10f else 18f)
        if (detailParams.topMargin != detailMargin) {
            detailParams.topMargin = detailMargin
            detail.layoutParams = detailParams
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val h = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val edge = sceneEdge(measuredWidth, measuredHeight)
        measureForeground(headerRow, w - 2 * edge, h)
        val portraitReceiver = viewport.logicalWidth <= 440f || (narrow && viewport.portrait)
        sourceReceiverWidth = if (portraitReceiver) w - 2 * edge - dp(24f) else measuredWidth / 2 - paddingLeft - edge - dp(54f)
        measureReceiver(sourceReceiverWidth, h)
        measureSideRule(headerRow)
        measureForeground(centerBand, measuredWidth, h, fillWidth = true)
    }

    /**
     * @param width Maximum receiver width in native pixels.
     * @param height Maximum receiver height in native pixels.
     */
    private fun measureReceiver(width: Int, height: Int) {
        measureForeground(receiver, width, height)
        measureSideRule(receiver)
    }

    /** @param block Masked block whose side rule follows its measured height. */
    private fun measureSideRule(block: LinearLayout) {
        block.getChildAt(0).measure(MeasureSpec.makeMeasureSpec(dp(2f), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(block.measuredHeight, MeasureSpec.EXACTLY))
    }

    /** Returns actual visible view rectangles, with empty rectangles for hidden optional views. */
    fun foregroundRectsInViewPixels(): HardlineForegroundPixelRects? {
        val header = visibleForegroundRect(headerRow) ?: return null
        val center = visibleForegroundRect(centerBand) ?: return null
        return HardlineForegroundPixelRects(
            header = header,
            centerBand = center,
            receiver = visibleForegroundRect(receiver) ?: Rect(),
            dock = visibleForegroundRect(controls.broadcast, allowPendingDockNoticeLayout = true) ?: Rect(),
        )
    }

    override fun onForegroundGeometryCheck() {
        val callback = onForegroundGeometryChanged ?: return
        val geometry = foregroundRectsInViewPixels()
        if (!geometryDelivered || geometry != lastGeometry) {
            geometryDelivered = true
            lastGeometry = geometry?.copyRects()
            callback(geometry?.copyRects())
        }
    }

    private fun HardlineForegroundPixelRects.copyRects() = HardlineForegroundPixelRects(
        Rect(header), Rect(centerBand), Rect(receiver), Rect(dock),
    )

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val h = motionLayer.height
        val previous = insetLanes
        if (previous == null) {
            ambientX = motionLayer.translationX
            ambientY = motionLayer.translationY
        } else {
            for (child in listOf(centerBand, headerRow, receiver)) {
                child.translationX = 0f
                child.translationY = 0f
            }
            if (previous.receiver != null && receiver.visibility == VISIBLE) measureReceiver(sourceReceiverWidth, h)
        }
        val edge = sceneEdge(width, height)
        placeForeground(headerRow, paddingLeft + edge, edge)
        val portraitReceiver = viewport.logicalWidth <= 440f || (viewport.narrow && viewport.portrait)
        if (receiver.visibility == VISIBLE) {
            val receiverBottom = if (portraitReceiver) {
                h - (dp(12f) - safeBottom).coerceAtLeast(0) - controls.actionSize - dp(12f)
            } else {
                h - edge
            }
            placeForeground(receiver, paddingLeft + edge, receiverBottom - receiver.measuredHeight)
        }
        val centerTop = (height * 0.47f).toInt() - paddingTop - centerBand.measuredHeight / 2
        placeForeground(centerBand, 0, centerTop)
        insetLanes = if (viewport.portrait && portraitReceiver) placeInsetLayout(edge) else null
        if (previous != null || insetLanes != null) applyAmbientOffset()
        controls.avoidForegroundObstacles(listOf(statusRow, title, detail), listOf(headerRow, receiver))
        completeForegroundLayout()
    }

    /**
     * @param edge Normal scene margin in native pixels.
     * @return The approved two-row layout only if the collided portrait roles still fit.
     */
    private fun placeInsetLayout(edge: Int): InsetLanes? {
        if (safeTop == 0 && safeBottom == 0) return null
        val dock = controls.foregroundDockBounds ?: return null
        val band = foregroundLayoutBounds(centerBand)
        val showReceiver = receiver.visibility == VISIBLE
        if (!Rect.intersects(band, dock) &&
            !(showReceiver && Rect.intersects(band, foregroundLayoutBounds(receiver)))
        ) return null
        val spare = dock.top - paddingTop - band.height()
        if (spare < 0) return null
        val bandLane = Rect(0, paddingTop, width, dock.top - minOf(dp(1f), spare))

        val menu = controls.foregroundMenuBounds
        if (menu != null) {
            // The stationary full-width mask may sit behind the handle, but no copy may cross its strip.
            val strip = Rect(band.left + menu.left, band.top, band.left + menu.right, band.bottom)
            if (intersectsForegroundLine(title, strip) || Rect.intersects(foregroundLayoutBounds(statusRow), strip)) return null
        }
        val headerGap = minOf(dp(1f), (dock.left - paddingLeft - headerRow.width) / 2).coerceAtLeast(0)
        val headerLane = Rect(paddingLeft + headerGap, dock.top, dock.left - headerGap, dock.bottom)
        if (headerRow.width > headerLane.width() ||
            headerRow.height > dock.bottom - maxOf(dock.top, menu?.bottom ?: dock.top)
        ) return null

        val receiverLane = if (showReceiver) Rect(dock.right + dp(6f), dock.top, width - paddingRight - edge, dock.bottom)
            else null
        if (receiverLane != null) {
            if (receiverLane.width() <= dp(12f)) return null
            val sourceBottom = receiver.bottom
            measureReceiver(minOf(sourceReceiverWidth, receiverLane.width()), motionLayer.height)
            if (receiver.measuredHeight > receiverLane.height()) {
                measureReceiver(sourceReceiverWidth, motionLayer.height)
                placeForeground(receiver, receiver.left, sourceBottom - receiver.measuredHeight)
                return null
            }
            placeForeground(receiver, receiverLane.left - motionLayer.left,
                dock.centerY() - motionLayer.top - receiver.measuredHeight / 2)
        }
        placeForeground(centerBand, -motionLayer.left, bandLane.top - motionLayer.top)
        placeForeground(headerRow, headerLane.centerX() - motionLayer.left - headerRow.measuredWidth / 2,
            dock.centerY() - motionLayer.top - headerRow.measuredHeight / 2)
        return InsetLanes(bandLane, headerLane, receiverLane)
    }

    /** Clips each role's real drift to the available space without moving either touch target. */
    private fun applyAmbientOffset() {
        val lanes = insetLanes
        motionLayer.translationX = if (lanes == null) ambientX else 0f
        motionLayer.translationY = if (lanes == null) ambientY else 0f
        if (lanes == null) return
        val headerLane = Rect(lanes.header)
        controls.foregroundMenuBounds?.let { headerLane.top = maxOf(headerLane.top, it.bottom) }
        if (headerRow.height > headerLane.height()) {
            // The new handle position invalidated the fitted row; do not publish its old geometry.
            requestLayout()
            return
        }
        driftWithin(centerBand, lanes.band, ambientX, ambientY)
        driftWithin(headerRow, headerLane, ambientX, ambientY)
        lanes.receiver?.let { driftWithin(receiver, it, ambientX, ambientY) }
    }

    /**
     * Maps the actual visible pixel rectangles into the 1600x1000 slice space.
     *
     * @return stage-unit rectangles, empty for hidden optional views, or null before visible layout.
     */
    fun foregroundRectsInStageSpace(): HardlineForegroundRects? {
        val pixels = foregroundRectsInViewPixels() ?: return null
        val slice = stageSlice(width.toDouble(), height.toDouble())
        if (slice.scale <= 0.0) return null
        fun toStage(rect: Rect): Rect = if (rect.isEmpty) Rect() else Rect(
            kotlin.math.floor(slice.toStageX(rect.left.toDouble())).toInt(),
            kotlin.math.floor(slice.toStageY(rect.top.toDouble())).toInt(),
            kotlin.math.ceil(slice.toStageX(rect.right.toDouble())).toInt(),
            kotlin.math.ceil(slice.toStageY(rect.bottom.toDouble())).toInt(),
        )
        return HardlineForegroundRects(
            header = toStage(pixels.header),
            centerBand = toStage(pixels.centerBand),
            receiver = toStage(pixels.receiver),
            dock = toStage(pixels.dock),
        )
    }
}

/** Measured Hardline rectangles in foreground-view pixels. */
data class HardlineForegroundPixelRects(
    val header: Rect,
    val centerBand: Rect,
    val receiver: Rect,
    val dock: Rect,
)

/**
 * Measured Hardline foreground rects in original 1600x1000 stage space.
 *
 * @param header brand row bounds.
 * @param centerBand status band bounds.
 * @param receiver receiver section bounds, empty when hidden.
 * @param dock broadcast control bounds, empty when hidden.
 */
data class HardlineForegroundRects(
    val header: Rect,
    val centerBand: Rect,
    val receiver: Rect,
    val dock: Rect,
)
