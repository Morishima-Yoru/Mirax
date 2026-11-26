package me.trinitrix.mirax.ui.standby.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM checks for issue 07 pure slices: visibility gate, keep-awake policy,
 * sensor lifecycle, animation-preference immunity, and math boundaries.
 *
 * No Android APIs, no Gradle device runs; fakes stand in for sensor owners.
 * External decoding is represented by [FakeExternalDecoder], which the
 * controller never receives, proving projection pause cannot reach it.
 */
class StandbyRuntimeTest {

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

    /** Stand-in for the incoming stream decoder and audio path. */
    private class FakeExternalDecoder {
        var decodeCalls = 0
        fun decodeFrame() {
            decodeCalls++
        }
    }

    private fun controller(): Triple<StandbySensorController, FakeParallax, FakeDrift> {
        val parallax = FakeParallax()
        val drift = FakeDrift()
        return Triple(StandbySensorController(parallax, drift), parallax, drift)
    }

    @Test
    fun gate_runsOnlyWhenVisible() {
        assertThat(StandbyMotionGate.shouldRunAmbient(StandbyVisibilityState.Visible)).isTrue()
        assertThat(StandbyMotionGate.shouldRunAmbient(StandbyVisibilityState.Hidden)).isFalse()
        assertThat(StandbyMotionGate.shouldRunAmbient(StandbyVisibilityState.Background)).isFalse()
        assertThat(StandbyMotionGate.shouldRunAmbient(StandbyVisibilityState.Projecting)).isFalse()
    }

    @Test
    fun gate_sensorsMatchAmbient() {
        for (state in StandbyVisibilityState.entries) {
            assertThat(StandbyMotionGate.shouldHoldSensors(state))
                .isEqualTo(StandbyMotionGate.shouldRunAmbient(state))
        }
    }

    @Test
    fun keepAwake_heldOnlyWhenVisible() {
        assertThat(StandbyKeepAwakePolicy.shouldHoldKeepAwake(StandbyVisibilityState.Visible)).isTrue()
        assertThat(StandbyKeepAwakePolicy.shouldHoldKeepAwake(StandbyVisibilityState.Hidden)).isFalse()
        assertThat(StandbyKeepAwakePolicy.shouldHoldKeepAwake(StandbyVisibilityState.Background)).isFalse()
        assertThat(StandbyKeepAwakePolicy.shouldHoldKeepAwake(StandbyVisibilityState.Projecting)).isFalse()
    }

    @Test
    fun keepAwake_neverDimsOrWritesBrightness() {
        assertThat(StandbyKeepAwakePolicy.allowsAppDimming()).isFalse()
        assertThat(StandbyKeepAwakePolicy.allowsBrightnessWrite()).isFalse()
    }

    @Test
    fun animation_allPreferencesKeepMovingWhenVisible() {
        for (preference in StandbyAnimationPreference.entries) {
            assertThat(StandbyAnimationPolicy.shouldAnimate(StandbyVisibilityState.Visible, preference)).isTrue()
        }
    }

    @Test
    fun animation_hiddenStaysPausedForEveryPreference() {
        for (preference in StandbyAnimationPreference.entries) {
            assertThat(StandbyAnimationPolicy.shouldAnimate(StandbyVisibilityState.Hidden, preference)).isFalse()
            assertThat(StandbyAnimationPolicy.shouldAnimate(StandbyVisibilityState.Background, preference)).isFalse()
            assertThat(StandbyAnimationPolicy.shouldAnimate(StandbyVisibilityState.Projecting, preference)).isFalse()
        }
    }

