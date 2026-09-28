package com.secondscreen.wfd;

/**
 * Sink capabilities advertised to a Windows Miracast source.
 *
 * <p>The inner panel is 2176×1812. {@code wfdx_video_formats} offers that size in
 * {@code max-hres} and {@code max-vres}, with {@code preferred-display-mode-supported} set.
 * CEA bits stay clear on the first offer so a source is not given a 16:9 mode to prefer.
 * If that session ends before the stream starts, {@link #offerCommonModes} adds 720p60,
 * 1080p30 and 1080p60.
 */
public final class Capabilities {
    public static final int WIDTH = 2176;
    public static final int HEIGHT = 1812;
    public static final int FPS = 60;
    public static final int RTP_PORT = 19000;

    /** Set after a full-panel session ends before PLAY. */
    public static volatile boolean offerCommonModes;

    private Capabilities() {}

    public static String hex4(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("field out of range: " + value);
        }
        return String.format("%04X", value);
    }

    /** Value only, without the parameter name. */
    public static String customVideoFormats() {
        return hex4(WIDTH) + " " + hex4(HEIGHT) + " " + hex4(FPS);
    }

    /**
     * Classic {@code wfd_video_formats}. Preferred mode is 2176×1812. Level {@code 40} is H.264
     * level 5.1. CEA bits are empty until {@link #offerCommonModes} is set.
     */
    public static String wfdVideoFormats() {
        String cea = offerCommonModes ? "000001C0" : "00000000";
        return "00 01 03 40 " + cea + " 00000000 00000000 00 0000 0000 11 "
                + hex4(WIDTH) + " " + hex4(HEIGHT);
    }

    /**
     * {@code wfdx_video_formats} / {@code wfd2_video_formats}. {@code 01} means
     * {@code max-hres} and {@code max-vres} are the preferred picture, not {@code none}.
     */
    public static String wfdxVideoFormats() {
        String cea = offerCommonModes ? "00000001C0" : "0000000000";
        return "0000 01 0003 0040 " + cea + " 0000000000 00000000 00 0000 0000 11 "
                + hex4(WIDTH) + " " + hex4(HEIGHT);
    }

    public static String audioCodecs() {
        return "LPCM 00000003 00";
    }

    public static String clientRtpPorts() {
        return "RTP/AVP/UDP;unicast " + RTP_PORT + " 0 mode=play";
    }

    public static String valueFor(String name) {
        if ("wfd_video_formats".equals(name)) {
            return wfdVideoFormats();
        }
        if ("wfdx_video_formats".equals(name) || "wfd2_video_formats".equals(name)) {
            return wfdxVideoFormats();
        }
        if ("microsoft_custom_video_formats".equals(name)) {
            return customVideoFormats();
        }
        if ("microsoft_video_formats".equals(name)) {
            return "000000000000";
        }
        if ("wfd_audio_codecs".equals(name) || "wfd2_audio_codecs".equals(name)) {
            return audioCodecs();
        }
        if ("wfd_client_rtp_ports".equals(name)) {
            return clientRtpPorts();
        }
        if ("wfd_display_edid".equals(name)) {
            return Edid.parameterValue();
        }
        if ("wfd_connector_type".equals(name)) {
            return "07";
        }
        if ("wfd_coupled_sink".equals(name)
                || "wfd_uibc_capability".equals(name)
                || "wfd_content_protection".equals(name)
                || "wfd_3d_video_formats".equals(name)
                || "wfd_presentation_URL".equals(name)
                || "microsoft_rtcp_capability".equals(name)
                || "microsoft_color_space_conversion".equals(name)
                || "microsoft_multiscreen_projection".equals(name)
                || "microsoft_cursor".equals(name)
                || "wfd2_rotation_capability".equals(name)
                || "wfd2_video_stream_control".equals(name)) {
            return "none";
        }
        if ("wfd_idr_request_capability".equals(name)) {
            return "1";
        }
        if ("microsoft_format_change_capability".equals(name)
                || "microsoft_latency_management_capability".equals(name)
                || "microsoft_diagnostics_capability".equals(name)) {
            return "supported";
        }
        if ("microsoft_max_bitrate".equals(name)) {
            return "40000000";
        }
        if ("microsoft_audio_mute".equals(name)) {
            return "supported";
        }
        if ("intel_friendly_name".equals(name)) {
            return "Z Fold 5";
        }
        if ("intel_sink_manufacturer_name".equals(name)) {
            return "Samsung";
        }
        if ("intel_sink_model_name".equals(name)) {
            return "SM-F946U";
        }
        if ("intel_sink_device_URL".equals(name)) {
            return "none";
        }
        if ("intel_sink_version".equals(name)) {
            return "product_ID=SM-F946U hw_version=1 sw_version=1";
        }
        return "none";
    }

    /** First custom resolution in a SET_PARAMETER value, or null. */
    public static int[] parseCustomResolution(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 0 || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        String first = trimmed.split(",")[0].trim();
        String[] parts = first.split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0], 16);
            int h = Integer.parseInt(parts[1], 16);
            int fps = Integer.parseInt(parts[2], 16);
            if (w <= 0 || h <= 0 || fps <= 0) {
                return null;
            }
            return new int[] {w, h, fps};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
