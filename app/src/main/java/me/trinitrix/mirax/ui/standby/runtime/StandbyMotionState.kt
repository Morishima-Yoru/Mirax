package me.trinitrix.mirax.ui.standby.runtime

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Pixel offsets for the Matter particle and information layers. */
internal data class StandbyMotionOffsets(
    val canvasX: Float,
    val canvasY: Float,
    val infoX: Float,
    val infoY: Float,
)

/** Main-thread motion state with generation-tagged sensor delivery. */
internal class StandbyMotionState(
    private val random: () -> Double = { Random.Default.nextDouble() },
) {
    private val generation = AtomicLong(0)
    @Volatile private var active = false
    @Volatile private var acceptingSamples = false
    private var targetX = 0.0
    private var targetY = 0.0
    private var parallax = StandbyParallaxMath.State()
    private var walk = StandbyOledDriftMath.Walk()
    private var oledElapsed = 0.0
    private var oledX = 0.0
    private var oledY = 0.0

    /** Starts a fresh registration and OLED walk. */
    fun start() {
        generation.incrementAndGet()
        targetX = 0.0
        targetY = 0.0
        parallax = StandbyParallaxMath.State()
        walk = StandbyOledDriftMath.pickTarget(random, 0.0, 0.0)
        oledElapsed = 0.0
        oledX = 0.0
        oledY = 0.0
        active = true
        acceptingSamples = true
    }

    /** Invalidates queued samples before the sensor registration is stopped. */
    fun stop() {
        acceptingSamples = false
        active = false
        generation.incrementAndGet()
        targetX = 0.0
        targetY = 0.0
        parallax = StandbyParallaxMath.State()
        walk = StandbyOledDriftMath.Walk()
        oledElapsed = 0.0
        oledX = 0.0
        oledY = 0.0
    }

    /** Invalidates samples queued before a display change. */
    fun beginDisplayChange() {
        acceptingSamples = false
        generation.incrementAndGet()
        resetTilt()
    }

    /** Accepts samples again after the sensor has processed the display change. */
    fun endDisplayChange() {
        generation.incrementAndGet()
        acceptingSamples = active
    }

    /** Returns a registration ticket from the sensor thread, or null when stopped. */
    fun ticket(): Long? = if (active && acceptingSamples) generation.get() else null

    /** Accepts a sample only while its registration is still current. */
    fun acceptTilt(ticket: Long, x: Double, y: Double) {
        if (!active || !acceptingSamples || ticket != generation.get()) return
        targetX = x
        targetY = y
    }

    /** Clears a tilt history only for the current registration. */
    fun acceptReset(ticket: Long) {
        if (!active || !acceptingSamples || ticket != generation.get()) return
        resetTilt()
    }

    /** Clears the tilt target and smoothing history. */
    fun resetTilt() {
        targetX = 0.0
        targetY = 0.0
        parallax = StandbyParallaxMath.State()
    }

    /** Advances the existing prototype math for one visible frame. */
    fun advance(deltaSeconds: Double, width: Int, height: Int): StandbyMotionOffsets {
        if (!active) return StandbyMotionOffsets(0f, 0f, 0f, 0f)
        parallax = StandbyParallaxMath.advance(parallax, targetX, targetY, deltaSeconds)
        val offsets = StandbyParallaxMath.offsets(parallax, width.toDouble(), height.toDouble())
        if (deltaSeconds.isFinite() && deltaSeconds > 0.0) oledElapsed += deltaSeconds
        if (oledElapsed >= OLED_INTERVAL_SECONDS) {
            val (nextWalk, point) = StandbyOledDriftMath.advance(walk, oledElapsed, random)
            walk = nextWalk
            oledX = point.x
            oledY = point.y
            oledElapsed = 0.0
        }
        return StandbyMotionOffsets(
            canvasX = offsets.canvasDx.toFloat(),
            canvasY = offsets.canvasDy.toFloat(),
            infoX = (offsets.infoDx + oledX).toFloat(),
            infoY = (offsets.infoDy + oledY).toFloat(),
        )
    }

    private companion object {
        const val OLED_INTERVAL_SECONDS = 0.1
    }
}
