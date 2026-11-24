package me.trinitrix.mirax.ui.standby

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import java.math.BigDecimal
import java.math.RoundingMode
import androidx.core.graphics.Insets
import androidx.core.view.doOnPreDraw
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.foreground.HardlineForegroundPixelRects
import me.trinitrix.mirax.ui.standby.foreground.HardlineStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.LandscapeStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.MatterStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView
import me.trinitrix.mirax.ui.standby.media.StandbyMediaUnderlayView
import me.trinitrix.mirax.ui.standby.runtime.StandbyOledDriftMath

/**
 * Full-window standby surface retaining one scene and foreground per theme.
 *
 * @param context activity context for the view hierarchy.
 * @param callbacks actions owned by the activity.
 */
class BigStandbyHost(
    context: Context,
    private val callbacks: StandbyForegroundCallbacks,
) : FrameLayout(context) {
    /** The single media output; the playback owner controls its binding. */
    val mediaUnderlay = StandbyMediaUnderlayView(context)

    // The editor reparents the underlay, so its readability mask must remain in the host.
    private val mediaScrim = View(context).apply {
        setBackgroundColor(0xC6000000.toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }
    private val landscapeScene = LandscapeSceneView(context)
    private val matterScene = MatterParticleView(context)
    private val hardlineScene = HardlineSceneView(context)
    private val landscapeForeground = LandscapeStandbyForegroundView(context)
    private val matterForeground = MatterStandbyForegroundView(context)
    private val hardlineForeground = HardlineStandbyForegroundView(context)
    private val unavailableMenu = ImageButton(context).apply {
        tag = "standby-theme-unavailable-menu"
        setImageResource(R.drawable.ic_nav_menu)
        contentDescription = context.getString(R.string.nav_menu)
        val padding = (12f * resources.displayMetrics.density + 0.5f).toInt()
        setPadding(padding, padding, padding, padding)
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attributes.getDrawable(0)
        attributes.recycle()
        setOnClickListener { callbacks.onOpenSettings?.invoke() }
    }
    private var theme: StandbyThemeId? = null
    private var presentationState: StandbyForegroundState? = null
    private var mediaVisible = false
    private var runtimeVisible = false
    private var hardlineGeometry: HardlineForegroundPixelRects? = null
    private var projecting = false
    private var legacyElapsedSeconds = 0.0
    private var legacyPreviousUptimeMillis: Long? = null
    private var legacyFrameCallback: Choreographer.FrameCallback? = null
    private var legacyObserver: ViewTreeObserver? = null
    private val stageLocation = IntArray(2)
    private val legacyPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        syncLegacyFrames()
        applyLegacyAmbient()
        true
    }

    /** Monotonic frame time; changing themes or media never resets the retained legacy phase. */
    internal var clockUptimeMillis: () -> Long = { SystemClock.uptimeMillis() }

    /** Called after a real viewport size change. */
    var onViewportChanged: (() -> Unit)? = null

    /** Reports whether the selected Matter foreground is attached. */
    var onMatterMotionAvailabilityChanged: ((Boolean) -> Unit)? = null

    init {
        matterForeground.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = reportMatterMotionAvailability()
            override fun onViewDetachedFromWindow(view: View) {
                onMatterMotionAvailabilityChanged?.invoke(false)
            }
        })
        val layers = listOf(
            mediaUnderlay,
            mediaScrim,
            landscapeScene,
            matterScene,
            hardlineScene,
            landscapeForeground,
            matterForeground,
            hardlineForeground,
        )
        layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        val menuSize = (48f * resources.displayMetrics.density + 0.5f).toInt()
        addView(unavailableMenu, LayoutParams(menuSize, menuSize, Gravity.TOP or Gravity.LEFT))
        landscapeForeground.onForegroundGeometryChanged = { geometry ->
            landscapeScene.setHostForegroundGeometry(geometry?.brandBottomPx, geometry?.primaryTopPx)
        }
        hardlineForeground.onForegroundGeometryChanged = { geometry ->
            hardlineGeometry = geometry
            updateHardlineGeometry()
        }
        hardlineScene.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            hardlineScene.doOnPreDraw { updateHardlineGeometry() }
        }
        setRuntimeVisible(false)
        updateLayerVisibility()
    }

    /**
     * Applies real session presentation without replacing any scene.
     *
     * @param selectedTheme persisted theme identity.
     * @param state real session and failure presentation.
     * @param title localized primary state text.
     * @param eyebrow localized secondary state label.
     * @param detail localized supporting text.
     */
    fun render(
        selectedTheme: StandbyThemeId?,
        state: StandbyForegroundState,
        title: String,
        eyebrow: String,
        detail: String,
    ) {
        presentationState = state
        theme = selectedTheme
        projecting = state.isProjecting ?: (state.phase == ScreenPhase.CONNECTED)
        forwardHardlinePresentation()
        landscapeScene.setLandscapeState(state.phase, state.advertisingEnabled)
        matterScene.setMatterState(
            state.phase,
            state.advertisingEnabled,
            state.phase == ScreenPhase.WIFI_PAUSED,
        )
        foregrounds().forEach { it.render(state, title, eyebrow, detail, callbacks) }
        updateLayerVisibility()
    }

    /** Updates only the clock, without rendering the session. */
    fun updateClock(text: String) {
        foregrounds().forEach { it.updateClock(text) }
    }

    /** Updates only the current failure presentation, without rendering the session. */
    fun updateFailure(cause: PrePlayFailureCause?, text: String) {
        presentationState = presentationState?.copy(failureCause = cause, failureText = text)
        forwardHardlinePresentation()
        foregrounds().forEach { it.updateFailure(cause, text) }
    }

    /** Applies the window's actual safe insets once to each foreground. */
    fun applySafeInsets(insets: Insets) {
        foregrounds().forEach { it.applySecurityInsets(insets) }
        unavailableMenu.layoutParams = (unavailableMenu.layoutParams as LayoutParams).apply {
            leftMargin = insets.left
            topMargin = insets.top
        }
    }

    /** Pauses or resumes scene frame scheduling for the activity visibility gate. */
    fun setRuntimeVisible(visible: Boolean) {
        runtimeVisible = visible
        forwardHardlinePresentation()
        updateSceneRuntime()
        syncLegacyFrames()
    }

    /** Detaches the same output for the framing editor. */
    fun takeMediaPreview(): StandbyMediaUnderlayView {
        (mediaUnderlay.parent as? ViewGroup)?.removeView(mediaUnderlay)
        return mediaUnderlay
    }

    /** Restores the editor output below the retained scenes. */
    fun restoreMediaPreview() {
        if (mediaUnderlay.parent === this) return
        (mediaUnderlay.parent as? ViewGroup)?.removeView(mediaUnderlay)
        addView(mediaUnderlay, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Replaces the ambient scene with confirmed usable custom media. */
    fun setMediaVisible(visible: Boolean) {
        if (mediaVisible == visible) return
        mediaVisible = visible
        forwardHardlinePresentation()
        updateLayerVisibility()
    }

    /** Applies the approved Matter-only parallax and OLED offsets in view pixels. */
    fun updateMatterMotion(canvasX: Float, canvasY: Float, infoX: Float, infoY: Float) {
        matterScene.setParticleLayerOffset(canvasX, canvasY)
        matterForeground.motionLayer.translationX = infoX
        matterForeground.motionLayer.translationY = infoY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        legacyObserver = viewTreeObserver.also { it.addOnPreDrawListener(legacyPreDrawListener) }
        applyLegacyAmbient()
        syncLegacyFrames()
    }

    override fun onDetachedFromWindow() {
        stopLegacyFrames()
        legacyObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(legacyPreDrawListener) }
        legacyObserver = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncLegacyFrames()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLegacyFrames()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        syncLegacyFrames()
        if (width != oldWidth || height != oldHeight) {
            // Child fit must see the new full-stage center before laying out the handle.
            val burnY = StandbyOledDriftMath.burnOffsets(legacyElapsedSeconds).y
            hardlineForeground.prepareLegacyHandleForResize(legacyHandleCenter(burnY))
            onViewportChanged?.invoke()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyLegacyAmbient()
    }

    /** Checks the host visibility gate without coupling legacy time to Matter's sensor policy. */
    private fun shouldAnimateLegacy(): Boolean {
        if (!runtimeVisible || !isAttachedToWindow || width <= 0 || height <= 0 || windowVisibility != VISIBLE) return false
        var ancestor: View? = this
        while (ancestor != null) {
            if (ancestor.visibility != VISIBLE || !(ancestor.alpha > 0f)) return false
            ancestor = ancestor.parent as? View
        }
        return true
    }

    /** Retires the callback and resume timestamp, retaining the shared elapsed phase and position. */
    private fun stopLegacyFrames() {
        val retired = legacyFrameCallback
        legacyFrameCallback = null
        retired?.let { Choreographer.getInstance().removeFrameCallback(it) }
        legacyPreviousUptimeMillis = null
    }

    /** Owns exactly one legacy callback while the host can actually draw. */
    private fun syncLegacyFrames() {
        if (!shouldAnimateLegacy()) {
            stopLegacyFrames()
            return
        }
        if (legacyFrameCallback != null) return
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (legacyFrameCallback !== this) return
                if (!shouldAnimateLegacy()) {
                    stopLegacyFrames()
                    return
                }
                val now = clockUptimeMillis()
                if (legacyFrameCallback !== this) return
                if (!shouldAnimateLegacy()) {
                    stopLegacyFrames()
                    return
                }
                val previous = legacyPreviousUptimeMillis
                if (previous != null) legacyElapsedSeconds += (now - previous).coerceIn(0L, 80L) / 1000.0
                legacyPreviousUptimeMillis = now
                applyLegacyAmbient()
                if (legacyFrameCallback === this) {
                    if (shouldAnimateLegacy()) Choreographer.getInstance().postFrameCallback(this) else stopLegacyFrames()
                }
            }
        }
        legacyFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Applies the frozen CSS/SVG unit conversions; Matter offsets remain independently owned. */
    private fun applyLegacyAmbient() {
        val burn = StandbyOledDriftMath.burnOffsets(legacyElapsedSeconds)
        val density = resources.displayMetrics.density.toDouble()
        val x = (fixedLegacy(burn.x) * density).toFloat()
        val y = (fixedLegacy(burn.y) * density).toFloat()
        landscapeForeground.setLegacyAmbientOffset(x, y)
        hardlineForeground.setLegacyAmbientOffset(x, y, legacyHandleCenter(burn.y))
        hardlineScene.setLegacySvgOffset(fixedLegacy(burn.x * 1.4), fixedLegacy(burn.y * 1.4))
    }

    /**
     * @param burnY Current legacy drift in CSS pixels.
     * @return Hardline's full-stage handle center in pixels, or null for the fallback.
     */
    private fun legacyHandleCenter(burnY: Double): Float? {
        if (theme != StandbyThemeId.HARDLINE || mediaVisible || projecting || height <= 0) return null
        getLocationInWindow(stageLocation)
        val density = resources.displayMetrics.density.toDouble()
        return (fixedLegacy(stageLocation[1] / density + height / density * 0.47 + burnY) * density - stageLocation[1]).toFloat()
    }

    /** Matches the prototype's toFixed(2) convention already used by LandscapeGeometry. */
    private fun fixedLegacy(value: Double): Double =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun reportMatterMotionAvailability() {
        onMatterMotionAvailabilityChanged?.invoke(matterForeground.isAttachedToWindow && theme == StandbyThemeId.MATTER)
    }

    private fun foregrounds(): List<StandbyForegroundView> =
        listOf(landscapeForeground, matterForeground, hardlineForeground)

    private fun updateHardlineGeometry() {
        val hasMeasuredViewport = hardlineScene.visibility == View.VISIBLE && hardlineScene.isLaidOut &&
            hardlineForeground.visibility == View.VISIBLE && hardlineForeground.isLaidOut &&
            !hardlineForeground.isLayoutRequested &&
            hardlineScene.width == hardlineForeground.width && hardlineScene.height == hardlineForeground.height
        val geometry = hardlineGeometry.takeIf { hasMeasuredViewport }
        val band = geometry?.centerBand?.takeUnless { it.isEmpty }
        val headerLower = if (hasMeasuredViewport) {
            hardlineHeaderLowerPx(hardlineScene.width, hardlineScene.height, resources.displayMetrics.density)
        } else {
            null
        }
        hardlineScene.setHostFieldGeometry(
            headerLower, band?.top?.toFloat(), band?.bottom?.toFloat(), geometry?.receiver, geometry?.dock,
        )
    }

    private fun updateLayerVisibility() {
        mediaScrim.visibility = if (mediaVisible) View.VISIBLE else View.GONE
        landscapeScene.visibility = if (theme == StandbyThemeId.LANDSCAPE && !mediaVisible) View.VISIBLE else View.GONE
        matterScene.visibility = if (theme == StandbyThemeId.MATTER && !mediaVisible) View.VISIBLE else View.GONE
        hardlineScene.visibility = if (theme == StandbyThemeId.HARDLINE && !mediaVisible) View.VISIBLE else View.GONE
        landscapeForeground.visibility = if (theme == StandbyThemeId.LANDSCAPE) View.VISIBLE else View.GONE
        matterForeground.visibility = if (theme == StandbyThemeId.MATTER) View.VISIBLE else View.GONE
        hardlineForeground.visibility = if (theme == StandbyThemeId.HARDLINE) View.VISIBLE else View.GONE
        unavailableMenu.visibility = if (theme == null && callbacks.onOpenSettings != null) View.VISIBLE else View.GONE
        applyLegacyAmbient()
        updateHardlineGeometry()
        updateSceneRuntime()
        syncLegacyFrames()
        reportMatterMotionAvailability()
    }

    private fun updateSceneRuntime() {
        val showScene = runtimeVisible && !mediaVisible
        landscapeScene.setProjecting(!showScene || theme != StandbyThemeId.LANDSCAPE)
        matterScene.setProjecting(!showScene || theme != StandbyThemeId.MATTER)
    }

    /** Delivers one immutable state/context snapshot before generated-child visibility changes. */
    private fun forwardHardlinePresentation() {
        val state = presentationState
        hardlineScene.setHostPresentation(state?.phase, state?.receiverName.orEmpty(), state?.failureCause != null,
            theme == StandbyThemeId.HARDLINE, runtimeVisible, mediaVisible, projecting)
    }
}

/** Maps the scaled header rule to measured scene pixels. */
internal fun hardlineHeaderLowerPx(width: Int, height: Int, density: Float = 1f): Float? {
    if (width <= 0 || height <= 0) return null
    val slice = HardlineSceneView.sliceTransformFor(width.toDouble(), height.toDouble())
    return slice.mapY(HARDLINE_HEADER_STAGE_Y) +
        density * HardlineSceneView.HEADER_WIDTH_PX / 2f
}

// shortcut: stage y is duplicated from the prototype, switch to a scene geometry API if it changes.
private const val HARDLINE_HEADER_STAGE_Y = 68.0
