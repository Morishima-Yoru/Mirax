package me.trinitrix.mirax.ui.standby.runtime

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ScreenPhase
import org.junit.Test

/**
 * JVM checks for the issue 07 host seam ([StandbyRuntimeHost]).
 *
 * Covers the corrected S34-S37 contract: foreground Big Standby holds
 * keep-awake in every phase (READY and FROZEN included) and releases it when
 * leaving, projecting, or backgrounding without any dimming; reduced and
 * disabled animation preferences never stop ambient motion; FROZEN never
 * unconditionally pauses the pipeline; attached Matter sensors register and
 * release in pairs and are reentrant-safe.
 *
 * No Android APIs, no Gradle device runs; fakes stand in for sensor owners.
 */
class StandbyRuntimeHostTest {

    private class FakeParallax : StandbyParallaxSource {
        var starts = 0
        var stops = 0
        var resets = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
        override fun reset() {
            resets++
        }
    }

    private class FakeDrift : StandbyOledDriftSource {
        var starts = 0
        var stops = 0
        var resets = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
        override fun resetOffsets() {
            resets++
        }
    }

    private fun host(): Triple<StandbyRuntimeHost, FakeParallax, FakeDrift> {
        val parallax = FakeParallax()
        val drift = FakeDrift()
        val controller = StandbySensorController(parallax, drift)
        return Triple(StandbyRuntimeHost(controller), parallax, drift)
    }

