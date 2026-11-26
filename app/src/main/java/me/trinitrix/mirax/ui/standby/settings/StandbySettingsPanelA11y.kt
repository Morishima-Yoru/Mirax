package me.trinitrix.mirax.ui.standby.settings

/**
 * Testable accessibility policy for [StandbySettingsPanelView].
 *
 * The Android view delegates selection description, touch target, and DPAD
 * chain setup here so JVM tests can verify behavior on fakes without
 * inflating real views. Every option keeps at least a 48dp touch target and
 * announces selection through `contentDescription`, never by color alone.
 */
object StandbySettingsPanelA11y {

    /**
     * Minimal injectable option control.
     *
     * The view adapts real `RadioButton`s to this seam; JVM tests use fakes.
     */
    interface OptionControl {
        /** Stable view id used for DPAD chain links. */
        val viewId: Int

        /** Checked state. */
        var checked: Boolean

        /** Visible label text. */
        var label: String

        /** TalkBack description; base text when unselected. */
        var description: String?

        /** Minimum width in pixels. */
        var minWidthPx: Int

        /** Minimum width floor in pixels. */
        var minimumWidthPx: Int

        /** Minimum height in pixels. */
        var minHeightPx: Int

        /** Minimum height floor in pixels. */
        var minimumHeightPx: Int

        /** DPAD up target. */
        var nextUp: Int

        /** DPAD down target. */
        var nextDown: Int

        /** DPAD left target. */
        var nextLeft: Int

        /** DPAD right target. */
        var nextRight: Int

        /** Forward traversal target. */
        var nextForward: Int

        /** Whether the option is focusable in touch mode. Must be false for single-tap selection. */
        var focusableInTouchMode: Boolean
    }

    /**
     * Selection description with selected semantics.
     *
     * Selected controls append [suffix]; unselected controls return [base]
     * unchanged so no selected residue remains.
     *
     * @param base unselected base description.
     * @param suffix selected suffix from resources.
     * @param selected true when this option is selected.
     * @return description to expose to TalkBack.
     */
    fun selectedContentDescription(base: String, suffix: String, selected: Boolean): String {
        return if (selected) base + suffix else base
    }

    /**
     * Whether a touch target meets the minimum.
     *
     * @param widthPx measured width in pixels.
     * @param heightPx measured height in pixels.
     * @param minPx minimum side in pixels.
     * @return true when both sides meet [minPx].
     */
    fun isTouchTargetSatisfied(widthPx: Int, heightPx: Int, minPx: Int): Boolean {
        return widthPx >= minPx && heightPx >= minPx
    }

    /**
     * Applies selection state to a control.
     *
     * Updates checked state, visible label marker, and `contentDescription`
     * together so TalkBack always perceives the selected state.
     *
     * @param control target control.
     * @param base unselected base description.
     * @param suffix selected suffix from resources.
     * @param selected true when selected.
     */
    fun applySelection(control: OptionControl, base: String, suffix: String, selected: Boolean) {
        control.checked = selected
        val stripped = if (control.label.endsWith(suffix)) {
            control.label.dropLast(suffix.length)
        } else {
            control.label
        }
        control.label = if (selected) stripped + suffix else stripped
        control.description = selectedContentDescription(base, suffix, selected)
    }

    /**
     * Applies the 48dp touch target floor to a control.
     *
     * Sets both `minWidth`/`minHeight` and `minimumWidth`/`minimumHeight` so
     * padding cannot shrink the target below [minPx].
     *
     * @param control target control.
     * @param minPx minimum side in pixels.
     */
    fun applyTouchTarget(control: OptionControl, minPx: Int) {
        control.minWidthPx = minPx
        control.minimumWidthPx = minPx
        control.minHeightPx = minPx
        control.minimumHeightPx = minPx
    }

    /**
     * Configures touch mode focus. Disabling focusableInTouchMode ensures the first tap
     * immediately fires onClick / selection rather than taking focus without checking.
     *
     * @param control target control.
     * @param focusableInTouchMode false for instant selection on tap.
     */
    fun applyTouchModeFocus(control: OptionControl, focusableInTouchMode: Boolean = false) {
        control.focusableInTouchMode = focusableInTouchMode
    }

    /**
     * Applies a complete vertical DPAD chain.
     *
     * Order is theme options then language options. Up/down follow the list;
     * left mirrors up and right mirrors down; forward follows down. Ends are
     * clamped to themselves so focus never escapes the panel.
     *
     * @param controls controls in traversal order.
     */
    fun applyFocusChain(controls: List<OptionControl>) {
        controls.forEachIndexed { index, control ->
            val up = if (index > 0) controls[index - 1].viewId else control.viewId
            val down = if (index < controls.size - 1) controls[index + 1].viewId else control.viewId
            control.nextUp = up
            control.nextDown = down
            control.nextLeft = up
            control.nextRight = down
            control.nextForward = down
        }
    }
}
