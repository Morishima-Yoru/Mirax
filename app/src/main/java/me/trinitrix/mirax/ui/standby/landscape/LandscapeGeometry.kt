package me.trinitrix.mirax.ui.standby.landscape

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry for the Landscape background candidate.
 *
 * Ports only observable prototype values from `design/big-standby-prototype.html`:
 * `#landscape` `1600x1000` `xMidYMid slice` stage (line 668), sky/near/mist/light
 * gradients (lines 670-673), ridge/bank/foot polygons (lines 687-693), celestial
 * pivots `1110,204` / glow `1110,220 rx380 ry290` / sun `r30` (lines 677-679),
 * moon path `M1125 182a27 27 0 1 0 0 44a24 24 0 0 1 0-44Z` scaled `1.4074074074`
 * about the pivot (line 683), ambient pan/mist clocks (lines 2732-2735), CSS
 * `ease-in-out` (`cubic-bezier(0.42, 0, 0.58, 1)`, line 80/96), and the
 * `layoutCelestial` slice/clamp math (lines 2809-2852). The celestial group sits
 * outside `.ambient.scene-pan`, so it never pans with the ridges.
 * Day/night follows only broadcast intent: `previewNativePhase` lines 2049-2055
 * keep intent when entering frozen, CSS lines 81-100 key on `is-broadcasting`.
 * No Android, clock, or settings access.
 */
object LandscapeGeometry {

    /** Stage viewBox width from the prototype SVG. */
    const val VIEWBOX_WIDTH = 1600.0

    /** Stage viewBox height from the prototype SVG. */
    const val VIEWBOX_HEIGHT = 1000.0

    /** Sun pivot X in viewBox units. */
    const val PIVOT_X = 1110.0

    /** Sun pivot Y in viewBox units. */
    const val PIVOT_Y = 204.0

    /** Sun disc radius in viewBox units. */
    const val SUN_RADIUS = 30.0

    /** Moon path scale about the pivot from the prototype transform. */
    const val MOON_SCALE = 1.4074074074

    /** Exact moon crescent path data from the prototype (line 683). */
    const val MOON_PATH_DATA = "M1125 182a27 27 0 1 0 0 44a24 24 0 0 1 0-44Z"

    /** Celestial glow horizontal radius in viewBox units (`rx380`). */
    const val GLOW_RX = 380.0

    /** Celestial glow vertical radius in viewBox units (`ry290`). */
    const val GLOW_RY = 290.0

    /** Glow vertical offset from the disc pivot in viewBox units (`cy220 - 204`). */
    const val GLOW_DY = 16.0

    /** Day glow peak alpha from `#a-light` first stop (0.4 * 255). */
    const val GLOW_DAY_ALPHA = 102

    /** Night glow peak alpha from the night override (0.12 * 255, truncated). */
    const val GLOW_NIGHT_ALPHA = 30

    /** Moon arc radii and shared chord length, from the SVG path. */
    const val MOON_OUTER_RX = 27.0
    const val MOON_INNER_RX = 24.0
    const val MOON_CHORD = 44.0

    /** Chromium 156 SVG getPointAtLength, 64 equal full-path samples, including the node transform. */
    // shortcut: fixed SVG path and Chromium reference, remeasure if either changes.
    private const val MOON_SAMPLED_RADIUS = 38.91758201652768

    /** Moon subpath start in viewBox units. */
    const val MOON_START_X = 1125.0
    const val MOON_START_Y = 182.0

    /** CSS `ease-in-out` first control X (`cubic-bezier(0.42, 0, 0.58, 1)`). */
    const val EASE_X1 = 0.42

    /** CSS `ease-in-out` first control Y. */
    const val EASE_Y1 = 0.0

    /** CSS `ease-in-out` second control X. */
    const val EASE_X2 = 0.58

    /** CSS `ease-in-out` second control Y. */
    const val EASE_Y2 = 1.0

    /** Broadcast color transition duration in seconds (prototype CSS 3.6s). */
    const val BROADCAST_TRANSITION_SECONDS = 3.6

    /** Ambient pan amplitude in viewBox units (`sin(t*.38)*38`, `cos(t*.29)*18`). */
    const val PAN_X_AMPLITUDE = 38.0

    /** Ambient pan Y amplitude in viewBox units. */
    const val PAN_Y_AMPLITUDE = 18.0

    /** Mist drift amplitude in viewBox units (`sin(t*.55)*120`). */
    const val MIST_X_AMPLITUDE = 120.0

