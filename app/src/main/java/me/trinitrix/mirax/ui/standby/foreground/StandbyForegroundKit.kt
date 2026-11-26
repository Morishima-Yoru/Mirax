package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import kotlin.math.roundToInt

/**
 * Shared presentational building blocks for the three standby foregrounds.
 *
 * Holds no session, clock, or navigation truth: callers pass an already
 * resolved [StandbyForegroundState] plus an already localized [phaseTitle],
 * [phaseEyebrow], and [phaseDetail]. Single-line receiver names use real
 * ellipsis and never auto-shrink text.
 */
internal object StandbyForegroundKit {
    /** Prototype `.device-name` nominal size; native font metrics remain device-dependent. */
    const val RECEIVER_TEXT_SP = 16f
    /** Nominal title size from the final prototype override. */
    const val TITLE_TEXT_SP = 40f
    /** Nominal Matter status and clock sizes from the final prototype override. */
    const val MATTER_STATUS_TEXT_SP = 20f
    const val MATTER_CLOCK_TEXT_SP = 11f

    /** Prototype Matter status light: 9dp circle (prototype `.c-status-light`). */
    const val MATTER_LIGHT_DP = 9f

    /** Requested minimum touch target for interactive controls. */
    const val MIN_TOUCH_DP = 48f

    /**
     * Maps the real phase and failure presentation to the prototype
     * status-light color. FAILED/FROZEN red only when the host proves that
     * state; CONNECTING white; ADVERTISING green; otherwise neutral gray.
     *
     * @param phase real screen phase.
     * @param failureCause genuine cause when the host presentation is Shown.
     * @return ARGB status color from the prototype palette.
     */
    fun matterLightColor(phase: ScreenPhase, failureCause: PrePlayFailureCause?): Int {
        if (failureCause != null || phase == ScreenPhase.FROZEN) {
            // Prototype `.v-C[data-phase=failed|frozen] .c-status-light`: #ff5959.
            return 0xFFFF5959.toInt()
        }
        return when (phase) {
            // Prototype `.v-C[data-phase=waiting] .c-status-light`: #7ef59b.
            ScreenPhase.ADVERTISING -> 0xFF7EF59B.toInt()
            ScreenPhase.CONNECTING -> 0xFFFFFFFF.toInt()
            else -> 0xFF999999.toInt()
        }
    }

