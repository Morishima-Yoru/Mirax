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

    /** Verifies M3 advertises only enabled Microsoft modes within the static limits. */
    @Test
    fun m3MicrosoftFormatsAdvertiseOnlyEnabledModesWithinLevel51() {
        val modes = defaults + setOf(
            VideoMode(1920, 1280, 30),
            VideoMode(2160, 1440, 60),
            VideoMode(3240, 2160, 24),
            VideoMode(3000, 2000, 60),
            VideoMode(4500, 3000, 24),
        )
        val session = RtspSinkSession(WfdCapabilityTable(modes, foldPreferred, "Mirax"))

        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "microsoft_video_formats\r\n",
        ).single()

        // MS-WFDPE table bits 0, 4, and 17; oversized and unselected modes stay clear.
        assertThat(response).contains("microsoft_video_formats: 000000020011\r\n")
    }

    /** Verifies interactive standard fallbacks cannot displace the Microsoft EDID timing. */
    @Test
    fun interactiveStandardFallbackDoesNotInventCustomModesAheadOfMicrosoftEdid() {
        val microsoft = VideoMode(2160, 1440, 30)
        for (baseline in listOf(VideoMode(640, 480, 60), VideoMode(720, 576, 50))) {
            val session = RtspSinkSession(
                WfdCapabilityTable(setOf(baseline, microsoft), null, "Mirax", touchEnabled = true),
            )
            val response = session.handle(
                "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                    "wfd_video_formats\r\nmicrosoft_video_formats\r\n" +
                    "microsoft_custom_video_formats\r\nwfd_display_edid\r\n",
            ).single()

            assertThat(response).contains("microsoft_custom_video_formats: none\r\n")
            assertThat(response).contains("microsoft_video_formats: 000000000008\r\n")
            val edid = response.lineSequence().first { it.startsWith("wfd_display_edid:") }
                .substringAfter("0001 ").trim().chunked(2).map { it.toInt(16) }
            assertThat(edid[56] or ((edid[58] shr 4) shl 8)).isEqualTo(2160)
            assertThat(edid[59] or ((edid[61] shr 4) shl 8)).isEqualTo(1440)
        }
    }

    /** Verifies an explicit custom VGA refresh keeps its custom-format priority. */
    @Test
    fun interactiveExplicitCustomVgaStillTakesPriority() {
        val custom = VideoMode(640, 480, 30)
        val session = RtspSinkSession(
            WfdCapabilityTable(setOf(custom, VideoMode(2160, 1440, 30)), custom, "Mirax", touchEnabled = true),
        )
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "microsoft_custom_video_formats\r\nmicrosoft_video_formats\r\n",
        ).single()

        assertThat(response).contains("microsoft_custom_video_formats: 0280 01E0 001E\r\n")
        assertThat(response).contains("microsoft_video_formats: 000000000008\r\n")
    }

    /** Verifies high custom Hz remains literal in M3 and survives Windows M4 selection. */
    @Test
    fun custom120And240Hz_areAdvertisedAndSelectedWithoutSnapping() {
        val modes = setOf(VideoMode(1280, 720, 120), VideoMode(1280, 720, 240))
        val session = RtspSinkSession(WfdCapabilityTable(modes, modes.first(), "Mirax"))
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "microsoft_custom_video_formats\r\n",
        ).single()
        assertThat(response).contains("microsoft_custom_video_formats: 0500 02D0 0078, 0500 02D0 00F0\r\n")

        val selections = listOf("0078" to VideoMode(1280, 720, 120), "00F0" to VideoMode(1280, 720, 240))
        for ((index, selection) in selections.withIndex()) {
            val (wire, expected) = selection
            session.handle(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: ${index + 3}\r\n\r\n" +
                    "microsoft_custom_video_formats: 0500 02D0 $wire\r\n",
            )
            assertThat(session.selectedMode).isEqualTo(expected)
            assertThat(session.formatChosen).isTrue()
        }
    }

    /** Verifies high custom Hz does not remove the existing interactive 30 Hz preference. */
    @Test
    fun interactiveHighRefreshOffer_retains30HzEdidPreference() {
        val modes = setOf(VideoMode(1280, 720, 120), VideoMode(1280, 720, 240))
        val session = RtspSinkSession(WfdCapabilityTable(modes, modes.first(), "Mirax", touchEnabled = true))
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "microsoft_custom_video_formats\r\nwfd_display_edid\r\n",
        ).single()
        assertThat(response).contains("microsoft_custom_video_formats: 0500 02D0 0078, 0500 02D0 00F0\r\n")
        val edid = response.lineSequence().first { it.startsWith("wfd_display_edid:") }
            .substringAfter("0001 ").trim().chunked(2).map { it.toInt(16) }
        // 1440 x 772 total timing at 30 Hz rounds to a 3335 x 10 kHz pixel clock.
        assertThat(edid[54] or (edid[55] shl 8)).isEqualTo(3335)
    }

    /** Verifies the Fold custom offer retains its calibrated low-latency alternative. */
    @Test
    fun interactiveFoldOfferRetainsItsLowLatencyCustomRefresh() {
        val session = RtspSinkSession(
            WfdCapabilityTable(setOf(foldPreferred), foldPreferred, "Mirax", touchEnabled = true),
        )
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "microsoft_custom_video_formats\r\nwfd_display_edid\r\n",
        ).single()

        assertThat(response).contains("0880 0714 003C")
        assertThat(response).contains("0880 0714 001E")
        val edid = response.lineSequence().first { it.startsWith("wfd_display_edid:") }
            .substringAfter("0001 ").trim().chunked(2).map { it.toInt(16) }
        assertThat(edid[54] or (edid[55] shl 8)).isEqualTo(13063)
    }

    /** Verifies a valid Microsoft M4 selection outranks standard formats in either order. */
    @Test
    fun m4MicrosoftSelectionWinsOverStandardRegardlessOfParameterOrder() {
        val microsoft = "microsoft_video_formats: 000000000010\r\n"
        val standard = "wfd_video_formats: 00 00 02 40 00000100 00000000 00000000 00 0000 0000 00 none none\r\n"
        val mode = VideoMode(2160, 1440, 60)
        for (body in listOf(microsoft + standard, standard + microsoft, microsoft)) {
            val session = RtspSinkSession(WfdCapabilityTable(defaults + mode, foldPreferred, "Mirax"))

            session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n\r\n$body")

            assertThat(session.selectedMode).isEqualTo(mode)
            assertThat(session.formatChosen).isTrue()
            assertThat(session.state).isEqualTo("CONFIGURED")
        }
    }

    /** Verifies Microsoft modes leave literal custom formats and their EDID timing intact. */
    @Test
    fun m3MicrosoftModesDoNotDisplaceLiteralCustomModesOrNativeEdid() {
        val modes = linkedSetOf(
            VideoMode(2160, 1440, 60),
            VideoMode(3000, 2000, 60),
            VideoMode(4500, 3000, 24),
            foldPreferred,
        )
        val session = RtspSinkSession(WfdCapabilityTable(modes, null, "Mirax"))

        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "wfd_video_formats\r\nmicrosoft_video_formats\r\n" +
                "microsoft_custom_video_formats\r\nwfd_display_edid\r\n",
        ).single()

        assertThat(response).contains("microsoft_custom_video_formats: 0880 0714 003C\r\n")
        assertThat(response).contains("microsoft_video_formats: 000000000010\r\n")
        val hex = response.lineSequence().first { it.startsWith("wfd_display_edid:") }
            .substringAfter("0001 ").trim()
        val edid = hex.chunked(2).map { it.toInt(16) }
        assertThat(edid).hasSize(128)
        assertThat(edid[56] or ((edid[58] shr 4) shl 8)).isEqualTo(2176)
        assertThat(edid[59] or ((edid[61] shr 4) shl 8)).isEqualTo(1812)
        assertThat(edid.sum() and 0xFF).isEqualTo(0)
    }

    /** Verifies a preferred 24 Hz Microsoft mode fits the EDID timing and range descriptor. */
    @Test
    fun m3PreferredMicrosoft24HzModeHasMatchingEdidAndInclusiveTimingRange() {
        val mode = VideoMode(2160, 1440, 24)
        val session = RtspSinkSession(WfdCapabilityTable(defaults + mode, mode, "Mirax"))
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "wfd_display_edid\r\n",
        ).single()
        val hex = response.lineSequence().first { it.startsWith("wfd_display_edid:") }
            .substringAfter("0001 ").trim()
        val edid = hex.chunked(2).map { it.toInt(16) }

        assertThat(edid).hasSize(128)
        assertThat(edid[56] or ((edid[58] shr 4) shl 8)).isEqualTo(2160)
        assertThat(edid[59] or ((edid[61] shr 4) shl 8)).isEqualTo(1440)
        assertThat(edid[54] or (edid[55] shl 8)).isEqualTo(8307)
        assertThat(edid[95]).isAtMost(24)
        assertThat(edid[97]).isAtMost(35)
        assertThat(edid.sum() and 0xFF).isEqualTo(0)
    }

    /** Verifies every supported Microsoft bit round-trips without setting reserved bits. */
    @Test
    fun microsoftTableBitsRoundTripAtRtspBoundaryWithReservedBitsClear() {
        // Independent MS-WFDPE 2.7.1.2.1 wire fixtures; Level 5.1 excludes six entries.
        val cases = listOf(
            "000000000001" to VideoMode(1920, 1280, 30),
            "000000000002" to VideoMode(1920, 1280, 60),
            "000000000004" to VideoMode(1920, 1280, 24),
            "000000000008" to VideoMode(2160, 1440, 30),
            "000000000010" to VideoMode(2160, 1440, 60),
            "000000000020" to VideoMode(2160, 1440, 24),
            "000000000040" to VideoMode(2256, 1504, 30),
            "000000000080" to VideoMode(2256, 1504, 60),
            "000000000100" to VideoMode(2256, 1504, 24),
            "000000000200" to VideoMode(2736, 1824, 30),
            "000000000800" to VideoMode(2736, 1824, 24),
            "000000001000" to VideoMode(3000, 2000, 30),
            "000000004000" to VideoMode(3000, 2000, 24),
            "000000008000" to VideoMode(3240, 2160, 30),
            "000000020000" to VideoMode(3240, 2160, 24),
        )
        for ((hex, mode) in cases) {
            val session = RtspSinkSession(WfdCapabilityTable(setOf(mode), null, "Mirax"))
            val response = session.handle(
                "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                    "microsoft_video_formats\r\n",
            ).single()
            assertThat(response).contains("microsoft_video_formats: $hex\r\n")
            session.handle(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n\r\n" +
                    "microsoft_video_formats: ${hex.lowercase()}\r\n",
            )
            assertThat(session.selectedMode).isEqualTo(mode)
        }
    }

    /** Verifies a connection without Microsoft opt-in retains the all-zero bitmap. */
    @Test
    fun m3WithoutEnabledMicrosoftModesKeepsTheZeroBitmap() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax"))
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n\r\n" +
                "microsoft_video_formats\r\n",
        ).single()
        assertThat(response).contains("microsoft_video_formats: 000000000000\r\n")
    }

    /** Verifies invalid Microsoft selections cannot override a valid standard fallback. */
    @Test
    fun m4MalformedReservedOversizedOrUnofferedMicrosoftSelectionsFallBackToStandard() {
        val rejected = listOf(
            "", "none", "00000000000G", "00000000001", "0000000000001",
            "000000000000", "000000000018", "FFFFFFFFFFFF", "800000000000", "000000200000",
            "000000000400", "000000002000", "000000010000",
            "000000040000", "000000080000", "000000100000",
            "000000000001", // Valid table entry, but not offered for this connection.
        )
        for (value in rejected) {
            val session = RtspSinkSession(
                WfdCapabilityTable(defaults + VideoMode(2160, 1440, 60), foldPreferred, "Mirax"),
            )
            session.handle(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n\r\n" +
                    "microsoft_video_formats: $value\r\n",
            )
            assertThat(session.selectedMode).isNull()
            assertThat(session.formatChosen).isFalse()
            session.handle(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                    "microsoft_video_formats: $value\r\n" +
                    "wfd_video_formats: 00 00 02 40 00000100 00000000 00000000 00 0000 0000 00 none none\r\n",
            )
            assertThat(session.selectedMode).isEqualTo(VideoMode(1920, 1080, 60))
        }
    }

    /** Verifies custom priority is order-independent and does not lock later requests. */
    @Test
    fun m4CustomSelectionWinsInEitherOrderButDoesNotLatchAcrossRequests() {
        val microsoft = "microsoft_video_formats: 000000000010\r\n"
        val custom = "microsoft_custom_video_formats: 0880 0714 003C\r\n"
        val standard = "wfd_video_formats: 00 00 02 40 00000100 00000000 00000000 00 0000 0000 00 none none\r\n"
        for (body in listOf(custom + microsoft + standard, microsoft + standard + custom, standard + custom + microsoft)) {
            val mode = VideoMode(2160, 1440, 60)
            val session = RtspSinkSession(WfdCapabilityTable(defaults + mode, foldPreferred, "Mirax"))
            session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n\r\n$body")
            assertThat(session.selectedMode).isEqualTo(foldPreferred)

            session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n$microsoft")
            assertThat(session.selectedMode).isEqualTo(mode)
            session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 5\r\n\r\n$standard")
            assertThat(session.selectedMode).isEqualTo(VideoMode(1920, 1080, 60))
        }
    }

    @Test
    fun m3OffersOnly48kStereoLpcmThatTheSinkCanPlay() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax"))
        val response = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\n\r\nwfd_audio_codecs\r\n",
        ).single()

        assertThat(response).contains("wfd_audio_codecs: LPCM 00000002 00\r\n")
    }

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
        assertThat(m1[0]).contains("RTSP/1.0 200 OK")
        assertThat(m1[0]).contains("Public: org.wfa.wfd1.0")
        assertThat(m1[1]).startsWith("OPTIONS * RTSP/1.0\r\n")
        assertThat(session.state).isEqualTo("WAIT_M2")

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
        assertThat(session.rtcpServerPort).isEqualTo(5001)
        val afterPlay = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 12345678\r\n\r\n",
        )
        assertThat(session.state).isEqualTo("PLAYING")
        assertThat(afterPlay[0]).contains("wfd_idr_request")

        val startupIdrCseq = extractCSeq(afterPlay[0])
        assertThat(session.requestIdr()).isEmpty()
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $startupIdrCseq\r\n\r\n")
        val refresh = session.requestIdr()
        assertThat(refresh).contains("wfd_idr_request")
        assertThat(refresh).contains("SET_PARAMETER")
        assertThat(session.requestIdr()).isEmpty()
    }

    @Test
    fun uibcCapabilityIsNone() {
        val caps = WfdCapabilityTable(defaults, foldPreferred, "Mirax")
        assertThat(caps.valueFor("wfd_uibc_capability")).isEqualTo("none")
    }

    @Test
    fun microsoftMaxBitrate_usesConfiguredCap() {
        val caps = WfdCapabilityTable(
            defaults,
            foldPreferred,
            "Mirax",
            maxBitrateBps = 12_000_000L,
        )
        assertThat(caps.valueFor("microsoft_max_bitrate")).isEqualTo("12000000")
    }

    @Test
    fun touchEnabled_m3InjectsMaxBitrateWhenOmitted() {
        val caps = WfdCapabilityTable(
            defaults,
            foldPreferred,
            "Mirax",
            touchEnabled = true,
            maxBitrateBps = 12_000_000L,
        )
        assertThat(caps.interactiveHints).isTrue()
        assertThat(caps.valueFor("microsoft_latency_management_capability"))
            .isEqualTo("none")
        assertThat(caps.valueFor("microsoft_rtcp_capability")).isEqualTo("supported")
        val session = RtspSinkSession(caps)
        val replies = session.handle(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                "CSeq: 2\r\nContent-Type: text/parameters\r\nContent-Length: 40\r\n\r\n" +
                "wfd_video_formats\r\nwfd_audio_codecs\r\n",
        )
        val body = replies.joinToString("\n")
        assertThat(body).contains("microsoft_max_bitrate: 12000000")
        assertThat(body).contains("microsoft_latency_management_capability: none")
        assertThat(body).contains("microsoft_custom_video_formats:")
    }

    @Test
    fun setupResponse_parsesServerRtcpPort() {
        assertThat(
            RtspSinkSession.parseServerRtcpPort(
                "RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001",
            ),
        ).isEqualTo(5001)
        assertThat(
            RtspSinkSession.parseServerRtcpPort(
                "RTP/AVP/UDP;unicast;client_port=19000;server_port=5000",
            ),
        ).isEqualTo(-1)
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
        assertThat(session.uibcHidType).isEqualTo(3)
    }

    @Test
    fun uibcCombinedTouchSelection_usesSingleTouchForFingerCheckpoint() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=50010\r\n",
        )
        assertThat(session.uibcHidType).isEqualTo(2)
    }

    @Test
    fun uibcSingleTouchSelection_usesHidTypeTwo_andDisableKeepsThePort() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=SingleTouch/USB; port=50010\r\n",
        )
        assertThat(session.uibcPort).isEqualTo(50010)
        assertThat(session.uibcHidType).isEqualTo(2)
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 5\r\n\r\n" +
                "wfd_uibc_setting: disable\r\n",
        )
        assertThat(session.uibcPort).isEqualTo(50010)
        assertThat(session.uibcEnabled).isFalse()
    }

    @Test
    fun disableThenEnablePreservesPort() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; generic_cap_list=none; " +
                "hidc_cap_list=SingleTouch/USB; port=50123\r\n",
        )
        session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 5\r\n\r\nwfd_uibc_setting: disable\r\n")
        session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 6\r\n\r\nwfd_uibc_setting: enable\r\n")
        assertThat(session.uibcPort).isEqualTo(50123)
        assertThat(session.uibcEnabled).isTrue()
    }

    @Test
    fun m4CapabilityWithEmbeddedSetting_enablesImmediately() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50123\r\n" +
                "wfd_uibc_setting: enable\r\n",
        )
        assertThat(session.uibcPort).isEqualTo(50123)
        assertThat(session.uibcHidType).isEqualTo(3)
        assertThat(session.uibcEnabled).isTrue()
    }

    @Test
    fun enableWithoutRepeatedCapability_isIdempotent() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; port=50123\r\n",
        )
        session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 5\r\n\r\nwfd_uibc_setting: enable\r\n",
        )
        assertThat(session.uibcPort).isEqualTo(50123)
        assertThat(session.uibcEnabled).isTrue()
    }

    @Test
    fun sinkM15AfterPlayAndSourcePortDoesNotRequireUibcSocketToBeConnected() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")
        val setupOut = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50123\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        val setupCseq = extractCSeq(setupOut.last())
        val playOut = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $setupCseq\r\nSession: 1;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        val playCseq = extractCSeq(playOut.last())
        val idrOut = session.handle("RTSP/1.0 200 OK\r\nCSeq: $playCseq\r\nSession: 1\r\n\r\n")
        val idrCseq = extractCSeq(idrOut.last())
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $idrCseq\r\n\r\n")
        assertThat(session.state).isEqualTo("PLAYING")
        val enable = session.requestUibcEnable()
        assertThat(enable).contains("wfd_uibc_setting: enable")
        val enableCseq = extractCSeq(enable)
        assertThat(session.uibcEnabled).isFalse()
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $enableCseq\r\n\r\n")
        assertThat(session.uibcEnabled).isTrue()
        assertThat(session.state).isEqualTo("PLAYING")
        assertThat(session.requestUibcEnable()).isEmpty()
    }

    @Test
    fun sinkM15EnableRejected_keepsPlayingWithoutTouch() {
        val session = RtspSinkSession(WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true))
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")
        val setupOut = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50123\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        val setupCseq = extractCSeq(setupOut.last())
        val playOut = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $setupCseq\r\nSession: 1;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        val playCseq = extractCSeq(playOut.last())
        val idrOut = session.handle("RTSP/1.0 200 OK\r\nCSeq: $playCseq\r\nSession: 1\r\n\r\n")
        val idrCseq = extractCSeq(idrOut.last())
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $idrCseq\r\n\r\n")
        val enable = session.requestUibcEnable()
        val enableCseq = extractCSeq(enable)
        session.handle("RTSP/1.0 403 Forbidden\r\nCSeq: $enableCseq\r\n\r\n")
        assertThat(session.uibcEnabled).isFalse()
        assertThat(session.state).isEqualTo("PLAYING")
    }

    @Test
    fun lostIdrReply_exchangeExpiresToError() {
        var now = 1_000L
        val session = RtspSinkSession(
            WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true),
            clockMs = { now },
        )
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")
        val setupOut = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50123\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        val setupCseq = extractCSeq(setupOut.last())
        val playOut = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $setupCseq\r\nSession: 1;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        val playCseq = extractCSeq(playOut.last())
        val idrOut = session.handle("RTSP/1.0 200 OK\r\nCSeq: $playCseq\r\nSession: 1\r\n\r\n")
        val idrCseq = extractCSeq(idrOut.last())
        assertThat(session.state).isEqualTo("PLAYING")
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $idrCseq\r\n\r\n")
        session.requestIdr()
        assertThat(session.requestUibcEnable()).isEmpty()
        now += 5_000L
        assertThat(session.requestUibcEnable()).isEmpty()
        assertThat(session.state).isEqualTo("ERROR")
    }

    @Test
    fun lostM15Reply_expiresAndFailsExchange() {
        var now = 1_000L
        val session = RtspSinkSession(
            WfdCapabilityTable(defaults, foldPreferred, "Mirax", touchEnabled = true),
            clockMs = { now },
        )
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")
        val setupOut = session.handle(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nContent-Type: text/parameters\r\n\r\n" +
                "wfd_uibc_capability: input_category_list=HIDC; hidc_cap_list=MultiTouch/USB; port=50123\r\n" +
                "wfd_trigger_method: SETUP\r\n",
        )
        val setupCseq = extractCSeq(setupOut.last())
        val playOut = session.handle(
            "RTSP/1.0 200 OK\r\nCSeq: $setupCseq\r\nSession: 1;timeout=30\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n",
        )
        val playCseq = extractCSeq(playOut.last())
        val idrOut = session.handle("RTSP/1.0 200 OK\r\nCSeq: $playCseq\r\nSession: 1\r\n\r\n")
        val idrCseq = extractCSeq(idrOut.last())
        session.handle("RTSP/1.0 200 OK\r\nCSeq: $idrCseq\r\n\r\n")
        assertThat(session.requestUibcEnable()).isNotEmpty()
        now += 5_000L
        assertThat(session.checkExchangeTimeout()).isTrue()
        assertThat(session.state).isEqualTo("ERROR")
    }

    @Test
    fun lateMatchingResponse_isAbortedAndStateBecomesError() {
        var now = 1_000L
        val session = RtspSinkSession(
            WfdCapabilityTable(defaults, foldPreferred, "Mirax"),
            clockMs = { now },
        )
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        now += 5_000L
        val out = session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")
        assertThat(out).isEmpty()
        assertThat(session.state).isEqualTo("ERROR")
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

    @Test
    fun rtpDeliversLpcmAlongsideVideo() {
        val wireFrame = byteArrayOf(0x12, 0x34, 0xFE.toByte(), 0xDC.toByte())
        val pcm = ByteArray(1920) { i -> wireFrame[i % wireFrame.size] }
        val audioPes = lpcmPes(pcm, stuffing = true)
        val ts = java.io.ByteArrayOutputStream().apply {
            write(pat())
            write(pmt(withLpcm = true))
            for (start in audioPes.indices step 184) {
                write(MpegTsDepacketizer.tsPacket(
                    0x102, start == 0, start / 184,
                    audioPes.copyOfRange(start, minOf(start + 184, audioPes.size)),
                ))
                if (start == 0) {
                    write(MpegTsDepacketizer.tsPacket(0x101, true, 0, pesPacket(byteArrayOf(0x11, 0x22))))
                }
            }
        }.toByteArray()
        val demux = MpegTsDepacketizer()
        val rtp = MpegTsDepacketizer.rtpWrap(ts)

        demux.pushRtp(rtp, rtp.size)

        val expectedFrame = byteArrayOf(0x34, 0x12, 0xDC.toByte(), 0xFE.toByte())
        val expectedPcm = ByteArray(1920) { i -> expectedFrame[i % expectedFrame.size] }
        assertThat(Arrays.equals(expectedPcm, demux.pollAudio())).isTrue()
        assertThat(Arrays.equals(byteArrayOf(0x11, 0x22), demux.poll())).isTrue()
        assertThat(demux.pollAudio()).isNull()
    }

    @Test
    fun missingAudioPacketsDoNotTurnFragmentsFromDifferentPesIntoSound() {
        val demux = MpegTsDepacketizer()
        fun deliver(ts: ByteArray) {
            val rtp = MpegTsDepacketizer.rtpWrap(ts)
            demux.pushRtp(rtp, rtp.size)
        }

        deliver(pat() + pmt(withLpcm = true))
        val firstPes = lpcmPes(ByteArray(1920) { 0x11 })
        for (start in firstPes.indices step 184) {
            if (start == 184) continue // One audio TS packet was lost in transit.
            deliver(MpegTsDepacketizer.tsPacket(
                0x102, start == 0, start / 184,
                firstPes.copyOfRange(start, minOf(start + 184, firstPes.size)),
            ))
        }
        // The following PES start was lost too; its continuation must not fill the old PES.
        deliver(MpegTsDepacketizer.tsPacket(0x102, false, 12, ByteArray(184) { 0x22 }))
        assertThat(demux.pollAudio()).isNull()

        val recoveredPes = lpcmPes(ByteArray(1920) { 0x33 })
        for (start in recoveredPes.indices step 184) {
            deliver(MpegTsDepacketizer.tsPacket(
                0x102, start == 0, 13 + start / 184,
                recoveredPes.copyOfRange(start, minOf(start + 184, recoveredPes.size)),
            ))
        }
        deliver(MpegTsDepacketizer.tsPacket(0x101, true, 0, pesPacket(byteArrayOf(0x11))))

        assertThat(Arrays.equals(ByteArray(1920) { 0x33 }, demux.pollAudio())).isTrue()
        assertThat(Arrays.equals(byteArrayOf(0x11), demux.poll())).isTrue()
    }

    /**
     * Extract the CSeq from a generated RTSP request.
     *
     * @param message generated RTSP request
     * @return decimal CSeq value, or fail the test if none is present
     */
    private fun extractCSeq(message: String): String =
        Regex("CSeq: (\\d+)").find(message)?.groupValues?.get(1) ?: error("missing CSeq in $message")

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

    /**
     * Builds the audio PES shared by video/audio and packet-loss regression fixtures.
     *
     * @param pcm big-endian stereo samples carried over WFD.
     * @param stuffing whether the PES optional header has two stuffing bytes.
     * @return a private-stream LPCM PES.
     */
    private fun lpcmPes(pcm: ByteArray, stuffing: Boolean = false): ByteArray {
        val payload = byteArrayOf(0xA0.toByte(), 0x06, 0x00, 0x11) + pcm
        val pes = pesPacket(payload)
        val result = if (stuffing) {
            pes.copyOfRange(0, 9) + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) +
                pes.copyOfRange(9, pes.size)
        } else {
            pes
        }
        result[3] = 0xBD.toByte()
        result[4] = ((result.size - 6) shr 8).toByte()
        result[5] = (result.size - 6).toByte()
        result[8] = if (stuffing) 2 else 0
        return result
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

    private fun pmt(withLpcm: Boolean = false): ByteArray {
        val streams = byteArrayOf(
            0x1B, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
        ) + if (withLpcm) {
            byteArrayOf(0x83.toByte(), 0xE1.toByte(), 0x02, 0xF0.toByte(), 0x00)
        } else {
            byteArrayOf()
        }
        val section = byteArrayOf(
            0x02,
            0xB0.toByte(), (0x0D + streams.size).toByte(),
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            0xE1.toByte(), 0x01,
            0xF0.toByte(), 0x00,
        ) + streams + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val payload = ByteArray(1 + section.size)
        payload[0] = 0x00
        System.arraycopy(section, 0, payload, 1, section.size)
        return MpegTsDepacketizer.tsPacket(0x100, true, 0, payload)
    }
}
