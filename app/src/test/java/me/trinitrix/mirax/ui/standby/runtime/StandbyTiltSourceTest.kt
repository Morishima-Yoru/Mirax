package me.trinitrix.mirax.ui.standby.runtime

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltProcessor
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltRegistry
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltScheduler
import me.trinitrix.mirax.ui.standby.runtime.StandbyTiltMapper.TiltStatus
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * JVM checks for the injectable tilt lifecycle delegate (issue 07).
 *
 * No Android APIs, no Gradle device runs; fake registry/scheduler/clock
 * stand in for SensorManager/Handler. Hand-computed vectors only:
 *
 * - Identity quaternion `(0,0,0,1)` yields identity rows.
 * - 90-degree Z quaternion `(0,0,sqrt(0.5),sqrt(0.5))` yields
 *   `[[0,-1,0],[1,0,0],[0,0,1]]` by the standard matrix form.
 * - A 4.5-degree Y rotation over 100ms gives `45/90 = 0.5` on X.
 * - A pure Z rotation leaves the surface normal unchanged.
 * - Screen rotations assert prototype lines 198-202 mappings exactly.
 *
 * Never asserts with implementation-computed expectations.
 */
class StandbyTiltSourceTest {

    private class FakeRegistry(
        var present: Boolean = true,
        var registerResult: Boolean = true,
        var throwOnRegister: Boolean = false,
    ) : TiltRegistry {
        var registers = 0
        var unregisters = 0
        val callbacks = mutableListOf<(DoubleArray, Long) -> Unit>()
        override fun hasSensor(): Boolean = present
        override fun register(onSample: (DoubleArray, Long) -> Unit): Boolean {
            registers += 1
            callbacks.add(onSample)
            if (throwOnRegister) {
                throw RuntimeException("boom")
            }
            return registerResult
        }
        fun emit(matrix: DoubleArray, timestampMillis: Long) = callbacks.last()(matrix, timestampMillis)
        override fun unregister() {
            unregisters += 1
        }
    }

    private class FakeScheduler(
        var now: Long = 0L,
        val autoRunIdle: Boolean = false,
        val autoRunSample: Boolean = false,
    ) : TiltScheduler {
        private data class Task(val at: Long, val gen: Long, val action: () -> Unit)
        private var idle: Task? = null
        private var sample: Task? = null
        var idleCancels = 0
        var sampleCancels = 0
        val idleCallbacks = mutableListOf<() -> Unit>()
        val sampleCallbacks = mutableListOf<() -> Unit>()

        override fun scheduleIdle(delayMillis: Long, generation: Long, action: () -> Unit) {
            idleCallbacks.add(action)
            idle = Task(now + delayMillis, generation, action)
            if (autoRunIdle) {
                advance(delayMillis)
            }
        }

        override fun cancelIdle(generation: Long) {
            idleCancels += 1
            if (generation == -1L || idle?.gen == generation) idle = null
        }

        override fun scheduleSampleTimeout(delayMillis: Long, generation: Long, action: () -> Unit) {
            sampleCallbacks.add(action)
            sample = Task(now + delayMillis, generation, action)
            if (autoRunSample) {
                advance(delayMillis)
            }
        }

        override fun cancelSampleTimeout(generation: Long) {
            sampleCancels += 1
            if (generation == -1L || sample?.gen == generation) sample = null
        }

        fun advance(millis: Long) {
            now += millis
            idle?.let {
                if (now >= it.at) {
                    idle = null
                    it.action()
                }
            }
            sample?.let {
                if (now >= it.at) {
                    sample = null
                    it.action()
                }
            }
        }

        fun hasIdle(): Boolean = idle != null
        fun hasSample(): Boolean = sample != null
    }