    /** Mist drift Y amplitude in viewBox units (`cos(t*.34)*12`). */
    const val MIST_Y_AMPLITUDE = 12.0

    /** Pan overscan scale from the prototype (`scale(1.06)`). */
    const val PAN_OVERSCAN = 1.06

    /**
     * Celestial bounding radius in viewBox units.
     *
     * The moon wins over the sun in the prototype's 64 equal-length samples.
     * This is a sampled layout radius, not an exact bound on the curved path.
     */
    val CELESTIAL_RADIUS: Double get() = celestialRadius()

    /**
     * `xMidYMid slice` mapping from stage viewBox units to view pixels.
     *
     * @param scale uniform stage scale.
     * @param offsetX horizontal stage offset in px.
     * @param offsetY vertical stage offset in px.
     */
    data class SliceTransform(
        val scale: Double,
        val offsetX: Double,
        val offsetY: Double,
    ) {
        /** Map a stage X coordinate to view pixels. */
        fun mapX(x: Double): Float = (offsetX + x * scale).toFloat()

        /** Map a stage Y coordinate to view pixels. */
        fun mapY(y: Double): Float = (offsetY + y * scale).toFloat()
    }

    /**
     * Celestial placement in viewBox units.
     *
     * @param celestialX stage X of the pivot after centering.
     * @param celestialY stage Y of the pivot after vertical placement.
     * @param edgeOffset hidden-orbit horizontal distance in viewBox units.
     * @param horizonY hidden-orbit vertical distance in viewBox units.
     */
    data class CelestialLayout(
        val celestialX: Double,
        val celestialY: Double,
        val edgeOffset: Double,
        val horizonY: Double,
    )

    /**
     * Sun/moon orbit positions for a day blend.
     *
     * Mirrors CSS lines 97-100: at blend 1 the sun sits at the pivot and the
     * moon hides right; at blend 0 they swap.
     */
    data class OrbitPositions(
        val sunX: Double,
        val sunY: Double,
        val sunAlpha: Double,
        val moonX: Double,
        val moonY: Double,
        val moonAlpha: Double,
    )

    /**
     * Circle arc in SVG coordinates, with angles in radians.
     * @param centerX circle center X.
     * @param centerY circle center Y.
     * @param radius circle radius.
     * @param startAngle start angle.
     * @param sweepAngle signed sweep angle.
     */
    internal data class MoonArc(
        val centerX: Double,
        val centerY: Double,
        val radius: Double,
        val startAngle: Double,
        val sweepAngle: Double,
    )

    /** The two SVG arcs, in path order. */
    internal val moonArcs: List<MoonArc> = run {
        val halfChord = MOON_CHORD / 2.0
        val outerOffset = sqrt(MOON_OUTER_RX * MOON_OUTER_RX - halfChord * halfChord)
        val innerOffset = sqrt(MOON_INNER_RX * MOON_INNER_RX - halfChord * halfChord)
        val outerStart = atan2(-halfChord, outerOffset)
        val innerStart = atan2(halfChord, -innerOffset)
        listOf(
            MoonArc(MOON_START_X - outerOffset, PIVOT_Y, MOON_OUTER_RX, outerStart, -2.0 * Math.PI - 2.0 * outerStart),
            MoonArc(MOON_START_X + innerOffset, PIVOT_Y, MOON_INNER_RX, innerStart, 2.0 * Math.PI - 2.0 * innerStart),
        )
    }

    /**
     * `xMidYMid slice` transform for the given view size.
     *
     * @param viewWidth view width in px.
     * @param viewHeight view height in px.
     */
    fun sliceTransformFor(viewWidth: Double, viewHeight: Double): SliceTransform {
        val scale = max(viewWidth / VIEWBOX_WIDTH, viewHeight / VIEWBOX_HEIGHT)
        return SliceTransform(
            scale = scale,
            offsetX = (viewWidth - VIEWBOX_WIDTH * scale) / 2.0,
            offsetY = (viewHeight - VIEWBOX_HEIGHT * scale) / 2.0,
        )
    }

    /**
     * Returns the prototype's sampled celestial radius, not an exact bound.
     *
     * The fixed moon path and transform were measured with Chromium 156 using
     * `celestialNodeRadius`: 64 equal-length samples over the complete SVG path.
     * @return the larger of the sun radius and the measured moon radius.
     */
    fun celestialRadius(): Double = max(SUN_RADIUS, MOON_SAMPLED_RADIUS)

