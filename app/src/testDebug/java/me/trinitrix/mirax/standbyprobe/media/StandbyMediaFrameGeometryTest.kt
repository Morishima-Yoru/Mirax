package me.trinitrix.mirax.standbyprobe.media

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM table checks for probe aspect-fill geometry.
 */
class StandbyMediaFrameGeometryTest {

    @Test
    fun squareInWideViewport_fillsHeightAndCenters() {
        val frame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f
        )
        // Scale covers: max(2, 1) = 2.
        assertThat(frame.scale).isWithin(0.001f).of(2f)
        assertThat(frame.scaledWidth).isWithin(0.001f).of(200f)
        assertThat(frame.scaledHeight).isWithin(0.001f).of(200f)
        assertThat(frame.offsetX).isWithin(0.001f).of(0f)
        assertThat(frame.offsetY).isWithin(0.001f).of(-50f)
    }

    @Test
    fun tallContentInWideViewport_noGapNoStretch() {
        val frame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 200f
        )
        // Uniform scale keeps aspect; cover means both dims meet or exceed.
        assertThat(frame.scaledWidth).isAtLeast(200f)
        assertThat(frame.scaledHeight).isAtLeast(100f)
        // Centered when no pan requested.
        assertThat(frame.offsetX).isWithin(0.001f).of((200f - frame.scaledWidth) / 2f)
        assertThat(frame.offsetY).isWithin(0.001f).of((100f - frame.scaledHeight) / 2f)
    }

    @Test
    fun largePan_clampedToKeepCover() {
        val bottomEdgeFrame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f,
            panX = 1000f,
            panY = -1000f
        )
        // Scaled 200x200; max pan = (200-200)/2=0 on X, (200-100)/2=50 on Y.
        // Center Y is -50, panY -1000 clamped to -50 -> offsetY = -100 (bottom edge covers viewport 100).
        assertThat(bottomEdgeFrame.offsetX).isWithin(0.001f).of(0f)
        assertThat(bottomEdgeFrame.offsetY).isWithin(0.001f).of(-100f)
        assertThat(bottomEdgeFrame.offsetY + bottomEdgeFrame.scaledHeight).isAtLeast(100f)

        // Opposite edge: panY +1000 clamped to +50 -> offsetY = 0 (top edge covers viewport 0).
        val topEdgeFrame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f,
            panX = -1000f,
            panY = 1000f
        )
        assertThat(topEdgeFrame.offsetX).isWithin(0.001f).of(0f)
        assertThat(topEdgeFrame.offsetY).isWithin(0.001f).of(0f)
        assertThat(topEdgeFrame.offsetY).isAtMost(0f)
    }

    @Test
    fun smallPanInsideCover_applied() {
        val frame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f,
            panX = 0f,
            panY = 20f
        )
        assertThat(frame.offsetY).isWithin(0.001f).of(-30f)
    }

    @Test
    fun zoom_scalesUniformly_andClampsSubOneToKeepCover() {
        val frame = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f,
            zoom = 2f
        )
        assertThat(frame.scale).isWithin(0.001f).of(4f)
        assertThat(frame.scaledWidth).isWithin(0.001f).of(400f)
        assertThat(frame.scaledHeight).isWithin(0.001f).of(400f)

        // Sub-1 zoom clamped to 1.0f to prevent uncovered gap.
        val subOne = StandbyMediaFrameGeometry.layout(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f,
            zoom = 0.5f
        )
        assertThat(subOne.scale).isWithin(0.001f).of(2f)
        assertThat(subOne.scaledWidth).isAtLeast(200f)
        assertThat(subOne.scaledHeight).isAtLeast(100f)
    }

    @Test
    fun computeTransform_matchesAspectFillParameters() {
        val transform = StandbyMediaFrameGeometry.computeTransform(
            viewportWidth = 200f,
            viewportHeight = 100f,
            contentWidth = 100f,
            contentHeight = 100f
        )
        // Scaled width 200 on viewport 200 -> factor 1.0; height 200 on viewport 100 -> factor 2.0.
        assertThat(transform.scaleX).isWithin(0.001f).of(1.0f)
        assertThat(transform.scaleY).isWithin(0.001f).of(2.0f)
        assertThat(transform.translateX).isWithin(0.001f).of(0f)
        assertThat(transform.translateY).isWithin(0.001f).of(-50f)
    }

    @Test
    fun invalidInputs_rejectedAtBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(0f, 100f, 100f, 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, -1f, 100f, 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, 100f, Float.NaN, 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, 100f, 100f, Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, 100f, 100f, 100f, zoom = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, 100f, 100f, 100f, zoom = Float.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandbyMediaFrameGeometry.layout(100f, 100f, 100f, 100f, panX = Float.NaN)
        }
    }
}
