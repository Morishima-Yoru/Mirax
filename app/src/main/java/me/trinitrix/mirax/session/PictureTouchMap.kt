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
}
