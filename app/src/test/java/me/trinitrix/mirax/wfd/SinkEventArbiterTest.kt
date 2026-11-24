package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ConnectionRunFact
import me.trinitrix.mirax.session.ExpiryMessageQueue
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.PrePlayAttemptId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.PrePlayFailureEpisodeOwner
import me.trinitrix.mirax.session.PrePlayNonFailureReason
import me.trinitrix.mirax.session.PrePlayPreemptReason
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.TestConnectionHistory
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.wfd.rtsp.RtspFailure
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertThrows
import org.junit.Test

/** Integration coverage for arbiter gates, real session history, and failure ownership. */
class SinkEventArbiterTest {
    private class FakeQueue : ExpiryMessageQueue {
        var pending: Runnable? = null
        var delayMs: Long? = null

        override fun postDelayed(delayMs: Long, task: Runnable): Boolean {
            pending = task
            this.delayMs = delayMs
            return true
        }

        override fun removeCallbacks(task: Runnable) {
            if (pending === task) {
                pending = null
                delayMs = null
            }
        }

        fun fire() {
            val task = checkNotNull(pending)
            pending = null
            delayMs = null
            task.run()
        }
    }

    private class Fixture {
        var now = 1_000L
        var onMainThread = true
        val queue = FakeQueue()
        val owner = PrePlayFailureEpisodeOwner(
            nowMs = { now },
            messageQueue = queue,
            isOwnerThread = { true },
        )
        val history = TestConnectionHistory()
        val session = MiraxSession(connectionHistory = history)
        val actions = mutableListOf<SessionAction>()
        val attempts = mutableListOf<SinkAttemptSource>()
        val drops = mutableListOf<SinkAttemptSource>()
        val terminals = mutableListOf<Pair<RtspFailure, SinkAttemptSource>>()
        val dialTimeouts = mutableListOf<SinkAttemptSource>()
        var afterAction: (SessionAction) -> Unit = {}
        var afterAttempt: (SinkAttemptSource) -> Unit = {}
        var afterDrop: (SinkAttemptSource) -> Unit = {}
        var afterTerminal: (RtspFailure, SinkAttemptSource) -> Unit = { _, _ -> }
        private var nextAttempt = 0L

        val arbiter = SinkEventArbiter(
            onAction = { action ->
                actions.add(action)
                session.handle(action)
                afterAction(action)
            },
            onAttempt = { source ->
                attempts.add(source)
                nextAttempt += 1
                owner.onPrePlayAttemptStarted(PrePlayAttemptId(nextAttempt), source)
                afterAttempt(source)
            },
            onDrop = { source ->
                drops.add(source)
                owner.onPrePlayNonFailure(PrePlayNonFailureReason.UNKNOWN_DROP, source)
                afterDrop(source)
            },
            onTerminal = { failure, source ->
                terminals.add(failure to source)
                owner.onPrePlayTerminal(failure, source)
                afterTerminal(failure, source)
            },
            onDialTimeout = { evidence, source ->
                dialTimeouts.add(source)
                owner.onTcpEstablishmentTimeout(evidence, source)
            },
            isMainThread = { onMainThread },
        )
    }

    private val timeout = RtspFailure.Timeout("M2", 2)
    private val selected = VideoMode(1280, 720, 30)

