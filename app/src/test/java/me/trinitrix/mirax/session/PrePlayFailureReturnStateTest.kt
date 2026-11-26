package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Return-state regression for the FAILED projection.
 *
 * Drives the real [MiraxSession] phase machine through
 * CONNECTING -> [SessionAction.PrePlayGroupDropped] -> ADVERTISING/ARMING and
 * feeds the resulting phase plus a real [PrePlayFailureEpisode] snapshot into
 * [prePlayFailurePresentation]. Proves the natural group-drop return keeps a
 * visible episode on screen, while truly superseding visual phases still hide.
 * A plain READY phase never proves an explicit stop, so a live episode stays
 * visible there; a genuine explicit stop is proven only by a real
 * [PrePlayFailureEpisode.preempt] with [PrePlayPreemptReason.EXPLICIT_STOP],
 * which clears the snapshot to null and then hides.
 *
 * Scope is projection plus episode only: owner/host event wiring
 * ([PrePlayFailureEpisodeOwner]/SessionHost integration) is not claimed here;
 * zero side effects beyond the projection gate are not claimed here.
 */
class PrePlayFailureReturnStateTest {

    private fun broadcastSession(withBeacon: Boolean): MiraxSession {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        if (withBeacon) {
            session.handle(SessionAction.BeaconListening)
        }
        return session
    }

    private fun liveEpisode(
        attempt: PrePlayAttemptId,
        cause: PrePlayFailureCause,
    ): PrePlayFailureSnapshot {
        val episode = PrePlayFailureEpisode(nowMs = { 1_000L })
        episode.beginAttempt(attempt)
        episode.onVisibilityChanged(true)
        episode.onTerminalOutcome(
            PrePlayTerminalOutcome.GenuineFailure(attempt, cause),
        )
        return episode.snapshot() ?: error("episode must open for $cause")
    }

    @Test
    fun groupDropToAdvertisingKeepsVisibleForAllCauses() {
        val session = broadcastSession(withBeacon = true)
        session.handle(SessionAction.PrePlayProgress)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTING)

        val attempt = PrePlayAttemptId(11L)
        for (cause in PrePlayFailureCause.entries) {
            val episode = liveEpisode(attempt, cause)
            assertThat(
                prePlayFailurePresentation(
                    episode = episode,
                    latestAttempt = attempt,
                    standbyForeground = true,
                    phase = session.snapshot().phase,
                ),
            ).isEqualTo(PrePlayFailurePresentation.Shown(cause))
        }

        session.handle(SessionAction.PrePlayGroupDropped)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)

        for (cause in PrePlayFailureCause.entries) {
            val episode = liveEpisode(attempt, cause)
            assertThat(
                prePlayFailurePresentation(
                    episode = episode,
                    latestAttempt = attempt,
                    standbyForeground = true,
                    phase = session.snapshot().phase,
                ),
            ).isEqualTo(PrePlayFailurePresentation.Shown(cause))
        }
    }

    @Test
    fun groupDropToArmingKeepsVisible() {
        val session = broadcastSession(withBeacon = false)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ARMING)
        session.handle(SessionAction.PrePlayProgress)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTING)

        session.handle(SessionAction.PrePlayGroupDropped)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ARMING)

        val attempt = PrePlayAttemptId(12L)
        val episode = liveEpisode(attempt, PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = attempt,
                standbyForeground = true,
                phase = session.snapshot().phase,
            ),
        ).isEqualTo(
            PrePlayFailurePresentation.Shown(PrePlayFailureCause.PRE_PLAY_TIMEOUT),
        )
    }

    @Test
    fun supersedingVisualPhasesFromRealSessionHideLiveEpisode() {
        val attempt = PrePlayAttemptId(13L)
        val episode = liveEpisode(attempt, PrePlayFailureCause.ESTABLISHMENT_FAILED)

        val connected = broadcastSession(withBeacon = true)
        connected.handle(SessionAction.ConnectionEstablished)
        assertThat(connected.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = attempt,
                standbyForeground = true,
                phase = connected.snapshot().phase,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)

        for (withBeacon in listOf(false, true)) {
            val wifiPaused = broadcastSession(withBeacon = withBeacon)
            assertThat(wifiPaused.snapshot().phase).isEqualTo(
                if (withBeacon) ScreenPhase.ADVERTISING else ScreenPhase.ARMING,
            )
            wifiPaused.report(WifiReport(enabled = false))
            val paused = wifiPaused.snapshot()
            assertThat(paused.phase).isEqualTo(ScreenPhase.WIFI_PAUSED)
            assertThat(paused.advertisingEnabled).isTrue()
            assertThat(paused.wfdAdvertise).isNull()
            assertThat(
                prePlayFailurePresentation(
                    episode = episode,
                    latestAttempt = attempt,
                    standbyForeground = true,
                    phase = paused.phase,
                ),
            ).isEqualTo(PrePlayFailurePresentation.Hidden)
        }

        val frozen = broadcastSession(withBeacon = true)
        frozen.report(PrivilegeReport())
        assertThat(frozen.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = attempt,
                standbyForeground = true,
                phase = frozen.snapshot().phase,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)

        val readySession = broadcastSession(withBeacon = true)
        readySession.handle(SessionAction.SetAdvertising(false))
        assertThat(readySession.snapshot().phase).isEqualTo(ScreenPhase.READY)
        // Plain READY proves no explicit stop: a live episode stays visible.
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = attempt,
                standbyForeground = true,
                phase = readySession.snapshot().phase,
            ),
        ).isEqualTo(
            PrePlayFailurePresentation.Shown(PrePlayFailureCause.ESTABLISHMENT_FAILED),
        )

        // A genuine explicit stop is an event, proven only by real preemption.
        val stopEpisode = PrePlayFailureEpisode(nowMs = { 1_000L })
        stopEpisode.beginAttempt(attempt)
        stopEpisode.onVisibilityChanged(true)
        stopEpisode.onTerminalOutcome(
            PrePlayTerminalOutcome.GenuineFailure(
                attempt,
                PrePlayFailureCause.ESTABLISHMENT_FAILED,
            ),
        )
        assertThat(stopEpisode.preempt(PrePlayPreemptReason.EXPLICIT_STOP)).isTrue()
        val cleared = stopEpisode.snapshot()
        assertThat(cleared).isNull()
        assertThat(
            prePlayFailurePresentation(
                episode = cleared,
                latestAttempt = attempt,
                standbyForeground = true,
                phase = readySession.snapshot().phase,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }

    @Test
    fun staleAttemptAndBackgroundStayHiddenAfterRealDrop() {
        val session = broadcastSession(withBeacon = true)
        session.handle(SessionAction.PrePlayProgress)
        session.handle(SessionAction.PrePlayGroupDropped)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        val phase = session.snapshot().phase

        val episode = liveEpisode(PrePlayAttemptId(14L), PrePlayFailureCause.PRE_PLAY_TIMEOUT)
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = PrePlayAttemptId(15L),
                standbyForeground = true,
                phase = phase,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
        assertThat(
            prePlayFailurePresentation(
                episode = episode,
                latestAttempt = PrePlayAttemptId(14L),
                standbyForeground = false,
                phase = phase,
            ),
        ).isEqualTo(PrePlayFailurePresentation.Hidden)
    }
}