    /**
     * Celestial pivot placement without host foreground clamping.
     *
     * Mirrors `layoutCelestial` lines 2816-2822; the brand/primary clamp
     * (lines 2823-2837) is applied separately via [clampCelestialY] only when
     * the host supplies real foreground geometry.
     *
     * @param viewWidth view width in px.
     * @param viewHeight view height in px.
     */
    fun celestialLayoutFor(viewWidth: Double, viewHeight: Double): CelestialLayout {
        if (viewWidth <= 0.0 || viewHeight <= 0.0) {
            return CelestialLayout(PIVOT_X, PIVOT_Y, 0.0, 0.0)
        }
        val scale = maxOf(viewWidth / VIEWBOX_WIDTH, viewHeight / VIEWBOX_HEIGHT, 0.001)
        val visibleWidth = viewWidth / scale
        val visibleHeight = viewHeight / scale
        val visibleTop = (VIEWBOX_HEIGHT - visibleHeight) / 2.0
        val radius = celestialRadius()
        val portraitFloor = max(48.0, radius + 8.0)
        val portraitY = max(
            visibleTop + portraitFloor,
            min(visibleTop + visibleHeight * 0.25, VIEWBOX_HEIGHT * 0.25),
        )
        val celestialY = (if (viewWidth > viewHeight) {
            max(visibleTop + radius + 8.0, portraitY - visibleHeight * 0.06)
        } else {
            portraitY
        }) + visibleHeight * 0.045
        val edgeOffset = visibleWidth / 2.0 + radius + 16.0 / scale
        return CelestialLayout(
            celestialX = VIEWBOX_WIDTH / 2.0,
            celestialY = celestialY,
            edgeOffset = edgeOffset,
            horizonY = visibleHeight * 0.09,
        )
    }

    /**
     * Sun/moon orbit positions for a day blend.
     *
     * Prototype layout writes its final offsets with JavaScript `toFixed(2)`.
     * @param blend eased day blend in `[0, 1]` (0 night, 1 day).
     * @param layout layout from [celestialLayoutFor].
     * @param celestialY clamped pivot Y in viewBox units.
     */
    fun orbitPositions(blend: Double, layout: CelestialLayout, celestialY: Double): OrbitPositions {
        val clamped = blend.coerceIn(0.0, 1.0)
        val pivotY = PIVOT_Y + fixed(celestialY - PIVOT_Y, 2)
        val edgeOffset = fixed(layout.edgeOffset, 2)
        val horizonY = fixed(layout.horizonY, 2)
        return OrbitPositions(
            sunX = layout.celestialX - edgeOffset * (1.0 - clamped),
            sunY = pivotY + horizonY * (1.0 - clamped),
            sunAlpha = clamped,
            moonX = layout.celestialX + edgeOffset * clamped,
            moonY = pivotY + horizonY * clamped,
            moonAlpha = 1.0 - clamped,
        )
    }

    private fun fixed(value: Double, decimals: Int): Double =
        BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

    /**
     * Returns the strings compared by the prototype before resetting orbit transitions.
     * @param layout calculated celestial layout.
     * @param celestialY clamped celestial Y coordinate.
     * @return formatted transform and orbit offsets.
     */
    internal fun celestialLayoutKey(layout: CelestialLayout, celestialY: Double): List<String> {
        fun format(value: Double): String {
            val rounded = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
            return if (value < 0.0 && !rounded.startsWith('-')) "-$rounded" else rounded
        }
        return listOf(
            format(layout.celestialX - PIVOT_X),
            format(celestialY - PIVOT_Y),
            format(-layout.edgeOffset),
            format(layout.edgeOffset),
            format(layout.horizonY),
        )
    }

    /**
     * Clamps a celestial Y between real host foreground edges.
     *
     * Mirrors the prototype `minCenter`/`maxCenter` clamp: both edges arrive in
     * view px, convert with the slice scale, keep the radius plus an 8px gap.
     * Returns the input unchanged while geometry is unknown or infeasible.
     *
     * @param celestialY unclamped pivot Y in viewBox units.
     * @param brandBottomPx real brand bottom in view px, or null while unknown.
     * @param primaryTopPx real primary top in view px, or null while unknown.
     * @param viewWidth view width in px.
     * @param viewHeight view height in px.
     */
    fun clampCelestialY(
        celestialY: Double,
        brandBottomPx: Float?,
        primaryTopPx: Float?,
        viewWidth: Double,
        viewHeight: Double,
    ): Double {
        if (brandBottomPx == null || primaryTopPx == null) {
            return celestialY
        }
        if (viewWidth <= 0.0 || viewHeight <= 0.0) {
            return celestialY
        }
        val scale = maxOf(viewWidth / VIEWBOX_WIDTH, viewHeight / VIEWBOX_HEIGHT, 0.001)
        val visibleHeight = viewHeight / scale
        val visibleTop = (VIEWBOX_HEIGHT - visibleHeight) / 2.0
        val gap = 8.0 / scale
        val radius = celestialRadius()
        val minCenter = visibleTop + brandBottomPx / scale + radius + gap
        val maxCenter = visibleTop + primaryTopPx / scale - radius - gap
        if (maxCenter < minCenter) {
            return celestialY
        }
        return min(max(celestialY, minCenter), maxCenter)
    }

