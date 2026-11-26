package me.trinitrix.mirax.standbyprobe.layout

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import java.util.Locale
import kotlin.math.roundToInt
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.foreground.HardlineStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.LandscapeStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.MatterStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView

/**
 * Fixed-pixel demo fixtures, independent of the application session and device configuration.
 * @param widthPx Viewport width in physical pixels.
 * @param heightPx Viewport height in physical pixels.
 * @param density Fixture-only density multiplier.
 * @param leftInsetPx Fixture-only left safe inset in pixels.
 * @param phaseLabel Visible demo phase label.
 */
enum class S17Fixture(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val leftInsetPx: Int,
    val phaseLabel: String,
) {
    HARDLINE_TINY(320, 280, 2f, 0, "READY + FAILED"),
    MATTER_NARROW(320, 280, 2f, 0, "ADVERTISING"),
    LANDSCAPE_INSET(400, 800, 1f, 250, "ADVERTISING"),
}

/** A throwaway, native-only comparison; no host, storage, or hardware is connected. */
class StandbyS17ComparisonActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var caption: TextView
    private lateinit var fixture: S17Fixture
    private var proposed = false
    private var demoAction = "none"

    /** Current fixed-pixel viewport, not the device's physical display. */
    lateinit var viewport: FrameLayout
        private set

    /** The real foreground used for this fixture, including in the rearranged proposal. */
    lateinit var foreground: StandbyForegroundView
        private set

    /** Outer proposed foreground scroll, absent from the current layout. */
    var proposalScroll: ScrollView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.BLACK)
        }
        caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "DEMO only: waiting for fixture"
        }
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    /**
     * Replaces only the in-memory preview with one explicitly labelled fixture.
     * @param selected Fixed-pixel fixture.
     * @param isProposal Whether to show the unapproved proposal.
     */
    fun showFixture(selected: S17Fixture, isProposal: Boolean) {
        fixture = selected
        proposed = isProposal
        demoAction = "none"
        if (::viewport.isInitialized) root.removeView(viewport)
        proposalScroll = null
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = (selected.density * 160f).roundToInt()
            fontScale = 1f
            setLocale(Locale.ENGLISH)
        }
        val previewContext = createConfigurationContext(configuration)
        viewport = FrameLayout(previewContext).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.BLACK)
        }
        val scene = createScene(previewContext, selected)
        viewport.addView(scene, FrameLayout.LayoutParams(-1, -1))
        foreground = when (selected) {
            S17Fixture.HARDLINE_TINY -> HardlineStandbyForegroundView(previewContext)
            S17Fixture.MATTER_NARROW -> MatterStandbyForegroundView(previewContext)
            S17Fixture.LANDSCAPE_INSET -> LandscapeStandbyForegroundView(previewContext)
        }
        foreground.applySecurityInsets(Insets.of(selected.leftInsetPx, 0, 0, 0))
        var state = initialState(selected)
        fun render() {
            val title = if (state.advertisingEnabled) "ADVERTISING" else "READY"
            val callbacks = StandbyForegroundCallbacks(
                onToggleBroadcast = {
                    state = state.copy(
                        phase = if (state.advertisingEnabled) ScreenPhase.READY else ScreenPhase.ADVERTISING,
                        advertisingEnabled = !state.advertisingEnabled,
                    )
                    demoAction = "toggle (demo only)"
                    render()
                    updateCaption(0)
                },
                onDismissFailure = {
                    state = state.copy(failureCause = null, failureText = "")
                    demoAction = "dismiss (demo only)"
                    render()
                    updateCaption(0)
                },
                onOpenSettings = {
                    demoAction = "menu (demo only)"
                    updateCaption(0)
                    AlertDialog.Builder(this)
                        .setMessage("DEMO only. No settings or session are connected.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                },
            )
            foreground.render(state, title, title, "Demo standby", callbacks)
        }
        render()
        if (isProposal && selected != S17Fixture.LANDSCAPE_INSET) {
            // Children are lifted out of the foreground below; measure/lay it out first so
            // viewport-scaled text and control sizes are the same as the non-proposal capture.
            val fixtureWidth = selected.widthPx
            val fixtureHeight = selected.heightPx
            foreground.measure(
                View.MeasureSpec.makeMeasureSpec(fixtureWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fixtureHeight, View.MeasureSpec.EXACTLY),
            )
            foreground.layout(0, 0, fixtureWidth, fixtureHeight)
            val scroll = rearrangeForTinyViewport(previewContext, foreground, selected)
            proposalScroll = scroll
            viewport.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            viewport.addView(foreground, FrameLayout.LayoutParams(-1, -1))
            if (isProposal) clampDockToSafeArea(foreground, selected)
        }
        root.addView(viewport, LinearLayout.LayoutParams(selected.widthPx, selected.heightPx))
        updateCaption(0)
    }

    /**
     * Updates the visible evidence page and its actual scroll position.
     * @param page Zero-based captured page.
     */
    fun updateCaption(page: Int) {
        val scroll = proposalScroll
        val maxScroll = scroll?.let { (it.getChildAt(0).height - it.height).coerceAtLeast(0) } ?: 0
        val scrollY = scroll?.scrollY ?: 0
        val variant = if (proposed) "未批准提案" else "現況"
        caption.text = "DEMO ${fixture.name} | $variant | page=$page\n" +
            "viewport=${fixture.widthPx}x${fixture.heightPx}px density=${fixture.density} fontScale=1\n" +
            "insets L${fixture.leftInsetPx} T0 R0 B0 px | fixture=${fixture.phaseLabel} (DEMO, not a connection)\n" +
            "safe x=${fixture.leftInsetPx}..${fixture.widthPx} | scroll=$scrollY/$maxScroll | scene=paused | action=$demoAction"
    }

    /**
     * @param selected Demo fixture.
     * @return In-memory fixture state.
     */
    private fun initialState(selected: S17Fixture): StandbyForegroundState {
        val failed = selected == S17Fixture.HARDLINE_TINY
        return StandbyForegroundState(
            phase = if (failed) ScreenPhase.READY else ScreenPhase.ADVERTISING,
            advertisingEnabled = !failed,
            receiverName = "DEMO RX",
            clockText = "12:34",
            failureCause = if (failed) PrePlayFailureCause.ESTABLISHMENT_FAILED else null,
            failureText = if (failed) "DEMO FAILED: Connection failed. Try the connection again." else "",
            isProjecting = false,
        )
    }

    /**
     * Creates the existing native background without a runtime host.
     * @param context Fixture density context.
     * @param selected Demo fixture.
     * @return Existing theme scene.
     */
    private fun createScene(context: Context, selected: S17Fixture): View = when (selected) {
        S17Fixture.HARDLINE_TINY -> HardlineSceneView(context).apply { setProjecting(true) }
        S17Fixture.MATTER_NARROW -> MatterParticleView(context).apply {
            setMatterState(ScreenPhase.ADVERTISING, true, false)
            setProjecting(true)
        }
        S17Fixture.LANDSCAPE_INSET -> LandscapeSceneView(context).apply {
            setLandscapeState(ScreenPhase.ADVERTISING, true)
            setProjecting(true)
        }
    }

    /**
     * Rearranges original native foreground children only for the two tiny proposals.
     * @param context Fixture density context.
     * @param original Rendered native foreground supplying the actual children.
     * @param selected Tiny demo fixture.
     * @return Scrollable proposal using the original views.
     */
    private fun rearrangeForTinyViewport(
        context: Context,
        original: StandbyForegroundView,
        selected: S17Fixture,
    ): ScrollView {
        val density = context.resources.displayMetrics.density
        val gap = (8f * density).roundToInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
        }
        fun tagged(tag: String): View = requireNotNull(original.findViewWithTag<View>(tag))
        fun add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            (view.parent as ViewGroup).removeView(view)
            content.addView(view, LinearLayout.LayoutParams(width, height).apply {
                topMargin = gap
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        if (selected == S17Fixture.HARDLINE_TINY) {
            val header = tagged("foreground_brand").parent as View
            val receiver = (tagged("foreground_receiver_label").parent as View).parent as View
            val center = tagged("foreground_phase_title").parent as ViewGroup
            center.setPadding(2 * gap, gap, 2 * gap, gap)
            add(header)
            add(receiver)
            add(center)
            val failure = tagged("foreground_failed") as TextView
            failure.maxLines = Int.MAX_VALUE
            failure.ellipsize = null
            add(failure)
            val dismiss = tagged("foreground_failed_dismiss") as TextView
            dismiss.setSingleLine(false)
            dismiss.maxLines = Int.MAX_VALUE
            dismiss.ellipsize = null
            add(dismiss, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            val word = tagged("foreground_phase_eyebrow") as TextView
            word.setSingleLine(false)
            word.maxLines = Int.MAX_VALUE
            word.ellipsize = null
            add(tagged("foreground_brand"), ViewGroup.LayoutParams.WRAP_CONTENT)
            add(tagged("foreground_matter_plate"))
            add(tagged("foreground_hint"))
        }
        for (tag in listOf("foreground_broadcast", "foreground_menu")) {
            val control = tagged(tag)
            val size = control.layoutParams
            add(control, size.width, size.height)
        }
        return ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(content, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Applies only the horizontal translation required by the fixture safe area.
     * @param original Unmodified native foreground.
     * @param selected Asymmetric inset fixture.
     */
    private fun clampDockToSafeArea(original: StandbyForegroundView, selected: S17Fixture) {
        val dock = requireNotNull(original.findViewWithTag<View>("foreground_broadcast"))
        original.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val safeRight = selected.widthPx
            if (safeRight - selected.leftInsetPx >= dock.width && dock.width > 0) {
                val dockLocation = IntArray(2)
                val viewportLocation = IntArray(2)
                dock.getLocationInWindow(dockLocation)
                viewport.getLocationInWindow(viewportLocation)
                val originalLeft = dockLocation[0] - viewportLocation[0] - dock.translationX.roundToInt()
                val clamped = originalLeft.coerceIn(selected.leftInsetPx, safeRight - dock.width)
                dock.translationX = (clamped - originalLeft).toFloat()
            } else {
                demoAction = "dock cannot fit safe width"
                updateCaption(0)
            }
        }
    }
}
