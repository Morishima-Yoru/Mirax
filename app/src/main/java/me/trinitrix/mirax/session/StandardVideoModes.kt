package me.trinitrix.mirax.session

/**
 * CEA, VESA, and HH modes from the Wi-Fi Display resolution bitmaps.
 *
 * One row per distinct width/height/refresh. Modes larger than the panel are kept.
 * The list is filtered by H.264 level 5.1 and [BITRATE_CAP_BPS].
 *
 * Tables follow the older receiver [VideoModes] evidence in this repo.
 */
object StandardVideoModes {
    /**
     * Default / maximum session bitrate cap (40 Mbps), matching the legacy
     * `microsoft_max_bitrate` answer. Filters the standard-mode list and is
     * advertised to Windows on the next RTSP connection.
     */
    const val BITRATE_CAP_BPS: Long = 40_000_000L

    /** Lowest accepted cap when loading or applying a setting (1 Mbps). */
    const val BITRATE_FLOOR_BPS: Long = 1_000_000L

    /** Clamp a persisted or UI bitrate into the supported range. */
    fun clampMaxBitrateBps(bps: Long): Long =
        bps.coerceIn(BITRATE_FLOOR_BPS, BITRATE_CAP_BPS)

    /** Whole kbps shown/edited in Picture settings. */
    fun maxBitrateKbps(bps: Long): Int =
        (clampMaxBitrateBps(bps) / 1_000L).toInt().coerceAtLeast(1)

    /** Convert a typed kbps value into clamped bps. */
    fun maxBitrateBpsFromKbps(kbps: Int): Long =
        clampMaxBitrateBps(kbps.toLong() * 1_000L)

    private val CEA: List<VideoMode> = listOf(
        VideoMode(640, 480, 60),
        VideoMode(720, 480, 60),
        VideoMode(720, 576, 50),
        VideoMode(1280, 720, 30),
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
        VideoMode(1280, 720, 25),
        VideoMode(1280, 720, 50),
        VideoMode(1920, 1080, 25),
        VideoMode(1920, 1080, 50),
        VideoMode(1280, 720, 24),
        VideoMode(1920, 1080, 24),
    )

    private val VESA: List<VideoMode> = listOf(
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

    private val HH: List<VideoMode> = listOf(
        VideoMode(800, 480, 30), VideoMode(800, 480, 60),
        VideoMode(854, 480, 30), VideoMode(854, 480, 60),
        VideoMode(864, 480, 30), VideoMode(864, 480, 60),
        VideoMode(640, 360, 30), VideoMode(640, 360, 60),
        VideoMode(960, 540, 30), VideoMode(960, 540, 60),
        VideoMode(848, 480, 30), VideoMode(848, 480, 60),
    )

    private val ALL_UNIQUE: List<VideoMode> =
        (CEA + VESA + HH).distinct()

    private val DEFAULT_CHECKED_SIZES = listOf(
        1920 to 1080,
        1920 to 1200,
        1600 to 1200,
    )

    /**
     * Fresh-install default checks: every refresh of 1920×1080, 1920×1200, and 1600×1200.
     */
    val DEFAULT_CHECKED: Set<VideoMode> =
        ALL_UNIQUE.filter { mode ->
            DEFAULT_CHECKED_SIZES.any { (width, height) ->
                mode.width == width && mode.height == height
            }
        }.toSet()

    /**
     * Standard modes within H.264 level 5.1 and [bitrateCapBps], one row per mode.
     *
     * Args:
     *     bitrateCapBps: Session bitrate cap; does not rewrite preferred modes.
     *
     * Returns:
     *     Filtered modes in table order.
     */
    fun catalog(bitrateCapBps: Long = BITRATE_CAP_BPS): List<VideoMode> {
        return ALL_UNIQUE.filter { mode ->
            H264Level51.fits(mode) && estimatedBitrateBps(mode) <= bitrateCapBps
        }
    }

    /**
     * Peak bitrate estimate used only to filter the standard list.
     *
     * Uses 1/4 bit per pixel per frame — keeps default-checked 1080p60 under
     * [BITRATE_CAP_BPS] while still excluding pathological sizes if the cap drops.
     */
    fun estimatedBitrateBps(mode: VideoMode): Long {
        return mode.width.toLong() * mode.height * mode.refreshHz / 4L
    }
}
