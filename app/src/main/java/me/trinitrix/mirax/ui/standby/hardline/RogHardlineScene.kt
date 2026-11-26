package me.trinitrix.mirax.ui.standby.hardline

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.LocaleList
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ROG-inspired sci-fi slash-channel and streaming data theme component for Hardline.
 *
 * Implements Prototype Theme E (ROG diagonal slash vocabulary without copying logos/assets):
 * - Two 45-degree slash channels with subtle dark translucent fills and bordered rails
 * - Continuously flowing monospace register, memory, and status text streams
 * - Four high-speed accelerating scan traces traveling quadratically along channel borders
 */
class RogHardlineScene internal constructor(
    private val random: () -> Double = { Math.random() },
    private var statusCopy: StatusCopy,
) {
    /** Resource-resolved Row 3 copy seam; production passes View resources without hardcoded fallback. */
    internal class StatusCopy(
        val ready: String,
        val arming: String,
        val wifiPaused: String,
        val frozen: String,
        val connecting: String,
        val failure: String,
        val formatAdvertising: (String) -> String,
    )
    data class EdgeStream(
        val name: String,
        val isUpper: Boolean,
        val isTop: Boolean,
        var gapSeconds: Double = 0.2,
        var durationSeconds: Double = 1.2,
        var elapsedSeconds: Double = 0.0,
        var inShot: Boolean = false,
        var progress: Double = 0.0,
        var position: Double = 0.0,
        var alpha: Double = 0.0,
    )

    /**
     * Slash channel band in the rotated stage.
     *
     * Mutable so the render loop can reuse one instance per band per frame.
     *
     * @param offset band center on the rotated Y axis.
     * @param pxHeight band height in view pixels.
     */
    data class ChannelBand(var offset: Float, var pxHeight: Float) {
        val top: Float get() = offset - pxHeight / 2f
        val bottom: Float get() = offset + pxHeight / 2f
    }

    /**
     * Prototype responsive channel placement, from `design/big-standby-e.css:175-189`.
     *
     * @param originXRatio channel origin X as a fraction of the stage width.
     * @param originYRatio channel origin Y as a fraction of the stage height.
     * @param sizeRatioW channel width as a fraction of the stage width.
     * @param sizeRatioH channel height as a fraction of the stage height.
     */
    private data class Quad(
        val originXRatio: Float,
        val originYRatio: Float,
        val sizeRatioW: Float,
        val sizeRatioH: Float,
    )

    private val upperBand = ChannelBand(0f, 0f)
    private val lowerBand = ChannelBand(0f, 0f)

    val streams: List<EdgeStream> = listOf(
        EdgeStream("upper-top", isUpper = true, isTop = true),
        EdgeStream("upper-bottom", isUpper = true, isTop = false),
        EdgeStream("lower-top", isUpper = false, isTop = true),
        EdgeStream("lower-bottom", isUpper = false, isTop = false),
    )

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(3, 255, 255, 255)
    }

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#393939")
        strokeWidth = 1.5f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * Row 3 typeface style flags, bold per the prototype.
     *
     * Exposed because `Typeface` itself is not inspectable in off-device tests.
     */
    internal val statusTypefaceStyle: Int = Typeface.BOLD

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, statusTypefaceStyle)
    }

    private var activeElapsedSeconds = 0.0

    /** Status-only resolved rendering contexts; decorative simulation has a separate capped delta. */
    internal enum class StatusMode { REMOVED, STOPPED, MEDIA_PAUSED, RUNNING }

    /** Fixed DOM paint order, independent of domain enum ordinals and equal durations. */
    private enum class StatusTrack(val phase: ScreenPhase?, val durationSeconds: Double) {
        READY(ScreenPhase.READY, 148.0),
        ARMING(ScreenPhase.ARMING, 68.0),
        WIFI_PAUSED(ScreenPhase.WIFI_PAUSED, 144.0),
        FROZEN(ScreenPhase.FROZEN, 112.0),
        WAITING(ScreenPhase.ADVERTISING, 144.0),
        CONNECTING(ScreenPhase.CONNECTING, 84.0),
        FAILURE(null, 140.0),
        PROJECTING(ScreenPhase.CONNECTED, 108.0),
    }

    /** Fixed inline runs from e-scene.js; font sizes do not replace the inherited 1.1px tracking. */
    private data class CodeRun(val text: String, val size: Float = 11f, val color: Int = 0xFF858585.toInt())

    /** One retained binary opacity transition and its independently paused marquee. */
    private class StatusLayer(val track: StatusTrack) {
        var elapsedSeconds = 0.0
        var alpha = 0.0
        var startAlpha = 0.0
        var targetAlpha = 0.0
        var startMillis = 0L
        var durationMillis = 0.0
        var reversingStartAlpha = 0.0
        var reversingFactor = 1.0
        var visible = false
        var hideAtMillis: Long? = null
        var runs: List<CodeRun> = emptyList()
    }

    private var latestReceiverName = ""
    private val statusLayers = Array(StatusTrack.entries.size) { index ->
        StatusLayer(StatusTrack.entries[index]).apply { runs = runsForTrack(track) }
    }
    private var statusTrack: StatusTrack? = null
    private var statusMode = StatusMode.REMOVED
    private var statusAnchorMillis: Long? = null
    private val upperRows = listOf(
        listOf(CodeRun("; display-only register sketch", 10f), CodeRun(" R[00] ← "),
            CodeRun("MIRAX", 12f, 0xFFCECECE.toInt()), CodeRun(" | R[03] := 0x0C / mask=0b1010 // ")),
        listOf(CodeRun("0xD030:", 10f, 0xFF9D9D9D.toInt()), CodeRun(" 4D 49 52 41 58 \u00a0 00 2F 2F 00 | "),
            CodeRun("MIRAX", 12f, 0xFFCECECE.toInt()), CodeRun(" [illustrative bytes] · ")),
    )
    private val lowerRows = listOf(
        listOf(CodeRun("/*", 13f, 0xFFC4C4C4.toInt()), CodeRun(" no input sampled; glyphs only <frame/> "),
            CodeRun("return decoration;", 10f), CodeRun(" "), CodeRun("*/", 13f, 0xFFC4C4C4.toInt()), CodeRun(" ")),
        listOf(CodeRun("<decor seq=\"--\" flags=\"00\"> "), CodeRun("MIRAX", 12f, 0xFFCECECE.toInt()),
            CodeRun(" : [empty] </decor> "), CodeRun("fiction / no signal data", 10f), CodeRun(" // ")),
    )

    private var cachedTraceGradient: LinearGradient? = null
    private var cachedTraceLength = -1f
    private var cachedTraceUnit = -1f
    private val traceLocalMatrix = Matrix()

    /** Row 3 status text; null means the row is not drawn. */
    var statusText: String? = null
        private set

    /** True when Row 3 renders in the prototype alert red instead of the normal light grey. */
    var statusAlert: Boolean = false
        private set

    init {
        streams.forEach { schedule(it) }
    }

    /**
     * Charges old state/context first, then commits one resolved presentation event.
     *
     * @param phase actual known phase, never a fabricated initial state.
     * @param receiverName latest raw identity, including while WAITING is outgoing.
     * @param hasFailure genuine host failure, selecting the shared visual failure track.
     * @param mode resolved status-only context.
     * @param nowUptimeMillis timestamp from the owning View's existing monotonic clock.
     */
    internal fun setState(
        phase: ScreenPhase, receiverName: String, hasFailure: Boolean,
        mode: StatusMode, nowUptimeMillis: Long,
    ) {
        advanceStatusTo(nowUptimeMillis)
        val now = maxOf(statusAnchorMillis ?: nowUptimeMillis, nowUptimeMillis)
        val first = statusTrack == null
        val wasSuppressed = statusText == null
        val oldMode = statusMode
        statusAnchorMillis = now
        statusTrack = if (hasFailure) StatusTrack.FAILURE else when (phase) {
            ScreenPhase.READY -> StatusTrack.READY
            ScreenPhase.ARMING -> StatusTrack.ARMING
            ScreenPhase.WIFI_PAUSED -> StatusTrack.WIFI_PAUSED
            ScreenPhase.FROZEN -> StatusTrack.FROZEN
            ScreenPhase.ADVERTISING -> StatusTrack.WAITING
            ScreenPhase.CONNECTING -> StatusTrack.CONNECTING
            ScreenPhase.CONNECTED -> StatusTrack.PROJECTING
        }
        statusText = statusMessage(phase, receiverName, hasFailure)
        statusAlert = statusAlertFor(phase, hasFailure)
        if (latestReceiverName != receiverName) {
            latestReceiverName = receiverName
            statusLayers[StatusTrack.WAITING.ordinal].runs = runsForTrack(StatusTrack.WAITING)
        }
        statusMode = mode
        if (mode == StatusMode.REMOVED) statusLayers.forEach { it.elapsedSeconds = 0.0 }
        if (first || oldMode == StatusMode.REMOVED || mode == StatusMode.REMOVED || mode == StatusMode.STOPPED ||
            wasSuppressed || statusText == null
        ) {
            settleStatusTargets()
        } else {
            for (layer in statusLayers) retargetStatus(layer, if (layer.track == statusTrack) 1.0 else 0.0, now)
        }
    }

    /** Updates active Paint locales for Row 3 fallback glyph selection without allocating during draw. */
    internal fun setStatusTextLocales(locales: LocaleList) {
        statusPaint.textLocales = locales
    }

    /** Refreshes active and cached outgoing Row 3 copies on configuration changes without resetting tracks or clocks. */
    internal fun refreshStatusCopy(statusCopy: StatusCopy, textLocales: LocaleList? = null) {
        this.statusCopy = statusCopy
        if (textLocales != null) statusPaint.textLocales = textLocales
        for (layer in statusLayers) layer.runs = runsForTrack(layer.track)
        val track = statusTrack ?: return
        statusText = copyForTrack(track)
    }

    private fun runsForTrack(track: StatusTrack): List<CodeRun> =
        copyForTrack(track)?.let { listOf(CodeRun(it, STATUS_TEXT_SIZE)) } ?: emptyList()

    private fun copyForTrack(track: StatusTrack): String? = when (track) {
        StatusTrack.FAILURE -> statusCopy.failure
        StatusTrack.WAITING -> if (latestReceiverName.isBlank()) null else statusCopy.formatAdvertising(latestReceiverName)
        StatusTrack.PROJECTING -> null
        else -> track.phase?.let { statusMessage(it, latestReceiverName, false) }
    }

    /**
     * Row 3 copy for the real screen state, without inventing a receiver identity.
     *
     * @param phase real screen phase.
     * @param receiverName real effective broadcast name; blank means unknown.
     * @param hasFailure true while the host shows a genuine failure presentation.
     * @return the status copy, or null for projection or an unknown advertising receiver without failure.
     */
    fun statusMessage(phase: ScreenPhase, receiverName: String, hasFailure: Boolean): String? {
        if (hasFailure) return statusCopy.failure
        return when (phase) {
            ScreenPhase.READY -> statusCopy.ready
            ScreenPhase.ARMING -> statusCopy.arming
            ScreenPhase.WIFI_PAUSED -> statusCopy.wifiPaused
            ScreenPhase.FROZEN -> statusCopy.frozen
            ScreenPhase.ADVERTISING -> if (receiverName.isBlank()) null else statusCopy.formatAdvertising(receiverName)
            ScreenPhase.CONNECTING -> statusCopy.connecting
            ScreenPhase.CONNECTED -> null
        }
    }

    /** Commits context-only changes without inventing a phase before the first actual state. */
    internal fun setStatusMode(mode: StatusMode, nowUptimeMillis: Long) {
        advanceStatusTo(nowUptimeMillis)
        val oldMode = statusMode
        statusMode = mode
        if (mode == StatusMode.REMOVED) statusLayers.forEach { it.elapsedSeconds = 0.0 }
        if (mode == StatusMode.REMOVED || mode == StatusMode.STOPPED || oldMode == StatusMode.REMOVED) settleStatusTargets()
    }

    /** Samples raw status time only; equal/regressing timestamps cannot create time or restart a transition. */
    internal fun advanceStatusTo(nowUptimeMillis: Long) {
        val track = statusTrack ?: return
        val previous = statusAnchorMillis
        val now = maxOf(previous ?: nowUptimeMillis, nowUptimeMillis)
        if (previous != null && statusMode == StatusMode.RUNNING) {
            statusLayers[track.ordinal].elapsedSeconds += (now - previous).toDouble() / 1000.0
        }
        statusAnchorMillis = now
        if (statusMode == StatusMode.RUNNING || statusMode == StatusMode.MEDIA_PAUSED) {
            for (layer in statusLayers) {
                if (layer.durationMillis > 0.0) {
                    val fraction = ((now - layer.startMillis).toDouble() / layer.durationMillis).coerceIn(0.0, 1.0)
                    layer.alpha = layer.startAlpha + (layer.targetAlpha - layer.startAlpha) * statusEase(fraction)
                    if (fraction >= 1.0) {
                        layer.alpha = layer.targetAlpha
                        layer.durationMillis = 0.0
                    }
                }
                if (layer.hideAtMillis?.let { now >= it } == true) {
                    layer.visible = false
                    layer.hideAtMillis = null
                }
            }
        }
    }

    /** Establishes settled targets for removal, explicit stop, first rendering, or whole-row suppression. */
    private fun settleStatusTargets() {
        for (layer in statusLayers) {
            layer.alpha = if (layer.track == statusTrack) 1.0 else 0.0
            layer.startAlpha = layer.alpha
            layer.targetAlpha = layer.alpha
            layer.durationMillis = 0.0
            layer.reversingStartAlpha = layer.alpha
            layer.reversingFactor = 1.0
            layer.visible = layer.track == statusTrack && statusText != null
            layer.hideAtMillis = null
        }
    }

    /** CSS binary-target retargeting; visibility's full delay is independent of opacity reversal shortening. */
    private fun retargetStatus(layer: StatusLayer, target: Double, now: Long) {
        if (layer.targetAlpha == target) return
        val reversing = layer.durationMillis > 0.0 && layer.reversingStartAlpha == target
        val oldEnd = layer.targetAlpha
        val fraction = if (layer.durationMillis > 0.0) ((now - layer.startMillis) / layer.durationMillis).coerceIn(0.0, 1.0) else 1.0
        val factor = if (reversing) abs(statusEase(fraction) * layer.reversingFactor + 1.0 - layer.reversingFactor).coerceIn(0.0, 1.0) else 1.0
        layer.startAlpha = layer.alpha
        layer.targetAlpha = target
        layer.startMillis = now
        layer.durationMillis = if (layer.alpha == target) 0.0 else 180.0 * factor
        if (layer.durationMillis == 0.0) layer.alpha = target
        layer.reversingStartAlpha = if (reversing) oldEnd else layer.alpha
        layer.reversingFactor = factor
        if (target == 1.0) {
            layer.visible = true
            layer.hideAtMillis = null
        } else {
            layer.hideAtMillis = now + 180L
        }
    }

    /** Fixed CSS ease cubic(.25,.1,.25,1); invert its monotonic X rather than treating curve time as X. */
    private fun statusEase(fraction: Double): Double {
        if (fraction <= 0.0) return 0.0
        if (fraction >= 1.0) return 1.0
        var low = 0.0
        var high = 1.0
        repeat(30) {
            val t = (low + high) / 2.0
            val x = ((t - 0.75) * t + 0.75) * t
            if (x < fraction) low = t else high = t
        }
        val t = (low + high) / 2.0
        return ((-1.7 * t + 2.4) * t + 0.3) * t
    }

    private fun schedule(stream: EdgeStream) {
        stream.gapSeconds = 0.12 + random() * 0.78
        stream.durationSeconds = 0.95 + random() * 0.65
        stream.elapsedSeconds = 0.0
        stream.inShot = false
        stream.progress = 0.0
        stream.position = 0.0
        stream.alpha = 0.0
    }

    fun update(deltaSeconds: Double) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) return
        var remaining = min(deltaSeconds, 5.0)
        while (remaining > 0.0) {
            val step = min(remaining, 0.05)
            remaining -= step
            advanceStep(step)
        }
    }

    private fun advanceStep(step: Double) {
        activeElapsedSeconds += step

        for (stream in streams) {
            stream.elapsedSeconds += step
            if (!stream.inShot) {
                if (stream.elapsedSeconds < stream.gapSeconds) continue
                stream.inShot = true
                stream.elapsedSeconds -= stream.gapSeconds
            }
            stream.progress = min(1.0, stream.elapsedSeconds / stream.durationSeconds)
            // Quadratic acceleration: travels faster and faster toward the end
            stream.position = stream.progress * stream.progress
            // Sharp fade-in, smooth fade-out
            stream.alpha = min(1.0, min(stream.progress * 12.0, (1.0 - stream.progress) * 14.0))

            if (stream.progress >= 1.0) {
                schedule(stream)
            }
        }
    }

    /**
     * Draws CSS geometry; the host supplies platform SP conversion for accessibility text.
     * @param drawStatus false to suppress Row 3 paint without changing its prepared state.
     */
    fun draw(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        viewport: StandbyViewportScale,
        drawStatus: Boolean = true,
        textSizePx: (Float) -> Float = { viewport.px(it) },
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val (originXRatio, originYRatio, sizeRatioW, sizeRatioH) = when {
            viewport.portrait && viewport.narrow -> NARROW_PORTRAIT_QUAD
            viewport.shortChannelLandscape -> SHORT_LANDSCAPE_QUAD
            else -> DEFAULT_QUAD
        }
        val originX = viewWidth * originXRatio
        val originY = viewHeight * originYRatio
        val channelSize = min(viewWidth * sizeRatioW, viewHeight * sizeRatioH)
        val channelSpan = max(viewWidth, viewHeight) * CHANNEL_SPAN_FACTOR
        val halfSpan = channelSpan / 2f

        canvas.save()
        canvas.translate(originX, originY)
        canvas.rotate(-45f)

        val upper = upperChannelInto(upperBand, viewport, channelSize)
        val lower = lowerChannelInto(lowerBand, viewport, channelSize)
        railPaint.strokeWidth = viewport.px(1f)

        // Band background and rails.
        drawBand(canvas, upper, halfSpan)
        drawBand(canvas, lower, halfSpan)

        val rowStep = viewport.px(ROW_HEIGHT_PX + ROW_GAP_PX)
        val upperContentTop = upper.top + (upper.pxHeight - viewport.px(UPPER_CONTENT_PX)) / 2f
        val lowerContentTop = lower.top + (lower.pxHeight - viewport.px(LOWER_CONTENT_PX)) / 2f
        fun rows(band: ChannelBand, contentTop: Float, rows: List<List<CodeRun>>, duration: Double, upperStatus: Boolean) {
            val start = scanStart(originX, originY, viewWidth, viewHeight, band.offset)
            val end = scanEnd(originX, originY, viewWidth, viewHeight, band.offset)
            val save = canvas.save()
            canvas.clipRect(start, band.top + railPaint.strokeWidth, end, band.bottom - railPaint.strokeWidth)
            rows.forEachIndexed { index, runs ->
                drawStreamRow(canvas, runs, contentTop + rowStep * index, start, end, viewport, textSizePx, duration)
            }
            if (upperStatus && drawStatus && statusText != null && statusMode != StatusMode.REMOVED) {
                for (index in statusLayers.indices) {
                    val layer = statusLayers[index]
                    if (!layer.visible || layer.runs.isEmpty()) continue
                    val group = canvas.saveLayerAlpha(start, band.top + railPaint.strokeWidth,
                        end, band.bottom - railPaint.strokeWidth, (layer.alpha * 255.0).roundToInt().coerceIn(0, 255))
                    try {
                        drawStreamRow(canvas, layer.runs, contentTop + rowStep * 2f,
                            start, end, viewport, textSizePx, layer.track.durationSeconds, status = layer)
                    } finally {
                        canvas.restoreToCount(group)
                    }
                }
            }
            canvas.restoreToCount(save)
        }
        rows(upper, upperContentTop, upperRows, 240.0, upperStatus = true)
        rows(lower, lowerContentTop, lowerRows, 360.0, upperStatus = false)

        // CSS clamp(56px, 12vmin, 96px), independent of the SVG slice.
        val traceLength = min(viewWidth, viewHeight).times(0.12f).coerceIn(viewport.px(56f), viewport.px(96f))
        val gradient = traceGradient(traceLength, viewport.px(1f))
        tracePaint.strokeWidth = viewport.px(TRACE_WIDTH_PX)
        tracePaint.shader = gradient

        for (stream in streams) {
            if (!stream.inShot || stream.alpha <= 0.0) continue

            val band = if (stream.isUpper) upper else lower
            val edgeOffset = if (stream.isTop) band.top else band.bottom
            val visibleStart = scanStart(originX, originY, viewWidth, viewHeight, edgeOffset)
            val visibleEnd = scanEnd(originX, originY, viewWidth, viewHeight, edgeOffset)
            val headX = traceHeadX(visibleStart, visibleEnd, traceLength, stream.position)
            val tailX = headX - traceLength

            traceLocalMatrix.setTranslate(tailX, edgeOffset)
            gradient.setLocalMatrix(traceLocalMatrix)
            tracePaint.alpha = (stream.alpha.coerceIn(0.0, 1.0) * 255.0).toInt()
            canvas.drawLine(tailX, edgeOffset, headX, edgeOffset, tracePaint)
        }
        tracePaint.alpha = 255

        canvas.restore()
    }

    private fun drawBand(canvas: Canvas, band: ChannelBand, halfSpan: Float) {
        canvas.drawRect(-halfSpan, band.top, halfSpan, band.bottom, bgPaint)
        canvas.drawLine(-halfSpan, band.top + railPaint.strokeWidth / 2f, halfSpan, band.top + railPaint.strokeWidth / 2f, railPaint)
        canvas.drawLine(-halfSpan, band.bottom - railPaint.strokeWidth / 2f, halfSpan, band.bottom - railPaint.strokeWidth / 2f, railPaint)
    }

    /**
     * Trace gradient cached at full opacity; per-shot fade arrives through
     * [Paint.alpha] so no shader is allocated per frame.
     *
     * @param traceLength trace length in view pixels.
     */
    private fun traceGradient(traceLength: Float, unit: Float): LinearGradient {
        val cached = cachedTraceGradient
        if (cached != null && cachedTraceLength == traceLength && cachedTraceUnit == unit) return cached
        cachedTraceUnit = unit
        val created = LinearGradient(
            0f, 0f, traceLength, 0f,
            intArrayOf(TRACE_TAIL_COLOR, 0x6BFFFFFF, TRACE_MID_COLOR, TRACE_HEAD_COLOR),
            floatArrayOf(0f, 0.65f, (traceLength - cachedTraceUnit * 5f) / traceLength,
                (traceLength - cachedTraceUnit * 3f) / traceLength),
            Shader.TileMode.CLAMP,
        )
        cachedTraceGradient = created
        cachedTraceLength = traceLength
        return created
    }

    /** Draws the source's eight-copy segments with absolute CSS tracking and native font baselines. */
    private fun drawStreamRow(
        canvas: Canvas,
        runs: List<CodeRun>,
        top: Float,
        start: Float,
        end: Float,
        viewport: StandbyViewportScale,
        textSizePx: (Float) -> Float,
        duration: Double,
        status: StatusLayer? = null,
    ) {
        val paint = if (status != null) statusPaint else textPaint
        val tracking = viewport.px(1.1f)
        fun apply(run: CodeRun) {
            paint.textSize = textSizePx(run.size)
            paint.letterSpacing = tracking / paint.textSize
            paint.color = if (status != null) (if (statusAlert) STATUS_ALERT_COLOR else STATUS_COLOR) else run.color
        }
        val base = if (status != null) STATUS_TEXT_SIZE else 11f
        paint.textSize = textSizePx(base)
        val metrics = paint.fontMetrics
        val lineHeight = viewport.px(if (status != null) STATUS_ROW_HEIGHT_PX else ROW_HEIGHT_PX)
        val baseline = top + (lineHeight - metrics.descent + metrics.ascent) / 2f - metrics.ascent
        val phraseWidth = runs.sumOf { run -> apply(run); paint.measureText(run.text).toDouble() }.toFloat()
        if (phraseWidth <= 0f) return
        val phrasePadding = if (status != null) viewport.px(16f) else 0f
        val advance = phraseWidth + phrasePadding * 2f
        paint.textSize = textSizePx(11f)
        paint.letterSpacing = 0f
        val segmentGap = if (status != null) 0f else paint.measureText("0") * 8f
        val segmentWidth = advance * 8f + segmentGap
        val elapsedSeconds = status?.elapsedSeconds ?: activeElapsedSeconds
        var segmentX = start - segmentWidth + ((elapsedSeconds % duration) / duration * segmentWidth).toFloat()
        while (segmentX < end) {
            var x = segmentX
            repeat(8) {
                x += phrasePadding
                for (run in runs) {
                    apply(run)
                    canvas.drawText(run.text, x, baseline, paint)
                    x += paint.measureText(run.text)
                }
                x += phrasePadding
            }
            segmentX += segmentWidth
        }
    }

    companion object {
        /** Row 3 status size: prototype `calc(12px + 2pt)`. */
        const val STATUS_TEXT_SIZE = 14.667f

        /** Prototype `#e-scene` channel origin and size, as fractions of the stage. */
        const val ORIGIN_X_FRACTION = 0.72f
        const val ORIGIN_Y_FRACTION = 0.48f
        const val CHANNEL_WIDTH_FRACTION = 0.54f
        const val CHANNEL_HEIGHT_FRACTION = 0.78f
        const val CHANNEL_SPAN_FACTOR = 2f

        /** Responsive channel placement from the prototype media queries. */
        private val NARROW_PORTRAIT_QUAD = Quad(0.59f, 0.38f, 0.84f, 0.48f)
        private val SHORT_LANDSCAPE_QUAD = Quad(0.75f, 0.47f, 0.44f, 0.74f)
        private val DEFAULT_QUAD = Quad(
            ORIGIN_X_FRACTION,
            ORIGIN_Y_FRACTION,
            CHANNEL_WIDTH_FRACTION,
            CHANNEL_HEIGHT_FRACTION,
        )
        const val UPPER_OFFSET_FACTOR = -0.59f
        const val LOWER_OFFSET_FACTOR = 0.59f

        /** Prototype band heights and offsets in px. */
        const val UPPER_HEIGHT_PX = 80f
        const val LOWER_HEIGHT_PX = 52f
        const val LOWER_LIFT_PX = 16f

        /** Centered content: 16 + 3 + 16 + 3 + 24 for the upper band, 16 + 3 + 16 for the lower. */
        const val UPPER_CONTENT_PX = 62f
        const val LOWER_CONTENT_PX = 35f
        const val ROW_HEIGHT_PX = 16f
        const val ROW_GAP_PX = 3f
        const val STATUS_ROW_HEIGHT_PX = 24f

        /** CSS trace width; length is clamp(56px, 12vmin, 96px). */
        const val TRACE_WIDTH_PX = 1f

        /** `rotate(-45deg)` axis factor: one rotated unit spans this many px per screen axis. */
        const val SQRT2 = 1.41421356f

        private val STATUS_COLOR: Int = Color.parseColor("#f2f2f2")
        private val STATUS_ALERT_COLOR: Int = Color.parseColor("#ff6b6b")
        private val COMMENT_COLOR: Int = Color.parseColor("#858585")
        private val ADDRESS_COLOR: Int = Color.parseColor("#9d9d9d")
        private val TRACE_TAIL_COLOR: Int = Color.argb(0, 255, 255, 255)
        private val TRACE_MID_COLOR: Int = Color.argb(188, 255, 255, 255)
        private val TRACE_HEAD_COLOR: Int = Color.argb(255, 255, 255, 255)

        /**
         * Upper channel: 80px tall, offset `-0.59 * channelSize`.
         *
         * @param viewport measured stage scale.
         * @param channelSize prototype `min(54vw, 78svh)` in px.
         */
        fun upperChannel(viewport: StandbyViewportScale, channelSize: Float): ChannelBand =
            upperChannelInto(ChannelBand(0f, 0f), viewport, channelSize)

        /**
         * Fills [out] with the upper channel so the render loop reuses one instance.
         *
         * @param out band instance to overwrite.
         * @param viewport measured stage scale.
         * @param channelSize prototype `min(54vw, 78svh)` in px.
         */
        fun upperChannelInto(out: ChannelBand, viewport: StandbyViewportScale, channelSize: Float): ChannelBand {
            out.offset = UPPER_OFFSET_FACTOR * channelSize
            out.pxHeight = viewport.px(UPPER_HEIGHT_PX)
            return out
        }

        /**
         * Lower channel: 52px tall, offset `0.59 * channelSize - 16px`.
         *
         * @param viewport measured stage scale.
         * @param channelSize prototype `min(54vw, 78svh)` in px.
         */
        fun lowerChannel(viewport: StandbyViewportScale, channelSize: Float): ChannelBand =
            lowerChannelInto(ChannelBand(0f, 0f), viewport, channelSize)

        /**
         * Fills [out] with the lower channel so the render loop reuses one instance.
         *
         * @param out band instance to overwrite.
         * @param viewport measured stage scale.
         * @param channelSize prototype `min(54vw, 78svh)` in px.
         */
        fun lowerChannelInto(out: ChannelBand, viewport: StandbyViewportScale, channelSize: Float): ChannelBand {
            out.offset = LOWER_OFFSET_FACTOR * channelSize - viewport.px(LOWER_LIFT_PX)
            out.pxHeight = viewport.px(LOWER_HEIGHT_PX)
            return out
        }

        /**
         * Visible start of one band edge, from the prototype `--e-scan-start`.
         *
         * @param originX channel origin X in px.
         * @param originY channel origin Y in px.
         * @param viewWidth stage width in px.
         * @param viewHeight stage height in px.
         * @param edgeOffset band edge on the rotated Y axis.
         */
        fun scanStart(originX: Float, originY: Float, viewWidth: Float, viewHeight: Float, edgeOffset: Float): Float =
            max(-originX * SQRT2 - edgeOffset, (originY - viewHeight) * SQRT2 + edgeOffset)

        /**
         * Visible end of one band edge, from the prototype `--e-scan-end`.
         *
         * @param originX channel origin X in px.
         * @param originY channel origin Y in px.
         * @param viewWidth stage width in px.
         * @param viewHeight stage height in px.
         * @param edgeOffset band edge on the rotated Y axis.
         */
        fun scanEnd(originX: Float, originY: Float, viewWidth: Float, viewHeight: Float, edgeOffset: Float): Float =
            min((viewWidth - originX) * SQRT2 - edgeOffset, originY * SQRT2 + edgeOffset)

        /**
         * Trace head at [position]; the trace enters at [scanStart] and clears
         * [scanEnd] by one trace length at `position = 1`.
         *
         * @param scanStart visible start from [scanStart].
         * @param scanEnd visible end from [scanEnd].
         * @param traceLength trace length in px.
         * @param position shot position in `[0, 1]`.
         */
        fun traceHeadX(scanStart: Float, scanEnd: Float, traceLength: Float, position: Double): Float =
            scanStart + (scanEnd - scanStart + traceLength) * position.toFloat()

        /**
         * Prototype alert condition: failure presentation or frozen receiver.
         *
         * @param phase real screen phase.
         * @param hasFailure true while the host shows a genuine failure presentation.
         */
        fun statusAlertFor(phase: ScreenPhase, hasFailure: Boolean): Boolean {
            return hasFailure || phase == ScreenPhase.FROZEN
        }
    }
}
