package me.trinitrix.mirax.wfd.rtsp

import android.util.Log
import me.trinitrix.mirax.session.MicrosoftVideoModes
import me.trinitrix.mirax.session.PreferredModeCorrection
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.wfd.MicrosoftCursorChannel

/**
 * Capability answers for one RTSP connection. Built from the advertisement set
 * for that connection — never latches extra 720p/1080p after a pre-PLAY drop.
 *
 * Advertises UIBC HIDC only when [touchEnabled] is true. Does not open the UIBC socket.
 * Interactive refresh alternatives are prepared here so all wire answers use the same offer.
 */
class WfdCapabilityTable(
    modes: Set<VideoMode>,
    preferred: VideoMode?,
    private val friendlyName: String,
    private val touchEnabled: Boolean = false,
    private val maxBitrateBps: Long = 40_000_000L,
) {
    private val modes = (modes + if (touchEnabled) modes.map(::remappedInteractiveRefresh) else emptyList())
        .filter { !MicrosoftVideoModes.contains(it) || it in MicrosoftVideoModes.catalog() }
        .toSet()
    private val preferred = if (touchEnabled) preferred?.let(::remappedInteractiveRefresh) else preferred

    /**
     * Preserves size while preparing the existing low-latency refresh alternative.
     *
     * @param mode settings-owned mode to prepare
     * @return the original mode or a Level 5.1 compliant interactive alternative
     */
    private fun remappedInteractiveRefresh(mode: VideoMode): VideoMode {
        val remapped = if (mode.refreshHz <= INTERACTIVE_MAX_FPS) {
            mode
        } else {
            VideoMode(mode.width, mode.height, INTERACTIVE_MAX_FPS)
        }
        // Fixed Microsoft bits must never add an unchecked sibling refresh.
        if (MicrosoftVideoModes.contains(mode) || MicrosoftVideoModes.contains(remapped)) return mode
        // Do not turn a standard fallback into an unrequested custom/EDID preference.
        if (WfdVideoFormatCodec.isStandardMode(mode) && !WfdVideoFormatCodec.isStandardMode(remapped)) return mode
        return PreferredModeCorrection.correct(remapped.width, remapped.height, remapped.refreshHz) ?: run {
            Log.w("WfdCapabilityTable", "interactive remap ${mode.format()} exceeds Level 5.1, keeping original")
            mode
        }
    }

    /**
     * Interactive (UIBC) sessions: M3 should prefer low-latency encode and always
     * surface [microsoft_max_bitrate], even when Windows omitted those names.
     */
    val interactiveHints: Boolean
        get() = touchEnabled

    /**
     * Validates a Microsoft selection against this connection's offer.
     *
     * @param value M4 bitmap value
     * @return the supported offered mode, or null when the source did not select one
     */
    fun microsoftModeFor(value: String): VideoMode? =
        MicrosoftVideoModes.decodeSelection(value)?.takeIf { it in modes }

    fun valueFor(name: String): String {
        return when (name) {
            "wfd_video_formats" -> WfdVideoFormatCodec.wfdVideoFormats(modes, preferred)
            "wfdx_video_formats", "wfd2_video_formats" ->
                WfdVideoFormatCodec.wfdxVideoFormats(modes, preferred)
            "microsoft_custom_video_formats" -> WfdVideoFormatCodec.encodeCustomFormats(modes)
            "microsoft_video_formats" -> MicrosoftVideoModes.encode(modes)
            "wfd_audio_codecs", "wfd2_audio_codecs" -> WfdVideoFormatCodec.audioCodecs()
            "wfd_client_rtp_ports" -> WfdVideoFormatCodec.clientRtpPorts()
            "wfd_display_edid" -> {
                val edidMode = preferred?.takeIf { it in modes }
                    ?: modes.firstOrNull { WfdVideoFormatCodec.isCustomMode(it) }
                    ?: modes.maxByOrNull { it.width.toLong() * it.height }
                    ?: VideoMode(1920, 1080, 60)
                WfdEdid.parameterValue(edidMode, friendlyName.take(13).ifEmpty { "Mirax" })
            }
            "wfd_connector_type" -> "07"
            "wfd_uibc_capability" -> if (touchEnabled) UIBC_HIDC else "none"
            "wfd_idr_request_capability" -> "1"
            "microsoft_format_change_capability",
            "microsoft_diagnostics_capability",
            "microsoft_rtcp_capability",
            -> "supported"
            // Interactive: advertise none so Win11 does not pick a buffered
            // high/normal role that batches ~30 AU then ~550 ms silence on this
            // Phh link. Mid-session role switches still stall encode — do not
            // fight after PLAY. Resolution stays Settings-owned.
            "microsoft_latency_management_capability" ->
                if (touchEnabled) "none" else "supported"
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
            "microsoft_color_space_conversion",
            "microsoft_multiscreen_projection",
            "wfd2_rotation_capability",
            "wfd2_video_stream_control",
            -> "none"
            else -> "none"
        }
    }

    companion object {
        private const val INTERACTIVE_MAX_FPS = 30

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
