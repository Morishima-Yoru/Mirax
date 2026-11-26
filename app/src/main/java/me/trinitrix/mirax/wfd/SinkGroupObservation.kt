package me.trinitrix.mirax.wfd

import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.wfd.rtsp.RtspFailure

/** Immutable link between a local source and its original owner observation. */
internal data class SinkSourceBinding(val source: SinkAttemptSource, val observation: OwnerGroupSnapshot)

/** Selects the observation of the locally registered source, never a replacement. */
internal fun selectSinkEndObservation(
    currentSource: SinkAttemptSource?,
    registered: SinkSourceBinding?,
    observed: SinkSourceBinding?,
): OwnerGroupSnapshot? =
    (registered?.takeIf { it.source == currentSource } ?: observed?.takeIf { it.source == currentSource })?.observation

/** Identity captured by a provisioning read, including an observation not yet registered on main. */
internal data class SinkProvisioningFence(
    val generation: Int,
    val registeredSource: SinkAttemptSource?,
    val observedSource: SinkAttemptSource?,
    val observation: OwnerGroupSnapshot?,
)

/** Retains a user-ended group until an authoritative replacement or down observation. */
internal class SinkUserEnd {
    private data class State(
        val original: OwnerGroupSnapshot?,
        val retry: Boolean,
        val recoverAccepted: Boolean = false,
    )

    private var state: State? = null

    @Synchronized
    fun get(): Boolean = state != null

    /**
     * Starts a guarded END for the original observation.
     *
     * @param original Captured group, or null when unavailable.
     * @param submit Submits without waiting for IPC.
     * @param recoverAccepted When true, also retain a retry after local ACCEPTED (queue ≠ removal).
     * @return Local submission result, or null when no observation exists.
     */
    @Synchronized
    fun begin(
        original: OwnerGroupSnapshot?,
        submit: (OwnerGroupSnapshot) -> OwnerEndSubmission,
        recoverAccepted: Boolean = false,
    ): OwnerEndSubmission? {
        if (original == null) {
            if (state == null) state = State(null, false)
            return null
        }
        val result = submit(original)
        state = State(original, shouldRetry(result, recoverAccepted), recoverAccepted)
        return result
    }

    /** Returns true only when a confirmed down or replacement permits a new source. */
    @Synchronized
    fun observe(
        observed: OwnerGroupSnapshot,
        submit: (OwnerGroupSnapshot) -> OwnerEndSubmission,
        scheduleRetry: (() -> Unit) -> Boolean = { action ->
            action()
            true
        },
    ): Boolean {
        val current = state ?: return false
        val original = current.original
        val relation = if (original == null) {
            if (observed.state == P2pGroupState.Down) SinkGroupRelation.DOWN else SinkGroupRelation.UNKNOWN
        } else {
            compareSinkGroup(original, observed)
        }
        return when (relation) {
            SinkGroupRelation.SAME -> {
                if (current.retry && original != null) {
                    val claimed = current.copy(retry = false)
                    state = claimed
                    val scheduled = scheduleRetry {
                        synchronized(this) {
                            if (state === claimed) {
                                state = claimed.copy(
                                    retry = shouldRetry(submit(original), claimed.recoverAccepted),
                                )
                            }
                        }
                    }
                    if (!scheduled && state === claimed) state = current
                }
                false
            }
            SinkGroupRelation.DOWN, SinkGroupRelation.SUPERSEDED -> {
                state = current.copy(retry = false)
                true
            }
            SinkGroupRelation.UNKNOWN -> false
        }
    }

    @Synchronized
    fun clear() {
        state = null
    }

    private fun shouldRetry(result: OwnerEndSubmission, recoverAccepted: Boolean): Boolean =
        result == OwnerEndSubmission.BUSY ||
            (recoverAccepted && result == OwnerEndSubmission.ACCEPTED)
}

/** Retains automatic END work until the originating group is observed down or replaced. */
internal class SinkAutomaticEnd {
    private data class Pending(val token: SinkRunToken, val stopSequence: Long, val retry: SinkUserEnd)

    private val pending = AtomicReference<Pending?>(null)

