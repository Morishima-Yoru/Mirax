package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import org.junit.Test

/** Tests the observation and guarded-END seam used by the controller. */
class SinkGroupObservationTest {
    private val epoch1 = "01234567-89ab-cdef-0123-456789abcdef-1"
    private val epoch2 = "01234567-89ab-cdef-0123-456789abcdef-2"
    private val pending = OwnerGroupSnapshot(WfdOwner.SHIZUKU, P2pGroupState.Pending, epoch1, 1L)
    private val up = pending.copy(state = P2pGroupState.Up("192.168.49.1"), epoch = epoch2)

    @Test
    fun pendingToUpAndFailedEndEpochReplaceTheOriginalObservation() {
        assertThat(compareSinkGroup(pending, up)).isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(compareSinkGroup(up, up.copy())).isEqualTo(SinkGroupRelation.SAME)
        assertThat(compareSinkGroup(up, up.copy(epoch = epoch1))).isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(compareSinkGroup(up, up.copy(state = P2pGroupState.Down))).isEqualTo(SinkGroupRelation.DOWN)
    }

    @Test
    fun sameGroupWithNewPeerNameKeepsLiveRun() {
        val named = up.copy(state = P2pGroupState.Up("192.168.49.1", sourceDeviceName = "Alice's Surface"))
        assertThat(compareSinkGroup(up, named)).isEqualTo(SinkGroupRelation.SAME)
        assertThat(compareSinkGroup(named, up)).isEqualTo(SinkGroupRelation.SAME)
        assertThat(compareSinkGroup(up, up.copy(state = P2pGroupState.Up("192.168.49.2"))))
            .isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(compareSinkGroup(up, up.copy(state = P2pGroupState.Up("192.168.49.1", phoneIsOwner = true))))
            .isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(compareSinkGroup(up, up.copy(state = P2pGroupState.Down))).isEqualTo(SinkGroupRelation.DOWN)
    }

    @Test
    fun sameOwnerRebindAndMalformedObservationsFailClosed() {
        assertThat(compareSinkGroup(up, up.copy(ownerRevision = 2L))).isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(compareSinkGroup(up, up.copy(owner = WfdOwner.HELPER))).isEqualTo(SinkGroupRelation.SUPERSEDED)
        val malformed = OwnerGroupSnapshot.parse(WfdOwner.SHIZUKU, "V2 bad DOWN")
        assertThat(compareSinkGroup(up, malformed)).isEqualTo(SinkGroupRelation.UNKNOWN)
        assertThat(compareSinkGroup(up, null)).isEqualTo(SinkGroupRelation.UNKNOWN)
    }

