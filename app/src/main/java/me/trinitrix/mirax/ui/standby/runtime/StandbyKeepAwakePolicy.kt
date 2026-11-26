package me.trinitrix.mirax.ui.standby.runtime

/**
 * Pure keep-awake policy for Big Standby (spec S34, issue 07).
 *
 * Foreground Big Standby holds keep-awake with no app-driven dimming call.
 * This policy only answers whether keep-awake must be held; it never touches
 * `WindowManager`, `Window`, brightness attributes, or system settings. The
 * (unwired) Android owner applies the answer.
 */
object StandbyKeepAwakePolicy {

    /**
     * Returns true only while Big Standby is foreground-visible.
     *
     * @param state current lifecycle visibility.
     * @return true for [StandbyVisibilityState.Visible], false otherwise.
     */
    fun shouldHoldKeepAwake(state: StandbyVisibilityState): Boolean =
        state == StandbyVisibilityState.Visible

    /**
     * App-driven dimming is never allowed.
     *
     * @return always false; the app must not dim or adjust brightness.
     */
    fun allowsAppDimming(): Boolean = false

    /**
     * Brightness writes are never allowed.
     *
     * @return always false; no brightness value is ever produced.
     */
    fun allowsBrightnessWrite(): Boolean = false
}
