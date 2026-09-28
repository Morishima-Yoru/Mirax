package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the picture-placement seam (issue #12).
 *
 * Seam under test: [PicturePlacement.place] — given picture size, panel size,
 * and [PictureScale], assert the placement rectangle. Uses negotiated picture
 * axes, not a decoder buffer padded to a multiple of 16.
 */
class PicturePlacementTest {

    @Test
    fun proportional_1812x2176_on_2176x1812_centered_keepsAspect_doesNotFillPanel() {
        val rect = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.PROPORTIONAL,
        )
        assertThat(rect).isEqualTo(PicturePlacement.Rect(left = 333, top = 0, width = 1509, height = 1812))
        assertThat(rect.width).isLessThan(2176)
        assertThat(rect.height).isEqualTo(1812)
        val pictureAspect = 1812.0 / 2176.0
        assertThat(rect.width.toDouble() / rect.height.toDouble()).isWithin(0.002).of(pictureAspect)
    }

    @Test
    fun centerCrop_1812x2176_on_2176x1812_coversPanel_centerAligned() {
        val rect = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.CENTER_CROP,
        )
        assertThat(rect).isEqualTo(PicturePlacement.Rect(left = 0, top = -400, width = 2176, height = 2613))
        assertThat(rect.width).isAtLeast(2176)
        assertThat(rect.height).isAtLeast(1812)
    }

    @Test
    fun matchEdges_equalsPanel() {
        val rect = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.MATCH_EDGES,
        )
        assertThat(rect).isEqualTo(PicturePlacement.Rect(left = 0, top = 0, width = 2176, height = 1812))
    }

    @Test
    fun actual_isPictureSize_topLeftAligned() {
        val rect = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.ACTUAL,
        )
        assertThat(rect).isEqualTo(PicturePlacement.Rect(left = 0, top = 0, width = 1812, height = 2176))
    }

    @Test
    fun placement_usesPictureSize_notDecoderBufferPadding() {
        // Decoder buffer 1824×2176; negotiated picture is 1812×2176.
        val fromPicture = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.PROPORTIONAL,
        )
        val fromPaddedBuffer = PicturePlacement.place(
            pictureWidth = 1824,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.PROPORTIONAL,
        )
        assertThat(fromPicture).isEqualTo(
            PicturePlacement.Rect(left = 333, top = 0, width = 1509, height = 1812),
        )
        assertThat(fromPicture).isNotEqualTo(fromPaddedBuffer)
    }

    @Test
    fun nextPlacement_usesNewlyChosenScale() {
        val rect = PicturePlacement.place(
            pictureWidth = 1812,
            pictureHeight = 2176,
            panelWidth = 2176,
            panelHeight = 1812,
            scale = PictureScale.MATCH_EDGES,
        )
        assertThat(rect).isEqualTo(PicturePlacement.Rect(left = 0, top = 0, width = 2176, height = 1812))
    }
}
