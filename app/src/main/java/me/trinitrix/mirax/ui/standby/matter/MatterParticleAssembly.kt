package me.trinitrix.mirax.ui.standby.matter

import kotlin.math.PI
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure double-precision port of the approved web prototype
 * `MiraxCParticleAssembly` (`design/big-standby-c-particle-assembly.js`).
 *
 * Covers only the assembly math: contour targets, deterministic per-index
 * parameters, 6s gather / 8s release easing with mid-flight reversal, and the
 * same-frame plus same-size caching semantics. Rendering, color, free-cloud
 * motion, FROZEN lattice, and frame pacing stay out of scope.
 *
 * @param count particle count, preserved exactly (prototype uses 1800).
 */
class MatterParticleAssembly(count: Int) {

    /**
     * Frame input for [project], mirroring the JS `{ width, height, time }`.
     *
     * @param width viewport width in pixels.
     * @param height viewport height in pixels.
     * @param time monotonic clock in seconds (prototype passes `particleTime * 0.001`).
     */
    data class Frame(
        val width: Double,
        val height: Double,
        val time: Double,
    )

    /**
     * Projected point in pixels.
     *
     * @param x horizontal pixel coordinate.
     * @param y vertical pixel coordinate.
     */
    data class Point(
        val x: Double,
        val y: Double,
    )

    private data class Edge(
        val x: Double,
        val y: Double,
        val dx: Double,
        val dy: Double,
        val length: Double,
        val start: Double,
    )

    private data class Contour(
        val edges: List<Edge>,
        val perimeter: Double,
        val cx: Double,
        val cy: Double,
    )

    private data class Target(
        val contour: Contour,
        val start: Double,
        val inset: Double,
        val speed: Double,
        val phase: Double,
        val lag: Double,
        val bend: Double,
    )

    private val targets: List<Target> = buildTargets(count)
    private var targetFlag: Int = 0
    private var fromFraction: Double = 0.0
    private var startedAt: Double = 0.0
    private var duration: Double = 0.0
    private var layoutWidth: Double = -1.0
    private var layoutHeight: Double = -1.0
    private var layoutSize: Double = 0.0
    private var centerX: Double = 0.0
    private var centerY: Double = 0.0
    private var drift: Double = 0.0
    private var frameTime: Double = Double.NaN
    private var fraction: Double = 0.0

    /**
     * Starts gathering (6s) or releasing (8s) from the current progress.
     *
     * @param active true gathers toward the mark, false releases toward the cloud.
     * @param time current clock in seconds; falls back to the previous start when non-finite.
     * @return current assembly progress after applying the switch.
     */
    fun setBroadcast(active: Boolean, time: Double): Double {
        val next = if (active) 1 else 0
        if (next == targetFlag) {
            return progress(time)
        }
        val now = if (time.isFinite()) time else startedAt
        fromFraction = progress(now)
        targetFlag = next
        startedAt = now
        duration = kotlin.math.abs(next - fromFraction) * if (active) ASSEMBLE_SECONDS else RELEASE_SECONDS
        frameTime = Double.NaN
        return fromFraction
    }

    /**
     * Assembly progress at the given clock.
     *
     * @param time clock in seconds; falls back to the previous start when non-finite.
     * @return blended fraction between [fromFraction] and the current target.
     */
    fun progress(time: Double): Double {
        if (duration == 0.0) {
            return targetFlag.toDouble()
        }
        val now = if (time.isFinite()) time else startedAt
        val elapsed = clamp((now - startedAt) / duration)
        return if (targetFlag == 1) {
            fromFraction + (targetFlag - fromFraction) * exponentialOut(elapsed)
        } else {
            fromFraction + (targetFlag - fromFraction) * smoother(elapsed)
        }
    }

    /**
     * Projects one particle, preserving the prototype cache semantics: progress
     * is recomputed only when frame time changes, and layout only when size changes.
     *
     * @param index particle index.
     * @param freeX free-cloud x in pixels.
     * @param freeY free-cloud y in pixels.
     * @param frame viewport size plus clock.
     * @return assembled point, or the free-cloud input when unassembled, untargeted, or every fifth particle.
     */
    fun project(index: Int, freeX: Double, freeY: Double, frame: Frame): Point {
        val time = if (frame.time.isFinite()) frame.time else startedAt
        if (time != frameTime) {
            frameTime = time
            fraction = progress(time)
        }
        val current = fraction
        val target = targets.getOrNull(index)
        if (current == 0.0 || target == null || index % 5 == 0) {
            return Point(freeX, freeY)
        }
        val width = kotlin.math.max(0.0, if (frame.width.isFinite()) frame.width else 0.0)
        val height = kotlin.math.max(0.0, if (frame.height.isFinite()) frame.height else 0.0)
        if (width != layoutWidth || height != layoutHeight) {
            layout(width, height)
        }
        val contour = target.contour
        val distance = (((target.start + time * target.speed) % 1.0 + 1.0) % 1.0) * contour.perimeter
        val edge = contour.edges.firstOrNull { distance < it.start + it.length } ?: contour.edges[0]
        val position = (distance - edge.start) / edge.length
        val pathX = edge.x + edge.dx * position
        val pathY = edge.y + edge.dy * position
        val swayX = sin(time * 0.93 + target.phase) * drift
        val swayY = sin(time * 0.75 + target.phase * 1.7) * drift * 0.7
        val markX = centerX + (pathX + (contour.cx - pathX) * target.inset - MARK_CENTER_X) / MARK_SPAN * layoutSize + swayX
        val markY = centerY + (pathY + (contour.cy - pathY) * target.inset - MARK_CENTER_Y) / MARK_SPAN * layoutSize + swayY
        val dx = markX - freeX
        val dy = markY - freeY
        val local = current + target.lag * current * (1.0 - current)
        val arc = 4.0 * local * (1.0 - local) * target.bend
        return Point(freeX + dx * local - dy * arc, freeY + dy * local + dx * arc)
    }

