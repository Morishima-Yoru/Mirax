package me.trinitrix.mirax.ui.standby.runtime

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure gyroscope tilt math for issue 07.
 *
 * Faithful port of `design/big-standby-c-gyroscope.js` lines 152-202,
 * Android writes stripped:
 *
 * - First sample or gap `elapsed > 250` emits `(0, 0)` (lines 184-187).
 * - `elapsed <= 0` samples are dropped (line 161).
 * - Relative motion uses the third column of `R_previous^T * R_current`
 *   (line 189-191), then `atan2` rate normalized by elapsed seconds over
 *   90 deg/sec, clamped to `[-1, 1]` (lines 192-195).
 * - Screen transform maps 90/180/270 to `(y, -x)`, `(-x, -y)`, `(-y, x)`
 *   (lines 196-202).
 * - Stillness `150ms` emits `(0, 0)` without snapping state (lines 180-183).
 * - No-sample `3000ms` after reset reports unavailable (lines 85-88).
 *
 * Smoothing and pixel scaling stay in [StandbyParallaxMath]; this file never
 * duplicates them. Rotation-vector to W3C equivalence is NOT proven here:
 * display remap needs device proof, so callers must supply a verified
 * display-degrees provider before enabling landscape parallax.
 */
object StandbyTiltMapper {

    /** Stillness timeout in millis (gyroscope line 180). */
    const val IDLE_TIMEOUT_MILLIS = 150L

    /** Gap timeout in millis (gyroscope line 184). */
    const val GAP_TIMEOUT_MILLIS = 250L

    /** No-sample timeout in millis (gyroscope lines 85-88). */
    const val SAMPLE_TIMEOUT_MILLIS = 3000L

    /** Rate denominator in degrees per second (gyroscope line 193). */
    const val RATE_DEGREES_PER_SECOND = 90.0

    /** Near-zero epsilon (gyroscope line 192). */
    const val EPSILON = 1e-12

    /**
     * Sensor status owned by the Android source.
     */
    enum class TiltStatus {
        /** Stopped; nothing registered. */
        IDLE,
        /** Started but no valid sample yet. */
        WAITING,
        /** Receiving valid samples. */
        ACTIVE,
        /** No rotation-vector sensor on this device. */
        MISSING,
        /** Sensor exists but register returned false. */
        REGISTER_FAILED,
        /** No valid sample for 3000ms after reset. */
        UNAVAILABLE,
    }

    /**
     * Controllable sensor registry seam (no Android types, JVM-testable).
     */
    interface TiltRegistry {
        /** Returns true when a rotation-vector sensor exists. */
        fun hasSensor(): Boolean
        /**
         * Registers a callback owned by this registration.
         *
         * @param onSample row-major rotation matrix and monotonic timestamp in millis.
         * @return false on platform refusal.
         */
        fun register(onSample: (matrix: DoubleArray, timestampMillis: Long) -> Unit): Boolean
        /** Unregisters; idempotent. */
        fun unregister()
    }

    /**
     * Controllable idle/sample scheduler seam (no Android types).
     */
    interface TiltScheduler {
        /** Schedules stillness zero with owning generation. */
        fun scheduleIdle(delayMillis: Long, generation: Long, action: () -> Unit)
        /** Cancels pending stillness zero for the given generation. */
        fun cancelIdle(generation: Long = -1)
        /** Schedules no-sample timeout with owning generation. */
        fun scheduleSampleTimeout(delayMillis: Long, generation: Long, action: () -> Unit)
        /** Cancels pending no-sample timeout for the given generation. */
        fun cancelSampleTimeout(generation: Long = -1)
    }

