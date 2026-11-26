package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import me.trinitrix.mirax.R
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.runtime.StandbyOledDriftMath
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures foreground and target geometry on the UI thread for a specific test case.
 *
 * @param caseId Test case and explicit inset values.
 * @param root Foreground whose coordinate space is used for line boxes.
 * @param targets Queried views whose ancestor chains are included.
 * @return Bounded diagnostic snapshot.
 */
internal fun foregroundSnapshot(caseId: String, root: View, vararg targets: View): String {
    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
    fun readBoolean(name: String): String = if (root !is StandbyForegroundView) "not-foreground" else try {
        val owner = generateSequence<Class<*>>(root.javaClass) { it.superclass }
            .firstOrNull { type -> type.declaredFields.any { it.name == name } }
            ?: throw NoSuchFieldException("$name on ${root.javaClass.name}")
        owner.getDeclaredField(name).apply { isAccessible = true }.getBoolean(root).toString()
    } catch (error: Exception) {
        Log.w("StandbyGeometry", "$name state unavailable for $caseId", error)
        "unavailable"
    }
    val completedLayout = readBoolean("hasCompletedLayout")
    val observing = readBoolean("observing")
    val window = root.rootView
    val location = IntArray(2).also { window.getLocationOnScreen(it) }
    val frame = Rect().also { window.getWindowVisibleDisplayFrame(it) }
    val toRoot = Matrix()
    val canMap = Matrix().also { root.transformMatrixToGlobal(it) }.invert(toRoot)
    val rootToScreenInvertible = Matrix().also { window.transformMatrixToGlobal(it) }.invert(Matrix())
    val inspected = if (targets.isNotEmpty()) targets.toList() else listOf(
        StandbyForegroundTags.BRAND, StandbyForegroundTags.PHASE_TITLE, StandbyForegroundTags.RECEIVER_NAME,
        StandbyForegroundTags.MATTER_PLATE, "foreground_hint", StandbyForegroundTags.BROADCAST, StandbyForegroundTags.MENU,
        StandbyForegroundTags.FAILED, StandbyForegroundTags.FAILED_DISMISS, "foreground_shizuku",
    ).mapNotNull { root.findViewWithTag<View>(it) }
    // shortcut: cap ancestor nodes at 24 and text lines at four, increase limits for larger fixtures.
    val nodes = (listOf(root) + inspected + inspected.flatMap { target ->
        generateSequence(target.parent as? View) { it.parent as? View }.toList()
    }).distinct().take(24)
    // L=laid out, R=requested, V=visibility, A=alpha, O=clipToOutline, M=rectStaysRect, P=parent visible; t/f=boolean, -=no parent.
    val gates = mutableListOf<String>()
    val details = nodes.joinToString(" | ") { node ->
        val visible = Rect()
        val global = node.getGlobalVisibleRect(visible)
        val parentBounds = Rect(0, 0, node.width, node.height)
        val parentVisible = node.parent?.getChildVisibleRect(node, parentBounds, null)
        val rectStaysRect = node.matrix.rectStaysRect()
        val outlineDetails = if (node.clipToOutline) {
            val outline = Outline()
            val provider = node.outlineProvider
            provider?.getOutline(node, outline)
            val bounds = Rect()
            " outline(provider=${provider != null},empty=${outline.isEmpty},rect=${if (outline.getRect(bounds)) bounds.toString() else "unsupported"},radius=${outline.radius})"
        } else ""
        gates += "${node.javaClass.simpleName.ifEmpty { node.javaClass.name.substringAfterLast('.') }}(${node.tag},${node.width}x${node.height}):" +
            "L${node.isLaidOut.toString().first()}R${node.isLayoutRequested.toString().first()}V${node.visibility}A${node.alpha}" +
            "O${node.clipToOutline.toString().first()}M${rectStaysRect.toString().first()}P${parentVisible?.toString()?.first() ?: '-'}$outlineDetails"
        val mapped = Matrix().also { matrix ->
            if (canMap) matrix.setConcat(toRoot, Matrix().also { node.transformMatrixToGlobal(it) })
        }
        fun map(rect: RectF): RectF = rect.also { if (canMap) mapped.mapRect(it) }
        val text = (node as? TextView)?.let { textView ->
            textView.layout?.let { layout ->
                val indices = (0 until minOf(layout.lineCount, 3)).toList() +
                    if (layout.lineCount > 3) listOf(layout.lineCount - 1) else emptyList()
                val value = layout.text.toString()
                indices.map { line ->
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line)
                    // shortcut: ink estimates omit bidi and ellipsis shaping, use glyph-run bounds when exact ink matters.
                    val ink = Rect().also { textView.paint.getTextBounds(value, start, end, it) }
                    val left = textView.compoundPaddingLeft + layout.getLineLeft(line) - textView.scrollX
                    val top = textView.extendedPaddingTop - textView.scrollY
                    val box = map(RectF(left, (top + layout.getLineTop(line)).toFloat(),
                        textView.compoundPaddingLeft + layout.getLineRight(line) - textView.scrollX,
                        (top + layout.getLineBottom(line)).toFloat()))
                    val inkEstimate = map(RectF(left + ink.left, (top + layout.getLineBaseline(line) + ink.top).toFloat(),
                        left + ink.right, (top + layout.getLineBaseline(line) + ink.bottom).toFloat()))
                    "$line:box=$box,inkEstimate=$inkEstimate,ellipsis=${layout.getEllipsisCount(line)}"
                }
            }
        }
        val group = node as? ViewGroup
        val container = map(RectF(0f, 0f, node.width.toFloat(), node.height.toFloat()))
        "${node.javaClass.simpleName}(${node.tag},${node.width}x${node.height},container=$container,attached=${node.isAttachedToWindow}," +
            "laidOut=${node.isLaidOut},inLayout=${node.isInLayout},requested=${node.isLayoutRequested},visibility=${node.visibility}," +
            "windowVisibility=${node.windowVisibility},alpha=${node.alpha},clip=${node.clipBounds},clipToOutline=${node.clipToOutline}," +
            "clipChildren=${group?.clipChildren},clipPadding=${group?.clipToPadding},matrix=${node.matrix.toShortString()}," +
            "rectStaysRect=$rectStaysRect,parentVisible=$parentVisible:$parentBounds," +
            "global=$global:$visible,scroll=${(node as? ScrollView)?.scrollY},lines=$text)"
    }
    val metrics = root.resources.displayMetrics
    val systemInsets = ViewCompat.getRootWindowInsets(root)?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
    )
    return "case=$caseId theme=${root.javaClass.simpleName} viewport=${root.width}x${root.height} " +
        "density=${metrics.density}/${metrics.densityDpi} hit48dp=${ceil(48.0 * metrics.density).toInt()} " +
        "referenceHits=96/64 hasCompletedLayout=$completedLayout observing=$observing rootInLayout=${root.isInLayout} " +
        "rootPadding=${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom} " +
        "systemInsets=$systemInsets mapToRoot=$canMap rootToScreenInvertible=$rootToScreenInvertible " +
        "ownWindow=${Rect(location[0], location[1], location[0] + window.width, location[1] + window.height)} " +
        "windowVisibleFrame=$frame gateSummary=[${gates.joinToString("|")}] nodes=[$details]"
}

/**
 * Waits for attached, visible views and their ancestors to finish layout before geometry checks.
 *
 * @param scenario The activity hosting the views.
 * @param views The views whose hierarchies must be ready.
 */
internal fun awaitForegroundLayout(scenario: ActivityScenario<StandbyProbeActivity>, vararg views: View) {
    require(views.isNotEmpty())
    val ready = CountDownLatch(1)
    val preDrawCount = java.util.concurrent.atomic.AtomicInteger(0)
    val lastPreDraw = java.util.concurrent.atomic.AtomicReference<Pair<Int, String>?>(null)
    val registeredState = java.util.concurrent.atomic.AtomicReference("unavailable")
    fun snapshot(): String {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        // shortcut: cap tags at 48 characters, ancestors at 12, and output at 8,000; raise limits for larger hierarchies.
        return views.mapIndexed { index, view ->
            val chain = generateSequence(view) { it.parent as? View }
                .joinToString(" -> ", limit = 12, truncated = "...") { ancestor ->
                    val tag = when (val value = ancestor.tag) {
                        null -> "null"
                        is String -> value.take(48).replace('\n', ' ').replace('\r', ' ')
                        else -> "<${value.javaClass.simpleName}>"
                    }
                    "${ancestor.javaClass.name}(tag=$tag,size=${ancestor.width}x${ancestor.height}," +
                        "attached=${ViewCompat.isAttachedToWindow(ancestor)},shown=${ancestor.isShown}," +
                        "laidOut=${ViewCompat.isLaidOut(ancestor)},layoutRequested=${ancestor.isLayoutRequested}," +
                        "visibility=${ancestor.visibility},windowVisibility=${ancestor.windowVisibility}," +
                        "hasWindowFocus=${ancestor.hasWindowFocus()})"
                }
            "view[$index]=$chain"
        }.joinToString(" | ").let { if (it.length > 8_000) "${it.take(8_000)}..." else it }
    }
    val listener = ViewTreeObserver.OnPreDrawListener {
        val draw = preDrawCount.incrementAndGet()
        if (views.all { view ->
                ViewCompat.isAttachedToWindow(view) && view.isShown &&
                    generateSequence(view) { it.parent as? View }.all {
                        ViewCompat.isLaidOut(it) && !it.isLayoutRequested && it.visibility == View.VISIBLE
                    }
            }
        ) {
            ready.countDown()
        }
        lastPreDraw.set(draw to snapshot())
        true
    }
    var registeredObserver: ViewTreeObserver? = null
    var failure: Throwable? = null
    try {
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val observer = decor.viewTreeObserver
            registeredObserver = observer
            observer.addOnPreDrawListener(listener)
            registeredState.set(snapshot())
            decor.invalidate()
        }
        val readyInTime = ready.await(5, TimeUnit.SECONDS)
        if (!readyInTime) {
            val sample = lastPreDraw.get()
            val count = preDrawCount.get()
            val afterTimeout = java.util.concurrent.atomic.AtomicReference("unavailable")
            val captured = CountDownLatch(1)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val capture = Runnable {
                afterTimeout.set(snapshot() + " | " + views.joinToString(" | ") {
                    foregroundSnapshot("awaitForegroundLayout timeout fixtureInsets=fixture", it)
                })
                captured.countDown()
            }
            val posted = handler.post(capture)
            val completed = try {
                posted && captured.await(250, TimeUnit.MILLISECONDS)
            } finally {
                handler.removeCallbacks(capture)
            }
            val after = when {
                !posted -> "post rejected"
                !completed -> "UI callback did not finish in 250ms"
                else -> afterTimeout.get()
            }
            val reason = if (count == 0) "no_pre_draw_observed" else "pre_draw_observed_but_wait_timed_out"
            assertTrue(
                "Timed out waiting for attached view hierarchy layout: reason=$reason preDrawCountAfterWait=$count " +
                    "registered=${registeredState.get()} lastSampleAfterWait=${sample?.first ?: 0}:" +
                    "${sample?.second ?: "unavailable"} afterTimeout=$after",
                readyInTime,
            )
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            scenario.onActivity { activity ->
                val previous = registeredObserver
                if (previous?.isAlive == true) previous.removeOnPreDrawListener(listener)
                val current = activity.window.decorView.viewTreeObserver
                if (current !== previous && current.isAlive) current.removeOnPreDrawListener(listener)
            }
        } catch (cleanup: Throwable) {
            val primary = failure ?: throw cleanup
            if (primary !== cleanup) primary.addSuppressed(cleanup)
        }
    }
}

/**
 * Returns the laid-out root's visible content area in root-local pixels.
 *
 * @param root The activity content root.
 * @return The visible area excluding root padding.
 */
internal fun visibleContentBounds(root: FrameLayout): Rect {
    val visible = Rect()
    assertTrue("Activity content root is not visible", root.getLocalVisibleRect(visible))
    assertTrue(
        "Activity content root has no visible content area",
        visible.intersect(root.paddingLeft, root.paddingTop, root.width - root.paddingRight, root.height - root.paddingBottom),
    )
    return visible
}

