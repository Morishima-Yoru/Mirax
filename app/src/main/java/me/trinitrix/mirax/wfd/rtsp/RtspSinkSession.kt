package me.trinitrix.mirax.wfd.rtsp

import me.trinitrix.mirax.session.VideoMode
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Wi-Fi Display RTSP sink session from M1 through PLAY.
 *
 * Host-testable protocol state machine. Does not touch frozen/tile/handle UI.
 * Parses the source UIBC port when touch was offered. Does not open the socket.
 */
class RtspSinkSession(
    private val capabilities: WfdCapabilityTable,
    private val rtpPort: Int = WfdVideoFormatCodec.RTP_PORT,
) {
    private var localCseq: Int = 1
    private var outstandingCseq: Int = -1
    private var outstandingMethod: String = ""
    private var presentationUrl: String = ""
    private var sessionId: String = ""
    var state: String = "INIT"
        private set
    var selectedMode: VideoMode? = null
        private set
    var formatChosen: Boolean = false
        private set
    /** TCP port the source asked this sink to dial for UIBC, or -1. */
    var uibcPort: Int = -1
        private set

    /**
     * HIDC type the source selected: 2 single touch, 3 multi touch.
     * Meaningful only when [uibcPort] is positive.
     */
    var uibcHidType: Int = 3
        private set

    /**
     * Latest microsoft_latency_management_capability value from the source
     * (`low` / `normal` / `high`), or empty before the first SET_PARAMETER.
     */
    var latencyMode: String = ""
        private set

    private var customSelected: Boolean = false
    private var activeRtpPort: Int = rtpPort

    fun rtpPort(): Int = activeRtpPort

    fun presentationUrl(): String = presentationUrl

    fun sessionId(): String = sessionId

    /** Messages to write, in order, after receiving one complete RTSP message. */
    fun handle(message: String?): List<String> {
        val out = ArrayList<String>()
        if (message == null) {
            return out
        }
        val normalized = message.replace("\r\n", "\n")
        val split = normalized.indexOf("\n\n")
        val head = if (split >= 0) normalized.substring(0, split) else normalized
        val body = if (split >= 0) normalized.substring(split + 2) else ""
        val lines = head.split("\n")
        if (lines.isEmpty() || lines[0].isEmpty()) {
            return out
        }
        val start = lines[0].trim()
        val cseq = headerInt(lines, "CSeq")
        if (start.startsWith("RTSP/1.0")) {
            handleResponse(start, lines, out)
            return out
        }
        val sp1 = start.indexOf(' ')
        val sp2 = if (sp1 < 0) -1 else start.indexOf(' ', sp1 + 1)
        if (sp1 < 0 || sp2 < 0) {
            return out
        }
        val method = start.substring(0, sp1)
        when (method) {
            "OPTIONS" -> {
                out.add(
                    response(
                        cseq,
                        "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n",
                        "",
                    ),
                )
                out.add(request("OPTIONS", "*", "Require: org.wfa.wfd1.0\r\n", ""))
                state = "WAIT_M2"
            }
            "GET_PARAMETER" -> {
                out.add(response(cseq, "Content-Type: text/parameters\r\n", getParameterBody(body)))
                if (state == "INIT" || state == "WAIT_M2") {
                    state = "CAPABILITIES"
                }
            }
            "SET_PARAMETER" -> {
                val trigger = applyParameters(body)
                out.add(response(cseq, "", ""))
                when (trigger) {
                    "SETUP" -> {
                        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0/streamid=0" }
                        val transport = "Transport: RTP/AVP/UDP;unicast;client_port=" +
                            "$activeRtpPort-${activeRtpPort + 1}\r\n"
                        out.add(request("SETUP", url, transport, ""))
                        state = "SETUP"
                    }
                    "PLAY" -> out.add(playRequest())
                    "TEARDOWN" -> {
                        out.add(teardownRequest())
                        state = "TEARDOWN"
                    }
                    "PAUSE" -> {
                        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0/streamid=0" }
                        val extra = if (sessionId.isEmpty()) "" else "Session: $sessionId\r\n"
                        out.add(request("PAUSE", url, extra, ""))
                        state = "PAUSED"
                    }
                    else -> {
                        if (state != "PLAYING" && formatChosen) {
                            state = "CONFIGURED"
                        }
                    }
                }
            }
            "TEARDOWN" -> {
                out.add(response(cseq, "", ""))
                state = "TEARDOWN"
            }
            else -> out.add(response(cseq, "", ""))
        }
        return out
    }

    fun requestIdr(): String {
        if (state != "PLAYING" || outstandingCseq != -1) {
            return ""
        }
        return idrRequest()
    }

    /**
     * Ask the source to switch microsoft latency role (`low` / `normal` / `high`).
     *
     * Windows normally pushes this sink-ward; sending it sink-ward after PLAY is a
     * best-effort nudge toward interactive encode when UIBC is active.
     */
    fun requestLatencyMode(mode: String): String {
        if (state != "PLAYING" || outstandingCseq != -1) {
            return ""
        }
        val normalized = mode.trim().lowercase(Locale.US)
        if (normalized !in setOf("low", "normal", "high")) {
            return ""
        }
        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0" }
        return request(
            "SET_PARAMETER",
            url,
            "Content-Type: text/parameters\r\n",
            "microsoft_latency_management_capability: $normalized\r\n",
        )
    }

    private fun handleResponse(start: String, lines: List<String>, out: MutableList<String>) {
        val code = statusCode(start)
        val cseq = headerInt(lines, "CSeq")
        if (cseq != outstandingCseq) {
            return
        }
        outstandingCseq = -1
        val method = outstandingMethod
        outstandingMethod = ""
        if (code < 200 || code >= 300) {
            state = "ERROR"
            return
        }
        when (method) {
            "OPTIONS" -> state = "READY"
            "SETUP" -> {
                sessionId = headerToken(lines, "Session")
                state = "READY_TO_PLAY"
                out.add(playRequest())
            }
            "PLAY" -> {
                state = "PLAYING"
                out.add(idrRequest())
            }
            "TEARDOWN" -> state = "TEARDOWN"
        }
    }

    private fun playRequest(): String {
        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0/streamid=0" }
        val extra = if (sessionId.isEmpty()) "" else "Session: $sessionId\r\n"
        return request("PLAY", url, extra, "")
    }

    private fun teardownRequest(): String {
        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0/streamid=0" }
        val extra = if (sessionId.isEmpty()) "" else "Session: $sessionId\r\n"
        return request("TEARDOWN", url, extra, "")
    }

    private fun idrRequest(): String {
        val url = presentationUrl.ifEmpty { "rtsp://localhost/wfd1.0" }
        return request("SET_PARAMETER", url, "Content-Type: text/parameters\r\n", "wfd_idr_request\r\n")
    }

    private fun getParameterBody(body: String): String {
        val lines = body.split("\n")
        val sb = StringBuilder()
        var askedVideo = false
        var askedCustom = false
        var askedWfdx = false
        var askedWfd2 = false
        for (line in lines) {
            val name = parameterName(line)
            if (name.isEmpty()) {
                continue
            }
            when (name) {
                "wfd_video_formats" -> askedVideo = true
                "wfdx_video_formats" -> askedWfdx = true
                "wfd2_video_formats" -> askedWfd2 = true
                "microsoft_custom_video_formats" -> askedCustom = true
            }
            appendParam(sb, name, capabilities.valueFor(name))
        }
        if (askedVideo && !askedWfdx && !askedWfd2) {
            appendParam(sb, "wfdx_video_formats", capabilities.valueFor("wfdx_video_formats"))
        }
        if ((askedVideo || askedWfdx) && !askedCustom) {
            appendParam(
                sb,
                "microsoft_custom_video_formats",
                capabilities.valueFor("microsoft_custom_video_formats"),
            )
        }
        return sb.toString()
    }

    private fun applyParameters(body: String): String {
        var trigger = ""
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) {
                continue
            }
            val colon = line.indexOf(':')
            val name = if (colon < 0) line else line.substring(0, colon).trim()
            val value = if (colon < 0) "" else line.substring(colon + 1).trim()
            when (name) {
                "microsoft_custom_video_formats" -> {
                    val mode = WfdVideoFormatCodec.parseCustomResolution(value)
                    if (mode != null) {
                        selectedMode = mode
                        formatChosen = true
                        customSelected = true
                    }
                }
                "wfd_video_formats", "wfd2_video_formats", "wfdx_video_formats" -> {
                    if (!customSelected) {
                        val mode = WfdVideoFormatCodec.fromBitmapValue(value)
                        if (mode != null) {
                            selectedMode = mode
                            formatChosen = true
                        }
                    }
                }
                "wfd_presentation_URL" -> {
                    val tokens = value.split(Regex("\\s+"))
                    if (tokens.isNotEmpty() && tokens[0].startsWith("rtsp://")) {
                        presentationUrl = tokens[0]
                    }
                }
                "wfd_client_rtp_ports" -> {
                    val port = firstPort(value)
                    if (port > 0) {
                        activeRtpPort = port
                    }
                }
                "wfd_trigger_method" -> trigger = value.trim().uppercase(Locale.US)
                "wfd_uibc_capability" -> applyUibc(value)
                "wfd_uibc_setting" -> {
                    if (value.contains("disable", ignoreCase = true)) {
                        uibcPort = -1
                    }
                }
                "microsoft_latency_management_capability" -> {
                    latencyMode = value.lowercase(Locale.US).ifEmpty { "supported" }
                }
                "microsoft_audio_mute",
                "microsoft_format_change_capability",
                -> {
                    // Accepted; does not change picture size.
                }
            }
        }
        return trigger
    }

    private fun applyUibc(value: String) {
        if (value.contains("disable", ignoreCase = true)) {
            uibcPort = -1
            return
        }
        val port = portField(value)
        if (port <= 0) {
            return
        }
        uibcPort = port
        uibcHidType = when {
            value.contains("MultiTouch", ignoreCase = true) -> 3
            value.contains("SingleTouch", ignoreCase = true) -> 2
            else -> 3
        }
    }

    private fun portField(value: String): Int {
        val match = Regex("port=(\\d+)").find(value) ?: return -1
        val port = match.groupValues[1].toIntOrNull() ?: return -1
        return if (port in 1..65535) port else -1
    }

    private fun request(method: String, url: String, extraHeaders: String, body: String): String {
        val cseq = localCseq++
        outstandingCseq = cseq
        outstandingMethod = method
        val sb = StringBuilder()
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        sb.append(extraHeaders)
        if (body.isNotEmpty()) {
            sb.append("Content-Length: ")
                .append(body.toByteArray(StandardCharsets.US_ASCII).size)
                .append("\r\n")
        }
        sb.append("\r\n")
        sb.append(body)
        return sb.toString()
    }

    companion object {
        private fun appendParam(sb: StringBuilder, name: String, value: String) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }

        private fun firstPort(value: String): Int {
            for (part in value.split(Regex("\\s+"))) {
                var p = part
                val semi = p.indexOf(';')
                if (semi >= 0) {
                    p = p.substring(semi + 1)
                }
                if (p.matches(Regex("\\d+"))) {
                    return try {
                        val port = p.toInt()
                        if (port in 1..65535) port else -1
                    } catch (_: NumberFormatException) {
                        -1
                    }
                }
            }
            return -1
        }

        private fun response(cseq: Int, extraHeaders: String, body: String): String {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 200 OK\r\n")
            sb.append("CSeq: ").append(cseq).append("\r\n")
            sb.append(extraHeaders)
            if (body.isNotEmpty()) {
                if (!extraHeaders.contains("Content-Type:")) {
                    sb.append("Content-Type: text/parameters\r\n")
                }
                sb.append("Content-Length: ")
                    .append(body.toByteArray(StandardCharsets.US_ASCII).size)
                    .append("\r\n")
            }
            sb.append("\r\n")
            sb.append(body)
            return sb.toString()
        }

        private fun statusCode(start: String): Int {
            val parts = start.split(Regex("\\s+"))
            if (parts.size < 2) {
                return 0
            }
            return try {
                parts[1].toInt()
            } catch (_: NumberFormatException) {
                0
            }
        }

        private fun headerInt(lines: List<String>, name: String): Int {
            val value = headerToken(lines, name)
            if (value.isEmpty()) {
                return -1
            }
            return try {
                value.toInt()
            } catch (_: NumberFormatException) {
                -1
            }
        }

        private fun headerToken(lines: List<String>, name: String): String {
            val prefix = name.lowercase(Locale.US)
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon < 0) {
                    continue
                }
                val key = lines[i].substring(0, colon).trim().lowercase(Locale.US)
                if (key != prefix) {
                    continue
                }
                var value = lines[i].substring(colon + 1).trim()
                val semi = value.indexOf(';')
                if (semi >= 0) {
                    value = value.substring(0, semi).trim()
                }
                return value
            }
            return ""
        }

        private fun parameterName(line: String): String {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                return ""
            }
            val colon = trimmed.indexOf(':')
            return if (colon >= 0) trimmed.substring(0, colon).trim() else trimmed
        }
    }
}


