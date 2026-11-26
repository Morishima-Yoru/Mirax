package me.trinitrix.mirax.session

/**
 * Identity for one pre-play connection attempt.
 *
 * IDs are produced by a process-wide producer as positive, strictly increasing
 * values. They distinguish attempts even when they reuse the same peer group,
 * so stale timers and outcomes carrying an older identity can never overwrite
 * newer reality.
 *
 * @property value Positive increasing token value.
 */
@JvmInline
value class PrePlayAttemptId(val value: Long)

/**
 * Genuine pre-play establishment or negotiation failure causes.
 *
 * Only these known causes can open a FAILED episode.
 */
enum class PrePlayFailureCause {
    ESTABLISHMENT_FAILED,
    NEGOTIATION_FAILED,
    PRE_PLAY_TIMEOUT,
}

/**
 * Classified non-failure endings. None of these ever opens a FAILED episode.
 */
enum class PrePlayNonFailureReason {
    NORMAL_TEARDOWN,
    USER_CANCELLED,
    WIFI_LOST,
    RECEIVER_UNAVAILABLE,
    UNKNOWN_DROP,
    POST_PLAY_ENDED,
}

/**
 * External truth changes that immediately supersede a FAILED episode.
 */
enum class PrePlayPreemptReason {
    PROJECTION_STARTED,
    EXPLICIT_STOP,
    WIFI_PAUSED,
    FROZEN_STATE,
}

/**
 * Terminal outcome of one pre-play attempt, keyed by attempt identity.
 *
 * Only [GenuineFailure] can open an episode. [NonFailure] consumes the attempt
 * without opening anything, so a later duplicate cannot revive.
 */
sealed interface PrePlayTerminalOutcome {
    /** Attempt this outcome belongs to. */
    val attemptId: PrePlayAttemptId

    /**
     * Genuine establishment or negotiation failure before playback.
     *
     * @property attemptId Attempt this failure belongs to.
     * @property cause Known failure cause.
     */
    data class GenuineFailure(
        override val attemptId: PrePlayAttemptId,
        val cause: PrePlayFailureCause,
    ) : PrePlayTerminalOutcome

    /**
     * Classified ending that must never open an episode.
     *
     * @property attemptId Attempt this outcome belongs to.
     * @property reason Explicit non-failure classification.
     */
    data class NonFailure(
        override val attemptId: PrePlayAttemptId,
        val reason: PrePlayNonFailureReason,
    ) : PrePlayTerminalOutcome
}

/**
 * Read-only observable FAILED episode.
 *
 * @property attemptId Attempt this episode belongs to.
 * @property cause Genuine failure cause.
 * @property remainingMs Visible milliseconds left before expiry.
 */
data class PrePlayFailureSnapshot(
    val attemptId: PrePlayAttemptId,
    val cause: PrePlayFailureCause,
    val remainingMs: Long,
)

/**
 * Pure presentational controller for the pre-play FAILED episode.
 *
 * Holds no platform objects, issues no outside commands, and performs no
 * persistence. The owner must keep one instance in process scope (not in an
 * Activity) so configuration recreation cannot replay an episode. Feed actual
 * foreground visibility via [onVisibilityChanged]; the 7.5s budget accrues
 * only while visible. Expiry and dismissal surface as null snapshots; the
 * caller keeps owning the real underlying phase.
 *
 * All methods must be called sequentially by a single serial owner (in
 * production, the session main thread). This class performs no locking and
 * references no platform message loop.
 *
 * Time comes from [nowMs] in milliseconds. The caller must supply a monotonic
 * source. A negative delta observed between two reads is clamped to zero at
 * that observation; a non-monotonic clock is otherwise not guaranteed correct,
 * and unobserved time can never be recovered.
 *
 * @param nowMs Injectable monotonic clock in milliseconds.
 */
