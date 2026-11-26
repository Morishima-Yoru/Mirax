package me.trinitrix.mirax.ui.standby.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Exercises the actual queued-sample and frame state used by the Android source. */
class StandbyMotionStateTest {
    @Test
    fun sampleFromPreviousRegistrationCannotMoveReplacement() {
        val motion = StandbyMotionState { 0.5 }
        motion.start()
        val old = requireNotNull(motion.ticket())
        motion.acceptTilt(old, 1.0, 0.0)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isGreaterThan(0f)

        motion.stop()
        assertThat(motion.ticket()).isNull()
        assertThat(motion.advance(0.1, 800, 600)).isEqualTo(StandbyMotionOffsets(0f, 0f, 0f, 0f))
        motion.start()
        motion.acceptTilt(old, 1.0, 0.0)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isEqualTo(0f)

        motion.acceptTilt(requireNotNull(motion.ticket()), 1.0, 0.0)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isGreaterThan(0f)
    }

    @Test
    fun displayChangeInvalidatesQueuedSamplesWithoutStoppingFrames() {
        val motion = StandbyMotionState { 0.5 }
        motion.start()
        val old = requireNotNull(motion.ticket())
        motion.beginDisplayChange()
        assertThat(motion.ticket()).isNull()
        motion.endDisplayChange()
        motion.acceptTilt(old, 1.0, 0.0)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isEqualTo(0f)
        motion.acceptTilt(requireNotNull(motion.ticket()), -1.0, 0.0)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isLessThan(0f)
    }

    @Test
    fun staleResetCannotClearCurrentRegistration() {
        val motion = StandbyMotionState { 0.5 }
        motion.start()
        val stale = requireNotNull(motion.ticket())
        motion.stop()
        motion.start()
        motion.acceptTilt(requireNotNull(motion.ticket()), 1.0, 0.0)
        val beforeReset = motion.advance(0.1, 800, 600).canvasX
        motion.acceptReset(stale)
        assertThat(motion.advance(0.1, 800, 600).canvasX).isGreaterThan(beforeReset)
    }

    @Test
    fun oledUsesExistingWalkAtItsPrototypeCadence() {
        val motion = StandbyMotionState { 1.0 }
        motion.start()
        assertThat(motion.advance(0.05, 800, 600).infoX).isEqualTo(0f)
        assertThat(motion.advance(0.05, 800, 600).infoX).isGreaterThan(0f)
    }
}
