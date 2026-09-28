package me.trinitrix.mirax.session

/**
 * H.264 level 5.1 frame-size and macroblock-rate limits used for preferred-mode
 * correction and standard-mode filtering.
 */
object H264Level51 {
    /** Maximum macroblocks per frame (level 5.1 MaxFS). */
    const val MAX_FRAME_MACROBLOCKS: Int = 36_864

    /** Maximum macroblocks per second (level 5.1 MaxMBPS). */
    const val MAX_MACROBLOCKS_PER_SECOND: Int = 983_040

    /**
     * Whether [mode] fits level 5.1 frame size and macroblock rate.
     */
    fun fits(mode: VideoMode): Boolean {
        val frameMb = frameMacroblocks(mode.width, mode.height)
        if (frameMb > MAX_FRAME_MACROBLOCKS) {
            return false
        }
        return frameMb.toLong() * mode.refreshHz <= MAX_MACROBLOCKS_PER_SECOND
    }

    fun frameMacroblocks(width: Int, height: Int): Int {
        val mbW = (width + 15) / 16
        val mbH = (height + 15) / 16
        return mbW * mbH
    }
}
