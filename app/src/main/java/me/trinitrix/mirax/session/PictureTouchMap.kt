package me.trinitrix.mirax.session

import kotlin.math.roundToInt

/**
 * Map a contact inside the picture view onto negotiated picture pixels.
 *
 * Coordinates outside the view are not part of the picture.
 */
object PictureTouchMap {
    /**
     * Returns the picture pixel, or null when [local] is outside the view.
     */
    fun pixel(local: Float, viewSpan: Int, pictureSpan: Int): Int? {
        if (viewSpan <= 0 || pictureSpan <= 0) {
            return null
        }
        if (local < 0f || local > viewSpan.toFloat()) {
            return null
        }
        val scaled = (local / viewSpan.toFloat()) * pictureSpan.toFloat()
        return scaled.roundToInt().coerceIn(0, pictureSpan)
    }

    /**
     * Returns the picture pixel clamped to the range [0, pictureSpan].
     *
     * Coordinates beyond the view span are clamped rather than dropped,
     * ensuring active drags and edge lifts are reliably delivered.
     */
    fun clampedPixel(local: Float, viewSpan: Int, pictureSpan: Int): Int? {
        if (viewSpan <= 0 || pictureSpan <= 0) {
            return null
        }
        val clamped = local.coerceIn(0f, viewSpan.toFloat())
        val scaled = (clamped / viewSpan.toFloat()) * pictureSpan.toFloat()
        return scaled.roundToInt().coerceIn(0, pictureSpan)
    }

    /**
     * Map a picture pixel back onto the view coordinate span.
     */
    fun viewCoord(picturePixel: Int, viewSpan: Int, pictureSpan: Int): Float {
        if (viewSpan <= 0 || pictureSpan <= 0) {
            return 0f
        }
        return (picturePixel.toFloat() / pictureSpan.toFloat()) * viewSpan.toFloat()
    }
}
