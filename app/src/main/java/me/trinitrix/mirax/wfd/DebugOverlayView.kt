package me.trinitrix.mirax.wfd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import me.trinitrix.mirax.session.PicturePlacement
import kotlin.math.abs

/**
 * Debug overlay showing stream info, frame tree, and real-time plots with axes
 * for FPS, decoder latency, and bitrate. Top-right when enabled in settings.
 * Viewport is height-capped; drag vertically to scroll, drag the title to move.
 */
class DebugOverlayView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val lineHeight = (15f * density).toInt()
    private val padding = (10f * density).toInt()
    private val sectionGap = (6f * density).toInt()
    private val axisLabelWidth = (36f * density).toInt()
    private val plotHeight = (56f * density).toInt()
    private val plotWidth = (180f * density).toInt()

    private val textPaint = Paint(ANTI_ALIAS).apply {
        color = Color.WHITE
        textSize = 11f * density
    }
    private val axisPaint = Paint(ANTI_ALIAS).apply {
        color = Color.argb(200, 200, 200, 200)
        textSize = 9f * density
    }
    private val bgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0) }
    private val accentPaint = Paint(ANTI_ALIAS).apply {
        color = Color.parseColor("#5B9FD4")
        textSize = 11f * density
    }
    private val titlePaint = Paint(ANTI_ALIAS).apply {
        color = Color.parseColor("#8EC5EF")
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val fpsPlotPaint = Paint(ANTI_ALIAS).apply {
        color = Color.parseColor("#00FF00")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val latencyPlotPaint = Paint(ANTI_ALIAS).apply {
        color = Color.parseColor("#FFB300")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val bandwidthPlotPaint = Paint(ANTI_ALIAS).apply {
        color = Color.parseColor("#00BFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val plotGridPaint = Paint(ANTI_ALIAS).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val plotBgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }

    private var decoderStats: H264SurfaceDecoder.DebugStats? = null
    private var frameTree: List<FrameNode>? = null
    private var cropRect: PicturePlacement.Rect? = null
    private var panelWidth = 0
    private var panelHeight = 0
    private var pictureScale = "PROPORTIONAL"

    private val maxHistoryPoints = 60
    private val sampleIntervalMs = 500
    private val fpsHistory = mutableListOf<Float>()
    private val latencyHistory = mutableListOf<Float>()
    private val bandwidthHistory = mutableListOf<Float>()

    private var contentHeightPx = 0
    private var scrollY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalY = 0f
    private var downScrollY = 0f
    private var downTranslationX = 0f
    private var downTranslationY = 0f
    private var gesture: Gesture = Gesture.NONE

    private enum class Gesture { NONE, SCROLL, MOVE }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = padding * 2 + axisLabelWidth + plotWidth + (8f * density).toInt()
        contentHeightPx = estimateContentHeight() + padding
        val parentH = (parent as? android.view.View)?.height?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val maxViewport = (parentH * 0.88f).toInt().coerceAtLeast(lineHeight * 12)
        val viewportH = contentHeightPx.coerceAtMost(maxViewport)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(viewportH, heightMeasureSpec),
        )
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalY = event.y
                downScrollY = scrollY
                downTranslationX = translationX
                downTranslationY = translationY
                gesture = Gesture.NONE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (gesture == Gesture.NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // Title row ≈ drag-to-move; elsewhere scroll content.
                    gesture = if (downLocalY < lineHeight * 2.5f + padding) {
                        Gesture.MOVE
                    } else {
                        Gesture.SCROLL
                    }
                }
                when (gesture) {
                    Gesture.SCROLL -> {
                        scrollY = (downScrollY - dy).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                    Gesture.MOVE -> {
                        translationX = downTranslationX + dx
                        translationY = downTranslationY + dy
                    }
                    Gesture.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesture = Gesture.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun updateDecoderStats(stats: H264SurfaceDecoder.DebugStats) {
        decoderStats = stats
        push(fpsHistory, stats.currentFps.toFloat())
        push(latencyHistory, stats.submitToFrameMs.toFloat().coerceAtLeast(0f))
        push(bandwidthHistory, stats.currentBitrateKbps.toFloat().coerceAtLeast(0f))
        val nextContent = estimateContentHeight() + padding
        if (nextContent != contentHeightPx) {
            contentHeightPx = nextContent
            requestLayout()
        }
        invalidate()
    }

    fun updateFrameTree(tree: List<FrameNode>) {
        frameTree = tree
        contentHeightPx = estimateContentHeight() + padding
        requestLayout()
        invalidate()
    }

    fun updateLayout(panelW: Int, panelH: Int, scale: String) {
        panelWidth = panelW
        panelHeight = panelH
        pictureScale = scale
        invalidate()
    }

    fun updateCrop(rect: PicturePlacement.Rect?) {
        cropRect = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, -scrollY)

        var y = padding
        drawText(canvas, "DEBUG OVERLAY  · drag title to move", titlePaint, padding, y)
        y += lineHeight + sectionGap

        drawSectionHeader(canvas, "STREAM INFO", y)
        y += lineHeight
        decoderStats?.let { s ->
            val yArr = intArrayOf(y)
            drawKV(canvas, "Decoder", s.decoderName, yArr)
            drawKV(canvas, "Input", "${s.inputWidth}x${s.inputHeight}@${s.inputFps}", yArr)
            drawKV(canvas, "Output", "${s.outputWidth}x${s.outputHeight}", yArr)
            drawKV(canvas, "FPS", "${s.currentFps} / ${s.inputFps}", yArr)
            drawKV(canvas, "Latency", "${s.submitToFrameMs} ms", yArr)
            drawKV(canvas, "Bitrate", formatBitrate(s.currentBitrateKbps), yArr)
            drawKV(canvas, "Frames", "${s.framesDecoded} dec, ${s.pendingFrames} q", yArr)
            drawKV(canvas, "Keyframe", if (s.awaitingKeyframe) "WAITING" else "OK", yArr)
            y = yArr[0]
        }
        y += sectionGap

        drawSectionHeader(canvas, "REAL-TIME PLOTS", y)
        y += lineHeight
        y = drawSeriesPlot(
            canvas, y, "FPS", fpsHistory, fpsPlotPaint,
            yMaxHint = (decoderStats?.inputFps ?: 60).toFloat().coerceAtLeast(30f),
            unit = "",
        )
        y += sectionGap / 2
        y = drawSeriesPlot(
            canvas, y, "LATENCY", latencyHistory, latencyPlotPaint,
            yMaxHint = 50f,
            unit = "ms",
        )
        y += sectionGap / 2
        y = drawSeriesPlot(
            canvas, y, "BITRATE", bandwidthHistory, bandwidthPlotPaint,
            yMaxHint = 1000f,
            unit = "kbps",
        )
        y += sectionGap

        drawSectionHeader(canvas, "FRAME TREE", y)
        y += lineHeight
        frameTree?.forEach { node ->
            val yArr = intArrayOf(y)
            drawFrameNode(canvas, node, 0, yArr)
            y = yArr[0]
        } ?: run {
            drawText(canvas, "No frame data", textPaint, padding, y)
            y += lineHeight
        }
        y += sectionGap

        drawSectionHeader(canvas, "LAYOUT", y)
        y += lineHeight
        val yArr = intArrayOf(y)
        drawKV(canvas, "Panel", "${panelWidth}x${panelHeight}", yArr)
        drawKV(canvas, "Scale", pictureScale, yArr)
        cropRect?.let {
            drawKV(
                canvas,
                "Crop",
                "L${it.left} T${it.top} R${it.left + it.width} B${it.top + it.height}",
                yArr,
            )
        }
        canvas.restore()

        if (maxScroll() > 0f) {
            drawScrollChrome(canvas)
        }
    }

    private fun maxScroll(): Float =
        (contentHeightPx - height).toFloat().coerceAtLeast(0f)

    private fun drawScrollChrome(canvas: Canvas) {
        val trackH = height - padding * 2f
        if (trackH <= 0f || contentHeightPx <= 0) return
        val thumbH = (trackH * height / contentHeightPx).coerceIn(24f * density, trackH)
        val maxTrack = trackH - thumbH
        val thumbTop = padding + if (maxScroll() > 0f) {
            maxTrack * (scrollY / maxScroll())
        } else {
            0f
        }
        val x = width - 3f * density
        canvas.drawRect(x, thumbTop, x + 2f * density, thumbTop + thumbH, accentPaint)
        if (scrollY < maxScroll() - 1f) {
            drawText(canvas, "▼ scroll", axisPaint, padding, height - lineHeight)
        }
    }

    private fun estimateContentHeight(): Int {
        var h = 0
        h += lineHeight + sectionGap // title
        h += lineHeight * 9 // stream info
        h += sectionGap
        h += lineHeight // plots header
        h += (plotHeight + lineHeight + sectionGap / 2) * 3 // three plots + x labels
        h += sectionGap
        val treeLines = 1 + (frameTree?.sumOf { 1 + it.children.size } ?: 1)
        h += lineHeight * (1 + treeLines)
        h += sectionGap
        h += lineHeight * 4 // layout
        return h + padding
    }

    private fun drawSeriesPlot(
        canvas: Canvas,
        topY: Int,
        title: String,
        history: List<Float>,
        linePaint: Paint,
        yMaxHint: Float,
        unit: String,
    ): Int {
        val current = history.lastOrNull() ?: 0f
        val histMax = history.maxOrNull() ?: 0f
        val yMax = maxOf(yMaxHint, histMax * 1.15f, 1f)
        val titleText = if (unit.isEmpty()) {
            "$title  ${current.toInt()}"
        } else {
            "$title  ${formatAxis(current)} $unit"
        }
        drawText(canvas, titleText, accentPaint, padding, topY)

        val plotTop = (topY + lineHeight).toFloat()
        val plotBottom = plotTop + plotHeight
        val plotLeft = (padding + axisLabelWidth).toFloat()
        val plotRight = plotLeft + plotWidth

        canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, plotBgPaint)

        val gridLines = 2
        for (i in 0..gridLines) {
            val t = i.toFloat() / gridLines
            val gy = plotBottom - (plotBottom - plotTop) * t
            canvas.drawLine(plotLeft, gy, plotRight, gy, plotGridPaint)
            val label = formatAxis(yMax * t)
            canvas.drawText(label, padding.toFloat(), gy + axisPaint.textSize * 0.35f, axisPaint)
        }

        // Y unit at top of axis column
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, padding.toFloat(), plotTop - 2f, axisPaint)
        }

        drawPlotLine(canvas, history, plotLeft, plotTop, plotRight, plotBottom, 0f, yMax, linePaint)

        // X axis ticks: left / mid / now
        val windowSec = (history.size.coerceAtLeast(1) * sampleIntervalMs) / 1000f
        val xLabelY = (plotBottom + axisPaint.textSize + 2f).toInt()
        val xBaseline = xLabelY.toFloat() + axisPaint.textSize
        val leftLabel = "-${formatAxis(windowSec)}s"
        val midLabel = "-${formatAxis(windowSec / 2f)}s"
        canvas.drawText(leftLabel, plotLeft, xBaseline, axisPaint)
        val midX = (plotLeft + plotRight) / 2f - axisPaint.measureText(midLabel) / 2f
        canvas.drawText(midLabel, midX, xBaseline, axisPaint)
        canvas.drawText("now", plotRight - axisPaint.measureText("now"), xBaseline, axisPaint)
        // Vertical tick marks on the plot bottom edge
        canvas.drawLine(plotLeft, plotBottom, plotLeft, plotBottom + 4f * density, axisPaint)
        canvas.drawLine((plotLeft + plotRight) / 2f, plotBottom, (plotLeft + plotRight) / 2f, plotBottom + 4f * density, axisPaint)
        canvas.drawLine(plotRight, plotBottom, plotRight, plotBottom + 4f * density, axisPaint)

        return xLabelY + lineHeight
    }

    private fun drawPlotLine(
        canvas: Canvas,
        history: List<Float>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        minVal: Float,
        maxVal: Float,
        paint: Paint,
    ) {
        if (history.size < 2) return
        val range = maxVal - minVal
        if (range <= 0f) return
        val stepX = (right - left) / (maxHistoryPoints - 1).toFloat()
        val path = Path()
        history.forEachIndexed { i, value ->
            val x = left + i * stepX
            val normalized = ((value - minVal) / range).coerceIn(0f, 1f)
            val y = bottom - normalized * (bottom - top)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSectionHeader(canvas: Canvas, text: String, y: Int) {
        drawText(canvas, text, accentPaint, padding, y)
    }

    private fun drawKV(canvas: Canvas, key: String, value: Any, varargY: IntArray) {
        drawText(canvas, "$key:", textPaint, padding, varargY[0])
        drawText(canvas, value.toString(), textPaint, padding + (72f * density).toInt(), varargY[0])
        varargY[0] += lineHeight
    }

    private fun drawText(canvas: Canvas, text: String, paint: Paint, x: Int, y: Int) {
        canvas.drawText(text, x.toFloat(), y + paint.textSize, paint)
    }

    private fun drawFrameNode(canvas: Canvas, node: FrameNode, indent: Int, varargY: IntArray) {
        val x = padding + indent * (12f * density).toInt()
        val prefix = if (node.children.isNotEmpty()) "▼ " else "  "
        drawText(canvas, "$prefix${node.name} (${node.width}x${node.height})", textPaint, x, varargY[0])
        varargY[0] += lineHeight
        node.children.forEach { drawFrameNode(canvas, it, indent + 1, varargY) }
    }

    private fun push(history: MutableList<Float>, value: Float) {
        history.add(value)
        while (history.size > maxHistoryPoints) history.removeAt(0)
    }

    private fun formatBitrate(kbps: Long): String {
        return if (kbps >= 1000) {
            String.format("%.1f Mbps", kbps / 1000.0)
        } else {
            "$kbps kbps"
        }
    }

    private fun formatAxis(value: Float): String {
        return when {
            value >= 1000f -> String.format("%.1fk", value / 1000f)
            value >= 100f -> value.toInt().toString()
            value >= 10f -> String.format("%.0f", value)
            else -> String.format("%.1f", value)
        }
    }

    data class FrameNode(
        val name: String,
        val width: Int,
        val height: Int,
        val children: List<FrameNode> = emptyList(),
    )

    private companion object {
        private const val ANTI_ALIAS = Paint.ANTI_ALIAS_FLAG
    }
}
