package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.ScreenPhase

/**
 * Landscape (A) foreground candidate.
 *
 * Displays host-provided text and controls without manufacturing session facts.
 * Its current foreground layout is a candidate, not an accepted prototype port.
 *
 * Prototype dimensions use the shared measured-stage viewport scale.
 */
class LandscapeStandbyForegroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : StandbyForegroundView(context, attrs, defStyleAttr) {
    override val useCssPixels = true
    private val brand = StandbyForegroundKit.brandView(context)
    private val receiverLabel = TextView(context).apply {
        tag = StandbyForegroundTags.RECEIVER_LABEL
        text = context.getString(R.string.standby_foreground_receiver_label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        letterSpacing = 0.22f
        setTextColor(Color.parseColor("#CBD0CB"))
    }
    private val receiverName: TextView = StandbyForegroundKit.receiverNameView(context).apply {
        setTextColor(0xFFF3F2EB.toInt())
    }
    private val eyebrow = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_EYEBROW
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        letterSpacing = 0.22f
        setTextColor(Color.parseColor("#E0E4DF"))
    }
    private val title = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_TITLE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, StandbyForegroundKit.TITLE_TEXT_SP)
        letterSpacing = 0.10f
        typeface = Typeface.create(Typeface.DEFAULT, 350, false)
        setTextColor(Color.parseColor("#F3F2EB"))
    }
    private val detail = TextView(context).apply {
        tag = StandbyForegroundTags.PHASE_DETAIL
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.10f
        setTextColor(Color.parseColor("#E1E3DE"))
    }
    private val hint = object : AppCompatTextView(context) {
        private var committedCopy: String? = null
        private var committedInvitation = false

        /** An exiting hint stays visible until the copy pipeline commits the empty text. */
        override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
            super.onTextChanged(text, start, lengthBefore, lengthAfter)
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val copy = text?.toString().orEmpty()
            val invitation = isInvitation(text)
            if (copy != committedCopy || invitation != committedInvitation) {
                committedCopy = copy
                committedInvitation = invitation
                // Refit new copy even at the same height; measuring only restores line-height spans.
                if (isAttachedToWindow && (canPlaceInsetLayout || canPlacePortraitInsetLayout)) {
                    this@LandscapeStandbyForegroundView.requestLayout()
                }
            }
        }
    }.apply {
        // Keep the platform copy spans without a second emoji processing pass.
        setEmojiCompatEnabled(false)
        tag = "foreground_hint"
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.08f
        setTextColor(Color.parseColor("#E0E4DF"))
    }
    private val controls = StandbyForegroundControls(context, StandbyControlTheme.LANDSCAPE)
    private val receiver = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(receiverLabel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(receiverName, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val statusDot = StandbyStatusDot(context)
    private val statusRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        val dotSize = unit(6f).toInt()
        addView(statusDot, LinearLayout.LayoutParams(dotSize, dotSize))
        addView(eyebrow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = unit(9f).toInt()
        })
    }
    private val center = object : LinearLayout(context) {
        var portraitInsetActive = false
            set(value) {
                if (field == value) return
                field = value
                clipToPadding = !value
                forceLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (portraitInsetActive) {
                // The lanes are exact; remeasuring the stack here would overwrite the fitted child sizes.
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!portraitInsetActive) super.onLayout(changed, left, top, right, bottom)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(statusRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(title)
        addView(detail, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private var lastGeometry: LandscapeForegroundGeometry? = null
    private var geometryDelivered = false
    private var sourceHintWidth = 0
    private var sourceCenterWidth = 0
    private var ambientX = 0f
    private var ambientY = 0f

    /** Available foreground pixels for each role in the approved inset layout. */
    private data class InsetLanes(val center: Rect, val status: Rect?, val brand: Rect, val hint: Rect?)

    private var insetLanes: InsetLanes? = null
    private var insetFitAttempted = false

    /** Receives changed visible edges, or null when the geometry becomes unavailable. */
    var onForegroundGeometryChanged: ((LandscapeForegroundGeometry?) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            geometryDelivered = false
            if (value != null && !isMotionContentVisible() && !isInLayout && !foregroundLayoutInProgress) {
                onForegroundGeometryCheck()
            } else {
                invalidate()
            }
        }

    init {
        title.gravity = Gravity.CENTER
        detail.gravity = Gravity.CENTER
        hint.gravity = Gravity.CENTER
        motionLayer.addView(brand)
        motionLayer.addView(receiver)
        motionLayer.addView(center)
        motionLayer.addView(hint)
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Applies host-owned CSS-density drift, then rechecks notices against the moved real content. */
    internal fun setLegacyAmbientOffset(xPx: Float, yPx: Float) {
        val lanes = insetLanes
        if (lanes == null && motionLayer.translationX == xPx && motionLayer.translationY == yPx ||
            lanes != null && ambientX == xPx && ambientY == yPx
        ) return
        ambientX = xPx
        ambientY = yPx
        val settled = isLaidOut && !isLayoutRequested && !foregroundLayoutInProgress
        // Adopt a fitted lane on entry, not a different layout for every drift sample.
        if (settled && insetLanes == null && !insetFitAttempted) {
            insetLanes = placeInsetLayout() ?: placePortraitInsetLayout()
        }
        applyAmbientOffset()
        if (settled && !isLayoutRequested) {
            controls.avoidForegroundObstacles(listOf(statusRow, title, detail), listOf(brand, hint, receiver))
            onForegroundGeometryCheck()
        }
    }

    /** Source max-width 600px AND max-height 820px, separate from the fractional-width query gap. */
    private val shortNarrow: Boolean
        get() = viewport.isValid && viewport.logicalWidth <= 600f && viewport.logicalHeight <= 820f

    /** The later compact-wide union wins when a small landscape also matches short-narrow. */
    private val compactWide: Boolean
        get() = viewport.isValid && viewport.logicalHeight <= 820f &&
            (viewport.logicalWidth >= 601f || !viewport.portrait)

    private val canPlaceInsetLayout: Boolean
        get() = !viewport.portrait && compactWide && (safeTop != 0 || safeBottom != 0)

    private val canPlacePortraitInsetLayout: Boolean
        get() = viewport.isValid && viewport.portrait && shortNarrow && (safeTop != 0 || safeBottom != 0)

    /** @return Whether this committed copy includes the original structured receiver group. */
    private fun isInvitation(text: CharSequence?): Boolean =
        ((text as? Spanned)?.getSpanStart(ReceiverGroupSpan) ?: -1) >= 0

    private fun updateScaledChildren() {
        StandbyForegroundKit.updateBrand(brand, viewport)
        StandbyForegroundKit.updateCssText(receiverLabel, 8f, viewport)
        StandbyForegroundKit.updateCssText(receiverName, StandbyForegroundKit.RECEIVER_TEXT_SP, viewport)
        StandbyForegroundKit.updateCssText(eyebrow, 9f, viewport)
        StandbyForegroundKit.updateCssText(title, StandbyForegroundKit.TITLE_TEXT_SP, viewport, 1.2f)
        StandbyForegroundKit.updateCssFirstLineIndent(title)
        StandbyForegroundKit.updateCssText(detail, 11f, viewport)
        StandbyForegroundKit.updateCssText(hint, 11f, viewport)
        val labelParams = receiverLabel.layoutParams as LinearLayout.LayoutParams
        val labelGap = viewport.roundedPx(8f)
        if (labelParams.bottomMargin != labelGap) {
            labelParams.bottomMargin = labelGap
            receiverLabel.layoutParams = labelParams
        }
        statusRow.setPadding(0, StandbyForegroundKit.inlineStatusLeading(eyebrow), 0, 0)
        val dotParams = statusDot.layoutParams as LinearLayout.LayoutParams
        val size = viewport.roundedPx(6f)
        if (dotParams.width != size || dotParams.height != size) {
            dotParams.width = size
            dotParams.height = size
            statusDot.layoutParams = dotParams
        }
        statusDot.setOutlineWidth(viewport.roundedPx(1f))
        val eyebrowParams = eyebrow.layoutParams as LinearLayout.LayoutParams
        val gap = viewport.roundedPx(9f)
        if (eyebrowParams.leftMargin != gap) {
            eyebrowParams.leftMargin = gap
            eyebrow.layoutParams = eyebrowParams
        }
    }

    override fun render(
        state: StandbyForegroundState,
        title: String,
        eyebrow: String,
        detail: String,
        callbacks: StandbyForegroundCallbacks,
    ) {
        super.render(state, title, eyebrow, detail, callbacks)
        receiverLabel.text = context.getString(R.string.standby_foreground_receiver_label)
        receiverName.text = state.receiverName
        receiverName.visibility = if (state.receiverName.isBlank()) View.GONE else View.VISIBLE
        receiverName.contentDescription = state.receiverName
        statusDot.setAdvertising(state.advertisingEnabled)
        replaceCopy(this.eyebrow, eyebrow, hardline = false)
        replaceCopy(this.title, title, hardline = false)
        // The prototype keeps non-Matter center detail empty even during a genuine failure episode.
        this.detail.text = ""
        this.detail.visibility = View.GONE
        updateHint(state)
        controls.update(state, callbacks)
    }

    /**
     * Selects the waiting/failed invitation without treating failure as a session phase.
     * @param state Current host facts, including any already-shown failure cause.
     */
    private fun updateHint(state: StandbyForegroundState) {
        val invitesConnection = when (state.phase) {
            ScreenPhase.ADVERTISING -> true
            ScreenPhase.CONNECTING -> state.isProjecting == false && state.failureCause != null
            else -> false
        }
        val hintCopy = if (invitesConnection && state.advertisingEnabled && state.receiverName.isNotBlank()) {
            val copy = context.getString(R.string.standby_foreground_connection_hint_format, state.receiverName)
            SpannableString(copy).apply {
                val start = copy.indexOf("Win + K")
                if (start >= 0) setSpan(ShortcutSpan(resources.displayMetrics.density), start, start + 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Both shipped formats end with the raw name; zh-TW's inline block also owns its source quotes.
                val quoted = context.getString(R.string.standby_foreground_connection_hint_format).endsWith("「%1\$s」")
                val groupStart = copy.length - state.receiverName.length - if (quoted) 2 else 0
                setSpan(ReceiverGroupSpan, groupStart, copy.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            when (state.phase) {
                ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_landscape_wifi_paused_hint)
                ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_detail)
                else -> ""
            }
        }
        replaceCopy(hint, hintCopy, hardline = false)
    }

    override fun onFailureChanged(cause: me.trinitrix.mirax.session.PrePlayFailureCause?, text: String) {
        if (foregroundState.phase == ScreenPhase.CONNECTING) updateHint(foregroundState)
        controls.updateFailure(cause, text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        updateViewport(viewportWidth, viewportHeight)
        updateScaledChildren()
        val narrow = viewport.narrow
        val shortNarrowOnly = shortNarrow && !compactWide
        val centerPadding = if (shortNarrowOnly) (viewportWidth * 0.05f).toInt() else 0
        val centerVerticalPad = if (shortNarrowOnly) viewport.roundedPx(14f) else 0
        center.portraitInsetActive = false
        center.setPadding(centerPadding, centerVerticalPad, centerPadding, centerVerticalPad)
        title.setPadding(0, 0, 0, 0)
        eyebrow.letterSpacing = if (compactWide) 0.12f else 0.22f
        title.letterSpacing = if (narrow) 0.08f else 0.10f
        detail.letterSpacing = if (narrow) 0.04f else 0.10f
        receiver.visibility = if (!shortNarrow && !compactWide && foregroundState.receiverName.isNotBlank()) VISIBLE else GONE
        val statusMargin = unit(when {
            compactWide -> 6f
            shortNarrow -> 9f
            narrow -> 16f
            else -> 19f
        }).toInt()
        val detailMargin = unit(when {
            compactWide -> 6f
            shortNarrow -> 8f
            narrow -> 16f
            else -> 19f
        }).toInt()
        val statusParams = statusRow.layoutParams as LinearLayout.LayoutParams
        val detailParams = detail.layoutParams as LinearLayout.LayoutParams
        if (statusParams.bottomMargin != statusMargin) {
            statusParams.bottomMargin = statusMargin
            statusRow.layoutParams = statusParams
        }
        if (detailParams.topMargin != detailMargin) {
            detailParams.topMargin = detailMargin
            detail.layoutParams = detailParams
        }
        controls.safeBottomInset = safeBottom
        controls.updateViewport(viewportWidth, viewportHeight)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val h = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val edge = sceneEdge(measuredWidth, measuredHeight)
        measureSourceBrand()
        measureForeground(receiver, minOf((measuredWidth * 0.88f).toInt(), w - 2 * edge - viewport.roundedPx(24f)), h)
        val centerWidth = minOf((measuredWidth * 0.92f).toInt(), if (compactWide) Int.MAX_VALUE else unit(1050f).toInt(),
            measuredWidth - 2 * maxOf(paddingLeft, paddingRight))
        sourceCenterWidth = centerWidth.coerceAtLeast(0)
        val detailMaxWidth = when {
            compactWide -> (centerWidth * 0.90f).toInt().coerceAtLeast(0)
            narrow -> unit(290f).toInt()
            else -> Int.MAX_VALUE
        }
        if (detail.maxWidth != detailMaxWidth) detail.maxWidth = detailMaxWidth
        measureForeground(center, sourceCenterWidth, h, fillWidth = true)
        sourceHintWidth = minOf(
            (measuredWidth * 0.90f).toInt(),
            unit(580f).toInt(),
            measuredWidth - 2 * maxOf(paddingLeft, paddingRight),
        )
        hint.setPadding(if (shortNarrowOnly) viewport.roundedPx(10f) else 0,
            if (shortNarrowOnly) viewport.roundedPx(7f) else 0,
            if (shortNarrowOnly) viewport.roundedPx(10f) else 0,
            if (shortNarrowOnly) viewport.roundedPx(7f) else 0)
        measureForeground(hint, sourceHintWidth, h, fillWidth = true)
    }

    /** Restores the brand's source measurement before normal placement. */
    private fun measureSourceBrand() {
        val width = measuredWidth - paddingLeft - paddingRight
        val height = measuredHeight - paddingTop - paddingBottom
        measureForeground(brand, width - 2 * sceneEdge(measuredWidth, measuredHeight), height)
    }

    /** Returns actual laid-out edges in this view's pixel coordinate space, or null when hidden. */
    fun foregroundGeometryInViewPixels(): LandscapeForegroundGeometry? {
        val brandRect = visibleForegroundRect(brand) ?: return null
        val primary = if (center.portraitInsetActive) title else center
        val primaryRect = visibleForegroundRect(primary) ?: return null
        return LandscapeForegroundGeometry(brandRect.bottom.toFloat(), primaryRect.top.toFloat())
    }

    override fun onForegroundGeometryCheck() {
        val callback = onForegroundGeometryChanged ?: return
        val geometry = foregroundGeometryInViewPixels()
        if (!geometryDelivered || geometry != lastGeometry) {
            geometryDelivered = true
            lastGeometry = geometry
            callback(geometry)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val h = motionLayer.height
        val previous = insetLanes
        controls.clearExplicitNoticeLane()
        if (center.portraitInsetActive) {
            center.portraitInsetActive = false
            measureForeground(center, sourceCenterWidth, h, fillWidth = true)
        }
        if (previous == null) {
            ambientX = motionLayer.translationX
            ambientY = motionLayer.translationY
        } else {
            for (child in listOf(center, title, statusRow, brand, hint)) {
                child.translationX = 0f
                child.translationY = 0f
            }
            if (previous.hint != null && hint.visibility == VISIBLE) {
                measureForeground(hint, sourceHintWidth, h, fillWidth = true)
            }
        }
        val edge = sceneEdge(width, height)
        placeForeground(brand, edge, edge)
        val viewportCenter = width / 2 - paddingLeft
        if (hint.visibility == VISIBLE) {
            val hintTop = h - edge - unit(100f).toInt() - hint.measuredHeight
            placeForeground(hint, viewportCenter - hint.measuredWidth / 2, hintTop)
        }
        val landscape = width > height
        val narrow = viewport.narrow
        val centerY = (height * when {
            landscape -> 0.43f
            compactWide -> 0.51f
            shortNarrow -> 0.47f
            else -> 0.49f
        }).toInt() - paddingTop
        val centerTop = centerY - center.measuredHeight / 2
        placeForeground(center, viewportCenter - center.measuredWidth / 2, centerTop)
        if (receiver.visibility == VISIBLE) {
            val offset = unit(when {
                landscape -> 111f
                narrow -> 127f
                else -> 151f
            }).toInt()
            val desired = (height * if (landscape) 0.43f else 0.49f).toInt() - paddingTop - offset
            placeForeground(receiver, viewportCenter - receiver.measuredWidth / 2, desired)
        }
        insetFitAttempted = false
        insetLanes = placeInsetLayout() ?: placePortraitInsetLayout()
        if (previous != null || insetLanes != null) applyAmbientOffset()
        controls.avoidForegroundObstacles(listOf(statusRow, title, detail), listOf(brand, hint, receiver))
        completeForegroundLayout()
    }

    /** @return Collision-only placement if the measured compact landscape roles fit. */
    private fun placeInsetLayout(): InsetLanes? {
        if (!canPlaceInsetLayout || insetFitAttempted) return null
        val dock = controls.foregroundDockBounds ?: return null
        val status = RectF(foregroundLayoutBounds(statusRow)).apply { offset(ambientX, ambientY) }
        if (!intersectsForegroundLine(title, dock, ambientX, ambientY) && !RectF.intersects(status, RectF(dock))) return null
        insetFitAttempted = true
        val safe = Rect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        val centerLane = Rect(safe.left, safe.top, safe.right, dock.top - viewport.roundedPx(4f))
        if (center.height > centerLane.height() || center.width > centerLane.width()) return null

        val menu = controls.foregroundMenuBounds
        val brandLane = Rect(safe)
        val brandBounds = foregroundLayoutBounds(brand)
        if (menu != null) {
            when {
                brandBounds.left >= menu.right -> brandLane.left = menu.right
                brandBounds.bottom <= menu.top -> brandLane.bottom = menu.top
                brandBounds.top >= menu.bottom -> brandLane.top = menu.bottom
                else -> brandLane.bottom = menu.top - viewport.roundedPx(1f)
            }
        }
        if (brand.height > brandLane.height() || brand.width > brandLane.width()) return null

        val invitation = isInvitation(hint.text)
        val moveHint = hint.visibility == VISIBLE && (invitation || plainHintCollides(centerLane, dock))
        var hintLane: Rect? = if (moveHint) {
            val gap = viewport.roundedPx(12f)
            Rect(maxOf(safe.left, menu?.right ?: safe.left) + gap, dock.top, dock.left - gap, dock.bottom)
        } else null
        if (hintLane != null) {
            val sourceLeft = hint.left
            val sourceBottom = hint.bottom
            val fitted = hintLane.width() > 0 && hintFits(hintLane.width(), hintLane.height(), hintLane.left - motionLayer.left,
                hintLane.top - motionLayer.top)
            if (!fitted) {
                measureForeground(hint, sourceHintWidth, motionLayer.height, fillWidth = true)
                placeForeground(hint, sourceLeft, sourceBottom - hint.measuredHeight)
                if (invitation) return null
                hintLane = null
            }
        }
        placeForeground(center, center.left, centerLane.bottom - motionLayer.top - center.measuredHeight)
        placeForeground(brand, brand.left, minOf(brand.top, brandLane.bottom - motionLayer.top - brand.measuredHeight))
        return InsetLanes(centerLane, null, brandLane, hintLane)
    }

    /** @return Collision-only placement for the approved portrait 360x200dp inset packing. */
    private fun placePortraitInsetLayout(): InsetLanes? {
        if (!canPlacePortraitInsetLayout || insetFitAttempted) return null
        val dock = controls.foregroundDockBounds ?: return null
        val menu = controls.foregroundMenuBounds
        val status = RectF(foregroundLayoutBounds(statusRow)).apply { offset(ambientX, ambientY) }
        val brandRect = RectF(foregroundLayoutBounds(brand)).apply { offset(ambientX, ambientY) }
        val collides = intersectsForegroundLine(title, dock, ambientX, ambientY) ||
            RectF.intersects(status, RectF(dock)) ||
            (menu != null && RectF.intersects(brandRect, RectF(menu))) ||
            (hint.visibility == VISIBLE && (
                intersectsForegroundLine(hint, dock, ambientX, ambientY) ||
                    intersectsForegroundLine(hint, foregroundLayoutBounds(title), 0f, 0f)
                ))
        if (!collides) return null
        insetFitAttempted = true
        val safe = Rect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)

        val leftTop = maxOf(safe.top, menu?.bottom ?: dock.top)
        val leftBelowMenu = Rect(safe.left, leftTop, dock.left, safe.bottom)
        if (leftBelowMenu.width() <= 0 || leftBelowMenu.height() <= 0) return null

        val sourceCenterLeft = center.left
        val sourceCenterTop = center.top
        val sourceHintLeft = hint.left
        val sourceHintTop = hint.top
        var hintProbed = false
        fun rejectFit(): InsetLanes? {
            measureSourceBrand()
            placeForeground(brand, brand.left, brand.top)
            // The normal stack must remeasure its children even if its own specs did not change.
            center.forceLayout()
            measureForeground(center, sourceCenterWidth, motionLayer.height, fillWidth = true)
            placeForeground(center, sourceCenterLeft, sourceCenterTop)
            if (hintProbed) {
                measureForeground(hint, sourceHintWidth, motionLayer.height, fillWidth = true)
                placeForeground(hint, sourceHintLeft, sourceHintTop)
            }
            return null
        }

        measureForeground(brand, leftBelowMenu.width(), leftBelowMenu.height())
        statusRow.measure(
            MeasureSpec.makeMeasureSpec(leftBelowMenu.width(), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(leftBelowMenu.height(), MeasureSpec.AT_MOST),
        )
        if (brand.measuredWidth > leftBelowMenu.width() || statusRow.measuredWidth > leftBelowMenu.width() ||
            brand.measuredHeight + statusRow.measuredHeight > leftBelowMenu.height()
        ) return rejectFit()
        val splitY = leftBelowMenu.top + brand.measuredHeight
        val brandLane = Rect(leftBelowMenu.left, leftBelowMenu.top, leftBelowMenu.right, splitY)
        val statusLane = Rect(leftBelowMenu.left, splitY, leftBelowMenu.right, leftBelowMenu.bottom)

        var hintLane: Rect? = null
        if (hint.visibility == VISIBLE) {
            val maxHintLane = Rect(dock.right, dock.top, safe.right, safe.bottom)
            if (maxHintLane.width() <= 0 || maxHintLane.height() <= 0) return rejectFit()
            hintProbed = true
            measureForeground(hint, maxHintLane.width(), maxHintLane.height(), fillWidth = true)
            val fittedTop = maxHintLane.bottom - motionLayer.top - hint.measuredHeight
            placeForeground(hint, maxHintLane.left - motionLayer.left, fittedTop)
            if (!textFitsIntact(hint) || hint.measuredHeight > maxHintLane.height()) return rejectFit()
            hintLane = Rect(maxHintLane.left, maxHintLane.bottom - hint.measuredHeight, maxHintLane.right, maxHintLane.bottom)
        }

        val titleMinLeft = maxOf(safe.left, menu?.right ?: safe.left)
        val maxTitleWidth = safe.right - titleMinLeft
        val maxTitleHeight = dock.top - safe.top
        if (maxTitleWidth <= 0 || maxTitleHeight <= 0) return rejectFit()

        fun titleFits(width: Int, heightLimit: Int = maxTitleHeight): Boolean {
            title.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxTitleHeight, MeasureSpec.AT_MOST),
            )
            title.layout(0, 0, title.measuredWidth, title.measuredHeight)
            return title.measuredHeight <= heightLimit && textFitsIntact(title)
        }
        if (!titleFits(maxTitleWidth)) return rejectFit()
        val naturalHeight = title.measuredHeight

        // A notice may reject this partition, but cannot reflow an otherwise identical title.
        val preferredWidth = (dock.right - titleMinLeft).coerceIn(1, maxTitleWidth)
        if (!titleFits(preferredWidth, naturalHeight)) {
            var lower = preferredWidth
            var upper = maxTitleWidth
            while (lower < upper) {
                val mid = lower + (upper - lower) / 2
                if (titleFits(mid, naturalHeight)) upper = mid else lower = mid + 1
            }
            if (!titleFits(lower, naturalHeight)) return rejectFit()
        }

        val titleRight = titleMinLeft + title.measuredWidth
        val noticeLane = Rect(maxOf(dock.right, titleRight), safe.top, safe.right, hintLane?.top ?: safe.bottom)
        if (!controls.tryExplicitNoticeLane(noticeLane)) return rejectFit()

        val centerLane = Rect(titleMinLeft, safe.top, titleRight, dock.top)
        // The passive wrapper must contain both children at the extremes of their independent drift.
        val wrapper = Rect(centerLane).apply { union(statusLane) }
        center.portraitInsetActive = true
        center.measure(
            MeasureSpec.makeMeasureSpec(wrapper.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(wrapper.height(), MeasureSpec.EXACTLY),
        )
        placeForeground(center, wrapper.left - motionLayer.left, wrapper.top - motionLayer.top)
        val titleLeft = centerLane.left - wrapper.left
        val titleTop = centerLane.top - wrapper.top
        title.layout(titleLeft, titleTop, titleLeft + title.measuredWidth, titleTop + title.measuredHeight)
        val statusLeft = statusLane.left - wrapper.left
        val statusTop = statusLane.top - wrapper.top
        statusRow.layout(statusLeft, statusTop, statusLeft + statusRow.measuredWidth, statusTop + statusRow.measuredHeight)
        placeForeground(brand, brandLane.left - motionLayer.left, brandLane.top - motionLayer.top)
        return InsetLanes(centerLane, statusLane, brandLane, hintLane)
    }

    private fun hintFits(widthPx: Int, heightPx: Int, leftInLayer: Int, topInLayer: Int): Boolean {
        measureForeground(hint, minOf(sourceHintWidth, widthPx), heightPx, fillWidth = true)
        placeForeground(hint, leftInLayer, topInLayer + (heightPx - hint.measuredHeight) / 2)
        return textFitsIntact(hint) &&
            (hint.layout?.let { it.height + hint.extendedPaddingTop + hint.extendedPaddingBottom <= hint.height } ?: false)
    }

    private fun textFitsIntact(view: TextView): Boolean {
        val lines = view.layout ?: return false
        if (lines.lineCount <= 0 || lines.getLineEnd(lines.lineCount - 1) != view.text.length) return false
        val textWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        return lines.height + view.extendedPaddingTop + view.extendedPaddingBottom <= view.height &&
            (0 until lines.lineCount).all { line ->
                lines.getEllipsisCount(line) == 0 &&
                    lines.getLineLeft(line) >= -0.5f && lines.getLineRight(line) <= textWidth + 0.5f
            }
    }

    /** @return Whether the normal-anchor plain hint line boxes collide with the lifted title or dock. */
    private fun plainHintCollides(centerLane: Rect, dock: Rect): Boolean {
        if (intersectsForegroundLine(hint, dock, ambientX, ambientY)) return true
        if (title.visibility != VISIBLE || title.text.isEmpty()) return false
        val titleLayout = title.layout ?: return false
        val newCenterTop = centerLane.bottom - motionLayer.top - center.measuredHeight
        val centerLiftY = (newCenterTop - center.top).toFloat()
        val left = motionLayer.left + center.left
        val top = motionLayer.top + newCenterTop
        val centerDx = ambientX.coerceIn(
            (centerLane.left - left).toFloat(),
            (maxOf(centerLane.left, centerLane.right - center.width) - left).toFloat(),
        )
        val centerDy = ambientY.coerceIn(
            (centerLane.top - top).toFloat(),
            (maxOf(centerLane.top, centerLane.bottom - center.height) - top).toFloat(),
        )
        val titleBounds = foregroundLayoutBounds(title)
        val titleX = titleBounds.left + title.compoundPaddingLeft + centerDx
        val titleY = titleBounds.top + title.totalPaddingTop + centerLiftY + centerDy
        return (0 until titleLayout.lineCount).any { line ->
            val lineLeft = titleLayout.getLineLeft(line)
            val lineRight = titleLayout.getLineRight(line)
            if (lineRight <= lineLeft) return@any false
            val lineBox = Rect(
                kotlin.math.floor((titleX + lineLeft).toDouble()).toInt(),
                kotlin.math.floor((titleY + titleLayout.getLineTop(line)).toDouble()).toInt(),
                kotlin.math.ceil((titleX + lineRight).toDouble()).toInt(),
                kotlin.math.ceil((titleY + titleLayout.getLineBottom(line)).toDouble()).toInt(),
            )
            intersectsForegroundLine(hint, lineBox, ambientX, ambientY)
        }
    }

    /** Keeps the constrained clip fixed while allowing the real drift where each lane has room. */
    private fun applyAmbientOffset() {
        val lanes = insetLanes
        motionLayer.translationX = if (lanes == null) ambientX else 0f
        motionLayer.translationY = if (lanes == null) ambientY else 0f
        if (lanes == null) return
        val statusLane = lanes.status
        if (statusLane == null) {
            driftWithin(center, lanes.center, ambientX, ambientY)
        } else {
            driftPortraitChild(title, lanes.center)
            driftPortraitChild(statusRow, statusLane)
        }
        driftWithin(brand, lanes.brand, ambientX, ambientY)
        val hintLane = lanes.hint
        if (hintLane == null) {
            hint.translationX = ambientX
            hint.translationY = ambientY
        } else {
            driftWithin(hint, hintLane, ambientX, ambientY)
        }
    }

    /** Clamps one fitted child inside its allocated lane, with the wrapper fixed. */
    private fun driftPortraitChild(child: View, lane: Rect) {
        val baseX = motionLayer.left + center.left + child.left
        val baseY = motionLayer.top + center.top + child.top
        child.translationX = ambientX.coerceIn((lane.left - baseX).toFloat(),
            (maxOf(lane.left, lane.right - child.width) - baseX).toFloat())
        child.translationY = ambientY.coerceIn((lane.top - baseY).toFloat(),
            (maxOf(lane.top, lane.bottom - child.height) - baseY).toFloat())
    }
}

/** The receiver inline block uses the native Paint unchanged; an oversized group may overflow, never shrink. */
private object ReceiverGroupSpan : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
    }
}

/** The prototype's unbreakable shortcut group with 600-weight, underlined Win/K keycaps. */
private data class ShortcutSpan(private val density: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val keyPaint = Paint(paint).apply { typeface = Typeface.create(paint.typeface, 600, false) }
        return kotlin.math.ceil((keyPaint.measureText("Win") + keyPaint.measureText("K") +
            paint.measureText(" + ") + 24f * density).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val keyPaint = Paint(paint).apply { typeface = Typeface.create(paint.typeface, 600, false) }
        val borderPaint = Paint(paint).apply { color = 0x70FFFFFF; strokeWidth = density }
        var left = x
        for ((index, key) in listOf("Win", "K").withIndex()) {
            val width = keyPaint.measureText(key)
            canvas.drawText(key, left + 6f * density, y.toFloat(), keyPaint)
            val borderY = y + paint.descent() + 3f * density
            canvas.drawLine(left + 3f * density, borderY, left + 9f * density + width, borderY, borderPaint)
            left += width + 12f * density
            if (index == 0) {
                canvas.drawText(" + ", left, y.toFloat(), paint)
                left += paint.measureText(" + ")
            }
        }
    }
}

/** Measured Landscape edges in foreground-view pixels. */
data class LandscapeForegroundGeometry(val brandBottomPx: Float, val primaryTopPx: Float)