/**
 * Positions a fixed-size viewport inside the root's visible content area.
 *
 * @param root The activity content root.
 * @param visible The visible content area in root-local pixels.
 * @param width The requested viewport width in pixels.
 * @param height The requested viewport height in pixels.
 * @return Layout parameters placing the viewport within the visible area.
 */
internal fun visibleContentLayoutParams(root: FrameLayout, visible: Rect, width: Int, height: Int): FrameLayout.LayoutParams {
    require(width in 1..visible.width() && height in 1..visible.height())
    return FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT).apply {
        leftMargin = visible.left - root.paddingLeft
        topMargin = visible.top - root.paddingTop
    }
}

/** Layout and lifecycle regressions, independent of pixel-perfect artwork acceptance. */
@RunWith(AndroidJUnit4::class)
class StandbyForegroundResponsiveTest {
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

    private fun bounds(parent: ViewGroup, tag: String): Rect {
        val child = requireNotNull(parent.findViewWithTag<View>(tag))
        return Rect(0, 0, child.width, child.height).also { parent.offsetDescendantRectToMyCoords(child, it) }
    }

    @Test
    fun responsiveSizes_longLocalizedFailureAndReceiver_doNotCoverStatusOrActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE, Locale("ar"))) {
                val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
                val context = base.createConfigurationContext(configuration)
                for ((widthDp, heightDp) in listOf(360f to 800f, 800f to 360f)) {
                    val views = listOf(
                        LandscapeStandbyForegroundView(context),
                        MatterStandbyForegroundView(context),
                        HardlineStandbyForegroundView(context),
                    )
                    for (view in views) {
                        val caseLabel = "${view.javaClass.simpleName} $locale ${widthDp}x$heightDp"
                        view.layoutDirection = if (locale.language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        val name = "Receiver 接收裝置 ".repeat(12)
                        val failure = "Connection failed 連線失敗 ".repeat(12)
                        val state = StandbyForegroundState(
                            ScreenPhase.ADVERTISING, true, receiverName = name,
                            failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "OK",
                            clockText = "12:34",
                        )
                        val callbacks = StandbyForegroundCallbacks(
                            onToggleBroadcast = {}, onDismissFailure = {}, onOpenSettings = {},
                        )
                        view.render(state, "待機 READY", "ADVERTISING", "Waiting 等待", callbacks)
                        layout(view, px(context, widthDp), px(context, heightDp))
                        val shortFailure = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        assertEquals("OK", shortFailure.text.toString())
                        val shortLayout = requireNotNull(shortFailure.layout)
                        assertTrue(shortLayout.lineCount > 0)
                        assertEquals(0, shortLayout.getEllipsisCount(0))
                        val shortTextSize = shortFailure.textSize
                        assertTrue(shortTextSize > 0f)
                        view.render(state.copy(failureText = failure), "待機 READY", "ADVERTISING", "Waiting 等待", callbacks)
                        layout(view, px(context, widthDp), px(context, heightDp))
                        val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                        val menu = bounds(view, StandbyForegroundTags.MENU)
                        val failedText = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        val failed = Rect(0, 0, failedText.width, failedText.height)
                        assertNull("$caseLabel: fixture must be detached", view.parent)
                        assertTrue("$caseLabel: failure is not visible", requireNotNull(failedText.parent).getChildVisibleRect(failedText, failed, null))
                        assertFalse("$caseLabel: failure has empty visible bounds", failed.isEmpty)
                        val primaryTag = if (view is MatterStandbyForegroundView) StandbyForegroundTags.MATTER_PLATE else StandbyForegroundTags.PHASE_TITLE
                        val primaryView = requireNotNull(view.findViewWithTag<View>(primaryTag))
                        val primary = bounds(view, primaryTag)
                        val decoration = if (primaryView is TextView) {
                            "background=${primaryView.background != null} compound=${primaryView.compoundDrawables.map { it != null }} " +
                                "relative=${primaryView.compoundDrawablesRelative.map { it != null }} shadow=${primaryView.shadowRadius}"
                        } else "nonTextView"
                        val evidence = foregroundSnapshot("responsiveSizes_longLocalizedFailureAndReceiver_doNotCoverStatusOrActions $caseLabel fixtureInsets=0 primaryDecoration=$decoration", view)
                        val primaryPaintedRects = if (primaryView is TextView && primaryView.background == null &&
                            primaryView.compoundDrawables.all { it == null } && primaryView.compoundDrawablesRelative.all { it == null } &&
                            primaryView.shadowRadius == 0f
                        ) {
                            assertNull(evidence, view.parent)
                            var ancestor: View? = primaryView
                            while (ancestor != null && ancestor !== view) {
                                assertFalse(evidence, ancestor.clipToOutline)
                                ancestor = ancestor.parent as? View
                            }
                            assertSame(evidence, view, ancestor)
                            assertFalse(evidence, view.clipToOutline)
                            val textLayout = requireNotNull(primaryView.layout)
                            assertTrue(evidence, textLayout.lineCount > 0)
                            assertEquals(evidence, primaryView.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                            assertTrue(evidence, (0 until textLayout.lineCount).all { line ->
                                textLayout.getEllipsisCount(line) == 0 &&
                                    textLayout.getLineTop(line) - primaryView.scrollY >= 0 &&
                                    textLayout.getLineBottom(line) - primaryView.scrollY <=
                                        primaryView.height - primaryView.extendedPaddingTop - primaryView.extendedPaddingBottom
                            })
                            assertTrue(evidence, (primaryView.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP ||
                                textLayout.height >= primaryView.height - primaryView.extendedPaddingTop - primaryView.extendedPaddingBottom)
                            val localClip = Rect(0, 0, primaryView.width, primaryView.height)
                            primaryView.clipBounds?.let { if (!localClip.intersect(it)) localClip.setEmpty() }
                            val parent = requireNotNull(primaryView.parent)
                            (0 until textLayout.lineCount).mapNotNull { line ->
                                val rect = Rect()
                                RectF(
                                    primaryView.compoundPaddingLeft + textLayout.getLineLeft(line) - primaryView.scrollX,
                                    (primaryView.extendedPaddingTop + textLayout.getLineTop(line) - primaryView.scrollY).toFloat(),
                                    primaryView.compoundPaddingLeft + textLayout.getLineRight(line) - primaryView.scrollX,
                                    (primaryView.extendedPaddingTop + textLayout.getLineBottom(line) - primaryView.scrollY).toFloat(),
                                ).roundOut(rect)
                                rect.takeIf { it.intersect(localClip) && parent.getChildVisibleRect(primaryView, it, null) }
                            }.also { assertTrue(evidence, it.isNotEmpty() && it.size == textLayout.lineCount) }
                        } else listOf(primary)
                        for (painted in primaryPaintedRects) {
                            assertFalse("Primary content intersects dock: painted=$painted $evidence", Rect.intersects(painted, dock))
                            assertFalse("Primary content intersects menu: painted=$painted $evidence", Rect.intersects(painted, menu))
                            assertFalse("Primary content intersects failure: painted=$painted $evidence", Rect.intersects(painted, failed))
                        }
                        val besideDock = failed.left >= dock.right
                        if (!besideDock) assertTrue(evidence, failed.bottom <= dock.top)
                        assertSame(shortFailure, failedText)
                        assertEquals(failure, failedText.text.toString())
                        assertEquals(0, TextViewCompat.getAutoSizeTextType(failedText))
                        assertEquals("$caseLabel: failure text size changed", shortTextSize, failedText.textSize, 0.01f)
                        assertEquals(View.VISIBLE, failedText.visibility)
                        assertTrue(
                            "$caseLabel: no visible failure text width",
                            failedText.width - failedText.compoundPaddingLeft - failedText.compoundPaddingRight > 0,
                        )
                        val failureLayout = requireNotNull(failedText.layout)
                        assertTrue(failureLayout.lineCount > 0)
                        assertNull(failedText.ellipsize)
                        assertEquals(Int.MAX_VALUE, failedText.maxLines)
                        assertEquals(failure.length, failureLayout.getLineEnd(failureLayout.lineCount - 1))
                        for (line in 0 until failureLayout.lineCount) {
                            assertEquals(0, failureLayout.getEllipsisCount(line))
                            assertTrue(failureLayout.getLineLeft(line) >= -0.5f)
                            assertTrue(failureLayout.getLineRight(line) <= failedText.width - failedText.compoundPaddingLeft - failedText.compoundPaddingRight + 0.5f)
                            assertTrue(failureLayout.getLineTop(line) >= 0)
                            assertTrue(failureLayout.getLineBottom(line) <= failedText.height - failedText.extendedPaddingTop - failedText.extendedPaddingBottom)
                        }
                        if (view is LandscapeStandbyForegroundView || view is HardlineStandbyForegroundView) {
                            val receiver = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                            assertEquals(name, receiver.text.toString())
                            assertEquals(1, receiver.maxLines)
                            assertEquals(TextUtils.TruncateAt.END, receiver.ellipsize)
                            assertEquals(0, TextViewCompat.getAutoSizeTextType(receiver))
                            assertEquals(
                                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics),
                                receiver.textSize, 0.01f,
                            )
                            if (view is HardlineStandbyForegroundView) {
                                assertTrue(requireNotNull(receiver.layout).getEllipsisCount(0) > 0)
                                val receiverBounds = bounds(view, StandbyForegroundTags.RECEIVER_NAME)
                                assertFalse(Rect.intersects(receiverBounds, dock))
                                assertFalse(Rect.intersects(receiverBounds, failed))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun shortSafeArea_insetsApplyOnceAndCanReturnToZero() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (view in listOf(LandscapeStandbyForegroundView(context), MatterStandbyForegroundView(context), HardlineStandbyForegroundView(context))) {
                view.setPadding(3, 4, 5, 6)
                view.render(
                    StandbyForegroundState(ScreenPhase.READY, false), "Title", "READY", "Detail",
                    StandbyForegroundCallbacks(onToggleBroadcast = {}),
                )
                fun applyInsets(left: Int, top: Int, right: Int, bottom: Int) {
                    view.applySecurityInsets(Insets.of(left, top, right, bottom))
                }
                val w = px(context, 800f)
                val h = px(context, 360f)
                applyInsets(0, 0, 0, 0)
                layout(view, w, h)
                val before = bounds(view, StandbyForegroundTags.BRAND)
                val brand = requireNotNull(view.findViewWithTag<ViewGroup>(StandbyForegroundTags.BRAND))
                val mark = brand.getChildAt(0)
                val label = (brand.getChildAt(1) as ViewGroup).getChildAt(0) as TextView
                fun measuredSize(child: View) = child.measuredWidth to child.measuredHeight
                fun glyphWidth() = requireNotNull(label.layout).getLineWidth(0)
                val brandSize = measuredSize(brand)
                val markSize = measuredSize(mark)
                val labelSize = measuredSize(label)
                val textSize = label.textSize
                val glyphSize = glyphWidth()
                assertTrue("Brand mark must have measured area", markSize.first > 0 && markSize.second > 0)
                assertTrue(
                    "Brand label and glyphs must have measured area",
                    labelSize.first > 0 && labelSize.second > 0 && glyphSize > 0f,
                )
                fun assertArtworkStable(message: String) {
                    assertEquals(message, w, view.width)
                    assertEquals(message, h, view.height)
                    assertEquals(message, w, view.measuredWidth)
                    assertEquals(message, h, view.measuredHeight)
                    assertEquals(message, brandSize, measuredSize(brand))
                    assertEquals(message, markSize, measuredSize(mark))
                    assertEquals(message, labelSize, measuredSize(label))
                    assertEquals(message, markSize, mark.width to mark.height)
                    assertEquals(message, labelSize, label.width to label.height)
                    assertEquals(message, textSize, label.textSize, 0f)
                    assertEquals(message, glyphSize, glyphWidth(), 0f)
                }
                fun layoutWithDiagnostics(phase: String): String {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    val brandBeforeLayout = measuredSize(brand)
                    val markBeforeLayout = measuredSize(mark)
                    val labelBeforeLayout = measuredSize(label)
                    val layerBeforeLayout = measuredSize(view.motionLayer)
                    val layerLayoutRequested = view.motionLayer.isLayoutRequested
                    view.layout(0, 0, w, h)
                    return "${view.javaClass.simpleName} $phase stage=${view.width}x${view.height} " +
                        "content=${w - view.paddingLeft - view.paddingRight}x${h - view.paddingTop - view.paddingBottom} " +
                        "layerBeforeLayout=$layerBeforeLayout layerLayoutRequested=$layerLayoutRequested " +
                        "brandBaseline=$brandSize brandBeforeLayout=$brandBeforeLayout brandAfterLayout=${measuredSize(brand)} " +
                        "markBeforeLayout=$markBeforeLayout markAfterLayout=${measuredSize(mark)} " +
                        "labelBeforeLayout=$labelBeforeLayout labelAfterLayout=${measuredSize(label)} glyphWidth=${glyphWidth()}"
                }
                repeat(3) { cycle ->
                    applyInsets(10, 20, 14, 30)
                    val insetDiagnostic = layoutWithDiagnostics("inset cycle=$cycle")
                    val inset = bounds(view, StandbyForegroundTags.BRAND)
                    assertEquals(insetDiagnostic, before.left + 10, inset.left)
                    assertEquals(insetDiagnostic, before.top + 20, inset.top)
                    assertEquals(insetDiagnostic, 13, view.paddingLeft)
                    assertEquals(insetDiagnostic, 24, view.paddingTop)
                    assertArtworkStable(insetDiagnostic)
                    applyInsets(0, 0, 0, 0)
                    val zeroDiagnostic = layoutWithDiagnostics("zero cycle=$cycle")
                    assertEquals(zeroDiagnostic, before, bounds(view, StandbyForegroundTags.BRAND))
                    assertEquals(zeroDiagnostic, 3, view.paddingLeft)
                    assertEquals(zeroDiagnostic, 6, view.paddingBottom)
                    assertArtworkStable(zeroDiagnostic)
                }
            }
        }
    }

    @Test
    fun veryShortInsetContent_placesFailureBesideDockWithoutCoveringStatus() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (view in listOf(LandscapeStandbyForegroundView(context), MatterStandbyForegroundView(context), HardlineStandbyForegroundView(context))) {
                view.render(
                    StandbyForegroundState(
                        ScreenPhase.ADVERTISING, true, receiverName = "Receiver",
                        failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Connection failed",
                    ),
                    "Title", "ADVERTISING", "Detail",
                    StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {}),
                )
                for ((width, height, inset) in listOf(Triple(800f, 360f, 80f), Triple(360f, 800f, 300f))) {
                    view.applySecurityInsets(Insets.of(0, px(context, inset), 0, px(context, inset)))
                    layout(view, px(context, width), px(context, height))
                    val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val failure = bounds(view, StandbyForegroundTags.FAILED)
                    val primary = bounds(view, if (view is MatterStandbyForegroundView) StandbyForegroundTags.MATTER_PLATE else StandbyForegroundTags.PHASE_TITLE)
                    val evidence = foregroundSnapshot("veryShortInsetContent_placesFailureBesideDockWithoutCoveringStatus fixtureInsets=0,${px(context, inset)},0,${px(context, inset)}", view)
                    assertTrue(evidence, failure.left >= dock.right)
                    assertFalse(evidence, Rect.intersects(primary, failure))
                    assertFalse(evidence, Rect.intersects(primary, dock))
                    if (view is LandscapeStandbyForegroundView || view is MatterStandbyForegroundView) {
                        val hint = bounds(view, "foreground_hint")
                        assertFalse(evidence, Rect.intersects(primary, hint))
                        assertFalse(evidence, Rect.intersects(dock, hint))
                        assertFalse(evidence, Rect.intersects(failure, hint))
                    }
                    if (view is HardlineStandbyForegroundView) {
                        val receiver = bounds(view, StandbyForegroundTags.RECEIVER_NAME)
                        assertFalse(evidence, Rect.intersects(primary, receiver))
                        assertFalse(evidence, Rect.intersects(dock, receiver))
                        assertFalse(evidence, Rect.intersects(failure, receiver))
                    }
                }
            }
        }
    }

    @Test
    fun hardlineShortSafeArea_longReceiverAndDockRemainVisibleWithoutOverlap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = HardlineStandbyForegroundView(context)
            val name = "Receiver 接收裝置 ".repeat(12)
            view.render(
                StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = name),
                "Title", "ADVERTISING", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}),
            )
            val inset = px(context, 300f)
            view.applySecurityInsets(Insets.of(0, inset, 0, inset))
            assertEquals(inset, view.paddingTop)
            assertEquals(inset, view.paddingBottom)
            layout(view, px(context, 360f), px(context, 800f))
            assertEquals(px(context, 360f), view.width)
            assertEquals(px(context, 800f), view.height)

            val receiver = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
            assertTrue(receiver.text.isNotEmpty())
            assertEquals(name, receiver.text.toString())
            assertEquals(1, receiver.maxLines)
            assertEquals(TextUtils.TruncateAt.END, receiver.ellipsize)
            assertEquals(0, TextViewCompat.getAutoSizeTextType(receiver))
            assertEquals(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics),
                receiver.textSize, 0.01f,
            )
            var ancestor: View = receiver
            var receiverRoot: View = receiver
            while (ancestor !== view.motionLayer) {
                assertTrue("Receiver ancestor ${ancestor.javaClass.simpleName} has no area", ancestor.width > 0 && ancestor.height > 0)
                assertEquals(View.VISIBLE, ancestor.visibility)
                receiverRoot = ancestor
                ancestor = requireNotNull(ancestor.parent as? View)
            }
            assertTrue(ancestor.width > 0 && ancestor.height > 0)
            assertEquals(View.VISIBLE, ancestor.visibility)
            val textLayout = requireNotNull(receiver.layout)
            assertTrue(textLayout.lineCount > 0)
            assertTrue(textLayout.getEllipsisCount(0) > 0)

            var primary = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
            while (primary.parent !== view.motionLayer) {
                primary = requireNotNull(primary.parent as? View)
            }
            assertEquals(View.VISIBLE, primary.visibility)
            val primaryBounds = Rect(0, 0, primary.width, primary.height)
            view.offsetDescendantRectToMyCoords(primary, primaryBounds)
            val receiverBounds = Rect(0, 0, receiverRoot.width, receiverRoot.height)
            view.offsetDescendantRectToMyCoords(receiverRoot, receiverBounds)
            val motionBounds = Rect(0, 0, view.motionLayer.width, view.motionLayer.height)
            view.offsetDescendantRectToMyCoords(view.motionLayer, motionBounds)
            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
            assertEquals(View.VISIBLE, dock.visibility)
            val dockBounds = bounds(view, StandbyForegroundTags.BROADCAST)
            assertFalse(primaryBounds.isEmpty)
            assertFalse(receiverBounds.isEmpty)
            assertFalse(dockBounds.isEmpty)
            assertTrue(motionBounds.contains(receiverBounds))
            assertTrue(receiverBounds.contains(bounds(view, StandbyForegroundTags.RECEIVER_NAME)))
            val evidence = foregroundSnapshot("hardlineShortSafeArea_longReceiverAndDockRemainVisibleWithoutOverlap fixtureInsets=0,$inset,0,$inset receiverContainer=$receiverBounds primaryContainer=$primaryBounds dock=$dockBounds", view)
            assertFalse("Receiver intersects dock: $evidence", Rect.intersects(receiverBounds, dockBounds))
            assertFalse("Receiver intersects primary: $evidence", Rect.intersects(receiverBounds, primaryBounds))
            assertFalse("Primary intersects dock: $evidence", Rect.intersects(primaryBounds, dockBounds))
        }
    }

    /** Checks the approved roles at the tight inset boundary and after returning to normal placement. */
    @Test
    fun approvedInsetLayouts_keepClearanceDuringDriftAndRestoreSourceAnchors() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 240
                fontScale = 1f
                setLocale(Locale.ENGLISH)
            })
            for (view in listOf(LandscapeStandbyForegroundView(context), HardlineStandbyForegroundView(context))) {
                val landscape = view is LandscapeStandbyForegroundView
                val width = px(context, if (landscape) 800f else 360f)
                val height = px(context, if (landscape) 360f else 800f)
                val inset = px(context, if (landscape) 80f else 300f)
                val insets = Insets.of(0, inset, 0, inset)
                val minimum = px(context, 48f)
                var clicks = 0
                val callbacks = StandbyForegroundCallbacks(
                    onToggleBroadcast = { clicks++ }, onOpenSettings = { clicks++ }, onDismissFailure = { clicks++ },
                )
                val name = if (landscape) "Receiver" else "Studio Display Meeting Room North 01"
                val state = StandbyForegroundState(
                    ScreenPhase.ADVERTISING, true, receiverName = name,
                    failureCause = if (landscape) PrePlayFailureCause.PRE_PLAY_TIMEOUT else null,
                    failureText = if (landscape) "Connection failed" else "", isProjecting = false,
                )
                view.render(state, "Title", "ADVERTISING", "Detail", callbacks)
                view.applySecurityInsets(insets)
                layout(view, width, height)

                fun <T : View> child(tag: String): T = requireNotNull(view.findViewWithTag<T>(tag))
                fun slot(child: View): View = generateSequence(child) { it.parent as? View }
                    .takeWhile { it !== view.motionLayer }.last()
                fun box(child: View, rect: RectF = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())): RectF {
                    val inverse = Matrix()
                    assertTrue(Matrix().also(view::transformMatrixToGlobal).invert(inverse))
                    val transform = Matrix().also { it.setConcat(inverse, Matrix().also(child::transformMatrixToGlobal)) }
                    transform.mapRect(rect)
                    return rect
                }

                val title = child<TextView>(StandbyForegroundTags.PHASE_TITLE)
                val primary = slot(title)
                val brand = slot(child<View>(StandbyForegroundTags.BRAND))
                val receiver = child<TextView>(StandbyForegroundTags.RECEIVER_NAME)
                val dock = child<View>(StandbyForegroundTags.BROADCAST)
                val menu = child<View>(StandbyForegroundTags.MENU)
                val fixedDock = box(dock)
                val safe = RectF(0f, inset.toFloat(), width.toFloat(), (height - inset).toFloat())
                assertEquals(width / 2f, fixedDock.centerX(), 0f)
                assertEquals(safe.bottom, fixedDock.bottom, 0f)
                assertEquals(px(context, if (landscape) 96f else 84f).toFloat(), fixedDock.height(), 0f)
                val clearance = if (landscape) px(context, 4f) else 1
                fun assertBoundary() {
                    assertEquals(fixedDock, box(dock))
                    for (target in listOf(dock, menu)) {
                        assertTrue(target.visibility == View.VISIBLE && target.isClickable && target.isFocusable)
                        assertTrue(target.width >= minimum && target.height >= minimum)
                    }
                    assertTrue(safe.contains(box(primary)))
                    assertTrue(safe.contains(box(brand)))
                    assertFalse(RectF.intersects(box(brand), box(menu)))
                    assertFalse(RectF.intersects(box(brand), fixedDock))
                    assertTrue(box(primary).bottom <= fixedDock.top - clearance)
                    assertEquals(name, receiver.contentDescription.toString())
                    if (landscape) {
                        val hint = child<TextView>("foreground_hint")
                        val failed = child<TextView>(StandbyForegroundTags.FAILED)
                        val dismiss = child<View>(StandbyForegroundTags.FAILED_DISMISS)
                        val notice = ((failed.parent as View).parent as View).parent as ScrollView
                        assertTrue(hint.text.contains(name) && hint.visibility == View.VISIBLE)
                        assertTrue(safe.contains(box(hint)))
                        assertTrue(box(hint).left >= box(menu).right + px(context, 12f))
                        assertTrue(box(hint).right <= fixedDock.left - px(context, 12f))
                        assertTrue(box(hint).top >= fixedDock.top && box(hint).bottom <= fixedDock.bottom)
                        assertEquals("Connection failed", failed.text.toString())
                        assertTrue(box(notice).left >= fixedDock.right && box(notice).contains(box(dismiss)))
                        assertTrue(dismiss.isClickable && dismiss.width >= minimum && dismiss.height >= minimum)
                        assertEquals(View.GONE, (receiver.parent as View).visibility)
                    } else {
                        val receiverSlot = box(slot(receiver))
                        assertEquals(safe.top, box(primary).top, 0.01f)
                        assertEquals(width.toFloat(), primary.width.toFloat(), 0f)
                        assertTrue(box(brand).right < fixedDock.left && box(brand).top >= fixedDock.top)
                        assertTrue(receiverSlot.left >= fixedDock.right + px(context, 6f))
                        assertTrue(receiverSlot.top >= fixedDock.top && receiverSlot.bottom <= fixedDock.bottom)
                        assertTrue(safe.contains(receiverSlot))
                        val titleLayout = requireNotNull(title.layout)
                        for (line in 0 until titleLayout.lineCount) {
                            val occupied = box(title, RectF(
                                title.compoundPaddingLeft + titleLayout.getLineLeft(line) - title.scrollX,
                                (title.totalPaddingTop + titleLayout.getLineTop(line) - title.scrollY).toFloat(),
                                title.compoundPaddingLeft + titleLayout.getLineRight(line) - title.scrollX,
                                (title.totalPaddingTop + titleLayout.getLineBottom(line) - title.scrollY).toFloat(),
                            ))
                            assertFalse(RectF.intersects(occupied, box(menu)))
                        }
                        assertEquals(name, receiver.text.toString())
                        assertTrue(requireNotNull(receiver.layout).getEllipsisCount(0) > 0)
                    }
                }
                assertBoundary()
                val footer = if (landscape) child<View>("foreground_hint") else slot(receiver)
                val rowBeforeDrift = box(footer)
                val density = context.resources.displayMetrics.density
                fun drift(timeSeconds: Double) {
                    val sample = StandbyOledDriftMath.burnOffsets(timeSeconds)
                    val x = (sample.x * density).toFloat()
                    val y = (sample.y * density).toFloat()
                    when (view) {
                        is LandscapeStandbyForegroundView -> view.setLegacyAmbientOffset(x, y)
                        is HardlineStandbyForegroundView -> view.setLegacyAmbientOffset(x, y, height * 0.47f + y)
                    }
                    if (view.isLayoutRequested) layout(view, width, height)
                }
                drift(0.0) // Downward drift exceeds Hardline's one-pixel clearance.
                assertBoundary()
                assertTrue(box(footer).top > rowBeforeDrift.top)
                drift(30.0)
                assertBoundary()

                if (!landscape) {
                    view.render(state.copy(receiverName = "Studio"), "Title", "ADVERTISING", "Detail", callbacks)
                    layout(view, width, height)
                    assertEquals("Studio", receiver.text.toString())
                    assertEquals("Studio", receiver.contentDescription.toString())
                    assertEquals(0, requireNotNull(receiver.layout).getEllipsisCount(0))
                    assertTrue(box(footer).left > fixedDock.right)
                    view.render(state, "Title", "ADVERTISING", "Detail", callbacks)
                }
                fun assertSourceAnchor(stageHeight: Int) {
                    val fraction = if (landscape) 0.43f else 0.47f
                    assertEquals((stageHeight * fraction).toInt() - primary.height / 2 + view.motionLayer.translationY,
                        box(primary).top, 0.01f)
                    assertTrue(view.motionLayer.translationY < 0f)
                    assertEquals(0f, primary.translationY, 0f)
                    assertEquals(0f, brand.translationY, 0f)
                    assertEquals(0f, footer.translationY, 0f)
                    assertTrue(footer.width.toFloat() > rowBeforeDrift.width())
                }
                val taller = height + px(context, 320f)
                layout(view, width, taller)
                assertSourceAnchor(taller)
                layout(view, width, height)
                view.applySecurityInsets(Insets.NONE)
                layout(view, width, height)
                assertSourceAnchor(height)
                if (view is LandscapeStandbyForegroundView) {
                    // Give the normal title one pixel of clearance before applying a real burn sample.
                    view.setLegacyAmbientOffset(0f, 0f)
                    view.render(state.copy(failureCause = null, failureText = ""), "Title", "ADVERTISING", "Detail", callbacks)
                    layout(view, width, height)
                    val closeInset = height - dock.height - box(title).bottom.toInt() - 1
                    view.applySecurityInsets(Insets.of(0, inset, 0, closeInset))
                    layout(view, width, height)
                    val closeDock = box(dock)
                    assertEquals(1f, closeDock.top - box(title).bottom, 0f)
                    assertEquals(((height * 0.43f).toInt() - primary.height / 2).toFloat(), box(primary).top, 0f)
                    val sample = StandbyOledDriftMath.burnOffsets(0.0)
                    view.setLegacyAmbientOffset((sample.x * density).toFloat(), (sample.y * density).toFloat())
                    assertEquals(closeDock, box(dock))
                    assertTrue(box(title).bottom <= closeDock.top - clearance)
                    assertTrue(box(footer).right < closeDock.left)
                    val fittedTop = primary.top
                    val later = StandbyOledDriftMath.burnOffsets(30.0)
                    view.setLegacyAmbientOffset((later.x * density).toFloat(), (later.y * density).toFloat())
                    assertFalse(view.isLayoutRequested)
                    assertEquals(fittedTop, primary.top)
                    assertTrue(box(title).bottom <= closeDock.top - clearance)
                }
                assertEquals(0, clicks)
            }
        }
    }

    /** A mounted Host must settle the Hardline band and scene inputs on resize, not on a retry. */
    @Test
    fun hardlineInsetResize_publishesBandAndReceiverMasksWithCurrentHandle() {
        for (tallBottom in listOf(450, 930)) ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var host: BigStandbyHost
            lateinit var foreground: HardlineStandbyForegroundView
            lateinit var scene: HardlineSceneView
            lateinit var menu: View
            val tallControls = Rect()
            var observer: ViewTreeObserver? = null
            var listener: ViewTreeObserver.OnPreDrawListener? = null
            var firstFrame: Result<Unit>? = null

            /** Checks the complete visible placement and the scene inputs at the same pre-draw. */
            fun assertResizeFrame() {
                val shown = Rect()
                assertTrue(host.getGlobalVisibleRect(shown))
                assertEquals(540, shown.width())
                assertEquals(1200, shown.height())
                assertEquals(450, foreground.paddingTop)
                assertEquals(450, foreground.paddingBottom)
                val controls = menu.parent as View
                val shortControls = Rect(controls.left, controls.top, controls.right, controls.bottom)
                val resize = "tallBottom=$tallBottom controls=$tallControls->$shortControls"
                assertEquals(resize, tallBottom == 930, shortControls == tallControls)
                val menuBottom = controls.top + menu.y + menu.height
                val menuCenter = menuBottom - menu.height / 2f
                val driftY = StandbyOledDriftMath.burnOffsets(0.0).y.toFloat() * 1.5f
                // The stopped Host still requests its stage-relative center at the frozen burn phase.
                assertEquals(resize, host.height * 0.47f + driftY, menuCenter, 0.01f)
                val geometry = requireNotNull(foreground.foregroundRectsInViewPixels())
                val title = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                assertEquals(0, geometry.centerBand.left)
                assertEquals(host.width, geometry.centerBand.right)
                assertEquals(foreground.paddingTop, geometry.centerBand.top)
                assertEquals((title.parent as View).height, geometry.centerBand.height())
                assertEquals(1, geometry.dock.top - geometry.centerBand.bottom)
                assertTrue(!geometry.header.isEmpty && geometry.header.top >= menuBottom)
                assertTrue(geometry.header.right < geometry.dock.left && geometry.header.bottom <= geometry.dock.bottom)
                assertTrue(geometry.receiver.left > geometry.dock.right)
                val brand = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.BRAND))
                val screenToRoot = Matrix()
                assertTrue(Matrix().also(brand.rootView::transformMatrixToGlobal).invert(screenToRoot))
                fun inRoot(view: View, box: RectF): RectF = box.also {
                    val transform = Matrix().also { it.setConcat(screenToRoot, Matrix().also(view::transformMatrixToGlobal)) }
                    assertTrue(transform.mapRect(box))
                }
                val fullBrand = inRoot(brand, RectF(0f, 0f, brand.width.toFloat(), brand.height.toFloat()))
                val lowerLane = inRoot(foreground, RectF(foreground.paddingLeft.toFloat(), geometry.dock.top.toFloat(),
                    geometry.dock.left.toFloat(), geometry.dock.bottom.toFloat()))
                val expected = Rect().also(fullBrand::roundOut)
                val actual = Rect()
                val visible = brand.getGlobalVisibleRect(actual)
                val evidence = "$resize menuCenter=$menuCenter brand=$fullBrand rounded=$expected visible=$actual host=$shown lowerLane=$lowerLane"
                assertTrue(evidence, visible && !fullBrand.isEmpty && RectF(shown).contains(fullBrand) && lowerLane.contains(fullBrand))
                assertEquals(evidence, expected, actual)
                val sceneClass = HardlineSceneView::class.java
                assertEquals(geometry.centerBand.top.toFloat(), sceneClass.getDeclaredField("bandUpperPx")
                    .apply { isAccessible = true }.get(scene) as Float?)
                assertEquals(geometry.centerBand.bottom.toFloat(), sceneClass.getDeclaredField("bandLowerPx")
                    .apply { isAccessible = true }.get(scene) as Float?)
                assertEquals(RectF(geometry.receiver), sceneClass.getDeclaredField("receiverBoundsPx")
                    .apply { isAccessible = true }.get(scene) as RectF)
                assertEquals(RectF(geometry.dock), sceneClass.getDeclaredField("dockBoundsPx")
                    .apply { isAccessible = true }.get(scene) as RectF)
                Log.i("StandbyGeometry", "Hardline resize $evidence band=${geometry.centerBand} receiver=${geometry.receiver} dock=${geometry.dock} sceneMasksMatch=true")
            }

            try {
                awaitActivityAction(scenario) { activity ->
                    root = activity.contentRoot
                    val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        densityDpi = 240
                        fontScale = 1f
                        setLocale(Locale.ENGLISH)
                    })
                    host = BigStandbyHost(context, StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}))
                    host.applySafeInsets(Insets.of(0, 450, 0, tallBottom))
                    host.render(StandbyThemeId.HARDLINE, StandbyForegroundState(
                        ScreenPhase.ADVERTISING, true, receiverName = "Studio Display Meeting Room North 01", isProjecting = false,
                    ), "Title", "ADVERTISING", "Detail")
                    val children = (0 until host.childCount).map(host::getChildAt)
                    foreground = children.filterIsInstance<HardlineStandbyForegroundView>().single()
                    scene = children.filterIsInstance<HardlineSceneView>().single()
                    menu = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.MENU))
                    // The tall intermediate layout is attached but never drawn as a visible viewport.
                    host.visibility = View.INVISIBLE
                    root.addView(host, FrameLayout.LayoutParams(540, 1680))
                }
                awaitForegroundLayout(scenario, root)
                awaitActivityAction(scenario) {
                    assertTrue(host.isAttachedToWindow && !host.isShown)
                    assertEquals(1680, host.height)
                    val controls = menu.parent as View
                    tallControls.set(controls.left, controls.top, controls.right, controls.bottom)
                    assertEquals(1680 - 450 - tallBottom, tallControls.height())
                    if (tallBottom == 930) {
                        assertEquals("The tall handle is clamped to the old safe bottom",
                            tallControls.bottom.toFloat(), controls.top + menu.y + menu.height, 0f)
                    }
                    val probe = ViewTreeObserver.OnPreDrawListener {
                        if (firstFrame == null && host.height == 1200 && host.isShown &&
                            !host.isLayoutRequested && !foreground.isLayoutRequested && !controls.isLayoutRequested && !scene.isLayoutRequested
                        ) firstFrame = runCatching { assertResizeFrame() }
                        true
                    }
                    listener = probe
                    observer = host.viewTreeObserver.also { it.addOnPreDrawListener(probe) }
                    host.applySafeInsets(Insets.of(0, 450, 0, 450))
                    host.layoutParams = visibleContentLayoutParams(root, visibleContentBounds(root), 540, 1200)
                    host.visibility = View.VISIBLE
                }
                awaitForegroundLayout(scenario, host, foreground, scene)
                awaitActivityAction(scenario) { requireNotNull(firstFrame) { "Missing first settled resize: tallBottom=$tallBottom" }.getOrThrow() }
            } finally {
                awaitActivityAction(scenario) { activity ->
                    listener?.let { probe -> observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe) }
                    activity.contentRoot.removeAllViews()
                }
            }
        }
    }

    /** Observes real EN/zh-TW steady, outgoing, and settled hint placement without advancing time manually. */
    @Test
    fun landscapeInsetHintFade_keepsPlainAnchorAndSettlesInvitationBesideDock() {
        for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
            ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
                lateinit var root: FrameLayout
                lateinit var view: LandscapeStandbyForegroundView
                val settled = CountDownLatch(1)
                var observer: ViewTreeObserver? = null
                var listener: ViewTreeObserver.OnPreDrawListener? = null
                var outgoing: Triple<Rect, Rect, Rect>? = null
                var nextCopy: Pair<String, CountDownLatch>? = null
                val state = StandbyForegroundState(ScreenPhase.WIFI_PAUSED, true, receiverName = "Receiver",
                    copyMotionAllowed = true, isProjecting = false)
                val advertising = state.copy(phase = ScreenPhase.ADVERTISING,
                    failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Connection failed")
                val callbacks = StandbyForegroundCallbacks(
                    onToggleBroadcast = {}, onOpenSettings = {}, onDismissFailure = {}, onStopBroadcastWhilePaused = {},
                )
                try {
                    awaitActivityAction(scenario) { root = it.contentRoot }
                    awaitForegroundLayout(scenario, root)
                    awaitActivityAction(scenario) { activity ->
                        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                            densityDpi = 160
                            fontScale = 1f
                            setLocale(locale)
                        })
                        view = LandscapeStandbyForegroundView(context)
                        view.applySecurityInsets(Insets.of(0, 80, 0, 80))
                        view.render(
                            state,
                            context.getString(R.string.standby_foreground_phase_wifi_paused_title),
                            context.getString(R.string.standby_foreground_phase_wifi_paused_eyebrow),
                            "",
                            callbacks,
                        )
                        root.addView(view, visibleContentLayoutParams(root, visibleContentBounds(root), 800, 360))
                    }
                    awaitForegroundLayout(scenario, view)
                    lateinit var steadyHintLaneBounds: Rect
                    awaitActivityAction(scenario) {
                        val context = view.context
                        val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                        val plain = context.getString(R.string.standby_foreground_landscape_wifi_paused_hint)
                        val invitation = context.getString(R.string.standby_foreground_connection_hint_format, "Receiver")
                        assertEquals(plain, hint.text.toString())
                        val titleView = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        val dockView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        Log.i("StandbyGeometry", foregroundSnapshot("landscapeInsetSteadyPlain_$locale", view, titleView, hint, dockView))
                        assertTrue("Paused dock must be shown ($locale)", dockView.isShown && dockView.width >= 48 && dockView.height >= 48)
                        val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                        val menu = bounds(view, StandbyForegroundTags.MENU)
                        steadyHintLaneBounds = bounds(view, "foreground_hint")
                        assertTrue("Steady colliding plain hint must move to dock-left lane ($locale)",
                            steadyHintLaneBounds.left >= menu.right + 12 && steadyHintLaneBounds.right <= dock.left - 12 &&
                                steadyHintLaneBounds.top >= dock.top && steadyHintLaneBounds.bottom <= dock.bottom)
                        assertHintIntact(hint)
                        assertTrue(bounds(view, StandbyForegroundTags.PHASE_TITLE).bottom <= dock.top - 4)

                        val probe = ViewTreeObserver.OnPreDrawListener {
                            if (!view.isLayoutRequested) {
                                val copy = hint.text.toString()
                                if (outgoing == null && copy == plain && hint.alpha > 0f && hint.alpha < 1f) {
                                    val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                                    val dockView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                                    outgoing = Triple(
                                        bounds(view, StandbyForegroundTags.PHASE_TITLE),
                                        bounds(view, "foreground_hint"),
                                        bounds(view, StandbyForegroundTags.MENU),
                                    )
                                    Log.i("StandbyGeometry", foregroundSnapshot("landscapeInsetOutgoingPlain_$locale", view, title, hint, dockView))
                                }
                                if (copy == invitation && hint.alpha == 1f) settled.countDown()
                                nextCopy?.let { (expected, ready) ->
                                    if (copy == expected && hint.alpha == 1f) ready.countDown()
                                }
                            }
                            true
                        }
                        listener = probe
                        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(probe) }
                        view.render(
                            advertising,
                            context.getString(R.string.standby_foreground_phase_failed_title),
                            context.getString(R.string.standby_foreground_phase_failed_eyebrow),
                            "",
                            callbacks,
                        )
                        assertEquals(plain, hint.text.toString())
                    }
                    assertTrue("The attached invitation did not finish its copy fade ($locale)", settled.await(5, TimeUnit.SECONDS))
                    awaitActivityAction(scenario) {
                        val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                        val (outgoingTitle, outgoingHint, outgoingMenu) = requireNotNull(outgoing) { "No laid-out outgoing fade frame ($locale)" }
                        assertEquals("Outgoing plain hint must stay in dock-left lane ($locale)", steadyHintLaneBounds, outgoingHint)
                        assertTrue(outgoingTitle.bottom <= dock.top - 4)
                        assertTrue(outgoingHint.left >= outgoingMenu.right + 12 && outgoingHint.right <= dock.left - 12 &&
                            outgoingHint.top >= dock.top && outgoingHint.bottom <= dock.bottom)
                        assertFalse(Rect.intersects(outgoingTitle, outgoingHint))
                        assertTrue(bounds(view, StandbyForegroundTags.PHASE_TITLE).bottom <= dock.top - 4)
                        val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                        val hintBounds = bounds(view, "foreground_hint")
                        assertTrue(hintBounds.right <= dock.left - 12 && hintBounds.top >= dock.top && hintBounds.bottom <= dock.bottom)
                        assertHintIntact(hint)
                        val failed = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        assertEquals("Connection failed", failed.text.toString())
                        assertTrue(bounds(view, StandbyForegroundTags.FAILED).left >= dock.right)
                        val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                        val visible = Rect()
                        assertTrue(dismiss.isShown && dismiss.isClickable && dismiss.width >= 48 && dismiss.height >= 48)
                        assertTrue(dismiss.getGlobalVisibleRect(visible))
                        assertEquals(dismiss.height, visible.height())
                        assertEquals(dismiss.width, visible.width())
                    }

                    if (locale == Locale.ENGLISH) {
                        val wideName = "W".repeat(32)
                        val wideCommit = CountDownLatch(1)
                        var laneWidth = 0
                        awaitActivityAction(scenario) {
                            val context = view.context
                            val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                            laneWidth = hint.width
                            nextCopy = context.getString(R.string.standby_foreground_connection_hint_format, wideName) to wideCommit
                            view.render(
                                advertising.copy(receiverName = wideName),
                                context.getString(R.string.standby_foreground_phase_failed_title),
                                context.getString(R.string.standby_foreground_phase_failed_eyebrow),
                                "",
                                callbacks,
                            )
                            assertEquals(context.getString(R.string.standby_foreground_connection_hint_format, "Receiver"), hint.text.toString())
                        }
                        assertTrue("The wider structured invitation did not commit", wideCommit.await(5, TimeUnit.SECONDS))
                        val narrowCommit = CountDownLatch(1)
                        awaitActivityAction(scenario) {
                            val context = view.context
                            val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                            val styled = hint.text as Spanned
                            val groups = styled.getSpans(0, styled.length, ReplacementSpan::class.java)
                            val receiver = groups.single { it.javaClass.simpleName == "ReceiverGroupSpan" }
                            val start = styled.getSpanStart(receiver)
                            val end = styled.getSpanEnd(receiver)
                            assertEquals(wideName, styled.subSequence(start, end).toString())
                            assertTrue(receiver.getSize(hint.paint, styled, start, end, null) > laneWidth)
                            assertTrue("The wider group must be measured against the original footer width", hint.width > laneWidth)
                            nextCopy = context.getString(R.string.standby_foreground_connection_hint_format, "Receiver") to narrowCommit
                            view.render(
                                advertising,
                                context.getString(R.string.standby_foreground_phase_failed_title),
                                context.getString(R.string.standby_foreground_phase_failed_eyebrow),
                                "",
                                callbacks,
                            )
                        }
                        assertTrue("The fitting structured invitation did not commit", narrowCommit.await(5, TimeUnit.SECONDS))
                        awaitActivityAction(scenario) {
                            val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                            val hint = bounds(view, "foreground_hint")
                            assertEquals(laneWidth, hint.width())
                            assertTrue(hint.right <= dock.left - 12 && hint.top >= dock.top && hint.bottom <= dock.bottom)
                            assertTrue(bounds(view, StandbyForegroundTags.PHASE_TITLE).bottom <= dock.top - 4)
                        }
                    }
                } finally {
                    awaitActivityAction(scenario) { activity ->
                        val probe = listener
                        if (probe != null) observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
                        activity.contentRoot.removeAllViews()
                    }
                }
            }
        }
    }

    /** Keeps normal hint anchor when non-colliding or when plain copy overflows dock-left lane, while lifting center. */
    @Test
    fun landscapeInsetPlainHint_preservesNormalAnchorWhenNonCollidingOrUnfit() {
        fun viewBounds(parent: ViewGroup, child: View): Rect =
            Rect(0, 0, child.width, child.height).also { parent.offsetDescendantRectToMyCoords(child, it) }

        fun lineBoxes(parent: ViewGroup, tv: TextView, dy: Int = 0): List<Rect> {
            val layout = requireNotNull(tv.layout)
            val box = viewBounds(parent, tv)
            val x = box.left + tv.compoundPaddingLeft
            val y = box.top + tv.totalPaddingTop + dy
            return (0 until layout.lineCount).mapNotNull { line ->
                val left = layout.getLineLeft(line)
                val right = layout.getLineRight(line)
                if (right <= left) null else Rect(
                    floor((x + left).toDouble()).toInt(),
                    y + layout.getLineTop(line),
                    ceil((x + right).toDouble()).toInt(),
                    y + layout.getLineBottom(line),
                )
            }
        }

        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var nonColliding: LandscapeStandbyForegroundView
            lateinit var unfit: LandscapeStandbyForegroundView
            val state = StandbyForegroundState(ScreenPhase.WIFI_PAUSED, true, receiverName = "Receiver",
                copyMotionAllowed = false, isProjecting = false)
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}, onStopBroadcastWhilePaused = {})
            try {
                awaitActivityAction(scenario) { root = it.contentRoot }
                awaitForegroundLayout(scenario, root)
                awaitActivityAction(scenario) { activity ->
                    val nonCollidingContext = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        densityDpi = 160
                        fontScale = 1f
                        setLocale(Locale.ENGLISH)
                    })
                    nonColliding = LandscapeStandbyForegroundView(nonCollidingContext)
                    nonColliding.applySecurityInsets(Insets.of(0, 80, 0, 12))
                    nonColliding.render(
                        state,
                        nonCollidingContext.getString(R.string.standby_foreground_phase_wifi_paused_title),
                        nonCollidingContext.getString(R.string.standby_foreground_phase_wifi_paused_eyebrow),
                        "",
                        callbacks,
                    )
                    root.addView(nonColliding, visibleContentLayoutParams(root, visibleContentBounds(root), 800, 360))
                }
                awaitForegroundLayout(scenario, nonColliding)
                awaitActivityAction(scenario) { activity ->
                    val dockView = requireNotNull(nonColliding.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    val titleView = requireNotNull(nonColliding.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    val hintView = requireNotNull(nonColliding.findViewWithTag<TextView>("foreground_hint"))
                    assertTrue(dockView.isShown && dockView.width >= 48 && dockView.height >= 48)
                    val hint = bounds(nonColliding, "foreground_hint")
                    val dock = bounds(nonColliding, StandbyForegroundTags.BROADCAST)
                    val titleLines = lineBoxes(nonColliding, titleView)
                    val hintLines = lineBoxes(nonColliding, hintView)
                    assertTrue(titleLines.isNotEmpty() && hintLines.isNotEmpty())
                    assertTrue(titleLines.none { Rect.intersects(it, dock) })
                    assertTrue(hintLines.none { hBox -> Rect.intersects(hBox, dock) || titleLines.any { Rect.intersects(hBox, it) } })
                    val centerView = titleView.parent as View
                    assertEquals((360 * 0.43f).toInt() - centerView.height / 2, viewBounds(nonColliding, centerView).top)
                    assertEquals(360 - 12 - 24 - 100, hint.bottom)
                    assertTrue(hint.right > dock.left)
                    assertHintIntact(hintView)
                    activity.contentRoot.removeAllViews()

                    val unfitContext = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        densityDpi = 160
                        fontScale = 1f
                        setLocale(Locale.ENGLISH)
                    })
                    unfit = LandscapeStandbyForegroundView(unfitContext)
                    unfit.applySecurityInsets(Insets.of(160, 80, 0, 90))
                    unfit.render(
                        state,
                        unfitContext.getString(R.string.standby_foreground_phase_wifi_paused_title),
                        unfitContext.getString(R.string.standby_foreground_phase_wifi_paused_eyebrow),
                        "",
                        callbacks,
                    )
                    root.addView(unfit, visibleContentLayoutParams(root, visibleContentBounds(root), 600, 380))
                }
                awaitForegroundLayout(scenario, unfit)
                awaitActivityAction(scenario) {
                    val dockView = requireNotNull(unfit.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    val menuView = requireNotNull(unfit.findViewWithTag<View>(StandbyForegroundTags.MENU))
                    val titleView = requireNotNull(unfit.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    val hintView = requireNotNull(unfit.findViewWithTag<TextView>("foreground_hint"))
                    Log.i("StandbyGeometry", foregroundSnapshot("landscapeInsetPlainUnfit", unfit, titleView, hintView, dockView))
                    assertTrue("Paused dock must be shown when testing the unfit lane",
                        dockView.isShown && dockView.isClickable && dockView.width >= 48 && dockView.height >= 48)
                    assertTrue("Menu target must be shown when testing the unfit lane",
                        menuView.isShown && menuView.isClickable && menuView.width >= 48 && menuView.height >= 48)
                    val dockVisible = Rect()
                    val menuVisible = Rect()
                    assertTrue(dockView.getGlobalVisibleRect(dockVisible))
                    assertEquals(dockView.width, dockVisible.width())
                    assertEquals(dockView.height, dockVisible.height())
                    assertTrue(menuView.getGlobalVisibleRect(menuVisible))
                    assertEquals(menuView.width, menuVisible.width())
                    assertEquals(menuView.height, menuVisible.height())

                    val dock = bounds(unfit, StandbyForegroundTags.BROADCAST)
                    val menu = bounds(unfit, StandbyForegroundTags.MENU)
                    val title = bounds(unfit, StandbyForegroundTags.PHASE_TITLE)
                    val hint = bounds(unfit, "foreground_hint")
                    val centerView = titleView.parent as View
                    val titleNormalDy = ((380 * 0.43f).toInt() - centerView.height / 2) - viewBounds(unfit, centerView).top
                    assertTrue(
                        "Normal-anchor title must collide with dock to trigger inset layout",
                        lineBoxes(unfit, titleView, titleNormalDy).any { Rect.intersects(it, dock) },
                    )
                    val liftedTitleLines = lineBoxes(unfit, titleView)
                    val hintNormalLines = lineBoxes(unfit, hintView)
                    assertTrue(
                        "Normal-anchor plain hint lines must collide with lifted title or dock",
                        hintNormalLines.any { hBox -> Rect.intersects(hBox, dock) || liftedTitleLines.any { Rect.intersects(hBox, it) } },
                    )

                    val gap = (12f * unfit.resources.displayMetrics.density).roundToInt()
                    val lane = Rect(maxOf(unfit.paddingLeft, menu.right) + gap, dock.top, dock.left - gap, dock.bottom)
                    assertTrue("Candidate lane must remain positive ($lane)", lane.width() > 0 && lane.height() > 0)
                    val laneTextWidth = (lane.width() - hintView.compoundPaddingLeft - hintView.compoundPaddingRight).coerceAtLeast(0)
                    val candidateLayout = StaticLayout.Builder.obtain(
                        hintView.text, 0, hintView.text.length, hintView.paint, laneTextWidth,
                    )
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(hintView.includeFontPadding)
                        .setUseLineSpacingFromFallbacks(hintView.isFallbackLineSpacing)
                        .setLineSpacing(hintView.lineSpacingExtra, hintView.lineSpacingMultiplier)
                        .setBreakStrategy(hintView.breakStrategy)
                        .setHyphenationFrequency(hintView.hyphenationFrequency)
                        .build()
                    val overflowsHeight = candidateLayout.height + hintView.extendedPaddingTop + hintView.extendedPaddingBottom > lane.height()
                    val overflowsWidth = (0 until candidateLayout.lineCount).any {
                        candidateLayout.getLineRight(it) > laneTextWidth + 0.5f || candidateLayout.getLineLeft(it) < -0.5f
                    }
                    assertTrue("Candidate lane $lane must overflow in height or width", overflowsHeight || overflowsWidth)

                    assertTrue("Center must stay lifted independently when plain hint does not fit", title.bottom <= dock.top - 4)
                    assertEquals(380 - 90 - 24 - 100, hint.bottom)
                    assertTrue(hint.right > dock.left)
                    assertEquals(unfit.context.getString(R.string.standby_foreground_landscape_wifi_paused_hint), hintView.text.toString())
                    assertHintIntact(hintView)
                }
            } finally {
                awaitActivityAction(scenario) { activity -> activity.contentRoot.removeAllViews() }
            }
        }
    }

    private fun assertHintIntact(hint: TextView) {
        val layout = requireNotNull(hint.layout)
        val textWidth = hint.width - hint.compoundPaddingLeft - hint.compoundPaddingRight
        assertTrue(layout.height + hint.extendedPaddingTop + hint.extendedPaddingBottom <= hint.height)
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
            assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= textWidth + 0.5f)
        }
    }

    @Test
    fun geometry_usesOnlyVisibleLaidOutNativeViewsAndExplicitStageUnits() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var view: HardlineStandbyForegroundView
            lateinit var dock: View
            var widthPx = 0
            var heightPx = 0
            try {
                scenario.onActivity { activity -> root = activity.contentRoot }
                awaitForegroundLayout(scenario, root)
                scenario.onActivity { activity ->
                    val visible = visibleContentBounds(root)
                    val maxWidth = minOf(visible.width(), px(activity, 800f))
                    assertTrue("Visible content is too narrow", maxWidth > 1)
                    val oddWidth = if (maxWidth % 2 == 0) maxWidth - 1 else maxWidth
                    // Keep the stage width-limited and coprime so interior edges exercise rounding.
                    widthPx = if (oddWidth % 5 == 0) oddWidth - 2 else oddWidth
                    assertTrue("Visible content is too narrow for stage geometry", widthPx > 1)
                    heightPx = minOf(visible.height(), px(activity, 360f), (widthPx.toLong() * 5 / 8).toInt())
                    assertTrue("Visible content is too short for stage geometry", heightPx > 0)
                    view = HardlineStandbyForegroundView(activity)
                    Log.i("StandbyGeometry", foregroundSnapshot("geometry_usesOnlyVisibleLaidOutNativeViewsAndExplicitStageUnits detached fixtureInsets=0", view))
                    assertNull(view.foregroundRectsInViewPixels())
                    assertNull(view.foregroundRectsInStageSpace())
                    view.render(
                        StandbyForegroundState(
                            ScreenPhase.ADVERTISING, true, receiverName = "Receiver", copyMotionAllowed = false,
                        ),
                        "Title", "ADVERTISING", "Detail", StandbyForegroundCallbacks(onToggleBroadcast = {}),
                    )
                    layout(view, widthPx, heightPx)
                    assertNull(view.foregroundRectsInViewPixels())
                    assertNull(view.foregroundRectsInStageSpace())
                    root.addView(view, visibleContentLayoutParams(root, visible, widthPx, heightPx))
                    assertNull(view.foregroundRectsInViewPixels())
                    assertNull(view.foregroundRectsInStageSpace())
                    dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    // Attachment clears the dock's pre-attachment layout state.
                    dock.requestLayout()
                }
                awaitForegroundLayout(scenario, view, dock)
                scenario.onActivity {
                    val w = view.width
                    val h = view.height
                    assertEquals(widthPx, w)
                    assertEquals(heightPx, h)
                    assertTrue(visibleContentBounds(root).contains(view.left, view.top, view.right, view.bottom))
                    val evidence = foregroundSnapshot("geometry_usesOnlyVisibleLaidOutNativeViewsAndExplicitStageUnits fixtureInsets=0", view)
                    Log.i("StandbyGeometry", evidence)
                    val pixels = requireNotNull(view.foregroundRectsInViewPixels()) { evidence }
                    val dockParent = requireNotNull(dock.parent as? View)
                    val visibleDock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val dockParentBounds = Rect(0, 0, dockParent.width, dockParent.height)
                    view.offsetDescendantRectToMyCoords(dockParent, dockParentBounds)
                    assertTrue(visibleDock.intersect(dockParentBounds))
                    assertTrue(visibleDock.intersect(0, 0, w, h))
                    assertEquals(visibleDock, pixels.dock)
                    assertTrue(!pixels.dock.isEmpty)
                    assertTrue(pixels.header.contains(bounds(view, StandbyForegroundTags.BRAND)))
                    assertTrue(pixels.centerBand.contains(bounds(view, StandbyForegroundTags.PHASE_TITLE)))
                    val stage = requireNotNull(view.foregroundRectsInStageSpace())
                    val scale = maxOf(w / 1600.0, h / 1000.0)
                    val offsetX = (w - 1600.0 * scale) / 2.0
                    val offsetY = (h - 1000.0 * scale) / 2.0
                    val left = (pixels.dock.left - offsetX) / scale
                    val top = (pixels.dock.top - offsetY) / scale
                    val right = (pixels.dock.right - offsetX) / scale
                    val bottom = (pixels.dock.bottom - offsetY) / scale
                    assertTrue("Expected a fractional leading edge", left != floor(left) || top != floor(top))
                    assertTrue("Expected a fractional trailing edge", right != ceil(right) || bottom != ceil(bottom))
                    assertEquals(floor(left).toInt(), stage.dock.left)
                    assertEquals(floor(top).toInt(), stage.dock.top)
                    assertEquals(ceil(right).toInt(), stage.dock.right)
                    assertEquals(ceil(bottom).toInt(), stage.dock.bottom)
                    view.visibility = View.INVISIBLE
                    assertNull(view.foregroundRectsInViewPixels())
                    assertNull(view.foregroundRectsInStageSpace())
                    view.visibility = View.VISIBLE
                    view.render(
                        StandbyForegroundState(ScreenPhase.READY, false, copyMotionAllowed = false),
                        "Title", "READY", "Detail", StandbyForegroundCallbacks(),
                    )
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity { activity ->
                    val withoutOptionalViews = requireNotNull(view.foregroundRectsInViewPixels())
                    assertTrue(withoutOptionalViews.receiver.isEmpty)
                    assertTrue(withoutOptionalViews.dock.isEmpty)
                    assertTrue(requireNotNull(view.foregroundRectsInStageSpace()).dock.isEmpty)
                    activity.contentRoot.removeView(view)
                    assertNull(view.foregroundRectsInViewPixels())
                    assertNull(view.foregroundRectsInStageSpace())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun geometry_hiddenAncestorNeverReturnsOldBounds() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var parent: FrameLayout
            lateinit var landscape: LandscapeStandbyForegroundView
            lateinit var hardline: HardlineStandbyForegroundView
            var widthPx = 0
            var heightPx = 0
            try {
                scenario.onActivity { activity -> root = activity.contentRoot }
                awaitForegroundLayout(scenario, root)
                scenario.onActivity { activity ->
                    val visible = visibleContentBounds(root)
                    widthPx = minOf(visible.width(), px(activity, 800f))
                    heightPx = minOf(visible.height(), px(activity, 360f))
                    parent = FrameLayout(activity)
                    landscape = LandscapeStandbyForegroundView(activity)
                    hardline = HardlineStandbyForegroundView(activity)
                    parent.addView(landscape, ViewGroup.LayoutParams(-1, -1))
                    parent.addView(hardline, ViewGroup.LayoutParams(-1, -1))
                    Log.i("StandbyGeometry", foregroundSnapshot("geometry_hiddenAncestorNeverReturnsOldBounds detached fixtureInsets=0", landscape))
                    Log.i("StandbyGeometry", foregroundSnapshot("geometry_hiddenAncestorNeverReturnsOldBounds detached fixtureInsets=0", hardline))
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                    root.addView(parent, visibleContentLayoutParams(root, visible, widthPx, heightPx))
                    for (view in listOf(landscape, hardline)) {
                        view.render(
                            StandbyForegroundState(ScreenPhase.READY, false, copyMotionAllowed = false),
                            "Title", "READY", "Detail", StandbyForegroundCallbacks(),
                        )
                    }
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                }
                awaitForegroundLayout(scenario, landscape, hardline)
                scenario.onActivity {
                    assertEquals(widthPx, parent.width)
                    assertEquals(heightPx, parent.height)
                    assertTrue(visibleContentBounds(root).contains(parent.left, parent.top, parent.right, parent.bottom))
                    val landscapeEvidence = foregroundSnapshot("geometry_hiddenAncestorNeverReturnsOldBounds fixtureInsets=0", landscape)
                    val hardlineEvidence = foregroundSnapshot("geometry_hiddenAncestorNeverReturnsOldBounds fixtureInsets=0", hardline)
                    Log.i("StandbyGeometry", landscapeEvidence)
                    Log.i("StandbyGeometry", hardlineEvidence)
                    assertNotNull(landscapeEvidence, landscape.foregroundGeometryInViewPixels())
                    assertNotNull(hardlineEvidence, hardline.foregroundRectsInViewPixels())
                    parent.visibility = View.GONE
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                    assertNull(hardline.foregroundRectsInStageSpace())
                    parent.visibility = View.INVISIBLE
                }
                awaitForegroundLayout(scenario, root)
                scenario.onActivity {
                    assertEquals(View.INVISIBLE, parent.visibility)
                    assertTrue(ViewCompat.isLaidOut(parent))
                    assertTrue(ViewCompat.isLaidOut(landscape))
                    assertTrue(ViewCompat.isLaidOut(hardline))
                    assertFalse(parent.isLayoutRequested)
                    assertFalse(landscape.isLayoutRequested)
                    assertFalse(hardline.isLayoutRequested)
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                    assertNull(hardline.foregroundRectsInStageSpace())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun controls_areFocusableAccessibleAndUseOnlyCurrentCallbacks() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            var actions = 0
            lateinit var view: LandscapeStandbyForegroundView
            lateinit var dock: View
            lateinit var menu: View
            try {
                scenario.onActivity { activity ->
                    view = LandscapeStandbyForegroundView(activity)
                    view.render(
                        StandbyForegroundState(ScreenPhase.READY, false), "Title", "READY", "Detail",
                        StandbyForegroundCallbacks(onToggleBroadcast = { actions++ }, onOpenSettings = { actions += 10 }),
                    )
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(-1, -1))
                    layout(view, px(activity, 360f), px(activity, 800f))
                    dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                    for (control in listOf(dock, menu)) {
                        assertTrue(control.isFocusable)
                        assertTrue(control.isClickable)
                        assertTrue(control.width >= px(activity, 48f))
                        assertTrue(control.height >= px(activity, 48f))
                        assertFalse(control.contentDescription.isNullOrBlank())
                    }
                    assertTrue(dock.createAccessibilityNodeInfo().isCheckable)
                    val menuBounds = bounds(view, StandbyForegroundTags.MENU)
                    val x = (menuBounds.right - px(activity, 2f)).toFloat()
                    val y = menuBounds.centerY().toFloat()
                    val touchDown = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
                    val touchUp = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_UP, x, y, 0)
                    try {
                        assertTrue(view.dispatchTouchEvent(touchDown))
                        assertTrue(view.dispatchTouchEvent(touchUp))
                    } finally {
                        touchDown.recycle()
                        touchUp.recycle()
                    }
                }
                awaitActivityAction(scenario) { activity ->
                    assertEquals(10, actions)
                    dock.isFocusableInTouchMode = true
                    assertTrue(dock.requestFocus())
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                    val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue(dock.dispatchKeyEvent(down))
                    assertTrue(dock.dispatchKeyEvent(up))
                    assertEquals(11, actions)
                    view.motionLayer.translationX = px(activity, 12f).toFloat()
                    assertTrue(dock.parent !== view.motionLayer)
                    view.updateClock("12:35")
                    view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                    view.render(
                        StandbyForegroundState(ScreenPhase.READY, false), "Title", "READY", "Detail",
                        StandbyForegroundCallbacks(onToggleBroadcast = { actions += 2 }),
                    )
                    assertSame(dock, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertTrue(dock.hasFocus())
                    assertEquals(px(activity, 12f).toFloat(), view.motionLayer.translationX, 0f)
                    assertEquals(View.GONE, menu.visibility)
                    assertEquals(11, actions)
                    assertTrue(dock.performClick())
                    assertEquals(13, actions)
                }
            } finally {
                awaitActivityAction(scenario) { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun frozenAndWifiPaused_onlyExposeHostAuthorizedActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = MatterStandbyForegroundView(context)
            var toggles = 0
            var infoClicks = 0
            var shizukuClicks = 0
            var wifiStops = 0
            val callbacks = StandbyForegroundCallbacks(
                onToggleBroadcast = { toggles++ }, onShowFrozenInfo = { infoClicks++ },
                onOpenShizuku = { shizukuClicks++ }, onStopBroadcastWhilePaused = { wifiStops++ },
            )
            view.render(
                StandbyForegroundState(ScreenPhase.FROZEN, true, shouldOfferShizuku = true),
                "", "FROZEN", "", callbacks,
            )
            val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
            assertEquals(View.VISIBLE, dock.visibility)
            val dockNode = dock.createAccessibilityNodeInfo()
            assertEquals(android.widget.Button::class.java.name, dockNode.className)
            assertFalse(dockNode.isCheckable)
            dockNode.recycle()
            //Focused regression: the dock is this theme's only FROZEN info action.
            assertNull(view.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO))
            assertEquals(View.VISIBLE, requireNotNull(view.findViewWithTag<View>("foreground_shizuku")).visibility)
            layout(view, px(context, 360f), px(context, 800f))
            val minimum = ceil(48.0 * context.resources.displayMetrics.density).toInt()
            for (tag in listOf(StandbyForegroundTags.BROADCAST, "foreground_shizuku")) {
                val control = requireNotNull(view.findViewWithTag<View>(tag))
                assertTrue(control.isFocusable)
                assertTrue(control.width >= minimum)
                assertTrue(control.height >= minimum)
            }
            assertEquals(0, infoClicks)
            assertEquals(0, toggles)
            assertEquals(0, shizukuClicks)
            assertEquals(0, wifiStops)
            assertTrue(dock.performClick())
            assertEquals(1, infoClicks)
            assertEquals(0, toggles)
            assertEquals(0, shizukuClicks)
            assertEquals(0, wifiStops)
            view.render(
                StandbyForegroundState(ScreenPhase.WIFI_PAUSED, true), "", "PAUSED", "", callbacks,
            )
            assertEquals(View.VISIBLE, requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.WIFI_WARNING)).visibility)
            assertEquals(View.VISIBLE, requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.WIFI_STOP)).visibility)
            assertNull(view.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO))
            view.render(StandbyForegroundState(ScreenPhase.WIFI_PAUSED, true), "", "PAUSED", "", StandbyForegroundCallbacks())
            assertEquals(View.GONE, requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.WIFI_STOP)).visibility)
            assertEquals(1, infoClicks)
            assertEquals(0, toggles)
            assertEquals(0, shizukuClicks)
            assertEquals(0, wifiStops)
        }
    }

    @Test
    fun matterWindowHidden_staleBlinkCannotReattach() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            var view: MatterStandbyForegroundView? = null
            var stale: Runnable? = null
            val field = MatterStandbyForegroundView::class.java.getDeclaredField("blinkCallback").apply { isAccessible = true }
            try {
                scenario.onActivity { activity ->
                    val surface = MatterStandbyForegroundView(activity)
                    activity.contentRoot.addView(FrameLayout(activity).apply { addView(surface) })
                    surface.render(StandbyForegroundState(ScreenPhase.CONNECTING, false, isProjecting = false), "", "CONNECTING", "", StandbyForegroundCallbacks())
                    view = surface
                    stale = field.get(surface) as? Runnable
                    assertNotNull(stale)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val surface = requireNotNull(view)
                    assertNull(field.get(surface))
                    stale?.run()
                    assertNull(field.get(surface))
                    assertEquals(1f, requireNotNull(surface.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT)).alpha, 0f)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
            } finally {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Rejected portrait fit probes leave the current notice and source stack intact. */
    @Test
    fun portraitLandscapeRejectedFit_restoresNoticeAndSourceStack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 240
                fontScale = 1f
                setLocale(Locale.ENGLISH)
            })
            val width = 900
            val height = 1200
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {})
            val state = StandbyForegroundState(
                ScreenPhase.ADVERTISING, true, receiverName = "Receiver",
                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                failureText = context.getString(R.string.preplay_failed_timeout),
                copyMotionAllowed = false, isProjecting = false,
            )
            fun frame(parent: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).also {
                parent.offsetDescendantRectToMyCoords(child, it)
            }
            fun snapshot(parent: ViewGroup, vararg children: View): List<String> = children.map { child ->
                val textLayout = (child as? TextView)?.layout
                "${child.tag} ${frame(parent, child)} measured=${child.measuredWidth}x${child.measuredHeight} " +
                    "lines=${textLayout?.lineCount}/${textLayout?.height} scroll=${child.scrollX},${child.scrollY} " +
                    "focused=${child.isFocused}"
            }

            val controls = StandbyForegroundControls(context, StandbyControlTheme.LANDSCAPE)
            controls.updateViewport(width, height)
            val longNotice = state.failureText.repeat(80)
            controls.update(state.copy(failureText = longNotice), callbacks)
            val parent = FrameLayout(context).apply {
                setPadding(0, 460, 360, 0)
                addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            layout(parent, width, height)
            val failed = requireNotNull(controls.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
            val dismiss = requireNotNull(controls.findViewWithTag<TextView>(StandbyForegroundTags.FAILED_DISMISS))
            val notice = generateSequence(dismiss.parent as View) { it.parent as? View }.filterIsInstance<ScrollView>().first()
            dismiss.isFocusableInTouchMode = true
            assertTrue(dismiss.requestFocus())
            notice.scrollTo(0, 32)
            assertTrue("The source notice must be scrolled", notice.scrollY > 0)
            val beforeReject = snapshot(parent, notice, failed, dismiss)
            val dock = requireNotNull(controls.foregroundDockBounds)
            val narrowNotice = Rect(dock.right, parent.paddingTop, width - parent.paddingRight, dock.top)
            assertEquals(18, narrowNotice.width())
            assertTrue(narrowNotice.width() < dismiss.minimumWidth && dismiss.minimumWidth >= 72)
            assertFalse("The explicit lane cannot fit Dismiss", controls.tryExplicitNoticeLane(narrowNotice))
            val afterReject = snapshot(parent, notice, failed, dismiss)
            assertEquals(longNotice, failed.text.toString())

            parent.setPadding(0, 460, 0, 0)
            controls.update(state, callbacks)
            layout(parent, width, height)
            val beforeClear = snapshot(parent, notice, failed, dismiss)
            val rightLane = Rect(width - dock.width(), parent.paddingTop, width, dock.top)
            assertTrue("The short notice fits an explicit lane", controls.tryExplicitNoticeLane(rightLane))
            assertEquals(rightLane.left, frame(parent, notice).left)
            controls.clearExplicitNoticeLane()
            val afterClear = snapshot(parent, notice, failed, dismiss)

            val view = LandscapeStandbyForegroundView(context)
            view.applySecurityInsets(Insets.of(300, 460, 60, 0))
            val name = "W".repeat(32)
            val advertising = state.copy(receiverName = name, failureCause = null, failureText = "")
            val settingsOnly = StandbyForegroundCallbacks(onOpenSettings = {})
            view.render(advertising, "Title", "ADVERTISING", "", settingsOnly)
            layout(view, width, height)
            val brand = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BRAND))
            val eyebrow = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
            val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
            val status = eyebrow.parent as View
            val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
            val source = snapshot(view, brand, status, eyebrow, title, hint)
            val sourceStatusWidth = status.width
            assertEquals(1, requireNotNull(eyebrow.layout).lineCount)

            view.render(advertising, "Title", "ADVERTISING", "", settingsOnly.copy(onToggleBroadcast = {}))
            layout(view, width, height)
            val activeDock = bounds(view, StandbyForegroundTags.BROADCAST)
            assertTrue("The brand must hit the visible menu at its source anchor", Rect.intersects(
                frame(view, brand), bounds(view, StandbyForegroundTags.MENU),
            ))
            val leftSlotWidth = activeDock.left - view.paddingLeft
            assertEquals(78, leftSlotWidth)
            assertTrue("The source status is wider than the probed left slot", sourceStatusWidth > leftSlotWidth)
            val copy = hint.text as Spanned
            val receiver = copy.getSpans(0, copy.length, ReplacementSpan::class.java).single {
                copy.subSequence(copy.getSpanStart(it), copy.getSpanEnd(it)).toString() == name
            }
            val nameWidth = receiver.getSize(hint.paint, copy, copy.getSpanStart(receiver), copy.getSpanEnd(receiver), null)
            val hintLaneWidth = width - view.paddingRight - activeDock.right - hint.compoundPaddingLeft - hint.compoundPaddingRight
            assertTrue("The intact name cannot fit the right lane", nameWidth > hintLaneWidth)
            assertEquals(context.getString(R.string.standby_foreground_connection_hint_format, name), copy.toString())

            assertEquals(
                mapOf("notice rejection" to beforeReject, "notice clear" to beforeClear, "source stack" to source),
                mapOf("notice rejection" to afterReject, "notice clear" to afterClear,
                    "source stack" to snapshot(view, brand, status, eyebrow, title, hint)),
            )
        }
    }

    @Test
    fun portraitLandscapeInset_packsAllRolesIn360x200DpAcrossLocalesAndRestores() {
        for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
            ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
                lateinit var root: FrameLayout
                lateinit var view: LandscapeStandbyForegroundView
                val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}, onDismissFailure = {})

                fun box(child: View, rect: RectF = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())): RectF {
                    val globalToForeground = Matrix()
                    assertTrue(Matrix().also(view::transformMatrixToGlobal).invert(globalToForeground))
                    val childToGlobal = Matrix().also(child::transformMatrixToGlobal)
                    assertTrue(Matrix().apply { setConcat(globalToForeground, childToGlobal) }.mapRect(rect))
                    return rect
                }

                try {
                    awaitActivityAction(scenario) { root = it.contentRoot }
                    awaitForegroundLayout(scenario, root)
                    awaitActivityAction(scenario) { activity ->
                        val ctx = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                            densityDpi = 240
                            fontScale = 1f
                            setLocale(locale)
                        })
                        view = LandscapeStandbyForegroundView(ctx)
                        view.applySecurityInsets(Insets.of(0, 450, 0, 450))
                        view.render(
                            StandbyForegroundState(
                                ScreenPhase.ADVERTISING, true, receiverName = "Receiver",
                                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                                failureText = ctx.getString(R.string.preplay_failed_timeout),
                                copyMotionAllowed = false, isProjecting = false,
                            ),
                            ctx.getString(R.string.standby_foreground_phase_failed_title),
                            ctx.getString(R.string.standby_foreground_phase_failed_eyebrow),
                            "",
                            callbacks,
                        )
                        root.addView(view, visibleContentLayoutParams(root, visibleContentBounds(root), 540, 1200))
                    }
                    awaitForegroundLayout(scenario, view)
                    awaitActivityAction(scenario) {
                        val safe = RectF(0f, 450f, 540f, 750f)
                        fun <T : View> tagged(tag: String): T = requireNotNull(view.findViewWithTag<T>(tag))
                        val dock = tagged<View>(StandbyForegroundTags.BROADCAST)
                        val menu = tagged<View>(StandbyForegroundTags.MENU)
                        val dismiss = tagged<TextView>(StandbyForegroundTags.FAILED_DISMISS)
                        val failed = tagged<TextView>(StandbyForegroundTags.FAILED)
                        val notice = generateSequence(dismiss.parent as? View) { it.parent as? View }
                            .filterIsInstance<ScrollView>().first()
                        val title = tagged<TextView>(StandbyForegroundTags.PHASE_TITLE)
                        val eyebrow = tagged<TextView>(StandbyForegroundTags.PHASE_EYEBROW)
                        val status = eyebrow.parent as ViewGroup
                        val dot = status.getChildAt(0)
                        val center = title.parent as View
                        val hint = tagged<TextView>("foreground_hint")
                        val brand = generateSequence(tagged<View>(StandbyForegroundTags.BRAND)) { it.parent as? View }
                            .takeWhile { it !== view.motionLayer }.last()
                        val roles = linkedMapOf("brand" to brand, "status" to status, "title" to title, "hint" to hint,
                            "dock" to dock, "menu" to menu, "notice" to notice)
                        val textViews = listOf(title, eyebrow, hint, failed, dismiss)
                        assertSame(center, status.parent)
                        assertEquals(view.context.getString(R.string.standby_foreground_connection_hint_format, "Receiver"),
                            hint.text.toString())

                        fun fullBox(child: View): RectF {
                            val allocation = box(child)
                            val visible = Rect()
                            assertFalse("$locale ${child.tag} empty: $allocation", allocation.isEmpty)
                            assertTrue("$locale ${child.tag} outside safe: $allocation", safe.contains(allocation))
                            assertTrue("$locale ${child.tag} not visible", child.isShown && child.alpha > 0f &&
                                child.getGlobalVisibleRect(visible))
                            val mappedVisible = box(child.rootView, RectF(visible))
                            assertTrue("$locale ${child.tag} clipped: $allocation not in $mappedVisible",
                                mappedVisible.contains(allocation))
                            return allocation
                        }

                        for ((dx, dy) in listOf(0f to 0f, 12f to 12f, -12f to -12f)) {
                            view.setLegacyAmbientOffset(dx, dy)
                            val allocations = roles.mapValues { fullBox(it.value) }.entries.toList()
                            for ((index, first) in allocations.withIndex()) {
                                for (second in allocations.drop(index + 1)) {
                                    assertFalse("$locale drift ($dx,$dy) $first intersects $second",
                                        RectF.intersects(first.value, second.value))
                                }
                            }
                            assertEquals(RectF(198f, 606f, 342f, 750f), box(dock))
                            assertEquals(RectF(0f, 534f, 72f, 666f), box(menu))
                            assertTrue(box(title).left >= box(menu).right && box(title).bottom <= box(dock).top)
                            assertTrue(box(brand).top >= box(menu).bottom && box(status).top >= box(brand).bottom)
                            assertTrue(box(notice).left >= box(dock).right && box(notice).bottom <= box(hint).top)
                            assertTrue(box(hint).left >= box(dock).right)
                            assertTrue(box(center).contains(box(title)) && box(center).contains(box(status)))
                            assertTrue(box(status).contains(fullBox(dot)) && box(status).contains(box(eyebrow)))
                            assertTrue(box(notice).contains(box(failed)) && box(notice).contains(box(dismiss)))
                            for (action in listOf(dock, menu, dismiss)) {
                                val hit = fullBox(action)
                                assertTrue("$locale ${action.tag} invalid hit: $hit",
                                    action.isFocusable && action.isClickable && hit.width() >= 72f && hit.height() >= 72f)
                            }
                            for (textView in textViews) {
                                val layout = requireNotNull(textView.layout)
                                assertTrue(layout.lineCount > 0)
                                assertEquals(textView.text.length, layout.getLineEnd(layout.lineCount - 1))
                                val allocation = fullBox(textView)
                                val x = textView.compoundPaddingLeft - textView.scrollX
                                val y = textView.totalPaddingTop - textView.scrollY
                                for (line in 0 until layout.lineCount) {
                                    assertEquals(0, layout.getEllipsisCount(line))
                                    val lineBox = box(textView, RectF(x + layout.getLineLeft(line),
                                        (y + layout.getLineTop(line)).toFloat(), x + layout.getLineRight(line),
                                        (y + layout.getLineBottom(line)).toFloat()))
                                    assertTrue("$locale ${textView.tag} line $lineBox outside $allocation / $safe",
                                        allocation.contains(lineBox) && safe.contains(lineBox))
                                }
                            }
                            val geometry = requireNotNull(view.foregroundGeometryInViewPixels())
                            assertEquals(box(title).top, geometry.primaryTopPx, 0f)
                        }

                        // A name that cannot fit the right lane must restore the source hint anchor.
                        view.setLegacyAmbientOffset(0f, 0f)
                        view.render(
                            StandbyForegroundState(
                                ScreenPhase.ADVERTISING, true, receiverName = "W".repeat(32),
                                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                                failureText = view.context.getString(R.string.preplay_failed_timeout),
                                copyMotionAllowed = false, isProjecting = false,
                            ),
                            view.context.getString(R.string.standby_foreground_phase_failed_title),
                            view.context.getString(R.string.standby_foreground_phase_failed_eyebrow),
                            "",
                            callbacks,
                        )
                    }
                    awaitForegroundLayout(scenario, view)
                    awaitActivityAction(scenario) {
                        val dock = box(requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)))
                        val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                        val fallbackHint = box(hint)
                        assertTrue("Overwide name must restore normal hint anchor ($locale)",
                            fallbackHint.left < dock.left && fallbackHint.right > dock.right)
                        assertEquals(view.context.getString(R.string.standby_foreground_connection_hint_format, "W".repeat(32)),
                            hint.text.toString())

                        // Restore normal layout when insets clear.
                        view.applySecurityInsets(Insets.NONE)
                        view.render(
                            StandbyForegroundState(
                                ScreenPhase.WIFI_PAUSED, true, receiverName = "Receiver",
                                copyMotionAllowed = false, isProjecting = false,
                            ),
                            view.context.getString(R.string.standby_foreground_phase_wifi_paused_title),
                            view.context.getString(R.string.standby_foreground_phase_wifi_paused_eyebrow),
                            "",
                            callbacks,
                        )
                    }
                    awaitForegroundLayout(scenario, view)
                    awaitActivityAction(scenario) {
                        val dock = box(requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)))
                        val restoredHint = box(requireNotNull(view.findViewWithTag<View>("foreground_hint")))
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        val eyebrow = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                        val status = eyebrow.parent as View
                        val center = title.parent as ViewGroup
                        assertTrue("Restored hint must re-center horizontally", restoredHint.left < dock.left && restoredHint.right > dock.right)
                        assertTrue(center.clipToPadding && center.clipChildren)
                        assertEquals(13, title.top - status.bottom)
                        assertEquals(((view.height * 0.47f).toInt() - center.height / 2).toFloat(), box(center).top, 0f)
                    }
                } finally {
                    awaitActivityAction(scenario) { it.contentRoot.removeAllViews() }
                }
            }
        }
    }
}
