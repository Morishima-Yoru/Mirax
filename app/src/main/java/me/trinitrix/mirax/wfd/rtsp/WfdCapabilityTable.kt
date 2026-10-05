package me.trinitrix.mirax.wfd.rtsp

import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.wfd.MicrosoftCursorChannel

/**
 * Capability answers for one RTSP connection. Built from the advertisement set
 * for that connection — never latches extra 720p/1080p after a pre-PLAY drop.
 *
 * Advertises UIBC HIDC only when [touchEnabled] is true. Does not open the UIBC socket.
 */
class WfdCapabilityTable(
    private val modes: Set<VideoMode>,
    private val preferred: VideoMode?,
    private val friendlyName: String,
    private val touchEnabled: Boolean = false,
    private val maxBitrateBps: Long = 40_000_000L,
) {
    fun valueFor(name: String): String {
        return when (name) {
            "wfd_video_formats" -> WfdVideoFormatCodec.wfdVideoFormats(modes, preferred)
            "wfdx_video_formats", "wfd2_video_formats" ->
                WfdVideoFormatCodec.wfdxVideoFormats(modes, preferred)
            "microsoft_custom_video_formats" -> WfdVideoFormatCodec.encodeCustomFormats(modes)
            "microsoft_video_formats" -> "000000000000"
            "wfd_audio_codecs", "wfd2_audio_codecs" -> WfdVideoFormatCodec.audioCodecs()
            "wfd_client_rtp_ports" -> WfdVideoFormatCodec.clientRtpPorts()
            "wfd_display_edid" -> {
                val edidMode = preferred?.takeIf { it in modes }
                    ?: modes.firstOrNull { !WfdVideoFormatCodec.isStandardMode(it) }
                    ?: modes.maxByOrNull { it.width.toLong() * it.height }
                    ?: VideoMode(1920, 1080, 60)
                WfdEdid.parameterValue(edidMode, friendlyName.take(13).ifEmpty { "Mirax" })
            }
            "wfd_connector_type" -> "07"
            "wfd_uibc_capability" -> if (touchEnabled) UIBC_HIDC else "none"
            "wfd_idr_request_capability" -> "1"
            "microsoft_format_change_capability",
            "microsoft_latency_management_capability",
            "microsoft_diagnostics_capability",
            -> "supported"
            "microsoft_max_bitrate" -> maxBitrateBps.coerceAtLeast(1L).toString()
            "microsoft_audio_mute" -> "supported"
            // MS-WDHCE: "none" or "xor-support x-max y-max port" — not the bare token "supported".
            "microsoft_cursor" ->
                if (ADVERTISE_HARDWARE_CURSOR) {
                    MicrosoftCursorChannel.capabilityValue()
                } else {
                    "none"
                }
            "intel_friendly_name" -> friendlyName.ifEmpty { "Mirax" }
            "intel_sink_manufacturer_name" -> "Mirax"
            "intel_sink_model_name" -> "Mirax"
            "intel_sink_device_URL" -> "none"
            "intel_sink_version" -> "product_ID=Mirax hw_version=1 sw_version=1"
            "wfd_coupled_sink",
            "wfd_content_protection",
            "wfd_3d_video_formats",
            "wfd_presentation_URL",
            "microsoft_rtcp_capability",
            "microsoft_color_space_conversion",
            "microsoft_multiscreen_projection",
            "wfd2_rotation_capability",
            "wfd2_video_stream_control",
            -> "none"
            else -> "none"
        }
    }

    companion object {
        /**
         * When true, advertise [MS-WDHCE] hardware cursor so Windows stops burning
         * the pointer into the H.264 stream (the main mouse-lag feel on local echo).
         */
        const val ADVERTISE_HARDWARE_CURSOR: Boolean = true

        private const val UIBC_HIDC: String =
            "input_category_list=HIDC; generic_cap_list=none; " +
                "hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none"
    }
}
