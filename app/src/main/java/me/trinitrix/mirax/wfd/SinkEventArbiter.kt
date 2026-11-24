package me.trinitrix.mirax.wfd

import android.os.Looper
import me.trinitrix.mirax.session.ConnectionRunFact
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.TcpEstablishmentTimeout
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.wfd.rtsp.RtspFailure

/**
 * A run identity issued from a positive, process-wide increasing sequence.
 *
 * @property source Group that owns this run.
 * @property sequence Process-wide run sequence.
 * @property observation Original owner observation, required for production runs.
 */
internal data class SinkRunToken(
    val source: SinkAttemptSource,
    val sequence: Long,
    val observation: OwnerGroupSnapshot? = null,
)

/** Coordinates source publication with timeout and group loss. */
internal class SinkSourceRegistration {
    private var abandoned = false
    private var superseded = false
    private var committing = false
    private var result: Boolean? = null

    /** Returns whether the main-thread callback may start. */
    @Synchronized
    fun mayStart(): Boolean = !abandoned

    /** Claims the right to accept the source before cancellation. */
    @Synchronized
    fun beginCommit(): Boolean {
        if (abandoned || committing || result != null) return false
        committing = true
        return true
    }

    /** Cancels only a source acceptance which has not begun. */
    @Synchronized
    fun cancelBeforeCommit(): Boolean {
        if (committing || result != null) return false
        abandoned = true
        return true
    }

    /** Abandons an acceptance because its observed group went away. */
    @Synchronized
    fun abandon() {
        abandoned = true
    }

    /** Invalidates a registration on a newer observation without asserting physical loss. */
    @Synchronized
    fun supersede() {
        abandoned = true
        superseded = true
    }

    /** Returns whether cleanup is for replacement rather than observed group loss. */
    @Synchronized
    fun wasSuperseded(): Boolean = superseded

    /** Returns whether a real drop or newer observation invalidated the request. */
    @Synchronized
    fun isAbandoned(): Boolean = abandoned

    /**
     * Publishes the result or requests cleanup after group loss.
     *
     * @param accepted Whether the source was accepted.
     * @return True when an abandoned callback accepted a source.
     */
    @Synchronized
    fun complete(accepted: Boolean): Boolean {
        if (result != null) return false
        result = accepted
        return abandoned && accepted
    }

    /** Returns null until the main-thread callback completes. */
    @Synchronized
    fun result(): Boolean? = result
}

/** The result of the controller's offer rendezvous, including an in-flight commit. */
internal sealed interface SinkOfferDecision<out T> {
    data object Waiting : SinkOfferDecision<Nothing>
    data object PreCommitCancelled : SinkOfferDecision<Nothing>
    data object CommittingPending : SinkOfferDecision<Nothing>
    data class Completed<T>(val offer: T) : SinkOfferDecision<T>
    data object FailedOrSuperseded : SinkOfferDecision<Nothing>
}

/**
 * Claims an offer only after a live authoritative SAME observation and a current local route check.
 *
 * @param relation Group relation observed at claim time; do not close over a worker-loop sample.
 */
internal fun <T> beginSinkOfferCommit(
    request: SinkOfferRequest<T>,
    relation: SinkGroupRelation,
    isCurrent: () -> Boolean,
    isOwnerCurrent: () -> Boolean,
): Boolean = relation == SinkGroupRelation.SAME && isCurrent() && isOwnerCurrent() && request.beginCommit()

/** Coordinates cancellation and publication of one complete offer. */
internal class SinkOfferRequest<T> {
    private var state: SinkOfferDecision<T> = SinkOfferDecision.Waiting
    private var started = false

    @Synchronized
    fun start(): Boolean {
        if (state != SinkOfferDecision.Waiting || started) return false
        started = true
        return true
    }

    @Synchronized
    fun beginCommit(): Boolean {
        if (state != SinkOfferDecision.Waiting || !started) return false
        state = SinkOfferDecision.CommittingPending
        return true
    }

    @Synchronized
    fun isActive(): Boolean = state == SinkOfferDecision.Waiting || state == SinkOfferDecision.CommittingPending

    @Synchronized
    fun cancel(): Boolean {
        if (state != SinkOfferDecision.Waiting) return false
        state = SinkOfferDecision.PreCommitCancelled
        return true
    }

    /** Publishes the entire offer only after the commit has been claimed. */
    @Synchronized
    fun complete(offer: T): Boolean {
        if (state != SinkOfferDecision.CommittingPending) return false
        state = SinkOfferDecision.Completed(offer)
        return true
    }

    /** Records callback failure without changing a cancelled or completed request. */
    @Synchronized
    fun fail() {
        if (state == SinkOfferDecision.Waiting || state == SinkOfferDecision.CommittingPending) {
            state = SinkOfferDecision.FailedOrSuperseded
        }
    }

