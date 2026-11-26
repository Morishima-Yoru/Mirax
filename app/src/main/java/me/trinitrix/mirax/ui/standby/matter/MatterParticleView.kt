package me.trinitrix.mirax.ui.standby.matter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale

/**
 * Standalone background-only candidate for the approved 06 Matter visual.
 *
 * Renders the real seeded [MatterParticleField] source field only: solid `#080808`
 * background, per-dot `RGB 231,231,224` color with depth alpha and radius, then the
 * prototype C readability scrim `#08080818` on top. No theme text, plates, overlays,
 * buttons, fonts, or metrics live here; a future host owns all foreground UI.
 *
 * State is never invented: freezing applies only when the host explicitly passes the
 * real [ScreenPhase.FROZEN], and assembly follows only the real broadcast intent plus
 * the real Wi-Fi paused flag. Projection pausing is also host-driven through
 * [setProjecting] and never touches the stream decoder or audio path.
 *
 * Timing uses the real monotonic clock and pauses while the surface is not actively
 * visible (hidden, window hidden, detached, zero size, or projecting), then resumes
 * smoothly with per-frame deltas clamped like the prototype. Frames are driven by
 * [Choreographer] plus [View.postInvalidateOnAnimation]; no `ValueAnimator`, no
 * duration scales, and no OS setting is ever changed.
 *
 * Outstanding on purpose, not wired here: failure warning color and glitch, exact v41
 * parallax and OLED content-safety math, and custom media compositing. This view only
 * accepts a raw particle-layer pixel offset from the future 07 surface owner via
 * [setParticleLayerOffset]; control and text layers live in the future host and may use
 * different offsets. This background alone is NOT full v41 acceptance, and 07 is NOT
 * claimed complete.
 *
 * @param field real seeded particle source; defaults to the production 1800 field.
 * @param particleOffsetXPx initial particle-layer X offset in px for the future owner.
 * @param particleOffsetYPx initial particle-layer Y offset in px for the future owner.
 */
class MatterParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val field: MatterParticleField = MatterParticleField(),
    particleOffsetXPx: Float = 0f,
    particleOffsetYPx: Float = 0f,
) : View(context, attrs, defStyleAttr) {

    /**
     * Explicit draw evidence from the attached view.
     *
     * Null [canvasWasHardwareAccelerated] means no draw happened yet and must be
     * treated as unavailable evidence, never as a pass or fail.
     *
     * @param observed true once [onDraw] ran while attached.
     * @param drawCount total [onDraw] executions while attached.
     * @param canvasWasHardwareAccelerated value of [Canvas.isHardwareAccelerated].
     * @param particleCount source field size actually iterated during draws.
     * @param activeElapsedMillis accumulated visible monotonic time driving the field.
     */
    data class MatterDrawEvidence(
        val observed: Boolean,
        val drawCount: Int,
        val canvasWasHardwareAccelerated: Boolean?,
        val particleCount: Int,
        val activeElapsedMillis: Double,
    )

    /** Monotonic timestamp source, injectable for deterministic tests. */
    var clockUptimeMillis: () -> Long = { SystemClock.uptimeMillis() }

    /** Optional per-draw callback for future hosts and tests; never blocks drawing. */
    var onFrameDrawn: (() -> Unit)? = null

    /** Current particle-layer X offset in px; raw passthrough for the future 07 surface owner. */
    var particleLayerOffsetXPx: Float = particleOffsetXPx
        private set

    /** Current particle-layer Y offset in px; raw passthrough for the future 07 surface owner. */
    var particleLayerOffsetYPx: Float = particleOffsetYPx
        private set

    /** Number of source particles iterated per draw; fixed at 1800 from [MatterParticleField]. */
    val particleCount: Int
        get() = this.field.count

    /** Latest attached draw evidence. */
    var drawEvidence: MatterDrawEvidence = MatterDrawEvidence(
        observed = false,
        drawCount = 0,
        canvasWasHardwareAccelerated = null,
        particleCount = field.count,
        activeElapsedMillis = 0.0,
    )
        private set

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var broadcasting = false
    private var wifiPaused = false
    private var frozen = false
    private var projecting = false
    private var activeElapsedMillis = 0.0
    private var lastUptimeMillis: Long? = null
    private var frameScheduled = false

    // Alpha changes do not trigger visibility callbacks.
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        scheduleFrameIfNeeded()
        true
    }

    private var frameCallback: Choreographer.FrameCallback? = null

    /**
     * Applies the real standby state for the next frames.
     *
     * @param phase real screen phase from the caller; only [ScreenPhase.FROZEN] freezes.
     * @param broadcasting real broadcast intent from the caller.
     * @param wifiPaused real Wi-Fi paused flag from the caller; pauses assembly only.
     */
    fun setMatterState(phase: ScreenPhase, broadcasting: Boolean, wifiPaused: Boolean) {
        this.frozen = phase == ScreenPhase.FROZEN
        this.broadcasting = broadcasting
        this.wifiPaused = wifiPaused
        field.setBroadcast(broadcasting, wifiPaused, activeElapsedMillis)
        if (!shouldAnimate()) {
            return
        }
        scheduleFrameIfNeeded()
        postInvalidateOnAnimation()
    }

    /**
     * Sets the particle-layer pixel offset owned by the future 07 surface.
     *
     * Raw passthrough only: no parallax or OLED math lives here, so exact v41 motion
     * stays unimplemented until the real owner supplies it. Control and text layers
     * belong to the future host and may receive different offsets there.
     *
     * @param offsetXPx particle-layer X translation in px.
     * @param offsetYPx particle-layer Y translation in px.
     */
    fun setParticleLayerOffset(offsetXPx: Float, offsetYPx: Float) {
        if (particleLayerOffsetXPx == offsetXPx && particleLayerOffsetYPx == offsetYPx) {
            return
        }
        particleLayerOffsetXPx = offsetXPx
        particleLayerOffsetYPx = offsetYPx
        if (!shouldAnimate()) {
            return
        }
        postInvalidateOnAnimation()
    }

    /**
     * Pauses standby motion while projecting without affecting decode or audio.
     *
     * @param projecting true while the incoming stream is projecting.
     */
    fun setProjecting(projecting: Boolean) {
        this.projecting = projecting
        scheduleFrameIfNeeded()
        if (!projecting && shouldAnimate()) {
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        scheduleFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(preDrawListener)
        }
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        scheduleFrameIfNeeded()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        scheduleFrameIfNeeded()
    }

    override fun onSizeChanged(newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, oldHeight)
        scheduleFrameIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_COLOR)
        val viewport = StandbyViewportScale(width, height)
        if (viewport.isValid) {
            val scale = viewport.scale.toDouble()
            val viewWidth = width / scale
            val viewHeight = height / scale
            canvas.save()
            canvas.translate(particleLayerOffsetXPx, particleLayerOffsetYPx)
            for (index in 0 until field.count) {
                val sample = field.sampleAt(index, viewWidth, viewHeight, activeElapsedMillis, frozen)
                val alpha = (sample.alpha.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
                dotPaint.color = Color.argb(alpha, DOT_RED, DOT_GREEN, DOT_BLUE)
                canvas.drawCircle(
                    (sample.x * scale).toFloat(),
                    (sample.y * scale).toFloat(),
                    (sample.radiusPx * scale).toFloat(),
                    dotPaint,
                )
            }
            canvas.restore()
        }
        canvas.drawColor(SCRIM_COLOR)
        val canvasHardware = canvas.isHardwareAccelerated
        val nextCount = drawEvidence.drawCount + 1
        drawEvidence = MatterDrawEvidence(
            observed = true,
            drawCount = nextCount,
            canvasWasHardwareAccelerated = canvasHardware,
            particleCount = field.count,
            activeElapsedMillis = activeElapsedMillis,
        )
        onFrameDrawn?.invoke()
        scheduleFrameIfNeeded()
    }

    private fun shouldAnimate(): Boolean {
        return isAttachedToWindow &&
            isShown &&
            windowVisibility == View.VISIBLE &&
            !projecting &&
            width > 0 &&
            height > 0 &&
            hasVisibleAlpha()
    }

    /** @return Whether this view and its ancestors have a positive combined alpha. */
    private fun hasVisibleAlpha(): Boolean {
        var effectiveAlpha = 1f
        var ancestor: View? = this
        while (ancestor != null) {
            effectiveAlpha *= ancestor.alpha
            if (!(effectiveAlpha > 0f)) {
                return false
            }
            ancestor = ancestor.parent as? View
        }
        return true
    }

    /** Retires the active frame run and clears its pending/time state. */
    private fun stopFrames() {
        val retiredCallback = frameCallback
        frameCallback = null
        retiredCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameScheduled = false
        lastUptimeMillis = null
    }

    private fun scheduleFrameIfNeeded() {
        if (!shouldAnimate()) {
            stopFrames()
            return
        }
        if (frameScheduled) {
            return
        }
        val callback = frameCallback ?: object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (frameCallback !== this) return
                frameScheduled = false
                if (!shouldAnimate()) {
                    stopFrames()
                    return
                }
                val now = clockUptimeMillis()
                val previous = lastUptimeMillis
                if (previous != null) {
                    val delta = (now - previous).toDouble().coerceIn(0.0, MAX_FRAME_DELTA_MILLIS)
                    activeElapsedMillis += delta
                }
                lastUptimeMillis = now
                field.setBroadcast(broadcasting, wifiPaused, activeElapsedMillis)
                postInvalidateOnAnimation()
                if (frameCallback === this) scheduleFrameIfNeeded()
            }
        }.also { frameCallback = it }
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private companion object {
        const val BACKGROUND_COLOR: Int = -0xF7F7F8
        const val DOT_RED = 231
        const val DOT_GREEN = 231
        const val DOT_BLUE = 224
        const val SCRIM_COLOR = 0x18080808
        const val MAX_FRAME_DELTA_MILLIS = 80.0
    }
}
