package com.secondscreen.wfd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Host-side checks for capability encoding, RTSP, and RTP/H.264 reassembly. */
public final class ProtocolTests {
    private static int failures = 0;

    public static void main(String[] args) {
        sixByFiveModeIsThePreferredOffer();
        customFormatParsesBackToPanelSize();
        commonModesAreAdvertisedForFallback();
        windowsBitmapSelects1080p();
        level51CoversSixtyHertz();
        edidPreferredTimingIsPanel();
        rtspNegotiatesCustomModeAndReachesPlaying();
        rtpReassemblesPesAcrossPackets();
        spsReportsCroppedPanelSize();
        if (failures > 0) {
            throw new AssertionError(failures + " test(s) failed");
        }
        System.out.println("ProtocolTests OK");
    }

    private static void sixByFiveModeIsThePreferredOffer() {
        check(Capabilities.WIDTH == 2176 && Capabilities.HEIGHT == 1812, "advertised picture is the panel");
        check("0880 0714 003C".equals(Capabilities.customVideoFormats()),
                "custom formats value, got " + Capabilities.customVideoFormats());
        Capabilities.offerCommonModes = false;
        String wfdx = Capabilities.wfdxVideoFormats();
        check(wfdx.startsWith("0000 01 0003 0040 "), "preferred mode and level 5.1: " + wfdx);
        check(wfdx.indexOf("0880 0714") >= 0, "wfdx max-hres/max-vres: " + wfdx);
        check(wfdx.indexOf("00000001C0") < 0, "first offer must not include 1080p: " + wfdx);
    }

    private static void commonModesAreAdvertisedForFallback() {
        Capabilities.offerCommonModes = true;
        try {
            String wfd2 = Capabilities.wfdxVideoFormats();
            check(wfd2.indexOf("00000001C0") >= 0, "wfd2 CEA fallback: " + wfd2);
            int[] best = VideoModes.fromBitmapValue(wfd2);
            check(best != null && best[0] == 1920 && best[1] == 1080 && best[2] == 60,
                    "highest advertised fallback should be 1080p60");
        } finally {
            Capabilities.offerCommonModes = false;
        }
    }

    private static void windowsBitmapSelects1080p() {
        int[] mode = VideoModes.fromBitmapValue(
                "00 01 04 0080 000000000100 000000000000 000000000000 00 0000 0000 00 00");
        check(mode != null && mode[0] == 1920 && mode[1] == 1080 && mode[2] == 60,
                "windows M4 bitmap");
        RtspSession session = new RtspSession();
        session.handle("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n");
        session.handle("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n");
        session.handle("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\nContent-Type: text/parameters\r\n\r\n"
                + "wfd2_video_formats: 00 01 04 0080 000000000100 000000000000 000000000000 00 0000 0000 00 00\r\n");
        check(session.width() == 1920 && session.height() == 1080 && session.fps() == 60,
                "fallback negotiation " + session.width() + "x" + session.height());
    }

    private static void customFormatParsesBackToPanelSize() {
        int[] mode = Capabilities.parseCustomResolution("0880 0714 003C");
        check(mode != null && mode[0] == 2176 && mode[1] == 1812 && mode[2] == 60,
                "parse custom resolution");
        int[] first = Capabilities.parseCustomResolution("0880 0714 003C, 0880 0714 001E");
        check(first != null && first[2] == 60, "first listed refresh should win");
    }

    private static void level51CoversSixtyHertz() {
        int mbW = (Capabilities.WIDTH + 15) / 16;
        int mbH = (Capabilities.HEIGHT + 15) / 16;
        int macroblocksPerSecond = mbW * mbH * 60;
        check(macroblocksPerSecond <= 983040,
                "2176x1812@60 must fit H.264 level 5.1, mb/s=" + macroblocksPerSecond);
        check(mbW * mbH <= 36864, "frame size must fit level 5.1");
    }

