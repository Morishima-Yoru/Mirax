package me.trinitrix.mirax.ui.standby.media

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.data.standby.StandbyFraming
import org.junit.Test

/**
 * Input safety for framing pan with non-finite deltas.
 *
 * A non-finite delta on either axis ignores the gesture without starting a
 * preview; committed and in-progress preview state stay untouched.
 */
class StandbyFramingControlsModelTest {

    @Test
    fun panNonFiniteWithoutPreviewKeepsCommitted() {
        val model = StandbyFramingControlsModel()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(model.pan(bad, 0.1f)).isEqualTo(StandbyFraming.CENTERED)
            assertThat(model.pan(0.1f, bad)).isEqualTo(StandbyFraming.CENTERED)
            assertThat(model.pan(bad, bad)).isEqualTo(StandbyFraming.CENTERED)
        }
        assertThat(model.isPreviewing).isFalse()
        assertThat(model.committed).isEqualTo(StandbyFraming.CENTERED)
        assertThat(model.visible()).isEqualTo(StandbyFraming.CENTERED)
    }

    @Test
    fun panNonFiniteDuringPreviewKeepsDraft() {
        val model = StandbyFramingControlsModel()
        model.pan(0.1f, -0.1f)
        val draft = model.visible()
        assertThat(model.isPreviewing).isTrue()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(model.pan(bad, 0.1f)).isEqualTo(draft)
            assertThat(model.pan(0.1f, bad)).isEqualTo(draft)
        }
        assertThat(model.isPreviewing).isTrue()
        assertThat(model.visible()).isEqualTo(draft)
        assertThat(model.committed).isEqualTo(StandbyFraming.CENTERED)
    }

    @Test
    fun panFiniteClampConfirmCancelUnchanged() {
        val model = StandbyFramingControlsModel()
        val moved = model.pan(10f, -10f)
        assertThat(moved.centerX).isWithin(1e-6f).of(0.75f)
        assertThat(moved.centerY).isWithin(1e-6f).of(0.25f)
        val confirmed = model.confirm()
        assertThat(model.committed).isEqualTo(confirmed)
        assertThat(model.isPreviewing).isFalse()

        val baseline = StandbyFraming(0.4f, 0.6f, 1.5f)
        val second = StandbyFramingControlsModel(baseline)
        second.pan(0.2f, 0.2f)
        assertThat(second.cancel()).isEqualTo(baseline)
        assertThat(second.isPreviewing).isFalse()
    }
}
