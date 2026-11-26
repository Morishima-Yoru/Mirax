package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import kotlin.math.roundToInt
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native-pixel regressions for approved standby foreground behavior. */
@RunWith(AndroidJUnit4::class)
class StandbyForegroundPrototypeRegressionTest {
    private fun context(densityDpi: Int, fontScale: Float = 1f): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
            this.fontScale = fontScale
            setLocale(Locale.ENGLISH)
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

    private fun bounds(parent: ViewGroup, view: View): Rect = Rect(0, 0, view.width, view.height).also {
        parent.offsetDescendantRectToMyCoords(view, it)
    }

    private fun bounds(parent: ViewGroup, tag: String): Rect =
        bounds(parent, requireNotNull(parent.findViewWithTag<View>(tag)))

    private fun matter(context: Context, receiver: String = "R", clock: String = ""): MatterStandbyForegroundView =
        MatterStandbyForegroundView(context).apply {
            render(
                StandbyForegroundState(ScreenPhase.READY, false, receiverName = receiver, clockText = clock),
                "OK", "unused", "", StandbyForegroundCallbacks(onToggleBroadcast = {}),
            )
        }

    /** Checks native footer slots and isolates the aligned cap at matched physical bounds. */
    @Test
    fun matterSlots_useActualWidthAndNativeDockHalfAcrossDensities() {
        /** Stores one completed main-thread layout sample for assertions on the test thread. */
        data class PlateReading(val width: Int, val areaCap: Int, val naturalWidth: Int, val evidence: String, val valid: Boolean)

        var readings = emptyList<PlateReading>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val one = matter(context(160))
            layout(one, 408, 800)
            val plate = bounds(one, StandbyForegroundTags.MATTER_PLATE)
            val hint = bounds(one, "foreground_hint")
            val dock = bounds(one, StandbyForegroundTags.BROADCAST)
            val oneScale = StandbyViewportScale(one.width, one.height, one.resources.displayMetrics.density)
            assertEquals(dock.top - oneScale.px(16f).toInt(), plate.bottom)
            assertEquals(plate.bottom, hint.bottom)
            assertTrue(hint.right <= plate.left - 12)

            val two = matter(context(320))
            layout(two, 880, 1600)
            val twoPlate = bounds(two, StandbyForegroundTags.MATTER_PLATE)
            val twoDock = bounds(two, StandbyForegroundTags.BROADCAST)
            val twoScale = StandbyViewportScale(two.width, two.height, two.resources.displayMetrics.density)
            val twoEvidence = "density=${two.resources.displayMetrics.density} size=${two.width}x${two.height} " +
                "${StandbyForegroundTags.MATTER_PLATE}=$twoPlate ${StandbyForegroundTags.BROADCAST}=$twoDock"
            assertEquals(twoEvidence, twoScale.roundedPx(96f), twoDock.width())
            assertEquals(twoEvidence, twoDock.top - twoScale.roundedPx(16f), twoPlate.bottom)

            val roomy = matter(context(160), receiver = "")
            layout(roomy, 800, 800)
            val roomyPlate = bounds(roomy, StandbyForegroundTags.MATTER_PLATE)
            val roomyDock = bounds(roomy, StandbyForegroundTags.BROADCAST)
            assertEquals(View.GONE, requireNotNull(roomy.findViewWithTag<View>("foreground_hint")).visibility)
            val roomyScale = StandbyViewportScale(roomy.width, roomy.height, roomy.resources.displayMetrics.density)
            val edge = (800f * 0.03f).coerceIn(roomyScale.px(24f), roomyScale.px(48f)).toInt()
            val corner = (800f * 0.03f).toInt().coerceIn(roomyScale.px(16f).toInt(), roomyScale.px(40f).toInt())
            val slot = 400 - edge - corner - roomyScale.roundedPx(96f) / 2 - roomyScale.px(16f).toInt()
            assertEquals(minOf(slot, roomyScale.px(280f).toInt()), roomyPlate.width())
            assertTrue(kotlin.math.abs(roomyPlate.centerY() - roomyDock.centerY()) <= 1)

            readings = listOf(160 to roomy, 320 to matter(context(320), receiver = "")).map { (dpi, view) ->
                view.applySecurityInsets(Insets.NONE)
                layout(view, 1440, 720)
                val metrics = view.resources.displayMetrics
                val fontScale = view.resources.configuration.fontScale
                val viewport = StandbyViewportScale(view.width, view.height, metrics.density)
                val plateView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MATTER_PLATE))
                val dockView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val plateBounds = bounds(view, plateView)
                val dockBounds = bounds(view, dockView)
                val word = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                val hintVisibility = requireNotNull(view.findViewWithTag<View>("foreground_hint")).visibility
                val clockVisibility = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.CLOCK)).visibility
                val statusLayout = word.layout
                val ellipsis = statusLayout?.takeIf { it.lineCount == 1 }?.getEllipsisCount(0)
                val wordPadding = word.compoundPaddingLeft + word.compoundPaddingRight
                val advance = Layout.getDesiredWidth(word.text, word.paint)
                // Replace the laid-out word's allocation with its uncapped text advance.
                val naturalWidth = (word.parent as View).width - word.width + wordPadding +
                    kotlin.math.ceil(advance.toDouble()).toInt() + plateView.paddingLeft + plateView.paddingRight
                val plateWidth = plateBounds.width()
                val areaCap = viewport.px(280f).toInt()
                val nominalDock = viewport.roundedPx(96f)
                val minimumTarget = kotlin.math.ceil(48.0 * metrics.density).toInt()
                val centerDelta = kotlin.math.abs(plateBounds.centerY() - dockBounds.centerY())
                // The measured aligned right edge already reserves the active corner and scene edge.
                val gap = viewport.px(16f).toInt()
                val room = plateBounds.right - dockBounds.right - gap
                val aligned = plateView.visibility == View.VISIBLE && dockView.visibility == View.VISIBLE &&
                    !plateBounds.isEmpty && centerDelta <= 1
                val statusFits = word.visibility == View.VISIBLE && word.text.isNotBlank() && ellipsis == 0 &&
                    statusLayout?.getLineEnd(0) == word.text.length && advance <= word.width - wordPadding + 0.5f
                val nominalDockFits = nominalDock > minimumTarget && dockBounds.width() == nominalDock &&
                    dockBounds.height() == nominalDock
                val valid = metrics.densityDpi == dpi && metrics.density == dpi / 160f && fontScale == 1f &&
                    aligned && hintVisibility == View.GONE && clockVisibility == View.GONE && statusFits &&
                    nominalDockFits && room > maxOf(plateWidth, areaCap)
                val evidence = "requestedDpi=$dpi density=${metrics.density}/${metrics.densityDpi} " +
                    "size=${view.width}x${view.height} fontScale=$fontScale plate=$plateBounds dock=$dockBounds " +
                    "aligned=$aligned nominalDock=$nominalDock minTarget=$minimumTarget gap=$gap room=$room " +
                    "width=$plateWidth areaCap=$areaCap naturalWidth=$naturalWidth floorAtWidth=${naturalWidth >= plateWidth} " +
                    "hintVisibility=$hintVisibility clockVisibility=$clockVisibility status=${word.text} " +
                    "statusFits=$statusFits advance=$advance ellipsis=$ellipsis"
                PlateReading(plateWidth, areaCap, naturalWidth, evidence, valid)
            }
        }
        val evidence = readings.joinToString("\n") { it.evidence }
        val (at160, at320) = readings
        assertTrue("$evidence\nFixture must be aligned with complete status, inactive touch floor and spare room",
            at160.valid && at320.valid)
        assertEquals("$evidence\nMatched AREA cap expectations", at160.areaCap, at320.areaCap)
        assertEquals("$evidence\nMatched aligned plate widths", at160.width, at320.width)
        assertTrue("$evidence\nNatural content reaches the actual or nominal width; cap not isolated",
            readings.all { it.naturalWidth < minOf(it.width, it.areaCap) })
        assertEquals("$evidence\nNominal 280 AREA-unit cap", at160.areaCap, at160.width)
    }

    /** Isolates the aligned receiver-hint width with spare space on both densities. */
    @Test
    fun matterAlignedHint_usesNominalAreaWidthAtMatchedBoundsAcrossDensities() {
        /** Holds one measured width and its fixture checks for the JUnit thread. */
        data class HintReading(val width: Int, val areaCap: Int, val isolated: Boolean, val evidence: String)

        var readings = emptyList<HintReading>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            readings = listOf(160, 320).map { dpi ->
                val view = matter(context(dpi), receiver = "R")
                view.applySecurityInsets(Insets.NONE)
                layout(view, 1920, 720)
                val metrics = view.resources.displayMetrics
                val fontScale = view.resources.configuration.fontScale
                val viewport = StandbyViewportScale(view.width, view.height, metrics.density)
                val hintView = requireNotNull(view.findViewWithTag<View>("foreground_hint"))
                val dockView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val name = requireNotNull(view.findViewWithTag<TextView>("foreground_hint_title"))
                val hint = bounds(view, hintView)
                val plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
                val dock = bounds(view, dockView)
                val nameBounds = bounds(view, name)
                val textLayout = name.layout
                val fullName = name.visibility == View.VISIBLE && name.text.toString() == "R" &&
                    !nameBounds.isEmpty && hint.contains(nameBounds) && textLayout != null && textLayout.lineCount == 1 &&
                    textLayout.getEllipsisCount(0) == 0 && textLayout.getLineEnd(0) == name.text.length
                val mode = when {
                    !hint.isEmpty && !plate.isEmpty && kotlin.math.abs(hint.centerY() - dock.centerY()) <= 1 &&
                        kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1 -> "aligned"
                    hint.bottom == plate.bottom && plate.bottom < dock.top -> "stacked"
                    else -> "other"
                }
                val areaCap = viewport.px(320f).toInt()
                val nominalDock = viewport.roundedPx(96f)
                val minTarget = kotlin.math.ceil(48.0 * metrics.density).toInt()
                val gap = viewport.px(16f).toInt()
                // Use the real hint anchor and dock bounds, allowing for the full AREA gap.
                val room = dock.left - hint.left - gap
                val fortyFivePercent = (view.width * 0.45f).toInt()
                val requiredWidth = maxOf(areaCap, hint.width())
                val isolated = metrics.densityDpi == dpi && metrics.density == dpi / 160f && fontScale == 1f &&
                    view.width == 1920 && view.height == 720 && mode == "aligned" &&
                    hintView.visibility == View.VISIBLE && fullName && dockView.visibility == View.VISIBLE &&
                    nominalDock > minTarget && dock.width() == nominalDock && dock.height() == nominalDock &&
                    room > requiredWidth && fortyFivePercent > requiredWidth
                val evidence = "requestedDpi=$dpi density=${metrics.density}/${metrics.densityDpi} " +
                    "size=${view.width}x${view.height} fontScale=$fontScale footerMode=$mode hint=$hint plate=$plate dock=$dock " +
                    "hintWidth=${hint.width()} areaCap=$areaCap room=$room gap=$gap width45Percent=$fortyFivePercent " +
                    "nominalDock=$nominalDock minTarget=$minTarget name=${name.text} fullName=$fullName"
                HintReading(hint.width(), areaCap, isolated, evidence)
            }
        }
        val evidence = readings.joinToString("\n") { it.evidence }
        val (at160, at320) = readings
        assertTrue("$evidence\nFixture must isolate the aligned nominal hint width", at160.isolated && at320.isolated)
        assertEquals("$evidence\nMatched AREA cap expectations", at160.areaCap, at320.areaCap)
        for (reading in readings) {
            assertEquals("$evidence\nNominal 320 AREA-unit aligned hint width", reading.areaCap, reading.width)
        }
    }

    /** Measures the aligned corner insets separately from slot widths and dock placement. */
    @Test
    fun matterAlignedCorners_useAreaClampAtMatchedBoundsAcrossDensities() {
        /** Keeps each native sample available for a combined assertion on the test thread. */
        data class CornerReading(
            val expected: Int, val insets: Pair<Int, Int>, val valid: Boolean, val calibrationOnly: Boolean, val evidence: String,
        )

        var readings = emptyList<CornerReading>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val calibrated = Triple(800, 1280, "min")
            val cases = listOf(
                Triple(960, 1360, "min"), Triple(1920, 720, "interior"), Triple(1920, 300, "max"), calibrated,
            )
            readings = cases.flatMap { case ->
                val (width, height, expectedBranch) = case
                val calibrationOnly = case == calibrated
                val densities = if (calibrationOnly) listOf(240) else listOf(160, 320)
                densities.map { dpi ->
                    val view = matter(context(dpi), receiver = "R")
                    view.applySecurityInsets(Insets.NONE)
                    layout(view, width, height)
                    val metrics = view.resources.displayMetrics
                    val fontScale = view.resources.configuration.fontScale
                    val views = listOf(StandbyForegroundTags.BRAND, "foreground_hint", StandbyForegroundTags.MATTER_PLATE,
                        StandbyForegroundTags.BROADCAST).map { requireNotNull(view.findViewWithTag<View>(it)) }
                    val (brand, hint, plate, dock) = views.map { bounds(view, it) }
                    val receiver = requireNotNull(view.findViewWithTag<TextView>("foreground_hint_title"))
                    val nameBounds = bounds(view, receiver)
                    val nameFits = receiver.visibility == View.VISIBLE && receiver.text.toString() == "R" &&
                        !nameBounds.isEmpty && hint.contains(nameBounds) && receiver.layout?.let {
                            it.lineCount == 1 && it.getLineEnd(0) == receiver.text.length && it.getEllipsisCount(0) == 0
                        } == true
                    val aligned = kotlin.math.abs(hint.centerY() - dock.centerY()) <= 1 &&
                        kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1
                    val edge = brand.left - view.paddingLeft
                    val left = hint.left - brand.left
                    val right = view.width - view.paddingRight - plate.right - edge
                    // Density cancels from AREA pixels at fixed full physical bounds; 3vw uses the full width.
                    val areaPxPerUnit = kotlin.math.sqrt(view.width.toDouble() * view.height / (390.0 * 844.0))
                    val minInset = 16.0 * areaPxPerUnit
                    val maxInset = 40.0 * areaPxPerUnit
                    val preferred = view.width * 0.03
                    val branch = when {
                        preferred < minInset -> "min"
                        preferred > maxInset -> "max"
                        else -> "interior"
                    }
                    val expected = preferred.coerceIn(minInset, maxInset).toInt()
                    val nominalDock = (96.0 * areaPxPerUnit).roundToInt()
                    val minTarget = kotlin.math.ceil(48.0 * metrics.density).toInt()
                    val valid = view.width == width && view.height == height && metrics.densityDpi == dpi &&
                        metrics.density == dpi / 160f && fontScale == 1f && branch == expectedBranch && aligned && nameFits &&
                        views.all { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 } &&
                        nominalDock > minTarget && dock.width() == nominalDock && dock.height() == nominalDock
                    val evidence = "size=${view.width}x${view.height} requestedDpi=$dpi density=${metrics.density}/${metrics.densityDpi} " +
                        "fontScale=$fontScale calibrationOnly=$calibrationOnly branch=$branch expectedBranch=$expectedBranch " +
                        "clampPx=${minInset.toInt()}/${preferred.toInt()}/${maxInset.toInt()} expected=$expected left=$left right=$right " +
                        "edge=$edge brand=$brand hint=$hint plate=$plate dock=$dock aligned=$aligned nameFits=$nameFits " +
                        "nominalDock=$nominalDock minTarget=$minTarget"
                    CornerReading(expected, left to right, valid, calibrationOnly, evidence)
                }
            }
        }
        val evidence = readings.joinToString("\n") { it.evidence }
        assertTrue("$evidence\nFixture must select the intended clamp branch with a visible aligned footer and nominal dock",
            readings.all { it.valid })
        // The calibrated position is diagnostic only; its existing readability guard stays separate.
        for (reading in readings.filterNot { it.calibrationOnly }) {
            assertEquals("$evidence\nAligned extra insets (hint-left, plate-right)",
                reading.expected to reading.expected, reading.insets)
        }
    }

    /** Matches physical bounds and inactive touch floors to isolate the stacked AREA gap. */
    @Test
    fun matterStackedGap_usesAreaUnitsAtMatchedBoundsAcrossDensities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val readings = listOf(160, 320).map { densityDpi ->
                val view = matter(context(densityDpi))
                view.applySecurityInsets(Insets.NONE)
                layout(view, 880, 1600)
                val metrics = view.resources.displayMetrics
                val viewport = StandbyViewportScale(view.width, view.height, metrics.density)
                val plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
                val hint = bounds(view, "foreground_hint")
                val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                val mode = when {
                    plate.bottom < dock.top && hint.bottom == plate.bottom -> "stacked"
                    kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1 &&
                        kotlin.math.abs(hint.centerY() - dock.centerY()) <= 1 -> "aligned"
                    else -> "other"
                }
                val nominalDock = viewport.roundedPx(96f)
                val minimumTarget = kotlin.math.ceil(48.0 * metrics.density).toInt()
                val expectedGap = viewport.px(16f).toInt()
                val actualGap = dock.top - plate.bottom
                val evidence = "requestedDpi=$densityDpi density=${metrics.density}/${metrics.densityDpi} " +
                    "size=${view.width}x${view.height} fontScale=${view.resources.configuration.fontScale} fixtureInsets=0 " +
                    "footerMode=$mode ${StandbyForegroundTags.MATTER_PLATE}=$plate foreground_hint=$hint " +
                    "${StandbyForegroundTags.BROADCAST}=$dock nominalDock=$nominalDock minimumTarget=$minimumTarget " +
                    "expectedAreaGap=$expectedGap actualGap=$actualGap"
                assertEquals(evidence, densityDpi, metrics.densityDpi)
                assertEquals(evidence, densityDpi / 160f, metrics.density, 0f)
                assertEquals(evidence, 1f, view.resources.configuration.fontScale, 0f)
                for (tag in listOf(StandbyForegroundTags.MATTER_PLATE, "foreground_hint", StandbyForegroundTags.BROADCAST)) {
                    val target = requireNotNull(view.findViewWithTag<View>(tag)) { evidence }
                    val rect = bounds(view, target)
                    assertEquals("$evidence tag=$tag", View.VISIBLE, target.visibility)
                    assertTrue("$evidence tag=$tag", rect.width() > 0 && rect.height() > 0 &&
                        Rect(0, 0, view.width, view.height).contains(rect))
                }
                assertEquals(evidence, "stacked", mode)
                assertTrue(evidence, nominalDock > minimumTarget)
                assertEquals(evidence, nominalDock, dock.width())
                assertEquals(evidence, nominalDock, dock.height())
                Triple(expectedGap, actualGap, evidence)
            }
            val evidence = readings.joinToString("\n") { it.third }
            assertEquals("$evidence matched AREA expectations", readings[0].first, readings[1].first)
            assertEquals("$evidence matched stacked gaps", readings[0].second, readings[1].second)
            for ((expectedGap, actualGap, detail) in readings) {
                assertEquals("$detail AREA gap", expectedGap, actualGap)
            }
        }
    }

    /** Preserves the calibrated normal-font READY footer independently of CSS-theme metrics. */
    @Test
    fun matterReady_preservesDisconnAndAlignedFooterAtCalibratedDensity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = matter(context(240))
            view.applySecurityInsets(Insets.NONE)
            layout(view, 800, 1280)
            val metrics = view.resources.displayMetrics
            val plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
            val hint = bounds(view, "foreground_hint")
            val dock = bounds(view, StandbyForegroundTags.BROADCAST)
            val word = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
            val wordBounds = bounds(view, word)
            val evidence = "density=${metrics.density}/${metrics.densityDpi} size=${view.width}x${view.height} " +
                "fontScale=${view.resources.configuration.fontScale} fixtureInsets=0 phase=READY " +
                "${StandbyForegroundTags.MATTER_PLATE}=$plate foreground_hint=$hint " +
                "${StandbyForegroundTags.BROADCAST}=$dock tag=${word.tag} bounds=$wordBounds"
            assertEquals(evidence, 240, metrics.densityDpi)
            assertEquals(evidence, 1.5f, metrics.density, 0f)
            assertEquals(evidence, 1f, view.resources.configuration.fontScale, 0f)
            assertEquals(evidence, "DISCONN", word.text.toString())
            val textLayout = requireNotNull(word.layout) { evidence }
            assertEquals(evidence, 1, textLayout.lineCount)
            assertEquals(evidence, word.text.length, textLayout.getLineEnd(0))
            assertEquals("$evidence line=0", 0, textLayout.getEllipsisCount(0))
            assertTrue(evidence, textLayout.getLineLeft(0) >= -0.5f)
            assertTrue(evidence, textLayout.getLineRight(0) <= word.width - word.compoundPaddingLeft - word.compoundPaddingRight + 0.5f)
            assertTrue(evidence, textLayout.getLineTop(0) >= 0 &&
                textLayout.getLineBottom(0) <= word.height - word.extendedPaddingTop - word.extendedPaddingBottom)
            assertTrue(evidence, wordBounds.width() > 0 && wordBounds.height() > 0 && plate.contains(wordBounds))
            for (rect in listOf(plate, hint, dock)) {
                assertTrue(evidence, rect.width() > 0 && rect.height() > 0 &&
                    Rect(0, 0, view.width, view.height).contains(rect))
            }
            assertTrue("$evidence aligned plate", kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1)
            assertTrue("$evidence aligned hint", kotlin.math.abs(hint.centerY() - dock.centerY()) <= 1)
            assertFalse(evidence, Rect.intersects(plate, dock))
            assertFalse(evidence, Rect.intersects(hint, dock))
            assertFalse(evidence, Rect.intersects(hint, plate))
        }
    }

    @Test
    fun matterSlots_stackForEllipsizedContentAndExcessiveFontHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val longName = "W".repeat(27)
            val view = matter(context(160), receiver = longName)
            layout(view, 800, 800)
            val hintTitle = requireNotNull(view.findViewWithTag<TextView>("foreground_hint_title"))
            val textLayout = requireNotNull(hintTitle.layout)
            assertEquals(longName, hintTitle.text.toString())
            assertEquals(0, textLayout.getEllipsisCount(0))
            assertEquals(longName.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            assertEquals(
                bounds(view, StandbyForegroundTags.BROADCAST).top - StandbyViewportScale(800, 800).px(16f).toInt(),
                bounds(view, StandbyForegroundTags.MATTER_PLATE).bottom,
            )

            val largeContext = context(160, 3f)
            val large = matter(largeContext, receiver = "", clock = "12:34")
            layout(large, 1200, 800)
            val word = requireNotNull(large.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
            assertEquals(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, largeContext.resources.displayMetrics) /
                    largeContext.resources.displayMetrics.density * StandbyViewportScale(1200, 800).scale,
                word.textSize, 0.01f,
            )
            assertEquals(
                bounds(large, StandbyForegroundTags.BROADCAST).top - StandbyViewportScale(1200, 800).px(16f).toInt(),
                bounds(large, StandbyForegroundTags.MATTER_PLATE).bottom,
            )
        }
    }

    @Test
    fun matterSlots_keepRotatedAsymmetricInsetsSafe() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = matter(context(160))
            view.applySecurityInsets(Insets.of(20, 5, 60, 15))
            layout(view, 800, 360)
            var plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
            var hint = bounds(view, "foreground_hint")
            var dock = bounds(view, StandbyForegroundTags.BROADCAST)
            assertTrue(kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1)
            assertTrue(hint.left >= 20 && plate.right <= 740)
            assertFalse(Rect.intersects(plate, dock))
            assertFalse(Rect.intersects(hint, dock))

            layout(view, 360, 800)
            plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
            hint = bounds(view, "foreground_hint")
            dock = bounds(view, StandbyForegroundTags.BROADCAST)
            assertEquals(dock.top - StandbyViewportScale(360, 800).px(16f).toInt(), plate.bottom)
            assertTrue(hint.left >= 20 && plate.right <= 300)
            assertFalse(Rect.intersects(hint, plate))
        }
    }

    /** Keeps the real handle's fractional center, artwork and focus target together across safe origins. */
    @Test
    fun legacyHardlineHandle_usesFullStageCenterAcrossInsetsResizeAndFallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (densityDpi in listOf(160, 240)) {
                val ctx = context(densityDpi)
                val density = ctx.resources.displayMetrics.density
                val view = HardlineStandbyForegroundView(ctx)
                view.applySecurityInsets(Insets.of(20, 30, 40, 50))
                view.render(StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio", isProjecting = false),
                    "Ready", "OFF", "", StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}))
                for ((width, height) in listOf(800 to 1280, 1280 to 800)) {
                    layout(view, width, height)
                    val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                    val controls = menu.parent as View
                    val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val titleView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                    val receiverView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.RECEIVER_NAME))
                    val title = Rect(titleView.left, titleView.top, titleView.right, titleView.bottom)
                    val receiver = Rect(receiverView.left, receiverView.top, receiverView.right, receiverView.bottom)
                    val center = height * 0.47f + 2.4f * density
                    view.setLegacyAmbientOffset(1.2f * density, 2.4f * density, center)
                    assertEquals(center, controls.top + menu.y + menu.height / 2f, 0.001f)
                    assertEquals(20f, controls.left + menu.x, 0f)
                    assertEquals(0f, menu.translationX, 0f)
                    assertEquals(48f * density, menu.width.toFloat(), 0f)
                    assertTrue(menu.height >= 48f * density)
                    assertEquals(dock, bounds(view, StandbyForegroundTags.BROADCAST))
                    val titleAfter = Rect(titleView.left, titleView.top, titleView.right, titleView.bottom)
                    val receiverAfter = Rect(receiverView.left, receiverView.top, receiverView.right, receiverView.bottom)
                    assertEquals(title, titleAfter)
                    assertEquals(receiver, receiverAfter)
                    assertEquals(1.2f * density, view.motionLayer.translationX, 0f)
                    assertEquals(2.4f * density, view.motionLayer.translationY, 0f)
                    val focused = Rect()
                    menu.getFocusedRect(focused)
                    assertEquals(Rect(0, 0, menu.width, menu.height), focused)
                    view.setLegacyAmbientOffset(1.2f * density, 2.4f * density, null)
                    assertEquals((height - menu.height) / 2 + menu.height / 2f,
                        controls.top + menu.y + menu.height / 2f, 0.001f)
                    assertEquals(dock, bounds(view, StandbyForegroundTags.BROADCAST))
                }
            }
        }
    }

    /** Forces a real notice collision, then requires the existing avoidance pass at the moved content. */
    @Test
    fun legacyForegroundDrift_rechecksNoticesWithoutMovingContentOrDockAnchors() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (view in listOf(LandscapeStandbyForegroundView(context(320)), HardlineStandbyForegroundView(context(320)))) {
                view.render(StandbyForegroundState(ScreenPhase.READY, false,
                    failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                    failureText = "Connection failed before playback. ".repeat(90), isProjecting = false),
                    "Ready", "OFF", "", StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {}, onOpenSettings = {}))
                layout(view, 720, 280)
                val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                val title = bounds(view, StandbyForegroundTags.PHASE_TITLE)
                val titleView = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                val titleLayout = Rect(titleView.left, titleView.top, titleView.right, titleView.bottom)
                val failure = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                val scroll = ((failure.parent as View).parent as View).parent as ScrollView
                val controls = scroll.parent as View
                // Only the fixture notice is placed onto the future title position to require re-avoidance.
                scroll.layout(title.left - controls.left + 8, title.top - controls.top + 6,
                    title.left - controls.left + 8 + scroll.width, title.top - controls.top + 6 + scroll.height)
                val noticesBeforeDrift = bounds(view, scroll)
                when (view) {
                    is LandscapeStandbyForegroundView -> view.setLegacyAmbientOffset(8f, 6f)
                    is HardlineStandbyForegroundView -> view.setLegacyAmbientOffset(8f, 6f, 280f * 0.47f + 4.8f)
                }
                // Unattached fixtures have no ViewRoot traversal to consume a requested layout.
                if (view.isLayoutRequested) layout(view, 720, 280)
                val readiness = "theme=${view.javaClass.simpleName} pendingLayout(root/controls/title)=" +
                    "${view.isLayoutRequested}/${controls.isLayoutRequested}/${titleView.isLayoutRequested}"
                assertTrue(readiness, !view.isLayoutRequested)
                assertEquals(dock, bounds(view, StandbyForegroundTags.BROADCAST))
                assertEquals(titleLayout, Rect(titleView.left, titleView.top, titleView.right, titleView.bottom))
                val notices = bounds(view, scroll)
                val placementEvidence = "theme=${view.javaClass.simpleName} viewport=${view.width}x${view.height} " +
                    "noticeBefore=$noticesBeforeDrift noticeAfter=$notices dock=$dock " +
                    "motion=${view.motionLayer.translationX},${view.motionLayer.translationY} layoutRequested=${view.isLayoutRequested}"
                assertTrue("$placementEvidence side location", notices.left >= dock.right)
                assertTrue("$placementEvidence viewport containment", notices.right <= 720 && notices.top >= 0 && notices.bottom <= 280)
                assertTrue("$placementEvidence minimum viewport", scroll.width >= 96 && scroll.height >= 96)
                val occupiedTitleLines = mutableListOf<android.graphics.RectF>()
                var titleComplete = false
                var titleLineClipped = false
                /** Maps native occupied line boxes and rectangular clipping, not estimated glyph ink. */
                fun titleLineEvidence(): String {
                    val text = titleView as? TextView ?: return "titleLines=unavailable:not-TextView"
                    val nativeLayout = text.layout ?: return "titleLines=unavailable:no-layout"
                    titleComplete = nativeLayout.lineCount > 0 &&
                        nativeLayout.getLineEnd(nativeLayout.lineCount - 1) == text.text.length
                    val globalToRoot = android.graphics.Matrix()
                    if (!android.graphics.Matrix().also { view.transformMatrixToGlobal(it) }.invert(globalToRoot)) {
                        return "titleLines=unavailable:noninvertible-root"
                    }
                    val titleToRoot = android.graphics.Matrix().also {
                        it.setConcat(globalToRoot, android.graphics.Matrix().also { matrix -> text.transformMatrixToGlobal(matrix) })
                    }
                    val full = android.graphics.RectF(0f, 0f, text.width.toFloat(), text.height.toFloat()).also { titleToRoot.mapRect(it) }
                    val localVisible = Rect()
                    val nativeVisible = text.getLocalVisibleRect(localVisible)
                    if (!nativeVisible || text.visibility != View.VISIBLE || text.alpha <= 0f) localVisible.setEmpty()
                    text.clipBounds?.let { if (!localVisible.intersect(it)) localVisible.setEmpty() }
                    val lines = (0 until nativeLayout.lineCount).joinToString { line ->
                        val occupied = android.graphics.RectF(
                            text.compoundPaddingLeft + nativeLayout.getLineLeft(line) - text.scrollX,
                            (text.totalPaddingTop + nativeLayout.getLineTop(line) - text.scrollY).toFloat(),
                            text.compoundPaddingLeft + nativeLayout.getLineRight(line) - text.scrollX,
                            (text.totalPaddingTop + nativeLayout.getLineBottom(line) - text.scrollY).toFloat(),
                        )
                        val shown = android.graphics.RectF(occupied)
                        val hasShown = !localVisible.isEmpty && shown.intersect(android.graphics.RectF(localVisible))
                        if (!hasShown) shown.setEmpty()
                        val clipped = !hasShown || shown != occupied
                        titleComplete = titleComplete && nativeLayout.getEllipsisCount(line) == 0
                        titleLineClipped = titleLineClipped || clipped
                        titleToRoot.mapRect(occupied)
                        if (hasShown) titleToRoot.mapRect(shown)
                        occupiedTitleLines.add(shown)
                        "$line:occupied=$occupied visible=$shown clipped=$clipped ellipsis=${nativeLayout.getEllipsisCount(line)} " +
                            "occupiedNoticeOverlap=${android.graphics.RectF.intersects(occupied, android.graphics.RectF(notices))} " +
                            "visibleNoticeOverlap=${hasShown && android.graphics.RectF.intersects(shown, android.graphics.RectF(notices))}"
                    }
                    return "titleFullRoot=$full nativeLocalVisible=$nativeVisible clipLocal=$localVisible ownClip=${text.clipBounds} " +
                        "paddingTop(extended/total)=${text.extendedPaddingTop}/${text.totalPaddingTop} scroll=${text.scrollX},${text.scrollY} lines=[$lines]"
                }
                val metrics = view.resources.displayMetrics
                val evidence = "theme=${view.javaClass.simpleName} viewClass=${view.javaClass.name} sourceViewport=720x280 " +
                    "viewport=${view.width}x${view.height} density=${metrics.density}/${metrics.densityDpi} fontScale=${view.resources.configuration.fontScale} " +
                    "phase=READY failure=PRE_PLAY_TIMEOUT fixtureInsets=0 padding=${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom} " +
                    "isLaidOut=${view.isLaidOut} isLayoutRequested=${view.isLayoutRequested} " +
                    "titleVisibility=${titleView.visibility} titleAlpha=${titleView.alpha} titleText=${(titleView as? TextView)?.text} " +
                    "titleLaidOut=${titleView.isLaidOut} titleLayoutRequested=${titleView.isLayoutRequested} titleLayout=$titleLayout titleBeforeRoot=$title " +
                    "expectedMovedFullBox=${Rect(title).apply { offset(8, 6) }} noticeBefore=$noticesBeforeDrift " +
                    "noticeBeforeSize=${noticesBeforeDrift.width()}x${noticesBeforeDrift.height()} noticeAfter=$notices noticeSize=${scroll.width}x${scroll.height} dock=$dock " +
                    "motionLayerTranslation=${view.motionLayer.translationX},${view.motionLayer.translationY} ${titleLineEvidence()} " +
                    "titleComplete=$titleComplete titleLineClipped=$titleLineClipped"
                val titleText = titleView as? TextView
                assertTrue(evidence, titleView.visibility == View.VISIBLE && titleView.isLaidOut && !titleView.isLayoutRequested &&
                    titleView.alpha > 0f && !titleText?.text.isNullOrEmpty())
                assertTrue(evidence, titleComplete && occupiedTitleLines.isNotEmpty())
                assertFalse("$evidence title line clipping", titleLineClipped)
                assertEquals(evidence, 8f, view.motionLayer.translationX, 0f)
                assertEquals(evidence, 6f, view.motionLayer.translationY, 0f)
                // Both checks use title-derived moved line boxes, independent of notice placement.
                val noticeBefore = android.graphics.RectF(noticesBeforeDrift)
                assertTrue("$evidence fixture requires re-avoidance", occupiedTitleLines.any {
                    android.graphics.RectF.intersects(noticeBefore, it)
                })
                val noticeAfter = android.graphics.RectF(notices)
                for ((line, occupied) in occupiedTitleLines.withIndex()) {
                    assertTrue("$evidence line=$line meaningful", occupied.width() > 0f && occupied.height() > 0f)
                    assertFalse("$evidence line=$line", android.graphics.RectF.intersects(noticeAfter, occupied))
                }
                val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                assertTrue(dismiss.width >= 96 && dismiss.height >= 96)
                val textLayout = requireNotNull(failure.layout)
                assertEquals(failure.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertTrue((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
            }
        }
    }

    /** Checks explicit frozen CSS query/cascade vectors, including the 600..601 portrait gap and width cap. */
    @Test
    fun landscapeCompactQueries_preserveGapCascadeAndCenterWidthCap() {
        /** Physical fixtures and independent expected CSS results; no production query determines expectations. */
        data class QueryCase(
            val width: Int,
            val height: Int,
            val centerWidth: Int,
            val paddingX: Int,
            val paddingY: Int,
            val statusGapCss: Int,
            val centerFraction: Float,
            val receiverVisible: Boolean,
            val hintPaddingCssX: Int = 0,
            val hintPaddingCssY: Int = 0,
            val densityDpi: Int = 320,
            val insets: Insets = Insets.NONE,
        )
        val cases = listOf(
            // Density 2: CSS widths 600 / 600.5 / 601 at height 800.
            QueryCase(1200, 1600, 1104, 60, 28, 9, 0.47f, false, 10, 7),
            QueryCase(1201, 1600, 1104, 0, 0, 19, 0.49f, true),
            QueryCase(1202, 1600, 1105, 0, 0, 6, 0.51f, false),
            // Both compact queries include height 820, not 820.5; max-width 600 still supplies its base 16px gap.
            QueryCase(1200, 1640, 1104, 60, 28, 9, 0.47f, false, 10, 7),
            QueryCase(1200, 1641, 1104, 0, 0, 16, 0.49f, true),
            QueryCase(1201, 1640, 1104, 0, 0, 19, 0.49f, true),
            QueryCase(1202, 1640, 1105, 0, 0, 6, 0.51f, false),
            QueryCase(1202, 1641, 1105, 0, 0, 19, 0.49f, true),
            // CSS 500x300 matches both queries: later compact-wide padding wins, then final landscape 43% wins.
            QueryCase(1000, 600, 920, 0, 0, 6, 0.43f, false),
            // CSS width 1500: compact 92% is 1380px; height 900 restores the base 1050px cap.
            QueryCase(3000, 1400, 2760, 0, 0, 6, 0.43f, false),
            QueryCase(3000, 1800, 2100, 0, 0, 19, 0.43f, true),
            // Full CSS height remains 900 although safe content is 800; the symmetric width clamp applies once.
            QueryCase(3000, 1800, 2000, 0, 0, 19, 0.43f, true, insets = Insets.of(180, 100, 500, 100)),
            QueryCase(800, 1280, 736, 0, 0, 16, 0.49f, true, densityDpi = 240),
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((densityDpi, vectors) in cases.groupBy { it.densityDpi }) {
                val ctx = context(densityDpi)
                val density = ctx.resources.displayMetrics.density
                val view = LandscapeStandbyForegroundView(ctx)
                val receiverText = "Studio"
                view.render(StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = receiverText, isProjecting = false),
                    "Ready", "BROADCAST ON", "", StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}))
                val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                val eyebrow = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                val center = title.parent as ViewGroup
                val status = eyebrow.parent as View
                val name = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                val receiver = name.parent as View
                val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                for (case in vectors) {
                    view.applySecurityInsets(case.insets)
                    layout(view, case.width, case.height)
                    val evidence = "density=$density case=$case"
                    assertEquals(evidence, case.width, view.width)
                    assertEquals(evidence, case.height, view.height)
                    assertEquals(evidence, case.centerWidth, center.width)
                    assertEquals(evidence, case.paddingX, center.paddingLeft)
                    assertEquals(evidence, case.paddingX, center.paddingRight)
                    assertEquals(evidence, case.paddingY, center.paddingTop)
                    assertEquals(evidence, case.paddingY, center.paddingBottom)
                    assertEquals(evidence, (case.statusGapCss * density).toInt(), title.top - status.bottom)
                    val centerBounds = bounds(view, center)
                    assertEquals(evidence, case.width / 2f, centerBounds.exactCenterX(), 1f)
                    assertEquals(evidence, case.height * case.centerFraction, centerBounds.exactCenterY(), 1f)
                    assertTrue(evidence, centerBounds.width() > 0 && centerBounds.height() > 0 &&
                        Rect(0, 0, case.width, case.height).contains(centerBounds))
                    assertTrue(evidence, centerBounds.left >= case.insets.left && centerBounds.right <= case.width - case.insets.right)
                    assertEquals(evidence, if (case.receiverVisible) View.VISIBLE else View.GONE, receiver.visibility)
                    assertEquals(evidence, receiverText, name.text.toString())
                    assertEquals(evidence, receiverText, name.contentDescription.toString())
                    if (case.receiverVisible) {
                        val receiverBounds = bounds(view, receiver)
                        assertTrue(evidence, receiverBounds.width() > 0 && receiverBounds.height() > 0 &&
                            Rect(0, 0, case.width, case.height).contains(receiverBounds))
                        val receiverOffsetCss = when {
                            case.centerFraction == 0.43f -> 111f
                            case.width == 1200 || densityDpi == 240 -> 127f
                            else -> 151f
                        }
                        val receiverAnchor = if (case.centerFraction == 0.43f) 0.43f else 0.49f
                        assertEquals(evidence, case.height * receiverAnchor - receiverOffsetCss * density, receiverBounds.top.toFloat(), 1f)
                    }
                    val safeWidth = case.width - 2 * maxOf(case.insets.left, case.insets.right)
                    val expectedHintWidth = minOf((case.width * 0.90f).toInt(), (580f * density).toInt(), safeWidth)
                    assertEquals(evidence, View.VISIBLE, hint.visibility)
                    assertEquals(evidence, expectedHintWidth, hint.width)
                    assertEquals(evidence, (case.hintPaddingCssX * density).toInt(), hint.paddingLeft)
                    assertEquals(evidence, hint.paddingLeft, hint.paddingRight)
                    assertEquals(evidence, (case.hintPaddingCssY * density).toInt(), hint.paddingTop)
                    assertEquals(evidence, hint.paddingTop, hint.paddingBottom)
                    val hintBounds = bounds(view, hint)
                    assertTrue(evidence, hintBounds.width() > 0 && hintBounds.height() > 0 &&
                        Rect(0, 0, case.width, case.height).contains(hintBounds))
                    assertTrue(evidence, hint.text.toString().contains(receiverText))
                    val edge = bounds(view, StandbyForegroundTags.BRAND).top - case.insets.top
                    assertEquals(evidence, case.height - case.insets.bottom - edge - (100f * density).toInt(), hintBounds.bottom)
                    assertEquals(evidence, 40f * density, title.textSize, 0.001f)
                    assertEquals(evidence, 350, title.typeface.weight)
                    assertFalse(evidence, title.includeFontPadding)
                    val titleLayout = requireNotNull(title.layout) { evidence }
                    assertEquals(evidence, (48f * density).toInt(), titleLayout.getLineBottom(0) - titleLayout.getLineTop(0))
                    assertEquals(evidence, 11f * density, hint.textSize, 0.001f)
                    assertEquals(evidence, (96f * density).toInt(), dock.width)
                    assertEquals(evidence, dock.width, dock.height)
                    assertEquals(evidence, case.width / 2f, bounds(view, dock).exactCenterX(), 1f)
                    val dockBottomMargin = if (case.height == 600) 8f * density else 12f * density
                    assertEquals(evidence, case.height - maxOf(case.insets.bottom, dockBottomMargin.toInt()), bounds(view, dock).bottom)
                    assertTrue(evidence, menu.width >= 48f * density && menu.height >= 48f * density)
                }
            }
        }
    }

    /** Uses actual Layout paragraph edges and centered line extents, not span presence alone. */
    @Test
    fun landscapeTitleIndent_usesResolvedEmOnFirstWrappedLineOnly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var observedResolvedGrowth = false
            for ((locale, dpi, fontScale) in listOf(
                Triple(Locale.ENGLISH, 240, 1f), Triple(Locale.TRADITIONAL_CHINESE, 320, 1f),
                Triple(Locale.ENGLISH, 320, 1.3f), Triple(Locale.TRADITIONAL_CHINESE, 240, 1.3f),
                Triple(Locale.ENGLISH, 320, 2f),
            )) {
                val base = context(dpi, fontScale)
                val ctx = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
                val density = ctx.resources.displayMetrics.density
                val width = (800f * density / 1.5f).roundToInt()
                val height = (1280f * density / 1.5f).roundToInt()
                val view = LandscapeStandbyForegroundView(ctx)
                val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio",
                    copyMotionAllowed = false, isProjecting = false)
                val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {})
                val shortCopy = if (locale == Locale.ENGLISH) "Ready" else "就緒"
                val wrappedCopy = if (locale == Locale.ENGLISH) {
                    "Waiting for Connection · Ready to receive projection"
                } else {
                    "等待連接，請在投放裝置選擇此接收器"
                }
                view.render(state, shortCopy, "BROADCAST OFF", "", callbacks)
                layout(view, width, height)
                val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                val center = title.parent as ViewGroup
                val anchors = listOf(StandbyForegroundTags.BRAND, StandbyForegroundTags.RECEIVER_NAME,
                    StandbyForegroundTags.BROADCAST, StandbyForegroundTags.MENU).associateWith { bounds(view, it) }
                val textSize = title.textSize
                val typeface = title.typeface
                for (copy in listOf(shortCopy, wrappedCopy)) {
                    view.render(state, copy, "BROADCAST OFF", "", callbacks)
                    layout(view, width, height)
                    val metrics = title.resources.displayMetrics
                    val expectedTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 40f, metrics)
                    val evidence = "locale=$locale dpi=$dpi fontScale=$fontScale textSize=${title.textSize} copy=$copy " +
                        "actualFontScale=${title.resources.configuration.fontScale} density=${metrics.density}/${metrics.densityDpi} " +
                        "scaledDensity=${metrics.scaledDensity} expectedPlatformSpPx=$expectedTextSize"
                    val textLayout = requireNotNull(title.layout) { evidence }
                    assertEquals(evidence, fontScale, title.resources.configuration.fontScale, 0f)
                    assertEquals(evidence, dpi, metrics.densityDpi)
                    assertEquals(evidence, dpi / 160f, metrics.density, 0f)
                    assertEquals(evidence, expectedTextSize, title.textSize, 0.001f)
                    val indent = (expectedTextSize * 0.10f).roundToInt()
                    assertTrue(evidence, kotlin.math.abs(indent - title.textSize * 0.10f) <= 0.5f)
                    assertEquals(evidence, copy, title.text.toString())
                    assertEquals(evidence, textSize, title.textSize, 0f)
                    assertEquals(evidence, typeface, title.typeface)
                    assertEquals(evidence, 350, title.typeface.weight)
                    assertEquals(evidence, 0.08f, title.letterSpacing, 0f)
                    assertFalse(evidence, title.includeFontPadding)
                    assertEquals(evidence, 0, title.paddingLeft)
                    assertEquals(evidence, 0, title.paddingRight)
                    assertEquals(evidence, title.width, textLayout.width)
                    if (copy == shortCopy) {
                        assertEquals(evidence, 1, textLayout.lineCount)
                        val glyphAdvance = title.paint.getRunAdvance(title.text, 0, copy.length, 0, copy.length, false, copy.length)
                        assertEquals(evidence, glyphAdvance, Layout.getDesiredWidth(title.text, title.paint) - indent, 0.01f)
                    } else {
                        assertTrue(evidence, textLayout.lineCount > 1 && !copy.contains('\n'))
                    }
                    assertEquals(evidence, copy.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                    for (line in 0 until textLayout.lineCount) {
                        val expectedLeft = if (line == 0) indent else 0
                        assertEquals(evidence, 1, textLayout.getParagraphDirection(line))
                        assertEquals(evidence, expectedLeft, textLayout.getParagraphLeft(line))
                        assertEquals(evidence, textLayout.width, textLayout.getParagraphRight(line))
                        assertEquals(evidence, textLayout.width - expectedLeft,
                            textLayout.getParagraphRight(line) - textLayout.getParagraphLeft(line))
                        assertEquals(evidence, (textLayout.width + expectedLeft) / 2f,
                            (textLayout.getLineLeft(line) + textLayout.getLineRight(line)) / 2f, 1f)
                        assertEquals(evidence, (title.textSize * 1.2f).roundToInt(),
                            textLayout.getLineBottom(line) - textLayout.getLineTop(line))
                        assertEquals(evidence, 0, textLayout.getEllipsisCount(line))
                    }
                    val centerBounds = bounds(view, center)
                    assertEquals(evidence, width / 2f, centerBounds.exactCenterX(), 1f)
                    assertEquals(evidence, height * 0.49f, centerBounds.exactCenterY(), 1f)
                    assertEquals(evidence, (width * 0.92f).toInt(), center.width)
                    for ((tag, before) in anchors) assertEquals("$evidence tag=$tag", before, bounds(view, tag))
                    val receiver = bounds(view, StandbyForegroundTags.RECEIVER_NAME)
                    val label = bounds(view, StandbyForegroundTags.RECEIVER_LABEL)
                    assertEquals(evidence, "Studio", requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME)).text.toString())
                    assertEquals(evidence, height * 0.49f - 127f * density, label.top.toFloat(), 1f)
                    assertTrue(evidence, receiver.width() > 0 && receiver.height() > 0)
                    val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val menu = bounds(view, StandbyForegroundTags.MENU)
                    assertEquals(evidence, (96f * density).roundToInt(), dock.width())
                    assertEquals(evidence, height - (12f * density).toInt(), dock.bottom)
                    assertTrue(evidence, dock.width() >= 48f * density && dock.height() >= 48f * density &&
                        menu.width() >= 48f * density && menu.height() >= 48f * density)
                    for (tag in listOf(StandbyForegroundTags.PHASE_EYEBROW, StandbyForegroundTags.RECEIVER_NAME)) {
                        val other = requireNotNull(view.findViewWithTag<TextView>(tag))
                        assertEquals(evidence, 0, (other.text as? Spanned)?.getSpans(0, other.text.length, LeadingMarginSpan::class.java)?.size ?: 0)
                    }
                    if (fontScale == 1f) assertEquals(evidence, 40f * density, title.textSize, 0.001f)
                    else if (fontScale > 1f && title.textSize > 40f * density && indent > (4f * density).roundToInt()) {
                        observedResolvedGrowth = true
                    }
                }
                // A later paragraph is not a second first rendered line of the title.
                view.render(state, "$shortCopy\n$shortCopy", "BROADCAST OFF", "", callbacks)
                layout(view, width, height)
                val paragraphs = requireNotNull(title.layout)
                assertEquals(0, paragraphs.getParagraphLeft(paragraphs.getLineForOffset(shortCopy.length + 1)))
                if (dpi == 240 && fontScale == 1f) {
                    val hardline = HardlineStandbyForegroundView(ctx)
                    hardline.render(state, wrappedCopy, "BROADCAST OFF", "", callbacks)
                    layout(hardline, width, height)
                    val hardlineTitle = requireNotNull(hardline.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    assertEquals(400, hardlineTitle.typeface.weight)
                    assertEquals(0, requireNotNull(hardlineTitle.layout).getParagraphLeft(0))
                    assertEquals(0, (hardlineTitle.text as Spanned).getSpans(0, hardlineTitle.text.length, LeadingMarginSpan::class.java).size)
                }
            }
            assertTrue("At least one greater fontScale must grow the platform-resolved title and rounded 0.1em indent beyond the density-matched base",
                observedResolvedGrowth)
        }
    }

    @Test
    fun cssMetrics_restorePrototypeValues_withoutChangingMatter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                val base = context(240)
                val ctx = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
                for (phase in listOf(ScreenPhase.READY, ScreenPhase.ADVERTISING)) {
                    for (view in listOf(LandscapeStandbyForegroundView(ctx), HardlineStandbyForegroundView(ctx))) {
                        view.render(StandbyForegroundState(phase, phase == ScreenPhase.ADVERTISING, receiverName = "MIRAX · 客廳"),
                            ctx.getString(me.trinitrix.mirax.R.string.standby_foreground_phase_off_title), "BROADCAST OFF", "",
                            StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}))
                        layout(view, 800, 1280)
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        val eyebrow = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                        val brand = requireNotNull(view.findViewWithTag<ViewGroup>(StandbyForegroundTags.BRAND))
                        val brandText = (brand.getChildAt(1) as ViewGroup).getChildAt(0) as TextView
                        assertEquals(60f, title.textSize, 0.01f)
                        assertEquals(if (view is LandscapeStandbyForegroundView) 350 else 400, title.typeface.weight)
                        assertEquals(if (view is HardlineStandbyForegroundView) 450 else 400, eyebrow.typeface.weight)
                        assertFalse(title.includeFontPadding)
                        assertEquals(72, requireNotNull(title.layout).getLineBottom(0) - title.layout.getLineTop(0))
                        assertEquals(27f, brandText.textSize, 0.01f)
                        assertEquals(650, brandText.typeface.weight)
                        assertFalse(brandText.includeFontPadding)
                        assertEquals(50, brand.height)
                        val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                        assertEquals(if (view is HardlineStandbyForegroundView) 126 else 144, dock.width())
                        assertEquals(18, 1280 - dock.bottom)
                        val menu = bounds(view, StandbyForegroundTags.MENU)
                        assertTrue(menu.width() >= 72 && menu.height() >= 72)
                        if (view is HardlineStandbyForegroundView) {
                            val center = title.parent as View
                            assertEquals(1280f * 0.47f, center.top + center.height / 2f, 1f)
                            assertEquals(24, center.paddingLeft)
                            assertNull(view.findViewWithTag<View>("foreground_corner_diamond"))
                        } else {
                            val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                            assertEquals(if (phase == ScreenPhase.ADVERTISING) View.VISIBLE else View.GONE, hint.visibility)
                            if (phase == ScreenPhase.ADVERTISING) {
                                assertFalse(hint.text.toString().startsWith("開啟接收後"))
                                assertTrue((hint.text as android.text.Spanned).getSpans(0, hint.text.length,
                                    android.text.style.ReplacementSpan::class.java).isNotEmpty())
                            }
                        }
                    }
                }
            }
            val matter = matter(context(240))
            layout(matter, 800, 1280)
            val brand = requireNotNull(matter.findViewWithTag<ViewGroup>(StandbyForegroundTags.BRAND))
            val column = brand.getChildAt(1) as ViewGroup
            val label = column.getChildAt(0) as TextView
            assertEquals(31.74819f, label.textSize, 0.01f)
            assertTrue(label.includeFontPadding)
            assertEquals(0xFFCBD0CB.toInt(), (column.getChildAt(1) as TextView).currentTextColor)
            assertEquals(169, bounds(matter, StandbyForegroundTags.BROADCAST).width())
        }
    }

    @Test
    fun receiverBlocks_wrapFittingNames_andEllipsizeOnlyRealOverflow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (view in listOf(LandscapeStandbyForegroundView(context(240)), HardlineStandbyForegroundView(context(240)))) {
                for (name in listOf("Studio", "MIRAX · 客廳", "W".repeat(200))) {
                    view.render(StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = name),
                        "Ready", "BROADCAST ON", "", StandbyForegroundCallbacks(onToggleBroadcast = {}))
                    layout(view, 800, 1280)
                    val receiver = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                    val label = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_LABEL))
                    val textLayout = requireNotNull(receiver.layout)
                    assertEquals(name, receiver.text.toString())
                    assertEquals(1, receiver.maxLines)
                    assertEquals(24f, receiver.textSize, 0.01f)
                    assertEquals(name.length > 100, textLayout.getEllipsisCount(0) > 0)
                    assertEquals(0, requireNotNull(label.layout).getEllipsisCount(0))
                    val labelBounds = bounds(view, label)
                    val nameBounds = bounds(view, receiver)
                    assertEquals(if (view is LandscapeStandbyForegroundView) 12 else 5, nameBounds.top - labelBounds.bottom)
                    if (view is LandscapeStandbyForegroundView) {
                        assertEquals(labelBounds.exactCenterX(), nameBounds.exactCenterX(), 1f)
                        assertTrue((receiver.parent as View).width <= 692)
                    } else {
                        assertEquals(labelBounds.left, nameBounds.left)
                        val block = receiver.parent.parent as View
                        assertTrue(block.width <= 692)
                        if (name.length < 100) assertTrue(block.width < 400)
                    }
                }
            }
        }
    }

    @Test
    fun hardlineForeground_keepsReceiverAndDockWithoutOwningGeneratedDiamond() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = HardlineStandbyForegroundView(context(240))
            view.render(StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio"),
                "Ready", "BROADCAST OFF", "", StandbyForegroundCallbacks(onToggleBroadcast = {}))
            for ((width, height) in listOf(1500 to 1000, 900 to 1200)) {
                layout(view, width, height)
                assertNull(view.findViewWithTag<View>("foreground_corner_diamond"))
                assertEquals(3, view.motionLayer.childCount)
                val receiver = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME)).parent.parent as View
                assertTrue(receiver.parent === view.motionLayer)
                assertEquals(View.VISIBLE, receiver.visibility)
                assertTrue(receiver.width > 0 && receiver.height > 0)
                val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                assertEquals(View.VISIBLE, dock.visibility)
                assertEquals(126, dock.width)
                assertEquals(126, dock.height)
            }
        }
    }

    @Test
    fun hardlineGeometry_reportsFullNativeWidthWithAsymmetricInsets() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var view: HardlineStandbyForegroundView
            scenario.onActivity { root = it.contentRoot }
            awaitForegroundLayout(scenario, root)
            try {
                scenario.onActivity { activity ->
                    val visible = visibleContentBounds(root)
                    view = HardlineStandbyForegroundView(activity).apply {
                        pivotX = 0f
                        pivotY = 0f
                        scaleX = 0.25f
                        scaleY = 0.25f
                        applySecurityInsets(Insets.of(20, 0, 60, 0))
                        render(
                            StandbyForegroundState(ScreenPhase.READY, false),
                            "Ready", "Status", "Detail", StandbyForegroundCallbacks(),
                        )
                    }
                    root.addView(view, FrameLayout.LayoutParams(800, 400, Gravity.TOP or Gravity.LEFT).apply {
                        leftMargin = visible.left - root.paddingLeft
                        topMargin = visible.top - root.paddingTop
                    })
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity {
                    val evidence = foregroundSnapshot("hardlineGeometry_reportsFullNativeWidthWithAsymmetricInsets fixtureInsets=20,0,60,0", view)
                    android.util.Log.i("StandbyGeometry", evidence)
                    val band = requireNotNull(view.foregroundRectsInViewPixels()) { evidence }.centerBand
                    val title = bounds(view, StandbyForegroundTags.PHASE_TITLE)
                    assertEquals(0, band.left)
                    assertEquals(800, band.right)
                    assertEquals(400, band.centerX())
                    assertTrue(kotlin.math.abs(title.centerX() - 400) <= 1)
                    assertTrue(title.left >= 20 && title.right <= 740)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun shortNotices_useReachableRightSideWithoutTruncatingFailure() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context(320)
            val explanation = "Connection failed before playback. ".repeat(90)
            val views = listOf(
                LandscapeStandbyForegroundView(context),
                MatterStandbyForegroundView(context),
                HardlineStandbyForegroundView(context),
            )
            for (view in views) {
                val state = StandbyForegroundState(ScreenPhase.READY, false)
                val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {})
                val primaryTag = if (view is MatterStandbyForegroundView) {
                    StandbyForegroundTags.MATTER_PLATE
                } else {
                    StandbyForegroundTags.PHASE_TITLE
                }
                view.render(state, "Ready", "Status", "", callbacks)
                layout(view, 720, 280)
                val originalDock = bounds(view, StandbyForegroundTags.BROADCAST)
                val originalPrimary = bounds(view, primaryTag)
                view.render(
                    state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = explanation),
                    "Ready", "Status", "", callbacks,
                )
                layout(view, 720, 280)
                val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                val primary = bounds(view, primaryTag)
                assertEquals(originalDock, dock)
                if (view is MatterStandbyForegroundView) {
                    assertEquals(originalPrimary.right, primary.right)
                    assertEquals(originalPrimary.bottom, primary.bottom)
                } else {
                    assertEquals(originalPrimary, primary)
                }
                val failure = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                val scroll = ((failure.parent as View).parent as View).parent as ScrollView
                val scrollBounds = bounds(view, scroll)
                val evidence = foregroundSnapshot("shortNotices_useReachableRightSideWithoutTruncatingFailure fixtureInsets=0 scroll=$scrollBounds child=${scroll.getChildAt(0).width}x${scroll.getChildAt(0).height}", view)
                assertTrue(evidence, scrollBounds.left >= dock.right)
                assertTrue(evidence, scrollBounds.right <= 720 && scrollBounds.bottom <= 280)
                assertTrue(evidence, scroll.width >= 96 && scroll.height >= 96)
                assertTrue(evidence, dismiss.width >= 96 && dismiss.height >= 96)
                assertEquals(explanation, failure.text.toString())
                assertNull(failure.ellipsize)
                val textLayout = requireNotNull(failure.layout)
                assertEquals(explanation.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertTrue((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                assertTrue(evidence, scroll.getChildAt(0).height > scroll.height)
                scroll.isSmoothScrollingEnabled = false
                scroll.fullScroll(View.FOCUS_DOWN)
                assertTrue(scroll.scrollY > 0)
                val dismissBounds = bounds(scroll, dismiss)
                scroll.scrollTo(0, dismissBounds.top)
                assertTrue(dismissBounds.top >= scroll.scrollY)
                assertTrue(dismissBounds.bottom <= scroll.scrollY + scroll.height)
            }
        }
    }

    @Test
    fun notices_doNotMoveDockOrStatusWhenRightSideCannotFit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context(320)
            val view = HardlineStandbyForegroundView(context)
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {})
            val state = StandbyForegroundState(ScreenPhase.READY, false)
            view.render(state, "Ready", "Status", "", callbacks)
            layout(view, 320, 280)
            val originalDock = bounds(view, StandbyForegroundTags.BROADCAST)
            val originalTitle = bounds(view, StandbyForegroundTags.PHASE_TITLE)
            view.render(
                state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Connection failed"),
                "Ready", "Status", "", callbacks,
            )
            layout(view, 320, 280)
            assertEquals(originalDock, bounds(view, StandbyForegroundTags.BROADCAST))
            assertEquals(originalTitle, bounds(view, StandbyForegroundTags.PHASE_TITLE))
            val failure = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
            val failureBounds = bounds(view, failure)
            val evidence = foregroundSnapshot("notices_doNotMoveDockOrStatusWhenRightSideCannotFit fixtureInsets=0 failure=$failureBounds dock=$originalDock", view)
            assertNull(evidence, failure.ellipsize)
            assertEquals(evidence, "Connection failed", failure.text.toString())
            val textLayout = requireNotNull(failure.layout) { evidence }
            assertTrue(evidence, textLayout.lineCount > 0)
            assertEquals(evidence, failure.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            val width = failure.width - failure.compoundPaddingLeft - failure.compoundPaddingRight
            val height = failure.height - failure.extendedPaddingTop - failure.extendedPaddingBottom
            for (line in 0 until textLayout.lineCount) {
                assertEquals(evidence, 0, textLayout.getEllipsisCount(line))
                assertTrue(evidence, textLayout.getLineLeft(line) >= -0.5f)
                assertTrue(evidence, textLayout.getLineRight(line) <= width + 0.5f)
                assertTrue(evidence, textLayout.getLineTop(line) >= 0 && textLayout.getLineBottom(line) <= height)
            }
            assertFalse(evidence, Rect.intersects(failureBounds, originalDock))
        }
    }

    @Test
    fun notices_preserveNormalPlacementAndUseRightSideForInsetShortAreas() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context(160)
            fun assertDismissLineFits(view: ViewGroup) {
                val dismiss = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED_DISMISS))
                val textLayout = requireNotNull(dismiss.layout)
                val evidence = foregroundSnapshot("normal dismiss before pre-draw", view, dismiss)
                assertNull(evidence, view.parent)
                assertTrue(evidence, dismiss.text.isNotEmpty())
                assertEquals(evidence, 1, textLayout.lineCount)
                assertEquals(evidence, dismiss.text.length, textLayout.getLineEnd(0))
                assertEquals(evidence, 0, textLayout.getEllipsisCount(0))
                assertTrue(evidence, textLayout.getLineLeft(0) >= -0.5f)
                assertTrue(evidence, textLayout.getLineRight(0) <= dismiss.width - dismiss.compoundPaddingLeft - dismiss.compoundPaddingRight + 0.5f)
            }
            val state = StandbyForegroundState(ScreenPhase.READY, false)
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {})
            val failureState = state.copy(
                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Connection failed",
            )
            // Controls alone verify normal placement without foreground content obstructing the upper area.
            for (theme in listOf(StandbyControlTheme.LANDSCAPE, StandbyControlTheme.MATTER, StandbyControlTheme.HARDLINE)) {
                for (noticeState in listOf(failureState, failureState.copy(phase = ScreenPhase.ADVERTISING, advertisingEnabled = true, receiverName = "Receiver"))) {
                    val controls = StandbyForegroundControls(context, theme)
                    controls.viewportWidth = 800
                    controls.viewportHeight = 800
                    controls.update(noticeState, callbacks)
                    layout(controls, 800, 800)
                    assertDismissLineFits(controls)
                    val failure = bounds(controls, StandbyForegroundTags.FAILED)
                    val dock = bounds(controls, StandbyForegroundTags.BROADCAST)
                    val evidence = foregroundSnapshot("unobstructed normal placement theme=$theme phase=${noticeState.phase}", controls)
                    assertTrue(evidence, failure.bottom <= dock.top && failure.left < dock.right)
                }
            }
            val views = listOf(
                LandscapeStandbyForegroundView(context),
                MatterStandbyForegroundView(context),
                HardlineStandbyForegroundView(context),
            )
            for (view in views) {
                for ((width, height, inset) in listOf(Triple(800, 360, 80), Triple(360, 800, 300))) {
                    view.applySecurityInsets(Insets.of(0, inset, 0, inset))
                    view.render(state, "Ready", "Status", "", callbacks)
                    layout(view, width, height)
                    val originalDock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val primaryTag = if (view is MatterStandbyForegroundView) {
                        StandbyForegroundTags.MATTER_PLATE
                    } else {
                        StandbyForegroundTags.PHASE_TITLE
                    }
                    val originalPrimary = bounds(view, primaryTag)
                    view.render(failureState, "Ready", "Status", "", callbacks)
                    layout(view, width, height)
                    val failure = bounds(view, StandbyForegroundTags.FAILED)
                    val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                    val primary = bounds(view, primaryTag)
                    assertEquals(originalDock, dock)
                    if (view is MatterStandbyForegroundView) {
                        assertEquals(originalPrimary.right, primary.right)
                        assertEquals(originalPrimary.bottom, primary.bottom)
                    } else {
                        assertEquals(originalPrimary, primary)
                    }
                    val evidence = foregroundSnapshot("notices_preserveNormalPlacementAndUseRightSideForInsetShortAreas fixtureInsets=0,$inset,0,$inset failure=$failure dock=$dock primary=$primary", view)
                    if (view is MatterStandbyForegroundView && width == 800) {
                        assertTrue(evidence, failure.bottom <= dock.top)
                    } else {
                        assertTrue(evidence, failure.left >= dock.right)
                    }
                }
                view.applySecurityInsets(Insets.NONE)
                view.render(failureState, "Ready", "Status", "", callbacks)
                layout(view, 800, 800)
                assertDismissLineFits(view)
                val failure = bounds(view, StandbyForegroundTags.FAILED)
                val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                val evidence = foregroundSnapshot("normal placement after side layout", view)
                assertTrue(evidence, view.findViewWithTag<View>(StandbyForegroundTags.RECEIVER_NAME)?.visibility != View.VISIBLE)
                assertTrue(evidence, view.findViewWithTag<View>("foreground_hint")?.visibility != View.VISIBLE)
                assertTrue(evidence, failure.bottom <= dock.top && failure.left < dock.right)
            }
        }
    }

    @Test
    fun matterDock_usesAreaBreakpoint440AcrossDensitiesAndBottomInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onDismissFailure = {})
            val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "R", clockText = "12:34")
            // Force overflow even at the widest notice measurement; short content does not reveal the cap.
            val longNotice = List(48) { "Connection failed." }.joinToString("\n")
            for ((width, height, expectedAreaMargin) in listOf(Triple(896, 592, 12f), Triple(800, 450, 8f))) {
                for (dpi in listOf(160, 320)) {
                    val ctx = context(dpi)
                    val viewport = StandbyViewportScale(width, height, ctx.resources.displayMetrics.density)
                    val expectedMarginPx = viewport.roundedPx(expectedAreaMargin)
                    val expectedGap = viewport.roundedPx(6f)
                    val bottomInset = expectedMarginPx + 12

                    for (inset in listOf(0, bottomInset)) {
                        val view = MatterStandbyForegroundView(ctx)
                        view.applySecurityInsets(Insets.of(0, 0, 0, inset))
                        view.render(state, "Ready", "Status", "", callbacks)
                        layout(view, width, height)
                        val dock = bounds(view, StandbyForegroundTags.BROADCAST)
                        val plate = bounds(view, StandbyForegroundTags.MATTER_PLATE)
                        val expectedDockMargin = maxOf(expectedMarginPx, inset)
                        val evidence = "size=${width}x$height dpi=$dpi inset=$inset dock=$dock plate=$plate"
                        assertEquals(evidence, height - expectedDockMargin, dock.bottom)
                        val aligned = kotlin.math.abs(plate.centerY() - dock.centerY()) <= 1
                        val stacked = plate.bottom == dock.top - viewport.px(16f).toInt()
                        assertTrue(evidence, aligned || stacked)

                        view.render(
                            state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Connection failed"),
                            "Ready", "Status", "", callbacks,
                        )
                        layout(view, width, height)
                        val failureDock = bounds(view, StandbyForegroundTags.BROADCAST)
                        val failureNotice = bounds(
                            view,
                            ((view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED).parent as View)
                                .parent as View).parent as ScrollView,
                        )
                        assertEquals(evidence, dock, failureDock)
                        assertEquals(evidence, failureDock.top - expectedGap, failureNotice.bottom)

                        val controls = StandbyForegroundControls(ctx, StandbyControlTheme.MATTER)
                        controls.viewportWidth = width
                        controls.viewportHeight = height
                        controls.safeBottomInset = inset
                        controls.update(
                            state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = longNotice),
                            callbacks,
                        )
                        val controlsHeight = height - inset
                        layout(controls, width, controlsHeight)
                        val controlsDock = bounds(controls, StandbyForegroundTags.BROADCAST)
                        val noticeScroll = ((controls.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)
                            .parent as View).parent as View).parent as ScrollView
                        val expectedNoticeHeight = upperNoticeMaxHeight(
                            controlsHeight,
                            (expectedMarginPx - inset).coerceAtLeast(0),
                            controlsDock.height(),
                            expectedGap,
                        )
                        val contentHeight = noticeScroll.getChildAt(0).height
                        val noticeEvidence = "$evidence density=${ctx.resources.displayMetrics.density} " +
                            "marginPx=${height - dock.bottom} noticeBottom=${failureNotice.bottom} " +
                            "noticeCap=$expectedNoticeHeight noticeViewport=${noticeScroll.height} contentHeight=$contentHeight"
                        assertTrue(noticeEvidence, contentHeight > noticeScroll.height)
                        assertEquals(noticeEvidence, expectedNoticeHeight, noticeScroll.height)
                        Log.i("StandbyGeometry", "matterAreaDock $noticeEvidence")
                    }
                }
            }
        }
    }
}