    private static void edidPreferredTimingIsPanel() {
        byte[] edid = Edid.block();
        check(edid.length == 128, "edid length");
        int sum = 0;
        for (int i = 0; i < edid.length; i++) {
            sum = (sum + (edid[i] & 0xFF)) & 0xFF;
        }
        check(sum == 0, "edid checksum");
        int hActive = (edid[56] & 0xFF) | (((edid[58] >> 4) & 0x0F) << 8);
        int vActive = (edid[59] & 0xFF) | (((edid[61] >> 4) & 0x0F) << 8);
        int hBlank = (edid[57] & 0xFF) | ((edid[58] & 0x0F) << 8);
        int vBlank = (edid[60] & 0xFF) | ((edid[61] & 0x0F) << 8);
        int clock = (edid[54] & 0xFF) | ((edid[55] & 0xFF) << 8);
        check(hActive == 2176, "edid hActive " + hActive);
        check(vActive == 1812, "edid vActive " + vActive);
        double refresh = (clock * 10000.0) / ((hActive + hBlank) * (vActive + vBlank));
        check(refresh > 59.5 && refresh < 60.5, "edid refresh " + refresh);
        String param = Edid.parameterValue();
        check(param.startsWith("0001 ") && param.length() == 5 + 256, "edid parameter length " + param.length());
    }

    private static void rtspNegotiatesCustomModeAndReachesPlaying() {
        RtspSession session = new RtspSession();
        List<String> m1 = session.handle(
                "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n");
        check(m1.size() == 2, "M1 should answer and send M2");
        check(m1.get(0).contains("Public: org.wfa.wfd1.0"), "M1 public");
        check(m1.get(1).startsWith("OPTIONS * RTSP/1.0\r\n"), "M2 options");

        String m2Response = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n";
        session.handle(m2Response);
        check("READY".equals(session.state()), "state after M2 " + session.state());

        String m3 = "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
                + "CSeq: 2\r\nContent-Type: text/parameters\r\nContent-Length: 88\r\n\r\n"
                + "wfd_video_formats\r\n"
                + "wfd_audio_codecs\r\n"
                + "wfd_client_rtp_ports\r\n"
                + "microsoft_custom_video_formats\r\n";
        List<String> caps = session.handle(m3);
        check(caps.size() == 1, "one M3 response");
        String body = caps.get(0);
        check(body.contains("microsoft_custom_video_formats: 0880 0714 003C\r\n"),
                "M3 custom format missing:\n" + body);
        check(body.contains("wfdx_video_formats: 0000 01 0003 0040"),
                "M3 should add wfdx level 5.1:\n" + body);
        check(body.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play"),
                "rtp ports");

        String m4 = "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
                + "CSeq: 3\r\nContent-Type: text/parameters\r\n\r\n"
                + "microsoft_custom_video_formats: 0880 0714 003C\r\n"
                + "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
                + "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n";
        session.handle(m4);
        check(session.formatChosen(), "format chosen");
        check(session.width() == 2176 && session.height() == 1812 && session.fps() == 60,
                "negotiated " + session.width() + "x" + session.height() + "@" + session.fps());
        check("rtsp://192.168.49.1/wfd1.0/streamid=0".equals(session.presentationUrl()),
                "presentation url " + session.presentationUrl());

        List<String> triggered = session.handle(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\nContent-Type: text/parameters\r\n\r\n"
                        + "wfd_trigger_method: SETUP\r\n");
        check(triggered.size() == 2, "200 then SETUP");
        check(triggered.get(1).startsWith("SETUP rtsp://192.168.49.1/wfd1.0/streamid=0 RTSP/1.0\r\n"),
                "SETUP url:\n" + triggered.get(1));
        check(triggered.get(1).contains("client_port=19000-19001"), "client port");

        List<String> afterSetup = session.handle(
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: 12345678;timeout=30\r\n"
                        + "Transport: RTP/AVP/UDP;unicast;client_port=19000-19001;server_port=5000-5001\r\n\r\n");
        check(afterSetup.size() == 1 && afterSetup.get(0).startsWith("PLAY "), "PLAY after SETUP:\n" + afterSetup);
        check(afterSetup.get(0).contains("Session: 12345678\r\n"), "session id echoed");

        List<String> afterPlay = session.handle("RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 12345678\r\n\r\n");
        check("PLAYING".equals(session.state()), "playing " + session.state());
        check(afterPlay.size() == 1 && afterPlay.get(0).contains("wfd_idr_request"), "idr request");
    }

