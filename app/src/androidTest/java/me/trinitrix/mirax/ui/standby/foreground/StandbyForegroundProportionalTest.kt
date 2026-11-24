package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks actual line layout rather than the unchanged semantic text alone.
 * @param view Text to inspect.
 * @param message Geometry context for failures.
 */
internal fun assertCompleteText(view: TextView, message: String) {
    val textLayout = requireNotNull(view.layout) { "Missing text layout: $message" }
    assertTrue(message, textLayout.lineCount > 0)
    assertEquals(message, view.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
    val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
    val height = view.height - view.extendedPaddingTop - view.extendedPaddingBottom
    for (line in 0 until textLayout.lineCount) {
        val lineMessage = "$message text=${view.tag} line=$line width=$width height=$height"
        assertEquals(lineMessage, 0, textLayout.getEllipsisCount(line))
        assertTrue(lineMessage, textLayout.getLineLeft(line) >= -0.5f)
        assertTrue(lineMessage, textLayout.getLineRight(line) <= width + 0.5f)
        assertTrue(lineMessage, textLayout.getLineTop(line) >= 0)
        assertTrue(lineMessage, textLayout.getLineBottom(line) <= height)
    }
}

/** Exercises measured native foregrounds, not a simulated viewport model. */
@RunWith(AndroidJUnit4::class)
class StandbyForegroundProportionalTest {
    private fun context(densityDpi: Int, fontScale: Float = 1f, locale: Locale = Locale.ENGLISH): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
            this.fontScale = fontScale
            setLocale(locale)
        })
    }

    private fun layout(view: View, width: Int, height: Int) {
        repeat(2) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
    }

    private fun render(view: StandbyForegroundView, name: String = "Receiver") {
        view.render(
            StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = name, clockText = "12:34"),
            "Ready", "Waiting", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}),
        )
    }

    /**
     * @param root Foreground coordinate space.
     * @param child Descendant to measure.
     * @return Descendant bounds in the foreground's native pixels.
     */
    private fun bounds(root: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        // The descendant conversion subtracts the child's own scroll offset.
        if (child !== root) it.offset(child.scrollX, child.scrollY)
        root.offsetDescendantRectToMyCoords(child, it)
    }

    /**
     * @param action Notice action within the viewport.
     * @return Notice scroll viewport containing the action.
     */
    private fun noticeScroll(action: View): ScrollView = generateSequence(action.parent as? View) {
        it.parent as? View
    }.filterIsInstance<ScrollView>().first()

    /**
     * Describes the current measured geometry in foreground coordinates.
     * @param root Foreground coordinate space.
     * @param action Current action or control.
     * @return Diagnostic context for an assertion.
     */
    private fun geometryMessage(root: StandbyForegroundView, action: View): String {
        val globalToRoot = Matrix()
        val canMap = Matrix().also { root.transformMatrixToGlobal(it) }.invert(globalToRoot)
        val primary = listOf(
            StandbyForegroundTags.PHASE_EYEBROW, StandbyForegroundTags.PHASE_TITLE,
            StandbyForegroundTags.PHASE_DETAIL, StandbyForegroundTags.CLOCK,
            "foreground_status_dot", StandbyForegroundTags.MATTER_LIGHT,
        ).mapNotNull { tag ->
            val view = root.findViewWithTag<View>(tag)
            if (view == null || view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) {
                null
            } else {
                val viewBounds = bounds(root, view)
                val viewToRoot = if (canMap) {
                    val viewToGlobal = Matrix().also { view.transformMatrixToGlobal(it) }
                    Matrix().also { it.setConcat(globalToRoot, viewToGlobal) }
                } else {
                    null
                }
                fun map(rect: RectF): RectF = rect.also {
                    if (viewToRoot != null) viewToRoot.mapRect(it) else it.offset(viewBounds.left.toFloat(), viewBounds.top.toFloat())
                }
                val rect = map(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                val textLayout = (view as? TextView)?.layout
                val lines = if (view is TextView && textLayout != null) {
                    (0 until textLayout.lineCount).mapNotNull { line ->
                        val lineRect = RectF(
                            view.compoundPaddingLeft + textLayout.getLineLeft(line) - view.scrollX,
                            (view.extendedPaddingTop + textLayout.getLineTop(line) - view.scrollY).toFloat(),
                            view.compoundPaddingLeft + textLayout.getLineRight(line) - view.scrollX,
                            (view.extendedPaddingTop + textLayout.getLineBottom(line) - view.scrollY).toFloat(),
                        )
                        if (lineRect.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat())) map(lineRect) else null
                    }
                } else {
                    emptyList()
                }
                "$tag=$rect lines=$lines"
            }
        }
        val dock = root.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)
        val menu = root.findViewWithTag<View>(StandbyForegroundTags.MENU)
        val scroll = generateSequence(action.parent as? View) { it.parent as? View }
            .filterIsInstance<ScrollView>().firstOrNull()
        val metrics = root.context.resources.displayMetrics
        return "theme=${root.javaClass.simpleName} density=${metrics.density} densityDpi=${metrics.densityDpi} " +
            "viewport=${root.width}x${root.height} scrollY=${scroll?.scrollY?.toString() ?: "n/a"} " +
            "scroll=${scroll?.let { bounds(root, it) }} primary=$primary " +
            "dock=${dock?.let { bounds(root, it) }} dockVisibility=${dock?.visibility} " +
            "menu=${menu?.let { bounds(root, it) }} action=${action.tag}:${bounds(root, action)}"
    }

    /**
     * Scrolls one complete action into its viewport without moving the dock.
     * @param scroll Notice viewport.
     * @param action Target action.
     */
    private fun revealAction(scroll: ScrollView, action: View) {
        val rect = Rect(0, 0, action.width, action.height)
        scroll.offsetDescendantRectToMyCoords(action, rect)
        scroll.scrollTo(0, rect.top)
    }

    /**
     * Dispatches a complete pointer gesture through the foreground.
     * @param root Foreground receiving the gesture.
     * @param x Horizontal coordinate in foreground pixels.
     * @param y Vertical coordinate in foreground pixels.
     * @param message Geometry context for failures.
     */
    private fun tap(root: View, x: Float, y: Float, message: String) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue("ACTION_DOWN at ($x,$y): $message", root.dispatchTouchEvent(down))
            assertTrue("ACTION_UP at ($x,$y): $message", root.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Verifies the visible action against primary text and interactive hit bounds.
     * @param root Foreground coordinate space.
     * @param action Action to inspect.
     * @param minimum Minimum target dimension in native pixels.
     */
    private fun assertActionVisible(root: StandbyForegroundView, action: TextView, minimum: Int) {
        val scroll = noticeScroll(action)
        val actionBounds = bounds(root, action)
        val scrollBounds = bounds(root, scroll)
        val message = geometryMessage(root, action)
        assertTrue("Minimum target $minimum: $message", action.width >= minimum && action.height >= minimum)
        assertTrue("Action outside scroll viewport: $message", scrollBounds.contains(actionBounds))
        assertTrue("Action outside stage: $message", Rect(0, 0, root.width, root.height).contains(actionBounds))
        if (root.isAttachedToWindow) {
            val visible = Rect()
            assertTrue("Action not globally visible: $message", action.getGlobalVisibleRect(visible))
            assertEquals("Visible bounds $visible: $message", action.width, visible.width())
            assertEquals("Visible bounds $visible: $message", action.height, visible.height())
        }
        for (tag in listOf(StandbyForegroundTags.BROADCAST, StandbyForegroundTags.MENU)) {
            val control = root.findViewWithTag<View>(tag)
            if (control != null && control.visibility == View.VISIBLE) {
                assertFalse("Notice viewport overlaps $tag: $message", Rect.intersects(scrollBounds, bounds(root, control)))
            }
        }
        val rootToGlobal = Matrix().also { root.transformMatrixToGlobal(it) }
        val globalToRoot = Matrix()
        assertTrue("Cannot map primary bounds: $message", rootToGlobal.invert(globalToRoot))
        for (tag in listOf(
            StandbyForegroundTags.PHASE_EYEBROW, StandbyForegroundTags.PHASE_TITLE,
            StandbyForegroundTags.PHASE_DETAIL, StandbyForegroundTags.CLOCK,
        )) {
            val text = root.findViewWithTag<TextView>(tag) ?: continue
            if (text.visibility != View.VISIBLE || text.width == 0 || text.height == 0) continue
            val textLayout = requireNotNull(text.layout) { "Missing primary layout $tag: $message" }
            val textToGlobal = Matrix().also { text.transformMatrixToGlobal(it) }
            val textToRoot = Matrix().also { it.setConcat(globalToRoot, textToGlobal) }
            for (line in 0 until textLayout.lineCount) {
                val lineBounds = RectF(
                    text.compoundPaddingLeft + textLayout.getLineLeft(line) - text.scrollX,
                    (text.extendedPaddingTop + textLayout.getLineTop(line) - text.scrollY).toFloat(),
                    text.compoundPaddingLeft + textLayout.getLineRight(line) - text.scrollX,
                    (text.extendedPaddingTop + textLayout.getLineBottom(line) - text.scrollY).toFloat(),
                )
                if (lineBounds.intersect(0f, 0f, text.width.toFloat(), text.height.toFloat())) {
                    textToRoot.mapRect(lineBounds)
                    assertFalse(
                        "Notice viewport overlaps $tag line=$line primaryLine=$lineBounds: $message",
                        RectF.intersects(lineBounds, RectF(scrollBounds)),
                    )
                }
            }
        }
        for (tag in listOf("foreground_status_dot", StandbyForegroundTags.MATTER_LIGHT)) {
            val indicator = root.findViewWithTag<View>(tag) ?: continue
            if (indicator.visibility == View.VISIBLE) {
                assertFalse("Notice viewport overlaps $tag: $message", Rect.intersects(scrollBounds, bounds(root, indicator)))
            }
        }
        assertCompleteText(action, message)
    }

    @Test
    fun measuredBrandAndText_useCssDensityExceptForLegacyMatter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sizes = listOf(640 to 360, 800 to 450, 1280 to 720, 3840 to 2160)
            for (theme in 0..2) {
                var brandReference = 0f
                var textReference = 0f
                var boundsReference: List<Float>? = null
                for ((width, height) in sizes) {
                    for (dpi in listOf(160, 320, 480)) {
                        val ctx = context(dpi)
                        val view = when (theme) {
                            0 -> LandscapeStandbyForegroundView(ctx)
                            1 -> MatterStandbyForegroundView(ctx)
                            else -> HardlineStandbyForegroundView(ctx)
                        }
                        render(view)
                        layout(view, width, height)
                        val scale = if (theme == 1) sqrt(width.toDouble() * height / (390.0 * 844.0)).toFloat()
                            else ctx.resources.displayMetrics.density
                        val brand = requireNotNull(view.findViewWithTag<ViewGroup>(StandbyForegroundTags.BRAND))
                        val textTag = if (theme == 1) StandbyForegroundTags.PHASE_EYEBROW else StandbyForegroundTags.PHASE_TITLE
                        val text = requireNotNull(view.findViewWithTag<TextView>(textTag))
                        val normalizedBrand = brand.getChildAt(0).width / scale
                        val normalizedText = text.textSize / scale
                        if (theme != 1) {
                            assertEquals(30f, normalizedBrand, 1f)
                            assertEquals(40f, normalizedText, 0.01f)
                        }
                        val brandRect = Rect(0, 0, brand.width, brand.height)
                        val textRect = Rect(0, 0, text.width, text.height)
                        view.offsetDescendantRectToMyCoords(brand, brandRect)
                        view.offsetDescendantRectToMyCoords(text, textRect)
                        val normalizedBounds = listOf(
                            brandRect.left.toFloat() / width, brandRect.top.toFloat() / height,
                            textRect.left.toFloat() / width, textRect.top.toFloat() / height,
                            textRect.right.toFloat() / width, textRect.bottom.toFloat() / height,
                        )
                        val expectedBounds = boundsReference
                        if (expectedBounds == null) boundsReference = normalizedBounds else if (theme == 1) {
                            for (index in normalizedBounds.indices) {
                                assertEquals(expectedBounds[index], normalizedBounds[index], 0.008f)
                            }
                        }
                        if (brandReference == 0f) {
                            brandReference = normalizedBrand
                            textReference = normalizedText
                        } else {
                            assertEquals("brand theme=$theme $width x $height dpi=$dpi", brandReference, normalizedBrand, 1.5f)
                            assertEquals("text theme=$theme $width x $height dpi=$dpi", textReference, normalizedText, 0.1f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nativeDockArtwork_keepsNormalizedBoundsWhileTouchTargetUsesDensity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (theme in 0..2) {
                var reference: List<Float>? = null
                for ((width, height) in listOf(640 to 360, 1280 to 720, 3840 to 2160)) {
                    for (dpi in listOf(160, 320, 480)) {
                        val ctx = context(dpi)
                        val view = when (theme) {
                            0 -> LandscapeStandbyForegroundView(ctx)
                            1 -> MatterStandbyForegroundView(ctx)
                            else -> HardlineStandbyForegroundView(ctx)
                        }
                        render(view)
                        layout(view, width, height)
                        val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        val minimum = kotlin.math.ceil(48.0 * ctx.resources.displayMetrics.density).toInt()
                        assertTrue(dock.width >= minimum && dock.height >= minimum)
                        val dockBounds = Rect(0, 0, dock.width, dock.height)
                        view.offsetDescendantRectToMyCoords(dock, dockBounds)
                        val bitmap = Bitmap.createBitmap(dock.width, dock.height, Bitmap.Config.ARGB_8888)
                        val ink = Rect(dock.width, dock.height, 0, 0)
                        try {
                            dock.draw(Canvas(bitmap))
                            for (y in 0 until bitmap.height) {
                                for (x in 0 until bitmap.width) {
                                    if (bitmap.getPixel(x, y) ushr 24 != 0) {
                                        ink.left = minOf(ink.left, x)
                                        ink.top = minOf(ink.top, y)
                                        ink.right = maxOf(ink.right, x + 1)
                                        ink.bottom = maxOf(ink.bottom, y + 1)
                                    }
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        assertFalse(ink.isEmpty)
                        val artScale = if (theme == 1) sqrt(width.toDouble() * height / (390.0 * 844.0)).toFloat()
                            else ctx.resources.displayMetrics.density
                        val bounds = listOf(ink.width() / artScale, ink.height() / artScale)
                        if (theme != 1) assertEquals(if (theme == 2) 84f else 96f, dock.width / artScale, 1f)
                        val expected = reference
                        if (expected == null) reference = bounds else {
                            for (index in bounds.indices) assertEquals(expected[index], bounds[index], 2f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun portrait_proportionalInsetsAndBoundsRemainStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (theme in 0..2) {
                val ctx = context(320)
                val view = when (theme) {
                    0 -> LandscapeStandbyForegroundView(ctx)
                    1 -> MatterStandbyForegroundView(ctx)
                    else -> HardlineStandbyForegroundView(ctx)
                }
                render(view)
                view.applySecurityInsets(Insets.of(10, 20, 30, 40))
                layout(view, 720, 1280)
                val brand = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                val first = Rect(0, 0, brand.width, brand.height)
                view.offsetDescendantRectToMyCoords(brand, first)
                view.applySecurityInsets(Insets.of(30, 60, 90, 120))
                layout(view, 2160, 3840)
                val second = Rect(0, 0, brand.width, brand.height)
                view.offsetDescendantRectToMyCoords(brand, second)
                if (theme == 1) {
                    assertEquals(first.left.toFloat() / 720, second.left.toFloat() / 2160, 0.002f)
                    assertEquals(first.top.toFloat() / 1280, second.top.toFloat() / 3840, 0.002f)
                    assertEquals(first.width().toFloat() / 720, second.width().toFloat() / 2160, 0.002f)
                    assertEquals(first.height().toFloat() / 1280, second.height().toFloat() / 3840, 0.002f)
                } else {
                    assertEquals(first.width(), second.width())
                    assertEquals(first.height(), second.height())
                    assertEquals(20 + 64 - 48, second.left - first.left)
                    assertEquals(40 + 64 - 48, second.top - first.top)
                }
            }
        }
    }

    @Test
    fun preferencesInsetsAndRealTouch_surviveResizeWithoutReplacingViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                for (theme in 0..2) {
                    val ctx = context(480, 1.5f, locale)
                    val view = when (theme) {
                        0 -> LandscapeStandbyForegroundView(ctx)
                        1 -> MatterStandbyForegroundView(ctx)
                        else -> HardlineStandbyForegroundView(ctx)
                    }
                    var toggles = 0
                    val name = "Receiver 接收裝置 ".repeat(8)
                    view.render(
                        StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = name),
                        "Ready", "Waiting", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = { toggles++ }),
                    )
                    layout(view, 640, 360)
                    val receiverTag = if (theme == 1) "foreground_hint_title" else StandbyForegroundTags.RECEIVER_NAME
                    val receiver = requireNotNull(view.findViewWithTag<TextView>(receiverTag))
                    assertEquals(name, receiver.text.toString())
                    val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertTrue(dock.isFocusable && dock.isClickable)
                    val target = Rect(0, 0, dock.width, dock.height)
                    view.offsetDescendantRectToMyCoords(dock, target)
                    val down = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), 0)
                    val cancel = MotionEvent.obtain(1, 2, MotionEvent.ACTION_CANCEL, target.exactCenterX(), target.exactCenterY(), 0)
                    try {
                        assertTrue(view.dispatchTouchEvent(down))
                        assertTrue(dock.isPressed)
                        view.dispatchTouchEvent(cancel)
                    } finally {
                        down.recycle()
                        cancel.recycle()
                    }
                    assertTrue(dock.performClick())
                    assertEquals(1, toggles)
                    val brand = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                    val before = Rect(0, 0, brand.width, brand.height)
                    view.offsetDescendantRectToMyCoords(brand, before)
                    view.applySecurityInsets(Insets.of(10, 20, 30, 40))
                    layout(view, 640, 360)
                    val after = Rect(0, 0, brand.width, brand.height)
                    view.offsetDescendantRectToMyCoords(brand, after)
                    assertEquals(before.left + 10, after.left)
                    assertEquals(before.top + 20, after.top)
                    layout(view, 1280, 720)
                    assertSame(dock, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(brand, view.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                    val textTag = if (theme == 1) StandbyForegroundTags.PHASE_EYEBROW else StandbyForegroundTags.PHASE_TITLE
                    val text = requireNotNull(view.findViewWithTag<TextView>(textTag))
                    val normalSize = if (theme == 1) 20f else 40f
                    val expected = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, normalSize, ctx.resources.displayMetrics,
                    ) * if (theme == 1) sqrt(1280.0 * 720 / (390.0 * 844.0)).toFloat() /
                        ctx.resources.displayMetrics.density else 1f
                    assertEquals(expected, text.textSize, 0.01f)
                }
            }
        }
    }

    @Test
    fun frozenActionsAt360pAndDensityThree_doNotOverlapPrimaryContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val windowFocused = CountDownLatch(1)
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) windowFocused.countDown()
        }
        var observer: ViewTreeObserver? = null
        var trackedActions: List<TextView> = emptyList()
        var ownedView: StandbyForegroundView? = null
        var firstFailure: Throwable? = null
        fun recordFailure(step: String, error: Throwable): Throwable {
            val first = firstFailure
            if (first != null && (first === error || first.suppressed.any { it === error })) return first
            Log.e("StandbyGeometry", step, error)
            if (first == null) firstFailure = error else first.addSuppressed(error)
            return first ?: error
        }
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            fun removeOwnedCase(fixture: String) {
                scenario.onActivity { activity ->
                    trackedActions = trackedActions.filter { action ->
                        try {
                            action.setOnKeyListener(null)
                            false
                        } catch (error: Throwable) {
                            recordFailure("$fixture cleanup key listener ${action.tag}", error)
                            true
                        }
                    }
                    ownedView?.let { view ->
                        if (view.parent === activity.contentRoot) activity.contentRoot.removeView(view)
                        check(view.parent == null && !view.isAttachedToWindow) { "$fixture foreground removal incomplete" }
                        ownedView = null
                    }
                    if (trackedActions.isNotEmpty()) firstFailure?.let { throw it }
                }
            }
            try {
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    observer = decor.viewTreeObserver
                    observer?.addOnWindowFocusChangeListener(focusListener)
                    if (decor.hasWindowFocus()) windowFocused.countDown()
                }
                var caseAssertion: AssertionError? = null
                fun runCase(strict: Boolean, width: Int, height: Int, theme: Int, withFailure: Boolean) {
                    lateinit var view: StandbyForegroundView
                    lateinit var actions: List<TextView>
                    var infoClicks = 0
                    var shizukuClicks = 0
                    var dismissClicks = 0
                    var menuClicks = 0
                    val fixture = "frozenActionsAt360p ${if (strict) "strict" else "stress"} ${width}x$height theme=$theme withFailure=$withFailure"
                    fun observeOwnedCase(assertions: (StandbyProbeActivity) -> Unit) {
                        scenario.onActivity { activity ->
                            check(view.parent === activity.contentRoot && view.isAttachedToWindow &&
                                view.rootView === activity.window.decorView && view.windowVisibility == View.VISIBLE) {
                                "$fixture lost its attached probe window"
                            }
                            try {
                                assertions(activity)
                            } catch (error: AssertionError) {
                                caseAssertion = error
                                throw error
                            }
                        }
                    }
                    fun keySnapshot(action: View): String =
                        "$fixture target=${action.tag} " +
                            "windowFocus=${view.rootView.hasWindowFocus()} targetFocus=${action.hasFocus()} " +
                            "touchMode=${action.isInTouchMode} pressed=${action.isPressed} " +
                            "rootFocus=${view.rootView.findFocus()?.tag} " +
                            "callbacks=info:$infoClicks,shizuku:$shizukuClicks,dismiss:$dismissClicks,menu:$menuClicks"
                    val ctx = context(480)
                    scenario.onActivity { activity ->
                        view = when (theme) {
                            0 -> LandscapeStandbyForegroundView(ctx)
                            1 -> MatterStandbyForegroundView(ctx)
                            else -> HardlineStandbyForegroundView(ctx)
                        }
                        ownedView = view
                        view.applySecurityInsets(Insets.NONE)
                        view.render(
                            StandbyForegroundState(
                                ScreenPhase.FROZEN, false, shouldOfferShizuku = true,
                                receiverName = "Receiver",
                                failureCause = if (withFailure) PrePlayFailureCause.PRE_PLAY_TIMEOUT else null,
                                failureText = if (withFailure) "Host timeout" else "",
                            ),
                            "Frozen", "FROZEN", "Detail",
                            StandbyForegroundCallbacks(
                                onShowFrozenInfo = { infoClicks++ }, onOpenShizuku = { shizukuClicks++ },
                                onDismissFailure = { dismissClicks++ }, onOpenSettings = { menuClicks++ },
                            ),
                        )
                        activity.contentRoot.addView(view, ViewGroup.LayoutParams(width, height))
                    }
                    awaitForegroundLayout(scenario, view)
                    observeOwnedCase {
                        val density = view.resources.displayMetrics.density
                        val usableShortDp = minOf(view.width - view.paddingLeft - view.paddingRight,
                            view.height - view.paddingTop - view.paddingBottom) / density
                        val evidence = "$fixture measured=${view.measuredWidth}x${view.measuredHeight} " +
                            "laidOut=${view.width}x${view.height} density=$density usableShortDp=$usableShortDp"
                        Log.i("StandbyGeometry", evidence)
                        check(density == 3f && view.measuredWidth == width && view.measuredHeight == height &&
                            view.width == width && view.height == height && strict == (usableShortDp >= 144f)) { evidence }
                        val info = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FROZEN_INFO))
                        val shizuku = requireNotNull(view.findViewWithTag<TextView>("foreground_shizuku"))
                        val dismiss = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED_DISMISS))
                        actions = if (withFailure) listOf(dismiss, info, shizuku) else listOf(info, shizuku)
                                        val failed = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        val failureVisibility = if (withFailure) View.VISIBLE else View.GONE
                                                        assertEquals(evidence, failureVisibility, failed.visibility)
                        assertEquals(evidence, failureVisibility, dismiss.visibility)
                        assertEquals(evidence, if (withFailure) "Host timeout" else "", failed.text.toString())
                        val nameTag = if (theme == 1) "foreground_hint_title" else StandbyForegroundTags.RECEIVER_NAME
                        assertEquals(evidence, "Receiver", requireNotNull(view.findViewWithTag<TextView>(nameTag)).text.toString())
                        val scroll = noticeScroll(info)
                        check(scroll.isAttachedToWindow && scroll.isLaidOut && scroll.rootView === view.rootView) { evidence }
                        assertTrue(evidence, scroll.width > 0 && scroll.height > 0)
                        if (withFailure) {
                            assertTrue("Expected scrollable content: ${geometryMessage(view, info)}", scroll.getChildAt(0).height > scroll.height)
                            // Start at the actual top so initial focus cannot exhaust the travel before observation.
                            scroll.scrollTo(0, 0)
                        }
                                        assertEquals(evidence, 0, infoClicks + shizukuClicks + dismissClicks + menuClicks)
                        if (strict) {
                            trackedActions = actions
                            actions.forEach { action ->
                                action.setOnKeyListener { _, keyCode, event ->
                                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                                        Log.i("StandbyKeyProbe", "delivered=${event.action} ${keySnapshot(action)}")
                                    }
                                    false
                                }
                            }
                        }
                    }
                    var scrollDisplaced = false
                    val scrollSteps = mutableListOf<String>()
                    for (action in actions) {
                        observeOwnedCase {
                            val scroll = noticeScroll(actions.first())
                            val minimum = kotlin.math.ceil(48.0 * view.resources.displayMetrics.density).toInt()
                            check(action.isAttachedToWindow && action.rootView === view.rootView && noticeScroll(action) === scroll) {
                                "$fixture action=${action.tag} lost its owned scroll viewport"
                            }
                            val viewportBefore = bounds(view, scroll)
                            val actionBefore = bounds(view, action)
                            val scrollYBefore = scroll.scrollY
                            revealAction(scroll, action)
                            val scrollYAfter = scroll.scrollY
                            val step = "${action.tag}:$scrollYBefore->$scrollYAfter"
                            val scrollMessage = "$fixture scrollY=$step"
                            assertEquals("Scroll viewport moved: $scrollMessage", viewportBefore, bounds(view, scroll))
                            assertEquals(
                                "Action did not follow scroll: $scrollMessage",
                                actionBefore.top - (scrollYAfter - scrollYBefore),
                                bounds(view, action).top,
                            )
                            scrollDisplaced = scrollDisplaced || scrollYAfter != scrollYBefore
                            scrollSteps.add(step)
                            val message = "$fixture ${geometryMessage(view, action)}"
                            assertTrue(message, action.isLaidOut && action.isShown)
                            assertTrue("Minimum target $minimum: $message",
                                minOf(action.measuredWidth, action.measuredHeight, action.width, action.height) >= minimum)
                            if (strict) {
                                assertActionVisible(view, action, minimum)
                                val rect = bounds(view, action)
                                for (other in actions.filter { it !== action }) {
                                    assertFalse(
                                        "Other action ${other.tag}:${bounds(view, other)}: $message",
                                        Rect.intersects(rect, bounds(view, other)),
                                    )
                                }
                                tap(view, rect.exactCenterX(), rect.exactCenterY(), message)
                            } else {
                                assertTrue("Request focus: $message", action.requestFocusFromTouch())
                                assertTrue("Action focus: $message", action.isFocused)
                            }
                        }
                        instrumentation.waitForIdleSync()
                    }
                    observeOwnedCase {
                        if (withFailure) {
                            val scroll = noticeScroll(actions.first())
                            val evidence = "$fixture contentHeight=${scroll.getChildAt(0).height} viewportHeight=${scroll.height} " +
                                "scrollY=${scrollSteps.joinToString()}"
                            Log.i("StandbyGeometry", evidence)
                            assertTrue("No action followed a nonzero scroll: $evidence", scrollDisplaced)
                        }
                        if (!strict) assertEquals(fixture, 0, infoClicks + shizukuClicks + dismissClicks + menuClicks)
                    }
                    if (!strict) return
                    val released = CountDownLatch(1)
                    var pressedObserver: ViewTreeObserver? = null
                    val pressedListener = ViewTreeObserver.OnPreDrawListener {
                        if (actions.none { it.isPressed }) released.countDown() else view.postInvalidateOnAnimation()
                        true
                    }
                    try {
                        scenario.onActivity {
                            Log.i("StandbyKeyProbe", "before pressed-wait ${actions.joinToString { keySnapshot(it) }}")
                            if (actions.none { it.isPressed }) {
                                released.countDown()
                            } else {
                                pressedObserver = view.viewTreeObserver
                                pressedObserver?.addOnPreDrawListener(pressedListener)
                                view.postInvalidateOnAnimation()
                            }
                        }
                        val releasedInTime = released.await(5, TimeUnit.SECONDS)
                        var stillReleased = false
                        var pressedEvidence = ""
                        scenario.onActivity {
                            stillReleased = actions.none { it.isPressed }
                            pressedEvidence = actions.joinToString { keySnapshot(it) }
                            Log.i("StandbyKeyProbe", "after pressed-wait $pressedEvidence")
                        }
                        assertTrue("Timed out waiting for natural unpress: $pressedEvidence", releasedInTime && stillReleased)
                    } catch (error: Throwable) {
                        throw recordFailure("$fixture pressed-state wait", error)
                    } finally {
                        try {
                            scenario.onActivity {
                                pressedObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(pressedListener)
                            }
                        } catch (cleanup: Throwable) {
                            throw recordFailure("$fixture cleanup pressed-state observer", cleanup)
                        }
                    }
                    val ready = windowFocused.await(5, TimeUnit.SECONDS)
                    var focusEvidence = ""
                    if (!ready) scenario.onActivity { focusEvidence = keySnapshot(actions.first()) }
                    check(ready) { "Timed out waiting for own window focus: $focusEvidence" }
                    fun requireFocusedWindow(activity: StandbyProbeActivity, action: View) {
                        val decor = activity.window.decorView
                        check(view.parent === activity.contentRoot && view.rootView === decor &&
                            action.isAttachedToWindow && action.rootView === decor && decor.hasWindowFocus()) {
                            "Lost owned, focused probe window: ${keySnapshot(action)}"
                        }
                    }
                    observeOwnedCase { activity ->
                        val message = "$fixture ${geometryMessage(view, actions.last())}"
                        requireFocusedWindow(activity, actions.first())
                        assertEquals("Info callback: $message", 1, infoClicks)
                        assertEquals("Shizuku callback: $message", 1, shizukuClicks)
                        assertEquals("Dismiss callback: $message", if (withFailure) 1 else 0, dismissClicks)
                        assertEquals("Menu callback: $message", 0, menuClicks)
                        revealAction(noticeScroll(actions.first()), actions.first())
                        val firstMessage = geometryMessage(view, actions.first())
                        assertTrue("Request focus: $firstMessage", actions.first().requestFocusFromTouch())
                        assertTrue("Initial focus: $firstMessage", actions.first().isFocused)
                    }
                    for ((index, action) in actions.withIndex()) {
                        if (index > 0) {
                            var focused = false
                            var message = ""
                            repeat(8) {
                                if (!focused) {
                                    scenario.onActivity { activity -> requireFocusedWindow(activity, action) }
                                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                                    scenario.onActivity { activity ->
                                        requireFocusedWindow(activity, action)
                                        focused = action.isFocused
                                        message = geometryMessage(view, action)
                                    }
                                }
                            }
                            observeOwnedCase { activity ->
                                requireFocusedWindow(activity, action)
                                assertTrue("DPAD could not reach notice action $index: $message", focused)
                            }
                        }
                        scenario.onActivity { activity ->
                            requireFocusedWindow(activity, action)
                            check(action.isFocused) { "Target lost focus: ${keySnapshot(action)}" }
                            Log.i("StandbyKeyProbe", "before CENTER ${keySnapshot(action)}")
                        }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            requireFocusedWindow(activity, action)
                            Log.i("StandbyKeyProbe", "after CENTER ${keySnapshot(action)}")
                        }
                    }
                    observeOwnedCase { activity ->
                        requireFocusedWindow(activity, actions.last())
                        val message = "$fixture ${geometryMessage(view, actions.last())}"
                        assertEquals("DPAD info callback: $message", 2, infoClicks)
                        assertEquals("DPAD Shizuku callback: $message", 2, shizukuClicks)
                        assertEquals("DPAD dismiss callback: $message", if (withFailure) 2 else 0, dismissClicks)
                        assertEquals("DPAD menu callback: $message", 0, menuClicks)
                    }
                }

                for ((strict, width, height) in listOf(Triple(false, 640, 360), Triple(true, 768, 432))) {
                    for (theme in 0..2) for (withFailure in listOf(false, true)) {
                        val fixture = "strict=$strict ${width}x$height theme=$theme withFailure=$withFailure"
                        caseAssertion = null
                        try {
                            runCase(strict, width, height, theme, withFailure)
                        } catch (error: Throwable) {
                            val primary = recordFailure(fixture, error)
                            // Only a test assertion in the owned case may continue after proven cleanup.
                            if (error !== caseAssertion) throw primary
                        } finally {
                            try {
                                removeOwnedCase(fixture)
                            } catch (cleanup: Throwable) {
                                throw recordFailure("$fixture cleanup owned case", cleanup)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                recordFailure("frozenActionsAt360p fixture", error)
            } finally {
                if (ownedView != null || trackedActions.isNotEmpty()) {
                    try {
                        removeOwnedCase("frozenActionsAt360p fallback")
                    } catch (cleanup: Throwable) {
                        recordFailure("frozenActionsAt360p cleanup owned case", cleanup)
                    }
                }
                try {
                    scenario.onActivity { observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener) }
                } catch (cleanup: Throwable) {
                    recordFailure("frozenActionsAt360p cleanup window focus observer", cleanup)
                }
            }
            firstFailure?.let { throw it }
        }
    }

    @Test
    fun frozenPortrait_preservesCompleteLocalizedTextAndReachableActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                for ((locale, fontScale) in listOf(Locale.ENGLISH to 1f, Locale.TRADITIONAL_CHINESE to 1.5f)) {
                    for (theme in 0..2) for (withInfo in listOf(true, false)) {
                        val ctx = context(160, fontScale, locale)
                        lateinit var view: StandbyForegroundView
                        lateinit var actions: List<TextView>
                        var infoClicks = 0
                        var shizukuClicks = 0
                        scenario.onActivity { activity ->
                            view = when (theme) {
                                0 -> LandscapeStandbyForegroundView(ctx)
                                1 -> MatterStandbyForegroundView(ctx)
                                else -> HardlineStandbyForegroundView(ctx)
                            }
                            view.render(
                                StandbyForegroundState(
                                    ScreenPhase.FROZEN, false, shouldOfferShizuku = true,
                                    receiverName = "Receiver",
                                ),
                                "Frozen", "FROZEN", "Detail",
                                StandbyForegroundCallbacks(
                                    onShowFrozenInfo = if (withInfo) ({ infoClicks++ }) else null,
                                    onOpenShizuku = { shizukuClicks++ },
                                ),
                            )
                            // The logical portrait viewport can extend below the physical window.
                            activity.contentRoot.addView(view, ViewGroup.LayoutParams(390, 844))
                        }
                        awaitForegroundLayout(scenario, view)
                        scenario.onActivity {
                            assertEquals(390, view.width)
                            assertEquals(844, view.height)
                                                val info = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FROZEN_INFO))
                            val shizuku = requireNotNull(view.findViewWithTag<TextView>("foreground_shizuku"))
                            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                            val scroll = noticeScroll(shizuku)
                            val message = "locale=$locale fontScale=$fontScale withInfo=$withInfo ${geometryMessage(view, shizuku)}"
                            assertEquals(message, if (withInfo) View.VISIBLE else View.GONE, dock.visibility)
                            if (withInfo) assertTrue(message, bounds(view, scroll).left >= bounds(view, dock).right)
                                                assertEquals(message, ctx.getString(R.string.frozen_open_shizuku), shizuku.text.toString())
                            if (locale == Locale.ENGLISH) assertEquals(message, "Open Shizuku authorization", shizuku.text.toString())
                                                assertNull(message, shizuku.ellipsize)
                                                val expectedSize = android.util.TypedValue.applyDimension(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 12f, ctx.resources.displayMetrics,
                            ) / ctx.resources.displayMetrics.density
                            assertEquals(message, expectedSize, shizuku.textSize, 0.01f)
                            actions = if (withInfo) listOf(info, shizuku) else listOf(shizuku)
                        }
                        for (action in actions) {
                            scenario.onActivity {
                                revealAction(noticeScroll(action), action)
                                val minimum = kotlin.math.ceil(48.0 * ctx.resources.displayMetrics.density).toInt()
                                assertActionVisible(view, action, minimum)
                                val rect = bounds(view, action)
                                tap(view, rect.exactCenterX(), rect.exactCenterY(), geometryMessage(view, action))
                            }
                            instrumentation.waitForIdleSync()
                        }
                        scenario.onActivity { activity ->
                            val finalMessage = "locale=$locale fontScale=$fontScale withInfo=$withInfo ${geometryMessage(view, actions.last())}"
                            assertEquals(finalMessage, if (withInfo) 1 else 0, infoClicks)
                            assertEquals(finalMessage, 1, shizukuClicks)
                            activity.contentRoot.removeView(view)
                        }
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun matterDensityFive_separatesFocusAndTouchWithoutMovingDockArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                // The legacy area-invariant fixture is Matter-only; CSS themes scale with density.
                for (theme in listOf(1)) {
                    var reference: Bitmap? = null
                    var glyphX = 0f
                    var glyphY = 0f
                    for (densityDpi in listOf(160, 800)) {
                        val ctx = context(densityDpi)
                        lateinit var view: StandbyForegroundView
                        var broadcastClicks = 0
                        var menuClicks = 0
                        scenario.onActivity { activity ->
                            view = when (theme) {
                                0 -> LandscapeStandbyForegroundView(ctx)
                                1 -> MatterStandbyForegroundView(ctx)
                                else -> HardlineStandbyForegroundView(ctx)
                            }
                            view.render(
                                StandbyForegroundState(ScreenPhase.ADVERTISING, true), "Ready", "Waiting", "Detail",
                                StandbyForegroundCallbacks(
                                    onToggleBroadcast = { broadcastClicks++ }, onOpenSettings = { menuClicks++ },
                                ),
                            )
                            activity.contentRoot.addView(view, ViewGroup.LayoutParams(640, 360))
                        }
                        awaitForegroundLayout(scenario, view)
                        scenario.onActivity {
                            assertEquals(640, view.width)
                            assertEquals(360, view.height)
                            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                            val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                            val dockBounds = bounds(view, dock)
                            val menuBounds = bounds(view, menu)
                            val message = geometryMessage(view, dock)
                            val minimum = kotlin.math.ceil(48.0 * ctx.resources.displayMetrics.density).toInt()
                            assertTrue("Minimum target $minimum: $message", dockBounds.width() >= minimum && dockBounds.height() >= minimum)
                            assertTrue("Minimum target $minimum: $message", menuBounds.width() >= minimum && menuBounds.height() >= minimum)
                            assertFalse("Hit targets overlap: $message", Rect.intersects(dockBounds, menuBounds))
                            val visibleDock = Rect()
                            val visibleMenu = Rect()
                            assertTrue("Dock not globally visible: $message", dock.getGlobalVisibleRect(visibleDock))
                            assertTrue("Menu not globally visible: $message", menu.getGlobalVisibleRect(visibleMenu))
                            assertEquals("Visible dock $visibleDock: $message", dock.width, visibleDock.width())
                            assertEquals("Visible dock $visibleDock: $message", dock.height, visibleDock.height())
                            assertEquals("Visible menu $visibleMenu: $message", menu.width, visibleMenu.width())
                            assertEquals("Visible menu $visibleMenu: $message", menu.height, visibleMenu.height())
                            val dockFocus = Rect().also { dock.getFocusedRect(it); view.offsetDescendantRectToMyCoords(dock, it) }
                            val menuFocus = Rect().also { menu.getFocusedRect(it); view.offsetDescendantRectToMyCoords(menu, it) }
                            assertFalse("Focus rects dock=$dockFocus menu=$menuFocus: $message", Rect.intersects(dockFocus, menuFocus))
                            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.translate(dockBounds.left.toFloat(), dockBounds.top.toFloat())
                            dock.draw(canvas)
                            if (densityDpi == 160) {
                                reference = bitmap
                                val artSize = 84f * sqrt(640.0 * 360 / (390.0 * 844.0)).toFloat()
                                glyphX = dockBounds.exactCenterX() - if (theme == 2) artSize * 0.02380952f else 0f
                                glyphY = dockBounds.exactCenterY() + when (theme) {
                                    0 -> -0.02f * artSize
                                    1 -> -0.11333333f * artSize
                                    else -> -0.00619048f * artSize
                                }
                                var bestScore = 0
                                var bestX = glyphX.toInt()
                                var bestY = glyphY.toInt()
                                for (y in glyphY.toInt() - 2..glyphY.toInt() + 2) {
                                    for (x in glyphX.toInt() - 2..glyphX.toInt() + 2) {
                                        val color = bitmap.getPixel(x, y)
                                        val red = android.graphics.Color.red(color)
                                        val green = android.graphics.Color.green(color)
                                        val blue = android.graphics.Color.blue(color)
                                        val score = if (theme == 0) red - maxOf(green, blue) else minOf(red, green, blue)
                                        if (score > bestScore) {
                                            bestScore = score
                                            bestX = x
                                            bestY = y
                                        }
                                    }
                                }
                                assertTrue("Expected a visible dock symbol: $message", bestScore > 60)
                                glyphX = bestX + 0.5f
                                glyphY = bestY + 0.5f
                            } else {
                                assertTrue("Dock artwork changed: $message", requireNotNull(reference).sameAs(bitmap))
                            }
                            assertTrue("Glyph ($glyphX,$glyphY) outside dock: $message", dockBounds.contains(glyphX.toInt(), glyphY.toInt()))
                            tap(view, glyphX, glyphY, message)
                        }
                        instrumentation.waitForIdleSync()
                        scenario.onActivity {
                            val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                            val menuBounds = bounds(view, menu)
                            tap(view, menuBounds.exactCenterX(), menuBounds.exactCenterY(), geometryMessage(view, menu))
                        }
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                            val message = geometryMessage(view, dock)
                            assertEquals("Broadcast callback: $message", 1, broadcastClicks)
                            assertEquals("Menu callback: $message", 1, menuClicks)
                            activity.contentRoot.removeView(view)
                        }
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun cssDensityFive_keepsSourceSlotsAndMinimumTargetsInTheSameLogicalViewport() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (dpi in listOf(160, 800)) for (hardline in listOf(false, true)) {
                val ctx = context(dpi)
                val density = ctx.resources.displayMetrics.density
                val view = if (hardline) HardlineStandbyForegroundView(ctx) else LandscapeStandbyForegroundView(ctx)
                var clicks = 0
                view.render(StandbyForegroundState(ScreenPhase.ADVERTISING, true), "Ready", "BROADCAST ON", "",
                    StandbyForegroundCallbacks(onToggleBroadcast = { clicks++ }, onOpenSettings = {}))
                layout(view, (640 * density).toInt(), (360 * density).toInt())
                val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                val dockBounds = bounds(view, dock)
                val menuBounds = bounds(view, menu)
                assertEquals(((if (hardline) 84 else 96) * density).toInt(), dock.width)
                assertTrue(dock.width >= 48 * density && dock.height >= 48 * density)
                assertTrue(menu.width >= 48 * density && menu.height >= 48 * density)
                assertFalse(Rect.intersects(dockBounds, menuBounds))
                assertTrue(Rect(0, 0, view.width, view.height).contains(dockBounds))
                val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                assertEquals(40 * density, title.textSize, 0.01f)
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, dockBounds.exactCenterX(), dockBounds.exactCenterY(), 0)
                val cancel = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_CANCEL, dockBounds.exactCenterX(), dockBounds.exactCenterY(), 0)
                try {
                    assertTrue(view.dispatchTouchEvent(down))
                    assertTrue(dock.isPressed)
                    view.dispatchTouchEvent(cancel)
                    assertFalse(dock.isPressed)
                    assertEquals(0, clicks)
                    assertTrue(dock.performClick())
                    assertEquals(1, clicks)
                } finally {
                    down.recycle()
                    cancel.recycle()
                }
            }
        }
    }

    @Test
    fun landscapeReceiver_followsResponsiveContractAndSurvivesResize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = LandscapeStandbyForegroundView(context(160))
            render(view, "Receiver name")
            val name = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
            val receiver = name.parent as View
            for ((width, height, visible) in listOf(
                Triple(390, 844, true), Triple(780, 1688, true), Triple(360, 640, false),
                Triple(1440, 1000, true), Triple(896, 592, false),
            )) {
                layout(view, width, height)
                assertSame(name, view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                assertEquals("$width x $height", visible, receiver.visibility == View.VISIBLE)
                assertEquals("Receiver name", name.text.toString())
            }
            val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
            assertTrue(hint.text.toString().contains("Receiver name"))
            assertFalse(hint.visibility == View.GONE)
        }
    }
}
