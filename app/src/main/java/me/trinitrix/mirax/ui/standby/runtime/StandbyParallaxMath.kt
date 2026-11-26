package me.trinitrix.mirax.ui.standby.runtime

import kotlin.math.exp

/**
 * Pure port of the web parallax math (`design/big-standby-c-parallax.js`
 * lines 4, 44-63), DOM/SVG writes stripped.
 *
 * Normalizes raw inputs to [-1, 1], advances two exponential smoothing
 * histories (fast canvas layer, slow information layer), then scales by the
 * short viewport edge. Invalid or non-positive viewports return zero.
 */
object StandbyParallaxMath {

    /** Fast-layer factor: `shortEdge * 0.0189` (parallax line 58-59). */
    const val CANVAS_FACTOR = 0.0189

    /** Slow-layer factor: `shortEdge * 0.01` (parallax line 62-63). */
    const val INFO_FACTOR = 0.01

    /** Fast-layer rate 4 (parallax line 45 `expm1(-4 * delta)`). */
    const val FAST_RATE = 4.0

    /** Slow-layer rate 2 (parallax line 46 `expm1(-2 * delta)`). */
    const val SLOW_RATE = 2.0

    /** Delta clamp upper bound in seconds (parallax line 44). */
    const val MAX_DELTA_SECONDS = 0.1

    /**
     * Smoothed parallax state.
     *
     * @param fastX fast-layer X in [-1, 1].
     * @param fastY fast-layer Y in [-1, 1].
     * @param slowX slow-layer X in [-1, 1].
     * @param slowY slow-layer Y in [-1, 1].
     */
    data class State(
        val fastX: Double = 0.0,
        val fastY: Double = 0.0,
        val slowX: Double = 0.0,
        val slowY: Double = 0.0,
    )

    /**
     * Pixel offsets for one frame.
     *
     * @param canvasDx fast-layer X pixels.
     * @param canvasDy fast-layer Y pixels.
     * @param infoDx slow-layer X pixels.
     * @param infoDy slow-layer Y pixels.
     */
    data class Offsets(
        val canvasDx: Double,
        val canvasDy: Double,
        val infoDx: Double,
        val infoDy: Double,
    )

    /**
     * Clamps a raw sample to [-1, 1]; non-finite becomes 0.
     *
     * @param value raw tilt or pointer sample.
     * @return clamped value.
     */
    fun normalize(value: Double): Double =
        if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0

    /**
     * Clamps frame delta to [0, 0.1]; non-finite becomes 0.
     *
     * @param deltaSeconds raw frame delta.
     * @return clamped delta.
     */
    fun clampDelta(deltaSeconds: Double): Double =
        if (deltaSeconds.isFinite()) deltaSeconds.coerceIn(0.0, MAX_DELTA_SECONDS) else 0.0

    /**
     * Advances smoothed state toward the target.
     *
     * @param state previous smoothed state.
     * @param targetX normalized X target.
     * @param targetY normalized Y target.
     * @param deltaSeconds raw frame delta.
     * @return next smoothed state.
     */
    fun advance(state: State, targetX: Double, targetY: Double, deltaSeconds: Double): State {
        val delta = clampDelta(deltaSeconds)
        // Prototype: blend = -expm1(-rate * delta).
        val fastBlend = -exp(-FAST_RATE * delta) + 1.0
        val slowBlend = -exp(-SLOW_RATE * delta) + 1.0
        return State(
            fastX = state.fastX + (targetX - state.fastX) * fastBlend,
            fastY = state.fastY + (targetY - state.fastY) * fastBlend,
            slowX = state.slowX + (targetX - state.slowX) * slowBlend,
            slowY = state.slowY + (targetY - state.slowY) * slowBlend,
        )
    }

    /**
     * Converts smoothed state to pixel offsets.
     *
     * Zero-size, non-finite, or non-positive viewports return zeros instead
     * of NaN (prototype lines 40-43 reset on the same condition).
     *
     * @param state smoothed state.
     * @param width viewport width in pixels.
     * @param height viewport height in pixels.
     * @return pixel offsets, or zeros for degenerate viewports.
     */
    fun offsets(state: State, width: Double, height: Double): Offsets {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) {
            return Offsets(0.0, 0.0, 0.0, 0.0)
        }
        val shortEdge = minOf(width, height)
        return Offsets(
            canvasDx = state.fastX * shortEdge * CANVAS_FACTOR,
            canvasDy = state.fastY * shortEdge * CANVAS_FACTOR,
            infoDx = state.slowX * shortEdge * INFO_FACTOR,
            infoDy = state.slowY * shortEdge * INFO_FACTOR,
        )
    }
}
