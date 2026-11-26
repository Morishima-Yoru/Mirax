package me.trinitrix.mirax.ui.standby.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltProcessor
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltRegistry
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltScheduler
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltStatus

/**
 * Controllable tilt-source lifecycle delegate (no Android types).
 *
 * Serializes lifecycle and sample state on this delegate's monitor. Callbacks
 * run synchronously under that monitor and must not wait for another thread
 * that calls the source. Reentrant calls are supported. Registrations own
 * distinct callback identities, and stale samples never produce motion.
 * Smoothing and pixel scaling stay in [StandbyParallaxMath]; this emits normalized
 * `[-1, 1]` targets plus explicit status.
 *
 * Prototype source: `design/big-standby-c-gyroscope.js` lines 152-202
 * (first/gap zeros, `elapsed <= 0` drop, relative `atan2` rate over
 * 90 deg/sec, 90/180/270 screen transform, 150ms idle zero, 3000ms
 * no-sample unavailable).
 */
class StandbyTiltSourceDelegate(
    private val registry: TiltRegistry,
    private val scheduler: TiltScheduler,
    private val processor: TiltProcessor = TiltProcessor(),
    private val displayDegrees: () -> Int = { 0 },
    private val clock: () -> Long = { 0L },
    private val onTilt: (x: Double, y: Double) -> Unit = { _, _ -> },
    private val onStatus: (status: TiltStatus) -> Unit = {},
    private val onResetRequest: () -> Unit = {},
) : StandbyParallaxSource {
    private class Registration(val generation: Long) {
        var ready = false
    }

    private var registration: Registration? = null
    private var generation = 0L
    private var sampleRevision = 0L
    private var status: TiltStatus = TiltStatus.IDLE

    fun currentStatus(): TiltStatus = synchronized(this) { status }

    fun currentGeneration(): Long = synchronized(this) { generation }

    fun isStarted(): Boolean = synchronized(this) { registration != null }

    override fun start() = synchronized(this) {
        if (registration == null) beginRegistrationLocked()
    }

    private fun beginRegistrationLocked() {
        if (!registry.hasSensor()) {
            val expected = generation
            setStatusLocked(TiltStatus.MISSING)
            if (generation == expected && registration == null && status == TiltStatus.MISSING) {
                safeResetEmit(expected)
            }
            return
        }
        val owner = Registration(++generation)
        registration = owner
        val registered = try {
            registry.register { matrix, timestamp -> onRotationMatrix(matrix, timestamp, owner) }
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "register failed", error)
            false
        }
        if (registration !== owner) return
        if (!registered) {
            registration = null
            setStatusLocked(TiltStatus.REGISTER_FAILED)
            if (generation == owner.generation && registration == null && status == TiltStatus.REGISTER_FAILED) {
                safeResetEmit(owner.generation)
            }
            return
        }
        owner.ready = true
        processor.onResetHistory()
        val now = clockSafely()
        if (registration !== owner) return
        processor.onWaitingStarted(now)
        val statusChanged = status != TiltStatus.WAITING
        status = TiltStatus.WAITING
        scheduler.scheduleSampleTimeout(StandbyTiltMapper.SAMPLE_TIMEOUT_MILLIS, owner.generation) {
            onSampleTimeout(owner)
        }
        if (registration !== owner) return
        if (statusChanged) safeStatusEmit(TiltStatus.WAITING)
        if (registration === owner && status == TiltStatus.WAITING) safeResetEmit(owner.generation)
    }

    override fun stop() = synchronized(this) {
        val owner = registration
        if (owner == null && status == TiltStatus.IDLE) return@synchronized
        val expected = ++generation
        if (owner != null) retireRegistrationLocked(owner)
        if (generation != expected || registration != null) return@synchronized
        setStatusLocked(TiltStatus.IDLE)
        if (owner != null && generation == expected && registration == null && status == TiltStatus.IDLE) {
            safeResetEmit(expected)
        }
    }

    override fun reset() = synchronized(this) {
        val owner = registration
        if (owner != null) {
            retireRegistrationLocked(owner)
            if (generation == owner.generation && registration == null) beginRegistrationLocked()
        } else {
            val expected = ++generation
            processor.onResetHistory()
            setStatusLocked(TiltStatus.IDLE)
            if (generation == expected && registration == null && status == TiltStatus.IDLE) {
                safeResetEmit(expected)
            }
        }
    }

    fun notifyDisplayChanged() = synchronized(this) {
        val owner = registration ?: return@synchronized
        retireRegistrationLocked(owner)
        if (generation == owner.generation && registration == null) beginRegistrationLocked()
    }

    private fun retireRegistrationLocked(owner: Registration) {
        if (registration !== owner) return
        registration = null
        scheduler.cancelIdle(owner.generation)
        scheduler.cancelSampleTimeout(owner.generation)
        processor.onResetHistory()
        try {
            registry.unregister()
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "unregister failed", error)
        }
    }

    private fun onRotationMatrix(matrix9: DoubleArray, timestampMillis: Long, owner: Registration) =
        synchronized(this) {
            if (registration !== owner || !owner.ready) return@synchronized
            val cols = StandbyTiltMapper.columnsFromRowMajor(matrix9) ?: return@synchronized
            val display = displayDegrees()
            if (registration !== owner) return@synchronized
            val result = processor.onSample(cols, timestampMillis, display) ?: return@synchronized
            val revision = ++sampleRevision
            scheduler.cancelSampleTimeout(owner.generation)
            scheduler.cancelIdle(owner.generation)
            scheduler.scheduleIdle(StandbyTiltMapper.IDLE_TIMEOUT_MILLIS, owner.generation) {
                onIdleTimeout(owner, revision)
            }
            if (registration !== owner || sampleRevision != revision) return@synchronized
            setStatusLocked(TiltStatus.ACTIVE)
            if (registration === owner && sampleRevision == revision && status == TiltStatus.ACTIVE) {
                safeTiltEmit(result[0], result[1])
            }
        }

    private fun onIdleTimeout(owner: Registration, revision: Long) = synchronized(this) {
        if (registration !== owner || sampleRevision != revision) return@synchronized
        processor.onIdleEmitted()
        safeTiltEmit(0.0, 0.0)
    }

    private fun onSampleTimeout(owner: Registration) = synchronized(this) {
        if (registration !== owner || status != TiltStatus.WAITING) return@synchronized
        val expected = ++generation
        retireRegistrationLocked(owner)
        if (generation != expected || registration != null) return@synchronized
        setStatusLocked(TiltStatus.UNAVAILABLE)
        if (generation == expected && registration == null && status == TiltStatus.UNAVAILABLE) {
            safeResetEmit(expected)
        }
    }

    private fun setStatusLocked(next: TiltStatus) {
        if (status == next) {
            return
        }
        status = next
        safeStatusEmit(next)
    }

    private fun clockSafely(): Long {
        return try {
            clock()
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "clock failed", error)
            0L
        }
    }

    private fun safeTiltEmit(x: Double, y: Double) {
        try {
            onTilt(x, y)
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "onTilt failed", error)
        }
    }

    private fun safeStatusEmit(next: TiltStatus) {
        try {
            onStatus(next)
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "onStatus failed", error)
        }
    }

    private fun safeResetEmit(expectedGeneration: Long) {
        val expectedRevision = sampleRevision
        val expectedStatus = status
        safeTiltEmit(0.0, 0.0)
        if (generation != expectedGeneration || sampleRevision != expectedRevision || status != expectedStatus) return
        try {
            onResetRequest()
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "onReset failed", error)
        }
    }
}

