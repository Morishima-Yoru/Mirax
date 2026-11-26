package me.trinitrix.mirax.ui.standby.foreground

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale

/** Prototype control artwork for each foreground. */
internal enum class StandbyControlTheme { LANDSCAPE, MATTER, HARDLINE }

/** Fixed interaction layer; its children do not move with the foreground artwork. */
internal class StandbyForegroundControls(
    context: Context,
    private val theme: StandbyControlTheme,
) : FrameLayout(context) {
    private var callbacks = StandbyForegroundCallbacks()
    private var phase = ScreenPhase.READY
    private val minimumTouchSize: Int get() = kotlin.math.ceil(
        StandbyForegroundKit.dpToPx(context, StandbyForegroundKit.MIN_TOUCH_DP).toDouble(),
    ).toInt()
    private var viewport = StandbyViewportScale(0, 0, context.resources.displayMetrics.density)
    private var appliedScale = 0f
    private val failed = notice(StandbyForegroundTags.FAILED)
    private val dismiss = action(StandbyForegroundTags.FAILED_DISMISS, R.string.preplay_failed_dismiss) {
        callbacks.onDismissFailure?.invoke()
    }
    private val shizuku = action("foreground_shizuku", R.string.frozen_open_shizuku) {
        callbacks.onOpenShizuku?.invoke()
    }
    private val warning = notice(StandbyForegroundTags.WIFI_WARNING).apply { setText(R.string.standby_wifi_warning) }
    private val wifiStop = action(StandbyForegroundTags.WIFI_STOP, R.string.standby_wifi_warning_stop) {
        callbacks.onStopBroadcastWhilePaused?.invoke()
    }
    private val failureRow = row(failed, dismiss)
    // Leading spacer column keeps Shizuku right-aligned in the FROZEN row; the explanation
    // copy itself belongs to the dialog, so this placeholder carries no text. The boxed info
    // action is gone: the dock artwork is its single FROZEN affordance on every theme.
    private val frozenLabel = notice("foreground_frozen_label").apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val frozenRow = row(frozenLabel, shizuku)
    private val wifiRow = row(warning, wifiStop)
    private val notices = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(failureRow)
        addView(frozenRow)
        addView(wifiRow)
    }
    private val noticeScroll = ScrollView(context).apply {
        isFocusable = false
        isVerticalScrollBarEnabled = false
        addView(notices, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    var safeBottomInset = 0
    var viewportWidth = 0
    var viewportHeight = 0
    var actionSize = dp(if (theme == StandbyControlTheme.HARDLINE) 84f else 96f)
        private set
    var actionTop = 0
        private set
    private var noticesBesideDock = false
    private var explicitNoticeLane: Rect? = null
    private var noticeViewportWidth = 0
    private var noticeViewportHeight = 0
    private var measuredControlsWidth = 0
    private var measuredControlsHeight = 0
    private var sideFitReported = false
    private var hardlineHandleCenterPx: Float? = null

    /** The real target, including any hit-area expansion, in the foreground parent's pixels. */
    val foregroundDockBounds: Rect?
        get() = broadcast.takeIf { it.visibility == VISIBLE && it.width > 0 && it.height > 0 }?.let {
            Rect(it.left, it.top, it.right, it.bottom).also { bounds -> bounds.offset(left, top) }
        }

    /** The outward-rounded handle target in the same coordinates, including its ambient offset. */
    val foregroundMenuBounds: Rect?
        get() = if (menu.visibility == VISIBLE) menuHitBounds().also { it.offset(left, top) } else null

    private val broadcastArt = StandbyControlArt(context, theme, menu = false).apply {
        tag = StandbyForegroundTags.BROADCAST
        setOnClickListener {
            when (phase) {
                ScreenPhase.FROZEN -> callbacks.onShowFrozenInfo?.invoke()
                ScreenPhase.WIFI_PAUSED -> callbacks.onStopBroadcastWhilePaused?.invoke()
                else -> callbacks.onToggleBroadcast?.invoke()
            }
        }
    }
    val broadcast: View get() = broadcastArt
    private val menu = StandbyControlArt(context, theme, menu = true).apply {
        tag = StandbyForegroundTags.MENU
        contentDescription = context.getString(R.string.standby_foreground_menu_desc)
        setOnClickListener { callbacks.onOpenSettings?.invoke() }
    }

    init {
        addView(noticeScroll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(broadcast, LayoutParams(actionSize, actionSize))
        addView(menu, LayoutParams(minimumTouchSize, maxOf(minimumTouchSize, dp(88f))))
        update(StandbyForegroundState(ScreenPhase.READY, false), callbacks)
    }

    private fun dp(value: Float): Int = viewport.roundedPx(value)

    /**
     * @param centerPx Hardline center in full-stage pixels; null retains the 50% fallback.
     * @param layoutPending Whether a host resize is already about to lay out these controls.
     * @return Whether the requested center changed.
     */
    fun setHardlineHandleCenter(centerPx: Float?, layoutPending: Boolean = false): Boolean {
        if (theme != StandbyControlTheme.HARDLINE || hardlineHandleCenterPx == centerPx) return false
        hardlineHandleCenterPx = centerPx
        if (!layoutPending) {
            if (isLaidOut && !isLayoutRequested) layoutControls() else requestLayout()
        }
        return true
    }

    /** Outward-rounded actual menu target, including its fractional source-center translation. */
    private fun menuHitBounds(): Rect = Rect(
        kotlin.math.floor(menu.x.toDouble()).toInt(), kotlin.math.floor(menu.y.toDouble()).toInt(),
        kotlin.math.ceil((menu.x + menu.width).toDouble()).toInt(),
        kotlin.math.ceil((menu.y + menu.height).toDouble()).toInt(),
    )

    /** Applies a nominal SP through the platform font scale and the density-aware viewport. */
    private fun updateTextSize(view: TextView, nominalSp: Float) {
        val metrics = view.resources.displayMetrics
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nominalSp, metrics) * viewport.scale
        if (view.textSize != size) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun notice(viewTag: String): TextView = TextView(context).apply {
        tag = viewTag
        setTextColor(0xFFF3F2EB.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(viewTag: String, label: Int, callback: () -> Unit): TextView = TextView(context).apply {
        tag = viewTag
        setText(label)
        contentDescription = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setSingleLine(false)
        setHorizontallyScrolling(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        gravity = Gravity.CENTER
        setPadding(dp(10f), 0, dp(10f), 0)
        minimumWidth = minimumTouchSize
        minimumHeight = minimumTouchSize
        isFocusable = true
        isClickable = true
        background = actionBackground()
        setOnClickListener { callback() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    private fun actionBackground(): StateListDrawable = StateListDrawable().apply {
        fun outline(stroke: Int) = GradientDrawable().apply {
            setColor(0xDD171A19.toInt())
            setStroke(dp(stroke.toFloat()).coerceAtLeast(1), Color.WHITE)
            cornerRadius = viewport.px(3f)
        }
        addState(intArrayOf(android.R.attr.state_focused), outline(2))
        addState(intArrayOf(), outline(1))
    }

    /** Updates native control dimensions without replacing the interactive views. */
    fun updateViewport(width: Int, height: Int) {
                if (theme == StandbyControlTheme.HARDLINE && (viewportWidth != width || viewportHeight != height)) {
            // Consume the staged handle center in this traversal even if the safe frame is unchanged.
            forceLayout()
        }
        viewportWidth = width
        viewportHeight = height
        viewport = StandbyViewportScale(width, height, context.resources.displayMetrics.density,
            cssPixels = theme != StandbyControlTheme.MATTER)
        actionSize = dp(if (theme == StandbyControlTheme.HARDLINE) 84f else 96f)
        val target = maxOf(actionSize, minimumTouchSize)
        val broadcastParams = broadcast.layoutParams
        if (broadcastParams.width != target || broadcastParams.height != target) {
            broadcastParams.width = target
            broadcastParams.height = target
            broadcast.layoutParams = broadcastParams
        }
        val menuParams = menu.layoutParams
        val menuHeight = maxOf(minimumTouchSize, dp(88f))
        if (menuParams.width != minimumTouchSize || menuParams.height != menuHeight) {
            menuParams.width = minimumTouchSize
            menuParams.height = menuHeight
            menu.layoutParams = menuParams
        }
        // Artwork draws itself in native pixels, so its unit is the full prototype-to-pixel factor.
        broadcastArt.viewportScale = viewport.px(1f)
        menu.viewportScale = viewport.px(1f)
        for (notice in listOf(failed, warning)) {
            updateTextSize(notice, 13f)
        }
        for (action in listOf(dismiss, shizuku, wifiStop)) {
            updateTextSize(action, 12f)
            if (action.minimumWidth != minimumTouchSize) action.minimumWidth = minimumTouchSize
            if (action.minimumHeight != minimumTouchSize) action.minimumHeight = minimumTouchSize
            val padding = dp(10f)
            if (action.paddingLeft != padding || action.paddingRight != padding) action.setPadding(padding, 0, padding, 0)
            if (appliedScale != viewport.scale) action.background = actionBackground()
        }
        appliedScale = viewport.scale
    }

    private fun row(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEachIndexed { index, child ->
            val weight = if (index == 0) 1f else 0f
            addView(child, LinearLayout.LayoutParams(if (weight == 1f) 0 else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, weight))
        }
    }

    /** Applies host truth and replaces callbacks without invoking them. */
    fun update(state: StandbyForegroundState, callbacks: StandbyForegroundCallbacks) {
        resetNoticePlacement()
        this.callbacks = callbacks
        phase = state.phase
        val isFrozen = state.phase == ScreenPhase.FROZEN
        val isPaused = state.phase == ScreenPhase.WIFI_PAUSED
        //Dock broadcast artwork is the only FROZEN info affordance on every theme.
        shizuku.visibility = if (isFrozen && state.shouldOfferShizuku && callbacks.onOpenShizuku != null) VISIBLE else GONE
        frozenRow.visibility = if (shizuku.visibility == VISIBLE) VISIBLE else GONE
        warning.visibility = if (isPaused) VISIBLE else GONE
        wifiStop.visibility = if (isPaused && callbacks.onStopBroadcastWhilePaused != null) VISIBLE else GONE
        wifiRow.visibility = if (isPaused) VISIBLE else GONE
        val broadcastAction = when {
            isFrozen -> callbacks.onShowFrozenInfo
            isPaused -> callbacks.onStopBroadcastWhilePaused
            else -> callbacks.onToggleBroadcast
        }
        broadcast.visibility = if (state.isProjecting != true && broadcastAction != null) VISIBLE else GONE
        broadcastArt.checked = state.advertisingEnabled
        broadcastArt.infoAction = isFrozen
        broadcast.contentDescription = context.getString(
            when {
                isFrozen -> R.string.frozen_dialog_title
                isPaused -> R.string.standby_wifi_warning_stop
                state.advertisingEnabled -> R.string.standby_foreground_broadcast_desc_on
                else -> R.string.standby_foreground_broadcast_desc_off
            },
        )
        menu.visibility = if (callbacks.onOpenSettings != null) VISIBLE else GONE
        updateFailure(state.failureCause, state.failureText)
    }

    /** Updates only host-projected failure text and its callback-backed action. */
    fun updateFailure(cause: PrePlayFailureCause?, text: String) {
        if (failed.text.toString() != text || (failed.visibility == VISIBLE) != (cause != null && text.isNotEmpty())) {
            resetNoticePlacement()
        }
        failed.text = text
        failed.visibility = if (cause != null && text.isNotEmpty()) VISIBLE else GONE
        dismiss.visibility = if (cause != null && callbacks.onDismissFailure != null) VISIBLE else GONE
        failureRow.visibility = if (failed.visibility == VISIBLE || dismiss.visibility == VISIBLE) VISIBLE else GONE
        broadcastArt.warning = phase == ScreenPhase.FROZEN ||
            (phase == ScreenPhase.WIFI_PAUSED && theme != StandbyControlTheme.LANDSCAPE) ||
            (cause != null && theme != StandbyControlTheme.HARDLINE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateViewport(viewportWidth, viewportHeight)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth != measuredControlsWidth || measuredHeight != measuredControlsHeight) {
            resetNoticePlacement()
            measuredControlsWidth = measuredWidth
            measuredControlsHeight = measuredHeight
        }
        if (noticesBesideDock) {
            measureNotices(noticeViewportWidth, noticeViewportHeight, besideDock = true)
        } else {
            measureUpperNotice()
        }
    }

    /** Measures the ordinary notice above the dock. */
    private fun measureUpperNotice() {
        val dock = if (broadcast.visibility == VISIBLE) maxOf(actionSize, minimumTouchSize) else 0
        val maxHeight = upperNoticeMaxHeight(measuredHeight, dockBottomMarginPx(), dock, dp(6f))
        val width = maxOf(measuredWidth - dp(104f), minOf(measuredWidth, 3 * minimumTouchSize))
        measureNotices(width, maxHeight, besideDock = false)
    }

    /**
     * @param action visible notice action.
     * @return untruncated width including padding and its minimum touch target.
     */
    private fun actionWidth(action: TextView): Int = kotlin.math.ceil(
        action.paint.measureText(action.text.toString()).toDouble(),
    ).toInt().plus(action.compoundPaddingLeft + action.compoundPaddingRight).coerceAtLeast(minimumTouchSize)

    /**
     * Measures the scroll viewport and its rows without resizing text.
     * @param width available native-pixel width.
     * @param height available native-pixel height.
     * @param besideDock whether the approved side placement is active.
     */
    private fun measureNotices(width: Int, height: Int, besideDock: Boolean) {
        val actionMaxWidth = if (besideDock) width else (width / 3).coerceAtLeast(minimumTouchSize)
        for (action in listOf(dismiss, shizuku, wifiStop)) {
            if (action.maxWidth != actionMaxWidth) action.maxWidth = actionMaxWidth
        }
        for (row in listOf(failureRow, frozenRow, wifiRow)) {
            val actions = (1 until row.childCount).map { row.getChildAt(it) as TextView }
                .filter { it.visibility == VISIBLE }
            val requiredWidth = minimumTouchSize + if (besideDock) {
                actions.sumOf { actionWidth(it) }
            } else {
                actions.size * minimumTouchSize
            }
            val stacked = width < requiredWidth
            row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val params = row.getChildAt(0).layoutParams as LinearLayout.LayoutParams
            val labelWidth = if (stacked) LayoutParams.MATCH_PARENT else 0
            val labelWeight = if (stacked) 0f else 1f
            if (params.width != labelWidth || params.weight != labelWeight) {
                params.width = labelWidth
                params.weight = labelWeight
                row.getChildAt(0).layoutParams = params
            }
        }
        noticeScroll.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.AT_MOST),
        )
    }

    /** @return Whether any notice row is currently visible. */
    internal fun hasVisibleNotice(): Boolean = listOf(failureRow, frozenRow, wifiRow).any { it.visibility == VISIBLE }

    /**
     * Validates and pins notices inside an explicit foreground-pixel lane without obstacle inflation.
     * @param laneInForeground Available rectangle in the foreground parent's pixels.
     * @return Whether all visible notice copy and >=48dp actions fit without clipping.
     */
    internal fun tryExplicitNoticeLane(laneInForeground: Rect): Boolean {
        if (!hasVisibleNotice()) {
            explicitNoticeLane = null
            return true
        }
        val lane = Rect(laneInForeground)
        lane.offset(-left, -top)
        if (lane.width() <= 0 || lane.height() <= 0) return false
        val oldScrollX = noticeScroll.scrollX
        val oldScrollY = noticeScroll.scrollY
        val oldBounds = Rect(noticeScroll.left, noticeScroll.top, noticeScroll.right, noticeScroll.bottom)

        measureNotices(lane.width(), lane.height(), besideDock = true)
        noticeScroll.scrollTo(0, 0)
        noticeScroll.layout(lane.left, lane.top, lane.left + noticeScroll.measuredWidth, lane.top + noticeScroll.measuredHeight)
        val textViews = listOf(failed, warning, dismiss, shizuku, wifiStop)
        val visibleActions = listOf(dismiss, shizuku, wifiStop).filter { it.visibility == VISIBLE }
        val fits = notices.measuredWidth <= lane.width() &&
            notices.measuredHeight <= noticeScroll.measuredHeight &&
            notices.measuredHeight <= lane.height() &&
            noticeScroll.measuredWidth <= lane.width() &&
            noticeScroll.measuredHeight <= lane.height() &&
            textViews.all(::completeTextFits) &&
            visibleActions.all { action ->
                val box = Rect(0, 0, action.width, action.height)
                noticeScroll.offsetDescendantRectToMyCoords(action, box)
                action.isFocusable && action.isClickable &&
                    action.width >= minimumTouchSize && action.height >= minimumTouchSize &&
                    box.left >= 0 && box.top >= 0 && box.right <= noticeScroll.width && box.bottom <= noticeScroll.height
            }
        if (fits) {
            explicitNoticeLane = lane
            noticesBesideDock = true
            noticeViewportWidth = lane.width()
            noticeViewportHeight = lane.height()
            sideFitReported = false
            return true
        }

        // Rebuild the original row constraints without committing the tested slot.
        if (noticesBesideDock) {
            measureNotices(noticeViewportWidth, noticeViewportHeight, besideDock = true)
        } else {
            measureUpperNotice()
        }
        noticeScroll.layout(oldBounds.left, oldBounds.top, oldBounds.right, oldBounds.bottom)
        noticeScroll.scrollTo(oldScrollX, oldScrollY)
        return false
    }

    /** Restores automatic notice measurement and placement. */
    internal fun clearExplicitNoticeLane() {
        if (explicitNoticeLane == null) return
        explicitNoticeLane = null
        noticesBesideDock = false
        measureUpperNotice()
        layoutUpperNotice()
    }

    /** Restores the original upper placement; complete text remains scrollable in either placement. */
    private fun resetNoticePlacement() {
        explicitNoticeLane = null
        if (!noticesBesideDock) return
        noticesBesideDock = false
        requestLayout()
    }

    /**
     * Checks the actual laid-out text, including ellipsis and line clipping.
     * @param view Text to inspect.
     * @return Whether every character fits without ellipsis.
     */
    private fun completeTextFits(view: TextView): Boolean {
        if (view.visibility != VISIBLE || view.text.isEmpty()) return true
        val textLayout = view.layout ?: return false
        if (textLayout.lineCount == 0 || textLayout.getLineEnd(textLayout.lineCount - 1) < view.text.length) return false
        val availableWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val availableHeight = view.height - view.extendedPaddingTop - view.extendedPaddingBottom
        return (0 until textLayout.lineCount).all { line ->
            textLayout.getEllipsisCount(line) == 0 &&
                textLayout.getLineLeft(line) >= -0.5f &&
                textLayout.getLineRight(line) <= availableWidth + 0.5f &&
                textLayout.getLineTop(line) >= 0 && textLayout.getLineBottom(line) <= availableHeight
        }
    }

    /**
     * Places overflowing notices in a scrollable area outside real content and hit bounds.
     * @param primary status content used to determine whether the upper area fits.
     * @param other additional content to avoid when using the side area.
     */
    fun avoidForegroundObstacles(primary: List<View>, other: List<View>) {
        if (parent !is ViewGroup) return
        if (!hasVisibleNotice()) {
            sideFitReported = false
            resetNoticePlacement()
            return
        }
        explicitNoticeLane?.let { lane ->
            noticeScroll.layout(lane.left, lane.top, lane.left + noticeScroll.measuredWidth, lane.top + noticeScroll.measuredHeight)
            sideFitReported = false
            return
        }
        val controlsToGlobal = Matrix().also { transformMatrixToGlobal(it) }
        val globalToControls = Matrix()
        if (!controlsToGlobal.invert(globalToControls)) {
            Log.e("StandbyForeground", "Cannot map foreground obstacles into the controls viewport")
            return
        }
        val obstacles = mutableListOf<Rect>()
        fun appendBounds(view: View, local: RectF) {
            val viewToGlobal = Matrix().also { view.transformMatrixToGlobal(it) }
            val viewToControls = Matrix().also { it.setConcat(globalToControls, viewToGlobal) }
            viewToControls.mapRect(local)
            val rect = Rect(
                kotlin.math.floor(local.left.toDouble()).toInt() - 1,
                kotlin.math.floor(local.top.toDouble()).toInt() - 1,
                kotlin.math.ceil(local.right.toDouble()).toInt() + 1,
                kotlin.math.ceil(local.bottom.toDouble()).toInt() + 1,
            )
            if (!rect.isEmpty) obstacles.add(rect)
        }
        fun appendVisibleContent(view: View) {
            if (view.visibility != VISIBLE || view.width <= 0 || view.height <= 0) return
            if (view is ViewGroup) {
                if (view.tag == StandbyForegroundTags.MATTER_PLATE) {
                    appendBounds(view, RectF((view.width - dp(2f)).toFloat(), 0f, view.width.toFloat(), view.height.toFloat()))
                }
                for (index in 0 until view.childCount) appendVisibleContent(view.getChildAt(index))
            } else if (view is TextView) {
                val textLayout = view.layout
                if (textLayout == null) {
                    appendBounds(view, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                    return
                }
                for (line in 0 until textLayout.lineCount) {
                    val rect = RectF(
                        view.compoundPaddingLeft + textLayout.getLineLeft(line) - view.scrollX,
                        (view.extendedPaddingTop + textLayout.getLineTop(line) - view.scrollY).toFloat(),
                        view.compoundPaddingLeft + textLayout.getLineRight(line) - view.scrollX,
                        (view.extendedPaddingTop + textLayout.getLineBottom(line) - view.scrollY).toFloat(),
                    )
                    if (rect.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat())) appendBounds(view, rect)
                }
            } else {
                appendBounds(view, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            }
        }
        for (view in primary + other) appendVisibleContent(view)
        if (menu.visibility == VISIBLE) obstacles.add(menuHitBounds())
        if (broadcast.visibility == VISIBLE) obstacles.add(Rect(broadcast.left, broadcast.top, broadcast.right, broadcast.bottom))
        val textViews = listOf(failed, warning, dismiss, shizuku, wifiStop)
        val visibleActions = listOf(dismiss, shizuku, wifiStop).filter { it.visibility == VISIBLE }
        val upper = Rect(noticeScroll.left, noticeScroll.top, noticeScroll.right, noticeScroll.bottom)
        if (!noticesBesideDock && upper.left >= 0 && upper.top >= 0 && upper.right <= width && upper.bottom <= height &&
            notices.measuredHeight <= noticeScroll.measuredHeight && textViews.all(::completeTextFits) &&
            visibleActions.all { it.width >= minimumTouchSize && it.height >= minimumTouchSize } &&
            obstacles.none { Rect.intersects(it, upper) }
        ) {
            sideFitReported = false
            return
        }
        if (!noticesBesideDock && !sideFitReported) {
            val incomplete = textViews.filterNot(::completeTextFits).map { text ->
                val layout = text.layout
                val line = if (layout != null && layout.lineCount > 0) {
                    "${layout.getLineLeft(0)}..${layout.getLineRight(0)}"
                } else "none"
                "${text.tag}:${text.width}x${text.height}:scroll=${text.scrollX},${text.scrollY}:line=$line"
            }
            Log.d("StandbyForeground", "Upper notice rejected: upper=$upper viewport=${width}x$height " +
                "content=${notices.measuredHeight}/${noticeScroll.measuredHeight} minimum=$minimumTouchSize " +
                "actions=${visibleActions.map { "${it.tag}:${it.width}x${it.height}" }} " +
                "incomplete=$incomplete collisions=${obstacles.filter { Rect.intersects(it, upper) }}")
        }

        val sideLeft = ((if (broadcast.visibility == VISIBLE) broadcast.right else viewportWidth / 2 - left) + dp(12f))
            .coerceAtLeast(0)
        val xEdges = (listOf(sideLeft, width) + obstacles.flatMap { listOf(it.left, it.right) })
            .filter { it in sideLeft..width }.distinct().sorted()
        val yEdges = (listOf(0, height) + obstacles.flatMap { listOf(it.top, it.bottom) })
            .map { it.coerceIn(0, height) }.distinct().sorted()
        val candidates = mutableListOf<Rect>()
        for (leftIndex in xEdges.indices) for (rightIndex in leftIndex + 1 until xEdges.size) {
            if (xEdges[rightIndex] - xEdges[leftIndex] < minimumTouchSize) continue
            for (topIndex in yEdges.indices) for (bottomIndex in topIndex + 1 until yEdges.size) {
                val candidate = Rect(xEdges[leftIndex], yEdges[topIndex], xEdges[rightIndex], yEdges[bottomIndex])
                if (candidate.height() >= minimumTouchSize && obstacles.none { Rect.intersects(it, candidate) }) {
                    candidates.add(candidate)
                }
            }
        }
        if (candidates.isNotEmpty()) noticesBesideDock = true
        for (candidate in candidates.sortedByDescending { it.width().toLong() * it.height() }) {
            noticeViewportWidth = candidate.width()
            noticeViewportHeight = candidate.height()
            measureNotices(candidate.width(), candidate.height(), besideDock = true)
            noticeScroll.layout(candidate.left, candidate.top, candidate.left + noticeScroll.measuredWidth,
                candidate.top + noticeScroll.measuredHeight)
            if (textViews.all(::completeTextFits) && visibleActions.all {
                    it.width >= minimumTouchSize && it.height >= minimumTouchSize &&
                        it.width <= noticeScroll.width && it.height <= noticeScroll.height
                }
            ) {
                sideFitReported = false
                return
            }
        }
        if (placeScrolledFrozenExplanation(obstacles)) {
            noticesBesideDock = false
            sideFitReported = false
            return
        }
        if (!sideFitReported) Log.e("StandbyForeground", "No unobstructed notice viewport can fit the complete actions")
        sideFitReported = true
    }

    /**
     * Wraps approved FROZEN explanation in its own scroll viewport when the upper slot overflows.
     * Complete copy stays reachable by scrolling; dock, font sizes and 48dp targets unchanged.
     * @param obstacles unobstructed primary artwork rectangles in controls pixels.
     * @return whether placement committed.
     */
    private fun placeScrolledFrozenExplanation(obstacles: List<Rect>): Boolean {
        val frozenExplains = frozenRow.visibility == VISIBLE &&
            failureRow.visibility != VISIBLE && wifiRow.visibility != VISIBLE
        if (!frozenExplains) return false
        val dockTop = if (broadcast.visibility == VISIBLE) minOf(actionTop, broadcast.top) else actionTop + actionSize
        val bandBottom = (dockTop - dp(6f)).coerceAtLeast(0)
        val yEdges = (listOf(0, bandBottom) + obstacles.flatMap { listOf(it.top, it.bottom) })
            .map { it.coerceIn(0, bandBottom) }.distinct().sorted()
        var bestTop = 0
        var bestBottom = 0
        for (topIndex in yEdges.indices) {
            for (bottomIndex in topIndex + 1 until yEdges.size) {
                val band = Rect(0, yEdges[topIndex], width, yEdges[bottomIndex])
                if (band.height() < minimumTouchSize) continue
                if (obstacles.any { Rect.intersects(it, band) }) continue
                if (band.height() > bestBottom - bestTop) {
                    bestTop = band.top
                    bestBottom = band.bottom
                }
            }
        }
        if (bestBottom - bestTop < minimumTouchSize) return false
        measureNotices(width, bestBottom - bestTop, besideDock = false)
        val scrollWidth = noticeScroll.measuredWidth
        val scrollHeight = noticeScroll.measuredHeight
        val actions = listOf(shizuku).filter { it.visibility == VISIBLE }
        if (scrollWidth < minimumTouchSize || scrollHeight < minimumTouchSize ||
            actions.any { it.width > scrollWidth || it.height > scrollHeight }
        ) {
            measureUpperNotice()
            return false
        }
        val noticeLeft = ((viewportWidth - scrollWidth) / 2 - left)
            .coerceIn(0, (width - scrollWidth).coerceAtLeast(0))
        val noticeTop = (bestBottom - scrollHeight).coerceAtLeast(bestTop)
        noticeScroll.scrollTo(0, 0)
        noticeScroll.layout(noticeLeft, noticeTop, noticeLeft + scrollWidth, noticeTop + scrollHeight)
        return true
    }


    /**
     * Chooses a hit rectangle that contains the unchanged artwork and avoids the menu.
     * @param dockLeft Original artwork slot's left edge.
     * @param targetWidth Required native-pixel target width.
     * @param targetHeight Required native-pixel target height.
     * @return The chosen target, or a logged fallback when no feasible target exists.
     */
    private fun broadcastHitBounds(dockLeft: Int, targetWidth: Int, targetHeight: Int): Rect {
        val artSize = viewport.px(84f)
        val artLeft = dockLeft + (actionSize - artSize) / 2f
        val artTop = actionTop + (actionSize - artSize) / 2f
        val minX = maxOf(0, kotlin.math.ceil((artLeft + artSize - targetWidth).toDouble()).toInt())
        val maxX = minOf(width - targetWidth, kotlin.math.floor(artLeft.toDouble()).toInt())
        val minY = maxOf(0, kotlin.math.ceil((artTop + artSize - targetHeight).toDouble()).toInt())
        val maxY = minOf(height - targetHeight, kotlin.math.floor(artTop.toDouble()).toInt())
        val originalX = dockLeft - (targetWidth - actionSize) / 2
        val originalY = actionTop - (targetHeight - actionSize) / 2
        if (minX > maxX || minY > maxY) {
            Log.e("StandbyForeground", "Broadcast artwork and minimum touch target do not fit inside the safe viewport")
            val x = originalX.coerceIn(0, (width - targetWidth).coerceAtLeast(0))
            val y = originalY.coerceIn(0, (height - targetHeight).coerceAtLeast(0))
            return Rect(x, y, x + targetWidth, y + targetHeight)
        }
        val preferredX = originalX.coerceIn(minX, maxX)
        val preferredY = originalY.coerceIn(minY, maxY)
        val menuBounds = menuHitBounds()
        val xs = listOf(preferredX, menuBounds.right, menuBounds.left - targetWidth).filter { it in minX..maxX }
        val ys = listOf(preferredY, menuBounds.bottom, menuBounds.top - targetHeight).filter { it in minY..maxY }
        val candidates = xs.flatMap { x -> ys.map { y -> Rect(x, y, x + targetWidth, y + targetHeight) } }
            .filter { menu.visibility != VISIBLE || !Rect.intersects(it, menuBounds) }
        val result = candidates.minByOrNull {
            kotlin.math.abs(it.left - preferredX).toLong() + kotlin.math.abs(it.top - preferredY).toLong()
        }
        if (result != null) return result
        Log.e("StandbyForeground", "Menu and broadcast hit targets cannot fit without overlap")
        return Rect(preferredX, preferredY, preferredX + targetWidth, preferredY + targetHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutControls()
    }

    /** Selects Matter's area-normalized 8/12 dock margin or CSS short-landscape margin, minus safe bottom inset. */
    private fun dockBottomMarginPx(): Int {
        val shortDock = if (theme == StandbyControlTheme.MATTER) {
            viewport.shortAreaLandscape
        } else {
            viewport.shortLandscape
        }
        val minimumBottom = dp(if (shortDock) 8f else 12f)
        return (minimumBottom - safeBottomInset).coerceAtLeast(0)
    }

    /** Repositions real targets before hit/notice collision checks, preserving all artwork anchors. */
    private fun layoutControls() {
        val dockBottom = height - dockBottomMarginPx()
        actionTop = dockBottom - actionSize
        val dockLeft = (viewportWidth - actionSize) / 2 - left
        val targetWidth = broadcast.measuredWidth
        val targetHeight = broadcast.measuredHeight
        val desiredMenuTop = hardlineHandleCenterPx?.let { it - top - menu.measuredHeight / 2f }
            ?: ((viewportHeight - menu.measuredHeight) / 2 - top).toFloat()
        val menuTop = desiredMenuTop.coerceIn(0f, (height - menu.measuredHeight).coerceAtLeast(0).toFloat())
        val menuLayoutTop = kotlin.math.floor(menuTop.toDouble()).toInt()
        menu.layout(0, menuLayoutTop, menu.measuredWidth, menuLayoutTop + menu.measuredHeight)
        menu.translationY = menuTop - menuLayoutTop
        if (menu.visibility == VISIBLE && (menu.measuredWidth > width || menu.measuredHeight > height)) {
            Log.e("StandbyForeground", "Menu minimum touch target exceeds the safe viewport")
        }
        val target = if (broadcast.visibility == VISIBLE) {
            broadcastHitBounds(dockLeft, targetWidth, targetHeight)
        } else {
            Rect(dockLeft, actionTop, dockLeft + targetWidth, actionTop + targetHeight)
        }
        broadcastArt.visualLeft = dockLeft - target.left + (actionSize - viewport.px(84f)) / 2f
        broadcastArt.visualTop = actionTop - target.top + (actionSize - viewport.px(84f)) / 2f
        broadcast.layout(target.left, target.top, target.right, target.bottom)
                layoutUpperNotice()
    }

    /** Places the ordinary notice using the actual dock anchor. */
    private fun layoutUpperNotice() {
        val noticeBottom = if (broadcast.visibility == VISIBLE) minOf(actionTop, broadcast.top) else actionTop + actionSize
        val noticeTop = (noticeBottom - noticeScroll.measuredHeight - dp(6f)).coerceAtLeast(0)
        val noticeLeft = ((viewportWidth - noticeScroll.measuredWidth) / 2 - left)
            .coerceIn(0, (width - noticeScroll.measuredWidth).coerceAtLeast(0))
        noticeScroll.layout(noticeLeft, noticeTop, noticeLeft + noticeScroll.measuredWidth, noticeTop + noticeScroll.measuredHeight)
    }
}

/**
 * Computes the vertical space available to the upper notice scroll viewport above the dock.
 * @param controlsHeight Measured controls height in native pixels.
 * @param bottomMargin Dock bottom margin remaining after safe bottom inset.
 * @param dockHeight Laid-out broadcast dock footprint, or zero when hidden.
 * @param gap Vertical spacing between the notice viewport and the dock.
 * @return Clamped maximum height in native pixels.
 */
internal fun upperNoticeMaxHeight(
    controlsHeight: Int,
    bottomMargin: Int,
    dockHeight: Int,
    gap: Int,
): Int = (controlsHeight - bottomMargin - dockHeight - gap).coerceAtLeast(0)

/** Draws the prototype SVG inside a separate, keyboard-focusable hit target. */
private class StandbyControlArt(
    context: Context,
    private val theme: StandbyControlTheme,
    private val menu: Boolean,
) : View(context) {
    var viewportScale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var visualLeft: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var visualTop: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var checked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var infoAction: Boolean = false
    var warning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    /** Reused shape bounds so [onDraw] allocates nothing per frame. */
    private val artBounds = RectF()
    private var artworkScale = 1f
    private var pressAnimator: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (menu) {
            info.className = android.widget.Button::class.java.name
        } else {
            info.className = if (infoAction) android.widget.Button::class.java.name else android.widget.Switch::class.java.name
            info.isCheckable = !infoAction
            info.isChecked = !infoAction && checked
        }
    }

    /** Animates only the painted artwork, never the View's hit or accessibility bounds. */
    private fun animateArtwork(scale: Float, durationMillis: Long) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(artworkScale, scale).apply {
            duration = durationMillis
            addUpdateListener {
                artworkScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The A/D settings handle does not share the broadcast toggle's :active scale.
        if (menu && theme != StandbyControlTheme.MATTER) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateArtwork(
                if (theme == StandbyControlTheme.MATTER) 0.92f else 0.9f,
                if (theme == StandbyControlTheme.MATTER) 120L else 160L,
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateArtwork(1f, 160L)
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        pressAnimator?.cancel()
        pressAnimator = null
        artworkScale = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        val artWidth = (if (menu) 24f else 84f) * viewportScale
        val artHeight = (if (menu) 88f else 84f) * viewportScale
        val artLeft = if (menu) 0f else visualLeft
        val artTop = if (menu) (height - artHeight) / 2f else visualTop
        canvas.scale(artworkScale, artworkScale, artLeft + artWidth / 2f, artTop + artHeight / 2f)
        canvas.translate(artLeft, artTop)
        canvas.scale(artWidth / if (menu) 24f else 100f, artHeight / if (menu) 88f else 100f)
        val legacy = theme == StandbyControlTheme.MATTER
        // Broadcast paths use non-scaling CSS strokes inside the fixed 84px/100-unit SVG.
        val strokeUnit = if (legacy || menu) 1f else 100f / 84f
        paint.strokeWidth = if (legacy) 1.3f else if (menu) 1f else 1.3f * strokeUnit
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = if (legacy) Paint.Join.ROUND else Paint.Join.MITER
        paint.strokeCap = if (legacy) Paint.Cap.ROUND else Paint.Cap.BUTT
        path.reset()
        if (menu) {
            if (theme == StandbyControlTheme.LANDSCAPE) {
                path.moveTo(0f, 16f); path.cubicTo(5f, 9f, 13f, 12f, 14f, 20f)
                path.cubicTo(22f, 19f, 23f, 31f, 17f, 36f); path.cubicTo(23f, 41f, 23f, 50f, 17f, 55f)
                path.cubicTo(23f, 62f, 18f, 73f, 12f, 72f); path.cubicTo(10f, 81f, 3f, 81f, 0f, 75f); path.close()
            } else {
                path.moveTo(0f, 10f); path.lineTo(10f, 10f); path.lineTo(21f, 21f)
                path.lineTo(21f, 67f); path.lineTo(10f, 78f); path.lineTo(0f, 78f)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (theme == StandbyControlTheme.LANDSCAPE) 0xE814281C.toInt() else 0xE8090909.toInt()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.color = if (theme == StandbyControlTheme.LANDSCAPE) 0xFFE3F5E9.toInt() else Color.WHITE
            canvas.drawPath(path, paint)
            if (!legacy) paint.alpha = 89
            canvas.drawLine(3f, 30f, 3f, 58f, paint)
            paint.color = Color.WHITE
            path.reset(); path.moveTo(10f, 36f); path.lineTo(16f, 44f); path.lineTo(10f, 52f)
            canvas.drawPath(path, paint)
        } else {
            val frameSave = canvas.save()
            if (theme != StandbyControlTheme.HARDLINE) {
                canvas.scale(1.12f, 1.12f, 50f, 50f)
                paint.strokeWidth /= 1.12f
            }
            when (theme) {
                StandbyControlTheme.LANDSCAPE -> {
                    artBounds.set(7f, 13f, 93f, 83f)
                    path.addRoundRect(artBounds, 35f, 35f, Path.Direction.CW)
                }
                StandbyControlTheme.MATTER -> {
                    path.moveTo(12f, 18f); path.lineTo(88f, 18f); path.lineTo(50f, 83.81793f); path.close()
                }
                StandbyControlTheme.HARDLINE -> {
                    path.moveTo(34f, 18f); path.lineTo(86f, 18f); path.lineTo(86f, 58f)
                    path.lineTo(62f, 82f); path.lineTo(10f, 82f); path.lineTo(10f, 42f); path.close()
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = when (theme) {
                StandbyControlTheme.LANDSCAPE -> 0xE6193B30.toInt()
                StandbyControlTheme.HARDLINE -> 0xFF262A28.toInt()
                StandbyControlTheme.MATTER -> 0xE6262A28.toInt()
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            canvas.drawPath(path, paint)
            if (theme == StandbyControlTheme.MATTER) canvas.drawLine(10f, 28f, 38f, 76.49742f, paint)
            canvas.restoreToCount(frameSave)
            paint.strokeWidth = if (legacy) 1.3f else 1.8f * strokeUnit
            val symbolSave = canvas.save()
            when (theme) {
                StandbyControlTheme.MATTER -> canvas.translate(0f, -8.333333f)
                StandbyControlTheme.HARDLINE -> canvas.translate(-2.380952f, 2.380952f)
                StandbyControlTheme.LANDSCAPE -> Unit
            }
            if (warning) {
                paint.color = if (theme == StandbyControlTheme.LANDSCAPE) Color.WHITE else 0xFFFF5757.toInt()
                paint.strokeWidth = 1.8f * strokeUnit
                paint.style = Paint.Style.STROKE
                canvas.drawLine(50f, 33f, 50f, 51f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(50f, 59f, 1.6f, paint)
            } else if (theme == StandbyControlTheme.LANDSCAPE) {
                paint.color = if (checked) 0xFFFF7777.toInt() else 0xFF68DC94.toInt()
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                if (checked) {
                    artBounds.set(39f, 37f, 61f, 59f)
                    canvas.drawRoundRect(artBounds, 4f, 4f, paint)
                } else {
                    path.reset(); path.moveTo(44f, 36f); path.quadTo(42f, 35f, 42f, 38f)
                    path.lineTo(42f, 58f); path.quadTo(42f, 61f, 44f, 60f); path.lineTo(61f, 50f)
                    path.quadTo(64f, 48f, 61f, 46f); path.close()
                    canvas.drawPath(path, paint)
                }
            } else if (checked) {
                canvas.drawLine(40f, 37f, 60f, 57f, paint); canvas.drawLine(60f, 37f, 40f, 57f, paint)
            } else {
                path.reset(); path.moveTo(50f, 35f); path.lineTo(61f, 55f); path.lineTo(39f, 55f); path.close()
                canvas.drawPath(path, paint)
            }
            canvas.restoreToCount(symbolSave)
        }
        canvas.restoreToCount(save)
        if (isFocused) {
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * viewportScale
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, paint)
        }
    }
}
