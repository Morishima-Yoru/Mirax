package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

/** Exercises the exact bounded-wait decision used by the socket controller. */
class SinkOfferRendezvousTest {
    @Test
    fun commitClaimPastDeadlineDoesNotEndOrRetry() = exerciseBarrier(0)

    @Test
    fun freezePastDeadlineDoesNotEndOrRetry() = exerciseBarrier(1)

    @Test
    fun publicationPastDeadlineDoesNotEndOrRetry() = exerciseBarrier(2)

    private fun exerciseBarrier(stage: Int) {
        val request = SinkOfferRequest<String>()
        val atBarrier = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val pendingObserved = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val freezeCount = AtomicInteger()
        val ends = AtomicInteger()
        val attempts = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<SinkOfferDecision<String>?>()
        val callback = Thread {
            try {
                check(request.start())
                check(request.beginCommit())
                fun barrier(point: Int) {
                    if (stage == point) {
                        atBarrier.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                barrier(0)
                freezeCount.incrementAndGet()
                barrier(1)
                val offer = "complete offer"
                barrier(2)
                check(request.complete(offer))
            } catch (err: Throwable) {
                failure.set(err)
            } finally {
                request.fail()
                callbackDone.countDown()
            }
        }
        val worker = Thread {
            try {
                var firstWait = true
                val decision = awaitSinkOffer(
                    request = request,
                    remainingMs = 0,
                    pollMs = 1,
                    waitMs = {
                        if (firstWait) {
                            firstWait = false
                            false
                        } else {
                            pendingObserved.countDown()
                            callbackDone.await(10, TimeUnit.SECONDS)
                        }
                    },
                    isCurrent = { true },
                    onPendingPoll = {},
                )
                result.set(decision)
                if (decision !is SinkOfferDecision.Completed) {
                    ends.incrementAndGet()
                    attempts.incrementAndGet()
                }
            } catch (err: Throwable) {
                failure.set(err)
            } finally {
                workerDone.countDown()
            }
        }
        callback.isDaemon = true
        worker.isDaemon = true
        try {
            callback.start()
            assertThat(atBarrier.await(5, TimeUnit.SECONDS)).isTrue()
            worker.start()
            assertThat(pendingObserved.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(request.decision()).isEqualTo(SinkOfferDecision.CommittingPending)
            assertThat(result.get()).isNull()
            assertThat(ends.get()).isEqualTo(0)
            assertThat(attempts.get()).isEqualTo(0)
            release.countDown()
            assertThat(workerDone.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(failure.get()).isNull()
            assertThat(result.get()).isEqualTo(SinkOfferDecision.Completed("complete offer"))
            assertThat(freezeCount.get()).isEqualTo(1)
            assertThat(ends.get()).isEqualTo(0)
            assertThat(attempts.get()).isEqualTo(0)
        } finally {
            release.countDown()
            callback.join(5_000L)
            if (worker.state != Thread.State.NEW) worker.join(5_000L)
        }
    }

    @Test
    fun preCommitDeadlineCancelsLateCallbackBeforeMutation() {
        val request = SinkOfferRequest<String>()
        val decision = awaitSinkOffer(request, 0, 1, { false }, { true }, {})
        var freezes = 0
        if (request.start() && request.beginCommit()) freezes++
        assertThat(decision).isEqualTo(SinkOfferDecision.PreCommitCancelled)
        assertThat(freezes).isEqualTo(0)
        assertThat(request.complete("late")).isFalse()
    }

    @Test
    fun observedDownWhileCommittingStopsWaitingWithoutSuccess() {
        val request = SinkOfferRequest<String>()
        val current = AtomicBoolean(true)
        assertThat(request.start()).isTrue()
        assertThat(request.beginCommit()).isTrue()
        val decision = awaitSinkOffer(request, 0, 1, { false }, { current.get() }) {
            current.set(false)
        }
        assertThat(decision).isEqualTo(SinkOfferDecision.FailedOrSuperseded)
        assertThat(request.decision()).isEqualTo(SinkOfferDecision.CommittingPending)
    }
}