    private static void rtpReassemblesPesAcrossPackets() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 3 + 1);
        }
        byte[] pes = pesPacket(payload);
        byte[] first = new byte[184];
        byte[] second = new byte[pes.length - 184];
        System.arraycopy(pes, 0, first, 0, 184);
        System.arraycopy(pes, 184, second, 0, second.length);

        ByteArrayOutputStream ts = new ByteArrayOutputStream();
        try {
            ts.write(pat());
            ts.write(pmt());
            ts.write(MpegTsDepacketizer.tsPacket(0x101, true, 0, first));
            ts.write(MpegTsDepacketizer.tsPacket(0x101, false, 1, second));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        MpegTsDepacketizer demux = new MpegTsDepacketizer();
        byte[] rtp = MpegTsDepacketizer.rtpWrap(ts.toByteArray());
        demux.pushRtp(rtp, rtp.length);
        byte[] au = demux.poll();
        check(au != null, "access unit missing");
        check(au != null && Arrays.equals(payload, au),
                "reassembled length " + (au == null ? -1 : au.length));
        check(demux.poll() == null, "only one access unit");
    }

    private static void spsReportsCroppedPanelSize() {
        byte[] sps = highProfileSps2176x1812();
        byte[] annex = new byte[4 + sps.length];
        annex[2] = 0;
        annex[3] = 1;
        System.arraycopy(sps, 0, annex, 4, sps.length);
        SpsParser.Size size = SpsParser.parseAnnexB(annex);
        check(size != null, "sps parse");
        check(size != null && size.width == 2176 && size.height == 1812,
                "sps size " + (size == null ? "null" : size.width + "x" + size.height));
    }

    private static byte[] pesPacket(byte[] payload) {
        int afterLength = 3 + payload.length;
        byte[] pes = new byte[6 + afterLength];
        pes[2] = 1;
        pes[3] = (byte) 0xE0;
        pes[4] = (byte) ((afterLength >> 8) & 0xFF);
        pes[5] = (byte) (afterLength & 0xFF);
        pes[6] = (byte) 0x80;
        pes[7] = 0x00;
        pes[8] = 0x00;
        System.arraycopy(payload, 0, pes, 9, payload.length);
        return pes;
    }

    private static byte[] pat() {
        byte[] section = new byte[] {
                0x00,
                (byte) 0xB0, 0x0D,
                0x00, 0x01,
                (byte) 0xC1,
                0x00, 0x00,
                0x00, 0x01,
                (byte) 0xE1, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] payload = new byte[1 + section.length];
        payload[0] = 0x00;
        System.arraycopy(section, 0, payload, 1, section.length);
        return MpegTsDepacketizer.tsPacket(0x000, true, 0, payload);
    }

    private static byte[] pmt() {
        byte[] section = new byte[] {
                0x02,
                (byte) 0xB0, 0x12,
                0x00, 0x01,
                (byte) 0xC1,
                0x00, 0x00,
                (byte) 0xE1, 0x01,
                (byte) 0xF0, 0x00,
                0x1B,
                (byte) 0xE1, 0x01,
                (byte) 0xF0, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] payload = new byte[1 + section.length];
        payload[0] = 0x00;
        System.arraycopy(section, 0, payload, 1, section.length);
        return MpegTsDepacketizer.tsPacket(0x100, true, 0, payload);
    }

    /** High profile, 4:2:0, coded 2176x1824, cropped to 2176x1812. */
    private static byte[] highProfileSps2176x1812() {
        BitWriter w = new BitWriter();
        w.u(8, 0x67);
        w.u(8, 100);
        w.u(8, 0x00);
        w.u(8, 51);
        w.ue(0);
        w.ue(1);
        w.ue(0);
        w.ue(0);
        w.u(1, 0);
        w.u(1, 0);
        w.ue(0);
        w.ue(0);
        w.ue(0);
        w.ue(1);
        w.u(1, 0);
        w.ue(135);
        w.ue(113);
        w.u(1, 1);
        w.u(1, 1);
        w.u(1, 1);
        w.ue(0);
        w.ue(0);
        w.ue(0);
        w.ue(6);
        w.u(1, 0);
        w.trailing();
        return w.toByteArray();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current = 0;
        private int bits = 0;

        void u(int n, int value) {
            for (int i = n - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                bits++;
                if (bits == 8) {
                    out.write(current & 0xFF);
                    current = 0;
                    bits = 0;
                }
            }
        }

        void ue(int value) {
            int code = value + 1;
            int width = 32 - Integer.numberOfLeadingZeros(code);
            u(width - 1, 0);
            u(width, code);
        }

        void trailing() {
            u(1, 1);
            if (bits != 0) {
                u(8 - bits, 0);
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    static {
        // Keep the ASCII import used by request bodies obvious to readers of the test.
        StandardCharsets.US_ASCII.name();
    }
}
