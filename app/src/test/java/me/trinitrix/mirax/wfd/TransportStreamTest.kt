package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.wfd.rtsp.RtspSinkSession
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import org.junit.Test
import java.nio.charset.StandardCharsets

class TransportStreamTest {

    @Test
    fun inMemoryStream_canDriveRtspSinkSessionM1ThroughPlay() {
        val stream = InMemoryTransportStream()
        val caps = WfdCapabilityTable(
            modes = setOf(VideoMode(1920, 1080, 60)),
            preferred = VideoMode(1920, 1080, 60),
            friendlyName = "TestSink",
            touchEnabled = true,
        )
        val session = RtspSinkSession(caps)

        val m1 = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
        val responses = session.handle(m1)
        assertThat(responses).isNotEmpty()

        for (resp in responses) {
            stream.outputStream.write(resp.toByteArray(StandardCharsets.US_ASCII))
        }

        val written = stream.writtenAscii()
        assertThat(written).contains("RTSP/1.0 200 OK")
        assertThat(written).contains("Public: org.wfa.wfd1.0")
        assertThat(session.state).isEqualTo("WAIT_M2")
    }
}
