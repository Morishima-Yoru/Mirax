package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the pure FAILED visibility projection.
 *
 * Seam under test: [prePlayFailurePresentation] — feed episode snapshot,
 * latest attempt, foreground flag, and screen phase; assert Hidden/Shown.
 * No Android, sleeps, threads, or devices.
 */
class PrePlayFailurePresentationTest {

    private fun episode(
        attempt: Long = 1L,
        cause: PrePlayFailureCause = PrePlayFailureCause.ESTABLISHMENT_FAILED,
    ): PrePlayFailureSnapshot {
        return PrePlayFailureSnapshot(
            attemptId = PrePlayAttemptId(attempt),
            cause = cause,
            remainingMs = 7_500L,
        )
    }

    @Test
    fun nullEpisodeIsHidden() {
        assertThat(
            prePlayFailurePresentation(
                episode = null,
                latestAttempt = PrePlayAttemptId(1L),
                standbyForeground = true,
                phase = ScreenPhase.CONNECTING,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }

    @Test
    fun backgroundForegroundIsHidden() {
        assertThat(
            prePlayFailurePresentation(
                episode = episode(),
                latestAttempt = PrePlayAttemptId(1L),
                standbyForeground = false,
                phase = ScreenPhase.CONNECTING,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }

    @Test
    fun staleAttemptIsHidden() {
        assertThat(
            prePlayFailurePresentation(
                episode = episode(attempt = 1L),
                latestAttempt = PrePlayAttemptId(2L),
                standbyForeground = true,
                phase = ScreenPhase.CONNECTING,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }

    @Test
    fun nullLatestAttemptIsHidden() {
        assertThat(
            prePlayFailurePresentation(
                episode = episode(),
                latestAttempt = null,
                standbyForeground = true,
                phase = ScreenPhase.CONNECTING,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }

    @Test
    fun supersedingVisualPhasesAreHidden() {
        for (phase in
            listOf(
                ScreenPhase.CONNECTED,
                ScreenPhase.WIFI_PAUSED,
                ScreenPhase.FROZEN,
            )) {
            assertThat(
                prePlayFailurePresentation(
                    episode = episode(),
                    latestAttempt = PrePlayAttemptId(1L),
                    standbyForeground = true,
                    phase = phase,
                ),
            ).isEqualTo(PrePlayFailurePresentation.Hidden)
        }
    }

    @Test
    fun underlyingReturnPhasesKeepVisible() {
        for (phase in
            listOf(
                ScreenPhase.CONNECTING,
                ScreenPhase.ADVERTISING,
                ScreenPhase.ARMING,
                ScreenPhase.READY,
            )) {
            assertThat(
                prePlayFailurePresentation(
                    episode = episode(),
                    latestAttempt = PrePlayAttemptId(1L),
                    standbyForeground = true,
                    phase = phase,
                ),
            ).isEqualTo(
                PrePlayFailurePresentation.Shown(PrePlayFailureCause.ESTABLISHMENT_FAILED),
            )
        }
    }

    @Test
    fun allGatesPassShowsCause() {
        assertThat(
            prePlayFailurePresentation(
                episode = episode(cause = PrePlayFailureCause.PRE_PLAY_TIMEOUT),
                latestAttempt = PrePlayAttemptId(1L),
                standbyForeground = true,
                phase = ScreenPhase.CONNECTING,
            ),
        ).isEqualTo(
            PrePlayFailurePresentation.Shown(PrePlayFailureCause.PRE_PLAY_TIMEOUT),
        )
    }

    @Test
    fun shownCarriesEachCauseWithoutStrings() {
        for (cause in PrePlayFailureCause.entries) {
            assertThat(
                prePlayFailurePresentation(
                    episode = episode(cause = cause),
                    latestAttempt = PrePlayAttemptId(1L),
                    standbyForeground = true,
                    phase = ScreenPhase.CONNECTING,
                ),
            ).isEqualTo(PrePlayFailurePresentation.Shown(cause))
        }
    }
}
