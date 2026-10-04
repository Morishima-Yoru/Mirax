package me.trinitrix.mirax.wfd.rtsp

import me.trinitrix.mirax.session.VideoMode

/**
 * Encodes and decodes Wi-Fi Display standard bitmaps and Microsoft custom formats.
 *
 * Standard modes are a bit set (CEA / VESA / HH), not an ordered list. Custom sizes
 * are literal width, height, and refresh. Wire field order is not a fallback priority.
 * Heights that are not multiples of 16 remain legal.
 *
 * There is no in-memory latch that adds 720p/1080p after a pre-PLAY drop — the
 * advertised set is exactly the [modes] given for that connection.
 */
object WfdVideoFormatCodec {
    const val RTP_PORT: Int = 19000
    const val H264_LEVEL_51: String = "40"

    /** Indexed CEA table matching the WFD resolution bitmap (duplicates share distinct bits). */
    private val CEA: Array<VideoMode> = arrayOf(
        VideoMode(640, 480, 60),
        VideoMode(720, 480, 60),
        VideoMode(720, 480, 60),
        VideoMode(720, 576, 50),
        VideoMode(720, 576, 50),
        VideoMode(1280, 720, 30),
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
        VideoMode(1920, 1080, 60),
        VideoMode(1280, 720, 25),
        VideoMode(1280, 720, 50),
        VideoMode(1920, 1080, 25),
        VideoMode(1920, 1080, 50),
        VideoMode(1280, 720, 24),
        VideoMode(1920, 1080, 24),
    )

    private val VESA: Array<VideoMode> = arrayOf(
        VideoMode(800, 600, 30), VideoMode(800, 600, 60),
        VideoMode(1024, 768, 30), VideoMode(1024, 768, 60),
        VideoMode(1152, 864, 30), VideoMode(1152, 864, 60),
        VideoMode(1280, 768, 30), VideoMode(1280, 768, 60),
        VideoMode(1280, 800, 30), VideoMode(1280, 800, 60),
        VideoMode(1360, 768, 30), VideoMode(1360, 768, 60),
        VideoMode(1366, 768, 30), VideoMode(1366, 768, 60),
        VideoMode(1280, 1024, 30), VideoMode(1280, 1024, 60),
        VideoMode(1400, 1050, 30), VideoMode(1400, 1050, 60),
        VideoMode(1440, 900, 30), VideoMode(1440, 900, 60),
        VideoMode(1600, 900, 30), VideoMode(1600, 900, 60),
        VideoMode(1600, 1200, 30), VideoMode(1600, 1200, 60),
        VideoMode(1680, 1024, 30), VideoMode(1680, 1024, 60),
        VideoMode(1680, 1050, 30), VideoMode(1680, 1050, 60),
        VideoMode(1920, 1200, 30), VideoMode(1920, 1200, 60),
    )

    private val HH: Array<VideoMode> = arrayOf(
        VideoMode(800, 480, 30), VideoMode(800, 480, 60),
        VideoMode(854, 480, 30), VideoMode(854, 480, 60),
        VideoMode(864, 480, 30), VideoMode(864, 480, 60),
        VideoMode(640, 360, 30), VideoMode(640, 360, 60),
        VideoMode(960, 540, 30), VideoMode(960, 540, 60),
        VideoMode(848, 480, 30), VideoMode(848, 480, 60),
    )

    private val STANDARD_LOOKUP: Set<VideoMode> =
        (CEA + VESA + HH).toSet()

    fun isStandardMode(mode: VideoMode): Boolean = mode in STANDARD_LOOKUP

    fun hex4(value: Int): String {
        require(value in 0..0xFFFF) { "field out of range: $value" }
        return String.format("%04X", value)
    }

    /**
     * Encode custom modes as space-separated hex width height refresh, comma between modes.
     */
    fun encodeCustomFormats(modes: Collection<VideoMode>): String {
        val customs = modes.filterNot { isStandardMode(it) }
        if (customs.isEmpty()) {
            return "none"
        }
        return customs.joinToString(", ") { mode ->
            "${hex4(mode.width)} ${hex4(mode.height)} ${hex4(mode.refreshHz)}"
        }
    }