    @Test
    fun offerCommitRejectsReboundRouteAndUnknownBeforeClaim() {
        val adapter = SocketHelperAdapter { command ->
            if (command == "GROUP_V2") "V2 $epoch2 UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val relation = compareSinkGroup(original, route.snapshot())
        assertThat(relation).isEqualTo(SinkGroupRelation.SAME)
        val request = SinkOfferRequest<String>()
        assertThat(request.start()).isTrue()
        route.updateEndpoint(WfdOwner.HELPER) {}
        assertThat(beginSinkOfferCommit(request, relation, { true }) { route.isCurrent(original) }).isFalse()
        assertThat(request.deadlineDecision()).isEqualTo(SinkOfferDecision.PreCommitCancelled)

        val current = checkNotNull(route.snapshot())
        val unknown = SinkOfferRequest<String>()
        assertThat(unknown.start()).isTrue()
        assertThat(beginSinkOfferCommit(unknown, SinkGroupRelation.UNKNOWN, { true }) { route.isCurrent(current) })
            .isFalse()
        assertThat(unknown.deadlineDecision()).isEqualTo(SinkOfferDecision.PreCommitCancelled)

        val accepted = SinkOfferRequest<String>()
        assertThat(accepted.start()).isTrue()
        assertThat(beginSinkOfferCommit(accepted, SinkGroupRelation.SAME, { true }) { route.isCurrent(current) })
            .isTrue()
        assertThat(accepted.deadlineDecision()).isEqualTo(SinkOfferDecision.CommittingPending)
        assertThat(accepted.complete("offer")).isTrue()
    }

    /** Live DOWN at claim time must reject even when the owner revision is still current. */
    @Test
    fun offerCommitRejectsLiveDownWhileOwnerRevisionStillCurrent() {
        var groupLine = "V2 $epoch2 UP 192.168.49.1"
        val adapter = SocketHelperAdapter { command ->
            if (command == "GROUP_V2") groupLine else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val staleSame = compareSinkGroup(original, route.snapshot())
        assertThat(staleSame).isEqualTo(SinkGroupRelation.SAME)

        groupLine = "V2 $epoch2 DOWN"
        assertThat(route.isCurrent(original)).isTrue()
        val liveRelation = compareSinkGroup(original, route.snapshot())
        assertThat(liveRelation).isEqualTo(SinkGroupRelation.DOWN)

        val request = SinkOfferRequest<String>()
        assertThat(request.start()).isTrue()
        assertThat(
            beginSinkOfferCommit(request, liveRelation, { true }) { route.isCurrent(original) },
        ).isFalse()
        assertThat(request.deadlineDecision()).isEqualTo(SinkOfferDecision.PreCommitCancelled)
    }

    @Test
    fun userEndSelectsRegisteredSourceNotNewlyObservedReplacement() {
        val first = SinkAttemptSource(1, 1L)
        val second = SinkAttemptSource(1, 2L)
        val registered = SinkSourceBinding(first, pending)
        val observed = SinkSourceBinding(second, up)
        assertThat(selectSinkEndObservation(first, registered, observed)).isEqualTo(pending)
        assertThat(selectSinkEndObservation(second, registered, observed)).isEqualTo(up)
        assertThat(selectSinkEndObservation(null, registered, observed)).isNull()
    }

    @Test
    fun busyUserEndRetriesOnlyOriginalAfterBlockedSnapshotCompletes() {
        val busy = AtomicBoolean(false)
        val hold = AtomicBoolean(false)
        val held = AtomicReference<Runnable?>()
        val entered = CountDownLatch(1)
        val executor = Executor { task ->
            if (!busy.compareAndSet(false, true)) throw RejectedExecutionException()
            val wrapped = Runnable {
                try {
                    task.run()
                } finally {
                    busy.set(false)
                }
            }
            if (hold.get()) {
                held.set(wrapped)
                entered.countDown()
            } else {
                wrapped.run()
            }
        }
        val commands = mutableListOf<String>()
        var groupEpoch = epoch1
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $groupEpoch UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = executor, snapshotTimeoutMs = 5_000) {
            owner -> if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        hold.set(true)
        val snapshotThread = Thread { route.snapshot() }
        snapshotThread.start()
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val userEnd = SinkUserEnd()
            userEnd.begin(original, route::submitEnd)
            userEnd.begin(null, route::submitEnd)
            assertThat(userEnd.get()).isTrue()
            assertThat(commands).containsExactly("GROUP_V2")
            hold.set(false)
            checkNotNull(held.getAndSet(null)).run()
            snapshotThread.join(2_000)
            val same = checkNotNull(route.snapshot())
            val retries = java.util.ArrayDeque<() -> Unit>()
            assertThat(userEnd.observe(same, route::submitEnd) { retries.addLast(it); true }).isFalse()
            assertThat(userEnd.observe(same, route::submitEnd) { retries.addLast(it); true }).isFalse()
            assertThat(retries).hasSize(1)
            assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(0)
            retries.removeFirst().invoke()
            assertThat(userEnd.observe(same, route::submitEnd) { retries.addLast(it); true }).isFalse()
            assertThat(retries).isEmpty()
            assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
            assertThat(commands).contains("END_V2 $epoch1")
            groupEpoch = epoch2
            assertThat(userEnd.observe(checkNotNull(route.snapshot()), route::submitEnd)).isTrue()
            userEnd.clear()
            assertThat(userEnd.get()).isFalse()
            assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
        } finally {
            hold.set(false)
            held.getAndSet(null)?.run()
            snapshotThread.join(2_000)
        }
    }

    @Test
    fun userEndDoesNotRetryAcceptedUnknownOrAReboundRevision() {
        val commands = mutableListOf<String>()
        var groupEpoch = epoch1
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $groupEpoch UP 192.168.49.1" else "ERR unknown"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val accepted = SinkUserEnd()
        accepted.begin(original, route::submitEnd)
        assertThat(accepted.observe(checkNotNull(route.snapshot()), route::submitEnd)).isFalse()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)

        val waitingEpoch = SinkUserEnd()
        waitingEpoch.begin(original, { _ -> OwnerEndSubmission.BUSY })
        assertThat(waitingEpoch.observe(original.copy(ownerRevision = null), route::submitEnd)).isFalse()
        val retries = java.util.ArrayDeque<() -> Unit>()
        assertThat(waitingEpoch.observe(checkNotNull(route.snapshot()), route::submitEnd) {
            retries.addLast(it)
            true
        }).isFalse()
        groupEpoch = epoch2
        assertThat(waitingEpoch.observe(checkNotNull(route.snapshot()), route::submitEnd)).isTrue()
        retries.removeFirst().invoke()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)

