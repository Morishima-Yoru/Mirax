package me.trinitrix.mirax.ui.standby.framing

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import org.junit.Test

/** Regression vectors for the production editor gestures and commit seam. */
class StandbyFramingEditorGestureTest {
    @Test
    fun panUsesSourceWindowForWideAndTallMedia() {
        val wide = StandbyFramingEditorGesture(StandbyFramingControlsModel())
        val wideMoved = requireNotNull(wide.pan(10f, 10f, 1600f, 900f, 100f, 100f))
        assertThat(wideMoved.centerX).isWithin(0.00001f).of(0.44375f)
        assertThat(wideMoved.centerY).isWithin(0.00001f).of(0.5f)
        val wideBack = requireNotNull(wide.pan(-10f, -10f, 1600f, 900f, 100f, 100f))
        assertThat(wideBack.centerX).isWithin(0.00001f).of(0.5f)

        val tall = StandbyFramingEditorGesture(StandbyFramingControlsModel())
        val tallMoved = requireNotNull(tall.pan(10f, 10f, 900f, 1600f, 100f, 100f))
        assertThat(tallMoved.centerX).isWithin(0.00001f).of(0.5f)
        assertThat(tallMoved.centerY).isWithin(0.00001f).of(0.44375f)
        val tallBack = requireNotNull(tall.pan(-10f, -10f, 900f, 1600f, 100f, 100f))
        assertThat(tallBack.centerY).isWithin(0.00001f).of(0.5f)
    }

    @Test
    fun panUsesViewportAspectAndZoom() {
        val wideView = StandbyFramingEditorGesture(StandbyFramingControlsModel())
        val wide = requireNotNull(wideView.pan(10f, 10f, 1000f, 1000f, 200f, 100f))
        assertThat(wide.centerX).isWithin(0.00001f).of(0.5f)
        assertThat(wide.centerY).isWithin(0.00001f).of(0.45f)
        val wideBack = requireNotNull(wideView.pan(-10f, -10f, 1000f, 1000f, 200f, 100f))
        assertThat(wideBack.centerY).isWithin(0.00001f).of(0.5f)

        val tallView = StandbyFramingEditorGesture(StandbyFramingControlsModel())
        val tall = requireNotNull(tallView.pan(10f, 10f, 1000f, 1000f, 100f, 200f))
        assertThat(tall.centerX).isWithin(0.00001f).of(0.45f)
        assertThat(tall.centerY).isWithin(0.00001f).of(0.5f)
        val tallBack = requireNotNull(tallView.pan(-10f, -10f, 1000f, 1000f, 100f, 200f))
        assertThat(tallBack.centerX).isWithin(0.00001f).of(0.5f)

        val zoomed = StandbyFramingEditorGesture(
            StandbyFramingControlsModel(StandbyFraming(0.5f, 0.5f, 2f)),
        )
        val moved = requireNotNull(zoomed.pan(10f, 0f, 1600f, 900f, 100f, 100f))
        assertThat(moved.centerX).isWithin(0.00001f).of(0.471875f)
    }

    @Test
    fun panLargeDragAndReverseAtEdgeRemainResponsive() {
        val gesture = StandbyFramingEditorGesture(
            StandbyFramingControlsModel(StandbyFraming(0.5f, 0.5f, 4f)),
        )
        val edge = requireNotNull(gesture.pan(200f, 0f, 1000f, 1000f, 100f, 100f))
        assertThat(edge.centerX).isWithin(0.00001f).of(0.125f)
        assertThat(gesture.pan(10f, 0f, 1000f, 1000f, 100f, 100f)).isNull()
        val reversed = requireNotNull(gesture.pan(-10f, 0f, 1000f, 1000f, 100f, 100f))
        assertThat(reversed.centerX).isWithin(0.00001f).of(0.15f)
    }

