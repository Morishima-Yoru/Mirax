package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.wfd.rtsp.RtspMessageBuffer
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Wire behaviour of the sink's control connection: the owner's group-state
 * line, the P2P neighbour lookup, and RTSP message framing across reads.
 */
class SinkConnectionWireTest {

    @Test
    fun groupState_parsesOwnerLines() {
        assertThat(P2pGroupState.parse("UP 192.168.137.1"))
            .isEqualTo(P2pGroupState.Up("192.168.137.1"))
        assertThat(P2pGroupState.parse("PENDING")).isEqualTo(P2pGroupState.Pending)
        assertThat(P2pGroupState.parse("DOWN")).isEqualTo(P2pGroupState.Down)
        assertThat(P2pGroupState.parse(null)).isEqualTo(P2pGroupState.Down)
        assertThat(P2pGroupState.parse("UP ")).isEqualTo(P2pGroupState.Down)
    }

    @Test
    fun persistentNetworkIds_forgetsEverySavedGroup() {
        assertThat(SavedP2pGroups.persistentNetworkIds(intArrayOf(0, 1, 2)))
            .isEqualTo(intArrayOf(0, 1, 2))
        assertThat(SavedP2pGroups.persistentNetworkIds(intArrayOf(-1, 0, -2)))
            .isEqualTo(intArrayOf(0))
        assertThat(SavedP2pGroups.persistentNetworkIds(null)).isEmpty()
        assertThat(SavedP2pGroups.persistentNetworkIds(intArrayOf())).isEmpty()
        assertThat(SavedP2pGroups.ALL_SOURCES_MAC).isEqualTo("ff:ff:ff:ff:ff:ff")
    }

    @Test
    fun p2pNeighbor_picksUsableIpv4OnP2pInterface() {
        val output = """
            192.168.1.1 dev wlan0 lladdr 11:22:33:44:55:66 REACHABLE
            192.168.49.30 dev p2p-wlan0-0 FAILED
            192.168.49.214 dev p2p-wlan0-0 lladdr 5e:aa:bb:cc:dd:ee STALE
        """.trimIndent()
        assertThat(PrimarySinkBeacon.parseP2pNeighbor(output)).isEqualTo("192.168.49.214")
        assertThat(PrimarySinkBeacon.parseP2pNeighbor("192.168.1.1 dev wlan0 lladdr 11:22:33:44:55:66 REACHABLE"))
            .isNull()
        assertThat(PrimarySinkBeacon.parseP2pNeighbor(null)).isNull()
    }

    @Test
    fun rtspBuffer_yieldsMessagesSplitAcrossReads() {
        val m1 = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
        val body = "wfd_video_formats\r\nwfd_client_rtp_ports\r\n"
        val m3 = "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n" +
            "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val bytes = (m1 + m3).toByteArray(StandardCharsets.US_ASCII)
        val buffer = RtspMessageBuffer()

        val firstCut = m1.length + 10
        buffer.append(bytes, firstCut)
        assertThat(buffer.next()).isEqualTo(m1)
        assertThat(buffer.next()).isNull()

        val rest = bytes.copyOfRange(firstCut, bytes.size)
        buffer.append(rest, rest.size - 5)
        assertThat(buffer.next()).isNull()
        val tail = rest.copyOfRange(rest.size - 5, rest.size)
        buffer.append(tail, tail.size)
        assertThat(buffer.next()).isEqualTo(m3)
        assertThat(buffer.next()).isNull()
    }
}
