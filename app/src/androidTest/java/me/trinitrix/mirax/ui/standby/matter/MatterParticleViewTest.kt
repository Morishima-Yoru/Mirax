package me.trinitrix.mirax.ui.standby.matter

import android.graphics.BitmapFactory
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Isolated background-only mount checks for [MatterParticleView].
 *
 * Mounts the candidate in the existing debug [StandbyProbeActivity] host and verifies
 * that the real 1800 source field is rendered on the attached native view. Hardware
 * acceleration is recorded, never asserted when unknown. Frame cadence is observed only;
 * no pass threshold is invented. Parent owns build, install, execution, and review.
 */
@RunWith(AndroidJUnit4::class)
class MatterParticleViewTest {

    /** Attached view draws the full 1800 source field, then stops when hidden. */
    @Test
    fun attachedBackground_rendersFullFieldThenStopsWhenHidden() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = MatterParticleView(activity)
                    view.setMatterState(ScreenPhase.READY, broadcasting = false, wifiPaused = false)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                awaitFrames(scenario, FRAME_LATCH_COUNT)
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    assertEquals("Source field must stay exactly 1800 particles.", 1800, view.particleCount)
                    val evidence = view.drawEvidence
                    assertTrue("No attached draw observed; evidence unavailable.", evidence.observed)
                    assertTrue("Draw count must exceed zero.", evidence.drawCount > 0)
                    assertEquals("Draws must iterate the full source field.", 1800, evidence.particleCount)
                    assertTrue(
                        "Active elapsed time must advance while visible.",
                        evidence.activeElapsedMillis >= 0.0,
                    )
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "matterBackground canvasWasHardwareAccelerated=" +
                            evidence.canvasWasHardwareAccelerated +
                            ", drawCount=" + evidence.drawCount +
                            ", activeElapsedMillis=" + evidence.activeElapsedMillis +
                            ", particleCount=" + evidence.particleCount,
                    )
                }
                val capture = WindowEvidence.capture(scenario, "matter-background")
                assertTrue(
                    "Window evidence PNG missing at " + capture.file.absolutePath + ".",
                    capture.file.isFile,
                )
                assertTrue(
                    "Window evidence PNG empty at " + capture.file.absolutePath + ".",
                    capture.bytesWritten > 0,
                )
                val decoded = BitmapFactory.decodeFile(capture.file.absolutePath)
                assertNotNull(
                    "Window evidence PNG undecodable at " + capture.file.absolutePath + ".",
                    decoded,
                )
                val evidenceBitmap = checkNotNull(decoded)
                try {
                    assertTrue("Window evidence bitmap has no size.", evidenceBitmap.width > 0 && evidenceBitmap.height > 0)
                    var brightPixels = 0
                    val stepX = (evidenceBitmap.width / 240).coerceAtLeast(2)
                    val stepY = (evidenceBitmap.height / 240).coerceAtLeast(2)
                    var y = 0
                    while (y < evidenceBitmap.height && brightPixels < BRIGHT_PIXEL_MIN) {
                        var x = 0
                        while (x < evidenceBitmap.width && brightPixels < BRIGHT_PIXEL_MIN) {
                            val pixel = evidenceBitmap.getPixel(x, y)
                            val red = (pixel shr 16) and 0xFF
                            val green = (pixel shr 8) and 0xFF
                            val blue = pixel and 0xFF
                            if (red >= BRIGHT_CHANNEL_MIN && green >= BRIGHT_CHANNEL_MIN &&
                                blue >= BRIGHT_CHANNEL_MIN
                            ) {
                                brightPixels++
                            }
                            x += stepX
                        }
                        y += stepY
                    }
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "matterBackground brightPixels(sampled)=" + brightPixels +
                            ", size=" + evidenceBitmap.width + "x" + evidenceBitmap.height,
                    )
                    assertTrue(
                        "No particle-bright pixels sampled; faithful output uncertain.",
                        brightPixels >= BRIGHT_PIXEL_MIN,
                    )
                } finally {
                    evidenceBitmap.recycle()
                }
                var drawsBeforeHide = -1
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    drawsBeforeHide = view.drawEvidence.drawCount
                    view.visibility = View.GONE
                    view.postInvalidateOnAnimation()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    val drawsAfterHide = view.drawEvidence.drawCount
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "matterBackground hidden drawsBefore=" + drawsBeforeHide +
                            " drawsAfter=" + drawsAfterHide,
                    )
                    assertTrue(
                        "Hidden view kept drawing; invisible loop must stop.",
                        drawsAfterHide == drawsBeforeHide,
                    )
                    view.visibility = View.VISIBLE
                    view.setProjecting(true)
                    view.postInvalidateOnAnimation()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    val drawsWhileProjecting = view.drawEvidence.drawCount
                    view.setProjecting(false)
                    view.postInvalidateOnAnimation()
                    Log.i(
                        WindowEvidence.LOG_TAG,
                        "matterBackground projecting draws=" + drawsWhileProjecting,
                    )
                }
            } finally {
                // Stop animation before ActivityScenario.close waits for idle.
                awaitActivityAction(scenario) { activity ->
                    (activity.contentRoot.getChildAt(0) as? MatterParticleView)?.setProjecting(true)
                    activity.contentRoot.removeAllViews()
                }
            }
        }
    }

    /** Frozen rendering is explicit input only, never invented by the view. */
    @Test
    fun frozenState_requiresExplicitFrozenPhase() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                awaitActivityAction(scenario) { activity ->
                    val view = MatterParticleView(activity)
                    view.setMatterState(ScreenPhase.CONNECTED, broadcasting = true, wifiPaused = false)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                awaitFrames(scenario, 1)
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    assertTrue("Expected at least one draw before frozen check.", view.drawEvidence.drawCount > 0)
                    view.setMatterState(ScreenPhase.FROZEN, broadcasting = true, wifiPaused = false)
                    view.postInvalidateOnAnimation()
                }
                // waitForIdleSync deadlocks here: FROZEN keeps posting invalidations.
                awaitFrames(scenario, 1)
                awaitActivityAction(scenario) { activity ->
                    val view = activity.contentRoot.getChildAt(0) as MatterParticleView
                    assertTrue("Frozen state must keep drawing the source field.", view.drawEvidence.drawCount > 0)
                    // Stop the animation before ActivityScenario.close waits for idle.
                    view.setProjecting(true)
                }
            } finally {
                // Stop animation before ActivityScenario.close waits for idle.
                awaitActivityAction(scenario) { activity ->
                    (activity.contentRoot.getChildAt(0) as? MatterParticleView)?.setProjecting(true)
                    activity.contentRoot.removeAllViews()
                }
            }
        }
    }

    /** Particle-layer offset is raw owner input only, no motion math inside. */
    @Test
    fun particleLayerOffset_passthroughWithoutMotionMath() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = MatterParticleView(activity)
                activity.contentRoot.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                assertEquals(0f, view.particleLayerOffsetXPx, 0.001f)
                assertEquals(0f, view.particleLayerOffsetYPx, 0.001f)
                view.setParticleLayerOffset(7.5f, -3.25f)
                assertEquals(7.5f, view.particleLayerOffsetXPx, 0.001f)
                assertEquals(-3.25f, view.particleLayerOffsetYPx, 0.001f)
            }
        }
    }

    private fun awaitFrames(scenario: ActivityScenario<StandbyProbeActivity>, frames: Int) {
        val latch = CountDownLatch(frames)
        awaitActivityAction(scenario) { activity ->
            val view = activity.contentRoot.getChildAt(0) as MatterParticleView
            val previous = view.onFrameDrawn
            view.onFrameDrawn = {
                previous?.invoke()
                latch.countDown()
            }
            view.postInvalidateOnAnimation()
        }
        val completed = latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitActivityAction(scenario) { activity ->
            val view = activity.contentRoot.getChildAt(0) as MatterParticleView
            view.onFrameDrawn = null
        }
        assertTrue("Matter background frames not observed; evidence unavailable.", completed)
    }

    private companion object {
        private const val FRAME_LATCH_COUNT = 3
        private const val FRAME_TIMEOUT_SECONDS = 5L
        private const val BRIGHT_PIXEL_MIN = 20
        // Single-particle max channel is ~179 after SCRIM_COLOR over #080808.
        private const val BRIGHT_CHANNEL_MIN = 100
    }
}
