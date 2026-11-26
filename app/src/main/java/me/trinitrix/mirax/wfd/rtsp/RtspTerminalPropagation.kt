package me.trinitrix.mirax.wfd.rtsp

/**
 * Resolves which terminal failure to forward when an RTSP sink run ends.
 *
 * Returns the session evidence only for genuine pre-play failure; success,
 * user stop, normal teardown, and any post-playback ending resolve to null
 * and must stay silent. Callers post a non-null result to the main thread.
 *
 * @param session Finished sink session holding terminal evidence.
 * @param reachedPlay True once PLAY was reached; suppresses any evidence.
 * @return Failure to forward, or null when nothing must be reported.
 */
fun terminalFailureToForward(
    session: RtspSinkSession,
    reachedPlay: Boolean,
): RtspFailure? {
    if (reachedPlay) return null
    return session.terminalFailure
}
