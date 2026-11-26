package me.trinitrix.mirax.ui.standby.landscape

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import kotlin.math.max

/**
 * Standalone background-only candidate for the approved Landscape visual
 * (formal name `山霧 · 流動地景`, English identity Landscape).
 *
 * Ports the observable `#landscape` background from
 * `design/big-standby-prototype.html`: the `1600x1000` `xMidYMid slice`
 * stage, sky/near/mist/light gradients with day/night stops, far/middle/near
 * ridges, drifting mist band, foreground slope, bank, foot, and the sun/moon
 * orbits with the 3.6s broadcast transition. No theme text, plates, buttons,
 * fonts, or metrics live here; a future host owns all foreground UI.
 *
 * State is never invented: day/night follows only the real broadcast intent
 * (frozen keeps intent per prototype `previewNativePhase`; CSS keys on intent),
 * and projection pausing is host-driven through [setProjecting]. Timing uses
 * the real monotonic clock. Ambient motion pauses while the surface is not
 * actively visible; the CSS-like transition snaps to its target when stopped.
 * Frames are driven by
 * [Choreographer] plus [View.postInvalidateOnAnimation]; no `ValueAnimator`,
 * no duration scales, and no OS setting is ever changed.
 *
 * This background alone is NOT full v41 acceptance.
 */
class LandscapeSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
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
     * @param activeElapsedSeconds accumulated visible monotonic time driving motion.
     */
    data class LandscapeDrawEvidence(
        val observed: Boolean,
        val drawCount: Int,
        val canvasWasHardwareAccelerated: Boolean?,
        val activeElapsedSeconds: Double,
    )

    /** Monotonic timestamp source, injectable for deterministic tests. */
    var clockUptimeMillis: () -> Long = { SystemClock.uptimeMillis() }

    /** Optional per-draw callback for future hosts and tests; never blocks drawing. */
    var onFrameDrawn: (() -> Unit)? = null

    private val motionState = LandscapeMotionState()

    /** Current displayed palette blend, observable for tests. */
    val displayedBlend: Double get() = motionState.paletteBlend

    /** Current orbit blend, observable for tests. */
    internal val displayedOrbitBlend: Double get() = motionState.orbitBlend

    /** Current blend target from the real broadcast intent. */
    val blendTarget: Double get() = motionState.target

    /** Latest attached draw evidence. */
    var drawEvidence: LandscapeDrawEvidence = LandscapeDrawEvidence(
        observed = false,
        drawCount = 0,
        canvasWasHardwareAccelerated = null,
        activeElapsedSeconds = 0.0,
    )
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var projecting = false
    private var brandBottomPx: Float? = null
    private var primaryTopPx: Float? = null
    private var activeElapsedSeconds = 0.0
    private var celestialLayout: LandscapeGeometry.CelestialLayout? = null
    private var lastUptimeMillis: Long? = null
    private var frameScheduled = false
    private var moonStagePath: Path? = null
    private var readabilityGradient: LinearGradient? = null

    private var frameCallback: Choreographer.FrameCallback? = null

    /**
     * Applies the real standby state for the next frames.
     *
     * Day/night follows only the real broadcast intent: frozen never forces
     * night here (`previewNativePhase` keeps intent, CSS keys on intent).
     * Palette and orbit reversals start from their own current values with CSS's
     * shortened duration; repeating the same target does not restart either.
     *
     * @param phase real screen phase from the caller.
     * @param broadcasting real broadcast intent from the caller.
     */
    fun setLandscapeState(phase: ScreenPhase, broadcasting: Boolean) {
        val animating = shouldAnimate()
        val target = LandscapeGeometry.broadcastTarget(broadcasting, phase == ScreenPhase.FROZEN)
        motionState.setTarget(target, clockUptimeMillis(), animating)
        if (!animating) {
            stopMotion()
            return
        }
        scheduleFrameIfNeeded()
        postInvalidateOnAnimation()
    }

    /**
     * Supplies real host foreground edges for celestial clamping.
     *
     * Mirrors the prototype brand/primary clamp; while unknown the layout falls
     * back to the unclamped placement. Spacing and typography are never invented here.
     *
     * @param brandBottomPx real brand bottom in view px, or null while unknown.
     * @param primaryTopPx real primary top in view px, or null while unknown.
     */
    fun setHostForegroundGeometry(brandBottomPx: Float?, primaryTopPx: Float?) {
        if (this.brandBottomPx == brandBottomPx && this.primaryTopPx == primaryTopPx) {
            return
        }
        this.brandBottomPx = brandBottomPx
        this.primaryTopPx = primaryTopPx
        updateCelestialLayout(width, height)
        if (!shouldAnimate()) {
            return
        }
        postInvalidateOnAnimation()
    }

    /**
     * Pauses standby motion while projecting without affecting decode or audio.
     *
     * @param projecting true while the real screen phase is projecting.
     */
    fun setProjecting(projecting: Boolean) {
        if (this.projecting == projecting) {
            return
        }
        this.projecting = projecting
        scheduleFrameIfNeeded()
        if (!projecting && shouldAnimate()) {
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
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
        readabilityGradient = if (newHeight > 0) LinearGradient(
            0f, 0f, 0f, newHeight.toFloat(),
            intArrayOf(0x55061010, 0x35061010, 0xA8061010.toInt()),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        ) else null
        updateCelestialLayout(newWidth, newHeight)
        scheduleFrameIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth > 0 && viewHeight > 0 && shouldAnimate()) {
            drawLandscape(canvas, viewWidth, viewHeight)
        }
        drawEvidence = drawEvidence.copy(
            observed = true,
            drawCount = drawEvidence.drawCount + 1,
            canvasWasHardwareAccelerated = canvas.isHardwareAccelerated,
            activeElapsedSeconds = activeElapsedSeconds,
        )
        val callback = onFrameDrawn
        if (callback != null) {
            try {
                callback()
            } catch (error: Exception) {
                Log.w(TAG, "Landscape onFrameDrawn callback failed.", error)
            }
        }
        scheduleFrameIfNeeded()
    }

    private fun shouldAnimate(): Boolean {
        return isAttachedToWindow &&
            isShown &&
            windowVisibility == View.VISIBLE &&
            !projecting &&
            width > 0 &&
            height > 0
    }

    private fun updateCelestialLayout(viewWidth: Int, viewHeight: Int) {
        if (shouldAnimate()) {
            motionState.advance(clockUptimeMillis())
        }
        val viewport = StandbyViewportScale(viewWidth, viewHeight, resources.displayMetrics.density, cssPixels = true)
        if (!viewport.isValid) {
            celestialLayout = null
            return
        }
        val scale = viewport.px(1f)
        celestialLayout = motionState.updateLayout(
            viewWidth / scale.toDouble(), viewHeight / scale.toDouble(),
            brandBottomPx?.div(scale), primaryTopPx?.div(scale),
        )
    }

    private fun stopMotion() {
        val retiredCallback = frameCallback
        frameCallback = null
        retiredCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameScheduled = false
        lastUptimeMillis = null
        motionState.stop()
    }

    private fun scheduleFrameIfNeeded() {
        if (!shouldAnimate()) {
            stopMotion()
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
                    stopMotion()
                    return
                }
                val now = clockUptimeMillis()
                val previous = lastUptimeMillis
                if (previous != null) {
                    activeElapsedSeconds += LandscapeGeometry.ambientDeltaSeconds((now - previous).toDouble())
                }
                motionState.advance(now)
                lastUptimeMillis = now
                postInvalidateOnAnimation()
                if (frameCallback === this) scheduleFrameIfNeeded()
            }
        }.also { frameCallback = it }
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun drawLandscape(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val slice = LandscapeGeometry.sliceTransformFor(viewWidth.toDouble(), viewHeight.toDouble())
        if (celestialLayout == null) {
            updateCelestialLayout(width, height)
        }
        val layout = celestialLayout ?: return
        val blend = motionState.paletteBlend.coerceIn(0.0, 1.0)
        val orbits = LandscapeGeometry.orbitPositions(motionState.orbitBlend, layout, layout.celestialY)
        val pan = LandscapeGeometry.panOffset(activeElapsedSeconds)
        val mist = LandscapeGeometry.mistOffset(activeElapsedSeconds)

        // Sky covers the whole view; slice has no letterbox by construction.
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            0f, slice.mapY(0.0), 0f, slice.mapY(1000.0),
            intArrayOf(skyStop(0, blend), skyStop(1, blend), skyStop(2, blend)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, fillPaint)

        // Prototype order: celestial sits outside `.ambient.scene-pan`, so draw it
        // before the panning ridges. Sky stays fixed; only ridges/mist pan.
        drawCelestial(canvas, slice, blend, orbits)

        canvas.save()
        // SVG applies the pan outside the center-origin overscan scale.
        canvas.translate(
            (slice.scale * pan.first).toFloat(),
            (slice.scale * pan.second).toFloat(),
        )
        canvas.translate(viewWidth / 2f, viewHeight / 2f)
        canvas.scale(PAN_OVERSCAN, PAN_OVERSCAN)
        canvas.translate(-viewWidth / 2f, -viewHeight / 2f)
        drawRidge(canvas, slice, FAR_RIDGE, ridgeFill(0, blend))
        drawRidge(canvas, slice, MIDDLE_RIDGE, ridgeFill(1, blend))
        drawRidge(canvas, slice, NEAR_RIDGE, ridgeFill(2, blend))
        canvas.save()
        canvas.translate(
            (slice.scale * mist.first).toFloat(),
            (slice.scale * mist.second).toFloat(),
        )
        drawMist(canvas, slice, blend)
        canvas.restore()
        drawForeground(canvas, slice, blend)
        canvas.restore()
        // The generated-background scrim is fixed to the viewport, not the SVG slice or pan.
        fillPaint.alpha = 255
        fillPaint.shader = readabilityGradient
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, fillPaint)
        fillPaint.shader = null
    }

    private fun drawCelestial(
        canvas: Canvas,
        slice: LandscapeGeometry.SliceTransform,
        blend: Double,
        orbits: LandscapeGeometry.OrbitPositions,
    ) {
        val glowBase = glowColor(blend)
        val glowAlpha = glowPeakAlpha(blend)
        if (orbits.sunAlpha > 0.0) {
            drawGlow(
                canvas, slice,
                orbits.sunX, orbits.sunY + LandscapeGeometry.GLOW_DY,
                glowBase, (glowAlpha * orbits.sunAlpha).toInt(),
            )
            fillPaint.shader = null
            fillPaint.color = withAlpha(SUN_COLOR or (0xFF shl 24), (SUN_ALPHA * orbits.sunAlpha).toInt())
            canvas.drawCircle(
                slice.mapX(orbits.sunX),
                slice.mapY(orbits.sunY),
                max(resources.displayMetrics.density, (slice.scale * LandscapeGeometry.SUN_RADIUS).toFloat()),
                fillPaint,
            )
        }
        if (orbits.moonAlpha > 0.0) {
            drawGlow(
                canvas, slice,
                orbits.moonX, orbits.moonY + LandscapeGeometry.GLOW_DY,
                glowBase, (glowAlpha * orbits.moonAlpha).toInt(),
            )
            fillPaint.shader = null
            fillPaint.color = withAlpha(MOON_COLOR or (0xFF shl 24), (MOON_ALPHA * orbits.moonAlpha).toInt())
            canvas.drawPath(moonPathFor(slice, orbits.moonX, orbits.moonY), fillPaint)
        }
        fillPaint.shader = null
    }

    private fun drawGlow(
        canvas: Canvas,
        slice: LandscapeGeometry.SliceTransform,
        centerX: Double,
        centerY: Double,
        glowBase: Int,
        peakAlpha: Int,
    ) {
        // RadialGradient is circular; scale canvas to the exact 380x290 ellipse.
        canvas.save()
        canvas.translate(slice.mapX(centerX), slice.mapY(centerY))
        canvas.scale(
            (slice.scale * LandscapeGeometry.GLOW_RX).toFloat(),
            (slice.scale * LandscapeGeometry.GLOW_RY).toFloat(),
        )
        fillPaint.alpha = 255
        fillPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(glowBase, peakAlpha), withAlpha(glowBase, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, 1f, fillPaint)
        canvas.restore()
    }

    private fun glowColor(blend: Double): Int = blendColor(NIGHT_LIGHT, DAY_LIGHT, blend)

    private fun glowPeakAlpha(blend: Double): Int =
        LandscapeGeometry.blendChannel(
            LandscapeGeometry.GLOW_NIGHT_ALPHA,
            LandscapeGeometry.GLOW_DAY_ALPHA,
            blend,
        )

    /** Maps the curved prototype moon to the selected orbit and slice. */
    internal fun moonPathFor(
        slice: LandscapeGeometry.SliceTransform,
        moonX: Double,
        moonY: Double,
    ): Path {
        var stage = moonStagePath
        if (stage == null) {
            stage = Path()
            stage.moveTo(LandscapeGeometry.MOON_START_X.toFloat(), LandscapeGeometry.MOON_START_Y.toFloat())
            for (arc in LandscapeGeometry.moonArcs) {
                stage.arcTo(
                    RectF(
                        (arc.centerX - arc.radius).toFloat(),
                        (arc.centerY - arc.radius).toFloat(),
                        (arc.centerX + arc.radius).toFloat(),
                        (arc.centerY + arc.radius).toFloat(),
                    ),
                    Math.toDegrees(arc.startAngle).toFloat(),
                    Math.toDegrees(arc.sweepAngle).toFloat(),
                    false,
                )
            }
            stage.close()
            val scale = LandscapeGeometry.MOON_SCALE.toFloat()
            stage.transform(Matrix().apply {
                setScale(scale, scale, LandscapeGeometry.PIVOT_X.toFloat(), LandscapeGeometry.PIVOT_Y.toFloat())
            })
            moonStagePath = stage
        }
        val path = Path(stage)
        val matrix = Matrix()
        matrix.setScale(slice.scale.toFloat(), slice.scale.toFloat())
        matrix.postTranslate(
            slice.mapX(moonX) - (LandscapeGeometry.PIVOT_X * slice.scale).toFloat(),
            slice.mapY(moonY) - (LandscapeGeometry.PIVOT_Y * slice.scale).toFloat(),
        )
        path.transform(matrix)
        return path
    }

    private fun drawRidge(
        canvas: Canvas,
        slice: LandscapeGeometry.SliceTransform,
        points: List<Pair<Double, Double>>,
        color: Int,
    ) {
        fillPaint.shader = null
        fillPaint.color = color
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(slice.mapX(point.first), slice.mapY(point.second))
            } else {
                path.lineTo(slice.mapX(point.first), slice.mapY(point.second))
            }
        }
        val first = points.first()
        val last = points.last()
        path.lineTo(slice.mapX(last.first), slice.mapY(1000.0))
        path.lineTo(slice.mapX(first.first), slice.mapY(1000.0))
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    /**
     * Draws the two mist shapes with their separate SVG object-bounding-box gradients.
     *
     * @param canvas destination canvas.
     * @param slice stage-to-viewport mapping.
     * @param blend displayed night-to-day palette blend.
     */
    internal fun drawMist(canvas: Canvas, slice: LandscapeGeometry.SliceTransform, blend: Double) {
        val mist = mistColor(blend)
        fillPaint.alpha = 255
        fillPaint.shader = mistGradient(slice, MIST_PATH_TOP, MIST_PATH_BOTTOM, mist)
        val path = Path()
        path.moveTo(slice.mapX(-100.0), slice.mapY(518.0))
        path.quadTo(slice.mapX(250.0), slice.mapY(473.0), slice.mapX(580.0), slice.mapY(558.0))
        path.quadTo(slice.mapX(910.0), slice.mapY(643.0), slice.mapX(1200.0), slice.mapY(526.0))
        path.quadTo(slice.mapX(1490.0), slice.mapY(409.0), slice.mapX(1700.0), slice.mapY(543.0))
        path.lineTo(slice.mapX(1700.0), slice.mapY(710.0))
        path.quadTo(slice.mapX(1280.0), slice.mapY(658.0), slice.mapX(1000.0), slice.mapY(699.0))
        path.quadTo(slice.mapX(720.0), slice.mapY(740.0), slice.mapX(430.0), slice.mapY(649.0))
        path.quadTo(slice.mapX(140.0), slice.mapY(558.0), slice.mapX(-100.0), slice.mapY(691.0))
        path.close()
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = mistGradient(slice, 650.0 - 64.0, 650.0 + 64.0, mist)
        canvas.drawOval(
            slice.mapX(990.0 - 710.0),
            slice.mapY(650.0 - 64.0),
            slice.mapX(990.0 + 710.0),
            slice.mapY(650.0 + 64.0),
            fillPaint,
        )
    }

    private fun mistGradient(
        slice: LandscapeGeometry.SliceTransform,
        top: Double,
        bottom: Double,
        mist: Int,
    ): LinearGradient = LinearGradient(
        0f, slice.mapY(top), 0f, slice.mapY(bottom),
        intArrayOf(withAlpha(mist, 0), mist, withAlpha(mist, 0)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )

    /**
     * Draws the opaque near gradient and the foreground bank and foot.
     *
     * @param canvas destination canvas.
     * @param slice stage-to-viewport mapping.
     * @param blend displayed night-to-day palette blend.
     */
    internal fun drawForeground(canvas: Canvas, slice: LandscapeGeometry.SliceTransform, blend: Double) {
        fillPaint.alpha = 255
        fillPaint.shader = LinearGradient(
            0f, slice.mapY(626.0), 0f, slice.mapY(1000.0),
            intArrayOf(nearTop(blend), nearBottom(blend)),
            null,
            Shader.TileMode.CLAMP,
        )
        val slope = Path()
        FOREGROUND_SLOPE.forEachIndexed { index, point ->
            if (index == 0) {
                slope.moveTo(slice.mapX(point.first), slice.mapY(point.second))
            } else {
                slope.lineTo(slice.mapX(point.first), slice.mapY(point.second))
            }
        }
        slope.close()
        canvas.drawPath(slope, fillPaint)
        fillPaint.shader = null
        fillPaint.color = ridgeFill(3, blend)
        val bank = Path()
        BANK.forEachIndexed { index, point ->
            if (index == 0) {
                bank.moveTo(slice.mapX(point.first), slice.mapY(point.second))
            } else {
                bank.lineTo(slice.mapX(point.first), slice.mapY(point.second))
            }
        }
        bank.lineTo(slice.mapX(1600.0), slice.mapY(1000.0))
        bank.close()
        canvas.drawPath(bank, fillPaint)
        fillPaint.color = ridgeFill(4, blend)
        val foot = Path()
        FOOT.forEachIndexed { index, point ->
            if (index == 0) {
                foot.moveTo(slice.mapX(point.first), slice.mapY(point.second))
            } else {
                foot.lineTo(slice.mapX(point.first), slice.mapY(point.second))
            }
        }
        foot.lineTo(slice.mapX(706.0), slice.mapY(1000.0))
        foot.lineTo(slice.mapX(0.0), slice.mapY(1000.0))
        foot.close()
        canvas.drawPath(foot, fillPaint)
    }

    private fun skyStop(index: Int, blend: Double): Int = when (index) {
        0 -> blendColor(NIGHT_SKY_TOP, DAY_SKY_TOP, blend)
        1 -> blendColor(NIGHT_SKY_MID, DAY_SKY_MID, blend)
        else -> blendColor(NIGHT_SKY_BOTTOM, DAY_SKY_BOTTOM, blend)
    }

    private fun nearTop(blend: Double): Int = blendColor(NIGHT_NEAR_TOP, DAY_NEAR_TOP, blend)

    private fun nearBottom(blend: Double): Int = blendColor(NIGHT_NEAR_BOTTOM, DAY_NEAR_BOTTOM, blend)

    private fun mistColor(blend: Double): Int {
        val base = blendColor(NIGHT_MIST, DAY_MIST, blend)
        val alpha = (NIGHT_MIST_MID_ALPHA + (DAY_MIST_MID_ALPHA - NIGHT_MIST_MID_ALPHA) * blend).toInt()
        return withAlpha(base, alpha)
    }

    private fun lightColor(blend: Double): Int = glowColor(blend)

    private fun ridgeFill(index: Int, blend: Double): Int = when (index) {
        0 -> blendColor(NIGHT_RIDGE_FAR, DAY_RIDGE_FAR, blend)
        1 -> blendColor(NIGHT_RIDGE_MIDDLE, DAY_RIDGE_MIDDLE, blend)
        2 -> blendColor(NIGHT_RIDGE_NEAR, DAY_RIDGE_NEAR, blend)
        3 -> blendColor(NIGHT_BANK, DAY_BANK, blend)
        else -> blendColor(NIGHT_FOOT, DAY_FOOT, blend)
    }

    private fun blendColor(night: Int, day: Int, blend: Double): Int {
        val eased = blend.coerceIn(0.0, 1.0)
        val r = LandscapeGeometry.blendChannel(night shr 16 and 0xFF, day shr 16 and 0xFF, eased)
        val g = LandscapeGeometry.blendChannel(night shr 8 and 0xFF, day shr 8 and 0xFF, eased)
        val b = LandscapeGeometry.blendChannel(night and 0xFF, day and 0xFF, eased)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    companion object {
        // Quadratic extrema of the two mist path segments in the prototype SVG.
        private const val MIST_PATH_TOP = 526.0 - 117.0 * 117.0 / 251.0
        private const val MIST_PATH_BOTTOM = 699.0 + 41.0 * 41.0 / 132.0

        /** Log tag for non-fatal callback failures. */
        const val TAG = "LandscapeSceneView"

        /** View-space pan overscan matching the prototype `scale(1.06)`. */
        const val PAN_OVERSCAN = 1.06f

        /** Glow vertical offset from the disc center in viewBox units. */
        const val GLOW_DY = 16.0

        /** Glow horizontal radius in viewBox units. */
        const val GLOW_RX = 380.0

        /** Day sky stops from `#a-sky`. */
        const val DAY_SKY_TOP = 0x263B3D
        const val DAY_SKY_MID = 0x9BA9A0
        const val DAY_SKY_BOTTOM = 0x334944

        /** Night sky stops from `.v-A:not(.is-broadcasting) #a-sky`. */
        const val NIGHT_SKY_TOP = 0x070D14
        const val NIGHT_SKY_MID = 0x243C43
        const val NIGHT_SKY_BOTTOM = 0x0B1A21

        /** Day foreground gradient from `#a-near`. */
        const val DAY_NEAR_TOP = 0x233B36
        const val DAY_NEAR_BOTTOM = 0x0A1B18

        /** Night foreground gradient stops. */
        const val NIGHT_NEAR_TOP = 0x101E23
        const val NIGHT_NEAR_BOTTOM = 0x050D10

        /** Day ridge fills. */
        const val DAY_RIDGE_FAR = 0x6D827A
        const val DAY_RIDGE_MIDDLE = 0x4E6B60
        const val DAY_RIDGE_NEAR = 0x3C5A50
        const val DAY_BANK = 0x0D2520
        const val DAY_FOOT = 0x081A16

        /** Night ridge fills. */
        const val NIGHT_RIDGE_FAR = 0x2E454A
        const val NIGHT_RIDGE_MIDDLE = 0x20353B
        const val NIGHT_RIDGE_NEAR = 0x152C32
        const val NIGHT_BANK = 0x0A171C
        const val NIGHT_FOOT = 0x060F13

        /** Mist tint; alpha handled separately per stop. */
        const val DAY_MIST = 0xC1C7BA
        const val NIGHT_MIST = 0x849B9B
        const val DAY_MIST_MID_ALPHA = 132
        const val NIGHT_MIST_MID_ALPHA = 41

        /** Celestial glow tint. */
        const val DAY_LIGHT = 0xD4D8C3
        const val NIGHT_LIGHT = 0xACBEC5

        /** Sun disc from `.a-sun`. */
        const val SUN_COLOR = 0xDADFC8
        const val SUN_ALPHA = 174

        /** Moon disc from `.a-moon` (crescent hollow is outstanding). */
        const val MOON_COLOR = 0xD2DDD8
        const val MOON_ALPHA = 204

        /** Far ridge polyline from the prototype path. */
        val FAR_RIDGE: List<Pair<Double, Double>> = listOf(
            Pair(0.0, 496.0), Pair(96.0, 464.0), Pair(169.0, 478.0), Pair(276.0, 375.0),
            Pair(342.0, 420.0), Pair(440.0, 315.0), Pair(525.0, 397.0), Pair(588.0, 363.0),
            Pair(688.0, 449.0), Pair(783.0, 371.0), Pair(837.0, 399.0), Pair(928.0, 310.0),
            Pair(1020.0, 355.0), Pair(1082.0, 324.0), Pair(1180.0, 405.0), Pair(1285.0, 340.0),
            Pair(1379.0, 392.0), Pair(1494.0, 306.0), Pair(1600.0, 367.0),
        )

        /** Middle ridge polyline from the prototype path. */
        val MIDDLE_RIDGE: List<Pair<Double, Double>> = listOf(
            Pair(0.0, 574.0), Pair(115.0, 502.0), Pair(180.0, 522.0), Pair(270.0, 475.0),
            Pair(340.0, 495.0), Pair(446.0, 459.0), Pair(532.0, 492.0), Pair(585.0, 483.0),
            Pair(685.0, 563.0), Pair(793.0, 518.0), Pair(863.0, 528.0), Pair(973.0, 436.0),
            Pair(1058.0, 460.0), Pair(1148.0, 389.0), Pair(1196.0, 433.0), Pair(1308.0, 405.0),
            Pair(1438.0, 483.0), Pair(1545.0, 456.0), Pair(1600.0, 471.0),
        )

        /** Near ridge polyline from the prototype path. */
        val NEAR_RIDGE: List<Pair<Double, Double>> = listOf(
            Pair(1600.0, 599.0), Pair(1472.0, 529.0), Pair(1346.0, 551.0), Pair(1241.0, 499.0),
            Pair(1115.0, 553.0), Pair(1052.0, 521.0), Pair(962.0, 572.0), Pair(852.0, 545.0),
            Pair(730.0, 587.0), Pair(603.0, 558.0), Pair(476.0, 606.0), Pair(328.0, 567.0),
            Pair(166.0, 640.0), Pair(0.0, 597.0),
        )

        /** Foreground slope polygon from the `#a-near` path. */
        val FOREGROUND_SLOPE: List<Pair<Double, Double>> = listOf(
            Pair(0.0, 1000.0), Pair(0.0, 729.0), Pair(80.0, 702.0), Pair(179.0, 720.0),
            Pair(250.0, 674.0), Pair(324.0, 685.0), Pair(386.0, 650.0), Pair(450.0, 669.0),
            Pair(519.0, 626.0), Pair(599.0, 651.0), Pair(685.0, 633.0), Pair(769.0, 695.0),
            Pair(883.0, 710.0), Pair(978.0, 766.0), Pair(1114.0, 791.0), Pair(1271.0, 855.0),
            Pair(1446.0, 895.0), Pair(1600.0, 925.0), Pair(1600.0, 1000.0),
        )

        /** Bank polygon from the `.a-bank` path. */
        val BANK: List<Pair<Double, Double>> = listOf(
            Pair(1600.0, 666.0), Pair(1481.0, 695.0), Pair(1437.0, 740.0), Pair(1361.0, 751.0),
            Pair(1291.0, 810.0), Pair(1200.0, 831.0), Pair(1129.0, 879.0), Pair(1010.0, 901.0),
            Pair(947.0, 960.0), Pair(855.0, 1000.0), Pair(1600.0, 1000.0),
        )

        /** Foot polygon from the `.a-foot` path. */
        val FOOT: List<Pair<Double, Double>> = listOf(
            Pair(0.0, 915.0), Pair(146.0, 882.0), Pair(290.0, 913.0), Pair(368.0, 893.0),
            Pair(443.0, 935.0), Pair(577.0, 944.0),
        )
    }
}