    private class Harness(
        val registry: FakeRegistry = FakeRegistry(),
        val scheduler: FakeScheduler = FakeScheduler(),
    ) {
        var clockValue = 1_000L
        val tilts = mutableListOf<Pair<Double, Double>>()
        val statuses = mutableListOf<TiltStatus>()
        var resets = 0
        var display = 0
        val delegate = StandbyTiltSourceDelegate(
            registry = registry,
            scheduler = scheduler,
            processor = TiltProcessor(),
            displayDegrees = { display },
            clock = { clockValue },
            onTilt = { x, y -> tilts.add(x to y) },
            onStatus = { statuses.add(it) },
            onResetRequest = { resets += 1 },
        )

        fun identity(): DoubleArray = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
    }

    @Test
    fun quaternion_identityYieldsIdentityCols() {
        val matrix = StandbyTiltMapper.rotationMatrixFromQuaternion(0.0, 0.0, 0.0, 1.0)
        assertThat(matrix).isNotNull()
        val cols = StandbyTiltMapper.columnsFromRowMajor(matrix!!)!!
        assertThat(cols[0]).usingExactEquality().containsExactly(1.0, 0.0, 0.0)
        assertThat(cols[1]).usingExactEquality().containsExactly(0.0, 1.0, 0.0)
        assertThat(cols[2]).usingExactEquality().containsExactly(0.0, 0.0, 1.0)
    }

    @Test
    fun quaternion_ninetyDegreeZYieldsKnownMatrix() {
        val half = kotlin.math.sqrt(0.5)
        val matrix = StandbyTiltMapper.rotationMatrixFromQuaternion(0.0, 0.0, half, half)!!
        val cols = StandbyTiltMapper.columnsFromRowMajor(matrix)!!
        assertThat(cols[0][0]).isWithin(1e-12).of(0.0)
        assertThat(cols[0][1]).isWithin(1e-12).of(1.0)
        assertThat(cols[0][2]).isWithin(1e-12).of(0.0)
        assertThat(cols[1][0]).isWithin(1e-12).of(-1.0)
        assertThat(cols[1][1]).isWithin(1e-12).of(0.0)
        assertThat(cols[1][2]).isWithin(1e-12).of(0.0)
        assertThat(cols[2][0]).isWithin(1e-12).of(0.0)
        assertThat(cols[2][1]).isWithin(1e-12).of(0.0)
        assertThat(cols[2][2]).isWithin(1e-12).of(1.0)
    }

    @Test
    fun quaternion_rejectsNonFiniteAndZero() {
        assertThat(StandbyTiltMapper.rotationMatrixFromQuaternion(Double.NaN, 0.0, 0.0, 1.0)).isNull()
        assertThat(StandbyTiltMapper.rotationMatrixFromQuaternion(0.0, 0.0, 0.0, 0.0)).isNull()
    }

    @Test
    fun screenRotation_matchesPrototypeFourCases() {
        val x = 0.25
        val y = -0.5
        assertThat(StandbyTiltMapper.applyScreenRotation(x, y, 0)).usingExactEquality()
            .containsExactly(0.25, -0.5)
        assertThat(StandbyTiltMapper.applyScreenRotation(x, y, 90)).usingExactEquality()
            .containsExactly(-0.5, -0.25)
        assertThat(StandbyTiltMapper.applyScreenRotation(x, y, 180)).usingExactEquality()
            .containsExactly(-0.25, 0.5)
        assertThat(StandbyTiltMapper.applyScreenRotation(x, y, 270)).usingExactEquality()
            .containsExactly(0.5, 0.25)
        // Unknown angles fall through to default, like prototype `default`.
        assertThat(StandbyTiltMapper.applyScreenRotation(x, y, 45)).usingExactEquality()
            .containsExactly(0.25, -0.5)
    }

