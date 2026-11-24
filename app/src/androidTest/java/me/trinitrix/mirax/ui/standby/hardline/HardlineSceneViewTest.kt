package me.trinitrix.mirax.ui.standby.hardline

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.trinitrix.mirax.WindowEvidence
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.foreground.HardlineStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.awaitForegroundLayout
import me.trinitrix.mirax.ui.standby.foreground.visibleContentBounds
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * Isolated background-only mount checks for [HardlineSceneView].
 *
 * Mounts the candidate in the existing debug [StandbyProbeActivity] host and
 * verifies 118 math vectors plus the attached native draw. Hardware
 * acceleration is recorded, never asserted when unknown. Frame cadence is
 * observed only; no pass threshold is invented. Parent owns build, install,
 * execution, and review.
 */
@RunWith(AndroidJUnit4::class)
class HardlineSceneViewTest {

    /** Records global source stacking and actual opaque-core occlusion across three viewports. */
    @Test
    fun scene_drawsCompleteMarkAboveViewportSpaceExtrusion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((width, height) in listOf(1600 to 1000, 2400 to 1080, 800 to 500)) {
                val view = HardlineSceneView(InstrumentationRegistry.getInstrumentation().targetContext)
                view.layout(0, 0, width, height)
                view.setHostFieldGeometry(0f, height * 0.7f, height * 0.8f)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = PathRecordingCanvas(bitmap)
                view.draw(canvas)
                val draws = canvas.paths
                val underlays = draws.filter { it.paint.color == HardlineSceneView.UNDERLAY_COLOR }
                val edges = draws.filter { it.paint.color == HardlineSceneView.EDGE_COLOR }
                val viewport = StandbyViewportScale(width, height, view.resources.displayMetrics.density, cssPixels = true)
                val sections = draws.filter { it.paint.strokeWidth == viewport.px(HardlineSceneView.SECTION_WIDTH) }
                val guides = draws.filter { it.paint.color == HardlineSceneView.GUIDE_COLOR }
                val rails = draws.filter { it.paint.color == HardlineSceneView.RAIL_COLOR }
                assertEquals(2, underlays.size)
                assertEquals(1, edges.size)
                assertTrue(sections.isNotEmpty())
                assertEquals(2, guides.size)
                assertEquals(1, rails.size)
                if (width == 1600 && height == 1000) {
                    assertStaticRailsOnly(rails.single())
                }
                assertTrue(draws.indexOf(underlays.last()) < draws.indexOf(edges.single()))
                assertTrue(draws.indexOf(edges.single()) < draws.indexOf(sections.first()))
                assertTrue(draws.indexOf(sections.last()) < draws.indexOf(guides.first()))
                val channelRects = canvas.rects.filter { it.paint.color == 0x03FFFFFF }
                val channelLines = canvas.lines.filter { it.paint.color == 0xFF393939.toInt() }
                assertEquals(2, channelRects.size)
                assertEquals(4, channelLines.size)
                assertTrue(canvas.texts.isNotEmpty())
                assertTrue(guides.last().order < channelRects.first().order)
                val channelsEnd = maxOf(
                    channelRects.maxOf { it.order }, channelLines.maxOf { it.order }, canvas.texts.maxOf { it.order },
                )
                assertTrue("All ROG bands, rails and text precede the complete static SVG.", channelsEnd < rails.single().order)
                val vertical = draws.single { it.paint.color == HardlineSceneView.VERTICAL_COLOR }
                val header = canvas.lines.single { it.paint.color == HardlineSceneView.HEADER_COLOR }
                assertTrue(rails.single().order < vertical.order)
                assertTrue(vertical.order < header.order)
                assertEquals(viewport.px(0.75f), underlays.first().paint.strokeWidth, 0.0001f)
                assertEquals(viewport.px(0.8f), edges.single().paint.strokeWidth, 0.0001f)
                assertEquals(viewport.px(0.85f), sections.first().paint.strokeWidth, 0.0001f)
                assertEquals(viewport.px(0.65f), guides.first().paint.strokeWidth, 0.0001f)

                val fills = draws.filter {
                    it.paint.color == HardlineSceneView.ARM_FILL_COLOR && it.paint.style == Paint.Style.FILL
                }
                val coreStrokes = draws.filter { it.paint.color == HardlineSceneView.ARM_STROKE_COLOR }
                val echoStrokes = draws.filter { it.paint.color == HardlineSceneView.ECHO_STROKE_COLOR }
                assertEquals(5, fills.size)
                assertEquals(5, coreStrokes.size)
                assertEquals(5, echoStrokes.size)
                assertTrue(header.order < fills.first().order)
                assertTrue(fills.last().order < coreStrokes.first().order)
                assertTrue(coreStrokes.last().order < echoStrokes.first().order)
                assertEquals(listOf(HardlineSceneView.BACKGROUND_COLOR, 0x18000000), canvas.colors.map { it.color })
                assertTrue(echoStrokes.last().order < canvas.colors.last().order)
                assertEquals(canvas.drawCount - 1, canvas.colors.last().order)
                val slice = HardlineSceneView.sliceTransformFor(width.toDouble(), height.toDouble())
                assertEquals((0.16 * 1370.0 / 108.0 * slice.scale).toFloat(), coreStrokes.last().paint.strokeWidth, 0.0001f)
                assertEquals((0.08 * 1370.0 / 108.0 * slice.scale).toFloat(), echoStrokes.last().paint.strokeWidth, 0.0001f)
                assertEquals(255, Color.alpha(coreStrokes.last().paint.color))
                assertEquals(0x3d, Color.alpha(echoStrokes.last().paint.color))
                assertEquals(slice.scale.toFloat(), rails.single().paint.strokeWidth, 0.0001f)
                assertMarkBounds(fills, slice, 510.0, -335.0)
                assertMarkBounds(coreStrokes, slice, 510.0, -335.0)
                assertMarkBounds(echoStrokes, slice, 526.0, -323.0)
                assertMarkCoversUnderlay(bitmap, fills.last().path, underlays)
                bitmap.recycle()
            }
        }
    }

    /** Changes only legacy SVG shift at fixed study time; viewport art and every clip stay put. */
    @Test
    fun legacySvgShift_movesCompleteMarkAndAllExtrusionFamiliesOnly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (densityDpi in listOf(160, 240)) {
                for ((width, height) in listOf(1600 to 1000, 2400 to 1080, 1000 to 1600)) {
                    val view = HardlineSceneView(contextAtDensity(densityDpi))
                    view.layout(0, 0, width, height)
                    view.setHostFieldGeometry(height * 0.08f, height * 0.45f, height * 0.6f,
                        Rect(40, height - 160, 180, height - 100), Rect(width / 2, height - 160, width / 2 + 100, height - 20))
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        val before = PathRecordingCanvas(bitmap)
                        view.draw(before)
                        view.setLegacySvgOffset(1.68, 3.36)
                        val after = PathRecordingCanvas(bitmap)
                        view.draw(after)
                        val slice = HardlineSceneView.sliceTransformFor(width.toDouble(), height.toDouble())
                        val dx = (1.68 * slice.scale).toFloat()
                        val dy = (3.36 * slice.scale).toFloat()
                        val viewport = StandbyViewportScale(width, height, densityDpi / 160f, cssPixels = true)
                        val movingColors = setOf(HardlineSceneView.ARM_FILL_COLOR, HardlineSceneView.ARM_STROKE_COLOR,
                            HardlineSceneView.ECHO_STROKE_COLOR, HardlineSceneView.EDGE_COLOR, HardlineSceneView.GUIDE_COLOR)
                        assertEquals(before.drawCount, after.drawCount)
                        assertEquals(before.paths.size, after.paths.size)
                        var movedPaths = 0
                        for ((original, shifted) in before.paths.zip(after.paths)) {
                            val moving = original.paint.color in movingColors ||
                                (original.paint.strokeWidth == viewport.px(HardlineSceneView.SECTION_WIDTH) && Color.red(original.paint.color) == 240)
                            if (moving) movedPaths++
                            val a = original.path.approximate(0.05f)
                            val b = shifted.path.approximate(0.05f)
                            assertEquals(a.size, b.size)
                            for (index in 0 until a.size / 3) {
                                assertEquals(a[index * 3 + 1] + if (moving) dx else 0f, b[index * 3 + 1], 0.002f)
                                assertEquals(a[index * 3 + 2] + if (moving) dy else 0f, b[index * 3 + 2], 0.002f)
                            }
                            assertEquals(original.matrix, shifted.matrix)
                            assertEquals(original.paint.color, shifted.paint.color)
                            assertEquals(original.paint.strokeWidth, shifted.paint.strokeWidth, 0f)
                        }
                        assertTrue(movedPaths > 15)
                        for (color in movingColors) assertTrue(before.paths.any { it.paint.color == color })
                        assertTrue(before.paths.any { it.paint.strokeWidth == viewport.px(HardlineSceneView.SECTION_WIDTH) })
                        assertEquals(before.clips, after.clips)
                        assertEquals(before.colors, after.colors)
                        assertEquals(before.lines.map { it.bounds }, after.lines.map { it.bounds })
                        assertEquals(before.lines.map { it.matrix }, after.lines.map { it.matrix })
                        assertEquals(before.rects.map { it.bounds }, after.rects.map { it.bounds })
                        assertEquals(before.rects.map { it.matrix }, after.rects.map { it.matrix })
                        assertEquals(before.texts.map { it.text }, after.texts.map { it.text })
                        assertEquals(before.texts.map { it.x }, after.texts.map { it.x })
                        assertEquals(before.texts.map { it.y }, after.texts.map { it.y })
                        assertEquals(before.texts.map { it.matrix }, after.texts.map { it.matrix })
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /** Uses fixed source candidates, including blocked/out-of-stage cases, without the main SVG slice. */
    @Test
    fun cornerDiamond_usesViewportCssPixelsAndOrderedObstacles() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cases = listOf(
                Triple(Rect(40, 680, 160, 720), Rect(450, 700, 550, 780), RectF(172f, 687f, 198f, 713f)),
                Triple(Rect(840, 680, 970, 720), Rect(450, 700, 550, 780), RectF(562f, 727f, 588f, 753f)),
                Triple(Rect(40, 680, 160, 720), Rect(172, 660, 970, 780), RectF(40f, 642f, 66f, 668f)),
                Triple(Rect(40, 760, 160, 800), Rect(450, 700, 550, 780), RectF(562f, 727f, 588f, 753f)),
                Triple(Rect(40, 30, 970, 70), Rect(40, 82, 970, 780), null),
                Triple(Rect(0, 100, 970, 140), Rect(40, 160, 970, 780), null),
            )
            for (densityDpi in listOf(160, 240)) {
                val context = contextAtDensity(densityDpi)
                val density = context.resources.displayMetrics.density
                val view = HardlineSceneView(context)
                val width = (1000 * density).toInt()
                val height = (800 * density).toInt()
                view.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fun scaled(rect: Rect): Rect = Rect(
                    (rect.left * density).toInt(), (rect.top * density).toInt(),
                    (rect.right * density).toInt(), (rect.bottom * density).toInt(),
                )
                fun assertHidden() {
                    val canvas = PathRecordingCanvas(bitmap)
                    view.draw(canvas)
                    assertTrue(canvas.paths.none { it.paint.color == CORNER_DIAMOND_COLOR })
                }
                try {
                    for ((receiver, dock, expected) in cases) {
                        view.setHostFieldGeometry(null, null, null, scaled(receiver), scaled(dock))
                        val canvas = PathRecordingCanvas(bitmap)
                        view.draw(canvas)
                        val diamonds = canvas.paths.filter { it.paint.color == CORNER_DIAMOND_COLOR }
                        val evidence = "density=$density receiver=$receiver dock=$dock expected=$expected"
                        if (expected == null) {
                            assertTrue(evidence, diamonds.isEmpty())
                        } else {
                            val diamond = diamonds.single()
                            val bounds = RectF()
                            diamond.path.computeBounds(bounds, true)
                            assertEquals(evidence, expected.left * density, bounds.left, 0.001f)
                            assertEquals(evidence, expected.top * density, bounds.top, 0.001f)
                            assertEquals(evidence, expected.right * density, bounds.right, 0.001f)
                            assertEquals(evidence, expected.bottom * density, bounds.bottom, 0.001f)
                            assertEquals(evidence, Paint.Style.STROKE, diamond.paint.style)
                            assertEquals(evidence, density, diamond.paint.strokeWidth, 0.001f)
                            assertTrue(evidence, canvas.paths.filter { it.paint.color == HardlineSceneView.ECHO_STROKE_COLOR }
                                .all { it.order < diamond.order })
                            assertTrue(evidence, diamond.order < canvas.colors.last().order)
                            assertEquals(evidence, 0x18000000, canvas.colors.last().color)
                            assertEquals(evidence, canvas.drawCount - 1, canvas.colors.last().order)
                        }
                    }
                    val receiver = scaled(cases.first().first)
                    val dock = scaled(cases.first().second)
                    view.setHostFieldGeometry(null, null, null, receiver, dock)
                    val breakpointWidth = (600 * density).toInt()
                    view.layout(0, 0, breakpointWidth, height)
                    assertHidden()
                    view.layout(0, 0, breakpointWidth + 1, height)
                    val aboveBreakpoint = PathRecordingCanvas(bitmap)
                    view.draw(aboveBreakpoint)
                    assertEquals(1, aboveBreakpoint.paths.count { it.paint.color == CORNER_DIAMOND_COLOR })
                    for ((absentReceiver, absentDock) in listOf(null to dock, receiver to null, Rect() to dock, receiver to Rect())) {
                        view.setHostFieldGeometry(null, null, null, absentReceiver, absentDock)
                        assertHidden()
                    }
                    view.setHostFieldGeometry(null, null, null)
                    assertHidden()
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    /** Exercises real host geometry, foreground occlusion and the media visibility gate without playback. */
    @Test
    fun host_placesGeneratedDiamondBelowForegroundAndHidesItForMedia() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var host: BigStandbyHost
            var ownedHost: BigStandbyHost? = null
            lateinit var scene: HardlineSceneView
            lateinit var foreground: HardlineStandbyForegroundView
            scenario.onActivity { root = it.contentRoot }
            awaitForegroundLayout(scenario, root)
            try {
                scenario.onActivity {
                    val visible = visibleContentBounds(root)
                    host = BigStandbyHost(contextAtDensity(240), StandbyForegroundCallbacks(onToggleBroadcast = {}))
                    ownedHost = host
                    host.pivotX = 0f
                    host.pivotY = 0f
                    val scale = minOf(1f, visible.width() / 1500f, visible.height() / 1000f)
                    host.scaleX = scale
                    host.scaleY = scale
                    host.render(StandbyThemeId.HARDLINE,
                        StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio", copyMotionAllowed = false),
                        "Ready", "BROADCAST OFF", "")
                    root.addView(host, FrameLayout.LayoutParams(1500, 1000, Gravity.TOP or Gravity.LEFT).apply {
                        leftMargin = visible.left - root.paddingLeft
                        topMargin = visible.top - root.paddingTop
                    })
                    scene = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineSceneView>().single()
                    foreground = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineStandbyForegroundView>().single()
                }
                awaitForegroundLayout(scenario, host, foreground)
                scenario.onActivity {
                    val geometry = requireNotNull(foreground.foregroundRectsInViewPixels())
                    assertFalse(geometry.receiver.isEmpty)
                    assertFalse(geometry.dock.isEmpty)
                    val bitmap = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = PathRecordingCanvas(bitmap)
                        scene.draw(canvas)
                        val diamond = canvas.paths.single { it.paint.color == CORNER_DIAMOND_COLOR }
                        val bounds = RectF()
                        diamond.path.computeBounds(bounds, true)
                        assertEquals(geometry.receiver.right + 18f, bounds.left, 0.001f)
                        assertEquals(geometry.receiver.exactCenterY() - 19.5f, bounds.top, 0.001f)
                        assertEquals(39f, bounds.width(), 0.001f)
                        assertEquals(1.5f, diamond.paint.strokeWidth, 0.001f)
                    } finally {
                        bitmap.recycle()
                    }
                    val receiver = requireNotNull(foreground.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME)).parent.parent as View
                    // Only the fixture receiver moves, placing the source's first candidate behind the real center plate.
                    receiver.translationY = geometry.centerBand.exactCenterY() - geometry.receiver.exactCenterY()
                }
                awaitForegroundLayout(scenario, host, foreground)
                scenario.onActivity {
                    val geometry = requireNotNull(foreground.foregroundRectsInViewPixels())
                    val bitmap = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
                    val mask = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = PathRecordingCanvas(bitmap)
                        scene.draw(canvas)
                        val diamond = canvas.paths.single { it.paint.color == CORNER_DIAMOND_COLOR }
                        val bounds = RectF()
                        diamond.path.computeBounds(bounds, true)
                        assertTrue(RectF(geometry.centerBand).contains(bounds))
                        Canvas(mask).drawPath(diamond.path, diamond.paint)
                        var visibleDiamond = false
                        for (y in bounds.top.toInt()..bounds.bottom.toInt()) {
                            for (x in bounds.left.toInt()..bounds.right.toInt()) {
                                if (Color.alpha(mask.getPixel(x, y)) > 0 && Color.red(bitmap.getPixel(x, y)) > 64) visibleDiamond = true
                            }
                        }
                        assertTrue("The generated scene must really paint the diamond before occlusion.", visibleDiamond)
                        foreground.draw(Canvas(bitmap))
                        for (y in bounds.top.toInt()..bounds.bottom.toInt()) {
                            for (x in bounds.left.toInt()..bounds.right.toInt()) {
                                if (Color.alpha(mask.getPixel(x, y)) > 0) assertEquals(0xFF090909.toInt(), bitmap.getPixel(x, y))
                            }
                        }
                        val title = requireNotNull(foreground.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        val titleBounds = Rect(0, 0, title.width, title.height)
                        foreground.offsetDescendantRectToMyCoords(title, titleBounds)
                        var undimmedTitleInk = false
                        for (y in titleBounds.top until titleBounds.bottom) {
                            for (x in titleBounds.left until titleBounds.right) {
                                if (bitmap.getPixel(x, y) == title.currentTextColor) undimmedTitleInk = true
                            }
                        }
                        assertTrue("The generated-only scrim must not dim foreground title ink.", undimmedTitleInk)
                    } finally {
                        mask.recycle()
                        bitmap.recycle()
                    }
                    assertNull(foreground.findViewWithTag<View>("foreground_corner_diamond"))
                    assertTrue(host.indexOfChild(scene) < host.indexOfChild(foreground))
                    val mediaScrim = host.getChildAt(1)
                    assertEquals(View.GONE, mediaScrim.visibility)
                    assertEquals(0xC6000000.toInt(), (mediaScrim.background as ColorDrawable).color)
                    host.setMediaVisible(true)
                    assertEquals(View.GONE, scene.visibility)
                    assertEquals(View.VISIBLE, foreground.visibility)
                    assertEquals(View.VISIBLE, mediaScrim.visibility)
                    host.setMediaVisible(false)
                    assertEquals(View.VISIBLE, scene.visibility)
                    assertEquals(View.VISIBLE, foreground.visibility)
                    assertEquals(View.GONE, mediaScrim.visibility)
                }
            } finally {
                scenario.onActivity { ownedHost?.let { root.removeView(it) } }
            }
        }
    }

    /** Checks the source's mixed inline sizes, absolute tracking, and 16px + 16px status padding. */
    @Test
    fun channelTypographyAndStatusGap_useCssDensityNotArea() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(800, 1280, Bitmap.Config.ARGB_8888)
            try {
                val canvas = PathRecordingCanvas(bitmap)
                val scene = RogHardlineScene(statusCopy = testStatusCopy())
                scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 0L)
                scene.draw(canvas, 800f, 1280f, StandbyViewportScale(800, 1280, 1.5f, cssPixels = true))
                val word = canvas.texts.first { it.text == "MIRAX" }
                assertEquals(18f, word.paint.textSize, 0.001f)
                assertEquals(1.65f, word.paint.letterSpacing * word.paint.textSize, 0.001f)
                val comment = canvas.texts.first { it.text == "; display-only register sketch" }
                assertEquals(15f, comment.paint.textSize, 0.001f)
                val marker = canvas.texts.first { it.text == "/*" }
                assertEquals(19.5f, marker.paint.textSize, 0.001f)
                val status = canvas.texts.filter { it.text == "Press the broadcast button to start receiving" }
                assertTrue(status.size >= 2)
                assertEquals(22f, status[0].paint.textSize, 0.002f)
                assertEquals(700, status[0].paint.typeface.weight)
                assertEquals(1.65f, status[0].paint.letterSpacing * status[0].paint.textSize, 0.001f)
                assertEquals(status[0].paint.measureText(status[0].text) + 48f, status[1].x - status[0].x, 0.01f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Records receiver status suppression/restoration without hiding the decorative channels. */
    @Test
    fun channelStatus_suppressesUnknownReceiverAndRestoresRealInvitation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(800, 1280, Bitmap.Config.ARGB_8888)
            try {
                val scene = RogHardlineScene(statusCopy = testStatusCopy())
                val viewport = StandbyViewportScale(800, 1280, 1.5f, cssPixels = true)
                val restoredName = "  MIRAX · Study  "
                val failureCopy = "Connection failed · Press Win + K to retry"
                val cases = listOf(
                    Triple("Studio", false, "Windows: press Win + K · Choose Studio"),
                    Triple("", false, null),
                    Triple("   ", false, null),
                    Triple(restoredName, false, "Windows: press Win + K · Choose $restoredName"),
                    Triple("", true, failureCopy),
                    Triple("   ", true, failureCopy),
                )
                for ((receiverName, hasFailure, expectedStatus) in cases) {
                    val canvas = PathRecordingCanvas(bitmap)
                    scene.setState(ScreenPhase.ADVERTISING, receiverName, hasFailure, RogHardlineScene.StatusMode.STOPPED, 0L)
                    scene.draw(canvas, 800f, 1280f, viewport)
                    val evidence = "receiver=[$receiverName] hasFailure=$hasFailure expectedStatus=$expectedStatus"
                    assertTrue(evidence, canvas.texts.any { it.text == "; display-only register sketch" })
                    assertTrue(evidence, canvas.texts.any { it.text == "/*" })
                    assertFalse(evidence, canvas.texts.any { it.text.contains("MIRAX · 客廳") })
                    // Only Row 3 uses bold status paint; the decorative rows use regular monospace.
                    val status = canvas.texts.filter { it.paint.typeface?.isBold == true }
                    if (expectedStatus == null) {
                        assertTrue(evidence, status.isEmpty())
                        assertFalse(evidence, canvas.texts.any { it.text.startsWith("Windows: press Win + K · Choose ") })
                    } else {
                        assertTrue(evidence, status.isNotEmpty())
                        val expectedColor = Color.parseColor(if (hasFailure) "#ff6b6b" else "#f2f2f2")
                        for (draw in status) {
                            assertEquals(evidence, expectedStatus, draw.text)
                            assertEquals(evidence, expectedColor, draw.paint.color)
                        }
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** A return resumes Row 3, while both decorative channels and deterministic edge traces keep global time. */
    @Test
    fun channelStatus_resumesPhaseLocalPositionWhileDecorativeRowsKeepGlobalTime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            try {
                val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                val continuous = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                var now = 0L
                val viewport = StandbyViewportScale(1600, 1000, 1f, cssPixels = true)
                fun draw(model: RogHardlineScene): PathRecordingCanvas = PathRecordingCanvas(bitmap).also {
                    model.draw(it, 1600f, 1000f, viewport)
                }
                fun advance(seconds: Double) {
                    scene.update(seconds)
                    continuous.update(seconds)
                    now += (seconds * 1000.0).toLong()
                    scene.advanceStatusTo(now)
                    continuous.advanceStatusTo(now)
                }
                val ready = "Press the broadcast button to start receiving"
                val arming = "Starting · Not ready"
                scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                continuous.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(scene), viewport, ready, 0.0, 148.0)
                advance(2.0)
                val before = draw(scene)
                val priorReady = assertStatusPhasePosition(before, viewport, ready, 2.0, 148.0)
                scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(scene), viewport, arming, 0.0, 68.0)
                advance(1.25)
                assertStatusPhasePosition(draw(scene), viewport, arming, 1.25, 68.0)
                scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                val returned = draw(scene)
                val resumed = assertStatusPhasePosition(returned, viewport, ready, 2.0, 148.0)
                assertEquals(priorReady.x, resumed.x, 0f)
                assertEquals(priorReady.y, resumed.y, 0f)
                assertEquals(priorReady.matrix, resumed.matrix)
                val global = draw(continuous)
                val decoration = returned.texts.filter { it.paint.typeface?.isBold != true }
                val expectedDecoration = global.texts.filter { it.paint.typeface?.isBold != true }
                assertEquals(expectedDecoration.map { it.text }, decoration.map { it.text })
                assertEquals(expectedDecoration.map { it.x }, decoration.map { it.x })
                assertEquals(expectedDecoration.map { it.y }, decoration.map { it.y })
                assertEquals(expectedDecoration.map { it.matrix }, decoration.map { it.matrix })
                for (marker in listOf("; display-only register sketch", "/*")) {
                    assertTrue("The $marker row must keep advancing during ARMING",
                        before.texts.first { it.text == marker }.x != returned.texts.first { it.text == marker }.x)
                }
                assertEquals(continuous.streams, scene.streams)
                val traces = scene.streams.map { it.copy() }
                repeat(2) {
                    val repeated = draw(scene)
                    assertStatusPhasePosition(repeated, viewport, ready, 2.0, 148.0)
                    assertEquals(returned.texts.map { it.x }, repeated.texts.map { it.x })
                    assertEquals(traces, scene.streams)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Same-duration phases are distinct; visual failure has one clock even when its underlying phase changes. */
    @Test
    fun channelStatus_separatesWaitingWifiAndSharedFailurePositions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(800, 1280, Bitmap.Config.ARGB_8888)
            try {
                val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                var now = 0L
                fun advance(seconds: Double) {
                    scene.update(seconds)
                    now += (seconds * 1000.0).toLong()
                    scene.advanceStatusTo(now)
                }
                val viewport = StandbyViewportScale(800, 1280, 1.5f, cssPixels = true)
                fun draw(): PathRecordingCanvas = PathRecordingCanvas(bitmap).also {
                    scene.draw(it, 800f, 1280f, viewport)
                }
                val waiting = "Windows: press Win + K · Choose Studio"
                val wifi = "Wi-Fi is off · Please turn on Wi-Fi to broadcast"
                val failure = "Connection failed · Press Win + K to retry"
                val frozen = "Receiver unavailable · Check settings"
                scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                advance(0.75)
                assertStatusPhasePosition(draw(), viewport, waiting, 0.75, 144.0)
                scene.setState(ScreenPhase.WIFI_PAUSED, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, wifi, 0.0, 144.0)
                advance(1.25)
                assertStatusPhasePosition(draw(), viewport, wifi, 1.25, 144.0)
                scene.setState(ScreenPhase.WIFI_PAUSED, "", true, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, failure, 0.0, 140.0)
                advance(0.5)
                scene.setState(ScreenPhase.CONNECTED, "", true, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, failure, 0.5, 140.0)
                advance(0.25)
                scene.setState(ScreenPhase.ADVERTISING, "", true, RogHardlineScene.StatusMode.RUNNING, now)
                val failed = assertStatusPhasePosition(draw(), viewport, failure, 0.75, 140.0)
                assertEquals(0xFFFF6B6B.toInt(), failed.paint.color)
                scene.setState(ScreenPhase.WIFI_PAUSED, "", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, wifi, 1.25, 144.0)
                scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, waiting, 0.75, 144.0)
                scene.setState(ScreenPhase.FROZEN, "", false, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, frozen, 0.0, 112.0)
                advance(0.5)
                val unavailable = assertStatusPhasePosition(draw(), viewport, frozen, 0.5, 112.0)
                assertEquals(0xFFFF6B6B.toInt(), unavailable.paint.color)
                scene.setState(ScreenPhase.CONNECTED, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                val connected = draw()
                assertTrue(connected.texts.none { it.paint.typeface?.isBold == true })
                assertTrue(connected.texts.any { it.text == "; display-only register sketch" })
                scene.setState(ScreenPhase.FROZEN, "", true, RogHardlineScene.StatusMode.RUNNING, now)
                assertStatusPhasePosition(draw(), viewport, failure, 0.75, 140.0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Fresh names change measured stride, never phase; blank names, draws, resize and invalid deltas cannot reset it. */
    @Test
    fun channelStatus_preservesWaitingProgressAcrossNamesSuppressionAndResize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(1600, 1280, Bitmap.Config.ARGB_8888)
            try {
                val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                var now = 0L
                fun advance(seconds: Double) {
                    scene.update(seconds)
                    now += (seconds * 1000.0).toLong()
                    scene.advanceStatusTo(now)
                }
                val viewport = StandbyViewportScale(1600, 1000, 1.5f, cssPixels = true)
                fun draw(): PathRecordingCanvas = PathRecordingCanvas(bitmap).also {
                    scene.draw(it, 1600f, 1000f, viewport)
                }
                val prefix = "Windows: press Win + K · Choose "
                scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                advance(0.5)
                val initial = assertStatusPhasePosition(draw(), viewport, prefix + "Studio", 0.5, 144.0)
                repeat(3) {
                    scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
                    assertEquals(initial.x, assertStatusPhasePosition(draw(), viewport, prefix + "Studio", 0.5, 144.0).x, 0f)
                }
                val name = "  <Desk & \"TV\" / 客廳>  "
                scene.setState(ScreenPhase.ADVERTISING, name, false, RogHardlineScene.StatusMode.RUNNING, now)
                val renamed = assertStatusPhasePosition(draw(), viewport, prefix + name, 0.5, 144.0)
                assertTrue(initial.paint.measureText(initial.text) != renamed.paint.measureText(renamed.text))
                for (unknown in listOf("", "   ", "\t\n")) {
                    scene.setState(ScreenPhase.ADVERTISING, unknown, false, RogHardlineScene.StatusMode.RUNNING, now)
                    assertNull(scene.statusText)
                    val hidden = draw()
                    assertTrue(hidden.texts.none { it.paint.typeface?.isBold == true || it.text.startsWith(prefix) })
                    assertTrue(hidden.texts.any { it.text == "; display-only register sketch" })
                    advance(0.25)
                }
                scene.setState(ScreenPhase.ADVERTISING, name, false, RogHardlineScene.StatusMode.RUNNING, now)
                val restored = draw()
                assertStatusPhasePosition(restored, viewport, prefix + name, 1.25, 144.0)
                val traces = scene.streams.map { it.copy() }
                val portrait = StandbyViewportScale(800, 1280, 1.5f, cssPixels = true)
                val resized = PathRecordingCanvas(bitmap)
                scene.draw(resized, 800f, 1280f, portrait)
                assertStatusPhasePosition(resized, portrait, prefix + name, 1.25, 144.0)
                for (delta in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) scene.update(delta)
                val repeated = draw()
                assertStatusPhasePosition(repeated, viewport, prefix + name, 1.25, 144.0)
                assertEquals(restored.texts.map { it.text }, repeated.texts.map { it.text })
                assertEquals(restored.texts.map { it.x }, repeated.texts.map { it.x })
                assertEquals(traces, scene.streams)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Platform CSS-ease expectations bind actual simultaneous groups, DOM order, paused X, current color and fresh copy. */
    @Test
    fun statusCrossfade_paintsSimultaneousTripleGroupsWithCurrentTruth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            try {
                val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                val viewport = StandbyViewportScale(1600, 1000, 1.5f, cssPixels = true)
                val curve = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
                fun ease(ms: Double, duration: Double = 180.0): Double = curve.getInterpolation((ms / duration).toFloat().coerceIn(0f, 1f)).toDouble()
                fun alpha(value: Double): Int = (value * 255.0).roundToInt()
                fun draw(): PathRecordingCanvas = PathRecordingCanvas(bitmap).also { scene.draw(it, 1600f, 1000f, viewport) }
                val name = "  <TV & \"Desk\">  "
                val freshName = "  <Receiver & \"客廳\" / TV>  "
                val prefix = "Windows: press Win + K · Choose "
                val ready = "Press the broadcast button to start receiving"
                val frozen = "Receiver unavailable · Check settings"
                val failure = "Connection failed · Press Win + K to retry"
                scene.setState(ScreenPhase.ADVERTISING, name, false, RogHardlineScene.StatusMode.RUNNING, 0L)
                assertStatusGroups(draw(), listOf(prefix + name to 255))
                scene.update(0.7)
                val traces = scene.streams.map { it.copy() }
                scene.setState(ScreenPhase.READY, name, false, RogHardlineScene.StatusMode.RUNNING, 1000L)
                val boundary = draw()
                assertStatusGroups(boundary, listOf(ready to 0, prefix + name to 255))
                val held = assertStatusPhasePosition(boundary, viewport, prefix + name, 1.0, 144.0)
                scene.advanceStatusTo(1045L)
                val e45 = ease(45.0)
                val middle = draw()
                assertStatusGroups(middle, listOf(ready to alpha(e45), prefix + name to alpha(1.0 - e45)))
                assertEquals(held.x, assertStatusPhasePosition(middle, viewport, prefix + name, 1.0, 144.0).x, 0f)
                assertStatusPhasePosition(middle, viewport, ready, 0.045, 148.0)
                scene.setState(ScreenPhase.READY, freshName, false, RogHardlineScene.StatusMode.RUNNING, 1045L)
                val renamed = draw()
                assertStatusGroups(renamed, listOf(ready to alpha(e45), prefix + freshName to alpha(1.0 - e45)))
                assertTrue(renamed.texts.none { it.text == prefix + name })
                assertStatusPhasePosition(renamed, viewport, prefix + freshName, 1.0, 144.0)
                scene.setState(ScreenPhase.FROZEN, freshName, false, RogHardlineScene.StatusMode.RUNNING, 1045L)
                assertStatusGroups(draw(), listOf(ready to alpha(e45), frozen to 0, prefix + freshName to alpha(1.0 - e45)), 0xFFFF6B6B.toInt())
                scene.advanceStatusTo(1065L)
                val outgoingReady = e45 * (1.0 - ease(20.0, 180.0 * e45))
                val incomingFrozen = ease(20.0)
                val outgoingWaiting = 1.0 - ease(65.0)
                val triple = draw()
                assertStatusGroups(triple, listOf(ready to alpha(outgoingReady), frozen to alpha(incomingFrozen),
                    prefix + freshName to alpha(outgoingWaiting)), 0xFFFF6B6B.toInt())
                assertTrue(triple.groups.all { it.alpha > 0 })
                scene.setState(ScreenPhase.FROZEN, freshName, true, RogHardlineScene.StatusMode.RUNNING, 1065L)
                val failed = draw()
                assertStatusGroups(failed, listOf(ready to alpha(outgoingReady), frozen to alpha(incomingFrozen),
                    prefix + freshName to alpha(outgoingWaiting), failure to 0), 0xFFFF6B6B.toInt())
                val repeated = draw()
                assertEquals(failed.groups.map { it.alpha }, repeated.groups.map { it.alpha })
                assertEquals(failed.texts.map { it.x }, repeated.texts.map { it.x })
                scene.setState(ScreenPhase.READY, freshName, false, RogHardlineScene.StatusMode.RUNNING, 1065L)
                assertStatusGroups(draw(), listOf(ready to alpha(outgoingReady), frozen to alpha(incomingFrozen),
                    prefix + freshName to alpha(outgoingWaiting), failure to 0))
                scene.setState(ScreenPhase.ADVERTISING, "   ", false, RogHardlineScene.StatusMode.RUNNING, 1065L)
                assertStatusGroups(draw(), emptyList())
                scene.advanceStatusTo(1465L)
                scene.setState(ScreenPhase.ADVERTISING, freshName, false, RogHardlineScene.StatusMode.RUNNING, 1465L)
                val restored = draw()
                assertStatusGroups(restored, listOf(prefix + freshName to 255))
                assertStatusPhasePosition(restored, viewport, prefix + freshName, 1.4, 144.0)
                scene.setState(ScreenPhase.CONNECTED, freshName, false, RogHardlineScene.StatusMode.RUNNING, 1465L)
                assertStatusGroups(draw(), emptyList())
                assertEquals(traces, scene.streams)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Independent PathInterpolator values test repeated reversal shortening and the separate visibility deadline. */
    @Test
    fun statusCrossfade_reversesFromCurrentAlphaWithoutRestartingVisibility() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            try {
                val viewport = StandbyViewportScale(1600, 1000, 1f, cssPixels = true)
                val curve = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
                fun ease(ms: Double, duration: Double = 180.0): Double = curve.getInterpolation((ms / duration).toFloat().coerceIn(0f, 1f)).toDouble()
                fun alpha(value: Double): Int = (value * 255.0).roundToInt()
                fun draw(scene: RogHardlineScene): PathRecordingCanvas = PathRecordingCanvas(bitmap).also { scene.draw(it, 1600f, 1000f, viewport) }
                val ready = "Press the broadcast button to start receiving"
                val arming = "Starting · Not ready"
                val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
                scene.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 100L)
                scene.advanceStatusTo(160L)
                val e60 = ease(60.0)
                val expected160 = listOf(ready to alpha(1.0 - e60), arming to alpha(e60))
                assertStatusGroups(draw(scene), expected160)
                scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 160L)
                assertStatusGroups(draw(scene), expected160)
                scene.advanceStatusTo(180L)
                val q = ease(20.0, 180.0 * e60)
                val ready180 = 1.0 - e60 + e60 * q
                val arming180 = e60 * (1.0 - q)
                val expected180 = listOf(ready to alpha(ready180), arming to alpha(arming180))
                assertStatusGroups(draw(scene), expected180)
                scene.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 180L)
                assertStatusGroups(draw(scene), expected180)
                val factor2 = kotlin.math.abs(q * e60 + 1.0 - e60)
                scene.advanceStatusTo(200L)
                val r = ease(20.0, 180.0 * factor2)
                assertStatusGroups(draw(scene), listOf(ready to alpha(ready180 * (1.0 - r)),
                    arming to alpha(arming180 + (1.0 - arming180) * r)))
                scene.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 200L)
                scene.advanceStatusTo(360L)
                assertStatusGroups(draw(scene), listOf(arming to 255))
                val short = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                short.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
                short.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 100L)
                short.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 160L)
                short.advanceStatusTo(300L)
                assertStatusGroups(draw(short), listOf(ready to 255, arming to 0))
                short.advanceStatusTo(339L)
                assertStatusGroups(draw(short), listOf(ready to 255, arming to 0))
                short.advanceStatusTo(340L)
                assertStatusGroups(draw(short), listOf(ready to 255))
                val normal = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
                normal.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
                normal.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 100L)
                normal.advanceStatusTo(280L)
                assertStatusGroups(draw(normal), listOf(arming to 255))
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Verifies Canvas Row 3 EN/zh-TW localization, raw placeholder handling, suppression, and non-resetting config refresh. */
    @Test
    fun channelStatus_localizesRowThreeAcrossEnAndZhTwWithoutFabricatingState() {
        val rawName = "100% 客廳"
        val expectedByLocale = listOf(
            Triple(
                Locale.ENGLISH,
                mapOf(
                    ScreenPhase.READY to "Press the broadcast button to start receiving",
                    ScreenPhase.ARMING to "Starting · Not ready",
                    ScreenPhase.WIFI_PAUSED to "Wi-Fi is off · Please turn on Wi-Fi to broadcast",
                    ScreenPhase.FROZEN to "Receiver unavailable · Check settings",
                    ScreenPhase.ADVERTISING to "Windows: press Win + K · Choose $rawName",
                    ScreenPhase.CONNECTING to "Connecting · Please wait",
                ),
                "Connection failed · Press Win + K to retry",
            ),
            Triple(
                Locale.TRADITIONAL_CHINESE,
                mapOf(
                    ScreenPhase.READY to "按下廣播按鈕以開始接收",
                    ScreenPhase.ARMING to "啟動中 · 尚未就緒",
                    ScreenPhase.WIFI_PAUSED to "Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播",
                    ScreenPhase.FROZEN to "接收不可用 · 請檢查設定",
                    ScreenPhase.ADVERTISING to "Windows 按 Win + K，選擇「$rawName」",
                    ScreenPhase.CONNECTING to "正在連接 · 請稍候",
                ),
                "連接未成功 · 按 Win + K 重試",
            ),
        )
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            // Exercise the original localization defect before comparing fallback selection in EN.
            for ((locale, phaseCopy, failureCopy) in expectedByLocale.asReversed()) {
                val steadyClock = AtomicLong(1000L)
                lateinit var localizedContext: Context
                lateinit var view: HardlineSceneView
                try {
                    scenario.onActivity { activity ->
                        localizedContext = activity.createConfigurationContext(
                            Configuration(activity.resources.configuration).apply {
                                setLocale(locale)
                                fontScale = 1f
                            },
                        )
                        view = HardlineSceneView(localizedContext)
                        view.clockUptimeMillis = steadyClock::get
                        activity.contentRoot.addView(view, ViewGroup.LayoutParams(800, 1280))
                    }
                    awaitForegroundLayout(scenario, view)
                    scenario.onActivity {
                        withRecordedScene(view) { canvas ->
                            assertStatusGroups(canvas, emptyList())
                            assertTrue(canvas.texts.any { it.text == "; display-only register sketch" })
                            assertTrue(canvas.texts.any { it.text == "/*" })
                        }
                        for (blank in listOf("", "   ")) {
                            view.setHostPresentation(ScreenPhase.ADVERTISING, blank, false, true, true, false, false)
                            withRecordedScene(view) { canvas -> assertStatusGroups(canvas, emptyList()) }
                        }
                        for ((phase, expectedText) in phaseCopy) {
                            val expectedColor = if (phase == ScreenPhase.FROZEN) 0xFFFF6B6B.toInt() else 0xFFF2F2F2.toInt()
                            view.setHostPresentation(phase, rawName, false, true, true, false, false)
                            // These assertions observe settled text; the separate refresh case observes mid-fade.
                            deliverStatusFrame(view, steadyClock, steadyClock.get() + 180L)
                            withRecordedScene(view) { canvas ->
                                assertStatusGroups(canvas, listOf(expectedText to 255), expectedColor)
                                val statusPaint = canvas.texts.first { it.paint.typeface?.isBold == true }.paint
                                assertEquals(
                                    localizedContext.resources.configuration.locales.toLanguageTags(),
                                    statusPaint.textLocales.toLanguageTags(),
                                )
                            }
                        }
                        for ((failurePhase, name) in listOf(
                            ScreenPhase.READY to "",
                            ScreenPhase.ADVERTISING to rawName,
                            ScreenPhase.CONNECTED to rawName,
                        )) {
                            view.setHostPresentation(failurePhase, name, true, true, true, false, false)
                            deliverStatusFrame(view, steadyClock, steadyClock.get() + 180L)
                            withRecordedScene(view) { canvas ->
                                assertStatusGroups(canvas, listOf(failureCopy to 255), 0xFFFF6B6B.toInt())
                            }
                        }
                        view.setHostPresentation(ScreenPhase.CONNECTED, rawName, false, true, true, false, false)
                        withRecordedScene(view) { canvas -> assertStatusGroups(canvas, emptyList()) }
                    }
                } finally {
                    scenario.onActivity { it.contentRoot.removeAllViews() }
                }
            }

            // Configuration refresh updates active + cached outgoing copies and paint locales without resetting tracks or clocks.
            val clock = AtomicLong(1000L)
            val reads = AtomicInteger()
            lateinit var enConfig: Configuration
            lateinit var zhConfig: Configuration
            lateinit var view: HardlineSceneView
            try {
                scenario.onActivity { activity ->
                    enConfig = Configuration(activity.resources.configuration).apply {
                        setLocale(Locale.ENGLISH)
                        fontScale = 1f
                    }
                    zhConfig = Configuration(activity.resources.configuration).apply {
                        setLocale(Locale.TRADITIONAL_CHINESE)
                        fontScale = 1f
                    }
                    view = HardlineSceneView(activity.createConfigurationContext(enConfig))
                    view.clockUptimeMillis = {
                        reads.incrementAndGet()
                        clock.get()
                    }
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(800, 1280))
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity {
                    val readsBefore = reads.get()
                    val callbacksBefore = view.animationCallbackCount
                    val scheduledCallback = sceneFrameCallback(view)
                    view.dispatchConfigurationChanged(zhConfig)
                    assertNull(statusModel(view).statusText)
                    assertEquals(readsBefore, reads.get())
                    assertEquals(callbacksBefore, view.animationCallbackCount)
                    assertTrue(sceneFrameCallback(view) === scheduledCallback)
                    withRecordedScene(view) { canvas -> assertStatusGroups(canvas, emptyList()) }

                    view.dispatchConfigurationChanged(enConfig)
                    clock.set(1000L)
                    view.setHostPresentation(ScreenPhase.ADVERTISING, rawName, false, true, true, false, false)
                    deliverStatusFrame(view, clock, 1040L)
                    clock.set(1040L)
                    view.setHostPresentation(ScreenPhase.READY, rawName, false, true, true, false, false)
                    deliverStatusFrame(view, clock, 1130L)
                    val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f).getInterpolation(90f / 180f)
                    val enWaiting = "Windows: press Win + K · Choose $rawName"
                    val enReady = "Press the broadcast button to start receiving"
                    withRecordedScene(view) { canvas ->
                        assertStatusGroups(
                            canvas,
                            listOf(
                                enReady to (ease * 255f).roundToInt(),
                                enWaiting to ((1f - ease) * 255f).roundToInt(),
                            ),
                        )
                    }

                    val model = statusModel(view)
                    val waitingElapsedBefore = statusValue(model, "WAITING", "elapsedSeconds")
                    val readyElapsedBefore = statusValue(model, "READY", "elapsedSeconds")
                    val waitingAlphaBefore = statusValue(model, "WAITING", "alpha")
                    val readyAlphaBefore = statusValue(model, "READY", "alpha")
                    val readsBeforeRefresh = reads.get()
                    val callbacksBeforeRefresh = view.animationCallbackCount
                    val callbackBeforeRefresh = sceneFrameCallback(view)

                    view.dispatchConfigurationChanged(zhConfig)

                    assertEquals(readsBeforeRefresh, reads.get())
                    assertEquals(callbacksBeforeRefresh, view.animationCallbackCount)
                    assertTrue(sceneFrameCallback(view) === callbackBeforeRefresh)
                    assertEquals(waitingElapsedBefore, statusValue(model, "WAITING", "elapsedSeconds"), 0.0)
                    assertEquals(readyElapsedBefore, statusValue(model, "READY", "elapsedSeconds"), 0.0)
                    assertEquals(waitingAlphaBefore, statusValue(model, "WAITING", "alpha"), 0.0)
                    assertEquals(readyAlphaBefore, statusValue(model, "READY", "alpha"), 0.0)

                    val zhWaiting = "Windows 按 Win + K，選擇「$rawName」"
                    val zhReady = "按下廣播按鈕以開始接收"
                    withRecordedScene(view) { canvas ->
                        assertStatusGroups(
                            canvas,
                            listOf(
                                zhReady to (ease * 255f).roundToInt(),
                                zhWaiting to ((1f - ease) * 255f).roundToInt(),
                            ),
                        )
                        for (text in canvas.texts.filter { it.paint.typeface?.isBold == true }) {
                            assertEquals(zhConfig.locales.toLanguageTags(), text.paint.textLocales.toLanguageTags())
                        }
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Binds group alpha/order/restore intervals to actual text; non-status draws cannot enter these layers. */
    private fun assertStatusGroups(canvas: PathRecordingCanvas, expected: List<Pair<String, Int>>, color: Int = 0xFFF2F2F2.toInt()) {
        assertEquals(expected.size, canvas.groups.size)
        assertEquals(1, canvas.saveCount)
        // A full View draw clips the 118 study before the rotated channel clips.
        val channelMatrix = canvas.texts.first { it.text == "; display-only register sketch" }.matrix
        val upperClip = canvas.clips.first { it.matrix == channelMatrix }
        canvas.groups.zip(expected).forEach { (group, value) ->
            val end = requireNotNull(group.restoreOrder)
            assertEquals(value.second.toFloat(), group.alpha.toFloat(), 1f)
            assertEquals(upperClip.bounds, group.bounds)
            assertEquals(upperClip.matrix, group.matrix)
            val texts = canvas.texts.filter { it.order >= group.startOrder && it.order < end }
            assertTrue(texts.isNotEmpty())
            assertEquals(0, texts.size % 8)
            texts.forEach {
                assertEquals(value.first, it.text)
                assertTrue(it.paint.typeface?.isBold == true)
                assertEquals(color, it.paint.color)
                assertEquals(255, it.paint.alpha)
                assertEquals(group.matrix, it.matrix)
            }
            assertTrue(canvas.lines.none { it.order >= group.startOrder && it.order < end })
            assertTrue(canvas.rects.none { it.order >= group.startOrder && it.order < end })
            assertTrue(canvas.paths.none { it.order >= group.startOrder && it.order < end })
        }
        assertEquals(canvas.texts.filter { it.paint.typeface?.isBold == true }.size,
            canvas.groups.sumOf { group -> canvas.texts.count { it.order >= group.startOrder && it.order < requireNotNull(group.restoreOrder) } })
    }

    /** @return Selected emitted Row 3 layer, checked against supplied phase time and native phrase stride. */
    private fun assertStatusPhasePosition(
        canvas: PathRecordingCanvas,
        viewport: StandbyViewportScale,
        expectedText: String,
        phaseSeconds: Double,
        durationSeconds: Double,
    ): RecordedText {
        val draws = canvas.texts.filter { it.paint.typeface?.isBold == true && it.text == expectedText }
        assertTrue("Missing status: $expectedText", draws.isNotEmpty())
        assertEquals(0, draws.size % 8)
        val first = draws.first()
        val stride = first.paint.measureText(expectedText) + viewport.px(32f)
        val segment = 8f * stride
        val start = canvas.clips.first().bounds.left
        val expectedX = start - segment + ((phaseSeconds % durationSeconds) / durationSeconds * segment).toFloat() + viewport.px(16f)
        assertEquals("phaseSeconds=$phaseSeconds duration=$durationSeconds text=$expectedText", expectedX, first.x, 0.01f)
        assertEquals(canvas.clips.first().matrix, first.matrix)
        draws.forEachIndexed { index, draw ->
            assertEquals(expectedText, draw.text)
            assertEquals(first.x + index * stride, draw.x, 0.05f)
            assertEquals(first.y, draw.y, 0f)
            assertEquals(first.matrix, draw.matrix)
        }
        return first
    }

    /** Slice mapping, 118 math, and section frames match prototype vectors. */
    @Test
    fun sectionExtrusionMath_matchesPrototypeVectors() {
        // 1080x2400 portrait: scale = max(1080/1600, 2400/1000) = 2.4,
        // offsetX = (1080 - 1600*2.4)/2 = -1380, offsetY = (2400 - 1000*2.4)/2 = 0.
        val slice = HardlineSceneView.sliceTransformFor(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
        assertEquals(2.4, slice.scale, TOLERANCE)
        assertEquals(-1380.0, slice.offsetX, TOLERANCE)
        assertEquals(0.0, slice.offsetY, TOLERANCE)

        // 2400x1080 landscape: scale = max(2400/1600, 1080/1000) = 1.5,
        // offsetX = (2400 - 1600*1.5)/2 = 0, offsetY = (1080 - 1000*1.5)/2 = -210.
        val landscape = HardlineSceneView.sliceTransformFor(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
        assertEquals(1.5, landscape.scale, TOLERANCE)
        assertEquals(0.0, landscape.offsetX, TOLERANCE)
        assertEquals(-210.0, landscape.offsetY, TOLERANCE)

        val pitch = HardlineSceneView.underlayPitch(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
        assertEquals(EXPECTED_PITCH, pitch, TOLERANCE)
        val phase = HardlineSceneView.underlayPhase(FIXED_TIME, pitch)
        assertEquals(sin(FIXED_TIME * 0.17) * pitch * 0.12, phase, TOLERANCE)
        val depth = HardlineSceneView.extrusionDepthMax(PORTRAIT_UNIT, FIXED_TIME)
        assertEquals(PORTRAIT_UNIT * (0.13 + 0.02 * sin(FIXED_TIME * 0.26)), depth, TOLERANCE)
        val angle = HardlineSceneView.extrusionAngle(FALLBACK_ANGLE, FIXED_TIME)
        assertEquals(FALLBACK_ANGLE + 0.04 * sin(FIXED_TIME * 0.22), angle, TOLERANCE)

        val frame = HardlineSceneView.sectionFrame(FRAME_INDEX, FIXED_TIME, depth)
        val progress = (FRAME_INDEX.toDouble() / 4 + FIXED_TIME * 0.075) % 1.0
        assertEquals(progress, frame.progress, TOLERANCE)
        assertEquals(progress * depth, frame.depth, TOLERANCE)
        assertEquals(1.0 - 0.15 * progress, frame.taper, TOLERANCE)
        assertEquals(sin(progress * PI) * 0.38, frame.opacity, TOLERANCE)

        val rails = HardlineSceneView.screenRailsFor(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
        assertEquals(2, rails.size)
        // Portrait 1080x2400: scale 2.4, gap 32, startY 2016, reach 432.
        assertEquals(32.0, rails[1].first.second - rails[0].first.second, TOLERANCE)
        assertEquals(-32.0, rails[0].first.first, TOLERANCE)
        assertEquals(2048.0, rails[0].first.second, TOLERANCE)
        assertEquals(432.0, rails[0].second.first, TOLERANCE)
        assertEquals(1584.0, rails[0].second.second, TOLERANCE)

        val center = HardlineSceneView.polygonCenter(
            listOf(Pair(0.0, 0.0), Pair(4.0, 0.0), Pair(4.0, 4.0), Pair(0.0, 4.0)),
        )
        assertEquals(2.0, center.first, TOLERANCE)
        assertEquals(2.0, center.second, TOLERANCE)
    }

    /** The existing clock attributes a between-frame switch exactly; status is raw while physics stays capped. */
    @Test
    fun statusClock_attributesBoundariesAndLongGapsWithoutUncappingPhysics() {
        val clock = AtomicLong(1000L)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var view: HardlineSceneView
            try {
                scenario.onActivity { activity ->
                    view = HardlineSceneView(activity)
                    view.clockUptimeMillis = clock::get
                    view.setHardlineState(ScreenPhase.READY, false, "Studio", false)
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity {
                    deliverStatusFrame(view, clock, 1000L)
                    deliverStatusFrame(view, clock, 1040L)
                    clock.set(1060L)
                    view.setHardlineState(ScreenPhase.ARMING, false, "Studio", false)
                    deliverStatusFrame(view, clock, 1100L)
                    val model = statusModel(view)
                    assertEquals(0.06, statusValue(model, "READY", "elapsedSeconds"), 1e-9)
                    assertEquals(0.04, statusValue(model, "ARMING", "elapsedSeconds"), 1e-9)
                    deliverStatusFrame(view, clock, 1500L)
                    assertEquals(0.44, statusValue(model, "ARMING", "elapsedSeconds"), 1e-9)
                    // Physics frames are 40 + 60 + min(400, 100) ms; the status commit is not a physics frame.
                    val expectedPhysicsSeconds = (40 + 60 + minOf(400, 100)) / 1000.0
                    assertEquals(expectedPhysicsSeconds, view.animationElapsedSeconds, 1e-9)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = PathRecordingCanvas(bitmap)
                        view.draw(canvas)
                        assertEquals(listOf(255), canvas.groups.map { it.alpha })
                        assertTrue(canvas.texts.filter { it.paint.typeface?.isBold == true }
                            .all { it.text == "Starting · Not ready" })
                    } finally { bitmap.recycle() }
                    val width = view.width
                    val height = view.height
                    val traces = model.streams.map { it.copy() }
                    clock.set(1600L)
                    view.layout(0, 0, 0, 0)
                    assertFalse(view.animationFrameScheduled)
                    assertEquals(RogHardlineScene.StatusMode.RUNNING, statusMode(model))
                    clock.set(2000L)
                    view.layout(0, 0, width, height)
                    assertEquals(0.94, statusValue(model, "ARMING", "elapsedSeconds"), 1e-9)
                    deliverStatusFrame(view, clock, 2000L)
                    assertEquals(expectedPhysicsSeconds, view.animationElapsedSeconds, 1e-9)
                    assertEquals(traces, model.streams)
                    clock.set(2100L)
                    view.visibility = View.INVISIBLE
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(model))
                    clock.set(2500L)
                    view.visibility = View.VISIBLE
                    assertEquals(1.04, statusValue(model, "ARMING", "elapsedSeconds"), 1e-9)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Generated self-GONE is media suspension, but direct/ancestor/window/detach gates still snap and dominate. */
    @Test
    fun statusContext_mediaIsLazyWhileExternalStopsSnapAndWin() {
        val clock = AtomicLong(1000L)
        val reads = AtomicInteger()
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var view: HardlineSceneView
            var heldReads = 0
            var heldCallbacks = 0L
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    view = HardlineSceneView(activity)
                    view.clockUptimeMillis = { reads.incrementAndGet(); clock.get() }
                    view.setHostPresentation(ScreenPhase.READY, "Studio", false, true, true, false, false)
                    parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                    activity.contentRoot.addView(parent, ViewGroup.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity {
                    clock.set(1100L)
                    view.setHostPresentation(ScreenPhase.ARMING, "Studio", false, true, true, false, false)
                    clock.set(1145L)
                    view.setHostPresentation(ScreenPhase.ARMING, "Studio", false, true, true, true, false)
                    view.visibility = View.GONE
                    assertEquals(RogHardlineScene.StatusMode.MEDIA_PAUSED, statusMode(statusModel(view)))
                    assertFalse(view.animationFrameScheduled)
                }
                awaitVsyncs(2)
                scenario.onActivity { heldReads = reads.get(); heldCallbacks = view.animationCallbackCount }
                clock.set(1190L)
                awaitVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    assertEquals(heldCallbacks, view.animationCallbackCount)
                    view.setHostPresentation(ScreenPhase.ARMING, "Studio", false, true, true, false, false)
                    view.visibility = View.VISIBLE
                    val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f).getInterpolation(90f / 180f)
                    withRecordedScene(view) { canvas ->
                        assertStatusGroups(canvas, listOf(
                            "Press the broadcast button to start receiving" to ((1f - ease) * 255f).roundToInt(),
                            "Starting · Not ready" to (ease * 255f).roundToInt(),
                        ))
                    }
                    assertEquals(heldCallbacks, view.animationCallbackCount)
                    assertEquals(0.045, statusValue(statusModel(view), "ARMING", "elapsedSeconds"), 1e-9)
                    // Re-enter media at the same timestamp to retain the original fade and phase credit.
                    view.setHostPresentation(ScreenPhase.ARMING, "Studio", false, true, true, true, false)
                    view.visibility = View.GONE
                    assertFalse(view.animationFrameScheduled)
                    heldReads = reads.get()
                }
                clock.set(1590L)
                awaitVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    assertEquals(heldCallbacks, view.animationCallbackCount)
                    view.setHostPresentation(ScreenPhase.ARMING, "Studio", false, true, true, false, false)
                    view.visibility = View.VISIBLE
                    val model = statusModel(view)
                    assertEquals(0.045, statusValue(model, "ARMING", "elapsedSeconds"), 1e-9)
                    assertEquals(1.0, statusValue(model, "ARMING", "alpha"), 0.0)
                    clock.set(1645L)
                    view.setHostPresentation(ScreenPhase.FROZEN, "Studio", false, true, true, false, false)
                    clock.set(1690L)
                    view.setProjecting(true)
                    assertEquals(1.0, statusValue(model, "FROZEN", "alpha"), 0.0)
                    assertEquals(0.0, statusValue(model, "ARMING", "alpha"), 0.0)
                    parent.visibility = View.GONE
                    clock.set(2000L)
                    view.setProjecting(false)
                    assertFalse(view.animationFrameScheduled)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(model))
                    parent.visibility = View.VISIBLE
                    assertEquals(0.045, statusValue(model, "FROZEN", "elapsedSeconds"), 1e-9)
                    for ((track, copy) in listOf(
                        "FROZEN" to "Receiver unavailable · Check settings",
                        "FAILURE" to "Connection failed · Press Win + K to retry",
                    )) {
                        val failure = track == "FAILURE"
                        val seconds = if (failure) 0.0 else 0.045
                        view.setHostPresentation(ScreenPhase.FROZEN, "Studio", failure, true, true, false, true)
                        assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(model))
                        assertEquals(copy, model.statusText)
                        assertEquals(1.0, statusValue(model, track, "alpha"), 0.0)
                        assertEquals(seconds, statusValue(model, track, "elapsedSeconds"), 1e-9)
                        assertTrue("Host projection must not broaden the non-status physics gate", view.animationFrameScheduled)
                        withRecordedScene(view) { canvas ->
                            assertStatusGroups(canvas, emptyList())
                            assertTrue(canvas.texts.none { it.text == copy })
                            assertTrue(canvas.texts.any { it.text == "; display-only register sketch" })
                            assertTrue(canvas.texts.any { it.text == "/*" })
                            assertTrue(canvas.paths.any { it.paint.color == HardlineSceneView.EDGE_COLOR })
                        }
                        view.setHostPresentation(ScreenPhase.FROZEN, "Studio", failure, true, true, false, false)
                        assertEquals(seconds, statusValue(model, track, "elapsedSeconds"), 1e-9)
                        withRecordedScene(view) { canvas ->
                            assertStatusGroups(canvas, listOf(copy to 255), 0xFFFF6B6B.toInt())
                            assertEquals(255, canvas.groups.single().alpha)
                        }
                    }
                    view.setHostPresentation(ScreenPhase.FROZEN, "Studio", false, true, true, true, true)
                    view.visibility = View.GONE
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity {
                    assertTrue(view.windowVisibility != View.VISIBLE)
                    view.setHostPresentation(ScreenPhase.FROZEN, "Studio", false, true, true, true, false)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(statusModel(view)))
                    heldReads = reads.get()
                }
                clock.set(3000L)
                awaitVsyncs(2)
                scenario.onActivity { assertEquals(heldReads, reads.get()) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity {
                    assertEquals(RogHardlineScene.StatusMode.MEDIA_PAUSED, statusMode(statusModel(view)))
                    parent.removeView(view)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(statusModel(view)))
                    clock.set(4000L)
                    parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                    assertEquals(RogHardlineScene.StatusMode.MEDIA_PAUSED, statusMode(statusModel(view)))
                    val before = reads.get()
                    view.setHostPresentation(ScreenPhase.WIFI_PAUSED, "Studio", false, true, true, false, false)
                    assertEquals(before + 1, reads.get())
                    view.visibility = View.VISIBLE
                    assertEquals(RogHardlineScene.StatusMode.RUNNING, statusMode(statusModel(view)))
                    assertEquals(0.0, statusValue(statusModel(view), "WIFI_PAUSED", "elapsedSeconds"), 0.0)
                    deliverStatusFrame(view, clock, 4040L)
                    assertEquals(0.04, statusValue(statusModel(view), "WIFI_PAUSED", "elapsedSeconds"), 1e-9)
                    view.setProjecting(true)
                    view.setHostPresentation(ScreenPhase.FROZEN, "Studio", false, true, false, false, false)
                    view.visibility = View.VISIBLE
                    view.setProjecting(false)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, statusMode(statusModel(view)))
                    assertFalse(view.animationFrameScheduled)
                    assertEquals(0.045, statusValue(statusModel(view), "FROZEN", "elapsedSeconds"), 1e-9)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** A standalone explicit stop settles Row 3 without hiding it. */
    @Test
    fun statusStop_directPublishesSettledVisibleDraw() = assertVisibleStopPublishes(throughHost = false)

    /** The real Host runtime entry must likewise publish even after animation stops. */
    @Test
    fun statusStop_hostRuntimePublishesSettledVisibleDraw() = assertVisibleStopPublishes(throughHost = true)

    /** Observes attached publication from a quiet partial draw before recording settled paint. */
    private fun assertVisibleStopPublishes(throughHost: Boolean) {
        val clock = AtomicLong(1000L)
        val reads = AtomicInteger()
        val partial = CountDownLatch(1)
        val published = CountDownLatch(1)
        val label = if (throughHost) "Host runtime stop" else "direct stop"
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var view: HardlineSceneView
            var ownedView: HardlineSceneView? = null
            var host: BigStandbyHost? = null
            val legacyFrame = BigStandbyHost::class.java.getDeclaredField("legacyFrameCallback").apply { isAccessible = true }
            fun renderPhase(phase: ScreenPhase) {
                val currentHost = host
                if (currentHost == null) {
                    view.setHardlineState(phase, false, "Studio", false)
                } else {
                    currentHost.render(StandbyThemeId.HARDLINE, StandbyForegroundState(phase, false,
                        receiverName = "Studio", copyMotionAllowed = false, isProjecting = false), "Ready", "OFF", "")
                }
            }
            fun stop() {
                host?.setRuntimeVisible(false) ?: view.setProjecting(true)
            }
            fun observation() = Triple(view.drawEvidence.drawCount, view.animationCallbackCount, reads.get())
            try {
                scenario.onActivity { activity ->
                    if (throughHost) {
                        val mountedHost = BigStandbyHost(activity, StandbyForegroundCallbacks())
                        mountedHost.clockUptimeMillis = { 1000L }
                        host = mountedHost
                        view = (0 until mountedHost.childCount).map(mountedHost::getChildAt)
                            .filterIsInstance<HardlineSceneView>().single()
                    } else {
                        view = HardlineSceneView(activity)
                    }
                    ownedView = view
                    view.clockUptimeMillis = { reads.incrementAndGet(); clock.get() }
                    renderPhase(ScreenPhase.READY)
                    host?.setRuntimeVisible(true)
                    activity.contentRoot.addView(host ?: view, ViewGroup.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, view)
                var partialIncoming = 0f
                var partialOutgoing = 0f
                scenario.onActivity {
                    clock.set(1100L)
                    renderPhase(ScreenPhase.ARMING)
                    val model = statusModel(view)
                    view.onFrameDrawn = {
                        val incoming = statusValue(model, "ARMING", "alpha").toFloat()
                        if (incoming > 0f && incoming < 1f) {
                            partialIncoming = incoming
                            partialOutgoing = statusValue(model, "READY", "alpha").toFloat()
                            view.onFrameDrawn = null
                            partial.countDown()
                        }
                    }
                    clock.set(1145L)
                }
                assertTrue("$label: no partial attached draw", partial.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                scenario.onActivity {
                    val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f).getInterpolation(45f / 180f)
                    assertEquals(ease, partialIncoming, 0.002f)
                    assertEquals(1f - ease, partialOutgoing, 0.002f)
                    // Park queued producers without changing ownership or status mode; drain their last invalidations.
                    assertTrue(view.animationFrameScheduled)
                    val choreographer = Choreographer.getInstance()
                    choreographer.removeFrameCallback(requireNotNull(sceneFrameCallback(view)))
                    host?.let { choreographer.removeFrameCallback(legacyFrame.get(it) as Choreographer.FrameCallback) }
                }
                awaitVsyncs(2)
                var quiet = Triple(0, 0L, 0)
                var readsAfterStop = 0
                var repeatedStopBaseline = quiet
                var bounds = Rect()
                scenario.onActivity { quiet = observation() }
                awaitVsyncs(3)
                scenario.onActivity { activity ->
                    assertEquals("$label: pending draws or scene clock work", quiet, observation())
                    assertTrue(view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE)
                    assertFalse(activity.contentRoot.isLayoutRequested)
                    assertFalse(view.isLayoutRequested)
                    bounds = Rect(view.left, view.top, view.right, view.bottom)
                    view.onFrameDrawn = {
                        view.onFrameDrawn = null
                        published.countDown()
                    }
                    stop()
                    readsAfterStop = reads.get()
                    assertFalse(view.animationFrameScheduled)
                    assertNull(sceneFrameCallback(view))
                    host?.let { assertNull(legacyFrame.get(it)) }
                }
                assertTrue("$label: stop did not publish a new attached draw", published.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                clock.set(2000L)
                awaitVsyncs(3)
                scenario.onActivity {
                    assertEquals("$label: expected exactly one draw and no polling",
                        Triple(quiet.first + 1, quiet.second, readsAfterStop), observation())
                    assertTrue(view.isShown)
                    assertEquals(bounds, Rect(view.left, view.top, view.right, view.bottom))
                    withRecordedScene(view) { canvas ->
                        assertStatusGroups(canvas, listOf("Starting · Not ready" to 255))
                        assertEquals(255, canvas.groups.single().alpha)
                    }
                    stop()
                    if (!throughHost) renderPhase(ScreenPhase.ARMING)
                    repeatedStopBaseline = observation()
                }
                awaitVsyncs(3)
                scenario.onActivity {
                    assertEquals("$label: identical stopped input must not publish again", repeatedStopBaseline, observation())
                    assertFalse(view.animationFrameScheduled)
                    assertNull(sceneFrameCallback(view))
                }
            } finally {
                scenario.onActivity { activity ->
                    ownedView?.onFrameDrawn = null
                    (host ?: ownedView)?.let(activity.contentRoot::removeView)
                }
            }
        }
    }

    /** Records a visible full View after the attachment or media-resume assertions have been observed. */
    private fun withRecordedScene(view: HardlineSceneView, verify: (PathRecordingCanvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            verify(PathRecordingCanvas(bitmap).also(view::draw))
        } finally {
            bitmap.recycle()
        }
    }

    /** @return The existing scene callback without a production test hook. */
    private fun sceneFrameCallback(view: HardlineSceneView): Choreographer.FrameCallback? =
        HardlineSceneView::class.java.getDeclaredField("frameCallback").apply { isAccessible = true }
            .get(view) as? Choreographer.FrameCallback

    /** Delivers the existing callback at a fixed clock input without creating a second frame loop. */
    private fun deliverStatusFrame(view: HardlineSceneView, clock: AtomicLong, time: Long) {
        clock.set(time)
        val callback = requireNotNull(sceneFrameCallback(view))
        Choreographer.getInstance().removeFrameCallback(callback)
        callback.doFrame(0L)
    }

    private fun statusModel(view: HardlineSceneView): RogHardlineScene =
        HardlineSceneView::class.java.getDeclaredField("rogScene").apply { isAccessible = true }.get(view) as RogHardlineScene

    private fun statusMode(model: RogHardlineScene): RogHardlineScene.StatusMode =
        RogHardlineScene::class.java.getDeclaredField("statusMode").apply { isAccessible = true }.get(model) as RogHardlineScene.StatusMode

    /** Reads private model state only for adapter attribution; actual alpha painting is asserted by the recorder. */
    private fun statusValue(model: RogHardlineScene, track: String, property: String): Double {
        val layers = RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(model) as Array<*>
        val layer = requireNotNull(layers.single {
            val value = requireNotNull(it)
            (value.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(value) as Enum<*>).name == track
        })
        return layer.javaClass.getDeclaredField(property).apply { isAccessible = true }.getDouble(layer)
    }

    /** Hidden ancestors, window state, projection, and detach cannot retain or advance frames. */
    @Test
    fun frameLoop_pausesForAncestorWindowDetachAndOverlappingProjection() {
        val clock = AtomicLong(1000L)
        val clockReads = AtomicInteger()
        lateinit var parent: FrameLayout
        lateinit var view: HardlineSceneView
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                parent = FrameLayout(activity)
                view = HardlineSceneView(activity)
                view.clockUptimeMillis = {
                    clockReads.incrementAndGet()
                    clock.get()
                }
                parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                activity.contentRoot.addView(parent, ViewGroup.LayoutParams(-1, -1))
            }
            awaitVsyncs(3)
            clock.addAndGet(50L)
            awaitVsyncs(2)
            var pausedElapsed = 0.0
            var pausedReads = 0
            var pausedCallbacks = 0L
            scenario.onActivity {
                assertTrue(view.animationFrameScheduled)
                assertTrue(view.animationElapsedSeconds >= 0.05)
                parent.visibility = View.GONE
                assertEquals(View.VISIBLE, view.visibility)
                assertFalse(view.isShown)
                assertFalse(view.animationFrameScheduled)
                pausedElapsed = view.animationElapsedSeconds
                pausedReads = clockReads.get()
                pausedCallbacks = view.animationCallbackCount
            }
            clock.addAndGet(100_000L)
            awaitVsyncs(3)
            scenario.onActivity {
                assertEquals(pausedCallbacks, view.animationCallbackCount)
                assertEquals(pausedReads, clockReads.get())
                assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9)
                parent.visibility = View.VISIBLE
                assertTrue(view.animationFrameScheduled)
            }
            awaitVsyncs(2)
            scenario.onActivity {
                assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9)
                view.setProjecting(true)
                assertFalse(view.animationFrameScheduled)
                parent.visibility = View.GONE
                parent.removeView(view)
                pausedReads = clockReads.get()
                pausedCallbacks = view.animationCallbackCount
            }
            clock.addAndGet(100_000L)
            awaitVsyncs(3)
            scenario.onActivity {
                assertEquals(pausedCallbacks, view.animationCallbackCount)
                assertEquals(pausedReads, clockReads.get())
                assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9)
                view.setProjecting(false)
                assertFalse(view.animationFrameScheduled)
                parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                assertFalse(view.animationFrameScheduled)
                parent.visibility = View.VISIBLE
            }
            awaitVsyncs(3)
            scenario.onActivity {
                assertTrue(view.animationFrameScheduled)
                assertTrue(clockReads.get() > pausedReads)
                assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9)
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                assertTrue("The real window must be hidden for this check.", view.windowVisibility != View.VISIBLE)
                assertFalse(view.animationFrameScheduled)
                view.setProjecting(true)
                pausedElapsed = view.animationElapsedSeconds
                pausedReads = clockReads.get()
                pausedCallbacks = view.animationCallbackCount
            }
            clock.addAndGet(100_000L)
            awaitVsyncs(3)
            scenario.onActivity { assertEquals(pausedCallbacks, view.animationCallbackCount) }
            assertEquals(pausedReads, clockReads.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals(View.VISIBLE, view.windowVisibility)
                assertFalse(view.animationFrameScheduled)
                assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9)
                view.setProjecting(false)
            }
            awaitVsyncs(2)
            scenario.onActivity { assertEquals(pausedElapsed, view.animationElapsedSeconds, 1e-9) }
            clock.addAndGet(40L)
            awaitVsyncs(2)
            scenario.onActivity { assertTrue(view.animationElapsedSeconds >= pausedElapsed + 0.04 - 1e-9) }
        }
    }

    /** Attached view draws the 118 background, then stops while projecting. */
    @Test
    fun attachedBackground_rendersStaticSceneThenStopsWhenProjecting() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = HardlineSceneView(activity)
                view.setHostFieldGeometry(headerLowerPx = 96f, bandUpperPx = 320f, bandLowerPx = 560f)
                activity.contentRoot.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitFrames(scenario, FRAME_LATCH_COUNT)
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as HardlineSceneView
                val evidence = view.drawEvidence
                assertTrue("No attached draw observed; evidence unavailable.", evidence.observed)
                assertTrue("Draw count must exceed zero.", evidence.drawCount > 0)
                assertTrue(
                    "Active elapsed time must advance while visible.",
                    evidence.activeElapsedSeconds >= 0.0,
                )
                Log.i(
                    WindowEvidence.LOG_TAG,
                    "hardlineBackground canvasWasHardwareAccelerated=" +
                        evidence.canvasWasHardwareAccelerated +
                        ", draws=" + evidence.drawCount,
                )
                view.setProjecting(true)
                view.visibility = View.GONE
                view.postInvalidateOnAnimation()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var drawsBeforeHide = -1
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as HardlineSceneView
                drawsBeforeHide = view.drawEvidence.drawCount
                view.postInvalidateOnAnimation()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as HardlineSceneView
                val drawsAfterHide = view.drawEvidence.drawCount
                Log.i(
                    WindowEvidence.LOG_TAG,
                    "hardlineBackground hidden drawsBefore=" + drawsBeforeHide +
                        " drawsAfter=" + drawsAfterHide,
                )
                assertTrue(
                    "Hidden view kept drawing; invisible loop must stop.",
                    drawsAfterHide == drawsBeforeHide,
                )
                view.visibility = View.VISIBLE
                view.setProjecting(false)
                view.postInvalidateOnAnimation()
            }
            val capture = WindowEvidence.capture(scenario, "hardline-background")
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
            } finally {
                evidenceBitmap.recycle()
            }
        }
    }

    /** Binds fixture density locally without changing device configuration or font calibration. */
    private fun contextAtDensity(densityDpi: Int): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
            fontScale = 1f
        })
    }

    private data class RecordedPath(val path: Path, val paint: Paint, val order: Int, val matrix: Matrix)
    private data class RecordedText(val text: String, val x: Float, val paint: Paint, val order: Int, val y: Float, val matrix: Matrix)
    private data class RecordedPaint(val paint: Paint, val order: Int, val bounds: RectF, val matrix: Matrix)
    private data class RecordedColor(val color: Int, val order: Int)
    private data class RecordedClip(val bounds: RectF, val matrix: Matrix)
    private data class RecordedGroup(
        val alpha: Int, val bounds: RectF, val matrix: Matrix, val startOrder: Int, val saveCount: Int,
        var restoreOrder: Int? = null,
    )

    private class PathRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val paths = mutableListOf<RecordedPath>()
        val texts = mutableListOf<RecordedText>()
        val lines = mutableListOf<RecordedPaint>()
        val rects = mutableListOf<RecordedPaint>()
        val colors = mutableListOf<RecordedColor>()
        val clips = mutableListOf<RecordedClip>()
        val groups = mutableListOf<RecordedGroup>()
        var drawCount = 0
            private set

        private fun snapshotMatrix(): Matrix = Matrix().also { getMatrix(it) }

        override fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int {
            val count = super.saveLayerAlpha(left, top, right, bottom, alpha)
            groups.add(RecordedGroup(alpha, RectF(left, top, right, bottom), snapshotMatrix(), drawCount, count))
            return count
        }

        override fun restoreToCount(saveCount: Int) {
            groups.filter { it.restoreOrder == null && it.saveCount >= saveCount }.forEach { it.restoreOrder = drawCount }
            super.restoreToCount(saveCount)
        }

        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            clips.add(RecordedClip(RectF(left, top, right, bottom), snapshotMatrix()))
            return super.clipRect(left, top, right, bottom)
        }

        override fun drawColor(color: Int) {
            colors.add(RecordedColor(color, drawCount++))
            super.drawColor(color)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects.add(RecordedPaint(Paint(paint), drawCount++, RectF(left, top, right, bottom), snapshotMatrix()))
            super.drawRect(left, top, right, bottom, paint)
        }

        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
            lines.add(RecordedPaint(Paint(paint), drawCount++, RectF(startX, startY, stopX, stopY), snapshotMatrix()))
            super.drawLine(startX, startY, stopX, stopY, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts.add(RecordedText(text, x, Paint(paint), drawCount++, y, snapshotMatrix()))
            super.drawText(text, x, y, paint)
        }

        override fun drawPath(path: Path, paint: Paint) {
            paths.add(RecordedPath(Path(path), Paint(paint), drawCount++, snapshotMatrix()))
            super.drawPath(path, paint)
        }
    }

    private fun assertMarkBounds(
        paths: List<RecordedPath>,
        slice: HardlineSceneView.SliceTransform,
        originX: Double,
        originY: Double,
    ) {
        val symbolBounds = listOf(
            RectF(26.1f, 23f, 51.8f, 50.8f),
            RectF(56.3f, 23.1f, 81.9f, 50.5f),
            RectF(26.1f, 55.7f, 51.7f, 83.3f),
            RectF(56.2f, 55.7f, 82f, 83.4f),
            RectF(47.8f, 47.5f, 60.2f, 59.4f),
        )
        for ((draw, reference) in paths.zip(symbolBounds)) {
            val bounds = RectF()
            draw.path.computeBounds(bounds, true)
            assertEquals(slice.mapX(originX + reference.left * 1370.0 / 108.0), bounds.left, 0.001f)
            assertEquals(slice.mapY(originY + reference.top * 1370.0 / 108.0), bounds.top, 0.001f)
            assertEquals(slice.mapX(originX + reference.right * 1370.0 / 108.0), bounds.right, 0.001f)
            assertEquals(slice.mapY(originY + reference.bottom * 1370.0 / 108.0), bounds.bottom, 0.001f)
        }
    }

    private fun assertStaticRailsOnly(rail: RecordedPath) {
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(rail.path, rail.paint)
        assertTrue(Color.alpha(bitmap.getPixel(100, 709)) > 0)
        assertTrue(Color.alpha(bitmap.getPixel(100, 748)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(100, 680)))
        bitmap.recycle()
    }

    private fun assertMarkCoversUnderlay(bitmap: Bitmap, diamond: Path, underlays: List<RecordedPath>) {
        val motion = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val motionCanvas = Canvas(motion)
        underlays.forEach { motionCanvas.drawPath(it.path, it.paint) }
        val mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(diamond, Paint().apply { color = Color.WHITE })
        val bounds = RectF()
        diamond.computeBounds(bounds, true)
        var covered = false
        search@ for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                if (Color.alpha(mask.getPixel(x, y)) == 255 && Color.alpha(motion.getPixel(x, y)) > 0 &&
                    bitmap.getPixel(x, y) == ColorUtils.compositeColors(0x18000000, HardlineSceneView.ARM_FILL_COLOR)
                ) {
                    covered = true
                    break@search
                }
            }
        }
        assertTrue("The opaque diamond must cover the 118 underlay.", covered)
        mask.recycle()
        motion.recycle()
    }

    private fun awaitVsyncs(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
            assertTrue("No Choreographer frame arrived.", latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun awaitFrames(scenario: ActivityScenario<StandbyProbeActivity>, count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            scenario.onActivity { activity ->
                val view = activity.contentRoot.getChildAt(0) as? HardlineSceneView
                if (view == null) {
                    latch.countDown()
                } else {
                    view.onFrameDrawn = { latch.countDown() }
                    view.postInvalidateOnAnimation()
                }
            }
            latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun testStatusCopy(): RogHardlineScene.StatusCopy = RogHardlineScene.StatusCopy(
        ready = "Press the broadcast button to start receiving",
        arming = "Starting · Not ready",
        wifiPaused = "Wi-Fi is off · Please turn on Wi-Fi to broadcast",
        frozen = "Receiver unavailable · Check settings",
        connecting = "Connecting · Please wait",
        failure = "Connection failed · Press Win + K to retry",
        formatAdvertising = { name -> "Windows: press Win + K · Choose $name" },
    )

    private companion object {
        const val CORNER_DIAMOND_COLOR = 0xFFE8E8E0.toInt()
        const val TOLERANCE = 1e-9
        const val PORTRAIT_WIDTH = 1080.0
        const val PORTRAIT_HEIGHT = 2400.0
        const val LANDSCAPE_WIDTH = 2400.0
        const val LANDSCAPE_HEIGHT = 1080.0
        const val EXPECTED_PITCH = 72.0
        const val PORTRAIT_UNIT = 1080.0
        const val FIXED_TIME = 3.0
        const val FALLBACK_ANGLE = -0.785
        const val FRAME_INDEX = 2
        const val FRAME_LATCH_COUNT = 3
        const val FRAME_TIMEOUT_SECONDS = 5L
    }
}
