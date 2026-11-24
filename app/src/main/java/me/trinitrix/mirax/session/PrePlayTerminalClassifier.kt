package me.trinitrix.mirax.session

import me.trinitrix.mirax.wfd.rtsp.RtspFailure

/**
 * Classifies a genuine pre-play terminal outcome from RTSP sink session evidence.
 *
 * Only outcomes that can be proven from existing [RtspFailure] evidence produce
 * a [PrePlayTerminalOutcome.GenuineFailure]:
 * - [RtspFailure.Timeout] maps to [PrePlayFailureCause.PRE_PLAY_TIMEOUT].
 * - [RtspFailure.Rejected] maps to [PrePlayFailureCause.ESTABLISHMENT_FAILED].
 *
 * All other paths (pairing timeout, negotiation failure without strict
 * evidence, user cancel, group-loss, wifi, frozen, malformed responses, or a
 * null/absent failure) return null and must never open a FAILED episode.
 * Callers must also ignore any evidence once playback has started.
 *
 * This is a pure function with no side effects, no platform dependencies, and
 * no state. It exists to enforce the "provable or nothing" boundary.
 *
 * @param attemptId Attempt this outcome belongs to.
 * @param rtspFailure Terminal evidence from the sink session, or null.
 * @return Genuine failure outcome, or null when unproven.
 */
fun terminalOutcome(
    attemptId: PrePlayAttemptId,
    rtspFailure: RtspFailure?,
): PrePlayTerminalOutcome? {
    return when (rtspFailure) {
        is RtspFailure.Timeout -> {
            PrePlayTerminalOutcome.GenuineFailure(attemptId, PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        }
        is RtspFailure.Rejected -> {
            PrePlayTerminalOutcome.GenuineFailure(attemptId, PrePlayFailureCause.ESTABLISHMENT_FAILED)
        }
        else -> null
    }
}

/** Evidence that TCP connect timed out until the establishment window expired. */
internal data object TcpEstablishmentTimeout {
    val cause: PrePlayFailureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT
}

/**
 * Classifies a proven TCP establishment timeout without inventing RTSP evidence.
 *
 * @param attemptId Attempt whose current TCP dial window expired.
 * @param evidence Evidence produced by the dial window.
 * @return Genuine timeout for the originating attempt.
 */
internal fun tcpEstablishmentTimeoutOutcome(
    attemptId: PrePlayAttemptId,
    evidence: TcpEstablishmentTimeout,
): PrePlayTerminalOutcome.GenuineFailure = PrePlayTerminalOutcome.GenuineFailure(attemptId, evidence.cause)

/**
 * Classifies a non-failure terminal outcome that must never open an episode.
 *
 * Pure function for callers that already determined the ending is not a
 * genuine failure. Returns the outcome so the attempt is consumed and a later
 * duplicate cannot revive an episode.
 *
 * @param attemptId Attempt this outcome belongs to.
 * @param reason Explicit non-failure classification.
 * @return Non-failure outcome for attempt consumption.
 */
fun nonFailureOutcome(
    attemptId: PrePlayAttemptId,
    reason: PrePlayNonFailureReason,
): PrePlayTerminalOutcome.NonFailure {
    return PrePlayTerminalOutcome.NonFailure(attemptId, reason)
}
