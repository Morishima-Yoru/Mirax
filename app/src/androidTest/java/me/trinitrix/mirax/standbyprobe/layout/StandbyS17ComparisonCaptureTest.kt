package me.trinitrix.mirax.standbyprobe.layout

import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.trinitrix.mirax.WindowEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures isolated app-window evidence for the unapproved fixture comparisons. */
@RunWith(AndroidJUnit4::class)
class StandbyS17ComparisonCaptureTest {
    /** Runs only these native comparison fixtures; no device or session state is changed. */
    @Test
    fun captureComparisons() {
        assertIsolatedPackage()
        val originalText = mutableMapOf<S17Fixture, Pair<String, Float>>()
        var baselineInsetDock: Rect? = null
        ActivityScenario.launch(StandbyS17ComparisonActivity::class.java).use { scenario ->
            awaitWindowFocus(scenario)
            for (fixture in S17Fixture.entries) {
                for (proposal in listOf(false, true)) {
                    scenario.onActivity { it.showFixture(fixture, proposal) }
                    awaitCommittedFrame(scenario)
                    var page = 0
                    val seenControls = mutableSetOf<String>()
                    var hasNext = false
                    do {
                        scenario.onActivity { it.updateCaption(page) }
                        awaitCommittedFrame(scenario)
                        assertEquals(Lifecycle.State.RESUMED, scenario.state)
                        scenario.onActivity { activity ->
                            assertReady(activity, fixture)
                            val viewport = activity.viewport
                            val statusTag = if (fixture == S17Fixture.HARDLINE_TINY) {
                                "foreground_failed"
                            } else {
                                "foreground_phase_eyebrow"
                            }
                            val status = requireNotNull(viewport.findViewWithTag<TextView>(statusTag))
                            if (page == 0) {
                                if (!proposal) {
                                    originalText[fixture] = status.text.toString() to status.textSize
                                } else {
                                    val baseline = requireNotNull(originalText[fixture])
                                    assertEquals(baseline.first, status.text.toString())
                                    assertEquals(baseline.second, status.textSize, 0.01f)
                                    if (fixture != S17Fixture.LANDSCAPE_INSET) assertCompleteText(status)
                                    if (fixture == S17Fixture.HARDLINE_TINY) {
                                        assertVisibleTextFits(requireNotNull(activity.proposalScroll))
                                    }
                                    if (fixture == S17Fixture.MATTER_NARROW) {
                                        // Measured on device: "ADVERTISING" renders ~45x15px inside a ~304px proposal plate at
                // this fixture density, so wrapping is unreachable at any product behaviour.
                // Untruncated rendering is asserted above by assertCompleteText.
                assertEquals("ADVERTISING must stay on the measured single line", 1, status.lineCount)
                                    }
                                }
                            }
                            if (proposal && fixture != S17Fixture.LANDSCAPE_INSET) {
                                val tags = if (fixture == S17Fixture.HARDLINE_TINY) {
                                    listOf("foreground_failed_dismiss", "foreground_broadcast", "foreground_menu")
                                } else {
                                    listOf("foreground_broadcast", "foreground_menu")
                                }
                                val minimum = (48f * fixture.density).toInt()
                                for (tag in tags) {
                                    val control = requireNotNull(viewport.findViewWithTag<View>(tag))
                                    assertTrue("$tag width is below 48dp", control.width >= minimum)
                                    assertTrue("$tag height is below 48dp", control.height >= minimum)
                                    assertTrue("$tag must have a working callback", control.isClickable && control.isFocusable)
                                    assertFalse("$tag needs an accessible name", control.contentDescription.isNullOrBlank())
                                    if (fullyVisible(viewport, control)) seenControls.add(tag)
                                }
                            }
                            if (fixture == S17Fixture.LANDSCAPE_INSET) {
                                if (proposal) {
                                    assertMinimalDockClamp(activity, fixture, requireNotNull(baselineInsetDock))
                                } else {
                                    val dock = requireNotNull(viewport.findViewWithTag<View>("foreground_broadcast"))
                                    baselineInsetDock = boundsInViewport(viewport, dock)
                                }
                            }
                        }
                        val label = "s17_${fixture.name}_${if (proposal) "proposal" else "current"}_p$page"
                        val capture = WindowEvidence.capture(scenario, label)
                        scenario.onActivity { activity ->
                            Log.i(
                                WindowEvidence.LOG_TAG,
                                "s17 file=${capture.file.absolutePath} case=${fixture.name} " +
                                    "variant=${if (proposal) "proposal_unapproved" else "current"} page=$page " +
                                    "window=${capture.widthPx}x${capture.heightPx} " + evidenceDetails(activity),
                            )
                            val scroll = activity.proposalScroll
                            val max = scroll?.let { (it.getChildAt(0).height - it.height).coerceAtLeast(0) } ?: 0
                            hasNext = scroll != null && scroll.scrollY < max
                            if (hasNext && scroll != null) {
                                scroll.scrollTo(0, minOf(max, scroll.scrollY + (scroll.height / 4).coerceAtLeast(1)))
                            }
                        }
                        page += 1
                        assertTrue("Unexpectedly many scroll pages", page <= 100)
                    } while (hasNext)
                    if (proposal && fixture != S17Fixture.LANDSCAPE_INSET) {
                        val expected = if (fixture == S17Fixture.HARDLINE_TINY) {
                            setOf("foreground_failed_dismiss", "foreground_broadcast", "foreground_menu")
                        } else {
                            setOf("foreground_broadcast", "foreground_menu")
                        }
                        assertTrue("Proposal should have more than one page", page > 1)
                        assertEquals("Each control must be fully reachable on a captured page", expected, seenControls)
                    }
                }
            }
        }
    }