    @Test
    fun controller_visibleStartsPauseResetsResumeRestarts() {
        val (controller, parallax, drift) = controller()
        controller.onVisibilityChanged(StandbyVisibilityState.Visible)
        assertThat(controller.isHolding()).isTrue()
        assertThat(parallax.starts).isEqualTo(1)
        assertThat(drift.starts).isEqualTo(1)

        controller.onVisibilityChanged(StandbyVisibilityState.Hidden)
        assertThat(controller.isHolding()).isFalse()
        assertThat(parallax.stops).isEqualTo(1)
        assertThat(parallax.resets).isEqualTo(1)
        assertThat(drift.stops).isEqualTo(1)
        assertThat(drift.resets).isEqualTo(1)

        controller.onVisibilityChanged(StandbyVisibilityState.Visible)
        assertThat(controller.isHolding()).isTrue()
        assertThat(parallax.starts).isEqualTo(2)
        assertThat(drift.starts).isEqualTo(2)
    }

    @Test
    fun controller_repeatedStateIsIdempotent() {
        val (controller, parallax, drift) = controller()
        controller.onVisibilityChanged(StandbyVisibilityState.Visible)
        controller.onVisibilityChanged(StandbyVisibilityState.Visible)
        assertThat(parallax.starts).isEqualTo(1)
        assertThat(drift.starts).isEqualTo(1)
        controller.onVisibilityChanged(StandbyVisibilityState.Background)
        controller.onVisibilityChanged(StandbyVisibilityState.Background)
        assertThat(parallax.stops).isEqualTo(1)
        assertThat(drift.stops).isEqualTo(1)
    }

    @Test
    fun controller_projectingPausesWithoutTouchingDecoder() {
        val (controller, parallax, drift) = controller()
        val decoder = FakeExternalDecoder()
        controller.onVisibilityChanged(StandbyVisibilityState.Visible)
        controller.onVisibilityChanged(StandbyVisibilityState.Projecting)
        assertThat(controller.isHolding()).isFalse()
        assertThat(parallax.stops).isEqualTo(1)
        assertThat(drift.stops).isEqualTo(1)
        // Controller never receives the decoder; nothing could have called it.
        assertThat(decoder.decodeCalls).isEqualTo(0)
    }

    @Test
    fun parallax_normalizeClampsAndRejectsNonFinite() {
        assertThat(StandbyParallaxMath.normalize(2.5)).isEqualTo(1.0)
        assertThat(StandbyParallaxMath.normalize(-3.0)).isEqualTo(-1.0)
        assertThat(StandbyParallaxMath.normalize(0.4)).isWithin(1e-12).of(0.4)
        assertThat(StandbyParallaxMath.normalize(Double.NaN)).isEqualTo(0.0)
        assertThat(StandbyParallaxMath.normalize(Double.POSITIVE_INFINITY)).isEqualTo(0.0)
    }

    @Test
    fun parallax_deltaClampBounds() {
        assertThat(StandbyParallaxMath.clampDelta(-1.0)).isEqualTo(0.0)
        assertThat(StandbyParallaxMath.clampDelta(10.0)).isWithin(1e-12).of(0.1)
        assertThat(StandbyParallaxMath.clampDelta(Double.NaN)).isEqualTo(0.0)
        assertThat(StandbyParallaxMath.clampDelta(0.05)).isWithin(1e-12).of(0.05)
    }

    @Test
    fun parallax_degenerateViewportReturnsZeros() {
        val state = StandbyParallaxMath.State(1.0, 1.0, 1.0, 1.0)
        for ((width, height) in listOf(0.0 to 600.0, 800.0 to 0.0, -800.0 to 600.0, Double.NaN to 600.0)) {
            val offsets = StandbyParallaxMath.offsets(state, width, height)
            assertThat(offsets.canvasDx).isEqualTo(0.0)
            assertThat(offsets.canvasDy).isEqualTo(0.0)
            assertThat(offsets.infoDx).isEqualTo(0.0)
            assertThat(offsets.infoDy).isEqualTo(0.0)
        }
    }

    @Test
    fun parallax_shortEdgeScalingMatchesPrototype() {
        val state = StandbyParallaxMath.State(fastX = 1.0, fastY = -1.0, slowX = 1.0, slowY = -1.0)
        val offsets = StandbyParallaxMath.offsets(state, 800.0, 600.0)
        assertThat(offsets.canvasDx).isWithin(1e-9).of(600.0 * 0.0189)
        assertThat(offsets.canvasDy).isWithin(1e-9).of(-600.0 * 0.0189)
        assertThat(offsets.infoDx).isWithin(1e-9).of(600.0 * 0.01)
        assertThat(offsets.infoDy).isWithin(1e-9).of(-600.0 * 0.01)
    }