    /**
     * Builds the single-line receiver name: `maxLines = 1`, real ellipsis,
     * never auto-shrinks.
     *
     * @param context host context.
     * @return configured name view with a stable tag for host tests.
     */
    fun receiverNameView(context: Context): TextView {
        return TextView(context).apply {
            tag = StandbyForegroundTags.RECEIVER_NAME
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, RECEIVER_TEXT_SP)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.15f
            setTextColor(Color.parseColor("#EEEDE6"))
            isFocusable = false
        }
    }

    /**
     * Records the measured child rectangle in its parent's coordinates.
     *
     * @param child measured child.
     * @param left child's left edge in parent pixels.
     * @param top child's top edge in parent pixels.
     * @param out rect receiving parent-space bounds.
     */
    fun recordRect(child: View, left: Int, top: Int, out: Rect) {
        out.set(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    /** Converts dp to pixels on this display. */
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    /** Converts sp to pixels on this display. */
    fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics,
        )
    }

    /** Applies the real platform font-scale conversion before the density-normalized viewport scale. */
    fun updateTextSize(view: TextView, nominalSp: Float, viewport: StandbyViewportScale) {
        val metrics = view.resources.displayMetrics
        val pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nominalSp, metrics) * viewport.scale
        if (view.textSize != pixels) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
    }

    /**
     * Applies CSS line boxes without Android's extra font padding, keeping native glyph metrics.
     *
     * @param view text whose lines inherit the CSS leading.
     * @param nominalSp source font size, still subject to system font scaling.
     * @param viewport CSS-to-native mapping.
     * @param leading CSS line-height multiplier.
     */
    fun updateCssText(view: TextView, nominalSp: Float, viewport: StandbyViewportScale, leading: Float = 1.6f) {
        updateTextSize(view, nominalSp, viewport)
        view.includeFontPadding = false
        view.isFallbackLineSpacing = false
        val height = (view.textSize * leading).roundToInt().coerceAtLeast(1)
        val current = (view.text as? Spanned)?.getSpans(0, view.text.length, CssLineHeight::class.java)
        if (current?.singleOrNull()?.height == height || view.text.isEmpty()) return
        val text = SpannableString(view.text)
        current?.forEach(text::removeSpan)
        text.setSpan(CssLineHeight(height), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        view.text = text
    }

    /**
     * Applies Landscape's 0.10em indent to the first rendered line, never to view padding.
     *
     * @param view title after its resolved font size has been updated.
     */
    fun updateCssFirstLineIndent(view: TextView) {
        val pixels = (view.textSize * 0.10f).roundToInt()
        val current = (view.text as? Spanned)?.getSpans(0, view.text.length, CssFirstLineIndent::class.java)
        if (current?.singleOrNull()?.pixels == pixels || view.text.isEmpty()) return
        val text = SpannableString(view.text)
        current?.forEach(text::removeSpan)
        val newline = text.indexOf('\n')
        val paragraphEnd = if (newline < 0) text.length else newline + 1
        text.setSpan(CssFirstLineIndent(pixels), 0, paragraphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = text
    }

    /**
     * Extra ascent from the inherited 15px/1.6 strut around Landscape's inline-flex row.
     * Hardline's block flex row has no surrounding inline strut.
     *
     * @param eyebrow inline-flex text after its CSS font size has been applied.
     * @return top leading in native pixels, derived from the actual platform ascent/descent.
     */
    fun inlineStatusLeading(eyebrow: TextView): Int {
        fun baseline(paint: Paint): Float {
            val metrics = paint.fontMetrics
            return (paint.textSize * 1.6f - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        }
        val strut = Paint(eyebrow.paint).apply { textSize = spToPx(eyebrow.context, 15f) }
        return (baseline(strut) - baseline(eyebrow.paint)).toInt().coerceAtLeast(0)
    }

    /** Updates the shared brand without replacing its native children. */
    fun updateBrand(brand: LinearLayout, viewport: StandbyViewportScale) {
        val mark = brand.getChildAt(0)
        val markParams = mark.layoutParams
        val size = viewport.roundedPx(30f)
        if (markParams.width != size || markParams.height != size) {
            markParams.width = size
            markParams.height = size
            mark.layoutParams = markParams
        }
        val textColumn = brand.getChildAt(1) as LinearLayout
        val label = textColumn.getChildAt(0) as TextView
        val version = textColumn.getChildAt(1) as TextView
        if (viewport.cssPixels) {
            updateCssText(label, 18f, viewport, 1f)
            updateCssText(version, 8f, viewport, 1f)
            version.setTextColor(0xFFF3F2EB.toInt())
        } else {
            updateTextSize(label, 18f, viewport)
            updateTextSize(version, 8f, viewport)
        }
        val versionParams = version.layoutParams as LinearLayout.LayoutParams
        val versionGap = viewport.roundedPx(7f)
        if (versionParams.topMargin != versionGap) {
            versionParams.topMargin = versionGap
            version.layoutParams = versionParams
        }
        val columnParams = textColumn.layoutParams as LinearLayout.LayoutParams
        val gap = viewport.roundedPx(8f)
        if (columnParams.marginStart != gap) {
            columnParams.marginStart = gap
            textColumn.layoutParams = columnParams
        }
    }

    /** Adds the current safe insets to fixed base padding. */
    fun applySafePadding(view: View, base: Rect, left: Int, top: Int, right: Int, bottom: Int) {
        view.setPadding(
            base.left + left,
            base.top + top,
            base.right + right,
            base.bottom + bottom,
        )
    }

    /** Brand mark, name, and the prototype's simulated version subtext. */
    fun brandView(context: Context): LinearLayout {
        val size = dpToPx(context, 30f).toInt()
        val label = TextView(context).apply {
            text = context.getString(R.string.standby_foreground_brand)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, 650, false)
            setTextColor(0xFFF3F2EB.toInt())
            letterSpacing = 0.19f
            isFocusable = false
        }
        // Prototype `<div class="brand-name">MIRAX<small>v0.0.4</small></div>`.
        val versionLabel = TextView(context).apply {
            text = "v0.0.4"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            letterSpacing = 0.25f
            setTextColor(0xFFCBD0CB.toInt())
            isFocusable = false
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(label, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(versionLabel, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPx(context, 7f).toInt() })
        }
        return LinearLayout(context).apply {
            tag = StandbyForegroundTags.BRAND
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(StandbyBrandMark(context), LinearLayout.LayoutParams(size, size))
            addView(textColumn, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dpToPx(context, 8f).toInt() })
        }
    }
}