    @Test
    fun oldGenerationCannotPolluteNewRunOrHistory() {
        val f = Fixture()
        val oldSource = SinkAttemptSource(1, 1L)
        val newSource = SinkAttemptSource(2, 2L)
        val oldRun = SinkRunToken(oldSource, 1L)
        val newRun = SinkRunToken(newSource, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(oldSource)).isTrue()
        assertThat(f.arbiter.begin(oldRun, "old-host")).isTrue()
        assertThat(f.arbiter.log(oldRun, "old line")).isTrue()
        assertThat(f.arbiter.action(oldRun, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.offerFrozen(oldRun, false)).isTrue()

        f.arbiter.start(2)
        assertThat(f.arbiter.accept(SinkAttemptSource(1, 3L))).isFalse()
        assertThat(f.arbiter.accept(newSource)).isTrue()
        assertThat(f.arbiter.begin(SinkRunToken(newSource, 1L), "reused-sequence")).isFalse()
        assertThat(f.arbiter.begin(newRun, "new-host")).isTrue()
        assertThat(f.arbiter.log(newRun, "new line")).isTrue()
        val actionCount = f.actions.size
        assertThat(f.arbiter.action(oldRun, SessionAction.EnteredPlay)).isFalse()
        assertThat(f.arbiter.log(oldRun, "stale line")).isFalse()
        assertThat(f.arbiter.finish(oldRun, true, null, true)).isFalse()
        assertThat(f.arbiter.drop(oldSource, oldRun)).isFalse()
        assertThat(f.arbiter.terminal(oldRun, timeout)).isFalse()
        assertThat(f.actions).hasSize(actionCount)
        assertThat(f.history.activeRun()?.remoteHost).isEqualTo("new-host")
        assertThat(f.history.activeRun()?.log).isEqualTo("new line")
        assertThat(f.terminals).isEmpty()
        assertThat(f.drops).isEmpty()
        assertThat(f.history.runs().single().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
        assertThat(f.arbiter.finish(newRun, true, null, true)).isTrue()
        assertThat(f.history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
    }

    @Test
    fun groupRolloverClosesOldRunAndRejectsOldSource() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(7, 10L)
        val secondSource = SinkAttemptSource(7, 11L)
        val first = SinkRunToken(firstSource, 10L)
        val second = SinkRunToken(secondSource, 11L)
        f.arbiter.start(7)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.accept(firstSource)).isFalse()
        assertThat(f.arbiter.accept(SinkAttemptSource(7, 0L))).isFalse()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(f.arbiter.action(first, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.offerFrozen(first, false)).isTrue()
        assertThat(f.arbiter.accept(secondSource)).isTrue()
        assertThat(f.arbiter.isSource(firstSource)).isFalse()
        assertThat(f.arbiter.isRun(first)).isFalse()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.log(first, "wrong group")).isFalse()
        assertThat(f.arbiter.drop(firstSource, first)).isFalse()
        assertThat(f.arbiter.terminal(first, timeout)).isFalse()
        assertThat(f.attempts).containsExactly(firstSource, secondSource).inOrder()
        assertThat(f.history.runs().single().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
        assertThat(f.history.activeRun()?.remoteHost).isEqualTo("second")
        assertThat(f.history.activeRun()?.log).isEmpty()
    }

    @Test
    fun newerRunInSameGroupRejectsEveryOldRunEvent() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(SinkRunToken(source, 0L), "zero")).isFalse()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(f.arbiter.log(first, "first log")).isTrue()
        assertThat(f.arbiter.action(first, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.offerFrozen(first, false)).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.history.runs().single().succeeded).isFalse()
        assertThat(f.history.runs().single().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
        val actionCount = f.actions.size
        assertThat(f.arbiter.begin(first, "duplicate")).isFalse()
        assertThat(f.arbiter.action(second, SessionAction.BeginConnectionRun("bypass"))).isFalse()
        assertThat(f.arbiter.action(second, SessionAction.AppendConnectionLog("bypass"))).isFalse()
        assertThat(f.arbiter.action(second, SessionAction.FinishConnectionRun(true))).isFalse()
        assertThat(f.arbiter.log(first, "wrong run")).isFalse()
        assertThat(f.arbiter.finish(first, true, selected, true)).isFalse()
        assertThat(f.arbiter.action(first, SessionAction.EnteredPlay)).isFalse()
        assertThat(f.arbiter.offerFrozen(first, true)).isFalse()
        assertThat(f.arbiter.drop(source, first)).isFalse()
        assertThat(f.arbiter.drop(source)).isFalse()
        assertThat(f.arbiter.terminal(first, timeout)).isFalse()
        assertThat(f.actions).hasSize(actionCount)
        assertThat(f.history.activeRun()?.remoteHost).isEqualTo("second")
        assertThat(f.history.activeRun()?.log).isEmpty()
        assertThat(f.arbiter.log(second, "second log")).isTrue()
        assertThat(f.arbiter.action(second, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.finish(second, false, null, false)).isTrue()
        assertThat(f.arbiter.isRun(second)).isTrue()
        assertThat(f.arbiter.log(second, "after finish")).isFalse()
        assertThat(f.arbiter.action(second, SessionAction.EnteredPlay)).isFalse()
        assertThat(f.arbiter.finish(second, false, null, false)).isFalse()
        assertThat(f.arbiter.terminal(second, timeout)).isTrue()
        assertThat(f.arbiter.drop(source, second)).isTrue()
        assertThat(f.arbiter.terminal(second, timeout)).isFalse()
        assertThat(f.arbiter.drop(source, second)).isFalse()
        assertThat(f.history.runs().first().log).isEqualTo("second log")
        assertThat(f.history.runs().first().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
    }

    @Test
    fun sameGroupRetryReceivesNewAttemptAndRejectsOldTerminal() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        assertThat(f.arbiter.terminal(first, timeout)).isTrue()
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(1L))
        val staleTimer = checkNotNull(f.queue.pending)

        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.attempts).containsExactly(source, source).inOrder()
        assertThat(f.owner.currentAttempt()).isEqualTo(PrePlayAttemptId(2L))
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.arbiter.terminal(first, timeout)).isFalse()
        assertThat(f.arbiter.drop(source, first)).isFalse()
        val rejected = RtspFailure.Rejected("M3", 3, 403)
        assertThat(f.arbiter.terminal(second, rejected)).isTrue()
        staleTimer.run()
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(2L))
        assertThat(f.owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.ESTABLISHMENT_FAILED)
    }

    @Test
    fun reentrantRunDuringRolloverGetsItsOwnAttempt() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val interrupted = SinkRunToken(source, 2L)
        val replacement = SinkRunToken(source, 3L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        assertThat(f.arbiter.terminal(first, timeout)).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) {
                assertThat(f.arbiter.begin(replacement, "replacement")).isTrue()
            }
        }
        assertThat(f.arbiter.begin(interrupted, "interrupted")).isFalse()
        assertThat(f.owner.currentAttempt()).isEqualTo(PrePlayAttemptId(2L))
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.arbiter.terminal(replacement, timeout)).isTrue()
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(2L))
        assertThat(f.arbiter.terminal(first, timeout)).isFalse()
    }

    @Test
    fun tokenlessDropCannotInterruptRolloverDuringCloseCallback() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) {
                assertThat(f.arbiter.drop(source)).isFalse()
            }
        }
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.isRun(second)).isTrue()
        assertThat(f.drops).isEmpty()
        assertThat(f.owner.currentAttempt()).isEqualTo(PrePlayAttemptId(2L))
    }

    @Test
    fun retryAttemptCallbackCanStopBeforeNewRunBegins() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.afterAttempt = { f.arbiter.stop() }
        assertThat(f.arbiter.begin(second, "second")).isFalse()
        assertThat(f.history.activeRun()).isNull()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("first")
    }

    @Test
    fun sameGroupRolloverPreservesPlayedOutcomeAndFacts() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(f.arbiter.action(first, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.offerFrozen(first, false)).isTrue()
        assertThat(f.arbiter.action(first, SessionAction.EnteredPlay)).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.history.runs().single().succeeded).isTrue()
        assertThat(f.history.runs().single().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
    }

    @Test
    fun pairingWithoutBeginCannotFinishAHistoryRun() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val neverStarted = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.finish(neverStarted, false, null, true)).isFalse()
        assertThat(f.arbiter.log(neverStarted, "pairing")).isFalse()
        assertThat(f.arbiter.terminal(neverStarted, timeout)).isFalse()
        assertThat(f.arbiter.drop(source)).isTrue()
        assertThat(f.history.runs()).isEmpty()
        assertThat(f.history.activeRun()).isNull()
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).isEmpty()
    }

    @Test
    fun sourceAcceptanceFenceRejectsNewerSourceOrRun() {
        val f = Fixture()
        val first = SinkAttemptSource(1, 1L)
        val second = SinkAttemptSource(1, 2L)
        val firstRun = SinkRunToken(first, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(first, null, null)).isTrue()
        assertThat(f.arbiter.accept(second, null, null)).isFalse()
        assertThat(f.arbiter.begin(firstRun, "first")).isTrue()
        assertThat(f.arbiter.accept(second, first, null)).isFalse()
        assertThat(f.arbiter.isRun(firstRun)).isTrue()
        assertThat(f.arbiter.accept(second, first, firstRun)).isTrue()
        assertThat(f.attempts).containsExactly(first, second).inOrder()
    }

    @Test
    fun sourceRegistrationCancelsOnlyBeforeCommitAndCleansUpAfterGroupLoss() {
        val pending = SinkSourceRegistration()
        assertThat(pending.cancelBeforeCommit()).isTrue()
        assertThat(pending.mayStart()).isFalse()
        assertThat(pending.beginCommit()).isFalse()
        assertThat(pending.complete(false)).isFalse()
        assertThat(pending.result()).isFalse()

        val started = SinkSourceRegistration()
        assertThat(started.mayStart()).isTrue()
        assertThat(started.cancelBeforeCommit()).isTrue()
        assertThat(started.beginCommit()).isFalse()

        val committed = SinkSourceRegistration()
        assertThat(committed.mayStart()).isTrue()
        assertThat(committed.beginCommit()).isTrue()
        assertThat(committed.cancelBeforeCommit()).isFalse()
        assertThat(committed.result()).isNull()
        assertThat(committed.complete(true)).isFalse()
        assertThat(committed.result()).isTrue()

        val dropped = SinkSourceRegistration()
        assertThat(dropped.beginCommit()).isTrue()
        dropped.abandon()
        assertThat(dropped.complete(true)).isTrue()
    }

    @Test
    fun sourceRegistrationOnProductionArbiterCancelsOrCleansGroupLoss() {
        val cancelled = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val pending = SinkSourceRegistration()
        cancelled.arbiter.start(1)
        assertThat(pending.cancelBeforeCommit()).isTrue()
        cancelled.arbiter.registerSource(source, null, null, pending, allowed = true)
        assertThat(cancelled.attempts).isEmpty()
        assertThat(cancelled.actions).isEmpty()
        assertThat(pending.result()).isFalse()

        val dropped = Fixture()
        val inFlight = SinkSourceRegistration()
        dropped.arbiter.start(1)
        dropped.afterAttempt = { inFlight.abandon() }
        dropped.arbiter.registerSource(source, null, null, inFlight, allowed = true)
        assertThat(inFlight.result()).isFalse()
        assertThat(dropped.attempts).containsExactly(source)
        assertThat(dropped.drops).containsExactly(source)
        assertThat(dropped.arbiter.isSource(source)).isFalse()
        assertThat(dropped.history.runs()).isEmpty()
    }

    @Test
    fun supersededRegistrationRetiresWithoutReportingPhysicalDown() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val request = SinkSourceRegistration()
        f.arbiter.start(1)
        f.afterAttempt = { request.supersede() }
        f.arbiter.registerSource(source, null, null, request, allowed = true)
        assertThat(request.result()).isFalse()
        assertThat(f.arbiter.isSource(source)).isFalse()
        assertThat(f.drops).isEmpty()
        assertThat(f.actions).doesNotContain(SessionAction.PrePlayProgress)
        assertThat(f.actions).doesNotContain(SessionAction.PrePlayGroupDropped)
    }

    @Test
    fun blockedProgressKeepsTheSameRegistrationUntilCompletion() = exerciseProgressBarrier(false)

    @Test
    fun downDuringBlockedProgressCleansTheOriginalRegistrationOnce() = exerciseProgressBarrier(true)

    private fun exerciseProgressBarrier(drop: Boolean) {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val registration = SinkSourceRegistration()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.arbiter.start(1)
        f.afterAction = { action ->
            if (action == SessionAction.PrePlayProgress) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val callback = Thread {
            try {
                f.arbiter.registerSource(source, null, null, registration, allowed = true)
            } catch (err: Throwable) {
                failure.set(err)
            }
        }
        callback.isDaemon = true
        try {
            callback.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(registration.cancelBeforeCommit()).isFalse()
            assertThat(registration.result()).isNull()
            assertThat(f.attempts).containsExactly(source)
            assertThat(f.drops).isEmpty()
            if (drop) registration.abandon()
            release.countDown()
            callback.join(5_000L)
            assertThat(callback.isAlive).isFalse()
            assertThat(failure.get()).isNull()
            assertThat(registration.result()).isTrue()
            assertThat(f.attempts).containsExactly(source)
            if (drop) {
                assertThat(f.drops).containsExactly(source)
                assertThat(f.arbiter.isSource(source)).isFalse()
            } else {
                assertThat(f.drops).isEmpty()
                assertThat(f.arbiter.isSource(source)).isTrue()
            }
        } finally {
            release.countDown()
            callback.join(5_000L)
        }
    }

    @Test
    fun committedSourceRegistrationSurvivesTimeoutWithoutDoubleAttempt() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val request = SinkSourceRegistration()
        f.arbiter.start(1)
        f.afterAttempt = { assertThat(request.cancelBeforeCommit()).isFalse() }
        f.arbiter.registerSource(source, null, null, request, allowed = true)
        assertThat(request.result()).isTrue()
        assertThat(f.arbiter.isSource(source)).isTrue()
        assertThat(f.attempts).containsExactly(source)
        assertThat(f.drops).isEmpty()
    }

    @Test
    fun abandonedRegistrationDoesNotDropAReentrantNewSource() {
        val f = Fixture()
        val first = SinkAttemptSource(1, 1L)
        val replacement = SinkAttemptSource(1, 2L)
        val request = SinkSourceRegistration()
        f.arbiter.start(1)
        f.afterAttempt = { source ->
            if (source == first) {
                request.abandon()
                assertThat(f.arbiter.accept(replacement)).isTrue()
            }
        }
        f.arbiter.registerSource(first, null, null, request, allowed = true)
        assertThat(f.arbiter.isSource(replacement)).isTrue()
        assertThat(f.owner.currentAttempt()).isEqualTo(PrePlayAttemptId(2L))
        assertThat(f.drops).isEmpty()
    }

    @Test
    fun offerRequestRejectsPendingAndAlreadyStartedCallbacksAfterCancel() {
        val pending = SinkOfferRequest<Unit>()
        assertThat(pending.cancel()).isTrue()
        assertThat(pending.start()).isFalse()
        assertThat(pending.isActive()).isFalse()

        val started = SinkOfferRequest<Unit>()
        assertThat(started.start()).isTrue()
        assertThat(started.start()).isFalse()
        assertThat(started.cancel()).isTrue()
        assertThat(started.isActive()).isFalse()
        assertThat(started.beginCommit()).isFalse()

        val committed = SinkOfferRequest<Unit>()
        assertThat(committed.start()).isTrue()
        assertThat(committed.beginCommit()).isTrue()
        assertThat(committed.beginCommit()).isFalse()
        assertThat(committed.cancel()).isFalse()
        assertThat(committed.isActive()).isTrue()
    }

    @Test
    fun timedOutCommittedOfferStillRecordsItsOwnFacts() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val run = SinkRunToken(source, 1L)
        val request = SinkOfferRequest<Unit>()
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(run, "host")).isTrue()
        assertThat(request.start()).isTrue()
        assertThat(request.beginCommit()).isTrue()
        assertThat(request.cancel()).isFalse()
        assertThat(request.isActive()).isTrue()
        assertThat(f.arbiter.offerFrozen(run, false)).isTrue()
        assertThat(f.arbiter.finish(run, false, null, null)).isTrue()
        assertThat(f.history.runs().single().metadata).contains(ConnectionRunFact("touch", "display only"))
    }

    @Test
    fun unfinishedOfferRecordsUnknownTouchInsteadOfAnotherRunFact() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(f.arbiter.offerFrozen(first, false)).isTrue()
        assertThat(f.arbiter.finish(first, false, null, null)).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.finish(second, false, null, null)).isTrue()
        assertThat(f.history.runs().first().metadata).contains(ConnectionRunFact("touch", "unknown"))
        assertThat(f.history.runs().last().metadata).contains(ConnectionRunFact("touch", "display only"))
    }

    @Test
    fun offerFromReplacedRunCannotRecordFrozenFacts() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        val request = SinkOfferRequest<Unit>()
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(request.start()).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.offerFrozen(first, false)).isFalse()
        request.cancel()
        assertThat(request.isActive()).isFalse()
        assertThat(f.arbiter.offerFrozen(second, true)).isTrue()
    }

    @Test
    fun retireSourceKeepsGenerationAndRejectsTheRetiredRun() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(1, 1L)
        val nextSource = SinkAttemptSource(1, 2L)
        val first = SinkRunToken(firstSource, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.arbiter.retireSource()
        assertThat(f.arbiter.generation).isEqualTo(1)
        assertThat(f.arbiter.log(first, "late")).isFalse()
        assertThat(f.arbiter.terminal(first, timeout)).isFalse()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("first")
        assertThat(f.arbiter.accept(nextSource)).isTrue()
    }

    @Test
    fun playRejectsLateTerminalEvidence() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val run = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(run, "host")).isTrue()
        assertThat(f.arbiter.action(run, SessionAction.EnteredPlay)).isTrue()
        assertThat(f.arbiter.terminal(run, timeout)).isFalse()
        assertThat(f.terminals).isEmpty()
    }

    @Test
    fun successfulFinishRejectsLateTerminalWithoutPlayEvent() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val run = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(run, "host")).isTrue()
        assertThat(f.arbiter.finish(run, true, null, true)).isTrue()
        assertThat(f.arbiter.terminal(run, timeout)).isFalse()
        assertThat(f.terminals).isEmpty()
    }

    @Test
    fun dropClosesOpenRunAndRejectsReentrantDrop() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val run = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(run, "host")).isTrue()
        f.afterAction = { action ->
            if (action == SessionAction.PrePlayGroupDropped) {
                assertThat(f.arbiter.drop(source, run)).isFalse()
            }
        }
        assertThat(f.arbiter.drop(source, run)).isTrue()
        assertThat(f.history.activeRun()).isNull()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("host")
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).hasSize(1)
    }

    @Test
    fun terminalCannotReenterOrArriveDuringDrop() {
        val terminalFixture = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        terminalFixture.arbiter.start(1)
        assertThat(terminalFixture.arbiter.accept(source)).isTrue()
        assertThat(terminalFixture.arbiter.begin(token, "host")).isTrue()
        terminalFixture.afterTerminal = { _, _ ->
            assertThat(terminalFixture.arbiter.terminal(token, timeout)).isFalse()
        }
        assertThat(terminalFixture.arbiter.terminal(token, timeout)).isTrue()
        assertThat(terminalFixture.terminals).hasSize(1)

        val dropFixture = Fixture()
        dropFixture.arbiter.start(1)
        assertThat(dropFixture.arbiter.accept(source)).isTrue()
        assertThat(dropFixture.arbiter.begin(token, "host")).isTrue()
        dropFixture.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) {
                assertThat(dropFixture.arbiter.terminal(token, timeout)).isFalse()
            }
        }
        assertThat(dropFixture.arbiter.drop(source, token)).isTrue()
        assertThat(dropFixture.terminals).isEmpty()
    }

    @Test
    fun dropAllowsReplacementSourceToDropReentrantly() {
        val f = Fixture()
        val first = SinkAttemptSource(1, 1L)
        val second = SinkAttemptSource(1, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(first)).isTrue()
        f.afterDrop = { source ->
            if (source == first) {
                assertThat(f.arbiter.accept(second)).isTrue()
                assertThat(f.arbiter.drop(second)).isTrue()
            }
        }
        assertThat(f.arbiter.drop(first)).isFalse()
        assertThat(f.drops).containsExactly(first, second).inOrder()
    }

    @Test
    fun newerRunDoesNotBeginIfClosingCallbackStopsController() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) f.arbiter.stop()
        }
        assertThat(f.arbiter.begin(second, "second")).isFalse()
        assertThat(f.history.activeRun()).isNull()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("first")
    }

    @Test
    fun stopClosesExactlyOnceWithRunFactsAndRejectsLateEvents() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        assertThat(f.arbiter.log(token, "before stop")).isTrue()
        assertThat(f.arbiter.action(token, SessionAction.SourceSelectedMode(selected))).isTrue()
        assertThat(f.arbiter.offerFrozen(token, false)).isTrue()
        f.arbiter.stop()
        f.arbiter.stop()
        assertThat(f.arbiter.generation).isNull()
        assertThat(f.arbiter.source).isNull()
        assertThat(f.arbiter.run).isNull()
        assertThat(f.arbiter.current).isNull()
        assertThat(f.arbiter.finish(token, true, null, true)).isFalse()
        assertThat(f.arbiter.log(token, "after stop")).isFalse()
        assertThat(f.arbiter.drop(source, token)).isFalse()
        assertThat(f.arbiter.terminal(token, timeout)).isFalse()
        assertThat(f.history.activeRun()).isNull()
        val closed = f.history.runs().single()
        assertThat(closed.succeeded).isFalse()
        assertThat(closed.log).isEqualTo("before stop")
        assertThat(closed.metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).hasSize(1)
    }

    @Test
    fun finishCallbackMayStopWithoutFinishingTwice() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) f.arbiter.stop()
        }
        assertThat(f.arbiter.finish(token, false, selected, false)).isFalse()
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).hasSize(1)
        assertThat(f.history.runs().single().metadata).containsExactly(
            ConnectionRunFact("selected mode", selected.format()),
            ConnectionRunFact("touch", "display only"),
        ).inOrder()
        assertThat(f.arbiter.terminal(token, timeout)).isFalse()
    }

    @Test
    fun playActionRecordedBeforeReentrantStopKeepsSuccessfulOutcome() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "played-host")).isTrue()
        f.afterAction = { action ->
            if (action == SessionAction.EnteredPlay) f.arbiter.stop()
        }
        assertThat(f.arbiter.action(token, SessionAction.EnteredPlay)).isFalse()
        assertThat(f.history.runs().single().succeeded).isTrue()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("played-host")
    }

    @Test
    fun terminalThenNaturalDropPreservesVisibleOnlyFailureBudget() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        assertThat(f.arbiter.terminal(token, timeout)).isTrue()
        assertThat(f.arbiter.finish(token, false, null, true)).isTrue()
        assertThat(f.queue.delayMs).isEqualTo(7_500L)
        val stale = checkNotNull(f.queue.pending)
        f.now += 3_000L
        assertThat(f.arbiter.drop(source, token)).isTrue()
        assertThat(f.drops).containsExactly(source)
        assertThat(f.owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(4_500L)
        f.owner.onForegroundVisibilityChanged(false)
        assertThat(f.queue.pending).isNull()
        f.now += 10_000L
        stale.run()
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(4_500L)
        f.owner.onForegroundVisibilityChanged(true)
        assertThat(f.queue.delayMs).isEqualTo(4_500L)
        f.now += 4_499L
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(1L)
        f.now += 1L
        f.queue.fire()
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.queue.pending).isNull()
    }

    /** Requires proven timeout evidence to survive the original owner's authoritative DOWN. */
    @Test
    fun provenTimeoutSurvivesSameOwnerDownBeforeMainDispatch() = exerciseProvenTerminalAfterSameOwnerDown(
        timeout, PrePlayFailureCause.PRE_PLAY_TIMEOUT,
    )

    /** Requires proven refusal evidence to survive the original owner's authoritative DOWN. */
    @Test
    fun provenRefusalSurvivesSameOwnerDownBeforeMainDispatch() = exerciseProvenTerminalAfterSameOwnerDown(
        RtspFailure.Rejected("M3", 3, 403), PrePlayFailureCause.ESTABLISHMENT_FAILED,
    )

    /** Requires a SAME-captured timeout to survive a later authoritative DOWN before dispatch. */
    @Test
    fun sameCapturedTimeoutSurvivesAuthoritativeDownBeforeMainDispatch() = exerciseProvenTerminalAfterSameOwnerDown(
        timeout, PrePlayFailureCause.PRE_PLAY_TIMEOUT, downAfterEnqueue = true,
    )

    /** Requires a SAME-captured refusal to survive a later authoritative DOWN before dispatch. */
    @Test
    fun sameCapturedRefusalSurvivesAuthoritativeDownBeforeMainDispatch() = exerciseProvenTerminalAfterSameOwnerDown(
        RtspFailure.Rejected("M3", 3, 403), PrePlayFailureCause.ESTABLISHMENT_FAILED, downAfterEnqueue = true,
    )

    /**
     * Exercises the controller's posting seam before any direct terminal delivery or natural drop.
     *
     * @param failure Proven RTSP terminal evidence for the original run.
     * @param cause Expected visible failure cause after queued delivery.
     * @param downAfterEnqueue Whether authoritative DOWN clears the binding after capturing SAME for posting.
     */
    private fun exerciseProvenTerminalAfterSameOwnerDown(
        failure: RtspFailure,
        cause: PrePlayFailureCause,
        downAfterEnqueue: Boolean = false,
    ) {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.SHIZUKU), worker = Executor { it.run() }) { null }
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU,
            P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1",
            checkNotNull(route.revisionFor(WfdOwner.SHIZUKU)),
        )
        val token = SinkRunToken(source, 1L, original)
        val binding = SinkSourceBinding(source, original)
        val observedBinding = AtomicReference<SinkSourceBinding?>(binding)
        val provenDownBinding = AtomicReference<SinkSourceBinding?>(null)
        val posted = mutableListOf<Runnable>()
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val attempt = checkNotNull(f.owner.currentAttempt())
        val relation = compareSinkGroup(original, original.copy(state = P2pGroupState.Down))
        assertThat(relation).isEqualTo(SinkGroupRelation.DOWN)
        assertThat(route.isCurrent(original)).isTrue()
        if (!downAfterEnqueue) observedBinding.set(null)
        postSinkTerminal(
            token = token,
            relation = if (downAfterEnqueue) compareSinkGroup(original, original.copy()) else relation,
            failure = failure,
            reachedPlay = false,
            originatingStopSequence = 0L,
            enqueue = { posted.add(it) },
            fence = {
                SinkTerminalFence(
                    activeGeneration = f.arbiter.generation,
                    stopSequence = 0L,
                    latestGroupSequence = source.groupSequence,
                    observedBinding = observedBinding.get(),
                    currentRun = f.arbiter.current,
                    userEnded = false,
                    originalOwnerCurrent = route.isCurrent(original),
                    provenDownBinding = provenDownBinding.get(),
                )
            },
            deliver = { run, terminal -> f.arbiter.terminal(run, terminal) },
        )
        if (downAfterEnqueue) {
            clearSinkSourceBinding(binding, relation, observedBinding, provenDownBinding)
            assertThat(observedBinding.get()).isNull()
            assertThat(provenDownBinding.get()).isSameInstanceAs(binding)
        }
        assertThat(f.owner.snapshot()).isNull()
        posted.forEach { it.run() }
        assertThat(f.owner.snapshot()?.cause).isEqualTo(cause)
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(attempt)
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(7_500L)
        assertThat(f.arbiter.finish(token, false, null, true)).isTrue()
        f.now += 3_000L
        assertThat(f.arbiter.drop(source, token)).isTrue()
        assertThat(f.owner.snapshot()?.cause).isEqualTo(cause)
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(attempt)
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(4_500L)
    }

    /** Requires authoritative comparison, not mere binding removal, to publish a DOWN key. */
    @Test
    fun sourceBindingClearPublishesOnlyAuthoritativeDown() {
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", 1L,
        )
        val binding = SinkSourceBinding(SinkAttemptSource(1, 1L), original)
        for (observed in listOf(
            original.copy(state = P2pGroupState.Down, ownerRevision = null),
            original.copy(state = P2pGroupState.Down, ownerRevision = 2L),
        )) {
            val current = AtomicReference<SinkSourceBinding?>(binding)
            val down = AtomicReference<SinkSourceBinding?>(null)
            clearSinkSourceBinding(binding, compareSinkGroup(original, observed), current, down)
            assertThat(current.get()).isNull()
            assertThat(down.get()).isNull()
        }
        val current = AtomicReference<SinkSourceBinding?>(binding)
        val down = AtomicReference<SinkSourceBinding?>(null)
        clearSinkSourceBinding(
            binding, compareSinkGroup(original, original.copy(state = P2pGroupState.Down)), current, down,
        )
        assertThat(current.get()).isNull()
        assertThat(down.get()).isSameInstanceAs(binding)
    }

    /** Keeps late old clears from replacing newer proof or removing a replacement binding. */
    @Test
    fun sourceBindingClearPreservesNewerProofAndReplacement() {
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", 1L,
        )
        val older = SinkSourceBinding(SinkAttemptSource(1, 1L), original)
        val newer = older.copy(source = SinkAttemptSource(1, 2L))
        val relation = compareSinkGroup(original, original.copy(state = P2pGroupState.Down))
        val current = AtomicReference<SinkSourceBinding?>(newer)
        val down = AtomicReference<SinkSourceBinding?>(null)
        clearSinkSourceBinding(older, relation, current, down)
        assertThat(current.get()).isSameInstanceAs(newer)
        assertThat(down.get()).isNull()
        clearSinkSourceBinding(newer, relation, current, down)
        assertThat(current.get()).isNull()
        assertThat(down.get()).isSameInstanceAs(newer)
        current.set(older)
        clearSinkSourceBinding(older, relation, current, down)
        assertThat(current.get()).isNull()
        assertThat(down.get()).isSameInstanceAs(newer)
        val rebound = older.copy(observation = original.copy(ownerRevision = 2L))
        current.set(rebound)
        clearSinkSourceBinding(older, relation, current, down)
        assertThat(current.get()).isSameInstanceAs(rebound)
        assertThat(down.get()).isSameInstanceAs(newer)
    }

    /** Protects SAME behavior while DOWN gains an independent dispatch fence. */
    @Test
    fun sameOwnerTerminalRemainsQueuedUntilMainDispatch() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.SHIZUKU), worker = Executor { it.run() }) { null }
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", checkNotNull(route.revisionFor(WfdOwner.SHIZUKU)),
        )
        val token = SinkRunToken(source, 1L, original)
        val posted = mutableListOf<Runnable>()
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val attempt = checkNotNull(f.owner.currentAttempt())
        postSinkTerminal(
            token = token,
            relation = compareSinkGroup(original, original.copy()),
            failure = timeout,
            reachedPlay = false,
            originatingStopSequence = 7L,
            enqueue = { posted.add(it) },
            fence = {
                SinkTerminalFence(
                    activeGeneration = f.arbiter.generation,
                    stopSequence = 7L,
                    latestGroupSequence = source.groupSequence,
                    observedBinding = SinkSourceBinding(source, original),
                    currentRun = f.arbiter.current,
                    userEnded = false,
                    originalOwnerCurrent = route.isCurrent(original),
                )
            },
            deliver = { run, failure -> f.arbiter.terminal(run, failure) },
        )
        assertThat(f.owner.snapshot()).isNull()
        posted.forEach { it.run() }
        assertThat(f.owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(attempt)
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(7_500L)
    }

    /** Prevents queued evidence from reviving a stopped, user-ended or rebound owner attempt. */
    @Test
    fun queuedTerminalRechecksStopGenerationUserEndAndOwnerRevision() {
        for (relation in listOf(SinkGroupRelation.SAME, SinkGroupRelation.DOWN)) {
            exerciseQueuedTerminalRejection(relation) { f, _, _, _ -> f.arbiter.stop() }
            exerciseQueuedTerminalRejection(relation) { f, _, token, _ ->
                f.arbiter.start(token.source.generation + 1)
            }
            exerciseQueuedTerminalRejection(relation) { _, _, _, facts ->
                facts.set(facts.get().copy(stopSequence = 1L))
            }
            exerciseQueuedTerminalRejection(relation) { _, _, _, facts ->
                facts.set(facts.get().copy(userEnded = true))
            }
            exerciseQueuedTerminalRejection(relation) { _, route, _, _ ->
                route.updateEndpoint(WfdOwner.SHIZUKU) {}
            }
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME) { _, _, _, facts ->
            facts.set(facts.get().copy(observedBinding = null))
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME) { _, _, token, facts ->
            val changed = checkNotNull(token.observation).copy(epoch = "01234567-89ab-cdef-0123-456789abcdef-2")
            facts.set(facts.get().copy(observedBinding = SinkSourceBinding(token.source, changed)))
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.DOWN) { _, _, token, facts ->
            facts.set(facts.get().copy(observedBinding = SinkSourceBinding(token.source, checkNotNull(token.observation))))
        }
    }

    /** Covers replacements reserved before main, including a newer UP followed by DOWN. */
    @Test
    fun queuedTerminalRejectsReplacementRunsAndReservedSources() {
        for (relation in listOf(SinkGroupRelation.SAME, SinkGroupRelation.DOWN)) {
            exerciseQueuedTerminalRejection(relation) { f, _, token, _ ->
                assertThat(f.arbiter.begin(token.copy(sequence = token.sequence + 1L), "replacement run")).isTrue()
            }
            exerciseQueuedTerminalRejection(relation) { f, _, token, facts ->
                val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
                facts.set(facts.get().copy(
                    latestGroupSequence = nextSource.groupSequence,
                    observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
                ))
                assertThat(f.arbiter.accept(nextSource)).isTrue()
                assertThat(f.arbiter.begin(
                    token.copy(source = nextSource, sequence = token.sequence + 1L), "replacement source",
                )).isTrue()
            }
            exerciseQueuedTerminalRejection(relation) { _, _, token, facts ->
                val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
                facts.set(facts.get().copy(
                    latestGroupSequence = nextSource.groupSequence,
                    observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
                ))
            }
            exerciseQueuedTerminalRejection(relation) { _, _, token, facts ->
                val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
                facts.set(facts.get().copy(
                    latestGroupSequence = nextSource.groupSequence,
                    observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
                ))
                facts.set(facts.get().copy(observedBinding = null))
            }
        }
    }

    /** Keeps unproven or non-authoritative evidence from creating a failure episode. */
    @Test
    fun terminalPostingRejectsPlayedUnknownAndUnprovenOrigins() {
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", 1L,
        )
        for (relation in listOf(SinkGroupRelation.SAME, SinkGroupRelation.DOWN)) {
            exerciseRejectedTerminalEvidence(relation, timeout, original, reachedPlay = true)
            exerciseRejectedTerminalEvidence(relation, null, original, reachedPlay = false)
            exerciseRejectedTerminalEvidence(relation, timeout, null, reachedPlay = false)
            exerciseRejectedTerminalEvidence(relation, timeout, original.copy(owner = WfdOwner.NONE), reachedPlay = false)
            exerciseRejectedTerminalEvidence(relation, timeout, original.copy(ownerRevision = null), reachedPlay = false)
        }
        exerciseRejectedTerminalEvidence(SinkGroupRelation.UNKNOWN, timeout, original, reachedPlay = false)
        exerciseRejectedTerminalEvidence(SinkGroupRelation.SUPERSEDED, timeout, original, reachedPlay = false)
    }

    /** Requires every part of a retained DOWN key to match the SAME-captured run. */
    @Test
    fun sameCapturedTerminalRejectsMismatchedDownProof() {
        val mismatches = listOf<(SinkSourceBinding) -> SinkSourceBinding>(
            { it.copy(source = SinkAttemptSource(2, 2L)) },
            { it.copy(source = it.source.copy(generation = it.source.generation + 1)) },
            { it.copy(source = it.source.copy(groupSequence = it.source.groupSequence + 1L)) },
            { it.copy(observation = it.observation.copy(owner = WfdOwner.HELPER)) },
            { it.copy(observation = it.observation.copy(ownerRevision = checkNotNull(it.observation.ownerRevision) + 1L)) },
            { it.copy(observation = it.observation.copy(epoch = "01234567-89ab-cdef-0123-456789abcdef-2")) },
        )
        for (mismatch in mismatches) {
            exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, _, _, facts ->
                val proof = checkNotNull(facts.get().provenDownBinding)
                facts.set(facts.get().copy(provenDownBinding = mismatch(proof)))
            }
        }
    }

    /** Exercises common dispatch fences with valid keyed DOWN proof, not a missing-binding rejection. */
    @Test
    fun sameCapturedTerminalWithDownProofRejectsQueuedInvalidation() {
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { f, _, _, _ ->
            f.arbiter.stop()
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { f, _, token, _ ->
            f.arbiter.start(token.source.generation + 1)
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, _, _, facts ->
            facts.set(facts.get().copy(stopSequence = 1L))
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, _, _, facts ->
            facts.set(facts.get().copy(userEnded = true))
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, route, _, _ ->
            route.updateEndpoint(WfdOwner.SHIZUKU) {}
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { f, _, token, _ ->
            assertThat(f.arbiter.begin(token.copy(sequence = token.sequence + 1L), "replacement run")).isTrue()
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { f, _, token, facts ->
            val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
            facts.set(facts.get().copy(
                latestGroupSequence = nextSource.groupSequence,
                observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
            ))
            assertThat(f.arbiter.accept(nextSource)).isTrue()
            assertThat(f.arbiter.begin(
                token.copy(source = nextSource, sequence = token.sequence + 1L), "replacement source",
            )).isTrue()
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, _, token, facts ->
            val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
            facts.set(facts.get().copy(
                latestGroupSequence = nextSource.groupSequence,
                observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
            ))
        }
        exerciseQueuedTerminalRejection(SinkGroupRelation.SAME, downAfterEnqueue = true) { _, _, token, facts ->
            val nextSource = token.source.copy(groupSequence = token.source.groupSequence + 1L)
            facts.set(facts.get().copy(
                latestGroupSequence = nextSource.groupSequence,
                observedBinding = SinkSourceBinding(nextSource, checkNotNull(token.observation)),
            ))
            facts.set(facts.get().copy(observedBinding = null))
        }
    }

    /**
     * Mutates live facts after posting and checks public ownership without copying the posting policy.
     *
     * @param relation Authoritative original-group relation used to enqueue the terminal.
     * @param downAfterEnqueue Publishes an actual keyed DOWN after capturing SAME, before changing live facts.
     * @param change Changes actual arbiter/owner identities or the controller's worker facts before dispatch.
     */
    private fun exerciseQueuedTerminalRejection(
        relation: SinkGroupRelation,
        downAfterEnqueue: Boolean = false,
        change: (Fixture, OwnerGroupRoute, SinkRunToken, AtomicReference<SinkTerminalFence>) -> Unit,
    ) {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.SHIZUKU), worker = Executor { it.run() }) { null }
        val original = OwnerGroupSnapshot(
            WfdOwner.SHIZUKU, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", checkNotNull(route.revisionFor(WfdOwner.SHIZUKU)),
        )
        val token = SinkRunToken(source, 1L, original)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val facts = AtomicReference(SinkTerminalFence(
            activeGeneration = f.arbiter.generation,
            stopSequence = 0L,
            latestGroupSequence = source.groupSequence,
            observedBinding = if (relation == SinkGroupRelation.SAME) SinkSourceBinding(source, original) else null,
            currentRun = f.arbiter.current,
            userEnded = false,
            originalOwnerCurrent = route.isCurrent(original),
        ))
        val posted = mutableListOf<Runnable>()
        val delivered = mutableListOf<SinkRunToken>()
        postSinkTerminal(
            token = token,
            relation = relation,
            failure = timeout,
            reachedPlay = false,
            originatingStopSequence = 0L,
            enqueue = { posted.add(it) },
            fence = {
                facts.get().copy(
                    activeGeneration = f.arbiter.generation,
                    currentRun = f.arbiter.current,
                    originalOwnerCurrent = route.isCurrent(original),
                )
            },
            deliver = { run, failure ->
                delivered.add(run)
                f.arbiter.terminal(run, failure)
            },
        )
        if (downAfterEnqueue) {
            check(relation == SinkGroupRelation.SAME)
            val binding = checkNotNull(facts.get().observedBinding)
            val observedBinding = AtomicReference<SinkSourceBinding?>(binding)
            val down = AtomicReference<SinkSourceBinding?>(null)
            clearSinkSourceBinding(
                binding, compareSinkGroup(original, original.copy(state = P2pGroupState.Down)), observedBinding, down,
            )
            assertThat(observedBinding.get()).isNull()
            assertThat(down.get()).isSameInstanceAs(binding)
            facts.set(facts.get().copy(observedBinding = observedBinding.get(), provenDownBinding = down.get()))
        }
        change(f, route, token, facts)
        val current = f.arbiter.current
        val attempt = f.owner.currentAttempt()
        assertThat(f.owner.snapshot()).isNull()
        posted.forEach { it.run() }
        assertThat(delivered).isEmpty()
        assertThat(f.terminals).isEmpty()
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.owner.currentAttempt()).isEqualTo(attempt)
        if (current != null && current != token) {
            // Rejecting the old callback must not consume the replacement attempt.
            assertThat(f.arbiter.terminal(current, timeout)).isTrue()
            assertThat(f.owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
            assertThat(f.owner.snapshot()?.attemptId).isEqualTo(attempt)
        }
    }

    /**
     * Checks rejected evidence with otherwise eligible live facts and the real public failure owner.
     *
     * @param relation Observed group relation.
     * @param failure Terminal evidence, or null for an unproven ending.
     * @param observation Original owner observation, possibly absent or non-authoritative.
     * @param reachedPlay Whether the originating run reached playback.
     */
    private fun exerciseRejectedTerminalEvidence(
        relation: SinkGroupRelation,
        failure: RtspFailure?,
        observation: OwnerGroupSnapshot?,
        reachedPlay: Boolean,
    ) {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L, observation)
        val posted = mutableListOf<Runnable>()
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val attempt = f.owner.currentAttempt()
        postSinkTerminal(
            token = token,
            relation = relation,
            failure = failure,
            reachedPlay = reachedPlay,
            originatingStopSequence = 0L,
            enqueue = { posted.add(it) },
            fence = {
                SinkTerminalFence(
                    activeGeneration = f.arbiter.generation,
                    stopSequence = 0L,
                    latestGroupSequence = source.groupSequence,
                    observedBinding = if (relation == SinkGroupRelation.SAME) observation?.let {
                        SinkSourceBinding(source, it)
                    } else null,
                    currentRun = f.arbiter.current,
                    userEnded = false,
                    originalOwnerCurrent = true,
                )
            },
            deliver = { run, terminal -> f.arbiter.terminal(run, terminal) },
        )
        assertThat(f.owner.snapshot()).isNull()
        posted.forEach { it.run() }
        assertThat(f.terminals).isEmpty()
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.owner.currentAttempt()).isEqualTo(attempt)
    }

    @Test
    fun showingDismissingAndExpiringFailureDoNotDispatchSessionActions() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(firstSource, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val firstSnapshot = f.session.snapshot()
        val firstActions = f.actions.toList()
        val firstHistory = f.history.activeRun()?.copy(endedAtEpochMs = 0L)
        assertThat(f.arbiter.terminal(first, timeout)).isTrue()
        f.owner.onDismissed()
        assertThat(f.session.snapshot()).isEqualTo(firstSnapshot)
        assertThat(f.actions).isEqualTo(firstActions)
        assertThat(f.history.activeRun()?.copy(endedAtEpochMs = 0L)).isEqualTo(firstHistory)

        val secondSource = SinkAttemptSource(1, 2L)
        val second = SinkRunToken(secondSource, 2L)
        assertThat(f.arbiter.accept(secondSource)).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        val secondSnapshot = f.session.snapshot()
        val secondActions = f.actions.toList()
        val secondHistory = f.history.activeRun()?.copy(endedAtEpochMs = 0L)
        assertThat(f.arbiter.terminal(second, timeout)).isTrue()
        f.now += 7_500L
        f.queue.fire()
        assertThat(f.session.snapshot()).isEqualTo(secondSnapshot)
        assertThat(f.actions).isEqualTo(secondActions)
        assertThat(f.history.activeRun()?.copy(endedAtEpochMs = 0L)).isEqualTo(secondHistory)
    }

    @Test
    fun newAttemptAndPreemptionCancelTimersAndStaleCallbacks() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(1, 1L)
        val secondSource = SinkAttemptSource(1, 2L)
        val first = SinkRunToken(firstSource, 1L)
        val second = SinkRunToken(secondSource, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        assertThat(f.arbiter.terminal(first, timeout)).isTrue()
        val staleFirst = checkNotNull(f.queue.pending)
        assertThat(f.arbiter.accept(secondSource)).isTrue()
        assertThat(f.queue.pending).isNull()
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.terminal(second, timeout)).isTrue()
        val staleSecond = checkNotNull(f.queue.pending)
        staleFirst.run()
        assertThat(f.owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(2L))
        f.owner.onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
        assertThat(f.queue.pending).isNull()
        staleSecond.run()
        assertThat(f.owner.snapshot()).isNull()
        assertThat(f.arbiter.terminal(second, timeout)).isFalse()
        assertThat(f.owner.snapshot()).isNull()
    }

    @Test
    fun attemptAndProgressCallbacksMayStopWithoutPublishingStaleProgress() {
        val attemptStop = Fixture()
        val source = SinkAttemptSource(1, 1L)
        attemptStop.arbiter.start(1)
        attemptStop.afterAttempt = { attemptStop.arbiter.stop() }
        assertThat(attemptStop.arbiter.accept(source)).isFalse()
        assertThat(attemptStop.actions).doesNotContain(SessionAction.PrePlayProgress)
        assertThat(attemptStop.arbiter.isSource(source)).isFalse()

        val progressStop = Fixture()
        progressStop.arbiter.start(1)
        progressStop.afterAction = { action ->
            if (action == SessionAction.PrePlayProgress) progressStop.arbiter.stop()
        }
        assertThat(progressStop.arbiter.accept(source)).isFalse()
        assertThat(progressStop.actions).containsExactly(SessionAction.PrePlayProgress)
        assertThat(progressStop.arbiter.isSource(source)).isFalse()
    }

    @Test
    fun attemptCallbackStartingRunDoesNotPublishLateProgress() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        f.afterAttempt = { accepted ->
            assertThat(accepted).isEqualTo(source)
            assertThat(f.arbiter.begin(token, "host")).isTrue()
        }
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.actions).doesNotContain(SessionAction.PrePlayProgress)
        assertThat(f.history.activeRun()?.remoteHost).isEqualTo("host")
    }

    @Test
    fun beginCallbackMayStopAndFinishTheRunOnlyOnce() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.BeginConnectionRun) f.arbiter.stop()
        }
        assertThat(f.arbiter.begin(token, "host")).isFalse()
        assertThat(f.history.activeRun()).isNull()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("host")
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).hasSize(1)
        assertThat(f.arbiter.finish(token, false, null, true)).isFalse()
    }

    @Test
    fun rolloverFinishCallbackMayStopBeforeNewAttemptIsPublished() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(1, 1L)
        val secondSource = SinkAttemptSource(1, 2L)
        val first = SinkRunToken(firstSource, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) f.arbiter.stop()
        }
        assertThat(f.arbiter.accept(secondSource)).isFalse()
        assertThat(f.attempts).containsExactly(firstSource)
        assertThat(f.arbiter.source).isNull()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("first")
        assertThat(f.actions.filterIsInstance<SessionAction.FinishConnectionRun>()).hasSize(1)
    }

    @Test
    fun rolloverFinishCallbackCannotOverwriteAReentrantGeneration() {
        val f = Fixture()
        val firstSource = SinkAttemptSource(1, 1L)
        val superseded = SinkAttemptSource(1, 2L)
        val replacement = SinkAttemptSource(2, 3L)
        val first = SinkRunToken(firstSource, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(firstSource)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        f.afterAction = { action ->
            if (action is SessionAction.FinishConnectionRun) {
                f.arbiter.start(2)
                assertThat(f.arbiter.accept(replacement)).isTrue()
            }
        }
        assertThat(f.arbiter.accept(superseded)).isFalse()
        assertThat(f.arbiter.isSource(replacement)).isTrue()
        assertThat(f.attempts).containsExactly(firstSource, replacement).inOrder()
        assertThat(f.history.runs().single().remoteHost).isEqualTo("first")
    }

    @Test
    fun dropActionRevalidatesAfterStopAndAfterNewRun() {
        val stop = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        stop.arbiter.start(1)
        assertThat(stop.arbiter.accept(source)).isTrue()
        assertThat(stop.arbiter.begin(first, "first")).isTrue()
        stop.afterAction = { action ->
            if (action == SessionAction.PrePlayGroupDropped) stop.arbiter.stop()
        }
        assertThat(stop.arbiter.drop(source, first)).isFalse()
        assertThat(stop.drops).isEmpty()
        assertThat(stop.history.runs().single().remoteHost).isEqualTo("first")

        val newer = Fixture()
        val second = SinkRunToken(source, 2L)
        newer.arbiter.start(1)
        assertThat(newer.arbiter.accept(source)).isTrue()
        assertThat(newer.arbiter.begin(first, "first")).isTrue()
        assertThat(newer.arbiter.finish(first, false, null, true)).isTrue()
        newer.afterAction = { action ->
            if (action == SessionAction.PrePlayGroupDropped) {
                assertThat(newer.arbiter.begin(second, "second")).isTrue()
            }
        }
        assertThat(newer.arbiter.drop(source, first)).isFalse()
        assertThat(newer.drops).isEmpty()
        assertThat(newer.arbiter.isRun(second)).isTrue()
        assertThat(newer.history.activeRun()?.remoteHost).isEqualTo("second")
        assertThat(newer.arbiter.terminal(first, timeout)).isFalse()
    }

    @Test
    fun tokenlessPairingDropCannotConsumeReentrantRun() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        f.afterAction = { action ->
            if (action == SessionAction.PrePlayGroupDropped) {
                assertThat(f.arbiter.begin(token, "host")).isTrue()
            }
        }
        assertThat(f.arbiter.drop(source)).isFalse()
        assertThat(f.drops).isEmpty()
        assertThat(f.history.activeRun()?.remoteHost).isEqualTo("host")
    }

    @Test
    fun terminalAndDropCallbacksMayStopWithoutRevivingTheRun() {
        val terminal = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        terminal.arbiter.start(1)
        assertThat(terminal.arbiter.accept(source)).isTrue()
        assertThat(terminal.arbiter.begin(token, "terminal")).isTrue()
        terminal.afterTerminal = { _, _ -> terminal.arbiter.stop() }
        assertThat(terminal.arbiter.terminal(token, timeout)).isFalse()
        assertThat(terminal.arbiter.isRun(token)).isFalse()
        assertThat(terminal.history.runs().single().remoteHost).isEqualTo("terminal")

        val drop = Fixture()
        drop.arbiter.start(1)
        assertThat(drop.arbiter.accept(source)).isTrue()
        assertThat(drop.arbiter.begin(token, "drop")).isTrue()
        drop.afterDrop = { drop.arbiter.stop() }
        assertThat(drop.arbiter.drop(source, token, played = true)).isFalse()
        assertThat(drop.drops).containsExactly(source)
        assertThat(drop.actions).contains(SessionAction.ConnectionEnded)
        assertThat(drop.arbiter.isRun(token)).isFalse()
        assertThat(drop.history.runs().single().remoteHost).isEqualTo("drop")
        assertThat(drop.history.runs().single().succeeded).isTrue()
    }

    @Test
    fun tcpDialResultOpensVisibleTimeoutAndNewRunPreemptsIt() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "host")).isTrue()
        f.owner.onForegroundVisibilityChanged(true)
        val window = SinkDialWindow()
        window.connectFailed(SocketTimeoutException("first"))
        window.connectFailed(SocketTimeoutException("second"))
        val result = window.finish(true, true, true)
        val actionsBefore = f.actions.toList()
        assertThat(f.arbiter.dialFinished(first, result)).isTrue()
        assertThat(f.owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        assertThat(f.actions).containsExactlyElementsIn(actionsBefore).inOrder()
        f.now += 3_000L
        f.owner.onForegroundVisibilityChanged(false)
        f.now += 20_000L
        assertThat(f.owner.snapshot()?.remainingMs).isEqualTo(4_500L)
        assertThat(f.arbiter.begin(second, "host")).isTrue()
        assertThat(f.owner.isEpisodeActive()).isFalse()
        assertThat(f.attempts).containsExactly(source, source).inOrder()
    }

    @Test
    fun tcpDialResultRejectsUnknownCancellationDownAndStaleRuns() {
        val window = SinkDialWindow()
        window.connectFailed(SocketTimeoutException("connect"))
        assertThat(window.finish(false, true, true).timeout).isNull()
        assertThat(window.finish(true, false, true).timeout).isNull()
        assertThat(window.finish(true, true, false).timeout).isNull()
        window.connectFailed(ConnectException("refused"))
        assertThat(window.finish(true, true, true).timeout).isNull()
        window.connectFailed(SocketTimeoutException("connect"))
        window.otherFailure()
        assertThat(window.finish(true, true, true).timeout).isNull()
        window.connectFailed(SocketTimeoutException("connect"))
        assertThat(window.finish(true, true, true).timeout).isNull()
        window.connectFailed(SocketTimeoutException("connect again"))
        val result = window.finish(true, true, true)
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val first = SinkRunToken(source, 1L)
        val second = SinkRunToken(source, 2L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(first, "first")).isTrue()
        assertThat(f.arbiter.begin(second, "second")).isTrue()
        assertThat(f.arbiter.dialFinished(first, result)).isFalse()
        assertThat(f.arbiter.dialFinished(second, SinkDialResult.NoConnection())).isFalse()
        assertThat(f.arbiter.drop(source, second)).isTrue()
        assertThat(f.arbiter.dialFinished(second, result)).isFalse()
        assertThat(f.owner.isEpisodeActive()).isFalse()
        assertThat(f.dialTimeouts).isEmpty()

        val stopped = Fixture()
        stopped.arbiter.start(1)
        assertThat(stopped.arbiter.accept(source)).isTrue()
        assertThat(stopped.arbiter.begin(first, "stopped")).isTrue()
        stopped.arbiter.retireSource()
        stopped.owner.onExplicitStop()
        assertThat(stopped.arbiter.dialFinished(first, result)).isFalse()
        assertThat(stopped.owner.isEpisodeActive()).isFalse()
        assertThat(stopped.dialTimeouts).isEmpty()
    }

    @Test
    fun mutationsRequireMainThreadButIdentityReadsDoNot() {
        val f = Fixture()
        val source = SinkAttemptSource(1, 1L)
        val token = SinkRunToken(source, 1L)
        f.arbiter.start(1)
        assertThat(f.arbiter.accept(source)).isTrue()
        assertThat(f.arbiter.begin(token, "host")).isTrue()
        f.onMainThread = false
        val observed = AtomicReference<Pair<Boolean, Boolean>>()
        val worker = Thread {
            observed.set(f.arbiter.isSource(source) to f.arbiter.isRun(token))
        }
        worker.start()
        worker.join(5_000L)
        assertThat(worker.isAlive).isFalse()
        assertThat(observed.get()).isEqualTo(true to true)
        assertThrows(IllegalStateException::class.java) { f.arbiter.stop() }
        assertThrows(IllegalStateException::class.java) { f.arbiter.log(token, "off thread") }
        assertThat(f.history.activeRun()?.log).isEmpty()
    }
}
