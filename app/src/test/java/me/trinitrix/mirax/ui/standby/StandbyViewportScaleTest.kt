package me.trinitrix.mirax.ui.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the area-normalized viewport in density-independent units. */
class StandbyViewportScaleTest {
    @Test
    fun referenceViewport_resolvesPrototypeUnitsToOne() {
        val reference = StandbyViewportScale(390, 844, density = 1f)
        assertEquals(1f, reference.scale, 0.0001f)
        assertEquals(96f, reference.px(96f), 0.0001f)
        assertEquals(390f, reference.logicalWidth, 0.001f)
        assertEquals(844f, reference.logicalHeight, 0.001f)
    }

    @Test
    fun sameLogicalSize_scalesIdenticallyAcrossDensities() {
        val mdpi = StandbyViewportScale(390, 844, density = 1f)
        val xxhdpi = StandbyViewportScale(1170, 2532, density = 3f)
        assertEquals(mdpi.scale, xxhdpi.scale, 0.0001f)
        assertEquals(mdpi.logicalWidth, xxhdpi.logicalWidth, 0.001f)
        assertEquals(mdpi.logicalHeight, xxhdpi.logicalHeight, 0.001f)
        assertEquals(96f * 3f, xxhdpi.px(96f), 0.001f)
        assertEquals(mdpi.compact, xxhdpi.compact)
    }

    @Test
    fun realDevices_keepPrototypeScaleCompact() {
        val phone = StandbyViewportScale(1080, 2340, density = 2.75f)
        assertTrue("phone scale ${phone.scale}", phone.scale in 0.95f..1.1f)
        assertFalse(phone.compact)

        val foldable = StandbyViewportScale(1768, 2208, density = 2.625f)
        assertTrue("foldable scale ${foldable.scale}", foldable.scale < 1.4f)
    }

    @Test
    fun sameAspect_scalesAreaLinearly() {
        val small = StandbyViewportScale(640, 360)
        val medium = StandbyViewportScale(1280, 720)
        val large = StandbyViewportScale(3840, 2160)
        assertEquals(small.scale * 2f, medium.scale, 0.0001f)
        assertEquals(small.scale * 6f, large.scale, 0.0001f)
        assertEquals(small.logicalWidth * 2f, medium.logicalWidth, 0.001f)
        assertEquals(small.logicalWidth * 6f, large.logicalWidth, 0.001f)
        assertEquals(small.logicalHeight * 6f, large.logicalHeight, 0.001f)
        assertEquals(small.px(96f) * 6f, large.px(96f), 0.001f)
    }

    @Test
    fun referenceCases_selectResponsiveBranches() {
        assertFalse(StandbyViewportScale(390, 844, density = 1f).compact)
        assertFalse(StandbyViewportScale(780, 1688, density = 2f).compact)
        assertTrue(StandbyViewportScale(720, 1280, density = 2f).compact)
        assertFalse(StandbyViewportScale(2880, 2000, density = 2f).compact)
        assertTrue(StandbyViewportScale(1792, 1184, density = 2f).compact)
        assertTrue(StandbyViewportScale(1280, 720, density = 2f).shortLandscape)
        assertTrue(StandbyViewportScale(1600, 640, density = 2f).veryShortLandscape)
        assertTrue(StandbyViewportScale(390, 844, density = 1f).narrow)
        assertFalse(StandbyViewportScale(2880, 2000, density = 2f).narrow)
    }

    @Test
    fun cssPixels_mapOneToDensity_withoutAreaEnlargement() {
        val css = StandbyViewportScale(800, 1280, density = 1.5f, cssPixels = true)
        assertEquals(1f, css.scale, 0f)
        assertEquals(60f, css.px(40f), 0f)
        assertEquals(144f, css.px(96f), 0f)
        assertEquals(126f, css.px(84f), 0f)
        assertEquals(533.3333f, css.logicalWidth, 0.001f)
        assertEquals(853.3333f, css.logicalHeight, 0.001f)
        assertFalse(css.compact)
        assertTrue(css.narrow)
    }

    @Test
    fun defaultMapping_preservesMatterAreaUnits() {
        val legacy = StandbyViewportScale(800, 1280, density = 1.5f)
        assertFalse(legacy.cssPixels)
        assertEquals(1.1758589f, legacy.scale, 0.0001f)
        assertEquals(70.55153f, legacy.px(40f), 0.001f)
    }

    @Test
    fun cssBreakpoints_useLogicalHeight_notAspectOrPhysicalPixels() {
        val tallWide = StandbyViewportScale(2400, 1300, density = 1.5f, cssPixels = true)
        assertFalse(tallWide.compact)
        val shortSquare = StandbyViewportScale(1200, 1200, density = 1.5f, cssPixels = true)
        assertTrue(shortSquare.compact)
        val channelOnly = StandbyViewportScale(1500, 750, density = 1.5f, cssPixels = true)
        assertTrue(channelOnly.shortChannelLandscape)
        assertFalse(channelOnly.shortLandscape)
        assertFalse(StandbyViewportScale(1500, 751, 1.5f, true).shortChannelLandscape)
        assertTrue(StandbyViewportScale(1500, 660, 1.5f, true).shortLandscape)
        assertFalse(StandbyViewportScale(750, 1500, 1.5f, true).shortChannelLandscape)
    }