    private fun layout(width: Double, height: Double) {
        layoutWidth = width
        layoutHeight = height
        layoutSize = minOf(width * 0.82, height * 0.74, kotlin.math.max(0.0, height - 160.0))
        centerX = width * 0.5
        centerY = height * 0.5
        drift = kotlin.math.min(3.0, layoutSize * 0.012)
    }

    private companion object {
        const val ASSEMBLE_SECONDS = 6.0
        const val RELEASE_SECONDS = 8.0
        const val MARK_CENTER_X = 54.05
        const val MARK_CENTER_Y = 53.2
        const val MARK_SPAN = 60.4

        // Source: ic_mirax_mark.xml; the diamond stays hollow, not a fifth filled arm.
        val MARK_CONTOURS: List<List<DoubleArray>> = listOf(
            listOf(doubleArrayOf(26.1, 23.0), doubleArrayOf(31.9, 23.0), doubleArrayOf(51.8, 42.9), doubleArrayOf(43.9, 50.8), doubleArrayOf(26.1, 32.8)),
            listOf(doubleArrayOf(76.0, 23.1), doubleArrayOf(81.9, 23.1), doubleArrayOf(81.9, 32.8), doubleArrayOf(64.2, 50.5), doubleArrayOf(56.3, 43.0)),
            listOf(doubleArrayOf(26.1, 73.6), doubleArrayOf(44.0, 55.7), doubleArrayOf(51.7, 63.5), doubleArrayOf(32.0, 83.3), doubleArrayOf(26.1, 83.3)),
            listOf(doubleArrayOf(64.0, 55.7), doubleArrayOf(82.0, 73.6), doubleArrayOf(82.0, 83.4), doubleArrayOf(76.1, 83.4), doubleArrayOf(56.2, 63.4)),
            listOf(doubleArrayOf(54.0, 47.5), doubleArrayOf(60.2, 53.4), doubleArrayOf(54.0, 59.4), doubleArrayOf(47.8, 53.4)),
        )

        fun clamp(value: Double): Double = kotlin.math.max(0.0, kotlin.math.min(1.0, value))

        fun smoother(value: Double): Double {
            val clamped = clamp(value)
            return clamp(clamped * clamped * clamped * (clamped * (clamped * 6.0 - 15.0) + 10.0))
        }

        // Normalize the exponential decay to complete exactly at the finite endpoint.
        fun exponentialOut(value: Double): Double = -expm1(-4.0 * clamp(value)) / -expm1(-4.0)

        fun hash(index: Int): Double {
            val value = kotlin.math.sin(index * 127.1 + 311.7) * 43758.5453
            return value - floor(value)
        }

        fun buildContours(): List<Contour> {
            return MARK_CONTOURS.map { vertices ->
                var sumX = 0.0
                var sumY = 0.0
                for (vertex in vertices) {
                    sumX += vertex[0]
                    sumY += vertex[1]
                }
                var perimeter = 0.0
                val edges = vertices.mapIndexed { edgeIndex, point ->
                    val next = vertices[(edgeIndex + 1) % vertices.size]
                    val dx = next[0] - point[0]
                    val dy = next[1] - point[1]
                    val length = hypot(dx, dy)
                    val edge = Edge(point[0], point[1], dx, dy, length, perimeter)
                    perimeter += length
                    edge
                }
                Contour(edges, perimeter, sumX / vertices.size, sumY / vertices.size)
            }
        }

        fun buildTargets(count: Int): List<Target> {
            if (count <= 0) {
                return emptyList()
            }
            val contours = buildContours()
            var arm = 0
            return List(count) { index ->
                val contourIndex = if (index % 10 == 9) {
                    4
                } else {
                    val selected = arm % 4
                    arm += 1
                    selected
                }
                val inset = if (contourIndex == 4) {
                    -0.02 - hash(index + 7) * 0.06
                } else {
                    0.025 + hash(index + 7) * 0.34
                }
                Target(
                    contour = contours[contourIndex],
                    start = (index * 0.618033988749895 + 0.13) % 1.0,
                    inset = inset,
                    speed = (0.031 + hash(index + 79) * 0.018) * if (index % 2 != 0) 1.0 else -1.0,
                    phase = hash(index + 23) * PI * 2.0,
                    lag = (hash(index + 41) - 0.5) * 1.4,
                    bend = (hash(index + 61) - 0.5) * 0.36,
                )
            }
        }
    }
}
