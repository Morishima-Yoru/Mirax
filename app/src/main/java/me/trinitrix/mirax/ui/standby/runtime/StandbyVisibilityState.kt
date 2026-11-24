package me.trinitrix.mirax.ui.standby.runtime

/**
 * Lifecycle visibility for Big Standby ambient work.
 *
 * Maps the web prototype gate (`design/big-standby-prototype.html` lines
 * 1229-1231 `oledShiftActive`: not paused, not hidden, home is big, phase is
 * not projecting) onto a closed native set. Only [Visible] permits ambient
 * rendering, sensor parallax, and OLED drift; every other state releases
 * them (spec S36, issue 07).
 */
enum class StandbyVisibilityState {
    /** Foreground and observable; ambient work may run. */
    Visible,

    /** Covered or navigated away inside the app; pause everything. */
    Hidden,

    /** App backgrounded; pause everything. */
    Background,

    /** Projecting to an external display; standby pauses, decoding continues. */
    Projecting,
}
