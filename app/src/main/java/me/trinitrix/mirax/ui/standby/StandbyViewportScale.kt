package me.trinitrix.mirax.ui.standby

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Presentation units for one measured stage before safe insets.
 *
 * Landscape and Hardline opt into CSS pixels mapped through display density.
 * The default retains Matter's existing area-normalized presentation.
 *
 * @param width measured stage width in native pixels.
 * @param height measured stage height in native pixels.
 * @param density display density converting native pixels to density-independent units.
 * @param cssPixels true for the prototype's CSS-pixel presentation, false for legacy area scaling.
 */
class StandbyViewportScale(
    val width: Int,
    val height: Int,
    val density: Float = 1f,
    val cssPixels: Boolean = false,
) {
    val isValid: Boolean = width > 0 && height > 0 && density > 0f
    val logicalWidth: Float = if (isValid) width / density else 0f
    val logicalHeight: Float = if (isValid) height / density else 0f
    val scale: Float = if (isValid && !cssPixels) {
        sqrt((logicalWidth.toDouble() * logicalHeight) / REFERENCE_AREA).toFloat()
    } else {
        1f
    }

    val portrait: Boolean = width <= height
    val narrow: Boolean = isValid && logicalWidth <= 600f
    val compact: Boolean = isValid && if (cssPixels || portrait) {
        logicalHeight <= 820f
    } else {
        width.toDouble() / height >= COMPACT_LANDSCAPE_ASPECT
    }
    /** The channel CSS query is max-height 500px, not the foreground's 440px query. */
    val shortChannelLandscape: Boolean = isValid && !portrait && logicalHeight <= 500f
    val shortLandscape: Boolean = isValid && !portrait && logicalHeight <= 440f
    val veryShortLandscape: Boolean = isValid && !portrait && logicalHeight <= 340f
    /** Area-normalized short-landscape check (`!portrait && H / (s * d) <= 440`) for Matter dock only. */
    internal val shortAreaLandscape: Boolean = isValid && !portrait && !cssPixels &&
        height.toDouble() * REFERENCE_AREA <= width.toDouble() * SHORT_AREA_HEIGHT_SQ

    /** Converts a prototype unit to measured view pixels, including display density. */
    fun px(value: Float): Float = value * scale * density

    /** Rounds a prototype dimension only when assigning an integer view bound. */
    fun roundedPx(value: Float): Int = px(value).roundToInt()

    companion object {
        private const val REFERENCE_AREA = 390.0 * 844.0
        private const val SHORT_AREA_HEIGHT_SQ = 440.0 * 440.0
        private const val COMPACT_LANDSCAPE_ASPECT = 896.0 / 592.0
    }
}
