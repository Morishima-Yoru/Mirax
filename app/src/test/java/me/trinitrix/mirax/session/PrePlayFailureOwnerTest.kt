package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.wfd.rtsp.RtspFailure
import org.junit.Test

/**
 * Behaviour tests for the process-scoped FAILED episode owner and the
 * provable-only terminal classifier.
 *
 * Covers attempt identity, owner-thread guard, visible accrual, preemption,
 * expiry, stale attempt rejection, and proven RTSP or TCP timeout evidence.
 * No sleeps, threads, devices, or network handles; the scheduler is faked.
 */
class PrePlayFailureOwnerTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(deltaMs: Long) {
            now += deltaMs
        }
    }

    private class FakeScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delayMs: Long, task: () -> Unit): Boolean {
            pending.add(delayMs to task)
            return true
        }
        fun runAll() {
            val tasks = pending.map { it.second }
            pending.clear()
            tasks.forEach { it() }
        }
    }

    private fun freshOwner(
        clock: FakeClock,
        scheduler: FakeScheduler,
        ownerThread: Boolean = true,
    ): PrePlayFailureEpisodeOwner {
        return PrePlayFailureEpisodeOwner(
            nowMs = { clock.now },
            scheduler = { delayMs, task -> scheduler.schedule(delayMs, task) },
            canceller = { scheduler.pending.clear() },
            isOwnerThread = { ownerThread },
        )
    }

    @Test
    fun attemptIdsAreMonotonicAndUnique() {
        PrePlayAttemptIdGenerator.resetForTest()
        val first = PrePlayAttemptIdGenerator.getInstance().next()
        val second = PrePlayAttemptIdGenerator.getInstance().next()
        assertThat(second.value).isGreaterThan(first.value)
    }

    @Test
    fun classifierAcceptsTimeoutOnly() {
        val outcome = terminalOutcome(PrePlayAttemptId(1L), RtspFailure.Timeout("M2", 2))
        val genuine = outcome as? PrePlayTerminalOutcome.GenuineFailure
        assertThat(genuine).isNotNull()
        assertThat(genuine!!.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
    }

    @Test
    fun classifierAcceptsRejectedOnly() {
        val outcome = terminalOutcome(PrePlayAttemptId(1L), RtspFailure.Rejected("M3", 3, 403))
        val genuine = outcome as? PrePlayTerminalOutcome.GenuineFailure
        assertThat(genuine).isNotNull()
        assertThat(genuine!!.cause).isEqualTo(PrePlayFailureCause.ESTABLISHMENT_FAILED)
    }

    @Test
    fun classifierRejectsNullEvidence() {
        assertThat(terminalOutcome(PrePlayAttemptId(1L), null)).isNull()
    }

    @Test
    fun ownerOpensEpisodeOnGenuineTerminal() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val attempt = PrePlayAttemptId(7L)
        assertThat(owner.onPrePlayAttemptStarted(attempt, source)).isTrue()
        assertThat(owner.onForegroundVisibilityChanged(true)).isTrue()
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(owner.isEpisodeActive()).isTrue()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(attempt)
    }

    @Test
    fun tcpTimeoutUsesCurrentSourceAndVisibleTimeOnly() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val first = SinkAttemptSource(1, 1L)
        val current = SinkAttemptSource(1, 2L)
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(1L), first)).isTrue()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(2L), current)).isTrue()
        assertThat(owner.onTcpEstablishmentTimeout(TcpEstablishmentTimeout, first)).isNull()
        owner.onForegroundVisibilityChanged(true)
        assertThat(owner.onTcpEstablishmentTimeout(TcpEstablishmentTimeout, current)).isNotNull()
        assertThat(owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        clock.advance(3_000L)
        owner.onForegroundVisibilityChanged(false)
        clock.advance(10_000L)
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(4_500L)
        owner.onForegroundVisibilityChanged(true)
        clock.advance(4_500L)
        owner.checkExpiry()
        assertThat(owner.snapshot()).isNull()
    }

    @Test
    fun ownerIgnoresUnprovenTerminal() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(7L), source)
        owner.onForegroundVisibilityChanged(true)
        assertThat(owner.onPrePlayTerminal(null, source)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    @Test
    fun staleAttemptCannotOverwrite() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(9L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        val latest = owner.currentAttempt()
        val newSource = SinkAttemptSource(1, 2L)
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(10L), newSource)).isTrue()
        assertThat(owner.currentAttempt()).isNotEqualTo(latest)
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), newSource)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(10L))
    }

    @Test
    fun visibleAccrualExpiresAfter7500() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(3L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        clock.advance(4_000L)
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(3_500L)
        clock.advance(3_500L)
        owner.checkExpiry()
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    @Test
    fun hiddenTimeDoesNotAccrue() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(3L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        clock.advance(3_000L)
        owner.onForegroundVisibilityChanged(false)
        clock.advance(20_000L)
        owner.onForegroundVisibilityChanged(true)
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(4_500L)
    }

    @Test
    fun preemptionClearsEpisode() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(3L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(owner.isEpisodeActive()).isTrue()
        owner.onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    @Test
    fun backgroundThreadCallsAreRejected() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler, ownerThread = false)
        val source = SinkAttemptSource(1, 1L)
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(1L), source)).isFalse()
        assertThat(owner.onForegroundVisibilityChanged(true)).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)).isNull()
        assertThat(owner.onPreempted(PrePlayPreemptReason.WIFI_PAUSED)).isFalse()
        assertThat(owner.onDismissed()).isNull()
    }

    @Test
    fun dismissClearsWithoutSideEffects() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val attempt = PrePlayAttemptId(5L)
        owner.onPrePlayAttemptStarted(attempt, source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source)
        assertThat(owner.isEpisodeActive()).isTrue()
        assertThat(owner.onDismissed()).isEqualTo(attempt)
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(scheduler.pending).isEmpty()
        // Dismiss consumes presentation only; latest attempt identity is kept
        // so MainActivity's "still latest" guard keeps working.
        assertThat(owner.currentAttempt()).isEqualTo(attempt)
    }

    @Test
    fun expirySurfacesNullSnapshot() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(5L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        clock.advance(7_500L)
        owner.checkExpiry()
        assertThat(owner.snapshot()).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)).isNull()
        assertThat(owner.snapshot()).isNull()
        assertThat(scheduler.pending).isEmpty()
    }

    @Test
    fun hidingAfterBudgetNotifiesExpiryAndCancelsPendingTimer() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(5L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        val staleTimer = scheduler.pending.single().second

        clock.advance(7_500L)
        owner.setEpisodeListener { seen.add(it) }
        owner.onForegroundVisibilityChanged(false)
        assertThat(seen).hasSize(2)
        assertThat(seen.last()).isNull()
        assertThat(scheduler.pending).isEmpty()
        staleTimer()
        assertThat(seen).hasSize(2)
    }

    @Test
    fun newerAttemptAfterBudgetClearsLastNotifiedEpisode() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val first = SinkAttemptSource(1, 1L)
        val second = SinkAttemptSource(1, 2L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(5L), first)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), first)
        val staleTimer = scheduler.pending.single().second

        clock.advance(7_500L)
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(6L), second)).isTrue()
        assertThat(seen).hasSize(2)
        assertThat(seen.last()).isNull()
        staleTimer()
        assertThat(seen).hasSize(2)
    }

    @Test
    fun manualEarlyExpiryCheckReplacesPendingTimer() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(5L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        val staleTimer = scheduler.pending.single().second

        clock.advance(1_000L)
        owner.checkExpiry()
        assertThat(scheduler.pending).hasSize(1)
        assertThat(scheduler.pending.single().first).isEqualTo(6_500L)
        staleTimer()
        assertThat(scheduler.pending).hasSize(1)
        assertThat(owner.snapshot()).isNotNull()
    }

    @Test
    fun expiryNotifiesListenerWithNull() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(6L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(seen.last()).isNotNull()
        scheduler.runAll()
        clock.advance(7_500L)
        owner.checkExpiry()
        assertThat(owner.snapshot()).isNull()
        assertThat(seen.last()).isNull()
    }

    @Test
    fun expiryNotifiesNullExactlyOnceAtFullBudget() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        var nullCount = 0
        owner.setEpisodeListener { snapshot -> if (snapshot == null) nullCount++ }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(60L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        clock.advance(7_500L)
        owner.checkExpiry()
        assertThat(owner.snapshot()).isNull()
        assertThat(nullCount).isEqualTo(1)
        owner.checkExpiry()
        assertThat(nullCount).isEqualTo(1)
    }

    @Test
    fun listenerNeverReentersOwnerMutations() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        var mutations = 0
        owner.setEpisodeListener {
            // Read-only observation only; failures drop the null-snapshot path.
            val active = owner.isEpisodeActive()
            if (active) mutations++
        }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(61L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        clock.advance(7_500L)
        owner.checkExpiry()
        assertThat(owner.snapshot()).isNull()
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun staleAttemptNeverOverwritesCurrentAttempt() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val staleSource = SinkAttemptSource(1, 2L)
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(62L), source)).isTrue()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(62L), staleSource)).isFalse()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(1L), staleSource)).isFalse()
        assertThat(owner.currentAttempt()).isEqualTo(PrePlayAttemptId(62L))
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), staleSource)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(62L))
    }

    @Test
    fun failureSurvivesNaturalDropAndAccruesOnlyVisibleTime() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(7L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        owner.onPrePlayNonFailure(PrePlayNonFailureReason.UNKNOWN_DROP, source)
        assertThat(owner.snapshot()?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        val stale = scheduler.pending.single().second
        clock.advance(3_000L)
        owner.onForegroundVisibilityChanged(false)
        assertThat(scheduler.pending).isEmpty()
        clock.advance(10_000L)
        stale()
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(4_500L)
        assertThat(scheduler.pending).isEmpty()
        owner.onForegroundVisibilityChanged(true)
        assertThat(scheduler.pending.single().first).isEqualTo(4_500L)
        clock.advance(4_499L)
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(1L)
        clock.advance(1L)
        scheduler.runAll()
        assertThat(owner.snapshot()).isNull()
        assertThat(scheduler.pending).isEmpty()
        assertThat(seen).hasSize(2)
        assertThat(seen.last()).isNull()
    }

    @Test
    fun dismissCancelsPendingAndNotifiesNull() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(8L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        owner.onDismissed()
        assertThat(scheduler.pending).isEmpty()
        assertThat(owner.snapshot()).isNull()
        assertThat(seen.last()).isNull()
    }

    @Test
    fun preemptCancelsPendingAndNotifiesNull() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(9L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        owner.onPreempted(PrePlayPreemptReason.FROZEN_STATE)
        assertThat(scheduler.pending).isEmpty()
        assertThat(owner.snapshot()).isNull()
        assertThat(seen.last()).isNull()
    }

    @Test
    fun explicitStopConsumesAttemptAndBlocksLateTerminal() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        val attempt = PrePlayAttemptId(10L)
        owner.onPrePlayAttemptStarted(attempt, source)
        owner.onForegroundVisibilityChanged(true)
        owner.onExplicitStop()
        assertThat(owner.isEpisodeActive()).isFalse()
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    @Test
    fun defaultConstructionNeverCrashesWithoutLooper() {
        val owner = PrePlayFailureEpisodeOwner()
        assertThat(owner.snapshot()).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    private class RecordingQueue : ExpiryMessageQueue {
        val posted = mutableListOf<Runnable>()
        var removeCalls = 0

        override fun postDelayed(delayMs: Long, task: Runnable): Boolean {
            removeCallbacks(task)
            posted.add(task)
            return true
        }

        override fun removeCallbacks(task: Runnable) {
            removeCalls++
            posted.removeAll { it === task }
        }
    }

    private fun queueOwner(
        clock: FakeClock,
        queue: RecordingQueue,
    ): PrePlayFailureEpisodeOwner {
        return PrePlayFailureEpisodeOwner(
            nowMs = { clock.now },
            messageQueue = queue,
            isOwnerThread = { true },
        )
    }

    @Test
    fun postAndRemovalTargetTheSameQueueInstance() {
        val clock = FakeClock()
        val targets = mutableListOf<ExpiryMessageQueue>()
        val tracking = object : ExpiryMessageQueue {
            override fun postDelayed(delayMs: Long, task: Runnable): Boolean {
                targets.add(this)
                return true
            }

            override fun removeCallbacks(task: Runnable) {
                targets.add(this)
            }
        }
        val owner = PrePlayFailureEpisodeOwner(
            nowMs = { clock.now },
            messageQueue = tracking,
            isOwnerThread = { true },
        )
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(75L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        owner.onForegroundVisibilityChanged(false)
        assertThat(targets).isNotEmpty()
        assertThat(targets.distinct()).hasSize(1)
    }

    @Test
    fun hideRemovesPendingOnSameQueue() {
        val clock = FakeClock()
        val queue = RecordingQueue()
        val owner = queueOwner(clock, queue)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(70L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(queue.posted).hasSize(1)
        owner.onForegroundVisibilityChanged(false)
        assertThat(queue.posted).isEmpty()
        clock.advance(20_000L)
        owner.checkExpiry()
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(7_500L)
        assertThat(queue.posted).isEmpty()
    }

    @Test
    fun preemptAndDismissDoNotDispatchStaleRunnable() {
        val clock = FakeClock()
        val queue = RecordingQueue()
        val owner = queueOwner(clock, queue)
        val source = SinkAttemptSource(1, 1L)
        val seen = mutableListOf<PrePlayFailureSnapshot?>()
        owner.setEpisodeListener { seen.add(it) }
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(71L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        val stale = queue.posted.single()
        val staleGeneration = currentQueueGeneration(owner)
        owner.onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
        assertThat(queue.posted).isEmpty()
        owner.checkExpiry(staleGeneration)
        stale.run()
        assertThat(owner.snapshot()).isNull()
        assertThat(seen.last()).isNull()

        val nextSource = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(72L), nextSource)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), nextSource)
        val dismissed = queue.posted.single()
        assertThat(owner.onDismissed()).isEqualTo(PrePlayAttemptId(72L))
        assertThat(queue.posted).isEmpty()
        dismissed.run()
        assertThat(owner.snapshot()).isNull()
        assertThat(seen.last()).isNull()
    }

    @Test
    fun foregroundTogglesNeverAccumulatePosts() {
        val clock = FakeClock()
        val queue = RecordingQueue()
        val owner = queueOwner(clock, queue)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(72L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        repeat(5) {
            owner.onForegroundVisibilityChanged(false)
            owner.onForegroundVisibilityChanged(true)
        }
        assertThat(queue.posted).hasSize(1)
    }

    @Test
    fun staleGenerationNeverTouchesNewerEpisode() {
        val clock = FakeClock()
        val queue = RecordingQueue()
        val owner = queueOwner(clock, queue)
        val source = SinkAttemptSource(1, 1L)
        val nextSource = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(73L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        val first = queue.posted.single()
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(74L), nextSource)
        assertThat(queue.posted).isEmpty()
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), nextSource)
        val second = queue.posted.single()
        first.run()
        assertThat(queue.posted).containsExactly(second)
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(74L))
    }

    private fun currentQueueGeneration(owner: PrePlayFailureEpisodeOwner): Long {
        val field = PrePlayFailureEpisodeOwner::class.java.getDeclaredField("expiryGeneration")
        field.isAccessible = true
        return field.getLong(owner)
    }

    // ===== New tests for Big Standby 04 RTSP→FAILED identity closure =====

    @Test
    fun crossGenOldTerminalRejected() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(2, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(1L), source1)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)
        assertThat(owner.isEpisodeActive()).isTrue()
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(2L), source2)
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source1)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source2)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(2L))
    }

    @Test
    fun sameGenGroupRolloverOldTerminalRejected() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(10L), source1)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)
        assertThat(owner.isEpisodeActive()).isTrue()
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(11L), source2)
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source1)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source2)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(11L))
    }

    @Test
    fun workerStaleBeginAfterNewAttemptBlocked() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(20L), source1)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)
        assertThat(owner.isEpisodeActive()).isTrue()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(21L), source2)).isTrue()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(20L), source1)).isFalse()
        assertThat(owner.currentAttempt()).isEqualTo(PrePlayAttemptId(21L))
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source1)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Rejected("M3", 3, 403), source2)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(21L))
    }

    @Test
    fun stopAfterNewAttemptConsumesAttempt() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(30L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onExplicitStop()
        assertThat(owner.isEpisodeActive()).isFalse()
        // Late terminal after explicit stop should not reopen
        val outcome = owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(outcome).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
    }

    @Test
    fun wifiPausedAfterNewAttemptConsumesAttempt() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(31L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPreempted(PrePlayPreemptReason.WIFI_PAUSED)
        assertThat(owner.isEpisodeActive()).isFalse()
        val outcome = owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(outcome).isNull()
    }

    @Test
    fun frozenStateAfterNewAttemptConsumesAttempt() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(32L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPreempted(PrePlayPreemptReason.FROZEN_STATE)
        assertThat(owner.isEpisodeActive()).isFalse()
        val outcome = owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(outcome).isNull()
    }

    @Test
    fun playPreemptsEpisode() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source = SinkAttemptSource(1, 1L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(33L), source)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(owner.isEpisodeActive()).isTrue()
        owner.onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
        assertThat(owner.isEpisodeActive()).isFalse()
        val outcome = owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
        assertThat(outcome).isNull()
    }

    @Test
    fun naturalDropConsumesAttemptAndNewAttemptPresentsAccrual() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(40L), source1)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayNonFailure(PrePlayNonFailureReason.UNKNOWN_DROP, source1)
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)).isNull()
        assertThat(owner.isEpisodeActive()).isFalse()
        assertThat(owner.onPrePlayAttemptStarted(PrePlayAttemptId(41L), source2)).isTrue()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source2)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(41L))
        clock.advance(3_000L)
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(4_500L)
    }

    @Test
    fun timerRescheduleOnNewAttemptCancelsOldExpiry() {
        val clock = FakeClock()
        val queue = RecordingQueue()
        val owner = queueOwner(clock, queue)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(1, 2L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(50L), source1)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)
        val firstRunnable = queue.posted.single()
        clock.advance(3_000L)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(51L), source2)
        assertThat(owner.snapshot()).isNull()
        assertThat(queue.posted).isEmpty()
        owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source2)
        val secondRunnable = queue.posted.single()
        assertThat(firstRunnable).isNotEqualTo(secondRunnable)
        clock.advance(4_500L)
        firstRunnable.run()
        assertThat(queue.posted).containsExactly(secondRunnable)
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(51L))
        assertThat(owner.snapshot()?.remainingMs).isEqualTo(3_000L)
        clock.advance(3_000L)
        queue.posted.remove(secondRunnable)
        secondRunnable.run()
        assertThat(owner.snapshot()).isNull()
        assertThat(queue.posted).isEmpty()
    }

    @Test
    fun sinkSourceVerificationBlocksStaleSource() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val owner = freshOwner(clock, scheduler)
        val source1 = SinkAttemptSource(1, 1L)
        val source2 = SinkAttemptSource(1, 2L)
        val source3 = SinkAttemptSource(2, 3L)
        val differentGeneration = SinkAttemptSource(2, 1L)
        owner.onForegroundVisibilityChanged(true)
        owner.onPrePlayAttemptStarted(PrePlayAttemptId(60L), source1)
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source2)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source3)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), differentGeneration)).isNull()
        assertThat(owner.snapshot()).isNull()
        assertThat(scheduler.pending).isEmpty()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(60L))

        owner.onPrePlayAttemptStarted(PrePlayAttemptId(61L), source2)
        assertThat(scheduler.pending).isEmpty()
        assertThat(owner.onPrePlayNonFailure(PrePlayNonFailureReason.UNKNOWN_DROP, source1)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source3)).isNull()
        assertThat(owner.snapshot()).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source2)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(61L))

        owner.onPrePlayAttemptStarted(PrePlayAttemptId(62L), source3)
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source1)).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source2)).isNull()
        assertThat(owner.snapshot()).isNull()
        assertThat(owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source3)).isNotNull()
        assertThat(owner.snapshot()?.attemptId).isEqualTo(PrePlayAttemptId(62L))
    }
}