/**
 * Listener bound to one registration's sample callback.
 *
 * @param onSample rotation vector and monotonic timestamp in milliseconds.
 */
internal class RotationVectorListener(private val onSample: (FloatArray, Long) -> Unit) : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent?) {
        val sample = event ?: return
        onVector(sample.values, sample.timestamp / 1_000_000L)
    }

    /**
     * Delivers a rotation vector.
     *
     * @param values rotation vector values.
     * @param timestampMillis monotonic timestamp in milliseconds.
     */
    internal fun onVector(values: FloatArray, timestampMillis: Long) {
        if (values.isNotEmpty()) onSample(values.clone(), timestampMillis)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Android registration seam for the listener installed by the source. */
internal interface RotationVectorRegistration {
    /** Returns whether a rotation-vector sensor is available. */
    fun hasSensor(): Boolean

    /**
     * Registers the given listener.
     *
     * @param handler delivery thread handler.
     * @param listener listener owned by this registration.
     * @return true when registration succeeds.
     */
    fun register(handler: Handler, listener: RotationVectorListener): Boolean

    /** Unregisters the given listener. */
    fun unregister(listener: RotationVectorListener)
}

/**
 * True Android rotation-vector parallax source for issue 07.
 *
 * Explicitly injected by a future host (never self-installed): the host
 * creates one with `applicationContext` and passes it to
 * [StandbySensorController], which calls [start]/[stop]/[reset] exactly on
 * the Matter/visible gate. No background motion: [stop] unregisters and
 * quits the serial thread. Sidebar toggles must not call [reset] or
 * [notifyDisplayChanged]; only real display rotation calls the latter.
 *
 * - No Activity leak: only `applicationContext` is retained.
 * - No input projection decoder, audio, or system-setting access.
 * - Listener work is confined to one serial `HandlerThread`; late callbacks
 *   carry the owning generation and are dropped when stale.
 * - Smoothing and pixel offsets stay in [StandbyParallaxMath]; this file
 *   emits only normalized targets.
 * - Missing sensor or `registerListener == false` is reported explicitly
 *   via [TiltStatus.MISSING]/[TiltStatus.REGISTER_FAILED] with zero
 *   fallback, no invented motion.
 *
 * BLOCKER (device-unverified, do not ship as verified): Android
 * rotation-vector world axes versus W3C `DeviceOrientationEvent`
 * compass/inclination equivalence is not proven from platform docs alone.
 * This source therefore does NOT apply `remapCoordinateSystem`; it converts
 * the vector to a row-major matrix and applies only the prototype screen
 * transform (`gyroscope.js` lines 196-202). Neither portrait axes nor the
 * 90/180/270 display transforms are verified on a target device.
 *
 * @param context any context; only `applicationContext` is retained.
 * @param onTilt normalized `[-1, 1]` target; sample callbacks use the sensor thread.
 * @param onStatus explicit lifecycle status.
 * @param onResetRequest zero/reset request; fires on start/stop/reset/timeout/display change.
 * @param displayDegrees verified display rotation in degrees; defaults to zero.
 * @param clock monotonic millis for timeouts; defaults to `elapsedRealtime`.
 */
class AndroidStandbyParallaxSource(
    context: Context,
    private val onTilt: (x: Double, y: Double) -> Unit = { _, _ -> },
    private val onStatus: (status: TiltStatus) -> Unit = {},
    private val onResetRequest: () -> Unit = {},
    private val displayDegrees: () -> Int = { 0 },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : StandbyParallaxSource {
    private val appContext: Context = context.applicationContext ?: context
    private val sensorManager: SensorManager? = try {
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    } catch (error: RuntimeException) {
        Log.w("StandbyTilt", "sensor service unavailable", error)
        null
    }
    private val rotationSensor: Sensor? = try {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    } catch (error: RuntimeException) {
        Log.w("StandbyTilt", "rotation sensor query failed", error)
        null
    }

    private data class ScheduledTask(val handler: Handler, val generation: Long, val runnable: Runnable)

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var idleRunnable: ScheduledTask? = null
    private var sampleRunnable: ScheduledTask? = null

    private var sensorRegistration: RotationVectorRegistration = object : RotationVectorRegistration {
        override fun hasSensor(): Boolean = rotationSensor != null

        override fun register(handler: Handler, listener: RotationVectorListener): Boolean {
            val manager = sensorManager ?: return false
            val sensor = rotationSensor ?: return false
            return manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        }

        override fun unregister(listener: RotationVectorListener) {
            sensorManager?.unregisterListener(listener)
        }
    }

    private val registry = object : TiltRegistry {
        private var listener: RotationVectorListener? = null

        override fun hasSensor(): Boolean = sensorRegistration.hasSensor()

        override fun register(onSample: (DoubleArray, Long) -> Unit): Boolean {
            val handler = sensorHandler ?: return false
            val candidate = RotationVectorListener { values, timestamp ->
                matrixFromVector(values)?.let { onSample(it, timestamp) }
            }
            listener = candidate
            val registered = try {
                sensorRegistration.register(handler, candidate)
            } catch (error: RuntimeException) {
                Log.w("StandbyTilt", "registerListener failed", error)
                false
            }
            if (!registered) {
                if (listener === candidate) listener = null
                try {
                    sensorRegistration.unregister(candidate)
                } catch (error: RuntimeException) {
                    Log.w("StandbyTilt", "failed registration cleanup failed", error)
                }
            }
            return registered
        }

        override fun unregister() {
            val previous = listener ?: return
            listener = null
            sensorRegistration.unregister(previous)
        }
    }

    private val scheduler: TiltScheduler = object : TiltScheduler {
        override fun scheduleIdle(delayMillis: Long, generation: Long, action: () -> Unit) {
            val handler = sensorHandler ?: return
            cancelIdle()
            val runnable = Runnable { action() }
            idleRunnable = ScheduledTask(handler, generation, runnable)
            handler.postDelayed(runnable, delayMillis)
        }

        override fun cancelIdle(generation: Long) {
            val task = idleRunnable ?: return
            if (generation != -1L && task.generation != generation) return
            idleRunnable = null
            task.handler.removeCallbacks(task.runnable)
        }

        override fun scheduleSampleTimeout(delayMillis: Long, generation: Long, action: () -> Unit) {
            val handler = sensorHandler ?: return
            cancelSampleTimeout()
            val runnable = Runnable {
                action()
                synchronized(delegate) {
                    if (!delegate.isStarted()) releaseThreadLocked()
                }
            }
            sampleRunnable = ScheduledTask(handler, generation, runnable)
            handler.postDelayed(runnable, delayMillis)
        }

        override fun cancelSampleTimeout(generation: Long) {
            val task = sampleRunnable ?: return
            if (generation != -1L && task.generation != generation) return
            sampleRunnable = null
            task.handler.removeCallbacks(task.runnable)
        }
    }

    private val delegate = StandbyTiltSourceDelegate(
        registry = registry,
        scheduler = scheduler,
        displayDegrees = displayDegrees,
        clock = clock,
        onTilt = onTilt,
        onStatus = onStatus,
        onResetRequest = onResetRequest,
    )

    internal constructor(
        context: Context,
        registration: RotationVectorRegistration,
        onTilt: (Double, Double) -> Unit = { _, _ -> },
        onStatus: (TiltStatus) -> Unit = {},
        onResetRequest: () -> Unit = {},
    ) : this(context, onTilt, onStatus, onResetRequest) {
        sensorRegistration = registration
    }

    internal fun retainedContext(): Context = appContext

    /** Runs scheduler operations under the lifecycle monitor. */
    internal fun withScheduler(action: (TiltScheduler) -> Unit) = synchronized(delegate) {
        action(scheduler)
    }

    override fun start() = synchronized(delegate) {
        if (delegate.isStarted()) return@synchronized
        if (sensorThread == null) {
            val thread = HandlerThread("StandbyParallax")
            thread.start()
            sensorThread = thread
            sensorHandler = Handler(thread.looper)
        }
        delegate.start()
        if (!delegate.isStarted()) releaseThreadLocked()
    }

    override fun stop() = synchronized(delegate) {
        delegate.stop()
        if (!delegate.isStarted()) releaseThreadLocked()
    }

    private fun releaseThreadLocked() {
        sensorHandler?.removeCallbacksAndMessages(null)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        idleRunnable = null
        sampleRunnable = null
    }

    override fun reset() = synchronized(delegate) {
        delegate.reset()
        if (!delegate.isStarted()) releaseThreadLocked()
    }

    fun notifyDisplayChanged() = synchronized(delegate) {
        delegate.notifyDisplayChanged()
        if (!delegate.isStarted()) releaseThreadLocked()
    }

    fun currentStatus(): TiltStatus = delegate.currentStatus()

    private fun matrixFromVector(vector: FloatArray): DoubleArray? {
        return try {
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, vector)
            // Android API returns void; check for valid matrix (non-zero)
            var hasValid = false
            for (v in matrix) {
                if (v != 0f) {
                    hasValid = true
                    break
                }
            }
            if (!hasValid) {
                return null
            }
            DoubleArray(9) { index -> matrix[index].toDouble() }
        } catch (error: RuntimeException) {
            Log.w("StandbyTilt", "rotation matrix failed", error)
            null
        }
    }
}
