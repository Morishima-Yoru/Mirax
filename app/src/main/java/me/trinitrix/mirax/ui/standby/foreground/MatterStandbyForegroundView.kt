package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase

/**
 * Matter (C) foreground candidate.
 *
 * Displays only host-provided status, clock, failure and actions. The prototype
 * hides the receiver block in Matter; its truthful name appears in the hint.
 * Nominal typography and status-light order follow the final CSS override, but
 * the foreground layout and platform font metrics are not device-verified.
 *
 * Prototype dimensions use the shared measured-stage viewport scale.
 */
class MatterStandbyForegroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : StandbyForegroundView(context, attrs, defStyleAttr) {
    private val brand = StandbyForegroundKit.brandView(context)
    private val plate = object : LinearLayout(context) {
        private val rule = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEEEEA.toInt()
            strokeWidth = unit(2f)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            rule.strokeWidth = unit(2f)
            canvas.drawLine(width - rule.strokeWidth / 2f, 0f, width - rule.strokeWidth / 2f, height.toFloat(), rule)
        }
    }.apply {
        tag = StandbyForegroundTags.MATTER_PLATE
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.RIGHT
        setWillNotDraw(false)
        setPadding(0, 0, unit(16f).toInt(), 0)
    }
    private val wordRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
    }
    private val light = View(context).apply { tag = StandbyForegroundTags.MATTER_LIGHT }
    private val word = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_EYEBROW
        setTextSize(TypedValue.COMPLEX_UNIT_SP, StandbyForegroundKit.MATTER_STATUS_TEXT_SP)
        letterSpacing = 0.04f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(0xFFEEEEEA.toInt())
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val time = TextView(context).apply {
        tag = StandbyForegroundTags.CLOCK
        setTextSize(TypedValue.COMPLEX_UNIT_SP, StandbyForegroundKit.MATTER_CLOCK_TEXT_SP)
        letterSpacing = 0.25f
        setTextColor(Color.parseColor("#A0A69C"))
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val hintTitle = TextView(context).apply {
        tag = "foreground_hint_title"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        letterSpacing = 0.10f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#E0E4DF"))
    }
    private val hintDescription = TextView(context).apply {
        tag = "foreground_hint_description"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.08f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.parseColor("#E0E4DF"))
    }
    private val hint = LinearLayout(context).apply {
        tag = "foreground_hint"
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(View(context).apply {
            setBackgroundColor(0xFFEEEEEA.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(unit(1f).toInt().coerceAtLeast(1), LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(hintTitle, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ))
            addView(hintDescription, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = unit(6f).toInt()
            })
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = unit(14f).toInt()
        })
    }
    private val controls = StandbyForegroundControls(context, StandbyControlTheme.MATTER)
    private var stackedFooter = false
    private var blinkGeneration = 0
    private var blinkCallback: Runnable? = null
    private var blinkReady = false

    init {
        val lightPx = unit(StandbyForegroundKit.MATTER_LIGHT_DP).toInt()
        light.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        light.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        wordRow.addView(light, LinearLayout.LayoutParams(lightPx, lightPx))
        wordRow.addView(word, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = unit(14f).toInt()
        })
        plate.addView(wordRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.RIGHT
        })
        plate.addView(time, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = unit(6f).toInt()
            gravity = Gravity.RIGHT
        })
        motionLayer.addView(brand)
        motionLayer.addView(plate)
        motionLayer.addView(hint)
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        blinkReady = true
    }

    private fun updateScaledChildren() {
        StandbyForegroundKit.updateBrand(brand, viewport)
        StandbyForegroundKit.updateTextSize(word, StandbyForegroundKit.MATTER_STATUS_TEXT_SP, viewport)
        StandbyForegroundKit.updateTextSize(time, StandbyForegroundKit.MATTER_CLOCK_TEXT_SP, viewport)
        StandbyForegroundKit.updateTextSize(hintTitle, 14f, viewport)
        StandbyForegroundKit.updateTextSize(hintDescription, 11f, viewport)
        val lightParams = light.layoutParams as LinearLayout.LayoutParams
        val lightSize = viewport.roundedPx(StandbyForegroundKit.MATTER_LIGHT_DP)
        if (lightParams.width != lightSize || lightParams.height != lightSize) {
            lightParams.width = lightSize
            lightParams.height = lightSize
            light.layoutParams = lightParams
        }
        val wordParams = word.layoutParams as LinearLayout.LayoutParams
        val wordGap = viewport.roundedPx(14f)
        if (wordParams.marginStart != wordGap) {
            wordParams.marginStart = wordGap
            word.layoutParams = wordParams
        }
        val timeParams = time.layoutParams as LinearLayout.LayoutParams
        val lineGap = viewport.roundedPx(6f)
        if (timeParams.topMargin != lineGap) {
            timeParams.topMargin = lineGap
            time.layoutParams = timeParams
        }
        val hintRule = hint.getChildAt(0)
        val ruleParams = hintRule.layoutParams as LinearLayout.LayoutParams
        val ruleWidth = viewport.roundedPx(1f).coerceAtLeast(1)
        if (ruleParams.width != ruleWidth) {
            ruleParams.width = ruleWidth
            hintRule.layoutParams = ruleParams
        }
        val hintText = hint.getChildAt(1) as LinearLayout
        val hintParams = hintText.layoutParams as LinearLayout.LayoutParams
        if (hintParams.leftMargin != wordGap) {
            hintParams.leftMargin = wordGap
            hintText.layoutParams = hintParams
        }
        val descriptionParams = hintDescription.layoutParams as LinearLayout.LayoutParams
        if (descriptionParams.topMargin != lineGap) {
            descriptionParams.topMargin = lineGap
            hintDescription.layoutParams = descriptionParams
        }
        val platePadding = viewport.roundedPx(16f)
        if (plate.paddingRight != platePadding) plate.setPadding(0, 0, platePadding, 0)
        plate.invalidate()
    }

    override fun render(
        state: StandbyForegroundState,
        title: String,
        eyebrow: String,
        detail: String,
        callbacks: StandbyForegroundCallbacks,
    ) {
        super.render(state, title, eyebrow, detail, callbacks)
        time.text = state.clockText
        time.visibility = if (state.clockText.isBlank()) View.GONE else View.VISIBLE
        val canInvite = state.advertisingEnabled && state.phase == ScreenPhase.ADVERTISING &&
            state.receiverName.isNotBlank()
        val hintText = if (canInvite) context.getString(R.string.standby_foreground_hint_prefix) else detail
        hintTitle.text = state.receiverName
        hintDescription.text = hintText
        hintDescription.visibility = if (hintText.isBlank()) View.GONE else View.VISIBLE
        hint.visibility = if (state.receiverName.isBlank()) View.GONE else View.VISIBLE
        controls.update(state, callbacks)
        onFailureChanged(state.failureCause, state.failureText)
    }

    override fun onClockChanged(clockText: String) {
        time.text = clockText
        time.visibility = if (clockText.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onFailureChanged(cause: me.trinitrix.mirax.session.PrePlayFailureCause?, text: String) {
        controls.updateFailure(cause, text)
        val status = matterStatusWord(foregroundState.phase, cause)
        word.text = status
        word.contentDescription = context.getString(
            R.string.standby_foreground_matter_status_desc,
            context.getString(statusMeaningResource(foregroundState.phase, cause)),
        )
        (light.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(StandbyForegroundKit.matterLightColor(foregroundState.phase, cause))
        syncBlink()
    }

    /**
     * Selects the localized meaning independently of the fixed plate code and notice detail.
     * @param phase Current host phase.
     * @param cause Host-projected shown failure cause, or null.
     * @return The phase or generic failure title resource.
     */
    @StringRes
    private fun statusMeaningResource(phase: ScreenPhase, cause: PrePlayFailureCause?): Int {
        if (cause != null) return R.string.standby_foreground_phase_failed_title
        return when (phase) {
            ScreenPhase.READY -> R.string.standby_foreground_phase_off_title
            ScreenPhase.ARMING -> R.string.standby_foreground_phase_arming_title
            ScreenPhase.ADVERTISING -> R.string.standby_foreground_phase_waiting_title
            ScreenPhase.CONNECTING -> R.string.standby_foreground_phase_connecting_title
            ScreenPhase.CONNECTED -> R.string.standby_foreground_phase_projecting_title
            ScreenPhase.WIFI_PAUSED -> R.string.standby_foreground_phase_wifi_paused_title
            ScreenPhase.FROZEN -> R.string.standby_foreground_phase_frozen_title
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        updateViewport(viewportWidth, viewportHeight)
        updateScaledChildren()
        controls.safeBottomInset = safeBottom
        controls.updateViewport(viewportWidth, viewportHeight)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val h = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val edge = sceneEdge(measuredWidth, measuredHeight)
        val density = viewport.scale
        val corner = alignedCornerInsetPx(measuredWidth)
        val available = (w - 2 * edge).coerceAtLeast(0)
        measureForeground(brand, available, h)
        val actionHalf = controls.actionSize / 2
        val actionGap = (16f * density).toInt()
        val leftSide = matterFooterSideWidth(measuredWidth, paddingLeft + edge, corner, actionHalf, actionGap)
        val rightSide = matterFooterSideWidth(measuredWidth, paddingRight + edge, corner, actionHalf, actionGap)
        val plateCap = minOf(rightSide, viewport.px(280f).toInt())
        // Prototype cap, widened to the plate's own content so the status word never ellipsizes.
        measureForeground(wordRow, Int.MAX_VALUE, h)
        val plateContent = wordRow.measuredWidth + plate.paddingRight
        measureForeground(plate, maxOf(plateCap, minOf(plateContent, rightSide)), h, fillWidth = true)
        if (hint.visibility == VISIBLE) {
            val wideRegular = !viewport.portrait && !viewport.narrow && !viewport.compact
            val maxHintWidth = if (wideRegular) (measuredWidth * 0.45f).toInt() else Int.MAX_VALUE
            measureForeground(hint, minOf(leftSide, viewport.px(320f).toInt(), maxHintWidth), h, fillWidth = true)
        }
        val maxSlotHeight = controls.actionSize - actionGap / 2
        stackedFooter = !fitsAlignedSlot(plate, maxSlotHeight) ||
            (hint.visibility == VISIBLE && !fitsAlignedSlot(hint, maxSlotHeight))
        if (stackedFooter) {
            measureForeground(plate, available, h)
            val hintWidth = (available - plate.measuredWidth - (12f * density).toInt()).coerceAtLeast(0)
            if (hint.visibility == VISIBLE) measureForeground(hint, hintWidth, h)
        }
    }

    /**
     * Shares the aligned corner clamp between slot measurement and placement.
     * @param stageWidth Full stage width in physical pixels, before insets.
     * @return The truncated extra inset in physical pixels.
     */
    private fun alignedCornerInsetPx(stageWidth: Int): Int =
        (stageWidth * 0.03f).coerceIn(viewport.px(16f), viewport.px(40f)).toInt()

    /**
     * Checks laid-out descendants and text without treating ellipsis as a fit.
     * @param slot measured aligned slot in native pixels.
     * @param maxHeight maximum aligned slot height in native pixels.
     * @return whether the complete content fits the aligned slot.
     */
    private fun fitsAlignedSlot(slot: ViewGroup, maxHeight: Int): Boolean {
        // Subpixel rounding must not demote a fitting footer to the stacked layout.
        val tolerance = (8f * viewport.scale).toInt()
        if (slot.measuredWidth < unit(128f) || slot.measuredHeight > maxHeight + tolerance) {
            return false
        }
        slot.layout(0, 0, slot.measuredWidth, slot.measuredHeight)
        fun fits(view: View): Boolean {
            if (view.visibility == GONE) return true
            val bounds = Rect(0, 0, view.width, view.height)
            if (view !== slot) slot.offsetDescendantRectToMyCoords(view, bounds)
            if (bounds.left < 0 || bounds.top < 0 || bounds.right > slot.width || bounds.bottom > slot.height ||
                view.measuredState and View.MEASURED_STATE_TOO_SMALL != 0
            ) return false
            if (view is TextView && view.text.isNotEmpty()) {
                val layout = view.layout ?: return false
                if (layout.lineCount == 0 || layout.getLineEnd(layout.lineCount - 1) < view.text.length) return false
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) > 0 ||
                        bounds.left + view.compoundPaddingLeft - view.scrollX + layout.getLineLeft(line) < -0.5f ||
                        bounds.left + view.compoundPaddingLeft - view.scrollX + layout.getLineRight(line) > slot.width + 0.5f ||
                        bounds.top + view.extendedPaddingTop - view.scrollY + layout.getLineTop(line) < 0 ||
                        bounds.top + view.extendedPaddingTop - view.scrollY + layout.getLineBottom(line) > slot.height
                    ) return false
                }
            }
            return view !is ViewGroup || (0 until view.childCount).all { fits(view.getChildAt(it)) }
        }
        return fits(slot)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = motionLayer.width
        val edge = sceneEdge(width, height)
        val corner = alignedCornerInsetPx(width)
        placeForeground(brand, edge, edge)
        if (stackedFooter) {
            val footerBottom = controls.actionTop - viewport.px(16f).toInt()
            placeForeground(plate, w - edge - plate.measuredWidth, footerBottom - plate.measuredHeight)
            if (hint.visibility == VISIBLE) {
                placeForeground(hint, edge, footerBottom - hint.measuredHeight)
            }
        } else {
            val center = controls.actionTop + controls.actionSize / 2
            placeForeground(plate, w - edge - corner - plate.measuredWidth, center - plate.measuredHeight / 2)
            if (hint.visibility == VISIBLE) {
                placeForeground(hint, edge + corner, center - hint.measuredHeight / 2)
            }
        }
        controls.avoidForegroundObstacles(listOf(plate), listOf(brand, hint))
        completeForegroundLayout()
    }

    override fun onMotionContentVisibilityChanged() {
        syncBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBlink()
    }

    override fun onDetachedFromWindow() {
        stopBlink()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncBlink()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncBlink()
    }

    private fun syncBlink() {
        if (!blinkReady) return
        val allowed = isMotionContentVisible() && foregroundState.isProjecting == false &&
            foregroundState.phase == ScreenPhase.CONNECTING &&
            foregroundState.failureCause == null
        if (!allowed) {
            stopBlink()
            return
        }
        if (blinkCallback != null) return
        val generation = ++blinkGeneration
        light.alpha = 1f
        val callback = object : Runnable {
            override fun run() {
                if (generation != blinkGeneration) return
                if (!isMotionContentVisible() || foregroundState.isProjecting != false ||
                    foregroundState.phase != ScreenPhase.CONNECTING || foregroundState.failureCause != null
                ) {
                    stopBlink()
                    return
                }
                light.alpha = if (light.alpha == 1f) 0.2f else 1f
                postDelayed(this, 600L)
            }
        }
        blinkCallback = callback
        postDelayed(callback, 600L)
    }

    private fun stopBlink() {
        blinkGeneration++
        blinkCallback?.let { removeCallbacks(it) }
        blinkCallback = null
        light.alpha = 1f
    }
}

/**
 * Prototype plate status word; fixed English codes, never localized.
 *
 * @param phase real screen phase.
 * @param cause genuine FAILED cause, or null.
 * @return prototype `.c-standby-word` code for [phase].
 */
internal fun matterStatusWord(phase: ScreenPhase, cause: PrePlayFailureCause?): String {
    if (cause != null) return "ERROR"
    return when (phase) {
        ScreenPhase.READY -> "DISCONN"
        ScreenPhase.ARMING -> "ARMING"
        ScreenPhase.ADVERTISING -> "STANDBY"
        ScreenPhase.CONNECTING -> "ESTABLISHING"
        ScreenPhase.CONNECTED -> "CONNECTED"
        ScreenPhase.WIFI_PAUSED -> "WAIT_WIFI"
        ScreenPhase.FROZEN -> "UNAVAILABLE"
    }
}
