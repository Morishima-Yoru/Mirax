package com.secondscreen.wfd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Wi-Fi Display RTSP sink session, M1 through PLAY, including Microsoft custom video formats. */
public final class RtspSession {
    private int localCseq = 1;
    private int outstandingCseq = -1;
    private String outstandingMethod = "";
    private String presentationUrl = "";
    private String sessionId = "";
    private String state = "INIT";
    private int width = Capabilities.WIDTH;
    private int height = Capabilities.HEIGHT;
    private int fps = Capabilities.FPS;
    private boolean formatChosen = false;
    private boolean customSelected = false;
    private int rtpPort = Capabilities.RTP_PORT;

    public String state() {
        return state;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int fps() {
        return fps;
    }

    public boolean formatChosen() {
        return formatChosen;
    }

    public int rtpPort() {
        return rtpPort;
    }

    public String sessionId() {
        return sessionId;
    }

    public String presentationUrl() {
        return presentationUrl;
    }

    /** Messages to write, in order, after receiving one complete RTSP message. */
    public List<String> handle(String message) {
        List<String> out = new ArrayList<String>();
        if (message == null) {
            return out;
        }
        String normalized = message.replace("\r\n", "\n");
        int split = normalized.indexOf("\n\n");
        String head = split >= 0 ? normalized.substring(0, split) : normalized;
        String body = split >= 0 ? normalized.substring(split + 2) : "";
        String[] lines = head.split("\n");
        if (lines.length == 0 || lines[0].length() == 0) {
            return out;
        }
        String start = lines[0].trim();
        int cseq = headerInt(lines, "CSeq");
        if (start.startsWith("RTSP/1.0")) {
            handleResponse(start, lines, out);
            return out;
        }
        int sp1 = start.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : start.indexOf(' ', sp1 + 1);
        if (sp1 < 0 || sp2 < 0) {
            return out;
        }
        String method = start.substring(0, sp1);
        if ("OPTIONS".equals(method)) {
            out.add(response(cseq, "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n", ""));
            out.add(request("OPTIONS", "*", "Require: org.wfa.wfd1.0\r\n", ""));
            state = "WAIT_M2";
            return out;
        }
        if ("GET_PARAMETER".equals(method)) {
            out.add(response(cseq, "Content-Type: text/parameters\r\n", getParameterBody(body)));
            if ("INIT".equals(state) || "WAIT_M2".equals(state)) {
                state = "CAPABILITIES";
            }
            return out;
        }
        if ("SET_PARAMETER".equals(method)) {
            String trigger = applyParameters(body);
            out.add(response(cseq, "", ""));
            if ("SETUP".equals(trigger)) {
                String url = presentationUrl.length() == 0 ? "rtsp://localhost/wfd1.0/streamid=0" : presentationUrl;
                String transport = "Transport: RTP/AVP/UDP;unicast;client_port="
                        + rtpPort + "-" + (rtpPort + 1) + "\r\n";
                out.add(request("SETUP", url, transport, ""));
                state = "SETUP";
            } else if ("PLAY".equals(trigger)) {
                out.add(playRequest());
            } else if ("TEARDOWN".equals(trigger)) {
                out.add(teardownRequest());
                state = "TEARDOWN";
            } else if ("PAUSE".equals(trigger)) {
                String url = presentationUrl.length() == 0 ? "rtsp://localhost/wfd1.0/streamid=0" : presentationUrl;
                String extra = sessionId.length() == 0 ? "" : "Session: " + sessionId + "\r\n";
                out.add(request("PAUSE", url, extra, ""));
                state = "PAUSED";
            } else if (!"PLAYING".equals(state) && formatChosen) {
                state = "CONFIGURED";
            }
            return out;
        }
        if ("TEARDOWN".equals(method)) {
            out.add(response(cseq, "", ""));
            state = "TEARDOWN";
            return out;
        }
        out.add(response(cseq, "", ""));
        return out;
    }

    private void handleResponse(String start, String[] lines, List<String> out) {
        int code = statusCode(start);
        int cseq = headerInt(lines, "CSeq");
        if (cseq != outstandingCseq) {
            return;
        }
        outstandingCseq = -1;
        String method = outstandingMethod;
        outstandingMethod = "";
        if (code < 200 || code >= 300) {
            state = "ERROR";
            return;
        }
        if ("OPTIONS".equals(method)) {
            state = "READY";
            return;
        }
        if ("SETUP".equals(method)) {
            sessionId = headerToken(lines, "Session");
            state = "READY_TO_PLAY";
            out.add(playRequest());
            return;
        }
        if ("PLAY".equals(method)) {
            state = "PLAYING";
            out.add(idrRequest());
            return;
        }
        if ("TEARDOWN".equals(method)) {
            state = "TEARDOWN";
        }
    }

    private String playRequest() {
        String url = presentationUrl.length() == 0 ? "rtsp://localhost/wfd1.0/streamid=0" : presentationUrl;
        String extra = sessionId.length() == 0 ? "" : "Session: " + sessionId + "\r\n";
        return request("PLAY", url, extra, "");
    }

    private String teardownRequest() {
        String url = presentationUrl.length() == 0 ? "rtsp://localhost/wfd1.0/streamid=0" : presentationUrl;
        String extra = sessionId.length() == 0 ? "" : "Session: " + sessionId + "\r\n";
        return request("TEARDOWN", url, extra, "");
    }

    /** Ask the source for a fresh keyframe. Empty when a request is already in flight. */
    public String requestIdr() {
        if (!"PLAYING".equals(state) || outstandingCseq != -1) {
            return "";
        }
        return idrRequest();
    }

    private String idrRequest() {
        String url = presentationUrl.length() == 0 ? "rtsp://localhost/wfd1.0" : presentationUrl;
        return request("SET_PARAMETER", url, "Content-Type: text/parameters\r\n", "wfd_idr_request\r\n");
    }

    private String getParameterBody(String body) {
        String[] lines = body.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean askedVideo = false;
        boolean askedCustom = false;
        boolean askedWfdx = false;
        boolean askedWfd2 = false;
        for (int i = 0; i < lines.length; i++) {
            String name = parameterName(lines[i]);
            if (name.length() == 0) {
                continue;
            }
            if ("wfd_video_formats".equals(name)) {
                askedVideo = true;
            } else if ("wfdx_video_formats".equals(name)) {
                askedWfdx = true;
            } else if ("wfd2_video_formats".equals(name)) {
                askedWfd2 = true;
            } else if ("microsoft_custom_video_formats".equals(name)) {
                askedCustom = true;
            }
            appendParam(sb, name, Capabilities.valueFor(name));
        }
        if (askedVideo && !askedWfdx && !askedWfd2) {
            appendParam(sb, "wfdx_video_formats", Capabilities.wfdxVideoFormats());
        }
        if ((askedVideo || askedWfdx) && !askedCustom) {
            appendParam(sb, "microsoft_custom_video_formats", Capabilities.customVideoFormats());
        }
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        sb.append(name).append(": ").append(value).append("\r\n");
    }

    private String applyParameters(String body) {
        String trigger = "";
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            int colon = line.indexOf(':');
            String name = colon < 0 ? line : line.substring(0, colon).trim();
            String value = colon < 0 ? "" : line.substring(colon + 1).trim();
            if ("microsoft_custom_video_formats".equals(name)) {
                int[] mode = Capabilities.parseCustomResolution(value);
                if (mode != null) {
                    width = mode[0];
                    height = mode[1];
                    fps = mode[2];
                    formatChosen = true;
                    customSelected = true;
                }
            } else if ("wfd_video_formats".equals(name)
                    || "wfd2_video_formats".equals(name)
                    || "wfdx_video_formats".equals(name)) {
                if (!customSelected) {
                    int[] mode = VideoModes.fromBitmapValue(value);
                    if (mode != null) {
                        width = mode[0];
                        height = mode[1];
                        fps = mode[2];
                        formatChosen = true;
                    }
                }
            } else if ("wfd_presentation_URL".equals(name)) {
                String[] tokens = value.split("\\s+");
                if (tokens.length > 0 && tokens[0].startsWith("rtsp://")) {
                    presentationUrl = tokens[0];
                }
            } else if ("wfd_client_rtp_ports".equals(name)) {
                int port = firstPort(value);
                if (port > 0) {
                    rtpPort = port;
                }
            } else if ("wfd_trigger_method".equals(name)) {
                trigger = value.trim().toUpperCase(Locale.US);
            } else if ("microsoft_latency_management_capability".equals(name)
                    || "microsoft_audio_mute".equals(name)
                    || "microsoft_format_change_capability".equals(name)) {
                // Accepted. Latency and mute do not change the picture size.
            }
        }
        return trigger;
    }