    /**
     * Submits once and retains a retry for busy workers or accepted local queues.
     *
     * Local ACCEPTED (helper QUEUED) is not remote removal; retain until the group is observed
     * down/replaced so a silent post-time no-op can recover on the next SAME observation.
     *
     * @param token Originating source, run, and observation.
     * @param stopSequence Stop watermark captured for this submission.
     * @param isCurrent Rechecks the run and watermark.
     * @param submit Submits the original observation locally.
     * @return The local submission result, not remote removal.
     */
    fun submit(
        token: SinkRunToken,
        stopSequence: Long,
        isCurrent: (SinkRunToken, Long) -> Boolean,
        submit: (OwnerGroupSnapshot) -> OwnerEndSubmission,
    ): OwnerEndSubmission {
        val original = token.observation ?: return OwnerEndSubmission.STALE
        if (!isCurrent(token, stopSequence)) return OwnerEndSubmission.STALE
        clear()
        val retry = SinkUserEnd()
        val result = retry.begin(original, submit, recoverAccepted = true) ?: OwnerEndSubmission.STALE
        if ((result == OwnerEndSubmission.BUSY || result == OwnerEndSubmission.ACCEPTED) &&
            isCurrent(token, stopSequence)
        ) {
            val next = Pending(token, stopSequence, retry)
            pending.getAndSet(next)?.retry?.clear()
            if (!isCurrent(token, stopSequence) && pending.compareAndSet(next, null)) retry.clear()
        }
        return result
    }

    /**
     * Retries only after the original group is observed and the run remains current.
     *
     * @param observed Current authoritative observation.
     * @param isCurrent Rechecks the originating run and stop watermark.
     * @param submit Submits the original observation locally.
     * @param scheduleRetry Schedules at most one delayed local retry.
     */
    fun observe(
        observed: OwnerGroupSnapshot,
        isCurrent: (SinkRunToken, Long) -> Boolean,
        submit: (OwnerGroupSnapshot) -> OwnerEndSubmission,
        scheduleRetry: (() -> Unit) -> Boolean,
    ) {
        val current = pending.get() ?: return
        if (!isCurrent(current.token, current.stopSequence)) {
            if (pending.compareAndSet(current, null)) current.retry.clear()
            return
        }
        val resolved = current.retry.observe(
            observed,
            submit = { snapshot ->
                if (pending.get() === current && snapshot == current.token.observation &&
                    isCurrent(current.token, current.stopSequence)
                ) submit(snapshot) else OwnerEndSubmission.STALE
            },
            scheduleRetry = scheduleRetry,
        )
        if (resolved && pending.compareAndSet(current, null)) current.retry.clear()
    }

    /** Cancels pending local retries without claiming remote removal. */
    fun clear() {
        pending.getAndSet(null)?.retry?.clear()
    }
}

/** Relation between a run's original owner observation and a later observation. */
internal enum class SinkGroupRelation {
    SAME,
    DOWN,
    SUPERSEDED,
    UNKNOWN,
}

/**
 * Clears an observed binding, publishing only authoritative DOWN before its original CAS clear.
 *
 * @param expected Original binding which must still own current before publishing proof.
 * @param relation Authoritative comparison result; other relations clear without publishing DOWN.
 * @param current Live source binding, cleared only if the original instance still owns it.
 * @param down Latest DOWN key, never replaced by an older or equal source sequence.
 */
internal fun clearSinkSourceBinding(
    expected: SinkSourceBinding,
    relation: SinkGroupRelation,
    current: AtomicReference<SinkSourceBinding?>,
    down: AtomicReference<SinkSourceBinding?>,
) {
    if (relation == SinkGroupRelation.DOWN) {
        while (current.get() === expected) {
            val previous = down.get()
            if (previous != null && previous.source.groupSequence >= expected.source.groupSequence) break
            if (down.compareAndSet(previous, expected)) break
        }
    }
    current.compareAndSet(expected, null)
}

/**
 * Live dispatch facts for a terminal event, without requiring a binding already cleared by DOWN.
 *
 * @property activeGeneration Running controller generation, or null after stop.
 * @property stopSequence Current explicit-stop watermark.
 * @property latestGroupSequence Latest reserved source sequence, including unregistered replacements.
 * @property observedBinding Current worker binding, or null after authoritative group loss.
 * @property currentRun Current arbiter run, including a finished run awaiting its drop.
 * @property userEnded Whether the user has ended the group.
 * @property originalOwnerCurrent Whether the original owner revision is still current.
 * @property provenDownBinding Latest authoritative DOWN key, retained separately from the live binding.
 */
