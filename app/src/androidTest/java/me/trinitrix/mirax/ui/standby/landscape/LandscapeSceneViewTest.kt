package me.trinitrix.mirax.ui.standby.landscape

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.trinitrix.mirax.WindowEvidence
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Isolated background-only mount checks for [LandscapeSceneView].
 *
 * Mounts the candidate in the existing debug [StandbyProbeActivity] host and
 * verifies the attached native draw plus the hidden/projecting pause, and that
 * broadcast intent (not frozen) owns the day/night target. Frame cadence is
 * observed with a bounded latch; no pass threshold is invented. Parent owns
 * build, install, execution, and review. This file is written but never
 * executed here.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeSceneViewTest {

    /** Verifies the actual paints and bitmap output at night, mid-transition, and day. */
    @Test
    fun mistAndForeground_useShapeBoundsAndOpaqueShaderPaint() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = LandscapeSceneView(InstrumentationRegistry.getInstrumentation().targetContext)
            val paintField = LandscapeSceneView::class.java.getDeclaredField("fillPaint")
            paintField.isAccessible = true
            val sharedPaint = paintField.get(view) as Paint
            val slice = LandscapeGeometry.sliceTransformFor(1600.0, 1000.0)
            for ((blend, peakAlpha, mistRgb) in listOf(
                Triple(0.0, 41, 0x849B9B),
                Triple(0.5, 86, 0xA2B1AA),
                Triple(1.0, 132, 0xC1C7BA),
            )) {
                val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
                val canvas = GradientRecordingCanvas(bitmap)
                sharedPaint.alpha = 37
                view.drawMist(canvas, slice, blend)
                val pathPaint = canvas.pathPaints.single()
                val ovalPaint = requireNotNull(canvas.ovalPaint)
                assertMistPathBounds(requireNotNull(canvas.firstPath))
                assertEquals(255, pathPaint.alpha)
                assertEquals(255, ovalPaint.alpha)
                assertEquals(RectF(280f, 586f, 1700f, 714f), canvas.ovalBounds)

                // SVG getBBox in isolated Chromium: y=471.462158, height=240.272705.
                assertAlphaNear(0, sampleGradientAlpha(pathPaint, 471), 2)
                assertAlphaNear(peakAlpha, sampleGradientAlpha(pathPaint, 591), 2)
                assertRgbNear(mistRgb, sampleGradientColor(pathPaint, 591), 4)
                assertAlphaNear(0, sampleGradientAlpha(pathPaint, 712), 2)
                assertAlphaNear((peakAlpha * (540.5 - 471.462158) / 120.136352).toInt(),
                    sampleGradientAlpha(pathPaint, 540), 2)
                assertAlphaNear(0, sampleGradientAlpha(ovalPaint, 585), 2)
                assertAlphaNear(peakAlpha, sampleGradientAlpha(ovalPaint, 649), 2)
                assertAlphaNear(0, sampleGradientAlpha(ovalPaint, 714), 2)
                assertAlphaNear(sampleGradientAlpha(pathPaint, 540), Color.alpha(bitmap.getPixel(100, 540)), 2)
                val pathAlpha = sampleGradientAlpha(pathPaint, 650)
                val ovalAlpha = sampleGradientAlpha(ovalPaint, 650)
                val combined = ovalAlpha + pathAlpha * (255 - ovalAlpha) / 255
                assertAlphaNear(combined, Color.alpha(bitmap.getPixel(990, 650)), 3)

                sharedPaint.alpha = 37
                view.drawForeground(canvas, slice, blend)
                val foregroundPaint = canvas.pathPaints[1]
                assertEquals(255, foregroundPaint.alpha)
                assertEquals(255, sampleGradientAlpha(foregroundPaint, 626))
                assertEquals(255, sampleGradientAlpha(foregroundPaint, 999))
                val expectedTop = when (blend) {
                    0.0 -> 0x101E23
                    0.5 -> 0x192C2C
                    else -> 0x233B36
                }
                val expectedBottom = when (blend) {
                    0.0 -> 0x050D10
                    0.5 -> 0x071414
                    else -> 0x0A1B18
                }
                assertRgbNear(expectedTop, sampleGradientColor(foregroundPaint, 626), 2)
                assertRgbNear(expectedBottom, sampleGradientColor(foregroundPaint, 999), 2)
                assertEquals(255, Color.alpha(bitmap.getPixel(500, 700)))
                bitmap.recycle()
            }
        }
    }

    /** Attached view draws the landscape background, then stops while projecting. */
    @Test
    fun attachedBackground_rendersSceneThenStopsWhenProjecting() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = LandscapeSceneView(activity)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                assertTrue("No frames observed while visible.", awaitFrames(scenario, 2))
                var elapsedBefore = -1.0
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    val evidence = view.drawEvidence
                    assertTrue("No attached draw observed; evidence unavailable.", evidence.observed)
                    assertTrue("Draw count must exceed zero.", evidence.drawCount > 0)
                    assertTrue(
                        "Active elapsed time must advance while visible, got ${evidence.activeElapsedSeconds}.",
                        evidence.activeElapsedSeconds > 0.0,
                    )
                    elapsedBefore = evidence.activeElapsedSeconds
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "landscapeBackground canvasWasHardwareAccelerated=" +
                            evidence.canvasWasHardwareAccelerated +
                            ", draws=" + evidence.drawCount,
                    )
                    view.setProjecting(true)
                    view.visibility = View.GONE
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                var drawsBeforeHide = -1
                var drawsAfterHide = -2
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    drawsBeforeHide = view.drawEvidence.drawCount
                }
                // Await, not sleep: the paused view posts nothing, so the latch must
                // time out here. waitForIdleSync drains any in-flight draw first.
                val hiddenLatch = CountDownLatch(1)
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    view.onFrameDrawn = { hiddenLatch.countDown() }
                    view.postInvalidateOnAnimation()
                }
                assertTrue(
                    "Hidden/projecting view kept drawing; invisible loop must stop.",
                    !hiddenLatch.await(2, TimeUnit.SECONDS),
                )
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    view.onFrameDrawn = null
                    drawsAfterHide = view.drawEvidence.drawCount
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "landscapeBackground hidden drawsBefore=" + drawsBeforeHide +
                            " drawsAfter=" + drawsAfterHide +
                            " elapsedBefore=" + elapsedBefore,
                    )
                }
                assertEquals(drawsBeforeHide, drawsAfterHide)
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    view.visibility = View.VISIBLE
                    view.setProjecting(false)
                }
            } finally {
                stopSceneAnimation(scenario)
            }
        }
    }

    /** Frozen keeps the real broadcast intent; day/night target is intent-owned. */
    @Test
    fun frozenState_keepsBroadcastIntentTarget() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = LandscapeSceneView(activity)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = true)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                assertTrue("No frame observed after initial frozen state.", awaitFrames(scenario, 1))
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    assertEquals(1.0, view.blendTarget, 1e-9)
                }
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = false)
                }
                assertTrue("No frame observed after updated frozen state.", awaitFrames(scenario, 1))
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    assertEquals(0.0, view.blendTarget, 1e-9)
                }
            } finally {
                stopSceneAnimation(scenario)
            }
        }
    }

    /** Compares the curved path and slice offsets with independent Chromium SVG bounds. */
    @Test
    fun moonPath_keepsCurvesAndSliceOffsets() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = LandscapeSceneView(activity)
                val portrait = view.moonPathFor(LandscapeGeometry.sliceTransformFor(1080.0, 2400.0), 800.0, 295.0)
                assertSampledBounds(portrait, 446.59653f, 616.8f, 590.6667f, 799.19995f)
                val wide = view.moonPathFor(
                    LandscapeGeometry.sliceTransformFor(2560.0, 720.0),
                    800.0,
                    342.17,
                )
                assertSampledBounds(wide, 1217.731f, 46.672f, 1313.7778f, 168.27197f)

                val measure = PathMeasure(portrait, false)
                val firstTangent = FloatArray(2)
                val secondTangent = FloatArray(2)
                assertTrue(measure.getPosTan(2f, null, firstTangent))
                assertTrue(measure.getPosTan(2.25f, null, secondTangent))
                assertTrue(abs(firstTangent[0] - secondTangent[0]) + abs(firstTangent[1] - secondTangent[1]) > 0.001f)
            }
        }
    }

    /** Exercises repeated targets, CSS reversal, and stopped-motion snapping with a fake clock. */
    @Test
    fun transition_reversesAndSnapsWhenMotionStops() {
        val clock = AtomicLong(1000L)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = LandscapeSceneView(activity)
                    view.clockUptimeMillis = { clock.get() }
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = false)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
                assertTrue("No frames observed while visible.", awaitFrames(scenario, 2))
                var ambientBefore = 0.0
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    ambientBefore = view.drawEvidence.activeElapsedSeconds
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    clock.set(1900L)
                }
                assertTrue("No frame observed after clock advance.", awaitFrames(scenario, 1))
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    assertEquals(0.080, view.drawEvidence.activeElapsedSeconds - ambientBefore, 1e-9)
                    assertEquals(0.129162, view.displayedBlend, 1e-6)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = true)
                    assertEquals(0.129162, view.displayedBlend, 1e-6)
                    clock.set(2800L)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = true)
                    assertEquals(0.5, view.displayedBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = false)
                    clock.set(3700L)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = false)
                    assertEquals(0.25, view.displayedBlend, 1e-9)
                    view.setProjecting(true)
                    assertEquals(0.0, view.displayedBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = true)
                    assertEquals(1.0, view.displayedBlend, 1e-9)
                    view.setProjecting(false)
                    assertEquals(1.0, view.displayedBlend, 1e-9)
                }
            } finally {
                stopSceneAnimation(scenario)
            }
        }
    }

    /** Drives the real view through clamp, resize, reversal, and projecting transitions. */
    @Test
    fun geometryReset_changesOnlyOrbitAndTracksLaterTargets() {
        val clock = AtomicLong(1000L)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = LandscapeSceneView(activity)
                    view.clockUptimeMillis = { clock.get() }
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(1600, 1000))
                }
                assertTrue("No frame observed after layout.", awaitFrames(scenario, 2))
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as LandscapeSceneView
                    assertEquals(1600, view.width)
                    assertEquals(1000, view.height)
                    view.setHostForegroundGeometry(0f, 700f)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    clock.set(2800L)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = true)
                    assertEquals(0.5, view.displayedBlend, 1e-9)
                    assertEquals(0.5, view.displayedOrbitBlend, 1e-9)

                    view.setHostForegroundGeometry(1f, 699f)
                    assertEquals(0.5, view.displayedOrbitBlend, 1e-9)
                    view.setHostForegroundGeometry(200f, 700f)
                    assertEquals(0.5, view.displayedBlend, 1e-9)
                    assertEquals(1.0, view.displayedOrbitBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = false)
                    clock.set(3700L)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = false)
                    assertEquals(0.25, view.displayedBlend, 1e-9)
                    assertEquals(0.870838, view.displayedOrbitBlend, 1e-6)
                    view.setHostForegroundGeometry(200.001f, 700f)
                    assertEquals(0.870838, view.displayedOrbitBlend, 1e-6)

                    view.layout(view.left, view.top, view.left + 1601, view.top + 1000)
                    assertEquals(0.25, view.displayedBlend, 1e-9)
                    assertEquals(0.0, view.displayedOrbitBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    clock.set(5050L)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    assertEquals(0.625, view.displayedBlend, 1e-9)
                    assertTrue(view.displayedOrbitBlend > 0.0 && view.displayedOrbitBlend < 0.5)

                    view.setProjecting(true)
                    assertEquals(1.0, view.displayedBlend, 1e-9)
                    assertEquals(1.0, view.displayedOrbitBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.FROZEN, broadcasting = false)
                    view.setProjecting(false)
                    assertEquals(0.0, view.displayedBlend, 1e-9)
                    assertEquals(0.0, view.displayedOrbitBlend, 1e-9)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    clock.set(5950L)
                    view.setLandscapeState(ScreenPhase.READY, broadcasting = true)
                    assertEquals(0.129162, view.displayedBlend, 1e-6)
                    assertEquals(0.129162, view.displayedOrbitBlend, 1e-6)
                    view.visibility = View.INVISIBLE
                    assertEquals(1.0, view.displayedBlend, 1e-9)
                    assertEquals(1.0, view.displayedOrbitBlend, 1e-9)
                    view.visibility = View.VISIBLE
                    assertEquals(1.0, view.displayedBlend, 1e-9)
                    assertEquals(1.0, view.displayedOrbitBlend, 1e-9)
                }
            } finally {
                stopSceneAnimation(scenario)
            }
        }
    }

    private class GradientRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val pathPaints = mutableListOf<Paint>()
        var firstPath: Path? = null
        var ovalPaint: Paint? = null
        var ovalBounds: RectF? = null

        override fun drawPath(path: Path, paint: Paint) {
            if (firstPath == null) {
                firstPath = Path(path)
            }
            pathPaints.add(Paint(paint))
            super.drawPath(path, paint)
        }

        override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            ovalPaint = Paint(paint)
            ovalBounds = RectF(left, top, right, bottom)
            super.drawOval(left, top, right, bottom, paint)
        }
    }

    private fun assertMistPathBounds(path: Path) {
        val measure = PathMeasure(path, false)
        val point = FloatArray(2)
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (step in 0..8192) {
            assertTrue(measure.getPosTan(measure.length * step / 8192f, point, null))
            top = minOf(top, point[1])
            bottom = maxOf(bottom, point[1])
        }
        assertEquals(471.462158f, top, 0.05f)
        assertEquals(711.734863f, bottom, 0.05f)
    }

    private fun sampleGradientAlpha(paint: Paint, y: Int): Int = Color.alpha(sampleGradientColor(paint, y))

    private fun sampleGradientColor(paint: Paint, y: Int): Int {
        val bitmap = Bitmap.createBitmap(1, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 1f, 1000f, paint)
        val color = bitmap.getPixel(0, y)
        bitmap.recycle()
        return color
    }

    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue(abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    private fun assertAlphaNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Expected alpha $expected ± $tolerance, got $actual.", abs(expected - actual) <= tolerance)
    }

    private fun assertSampledBounds(path: Path, left: Float, top: Float, right: Float, bottom: Float) {
        val measure = PathMeasure(path, false)
        val point = FloatArray(2)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (step in 0..512) {
            assertTrue(measure.getPosTan(measure.length * step / 512f, point, null))
            minX = minOf(minX, point[0])
            minY = minOf(minY, point[1])
            maxX = maxOf(maxX, point[0])
            maxY = maxOf(maxY, point[1])
        }
        // Sampling misses an extremum by under 0.003px here; Android's extra 0.03px allowance is unverified.
        assertEquals(left, minX, 0.03f)
        assertEquals(top, minY, 0.03f)
        assertEquals(right, maxX, 0.03f)
        assertEquals(bottom, maxY, 0.03f)
    }

    private fun awaitFrames(scenario: ActivityScenario<StandbyProbeActivity>, frames: Int): Boolean {
        repeat(frames) {
            val latch = CountDownLatch(1)
            awaitActivityAction(scenario) { activity ->
                val view = activity.contentRoot.getChildAt(0) as? LandscapeSceneView
                if (view == null) {
                    latch.countDown()
                    return@awaitActivityAction
                }
                view.onFrameDrawn = { latch.countDown() }
                view.postInvalidateOnAnimation()
            }
            if (!latch.await(FRAME_WAIT_SECONDS, TimeUnit.SECONDS)) {
                awaitActivityAction(scenario) { activity ->
                    (activity.contentRoot.getChildAt(0) as? LandscapeSceneView)?.onFrameDrawn = null
                }
                return false
            }
            awaitActivityAction(scenario) { activity ->
                (activity.contentRoot.getChildAt(0) as? LandscapeSceneView)?.onFrameDrawn = null
            }
        }
        return true
    }

    // Stop the Choreographer invalidation loop before ActivityScenario.close() calls waitForIdleSync().
    private fun stopSceneAnimation(scenario: ActivityScenario<StandbyProbeActivity>) {
        awaitActivityAction(scenario) { activity ->
            (activity.contentRoot.getChildAt(0) as? LandscapeSceneView)?.let { view ->
                view.onFrameDrawn = null
                view.setProjecting(true)
            }
            activity.contentRoot.removeAllViews()
        }
    }

    companion object {
        private const val FRAME_WAIT_SECONDS = 5L
    }
}
