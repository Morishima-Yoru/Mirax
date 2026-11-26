package me.trinitrix.mirax.standbyprobe.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-local bounds for fixed logical probe widths and insets calculation. */
class ProbeLayoutCaseTest {

    @Test
    fun fixedWidths_areSoftwareOnlyBounds() {
        assertThat(ProbeLayoutCase.WIDE_1080.logicalWidthPx).isEqualTo(1080)
        assertThat(ProbeLayoutCase.NARROW_320.logicalWidthPx).isEqualTo(320)
        assertThat(ProbeLayoutCase.FOLD_LOGICAL.logicalWidthPx).isEqualTo(1812)
        assertThat(ProbeLayoutCase.FOLD_LOGICAL.note).contains("not an actual")
        assertThat(ProbeLayoutCase.REFERENCE.logicalWidthPx).isEqualTo(0)
    }

    @Test
    fun resolveEffectiveWidth_fixedCasesIgnoreInsets() {
        assertThat(ProbeLayoutCase.WIDE_1080.resolveEffectiveWidth(viewWidth = 800, insetsHorizontal = 100))
            .isEqualTo(1080)
        assertThat(ProbeLayoutCase.NARROW_320.resolveEffectiveWidth(viewWidth = 200, insetsHorizontal = 50))
            .isEqualTo(320)
        assertThat(ProbeLayoutCase.FOLD_LOGICAL.resolveEffectiveWidth(viewWidth = 1000, insetsHorizontal = 80))
            .isEqualTo(1812)
    }

    @Test
    fun resolveEffectiveWidth_referenceAppliesInsetsAndFallbacks() {
        assertThat(ProbeLayoutCase.REFERENCE.resolveEffectiveWidth(viewWidth = 1000, insetsHorizontal = 120))
            .isEqualTo(880)

        assertThat(ProbeLayoutCase.REFERENCE.resolveEffectiveWidth(viewWidth = 100, insetsHorizontal = 100, defaultFallback = 1080))
            .isEqualTo(1080)
        assertThat(ProbeLayoutCase.REFERENCE.resolveEffectiveWidth(viewWidth = 50, insetsHorizontal = 100, defaultFallback = 720))
            .isEqualTo(720)
        assertThat(ProbeLayoutCase.REFERENCE.resolveEffectiveWidth(viewWidth = 0, insetsHorizontal = 0, defaultFallback = 1080))
            .isEqualTo(1080)

        assertThat(ProbeLayoutCase.REFERENCE.resolveEffectiveWidth(viewWidth = 500, insetsHorizontal = -20))
            .isEqualTo(500)
    }
}
