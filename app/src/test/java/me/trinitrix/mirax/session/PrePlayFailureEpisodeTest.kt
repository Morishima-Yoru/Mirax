package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the pre-play FAILED episode seam (issue 04).
 *
 * Seam under test: [PrePlayFailureEpisode] — feed caller-produced attempt IDs,
 * typed terminal outcomes, and actual visibility; assert genuine-only episodes,
 * identity keying, cumulative 7.5s visible accrual, null expiry, preemption,
 * and stale/duplicate immunity. Uses a fake monotonic clock; no sleeps,
 * threads, devices, or network handles.
 */
class PrePlayFailureEpisodeTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(deltaMs: Long) {
            now += deltaMs
        }
    }

    private fun freshEpisode(clock: FakeClock): PrePlayFailureEpisode {
        return PrePlayFailureEpisode(nowMs = { clock.now })
    }

    private fun genuine(
        attemptId: PrePlayAttemptId,
        cause: PrePlayFailureCause = PrePlayFailureCause.ESTABLISHMENT_FAILED,
    ): PrePlayTerminalOutcome.GenuineFailure {
        return PrePlayTerminalOutcome.GenuineFailure(attemptId = attemptId, cause = cause)
    }

    @Test
    fun genuineFailureVisibleAccruesOnly() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(episode.onTerminalOutcome(genuine(attempt))).isTrue()
        clock.advance(3_000L)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(4_500L)
    }

    @Test
    fun hiddenTimeDoesNotAccrue() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(3_000L)
        episode.onVisibilityChanged(false)
        clock.advance(10_000L)
        episode.onVisibilityChanged(true)
        clock.advance(4_500L)
        assertThat(episode.snapshot()).isNull()
        assertThat(episode.expireIfDue()).isEqualTo(attempt)
    }

    @Test
    fun exactBoundary7500Expires() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(7_500L)
        assertThat(episode.snapshot()).isNull()
        assertThat(episode.expireIfDue()).isEqualTo(attempt)
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun longHideThenResumeAccumulates() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(2_000L)
        episode.onVisibilityChanged(false)
        clock.advance(60_000L)
        episode.onVisibilityChanged(true)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(5_500L)
        clock.advance(5_500L)
        assertThat(episode.snapshot()).isNull()
        assertThat(episode.expireIfDue()).isEqualTo(attempt)
    }

    @Test
    fun repeatedVisibilityReportsAreIdempotent() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(1_000L)
        episode.onVisibilityChanged(true)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(6_500L)
        episode.onVisibilityChanged(false)
        episode.onVisibilityChanged(false)
        clock.advance(9_000L)
        episode.onVisibilityChanged(true)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(6_500L)
    }

    @Test
    fun nonGenuineOutcomesNeverOpen() {
        val clock = FakeClock()
        PrePlayNonFailureReason.entries.forEach { reason ->
            val episode = freshEpisode(clock)
            val attempt = PrePlayAttemptId(1L)
            assertThat(episode.beginAttempt(attempt)).isTrue()
            episode.onVisibilityChanged(true)
            val outcome = PrePlayTerminalOutcome.NonFailure(attemptId = attempt, reason = reason)
            assertThat(episode.onTerminalOutcome(outcome)).isFalse()
            clock.advance(7_500L)
            assertThat(episode.snapshot()).isNull()
            assertThat(episode.expireIfDue()).isNull()
        }
    }

    @Test
    fun unknownIdentityOutcomeIgnored() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        assertThat(episode.beginAttempt(PrePlayAttemptId(1L))).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(episode.onTerminalOutcome(genuine(PrePlayAttemptId(999L)))).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun terminalWithNoActiveAttemptRejectedEvenWithLargeToken() {
        val episode = freshEpisode(FakeClock())
        val outcome = genuine(PrePlayAttemptId(999_999L))
        assertThat(episode.onTerminalOutcome(outcome)).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun duplicateTerminalCannotReviveAfterDismiss() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        val outcome = genuine(attempt, PrePlayFailureCause.NEGOTIATION_FAILED)
        assertThat(episode.onTerminalOutcome(outcome)).isTrue()
        assertThat(episode.dismiss(attempt)).isTrue()
        assertThat(episode.onTerminalOutcome(outcome)).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun secondTerminalCannotReplaceFirstCause() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(
            episode.onTerminalOutcome(genuine(attempt, PrePlayFailureCause.ESTABLISHMENT_FAILED)),
        ).isTrue()
        assertThat(
            episode.onTerminalOutcome(genuine(attempt, PrePlayFailureCause.PRE_PLAY_TIMEOUT)),
        ).isFalse()
        assertThat(episode.snapshot()?.cause).isEqualTo(PrePlayFailureCause.ESTABLISHMENT_FAILED)
    }

    @Test
    fun newAttemptInvalidatesOldEpisodeAndOutcome() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val first = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(first)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(episode.onTerminalOutcome(genuine(first))).isTrue()
        val second = PrePlayAttemptId(2L)
        assertThat(episode.beginAttempt(second)).isTrue()
        assertThat(episode.snapshot()).isNull()
        assertThat(episode.onTerminalOutcome(genuine(first))).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun newAttemptAfterSilentTeardownStillInvalidatesLateGenuine() {
        val episode = freshEpisode(FakeClock())
        val first = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(first)).isTrue()
        episode.onTerminalOutcome(
            PrePlayTerminalOutcome.NonFailure(
                attemptId = first,
                reason = PrePlayNonFailureReason.NORMAL_TEARDOWN,
            ),
        )
        assertThat(episode.beginAttempt(PrePlayAttemptId(2L))).isTrue()
        assertThat(episode.onTerminalOutcome(genuine(first))).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun newBeginAccepted() {
        val episode = freshEpisode(FakeClock())
        assertThat(episode.beginAttempt(PrePlayAttemptId(7L))).isTrue()
    }

    @Test
    fun duplicateBeginRejectedAndKeepsEpisode() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(4L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(episode.onTerminalOutcome(genuine(attempt))).isTrue()
        assertThat(episode.beginAttempt(attempt)).isFalse()
        assertThat(episode.snapshot()?.attemptId).isEqualTo(attempt)
    }

    @Test
    fun illegalBeginRejected() {
        val episode = freshEpisode(FakeClock())
        assertThat(episode.beginAttempt(PrePlayAttemptId(0L))).isFalse()
        assertThat(episode.beginAttempt(PrePlayAttemptId(-3L))).isFalse()
        assertThat(episode.snapshot()).isNull()
    }

    @Test
    fun lateOldBeginCannotReplaceNew() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val first = PrePlayAttemptId(5L)
        val second = PrePlayAttemptId(6L)
        assertThat(episode.beginAttempt(first)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(episode.onTerminalOutcome(genuine(first))).isTrue()
        assertThat(episode.beginAttempt(second)).isTrue()
        assertThat(episode.onTerminalOutcome(genuine(second))).isTrue()
        assertThat(episode.beginAttempt(first)).isFalse()
        val snapshot = episode.snapshot()
        assertThat(snapshot?.attemptId).isEqualTo(second)
        assertThat(episode.onTerminalOutcome(genuine(first))).isFalse()
    }

    @Test
    fun sequentialAttemptsInSameGroupUseDistinctIds() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val first = PrePlayAttemptId(10L)
        val second = PrePlayAttemptId(11L)
        assertThat(episode.beginAttempt(first)).isTrue()
        episode.onVisibilityChanged(true)
        assertThat(
            episode.onTerminalOutcome(genuine(first, PrePlayFailureCause.NEGOTIATION_FAILED)),
        ).isTrue()
        assertThat(episode.snapshot()?.attemptId).isEqualTo(first)
        assertThat(episode.beginAttempt(second)).isTrue()
        assertThat(episode.snapshot()).isNull()
        assertThat(
            episode.onTerminalOutcome(genuine(second, PrePlayFailureCause.PRE_PLAY_TIMEOUT)),
        ).isTrue()
        val snapshot = episode.snapshot()
        assertThat(snapshot?.attemptId).isEqualTo(second)
        assertThat(snapshot?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
    }

    @Test
    fun preemptClearsEpisodeAndConsumesAttempt() {
        val clock = FakeClock()
        val reasons = PrePlayPreemptReason.entries
        var nextId = 20L
        reasons.forEach { reason ->
            val episode = freshEpisode(clock)
            val attempt = PrePlayAttemptId(nextId)
            nextId += 1L
            assertThat(episode.beginAttempt(attempt)).isTrue()
            episode.onVisibilityChanged(true)
            episode.onTerminalOutcome(genuine(attempt))
            clock.advance(1_000L)
            assertThat(episode.preempt(reason)).isTrue()
            assertThat(episode.snapshot()).isNull()
            assertThat(episode.onTerminalOutcome(genuine(attempt))).isFalse()
            assertThat(episode.snapshot()).isNull()
        }
    }

    @Test
    fun preemptWithoutEpisodeReturnsFalse() {
        val episode = freshEpisode(FakeClock())
        assertThat(episode.beginAttempt(PrePlayAttemptId(1L))).isTrue()
        assertThat(episode.preempt(PrePlayPreemptReason.EXPLICIT_STOP)).isFalse()
    }

    @Test
    fun dismissReturnsFalseWithoutEpisode() {
        val episode = freshEpisode(FakeClock())
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        assertThat(episode.dismiss(attempt)).isFalse()
    }

    @Test
    fun staleDismissKeepsNewerEpisode() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val first = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(first)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(first))
        val second = PrePlayAttemptId(2L)
        assertThat(episode.beginAttempt(second)).isTrue()
        episode.onTerminalOutcome(
            genuine(second, PrePlayFailureCause.PRE_PLAY_TIMEOUT),
        )
        assertThat(episode.dismiss(first)).isFalse()
        val snapshot = episode.snapshot()
        assertThat(snapshot?.attemptId).isEqualTo(second)
        assertThat(snapshot?.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
    }

    @Test
    fun expiryReturnsNullWhenIdle() {
        val episode = freshEpisode(FakeClock())
        assertThat(episode.expireIfDue()).isNull()
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        assertThat(episode.expireIfDue()).isNull()
        assertThat(episode.snapshot()).isNull()
        episode.dismiss(attempt)
    }

    @Test
    fun hiddenClockBackwardKeepsAccruedTime() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(2_000L)
        episode.onVisibilityChanged(false)
        clock.now -= 1_000L
        episode.onVisibilityChanged(true)
        clock.advance(1_500L)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(4_000L)
    }

    @Test
    fun recreationResumesWithoutReplay() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(2_000L)
        episode.onVisibilityChanged(false)
        episode.onVisibilityChanged(true)
        assertThat(episode.snapshot()?.remainingMs).isEqualTo(5_500L)
        assertThat(episode.onTerminalOutcome(genuine(attempt))).isFalse()
    }

    @Test
    fun fakeClockReadsDoNotMutateState() {
        val clock = FakeClock()
        val episode = freshEpisode(clock)
        val attempt = PrePlayAttemptId(1L)
        assertThat(episode.beginAttempt(attempt)).isTrue()
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(genuine(attempt))
        clock.advance(1_000L)
        val firstRemaining = episode.snapshot()?.remainingMs
        val secondRemaining = episode.snapshot()?.remainingMs
        assertThat(firstRemaining).isEqualTo(6_500L)
        assertThat(secondRemaining).isEqualTo(firstRemaining)
        assertThat(clock.now).isEqualTo(2_000L)
    }
}
