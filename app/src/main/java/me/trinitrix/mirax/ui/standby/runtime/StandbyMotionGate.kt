package me.trinitrix.mirax.ui.standby.runtime

/**
 * Pure gate deciding whether ambient rendering and sensors may run.
 *
 * Ports the prototype rule (`design/big-standby-prototype.html` lines
 * 1229-1231: `oledShiftActive = !paused && !hidden && home == big &&
 * phase != projecting`) to a closed [StandbyVisibilityState]: only
 * [StandbyVisibilityState.Visible] runs. No Android APIs, no clock, no
 * settings access.
 */
object StandbyMotionGate {

    /**
     * Returns true only when ambient rendering may run.
     *
     * @param state current lifecycle visibility.
     * @return true for [StandbyVisibilityState.Visible], false otherwise.
     */
    fun shouldRunAmbient(state: StandbyVisibilityState): Boolean =
        state == StandbyVisibilityState.Visible

    /**
     * Returns true only when sensor parallax and OLED drift may stay active.
     *
     * Sensors are lifecycle-scoped to the rendered surface (spec S36), so
     * the answer is identical to [shouldRunAmbient].
     *
     * @param state current lifecycle visibility.
     * @return true for [StandbyVisibilityState.Visible], false otherwise.
     */
    fun shouldHoldSensors(state: StandbyVisibilityState): Boolean =
        state == StandbyVisibilityState.Visible
}