    @Test
    fun invalidGeometryAndDeltasNeverStartOrChangeDraft() {
        val model = StandbyFramingControlsModel()
        val gesture = StandbyFramingEditorGesture(model)
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(gesture.pan(1f, 1f, invalid, 100f, 100f, 100f)).isNull()
            assertThat(gesture.pan(1f, 1f, 100f, invalid, 100f, 100f)).isNull()
            assertThat(gesture.pan(1f, 1f, 100f, 100f, invalid, 100f)).isNull()
            assertThat(gesture.pan(1f, 1f, 100f, 100f, 100f, invalid)).isNull()
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(gesture.pan(invalid, 1f, 100f, 100f, 100f, 100f)).isNull()
            assertThat(gesture.pan(1f, invalid, 100f, 100f, 100f, 100f)).isNull()
            assertThat(gesture.zoom(invalid)).isNull()
        }
        assertThat(model.isPreviewing).isFalse()
        assertThat(model.visible()).isEqualTo(StandbyFraming.CENTERED)
        requireNotNull(gesture.zoom(2f))
        val draft = model.visible()
        assertThat(gesture.pan(1f, 1f, 0f, 100f, 100f, 100f)).isNull()
        assertThat(gesture.zoom(Float.NaN)).isNull()
        assertThat(model.visible()).isEqualTo(draft)
    }

    @Test
    fun zoomClampsAndCancelRestoresBaselineOnce() {
        val baseline = StandbyFraming(0.4f, 0.6f, 1.5f)
        val model = StandbyFramingControlsModel(baseline)
        val gesture = StandbyFramingEditorGesture(model)
        assertThat(requireNotNull(gesture.zoom(100f)).zoom).isEqualTo(4f)
        assertThat(requireNotNull(gesture.zoom(-1f)).zoom).isEqualTo(1f)
        val restored = mutableListOf<StandbyFraming>()
        assertThat(gesture.cancel { restored.add(it) }).isTrue()
        assertThat(gesture.cancel { restored.add(it) }).isFalse()
        assertThat(restored).containsExactly(baseline)
        assertThat(model.visible()).isEqualTo(baseline)
        assertThat(model.committed).isEqualTo(baseline)
    }

    @Test
    fun rapidConfirmDoesNotCommitAndFailureAllowsRetry() {
        val model = StandbyFramingControlsModel()
        val gesture = StandbyFramingEditorGesture(model)
        val draft = requireNotNull(gesture.zoom(2f))
        val requests = mutableListOf<StandbyFraming>()
        assertThat(gesture.requestCommit { requests.add(it) }).isTrue()
        assertThat(gesture.requestCommit { requests.add(it) }).isFalse()
        assertThat(gesture.cancel { error("busy cancel must not run") }).isFalse()
        assertThat(gesture.zoom(3f)).isNull()
        assertThat(requests).containsExactly(draft)
        assertThat(model.committed).isEqualTo(StandbyFraming.CENTERED)
        assertThat(model.visible()).isEqualTo(draft)
        assertThat(gesture.failCommit()).isTrue()
        assertThat(model.visible()).isEqualTo(draft)
        assertThat(gesture.requestCommit { requests.add(it) }).isTrue()
        assertThat(model.committed).isEqualTo(StandbyFraming.CENTERED)
        assertThat(gesture.completeCommit()).isTrue()
        assertThat(model.committed).isEqualTo(draft)
        assertThat(gesture.isConfirmed).isTrue()
        assertThat(gesture.completeCommit()).isFalse()
        assertThat(requests).containsExactly(draft, draft)
    }

    @Test
    fun recreatedEditorRemainsBusyUntilHostReportsResult() {
        val model = StandbyFramingControlsModel()
        val original = StandbyFramingEditorGesture(model)
        val draft = requireNotNull(original.zoom(2f))
        assertThat(original.requestCommit { }).isTrue()
        val recreated = StandbyFramingEditorGesture(model, commitPending = true)
        assertThat(recreated.visible).isEqualTo(draft)
        assertThat(recreated.requestCommit { error("duplicate request") }).isFalse()
        assertThat(recreated.cancel { error("pending transaction") }).isFalse()
        assertThat(recreated.completeCommit()).isTrue()
        assertThat(model.committed).isEqualTo(draft)
    }
}
