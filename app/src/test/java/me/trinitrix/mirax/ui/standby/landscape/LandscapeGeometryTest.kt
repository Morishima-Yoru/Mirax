package me.trinitrix.mirax.ui.standby.landscape

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Cross-checks [LandscapeGeometry] against fixed prototype vectors.
 *
 * Sources: `design/big-standby-prototype.html` and an isolated Chromium 156
 * SVG with its 1600x1000 viewBox, exact moon path and transform. Chromium's
 * getBBox uses floats; its observed bounds differ from the analytic circles
 * by up to 0.00014 viewBox units, so that comparison allows 0.0002.
 */
class LandscapeGeometryTest {

    @Test
    fun slice_usesXMidYMidSlice() {
        // 1080x2400 portrait: scale = max(1080/1600, 2400/1000) = 2.4,
        // offsetX = (1080 - 3840)/2 = -1380, offsetY = 0.
        val slice = LandscapeGeometry.sliceTransformFor(1080.0, 2400.0)
        assertThat(slice.scale).isWithin(1e-9).of(2.4)
        assertThat(slice.offsetX).isWithin(1e-9).of(-1380.0)
        assertThat(slice.offsetY).isWithin(1e-9).of(0.0)
        // Far ridge head maps to the left edge mid-slope.
        assertThat(slice.mapX(0.0)).isWithin(1e-6f).of(-1380.0f)
        assertThat(slice.mapY(496.0)).isWithin(1e-3f).of(496.0f * 2.4f)
    }

    @Test
    fun celestial_browserSampledMoonRadiusSetsLayout() {
        // Chromium SVG: length 173.8923797607422, maximum at sample 22 of 64.
        val radius = LandscapeGeometry.celestialRadius()
        val measuredX = 1071.0935692771454
        val measuredY = 204.93157845664064
        assertThat(radius).isWithin(1e-12).of(hypot(measuredX - 1110.0, measuredY - 204.0))
        val layout = LandscapeGeometry.celestialLayoutFor(1600.0, 1000.0)
        assertThat(layout.celestialX).isWithin(1e-9).of(800.0)
        assertThat(layout.celestialY).isWithin(1e-9).of(235.0)
        assertThat(layout.edgeOffset).isWithin(1e-9).of(854.9175820165277)
        assertThat(layout.horizonY).isWithin(1e-9).of(90.0)
        val portrait = LandscapeGeometry.celestialLayoutFor(1080.0, 2400.0)
        assertThat(portrait.celestialY).isWithin(1e-9).of(295.0)
        assertThat(portrait.edgeOffset).isWithin(1e-9).of(270.58424868319435)
        val wide = LandscapeGeometry.celestialLayoutFor(2560.0, 720.0)
        assertThat(wide.celestialY).isWithin(1e-9).of(342.16758201652766)
        assertThat(wide.edgeOffset).isWithin(1e-9).of(848.9175820165277)
    }

    @Test
    fun moonArcs_matchSvgEndpointsAndBrowserBounds() {
        val arcs = LandscapeGeometry.moonArcs
        assertThat(arcs).hasSize(2)
        val outer = arcs[0]
        val inner = arcs[1]
        assertThat(outer.sweepAngle).isLessThan(-PI)
        assertThat(inner.sweepAngle).isGreaterThan(0.0)
        assertThat(inner.sweepAngle).isLessThan(PI)
        for ((arc, startY, endY) in listOf(Triple(outer, 182.0, 226.0), Triple(inner, 226.0, 182.0))) {
            assertThat(arc.centerX + arc.radius * cos(arc.startAngle)).isWithin(1e-9).of(1125.0)
            assertThat(arc.centerY + arc.radius * sin(arc.startAngle)).isWithin(1e-9).of(startY)
            assertThat(arc.centerX + arc.radius * cos(arc.startAngle + arc.sweepAngle))
                .isWithin(1e-9).of(1125.0)
            assertThat(arc.centerY + arc.radius * sin(arc.startAngle + arc.sweepAngle))
                .isWithin(1e-9).of(endY)
        }
        // Chromium getBBox on each untransformed SVG arc.
        assertThat(outer.centerX - outer.radius).isWithin(0.0002).of(1082.34765625)
        assertThat(outer.centerY - outer.radius).isWithin(0.00002).of(177.0)
        assertThat(inner.centerX - inner.radius).isWithin(0.0002).of(1110.591552734375)
    }

