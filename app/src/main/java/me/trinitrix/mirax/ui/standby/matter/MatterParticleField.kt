package me.trinitrix.mirax.ui.standby.matter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure deterministic per-particle data for the production Matter field,
 * ported from the v41 prototype (`design/big-standby-prototype.html`
 * `drawParticles` lines 2742-2767 plus `cParticleSampleProject` frozen branch
 * lines 2506-2518, seeded particles lines 2436-2438).
 *
 * Covers the real field only: LCG seed 74321 with 1800 particles, free-cloud
 * projection, radius and opacity formulas, and the approved frozen lattice.
 * Non-frozen states delegate projection to the tested [MatterParticleAssembly];
 * frozen output never touches the assembly so switching cannot corrupt it.
 * No Android, wall clock, settings, WFD, files, or extra dependencies.
 * The caller owns lifecycle: construct once per visible session for a stable
 * restart, then drive monotonically increasing [elapsedMillis] while visible.
 *
 * @param assembly assembly used for every non-frozen projection.
 */
class MatterParticleField(
    private val assembly: MatterParticleAssembly = MatterParticleAssembly(PARTICLE_COUNT),
) {

    /**
     * Deterministic seed attributes for one particle.
     *
     * @param angle base angle in radians.
     * @param tube base tube phase in radians.
     * @param radius tube radius factor.
     * @param size base dot size.
     * @param alpha base opacity.
     */
    data class Seed(
        val angle: Double,
        val tube: Double,
        val radius: Double,
        val size: Double,
        val alpha: Double,
    )

    /**
     * One rendered particle sample in pixels.
     *
     * @param x horizontal pixel coordinate.
     * @param y vertical pixel coordinate.
     * @param radiusPx dot radius in pixels.
     * @param alpha opacity multiplier.
     * @param depth free-cloud depth used for radius and opacity.
     */
    data class Sample(
        val x: Double,
        val y: Double,
        val radiusPx: Double,
        val alpha: Double,
        val depth: Double,
    )

    /** Particle count, preserved exactly from the prototype. */
    val count: Int
        get() = seeds.size

    private val seeds: List<Seed> = buildSeeds()

    /**
     * Returns the deterministic seed for [index].
     *
     * @param index particle index in `0 until count`.
     * @return seed attributes.
     */
    fun seedAt(index: Int): Seed {
        require(index in seeds.indices) { "index out of range" }
        return seeds[index]
    }

    /**
     * Applies the approved broadcast transition.
     *
     * @param broadcasting real broadcast flag from the caller.
     * @param wifiPaused real Wi-Fi paused flag from the caller.
     * @param elapsedMillis active elapsed time in milliseconds (prototype `particleTime`).
     * @return assembly progress after applying the switch.
     */
    fun setBroadcast(broadcasting: Boolean, wifiPaused: Boolean, elapsedMillis: Double): Double {
        return assembly.setBroadcast(broadcasting && !wifiPaused, elapsedMillis * MILLIS_TO_SECONDS)
    }

    /**
     * Assembly progress at the given clock.
     *
     * @param elapsedMillis active elapsed time in milliseconds.
     * @return blended assembly fraction.
     */
    fun progress(elapsedMillis: Double): Double {
        return assembly.progress(elapsedMillis * MILLIS_TO_SECONDS)
    }

    /**
     * Samples one particle for a Canvas consumer.
     *
     * @param index particle index in `0 until count`.
     * @param width surface width in pixels.
     * @param height surface height in pixels.
     * @param elapsedMillis active elapsed time in milliseconds.
     * @param frozen true only when the caller already resolved the real FROZEN phase.
     * @return pixel position with radius, opacity, and depth.
     */
    fun sampleAt(index: Int, width: Double, height: Double, elapsedMillis: Double, frozen: Boolean): Sample {
        require(index in seeds.indices) { "index out of range" }
        val free = freeSample(index, width, height, elapsedMillis)
        if (frozen) {
            if (index % 5 == 0) {
                return free
            }
            return frozenSample(free, index, width, height, elapsedMillis)
        }
        val projected = assembly.project(
            index,
            free.x,
            free.y,
            MatterParticleAssembly.Frame(width, height, elapsedMillis * MILLIS_TO_SECONDS),
        )
        return Sample(projected.x, projected.y, free.radiusPx, free.alpha, free.depth)
    }

    private fun freeSample(index: Int, width: Double, height: Double, elapsedMillis: Double): Sample {
        val seed = seeds[index]
        val scale = max(SCALE_MIN, min(width * SCALE_WIDTH, height * SCALE_HEIGHT))
        val rotation = elapsedMillis * ROTATION_RATE
        val driftX = sin(elapsedMillis * DRIFT_X_RATE) * DRIFT_X_AMPLITUDE
        val driftY = cos(elapsedMillis * DRIFT_Y_RATE) * DRIFT_Y_AMPLITUDE
        val angle = seed.angle + rotation
        val tube = seed.tube + elapsedMillis * TUBE_RATE
        val radius = 1.0 + cos(tube) * seed.radius
        val x = cos(angle) * radius
        val y = sin(angle) * radius * Y_SQUASH + sin(tube) * seed.radius * TUBE_LIFT
        val depth = sin(angle) * DEPTH_ANGLE + cos(tube) * DEPTH_TUBE
        val perspective = 1.0 + depth * PERSPECTIVE
        val screenX = width * CENTER_X + driftX + (x * SHEAR_X + y * SHEAR_Y) * scale * perspective
        val screenY = height * CENTER_Y + driftY + (y * STRETCH_Y - x * STRETCH_X) * scale * perspective
        val radiusPx = max(RADIUS_MIN, seed.size * SIZE_GAIN * (1.0 + depth * DEPTH_SIZE))
        val alpha = seed.alpha * (ALPHA_BASE + depth * DEPTH_ALPHA)
        return Sample(screenX, screenY, radiusPx, alpha, depth)
    }

    private fun frozenSample(free: Sample, index: Int, width: Double, height: Double, elapsedMillis: Double): Sample {
        val scale = max(SCALE_MIN, min(width * SCALE_WIDTH, height * SCALE_HEIGHT))
        val centerX = width * CENTER_X
        val centerY = height * CENTER_Y
        val time = elapsedMillis * MILLIS_TO_SECONDS
        val row = index / LATTICE_COLUMNS - LATTICE_ROW_OFFSET
        val col = index % LATTICE_COLUMNS - LATTICE_COL_OFFSET
        val step = scale * LATTICE_STEP
        // Keep JS remainder semantics for negative odd rows (for example -19 % 2 == -1).
        val hexX = (col + (row % 2) * 0.5) * step * HEX_X
        val hexY = row * step * HEX_Y * HEX_Y_SQUASH
        val seam = hexX * SEAM_X - hexY * SEAM_X
        val shear = if (seam > 0.0) scale * SHEAR_AMPLITUDE else -scale * SHEAR_AMPLITUDE
        val shudder = sin(time * SHUDDER_RATE + index.toDouble()) * SHUDDER_AMPLITUDE
        val x = centerX + hexX + (if (seam > 0.0) shear else 0.0) + shudder
        val y = centerY + hexY + (if (seam > 0.0) shear * SHEAR_Y_RATIO else 0.0) + shudder * SHUDDER_Y_RATIO
        return Sample(x, y, free.radiusPx, free.alpha, free.depth)
    }

    private companion object {
        const val PARTICLE_COUNT = 1800
        const val LCG_SEED = 74321L
        const val LCG_MULTIPLIER = 16807L
        const val LCG_MODULUS = 2147483647L
        const val LCG_DENOMINATOR = 2147483646.0
        const val MILLIS_TO_SECONDS = 0.001
        const val SCALE_MIN = 260.0
        const val SCALE_WIDTH = 0.47
        const val SCALE_HEIGHT = 0.64
        const val ROTATION_RATE = 0.00022
        const val DRIFT_X_RATE = 0.00055
        const val DRIFT_X_AMPLITUDE = 26.0
        const val DRIFT_Y_RATE = 0.00043
        const val DRIFT_Y_AMPLITUDE = 16.0
        const val TUBE_RATE = 0.00018
        const val Y_SQUASH = 0.45
        const val TUBE_LIFT = 0.68
        const val DEPTH_ANGLE = 0.45
        const val DEPTH_TUBE = 0.25
        const val PERSPECTIVE = 0.12
        const val CENTER_X = 0.50
        const val CENTER_Y = 0.51
        const val SHEAR_X = 0.93
        const val SHEAR_Y = 0.27
        const val STRETCH_Y = 0.84
        const val STRETCH_X = 0.27
        const val SIZE_GAIN = 1.12
        const val RADIUS_MIN = 0.3
        const val DEPTH_SIZE = 0.3
        const val ALPHA_BASE = 0.82
        const val DEPTH_ALPHA = 0.22
        const val LATTICE_COLUMNS = 36
        const val LATTICE_ROW_OFFSET = 20
        const val LATTICE_COL_OFFSET = 18
        const val LATTICE_STEP = 0.055
        const val HEX_X = 1.732
        const val HEX_Y = 1.5
        const val HEX_Y_SQUASH = 0.62
        const val SEAM_X = 0.707
        const val SHEAR_AMPLITUDE = 0.03
        const val SHEAR_Y_RATIO = 0.5
        const val SHUDDER_RATE = 22.0
        const val SHUDDER_AMPLITUDE = 0.8
        const val SHUDDER_Y_RATIO = 0.5

        fun buildSeeds(): List<Seed> {
            var seed = LCG_SEED
            fun next(): Double {
                seed = (seed * LCG_MULTIPLIER) % LCG_MODULUS
                return (seed - 1).toDouble() / LCG_DENOMINATOR
            }
            return List(PARTICLE_COUNT) {
                Seed(
                    angle = next() * PI * 2.0,
                    tube = next() * PI * 2.0,
                    radius = 0.21 + next() * 0.17,
                    size = 0.45 + next() * 1.0,
                    alpha = 0.25 + next() * 0.65,
                )
            }
        }
    }
}
