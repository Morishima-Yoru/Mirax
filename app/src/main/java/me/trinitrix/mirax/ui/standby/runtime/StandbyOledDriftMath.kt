package me.trinitrix.mirax.ui.standby.runtime

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure port of OLED drift and compensation math.
 *
 * C random walk: `design/big-standby-c-oled-drift.js` lines 15-36
 * (`pickTarget` in [-3, 3]px, `duration = max(150, hypot * 75)`,
 * per-frame clamp 0.25s, smootherstep blend, retarget on arrival).
 * Legacy burn compensation: `design/big-standby-prototype.html` lines
 * 2698-2707 (`applyXPlaneTransform` adds the shared shift to caller motion
 * through the CTM unit) and lines 2724-2725 (sinusoidal burn offsets).
 * DOM, CSS variables, and SVG writes stripped; no Android APIs.
 */
object StandbyOledDriftMath {

    /** Random-walk target radius in px (oled-drift line 16-17). */
    const val MAX_RADIUS_PX = 3.0

    /** Minimum excursion duration in seconds (oled-drift line 19). */
    const val MIN_DURATION_SECONDS = 150.0

    /** Seconds of travel per px of distance (oled-drift line 19). */
    const val SECONDS_PER_PX = 75.0

    /** Per-frame delta clamp in seconds (oled-drift line 24). */
    const val MAX_DELTA_SECONDS = 0.25

    /**
     * Random-walk state.
     *
     * @param fromX segment start X in px.
     * @param fromY segment start Y in px.
     * @param targetX segment end X in px.
     * @param targetY segment end Y in px.
     * @param elapsed seconds spent on this segment.
     * @param duration segment length in seconds.
     */
    data class Walk(
        val fromX: Double = 0.0,
        val fromY: Double = 0.0,
        val targetX: Double = 0.0,
        val targetY: Double = 0.0,
        val elapsed: Double = 0.0,
        val duration: Double = MIN_DURATION_SECONDS,
    )

    /**
     * Picks a target inside [-3, 3]px and derives the duration.
     *
     * Non-finite random output falls back to 0 so the walk stays bounded.
     *
     * @param random source returning [0, 1).
     * @param fromX segment start X in px.
     * @param fromY segment start Y in px.
     * @return next walk segment starting at elapsed 0.
     */
    fun pickTarget(random: () -> Double, fromX: Double, fromY: Double): Walk {
        fun component(): Double {
            val raw = random()
            if (!raw.isFinite()) return 0.0
            return (raw * 2.0 - 1.0) * MAX_RADIUS_PX
        }
        val targetX = component()
        val targetY = component()
        val duration = maxOf(MIN_DURATION_SECONDS, hypot(targetX - fromX, targetY - fromY) * SECONDS_PER_PX)
        return Walk(fromX, fromY, targetX, targetY, 0.0, duration)
    }

    /**
     * Advances the walk; non-positive or non-finite deltas hold position.
     *
     * @param walk current segment.
     * @param deltaSeconds raw frame delta.
     * @param random source used only when the segment completes.
     * @return pair of next segment and current position in px.
     */
    fun advance(walk: Walk, deltaSeconds: Double, random: () -> Double): Pair<Walk, Point> {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
            val held = walk.elapsed.coerceIn(0.0, walk.duration)
            return walk to positionAt(walk, held)
        }
        val elapsed = minOf(walk.duration, walk.elapsed + minOf(deltaSeconds, MAX_DELTA_SECONDS))
        val point = positionAt(walk, elapsed)
        if (elapsed >= walk.duration) {
            val next = pickTarget(random, point.x, point.y)
            return next to Point(point.x, point.y, next)
        }
        val advanced = walk.copy(elapsed = elapsed)
        return advanced to Point(point.x, point.y, advanced)
    }

    private fun positionAt(walk: Walk, elapsed: Double): Point {
        val progress = (elapsed / walk.duration).coerceIn(0.0, 1.0)
        // Smootherstep (oled-drift line 26).
        val blend = progress * progress * progress * (progress * (progress * 6.0 - 15.0) + 10.0)
        val x = walk.fromX + (walk.targetX - walk.fromX) * blend
        val y = walk.fromY + (walk.targetY - walk.fromY) * blend
        return Point(x, y, walk)
    }

    /**
     * Position snapshot in px.
     *
     * @param x horizontal offset in px.
     * @param y vertical offset in px.
     * @param walk segment producing this position.
     */
    data class Point(
        val x: Double,
        val y: Double,
        val walk: Walk,
    )

    /**
     * Legacy shared burn offsets (prototype lines 2724-2725).
     *
     * Non-finite time yields zeros.
     *
     * @param timeSeconds monotonic clock in seconds.
     * @return shared shift in px.
     */
    fun burnOffsets(timeSeconds: Double): BurnShift {
        if (!timeSeconds.isFinite()) return BurnShift(0.0, 0.0)
        return BurnShift(
            x = sin(timeSeconds * 0.12) * 3.2 + cos(timeSeconds * 0.05) * 1.2,
            y = cos(timeSeconds * 0.09) * 2.4 + sin(timeSeconds * 0.04) * 0.9,
        )
    }

    /**
     * Shared shift in px.
     *
     * @param x horizontal shift.
     * @param y vertical shift.
     */
    data class BurnShift(
        val x: Double,
        val y: Double,
    )

    /**
     * Adds caller motion to the shared shift through the CTM unit
     * (prototype line 2707: `oledShift + dx * unit`).
     *
     * Non-finite motion falls back to 0; non-positive or non-finite scale
     * falls back to 1; zero or non-finite CTM `a` falls back to unit 1.
     *
     * @param baseX shared shift X in px.
     * @param baseY shared shift Y in px.
     * @param dx caller motion X in user units.
     * @param dy caller motion Y in user units.
     * @param ctmA CTM horizontal scale, or null when unknown.
     * @return compensated translation in px.
     */
    fun compensate(baseX: Double, baseY: Double, dx: Double, dy: Double, ctmA: Double?): BurnShift {
        val safeDx = if (dx.isFinite()) dx else 0.0
        val safeDy = if (dy.isFinite()) dy else 0.0
        val unit = if (ctmA != null && ctmA.isFinite() && ctmA != 0.0) 1.0 / ctmA else 1.0
        return BurnShift(
            x = baseX + safeDx * unit,
            y = baseY + safeDy * unit,
        )
    }
}
