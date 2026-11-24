package me.trinitrix.mirax.ui.standby

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offscreen native checks; transient copy fades and device display output are outside this test. */
@RunWith(AndroidJUnit4::class)
class StandbyMediaReadabilityTest {
    /** Checks actual foreground colors against the host's composited media pixels. */
    @Test
    fun mediaContrastCoversVisibleTextAcrossThemesAndStates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = pinnedContext(240, 1f)
            val content = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888)
            val base = StandbyForegroundState(
                phase = ScreenPhase.ADVERTISING,
                advertisingEnabled = true,
                receiverName = "Test receiver",
                clockText = "12:34",
                copyMotionAllowed = false,
                isProjecting = false,
            )
            val states = listOf(
                base,
                base.copy(phase = ScreenPhase.ARMING),
                base.copy(phase = ScreenPhase.CONNECTING),
                base.copy(
                    phase = ScreenPhase.READY,
                    advertisingEnabled = false,
                    failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
                    failureText = "Timeout",
                ),
                base.copy(phase = ScreenPhase.FROZEN, shouldOfferShizuku = true),
                base.copy(phase = ScreenPhase.WIFI_PAUSED),
            )
            try {
                for (theme in themes()) {
                    val host = BigStandbyHost(context, callbacks())
                    host.setRuntimeVisible(false)
                    host.mediaUnderlay.setBitmap(content)
                    host.setMediaVisible(true)
                    for (state in states) {
                        host.render(theme, state, state.phase.name, "Standby", "Details")
                        layout(host)
                        val children = (0 until host.childCount).map(host::getChildAt)
                        val scenes = children.filter {
                            it is LandscapeSceneView || it is MatterParticleView || it is HardlineSceneView
                        }
                        assertEquals(3, scenes.size)
                        assertTrue(scenes.all { it.visibility == View.GONE })
                        val foreground = children.filterIsInstance<StandbyForegroundView>()
                            .single { it.visibility == View.VISIBLE }
                        val visible = visibleViews(foreground)
                        // Frozen compact-wide hides receiver group but retains raw receiver text.
                        if (theme == StandbyThemeId.LANDSCAPE) {
                            val retained = requireNotNull(foreground.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                            assertEquals(state.receiverName, retained.text.toString())
                            assertEquals(View.GONE, (retained.parent as View).visibility)
                        }
                        val required = mutableListOf(
                            if (theme == StandbyThemeId.MATTER) StandbyForegroundTags.PHASE_EYEBROW
                            else StandbyForegroundTags.PHASE_TITLE,
                            if (theme == StandbyThemeId.MATTER) "foreground_hint_title"
                            else if (theme == StandbyThemeId.HARDLINE) StandbyForegroundTags.RECEIVER_NAME else StandbyForegroundTags.PHASE_EYEBROW,
                        )
                        if (theme == StandbyThemeId.MATTER) required.add(StandbyForegroundTags.CLOCK)
                        when (state.phase) {
                            ScreenPhase.READY -> required.addAll(
                                listOf(StandbyForegroundTags.FAILED, StandbyForegroundTags.FAILED_DISMISS),
                            )
                            ScreenPhase.FROZEN -> required.addAll(
                                listOf(StandbyForegroundTags.FROZEN_INFO, "foreground_shizuku"),
                            )
                            ScreenPhase.WIFI_PAUSED -> required.addAll(
                                listOf(StandbyForegroundTags.WIFI_WARNING, StandbyForegroundTags.WIFI_STOP),
                            )
                            else -> Unit
                        }
                        required.forEach { tag ->
                            assertTrue("Missing $theme ${state.phase} $tag", visible.any {
                                it is TextView && it.tag == tag && it.text.isNotBlank() && it.width > 0 && it.height > 0
                            })
                        }
                        for (color in listOf(Color.WHITE, Color.BLACK)) {
                            content.eraseColor(color)
                            val frame = capture(host)
                            foreground.visibility = View.INVISIBLE
                            val backgroundFrame = try {
                                capture(host)
                            } finally {
                                foreground.visibility = View.VISIBLE
                            }
                            try {
                                val samples = listOf(1 to 1, WIDTH - 2 to 1, 1 to HEIGHT - 2, WIDTH - 2 to HEIGHT - 2)
                samples.forEach { (x, y) ->
                    assertEquals(
                        "$theme ${state.phase} background opaque at $x,$y",
                        0xFF,
                        backgroundFrame.getPixel(x, y) ushr 24,
                    )
                    assertEquals(
                        "$theme ${state.phase} frame opaque at $x,$y",
                        0xFF,
                        frame.getPixel(x, y) ushr 24,
                    )
                }
                
                                visible.filterIsInstance<TextView>().filter {
                                    it.text.isNotBlank() && it.width > 0 && it.height > 0
                                }.forEach { text ->
                                    val bounds = Rect(0, 0, text.width, text.height)
                                    host.offsetDescendantRectToMyCoords(text, bounds)
                                    val x = bounds.centerX().coerceIn(0, WIDTH - 1)
                                    val y = bounds.centerY().coerceIn(0, HEIGHT - 1)
                                    val background = backgroundFrame.getPixel(x, y)
                                    var ancestor: View? = text
                                    while (ancestor != null && ancestor !== host) {
                                        assertEquals("Transient alpha for ${text.tag}", 1f, ancestor.alpha, 0.001f)
                                        ancestor = ancestor.parent as? View
                                    }
                                    val contrast = ColorUtils.calculateContrast(text.currentTextColor, background)
                                    assertTrue("$theme ${state.phase} ${text.tag}: $contrast", contrast >= 4.5)
                                }
                            } finally {
                                backgroundFrame.recycle()
                                frame.recycle()
                            }
                        }
                    }
                }
            } finally {
                content.recycle()
            }
        }
    }

    /** Checks no-media restoration and that the mask adds no interactive or accessible control. */
    @Test
    fun togglingMediaRestoresTheOriginalLayersAndControls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val state = StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = "Test receiver")
            val content = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                for (theme in themes()) {
                    val host = BigStandbyHost(context, callbacks())
                    host.setRuntimeVisible(false)
                    host.render(theme, state, "Ready", "Standby", "Details")
                    layout(host)
                    val underlay = host.mediaUnderlay
                    val scrim = host.getChildAt(host.indexOfChild(underlay) + 1)
                    val controls = visibleViews(host).filter { it.isClickable && it.isFocusable }.toSet()
                    assertTrue(controls.isNotEmpty())
                    assertEquals(View.GONE, scrim.visibility)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, scrim.importantForAccessibility)
                    assertFalse(scrim.isClickable)
                    assertFalse(scrim.isFocusable)
                    for (withBitmap in listOf(false, true)) {
                        host.mediaUnderlay.setBitmap(if (withBitmap) content else null)
                        host.setMediaVisible(true)
                        layout(host)
                        assertEquals(View.VISIBLE, scrim.visibility)
                        assertEquals(controls, visibleViews(host).filter { it.isClickable && it.isFocusable }.toSet())
                        host.setMediaVisible(false)
                        layout(host)
                        assertEquals(controls, visibleViews(host).filter { it.isClickable && it.isFocusable }.toSet())
                        assertEquals(View.GONE, scrim.visibility)
                        assertSame(underlay, host.mediaUnderlay)
                        assertSame(host, underlay.parent)
                    }
                }
            } finally {
                content.recycle()
            }
        }
    }

    /** Checks the retained underlay stays undimmed in an editor-owned parent. */
    @Test
    fun reparentingLeavesTheEditorPreviewUndimmed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val host = BigStandbyHost(context, callbacks())
            val content = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                val framing = StandbyFraming(0.75f, 0.5f, 2f)
                host.mediaUnderlay.setBitmap(content)
                host.mediaUnderlay.setFraming(framing)
                host.render(StandbyThemeId.MATTER, StandbyForegroundState(ScreenPhase.READY, false), "Ready", "Standby", "")
                host.setRuntimeVisible(false)
                host.setMediaVisible(true)
                layout(host)
                val underlay = host.mediaUnderlay
                val scrim = host.getChildAt(host.indexOfChild(underlay) + 1)
                val masked = capture(host)
                try {
                    assertEquals(Color.rgb(57, 57, 57), masked.getPixel(1, 1))
                } finally {
                    masked.recycle()
                }
                val editor = FrameLayout(context)
                val preview = host.takeMediaPreview()
                assertSame(underlay, preview)
                assertSame(host, scrim.parent)
                editor.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                layout(editor)
                val unmasked = capture(editor)
                try {
                    assertEquals(Color.WHITE, unmasked.getPixel(1, 1))
                    assertEquals(framing, preview.currentFraming())
                } finally {
                    unmasked.recycle()
                }
                host.restoreMediaPreview()
                layout(host)
                assertSame(host, underlay.parent)
                assertSame(underlay, host.getChildAt(0))
                assertSame(scrim, host.getChildAt(1))
                assertEquals(framing, underlay.currentFraming())
                val restored = capture(host)
                try {
                    assertEquals(Color.rgb(57, 57, 57), restored.getPixel(1, 1))
                } finally {
                    restored.recycle()
                }
            } finally {
                content.recycle()
            }
        }
    }

    private fun pinnedContext(densityDpi: Int, fontScale: Float = 1f): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.densityDpi = densityDpi
            this.fontScale = fontScale
            setLocale(Locale.ENGLISH)
        })
    }

    /** @return Supported standby themes. */
    private fun themes() = listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.MATTER, StandbyThemeId.HARDLINE)

    /** @return Callback-backed controls for exercising notices and actions. */
    private fun callbacks() = StandbyForegroundCallbacks(
        onToggleBroadcast = {},
        onDismissFailure = {},
        onOpenShizuku = {},
        onShowFrozenInfo = {},
        onOpenSettings = {},
        onStopBroadcastWhilePaused = {},
    )

    /**
     * Measures and lays out an offscreen view at a fixed native viewport.
     * @param view View to lay out.
     */
    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    /**
     * Draws the view into an opaque native bitmap.
     * @param view View to draw.
     * @return Caller-owned bitmap.
     */
    private fun capture(view: View): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK)
        view.draw(Canvas(this))
    }

    /**
     * Finds descendants whose entire path from the supplied root is visible.
     * @param view Root view.
     * @return Visible views including the root when visible.
     */
    private fun visibleViews(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { visibleViews(view.getChildAt(it)) }
        } else {
            emptyList()
        }
    }

    private companion object {
        const val WIDTH = 1400
        const val HEIGHT = 1050
    }
}