    @Test
    fun keepAwake_heldForReadyAndFrozenWhenForeground() {
        val (host, _, _) = host()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        assertThat(host.shouldHoldKeepAwake()).isTrue()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.FROZEN)
        assertThat(host.shouldHoldKeepAwake()).isTrue()
    }

    @Test
    fun keepAwake_releasedWhenLeavingProjectingOrBackground() {
        val (host, _, _) = host()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        assertThat(host.shouldHoldKeepAwake()).isTrue()
        host.onVisibilityChanged(StandbyVisibilityState.Hidden, ScreenPhase.READY)
        assertThat(host.shouldHoldKeepAwake()).isFalse()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Projecting, ScreenPhase.CONNECTED)
        assertThat(host.shouldHoldKeepAwake()).isFalse()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Background, ScreenPhase.READY)
        assertThat(host.shouldHoldKeepAwake()).isFalse()
    }

    @Test
    fun motion_ignoresReducedAndDisabledPreferences() {
        val (host, _, _) = host()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onAnimationPreferenceChanged(StandbyAnimationPreference.REDUCED)
        assertThat(host.shouldRunAmbient()).isTrue()
        host.onAnimationPreferenceChanged(StandbyAnimationPreference.DISABLED)
        assertThat(host.shouldRunAmbient()).isTrue()
        host.onAnimationPreferenceChanged(StandbyAnimationPreference.FULL)
        assertThat(host.shouldRunAmbient()).isTrue()
    }

    @Test
    fun motion_frozenStaysRunningOnlyHiddenPauses() {
        val (host, _, _) = host()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.FROZEN)
        assertThat(host.shouldRunAmbient()).isTrue()
        host.onVisibilityChanged(StandbyVisibilityState.Hidden, ScreenPhase.FROZEN)
        assertThat(host.shouldRunAmbient()).isFalse()
    }

    @Test
    fun sensors_registerAndReleaseInPairs() {
        val (host, parallax, drift) = host()
        assertThat(host.isHoldingSensors()).isFalse()
        host.onMatterLayerAvailabilityChanged(true)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isTrue()
        assertThat(parallax.starts).isEqualTo(1)
        assertThat(drift.starts).isEqualTo(1)
        host.onVisibilityChanged(StandbyVisibilityState.Hidden, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isFalse()
        assertThat(parallax.stops).isEqualTo(1)
        assertThat(parallax.resets).isEqualTo(1)
        assertThat(drift.stops).isEqualTo(1)
        assertThat(drift.resets).isEqualTo(1)
    }

    @Test
    fun sensors_reentrantTransitionsStayBalanced() {
        val (host, parallax, drift) = host()
        host.onMatterLayerAvailabilityChanged(true)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.FROZEN)
        assertThat(parallax.starts).isEqualTo(1)
        assertThat(drift.starts).isEqualTo(1)
        host.onVisibilityChanged(StandbyVisibilityState.Projecting, ScreenPhase.CONNECTED)
        host.onVisibilityChanged(StandbyVisibilityState.Background, ScreenPhase.READY)
        assertThat(parallax.stops).isEqualTo(1)
        assertThat(parallax.resets).isEqualTo(1)
        assertThat(drift.stops).isEqualTo(1)
        assertThat(drift.resets).isEqualTo(1)
    }

    @Test
    fun matterLayerAndVisibilityGateSensorsWithoutChangingKeepAwakeOrAmbient() {
        val (host, parallax, drift) = host()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isFalse()
        assertThat(host.shouldHoldKeepAwake()).isTrue()
        assertThat(host.shouldRunAmbient()).isTrue()

        host.onMatterLayerAvailabilityChanged(true)
        assertThat(host.isHoldingSensors()).isTrue()
        host.onMatterLayerAvailabilityChanged(false)
        host.onMatterLayerAvailabilityChanged(false)
        assertThat(host.isHoldingSensors()).isFalse()
        assertThat(host.shouldHoldKeepAwake()).isTrue()
        assertThat(host.shouldRunAmbient()).isTrue()
        host.onMatterLayerAvailabilityChanged(true)
        host.onVisibilityChanged(StandbyVisibilityState.Hidden, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isFalse()
        assertThat(host.shouldHoldKeepAwake()).isFalse()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.FROZEN)
        assertThat(host.isHoldingSensors()).isTrue()
        assertThat(host.shouldHoldKeepAwake()).isTrue()
        host.onVisibilityChanged(StandbyVisibilityState.Projecting, ScreenPhase.CONNECTED)
        assertThat(host.isHoldingSensors()).isFalse()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Background, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isFalse()
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        assertThat(host.isHoldingSensors()).isTrue()
        assertThat(parallax.starts).isEqualTo(5)
        assertThat(parallax.stops).isEqualTo(4)
        assertThat(drift.starts).isEqualTo(parallax.starts)
        assertThat(drift.stops).isEqualTo(parallax.stops)
    }

    @Test
    fun queuedTiltFromBeforeLayerOrVisibilityChangeCannotMoveNewRegistration() {
        val state = StandbyMotionState { 0.5 }
        val parallax = object : StandbyParallaxSource {
            override fun start() = state.start()
            override fun stop() = state.stop()
            override fun reset() = state.resetTilt()
        }
        val host = StandbyRuntimeHost(StandbySensorController(parallax, FakeDrift()))
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        host.onMatterLayerAvailabilityChanged(true)
        val beforeDetach = requireNotNull(state.ticket())
        host.onMatterLayerAvailabilityChanged(false)
        host.onMatterLayerAvailabilityChanged(true)
        val beforeBackground = requireNotNull(state.ticket())
        host.onVisibilityChanged(StandbyVisibilityState.Background, ScreenPhase.READY)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)
        val beforeProjection = requireNotNull(state.ticket())
        host.onVisibilityChanged(StandbyVisibilityState.Projecting, ScreenPhase.CONNECTED)
        host.onVisibilityChanged(StandbyVisibilityState.Visible, ScreenPhase.READY)

        listOf(beforeDetach, beforeBackground, beforeProjection).forEach { state.acceptTilt(it, 1.0, 0.0) }
        assertThat(state.advance(0.1, 800, 600).canvasX).isEqualTo(0f)
        state.acceptTilt(requireNotNull(state.ticket()), 1.0, 0.0)
        assertThat(state.advance(0.1, 800, 600).canvasX).isGreaterThan(0f)
    }
}
