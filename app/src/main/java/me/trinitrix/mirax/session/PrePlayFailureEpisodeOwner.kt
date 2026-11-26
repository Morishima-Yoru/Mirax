package me.trinitrix.mirax.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.trinitrix.mirax.wfd.rtsp.RtspFailure

/**
 * Lifecycle-scoped message queue used to post and remove delayed expiry
 * checks. The production implementation wraps a single persistent main
 * [Handler]; tests inject recording fakes. Post and removal always target
 * the same queue instance holding the posted task, so hiding, rescheduling,
 * preemption, and explicit stop can never strand a runnable on another
 * target.
 */
internal interface ExpiryMessageQueue {
    /**
     * Posts [task] after [delayMs], replacing any earlier post of [task].
     *
     * @return True when the post was accepted.
     */
    fun postDelayed(delayMs: Long, task: Runnable): Boolean

    /**
     * Removes a previously posted [task] from this same queue.
     */
    fun removeCallbacks(task: Runnable)
}

/**
 * Production [ExpiryMessageQueue] bound to one [Handler] for its lifetime.
 *
 * @param handler Persistent main-thread handler owning post and removal.
 */
internal class MainHandlerExpiryQueue(
    private val handler: Handler,
) : ExpiryMessageQueue {
    override fun postDelayed(delayMs: Long, task: Runnable): Boolean {
        handler.removeCallbacks(task)
        return handler.postDelayed(task, delayMs)
    }

    override fun removeCallbacks(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/**
 * Process-scoped owner of the pre-play FAILED episode.
 *
 * Holds a single [PrePlayEpisode] instance for the entire process lifetime.
 * Manages the 7.5s visible accrual, preemption by new attempts, and expiry.
 * Does not perform any navigation, broadcast changes, or idle clock modifications.
 *
 * All mutating calls must run on the same serial owner thread. In production
 * that is the main thread: the default [isOwnerThread] strictly checks
 * `Looper.getMainLooper().isCurrentThread` with no fallback, so off-thread
 * calls are rejected and unit-test construction without a Looper never
 * crashes (mutations are simply rejected until fakes are injected).
 * Timer scheduling defaults to a single persistent main-thread queue
 * created once per owner (or per process singleton), so every post and
 * removal targets the same [Handler]; hiding, rescheduling, preemption, and
 * explicit stop remove the exact pending runnable and can never strand a
 * callback on another target. Each posted check carries the scheduling
 * generation, so a stale runnable can never touch a newer episode. The
 * default clock is the monotonic
 * [SystemClock.elapsedRealtime], never wall time, so device clock corrections
 * cannot stretch or shrink the visible budget.
 *
 * Host JVM tests inject [nowMs], [messageQueue], and [isOwnerThread] fakes
 * to drive expiry deterministically; legacy [scheduler]/[canceller] fakes
 * keep working by adapting onto a recording queue.
 *
 * State changes surface through an optional episode listener invoked
 * synchronously on the owner thread with the new observable snapshot (null
 * when no episode is active). Notifications are keyed on the
 * (attempt, cause) pair, so remaining-time ticks never re-fire; expiry
 * notifies null exactly once. The listener must be cheap, must never invoke
 * owner mutations, must never hold an Activity, and must never run
 * [me.trinitrix.mirax.SessionHost.commit], network, broadcast, idle, or
 * navigation work; the session host forwards it to a FAILED-only view
 * refresh. Read-only [snapshot] access from the listener is safe because
 * every public method shares one reentrant monitor.
 *
 * @param nowMs Injectable monotonic clock in milliseconds.
 * @param messageQueue Queue posting and removing delayed expiry checks.
 *   Null selects a lazily created persistent production main queue.
 * @param scheduler Legacy post hook; kept for existing tests. When non-null
 *   it drives expiry through a recording queue instead of [messageQueue].
 * @param canceller Legacy removal hook paired with [scheduler].
 * @param isOwnerThread True only on the serial owner thread. Null selects the
 *   strict production main-thread check.
 */
class PrePlayFailureEpisodeOwner internal constructor(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    messageQueue: ExpiryMessageQueue? = null,
    private val scheduler: ((delayMs: Long, task: () -> Unit) -> Boolean)? = null,
    private val canceller: (() -> Unit)? = null,
    private val isOwnerThread: (() -> Boolean)? = null,
) {
    private val episode = PrePlayFailureEpisode(nowMs)
    private var currentAttempt: PrePlayAttemptId? = null
    private var currentSinkSource: SinkAttemptSource? = null
    private var isForegroundVisible: Boolean = false
    private var episodeListener: ((PrePlayFailureSnapshot?) -> Unit)? = null
    private var lastNotifiedKey: EpisodeKey? = null
    private var expiryGeneration: Long = 0L
    private val lazyMainQueue: ExpiryMessageQueue? by lazy(LazyThreadSafetyMode.NONE) {
        if (scheduler != null || canceller != null) null else createMainQueue()
    }
    private val injectedQueue: ExpiryMessageQueue? = messageQueue
        ?: run {
            if (scheduler == null && canceller == null) {
                null
            } else {
                object : ExpiryMessageQueue {
                    override fun postDelayed(delayMs: Long, task: Runnable): Boolean {
                        val post = scheduler ?: return false
                        removeCallbacks(task)
                        return post(delayMs) { task.run() }
                    }

                    override fun removeCallbacks(task: Runnable) {
                        canceller?.invoke()
                    }
                }
            }
        }
    private var pendingExpiryTask: Runnable? = null
    private val expiryRunnable = Runnable { checkExpiry(expiryGeneration) }

    /**
     * Registers the episode-change listener, replacing any previous one.
     *
     * The listener runs synchronously on the owner thread after every accepted
     * mutation whose observable snapshot changed. Read-only owner queries are
     * allowed; mutations and holding an Activity are not.
     *
     * @param listener Episode observer, or null to clear.
     */
    @Synchronized
    fun setEpisodeListener(listener: ((PrePlayFailureSnapshot?) -> Unit)?) {
        if (!onOwnerThread()) return
        episodeListener = listener
    }

    /**
     * Binds a new attempt to the source accepted by the owner thread.
     *
     * @param attemptId New attempt identity from the process-wide producer.
     * @param source Source accepted for this attempt.
     * @return True when accepted on the owner thread.
     */
    @Synchronized
    fun onPrePlayAttemptStarted(attemptId: PrePlayAttemptId, source: SinkAttemptSource): Boolean {
        if (!onOwnerThread()) return false
        val beforeKey = episodeKey()
        if (!episode.beginAttempt(attemptId)) return false
        cancelExpiry()
        currentAttempt = attemptId
        currentSinkSource = source
        emitIfChanged(beforeKey)
        return true
    }

    /**
     * Applies genuine terminal evidence only to its original source.
     *
     * @param rtspFailure Terminal evidence, or null for unproven paths.
     * @param source Source captured when this attempt began.
     * @return Classified outcome, or null when rejected or stale.
     */
    @Synchronized
    fun onPrePlayTerminal(rtspFailure: RtspFailure?, source: SinkAttemptSource): PrePlayTerminalOutcome? {
        if (!onOwnerThread() || source != currentSinkSource) return null
        val attempt = currentAttempt ?: return null
        val outcome = terminalOutcome(attempt, rtspFailure) ?: return null
        val beforeKey = episodeKey()
        if (!episode.onTerminalOutcome(outcome)) return null
        if (isForegroundVisible) scheduleExpiryCheck()
        emitIfChanged(beforeKey)
        return outcome
    }

    /**
     * Applies proven TCP establishment timeout evidence to its original source.
     *
     * @param evidence Evidence from the expired TCP dial window.
     * @param source Source captured by that dial.
     * @return Classified outcome, or null when rejected or stale.
     */
    @Synchronized
    internal fun onTcpEstablishmentTimeout(
        evidence: TcpEstablishmentTimeout,
        source: SinkAttemptSource,
    ): PrePlayTerminalOutcome? {
        if (!onOwnerThread() || source != currentSinkSource) return null
        val attempt = currentAttempt ?: return null
        val outcome = tcpEstablishmentTimeoutOutcome(attempt, evidence)
        val beforeKey = episodeKey()
        if (!episode.onTerminalOutcome(outcome)) return null
        if (isForegroundVisible) scheduleExpiryCheck()
        emitIfChanged(beforeKey)
        return outcome
    }

    /**
     * Called when a non-failure terminal outcome is determined.
     * Consumes the attempt without opening an episode.
     *
     * @param reason Explicit non-failure classification.
     * @param source Source which ended without a genuine failure.
     * @return Outcome, or null when off-thread or stale.
     */
    @Synchronized
    fun onPrePlayNonFailure(
        reason: PrePlayNonFailureReason,
        source: SinkAttemptSource,
    ): PrePlayTerminalOutcome.NonFailure? {
        if (!onOwnerThread() || source != currentSinkSource) return null
        val attempt = currentAttempt ?: return null
        val beforeKey = episodeKey()
        val outcome = nonFailureOutcome(attempt, reason)
        episode.onTerminalOutcome(outcome)
        emitIfChanged(beforeKey)
        return outcome
    }

    /**
     * Called when Big Standby becomes visible/hidden in the foreground.
     * The 7.5s budget accrues only while visible: hiding cancels the pending
     * check without expiring, and re-showing reschedules the remainder.
     *
     * @param visible True while Big Standby is actually in the visible foreground.
     * @return True when accepted on the owner thread.
     */
    @Synchronized
    fun onForegroundVisibilityChanged(visible: Boolean): Boolean {
        if (!onOwnerThread()) return false
        val beforeKey = episodeKey()
        val expired = episode.expireIfDue()
        isForegroundVisible = visible
        episode.onVisibilityChanged(visible)
        if (visible && expired == null) {
            scheduleExpiryCheck()
        } else {
            cancelExpiry()
        }
        if (expired != null) emitExpired(expired) else emitIfChanged(beforeKey)
        return true
    }

    /**
     * Called when a preemption reason occurs (projection started, explicit stop,
     * Wi-Fi pause, frozen state). Immediately clears any active episode and
     * consumes the current attempt so its late outcomes cannot reopen one.
     *
     * @param reason External truth that supersedes the episode.
     * @return True when accepted on the owner thread.
     */
    @Synchronized
    fun onPreempted(reason: PrePlayPreemptReason): Boolean {
        if (!onOwnerThread()) return false
        val beforeKey = episodeKey()
        episode.preempt(reason)
        cancelExpiry()
        emitIfChanged(beforeKey)
        return true
    }

    /**
     * Called on an explicit user stop (advertising switched off). Clears any
     * active episode and consumes the current attempt so a late terminal for
     * it can never open a replacement. Normal pre-play drops without an
     * explicit stop must not call this.
     *
     * @return True when accepted on the owner thread.
     */
    @Synchronized
    fun onExplicitStop(): Boolean {
        if (!onOwnerThread()) return false
        val beforeKey = episodeKey()
        episode.preempt(PrePlayPreemptReason.EXPLICIT_STOP)
        cancelExpiry()
        emitIfChanged(beforeKey)
        return true
    }

    /**
     * Called when user explicitly dismisses the FAILED presentation.
     * Does not change broadcast state, idle clock, or navigation.
     *
     * @return Dismissed attempt, or null when off-thread or no active attempt.
     */
    @Synchronized
    fun onDismissed(): PrePlayAttemptId? {
        if (!onOwnerThread()) return null
        val attempt = currentAttempt ?: return null
        val beforeKey = episodeKey()
        episode.dismiss(attempt)
        cancelExpiry()
        emitIfChanged(beforeKey)
        return attempt
    }

    /**
     * Returns the current observable snapshot, or null if no episode is active
     * or the episode has expired.
     *
     * Read-only and monitor-guarded like every other public method, so it is
     * safe to call from the episode listener.
     *
     * @return Snapshot with cause and remaining time, or null.
     */
    @Synchronized
    fun snapshot(): PrePlayFailureSnapshot? {
        // Read-only; safe from the listener and any monitor holder.
        return episode.snapshot()
    }

    /**
     * Returns the currently tracked attempt identity.
     *
     * @return Active attempt, or null before the first attempt.
     */
    @Synchronized
    fun currentAttempt(): PrePlayAttemptId? = currentAttempt

    /**
     * Checks if an episode is currently active (visible and not expired).
     *
     * @return True when an episode snapshot exists.
     */
    @Synchronized
    fun isEpisodeActive(): Boolean = episode.snapshot() != null

    /**
     * Runs one expiry check. Public for the scheduler callback and tests.
     * Expiry notifies the listener with null exactly once even though the
     * expired snapshot already reads null; a surviving visible episode
     * reschedules its remainder without recursion (one posted runnable).
     * Stale runnables carrying an older generation are ignored.
     */
    @Synchronized
    fun checkExpiry() {
        checkExpiry(expiryGeneration)
    }

    @Synchronized
    internal fun checkExpiry(generation: Long) {
        if (!onOwnerThread()) return
        if (generation != expiryGeneration) return
        activeQueue()?.let { removePending(it) }
        val beforeKey = episodeKey()
        val expired = episode.expireIfDue()
        if (expired == null && isForegroundVisible) scheduleExpiryCheck()
        if (expired != null) {
            cancelExpiry()
            emitExpired(expired)
            return
        }
        emitIfChanged(beforeKey)
    }

    private fun scheduleExpiryCheck() {
        val remaining = episode.snapshot()?.remainingMs ?: return
        if (remaining <= 0L) return
        postExpiry(remaining)
    }

    private fun postExpiry(delayMs: Long): Boolean {
        val queue = activeQueue() ?: return false
        removePending(queue)
        expiryGeneration += 1L
        val task = snapshotRunnable(expiryGeneration)
        val posted = queue.postDelayed(delayMs, task)
        if (posted) {
            pendingExpiryTask = task
        } else {
            queue.removeCallbacks(task)
        }
        return posted
    }

    private fun cancelExpiry() {
        expiryGeneration += 1L
        activeQueue()?.let { removePending(it) }
    }

    private fun removePending(queue: ExpiryMessageQueue) {
        queue.removeCallbacks(expiryRunnable)
        val pending = pendingExpiryTask
        if (pending !== null && pending !== expiryRunnable) {
            queue.removeCallbacks(pending)
            pendingExpiryTask = null
        }
    }

    private fun snapshotRunnable(generation: Long): Runnable =
        Runnable { checkExpiry(generation) }

    private fun activeQueue(): ExpiryMessageQueue? = injectedQueue ?: lazyMainQueue

    private fun createMainQueue(): ExpiryMessageQueue? {
        val looper = Looper.getMainLooper() ?: return null
        return MainHandlerExpiryQueue(Handler(looper))
    }

    /** Observable identity of the current episode; remaining time excluded. */
    private data class EpisodeKey(
        val attemptId: PrePlayAttemptId,
        val cause: PrePlayFailureCause,
    )

    private fun episodeKey(): EpisodeKey? {
        val snapshot = episode.snapshot() ?: return null
        return EpisodeKey(snapshot.attemptId, snapshot.cause)
    }

    private fun emitExpired(expired: PrePlayAttemptId) {
        if (episodeKey() == null && lastNotifiedKey != null) {
            lastNotifiedKey = null
            episodeListener?.invoke(null)
        }
    }

    private fun onOwnerThread(): Boolean {
        val custom = isOwnerThread
        if (custom != null) return custom()
        return Looper.getMainLooper()?.isCurrentThread == true
    }

    private fun emitIfChanged(beforeKey: EpisodeKey?) {
        val afterKey = episodeKey()
        if (afterKey != beforeKey || afterKey != lastNotifiedKey) {
            lastNotifiedKey = afterKey
            episodeListener?.invoke(episode.snapshot())
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PrePlayFailureEpisodeOwner? = null

        /**
         * Returns the process-wide owner instance with production wiring
         * (monotonic clock, main-handler scheduler, strict main-thread guard).
         *
         * @return Shared owner.
         */
        fun getInstance(): PrePlayFailureEpisodeOwner =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrePlayFailureEpisodeOwner().also { INSTANCE = it }
            }

        /** For test isolation only. */
        internal fun resetForTest() {
            INSTANCE = null
        }
    }
}
