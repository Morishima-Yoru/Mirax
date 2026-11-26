package me.trinitrix.mirax.standbyprobe.render

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import me.trinitrix.mirax.R

/**
 * Software layout readout for one probe case.
 *
 * Produced by CPU-side [StaticLayout] measurement only and never claims GPU work.
 *
 * @param lineCount Measured line count.
 * @param widthPx Layout width used for measurement.
 * @param heightPx Measured layout height.
 * @param ellipsized True when the ellipsis character was applied.
 * @param referenceWidthPx Measured width of the reference single line.
 * @param referenceHeightPx Measured height of the reference single line.
 */
data class LayoutReadout(
    val lineCount: Int,
    val widthPx: Int,
    val heightPx: Int,
    val ellipsized: Boolean,
    val referenceWidthPx: Int,
    val referenceHeightPx: Int,
)

/**
 * Explicit evidence of what the attached draw actually observed.
 *
 * Null [canvasWasHardwareAccelerated] means no draw happened yet, which tests
 * must treat as unavailable evidence rather than a pass.
 *
 * @param observed True once [onDraw] ran while attached.
 * @param drawCount Total number of [onDraw] executions while attached.
 * @param canvasWasHardwareAccelerated Value of [Canvas.isHardwareAccelerated].
 * @param viewWasHardwareAccelerated Value of [View.isHardwareAccelerated].
 */
data class DrawEvidence(
    val observed: Boolean,
    val drawCount: Int,
    val canvasWasHardwareAccelerated: Boolean?,
    val viewWasHardwareAccelerated: Boolean?,
)

/**
 * Combined probe snapshot separating CPU measurement from GPU draw.
 *
 * @param layout Latest software measurement.
 * @param draw Latest attached-draw evidence.
 * @param frameIntervalsNanos Observed frame deltas only, never a performance claim.
 * @param systemBars Last applied system-bar insets, null before delivery.
 */
data class ProbeSnapshot(
    val layout: LayoutReadout?,
    val draw: DrawEvidence,
    val frameIntervalsNanos: List<Long>,
    val systemBars: Insets?,
)

/**
 * Single simple canvas probe plus native hypothesis bound.
 *
 * Draws both reference single line at 20px and raw receiver name ellipsized.
 * Software [StaticLayout] measurement and attached GPU draw are recorded
 * separately so tests cannot mistake an offscreen bitmap for acceleration.
 */
class StandbyRenderProbeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val referencePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        // shortcut: raw px reference for prototype parity, switch to sp when product type lands.
        textSize = REFERENCE_TEXT_PX
        // Probe-only explicit white; keeps PixelCopy readable on dark background.
        color = Color.WHITE
    }
    private val receiverPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = REFERENCE_TEXT_PX
        // Probe-only explicit white; keeps PixelCopy readable on dark background.
        color = Color.WHITE
    }
    private val textBounds = Rect()
    private var receiverLayout: StaticLayout? = null
    private var referenceLayout: StaticLayout? = null
    private var lastLayout: LayoutReadout? = null
    private var drawCount = 0
    private var drawEvidence = DrawEvidence(
        observed = false,
        drawCount = 0,
        canvasWasHardwareAccelerated = null,
        viewWasHardwareAccelerated = null,
    )
    private var lastSystemBars: Insets? = null
    private val frameIntervals = ArrayDeque<Long>()
    private var lastFrameNanos: Long? = null
    private var frameProbeRequested = false
    private var frameCallbackScheduled = false
    private var frameLoopActive = false

    private var customReceiverName: CharSequence? = null
    private var customReferenceText: CharSequence? = null
    private var localizedContext: Context = context

    /** Active probe case, fixed logical widths except reference. */
    var probeCase: ProbeLayoutCase = ProbeLayoutCase.REFERENCE
        set(value) {
            field = value
            refreshLayouts(width.coerceAtLeast(0))
            invalidate()
        }

    /** Raw long receiver name under test, deliberately ellipsized. */
    var receiverName: CharSequence
        get() = customReceiverName ?: localizedContext.getString(R.string.standby_probe_long_name)
        set(value) {
            customReceiverName = value
            refreshLayouts(width.coerceAtLeast(0))
            invalidate()
        }

    /** Reference single-line text at the prototype source size. */
    var referenceText: CharSequence
        get() = customReferenceText ?: localizedContext.getString(R.string.standby_probe_reference)
        set(value) {
            customReferenceText = value
            refreshLayouts(width.coerceAtLeast(0))
            invalidate()
        }

    /** True while the frame callback loop is scheduled. */
    val isFrameLoopActive: Boolean
        get() = frameLoopActive

    /** Observed frame deltas in nanos, newest last, capped for determinism. */
    val frameIntervalsNanos: List<Long>
        get() = frameIntervals.toList()

    /** Last applied system-bar insets, null before first delivery. */
    val lastSystemBarsInsets: Insets?
        get() = lastSystemBars

    /** Fixed prototype reference size in px, never auto-shrunk. */
    val referenceTextPx: Float
        get() = referencePaint.textSize

    private val frameCallback: Choreographer.FrameCallback =
        Choreographer.FrameCallback { frameTimeNanos ->
            frameCallbackScheduled = false
            val previous = lastFrameNanos
            if (previous != null) {
                val delta = frameTimeNanos - previous
                if (delta >= 0) {
                    frameIntervals.addLast(delta)
                    while (frameIntervals.size > MAX_FRAME_SAMPLES) {
                        frameIntervals.removeFirst()
                    }
                } else {
                    Log.w(TAG, "Non-monotonic frame time ignored.")
                }
            }
            lastFrameNanos = frameTimeNanos
            frameLoopActive = true
            scheduleFrameIfNeeded()
        }

    init {
        contentDescription = context.getString(R.string.standby_probe_overlay_desc)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * Measures text on CPU without drawing.
     *
     * @param text Text to lay out.
     * @param widthPx Layout width, must be non-negative.
     * @return Readout describing line, ellipsis and bounds behavior.
     */
    fun measureLayout(text: CharSequence, widthPx: Int): LayoutReadout {
        val insetsHorizontal = (lastSystemBars?.left ?: 0) + (lastSystemBars?.right ?: 0)
        val safeWidth = (widthPx - insetsHorizontal).coerceAtLeast(0)
        val layoutWidth = if (safeWidth > 0) safeWidth else DEFAULT_MEASURE_WIDTH_PX
        val layout = buildLayout(text, receiverPaint, layoutWidth, ellipsize = true)
        val reference = buildLayout(referenceText, referencePaint, layoutWidth, ellipsize = false)
        return LayoutReadout(
            lineCount = layout.lineCount,
            widthPx = layout.width,
            heightPx = layout.height,
            ellipsized = layout.getEllipsisCount(0) > 0,
            referenceWidthPx = reference.width,
            referenceHeightPx = reference.height,
        )
    }

    /**
     * Returns the latest snapshot separating CPU measurement from GPU draw.
     *
     * @return Snapshot, or null layout until first measurement.
     */
    fun currentSnapshot(): ProbeSnapshot {
        return ProbeSnapshot(
            layout = lastLayout,
            draw = drawEvidence,
            frameIntervalsNanos = frameIntervals.toList(),
            systemBars = lastSystemBars,
        )
    }

    /**
     * Starts frame observation without performance claims.
     */
    fun startFrameProbe() {
        frameProbeRequested = true
        lastFrameNanos = null
        scheduleFrameIfNeeded()
    }

    /**
     * Stops frame observation.
     */
    fun stopFrameProbe() {
        frameProbeRequested = false
        cancelScheduledFrame()
        lastFrameNanos = null
    }

    private fun scheduleFrameIfNeeded() {
        if (!frameProbeRequested || frameCallbackScheduled) {
            return
        }
        if (!isAttachedToWindow || visibility != VISIBLE) {
            return
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
        frameCallbackScheduled = true
        frameLoopActive = true
    }

    private fun cancelScheduledFrame() {
        frameCallbackScheduled = false
        frameLoopActive = false
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to remove frame callback.", e)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        cancelScheduledFrame()
        lastFrameNanos = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            cancelScheduledFrame()
        } else {
            scheduleFrameIfNeeded()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            cancelScheduledFrame()
        } else {
            scheduleFrameIfNeeded()
        }
    }

    override fun onSizeChanged(newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, oldHeight)
        // Fixed text size is preserved here; no auto-shrink on resize.
        refreshLayouts(newWidth.coerceAtLeast(0))
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (newConfig != null) {
            localizedContext = context.createConfigurationContext(newConfig)
        }
        // Language and size changes re-measure and invalidate.
        refreshLayouts(width.coerceAtLeast(0))
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        lastSystemBars = bars
        refreshLayouts(width.coerceAtLeast(0))
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Probe-only opaque base; isolates PixelCopy from system theme.
        canvas.drawColor(Color.BLACK)
        drawCount++
        val canvasHw = canvas.isHardwareAccelerated
        val viewHw = isHardwareAccelerated
        drawEvidence = DrawEvidence(
            observed = true,
            drawCount = drawCount,
            canvasWasHardwareAccelerated = canvasHw,
            viewWasHardwareAccelerated = viewHw,
        )
        if (!canvasHw) {
            Log.i(TAG, "Attached draw is not hardware accelerated; recorded as software evidence.")
        }
        val refLayout = referenceLayout ?: refreshLayouts(width.coerceAtLeast(0))
        val recLayout = receiverLayout ?: refreshLayouts(width.coerceAtLeast(0))

        val offsetX = (lastSystemBars?.left ?: paddingLeft).toFloat()
        val offsetY = (lastSystemBars?.top ?: paddingTop).toFloat()

        canvas.save()
        canvas.translate(offsetX, offsetY)
        refLayout.draw(canvas)
        canvas.translate(0f, refLayout.height.toFloat())
        recLayout.draw(canvas)
        canvas.restore()

        referenceText.toString().let { text ->
            referencePaint.getTextBounds(text, 0, text.length, textBounds)
        }
    }

    private fun refreshLayouts(viewWidth: Int): StaticLayout {
        val insetsHorizontal = (lastSystemBars?.left ?: 0) + (lastSystemBars?.right ?: 0)
        val effectiveWidth = probeCase.resolveEffectiveWidth(
            viewWidth = viewWidth,
            insetsHorizontal = insetsHorizontal,
            defaultFallback = DEFAULT_MEASURE_WIDTH_PX,
        )
        referenceLayout = buildLayout(referenceText, referencePaint, effectiveWidth, ellipsize = false)
        val layout = buildLayout(receiverName, receiverPaint, effectiveWidth, ellipsize = true)
        receiverLayout = layout
        lastLayout = LayoutReadout(
            lineCount = layout.lineCount,
            widthPx = layout.width,
            heightPx = layout.height,
            ellipsized = layout.getEllipsisCount(0) > 0,
            referenceWidthPx = referenceLayout?.width ?: 0,
            referenceHeightPx = referenceLayout?.height ?: 0,
        )
        return layout
    }

    private fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        widthPx: Int,
        ellipsize: Boolean,
    ): StaticLayout {
        val safeText = if (text.isEmpty()) " " else text
        val builder = StaticLayout.Builder.obtain(safeText, 0, safeText.length, paint, widthPx)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setMaxLines(1)
        if (ellipsize) {
            builder.setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "StandbyRenderProbe"
        /** Prototype 20px source size, fixed raw px. */
        const val REFERENCE_TEXT_PX = 20f
        private const val DEFAULT_MEASURE_WIDTH_PX = 1080
        private const val MAX_FRAME_SAMPLES = 8
    }
}
