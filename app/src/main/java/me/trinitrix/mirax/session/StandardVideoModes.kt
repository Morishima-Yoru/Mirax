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
     * Session bitrate cap reused from the legacy `microsoft_max_bitrate` value (40 Mbps).
     * Used only to filter the standard-mode list — never to rewrite a preferred mode.
     */
    const val BITRATE_CAP_BPS: Long = 40_000_000L

    /**
     * Fresh-install default checks: 720p60, 1080p30, and 1080p60.
     */
    val DEFAULT_CHECKED: Set<VideoMode> = setOf(
        VideoMode(1280, 720, 60),
        VideoMode(1920, 1080, 30),
        VideoMode(1920, 1080, 60),
    )

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