    /** Cancels a pre-commit request or reports the already claimed result. */
    @Synchronized
    fun deadlineDecision(): SinkOfferDecision<T> {
        if (state == SinkOfferDecision.Waiting) state = SinkOfferDecision.PreCommitCancelled
        return state
    }

    /** Returns the current decision without cancelling it. */
    @Synchronized
    fun decision(): SinkOfferDecision<T> = state
}

/**
 * Waits for the offer without mistaking a claimed commit for cancellation.
 *
 * @param request Shared offer decision.
 * @param remainingMs Time left in the original WM-plus-main deadline.
 * @param pollMs Bounded interval for observing replacement while commit is pending.
 * @param waitMs Waits for callback completion for at most the requested interval.
 * @param isCurrent Whether the originating run is still current.
 * @param onPendingPoll Checks for group loss or replacement.
 * @return A completed offer, an explicit failure, or a pre-commit cancellation.
 */
internal fun <T> awaitSinkOffer(
    request: SinkOfferRequest<T>,
    remainingMs: Long,
    pollMs: Long,
    waitMs: (Long) -> Boolean,
    isCurrent: () -> Boolean,
    onPendingPoll: () -> Unit,
): SinkOfferDecision<T> {
    val completed = waitMs(remainingMs.coerceAtLeast(0))
    var decision = if (completed) request.decision() else request.deadlineDecision()
    while (decision == SinkOfferDecision.CommittingPending && isCurrent()) {
        if (!waitMs(pollMs)) onPendingPoll()
        decision = request.decision()
    }
    return if (isCurrent()) decision else SinkOfferDecision.FailedOrSuperseded
}

/**
 * Serializes sink events on the main thread and publishes identities to workers.
 * The caller owns the process-wide group and run sequence producers. This arbiter
 * must live for the process so that its accepted sequence watermarks survive stops.
 * Callbacks are synchronous and may reenter this arbiter; a false result after a
 * callback can therefore mean that an action was dispatched but then superseded.
 * Published identity properties are separate volatile reads, not an atomic snapshot.
 *
 * @param onAction Dispatches an accepted session action.
 * @param onAttempt Registers an accepted group attempt.
 * @param onDrop Reports a natural group drop.
 * @param onTerminal Reports a current run's RTSP terminal failure.
 * @param onDialTimeout Reports a proven TCP establishment timeout.
 * @param isMainThread Main-thread check; injectable for host JVM tests.
 */