internal data class SinkTerminalFence(
    val activeGeneration: Int?,
    val stopSequence: Long,
    val latestGroupSequence: Long,
    val observedBinding: SinkSourceBinding?,
    val currentRun: SinkRunToken?,
    val userEnded: Boolean,
    val originalOwnerCurrent: Boolean,
    val provenDownBinding: SinkSourceBinding? = null,
)

/**
 * Posts proven pre-play evidence for the original group, retaining it across same-owner DOWN.
 *
 * @param token Originating run identity and authoritative owner observation.
 * @param relation Final relation to the run's original group observation.
 * @param failure RTSP terminal evidence, or null when no failure was proven.
 * @param reachedPlay Whether this run has already reached playback.
 * @param originatingStopSequence Stop watermark captured by the originating watch pass.
 * @param enqueue Schedules the callback on the caller's main queue.
 * @param fence Reads live controller, source, run and owner facts at dispatch.
 * @param deliver Forwards evidence to the event arbiter.
 */
internal fun postSinkTerminal(
    token: SinkRunToken,
    relation: SinkGroupRelation,
    failure: RtspFailure?,
    reachedPlay: Boolean,
    originatingStopSequence: Long,
    enqueue: (Runnable) -> Unit,
    fence: () -> SinkTerminalFence,
    deliver: (SinkRunToken, RtspFailure) -> Unit,
) {
    val original = token.observation ?: return
    if (failure == null || reachedPlay || original.owner == WfdOwner.NONE || original.ownerRevision == null ||
        (relation != SinkGroupRelation.SAME && relation != SinkGroupRelation.DOWN)
    ) return
    enqueue(Runnable {
        val current = fence()
        if (current.activeGeneration != token.source.generation ||
            current.stopSequence != originatingStopSequence || current.userEnded || current.currentRun != token ||
            current.latestGroupSequence != token.source.groupSequence || !current.originalOwnerCurrent
        ) return@Runnable
        val expectedBinding = SinkSourceBinding(token.source, original)
        val bindingAllowed = if (relation == SinkGroupRelation.SAME) {
            current.observedBinding == expectedBinding ||
                (current.observedBinding == null && current.provenDownBinding == expectedBinding)
        } else {
            current.observedBinding == null
        }
        if (bindingAllowed) deliver(token, failure)
    })
}

/** Compares observations without interpreting unavailable V2 data as group loss. */
internal fun compareSinkGroup(
    original: OwnerGroupSnapshot,
    observed: OwnerGroupSnapshot?,
): SinkGroupRelation {
    if (observed == null || original.ownerRevision == null || observed.ownerRevision == null) {
        return SinkGroupRelation.UNKNOWN
    }
    if (original.owner != observed.owner || original.ownerRevision != observed.ownerRevision) {
        return SinkGroupRelation.SUPERSEDED
    }
    if (observed.state == P2pGroupState.Down) return SinkGroupRelation.DOWN
    // Peer name is advisory display metadata; only address/role change supersede a live group.
    val originalState = original.state
    val observedState = observed.state
    if (originalState is P2pGroupState.Up && observedState is P2pGroupState.Up &&
        originalState.sourceAddress == observedState.sourceAddress &&
        originalState.phoneIsOwner == observedState.phoneIsOwner
    ) {
        return if (original.epoch == observed.epoch) SinkGroupRelation.SAME else SinkGroupRelation.SUPERSEDED
    }
    return if (original.epoch == observed.epoch && original.state == observed.state) {
        SinkGroupRelation.SAME
    } else {
        SinkGroupRelation.SUPERSEDED
    }
}

/** Waits for an authoritative relation without treating unavailable observations as failure. */
internal fun awaitSinkGroupRelation(
    isCurrent: () -> Boolean,
    observe: () -> SinkGroupRelation,
    wait: () -> Unit,
): SinkGroupRelation {
    while (isCurrent()) {
        val relation = observe()
        if (relation != SinkGroupRelation.UNKNOWN) return relation
        wait()
    }
    return SinkGroupRelation.UNKNOWN
}

/** Dispatches only the captured observation; local acceptance is not group removal. */
internal fun dispatchSinkEnd(
    original: OwnerGroupSnapshot?,
    isCurrent: () -> Boolean,
    submit: (OwnerGroupSnapshot) -> OwnerEndSubmission,
    finishLocalStream: () -> Unit,
) {
    if (!isCurrent()) return
    original?.let { submit(it) }
    if (isCurrent()) finishLocalStream()
}