    /**
     * Builds a row-major 3x3 rotation matrix from a unit quaternion.
     *
     * Standard quaternion-to-matrix form; hand-checked fixed vectors live in
     * `StandbyTiltSourceTest` (identity and 90-degree Z).
     *
     * @param qx x component.
     * @param qy y component.
     * @param qz z component.
     * @param qw scalar component.
     * @return row-major 9 doubles, or null for non-finite or zero norm.
     */
    fun rotationMatrixFromQuaternion(qx: Double, qy: Double, qz: Double, qw: Double): DoubleArray? {
        if (!qx.isFinite() || !qy.isFinite() || !qz.isFinite() || !qw.isFinite()) {
            return null
        }
        val norm = qx * qx + qy * qy + qz * qz + qw * qw
        if (!norm.isFinite() || norm < EPSILON) {
            return null
        }
        val inv = 1.0 / kotlin.math.sqrt(norm)
        val x = qx * inv
        val y = qy * inv
        val z = qz * inv
        val w = qw * inv
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val xw = x * w
        val yw = y * w
        val zw = z * w
        return doubleArrayOf(
            1.0 - 2.0 * (yy + zz), 2.0 * (xy - zw), 2.0 * (xz + yw),
            2.0 * (xy + zw), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - xw),
            2.0 * (xz - yw), 2.0 * (yz + xw), 1.0 - 2.0 * (xx + yy),
        )
    }

    /**
     * Splits row-major 3x3 into three columns.
     *
     * Prototype stores R columns (`gyroscope.js` lines 169-177: row0 is
     * R column 0, row1 is R column 1, normal is R column 2) and dots
     * previous columns against the current normal (lines 189-191).
     * Android `getRotationMatrixFromVector` returns row-major rows, so
     * this converts rows to columns to match the prototype layout.
     *
     * @param matrix9 row-major 9 doubles.
     * @return three columns, or null for bad size or non-finite.
     */
    fun columnsFromRowMajor(matrix9: DoubleArray): Array<DoubleArray>? {
        if (matrix9.size != 9) {
            return null
        }
        for (value in matrix9) {
            if (!value.isFinite()) {
                return null
            }
        }
        // Row-major: [R00,R01,R02, R10,R11,R12, R20,R21,R22].
        // Prototype stores R columns, so extract columns here.
        return arrayOf(
            doubleArrayOf(matrix9[0], matrix9[3], matrix9[6]),
            doubleArrayOf(matrix9[1], matrix9[4], matrix9[7]),
            doubleArrayOf(matrix9[2], matrix9[5], matrix9[8]),
        )
    }

    /**
     * Normalizes a relative angle to [-1, 1] by elapsed time.
     *
     * @param angleRadians relative angle.
     * @param elapsedMillis positive elapsed millis.
     * @return clamped rate, or 0 for bad input.
     */
    fun normalizeRate(angleRadians: Double, elapsedMillis: Long): Double {
        if (!angleRadians.isFinite() || elapsedMillis <= 0L) {
            return 0.0
        }
        if (kotlin.math.abs(angleRadians) < EPSILON) {
            return 0.0
        }
        val degrees = angleRadians / (PI / 180.0)
        val seconds = elapsedMillis.toDouble() / 1000.0
        if (!seconds.isFinite() || seconds <= 0.0) {
            return 0.0
        }
        val rate = degrees / seconds / RATE_DEGREES_PER_SECOND
        if (!rate.isFinite()) {
            return 0.0
        }
        return rate.coerceIn(-1.0, 1.0)
    }

    /**
     * Computes relative tilt from stored previous columns and current columns.
     *
     * Prototype stores R columns (`gyroscope.js` lines 169-177) and dots
     * previous columns against the current normal (lines 189-191). Assumes
     * caller already handled first-sample and gap cases; this pure step
     * needs `0 < elapsed <= 250`.
     *
     * @param previousCols previous three columns.
     * @param currentCols current three columns.
     * @param elapsedMillis elapsed millis.
     * @return `[x, y]` normalized, never null; invalid yields zeros, no invented motion.
     */
    fun computeTilt(
        previousCols: Array<DoubleArray>,
        currentCols: Array<DoubleArray>,
        elapsedMillis: Long,
    ): DoubleArray {
        if (previousCols.size != 3 || currentCols.size != 3) {
            return doubleArrayOf(0.0, 0.0)
        }
        for (column in previousCols + currentCols) {
            if (column.size != 3) {
                return doubleArrayOf(0.0, 0.0)
            }
            for (value in column) {
                if (!value.isFinite()) {
                    return doubleArrayOf(0.0, 0.0)
                }
            }
        }
        if (elapsedMillis <= 0L || elapsedMillis > GAP_TIMEOUT_MILLIS) {
            return doubleArrayOf(0.0, 0.0)
        }
        val normal = currentCols[2]
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (index in 0..2) {
            nx += previousCols[0][index] * normal[index]
            ny += previousCols[1][index] * normal[index]
            nz += previousCols[2][index] * normal[index]
        }
        if (!nx.isFinite() || !ny.isFinite() || !nz.isFinite()) {
            return doubleArrayOf(0.0, 0.0)
        }
        val x = normalizeRate(atan2(nx, nz), elapsedMillis)
        val y = normalizeRate(-atan2(ny, nz), elapsedMillis)
        return doubleArrayOf(
            StandbyParallaxMath.normalize(x),
            StandbyParallaxMath.normalize(y),
        )
    }

    /**
     * Applies the prototype screen-orientation transform.
     *
     * @param x normalized X.
     * @param y normalized Y.
     * @param displayDegrees 0/90/180/270 display rotation in degrees.
     * @return transformed `[x, y]`.
     */
    fun applyScreenRotation(x: Double, y: Double, displayDegrees: Int): DoubleArray {
        val safeX = StandbyParallaxMath.normalize(x)
        val safeY = StandbyParallaxMath.normalize(y)
        val normalized = ((displayDegrees % 360) + 360) % 360
        return when (normalized) {
            90 -> doubleArrayOf(safeY, -safeX)
            180 -> doubleArrayOf(-safeX, -safeY)
            270 -> doubleArrayOf(-safeY, safeX)
            else -> doubleArrayOf(safeX, safeY)
        }
    }

    /**
     * Serialized tilt state machine (no threads owned here).
     *
     * The Android source serializes calls with the delegate monitor;
     * JVM tests drive it directly on one thread.
     */
    class TiltProcessor {
        private var previousCols: Array<DoubleArray>? = null
        private var previousTimeMillis: Long? = null
        private var lastSampleTimeMillis: Long? = null
        private var waitingSinceMillis: Long? = null

        /**
         * Marks waiting start for the 3000ms no-sample timer.
         *
         * @param nowMillis current clock millis.
         */
        fun onWaitingStarted(nowMillis: Long) {
            waitingSinceMillis = nowMillis
        }

        /**
         * Clears history without touching waiting state.
         */
        fun onResetHistory() {
            previousCols = null
            previousTimeMillis = null
            lastSampleTimeMillis = null
        }

        /**
         * Full reset: clears history and restarts waiting timer.
         *
         * @param nowMillis current clock millis.
         */
        fun onReset(nowMillis: Long) {
            onResetHistory()
            waitingSinceMillis = nowMillis
        }

        /**
         * Handles one rotation matrix sample.
         *
         * First sample or gap `> 250` stores and returns zeros; `elapsed <= 0`
         * or invalid returns null (dropped, like prototype line 161/156).
         *
         * @param currentCols current three columns.
         * @param nowMillis sample clock millis.
         * @param displayDegrees display rotation in degrees.
         * @return `[x, y]`, zeros for first/gap, or null when dropped.
         */
        fun onSample(
            currentCols: Array<DoubleArray>,
            nowMillis: Long,
            displayDegrees: Int,
        ): DoubleArray? {
            if (currentCols.size != 3) {
                return null
            }
            for (column in currentCols) {
                if (column.size != 3) {
                    return null
                }
                for (value in column) {
                    if (!value.isFinite()) {
                        return null
                    }
                }
            }
            val previous = previousCols
            val previousTime = previousTimeMillis
            if (previous == null || previousTime == null) {
                previousCols = arrayOf(currentCols[0].copyOf(), currentCols[1].copyOf(), currentCols[2].copyOf())
                previousTimeMillis = nowMillis
                lastSampleTimeMillis = nowMillis
                waitingSinceMillis = null
                return doubleArrayOf(0.0, 0.0)
            }
            val elapsed = nowMillis - previousTime
            if (elapsed <= 0L) {
                return null
            }
            previousCols = arrayOf(currentCols[0].copyOf(), currentCols[1].copyOf(), currentCols[2].copyOf())
            previousTimeMillis = nowMillis
            lastSampleTimeMillis = nowMillis
            waitingSinceMillis = null
            if (elapsed > GAP_TIMEOUT_MILLIS) {
                return doubleArrayOf(0.0, 0.0)
            }
            val tilt = computeTilt(previous, currentCols, elapsed)
            return applyScreenRotation(tilt[0], tilt[1], displayDegrees)
        }

        /**
         * Returns true when stillness exceeded 150ms.
         *
         * @param nowMillis current clock millis.
         * @return true when idle zero should be emitted.
         */
        fun shouldIdleZero(nowMillis: Long): Boolean {
            val last = lastSampleTimeMillis ?: return false
            return nowMillis - last >= IDLE_TIMEOUT_MILLIS
        }

        /**
         * Returns true when waiting exceeded 3000ms with no valid sample.
         *
         * @param nowMillis current clock millis.
         * @return true when status should become unavailable.
         */
        fun isSampleTimedOut(nowMillis: Long): Boolean {
            val since = waitingSinceMillis ?: return false
            return nowMillis - since >= SAMPLE_TIMEOUT_MILLIS
        }

        /**
         * Idle zero keeps history (prototype never snaps via onReset).
         */
        fun onIdleEmitted() {
            // Intentionally retains previous matrix for the next relative sample.
        }
    }

    /**
     * Builds a rotation about Y for hand-computed tests, not a Z-axis yaw.
     *
     * @param degrees rotation about Y in degrees.
     * @return row-major 3x3.
     */
    fun yawMatrixDegrees(degrees: Double): DoubleArray {
        val radians = degrees * PI / 180.0
        val cosValue = cos(radians)
        val sinValue = sin(radians)
        return doubleArrayOf(
            cosValue, 0.0, sinValue,
            0.0, 1.0, 0.0,
            -sinValue, 0.0, cosValue,
        )
    }
}