/** Platform leading margins are integer pixels; nearest rounding limits the em error to 0.5px. */
private class CssFirstLineIndent(val pixels: Int) : LeadingMarginSpan.Standard(pixels, 0)

/** CSS leading is split around the platform's own ascent and descent, including the last line. */
private class CssLineHeight(val height: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        metrics: Paint.FontMetricsInt,
    ) {
        val extra = height - (metrics.descent - metrics.ascent)
        metrics.ascent -= extra / 2
        metrics.descent = metrics.ascent + height
        metrics.top = metrics.ascent
        metrics.bottom = metrics.descent
    }
}

/** The five paths of the prototype brand symbol, without the simulation version. */
private class StandbyBrandMark(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F2EB.toInt()
        style = android.graphics.Paint.Style.FILL
    }
    private val path = android.graphics.Path().apply {
        moveTo(26.1f, 23f); lineTo(31.9f, 23f); lineTo(51.8f, 42.9f); lineTo(43.9f, 50.8f); lineTo(26.1f, 32.8f); close()
        moveTo(76f, 23.1f); lineTo(81.9f, 23.1f); lineTo(81.9f, 32.8f); lineTo(64.2f, 50.5f); lineTo(56.3f, 43f); close()
        moveTo(26.1f, 73.6f); lineTo(44f, 55.7f); lineTo(51.7f, 63.5f); lineTo(32f, 83.3f); lineTo(26.1f, 83.3f); close()
        moveTo(64f, 55.7f); lineTo(82f, 73.6f); lineTo(82f, 83.4f); lineTo(76.1f, 83.4f); lineTo(56.2f, 63.4f); close()
        moveTo(54f, 47.5f); lineTo(60.2f, 53.4f); lineTo(54f, 59.4f); lineTo(47.8f, 53.4f); close()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / 108f, height / 108f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
}

/** Stable view tags shared by the three foreground candidates and host tests. */
internal object StandbyForegroundTags {
    const val BRAND = "foreground_brand"
    const val RECEIVER_LABEL = "foreground_receiver_label"
    const val RECEIVER_NAME = "foreground_receiver_name"
    const val PHASE_TITLE = "foreground_phase_title"
    const val PHASE_EYEBROW = "foreground_phase_eyebrow"
    const val PHASE_DETAIL = "foreground_phase_detail"
    const val CLOCK = "foreground_clock"
    const val FAILED = "foreground_failed"
    const val FAILED_DISMISS = "foreground_failed_dismiss"
    const val FROZEN_INFO = "foreground_frozen_info"
    const val WIFI_WARNING = "foreground_wifi_warning"
    const val WIFI_STOP = "foreground_wifi_stop"
    const val BROADCAST = "foreground_broadcast"
    const val MENU = "foreground_menu"
    const val MATTER_PLATE = "foreground_matter_plate"
    const val MATTER_LIGHT = "foreground_matter_light"
}