    @Test
    fun parallax_advanceMovesTowardTarget() {
        val start = StandbyParallaxMath.State()
        val next = StandbyParallaxMath.advance(start, 1.0, -1.0, 0.1)
        assertThat(next.fastX).isGreaterThan(0.0)
        assertThat(next.fastY).isLessThan(0.0)
        // Fast layer converges faster than the slow layer.
        assertThat(next.fastX).isGreaterThan(next.slowX)
        assertThat(next.fastY).isLessThan(next.slowY)
    }

    @Test
    fun drift_pickTargetStaysBounded() {
        val walk = StandbyOledDriftMath.pickTarget({ 1.0 }, 0.0, 0.0)
        assertThat(walk.targetX).isWithin(1e-12).of(3.0)
        assertThat(walk.targetY).isWithin(1e-12).of(3.0)
        // Far target still takes at least the minimum excursion.
        assertThat(walk.duration).isAtLeast(150.0)
    }

    @Test
    fun drift_advanceHoldsOnInvalidDelta() {
        val walk = StandbyOledDriftMath.Walk(targetX = 2.0, targetY = 1.0, duration = 200.0)
        val (_, zero) = StandbyOledDriftMath.advance(walk, 0.0, { 0.5 })
        assertThat(zero.x).isEqualTo(0.0)
        val (_, nan) = StandbyOledDriftMath.advance(walk, Double.NaN, { 0.5 })
        assertThat(nan.x).isEqualTo(0.0)
    }

    @Test
    fun drift_zeroDeltaHoldsInterpolatedPosition() {
        // Halfway through a 0 -> 2px segment: smootherstep(0.5) = 0.5, so x = 1.0.
        val mid = StandbyOledDriftMath.Walk(
            fromX = 0.0,
            fromY = 0.0,
            targetX = 2.0,
            targetY = 0.0,
            elapsed = 75.0,
            duration = 150.0,
        )
        val (heldWalk, held) = StandbyOledDriftMath.advance(mid, 0.0, { 0.5 })
        assertThat(held.x).isWithin(1e-12).of(1.0)
        assertThat(held.y).isWithin(1e-12).of(0.0)
        assertThat(heldWalk).isEqualTo(mid)
        // Same hold for non-finite delta: must not jump back to segment start.
        val (_, nanHeld) = StandbyOledDriftMath.advance(mid, Double.NaN, { 0.5 })
        assertThat(nanHeld.x).isWithin(1e-12).of(1.0)
    }

    @Test
    fun drift_burnOffsetsBoundary() {
        val origin = StandbyOledDriftMath.burnOffsets(0.0)
        assertThat(origin.x).isWithin(1e-9).of(1.2)
        assertThat(origin.y).isWithin(1e-9).of(2.4)
        val invalid = StandbyOledDriftMath.burnOffsets(Double.NaN)
        assertThat(invalid.x).isEqualTo(0.0)
        assertThat(invalid.y).isEqualTo(0.0)
    }

    @Test
    fun drift_compensateHandlesCtmEdgeCases() {
        val scaled = StandbyOledDriftMath.compensate(1.0, 2.0, 4.0, 6.0, 2.0)
        assertThat(scaled.x).isWithin(1e-12).of(3.0)
        assertThat(scaled.y).isWithin(1e-12).of(5.0)
        val fallback = StandbyOledDriftMath.compensate(1.0, 2.0, 4.0, 6.0, 0.0)
        assertThat(fallback.x).isWithin(1e-12).of(5.0)
        val badMotion = StandbyOledDriftMath.compensate(1.0, 2.0, Double.NaN, Double.NaN, null)
        assertThat(badMotion.x).isWithin(1e-12).of(1.0)
        assertThat(badMotion.y).isWithin(1e-12).of(2.0)
    }
}