    /**
     * Ambient scene-pan offset in viewBox units at monotonic seconds.
     *
     * @param timeSeconds monotonic seconds while visible.
     */
    fun panOffset(timeSeconds: Double): Pair<Double, Double> = Pair(
        fixed(sin(timeSeconds * 0.38) * PAN_X_AMPLITUDE, 1),
        fixed(cos(timeSeconds * 0.29) * PAN_Y_AMPLITUDE, 1),
    )

    /**
     * Mist drift offset in viewBox units at monotonic seconds.
     *
     * @param timeSeconds monotonic seconds while visible.
     */
    fun mistOffset(timeSeconds: Double): Pair<Double, Double> = Pair(
        fixed(sin(timeSeconds * 0.55) * MIST_X_AMPLITUDE, 1),
        fixed(cos(timeSeconds * 0.34) * MIST_Y_AMPLITUDE, 1),
    )

    /**
     * Exact CSS `ease-in-out` (`cubic-bezier(0.42, 0, 0.58, 1)`).
     *
     * Solves x(t) = x for t via Newton iteration with bisection fallback, then
     * returns y(t). Reference: prototype CSS lines 80/96 `ease-in-out`.
     *
     * @param fraction linear progress in `[0, 1]`.
     */
    fun easeInOut(fraction: Double): Double {
        val x = fraction.coerceIn(0.0, 1.0)
        if (x <= 0.0 || x >= 1.0) {
            return x
        }
        var t = x
        repeat(8) {
            val error = cubicX(t) - x
            if (abs(error) < 1e-7) {
                return cubicY(t)
            }
            val slope = cubicXDerivative(t)
            if (abs(slope) < 1e-6) {
                return@repeat
            }
            t = (t - error / slope).coerceIn(0.0, 1.0)
        }
        var lower = 0.0
        var upper = 1.0
        t = x
        repeat(24) {
            val error = cubicX(t) - x
            if (abs(error) < 1e-7) {
                return cubicY(t)
            }
            if (error > 0.0) {
                upper = t
            } else {
                lower = t
            }
            t = (lower + upper) / 2.0
        }
        return cubicY(t)
    }

    private fun cubicX(t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * t * EASE_X1 + 3.0 * u * t * t * EASE_X2 + t * t * t
    }

    private fun cubicY(t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * t * EASE_Y1 + 3.0 * u * t * t * EASE_Y2 + t * t * t
    }

    private fun cubicXDerivative(t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * EASE_X1 + 6.0 * u * t * (EASE_X2 - EASE_X1) + 3.0 * t * t * (1.0 - EASE_X2)
    }

    /**
     * CSS reversal duration for binary day/night targets.
     *
     * @param from displayed blend when the target changes.
     * @param to binary day/night target.
     * @return duration in seconds, shortened by the remaining distance.
     */
    fun transitionDuration(from: Double, to: Double): Double =
        BROADCAST_TRANSITION_SECONDS * abs(to - from)

    /**
     * Prototype ambient clock delta, clamped independently of CSS transitions.
     *
     * @param elapsedMillis elapsed milliseconds since the previous frame.
     * @return clamped ambient seconds.
     */
    fun ambientDeltaSeconds(elapsedMillis: Double): Double = elapsedMillis.coerceIn(0.0, 80.0) / 1000.0

    /**
     * CSS-transition value from [from] toward [to] after [elapsedSeconds].
     *
     * @param from displayed value when the transition started.
     * @param to target value.
     * @param elapsedSeconds seconds since the start.
     * @param durationSeconds actual transition duration after reversal shortening.
     * @return eased blend.
     */
    fun transitionBlend(
        from: Double,
        to: Double,
        elapsedSeconds: Double,
        durationSeconds: Double = BROADCAST_TRANSITION_SECONDS,
    ): Double {
        if (from == to || elapsedSeconds <= 0.0) {
            return from
        }
        val progress = (elapsedSeconds / durationSeconds).coerceIn(0.0, 1.0)
        return from + (to - from) * easeInOut(progress)
    }

