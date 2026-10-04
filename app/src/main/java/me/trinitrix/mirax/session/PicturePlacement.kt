package me.trinitrix.mirax.session

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure placement of the picture rectangle on the panel for a [PictureScale].
 *
 * Uses negotiated picture width and height — not a decoder buffer padded up to
 * a multiple of 16. Views call this and render; they are not a second test seam.
 */
object PicturePlacement {

    /**
     * Placement rectangle in panel coordinates. May extend outside the panel
     * for [PictureScale.CENTER_CROP] and [PictureScale.ACTUAL].
     */
    data class Rect(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Compute where the picture sits on the panel.
     *
     * Args:
     *     pictureWidth: Negotiated picture width in pixels.
     *     pictureHeight: Negotiated picture height in pixels.
     *     panelWidth: Panel width in pixels.
     *     panelHeight: Panel height in pixels.
     *     scale: Chosen [PictureScale].
     *
     * Returns:
     *     A [Rect] in panel coordinates.
     */
    fun place(
        pictureWidth: Int,
        pictureHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        scale: PictureScale,
    ): Rect {
        if (pictureWidth <= 0 || pictureHeight <= 0 || panelWidth <= 0 || panelHeight <= 0) {
            return Rect(0, 0, 0, 0)
        }
        return when (scale) {
            PictureScale.PROPORTIONAL -> scaledCentered(
                pictureWidth,
                pictureHeight,
                panelWidth,
                panelHeight,
                factor = min(
                    panelWidth.toFloat() / pictureWidth.toFloat(),
                    panelHeight.toFloat() / pictureHeight.toFloat(),
                ),
            )
            PictureScale.CENTER_CROP -> scaledCentered(
                pictureWidth,
                pictureHeight,
                panelWidth,
                panelHeight,
                factor = max(
                    panelWidth.toFloat() / pictureWidth.toFloat(),
                    panelHeight.toFloat() / pictureHeight.toFloat(),
                ),
            )
            PictureScale.MATCH_EDGES -> Rect(
                left = 0,
                top = 0,
                width = panelWidth,
                height = panelHeight,
            )
            PictureScale.ACTUAL -> Rect(
                left = 0,
                top = 0,
                width = pictureWidth,
                height = pictureHeight,
            )
        }
    }

    private fun scaledCentered(
        pictureWidth: Int,
        pictureHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        factor: Float,
    ): Rect {
        val width = max(1, (pictureWidth * factor).roundToInt())
        val height = max(1, (pictureHeight * factor).roundToInt())
        return Rect(
            left = (panelWidth - width) / 2,
            top = (panelHeight - height) / 2,
            width = width,
            height = height,
        )
    }
}