    private static int firstPort(String value) {
        String[] parts = value.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int semi = p.indexOf(';');
            if (semi >= 0) {
                p = p.substring(semi + 1);
            }
            if (p.matches("\\d+")) {
                try {
                    int port = Integer.parseInt(p);
                    if (port > 0 && port < 65536) {
                        return port;
                    }
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private String request(String method, String url, String extraHeaders, String body) {
        int cseq = localCseq++;
        outstandingCseq = cseq;
        outstandingMethod = method;
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append(extraHeaders);
        if (body.length() > 0) {
            sb.append("Content-Length: ").append(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length).append("\r\n");
        }
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    private static String response(int cseq, String extraHeaders, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 200 OK\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        if (body != null && body.length() > 0) {
            if (extraHeaders == null || extraHeaders.indexOf("Content-Type:") < 0) {
                sb.append("Content-Type: text/parameters\r\n");
            }
            sb.append("Content-Length: ").append(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length).append("\r\n");
        }
        sb.append("\r\n");
        if (body != null) {
            sb.append(body);
        }
        return sb.toString();
    }

    private static int statusCode(String start) {
        String[] parts = start.split("\\s+");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int headerInt(String[] lines, String name) {
        String value = headerToken(lines, name);
        if (value.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String headerToken(String[] lines, String name) {
        String prefix = name.toLowerCase(Locale.US);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
            if (!key.equals(prefix)) {
                continue;
            }
            String value = lines[i].substring(colon + 1).trim();
            int semi = value.indexOf(';');
            if (semi >= 0) {
                value = value.substring(0, semi).trim();
            }
            return value;
        }
        return "";
    }

    private static String parameterName(String line) {
        String trimmed = line.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            return trimmed.substring(0, colon).trim();
        }
        return trimmed;
    }
}