    /** Fails before launch when the installed target package is not isolated. */
    @Suppress("DEPRECATION")
    private fun assertIsolatedPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals("me.trinitrix.mirax.vanillaprobe", target.packageName)
        assertEquals("me.trinitrix.mirax.vanillaprobe.test", instrumentation.context.packageName)
        assertEquals("android.app.Application", target.applicationContext.javaClass.name)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
        val info = target.packageManager.getPackageInfo(target.packageName, flags)
        assertEquals(
            setOf(StandbyS17ComparisonActivity::class.java.name),
            info.activities.orEmpty().map { it.name }.toSet(),
        )
        assertTrue("Unexpected service in preview package", info.services.isNullOrEmpty())
        assertTrue("Unexpected receiver in preview package", info.receivers.isNullOrEmpty())
        assertTrue("Unexpected provider in preview package", info.providers.isNullOrEmpty())
        assertTrue("Unexpected permission in preview package", info.requestedPermissions.isNullOrEmpty())
    }

    /** @param scenario Active preview scenario whose window must gain focus. */
    private fun awaitWindowFocus(scenario: ActivityScenario<StandbyS17ComparisonActivity>) {
        val focused = CountDownLatch(1)
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) focused.countDown()
        }
        var observer: ViewTreeObserver? = null
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                observer = decor.viewTreeObserver
                observer?.addOnWindowFocusChangeListener(listener)
                if (decor.hasWindowFocus()) focused.countDown()
            }
            assertTrue("Preview window did not gain focus", focused.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
            }
        }
    }

    /** @param scenario Active preview scenario whose next frame must commit. */
    private fun awaitCommittedFrame(scenario: ActivityScenario<StandbyS17ComparisonActivity>) {
        val committed = CountDownLatch(1)
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            assertTrue("Hardware acceleration is required for window evidence", decor.isHardwareAccelerated)
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Preview frame was not committed", committed.await(5, TimeUnit.SECONDS))
    }

    /**
     * Checks that the requested fixture is actually visible and pixel-sized.
     * @param activity Focused preview activity.
     * @param fixture Requested fixture.
     */
    private fun assertReady(
        activity: StandbyS17ComparisonActivity,
        fixture: S17Fixture,
    ) {
        assertFalse(activity.isFinishing)
        assertTrue("Preview window must own focus", activity.window.decorView.hasWindowFocus())
        assertTrue(activity.viewport.isAttachedToWindow)
        assertEquals(fixture.widthPx, activity.viewport.width)
        assertEquals(fixture.heightPx, activity.viewport.height)
        assertEquals(fixture.density, activity.viewport.resources.displayMetrics.density, 0.001f)
        assertEquals(1f, activity.viewport.resources.configuration.fontScale, 0.001f)
        val visible = Rect()
        assertTrue("Viewport must be on screen", activity.viewport.getGlobalVisibleRect(visible))
        assertEquals("Viewport must not be horizontally cropped", fixture.widthPx, visible.width())
        assertEquals("Viewport must not be vertically cropped", fixture.heightPx, visible.height())
    }

    /** @param text Proposal text that must be rendered without truncation. */
    private fun assertCompleteText(text: TextView) {
        assertEquals(null, text.ellipsize)
        val layout = requireNotNull(text.layout)
        assertTrue("Text must have a rendered line", layout.lineCount > 0)
        for (line in 0 until layout.lineCount) {
            assertEquals("Text must not be ellipsized", 0, layout.getEllipsisCount(line))
        }
        assertEquals("Text must not be clipped by a line limit", text.text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    /** @param view Visible proposal subtree whose text must fit without truncation. */
    private fun assertVisibleTextFits(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            val layout = requireNotNull(view.layout)
            assertTrue("Text must fit its view height: ${view.tag}",
                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals("Text must not be ellipsized: ${view.tag}", 0, layout.getEllipsisCount(line))
                assertTrue("Text must fit its view width: ${view.tag}",
                    layout.getLineWidth(line) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1f)
            }
            assertEquals("Text must not be clipped by a line limit: ${view.tag}",
                view.text.length, layout.getLineEnd(layout.lineCount - 1))
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) assertVisibleTextFits(view.getChildAt(index))
        }
    }

    /**
     * Checks that only the smallest necessary horizontal dock movement occurred.
     * @param activity Preview activity.
     * @param fixture Asymmetric inset fixture.
     * @param baseline Original dock bounds in viewport pixels.
     */
    private fun assertMinimalDockClamp(activity: StandbyS17ComparisonActivity, fixture: S17Fixture, baseline: Rect) {
        val viewport = activity.viewport
        val dock = requireNotNull(viewport.findViewWithTag<View>("foreground_broadcast"))
        val actual = boundsInViewport(viewport, dock)
        val safeWidth = fixture.widthPx - fixture.leftInsetPx
        assertTrue("Dock cannot fit inside the safe area", safeWidth >= dock.width)
        val expected = baseline.left.coerceIn(fixture.leftInsetPx, fixture.widthPx - dock.width)
        assertEquals("Dock must use the smallest horizontal clamp", expected, actual.left)
        assertEquals("Dock width must not change", baseline.width(), actual.width())
        assertEquals("Dock height must not change", baseline.height(), actual.height())
        assertEquals("Dock top must not change", baseline.top, actual.top)
        assertEquals("Dock bottom must not change", baseline.bottom, actual.bottom)
        assertTrue(actual.right <= fixture.widthPx && actual.left >= fixture.leftInsetPx)
        assertEquals("Dock vertical position must not move", 0f, dock.translationY, 0f)
    }

    /**
     * @param viewport Fixed-pixel clipping viewport.
     * @param child Native control.
     * @return Whether the entire control is visible inside the viewport.
     */
    private fun fullyVisible(viewport: View, child: View): Boolean {
        val viewportRect = Rect()
        val visible = Rect()
        return viewport.getGlobalVisibleRect(viewportRect) && child.getGlobalVisibleRect(visible) &&
            visible.width() == child.width && visible.height() == child.height && viewportRect.contains(visible)
    }

    /**
     * @param viewport Fixed-pixel clipping viewport.
     * @param child Native descendant.
     * @return Actual descendant bounds in viewport-local pixels.
     */
    private fun boundsInViewport(viewport: View, child: View): Rect {
        val viewportLocation = IntArray(2)
        val childLocation = IntArray(2)
        viewport.getLocationInWindow(viewportLocation)
        child.getLocationInWindow(childLocation)
        val left = childLocation[0] - viewportLocation[0]
        val top = childLocation[1] - viewportLocation[1]
        return Rect(left, top, left + child.width, top + child.height)
    }

    /**
     * @param activity Preview activity.
     * @return Pixel bounds and scroll positions for the captured frame.
     */
    private fun evidenceDetails(activity: StandbyS17ComparisonActivity): String {
        val viewport = activity.viewport
        val windowLocation = IntArray(2)
        viewport.getLocationInWindow(windowLocation)
        val bounds = listOf(
            "foreground_phase_eyebrow", "foreground_failed", "foreground_failed_dismiss",
            "foreground_broadcast", "foreground_menu",
        ).joinToString(" ") { tag ->
            val child = viewport.findViewWithTag<View>(tag)
            "$tag=${child?.let { boundsInViewport(viewport, it).toShortString() } ?: "absent"}"
        }
        val scroll = activity.proposalScroll
        val maximum = scroll?.let { (it.getChildAt(0).height - it.height).coerceAtLeast(0) } ?: 0
        val failed = viewport.findViewWithTag<View>("foreground_failed")
        val noticeScroll = generateSequence(failed?.parent as? View) { it.parent as? View }
            .filterIsInstance<ScrollView>().firstOrNull()
        return "viewportWindow=[${windowLocation[0]},${windowLocation[1]} ${viewport.width}x${viewport.height}] " +
            "scroll=${scroll?.scrollY ?: 0}/$maximum noticeScroll=${noticeScroll?.scrollY ?: 0} $bounds"
    }
}
