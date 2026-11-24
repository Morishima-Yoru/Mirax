package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Attached geometry, responsive placement, and motion lifecycle contracts. */
@RunWith(AndroidJUnit4::class)
class StandbyForegroundContractTest {
    private fun px(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    ).toInt()

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    /** Sends a touch pair through the root at target-local coordinates. */
    private fun tapAt(root: View, target: View, x: Float, y: Float) {
        val rootLocation = IntArray(2)
        val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        target.getLocationOnScreen(targetLocation)
        val eventX = targetLocation[0] - rootLocation[0] + x
        val eventY = targetLocation[1] - rootLocation[1] + y
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, eventX, eventY, 0)
        val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, eventX, eventY, 0)
        try {
            assertTrue(root.dispatchTouchEvent(down))
            assertTrue(root.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun awaitDraw(scenario: ActivityScenario<StandbyProbeActivity>) {
        val done = CountDownLatch(1)
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        try {
            scenario.onActivity { activity ->
                observer = activity.contentRoot.viewTreeObserver
                val registered = ViewTreeObserver.OnPreDrawListener {
                    done.countDown()
                    true
                }
                listener = registered
                observer?.addOnPreDrawListener(registered)
                activity.contentRoot.invalidate()
            }
            assertTrue("Timed out waiting for a draw", done.await(3, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                val current = observer
                val registered = listener
                if (current != null && current.isAlive && registered != null) {
                    current.removeOnPreDrawListener(registered)
                }
            }
        }
    }

    /**
     * Waits for a UI-thread condition and captures optional timeout evidence.
     *
     * @param scenario Activity hosting the condition.
     * @param diagnostic Optional UI-thread diagnostic snapshot.
     * @param condition Predicate evaluated until ready or timed out.
     */
    private fun awaitCondition(
        scenario: ActivityScenario<StandbyProbeActivity>,
        diagnostic: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        val done = CountDownLatch(1)
        var callback: Runnable? = null
        var failure: Throwable? = null
        try {
            scenario.onActivity { activity ->
                diagnostic?.let { android.util.Log.i("StandbyGeometry", "before condition ${it()}") }
                val deadline = SystemClock.uptimeMillis() + 2500L
                val registered = object : Runnable {
                    override fun run() {
                        if (condition()) {
                            done.countDown()
                        } else if (SystemClock.uptimeMillis() < deadline) {
                            activity.contentRoot.postOnAnimation(this)
                        }
                    }
                }
                callback = registered
                activity.contentRoot.post(registered)
            }
            val completed = done.await(3, TimeUnit.SECONDS)
            var evidence = ""
            if (!completed && diagnostic != null) {
                scenario.onActivity { evidence = diagnostic() }
                android.util.Log.w("StandbyGeometry", "condition timeout $evidence")
            }
            assertTrue("Timed out waiting for the condition $evidence", completed)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                scenario.onActivity { activity -> callback?.let { activity.contentRoot.removeCallbacks(it) } }
            } catch (cleanup: Throwable) {
                val primary = failure ?: throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    /** Checks artwork feedback separately from hit bounds and click routing. */
    @Test
    fun controls_pressFeedbackKeepsHitBoundsThroughUpCancelAndDetach() {
        fun inkBounds(view: View): Rect {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val ink = Rect(view.width, view.height, 0, 0)
            try {
                view.draw(Canvas(bitmap))
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) ushr 24 != 0) {
                        ink.left = minOf(ink.left, x)
                        ink.top = minOf(ink.top, y)
                        ink.right = maxOf(ink.right, x + 1)
                        ink.bottom = maxOf(ink.bottom, y + 1)
                    }
                }
                return ink
            } finally {
                bitmap.recycle()
            }
        }
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var controls: StandbyForegroundControls
            lateinit var target: View
            var broadcastClicks = 0
            var menuClicks = 0
            var pressedAt = 0L
            var originalInk = Rect()
            var originalHit = Rect()
            var originalGlobal = Rect()
            var targetX = 0f
            var targetY = 0f
            var downTime = 0L
            fun send(action: Int, edge: Boolean = false) {
                val x = if (edge) target.right - 0.5f else targetX
                val y = if (edge) target.bottom - 0.5f else targetY
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try {
                    assertTrue(controls.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
            /** @return Broadcast and settings callback counts in that order. */
            fun clickCounts(): Pair<Int, Int> = broadcastClicks to menuClicks

            /**
             * Compares the drawn artwork, not the View's touch rectangle.
             * @param evidence the theme, control, and gesture checkpoint.
             * @param expectShrink true for broadcast and retained Matter feedback.
             */
            fun assertHeldInk(evidence: String, expectShrink: Boolean) {
                val heldInk = inkBounds(target)
                assertFalse(evidence, heldInk.isEmpty)
                if (expectShrink) {
                    assertTrue("$evidence: held=$heldInk idle=$originalInk", heldInk.width() < originalInk.width())
                } else {
                    assertEquals(evidence, originalInk, heldInk)
                }
            }

            try {
                for (theme in StandbyControlTheme.entries) {
                    scenario.onActivity { activity ->
                        controls = StandbyForegroundControls(activity, theme).apply {
                            viewportWidth = activity.contentRoot.width
                            viewportHeight = activity.contentRoot.height
                            isFocusableInTouchMode = true
                            update(StandbyForegroundState(ScreenPhase.READY, false), StandbyForegroundCallbacks(
                                onToggleBroadcast = { broadcastClicks++ }, onOpenSettings = { menuClicks++ },
                            ))
                        }
                        activity.contentRoot.addView(controls, ViewGroup.LayoutParams(-1, -1))
                    }
                    awaitDraw(scenario)
                    for (tag in listOf(StandbyForegroundTags.BROADCAST, StandbyForegroundTags.MENU)) {
                        val isMenu = tag == StandbyForegroundTags.MENU
                        val expectShrink = !isMenu || theme == StandbyControlTheme.MATTER
                        val evidence = "$theme/$tag"
                        var expectedClicks = 0 to 0
                        scenario.onActivity { activity ->
                            target = requireNotNull(controls.findViewWithTag<View>(tag))
                            controls.requestFocus()
                            assertTrue(evidence, target.isFocusable && target.isClickable)
                            assertTrue(target.width >= px(activity, 48f) && target.height >= px(activity, 48f))
                            originalInk = inkBounds(target)
                            assertFalse(originalInk.isEmpty)
                            originalHit = Rect().also(target::getHitRect)
                            originalGlobal = Rect().also { assertTrue(target.getGlobalVisibleRect(it)) }
                            targetX = target.left + target.width / 2f
                            targetY = target.top + target.height / 2f
                            expectedClicks = if (isMenu) broadcastClicks to (menuClicks + 1) else (broadcastClicks + 1) to menuClicks
                            downTime = SystemClock.uptimeMillis()
                            pressedAt = downTime
                            send(MotionEvent.ACTION_DOWN)
                        }
                        awaitCondition(scenario) { SystemClock.uptimeMillis() - pressedAt >= 190L }
                        scenario.onActivity {
                            assertTrue(target.isPressed)
                            assertEquals(originalHit, Rect().also(target::getHitRect))
                            assertEquals(originalGlobal, Rect().also { assertTrue(target.getGlobalVisibleRect(it)) })
                            assertHeldInk(evidence, expectShrink)
                            // The full View edge remains a valid hit regardless of the artwork feedback.
                            send(MotionEvent.ACTION_MOVE, edge = true)
                            send(MotionEvent.ACTION_UP, edge = true)
                            if (!expectShrink) assertEquals(evidence, originalInk, inkBounds(target))
                        }
                        awaitCondition(scenario, diagnostic = { "$evidence expected=$expectedClicks actual=${clickCounts()}" }) {
                            clickCounts() == expectedClicks
                        }
                        scenario.onActivity {
                            controls.requestFocus()
                            downTime = SystemClock.uptimeMillis()
                            pressedAt = downTime
                            send(MotionEvent.ACTION_DOWN)
                        }
                        awaitCondition(scenario) { SystemClock.uptimeMillis() - pressedAt >= 190L }
                        scenario.onActivity {
                            assertEquals(originalHit, Rect().also(target::getHitRect))
                            assertEquals(originalGlobal, Rect().also { assertTrue(target.getGlobalVisibleRect(it)) })
                            assertHeldInk(evidence, expectShrink)
                            send(MotionEvent.ACTION_CANCEL)
                            pressedAt = SystemClock.uptimeMillis()
                            assertFalse(target.isPressed)
                            if (!expectShrink) assertEquals(evidence, originalInk, inkBounds(target))
                        }
                        awaitCondition(scenario) { SystemClock.uptimeMillis() - pressedAt >= 190L }
                        scenario.onActivity {
                            assertEquals(evidence, expectedClicks, clickCounts())
                            assertEquals(evidence, originalInk, inkBounds(target))
                        }
                    }
                    var beforeDetachClicks = 0 to 0
                    scenario.onActivity {
                        beforeDetachClicks = clickCounts()
                        downTime = SystemClock.uptimeMillis()
                        pressedAt = downTime
                        send(MotionEvent.ACTION_DOWN)
                    }
                    awaitCondition(scenario) { SystemClock.uptimeMillis() - pressedAt >= 190L }
                    scenario.onActivity { activity ->
                        val evidence = "$theme/${StandbyForegroundTags.MENU} detach"
                        assertTrue(evidence, target.isAttachedToWindow && target.isPressed)
                        assertEquals(originalHit, Rect().also(target::getHitRect))
                        assertEquals(originalGlobal, Rect().also { assertTrue(target.getGlobalVisibleRect(it)) })
                        assertHeldInk(evidence, expectShrink = theme == StandbyControlTheme.MATTER)
                        activity.contentRoot.removeView(controls)
                        assertFalse(evidence, target.isAttachedToWindow)
                        assertEquals(evidence, originalInk, inkBounds(target))
                        assertEquals(evidence, beforeDetachClicks, clickCounts())
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun notices_keepCompleteTextBeforeAnySideCandidate() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (theme in StandbyControlTheme.entries) {
                    val controls = StandbyForegroundControls(activity, theme).apply {
                        viewportWidth = px(activity, 600f)
                        viewportHeight = px(activity, 500f)
                    }
                    for (phase in listOf(ScreenPhase.READY, ScreenPhase.FROZEN, ScreenPhase.WIFI_PAUSED)) {
                        controls.update(StandbyForegroundState(phase, true,
                            shouldOfferShizuku = true, failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                            failureText = "Complete failure explanation. ".repeat(80)),
                            StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {},
                                onShowFrozenInfo = {}, onOpenShizuku = {}, onStopBroadcastWhilePaused = {}))
                        layout(controls, controls.viewportWidth, controls.viewportHeight)
                        assertNull(controls.parent)
                        val scroll = controls.getChildAt(0) as ScrollView
                        assertTrue(scroll.getChildAt(0).height > scroll.height)
                        for (tag in listOf(StandbyForegroundTags.FAILED,
                            StandbyForegroundTags.WIFI_WARNING, StandbyForegroundTags.FAILED_DISMISS,
                            StandbyForegroundTags.FROZEN_INFO, "foreground_shizuku", StandbyForegroundTags.WIFI_STOP)) {
                            val text = requireNotNull(controls.findViewWithTag<TextView>(tag))
                            if (text.visibility != View.VISIBLE) continue
                            val textLayout = requireNotNull(text.layout)
                            assertNull(text.ellipsize)
                            assertEquals(Int.MAX_VALUE, text.maxLines)
                            assertEquals(text.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                            for (line in 0 until textLayout.lineCount) {
                                assertEquals(0, textLayout.getEllipsisCount(line))
                                assertTrue(textLayout.getLineLeft(line) >= -0.5f)
                                assertTrue(textLayout.getLineRight(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 0.5f)
                                assertTrue(textLayout.getLineBottom(line) <= text.height - text.extendedPaddingTop - text.extendedPaddingBottom)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var foreground: HardlineStandbyForegroundView
            val changes = mutableListOf<Rect?>()
            var childLayoutChecked = false
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    foreground = HardlineStandbyForegroundView(activity)
                    foreground.onForegroundGeometryChanged = { geometry ->
                        changes.add(geometry?.header?.let(::Rect))
                        geometry?.header?.setEmpty()
                        if (geometry != null) {
                            foreground.render(
                                StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Receiver"),
                                "Title", "READY", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}),
                            )
                        }
                    }
                    foreground.render(
                        StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Receiver"),
                        "Title", "READY", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}),
                    )
                    requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                        .addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            if (!childLayoutChecked) {
                                android.util.Log.i("StandbyGeometry", foregroundSnapshot(
                                    "attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates child-layout fixtureInsets=0",
                                    foreground,
                                ))
                            }
                            childLayoutChecked = true
                            assertNull(foreground.foregroundRectsInViewPixels())
                        }
                    parent.addView(foreground, ViewGroup.LayoutParams(-1, -1))
                    activity.contentRoot.addView(parent, ViewGroup.LayoutParams(px(activity, 280f) + 1, px(activity, 400f) + 3))
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates fixtureInsets=0 changes=${changes.size} lastCallback=${changes.lastOrNull()}", foreground)
                }) { foreground.foregroundRectsInViewPixels() != null }
                lateinit var before: Rect
                var count = 0
                scenario.onActivity {
                    assertTrue(childLayoutChecked)
                    before = Rect(requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    assertFalse(before.isEmpty)
                    assertNotNull(changes.last())
                    count = changes.size
                }
                awaitDraw(scenario)
                awaitDraw(scenario)
                scenario.onActivity { assertEquals(count, changes.size) }
                scenario.onActivity {
                    foreground.motionLayer.pivotX = 0f
                    foreground.motionLayer.pivotY = 0f
                    foreground.motionLayer.scaleX = 1.1f
                    foreground.motionLayer.scaleY = 1.1f
                    foreground.motionLayer.translationX = 8f
                    foreground.motionLayer.translationY = 10f
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates transformed fixtureInsets=0 changes=${changes.size} lastCallback=${changes.lastOrNull()}", foreground)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    val pixels = requireNotNull(foreground.foregroundRectsInViewPixels())
                    assertTrue(pixels.header.left > before.left)
                    assertTrue(pixels.header.top > before.top)
                    assertTrue(changes.size > count)
                    val stage = requireNotNull(foreground.foregroundRectsInStageSpace())
                    val scale = maxOf(foreground.width / 1600.0, foreground.height / 1000.0)
                    val offsetX = (foreground.width - 1600.0 * scale) / 2.0
                    val offsetY = (foreground.height - 1000.0 * scale) / 2.0
                    val edges = listOf(
                        (pixels.header.left - offsetX) / scale, (pixels.header.top - offsetY) / scale,
                        (pixels.header.right - offsetX) / scale, (pixels.header.bottom - offsetY) / scale,
                    )
                    assertTrue(edges.any { it != floor(it) })
                    assertEquals(floor((pixels.header.left - offsetX) / scale).toInt(), stage.header.left)
                    assertEquals(floor((pixels.header.top - offsetY) / scale).toInt(), stage.header.top)
                    assertEquals(ceil((pixels.header.right - offsetX) / scale).toInt(), stage.header.right)
                    assertEquals(ceil((pixels.header.bottom - offsetY) / scale).toInt(), stage.header.bottom)
                    parent.clipBounds = Rect(0, 0, pixels.header.centerX(), parent.height)
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates clipped fixtureInsets=0 changes=${changes.size} lastCallback=${changes.lastOrNull()}", foreground)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    val clipped = requireNotNull(foreground.foregroundRectsInViewPixels()).header
                    assertTrue(clipped.right <= requireNotNull(parent.clipBounds).right)
                    assertTrue(clipped.width() > 0)
                    parent.clipBounds = Rect(0, 0, 1, 1)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertNull(foreground.foregroundRectsInViewPixels())
                    assertNull(changes.last())
                    parent.clipBounds = null
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_tracksTransformsClipsAndInvalidationWithoutDuplicates restored fixtureInsets=0 changes=${changes.size} lastCallback=${changes.lastOrNull()}", foreground)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    assertNotNull(foreground.foregroundRectsInViewPixels())
                    parent.removeView(foreground)
                    assertNull(foreground.foregroundRectsInViewPixels())
                    assertNull(changes.last())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun attachedGeometry_respectsTargetClipBounds() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var foreground: HardlineStandbyForegroundView
            lateinit var target: View
            lateinit var full: Rect
            lateinit var partial: Rect
            try {
                scenario.onActivity { activity ->
                    foreground = HardlineStandbyForegroundView(activity)
                    foreground.render(
                        StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Receiver"),
                        "Title", "READY", "Detail", StandbyForegroundCallbacks(),
                    )
                    val brand = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                    target = brand.parent as View
                    activity.contentRoot.addView(foreground, ViewGroup.LayoutParams(px(activity, 280f), px(activity, 400f)))
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_respectsTargetClipBounds fixtureInsets=0", foreground, target)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    assertTrue(foreground.isAttachedToWindow)
                    full = Rect(requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    val bounds = Rect(0, 0, target.width, target.height)
                    foreground.offsetDescendantRectToMyCoords(target, bounds)
                    assertEquals(bounds, full)
                    target.clipBounds = Rect(0, 0, target.width, target.height)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(full, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    target.clipBounds = Rect(-1, -1, target.width + 1, target.height + 1)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(full, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    val clip = Rect(target.width / 4, target.height / 4, target.width * 3 / 4, target.height * 3 / 4)
                    partial = Rect(clip)
                    foreground.offsetDescendantRectToMyCoords(target, partial)
                    target.clipBounds = clip
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(partial, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    target.translationX = 5f
                    target.translationY = 7f
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(Rect(partial).apply { offset(5, 7) }, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    target.translationX = 0f
                    target.translationY = 0f
                    target.clipBounds = Rect(target.width, 0, target.width + 1, target.height)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertNull(foreground.foregroundRectsInViewPixels())
                    target.clipBounds = null
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_respectsTargetClipBounds restored fixtureInsets=0", foreground, target)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity { assertEquals(full, requireNotNull(foreground.foregroundRectsInViewPixels()).header) }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Verifies rectangular ancestor outlines and conservatively rejects rounded ones. */
    @Test
    fun attachedGeometry_respectsTransformedAncestorOutline() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            var parent: FrameLayout? = null
            var originalProvider: ViewOutlineProvider? = null
            var originalClipToOutline = false
            lateinit var foreground: HardlineStandbyForegroundView
            lateinit var target: View
            lateinit var full: Rect
            lateinit var partial: Rect
            lateinit var outlineBounds: Rect
            try {
                scenario.onActivity { activity ->
                    val container = FrameLayout(activity).apply {
                        pivotX = 0f
                        pivotY = 0f
                        scaleX = 2f
                        scaleY = 1f
                        translationX = 11f
                        translationY = 7f
                    }
                    parent = container
                    originalProvider = container.outlineProvider
                    originalClipToOutline = container.clipToOutline
                    foreground = HardlineStandbyForegroundView(activity)
                    foreground.render(
                        StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Receiver"),
                        "Title", "READY", "Detail", StandbyForegroundCallbacks(),
                    )
                    val brand = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                    target = brand.parent as View
                    container.addView(foreground, ViewGroup.LayoutParams(-1, -1))
                    activity.contentRoot.addView(container, ViewGroup.LayoutParams(px(activity, 160f), px(activity, 300f)))
                }
                awaitForegroundLayout(scenario, requireNotNull(parent), foreground, target)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_respectsTransformedAncestorOutline fixtureInsets=0", foreground, target)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    val container = requireNotNull(parent)
                    val visible = Rect()
                    assertTrue(container.getGlobalVisibleRect(visible))
                    assertEquals(container.width * 2, visible.width())
                    assertEquals(container.height, visible.height())
                    full = Rect(0, 0, target.width, target.height)
                    foreground.offsetDescendantRectToMyCoords(target, full)
                    assertEquals(full, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    outlineBounds = Rect(0, 0, full.centerX(), container.height)
                    partial = Rect(full)
                    assertTrue(partial.intersect(outlineBounds))
                    assertTrue(partial.right < full.right)
                    container.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRect(outlineBounds)
                        }
                    }
                    container.clipToOutline = true
                    container.invalidateOutline()
                    assertTrue(container.clipToOutline)
                    val actual = Outline()
                    requireNotNull(container.outlineProvider).getOutline(container, actual)
                    val bounds = Rect()
                    assertFalse(actual.isEmpty)
                    assertTrue(actual.getRect(bounds))
                    assertEquals(outlineBounds, bounds)
                    assertEquals(0f, actual.radius, 0f)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(partial, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                    val container = requireNotNull(parent)
                    container.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(outlineBounds, 12f)
                        }
                    }
                    container.invalidateOutline()
                    val actual = Outline()
                    requireNotNull(container.outlineProvider).getOutline(container, actual)
                    assertTrue(actual.radius > 0f)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertNull(foreground.foregroundRectsInViewPixels())
                    requireNotNull(parent).apply {
                        clipToOutline = originalClipToOutline
                        outlineProvider = originalProvider
                        invalidateOutline()
                    }
                }
                awaitDraw(scenario)
                awaitCondition(scenario, {
                    foregroundSnapshot("attachedGeometry_respectsTransformedAncestorOutline restored fixtureInsets=0", foreground, target)
                }) { foreground.foregroundRectsInViewPixels() != null }
                scenario.onActivity {
                    assertEquals(full, requireNotNull(foreground.foregroundRectsInViewPixels()).header)
                }
            } finally {
                scenario.onActivity { activity ->
                    parent?.apply {
                        clipToOutline = originalClipToOutline
                        outlineProvider = originalProvider
                    }
                    activity.contentRoot.removeAllViews()
                }
            }
        }
    }

    @Test
    fun attachedHiddenGeometryCallback_reportsNullImmediately() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var landscape: LandscapeStandbyForegroundView
            lateinit var hardline: HardlineStandbyForegroundView
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    landscape = LandscapeStandbyForegroundView(activity)
                    hardline = HardlineStandbyForegroundView(activity)
                    val state = StandbyForegroundState(ScreenPhase.READY, false)
                    landscape.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    hardline.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    parent.addView(landscape, ViewGroup.LayoutParams(-1, -1))
                    parent.addView(hardline, ViewGroup.LayoutParams(-1, -1))
                    activity.contentRoot.addView(parent, ViewGroup.LayoutParams(-1, -1))
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertTrue(landscape.isAttachedToWindow)
                    assertTrue(hardline.isAttachedToWindow)
                    parent.visibility = View.INVISIBLE
                    val landscapeResults = mutableListOf<LandscapeForegroundGeometry?>()
                    val hardlineResults = mutableListOf<HardlineForegroundPixelRects?>()
                    val landscapeCallback: (LandscapeForegroundGeometry?) -> Unit = { landscapeResults.add(it) }
                    val hardlineCallback: (HardlineForegroundPixelRects?) -> Unit = { hardlineResults.add(it) }
                    landscape.onForegroundGeometryChanged = landscapeCallback
                    hardline.onForegroundGeometryChanged = hardlineCallback
                    assertEquals(1, landscapeResults.size)
                    assertEquals(1, hardlineResults.size)
                    assertNull(landscapeResults.single())
                    assertNull(hardlineResults.single())
                    landscape.onForegroundGeometryChanged = landscapeCallback
                    hardline.onForegroundGeometryChanged = hardlineCallback
                    assertEquals(1, landscapeResults.size)
                    assertEquals(1, hardlineResults.size)
                    parent.visibility = View.VISIBLE
                    landscape.motionLayer.alpha = 0f
                    hardline.motionLayer.visibility = View.INVISIBLE
                    val transparentResults = mutableListOf<LandscapeForegroundGeometry?>()
                    val hiddenLayerResults = mutableListOf<HardlineForegroundPixelRects?>()
                    landscape.onForegroundGeometryChanged = { transparentResults.add(it) }
                    hardline.onForegroundGeometryChanged = { hiddenLayerResults.add(it) }
                    assertEquals(1, transparentResults.size)
                    assertEquals(1, hiddenLayerResults.size)
                    assertNull(transparentResults.single())
                    assertNull(hiddenLayerResults.single())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun density213_touchTargetsRoundUpWithoutScalingMenuArtwork() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                for (theme in listOf(StandbyControlTheme.LANDSCAPE, StandbyControlTheme.MATTER, StandbyControlTheme.HARDLINE)) {
                    lateinit var controls: StandbyForegroundControls
                    var dismissClicks = 0
                    var broadcastClicks = 0
                    var menuClicks = 0
                    scenario.onActivity { activity ->
                        val configuration = Configuration(activity.resources.configuration).apply { densityDpi = 213 }
                        val context = activity.createConfigurationContext(configuration)
                        assertEquals(213, context.resources.displayMetrics.densityDpi)
                        controls = StandbyForegroundControls(context, theme)
                        controls.viewportWidth = px(context, 199f)
                        controls.viewportHeight = px(context, 300f)
                        controls.update(
                            StandbyForegroundState(
                                ScreenPhase.ADVERTISING, true,
                                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                                failureText = "Host timeout",
                            ),
                            StandbyForegroundCallbacks(
                                onDismissFailure = { dismissClicks++ },
                                onToggleBroadcast = { broadcastClicks++ },
                                onOpenSettings = { menuClicks++ },
                            ),
                        )
                        activity.contentRoot.addView(
                            controls, ViewGroup.LayoutParams(controls.viewportWidth, controls.viewportHeight),
                        )
                    }
                    awaitDraw(scenario)
                    scenario.onActivity {
                        val context = controls.context
                        val density = context.resources.displayMetrics.density
                        assertEquals(213, context.resources.displayMetrics.densityDpi)
                        val minimum = ceil(48.0 * density).toInt()
                        assertEquals(64, minimum)
                        val menu = requireNotNull(controls.findViewWithTag<View>(StandbyForegroundTags.MENU))
                        val dock = requireNotNull(controls.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        val dismiss = requireNotNull(controls.findViewWithTag<TextView>(StandbyForegroundTags.FAILED_DISMISS))
                        val viewport = StandbyViewportScale(controls.viewportWidth, controls.viewportHeight, density,
                            cssPixels = theme != StandbyControlTheme.MATTER)
                        assertEquals(minimum, menu.width)
                        assertEquals(maxOf(minimum, viewport.roundedPx(88f)), menu.height)
                        assertEquals(maxOf(minimum, viewport.roundedPx(if (theme == StandbyControlTheme.HARDLINE) 84f else 96f)), dock.width)
                        assertEquals(dock.width, dock.height)
                        assertTrue(dismiss.minimumWidth >= minimum)
                        assertTrue(dismiss.minimumHeight >= minimum)
                        assertTrue(dismiss.maxWidth >= minimum)
                        for (target in listOf(menu, dock, dismiss)) {
                            assertTrue(target.width >= minimum)
                            assertTrue(target.height >= minimum)
                            assertTrue(target.isFocusable)
                        }
                        menu.clearFocus()
                        assertFalse(menu.isFocused)
                        val bitmap = Bitmap.createBitmap(menu.width, menu.height, Bitmap.Config.ARGB_8888)
                        try {
                            menu.draw(Canvas(bitmap))
                            val artWidth = ceil(viewport.px(24f).toDouble()).toInt()
                            var hasArtwork = false
                            for (y in 0 until bitmap.height) {
                                for (x in 0 until bitmap.width) {
                                    if (bitmap.getPixel(x, y) ushr 24 != 0) {
                                        hasArtwork = true
                                        assertTrue("Menu artwork exceeds 24dp", x < artWidth)
                                    }
                                }
                            }
                            assertTrue(hasArtwork)
                        } finally {
                            bitmap.recycle()
                        }
                        for (target in listOf(menu, dock, dismiss)) {
                            assertTrue(target.requestFocusFromTouch())
                            val visible = Rect()
                            val evidence = foregroundSnapshot("density213_touchTargetsRoundUpWithoutScalingMenuArtwork theme=$theme fixtureInsets=0 minimum=$minimum scroll=${target.scrollX},${target.scrollY}", controls, target)
                            assertTrue(evidence, target.getLocalVisibleRect(visible))
                            // Local visible coordinates include the target's own content scroll.
                            visible.offset(-target.scrollX, -target.scrollY)
                            assertTrue("$evidence localVisible=$visible", visible.left <= 0 && visible.top <= 0)
                            assertTrue("$evidence localVisible=$visible", visible.right >= target.width && visible.bottom >= target.height)
                            tapAt(controls, target, 0.5f, 0.5f)
                            tapAt(controls, target, minimum - 0.5f, minimum - 0.5f)
                        }
                    }
                    awaitCondition(scenario) { menuClicks >= 2 && broadcastClicks >= 2 && dismissClicks >= 2 }
                    scenario.onActivity { activity ->
                        assertEquals(2, menuClicks)
                        assertEquals(2, broadcastClicks)
                        assertEquals(2, dismissClicks)
                        activity.contentRoot.removeView(controls)
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun density213_threeColumnNoticeStacksBelowRoundedTouchThreshold() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var controls: StandbyForegroundControls
            lateinit var row: LinearLayout
            try {
                scenario.onActivity { activity ->
                    val configuration = Configuration(activity.resources.configuration).apply { densityDpi = 213 }
                    val context = activity.createConfigurationContext(configuration)
                    assertEquals(213, context.resources.displayMetrics.densityDpi)
                    controls = StandbyForegroundControls(context, StandbyControlTheme.LANDSCAPE)
                    val minimum = ceil(48.0 * context.resources.displayMetrics.density).toInt()
                    assertEquals(64, minimum)
                    controls.viewportWidth = 3 * minimum - 1
                    controls.viewportHeight = px(context, 180f)
                    controls.update(
                        StandbyForegroundState(
                            ScreenPhase.FROZEN, false, shouldOfferShizuku = true,
                        ),
                        StandbyForegroundCallbacks(onShowFrozenInfo = {}, onOpenShizuku = {}),
                    )
                    row = requireNotNull(controls.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO))
                        .parent as LinearLayout
                    activity.contentRoot.addView(
                        controls, ViewGroup.LayoutParams(controls.viewportWidth, controls.viewportHeight),
                    )
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(191, row.width)
                     assertEquals(LinearLayout.VERTICAL, row.orientation)
                    for (index in 1..2) {
                        val action = row.getChildAt(index) as TextView
                        assertTrue(action.width >= 64)
                        assertTrue(action.height >= 64)
                        assertTrue(action.maxWidth >= 64)
                    }
                    controls.viewportWidth += 1
                    controls.layoutParams = controls.layoutParams.apply { width = controls.viewportWidth }
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(192, row.width)
                    assertEquals(LinearLayout.HORIZONTAL, row.orientation)
                    for (index in 1..2) {
                        val action = row.getChildAt(index) as TextView
                        assertTrue(action.width >= 64)
                        assertTrue(action.height >= 64)
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun shortLandscape_failureAndDockKeepSeparateFullTouchTargets() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                for (theme in listOf(StandbyControlTheme.LANDSCAPE, StandbyControlTheme.MATTER, StandbyControlTheme.HARDLINE)) {
                    lateinit var foreground: StandbyForegroundView
                    var dismissClicks = 0
                    var broadcastClicks = 0
                    scenario.onActivity { activity ->
                        foreground = when (theme) {
                            StandbyControlTheme.LANDSCAPE -> LandscapeStandbyForegroundView(activity)
                            StandbyControlTheme.MATTER -> MatterStandbyForegroundView(activity)
                            StandbyControlTheme.HARDLINE -> HardlineStandbyForegroundView(activity)
                        }
                        foreground.render(
                            StandbyForegroundState(
                                ScreenPhase.ADVERTISING, true,
                                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                                failureText = "Host timeout",
                            ),
                            "Title", "ADVERTISING", "Detail",
                            StandbyForegroundCallbacks(
                                onDismissFailure = { dismissClicks++ },
                                onToggleBroadcast = { broadcastClicks++ },
                            ),
                        )
                        activity.contentRoot.addView(
                            foreground, ViewGroup.LayoutParams(px(activity, 360f), px(activity, 140f)),
                        )
                    }
                    awaitDraw(scenario)
                    scenario.onActivity {
                        ViewCompat.dispatchApplyWindowInsets(
                            foreground,
                            WindowInsetsCompat.Builder().setInsets(
                                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(), Insets.NONE,
                            ).build(),
                        )
                    }
                    awaitDraw(scenario)
                    scenario.onActivity { activity ->
                        assertTrue(foreground.isAttachedToWindow)
                        val failed = requireNotNull(foreground.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        val dismiss = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                        val dock = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        assertEquals(View.VISIBLE, failed.visibility)
                        assertEquals("Host timeout", failed.text.toString())
                        val minimum = ceil(48.0 * activity.resources.displayMetrics.density).toInt()
                        val dismissVisible = Rect()
                        val dockVisible = Rect()
                        assertTrue(dismiss.getGlobalVisibleRect(dismissVisible))
                        assertTrue(dock.getGlobalVisibleRect(dockVisible))
                        assertTrue(dismiss.width >= minimum)
                        assertTrue(dismiss.height >= minimum)
                        assertTrue(dismissVisible.width() >= minimum)
                        assertTrue(dismissVisible.height() >= minimum)
                        assertTrue(dockVisible.width() >= minimum)
                        assertTrue(dockVisible.height() >= minimum)
                        assertFalse(Rect.intersects(dismissVisible, dockVisible))
                        assertTrue(dismiss.requestFocusFromTouch())
                        val dismissLocal = Rect()
                        assertTrue(dismiss.getLocalVisibleRect(dismissLocal))
                        assertTrue(dismissLocal.left <= 0 && dismissLocal.top <= 0)
                        assertTrue(dismissLocal.right >= minimum && dismissLocal.bottom >= minimum)
                        tapAt(foreground, dismiss, 0.5f, 0.5f)
                        tapAt(foreground, dismiss, minimum - 0.5f, minimum - 0.5f)
                        assertTrue(dock.requestFocusFromTouch())
                        tapAt(foreground, dock, dock.width / 2f, dock.height / 2f)
                    }
                    awaitCondition(scenario) { dismissClicks >= 2 && broadcastClicks >= 1 }
                    scenario.onActivity { activity ->
                        assertEquals(2, dismissClicks)
                        assertEquals(1, broadcastClicks)
                        activity.contentRoot.removeView(foreground)
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun stackedNotices_scrollFocusedActionFullyIntoView() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            for ((strict, widthDp, heightDp) in listOf(Triple(false, 143f, 140f), Triple(true, 216f, 144f))) {
                val fixture = "stackedNotices ${if (strict) "strict" else "stress"} ${widthDp}x${heightDp}dp"
                var ownedControls: StandbyForegroundControls? = null
                var failure: Throwable? = null
                lateinit var actions: List<TextView>
                lateinit var scroll: ScrollView
                var clicks = 0
                var otherClicks = 0
                fun boundsInScroll(target: View): Rect = Rect().also {
                    target.getDrawingRect(it)
                    scroll.offsetDescendantRectToMyCoords(target, it)
                }
                fun scrollViewport(): Rect = Rect(
                    scroll.paddingLeft, scroll.paddingTop, scroll.width - scroll.paddingRight, scroll.height - scroll.paddingBottom,
                ).also { it.offset(scroll.scrollX, scroll.scrollY) }
                fun isFullyRevealed(target: View): Boolean {
                    val visible = Rect()
                    if (!target.isFocused || !target.getLocalVisibleRect(visible)) return false
                    visible.offset(-target.scrollX, -target.scrollY)
                    return visible.contains(0, 0, target.width, target.height) && scrollViewport().contains(boundsInScroll(target))
                }
                // The standalone controls have no host foreground obstacles; use the real dock and layout pass.
                val placeNotices = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    ownedControls?.avoidForegroundObstacles(emptyList(), emptyList())
                }
                try {
                    scenario.onActivity { activity ->
                        val controls = StandbyForegroundControls(activity, StandbyControlTheme.LANDSCAPE)
                        ownedControls = controls
                        controls.viewportWidth = px(activity, widthDp)
                        controls.viewportHeight = px(activity, heightDp)
                        controls.update(
                            StandbyForegroundState(
                                ScreenPhase.FROZEN, false, shouldOfferShizuku = true,
                                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Host timeout",
                            ),
                            StandbyForegroundCallbacks(
                                onDismissFailure = { otherClicks++ }, onShowFrozenInfo = { otherClicks++ },
                                onOpenShizuku = { clicks++ },
                            ),
                        )
                        if (strict) controls.addOnLayoutChangeListener(placeNotices)
                        activity.contentRoot.addView(
                            controls, ViewGroup.LayoutParams(controls.viewportWidth, controls.viewportHeight),
                        )
                    }
                    val controls = requireNotNull(ownedControls)
                    awaitForegroundLayout(scenario, controls)
                    scenario.onActivity { activity ->
                        val actionLabels = listOf(
                            StandbyForegroundTags.FAILED_DISMISS to R.string.preplay_failed_dismiss,
                            StandbyForegroundTags.FROZEN_INFO to R.string.frozen_dialog_title,
                            "foreground_shizuku" to R.string.frozen_open_shizuku,
                        )
                        actions = actionLabels.map { (tag, labelId) ->
                            requireNotNull(controls.findViewWithTag<TextView>(tag)).also {
                                assertEquals("$fixture $tag copy", controls.context.getString(labelId), it.text.toString())
                            }
                        }
                        val action = actions.last()
                        val row = action.parent as LinearLayout
                        scroll = (row.parent as View).parent as ScrollView
                        val density = controls.resources.displayMetrics.density
                        val usableShortDp = minOf(controls.width - controls.paddingLeft - controls.paddingRight,
                            controls.height - controls.paddingTop - controls.paddingBottom) / density
                        val evidence = foregroundSnapshot(
                            "$fixture usableShortDp=$usableShortDp childHeight=${scroll.getChildAt(0).height}", controls, action, scroll,
                        )
                        val expectedSize = if (strict) 324 to 216 else 214 to 210
                        assertEquals(evidence, 1.5f, density, 0f)
                        assertEquals(evidence, expectedSize, controls.measuredWidth to controls.measuredHeight)
                        assertEquals(evidence, expectedSize, controls.width to controls.height)
                        assertEquals(evidence, strict, usableShortDp >= 144f)
                        assertSame(evidence, activity.contentRoot, controls.parent)
                        assertEquals(evidence, LinearLayout.VERTICAL, row.orientation)
                                        assertTrue(evidence, scroll.getChildAt(0).height > scroll.height)
                        val minimum = ceil(48.0 * density).toInt()
                        for (target in actions) {
                            assertTrue("$evidence target=${target.tag}", target.isAttachedToWindow && target.isLaidOut && target.isShown &&
                                minOf(target.measuredWidth, target.measuredHeight, target.width, target.height) >= minimum)
                        }
                        val notice = requireNotNull(controls.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                                        assertEquals(evidence, "Host timeout", notice.text.toString())
                                                        if (strict) {
                            val dock = controls.broadcast
                            val side = Rect(scroll.left, scroll.top, scroll.right, scroll.bottom)
                            assertTrue("$evidence side=$side dockRight=${dock.right}",
                                scroll.parent === controls && dock.parent === controls && dock.isShown && !side.isEmpty &&
                                    side.left > dock.right && Rect(0, 0, controls.width, controls.height).contains(side))
                            val viewport = scrollViewport()
                            val content = boundsInScroll(scroll.getChildAt(0))
                            for (text in listOf(notice) + actions) {
                                val allocation = boundsInScroll(text)
                                val message = "$fixture ${text.tag} allocation=$allocation content=$content viewport=$viewport"
                                assertTrue(message, text.isAttachedToWindow && text.isLaidOut && text.isShown &&
                                    !allocation.isEmpty && content.contains(allocation) &&
                                    allocation.left >= viewport.left && allocation.right <= viewport.right)
                                assertCompleteText(text, message)
                            }
                            for (target in actions) {
                                assertTrue("$fixture ${target.tag} target=${target.width}x${target.height} viewport=$viewport",
                                    maxOf(target.measuredWidth, target.width) <= viewport.width() &&
                                        maxOf(target.measuredHeight, target.height) <= viewport.height())
                            }
                        }
                        assertTrue(evidence, action.requestFocusFromTouch())
                        assertTrue(evidence, action.isFocused)
                        assertEquals(evidence, 0, clicks + otherClicks)
                    }
                    val action = actions.last()
                    awaitCondition(scenario, { foregroundSnapshot(fixture, controls, action, scroll) }) {
                        action.isFocused && scroll.scrollY > 0 && (!strict || isFullyRevealed(action))
                    }
                    if (strict) {
                        scenario.onActivity {
                            val minimum = ceil(48.0 * controls.resources.displayMetrics.density).toInt()
                            assertTrue(fixture, scroll.scrollY > 0 && isFullyRevealed(action))
                            assertEquals(fixture, 0, clicks + otherClicks)
                            tapAt(controls, action, 0.5f, 0.5f)
                            tapAt(controls, action, minimum - 0.5f, minimum - 0.5f)
                        }
                        awaitCondition(scenario) { clicks >= 2 }
                        // Check the earlier actions only after the original unprimed Shizuku reveal and taps.
                        for (target in actions.dropLast(1)) {
                            scenario.onActivity { assertTrue("$fixture focus ${target.tag}", target.requestFocusFromTouch()) }
                            awaitCondition(scenario, { foregroundSnapshot(fixture, controls, target, scroll) }) {
                                isFullyRevealed(target)
                            }
                        }
                    }
                    scenario.onActivity {
                        assertEquals(fixture, if (strict) 2 else 0, clicks)
                        assertEquals(fixture, 0, otherClicks)
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    fun clean(step: String, action: () -> Unit) {
                        try {
                            action()
                        } catch (error: Throwable) {
                            android.util.Log.e("StandbyGeometry", "$fixture cleanup $step", error)
                            val first = failure
                            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                        }
                    }
                    clean("side placement listener") { scenario.onActivity { ownedControls?.removeOnLayoutChangeListener(placeNotices) } }
                    clean("owned controls") {
                        scenario.onActivity { activity ->
                            ownedControls?.let {
                                if (it.parent === activity.contentRoot) activity.contentRoot.removeView(it)
                                check(it.parent == null && !it.isAttachedToWindow) { "$fixture controls removal incomplete" }
                            }
                        }
                    }
                    failure?.let { throw it }
                }
            }
        }
    }

    @Test
    fun breakpoints_dotsAndNarrowNoticeTargetsKeepTheirInstances() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            var frozenClicks = 0
            try {
                scenario.onActivity { activity ->
                    val landscape = LandscapeStandbyForegroundView(activity)
                    val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Receiver")
                    landscape.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    val dot = requireNotNull(landscape.findViewWithTag<View>("foreground_status_dot"))
                    assertEquals(0, (dot.background as GradientDrawable).color?.defaultColor)
                    val eyebrow = requireNotNull(landscape.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                    val center = eyebrow.parent.parent as View
                    for ((w, h) in listOf(700f to 800f, 360f to 800f, 800f to 360f)) {
                        val width = px(activity, w)
                        val height = px(activity, h)
                        layout(landscape, width, height)
                        val viewport = StandbyViewportScale(width, height, activity.resources.displayMetrics.density, cssPixels = true)
                        val fraction = when {
                            !viewport.portrait -> 0.43f
                            viewport.compact && viewport.narrow -> 0.47f
                            viewport.compact -> 0.51f
                            else -> 0.49f
                        }
                        assertEquals(height * fraction, center.top + center.height / 2f, px(activity, 2f).toFloat())
                    }
                    layout(landscape, px(activity, 360f), px(activity, 900f))
                    val receiverName = requireNotNull(landscape.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                    val landscapeReceiver = receiverName.parent as View
                    val portraitScale = StandbyViewportScale(landscape.width, landscape.height, activity.resources.displayMetrics.density, cssPixels = true)
                    assertEquals(landscape.height * 0.49f - portraitScale.px(127f), landscapeReceiver.top.toFloat(), px(activity, 2f).toFloat())
                    landscape.render(state.copy(advertisingEnabled = true), "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    assertSame(dot, landscape.findViewWithTag<View>("foreground_status_dot"))
                    assertEquals(0xFFE0E4DF.toInt(), (dot.background as GradientDrawable).color?.defaultColor)
                    assertFalse(dot.isFocusable)
                    val hardline = HardlineStandbyForegroundView(activity)
                    hardline.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}))
                    val hardlineDot = requireNotNull(hardline.findViewWithTag<View>("foreground_status_dot"))
                    layout(hardline, px(activity, 400f), px(activity, 700f))
                    val name = requireNotNull(hardline.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                    val receiver = name.parent.parent as View
                    val dock = requireNotNull(hardline.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    val hardlineScale = StandbyViewportScale(hardline.width, hardline.height, activity.resources.displayMetrics.density, cssPixels = true)
                    assertTrue(receiver.bottom + hardlineScale.roundedPx(12f) <= dock.top)
                    assertEquals(0.03f, name.letterSpacing, 0f)
                    val hardlineTitle = requireNotNull(hardline.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                    assertEquals(hardlineScale.roundedPx(16f), (hardlineTitle.parent as View).paddingLeft)
                    hardline.render(state.copy(advertisingEnabled = true), "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    assertSame(hardlineDot, hardline.findViewWithTag<View>("foreground_status_dot"))
                    assertEquals(0xFFE0E4DF.toInt(), (hardlineDot.background as GradientDrawable).color?.defaultColor)
                    val narrow = HardlineStandbyForegroundView(activity)
                    narrow.render(
                        StandbyForegroundState(ScreenPhase.FROZEN, false, shouldOfferShizuku = true),
                        "Title", "FROZEN", "Detail",
                        StandbyForegroundCallbacks(onShowFrozenInfo = { frozenClicks++ }, onOpenShizuku = {}),
                    )
                    activity.contentRoot.addView(narrow, ViewGroup.LayoutParams(px(activity, 199f), px(activity, 500f)))
                }
                awaitDraw(scenario)
                scenario.onActivity { activity ->
                    val root = activity.contentRoot
                    val frozen = requireNotNull(root.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO))
                    val shizuku = requireNotNull(root.findViewWithTag<View>("foreground_shizuku"))
                    for (action in listOf(frozen, shizuku)) {
                        assertTrue(action.width >= px(activity, 48f))
                        assertTrue(action.height >= px(activity, 48f))
                        assertTrue(action.isFocusable)
                        assertTrue(action.getGlobalVisibleRect(Rect()))
                    }
                    frozen.isFocusableInTouchMode = true
                    shizuku.isFocusableInTouchMode = true
                    assertTrue(frozen.requestFocus())
                    assertTrue(frozen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
                    assertTrue(frozen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
                    assertEquals(1, frozenClicks)
                    assertTrue(shizuku.requestFocus())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun veryShortNotices_scrollToBothMinimumTouchTargets() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            for ((strict, widthDp, heightDp) in listOf(Triple(false, 119f, 120f), Triple(true, 216f, 216f))) {
                val fixture = "veryShortNotices ${if (strict) "strict" else "stress"} ${widthDp}x${heightDp}dp"
                var ownedView: HardlineStandbyForegroundView? = null
                var failure: Throwable? = null
                lateinit var actions: List<View>
                var callbacks = 0
                try {
                    scenario.onActivity { activity ->
                        val view = HardlineStandbyForegroundView(activity)
                        ownedView = view
                        view.applySecurityInsets(Insets.NONE)
                        view.render(
                            StandbyForegroundState(ScreenPhase.FROZEN, false, shouldOfferShizuku = true),
                            "Title", "FROZEN", "Detail",
                            StandbyForegroundCallbacks(onShowFrozenInfo = { callbacks++ }, onOpenShizuku = { callbacks++ }),
                        )
                        activity.contentRoot.addView(view, ViewGroup.LayoutParams(px(activity, widthDp), px(activity, heightDp)))
                        actions = listOf(StandbyForegroundTags.FROZEN_INFO, "foreground_shizuku").map { tag ->
                            requireNotNull(view.findViewWithTag<View>(tag)).apply { isFocusableInTouchMode = true }
                        }
                    }
                    val view = requireNotNull(ownedView)
                    awaitForegroundLayout(scenario, view)
                    scenario.onActivity { activity ->
                        val density = view.resources.displayMetrics.density
                        val usableShortDp = minOf(view.width - view.paddingLeft - view.paddingRight,
                            view.height - view.paddingTop - view.paddingBottom) / density
                        val evidence = "$fixture measured=${view.measuredWidth}x${view.measuredHeight} " +
                            "laidOut=${view.width}x${view.height} density=$density usableShortDp=$usableShortDp"
                        assertEquals(evidence, 1.5f, density, 0f)
                        assertEquals(evidence, (if (strict) 324 to 324 else 178 to 180), view.measuredWidth to view.measuredHeight)
                        assertEquals(evidence, view.measuredWidth to view.measuredHeight, view.width to view.height)
                        assertEquals(evidence, strict, usableShortDp >= 144f)
                        assertSame(evidence, activity.contentRoot, view.parent)
                                                                                                        android.util.Log.i("StandbyGeometry", evidence)
                    }
                    for (action in actions) {
                        scenario.onActivity {
                            val minimum = ceil(48.0 * view.resources.displayMetrics.density).toInt()
                            val evidence = "$fixture target=${action.tag} size=${action.width}x${action.height} minimum=$minimum"
                            assertTrue(evidence, action.isAttachedToWindow && action.isLaidOut && action.isShown &&
                                minOf(action.measuredWidth, action.measuredHeight, action.width, action.height) >= minimum)
                            assertTrue(evidence, action.requestFocus())
                            assertTrue(evidence, action.isFocused)
                        }
                        if (strict) {
                            awaitCondition(scenario, { foregroundSnapshot(fixture, view, action) }) {
                                val visible = Rect()
                                action.isFocused && action.getGlobalVisibleRect(visible) &&
                                    visible.width() >= action.width && visible.height() >= action.height
                            }
                        }
                    }
                    scenario.onActivity { assertEquals(fixture, 0, callbacks) }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        scenario.onActivity { activity ->
                            ownedView?.let {
                                if (it.parent === activity.contentRoot) activity.contentRoot.removeView(it)
                                check(it.parent == null && !it.isAttachedToWindow) { "$fixture foreground removal incomplete" }
                            }
                        }
                    } catch (cleanup: Throwable) {
                        android.util.Log.e("StandbyGeometry", "$fixture cleanup owned foreground", cleanup)
                        val first = failure ?: throw cleanup
                        if (first !== cleanup) first.addSuppressed(cleanup)
                    }
                }
            }
        }
    }

    /** @return Source-bound receiver range after checking both atomic groups and their native Paint widths. */
    private fun assertInvitationSpans(hint: TextView, expected: String, receiverGroup: String): IntRange {
        assertEquals(expected, hint.text.toString())
        val text = hint.text as Spanned
        val spans = text.getSpans(0, text.length, ReplacementSpan::class.java)
        assertEquals(2, spans.size)
        val shortcut = spans.single { it.javaClass.simpleName == "ShortcutSpan" }
        val shortcutStart = expected.indexOf("Win + K")
        assertEquals(shortcutStart, text.getSpanStart(shortcut))
        assertEquals(shortcutStart + 7, text.getSpanEnd(shortcut))
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(shortcut))
        val keyPaint = Paint(hint.paint).apply { typeface = Typeface.create(hint.typeface, 600, false) }
        val keyWidth = ceil((keyPaint.measureText("Win") + keyPaint.measureText("K") +
            hint.paint.measureText(" + ") + 24f * hint.resources.displayMetrics.density).toDouble()).toInt()
        assertEquals(keyWidth, shortcut.getSize(hint.paint, text, shortcutStart, shortcutStart + 7, null))
        val group = spans.single { it.javaClass.simpleName == "ReceiverGroupSpan" }
        val start = expected.length - receiverGroup.length
        assertEquals(start, text.getSpanStart(group))
        assertEquals(expected.length, text.getSpanEnd(group))
        assertEquals(receiverGroup, text.subSequence(start, text.length).toString())
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(group))
        val metrics = hint.paint.fontMetricsInt
        val before = listOf(metrics.top, metrics.ascent, metrics.descent, metrics.bottom, metrics.leading)
        assertEquals(ceil(hint.paint.measureText(receiverGroup).toDouble()).toInt(),
            group.getSize(hint.paint, text, start, text.length, metrics))
        assertEquals(before, listOf(metrics.top, metrics.ascent, metrics.descent, metrics.bottom, metrics.leading))
        return start until text.length
    }

    /** Independent Paint widths select legal names; actual Layout budgets prove first-line relocation as a unit. */
    @Test
    fun landscapeReceiverGroup_wrapsAtomicallyWithinUnchangedHintBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                val resolvedSizes = listOf(1f, 2f).map { fontScale ->
                    val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                        densityDpi = 240
                        this.fontScale = fontScale
                        setLocale(locale)
                    })
                    val view = LandscapeStandbyForegroundView(context)
                    val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                    val receiver = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                    val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    val state = StandbyForegroundState(ScreenPhase.ADVERTISING, true,
                        copyMotionAllowed = false, isProjecting = false)
                    val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {})
                    val punctuation = "<>&/ \"'"
                    fun group(name: String): String = if (locale == Locale.ENGLISH) name else "「$name」"
                    fun render(name: String) {
                        view.render(state.copy(receiverName = name), "Ready", "BROADCAST ON", "", callbacks)
                        repeat(2) { layout(view, 1080, 1920) }
                    }
                    fun bounds(target: View): Rect = Rect(0, 0, target.width, target.height).also {
                        view.offsetDescendantRectToMyCoords(target, it)
                    }
                    render(punctuation)
                    val firstRange = assertInvitationSpans(hint,
                        context.getString(R.string.standby_foreground_connection_hint_format, punctuation), group(punctuation))
                    val firstLayout = requireNotNull(hint.layout)
                    val capacity = firstLayout.getParagraphRight(0) - firstLayout.getParagraphLeft(0)
                    val prefixAdvance = Layout.getDesiredWidth(hint.text.subSequence(0, firstRange.first), hint.paint)
                    val initialTypeface = hint.typeface
                    val initialTextSize = hint.textSize
                    val anchors = listOf(StandbyForegroundTags.BRAND, StandbyForegroundTags.BROADCAST,
                        StandbyForegroundTags.MENU).associateWith { bounds(requireNotNull(view.findViewWithTag<View>(it))) }
                    val centerBounds = bounds(title.parent as View)
                    val receiverBounds = bounds(receiver.parent as View)
                    val expectedSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                        context.resources.displayMetrics)
                    assertEquals(expectedSize, initialTextSize, 0.001f)
                    assertEquals(1, firstLayout.lineCount)
                    assertTrue(prefixAdvance + ceil(hint.paint.measureText(group(punctuation)).toDouble()) <= capacity)
                    assertTrue(prefixAdvance < capacity)
                    val wrappedName = requireNotNull((0..(40 - punctuation.length)).map { punctuation + "廳".repeat(it) }
                        .firstOrNull {
                            val advance = ceil(hint.paint.measureText(group(it)).toDouble())
                            advance <= capacity - 8 && prefixAdvance + advance > capacity + 8
                        }) { "No legal atomic-fit vector: locale=$locale size=$initialTextSize capacity=$capacity prefix=$prefixAdvance" }
                    for (name in listOf(punctuation, wrappedName)) {
                        render(name)
                        val expected = context.getString(R.string.standby_foreground_connection_hint_format, name)
                        val range = assertInvitationSpans(hint, expected, group(name))
                        val textLayout = requireNotNull(hint.layout)
                        val evidence = "locale=$locale fontScale=$fontScale size=${hint.textSize} capacity=$capacity name=[$name]"
                        val groupLine = textLayout.getLineForOffset(range.first)
                        assertTrue(evidence, name.codePointCount(0, name.length) <= 40)
                        assertFalse(evidence, name.contains('\n'))
                        assertEquals(evidence, groupLine, textLayout.getLineForOffset(range.last))
                        val shortcutStart = expected.indexOf("Win + K")
                        assertEquals(evidence, textLayout.getLineForOffset(shortcutStart), textLayout.getLineForOffset(shortcutStart + 6))
                        val groupAdvance = ceil(hint.paint.measureText(group(name)).toDouble()).toInt()
                        assertTrue(evidence, prefixAdvance < capacity && groupAdvance <= capacity)
                        if (name == punctuation) {
                            assertTrue(evidence, prefixAdvance + groupAdvance <= capacity)
                            assertEquals(evidence, 1, textLayout.lineCount)
                        } else {
                            assertTrue(evidence, prefixAdvance + groupAdvance > capacity)
                            assertTrue(evidence, groupLine > 0 && textLayout.lineCount > 1)
                        }
                        assertEquals(evidence, capacity, textLayout.getParagraphRight(groupLine) - textLayout.getParagraphLeft(groupLine))
                        assertEquals(evidence, expected.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                        for (line in 0 until textLayout.lineCount) {
                            assertEquals(evidence, 0, textLayout.getEllipsisCount(line))
                            assertTrue(evidence, textLayout.getLineWidth(line) <= capacity + 1f)
                            assertEquals(evidence, (hint.textSize * 1.6f).roundToInt(),
                                textLayout.getLineBottom(line) - textLayout.getLineTop(line))
                        }
                        assertEquals(evidence, initialTextSize, hint.textSize, 0f)
                        assertEquals(evidence, initialTypeface, hint.typeface)
                        assertEquals(evidence, 0.08f, hint.letterSpacing, 0f)
                        assertEquals(evidence, 1f, hint.textScaleX, 0f)
                        assertFalse(evidence, hint.includeFontPadding)
                        assertNull(evidence, hint.ellipsize)
                        assertEquals(evidence, Int.MAX_VALUE, hint.maxLines)
                        assertEquals(evidence, 870, hint.width)
                        assertEquals(evidence, 0, hint.paddingLeft + hint.paddingRight)
                        assertEquals(evidence, 540f, hint.left + hint.width / 2f, 1f)
                        assertEquals(evidence, 1734, hint.bottom)
                        assertEquals(evidence, centerBounds, bounds(title.parent as View))
                        assertEquals(evidence, receiverBounds.top, bounds(receiver.parent as View).top)
                        assertEquals(evidence, receiverBounds.exactCenterX(), bounds(receiver.parent as View).exactCenterX(), 1f)
                        assertEquals(evidence, name, receiver.text.toString())
                        assertEquals(evidence, 1, receiver.maxLines)
                        assertEquals(evidence, android.text.TextUtils.TruncateAt.END, receiver.ellipsize)
                        assertEquals(evidence, 0, (receiver.text as? Spanned)?.getSpans(0, receiver.text.length, ReplacementSpan::class.java)?.size ?: 0)
                        for ((tag, before) in anchors) assertEquals("$evidence tag=$tag", before, bounds(requireNotNull(view.findViewWithTag<View>(tag))))
                        for (tag in listOf(StandbyForegroundTags.BROADCAST, StandbyForegroundTags.MENU)) {
                            val target = requireNotNull(view.findViewWithTag<View>(tag))
                            assertTrue(evidence, target.width >= 72 && target.height >= 72)
                        }
                    }
                    initialTextSize
                }
                assertTrue("At least two actual resolved SP sizes are required: $resolvedSizes", resolvedSizes.distinct().size > 1)
            }
        }
    }

    /** A legal but oversized name stays intact; an atomic inline block is not a universal-fit or clipping guarantee. */
    @Test
    fun landscapeReceiverGroup_preservesOversizedTextWithoutInventingFitPolicy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 240
                    fontScale = 2f
                    setLocale(locale)
                })
                val name = "<>&/ \"'" + "廳".repeat(33)
                val view = LandscapeStandbyForegroundView(context)
                view.render(StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = name,
                    copyMotionAllowed = false, isProjecting = false), "Ready", "BROADCAST ON", "", StandbyForegroundCallbacks())
                repeat(2) { layout(view, 1080, 1920) }
                val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                val expected = context.getString(R.string.standby_foreground_connection_hint_format, name)
                val group = if (locale == Locale.ENGLISH) name else "「$name」"
                val range = assertInvitationSpans(hint, expected, group)
                val textLayout = requireNotNull(hint.layout)
                val line = textLayout.getLineForOffset(range.first)
                val capacity = textLayout.getParagraphRight(line) - textLayout.getParagraphLeft(line)
                val advance = hint.paint.measureText(group)
                val evidence = "Oversized atomic group: locale=$locale size=${hint.textSize} advance=$advance capacity=$capacity; full visibility is not asserted"
                assertEquals(evidence, 40, name.codePointCount(0, name.length))
                assertTrue(evidence, advance > capacity)
                assertEquals(evidence, line, textLayout.getLineForOffset(range.last))
                assertTrue(evidence, textLayout.getLineWidth(line) > capacity)
                assertEquals(evidence, expected.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertTrue(evidence, (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                assertNull(evidence, hint.ellipsize)
                assertEquals(evidence, Int.MAX_VALUE, hint.maxLines)
                assertEquals(evidence, 1f, hint.textScaleX, 0f)
                assertEquals(evidence, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                    context.resources.displayMetrics), hint.textSize, 0.001f)
                assertEquals(evidence, 870, hint.width)
                assertEquals(evidence, 1734, hint.bottom)
            }
        }
    }

    /** Checks the waiting and shown-failure invitations against the frozen phase cases. */
    @Test
    fun landscapePhaseHints_useLocalizedStateCopyAndKeepInvitationGuard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val knownReceiver = "<Studio & 客廳>"
            val timeout = PrePlayFailureCause.PRE_PLAY_TIMEOUT
            val invitationCases = setOf(
                ScreenPhase.ADVERTISING to false,
                ScreenPhase.ADVERTISING to true,
                ScreenPhase.CONNECTING to true,
            )
            val stateField = StandbyForegroundView::class.java.getDeclaredField("foregroundState").apply { isAccessible = true }
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 240
                    fontScale = 1f
                    setLocale(locale)
                })
                val view = LandscapeStandbyForegroundView(context)
                val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                val eyebrow = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                val detail = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_DETAIL))
                val name = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                val broadcast = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                val notice = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                val failure = context.getString(R.string.preplay_failed_timeout)
                val group = if (locale == Locale.ENGLISH) knownReceiver else "「$knownReceiver」"
                val invitation = if (locale == Locale.ENGLISH) {
                    "Windows: press Win + K · Choose $group"
                } else {
                    "Windows 按 Win + K，選擇$group"
                }
                val wifiCopy = if (locale == Locale.ENGLISH) {
                    "Wi-Fi is off · Turn on Wi-Fi to broadcast"
                } else {
                    "Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播"
                }
                val frozenCopy = if (locale == Locale.ENGLISH) "Receiver unavailable" else "接收不可用"
                for (phase in ScreenPhase.entries) {
                    for (enabled in listOf(false, true)) {
                        for (receiver in listOf("", "   ", knownReceiver)) {
                            for (hasFailure in listOf(false, true)) {
                                val state = StandbyForegroundState(phase, enabled, receiverName = receiver,
                                    failureCause = if (hasFailure) timeout else null,
                                    failureText = if (hasFailure) failure else "", isProjecting = phase == ScreenPhase.CONNECTED)
                                view.render(state, "Host title", "HOST STATUS",
                                    if (hasFailure) failure else "Unrelated supplied host detail", StandbyForegroundCallbacks())
                                repeat(2) { layout(view, 800, 1280) }
                                val inviting = enabled && receiver == knownReceiver && (phase to hasFailure) in invitationCases
                                val expected = when {
                                    inviting -> invitation
                                    phase == ScreenPhase.WIFI_PAUSED -> wifiCopy
                                    phase == ScreenPhase.FROZEN -> frozenCopy
                                    else -> ""
                                }
                                val evidence = "locale=$locale phase=$phase enabled=$enabled receiver=[$receiver] failure=$hasFailure"
                                assertEquals(evidence, expected, hint.text.toString())
                                assertEquals(evidence, if (expected.isEmpty()) View.GONE else View.VISIBLE, hint.visibility)
                                val spans = (hint.text as? Spanned)?.getSpans(0, hint.text.length, ReplacementSpan::class.java)
                                assertEquals(evidence, if (inviting) 2 else 0, spans?.size ?: 0)
                                if (inviting) assertInvitationSpans(hint, expected, group)
                                assertEquals(evidence, state, stateField.get(view) as StandbyForegroundState)
                                assertEquals(evidence, receiver, name.text.toString())
                                assertTrue(evidence, listOf(broadcast, menu, dismiss).all { it.visibility == View.GONE })
                                if (expected.isNotEmpty()) {
                                    assertTrue(evidence, hint.width > 0 && hint.height > 0)
                                    assertTrue(evidence, hint.left >= 0 && hint.right <= view.width && hint.top >= 0 && hint.bottom <= view.height)
                                    val textLayout = requireNotNull(hint.layout) { evidence }
                                    assertEquals(evidence, expected.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                                    assertTrue(evidence, (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                                }
                                assertEquals(evidence, 16.5f, hint.textSize, 0.001f)
                                assertEquals(evidence, "Host title", title.text.toString())
                                assertEquals(evidence, "HOST STATUS", eyebrow.text.toString())
                                assertEquals(evidence, 60f, title.textSize, 0.001f)
                                assertEquals(evidence, 350, title.typeface.weight)
                                assertEquals(evidence, 72, requireNotNull(title.layout).getLineBottom(0) - title.layout.getLineTop(0))
                                assertEquals(evidence, View.GONE, detail.visibility)
                                assertTrue(evidence, detail.text.isEmpty())
                            }
                        }
                    }
                }

                val connecting = StandbyForegroundState(
                    ScreenPhase.CONNECTING, advertisingEnabled = true, receiverName = knownReceiver,
                    failureText = failure, isProjecting = false,
                )
                view.render(connecting.copy(failureCause = timeout, isProjecting = true),
                    "Host title", "HOST STATUS", failure, StandbyForegroundCallbacks())
                assertTrue(hint.text.isEmpty())
                assertEquals(View.GONE, hint.visibility)

                var actions = 0
                val callbacks = StandbyForegroundCallbacks(
                    onToggleBroadcast = { actions++ }, onOpenSettings = { actions++ }, onDismissFailure = { actions++ },
                )
                // Stray notice and detail text without a cause must not invite.
                view.render(connecting, "Host title", "HOST STATUS", failure, callbacks)
                assertTrue(hint.text.isEmpty())
                assertEquals(View.GONE, notice.visibility)
                for ((cause, expected) in listOf(timeout to invitation, null to "")) {
                    val text = if (cause == null) "" else failure
                    val visibility = if (cause == null) View.GONE else View.VISIBLE
                    view.updateFailure(cause, text)
                    assertEquals(expected, hint.text.toString())
                    assertEquals(visibility, hint.visibility)
                    if (cause != null) assertInvitationSpans(hint, expected, group)
                    assertEquals(connecting.copy(failureCause = cause, failureText = text), stateField.get(view) as StandbyForegroundState)
                    assertEquals("Host title", title.text.toString())
                    assertEquals("HOST STATUS", eyebrow.text.toString())
                    assertEquals(View.GONE, detail.visibility)
                    assertEquals(text, notice.text.toString())
                    assertEquals(visibility, notice.visibility)
                    assertEquals(visibility, dismiss.visibility)
                    assertEquals(View.VISIBLE, broadcast.visibility)
                    assertTrue(broadcast.createAccessibilityNodeInfo().isChecked)
                    assertEquals(View.VISIBLE, menu.visibility)
                }
                assertEquals(0, actions)
            }
        }
    }

    @Test
    fun hints_useInvitablePhaseAndSuppliedHostDetails() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val landscape = LandscapeStandbyForegroundView(activity)
                val matter = MatterStandbyForegroundView(activity)
                val landscapeHint = requireNotNull(landscape.findViewWithTag<TextView>("foreground_hint"))
                val matterHint = requireNotNull(matter.findViewWithTag<View>("foreground_hint"))
                val hintTitle = requireNotNull(matter.findViewWithTag<TextView>("foreground_hint_title"))
                val hintDescription = requireNotNull(matter.findViewWithTag<TextView>("foreground_hint_description"))
                val statusWord = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                val receiver = "Host receiver"
                val callbacks = StandbyForegroundCallbacks()
                val notInviting = listOf(
                    ScreenPhase.READY to false,
                    ScreenPhase.READY to true,
                    ScreenPhase.ARMING to true,
                    ScreenPhase.CONNECTING to true,
                    ScreenPhase.WIFI_PAUSED to true,
                    ScreenPhase.FROZEN to true,
                    ScreenPhase.ADVERTISING to false,
                )
                for ((phase, enabled) in notInviting) {
                    val state = StandbyForegroundState(phase, enabled, receiverName = receiver)
                    val suppliedDetail = "Host detail: $phase / $enabled"
                    landscape.render(state, "Title", "Status", suppliedDetail, callbacks)
                    matter.render(state, "Title", "Status", suppliedDetail, callbacks)
                    val landscapeCopy = when (phase) {
                        ScreenPhase.WIFI_PAUSED -> activity.getString(R.string.standby_foreground_landscape_wifi_paused_hint)
                        ScreenPhase.FROZEN -> activity.getString(R.string.standby_foreground_phase_frozen_detail)
                        else -> ""
                    }
                    assertEquals(if (landscapeCopy.isEmpty()) View.GONE else View.VISIBLE, landscapeHint.visibility)
                    assertEquals(landscapeCopy, landscapeHint.text.toString())
                    assertEquals(View.VISIBLE, matterHint.visibility)
                    assertEquals(receiver, hintTitle.text.toString())
                    assertEquals(View.VISIBLE, hintDescription.visibility)
                    assertEquals(suppliedDetail, hintDescription.text.toString())
                    assertSame(landscapeHint, landscape.findViewWithTag<View>("foreground_hint"))
                    assertSame(matterHint, matter.findViewWithTag<View>("foreground_hint"))
                    assertSame(statusWord, matter.findViewWithTag<View>(StandbyForegroundTags.PHASE_EYEBROW))
                    val expectedStatus = when (phase) {
                        ScreenPhase.READY -> "DISCONN"
                        ScreenPhase.ARMING -> "ARMING"
                        ScreenPhase.CONNECTING -> "ESTABLISHING"
                        ScreenPhase.WIFI_PAUSED -> "WAIT_WIFI"
                        ScreenPhase.FROZEN -> "UNAVAILABLE"
                        ScreenPhase.ADVERTISING -> "STANDBY"
                        ScreenPhase.CONNECTED -> "CONNECTED"
                    }
                    assertEquals(expectedStatus, statusWord.text.toString())
                }
                val advertising = StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = receiver)
                landscape.render(advertising, "Title", "Advertising", "Host detail", callbacks)
                matter.render(advertising, "Title", "Advertising", "Host detail", callbacks)
                val prefix = activity.getString(R.string.standby_foreground_hint_prefix)
                assertEquals(View.VISIBLE, landscapeHint.visibility)
                assertEquals(
                    activity.getString(R.string.standby_foreground_connection_hint_format, receiver),
                    landscapeHint.text.toString(),
                )
                assertEquals(View.VISIBLE, matterHint.visibility)
                assertEquals(receiver, hintTitle.text.toString())
                assertEquals(View.VISIBLE, hintDescription.visibility)
                assertEquals(prefix, hintDescription.text.toString())
                assertEquals("STANDBY", statusWord.text.toString())

                val notAdvertising = advertising.copy(phase = ScreenPhase.READY, advertisingEnabled = false)
                matter.render(notAdvertising, "Title", "Ready", "", callbacks)
                assertEquals(View.VISIBLE, matterHint.visibility)
                assertEquals(View.GONE, hintDescription.visibility)
                assertTrue(hintDescription.text.isEmpty())
                assertEquals(receiver, hintTitle.text.toString())
                landscape.render(advertising.copy(receiverName = ""), "Title", "Advertising", "Detail", callbacks)
                matter.render(advertising.copy(receiverName = ""), "Title", "Advertising", "Detail", callbacks)
                assertEquals(View.GONE, landscapeHint.visibility)
                assertEquals(View.GONE, matterHint.visibility)
                assertEquals("Detail", hintDescription.text.toString())
            }
        }
    }

    @Test
    fun securityInsets_publicApi_replacesInsetsWithoutAccumulation() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = Insets.of(10, 20, 30, 40)
                val smaller = Insets.of(1, 2, 3, 4)
                val cases = listOf(
                    first to Rect(13, 24, 35, 46),
                    smaller to Rect(4, 6, 8, 10),
                    Insets.NONE to Rect(3, 4, 5, 6),
                    Insets.NONE to Rect(3, 4, 5, 6),
                    first to Rect(13, 24, 35, 46),
                    smaller to Rect(4, 6, 8, 10),
                    Insets.NONE to Rect(3, 4, 5, 6),
                )
                val views = listOf(
                    LandscapeStandbyForegroundView(activity),
                    MatterStandbyForegroundView(activity),
                    HardlineStandbyForegroundView(activity),
                )
                for (view in views) {
                    view.setPadding(3, 4, 5, 6)
                    layout(view, px(activity, 360f), px(activity, 800f))
                    assertFalse(view.isLayoutRequested)
                    for ((insets, expected) in cases) {
                        val previous = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                        view.applySecurityInsets(insets)
                        assertEquals(expected, Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                        if (previous == expected) {
                            assertFalse(view.isLayoutRequested)
                        } else {
                            assertTrue(view.isLayoutRequested)
                        }
                        layout(view, px(activity, 360f), px(activity, 800f))
                        assertFalse(view.isLayoutRequested)
                    }
                    view.applySecurityInsets(first)
                    ViewCompat.dispatchApplyWindowInsets(
                        view,
                        WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), first).build(),
                    )
                    assertEquals(Rect(13, 24, 35, 46), Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                    view.applySecurityInsets(Insets.NONE)
                    assertEquals(Rect(3, 4, 5, 6), Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                }
                val relative = LandscapeStandbyForegroundView(activity)
                relative.setPaddingRelative(7, 8, 9, 10)
                val base = Rect(relative.paddingLeft, relative.paddingTop, relative.paddingRight, relative.paddingBottom)
                assertTrue(relative.isPaddingRelative)
                relative.applySecurityInsets(Insets.NONE)
                assertFalse(relative.isPaddingRelative)
                assertEquals(base, Rect(relative.paddingLeft, relative.paddingTop, relative.paddingRight, relative.paddingBottom))
            }
        }
    }

    @Test
    fun safeBottomAndMatterFooter_useActualAvailableWidth() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {})
                    val views = listOf(
                        LandscapeStandbyForegroundView(activity),
                        MatterStandbyForegroundView(activity),
                        HardlineStandbyForegroundView(activity),
                    )
                    for (view in views) {
                        view.render(
                            StandbyForegroundState(ScreenPhase.READY, true, receiverName = "Receiver"),
                            "Title", "READY", "Detail", callbacks,
                        )
                        for (inset in listOf(30f, 0f)) {
                            view.applySecurityInsets(Insets.of(0, 0, 0, px(activity, inset)))
                            layout(view, px(activity, 360f), px(activity, 800f))
                            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                            val bounds = Rect(0, 0, dock.width, dock.height)
                            view.offsetDescendantRectToMyCoords(dock, bounds)
                            val viewport = StandbyViewportScale(view.width, view.height, activity.resources.displayMetrics.density,
                                cssPixels = view !is MatterStandbyForegroundView)
                            assertEquals(maxOf(viewport.roundedPx(12f), px(activity, inset)), view.height - bounds.bottom)
                        }
                    }
                    val largeFont = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        fontScale = 3f
                    })
                    val matter = MatterStandbyForegroundView(largeFont)
                    val insets = Insets.of(px(largeFont, 20f), px(largeFont, 10f), px(largeFont, 40f), px(largeFont, 15f))
                    matter.applySecurityInsets(insets)
                    for (receiverName in listOf("R", "Receiver")) {
                        matter.render(
                            StandbyForegroundState(ScreenPhase.CONNECTING, true, receiverName = receiverName),
                            "Ignored host title", "Ignored host eyebrow", "Detail", callbacks,
                        )
                        layout(matter, px(largeFont, 1440f), px(largeFont, 1000f))
                        val viewport = StandbyViewportScale(matter.width, matter.height, largeFont.resources.displayMetrics.density)
                        val plate = requireNotNull(matter.findViewWithTag<ViewGroup>(StandbyForegroundTags.MATTER_PLATE))
                        val word = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                        assertEquals("ESTABLISHING", word.text.toString())
                        // Real displayed content, not the ignored host title, must justify exceeding the nominal cap.
                        val nominalCap = StandbyViewportScale(matter.width, matter.height).px(280f)
                        assertTrue(word.paint.measureText(word.text.toString()) > nominalCap)
                        assertTrue(plate.width > nominalCap)
                        val hintTitle = requireNotNull(matter.findViewWithTag<TextView>("foreground_hint_title"))
                        val hintDescription = requireNotNull(matter.findViewWithTag<TextView>("foreground_hint_description"))
                        assertEquals(receiverName, hintTitle.text.toString())
                        assertTrue(hintDescription.text.isNotEmpty())
                        val safeBounds = Rect(insets.left, insets.top, matter.width - insets.right, matter.height - insets.bottom)
                        fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
                            matter.offsetDescendantRectToMyCoords(view, it)
                        }
                        val dock = bounds(requireNotNull(matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)))
                        val row = word.parent as LinearLayout
                        val light = requireNotNull(matter.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                        val wordParams = word.layoutParams as LinearLayout.LayoutParams
                        val lightParams = light.layoutParams as LinearLayout.LayoutParams
                        val rowParams = row.layoutParams as LinearLayout.LayoutParams
                        val statusAdvance = android.text.Layout.getDesiredWidth(word.text, word.paint)
                        assertTrue(statusAdvance.isFinite() && statusAdvance > 0f)
                        val naturalWordWidth = ceil(statusAdvance).toInt() + word.compoundPaddingLeft + word.compoundPaddingRight
                        val maxContentPlateWidth = plate.paddingLeft + plate.paddingRight + rowParams.leftMargin + rowParams.rightMargin +
                            row.paddingLeft + row.paddingRight + light.measuredWidth + lightParams.leftMargin + lightParams.rightMargin +
                            naturalWordWidth + wordParams.leftMargin + wordParams.rightMargin
                        val column = hintTitle.parent as LinearLayout
                        val hint = column.parent as LinearLayout
                        val rule = hint.getChildAt(0)
                        val ruleParams = rule.layoutParams as LinearLayout.LayoutParams
                        val columnParams = column.layoutParams as LinearLayout.LayoutParams
                        val titleParams = hintTitle.layoutParams as LinearLayout.LayoutParams
                        val edge = (minOf(matter.width, matter.height) * 0.03f).coerceIn(viewport.px(24f), viewport.px(48f)).toInt()
                        val availableFooterWidth = (matter.width - matter.paddingLeft - matter.paddingRight - 2 * edge).coerceAtLeast(0)
                        // Stacked capacity protects max-content status and retains the current calibrated column gap.
                        val interColumnGap = (12f * viewport.scale).toInt()
                        val hintBudget = (availableFooterWidth - plate.measuredWidth - interColumnGap).coerceAtLeast(0)
                        val ruleReservation = rule.measuredWidth + ruleParams.leftMargin + ruleParams.rightMargin
                        val titleReservations = hint.paddingLeft + hint.paddingRight + ruleReservation +
                            columnParams.leftMargin + columnParams.rightMargin + column.paddingLeft + column.paddingRight +
                            titleParams.leftMargin + titleParams.rightMargin + hintTitle.compoundPaddingLeft + hintTitle.compoundPaddingRight
                        val receiverCapacity = hintBudget - titleReservations
                        val plateBounds = bounds(plate)
                        val hintBounds = bounds(hint)
                        val allocation = "receiver=$receiverName edge=$edge footerWidth=$availableFooterWidth " +
                            "plateWidth=${plate.measuredWidth} maxContentPlateWidth=$maxContentPlateWidth columnGap=$interColumnGap " +
                            "hintBudget=$hintBudget titleReservations=$titleReservations capacity=$receiverCapacity hintBounds=$hintBounds"
                        assertEquals(allocation, 3f, largeFont.resources.configuration.fontScale, 0f)
                        assertEquals(allocation, StandbyForegroundKit.spToPx(largeFont, StandbyForegroundKit.MATTER_STATUS_TEXT_SP) * viewport.scale,
                            word.textSize, 0.01f)
                        assertEquals(allocation, StandbyForegroundKit.spToPx(largeFont, 14f) * viewport.scale, hintTitle.textSize, 0.01f)
                        assertSame(plate, row.parent)
                        assertSame(row, light.parent)
                        assertEquals(2, row.childCount)
                        assertEquals(2, plate.childCount)
                        assertEquals(View.GONE, requireNotNull(matter.findViewWithTag<View>(StandbyForegroundTags.CLOCK)).visibility)
                        assertEquals(2, hint.childCount)
                        assertSame(column, hint.getChildAt(1))
                        assertTrue(allocation, kotlin.math.abs(plate.measuredWidth - maxContentPlateWidth) <= 1)
                        assertTrue(allocation, plateBounds.contains(bounds(word)) && plateBounds.contains(bounds(light)))
                        assertTrue(allocation, rule.measuredWidth > 0 && hintBudget > 0 && receiverCapacity > 0)
                        assertEquals(allocation, matter.paddingLeft + edge, hintBounds.left)
                        assertEquals(allocation, matter.width - matter.paddingRight - edge, plateBounds.right)
                        assertEquals(allocation, plateBounds.bottom, hintBounds.bottom)
                        assertTrue(allocation, plateBounds.bottom < dock.top)
                        assertTrue(allocation, hint.width <= hintBudget + 1 && plateBounds.left - hintBounds.right >= interColumnGap - 1)
                        val expectedTitleLeft = hintBounds.left + hint.paddingLeft + ruleReservation + columnParams.leftMargin +
                            column.paddingLeft + titleParams.leftMargin
                        assertTrue(allocation, kotlin.math.abs(bounds(hintTitle).left - expectedTitleLeft) <= 1)
                        for (text in listOf(word, hintTitle, hintDescription)) {
                            val desiredWidth = android.text.Layout.getDesiredWidth(text.text, text.paint)
                            val availableContentWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                            val evidence = "Matter tag=${text.tag} density=${text.resources.displayMetrics.density} " +
                                "fontScale=${text.resources.configuration.fontScale} size=${matter.width}x${matter.height} " +
                                "text=\"${text.text}\" textLength=${text.text.length} textSize=${text.textSize} " +
                                "desiredWidth=$desiredWidth availableContentWidth=$availableContentWidth " +
                                "ellipsize=${text.ellipsize} maxLines=${text.maxLines} singleLine=${text.isSingleLine} " +
                                "insets=$insets textBounds=${bounds(text)} safeBounds=$safeBounds dock=$dock $allocation"
                            val nominalSp = when {
                                text === word -> 20f
                                text === hintTitle -> 14f
                                else -> 11f
                            }
                            assertEquals("$evidence nominalSp=$nominalSp",
                                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nominalSp, text.resources.displayMetrics) * viewport.scale,
                                text.textSize, 0.01f)
                            val textLayout = requireNotNull(text.layout) { evidence }
                            assertTrue(evidence, text.text.isNotEmpty() && text.textSize.isFinite() && text.textSize > 0f &&
                                desiredWidth.isFinite() && desiredWidth > 0f && availableContentWidth > 0 && textLayout.lineCount > 0)
                            assertEquals("$evidence completeText line=${textLayout.lineCount - 1}", text.text.length,
                                textLayout.getLineEnd(textLayout.lineCount - 1))
                            assertTrue("$evidence safeBounds", safeBounds.contains(bounds(text)))
                            assertFalse("$evidence dockOverlap", Rect.intersects(dock, bounds(text)))
                            if (text === hintTitle) {
                                assertEquals(evidence, 1, text.maxLines)
                                assertEquals(evidence, 1, textLayout.lineCount)
                                assertEquals(evidence, android.text.TextUtils.TruncateAt.END, text.ellipsize)
                                assertTrue(evidence, availableContentWidth <= receiverCapacity + 1)
                                if (receiverName == "Receiver") {
                                    assertTrue("$evidence genuine overflow", desiredWidth > receiverCapacity + 1f)
                                    assertTrue("$evidence exhausted hint budget", kotlin.math.abs(hint.width - hintBudget) <= 1)
                                    assertTrue("$evidence exhausted receiver capacity", kotlin.math.abs(availableContentWidth - receiverCapacity) <= 1)
                                } else {
                                    assertTrue("$evidence fitting slack=${receiverCapacity - desiredWidth}", desiredWidth + 1f < receiverCapacity)
                                    assertTrue(evidence, desiredWidth <= availableContentWidth + 1f)
                                }
                            }
                            for (line in 0 until textLayout.lineCount) {
                                if (text === hintTitle && receiverName == "Receiver") {
                                    assertTrue("$evidence line=$line genuine overflow ellipsis", textLayout.getEllipsisCount(line) > 0)
                                } else {
                                    assertEquals("$evidence line=$line ellipsis", 0, textLayout.getEllipsisCount(line))
                                }
                                assertTrue("$evidence line=$line left", textLayout.getLineLeft(line) >= -0.5f)
                                assertTrue("$evidence line=$line right", textLayout.getLineRight(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 0.5f)
                                assertTrue("$evidence line=$line bottom", textLayout.getLineBottom(line) <= text.height - text.extendedPaddingTop - text.extendedPaddingBottom)
                            }
                        }
                        assertTrue(safeBounds.contains(bounds(plate)))
                        assertTrue(safeBounds.contains(dock))
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun copyFade_advancesOnRealFramesWithoutChangingSystemSettings() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var views: List<StandbyForegroundView>
            lateinit var titles: List<TextView>
            var started = 0L
            var finished = 0L
            val state = StandbyForegroundState(ScreenPhase.READY, false, copyMotionAllowed = true, isProjecting = false)
            try {
                scenario.onActivity { activity ->
                    views = listOf(LandscapeStandbyForegroundView(activity), HardlineStandbyForegroundView(activity))
                    views.forEach { view ->
                        view.render(state, "Old", "Status", "", StandbyForegroundCallbacks())
                        activity.contentRoot.addView(view, ViewGroup.LayoutParams(-1, -1))
                    }
                    titles = views.map { requireNotNull(it.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE)) }
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    started = SystemClock.uptimeMillis()
                    views.forEach { it.render(state, "New", "Status", "", StandbyForegroundCallbacks()) }
                    titles.forEach { assertEquals("Old", it.text.toString()) }
                }
                awaitCondition(scenario) {
                    if (titles.all { it.text.toString() == "New" && it.alpha == 1f }) {
                        finished = SystemClock.uptimeMillis()
                        true
                    } else {
                        false
                    }
                }
                assertTrue("Landscape copy fade completed too early", finished - started >= 300L)
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun fadeStopsForMovingLayerAndTransparentAncestor() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var views: List<StandbyForegroundView>
            val state = StandbyForegroundState(ScreenPhase.READY, false, copyMotionAllowed = true, isProjecting = false)
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    views = listOf(LandscapeStandbyForegroundView(activity), HardlineStandbyForegroundView(activity))
                    views.forEach { view ->
                        view.render(state, "Old", "OLD", "Detail", StandbyForegroundCallbacks())
                        parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                    }
                    activity.contentRoot.addView(parent, ViewGroup.LayoutParams(-1, -1))
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    for (view in views) {
                        view.render(state, "New", "NEW", "Detail", StandbyForegroundCallbacks())
                        view.motionLayer.visibility = View.GONE
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        assertEquals("New", title.text.toString())
                        assertEquals(1f, title.alpha, 0f)
                        view.motionLayer.visibility = View.VISIBLE
                        view.render(state, "Next", "NEXT", "Detail", StandbyForegroundCallbacks())
                    }
                    parent.alpha = 0f
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    for (view in views) {
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        assertEquals("Next", title.text.toString())
                        assertEquals(1f, title.alpha, 0f)
                    }
                    assertNull((views[0] as LandscapeStandbyForegroundView).foregroundGeometryInViewPixels())
                    assertNull((views[1] as HardlineStandbyForegroundView).foregroundRectsInViewPixels())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun motionLayerVisibilityAndProjection_stopAndResumeBlink() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var foreground: MatterStandbyForegroundView
            lateinit var light: View
            val blinkCallbackField = MatterStandbyForegroundView::class.java.getDeclaredField("blinkCallback").apply {
                isAccessible = true
            }
            try {
                scenario.onActivity { activity ->
                    foreground = MatterStandbyForegroundView(activity)
                    light = requireNotNull(foreground.findViewWithTag(StandbyForegroundTags.MATTER_LIGHT))
                    activity.contentRoot.addView(foreground, ViewGroup.LayoutParams(-1, -1))
                    foreground.render(
                        StandbyForegroundState(ScreenPhase.CONNECTING, true, isProjecting = false),
                        "Title", "CONNECTING", "Detail", StandbyForegroundCallbacks(),
                    )
                }
                awaitCondition(scenario) { light.alpha < 1f }
                scenario.onActivity {
                    val stale = requireNotNull(blinkCallbackField.get(foreground) as? Runnable)
                    val handler = requireNotNull(foreground.handler)
                    assertTrue(handler.hasCallbacks(stale))
                    foreground.motionLayer.alpha = 0f
                    assertNull(blinkCallbackField.get(foreground))
                    assertFalse(handler.hasCallbacks(stale))
                    assertEquals(1f, light.alpha, 0f)
                    stale.run()
                    assertNull(blinkCallbackField.get(foreground))
                    assertFalse(handler.hasCallbacks(stale))
                    assertEquals(1f, light.alpha, 0f)
                }
                awaitDraw(scenario)
                scenario.onActivity {
                    assertEquals(1f, light.alpha, 0f)
                    foreground.motionLayer.alpha = 1f
                }
                awaitCondition(scenario) { light.alpha < 1f }
                scenario.onActivity {
                    val stale = requireNotNull(blinkCallbackField.get(foreground) as? Runnable)
                    val handler = requireNotNull(foreground.handler)
                    assertTrue(handler.hasCallbacks(stale))
                    foreground.render(
                        StandbyForegroundState(ScreenPhase.CONNECTING, true, isProjecting = true),
                        "Title", "CONNECTING", "Detail", StandbyForegroundCallbacks(),
                    )
                    assertNull(blinkCallbackField.get(foreground))
                    assertFalse(handler.hasCallbacks(stale))
                    assertEquals(1f, light.alpha, 0f)
                    stale.run()
                    assertNull(blinkCallbackField.get(foreground))
                    assertFalse(handler.hasCallbacks(stale))
                    assertEquals(1f, light.alpha, 0f)
                    foreground.motionLayer.visibility = View.GONE
                    assertEquals(1f, light.alpha, 0f)
                }
                awaitDraw(scenario)
                scenario.onActivity { assertEquals(1f, light.alpha, 0f) }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }
}
