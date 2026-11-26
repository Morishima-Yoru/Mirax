package me.trinitrix.mirax.standbyprobe.render

/**
 * Logical width probe cases for standby text measurement.
 *
 * Widths are fixed software-measurement inputs only. FOLD_LOGICAL reuses a
 * representative wide pixel width and is explicitly not an actual foldable
 * posture, hinge state, or device-specific configuration.
 *
 * @param logicalWidthPx Width in px used for StaticLayout measurement. Zero
 * means use the attached view width.
 * @param note Human-readable bound of this case, surfaced for tests and logs.
 */
enum class ProbeLayoutCase(val logicalWidthPx: Int, val note: String) {
    /** Reference single line at source size, measured at view width. */
    REFERENCE(0, "Reference single line at view width."),
    /** Wide phone logical width probe. */
    WIDE_1080(1080, "Wide logical width probe only."),
    /** Narrow phone logical width probe, expected to ellipsize long names. */
    NARROW_320(320, "Narrow logical width probe only."),
    /** Representative wide logical width; not an actual foldable posture. */
    FOLD_LOGICAL(1812, "Logical width probe only; not an actual foldable posture."),
    ;

    /**
     * Resolves effective layout width in pixels, clamping invalid zero/negative values.
     *
     * @param viewWidth Current width of the attached view in px.
     * @param insetsHorizontal Sum of left and right insets in px.
     * @param defaultFallback Default fallback width when available width is not positive.
     * @return Positive layout width in pixels.
     */
    fun resolveEffectiveWidth(
        viewWidth: Int,
        insetsHorizontal: Int = 0,
        defaultFallback: Int = 1080,
    ): Int {
        if (logicalWidthPx > 0) {
            return logicalWidthPx
        }
        val available = viewWidth - insetsHorizontal.coerceAtLeast(0)
        return if (available > 0) available else defaultFallback.coerceAtLeast(1)
    }
}
