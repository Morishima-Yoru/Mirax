package me.trinitrix.mirax.ui.standby.runtime

import android.content.Context
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Device-only contract for [AndroidStandbyParallaxSource] (written, NOT executed).
 *
 * Parent owns build/install/execution; this file only pins the integration
 * claims that cannot be proven on JVM:
 *
 * - Retained context is the application context (no Activity leak).
 * - Missing sensor or register refusal is reported explicitly, never crashes.
 * - start/stop/reset stay symmetric and reentrant on a real SensorManager.
 * - A fake platform adapter captures the actual source-created listeners.
 * - Reentrant lifecycle calls share one monitor and cannot tear down a restart.
 *
 * Device unverified: rotation-vector to W3C equivalence and display remap
 * remain blockers; do not claim landscape parallax until proven on device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStandbyParallaxSourceTest {

    private class CapturingRegistration : RotationVectorRegistration {
        val listeners = mutableListOf<RotationVectorListener>()
        var unregisters = 0

        override fun hasSensor(): Boolean = true

        override fun register(handler: Handler, listener: RotationVectorListener): Boolean {
            listeners.add(listener)
            return true
        }

        override fun unregister(listener: RotationVectorListener) {
            unregisters += 1
        }
    }

    @Test
    fun retainedContext_isApplicationContext() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidStandbyParallaxSource(appContext)
        assertThat(source.retainedContext()).isEqualTo(appContext.applicationContext)
    }

    @Test
    fun lifecycle_isReentrantWithoutDeviceMotion() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        var resets = 0
        val source = AndroidStandbyParallaxSource(
            appContext,
            onResetRequest = { resets += 1 },
        )
        source.start()
        source.start()
        source.reset()
        source.stop()
        source.stop()
        assertThat(resets).isAtLeast(3)
        assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.IDLE)
    }

    @Test
    fun multipleStartStop_keepsIdleStatus() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        // This exercises the platform registration path without assuming a sensor exists.
        val source = AndroidStandbyParallaxSource(appContext)
        repeat(5) {
            source.start()
            source.stop()
        }
        assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.IDLE)
    }

    @Test
    fun displayChanged_resetsWithoutDeadlock() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidStandbyParallaxSource(appContext)
        source.start()
        source.notifyDisplayChanged()
        source.stop()
        assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.IDLE)
    }

    @Test
    fun registeredCallbacks_rejectLateSamplesAcrossEveryReregistration() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val registration = CapturingRegistration()
        val nonZeroTilts = CopyOnWriteArrayList<Double>()
        val source = AndroidStandbyParallaxSource(
            appContext,
            registration,
            onTilt = { x, _ -> if (abs(x) > 1e-6) nonZeroTilts.add(x) },
        )
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val tilted = floatArrayOf(0f, 0.039259817f, 0f, 0.99922904f)
        try {
            source.start()
            source.start()
            assertThat(registration.listeners).hasSize(1)
            val first = registration.listeners[0]
            first.onVector(identity, 1_000L)
            source.reset()
            val second = registration.listeners[1]
            assertThat(second).isNotSameInstanceAs(first)
            first.onVector(tilted, 2_000L)
            assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.WAITING)
            second.onVector(identity, 2_100L)
            first.onVector(tilted, 2_150L)
            assertThat(nonZeroTilts).isEmpty()
            second.onVector(tilted, 2_200L)
            assertThat(nonZeroTilts).hasSize(1)
            assertThat(nonZeroTilts[0]).isWithin(1e-5).of(0.5)

            source.notifyDisplayChanged()
            val third = registration.listeners[2]
            assertThat(third).isNotSameInstanceAs(second)
            second.onVector(tilted, 3_000L)
            assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.WAITING)
            third.onVector(identity, 3_100L)
            second.onVector(tilted, 3_150L)
            assertThat(nonZeroTilts).hasSize(1)

            source.stop()
            source.start()
            val fourth = registration.listeners[3]
            assertThat(fourth).isNotSameInstanceAs(third)
            third.onVector(tilted, 4_000L)
            assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.WAITING)
            fourth.onVector(identity, 4_100L)
            third.onVector(tilted, 4_150L)
            fourth.onVector(tilted, 4_200L)
            assertThat(nonZeroTilts).hasSize(2)
            assertThat(nonZeroTilts[1]).isWithin(1e-5).of(0.5)
            assertThat(registration.unregisters).isEqualTo(3)
        } finally {
            source.stop()
        }
    }

    @Test
    fun scheduler_oldGenerationCannotCancelNewCallbacks() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val source = AndroidStandbyParallaxSource(appContext, CapturingRegistration())
        val fired = CountDownLatch(2)
        try {
            source.start()
            source.withScheduler { scheduler ->
                scheduler.scheduleIdle(25L, 22L) { fired.countDown() }
                scheduler.scheduleSampleTimeout(25L, 22L) { fired.countDown() }
                scheduler.cancelIdle(21L)
                scheduler.cancelSampleTimeout(21L)
            }
            assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            source.stop()
        }
    }

    @Test
    fun reentrantRestart_doesNotLoseNewRegistrationOrThread() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val registration = CapturingRegistration()
        var restart = true
        lateinit var source: AndroidStandbyParallaxSource
        source = AndroidStandbyParallaxSource(
            appContext,
            registration,
            onStatus = { status ->
                if (status == StandbyTiltMapper.TiltStatus.WAITING) source.start()
                if (status == StandbyTiltMapper.TiltStatus.IDLE && restart) {
                    restart = false
                    source.start()
                }
            },
        )
        try {
            source.start()
            assertThat(registration.listeners).hasSize(1)
            source.stop()
            assertThat(registration.listeners).hasSize(2)
            assertThat(registration.unregisters).isEqualTo(1)
            registration.listeners[0].onVector(floatArrayOf(0f, 0f, 0f, 1f), 1_000L)
            assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.WAITING)
            registration.listeners[1].onVector(floatArrayOf(0f, 0f, 0f, 1f), 1_100L)
            assertThat(source.currentStatus()).isEqualTo(StandbyTiltMapper.TiltStatus.ACTIVE)
        } finally {
            source.stop()
        }
    }
}