        groupEpoch = epoch1
        val waitingRevision = SinkUserEnd()
        waitingRevision.begin(original, { _ -> OwnerEndSubmission.BUSY })
        route.updateEndpoint(WfdOwner.HELPER) {}
        val replacement = checkNotNull(route.snapshot())
        assertThat(waitingRevision.observe(replacement, route::submitEnd)).isTrue()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
        waitingRevision.clear()
        assertThat(waitingRevision.get()).isFalse()
    }

    @Test
    fun automaticEndRetriesBusyAfterSnapshotAndFinishesLocallyOnce() {
        val busy = AtomicBoolean(false)
        val hold = AtomicBoolean(false)
        val held = AtomicReference<Runnable?>()
        val entered = CountDownLatch(1)
        val executor = Executor { task ->
            if (!busy.compareAndSet(false, true)) throw RejectedExecutionException()
            val wrapped = Runnable {
                try {
                    task.run()
                } finally {
                    busy.set(false)
                }
            }
            if (hold.get()) {
                held.set(wrapped)
                entered.countDown()
            } else {
                wrapped.run()
            }
        }
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $epoch1 UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = executor, snapshotTimeoutMs = 5_000) {
            owner -> if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val token = SinkRunToken(SinkAttemptSource(3, 4L), 5L, original)
        val arbiter = SinkEventArbiter(
            onAction = {}, onAttempt = {}, onDrop = {}, onTerminal = { _, _ -> }, isMainThread = { true },
        )
        arbiter.start(token.source.generation)
        assertThat(arbiter.accept(token.source)).isTrue()
        assertThat(arbiter.begin(token, "192.168.49.1")).isTrue()
        assertThat(arbiter.finish(token, succeeded = true, selected = null, touch = null)).isTrue()
        val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
            stop == 7L && arbiter.generation == candidate.source.generation && arbiter.isSource(candidate.source) &&
                arbiter.isRun(candidate) && candidate.observation?.let(route::isCurrent) == true
        }
        val automatic = SinkAutomaticEnd()
        val retries = java.util.ArrayDeque<() -> Unit>()
        var finished = 0
        hold.set(true)
        val snapshotThread = Thread { route.snapshot() }
        snapshotThread.start()
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            dispatchSinkEnd(original, { isCurrent(token, 7L) }, {
                automatic.submit(token, 7L, isCurrent, route::submitEnd)
            }) { finished++ }
            assertThat(finished).isEqualTo(1)
            assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(0)
            hold.set(false)
            checkNotNull(held.getAndSet(null)).run()
            snapshotThread.join(2_000)
            val same = checkNotNull(route.snapshot())
            repeat(2) {
                automatic.observe(same, isCurrent, route::submitEnd) { retry ->
                    retries.addLast(retry)
                    true
                }
            }
            assertThat(retries).hasSize(1)
            retries.removeFirst().invoke()
            automatic.observe(same, isCurrent, route::submitEnd) { retry ->
                retries.addLast(retry)
                true
            }
            // ACCEPTED after BUSY still needs recovery while the group remains UP.
            assertThat(retries).hasSize(1)
            assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
            assertThat(commands).contains("END_V2 $epoch1")
            assertThat(finished).isEqualTo(1)
        } finally {
            hold.set(false)
            held.getAndSet(null)?.run()
            snapshotThread.join(2_000)
        }
    }

    @Test
    fun automaticAcceptedRetriesWhileGroupStillUp() {
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $epoch1 UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val token = SinkRunToken(SinkAttemptSource(1, 1L), 1L, original)
        val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
            candidate == token && stop == 1L && route.isCurrent(original)
        }
        val automatic = SinkAutomaticEnd()
        val retries = java.util.ArrayDeque<() -> Unit>()
        assertThat(automatic.submit(token, 1L, isCurrent, route::submitEnd))
            .isEqualTo(OwnerEndSubmission.ACCEPTED)
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
        val same = checkNotNull(route.snapshot())
        repeat(2) {
            automatic.observe(same, isCurrent, route::submitEnd) { retry ->
                retries.addLast(retry)
                true
            }
        }
        assertThat(retries).hasSize(1)
        retries.removeFirst().invoke()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(2)
        automatic.observe(same, isCurrent, route::submitEnd) { retry ->
            retries.addLast(retry)
            true
        }
        assertThat(retries).hasSize(1)
        assertThat(commands.filter { it.startsWith("END_V2") }).containsExactly(
            "END_V2 $epoch1",
            "END_V2 $epoch1",
        )
    }

    @Test
    fun automaticAcceptedDoesNotRetryAfterGroupDown() {
        val commands = mutableListOf<String>()
        var groupLine = "V2 $epoch1 UP 192.168.49.1"
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") groupLine else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val token = SinkRunToken(SinkAttemptSource(1, 1L), 1L, original)
        val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
            candidate == token && stop == 1L && candidate.observation?.let(route::isCurrent) == true
        }
        val automatic = SinkAutomaticEnd()
        assertThat(automatic.submit(token, 1L, isCurrent, route::submitEnd))
            .isEqualTo(OwnerEndSubmission.ACCEPTED)
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
        groupLine = "V2 $epoch1 DOWN"
        automatic.observe(checkNotNull(route.snapshot()), isCurrent, route::submitEnd) {
            error("DOWN after ACCEPTED must not schedule a retry")
        }
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
    }

    @Test
    fun automaticAcceptedRetryHonorsStaleCurrentRunGuards() {
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $epoch1 UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val token = SinkRunToken(SinkAttemptSource(1, 1L), 1L, original)
        var stopSequence = 1L
        val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
            candidate == token && stop == stopSequence && route.isCurrent(original)
        }
        val automatic = SinkAutomaticEnd()
        assertThat(automatic.submit(token, stopSequence, isCurrent, route::submitEnd))
            .isEqualTo(OwnerEndSubmission.ACCEPTED)
        val retries = java.util.ArrayDeque<() -> Unit>()
        automatic.observe(original, isCurrent, route::submitEnd) { retry ->
            retries.addLast(retry)
            true
        }
        stopSequence++
        retries.removeFirst().invoke()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(1)
        automatic.observe(original, isCurrent, route::submitEnd) {
            error("stale stop watermark must clear ACCEPTED recovery")
        }
    }

    @Test
    fun automaticRetryIsCancelledBeforeReplacementAndUserEnd() {
        val commands = mutableListOf<String>()
        var epoch = epoch1
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") "V2 $epoch UP 192.168.49.1" else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val token = SinkRunToken(SinkAttemptSource(1, 1L), 1L, original)
        var currentToken = token
        var stopSequence = 1L
        val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
            candidate == currentToken && stop == stopSequence && candidate.observation?.let(route::isCurrent) == true
        }
        val automatic = SinkAutomaticEnd()
        val retries = java.util.ArrayDeque<() -> Unit>()
        assertThat(automatic.submit(token, 1L, isCurrent) { OwnerEndSubmission.BUSY })
            .isEqualTo(OwnerEndSubmission.BUSY)
        automatic.observe(checkNotNull(route.snapshot()), isCurrent, route::submitEnd) { retry ->
            retries.addLast(retry)
            true
        }
        epoch = epoch2
        val replacement = checkNotNull(route.snapshot())
        automatic.observe(replacement, isCurrent, route::submitEnd) { retry ->
            retries.addLast(retry)
            true
        }
        retries.removeFirst().invoke()
        assertThat(commands.count { it.startsWith("END_V2") }).isEqualTo(0)

        val replacementToken = SinkRunToken(SinkAttemptSource(1, 2L), 2L, replacement)
        currentToken = replacementToken
        stopSequence++
        assertThat(automatic.submit(replacementToken, stopSequence, isCurrent) { OwnerEndSubmission.BUSY })
            .isEqualTo(OwnerEndSubmission.BUSY)
        automatic.observe(replacement, isCurrent, route::submitEnd) { retry ->
            retries.addLast(retry)
            true
        }
        stopSequence++
        automatic.clear()
        val user = SinkUserEnd()
        user.begin(replacement, route::submitEnd)
        retries.removeFirst().invoke()
        assertThat(commands.filter { it.startsWith("END_V2") }).containsExactly("END_V2 $epoch2")
    }

    @Test
    fun automaticRetryRechecksStopRunSourceGenerationAndOwnerRevision() {
        repeat(5) { changed ->
            val commands = mutableListOf<String>()
            val adapter = SocketHelperAdapter { command ->
                commands.add(command)
                if (command == "GROUP_V2") "V2 $epoch1 UP 192.168.49.1" else "QUEUED"
            }
            val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
                if (owner == WfdOwner.HELPER) adapter else null
            }
            route.switchOwner(WfdOwner.HELPER)
            val original = checkNotNull(route.snapshot())
            val token = SinkRunToken(SinkAttemptSource(1, 1L), 1L, original)
            val arbiter = SinkEventArbiter(
                onAction = {}, onAttempt = {}, onDrop = {}, onTerminal = { _, _ -> }, isMainThread = { true },
            )
            arbiter.start(1)
            assertThat(arbiter.accept(token.source)).isTrue()
            assertThat(arbiter.begin(token, "192.168.49.1")).isTrue()
            assertThat(arbiter.finish(token, succeeded = false, selected = null, touch = null)).isTrue()
            var stopSequence = 1L
            val isCurrent: (SinkRunToken, Long) -> Boolean = { candidate, stop ->
                stop == stopSequence && arbiter.generation == candidate.source.generation &&
                    arbiter.isSource(candidate.source) && arbiter.isRun(candidate) &&
                    candidate.observation?.let(route::isCurrent) == true
            }
            val automatic = SinkAutomaticEnd()
            assertThat(automatic.submit(token, stopSequence, isCurrent) { OwnerEndSubmission.BUSY })
                .isEqualTo(OwnerEndSubmission.BUSY)
            val retries = java.util.ArrayDeque<() -> Unit>()
            automatic.observe(original, isCurrent, route::submitEnd) { retry ->
                retries.addLast(retry)
                true
            }
            when (changed) {
                0 -> stopSequence++
                1 -> assertThat(arbiter.begin(token.copy(sequence = 2L), "192.168.49.1")).isTrue()
                2 -> assertThat(arbiter.accept(SinkAttemptSource(1, 2L))).isTrue()
                3 -> arbiter.start(2)
                else -> route.updateEndpoint(WfdOwner.HELPER) {}
            }
            retries.removeFirst().invoke()
            assertThat(commands.filter { it.startsWith("END_V2") }).isEmpty()
        }
    }

    @Test
    fun controllerEndSeamRespectsRealSameOwnerRevisionGate() {
        var legacyEnds = 0
        val remoteEnds = mutableListOf<OwnerGroupSnapshot>()
        val transport = object : WfdOwnerTransport {
            override val owner = WfdOwner.SHIZUKU
            override fun advertise(command: WfdAdvertiseCommand): Boolean = true
            override fun stopAdvertise() = Unit
            override fun isListening(): Boolean = true
            override fun groupState(): String? = null
            override fun groupSnapshotV2(): OwnerGroupSnapshot = up.copy(ownerRevision = null)
            override fun endSessionIfEpoch(snapshot: OwnerGroupSnapshot): Boolean {
                remoteEnds.add(snapshot)
                return true
            }
            override fun endSession() { legacyEnds++ }
            override fun forgetAllPairings() = Unit
            override fun pairingState(): String = "PAIRED"
            override fun wmSize(displayId: Int?): String? = null
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.SHIZUKU) transport else null
        }
        route.switchOwner(WfdOwner.SHIZUKU)
        val original = checkNotNull(route.snapshot())
        val queued = mutableListOf<() -> Unit>()
        val submitted = mutableListOf<OwnerGroupSnapshot>()
        var finished = 0
        queued.add {
            dispatchSinkEnd(original, { true }, { snapshot ->
                submitted.add(snapshot)
                route.submitEnd(snapshot)
            }) { finished++ }
        }
        route.updateEndpoint(WfdOwner.SHIZUKU) {}
        queued.removeAt(0).invoke()
        assertThat(submitted).containsExactly(original)
        assertThat(remoteEnds).isEmpty()
        assertThat(legacyEnds).isEqualTo(0)
        assertThat(finished).isEqualTo(1)
        val fresh = checkNotNull(route.snapshot())
        dispatchSinkEnd(fresh, { true }, route::submitEnd) { finished++ }
        assertThat(remoteEnds).containsExactly(fresh)
        assertThat(legacyEnds).isEqualTo(0)
        assertThat(finished).isEqualTo(2)
    }

    @Test
    fun queuedEndUsesOriginalAndNeverConsumesReplacement() {
        var localIsOriginal = true
        var routeRevision = 1L
        var localFinishes = 0
        val submitted = mutableListOf<OwnerGroupSnapshot>()
        val queue = mutableListOf<() -> Unit>()
        fun enqueue() {
            queue.add {
                dispatchSinkEnd(
                    original = up,
                    isCurrent = { localIsOriginal },
                    submit = { original ->
                        submitted.add(original)
                        if (original.ownerRevision == routeRevision) OwnerEndSubmission.ACCEPTED else OwnerEndSubmission.STALE
                    },
                    finishLocalStream = { localFinishes++ },
                )
            }
        }
        enqueue()
        routeRevision = 2L
        queue.removeAt(0).invoke()
        assertThat(submitted).containsExactly(up)
        assertThat(localFinishes).isEqualTo(1)

        enqueue()
        localIsOriginal = false
        queue.removeAt(0).invoke()
        assertThat(submitted).containsExactly(up)
        assertThat(localFinishes).isEqualTo(1)

        localIsOriginal = true
        routeRevision = 1L
        enqueue()
        queue.removeAt(0).invoke()
        assertThat(submitted).containsExactly(up, up).inOrder()
        assertThat(localFinishes).isEqualTo(2)
    }
}