/**
 * Common base for the three foreground candidates.
 *
 * Owns safe-inset padding, the host-provided state/callbacks pair, and the
 * 1600x1000 stage mapping shared with [me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView]
 * so Hardline rects are reported in the same original space.
 *
 * @param context host context.
 */
abstract class StandbyForegroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    protected var foregroundState: StandbyForegroundState = StandbyForegroundState(
        phase = ScreenPhase.READY,
        advertisingEnabled = false,
    )
    protected var foregroundCallbacks: StandbyForegroundCallbacks = StandbyForegroundCallbacks()
    protected var phaseTitle: String = ""
    protected var phaseEyebrow: String = ""
    protected var phaseDetail: String = ""
    protected var viewport = StandbyViewportScale(0, 0)
        private set

    /** Matter retains legacy area units unless a theme explicitly opts into CSS pixels. */
    protected open val useCssPixels: Boolean = false

    /** Updates presentation units from the full measured stage before insets. */
    protected fun updateViewport(width: Int, height: Int) {
        viewport = StandbyViewportScale(width, height, resources.displayMetrics.density, useCssPixels)
    }

    /** Converts a nominal prototype dimension to view pixels. */
    protected fun unit(value: Float): Float = viewport.px(value)

    protected var safeLeft: Int = 0
    protected var safeTop: Int = 0
    protected var safeRight: Int = 0
    protected var safeBottom: Int = 0
    private var basePadding: Rect? = null
    private data class CopyChange(
        val text: SpannedString,
        val hardline: Boolean,
        val startAlpha: Float,
        var phaseStartMillis: Long,
        var fadingIn: Boolean = false,
    )
    private var copyChanges: MutableMap<TextView, CopyChange>? = null
    private val copyFadeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private val copyFadeIn = PathInterpolator(0.42f, 0f, 1f, 1f)
    private var copyFrameScheduled = false
    private val copyFrameCallback = Choreographer.FrameCallback {
        copyFrameScheduled = false
        advanceCopyChanges(SystemClock.uptimeMillis())
    }
    private var hasCompletedLayout = false
    protected var foregroundLayoutInProgress = false
        private set
    private var observing = false
    private var foregroundObserver: android.view.ViewTreeObserver? = null
    private var motionLayerReady = false
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        syncMotionVisibility()
        if (!isInLayout && !foregroundLayoutInProgress) onForegroundGeometryCheck()
        true
    }

    /** Move this layer for scene motion; sibling controls retain their hit targets. */
    val motionLayer: FrameLayout = object : FrameLayout(context) {
        /**
         * Fills constrained space without remeasuring children owned by the foreground themes.
         *
         * @param widthMeasureSpec horizontal constraint from the parent.
         * @param heightMeasureSpec vertical constraint from the parent.
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                View.getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
            )
        }

        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            if (motionLayerReady) syncMotionVisibility()
        }

        override fun setAlpha(alpha: Float) {
            super.setAlpha(alpha)
            if (motionLayerReady) syncMotionVisibility()
        }
    }

    init {
        addView(motionLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        motionLayerReady = true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    /**
     * Applies host-provided insets relative to the padding captured on the first call.
     * Each call replaces the previous insets.
     *
     * Call on the UI thread. Callers must avoid applying the same insets to both an ancestor and this view.
     *
     * @param insets security insets in native view pixels; [Insets.NONE] restores the base padding.
     */
    fun applySecurityInsets(insets: Insets) {
        val base = basePadding ?: Rect(paddingLeft, paddingTop, paddingRight, paddingBottom).also { basePadding = it }
        safeLeft = insets.left
        safeTop = insets.top
        safeRight = insets.right
        safeBottom = insets.bottom
        if (isPaddingRelative || paddingLeft != base.left + safeLeft || paddingTop != base.top + safeTop ||
            paddingRight != base.right + safeRight || paddingBottom != base.bottom + safeBottom
        ) {
            StandbyForegroundKit.applySafePadding(this, base, safeLeft, safeTop, safeRight, safeBottom)
        }
    }

    /**
     * Renders only host-provided truth; never invents broadcast or failure facts.
     *
     * @param state resolved host truth.
     * @param title localized title for [state]'s phase, from the host.
     * @param eyebrow localized eyebrow for [state]'s phase, from the host.
     * @param detail localized detail for [state]'s phase, from the host.
     * @param callbacks explicit host actions; null entries hide controls.
     */
    open fun render(
        state: StandbyForegroundState,
        title: String,
        eyebrow: String,
        detail: String,
        callbacks: StandbyForegroundCallbacks,
    ) {
        foregroundState = state
        foregroundCallbacks = callbacks
        phaseTitle = title
        phaseEyebrow = eyebrow
        phaseDetail = detail
        if (state.isProjecting != false || !state.copyMotionAllowed) finishCopyChanges()
        requestLayout()
    }

    /**
     * Freezes characters and span ranges; source spans must be immutable and value-equal.
     * Only the layout-owned line-height and first-line indent decorators are excluded from copy equality.
     *
     * @param text plain copy or immutable source-formatting spans.
     * @return immutable content snapshot using platform span/range/flag equality.
     */
    private fun copySnapshot(text: CharSequence): SpannedString {
        val snapshot = SpannableString(text)
        snapshot.getSpans(0, snapshot.length, CssLineHeight::class.java).forEach(snapshot::removeSpan)
        snapshot.getSpans(0, snapshot.length, CssFirstLineIndent::class.java).forEach(snapshot::removeSpan)
        return SpannedString(snapshot)
    }

    /** Replaces plain or styled prototype copy with its original fade-out and fade-in timings. */
    protected fun replaceCopy(view: TextView, text: CharSequence, hardline: Boolean) {
        val target = copySnapshot(text)
        val changes = copyChanges ?: mutableMapOf<TextView, CopyChange>().also { copyChanges = it }
        val previous = changes[view]
        if (previous != null && previous.text == target) return
        if (previous == null && copySnapshot(view.text) == target) return
        changes.remove(view)
        val change = CopyChange(target, hardline, view.alpha, SystemClock.uptimeMillis())
        if (!isMotionContentVisible() || !foregroundState.copyMotionAllowed ||
            foregroundState.isProjecting != false || view.visibility != View.VISIBLE
        ) {
            applyCopy(view, change)
            if (changes.isEmpty()) cancelCopyFrame()
            return
        }
        changes[view] = change
        scheduleCopyFrame()
    }

    /**
     * Advances copy transitions independently of animator duration scale.
     * @param nowMillis monotonic time in milliseconds.
     */
    private fun advanceCopyChanges(nowMillis: Long) {
        if (!isMotionContentVisible() || !foregroundState.copyMotionAllowed || foregroundState.isProjecting != false) {
            finishCopyChanges()
            return
        }
        val changes = copyChanges ?: return
        val iterator = changes.iterator()
        while (iterator.hasNext()) {
            val (view, change) = iterator.next()
            if (view.visibility != View.VISIBLE) {
                applyCopy(view, change)
                iterator.remove()
                continue
            }
            val duration = if (change.hardline) 90L else if (change.fadingIn) 180L else 150L
            val elapsed = (nowMillis - change.phaseStartMillis).coerceAtLeast(0L)
            val fraction = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            if (change.fadingIn) {
                view.alpha = copyFadeIn.getInterpolation(fraction)
                if (elapsed >= duration) iterator.remove()
            } else {
                view.alpha = change.startAlpha * (1f - copyFadeOut.getInterpolation(fraction))
                if (elapsed >= duration) {
                    applyCopy(view, change)
                    if (change.text.isEmpty()) {
                        iterator.remove()
                    } else {
                        view.alpha = 0f
                        change.fadingIn = true
                        change.phaseStartMillis = nowMillis
                    }
                }
            }
        }
        if (changes.isEmpty()) cancelCopyFrame() else scheduleCopyFrame()
    }

    /** Schedules one frame while copy transitions remain active. */
    private fun scheduleCopyFrame() {
        if (copyFrameScheduled) return
        copyFrameScheduled = true
        Choreographer.getInstance().postFrameCallback(copyFrameCallback)
    }

    /** Cancels the pending copy frame, if any. */
    private fun cancelCopyFrame() {
        if (!copyFrameScheduled) return
        Choreographer.getInstance().removeFrameCallback(copyFrameCallback)
        copyFrameScheduled = false
    }

    private fun applyCopy(view: TextView, change: CopyChange) {
        view.text = change.text
        view.alpha = 1f
    }

    private fun finishCopyChanges() {
        cancelCopyFrame()
        val changes = copyChanges ?: return
        val pending = changes.toMap()
        changes.clear()
        pending.forEach { (view, change) -> applyCopy(view, change) }
    }

    /** Returns whether the moving layer and all its ancestors can be drawn. */
    protected fun isMotionContentVisible(): Boolean {
        if (!observing || !isAttachedToWindow || windowVisibility != View.VISIBLE) return false
        var current: View? = motionLayer
        while (current != null) {
            if (current.visibility != View.VISIBLE || !(current.alpha > 0f)) return false
            current = current.parent as? View
        }
        return true
    }

    /** Synchronizes a subclass's motion with the visibility of the moving layer. */
    protected open fun onMotionContentVisibilityChanged() = Unit

    /** Checks geometry after the complete tree layout, or after detachment. */
    protected open fun onForegroundGeometryCheck() = Unit

    private fun syncMotionVisibility() {
        if (!motionLayerReady) return
        if (!isMotionContentVisible()) finishCopyChanges()
        onMotionContentVisibilityChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observing = true
        foregroundObserver = viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
        syncMotionVisibility()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncMotionVisibility()
        if (motionLayerReady && !isInLayout && !foregroundLayoutInProgress && !isLayoutRequested) {
            onForegroundGeometryCheck()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncMotionVisibility()
        if (motionLayerReady && !isInLayout && !foregroundLayoutInProgress &&
            (visibility != View.VISIBLE || !isLayoutRequested)
        ) {
            onForegroundGeometryCheck()
        }
    }

    override fun onDetachedFromWindow() {
        val previousRoot = rootView
        observing = false
        foregroundObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(preDrawListener) }
        foregroundObserver = null
        syncMotionVisibility()
        if (isInLayout || foregroundLayoutInProgress) {
            previousRoot.post { if (!observing) onForegroundGeometryCheck() }
        } else {
            onForegroundGeometryCheck()
        }
        super.onDetachedFromWindow()
    }

    override fun requestLayout() {
        hasCompletedLayout = false
        super.requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        hasCompletedLayout = false
        foregroundLayoutInProgress = true
        super.onLayout(changed, left, top, right, bottom)
    }

    /** Marks completion only after a subclass has placed every foreground child. */
    protected fun completeForegroundLayout() {
        hasCompletedLayout = true
        foregroundLayoutInProgress = false
    }

    /** Measures an already-mounted child inside the current safe content area. */
    protected fun measureForeground(child: View, maxWidth: Int, maxHeight: Int, fillWidth: Boolean = false) {
        child.measure(
            MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), if (fillWidth) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(0), MeasureSpec.AT_MOST),
        )
    }

    /** Positions a child of [motionLayer] without changing its instance. */
    protected fun placeForeground(child: View, left: Int, top: Int) {
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }

    /**
     * @param child Laid-out descendant.
     * @return Its source layout allocation in foreground pixels, before transforms.
     */
    protected fun foregroundLayoutBounds(child: View): Rect = Rect(0, 0, child.width, child.height).also {
        offsetDescendantRectToMyCoords(child, it)
    }

    /**
     * @param text Laid-out text descendant.
     * @param obstacle Rectangle in foreground coordinates.
     * @param offsetX Horizontal ambient offset from the source allocation.
     * @param offsetY Vertical ambient offset from the source allocation.
     * @return Whether an occupied line box, rather than the TextView's full allocation, intersects.
     */
    protected fun intersectsForegroundLine(
        text: TextView,
        obstacle: Rect,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): Boolean {
        if (text.visibility != VISIBLE || text.text.isEmpty()) return false
        val layout = text.layout ?: return false
        val bounds = foregroundLayoutBounds(text)
        val x = bounds.left + text.compoundPaddingLeft + offsetX
        val y = bounds.top + text.totalPaddingTop + offsetY
        return (0 until layout.lineCount).any { line ->
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            right > left && x + left < obstacle.right && x + right > obstacle.left &&
                y + layout.getLineTop(line) < obstacle.bottom && y + layout.getLineBottom(line) > obstacle.top
        }
    }

    /**
     * Applies the actual drift inside a fixed clip, consuming only the room available on each axis.
     * @param child Direct child of the unshifted motion layer.
     * @param lane Available rectangle in foreground pixels.
     * @param x Requested ambient offset on the horizontal axis.
     * @param y Requested ambient offset on the vertical axis.
     */
    protected fun driftWithin(child: View, lane: Rect, x: Float, y: Float) {
        val left = motionLayer.left + child.left
        val top = motionLayer.top + child.top
        child.translationX = x.coerceIn((lane.left - left).toFloat(),
            (maxOf(lane.left, lane.right - child.width) - left).toFloat())
        child.translationY = y.coerceIn((lane.top - top).toFloat(),
            (maxOf(lane.top, lane.bottom - child.height) - top).toFloat())
    }

    /** Prototype scene padding in pixels, excluding the already-applied insets. */
    protected fun sceneEdge(width: Int, height: Int): Int =
        (minOf(width, height) * 0.03f).coerceIn(unit(24f), unit(48f)).toInt()

    /**
     * @param child Descendant whose current visible bounds are needed.
     * @param allowPendingDockNoticeLayout Whether only the dock's direct controls parent may remain pending
     * while a notice is visible and both views' laid-out sizes still match their measurements.
     * @return Clipped foreground-local native pixel bounds, or null if unavailable.
     */
    protected fun visibleForegroundRect(child: View, allowPendingDockNoticeLayout: Boolean = false): Rect? {
        if (!observing || !isAttachedToWindow || !hasCompletedLayout || isInLayout ||
            windowVisibility != View.VISIBLE
        ) return null
        // getChildVisibleRect uses root coordinates, so cancel the root-to-screen transform first.
        val rootToScreen = Matrix().also { rootView.transformMatrixToGlobal(it) }
        val screenToRoot = Matrix()
        if (!rootToScreen.invert(screenToRoot)) return null
        // Measuring a side notice can leave its controls pending after the dock has been laid out.
        val pendingDockControls = if (allowPendingDockNoticeLayout) {
            (child.parent as? StandbyForegroundControls)?.takeIf { controls ->
                controls.parent === this && controls.broadcast === child && controls.isLayoutRequested &&
                    controls.width == controls.measuredWidth && controls.height == controls.measuredHeight &&
                    child.width == child.measuredWidth && child.height == child.measuredHeight &&
                    controls.hasVisibleNotice()
            }
        } else null
        var current: View? = child
        var isDescendant = false
        var outlineClip: RectF? = null
        // shortcut: non-rectilinear transforms and non-rectangular outlines return null, use shape clipping to support them.
        while (current != null) {
            if (!current.isLaidOut || (current.isLayoutRequested && current !== pendingDockControls) ||
                current.visibility != View.VISIBLE || !(current.alpha > 0f) || current.width <= 0 || current.height <= 0 ||
                !current.matrix.rectStaysRect()
            ) return null
            if (current.clipToOutline) {
                val outline = Outline()
                val bounds = Rect()
                val provider = current.outlineProvider ?: return null
                provider.getOutline(current, outline)
                if (outline.isEmpty || !outline.getRect(bounds) || outline.radius != 0f || bounds.isEmpty) return null
                val ancestorToScreen = Matrix()
                current.transformMatrixToGlobal(ancestorToScreen)
                val ancestorToRoot = Matrix().also { it.setConcat(screenToRoot, ancestorToScreen) }
                if (!ancestorToRoot.rectStaysRect() || !ancestorToRoot.invert(Matrix())) return null
                val mappedOutline = RectF(bounds)
                ancestorToRoot.mapRect(mappedOutline)
                if (outlineClip == null) outlineClip = mappedOutline
                else if (!outlineClip.intersect(mappedOutline)) return null
            }
            if (current === this) isDescendant = true
            current = current.parent as? View
        }
        if (!isDescendant) return null
        val global = Rect(0, 0, child.width, child.height)
        val clip = child.clipBounds
        if (clip != null && !global.intersect(clip)) return null
        val parent = child.parent ?: return null
        if (!parent.getChildVisibleRect(child, global, null)) return null
        val localToScreen = Matrix().also { transformMatrixToGlobal(it) }
        val localToRoot = Matrix().also { it.setConcat(screenToRoot, localToScreen) }
        val rootToLocal = Matrix()
        if (!localToRoot.rectStaysRect() || !localToRoot.invert(rootToLocal)) return null
        val mapped = RectF(global)
        if (outlineClip != null && !mapped.intersect(outlineClip)) return null
        rootToLocal.mapRect(mapped)
        val result = Rect()
        mapped.roundOut(result)
        return result.takeUnless { it.isEmpty }
    }

    /** Updates the host-formatted clock without rebuilding controls. */
    fun updateClock(clockText: String) {
        foregroundState = foregroundState.copy(clockText = clockText)
        onClockChanged(clockText)
    }

    /** Updates only the host-projected failure presentation and dismiss control. */
    fun updateFailure(cause: PrePlayFailureCause?, text: String) {
        foregroundState = foregroundState.copy(failureCause = cause, failureText = text)
        onFailureChanged(cause, text)
    }

    protected open fun onClockChanged(clockText: String) = Unit

    protected open fun onFailureChanged(cause: PrePlayFailureCause?, text: String) = Unit

    /**
     * `xMidYMid slice` mapping identical to the Hardline background so rects
     * below are reported in the original 1600x1000 stage space.
     *
     * @param viewWidth view width in px.
     * @param viewHeight view height in px.
     * @return uniform scale plus stage offsets in px.
     */
    protected fun stageSlice(viewWidth: Double, viewHeight: Double): StandbyStageSlice {
        val scale = maxOf(viewWidth / STAGE_WIDTH, viewHeight / STAGE_HEIGHT)
        return StandbyStageSlice(
            scale = scale,
            offsetX = (viewWidth - STAGE_WIDTH * scale) / 2.0,
            offsetY = (viewHeight - STAGE_HEIGHT * scale) / 2.0,
        )
    }

    companion object {
        /** Original stage width shared with the Hardline background viewBox. */
        const val STAGE_WIDTH = 1600.0
        /** Original stage height shared with the Hardline background viewBox. */
        const val STAGE_HEIGHT = 1000.0
    }
}

/**
 * Stage mapping from view pixels back to the original 1600x1000 space.
 *
 * @param scale uniform stage scale.
 * @param offsetX horizontal stage offset in px.
 * @param offsetY vertical stage offset in px.
 */
data class StandbyStageSlice(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
) {
    /** Map a view X coordinate back to original stage units. */
    fun toStageX(viewX: Double): Double = (viewX - offsetX) / scale

    /** Map a view Y coordinate back to original stage units. */
    fun toStageY(viewY: Double): Double = (viewY - offsetY) / scale
}
