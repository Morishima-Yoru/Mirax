package me.trinitrix.mirax.session

/** Microsoft 3:2 modes in MS-WFDPE section 2.7.1.2.1 bitmap order. */
object MicrosoftVideoModes {
    private val FORMATS: List<VideoMode> = listOf(
        VideoMode(1920, 1280, 30), VideoMode(1920, 1280, 60), VideoMode(1920, 1280, 24),
        VideoMode(2160, 1440, 30), VideoMode(2160, 1440, 60), VideoMode(2160, 1440, 24),
        VideoMode(2256, 1504, 30), VideoMode(2256, 1504, 60), VideoMode(2256, 1504, 24),
        VideoMode(2736, 1824, 30), VideoMode(2736, 1824, 60), VideoMode(2736, 1824, 24),
        VideoMode(3000, 2000, 30), VideoMode(3000, 2000, 60), VideoMode(3000, 2000, 24),
        VideoMode(3240, 2160, 30), VideoMode(3240, 2160, 60), VideoMode(3240, 2160, 24),
        VideoMode(4500, 3000, 30), VideoMode(4500, 3000, 60), VideoMode(4500, 3000, 24),
    )

    /**
     * Distinguishes fixed Microsoft modes from literal custom modes, including unsupported entries.
     *
     * @param mode mode to classify
     * @return whether the mode has a defined Microsoft bitmap index
     */
    fun contains(mode: VideoMode): Boolean = mode in FORMATS

    /**
     * Lists modes within the receiver's static width and H.264 Level 5.1 limits.
     *
     * @return selectable modes in bitmap order; not a hardware decode guarantee
     */
    // shortcut: static limits do not probe MediaCodec; add per-device checks before supporting more hardware.
    fun catalog(): List<VideoMode> = FORMATS.filter { it.width <= 4096 && H264Level51.fits(it) }

    /**
     * Encodes enabled supported modes; reserved bits remain clear.
     *
     * @param modes modes offered for this connection
     * @return twelve hexadecimal digits, including an all-zero empty offer
     */
    fun encode(modes: Collection<VideoMode>): String {
        val enabled = modes.toSet().intersect(catalog().toSet())
        var mask = 0L
        FORMATS.forEachIndexed { index, mode ->
            if (mode in enabled) {
                mask = mask or (1L shl index)
            }
        }
        return String.format("%012X", mask)
    }

    /**
     * Decodes one unambiguous, supported source selection.
     *
     * @param value twelve hexadecimal digits from an M4 parameter
     * @return the selected mode, or null for malformed, reserved, or unsupported values
     */
    fun decodeSelection(value: String): VideoMode? {
        if (!value.matches(Regex("[0-9A-Fa-f]{12}"))) return null
        val mask = value.toLong(16)
        if (mask.countOneBits() != 1) return null
        return FORMATS.getOrNull(mask.countTrailingZeroBits())?.takeIf { it in catalog() }
    }
}