    fun parseCustomResolution(value: String?): VideoMode? {
        if (value.isNullOrBlank() || value.equals("none", ignoreCase = true)) {
            return null
        }
        val first = value.trim().split(",")[0].trim()
        val parts = first.split(Regex("\\s+"))
        if (parts.size < 3) {
            return null
        }
        return try {
            val w = parts[0].toInt(16)
            val h = parts[1].toInt(16)
            val fps = parts[2].toInt(16)
            if (w <= 0 || h <= 0 || fps <= 0) null else VideoMode(w, h, fps)
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun ceaMask(modes: Collection<VideoMode>): Long = maskFor(modes, CEA)

    fun vesaMask(modes: Collection<VideoMode>): Long = maskFor(modes, VESA)

    fun hhMask(modes: Collection<VideoMode>): Long = maskFor(modes, HH)

    private fun maskFor(modes: Collection<VideoMode>, table: Array<VideoMode>): Long {
        var mask = 0L
        val wanted = modes.toSet().toMutableSet()
        for (i in table.indices) {
            val entry = table[i]
            if (entry in wanted) {
                mask = mask or (1L shl i)
                // One bit per distinct mode — duplicate table slots are not a priority order.
                wanted.remove(entry)
            }
        }
        return mask
    }

    fun encodeCeaHex(modes: Collection<VideoMode>): String = String.format("%08X", ceaMask(modes))

    fun encodeVesaHex(modes: Collection<VideoMode>): String = String.format("%08X", vesaMask(modes))

    fun encodeHhHex(modes: Collection<VideoMode>): String = String.format("%08X", hhMask(modes))

    fun encodeCeaHexWfdx(modes: Collection<VideoMode>): String =
        String.format("%010X", ceaMask(modes))

    fun encodeVesaHexWfdx(modes: Collection<VideoMode>): String =
        String.format("%010X", vesaMask(modes))

    /**
     * Classic [wfd_video_formats] value for the advertised set.
     *
     * [preferred] supplies max-hres/max-vres when present in [modes]; otherwise
     * preferred-display-mode-supported is cleared.
     */
    fun wfdVideoFormats(modes: Set<VideoMode>, preferred: VideoMode?): String {
        val cea = encodeCeaHex(modes)
        val vesa = encodeVesaHex(modes)
        val hh = encodeHhHex(modes)
        val pref = preferred?.takeIf { it in modes }
            ?: modes.firstOrNull { !isStandardMode(it) }
        return if (pref != null) {
            "00 01 03 $H264_LEVEL_51 $cea $vesa $hh 00 0000 0000 11 ${hex4(pref.width)} ${hex4(pref.height)}"
        } else {
            "00 00 03 $H264_LEVEL_51 $cea $vesa $hh 00 0000 0000 00 0000 0000"
        }
    }

    fun wfdxVideoFormats(modes: Set<VideoMode>, preferred: VideoMode?): String {
        val cea = encodeCeaHexWfdx(modes)
        val vesa = encodeVesaHexWfdx(modes)
        val hh = String.format("%08X", hhMask(modes))
        val pref = preferred?.takeIf { it in modes }
            ?: modes.firstOrNull { !isStandardMode(it) }
        return if (pref != null) {
            "0000 01 0003 00$H264_LEVEL_51 $cea $vesa $hh 00 0000 0000 11 ${hex4(pref.width)} ${hex4(pref.height)}"
        } else {
            "0000 00 0003 00$H264_LEVEL_51 $cea $vesa $hh 00 0000 0000 00 0000 0000"
        }
    }

    fun clientRtpPorts(): String =
        "RTP/AVP/UDP;unicast $RTP_PORT 0 mode=play"

    fun audioCodecs(): String = "LPCM 00000003 00"

    /**
     * Highest-pixel progressive-looking mode whose bit is set in a source M4 value.
     */
    fun fromBitmapValue(value: String?): VideoMode? {
        if (value == null) {
            return null
        }
        var cea = -1L
        var vesa = -1L
        var hh = -1L
        var masks = 0
        for (token in value.trim().split(Regex("\\s+"))) {
            val length = token.length
            if (length != 8 && length != 10 && length != 12) {
                continue
            }
            if (!token.matches(Regex("[0-9A-Fa-f]+"))) {
                continue
            }
            val mask = try {
                token.toLong(16)
            } catch (_: NumberFormatException) {
                continue
            }
            when (masks) {
                0 -> cea = mask
                1 -> vesa = mask
                2 -> hh = mask
            }
            masks++
        }
        var chosen: VideoMode? = null
        chosen = prefer(chosen, best(cea, CEA))
        chosen = prefer(chosen, best(vesa, VESA))
        chosen = prefer(chosen, best(hh, HH))
        return chosen
    }

    private fun prefer(current: VideoMode?, candidate: VideoMode?): VideoMode? {
        if (candidate == null) {
            return current
        }
        if (current == null) {
            return candidate
        }
        val currentPixels = current.width * current.height
        val candidatePixels = candidate.width * candidate.height
        if (candidatePixels > currentPixels) {
            return candidate
        }
        if (candidatePixels == currentPixels && candidate.refreshHz > current.refreshHz) {
            return candidate
        }
        return current
    }

    private fun best(mask: Long, table: Array<VideoMode>): VideoMode? {
        if (mask <= 0) {
            return null
        }
        var chosen: VideoMode? = null
        var pixels = -1
        val limit = minOf(table.size, 48)
        for (i in 0 until limit) {
            if ((mask and (1L shl i)) == 0L) {
                continue
            }
            val mode = table[i]
            val area = mode.width * mode.height
            if (chosen == null || area > pixels || (area == pixels && mode.refreshHz > chosen.refreshHz)) {
                chosen = mode
                pixels = area
            }
        }
        return chosen
    }
}
