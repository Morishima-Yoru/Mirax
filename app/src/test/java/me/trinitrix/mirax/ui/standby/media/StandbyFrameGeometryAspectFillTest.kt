package me.trinitrix.mirax.ui.standby.media

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.data.standby.StandbyFraming
import org.junit.Test

/**
 * Aspect-fill regression for sub-unity zoom in production geometry.
 *
 * Sub-unity zoom is valid persisted data, so geometry clamps it to unity
 * fill instead of stretching. Calls the real [StandbyFrameGeometry].
 */
class StandbyFrameGeometryAspectFillTest {

    @Test
    fun subUnityZoomKeepsFixedSquareCrop() {
        val window = StandbyFrameGeometry.computeSrc(
            1600f,
            900f,
            100f,
            100f,
            StandbyFraming(0.5f, 0.5f, 0.5f),
        )
        assertThat(window).isNotNull()
        // Fixed vector: 900x900 window centred horizontally, full height.
        assertThat(window!!.left).isWithin(0.001f).of(350f)
        assertThat(window.top).isWithin(0.001f).of(0f)
        assertThat(window.right).isWithin(0.001f).of(1250f)
        assertThat(window.bottom).isWithin(0.001f).of(900f)
    }

    @Test
    fun tinyZoomMatchesUnity() {
        val tiny = StandbyFrameGeometry.computeSrc(
            1600f,
            900f,
            100f,
            100f,
            StandbyFraming(0.5f, 0.5f, 0.1f),
        )
        val unity = StandbyFrameGeometry.computeSrc(
            1600f,
            900f,
            100f,
            100f,
            StandbyFraming(0.5f, 0.5f, 1f),
        )
        assertThat(tiny).isNotNull()
        assertThat(unity).isNotNull()
        assertThat(tiny!!.left).isWithin(0.001f).of(unity!!.left)
        assertThat(tiny.top).isWithin(0.001f).of(unity.top)
        assertThat(tiny.right).isWithin(0.001f).of(unity.right)
        assertThat(tiny.bottom).isWithin(0.001f).of(unity.bottom)
    }

    @Test
    fun posturesKeepAspectInsideBounds() {
        val views = listOf(
            1080f to 1920f,
            1920f to 1080f,
            1768f to 2208f,
        )
        for ((viewW, viewH) in views) {
            val window = StandbyFrameGeometry.computeSrc(
                1920f,
                1080f,
                viewW,
                viewH,
                StandbyFraming(0.5f, 0.5f, 0.5f),
            )
            assertThat(window).isNotNull()
            val srcW = window!!.right - window.left
            val srcH = window.bottom - window.top
            assertThat(srcW / srcH).isWithin(0.001f).of(viewW / viewH)
            assertThat(window.left).isAtLeast(0f)
            assertThat(window.top).isAtLeast(0f)
            assertThat(window.right).isAtMost(1920f)
            assertThat(window.bottom).isAtMost(1080f)
            // Centre priority: window centre matches content centre.
            assertThat((window.left + window.right) / 2f).isWithin(1f).of(960f)
            assertThat((window.top + window.bottom) / 2f).isWithin(1f).of(540f)
        }
    }

    @Test
    fun normalZoomAboveUnityUnchanged() {
        val window = StandbyFrameGeometry.computeSrc(
            1600f,
            900f,
            100f,
            100f,
            StandbyFraming(0.5f, 0.5f, 2f),
        )
        assertThat(window).isNotNull()
        assertThat(window!!.left).isWithin(0.001f).of(575f)
        assertThat(window.top).isWithin(0.001f).of(225f)
        assertThat(window.right).isWithin(0.001f).of(1025f)
        assertThat(window.bottom).isWithin(0.001f).of(675f)
    }

    @Test
    fun invalidViewDrawsNothing() {
        val framing = StandbyFraming(0.5f, 0.5f, 0.5f)
        assertThat(StandbyFrameGeometry.computeSrc(1600f, 900f, 0f, 100f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1600f, 900f, -1f, 100f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1600f, 900f, 100f, 0f, framing)).isNull()
    }
}