class PrePlayFailureEpisode(
    private val nowMs: () -> Long,
) {
    companion object {
        /** Visible foreground budget in milliseconds. */
        const val VISIBLE_BUDGET_MS: Long = 7_500L
    }

    private var maxSeenAttemptValue: Long = 0L
    private var currentAttempt: PrePlayAttemptId? = null
    private var terminalSeenForCurrent: Boolean = false
    private var episodeAttempt: PrePlayAttemptId? = null
    private var episodeCause: PrePlayFailureCause? = null
    private var accruedVisibleMs: Long = 0L
    private var visibleSinceMs: Long? = null
    private var bigStandbyVisible: Boolean = false

    /**
     * Registers a new attempt produced by the process-wide producer.
     *
     * Accepts only positive IDs strictly greater than the highest ID seen so
     * far. A stale, duplicate, zero, or negative ID is rejected and leaves the
     * current episode, timers, and attempt untouched. An accepted ID clears old
     * episodes, timers, and outcomes, even when the previous attempt never
     * opened an episode.
     *
     * @param attemptId Producer-assigned attempt identity.
     * @return True when accepted as the new current attempt.
     */
    fun beginAttempt(attemptId: PrePlayAttemptId): Boolean {
        if (attemptId.value <= 0L) {
            return false
        }
        if (attemptId.value <= maxSeenAttemptValue) {
            return false
        }
        maxSeenAttemptValue = attemptId.value
        currentAttempt = attemptId
        episodeAttempt = null
        episodeCause = null
        accruedVisibleMs = 0L
        visibleSinceMs = null
        terminalSeenForCurrent = false
        return true
    }

    /**
     * Reports one terminal outcome for an attempt.
     *
     * Only a [PrePlayTerminalOutcome.GenuineFailure] whose identity equals the
     * current attempt, arriving before any other terminal for that attempt,
     * opens an episode. Outcomes with no active attempt, with a stale identity,
     * or arriving after the first terminal are ignored. A duplicate terminal
     * for the same attempt can never revive or reset.
     *
     * @param outcome Typed terminal outcome keyed by attempt identity.
     * @return True when a new episode opened, false otherwise.
     */
    fun onTerminalOutcome(outcome: PrePlayTerminalOutcome): Boolean {
        val current = currentAttempt ?: return false
        if (outcome.attemptId != current) {
            return false
        }
        if (terminalSeenForCurrent) {
            return false
        }
        terminalSeenForCurrent = true
        val genuine = outcome as? PrePlayTerminalOutcome.GenuineFailure ?: return false
        episodeAttempt = genuine.attemptId
        episodeCause = genuine.cause
        accruedVisibleMs = 0L
        visibleSinceMs = if (bigStandbyVisible) {
            nowMs()
        } else {
            null
        }
        return true
    }

    /**
     * Reports whether the standby surface is actually visible in the foreground.
     *
     * Each visible to hidden transition accrues time; hidden time never counts.
     * Repeated reports of the same value are idempotent.
     *
     * @param visible True while the standby surface is actually visible.
     */
    fun onVisibilityChanged(visible: Boolean) {
        if (visible == bigStandbyVisible) {
            return
        }
        val now = nowMs()
        if (bigStandbyVisible && !visible) {
            val start = visibleSinceMs
            if (start != null) {
                accruedVisibleMs += (now - start).coerceAtLeast(0L)
            }
            visibleSinceMs = null
            bigStandbyVisible = false
        } else {
            bigStandbyVisible = true
            if (episodeAttempt != null && visibleSinceMs == null) {
                visibleSinceMs = now
            }
        }
    }

    /**
     * Returns the current episode, or null when absent or expired.
     *
     * Returning null is the expiry signal; this controller never forces a
     * phase value. This read does not accrue or clear timers by itself.
     *
     * @return Snapshot with cause and remaining time, or null.
     */
    fun snapshot(): PrePlayFailureSnapshot? {
        val attempt = episodeAttempt ?: return null
        val cause = episodeCause ?: return null
        val remaining = remainingMs()
        if (remaining <= 0L) {
            return null
        }
        return PrePlayFailureSnapshot(
            attemptId = attempt,
            cause = cause,
            remainingMs = remaining,
        )
    }

    /**
     * Dismisses the episode carrying [attemptId].
     *
     * A stale identity never removes a newer episode.
     *
     * @param attemptId Episode identity to dismiss.
     * @return True when the matching episode was cleared.
     */
    fun dismiss(attemptId: PrePlayAttemptId): Boolean {
        val attempt = episodeAttempt ?: return false
        if (attempt != attemptId) {
            return false
        }
        episodeAttempt = null
        episodeCause = null
        accruedVisibleMs = 0L
        visibleSinceMs = null
        return true
    }

    /**
     * Expires the episode when its visible budget is exhausted.
     *
     * @return Expired attempt identity, or null when nothing expired.
     */
    fun expireIfDue(): PrePlayAttemptId? {
        val attempt = episodeAttempt ?: return null
        if (remainingMs() > 0L) {
            return null
        }
        episodeAttempt = null
        episodeCause = null
        accruedVisibleMs = 0L
        visibleSinceMs = null
        return attempt
    }

    /**
     * Immediately supersedes the current episode for an external truth change.
     *
     * Also consumes the current attempt so its late outcomes cannot open a
     * replacement episode.
     *
     * @param reason External truth change that supersedes the episode.
     * @return True when an episode was cleared.
     */
    fun preempt(reason: PrePlayPreemptReason): Boolean {
        val hadEpisode = episodeAttempt != null
        episodeAttempt = null
        episodeCause = null
        accruedVisibleMs = 0L
        visibleSinceMs = null
        if (currentAttempt != null) {
            terminalSeenForCurrent = true
        }
        return hadEpisode
    }

    private fun remainingMs(): Long {
        val start = visibleSinceMs
        val liveMs = if (bigStandbyVisible && start != null) {
            (nowMs() - start).coerceAtLeast(0L)
        } else {
            0L
        }
        return (VISIBLE_BUDGET_MS - accruedVisibleMs - liveMs).coerceAtLeast(0L)
    }
}
