package me.trinitrix.mirax.ui.standby.runtime

/**
 * System animation preference delivered to Big Standby.
 *
 * The OS value is never read here; the Android owner passes it in so this
 * policy stays pure and testable with fakes (spec S35, issue 07).
 */
enum class StandbyAnimationPreference {
    /** Full motion requested. */
    FULL,

    /** Reduced motion requested; ambient keeps moving. */
    REDUCED,

    /** Motion disabled requested; ambient keeps moving. */
    DISABLED,
}

/**
 * Pure policy: ambient motion ignores animation preferences.
 *
 * Prototype reference: `design/big-standby-prototype.html` lines 1229-1234
 * (`oledShiftActive` and `effectiveMotion` consult paused, hidden, home,
 * and phase only, never an animation-preference kill switch). Nothing here
 * reads or writes any system setting.
 */
object StandbyAnimationPolicy {

    /**
     * Returns true when ambient may animate; the preference never disables it.
     *
     * @param state current lifecycle visibility.
     * @param preference injected OS preference, ignored by design.
     * @return true only for [StandbyVisibilityState.Visible].
     */
    fun shouldAnimate(
        state: StandbyVisibilityState,
        preference: StandbyAnimationPreference,
    ): Boolean = StandbyMotionGate.shouldRunAmbient(state)
}
