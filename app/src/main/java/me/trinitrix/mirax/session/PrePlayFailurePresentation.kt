package me.trinitrix.mirax.session

/**
 * Pure FAILED visibility projection for JVM testing.
 *
 * Maps the process episode snapshot plus caller-owned truth to a single
 * presentational outcome. Holds no Android, clock, or owner state.
 *
 * Expiry surfaces as a null [episode]; the caller keeps owning the clock.
 */
sealed interface PrePlayFailurePresentation {
    /** Nothing may show now. */
    data object Hidden : PrePlayFailurePresentation

    /**
     * FAILED line may show for a provable cause.
     *
     * @property cause Genuine failure cause; text mapping stays in the Activity.
     */
    data class Shown(val cause: PrePlayFailureCause) : PrePlayFailurePresentation
}

/**
 * Projects whether the FAILED presentation may show now.
 *
 * FAILED is an overlay episode layered over the existing phases: the natural
 * return to the underlying phase after [SessionAction.PrePlayGroupDropped]
 * (ADVERTISING, ARMING, or READY) keeps a visible episode on screen. READY
 * alone never proves an explicit stop, so a live episode stays visible there.
 * Only phases that prove a superseding visual truth hide here: CONNECTED
 * (projection started), WIFI_PAUSED, and FROZEN. Explicit stop is an event
 * consumed by the owner and surfaces as a null [episode], never as a READY
 * phase gate. Owner-side preemption (new attempt, explicit stop, dismiss,
 * expiry) surfaces as a null [episode].
 *
 * @param episode Current episode snapshot, or null when none or expired.
 * @param latestAttempt Latest pre-play attempt identity, or null before first entry.
 * @param standbyForeground True only while Big Standby is visibly foreground.
 * @param phase Current screen phase; superseding phases hide, underlying phases keep.
 * @return [PrePlayFailurePresentation.Shown] only when every gate passes.
 */
fun prePlayFailurePresentation(
    episode: PrePlayFailureSnapshot?,
    latestAttempt: PrePlayAttemptId?,
    standbyForeground: Boolean,
    phase: ScreenPhase,
): PrePlayFailurePresentation {
    if (episode == null) return PrePlayFailurePresentation.Hidden
    if (!standbyForeground) return PrePlayFailurePresentation.Hidden
    if (episode.attemptId != latestAttempt) return PrePlayFailurePresentation.Hidden
    // shortcut: superseding visual truth replaced the episode, drop it here.
    // Natural group-drop returns (ADVERTISING/ARMING/READY) keep the overlay
    // visible. READY alone never proves an explicit stop event.
    if (phase == ScreenPhase.CONNECTED ||
        phase == ScreenPhase.WIFI_PAUSED ||
        phase == ScreenPhase.FROZEN
    ) {
        return PrePlayFailurePresentation.Hidden
    }
    return PrePlayFailurePresentation.Shown(episode.cause)
}
