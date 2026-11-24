package me.trinitrix.mirax.ui.standby.foreground

import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase

/**
 * Host-owned inputs for all three standby foreground candidates.
 *
 * Every field is provable host truth: [phase] is the real [ScreenPhase],
 * [advertisingEnabled] is the real broadcast intent, [receiverName] is the
 * real effective broadcast name (blank when unknown), [clockText] is the
 * host-formatted `HH:mm` string, and [failureCause] is non-null only when
 * the host already projected `Shown` via `prePlayFailurePresentation`.
 * Nothing here invents broadcast, failure, or privilege facts.
 *
 * @param phase real screen phase.
 * @param advertisingEnabled real broadcast intent.
 * @param shouldOfferShizuku real Shizuku affordance flag from the snapshot.
 * @param receiverName real effective broadcast name, blank when unknown.
 * @param clockText host-formatted clock, blank to hide the clock line.
 * @param failureCause genuine cause when the host presentation is Shown, else null.
 * @param failureText host-localized FAILED line for [failureCause], empty when none.
 * @param copyMotionAllowed host-confirmed prototype copy-motion gate; false when unknown.
 * @param isProjecting host-provided projection state; null pauses motion until known.
 */
data class StandbyForegroundState(
    val phase: ScreenPhase,
    val advertisingEnabled: Boolean,
    val shouldOfferShizuku: Boolean = false,
    val receiverName: String = "",
    val clockText: String = "",
    val failureCause: PrePlayFailureCause? = null,
    val failureText: String = "",
    val copyMotionAllowed: Boolean = false,
    val isProjecting: Boolean? = null,
)
