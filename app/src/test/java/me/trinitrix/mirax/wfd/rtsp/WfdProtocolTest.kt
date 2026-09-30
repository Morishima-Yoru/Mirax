package me.trinitrix.mirax.wfd.rtsp

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.VideoMode
import org.junit.Test
import java.util.Arrays

/**
 * Host-side RTSP / mode encode-decode tests behind the Mirax session.
 *
 * Does not assert frozen, tile, or handle behaviour.
 */
class WfdProtocolTest {

    private val foldPreferred = VideoMode(2176, 1812, 60)
    private val defaults = setOf(
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
        foldPreferred,
    )

    @Test
    fun standardModesAreBitSet_notOrderedList_andCustomIsLiteral() {
        val cea = WfdVideoFormatCodec.ceaMask(
            setOf(
                VideoMode(1280, 720, 60),
                VideoMode(1920, 1080, 30),
                VideoMode(1920, 1080, 60),
            ),
        )
        // Bits 6, 7, 8 → 0x1C0 — order of the set does not matter.
        assertThat(cea).isEqualTo(0x1C0L)
        assertThat(WfdVideoFormatCodec.encodeCeaHex(defaults)).isEqualTo("000001C0")

        val custom = WfdVideoFormatCodec.encodeCustomFormats(setOf(foldPreferred))
        assertThat(custom).isEqualTo("0880 0714 003C")
        assertThat(WfdVideoFormatCodec.parseCustomResolution(custom))
            .isEqualTo(foldPreferred)
    }

    @Test
    fun heightNotMultipleOfSixteen_isLegalCustom() {
        // 1812 % 16 == 4; Microsoft's own 1080 example is similarly non-aligned.
        assertThat(1812 % 16).isNotEqualTo(0)
        assertThat(WfdVideoFormatCodec.encodeCustomFormats(setOf(foldPreferred)))
            .contains("0714")
        assertThat(WfdVideoFormatCodec.parseCustomResolution("0880 0714 003C"))
            .isEqualTo(foldPreferred)
    }

    @Test
    fun wireOrderIsNotFallbackPriority_noLatchAfterPrePlayDrop() {
        val first = WfdVideoFormatCodec.wfdxVideoFormats(defaults, foldPreferred)
        assertThat(first).contains("0880 0714")
        assertThat(first).contains("00000001C0")

        // Same set again — no hidden latch adds extra modes.
        val again = WfdVideoFormatCodec.wfdxVideoFormats(defaults, foldPreferred)
        assertThat(again).isEqualTo(first)

        val standardsOnly = setOf(
            VideoMode(1280, 720, 60),
            VideoMode(1920, 1080, 60),
        )
        val withoutPreferred = WfdVideoFormatCodec.wfdxVideoFormats(standardsOnly, null)
        assertThat(withoutPreferred).doesNotContain("0880 0714")
        assertThat(WfdVideoFormatCodec.encodeCustomFormats(standardsOnly)).isEqualTo("none")
    }

    @Test
    fun bitmapDecodeSelectsAdvertisedStandardMode() {
        val mode = WfdVideoFormatCodec.fromBitmapValue(
            "00 01 04 0080 000000000100 000000000000 000000000000 00 0000 0000 00 00",
        )
        assertThat(mode).isEqualTo(VideoMode(1920, 1080, 60))
    }