    @Test
    fun orbits_followCssDayNightTracks() {
        val layout = LandscapeGeometry.CelestialLayout(800.0, 259.0, 854.916, 90.0)
        val day = LandscapeGeometry.orbitPositions(1.0, layout, 259.0)
        assertThat(day.sunX).isWithin(1e-9).of(800.0)
        assertThat(day.sunY).isWithin(1e-9).of(259.0)
        assertThat(day.sunAlpha).isWithin(1e-9).of(1.0)
        assertThat(day.moonX).isWithin(1e-9).of(1654.92)
        assertThat(day.moonY).isWithin(1e-9).of(349.0)
        assertThat(day.moonAlpha).isWithin(1e-9).of(0.0)
        val night = LandscapeGeometry.orbitPositions(0.0, layout, 259.0)
        assertThat(night.sunX).isWithin(1e-9).of(-54.92)
        assertThat(night.moonX).isWithin(1e-9).of(800.0)
        val wide = LandscapeGeometry.celestialLayoutFor(2560.0, 720.0)
        val wideNight = LandscapeGeometry.orbitPositions(0.0, wide, wide.celestialY)
        assertThat(wideNight.moonY).isWithin(1e-9).of(342.17)
        assertThat(wideNight.sunX).isWithin(1e-9).of(-48.92)
        // JavaScript (2.675).toFixed(2) is "2.67", not 2.68.
        val tie = LandscapeGeometry.CelestialLayout(800.0, 204.0, 2.675, 0.0)
        assertThat(LandscapeGeometry.orbitPositions(1.0, tie, 204.0).moonX).isWithin(1e-9).of(802.67)
    }

    @Test
    fun clamp_keepsFeasibleCenterAndRejectsUnknown() {
        // 1600x1000, brand bottom 100px, primary top 700px, sampled radius 38.92:
        // min = 100 + 38.92 + 8 = 146.92, max = 700 - 38.92 - 8 = 653.08.
        val radius = LandscapeGeometry.celestialRadius()
        assertThat(
            LandscapeGeometry.clampCelestialY(259.0, 100f, 700f, 1600.0, 1000.0),
        ).isWithin(1e-9).of(259.0)
        assertThat(
            LandscapeGeometry.clampCelestialY(100.0, 100f, 700f, 1600.0, 1000.0),
        ).isWithin(1e-6).of(100.0 + radius + 8.0)
        assertThat(
            LandscapeGeometry.clampCelestialY(259.0, null, 700f, 1600.0, 1000.0),
        ).isWithin(1e-9).of(259.0)
        // Infeasible window (brand below primary) keeps the input.
        assertThat(
            LandscapeGeometry.clampCelestialY(259.0, 700f, 100f, 1600.0, 1000.0),
        ).isWithin(1e-9).of(259.0)
    }

    @Test
    fun ambientClocks_matchPrototypeVectors() {
        // t = 0: pan (0, 18), mist (0, 12).
        val pan = LandscapeGeometry.panOffset(0.0)
        assertThat(pan.first).isWithin(1e-9).of(0.0)
        assertThat(pan.second).isWithin(1e-9).of(18.0)
        val mist = LandscapeGeometry.mistOffset(0.0)
        assertThat(mist.first).isWithin(1e-9).of(0.0)
        assertThat(mist.second).isWithin(1e-9).of(12.0)
        // Chromium prototype toFixed(1) at t = 1 second.
        assertThat(LandscapeGeometry.panOffset(1.0).first).isWithin(1e-9).of(14.1)
        assertThat(LandscapeGeometry.panOffset(1.0).second).isWithin(1e-9).of(17.2)
        assertThat(LandscapeGeometry.mistOffset(1.0).first).isWithin(1e-9).of(62.7)
        assertThat(LandscapeGeometry.mistOffset(1.0).second).isWithin(1e-9).of(11.3)
        // t = PI/2 / 0.38: sin = 1 so panX hits +38.
        val peak = LandscapeGeometry.panOffset(PI / 2.0 / 0.38)
        assertThat(peak.first).isWithin(1e-9).of(38.0)
    }

