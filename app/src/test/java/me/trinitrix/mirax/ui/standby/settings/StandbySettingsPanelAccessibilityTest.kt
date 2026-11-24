package me.trinitrix.mirax.ui.standby.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM checks for panel accessibility behavior via the injectable seam.
 *
 * Each option must carry a content description resource and meet the minimum
 * touch target declared on the view; selection, touch target, and DPAD chain
 * behavior is verified on [StandbySettingsPanelA11y.OptionControl] fakes
 * without inflating Android views.
 */
class StandbySettingsPanelAccessibilityTest {

    private class FakeControl(override val viewId: Int) : StandbySettingsPanelA11y.OptionControl {
        override var checked: Boolean = false
        override var label: String = "label"
        override var description: String? = "base"
        override var minWidthPx: Int = 0
        override var minimumWidthPx: Int = 0
        override var minHeightPx: Int = 0
        override var minimumHeightPx: Int = 0
        override var nextUp: Int = viewId
        override var nextDown: Int = viewId
        override var nextLeft: Int = viewId
        override var nextRight: Int = viewId
        override var nextForward: Int = viewId
        override var focusableInTouchMode: Boolean = true
    }

    @Test
    fun everyOptionHasLabelAndDescription() {
        assertThat(StandbySettingsPanelView.OPTION_METADATA).hasSize(6)
        for (option in StandbySettingsPanelView.OPTION_METADATA) {
            assertThat(option.key).isNotEmpty()
            assertThat(option.labelRes).isNotEqualTo(0)
            assertThat(option.descriptionRes).isNotEqualTo(0)
            assertThat(option.descriptionRes).isNotEqualTo(option.labelRes)
        }
    }

    @Test
    fun everyOptionKeyIsCoveredOnce() {
        val keys = StandbySettingsPanelView.OPTION_METADATA.map { it.key }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys).containsExactly(
            "LANDSCAPE",
            "MATTER",
            "HARDLINE",
            StandbySettingsPanelView.FOLLOW_SYSTEM_KEY,
            "TRADITIONAL_CHINESE",
            "ENGLISH",
        )
    }

    @Test
    fun minimumTouchTargetMeetsGuidance() {
        assertThat(StandbySettingsPanelView.MIN_TOUCH_DP).isAtLeast(48f)
    }

    @Test
    fun traversalOrderMatchesOptionMetadata() {
        val expected = StandbySettingsPanelModel.THEME_ORDER.map { it.name } +
            listOf(
                StandbySettingsPanelView.FOLLOW_SYSTEM_KEY,
                "TRADITIONAL_CHINESE",
                "ENGLISH",
            )
        assertThat(StandbySettingsPanelView.OPTION_METADATA.map { it.key })
            .containsExactlyElementsIn(expected)
            .inOrder()
    }

    @Test
    fun selectionSyncsDescriptionWithSelectedSemantics() {
        val control = FakeControl(1)
        StandbySettingsPanelA11y.applySelection(control, "base", " (selected)", true)
        assertThat(control.checked).isTrue()
        assertThat(control.description).isEqualTo("base (selected)")
        assertThat(control.label).isEqualTo("label (selected)")
    }

    @Test
    fun deselectionLeavesNoSelectedResidue() {
        val control = FakeControl(1)
        control.label = "label (selected)"
        control.description = "base (selected)"
        control.checked = true
        StandbySettingsPanelA11y.applySelection(control, "base", " (selected)", false)
        assertThat(control.checked).isFalse()
        assertThat(control.description).isEqualTo("base")
        assertThat(control.label).isEqualTo("label")
    }

    @Test
    fun touchTargetSetsMinWidthAndMinHeight() {
        val control = FakeControl(1)
        StandbySettingsPanelA11y.applyTouchTarget(control, 96)
        assertThat(control.minWidthPx).isEqualTo(96)
        assertThat(control.minimumWidthPx).isEqualTo(96)
        assertThat(control.minHeightPx).isEqualTo(96)
        assertThat(control.minimumHeightPx).isEqualTo(96)
        assertThat(StandbySettingsPanelA11y.isTouchTargetSatisfied(96, 96, 96)).isTrue()
        assertThat(StandbySettingsPanelA11y.isTouchTargetSatisfied(95, 96, 96)).isFalse()
        assertThat(StandbySettingsPanelA11y.isTouchTargetSatisfied(96, 95, 96)).isFalse()
    }

    @Test
    fun focusChainFormsCompleteLoop() {
        val controls = listOf(FakeControl(1), FakeControl(2), FakeControl(3))
        StandbySettingsPanelA11y.applyFocusChain(controls)
        assertThat(controls[0].nextUp).isEqualTo(1)
        assertThat(controls[0].nextDown).isEqualTo(2)
        assertThat(controls[0].nextLeft).isEqualTo(1)
        assertThat(controls[0].nextRight).isEqualTo(2)
        assertThat(controls[0].nextForward).isEqualTo(2)
        assertThat(controls[1].nextUp).isEqualTo(1)
        assertThat(controls[1].nextDown).isEqualTo(3)
        assertThat(controls[1].nextLeft).isEqualTo(1)
        assertThat(controls[1].nextRight).isEqualTo(3)
        assertThat(controls[1].nextForward).isEqualTo(3)
        assertThat(controls[2].nextUp).isEqualTo(2)
        assertThat(controls[2].nextDown).isEqualTo(3)
        assertThat(controls[2].nextLeft).isEqualTo(2)
        assertThat(controls[2].nextRight).isEqualTo(3)
        assertThat(controls[2].nextForward).isEqualTo(3)
    }

    @Test
    fun touchModeFocusIsDisabledForSingleTapCheck() {
        val control = FakeControl(1).apply { focusableInTouchMode = true }
        StandbySettingsPanelA11y.applyTouchModeFocus(control, false)
        assertThat(control.focusableInTouchMode).isFalse()
    }
}
