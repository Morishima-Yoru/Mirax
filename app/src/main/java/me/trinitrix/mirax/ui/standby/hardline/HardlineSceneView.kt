package me.trinitrix.mirax.ui.standby.hardline

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale

/**
 * Standalone background-only candidate for the approved Hardline visual
 * (formal name `硬線 · MIRAX 形構`, English identity Hardline).
 *
 * Ports only the approved default motion `study-118-section-extrusion`
 * (`design/big-standby-motion-prototype/118-section-extrusion.js`) over the
 * static Hardline scene (`design/big-standby-prototype.html` `#hardline-scene`):
 * `mirax-mark` X arms plus center diamond, the sliced `1600x1000`
 * `xMidYMid slice` stage mapping, the paired lower-left rails, the verticals,
 * the header rule, and the 118 underlay / longitudinal edges / 4 travelling
 * section frames / outer projection guides. ROG channels sit below the complete
 * static SVG; the viewport corner diamond and generated readability scrim sit
 * above it. The host owns all foreground text, plates, and controls.
 *
 * Excluded on purpose: the `signal` / `scan` / `quiet` / `off` contour-current
 * modes and every other study are never rendered; this view carries only the
 * approved default motion.
 *
 * DOM values this background cannot derive (real header center line, center
 * information band, and receiver/dock bounds) arrive through [setHostFieldGeometry]; while unknown
 * the 118 field clip falls back to the full view and the underlay plus outer
 * guides are skipped, exactly like the prototype does when its frame regions
 * are empty. Spacing and typography are never invented here.
 *
 * Backdrop timing retains its capped monotonic frame deltas and existing visibility
 * gates. Status uses raw timestamps from the same clock with separate removal,
 * stop and media-pause semantics. Frames still use [Choreographer] plus
 * [View.postInvalidateOnAnimation]; no animator duration scales or OS settings change.
 * [setProjecting] remains an explicit standalone stop and never touches the decoder.
 *
 * This background alone is NOT full v41 acceptance.
 */
class HardlineSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Explicit draw evidence from the attached view.
     *
     * Null [canvasWasHardwareAccelerated] means no draw happened yet and must be
     * treated as unavailable evidence, never as a pass or fail.
     *
     * @param observed true once [onDraw] ran while attached.
     * @param drawCount total [onDraw] executions while attached.
     * @param canvasWasHardwareAccelerated value of [Canvas.isHardwareAccelerated].
     * @param activeElapsedSeconds accumulated visible monotonic time driving motion.
     */
    data class HardlineDrawEvidence(
        val observed: Boolean,
        val drawCount: Int,
        val canvasWasHardwareAccelerated: Boolean?,
        val activeElapsedSeconds: Double,
    )

    /**
     * One travelling section frame on the 118 extrusion axis.
     *
     * Mutable so the render loop can reuse a single instance instead of
     * allocating one per frame.
     *
     * @param progress travel position in `[0, 1)`.
     * @param depth extrusion distance in px along [directionX]/[directionY].
     * @param taper polygon shrink factor toward the section center.
     * @param opacity stroke alpha in `[0, 1]`.
     */
    data class SectionFrame(
        var progress: Double,
        var depth: Double,
        var taper: Double,
        var opacity: Double,
    )

    /** Monotonic timestamp source, injectable for deterministic tests. */
    var clockUptimeMillis: () -> Long = { SystemClock.uptimeMillis() }

    /** Optional per-draw callback for future hosts and tests; never blocks drawing. */
    var onFrameDrawn: (() -> Unit)? = null

    /** Latest attached draw evidence. */
    var drawEvidence: HardlineDrawEvidence = HardlineDrawEvidence(
        observed = false,
        drawCount = 0,
        canvasWasHardwareAccelerated = null,
        activeElapsedSeconds = 0.0,
    )
        private set

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }

    /** Reused draw paths and matrices; [onDraw] must not allocate per frame. */
    private val scratchPath = Path()
    private val edgePath = Path()
    private val sectionPath = Path()
    private val underlayPath = Path()
    private val guidesPath = Path()
    private val mappedPoint = FloatArray(2)
    private var cachedGeometry: Geometry? = null
    /** Reused travelling section frame; [onDraw] must not allocate per frame. */
    private val scratchSectionFrame = SectionFrame(0.0, 0.0, 1.0, 0.0)
    /** Reused `[top, bottom]` clip bands for the two 118 field regions, plus its count. */
    private val bandEdges = FloatArray(4)

    /**
     * One arm polygon with its centroid, both precomputed when the view size changes.
     *
     * @param vertices mapped vertices in px.
     * @param center precomputed centroid in px.
     */
    private class SectionPolygon(
        val vertices: List<Pair<Double, Double>>,
        val center: Pair<Double, Double>,
    )

    /**
     * Size-derived scene state: slice mapping, static rails, mark matrices, and
     * the arm polygons, all rebuilt only when the view size changes.
     */
    private class Geometry(
        val viewWidth: Float,
        val viewHeight: Float,
        val slice: SliceTransform,
        val viewport: StandbyViewportScale,
        val polygons: List<SectionPolygon>,
        val baseAngle: Double,
        val railPath: Path,
        val verticalPath: Path,
        val coreMatrix: Matrix,
        val echoMatrix: Matrix,
    )

    private var projecting = false
    private var headerLowerPx: Float? = null
    private var bandUpperPx: Float? = null
    private var bandLowerPx: Float? = null
    private val receiverBoundsPx = RectF()
    private val dockBoundsPx = RectF()
    private var legacySvgX = 0.0
    private var legacySvgY = 0.0
    private var legacyOffsetXPx = 0f
    private var legacyOffsetYPx = 0f
    private var activeElapsedSeconds = 0.0
    private var lastUptimeMillis: Long? = null
    private var frameScheduled = false
    private var localizedContext: Context = context
    private val rogScene = RogHardlineScene(statusCopy = resolveStatusCopy()).apply {
        setStatusTextLocales(localizedContext.resources.configuration.locales)
    }
    private var statusPhase: ScreenPhase? = null
    private var statusReceiverName = ""
    private var statusHasFailure = false
    private var hostManaged = false
    private var hostThemeIsD = false
    private var hostRuntimeVisible = false
    private var hostMediaReady = false
    private var hostActualProjecting = false
    private var statusAttached = false
    private var lastStatusMode = RogHardlineScene.StatusMode.REMOVED
    private var statusReady = true
    private val channelTextSizePx: (Float) -> Float = { size ->
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
    }

    /** Frame-loop state for deterministic lifecycle checks. */
    internal val animationFrameScheduled: Boolean get() = frameScheduled
    internal val animationElapsedSeconds: Double get() = activeElapsedSeconds
    internal var animationCallbackCount = 0L
        private set

    private var frameCallback: Choreographer.FrameCallback? = null

    /**
     * Explicit standalone stop; Host projection/context facts remain independently owned.
     * While true both status and the existing simulation frame loop stop; decoder/audio are untouched.
     *
     * @param projecting true to retain the direct stop, false to release only that stop.
     */
    fun setProjecting(projecting: Boolean) {
        if (this.projecting == projecting) {
            return
        }
        this.projecting = projecting
        commitStatus(clockUptimeMillis())
        scheduleFrameIfNeeded()
    }

    /** Receives the already-rounded legacy SVG user-unit shift, independent of study time and density. */
    internal fun setLegacySvgOffset(x: Double, y: Double) {
        if (legacySvgX == x && legacySvgY == y) return
        legacySvgX = x
        legacySvgY = y
        postInvalidateOnAnimation()
    }

    /**
     * Real external clip dimensions from the host layout, in view pixels.
     *
     * The prototype derives these from the real header center line and the real
     * center information band, which a background layer cannot measure. Pass
     * nulls while unknown; the 118 field clip then falls back to the full view
     * and the underlay plus outer guides are skipped.
     *
     * @param headerLowerPx lower edge of the header rule, or null when unknown.
     * @param bandUpperPx top edge of the center information band, or null.
     * @param bandLowerPx bottom edge of the center information band, or null.
     * @param receiverBoundsPx actual visible receiver bounds, null or empty when unavailable.
     * @param dockBoundsPx actual visible broadcast dock bounds, null or empty when unavailable.
     */
    fun setHostFieldGeometry(
        headerLowerPx: Float?,
        bandUpperPx: Float?,
        bandLowerPx: Float?,
        receiverBoundsPx: Rect? = null,
        dockBoundsPx: Rect? = null,
    ) {
        this.headerLowerPx = headerLowerPx
        this.bandUpperPx = bandUpperPx
        this.bandLowerPx = bandLowerPx
        if (receiverBoundsPx == null) this.receiverBoundsPx.setEmpty() else this.receiverBoundsPx.set(receiverBoundsPx)
        if (dockBoundsPx == null) this.dockBoundsPx.setEmpty() else this.dockBoundsPx.set(dockBoundsPx)
        postInvalidateOnAnimation()
    }

    /**
     * Applies the real standby state to the Row 3 dynamic status marquee.
     *
     * @param phase real screen phase from the caller.
     * @param advertisingEnabled real broadcast intent; retained for host parity,
     * the status copy is fully phase-driven like the prototype.
     * @param receiverName real effective broadcast name, blank when unknown.
     * @param hasFailure true while the host shows a genuine failure presentation.
     */
    fun setHardlineState(
        phase: ScreenPhase,
        advertisingEnabled: Boolean,
        receiverName: String,
        hasFailure: Boolean,
    ) {
        statusPhase = phase
        statusReceiverName = receiverName
        statusHasFailure = hasFailure
        commitStatus(clockUptimeMillis())
    }

    /** One Host-owned state/context boundary, delivered before the Host changes generated-child visibility. */
    internal fun setHostPresentation(
        phase: ScreenPhase?, receiverName: String, hasFailure: Boolean,
        themeIsD: Boolean, runtimeVisible: Boolean, mediaReady: Boolean, actualProjecting: Boolean,
    ) {
        statusPhase = phase
        statusReceiverName = receiverName
        statusHasFailure = hasFailure
        hostManaged = true
        hostThemeIsD = themeIsD
        hostRuntimeVisible = runtimeVisible
        hostMediaReady = mediaReady
        val wasHostProjecting = hostActualProjecting
        hostActualProjecting = actualProjecting
        commitStatus(clockUptimeMillis(), wasHostProjecting)
        scheduleFrameIfNeeded()
    }

    /** Charges the old interval and publishes a changed visible target even if frames stop. */
    private fun commitStatus(now: Long, wasHostProjecting: Boolean = hostActualProjecting) {
        val oldText = rogScene.statusText.takeUnless { wasHostProjecting }
        val oldAlert = rogScene.statusAlert
        val mode = resolvedStatusMode()
        val phase = statusPhase
        if (phase == null) rogScene.setStatusMode(mode, now) else
            rogScene.setState(phase, statusReceiverName, statusHasFailure, mode, now)
        val text = rogScene.statusText.takeUnless { hostActualProjecting }
        val settledChanged = oldText != text || (text != null && (lastStatusMode != mode || oldAlert != rogScene.statusAlert))
        lastStatusMode = mode
        val publish = mode == RogHardlineScene.StatusMode.RUNNING ||
            (mode == RogHardlineScene.StatusMode.STOPPED && settledChanged)
        if (publish && canDrawGeneratedScene()) postInvalidateOnAnimation()
    }

    /** Media-owned self-GONE is not a page stop; real ancestors, window, detach and direct stop win. */
    private fun resolvedStatusMode(): RogHardlineScene.StatusMode {
        if (hostManaged && !hostThemeIsD) return RogHardlineScene.StatusMode.REMOVED
        if (!statusAttached || windowVisibility != VISIBLE || projecting ||
            (hostManaged && (!hostRuntimeVisible || hostActualProjecting))
        ) return RogHardlineScene.StatusMode.STOPPED
        var ancestor: View? = if (hostManaged) parent as? View else this
        while (ancestor != null) {
            if (ancestor.visibility != VISIBLE) return RogHardlineScene.StatusMode.STOPPED
            ancestor = ancestor.parent as? View
        }
        return if (hostManaged && hostMediaReady) RogHardlineScene.StatusMode.MEDIA_PAUSED else RogHardlineScene.StatusMode.RUNNING
    }

    private fun resolveStatusCopy(): RogHardlineScene.StatusCopy {
        val currentContext = localizedContext
        return RogHardlineScene.StatusCopy(
            ready = currentContext.getString(R.string.standby_foreground_hardline_status_ready),
            arming = currentContext.getString(R.string.standby_foreground_hardline_status_arming),
            wifiPaused = currentContext.getString(R.string.standby_foreground_hardline_status_wifi_paused),
            frozen = currentContext.getString(R.string.standby_foreground_hardline_status_frozen),
            connecting = currentContext.getString(R.string.standby_foreground_phase_connecting_detail),
            failure = currentContext.getString(R.string.standby_foreground_hardline_status_failure),
            formatAdvertising = { name ->
                currentContext.getString(R.string.standby_foreground_connection_hint_format, name)
            },
        )
    }

    /** Visibility callbacks sample only actual context changes; hidden status never polls a clock. */
    private fun syncStatusMode() {
        if (!statusReady) return
        val mode = resolvedStatusMode()
        if (mode == lastStatusMode) return
        rogScene.setStatusMode(mode, clockUptimeMillis())
        lastStatusMode = mode
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        statusAttached = true
        syncStatusMode()
        scheduleFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        statusAttached = false
        syncStatusMode()
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncStatusMode()
        scheduleFrameIfNeeded()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncStatusMode()
        scheduleFrameIfNeeded()
    }

    override fun onSizeChanged(newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(newWidth, newHeight, oldWidth, newHeight)
        if (statusReady) {
            // Zero size stops scheduling, not the CSS status namespace; measurable resume samples lazily.
            val mode = resolvedStatusMode()
            rogScene.setStatusMode(mode, clockUptimeMillis())
            lastStatusMode = mode
        }
        scheduleFrameIfNeeded()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        localizedContext = if (newConfig != null) context.createConfigurationContext(newConfig) else context
        rogScene.refreshStatusCopy(
            resolveStatusCopy(),
            localizedContext.resources.configuration.locales,
        )
        if (canDrawGeneratedScene()) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_COLOR)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth > 0f && viewHeight > 0f) {
            val geometry = geometryFor(viewWidth, viewHeight)
            legacyOffsetXPx = (legacySvgX * geometry.slice.scale).toFloat()
            legacyOffsetYPx = (legacySvgY * geometry.slice.scale).toFloat()
            // Frozen stacking: study, ROG channels, complete static SVG, viewport overlay, generated scrim.
            drawSectionExtrusion(canvas, viewWidth, viewHeight, activeElapsedSeconds, geometry)
            rogScene.draw(canvas, viewWidth, viewHeight, geometry.viewport,
                drawStatus = !hostActualProjecting, textSizePx = channelTextSizePx)
            drawStaticRails(canvas, viewWidth, geometry)
            drawMarkLayer(canvas, geometry)
            drawCornerDiamond(canvas, geometry)
            canvas.drawColor(GENERATED_SCRIM_COLOR)
        }
        val canvasHardware = canvas.isHardwareAccelerated
        val nextCount = drawEvidence.drawCount + 1
        drawEvidence = HardlineDrawEvidence(
            observed = true,
            drawCount = nextCount,
            canvasWasHardwareAccelerated = canvasHardware,
            activeElapsedSeconds = activeElapsedSeconds,
        )
        onFrameDrawn?.invoke()
        scheduleFrameIfNeeded()
    }

    /** Drawing remains possible when a direct or runtime stop retires the animation callback. */
    private fun canDrawGeneratedScene(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == View.VISIBLE && width > 0 && height > 0 &&
            (!hostManaged || (hostThemeIsD && !hostMediaReady))

    private fun shouldAnimate(): Boolean =
        canDrawGeneratedScene() && !projecting && (!hostManaged || hostRuntimeVisible)

    private fun stopFrames() {
        val retiredCallback = frameCallback
        frameCallback = null
        retiredCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameScheduled = false
        lastUptimeMillis = null
    }

    private fun scheduleFrameIfNeeded() {
        if (!shouldAnimate()) {
            stopFrames()
            return
        }
        if (!frameScheduled) {
            val callback = frameCallback ?: object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (frameCallback !== this) return
                    animationCallbackCount++
                    frameScheduled = false
                    if (!shouldAnimate()) {
                        stopFrames()
                        return
                    }
                    val now = clockUptimeMillis()
                    rogScene.advanceStatusTo(now)
                    val previous = lastUptimeMillis
                    if (previous != null) {
                        val delta = (now - previous).toDouble().coerceIn(0.0, MAX_FRAME_DELTA_MILLIS) / 1000.0
                        activeElapsedSeconds += delta
                        rogScene.update(delta)
                    }
                    lastUptimeMillis = now
                    if (frameCallback === this) scheduleFrameIfNeeded()
                    if (!projecting) {
                        postInvalidateOnAnimation()
                    }
                }
            }.also { frameCallback = it }
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    private fun drawStaticRails(canvas: Canvas, viewWidth: Float, geometry: Geometry) {
        val slice = geometry.slice
        // Static paired rails from the base SVG, in 1600x1000 viewBox space.
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = RAIL_COLOR
        strokePaint.strokeWidth = (slice.scale * STATIC_RAIL_WIDTH).toFloat()
        canvas.drawPath(geometry.railPath, strokePaint)
        // Verticals at x = 58 / 1542.
        strokePaint.color = VERTICAL_COLOR
        canvas.drawPath(geometry.verticalPath, strokePaint)
        // Header rule at y = 68: prototype uses non-scaling 2px stroke.
        strokePaint.color = HEADER_COLOR
        strokePaint.strokeWidth = geometry.viewport.px(HEADER_WIDTH_PX)
        canvas.drawLine(0f, slice.mapY(68.0), viewWidth, slice.mapY(68.0), strokePaint)
    }

    /** Static SVG mark: opaque `#090909` X core plus the outline echo. */
    private fun drawMarkLayer(canvas: Canvas, geometry: Geometry) {
        val slice = geometry.slice
        // Both uses contain the complete symbol in its own 108-unit coordinate space.
        drawMark(canvas, geometry.coreMatrix, ARM_FILL_COLOR, ARM_STROKE_COLOR,
            (X_CORE_STROKE_WIDTH * X_CORE_SIZE / MARK_SIZE * slice.scale).toFloat(), fill = true)
        drawMark(canvas, geometry.echoMatrix, 0, ECHO_STROKE_COLOR,
            (X_ECHO_STROKE_WIDTH * X_CORE_SIZE / MARK_SIZE * slice.scale).toFloat(), fill = false)
    }

    /** Draws the viewport overlay using the frozen receiver-right, dock-right, above-receiver order. */
    private fun drawCornerDiamond(canvas: Canvas, geometry: Geometry) {
        if (geometry.viewport.logicalWidth <= 600f || receiverBoundsPx.isEmpty || dockBoundsPx.isEmpty) return
        val size = geometry.viewport.px(26f)
        val gap = geometry.viewport.px(12f)
        for (candidate in 0 until 3) {
            val x = when (candidate) {
                0 -> receiverBoundsPx.right + gap
                1 -> dockBoundsPx.right + gap
                else -> receiverBoundsPx.left
            }
            val y = when (candidate) {
                0 -> receiverBoundsPx.centerY() - size / 2f
                1 -> dockBoundsPx.centerY() - size / 2f
                else -> receiverBoundsPx.top - size - gap
            }
            if (x < gap || y < gap || x + size > geometry.viewWidth - gap ||
                y + size > geometry.viewHeight - gap ||
                diamondHitsObstacle(x, y, size, gap, receiverBoundsPx) ||
                diamondHitsObstacle(x, y, size, gap, dockBoundsPx)
            ) continue
            scratchPath.rewind()
            scratchPath.moveTo(x + size / 2f, y)
            scratchPath.lineTo(x + size, y + size / 2f)
            scratchPath.lineTo(x + size / 2f, y + size)
            scratchPath.lineTo(x, y + size / 2f)
            scratchPath.close()
            strokePaint.style = Paint.Style.STROKE
            strokePaint.color = CORNER_DIAMOND_COLOR
            // The overlay viewBox is the CSS viewport, not the sliced 1600x1000 SVG.
            strokePaint.strokeWidth = geometry.viewport.px(1f)
            canvas.drawPath(scratchPath, strokePaint)
            return
        }
    }

    /** Tests the prototype's gap-expanded receiver or dock obstacle in viewport pixels. */
    private fun diamondHitsObstacle(x: Float, y: Float, size: Float, gap: Float, box: RectF): Boolean =
        x < box.right + gap && x + size > box.left - gap &&
            y < box.bottom + gap && y + size > box.top - gap

    private fun drawMark(
        canvas: Canvas,
        matrix: Matrix,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float,
        fill: Boolean,
    ) {
        if (fill) {
            strokePaint.style = Paint.Style.FILL
            strokePaint.color = fillColor
            drawArmSet(canvas, matrix)
        }
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthPx
        drawArmSet(canvas, matrix)
    }

    private fun drawArmSet(canvas: Canvas, matrix: Matrix) {
        for (arm in ARM_VERTICES) {
            canvas.drawPath(armPath(arm, matrix), strokePaint)
        }
        canvas.drawPath(armPath(DIAMOND_VERTICES, matrix), strokePaint)
    }

    private fun armPath(arm: List<Pair<Double, Double>>, matrix: Matrix): Path {
        scratchPath.rewind()
        arm.forEachIndexed { index, point ->
            mappedPoint[0] = point.first.toFloat()
            mappedPoint[1] = point.second.toFloat()
            matrix.mapPoints(mappedPoint)
            if (index == 0) {
                scratchPath.moveTo(mappedPoint[0], mappedPoint[1])
            } else {
                scratchPath.lineTo(mappedPoint[0], mappedPoint[1])
            }
        }
        scratchPath.close()
        scratchPath.offset(legacyOffsetXPx, legacyOffsetYPx)
        return scratchPath
    }

    /** Size-derived scene state, rebuilt only when the view size changes. */
    private fun geometryFor(viewWidth: Float, viewHeight: Float): Geometry {
        val cached = cachedGeometry
        if (cached != null && cached.viewWidth == viewWidth && cached.viewHeight == viewHeight) {
            return cached
        }
        val slice = sliceTransformFor(viewWidth.toDouble(), viewHeight.toDouble())
        val viewport = StandbyViewportScale(width, height, resources.displayMetrics.density, cssPixels = true)
        val coreMatrix = sliceMatrixFor(X_CORE_X, X_CORE_Y, X_CORE_SIZE, slice)
        val polygons = (ARM_VERTICES + listOf(DIAMOND_VERTICES)).map { arm ->
            val mapped = arm.map { point -> mapMarkPoint(point, coreMatrix) }
            SectionPolygon(mapped, polygonCenter(mapped))
        }
        val scale = viewport.px(1f).toDouble()
        val rails = screenRailsFor(viewWidth / scale, viewHeight / scale)
            .filter { hypot(it.second.first - it.first.first, it.second.second - it.first.second) > 10.0 }
        val baseAngle = if (rails.isNotEmpty()) {
            atan2(rails[0].second.second - rails[0].first.second, rails[0].second.first - rails[0].first.first)
        } else {
            FALLBACK_RAIL_ANGLE
        }
        val railPath = Path().apply {
            moveTo(slice.mapX(0.0), slice.mapY(810.0))
            lineTo(slice.mapX(430.0), slice.mapY(380.0))
            moveTo(slice.mapX(0.0), slice.mapY(849.0))
            lineTo(slice.mapX(468.0), slice.mapY(381.0))
        }
        val verticalPath = Path().apply {
            moveTo(slice.mapX(58.0), slice.mapY(0.0))
            lineTo(slice.mapX(58.0), slice.mapY(1000.0))
            moveTo(slice.mapX(1542.0), slice.mapY(0.0))
            lineTo(slice.mapX(1542.0), slice.mapY(1000.0))
        }
        val geometry = Geometry(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            slice = slice,
            viewport = viewport,
            polygons = polygons,
            baseAngle = baseAngle,
            railPath = railPath,
            verticalPath = verticalPath,
            coreMatrix = coreMatrix,
            echoMatrix = sliceMatrixFor(X_ECHO_X, X_ECHO_Y, X_CORE_SIZE, slice),
        )
        cachedGeometry = geometry
        return geometry
    }

    private fun drawSectionExtrusion(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        time: Double,
        geometry: Geometry,
    ) {
        val width = viewWidth.toDouble()
        val height = viewHeight.toDouble()
        val polygons = geometry.polygons
        val unit = min(width, height)
        val angle = extrusionAngle(geometry.baseAngle, time)
        val directionX = cos(angle)
        val directionY = sin(angle)
        val depthMax = extrusionDepthMax(unit, time)

        val headerLower = headerLowerPx?.toDouble() ?: 0.0
        val fieldTop = headerLower
        val fieldBottom = height
        val upper = bandUpperPx?.toDouble()
        val lower = bandLowerPx?.toDouble()
        val bandCount = resolveFieldBands(fieldTop, height, upper, lower)
        for (index in 0 until bandCount) {
            canvas.save()
            canvas.clipRect(0f, bandEdges[index * 2], viewWidth, bandEdges[index * 2 + 1])
            try {
                drawUnderlay(canvas, width, height, time, geometry.viewport)
            } finally {
                canvas.restore()
            }
        }
        // Study canvas clip: rect(0, field.top.y, w, h - top.y), prototype drawDMotion.
        canvas.save()
        canvas.clipRect(0f, fieldTop.toFloat(), viewWidth, fieldBottom.toFloat())
        try {
            // Longitudinal corner edges anchored to real X arm vertices.
            strokePaint.style = Paint.Style.STROKE
            strokePaint.color = EDGE_COLOR
            strokePaint.strokeWidth = geometry.viewport.px(EDGE_WIDTH)
            edgePath.rewind()
            for (polygon in polygons) {
                val center = polygon.center
                for (vertex in polygon.vertices) {
                    edgePath.moveTo(vertex.first.toFloat(), vertex.second.toFloat())
                    edgePath.lineTo(
                        (center.first + (vertex.first - center.first) * TAPER_END + directionX * depthMax).toFloat(),
                        (center.second + (vertex.second - center.second) * TAPER_END + directionY * depthMax).toFloat(),
                    )
                }
            }
            edgePath.offset(legacyOffsetXPx, legacyOffsetYPx)
            canvas.drawPath(edgePath, strokePaint)
            // Registered sectional frames along the extrusion axis.
            for (k in 0 until SECTION_FRAME_COUNT) {
                val frame = sectionFrameInto(scratchSectionFrame, k, time, depthMax)
                if (frame.opacity < 0.01) {
                    continue
                }
                strokePaint.color = Color.argb(
                    (frame.opacity.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255),
                    240, 240, 240,
                )
                strokePaint.strokeWidth = geometry.viewport.px(SECTION_WIDTH)
                for (polygon in polygons) {
                    val center = polygon.center
                    sectionPath.rewind()
                    polygon.vertices.forEachIndexed { index, vertex ->
                        val px = center.first + (vertex.first - center.first) * frame.taper + directionX * frame.depth
                        val py = center.second + (vertex.second - center.second) * frame.taper + directionY * frame.depth
                        if (index == 0) {
                            sectionPath.moveTo(px.toFloat(), py.toFloat())
                        } else {
                            sectionPath.lineTo(px.toFloat(), py.toFloat())
                        }
                    }
                    sectionPath.close()
                    sectionPath.offset(legacyOffsetXPx, legacyOffsetYPx)
                    canvas.drawPath(sectionPath, strokePaint)
                }
            }
        } finally {
            canvas.restore()
        }

        // Outer guides stay inside the two field bands when known.
        for (index in 0 until bandCount) {
            canvas.save()
            canvas.clipRect(0f, bandEdges[index * 2], viewWidth, bandEdges[index * 2 + 1])
            try {
                drawOuterGuides(canvas, polygons, unit, directionX, directionY, depthMax, geometry.viewport)
            } finally {
                canvas.restore()
            }
        }
    }

    /**
     * Resolves the up-to-two 118 field clip bands into [bandEdges].
     *
     * @param fieldTop top of the study field in px.
     * @param height view height in px.
     * @param upper bottom of the center information band upper edge, or null when unknown.
     * @param lower top of the center information band lower edge, or null when unknown.
     * @return number of resolved `[top, bottom]` pairs written to [bandEdges].
     */
    private fun resolveFieldBands(fieldTop: Double, height: Double, upper: Double?, lower: Double?): Int {
        if (upper == null || lower == null) return 0
        var count = 0
        val upperEdge = upper.coerceIn(0.0, height)
        if (upperEdge - fieldTop > 0.0) {
            bandEdges[0] = fieldTop.toFloat()
            bandEdges[1] = upperEdge.toFloat()
            count = 1
        }
        val lowerEdge = lower.coerceIn(0.0, height)
        if (height - lowerEdge > 0.0) {
            bandEdges[count * 2] = lowerEdge.toFloat()
            bandEdges[count * 2 + 1] = height.toFloat()
            count++
        }
        return count
    }

    private fun drawUnderlay(
        canvas: Canvas,
        width: Double,
        height: Double,
        time: Double,
        viewport: StandbyViewportScale,
    ) {
        val scale = viewport.px(1f).toDouble()
        val pitch = underlayPitch(width / scale, height / scale) * scale
        val phase = underlayPhase(time, pitch)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = UNDERLAY_COLOR
        strokePaint.strokeWidth = (UNDERLAY_WIDTH_PX * scale).toFloat()
        underlayPath.rewind()
        var x = -height
        while (x < width + height) {
            underlayPath.moveTo((x + phase).toFloat(), 0f)
            underlayPath.lineTo((x + phase + height * 0.6).toFloat(), height.toFloat())
            underlayPath.moveTo((x - phase).toFloat(), 0f)
            underlayPath.lineTo((x - phase - height * 0.6).toFloat(), height.toFloat())
            x += pitch
        }
        canvas.drawPath(underlayPath, strokePaint)
    }

    private fun drawOuterGuides(
        canvas: Canvas,
        polygons: List<SectionPolygon>,
        unit: Double,
        directionX: Double,
        directionY: Double,
        depthMax: Double,
        viewport: StandbyViewportScale,
    ) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = GUIDE_COLOR
        strokePaint.strokeWidth = viewport.px(GUIDE_WIDTH)
        guidesPath.rewind()
        for (polygon in polygons) {
            val center = polygon.center
            for (vertex in polygon.vertices) {
                if (hypot(vertex.first - center.first, vertex.second - center.second) < unit * 0.08) {
                    continue
                }
                val startX = center.first + (vertex.first - center.first) * TAPER_END + directionX * depthMax
                val startY = center.second + (vertex.second - center.second) * TAPER_END + directionY * depthMax
                val endX = center.first + (vertex.first - center.first) * GUIDE_TAPER + directionX * depthMax * GUIDE_EXTEND
                val endY = center.second + (vertex.second - center.second) * GUIDE_TAPER + directionY * depthMax * GUIDE_EXTEND
                guidesPath.moveTo(startX.toFloat(), startY.toFloat())
                guidesPath.lineTo(endX.toFloat(), endY.toFloat())
            }
        }
        guidesPath.offset(legacyOffsetXPx, legacyOffsetYPx)
        canvas.drawPath(guidesPath, strokePaint)
    }

    companion object {
        /** Stage viewBox from `#hardline-scene > svg`, mapped with `xMidYMid slice`. */
        const val VIEWBOX_WIDTH = 1600.0
        const val VIEWBOX_HEIGHT = 1000.0

        /** `mirax-mark` symbol viewBox edge. */
        const val MARK_SIZE = 108.0

        /** X core placement from `.x-core`: x/y/width in viewBox units. */
        const val X_CORE_X = 510.0
        const val X_CORE_Y = -335.0
        const val X_CORE_SIZE = 1370.0

        /** X outline echo placement from `.x-core-line`. */
        const val X_ECHO_X = 526.0
        const val X_ECHO_Y = -323.0

        /** Prototype stroke widths in viewBox units, except where noted. */
        const val X_CORE_STROKE_WIDTH = 0.16
        const val X_ECHO_STROKE_WIDTH = 0.08
        const val STATIC_RAIL_WIDTH = 1.0
        const val HEADER_WIDTH_PX = 2f

        /** 118 constants, straight from `118-section-extrusion.js`. */
        const val SECTION_FRAME_COUNT = 4
        const val TAPER_END = 0.85
        const val GUIDE_TAPER = 0.72
        const val GUIDE_EXTEND = 1.85
        const val EDGE_WIDTH = 0.8f
        const val SECTION_WIDTH = 0.85f
        const val GUIDE_WIDTH = 0.65f
        const val UNDERLAY_WIDTH_PX = 0.75f
        const val FALLBACK_RAIL_ANGLE = -0.785

        /** Original `mirax-mark` arm vertices, in symbol units. */
        val ARM_VERTICES: List<List<Pair<Double, Double>>> = listOf(
            listOf(Pair(26.1, 23.0), Pair(31.9, 23.0), Pair(51.8, 42.9), Pair(43.9, 50.8), Pair(26.1, 32.8)),
            listOf(Pair(76.0, 23.1), Pair(81.9, 23.1), Pair(81.9, 32.8), Pair(64.2, 50.5), Pair(56.3, 43.0)),
            listOf(Pair(26.1, 73.6), Pair(44.0, 55.7), Pair(51.7, 63.5), Pair(32.0, 83.3), Pair(26.1, 83.3)),
            listOf(Pair(64.0, 55.7), Pair(82.0, 73.6), Pair(82.0, 83.4), Pair(76.1, 83.4), Pair(56.2, 63.4)),
        )

        /** Center diamond vertices from the `mirax-mark` symbol, in symbol units. */
        val DIAMOND_VERTICES: List<Pair<Double, Double>> = listOf(
            Pair(54.0, 47.5), Pair(60.2, 53.4), Pair(54.0, 59.4), Pair(47.8, 53.4),
        )

        const val BACKGROUND_COLOR: Int = -0xF6F6F7
        val RAIL_COLOR: Int = Color.rgb(119, 119, 113)
        val VERTICAL_COLOR: Int = Color.argb(0x21, 255, 255, 255)
        val HEADER_COLOR: Int = Color.rgb(255, 255, 255)
        val ARM_FILL_COLOR: Int = Color.rgb(9, 9, 9)
        val ARM_STROKE_COLOR: Int = Color.rgb(216, 216, 208)
        val ECHO_STROKE_COLOR: Int = Color.argb(0x3d, 255, 255, 255)
        val UNDERLAY_COLOR: Int = Color.argb(14, 240, 240, 240)
        val EDGE_COLOR: Int = Color.argb(56, 240, 240, 240)
        val GUIDE_COLOR: Int = Color.argb(23, 240, 240, 240)
        private const val CORNER_DIAMOND_COLOR: Int = 0xFFE8E8E0.toInt()
        private const val GENERATED_SCRIM_COLOR: Int = 0x18000000

        const val MAX_FRAME_DELTA_MILLIS = 100.0

        /**
         * `xMidYMid slice` mapping from the `1600x1000` stage to view pixels.
         *
         * @param viewWidth view width in px.
         * @param viewHeight view height in px.
         */
        fun sliceTransformFor(viewWidth: Double, viewHeight: Double): SliceTransform {
            val scale = max(viewWidth / VIEWBOX_WIDTH, viewHeight / VIEWBOX_HEIGHT)
            val offsetX = (viewWidth - VIEWBOX_WIDTH * scale) / 2.0
            val offsetY = (viewHeight - VIEWBOX_HEIGHT * scale) / 2.0
            return SliceTransform(scale, offsetX, offsetY)
        }

        /**
         * Screen-space paired rails mirroring `layoutDCurrents()`: `matrix.a` is
         * the slice scale, `startY` falls back to the prototype `minimumY`
         * branch (`0.84` portrait / `0.78` landscape of height) because the DOM
         * original-start mapping has no host equivalent.
         *
         * @param viewWidth view width in px.
         * @param viewHeight view height in px.
         */
        fun screenRailsFor(viewWidth: Double, viewHeight: Double): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
            val scale = max(viewWidth / VIEWBOX_WIDTH, viewHeight / VIEWBOX_HEIGHT)
            val minimumY = viewHeight * (if (viewWidth < viewHeight) 0.84 else 0.78)
            val startY = min(viewHeight * 0.9, minimumY)
            val gap = min(32.0, max(16.0, 39.0 * scale))
            val reach = min(430.0 * scale, min(viewWidth * 0.4, viewHeight * 0.65))
            return listOf(0, 1).map { index ->
                val y = startY + index * gap
                val x = reach + index * gap
                Pair(Pair(-32.0, y + 32.0), Pair(x, y - x))
            }
        }

        /**
         * 118 underlay pitch: `clamp(36, 72, min(w, h) * 0.1)`.
         *
         * @param viewWidth viewport width in px.
         * @param viewHeight viewport height in px.
         */
        fun underlayPitch(viewWidth: Double, viewHeight: Double): Double {
            return min(72.0, max(36.0, min(viewWidth, viewHeight) * 0.1))
        }

        /**
         * 118 underlay phase: `sin(time * 0.17) * pitch * 0.12`.
         *
         * @param time monotonic seconds.
         * @param pitch value from [underlayPitch].
         */
        fun underlayPhase(time: Double, pitch: Double): Double {
            return sin(time * 0.17) * pitch * 0.12
        }

        /**
         * 118 extrusion direction: rail angle plus `0.04 * sin(time * 0.22)`.
         *
         * @param baseAngle rail angle in radians, or [FALLBACK_RAIL_ANGLE].
         * @param time monotonic seconds.
         */
        fun extrusionAngle(baseAngle: Double, time: Double): Double {
            return baseAngle + 0.04 * sin(time * 0.22)
        }

        /**
         * 118 maximum depth: `unit * (0.13 + 0.02 * sin(time * 0.26))`.
         *
         * @param unit `min(width, height)` in px.
         * @param time monotonic seconds.
         */
        fun extrusionDepthMax(unit: Double, time: Double): Double {
            return unit * (0.13 + 0.02 * sin(time * 0.26))
        }

        /**
         * One registered section frame: `progress = (k / 4 + time * 0.075) % 1`,
         * `taper = 1 - 0.15 * progress`, `opacity = sin(progress * PI) * 0.38`.
         *
         * @param index frame index in `[0, 4)`.
         * @param time monotonic seconds.
         * @param depthMax value from [extrusionDepthMax].
         */
        fun sectionFrame(index: Int, time: Double, depthMax: Double): SectionFrame =
            sectionFrameInto(SectionFrame(0.0, 0.0, 0.0, 0.0), index, time, depthMax)

        /**
         * Fills [out] with one registered section frame and returns it unchanged
         * identity, so callers can reuse a single instance across frames.
         *
         * @param out frame instance to overwrite.
         * @param index frame index in `[0, 4)`.
         * @param time monotonic seconds.
         * @param depthMax value from [extrusionDepthMax].
         */
        fun sectionFrameInto(out: SectionFrame, index: Int, time: Double, depthMax: Double): SectionFrame {
            val progress = (index.toDouble() / SECTION_FRAME_COUNT + time * 0.075) % 1.0
            out.progress = progress
            out.depth = progress * depthMax
            out.taper = 1.0 - 0.15 * progress
            out.opacity = sin(progress * PI) * 0.38
            return out
        }

        /**
         * Polygon centroid, mirroring the prototype `polygonCenter()`.
         *
         * @param polygon vertices in px.
         */
        fun polygonCenter(polygon: List<Pair<Double, Double>>): Pair<Double, Double> {
            var sumX = 0.0
            var sumY = 0.0
            for (vertex in polygon) {
                sumX += vertex.first / polygon.size
                sumY += vertex.second / polygon.size
            }
            return Pair(sumX, sumY)
        }
    }

    /**
     * `xMidYMid slice` mapping from stage viewBox units to view pixels.
     *
     * @param scale uniform stage scale.
     * @param offsetX horizontal stage offset in px.
     * @param offsetY vertical stage offset in px.
     */
    data class SliceTransform(
        val scale: Double,
        val offsetX: Double,
        val offsetY: Double,
    ) {
        /** Map a stage X coordinate to view pixels. */
        fun mapX(x: Double): Float = (offsetX + x * scale).toFloat()

        /** Map a stage Y coordinate to view pixels. */
        fun mapY(y: Double): Float = (offsetY + y * scale).toFloat()
    }

    private fun sliceMatrixFor(coreX: Double, coreY: Double, coreSize: Double, slice: SliceTransform): Matrix {
        val markScale = coreSize / MARK_SIZE
        val combined = (markScale * slice.scale).toFloat()
        return Matrix().apply {
            setScale(combined, combined)
            postTranslate(
                (slice.offsetX + coreX * slice.scale).toFloat(),
                (slice.offsetY + coreY * slice.scale).toFloat(),
            )
        }
    }

    private fun mapMarkPoint(point: Pair<Double, Double>, matrix: Matrix): Pair<Double, Double> {
        val mapped = floatArrayOf(point.first.toFloat(), point.second.toFloat())
        matrix.mapPoints(mapped)
        return Pair(mapped[0].toDouble(), mapped[1].toDouble())
    }
}
