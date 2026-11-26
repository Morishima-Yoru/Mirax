package me.trinitrix.mirax.standbyprobe.render

import android.graphics.BitmapFactory
import android.graphics.Insets
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.trinitrix.mirax.R
import me.trinitrix.mirax.WindowEvidence
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Attached-view probe for software measurement versus GPU draw evidence.
 *
 * Runs on device under the parent runner; parent owns build, install, pull, and viewing.
 */
@RunWith(AndroidJUnit4::class)
class StandbyRenderProbeTest {

    /** Reference size stays fixed; resize must never auto-shrink text. */
    @Test
    fun referenceSize_fixedAt20px() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                activity.contentRoot.addView(view)
                assertEquals(20f, view.referenceTextPx, 0.001f)
            }
        }
    }

    /** Long receiver name stays single-line and ellipsizes at narrow width. */
    @Test
    fun narrowWidth_singleLineEllipsized() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                val longName = activity.getString(R.string.standby_probe_long_name)
                assertTrue(longName.isNotEmpty())
                val narrow = view.measureLayout(longName, ProbeLayoutCase.NARROW_320.logicalWidthPx)
                assertEquals(1, narrow.lineCount)
                assertEquals(320, narrow.widthPx)
                assertTrue("Narrow probe must ellipsize the raw long name.", narrow.ellipsized)
                assertTrue(narrow.referenceHeightPx > 0)
                Log.i(
                    "MiraxStandbyProbe",
                    "narrowWidth width=" + narrow.widthPx +
                        " lineCount=" + narrow.lineCount +
                        " ellipsized=" + narrow.ellipsized +
                        " height=" + narrow.heightPx +
                        " referenceHeight=" + narrow.referenceHeightPx,
                )
            }
        }
    }

    /** Fixed logical widths are software inputs only, fold is not a real posture. */
    @Test
    fun logicalCases_reportExpectedWidths() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                val longName = activity.getString(R.string.standby_probe_long_name)
                val wide = view.measureLayout(longName, ProbeLayoutCase.WIDE_1080.logicalWidthPx)
                assertEquals(1080, wide.widthPx)
                assertEquals(1, wide.lineCount)
                val fold = view.measureLayout(longName, ProbeLayoutCase.FOLD_LOGICAL.logicalWidthPx)
                assertEquals(1812, fold.widthPx)
                assertEquals(1, fold.lineCount)
                assertTrue(ProbeLayoutCase.FOLD_LOGICAL.note.contains("not an actual"))
            }
        }
    }

    /** Attached draw records hardware evidence explicitly, never assumed. */
    @Test
    fun attachedDraw_recordsHardwareEvidence() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                activity.contentRoot.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitDraw(scenario)
            val windowEvidence = WindowEvidence.capture(scenario, "attached-draw")
            assertTrue(
                "Window evidence PNG missing at " + windowEvidence.file.absolutePath + ".",
                windowEvidence.file.isFile,
            )
            assertTrue(
                "Window evidence PNG empty at " + windowEvidence.file.absolutePath + ".",
                windowEvidence.bytesWritten > 0,
            )
            // Probe-only visibility sanity: PNG must decode and contain foreground
            // bright pixels from the explicit white probe text, not just non-empty bytes.
            val decoded = BitmapFactory.decodeFile(windowEvidence.file.absolutePath)
            assertNotNull(
                "Window evidence PNG undecodable at " + windowEvidence.file.absolutePath + ".",
                decoded,
            )
            val evidenceBitmap = checkNotNull(decoded)
            try {
                assertTrue("Window evidence bitmap has no size.", evidenceBitmap.width > 0 && evidenceBitmap.height > 0)
                var brightPixels = 0
                val stepX = (evidenceBitmap.width / 120).coerceAtLeast(4)
                val stepY = (evidenceBitmap.height / 120).coerceAtLeast(4)
                var y = 0
                while (y < evidenceBitmap.height && brightPixels < PROBE_BRIGHT_PIXEL_MIN) {
                    var x = 0
                    while (x < evidenceBitmap.width && brightPixels < PROBE_BRIGHT_PIXEL_MIN) {
                        val pixel = evidenceBitmap.getPixel(x, y)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        if (r >= PROBE_BRIGHT_CHANNEL_MIN && g >= PROBE_BRIGHT_CHANNEL_MIN &&
                            b >= PROBE_BRIGHT_CHANNEL_MIN
                        ) {
                            brightPixels++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                Log.i(
                    "MiraxStandbyProbe",
                    "attachedDraw brightPixels(sampled)=" + brightPixels +
                        " bitmap=" + evidenceBitmap.width + "x" + evidenceBitmap.height,
                )
                assertTrue(
                    "No probe foreground pixels in window evidence; text may be unreadable.",
                    brightPixels >= PROBE_BRIGHT_PIXEL_MIN,
                )
            } finally {
                evidenceBitmap.recycle()
            }
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                val snapshot = view.currentSnapshot()
                assertTrue("No attached draw observed; hardware state unavailable.", snapshot.draw.observed)
                assertTrue("Draw count must be observed greater than zero.", snapshot.draw.drawCount > 0)
                assertNotNull(
                    "Missing canvas acceleration flag; treat as unavailable, not pass.",
                    snapshot.draw.canvasWasHardwareAccelerated,
                )
                assertNotNull(snapshot.layout)
                val layout = checkNotNull(snapshot.layout)
                assertTrue(layout.referenceHeightPx > 0)
                assertTrue(layout.heightPx > 0)
                Log.i(
                    "MiraxStandbyProbe",
                    "attachedDraw actual canvasWasHardwareAccelerated=" +
                        snapshot.draw.canvasWasHardwareAccelerated +
                        ", viewWasHardwareAccelerated=" +
                        snapshot.draw.viewWasHardwareAccelerated +
                        ", drawCount=" + snapshot.draw.drawCount +
                        ", observed=" + snapshot.draw.observed +
                        ", layout width=" + layout.widthPx +
                        " height=" + layout.heightPx +
                        " lineCount=" + layout.lineCount +
                        " ellipsized=" + layout.ellipsized +
                        " referenceHeight=" + layout.referenceHeightPx +
                        " referenceWidth=" + layout.referenceWidthPx +
                        ", systemBars=" + snapshot.systemBars,
                )
            }
        }
    }

    /** Actual window bounds from real insets; tight glyph rect stays explicit unknown. */
    @Test
    fun attachedDraw_reportsActualVisibleBounds() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                activity.contentRoot.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitDraw(scenario)
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                // Real window evidence only; never substitute a fabricated zero.
                val decor = activity.window.decorView
                val rootInsets = decor.rootWindowInsets
                assertNotNull("Actual rootWindowInsets missing; safe-inset unproven.", rootInsets)
                val actualBars = checkNotNull(rootInsets).getInsets(WindowInsets.Type.systemBars())
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val globalRect = Rect()
                val globalVisible = view.getGlobalVisibleRect(globalRect)
                val displayFrame = Rect()
                view.getWindowVisibleDisplayFrame(displayFrame)
                assertTrue("Global visible rect empty; no actual bounds.", globalVisible && !globalRect.isEmpty)
                assertTrue("Window visible display frame empty.", !displayFrame.isEmpty)
                assertTrue("Actual view has no size.", view.width > 0 && view.height > 0)
                val snapshot = view.currentSnapshot()
                assertTrue("No attached draw observed.", snapshot.draw.observed)
                assertNotNull(snapshot.layout)
                val layout = checkNotNull(snapshot.layout)
                assertTrue(layout.referenceHeightPx > 0)
                assertTrue(layout.heightPx > 0)
                // Offsets match onDraw: snapshot.systemBars?.top ?: paddingTop, same for left.
                val offsetX = snapshot.systemBars?.left ?: view.paddingLeft
                val offsetY = snapshot.systemBars?.top ?: view.paddingTop
                // Layout block (conservative outer bound) from public snapshot only:
                // ref block on top, receiver block below, same origin offsets as onDraw.
                val blockLeft = location[0] + offsetX
                val blockTop = location[1] + offsetY
                val blockWidth = maxOf(layout.referenceWidthPx, layout.widthPx)
                val blockHeight = layout.referenceHeightPx + layout.heightPx
                val textBlock = Rect(blockLeft, blockTop, blockLeft + blockWidth, blockTop + blockHeight)
                // Layout block inside view-proven bounds implies glyphs inside too;
                // tight glyph rect (private textBounds) stays explicit unknown.
                assertTrue("Layout text block empty.", !textBlock.isEmpty)
                assertTrue(
                    "Layout text block outside visible display frame: " + textBlock.toShortString() +
                        " frame=" + displayFrame.toShortString(),
                    displayFrame.contains(textBlock),
                )
                Log.i(
                    "MiraxStandbyProbe",
                    "actualVisibleBounds view=" + view.width + "x" + view.height +
                        " location=" + location[0] + "," + location[1] +
                        " globalRect=" + globalRect.toShortString() +
                        " displayFrame=" + displayFrame.toShortString() +
                        " actualBars=" + actualBars +
                        " snapshotBars=" + snapshot.systemBars +
                        " layoutWidth=" + layout.widthPx +
                        " receiverHeight=" + layout.heightPx +
                        " referenceHeight=" + layout.referenceHeightPx +
                        " referenceWidth=" + layout.referenceWidthPx +
                        " textBlock=" + textBlock.toShortString() +
                        " textRect=unknown-tight-glyphs-unavailable-from-public-snapshot",
                )
            }
        }
    }

    /** Frame observation uses an independent main-loop clock and stops deterministically. */
    @Test
    fun frameProbe_startsAndStopsWithoutSleep() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                activity.contentRoot.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitDraw(scenario)
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                view.startFrameProbe()
            }
            try {
                val clockLatch = CountDownLatch(3)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val choreographer = Choreographer.getInstance()
                    val clockHolder = arrayOfNulls<Choreographer.FrameCallback>(1)
                    clockHolder[0] = Choreographer.FrameCallback {
                        clockLatch.countDown()
                        if (clockLatch.count > 0) {
                            val next = clockHolder[0]
                            if (next != null) {
                                choreographer.postFrameCallback(next)
                            }
                        }
                    }
                    val first = clockHolder[0]
                    if (first != null) {
                        choreographer.postFrameCallback(first)
                    }
                }
                val clockCompleted = clockLatch.await(5, TimeUnit.SECONDS)
                assertTrue("Independent frame clock did not tick; view loop never started.", clockCompleted)
                scenario.onActivity { activity ->
                    val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                    val snapshot = view.currentSnapshot()
                    Log.i(
                        "MiraxStandbyProbe",
                        "frameProbe intervals=" + snapshot.frameIntervalsNanos +
                            ", count=" + snapshot.frameIntervalsNanos.size +
                            ", attached=" + view.isAttachedToWindow +
                            " visible=" + (view.visibility == View.VISIBLE),
                    )
                    if (view.isAttachedToWindow && view.visibility == View.VISIBLE) {
                        assertTrue(
                            "No frame intervals observed while visible and attached.",
                            snapshot.frameIntervalsNanos.isNotEmpty(),
                        )
                    }
                }
            } finally {
                scenario.onActivity { activity ->
                    val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                    view.stopFrameProbe()
                }
            }
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
                assertFalse(view.isFrameLoopActive)
            }
        }
    }

    /** Accessibility semantics stay meaningful and localized in both languages. */
    @Test
    fun overlayStrings_localizedEnAndZhTw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val defaultDesc = target.getString(R.string.standby_probe_overlay_desc)
        assertTrue(defaultDesc.isNotEmpty())

        val zhConfig = android.content.res.Configuration(target.resources.configuration)
        zhConfig.setLocale(Locale.TRADITIONAL_CHINESE)
        val zhDesc = target.createConfigurationContext(zhConfig)
            .getString(R.string.standby_probe_overlay_desc)
        assertTrue(zhDesc.isNotEmpty())

        val enConfig = android.content.res.Configuration(target.resources.configuration)
        enConfig.setLocale(Locale.US)
        val enDesc = target.createConfigurationContext(enConfig)
            .getString(R.string.standby_probe_overlay_desc)
        assertTrue(enDesc.isNotEmpty())

        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = StandbyRenderProbeView(activity)
                assertTrue(view.contentDescription.isNotEmpty())

                view.dispatchConfigurationChanged(zhConfig)
                val zhReference = activity.createConfigurationContext(zhConfig)
                    .getString(R.string.standby_probe_reference)
                assertEquals(zhReference, view.referenceText.toString())

                view.dispatchConfigurationChanged(enConfig)
                val enReference = activity.createConfigurationContext(enConfig)
                    .getString(R.string.standby_probe_reference)
                assertEquals(enReference, view.referenceText.toString())

                val sampleInsets = Insets.of(16, 28, 16, 40)
                val windowInsets = WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), sampleInsets)
                    .build()
                view.onApplyWindowInsets(windowInsets)
                assertEquals(sampleInsets, view.lastSystemBarsInsets)
                assertEquals(sampleInsets, view.currentSnapshot().systemBars)
            }
        }
    }

    private fun awaitDraw(scenario: ActivityScenario<StandbyProbeActivity>) {
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = activity.contentRoot.getChildAt(0) as StandbyRenderProbeView
            view.post {
                view.invalidate()
                view.post { latch.countDown() }
            }
        }
        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("View draw was not scheduled; hardware evidence unavailable.", completed)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    companion object {
        // Probe-only sampled bright-pixel floor; guards unreadable black-on-dark captures.
        private const val PROBE_BRIGHT_PIXEL_MIN = 20
        private const val PROBE_BRIGHT_CHANNEL_MIN = 200
    }
}