internal class SinkEventArbiter(
    private val onAction: (SessionAction) -> Unit,
    private val onAttempt: (SinkAttemptSource) -> Unit,
    private val onDrop: (SinkAttemptSource) -> Unit,
    private val onTerminal: (RtspFailure, SinkAttemptSource) -> Unit,
    private val onDialTimeout: (TcpEstablishmentTimeout, SinkAttemptSource) -> Unit = { _, _ -> },
    private val isMainThread: () -> Boolean = { Looper.getMainLooper()?.isCurrentThread == true },
) {
    @Volatile
    var generation: Int? = null
        private set

    @Volatile
    var source: SinkAttemptSource? = null
        private set

    @Volatile
    var run: SinkRunToken? = null
        private set

    @Volatile
    var current: SinkRunToken? = null
        private set

    private var lastGroupSequence = 0L
    private var lastRunSequence = 0L
    private var transition = 0L
    private var hasBegunRun = false
    private var selectedMode: VideoMode? = null
    private var touchEnabled: Boolean? = null
    private var played = false
    private var terminalSeen = false
    private val droppingSources = mutableSetOf<SinkAttemptSource>()

    /**
     * Starts a generation, closing any previous open run before returning.
     *
     * @param generation Controller generation to accept.
     */
    fun start(generation: Int) {
        checkMainThread()
        val closing = closeOpenRun()
        transition += 1
        clearIdentities()
        this.generation = generation
        closing?.let(onAction)
    }

    /** Invalidates all identities and closes the previous open run at most once. */
    fun stop() {
        checkMainThread()
        val closing = closeOpenRun()
        transition += 1
        clearIdentities()
        generation = null
        closing?.let(onAction)
    }

    /** Invalidates the current group without stopping the advertising generation. */
    fun retireSource() {
        checkMainThread()
        val closing = closeOpenRun()
        transition += 1
        clearIdentities()
        closing?.let(onAction)
    }

    /**
     * Accepts a newer group from the active generation.
     *
     * @param source Group identity captured by the worker.
     * @return True if the source remains current after its callbacks.
     */
    fun accept(source: SinkAttemptSource): Boolean = accept(source, this.source, current)

    /**
     * Accepts a source only if the previously observed identities are unchanged.
     *
     * @param source Group identity captured by the worker.
     * @param expectedSource Previously observed source, or null.
     * @param expectedRun Previously observed run, or null.
     * @param isValid Rechecks a worker request after reentrant callbacks.
     * @return True if the accepted source remains current after callbacks.
     */
    fun accept(
        source: SinkAttemptSource,
        expectedSource: SinkAttemptSource?,
        expectedRun: SinkRunToken?,
        isValid: () -> Boolean = { true },
    ): Boolean {
        checkMainThread()
        if (this.source != expectedSource || current != expectedRun) return false
        if (source.generation != generation || source.groupSequence <= lastGroupSequence) return false
        val closing = closeOpenRun()
        lastGroupSequence = source.groupSequence
        transition += 1
        val accepting = transition
        clearIdentities()
        if (closing != null) {
            onAction(closing)
            if (transition != accepting || generation != source.generation) return false
        }
        if (!isValid()) return false
        this.source = source
        onAttempt(source)
        if (!isSource(source) || !isValid()) return false
        if (current != null) return true
        onAction(SessionAction.PrePlayProgress)
        return isSource(source)
    }

    /**
     * Completes a worker's source request, cleaning up after observed group loss.
     *
     * @param source Group identity captured by the worker.
     * @param expectedSource Previously observed source, or null.
     * @param expectedRun Previously observed run, or null.
     * @param request Cancellation and completion state shared with the worker.
     * @param allowed Whether the controller generation and stop state still match.
     */
    fun registerSource(
        source: SinkAttemptSource,
        expectedSource: SinkAttemptSource?,
        expectedRun: SinkRunToken?,
        request: SinkSourceRegistration,
        allowed: Boolean,
    ) {
        checkMainThread()
        var accepted = false
        try {
            if (allowed && request.mayStart() && this.source == expectedSource && current == expectedRun &&
                request.beginCommit()
            ) {
                accepted = accept(source, expectedSource, expectedRun) { request.mayStart() }
            }
        } finally {
            request.complete(accepted)
            if (request.isAbandoned() && isSource(source)) {
                if (request.wasSuperseded()) retireSource() else drop(source, current)
            }
        }
    }

    /**
     * Checks the published source from any thread.
     *
     * @param source Group identity to check.
     * @return True if it is current at the volatile read.
     */
    fun isSource(source: SinkAttemptSource): Boolean = this.source == source

    /**
     * Begins a run after closing the previous run and issuing an attempt for a retry.
     *
     * @param token New process-wide run identity.
     * @param host Remote host recorded in connection history.
     * @return True if this run remains open after dispatch.
     */
    fun begin(token: SinkRunToken, host: String): Boolean {
        checkMainThread()
        if (!isSource(token.source) || token.sequence <= lastRunSequence) return false
        val isRetry = hasBegunRun
        val closing = closeOpenRun()
        hasBegunRun = true
        lastRunSequence = token.sequence
        transition += 1
        val beginning = transition
        current = null
        run = null
        if (closing != null) {
            onAction(closing)
            if (transition != beginning || !isSource(token.source)) return false
        }
        if (isRetry) {
            onAttempt(token.source)
            if (transition != beginning || !isSource(token.source)) return false
        }
        selectedMode = null
        touchEnabled = null
        played = false
        terminalSeen = false
        run = token
        current = token
        onAction(SessionAction.BeginConnectionRun(host))
        return run == token && isRun(token)
    }

    /**
     * Checks the published current token, including a finished run, from any thread.
     *
     * @param token Run identity to check.
     * @return True if it is still current at the volatile read.
     */
    fun isRun(token: SinkRunToken): Boolean = current == token

    /**
     * Dispatches a current run's non-history action.
     *
     * @param token Originating run.
     * @param action Session event to dispatch.
     * @return True if the run remains current after dispatch.
     */
    fun action(token: SinkRunToken, action: SessionAction): Boolean {
        checkMainThread()
        if (!isRun(token)) return false
        if (run != token && action != SessionAction.PrePlayGroupDropped && action != SessionAction.ConnectionEnded) {
            return false
        }
        when (action) {
            is SessionAction.BeginConnectionRun,
            is SessionAction.AppendConnectionLog,
            is SessionAction.FinishConnectionRun -> return false
            is SessionAction.SourceSelectedMode -> selectedMode = action.mode
            SessionAction.EnteredPlay, SessionAction.ConnectionEstablished -> played = true
            else -> Unit
        }
        onAction(action)
        return isRun(token)
    }

    /**
     * Appends a line only to the unique open run.
     *
     * @param token Originating run.
     * @param line Diagnostic line.
     * @return True if the run remains open after dispatch.
     */
    fun log(token: SinkRunToken, line: String): Boolean {
        checkMainThread()
        if (run != token || !isRun(token)) return false
        onAction(SessionAction.AppendConnectionLog(line))
        return run == token && isRun(token)
    }

    /**
     * Closes the unique open run but retains its identity for terminal and drop.
     *
     * @param token Originating run.
     * @param succeeded Whether playback started.
     * @param selected Selected mode, or null to use the last observed selection.
     * @param touch Touch setting, or null to use the frozen run fact.
     * @return True if the token remains current after dispatch.
     */
    fun finish(token: SinkRunToken, succeeded: Boolean, selected: VideoMode?, touch: Boolean?): Boolean {
        checkMainThread()
        if (run != token || !isRun(token)) return false
        if (succeeded) played = true
        val action = SessionAction.FinishConnectionRun(succeeded, facts(selected ?: selectedMode, touch ?: touchEnabled))
        run = null
        onAction(action)
        return isRun(token)
    }

    /**
     * Dispatches a natural group drop, then reports it only if still current.
     * A tokenless drop is valid only before any run has begun in this group.
     *
     * @param source Originating group.
     * @param token Originating run, or null for a pre-run group drop.
     * @param played Whether playback started.
     * @return True if the drop was consumed without a reentrant replacement.
     */
    fun drop(source: SinkAttemptSource, token: SinkRunToken? = null, played: Boolean = false): Boolean {
        checkMainThread()
        if (!matchesDrop(source, token) || !droppingSources.add(source)) return false
        try {
            if (played && token != null) this.played = true
            val wasPlayed = this.played
            val closing = closeOpenRun()
            run = null
            if (closing != null) {
                onAction(closing)
                if (!matchesDrop(source, token)) return false
            }
            val event = if (wasPlayed) SessionAction.ConnectionEnded else SessionAction.PrePlayGroupDropped
            if (token == null) {
                onAction(event)
            } else {
                action(token, event)
            }
            if (!matchesDrop(source, token)) return false
            onDrop(source)
            if (!matchesDrop(source, token)) return false
            transition += 1
            clearIdentities()
            return true
        } finally {
            droppingSources.remove(source)
        }
    }

    /**
     * Forwards terminal evidence only while its run identity is still current.
     *
     * @param token Originating run.
     * @param failure RTSP terminal evidence.
     * @return True if the token remains current after the callback.
     */
    fun terminal(token: SinkRunToken, failure: RtspFailure): Boolean {
        checkMainThread()
        if (!isRun(token) || played || terminalSeen || token.source in droppingSources) return false
        terminalSeen = true
        onTerminal(failure, token.source)
        return isRun(token)
    }

    /**
     * Forwards a dial result's timeout only for the current unplayed run.
     *
     * @param token Originating run.
     * @param result Result of that run's TCP dial window.
     * @return True if timeout evidence was dispatched and the run remains current.
     */
    fun dialFinished(token: SinkRunToken, result: SinkDialResult): Boolean {
        checkMainThread()
        val evidence = (result as? SinkDialResult.NoConnection)?.timeout ?: return false
        if (!isRun(token) || played || terminalSeen || token.source in droppingSources) return false
        terminalSeen = true
        onDialTimeout(evidence, token.source)
        return isRun(token)
    }

    /**
     * Records the frozen touch setting for an implicit stop or group rollover.
     *
     * @param token Originating open run.
     * @param touch Frozen touch setting.
     * @return True when the fact was recorded.
     */
    fun offerFrozen(token: SinkRunToken, touch: Boolean): Boolean {
        checkMainThread()
        if (run != token || !isRun(token)) return false
        touchEnabled = touch
        return true
    }

    private fun matchesDrop(source: SinkAttemptSource, token: SinkRunToken?): Boolean =
        isSource(source) &&
            (if (token == null) !hasBegunRun && current == null else token.source == source && isRun(token))

    private fun closeOpenRun(): SessionAction.FinishConnectionRun? =
        if (run == null) null else SessionAction.FinishConnectionRun(played, facts(selectedMode, touchEnabled))

    private fun facts(selected: VideoMode?, touch: Boolean?): List<ConnectionRunFact> = listOf(
        ConnectionRunFact("selected mode", selected?.format() ?: "none"),
        ConnectionRunFact("touch", when (touch) {
            true -> "touch"
            false -> "display only"
            null -> "unknown"
        }),
    )

    private fun clearIdentities() {
        current = null
        run = null
        source = null
        hasBegunRun = false
        selectedMode = null
        touchEnabled = null
        played = false
        terminalSeen = false
    }

    private fun checkMainThread() {
        check(isMainThread()) { "SinkEventArbiter mutations must run on the main thread" }
    }
}