    @Test
    fun rotationYStep_matchesHandComputedRate() {
        val previous = StandbyTiltMapper.columnsFromRowMajor(Harness().identity())!!
        val current = StandbyTiltMapper.columnsFromRowMajor(doubleArrayOf(
            0.996917333733128, 0.0, 0.078459095727845,
            0.0, 1.0, 0.0,
            -0.078459095727845, 0.0, 0.996917333733128,
        ))!!
        val tilt = StandbyTiltMapper.computeTilt(previous, current, 100L)
        assertThat(tilt[0]).isWithin(1e-9).of(0.5)
        assertThat(abs(tilt[1])).isWithin(1e-9).of(0.0)
        val fast = StandbyTiltMapper.computeTilt(
            previous,
            StandbyTiltMapper.columnsFromRowMajor(StandbyTiltMapper.yawMatrixDegrees(0.1))!!,
            4L,
        )
        assertThat(fast[0]).isWithin(1e-9).of(25.0 / 90.0)
        assertThat(abs(fast[1])).isWithin(1e-9).of(0.0)
        val clamped = StandbyTiltMapper.computeTilt(
            previous,
            StandbyTiltMapper.columnsFromRowMajor(StandbyTiltMapper.yawMatrixDegrees(1.0))!!,
            4L,
        )
        assertThat(clamped[0]).isWithin(1e-9).of(1.0)
    }

    @Test
    fun rotationZAndLongGap_emitZero() {
        val previous = StandbyTiltMapper.columnsFromRowMajor(Harness().identity())!!
        val yaw = StandbyTiltMapper.columnsFromRowMajor(doubleArrayOf(
            0.0, -1.0, 0.0,
            1.0, 0.0, 0.0,
            0.0, 0.0, 1.0,
        ))!!
        val tilted = StandbyTiltMapper.columnsFromRowMajor(doubleArrayOf(
            0.0, 0.0, 1.0,
            0.0, 1.0, 0.0,
            -1.0, 0.0, 0.0,
        ))!!
        assertThat(StandbyTiltMapper.computeTilt(previous, yaw, 100L)).usingExactEquality()
            .containsExactly(0.0, 0.0).inOrder()
        assertThat(StandbyTiltMapper.computeTilt(previous, tilted, 251L)).usingExactEquality()
            .containsExactly(0.0, 0.0).inOrder()
    }

