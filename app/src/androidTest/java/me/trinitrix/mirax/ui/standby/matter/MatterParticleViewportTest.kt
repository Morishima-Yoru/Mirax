package me.trinitrix.mirax.ui.standby.matter

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the actual scene's particle projection before any display capture. */
@RunWith(AndroidJUnit4::class)
class MatterParticleViewportTest {
    private data class Circle(val x: Float, val y: Float, val radius: Float)

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val circles = mutableListOf<Circle>()
        var offsetX = 0f
        var offsetY = 0f

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            if (circles.size < 8) {
                val matrix = Matrix()
                getMatrix(matrix)
                val values = FloatArray(9)
                matrix.getValues(values)
                offsetX = values[Matrix.MTRANS_X]
                offsetY = values[Matrix.MTRANS_Y]
                circles.add(Circle(cx, cy, radius))
            }
            super.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun context(densityDpi: Int): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
        })
    }

    @Test
    fun nativeView_scalesSamplesButKeepsHostOffsetsInPhysicalPixels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var reference: List<Circle>? = null
            for ((width, height, factor) in listOf(
                Triple(640, 360, 1f), Triple(1280, 720, 2f), Triple(3840, 2160, 6f),
            )) {
                for (density in listOf(160, 320, 480)) {
                    val view = MatterParticleView(context(density))
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, width, height)
                    view.setParticleLayerOffset(10f, 20f)
                    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = RecordingCanvas(bitmap)
                        view.draw(canvas)
                        assertEquals(8, canvas.circles.size)
                        assertEquals(10f, canvas.offsetX, 0.001f)
                        assertEquals(20f, canvas.offsetY, 0.001f)
                        val expected = reference
                        if (expected == null) {
                            reference = canvas.circles.toList()
                        } else {
                            for (index in expected.indices) {
                                assertEquals(expected[index].x * factor, canvas.circles[index].x, 0.01f)
                                assertEquals(expected[index].y * factor, canvas.circles[index].y, 0.01f)
                                assertEquals(expected[index].radius * factor, canvas.circles[index].radius, 0.01f)
                            }
                        }
                        assertTrue(view.particleCount > 0)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }
}