    @Test
    fun rtspNegotiatesCustomModeAndReachesPlaying() {
        val caps = WfdCapabilityTable(defaults, foldPreferred, "Desk Fold")
        val session = RtspSinkSession(caps)

        val m1 = session.handle(
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n",
        )
        assertThat(m1).hasSize(2)
        assertThat(m1[0]).contains("Public: org.wfa.wfd1.0")
        assertThat(m1[1]).startsWith("OPTIONS * RTSP/1.0\r\n")

        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("READY")

        val capsResp = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\nContent-Length: 88\r\n\r\n" +
                "wfd_video_formats\r\n" +
                "wfd_audio_codecs\r\n" +
                "wfd_client_rtp_ports\r\n" +
                "microsoft_custom_video_formats\r\n",
        )
        assertThat(capsResp).hasSize(1)
        assertThat(capsResp[0]).contains("microsoft_custom_video_formats: 0880 0714 003C\r\n")
        assertThat(capsResp[0]).contains("wfdx_video_formats:")
        assertThat(capsResp[0]).doesNotContain("intel_friendly_name")
        assertThat(capsResp[0]).doesNotContain("wfd_uibc_capability")
        assertThat(capsResp[0]).contains(
            "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play",
        )

        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 3\r\nContent-Type: text/parameters\r\n\r\n" +
                "microsoft_custom_video_formats: 0880 0714 003C\r\n" +
                "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n" +
                "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n",
        )
        assertThat(session.formatChosen).isTrue()
        assertThat(session.selectedMode).isEqualTo(foldPreferred)

        val triggered = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                "Content-Type: text/parameters\r\n\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        assertThat(triggered).hasSize(2)
        assertThat(triggered[1]).contains("client_port=19000-19001")

        session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: 12345678;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        val afterPlay = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 12345678\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("PLAYING")
        assertThat(afterPlay[0]).contains("wfd_idr_request")
    }

    @Test
    fun uibcCapabilityIsNone() {
        val caps = WfdCapabilityTable(defaults, foldPreferred, "Mirax")
        assertThat(caps.valueFor("wfd_uibc_capability")).isEqualTo("none")
    }

    @Test
    fun touchEnabled_advertisesHidcAndReadsSourcePort() {
        val caps = WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true)
        assertThat(caps.valueFor("wfd_uibc_capability")).isEqualTo(
            "input_category_list=HIDC; generic_cap_list=none; " +
                "hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none",
        )
        val session = RtspSinkSession(caps)
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                "Content-Type: text/parameters\r\nContent-Length: 80\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; port=50123\r\n",
        )
        assertThat(session.uibcPort).isEqualTo(50123)
    }

    @Test
    fun edidPreferredTimingMatchesMode() {
        val edid = WfdEdid.block(foldPreferred)
        assertThat(edid).hasLength(128)
        var sum = 0
        for (b in edid) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        }
        assertThat(sum).isEqualTo(0)
        val hActive = (edid[56].toInt() and 0xFF) or (((edid[58].toInt() shr 4) and 0x0F) shl 8)
        val vActive = (edid[59].toInt() and 0xFF) or (((edid[61].toInt() shr 4) and 0x0F) shl 8)
        assertThat(hActive).isEqualTo(2176)
        assertThat(vActive).isEqualTo(1812)
    }

    @Test
    fun rtpReassemblesPesAcrossPackets() {
        val payload = ByteArray(200) { i -> (i * 3 + 1).toByte() }
        val pes = pesPacket(payload)
        val first = pes.copyOfRange(0, 184)
        val second = pes.copyOfRange(184, pes.size)
        val tsOut = java.io.ByteArrayOutputStream()
        tsOut.write(pat())
        tsOut.write(pmt())
        tsOut.write(MpegTsDepacketizer.tsPacket(0x101, true, 0, first))
        tsOut.write(MpegTsDepacketizer.tsPacket(0x101, false, 1, second))
        val ts = tsOut.toByteArray()
        val demux = MpegTsDepacketizer()
        val rtp = MpegTsDepacketizer.rtpWrap(ts)
        demux.pushRtp(rtp, rtp.size)
        val au = demux.poll()
        assertThat(au).isNotNull()
        assertThat(Arrays.equals(payload, au)).isTrue()
        assertThat(demux.poll()).isNull()
    }

    private fun pesPacket(payload: ByteArray): ByteArray {
        val afterLength = 3 + payload.size
        val pes = ByteArray(6 + afterLength)
        pes[2] = 1
        pes[3] = 0xE0.toByte()
        pes[4] = ((afterLength shr 8) and 0xFF).toByte()
        pes[5] = (afterLength and 0xFF).toByte()
        pes[6] = 0x80.toByte()
        pes[7] = 0x00
        pes[8] = 0x00
        System.arraycopy(payload, 0, pes, 9, payload.size)
        return pes
    }

    private fun pat(): ByteArray {
        val section = byteArrayOf(
            0x00,
            0xB0.toByte(), 0x0D,
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            0x00, 0x01,
            0xE1.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val payload = ByteArray(1 + section.size)
        payload[0] = 0x00
        System.arraycopy(section, 0, payload, 1, section.size)
        return MpegTsDepacketizer.tsPacket(0x000, true, 0, payload)
    }

    private fun pmt(): ByteArray {
        val section = byteArrayOf(
            0x02,
            0xB0.toByte(), 0x12,
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            0xE1.toByte(), 0x01,
            0xF0.toByte(), 0x00,
            0x1B,
            0xE1.toByte(), 0x01,
            0xF0.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val payload = ByteArray(1 + section.size)
        payload[0] = 0x00
        System.arraycopy(section, 0, payload, 1, section.size)
        return MpegTsDepacketizer.tsPacket(0x100, true, 0, payload)
    }
}