    /**
     * Day-blend target for the given standby state.
     *
     * Prototype reference: `previewNativePhase` lines 2049-2055 keeps the real
     * broadcast intent when entering frozen, and CSS lines 81-100 key day/night
     * only on `is-broadcasting`. Frozen therefore never forces night here; the
     * host owns the frozen dialog separately.
     *
     * @param broadcasting real broadcast intent from the caller.
     * @param frozen real frozen flag from the caller, recorded but palette-neutral.
     */
    fun broadcastTarget(broadcasting: Boolean, frozen: Boolean): Double =
        if (broadcasting) 1.0 else 0.0

    /**
     * Interpolates one sRGB channel between night and day stops.
     *
     * @param night night channel in `[0, 255]`.
     * @param day day channel in `[0, 255]`.
     * @param blend eased day blend in `[0, 1]`.
     */
    fun blendChannel(night: Int, day: Int, blend: Double): Int =
        (night + (day - night) * blend.coerceIn(0.0, 1.0)).toInt().coerceIn(0, 255)
}

/** Tracks palette and orbit transitions independently, including prototype layout resets. */
internal class LandscapeMotionState {
    private val palette = Transition()
    private val orbit = Transition()
    private var layoutKey: List<String>? = null

    val paletteBlend: Double get() = palette.value
    val orbitBlend: Double get() = orbit.value
    val target: Double get() = palette.target

    /**
     * Updates both tracks from their actual current values when the target changes.
     * @param target binary broadcast target.
     * @param nowMillis monotonic time in milliseconds.
     * @param animating whether transitions are enabled.
     */
    fun setTarget(target: Double, nowMillis: Long, animating: Boolean) {
        if (animating) {
            advance(nowMillis)
        }
        if (target != palette.target) {
            palette.setTarget(target, nowMillis)
            orbit.setTarget(target, nowMillis)
        }
        if (!animating) {
            stop()
        }
    }

    /**
     * Advances both transitions without changing their targets.
     * @param nowMillis monotonic time in milliseconds.
     */
    fun advance(nowMillis: Long) {
        palette.advance(nowMillis)
        orbit.advance(nowMillis)
    }

    /** Snaps both tracks when prototype motion is stopped. */
    fun stop() {
        palette.snap()
        orbit.snap()
    }

    /**
     * Calculates host-clamped placement and resets only orbits if the formatted layout changes.
     * @param width view width in pixels.
     * @param height view height in pixels.
     * @param brandBottomPx brand bottom relative to the view, or null.
     * @param primaryTopPx primary top relative to the view, or null.
     * @return the clamped placement, or null for an unmeasurable view.
     */
    fun updateLayout(
        width: Double,
        height: Double,
        brandBottomPx: Float?,
        primaryTopPx: Float?,
    ): LandscapeGeometry.CelestialLayout? {
        if (width <= 0.0 || height <= 0.0) {
            return null
        }
        val layout = LandscapeGeometry.celestialLayoutFor(width, height)
        val celestialY = LandscapeGeometry.clampCelestialY(
            layout.celestialY, brandBottomPx, primaryTopPx, width, height,
        )
        val nextKey = LandscapeGeometry.celestialLayoutKey(layout, celestialY)
        if (nextKey != layoutKey) {
            layoutKey = nextKey
            orbit.snap()
        }
        return layout.copy(celestialY = celestialY)
    }

    private class Transition {
        var value = 0.0
            private set
        var target = 0.0
            private set
        private var from = 0.0
        private var durationSeconds = 0.0
        private var startMillis: Long? = null

        fun setTarget(next: Double, nowMillis: Long) {
            from = value
            target = next
            durationSeconds = LandscapeGeometry.transitionDuration(from, target)
            startMillis = if (durationSeconds > 0.0) nowMillis else null
        }

        fun advance(nowMillis: Long) {
            val start = startMillis ?: return
            val elapsed = (nowMillis - start).coerceAtLeast(0L) / 1000.0
            value = LandscapeGeometry.transitionBlend(from, target, elapsed, durationSeconds)
            if (elapsed >= durationSeconds) {
                startMillis = null
            }
        }

        fun snap() {
            value = target
            startMillis = null
        }
    }
}
