package me.trinitrix.mirax.ui.standby.foreground

/**
 * Explicit host callback contract for standby foreground controls.
 *
 * A foreground candidate never toggles broadcast, dismisses FAILED, opens
 * settings, or opens Shizuku itself. When the host has no wiring for an
 * action it passes `null`; the candidate then hides that control instead of
 * rendering a dead button.
 */
data class StandbyForegroundCallbacks(
    /** Toggle broadcast intent; null hides the broadcast control. */
    val onToggleBroadcast: (() -> Unit)? = null,
    /** Dismiss the visible FAILED episode; null hides dismiss. */
    val onDismissFailure: (() -> Unit)? = null,
    /** Open Shizuku authorization; null hides the Shizuku affordance. */
    val onOpenShizuku: (() -> Unit)? = null,
    /** Show the small truthful FROZEN dialog; null hides the info control. */
    val onShowFrozenInfo: (() -> Unit)? = null,
    /** Open standby settings; null hides the menu control. */
    val onOpenSettings: (() -> Unit)? = null,
    /** Stop broadcast intent while Wi-Fi is paused; null hides the stop control. */
    val onStopBroadcastWhilePaused: (() -> Unit)? = null,
)