    @Test
    fun shortAreaLandscape_crossMultipliesInclusiveBoundaryAcrossDensities() {
        // Exact equality: 4840 * (390 * 844) == 8229 * 440^2, so H / (s * d) == 440.
        assertTrue(StandbyViewportScale(8229, 4840, density = 1f).shortAreaLandscape)
        assertTrue(StandbyViewportScale(8229, 4840, density = 2f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(8229, 4841, density = 1f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(8228, 4840, density = 1f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(4840, 8229, density = 1f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(8229, 4840, density = 1f, cssPixels = true).shortAreaLandscape)
        assertTrue(StandbyViewportScale(800, 450, density = 1f).shortAreaLandscape)
        assertTrue(StandbyViewportScale(800, 450, density = 2f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(896, 592, density = 1f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(896, 592, density = 2f).shortAreaLandscape)
        assertFalse(StandbyViewportScale(800, 450, density = 1f, cssPixels = true).shortLandscape)
        assertTrue(StandbyViewportScale(896, 592, density = 2f, cssPixels = true).shortLandscape)
    }

    @Test
    fun matterTextScale_dampsSquareStages_preservesTallWideAndRotations() {
        fun v(w: Int, h: Int, d: Float) = StandbyViewportScale(w, h, density = d)
        // Reported display dims as representative fixture (not actual stage): ratio ~1.2009, AREA ~1.3185.
        val fold5 = v(1812, 2176, 2.625f)
        assertEquals(2176f / 1812f, fold5.logicalHeight / fold5.logicalWidth, 0.0001f)
        assertEquals(1.3185f, fold5.scale, 0.001f)
        assertEquals(1f, fold5.matterTextScale, 0.0001f)
        // ~5:6 square portrait fixture damped to reference; dpi equivalence holds.
        val nearSquare = v(1000, 1200, 1f)
        assertEquals(1.9094f, nearSquare.scale, 0.001f)
        assertEquals(1f, nearSquare.matterTextScale, 0.0001f)
        val nearSquareHdpi = v(2000, 2400, 2f)
        assertEquals(nearSquare.scale, nearSquareHdpi.scale, 0.0001f)
        assertEquals(nearSquare.matterTextScale, nearSquareHdpi.matterTextScale, 0.0001f)
        // Cross-orientation: rotated square fixture matches portrait/landscape (no abrupt jump).
        val rotated = v(1200, 1000, 1f)
        assertEquals(nearSquare.matterTextScale, rotated.matterTextScale, 0.0001f)
        // The fade starts at the cap, interpolates halfway at the midpoint, and ends at AREA.
        val full = v(1000, 1250, 1f)
        assertEquals(1f, full.matterTextScale, 0.0001f)
        val gone = v(1000, 1400, 1f)
        assertEquals(gone.scale, gone.matterTextScale, 0.0001f)
        val mid = v(1000, 1325, 1f)
        assertEquals((1f + mid.scale) / 2f, mid.matterTextScale, 0.0001f)
        val square = v(1200, 1200, 1f)
        assertEquals(1f, square.matterTextScale, 0.0001f)
        // Ordinary tall portrait + wide/ratio-check landscape keep exact AREA text.
        assertEquals(1f, v(390, 844, 1f).matterTextScale, 0.0001f)
        val tall = v(1080, 2340, 2.75f)
        assertEquals(tall.scale, tall.matterTextScale, 0f)
        val wide = v(1280, 720, 2f)
        assertEquals(wide.scale, wide.matterTextScale, 0f)
        val landscapeRatio = v(1792, 1184, 2f)
        assertEquals(landscapeRatio.scale, landscapeRatio.matterTextScale, 0f)
        // Larger square window: still bounded at reference, never above AREA.
        val largeSquare = v(2880, 2880, 2f)
        assertTrue(largeSquare.matterTextScale <= largeSquare.scale)
        assertEquals(1f, largeSquare.matterTextScale, 0.0001f)
    }

    @Test
    fun emptyOrDegenerateViewport_isSafe() {
        val empty = StandbyViewportScale(0, 0)
        assertFalse(empty.isValid)
        assertEquals(0f, empty.logicalWidth, 0f)
        assertEquals(0f, empty.logicalHeight, 0f)
        assertTrue(empty.scale.isFinite())
        assertTrue(empty.px(96f).isFinite())

        val noDensity = StandbyViewportScale(390, 844, density = 0f)
        assertFalse(noDensity.isValid)
        assertEquals(0f, noDensity.logicalWidth, 0f)
        assertTrue(noDensity.scale.isFinite())
    }
}