    @Test
    fun startStopReset_areSymmetricAndReentrant() {
        val harness = Harness()
        harness.delegate.start()
        harness.delegate.start()
        assertThat(harness.registry.registers).isEqualTo(1)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.WAITING)
        harness.delegate.stop()
        harness.delegate.stop()
        harness.delegate.reset()
        assertThat(harness.registry.unregisters).isEqualTo(1)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.IDLE)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
    }

    @Test
    fun missingSensor_reportsExplicitlyWithZeroFallback() {
        val harness = Harness(FakeRegistry(present = false))
        harness.delegate.start()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.MISSING)
        assertThat(harness.registry.registers).isEqualTo(0)
        assertThat(harness.statuses.last()).isEqualTo(TiltStatus.MISSING)
        assertThat(harness.tilts.single()).isEqualTo(0.0 to 0.0)
        assertThat(harness.resets).isEqualTo(1)
        // generation NOT incremented on missing sensor
        assertThat(harness.delegate.currentGeneration()).isEqualTo(0L)
    }

    @Test
    fun registerFalse_reportsExplicitlyWithZeroFallback() {
        val harness = Harness(FakeRegistry(registerResult = false))
        harness.delegate.start()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.REGISTER_FAILED)
        assertThat(harness.tilts.single()).isEqualTo(0.0 to 0.0)
        assertThat(harness.resets).isEqualTo(1)
        // A failed registration still consumes its callback identity.
        assertThat(harness.delegate.currentGeneration()).isEqualTo(1L)
    }

    @Test
    fun registerThrows_reportsFailedWithZeroFallback() {
        val harness = Harness(FakeRegistry(throwOnRegister = true))
        harness.delegate.start()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.REGISTER_FAILED)
        assertThat(harness.tilts.single()).isEqualTo(0.0 to 0.0)
        assertThat(harness.resets).isEqualTo(1)
        assertThat(harness.delegate.currentGeneration()).isEqualTo(1L)
    }

    @Test
    fun lateSample_afterStopIsDropped() {
        val harness = Harness()
        harness.delegate.start()
        val oldCallback = harness.registry.callbacks.single()
        harness.delegate.stop()
        oldCallback(harness.identity(), 2_000L)
        assertThat(harness.tilts).containsExactly(0.0 to 0.0, 0.0 to 0.0).inOrder()
    }

    @Test
    fun staleRegistration_sampleIsDroppedAfterReset() {
        val harness = Harness()
        harness.delegate.start()
        val oldCallback = harness.registry.callbacks.single()
        harness.delegate.reset()
        oldCallback(harness.identity(), 2_000L)
        assertThat(harness.tilts).containsExactly(0.0 to 0.0, 0.0 to 0.0).inOrder()
        assertThat(harness.registry.registers).isEqualTo(2)
        harness.registry.emit(harness.identity(), 2_100L)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.ACTIVE)
        assertThat(harness.tilts).hasSize(3)
    }

    @Test
    fun firstSampleAndGapZero_storeButEmitZeros() {
        val harness = Harness()
        harness.delegate.start()
        assertThat(harness.scheduler.hasSample()).isTrue()
        harness.registry.emit(harness.identity(), 1_100L)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.ACTIVE)
        // Gap > 250 stores but emits zeros, like prototype line 184-187.
        harness.registry.emit(harness.identity(), 1_500L)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
        // Non-positive elapsed is dropped without emitting.
        val before = harness.tilts.size
        harness.registry.emit(harness.identity(), 1_500L)
        assertThat(harness.tilts.size).isEqualTo(before)
        // Invalid matrix is dropped without emitting.
        harness.registry.emit(doubleArrayOf(1.0), 1_600L)
        assertThat(harness.tilts.size).isEqualTo(before)
    }

    @Test
    fun idleTimeout_emitsZeroWithoutStateSnap() {
        val harness = Harness()
        harness.delegate.start()
        harness.registry.emit(harness.identity(), 1_100L)
        val emitted = harness.tilts.size
        harness.clockValue = 1_100L + StandbyTiltMapper.IDLE_TIMEOUT_MILLIS
        harness.scheduler.advance(StandbyTiltMapper.IDLE_TIMEOUT_MILLIS)
        assertThat(harness.scheduler.hasIdle()).isFalse()
        assertThat(harness.tilts.size).isEqualTo(emitted + 1)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.ACTIVE)
    }

    @Test
    fun sampleTimeout_reportsUnavailableAndResets() {
        val harness = Harness()
        harness.delegate.start()
        assertThat(harness.scheduler.hasSample()).isTrue()
        harness.clockValue = 1_000L + StandbyTiltMapper.SAMPLE_TIMEOUT_MILLIS
        harness.scheduler.advance(StandbyTiltMapper.SAMPLE_TIMEOUT_MILLIS)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.UNAVAILABLE)
        assertThat(harness.registry.unregisters).isEqualTo(1)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
        assertThat(harness.resets).isEqualTo(2)
        // After timeout, started = false so reset should not re-enter WAITING
        harness.delegate.reset()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.IDLE)
        assertThat(harness.scheduler.hasSample()).isFalse()
    }

    @Test
    fun rotationRate_usesRadiansOverElapsed() {
        // normalizeRate itself does not apply the processor's 250ms gap rule.
        val radians = 45.0 * PI / 180.0
        assertThat(StandbyTiltMapper.normalizeRate(radians, 1000L)).isWithin(1e-12).of(0.5)
        assertThat(StandbyTiltMapper.normalizeRate(Double.NaN, 1000L)).isEqualTo(0.0)
        assertThat(StandbyTiltMapper.normalizeRate(1.0, 0L)).isEqualTo(0.0)
    }

    @Test
    fun highRate_highFrequencySamplesDoNotLeakTimers() {
        val harness = Harness()
        harness.delegate.start()
        var time = 1_100L
        // 240Hz-style burst: 60 samples at ~4ms stay bounded and keep one idle timer.
        repeat(60) { index ->
            harness.registry.emit(
                StandbyTiltMapper.yawMatrixDegrees(index.toDouble()),
                time,
            )
            time += 4L
        }
        assertThat(harness.scheduler.hasIdle()).isTrue()
        harness.delegate.stop()
        assertThat(harness.scheduler.hasIdle()).isFalse()
        assertThat(harness.scheduler.hasSample()).isFalse()
        assertThat(harness.registry.unregisters).isEqualTo(1)
    }

    @Test
    fun displayChanged_reRegistersAndResetsHistory() {
        val harness = Harness()
        harness.delegate.start()
        harness.registry.emit(harness.identity(), 1_100L)
        val gen1 = harness.delegate.currentGeneration()
        // display change triggers re-register
        harness.delegate.notifyDisplayChanged()
        val gen2 = harness.delegate.currentGeneration()
        assertThat(gen2).isEqualTo(gen1 + 1)
        assertThat(harness.registry.registers).isEqualTo(2) // initial + re-register
        assertThat(harness.registry.unregisters).isEqualTo(1) // unregister before re-register
        // History cleared, status WAITING again
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.WAITING)
        // First sample after display change emits zero
        harness.registry.emit(harness.identity(), 2_000L)
        assertThat(harness.tilts.last()).isEqualTo(0.0 to 0.0)
    }

    @Test
    fun multipleStartStop_stopsOldBeforeNew() {
        val harness = Harness()
        harness.delegate.start()
        val gen1 = harness.delegate.currentGeneration()
        harness.registry.emit(harness.identity(), 1_100L)
        // Second start should not create duplicate registration
        harness.delegate.start()
        assertThat(harness.registry.registers).isEqualTo(1)
        // Stop should unregister once
        harness.delegate.stop()
        assertThat(harness.registry.unregisters).isEqualTo(1)
        // Start again - should register again
        harness.delegate.start()
        assertThat(harness.registry.registers).isEqualTo(2)
        val gen3 = harness.delegate.currentGeneration()
        assertThat(gen3).isEqualTo(gen1 + 2) // gen1 + reset(if any) + new start
    }

    @Test
    fun resetAfterTimeout_doesNotReEnterWaiting() {
        val harness = Harness()
        harness.delegate.start()
        harness.clockValue = 1_000L + StandbyTiltMapper.SAMPLE_TIMEOUT_MILLIS
        harness.scheduler.advance(StandbyTiltMapper.SAMPLE_TIMEOUT_MILLIS)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.UNAVAILABLE)
        // Reset after timeout should go to IDLE, not WAITING
        harness.delegate.reset()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.IDLE)
        assertThat(harness.scheduler.hasSample()).isFalse()
    }

    @Test
    fun stopCalledTwice_unregisterOnlyOnce() {
        val harness = Harness()
        harness.delegate.start()
        harness.delegate.stop()
        harness.delegate.stop()
        assertThat(harness.registry.unregisters).isEqualTo(1)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.IDLE)
    }

    @Test
    fun emitOrder_resetEmitsTiltThenReset() {
        val events = mutableListOf<String>()
        val delegate = StandbyTiltSourceDelegate(
            registry = FakeRegistry(),
            scheduler = FakeScheduler(),
            onTilt = { x, y -> events.add("tilt:$x:$y") },
            onResetRequest = { events.add("reset") },
        )
        delegate.start()
        delegate.reset()
        assertThat(events).containsExactly("tilt:0.0:0.0", "reset", "tilt:0.0:0.0", "reset").inOrder()
    }

    @Test
    fun failedReregistration_invalidatesBothCallbacks() {
        val harness = Harness()
        harness.delegate.start()
        val oldCallback = harness.registry.callbacks.single()
        harness.registry.registerResult = false
        harness.delegate.notifyDisplayChanged()
        val failedCallback = harness.registry.callbacks.last()
        val count = harness.tilts.size
        oldCallback(harness.identity(), 1_000L)
        failedCallback(harness.identity(), 1_100L)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.REGISTER_FAILED)
        assertThat(harness.delegate.isStarted()).isFalse()
        assertThat(harness.scheduler.hasSample()).isFalse()
        assertThat(harness.scheduler.hasIdle()).isFalse()
        assertThat(harness.tilts).hasSize(count)
    }

    @Test
    fun failedRegistrationCallback_doesNotContaminateNextRegistration() {
        val harness = Harness(FakeRegistry(registerResult = false))
        harness.delegate.start()
        val failedCallback = harness.registry.callbacks.single()
        harness.registry.registerResult = true
        harness.delegate.start()
        harness.registry.emit(harness.identity(), 1_000L)
        val count = harness.tilts.size
        failedCallback(StandbyTiltMapper.yawMatrixDegrees(4.5), 1_050L)
        assertThat(harness.tilts).hasSize(count)
        harness.registry.emit(StandbyTiltMapper.yawMatrixDegrees(4.5), 1_100L)
        assertThat(harness.tilts.last().first).isWithin(1e-9).of(0.5)
    }

    @Test
    fun staleTimers_neitherEmitNorCancelNewGeneration() {
        val harness = Harness()
        harness.delegate.start()
        val oldGeneration = harness.delegate.currentGeneration()
        val oldSampleTimer = harness.scheduler.sampleCallbacks.last()
        harness.registry.emit(harness.identity(), 1_000L)
        val activeCount = harness.tilts.size
        oldSampleTimer()
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.ACTIVE)
        assertThat(harness.tilts).hasSize(activeCount)
        val oldIdleTimer = harness.scheduler.idleCallbacks.last()
        harness.delegate.reset()
        val count = harness.tilts.size
        oldSampleTimer()
        oldIdleTimer()
        harness.scheduler.cancelSampleTimeout(oldGeneration)
        assertThat(harness.tilts).hasSize(count)
        assertThat(harness.delegate.currentStatus()).isEqualTo(TiltStatus.WAITING)
        assertThat(harness.scheduler.hasSample()).isTrue()
        harness.registry.emit(harness.identity(), 2_000L)
        val supersededIdle = harness.scheduler.idleCallbacks.last()
        harness.registry.emit(StandbyTiltMapper.yawMatrixDegrees(4.5), 2_100L)
        val currentCount = harness.tilts.size
        supersededIdle()
        harness.scheduler.cancelIdle(oldGeneration)
        assertThat(harness.tilts).hasSize(currentCount)
        assertThat(harness.scheduler.hasIdle()).isTrue()
    }

    @Test
    fun resetTiltCallback_deliveringNewSampleCannotIssueLateReset() {
        val registry = FakeRegistry()
        var sent = false
        var resets = 0
        val delegate = StandbyTiltSourceDelegate(
            registry = registry,
            scheduler = FakeScheduler(),
            onTilt = { _, _ ->
                if (!sent) {
                    sent = true
                    registry.emit(Harness().identity(), 1_000L)
                }
            },
            onResetRequest = { resets += 1 },
        )
        delegate.start()
        assertThat(delegate.currentStatus()).isEqualTo(TiltStatus.ACTIVE)
        assertThat(resets).isEqualTo(0)
    }

    @Test
    fun statusCallback_canReenterStartAndStopWithoutLateTilt() {
        val registry = FakeRegistry()
        val scheduler = FakeScheduler()
        val tilts = mutableListOf<Pair<Double, Double>>()
        var resets = 0
        lateinit var delegate: StandbyTiltSourceDelegate
        delegate = StandbyTiltSourceDelegate(
            registry = registry,
            scheduler = scheduler,
            onTilt = { x, y -> tilts.add(x to y) },
            onResetRequest = { resets += 1 },
            onStatus = { status ->
                when (status) {
                    TiltStatus.WAITING -> delegate.start()
                    TiltStatus.ACTIVE -> delegate.stop()
                    else -> Unit
                }
            },
        )
        delegate.start()
        assertThat(registry.registers).isEqualTo(1)
        registry.emit(Harness().identity(), 1_000L)
        assertThat(delegate.currentStatus()).isEqualTo(TiltStatus.IDLE)
        assertThat(registry.unregisters).isEqualTo(1)
        assertThat(tilts).containsExactly(0.0 to 0.0, 0.0 to 0.0).inOrder()
        assertThat(resets).isEqualTo(2)
        assertThat(scheduler.hasIdle()).isFalse()
    }
}