    @Test
    fun ease_matchesCssEaseInOutReference() {
        // CSS `ease-in-out` reference points (cubic-bezier(0.42, 0, 0.58, 1)).
        assertThat(LandscapeGeometry.easeInOut(0.0)).isWithin(1e-9).of(0.0)
        assertThat(LandscapeGeometry.easeInOut(0.5)).isWithin(1e-9).of(0.5)
        assertThat(LandscapeGeometry.easeInOut(0.25)).isWithin(1e-4).of(0.1292)
        assertThat(LandscapeGeometry.easeInOut(0.75)).isWithin(1e-4).of(0.8708)
        assertThat(LandscapeGeometry.easeInOut(1.0)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun transition_shortensReversalLikeChromiumCss() {
        assertThat(LandscapeGeometry.transitionBlend(0.0, 1.0, 1.8)).isWithin(1e-9).of(0.5)
        assertThat(LandscapeGeometry.transitionBlend(0.0, 1.0, 3.6)).isWithin(1e-9).of(1.0)
        // Chromium 156: reverse at 900ms, duration 464.98295177023675ms.
        val before = LandscapeGeometry.transitionBlend(0.0, 1.0, 0.9)
        val duration = LandscapeGeometry.transitionDuration(before, 0.0)
        assertThat(before).isWithin(1e-6).of(0.129162)
        assertThat(duration).isWithin(1e-6).of(0.46498295177023675)
        assertThat(LandscapeGeometry.transitionBlend(before, 0.0, 0.0, duration)).isWithin(1e-9).of(before)
        val midpoint = LandscapeGeometry.transitionBlend(before, 0.0, duration / 2.0, duration)
        assertThat(midpoint).isWithin(1e-6).of(0.064581)
        assertThat(LandscapeGeometry.transitionDuration(midpoint, 1.0)).isWithin(1e-6).of(3.3675085241148818)
        assertThat(LandscapeGeometry.transitionBlend(before, 0.0, duration, duration)).isWithin(1e-9).of(0.0)
        assertThat(LandscapeGeometry.broadcastTarget(true, true)).isWithin(1e-9).of(1.0)
        assertThat(LandscapeGeometry.broadcastTarget(true, false)).isWithin(1e-9).of(1.0)
        assertThat(LandscapeGeometry.broadcastTarget(false, true)).isWithin(1e-9).of(0.0)
        assertThat(LandscapeGeometry.blendChannel(0x07, 0x26, 0.5)).isEqualTo(22)
    }

    @Test
    fun ambientDelta_clampsAtEightyMilliseconds() {
        assertThat(LandscapeGeometry.ambientDeltaSeconds(16.0)).isWithin(1e-9).of(0.016)
        assertThat(LandscapeGeometry.ambientDeltaSeconds(200.0)).isWithin(1e-9).of(0.080)
        assertThat(LandscapeGeometry.ambientDeltaSeconds(-1.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun layoutChange_snapsOnlyOrbitWhilePaletteContinues() {
        val state = LandscapeMotionState()
        state.updateLayout(1600.0, 1000.0, null, null)
        state.setTarget(1.0, 1000L, animating = true)
        state.advance(2800L)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.5)

        state.updateLayout(1600.0, 1000.0, null, null)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.5)
        state.updateLayout(1601.0, 1000.0, null, null)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)
        state.advance(3700L)
        assertThat(state.paletteBlend).isWithin(1e-6).of(0.870838)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
    }

    @Test
    fun viewPixelScaleChange_resetsWhenOnlyEdgeOffsetChanges() {
        val state = LandscapeMotionState()
        val before = checkNotNull(state.updateLayout(1600.0, 1000.0, null, null))
        state.setTarget(1.0, 0L, animating = true)
        state.advance(1800L)
        val after = checkNotNull(state.updateLayout(3200.0, 2000.0, null, null))
        assertThat(after.celestialY).isWithin(1e-9).of(before.celestialY)
        assertThat(after.horizonY).isWithin(1e-9).of(before.horizonY)
        assertThat(before.edgeOffset - after.edgeOffset).isWithin(1e-9).of(8.0)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)
    }

    @Test
    fun layoutReset_thenToggleUsesEachTracksCurrentValue() {
        val state = LandscapeMotionState()
        state.updateLayout(1600.0, 1000.0, null, null)
        state.setTarget(1.0, 0L, animating = true)
        state.advance(1800L)
        state.updateLayout(1601.0, 1000.0, null, null)
        state.setTarget(0.0, 1800L, animating = true)
        state.advance(2700L)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.25)
        assertThat(state.orbitBlend).isWithin(1e-6).of(0.870838)
        state.advance(3600L)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.0)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.5)
    }

    @Test
    fun hostClamp_resetsOnlyWhenFormattedPlacementChanges() {
        val state = LandscapeMotionState()
        state.updateLayout(1600.0, 1000.0, 0f, 700f)
        state.setTarget(1.0, 0L, animating = true)
        state.advance(1800L)
        state.updateLayout(1600.0, 1000.0, 1f, 699f)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.5)
        state.updateLayout(1600.0, 1000.0, 200f, 700f)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)

        state.setTarget(0.0, 1800L, animating = true)
        state.advance(2700L)
        state.updateLayout(1600.0, 1000.0, 200.001f, 700f)
        assertThat(state.orbitBlend).isWithin(1e-6).of(0.870838)
        state.updateLayout(1600.0, 1000.0, 200.01f, 700f)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.0)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.25)
    }

    @Test
    fun layoutKey_usesViewPixelsAndPrototypeSignedZeroRounding() {
        val scaled = LandscapeMotionState()
        val placement = checkNotNull(scaled.updateLayout(1080.0, 2400.0, 630f, 2200f))
        assertThat(placement.celestialY).isWithin(1e-6).of(304.750915349861)
        scaled.setTarget(1.0, 0L, animating = true)
        scaled.advance(1800L)
        scaled.updateLayout(1080.0, 2400.0, 630.005f, 2200f)
        assertThat(scaled.orbitBlend).isWithin(1e-9).of(0.5)
        scaled.updateLayout(1080.0, 2400.0, 630.02f, 2200f)
        assertThat(scaled.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(scaled.paletteBlend).isWithin(1e-9).of(0.5)

        val signedZero = LandscapeMotionState()
        signedZero.updateLayout(1600.0, 1000.0, 0f, 250.91658f)
        signedZero.setTarget(1.0, 0L, animating = true)
        signedZero.advance(1800L)
        signedZero.updateLayout(1600.0, 1000.0, 0f, 250.91858f)
        assertThat(signedZero.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(signedZero.paletteBlend).isWithin(1e-9).of(0.5)
    }

    @Test
    fun initialLayout_snapsOrbitWithoutStoppingPalette() {
        val state = LandscapeMotionState()
        state.setTarget(1.0, 0L, animating = true)
        state.advance(900L)
        assertThat(state.orbitBlend).isWithin(1e-6).of(0.129162)
        state.updateLayout(1600.0, 1000.0, null, null)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(state.paletteBlend).isWithin(1e-6).of(0.129162)
        state.advance(1800L)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)
    }

    @Test
    fun stoppedMotion_snapsBothTracksAndCanRestart() {
        val state = LandscapeMotionState()
        state.updateLayout(1600.0, 1000.0, null, null)
        state.setTarget(1.0, 0L, animating = true)
        state.advance(900L)
        state.stop()
        assertThat(state.paletteBlend).isWithin(1e-9).of(1.0)
        assertThat(state.orbitBlend).isWithin(1e-9).of(1.0)
        assertThat(state.updateLayout(0.0, 0.0, null, null)).isNull()
        state.setTarget(0.0, 5000L, animating = false)
        state.updateLayout(1601.0, 1000.0, null, null)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.0)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.0)
        state.setTarget(1.0, 6000L, animating = true)
        state.advance(7800L)
        assertThat(state.paletteBlend).isWithin(1e-9).of(0.5)
        assertThat(state.orbitBlend).isWithin(1e-9).of(0.5)
    }
}
