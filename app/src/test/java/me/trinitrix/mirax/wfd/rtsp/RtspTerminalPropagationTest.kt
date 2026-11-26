package me.trinitrix.mirax.wfd.rtsp

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.PrePlayAttemptId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.PrePlayTerminalOutcome
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.terminalOutcome
import org.junit.Test

/**
 * Propagation decision for finished RTSP sink runs.
 *
 * Drives only the public [RtspSinkSession] seam with an injectable clock.
 * No sleeps, no reflection, no network.
 */
class RtspTerminalPropagationTest {
    private val foldPreferred = VideoMode(2176, 1812, 60)
    private val defaults = setOf(
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
        foldPreferred,
    )
    private val attempt = PrePlayAttemptId(1L)

    private fun caps(): WfdCapabilityTable {
        return WfdCapabilityTable(defaults, foldPreferred, "Mirax", false)
    }

    private fun cseqOf(message: String): Int {
        val found = Regex("CSeq: (\\d+)").find(message) ?: return -1
        return found.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun startM2(now: LongArray): Pair<RtspSinkSession, Int> {
        val session = RtspSinkSession(caps(), clockMs = { now[0] })
        val m1 = session.handle(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n",
        )
        assertThat(m1).hasSize(2)
        val pending = cseqOf(m1[1])
        assertThat(pending).isGreaterThan(0)
        return Pair(session, pending)
    }

    private fun driveToSetupPending(now: LongArray): Pair<RtspSinkSession, Int> {
        val session = RtspSinkSession(caps(), clockMs = { now[0] })
        session.handle(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n",
        )
        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\n" +
                "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n",
        )
        session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\nContent-Length: 88\r\n\r\n" +
                "wfd_video_formats\r\n" +
                "wfd_audio_codecs\r\n" +
                "wfd_client_rtp_ports\r\n" +
                "microsoft_custom_video_formats\r\n",
        )
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 3\r\nContent-Type: text/parameters\r\n\r\n" +
                "microsoft_custom_video_formats: 0880 0714 003C\r\n" +
                "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n" +
                "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n",
        )
        val triggered = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                "Content-Type: text/parameters\r\n\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        assertThat(triggered).hasSize(2)
        val pending = cseqOf(triggered[1])
        assertThat(pending).isGreaterThan(0)
        assertThat(session.terminalFailure).isNull()
        return Pair(session, pending)
    }

    @Test
    fun unansweredM2TimeoutForwardsAsPrePlayTimeout() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        now[0] = 5_001L
        assertThat(session.checkExchangeTimeout()).isTrue()
        val forwarded = terminalFailureToForward(session, reachedPlay = false)
        assertThat(forwarded).isInstanceOf(RtspFailure.Timeout::class.java)
        val timeout = forwarded as RtspFailure.Timeout
        assertThat(timeout.method).isEqualTo("OPTIONS")
        assertThat(timeout.cseq).isEqualTo(pending)
        val outcome = terminalOutcome(attempt, forwarded)
        val genuine = outcome as PrePlayTerminalOutcome.GenuineFailure
        assertThat(genuine.cause).isEqualTo(PrePlayFailureCause.PRE_PLAY_TIMEOUT)
    }

    @Test
    fun explicitRejectionForwardsAsEstablishmentFailed() {
        val now = longArrayOf(0L)
        val (session, pending) = driveToSetupPending(now)
        session.handle("RTSP/1.0 503 Service Unavailable\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        val forwarded = terminalFailureToForward(session, reachedPlay = false)
        assertThat(forwarded).isInstanceOf(RtspFailure.Rejected::class.java)
        val rejected = forwarded as RtspFailure.Rejected
        assertThat(rejected.method).isEqualTo("SETUP")
        assertThat(rejected.cseq).isEqualTo(pending)
        assertThat(rejected.statusCode).isEqualTo(503)
        val outcome = terminalOutcome(attempt, forwarded)
        val genuine = outcome as PrePlayTerminalOutcome.GenuineFailure
        assertThat(genuine.cause).isEqualTo(PrePlayFailureCause.ESTABLISHMENT_FAILED)
    }

    @Test
    fun successfulRunForwardsNothing() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $pending\r\n" +
                "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("READY")
        assertThat(session.terminalFailure).isNull()
        assertThat(terminalFailureToForward(session, reachedPlay = false)).isNull()
        assertThat(terminalOutcome(attempt, null)).isNull()
    }

    @Test
    fun reachedPlaySuppressesGenuineEvidence() {
        val now = longArrayOf(0L)
        val (session, _) = startM2(now)
        now[0] = 5_001L
        assertThat(session.checkExchangeTimeout()).isTrue()
        assertThat(terminalFailureToForward(session, reachedPlay = true)).isNull()
    }
}
