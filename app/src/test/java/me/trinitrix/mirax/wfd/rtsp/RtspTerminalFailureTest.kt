package me.trinitrix.mirax.wfd.rtsp

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.VideoMode
import org.junit.Test

/**
 * Terminal failure evidence for genuine RTSP exchange outcomes.
 *
 * Drives only the public [RtspSinkSession] seam with an injectable clock.
 * No sleeps, no reflection, no network.
 */
class RtspTerminalFailureTest {
    private val foldPreferred = VideoMode(2176, 1812, 60)
    private val defaults = setOf(
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
        foldPreferred,
    )

    private fun caps(touchEnabled: Boolean = false): WfdCapabilityTable {
        return WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled)
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
    fun exactFiveSecondTimeoutRecordsMethodAndCseq() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        now[0] = 5_000L
        assertThat(session.checkExchangeTimeout()).isTrue()
        assertThat(session.state).isEqualTo("ERROR")
        val failure = session.terminalFailure
        assertThat(failure).isInstanceOf(RtspFailure.Timeout::class.java)
        val timeout = failure as RtspFailure.Timeout
        assertThat(timeout.method).isEqualTo("OPTIONS")
        assertThat(timeout.cseq).isEqualTo(pending)
    }

    @Test
    fun matchingResponseAfterDeadlineRecordsTimeout() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        now[0] = 5_001L
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        val failure = session.terminalFailure
        assertThat(failure).isInstanceOf(RtspFailure.Timeout::class.java)
        val timeout = failure as RtspFailure.Timeout
        assertThat(timeout.method).isEqualTo("OPTIONS")
        assertThat(timeout.cseq).isEqualTo(pending)
    }

    @Test
    fun setupRejectionRetainsMethodAndStatus() {
        val now = longArrayOf(0L)
        val (session, pending) = driveToSetupPending(now)
        session.handle("RTSP/1.0 404 Not Found\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        val failure = session.terminalFailure
        assertThat(failure).isInstanceOf(RtspFailure.Rejected::class.java)
        val rejected = failure as RtspFailure.Rejected
        assertThat(rejected.method).isEqualTo("SETUP")
        assertThat(rejected.cseq).isEqualTo(pending)
        assertThat(rejected.statusCode).isEqualTo(404)
    }

    @Test
    fun mismatchedCseqIsIgnoredWithoutEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0 404 Not Found\r\nCSeq: ${pending + 100}\r\n\r\n")
        assertThat(session.state).isEqualTo("WAIT_M2")
        assertThat(session.terminalFailure).isNull()
        assertThat(session.checkExchangeTimeout()).isFalse()
    }

    @Test
    fun malformedStatusIsNotPositiveEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0 BOGUS\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun provisionalResponseIsNotPositiveEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0 100 Trying\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun acceptedResponseLeavesNoFailure() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $pending\r\n" +
                "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("READY")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun noPendingMissingCseqKeepsErrorStateWithoutEvidence() {
        val now = longArrayOf(10_000L)
        val session = RtspSinkSession(caps(), clockMs = { now[0] })
        session.handle("RTSP/1.0 403 Forbidden\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun pendingButMissingCseqIsIgnoredWithoutEvidence() {
        val now = longArrayOf(0L)
        val session = startM2(now).first
        session.handle("RTSP/1.0 404 Not Found\r\n\r\n")
        assertThat(session.state).isEqualTo("WAIT_M2")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun invalidVersionWithPendingKeepsErrorStateWithoutEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0BAD 404 Not Found\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun fourDigitCodeWithPendingKeepsErrorStateWithoutEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0 0404 Not Found\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun signedCodeWithPendingKeepsErrorStateWithoutEvidence() {
        val now = longArrayOf(0L)
        val (session, pending) = startM2(now)
        session.handle("RTSP/1.0 +404 Not Found\r\nCSeq: $pending\r\n\r\n")
        assertThat(session.state).isEqualTo("ERROR")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun sourceTeardownIsNotGenuineFailure() {
        val now = longArrayOf(0L)
        val session = RtspSinkSession(caps(), clockMs = { now[0] })
        session.handle("TEARDOWN rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 9\r\nSession: abc\r\n\r\n")
        assertThat(session.state).isEqualTo("TEARDOWN")
        assertThat(session.terminalFailure).isNull()
    }

    @Test
    fun uibcEnableRejectionKeepsPlayingWithoutFailure() {
        val now = longArrayOf(0L)
        val session = RtspSinkSession(caps(touchEnabled = true), clockMs = { now[0] })
        session.handle(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n",
        )
        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\n" +
                "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n",
        )
        session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "wfd_video_formats\r\n",
        )
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 3\r\nContent-Type: text/parameters\r\n\r\n" +
                "microsoft_custom_video_formats: 0880 0714 003C\r\n" +
                "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n" +
                "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50010\r\n",
        )
        val triggered = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                "Content-Type: text/parameters\r\n\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        val setupCseq = cseqOf(triggered[1])
        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $setupCseq\r\nSession: 12345678;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        // PLAY request is emitted automatically after SETUP; ack it to reach PLAYING.
        val playCseq = setupCseq + 1
        val afterPlay = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $playCseq\r\nSession: 12345678\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("PLAYING")
        val idrCseq = cseqOf(afterPlay[0])
        assertThat(idrCseq).isGreaterThan(0)
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $idrCseq\r\n\r\n")
        val m15 = session.requestUibcEnable()
        assertThat(m15).isNotEmpty()
        val m15Cseq = cseqOf(m15)
        session.handle("RTSP/1.0 403 Forbidden\r\nCSeq: $m15Cseq\r\n\r\n")
        assertThat(session.state).isEqualTo("PLAYING")
        assertThat(session.uibcEnabled).isFalse()
        assertThat(session.terminalFailure).isNull()
    }
}
