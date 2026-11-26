package me.trinitrix.mirax.ui.standby.matter

import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle regression for a hidden ancestor, not a rendering acceptance test. */
@RunWith(AndroidJUnit4::class)
class MatterAncestorVisibilityTest {
    @Test
    fun hiddenAncestor_stopsFramesAndDoesNotAccrueHiddenTime() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            val firstDraws = CountDownLatch(2)
            val clock = AtomicLong(100L)
            var view: MatterParticleView? = null
            var parent: FrameLayout? = null
            var barrierCallback: Choreographer.FrameCallback? = null
            try {
                scenario.onActivity { activity ->
                    val surface = MatterParticleView(activity).apply {
                        clockUptimeMillis = { clock.get() }
                        onFrameDrawn = { firstDraws.countDown() }
                    }
                    val container = FrameLayout(activity).apply {
                        addView(
                            surface,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                    view = surface
                    parent = container
                    activity.contentRoot.addView(
                        container,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                assertTrue("Visible frames did not arrive", firstDraws.await(5, TimeUnit.SECONDS))
                var hiddenDraws = 0
                var hiddenElapsed = 0.0
                scenario.onActivity {
                    val surface = requireNotNull(view)
                    requireNotNull(parent).visibility = View.GONE
                    surface.onFrameDrawn = null
                    hiddenDraws = surface.drawEvidence.drawCount
                    hiddenElapsed = surface.drawEvidence.activeElapsedMillis
                    surface.setProjecting(true)
                    surface.setProjecting(false)
                    clock.addAndGet(100_000L)
                }
                val frameBoundary = CountDownLatch(1)
                scenario.onActivity {
                    val callback = Choreographer.FrameCallback { frameBoundary.countDown() }
                    barrierCallback = callback
                    Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue("Frame boundary did not arrive", frameBoundary.await(5, TimeUnit.SECONDS))
                val resumed = CountDownLatch(1)
                scenario.onActivity {
                    val surface = requireNotNull(view)
                    assertEquals(hiddenDraws, surface.drawEvidence.drawCount)
                    surface.onFrameDrawn = { resumed.countDown() }
                    requireNotNull(parent).visibility = View.VISIBLE
                }
                assertTrue("Resumed frame did not arrive", resumed.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(hiddenElapsed, requireNotNull(view).drawEvidence.activeElapsedMillis, 0.0)
                }
            } finally {
                scenario.onActivity { activity ->
                    barrierCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
                    view?.setProjecting(true)
                    view?.onFrameDrawn = null
                    parent?.let { activity.contentRoot.removeView(it) }
                }
            }
        }
    }

    /** Checks alpha, projection, and attachment gates with bounded frame barriers. */
    @Test
    fun transparentViewOrAncestor_stopsFramesAndRestartsWithoutHiddenTime() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            val clock = AtomicLong(100L)
            val clockReads = AtomicLong()
            val initialFrames = CountDownLatch(2)
            var frameSignal = initialFrames
            var view: MatterParticleView? = null
            var ancestor: FrameLayout? = null
            var observer: ViewTreeObserver? = null
            var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
            var barrierCallback: Choreographer.FrameCallback? = null

            /** Waits for three frame boundaries with a timeout. */
            fun awaitThreeFrames() {
                val frames = CountDownLatch(3)
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        frames.countDown()
                        if (frames.count > 0) {
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                }
                scenario.onActivity {
                    barrierCallback = callback
                    Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue("Frame boundaries did not arrive", frames.await(5, TimeUnit.SECONDS))
            }

            try {
                scenario.onActivity { activity ->
                    val surface = MatterParticleView(activity).apply {
                        alpha = 0f
                        setProjecting(true)
                        alpha = 1f
                        setProjecting(false)
                        clockUptimeMillis = {
                            clockReads.incrementAndGet()
                            frameSignal.countDown()
                            clock.get()
                        }
                    }
                    val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
                    val middle = FrameLayout(activity).apply {
                        addView(surface, ViewGroup.LayoutParams(matchParent, matchParent))
                    }
                    val outer = FrameLayout(activity).apply {
                        addView(middle, ViewGroup.LayoutParams(matchParent, matchParent))
                    }
                    view = surface
                    ancestor = outer
                    activity.contentRoot.addView(outer, ViewGroup.LayoutParams(matchParent, matchParent))
                }
                assertTrue("Initial frames did not arrive", initialFrames.await(5, TimeUnit.SECONDS))
                val tick = CountDownLatch(1)
                scenario.onActivity {
                    frameSignal = tick
                    clock.addAndGet(25L)
                }
                assertTrue("Clock advance was not observed", tick.await(5, TimeUnit.SECONDS))

                for (hideSelf in listOf(false, true)) {
                    val hiddenPreDraw = CountDownLatch(1)
                    var hiddenElapsed = 0.0
                    var hiddenReads = 0L
                    scenario.onActivity {
                        val surface = requireNotNull(view)
                        hiddenElapsed = activeElapsed(surface)
                        assertTrue("Expected accumulated visible time", hiddenElapsed > 0.0)
                        assertTrue("Visible frame was not scheduled", isFrameScheduled(surface))
                        val currentObserver = surface.viewTreeObserver
                        val listener = ViewTreeObserver.OnPreDrawListener {
                            hiddenPreDraw.countDown()
                            true
                        }
                        observer = currentObserver
                        preDrawListener = listener
                        currentObserver.addOnPreDrawListener(listener)
                        val target = if (hideSelf) surface else requireNotNull(ancestor)
                        target.alpha = 0f
                        clock.addAndGet(100_000L)
                    }
                    assertTrue("Transparent pre-draw did not arrive", hiddenPreDraw.await(5, TimeUnit.SECONDS))
                    scenario.onActivity {
                        val currentObserver = observer
                        val listener = preDrawListener
                        if (currentObserver?.isAlive == true && listener != null) {
                            currentObserver.removeOnPreDrawListener(listener)
                        }
                        observer = null
                        preDrawListener = null
                        val surface = requireNotNull(view)
                        assertFalse("Transparent view scheduled a frame", isFrameScheduled(surface))
                        assertEquals(hiddenElapsed, activeElapsed(surface), 0.0)
                        hiddenReads = clockReads.get()
                    }
                    awaitThreeFrames()
                    val resumed = CountDownLatch(2)
                    scenario.onActivity {
                        val surface = requireNotNull(view)
                        assertFalse("Transparent view rescheduled a frame", isFrameScheduled(surface))
                        assertEquals(hiddenReads, clockReads.get())
                        assertEquals(hiddenElapsed, activeElapsed(surface), 0.0)
                        frameSignal = resumed
                        val target = if (hideSelf) surface else requireNotNull(ancestor)
                        target.alpha = 1f
                    }
                    assertTrue("Frames did not resume after alpha recovery", resumed.await(5, TimeUnit.SECONDS))
                    scenario.onActivity {
                        assertEquals(hiddenElapsed, activeElapsed(requireNotNull(view)), 0.0)
                    }
                }

                var projectedElapsed = 0.0
                var projectedReads = 0L
                scenario.onActivity {
                    val surface = requireNotNull(view)
                    assertTrue("Visible frame was not scheduled", isFrameScheduled(surface))
                    projectedElapsed = activeElapsed(surface)
                    projectedReads = clockReads.get()
                    val staleCallback = frameCallback(surface)
                    surface.setProjecting(true)
                    clock.addAndGet(100_000L)
                    staleCallback.doFrame(0L)
                    assertFalse("Stale callback rescheduled during projection", isFrameScheduled(surface))
                    assertEquals(projectedElapsed, activeElapsed(surface), 0.0)
                    assertEquals(projectedReads, clockReads.get())
                }
                awaitThreeFrames()
                val projectionResumed = CountDownLatch(2)
                scenario.onActivity {
                    val surface = requireNotNull(view)
                    assertFalse("Projection rescheduled a frame", isFrameScheduled(surface))
                    assertEquals(projectedElapsed, activeElapsed(surface), 0.0)
                    assertEquals(projectedReads, clockReads.get())
                    frameSignal = projectionResumed
                    surface.setProjecting(false)
                }
                assertTrue("Frames did not resume after projection", projectionResumed.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(projectedElapsed, activeElapsed(requireNotNull(view)), 0.0)
                }

                val reattachedFrames = CountDownLatch(2)
                var detachedElapsed = 0.0
                scenario.onActivity { activity ->
                    val surface = requireNotNull(view)
                    val outer = requireNotNull(ancestor)
                    detachedElapsed = activeElapsed(surface)
                    val staleCallback = frameCallback(surface)
                    activity.contentRoot.removeView(outer)
                    assertFalse("View remained attached", surface.isAttachedToWindow)
                    assertFalse("Detached view scheduled a frame", isFrameScheduled(surface))
                    staleCallback.doFrame(0L)
                    assertFalse("Detached callback rescheduled a frame", isFrameScheduled(surface))
                    clock.addAndGet(100_000L)
                    outer.alpha = 0f
                    val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
                    activity.contentRoot.addView(outer, ViewGroup.LayoutParams(matchParent, matchParent))
                    assertFalse("Transparent reattached view scheduled a frame", isFrameScheduled(surface))
                    frameSignal = reattachedFrames
                    outer.alpha = 1f
                }
                assertTrue("Frames did not resume after reattachment", reattachedFrames.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(detachedElapsed, activeElapsed(requireNotNull(view)), 0.0)
                }
            } finally {
                scenario.onActivity { activity ->
                    val currentObserver = observer
                    val listener = preDrawListener
                    if (currentObserver?.isAlive == true && listener != null) {
                        currentObserver.removeOnPreDrawListener(listener)
                    }
                    barrierCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
                    view?.setProjecting(true)
                    ancestor?.let { activity.contentRoot.removeView(it) }
                }
            }
        }
    }

    /**
     * Reads the scheduler state without changing the public draw-evidence contract.
     * @param view particle view under test.
     * @return Whether a frame is scheduled.
     */
    private fun isFrameScheduled(view: MatterParticleView): Boolean =
        MatterParticleView::class.java.getDeclaredField("frameScheduled")
            .apply { isAccessible = true }
            .getBoolean(view)

    /**
     * Reads elapsed animation time even when a transparent view does not draw.
     * @param view particle view under test.
     * @return Accumulated animation time in milliseconds.
     */
    private fun activeElapsed(view: MatterParticleView): Double =
        MatterParticleView::class.java.getDeclaredField("activeElapsedMillis")
            .apply { isAccessible = true }
            .getDouble(view)

    /**
     * Retrieves the callback to simulate a stale delivery after projection.
     * @param view particle view under test.
     * @return The view's frame callback.
     */
    private fun frameCallback(view: MatterParticleView): Choreographer.FrameCallback =
        MatterParticleView::class.java.getDeclaredField("frameCallback")
            .apply { isAccessible = true }
            .get(view) as Choreographer.FrameCallback
}
