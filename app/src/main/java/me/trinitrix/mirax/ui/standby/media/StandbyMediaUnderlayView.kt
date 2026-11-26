package me.trinitrix.mirax.ui.standby.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import me.trinitrix.mirax.data.standby.StandbyFraming

/**
 * One framework-created output lifetime, with no view or holder ownership.
 *
 * A new receipt is required even when the framework reuses its Java Surface.
 * Retaining this receipt does not keep a destroyed native surface alive.
 *
 * @param surface borrowed Surface; only its SurfaceHolder may release it.
 */
internal class StandbyVideoOutput(val surface: Surface) {
    var alive: Boolean = true
        private set

    /** Invalidates this lifetime before destruction is dispatched to its player. */
    fun retire() {
        alive = false
    }
}

/**
 * Underlay view rendering standby custom media beneath the theme layer.
 *
 * Delegates all framing math to [StandbyMediaUnderlayState] via
 * [StandbyMediaUnderlayCoordinator]. Draws clamped aspect fill only when
 * genuine decoded content is attached; empty or corrupt content draws
 * nothing so the theme layer takes over. The caller owns the bitmap or drawable
 * and must stop an animated drawable before removing it.
 *
 * Video uses a below-window [SurfaceView]; its holder owns the borrowed Surface.
 *
 * @param context Android context.
 * @param attrs optional XML attributes.
 */
class StandbyMediaUnderlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    StandbyUnderlayHost {

    private val coordinator = StandbyMediaUnderlayCoordinator(
        state = StandbyMediaUnderlayState(),
        host = this,
    )

    private var bitmap: android.graphics.Bitmap? = null
    private var bitmapUsable: Boolean = false
    internal var mediaDrawable: Drawable? = null
        private set
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private var mediaDriver: AndroidStandbyMediaDriver? = null
    private var videoWidth = 0
    private var videoHeight = 0
    internal var currentVideoOutput: StandbyVideoOutput? = null
        private set
    private var renderingOutput: StandbyVideoOutput? = null
    private var videoView = createVideoView()

    private fun createVideoView(): SurfaceView = SurfaceView(context).apply {
        val owner = this
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
        alpha = 0f
        holder.addCallback(object : SurfaceHolder.Callback {
            private var output: StandbyVideoOutput? = null

            override fun surfaceCreated(holder: SurfaceHolder) {
                if (videoView !== owner) return
                check(output == null) { "video holder created before its previous lifetime ended" }
                val created = StandbyVideoOutput(holder.surface)
                output = created
                currentVideoOutput = created
                val driver = mediaDriver
                val binding = driver?.underlayBindingGeneration
                driver?.onFrameworkVideoSurfaceAvailable(this@StandbyMediaUnderlayView, created)
                if (videoView === owner && currentVideoOutput === created &&
                    (driver == null && mediaDriver == null ||
                        driver != null && binding != null &&
                        driver.isCurrentUnderlayBinding(this@StandbyMediaUnderlayView, binding))
                ) updateVideoPlacement()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val changed = output ?: return
                if (videoView !== owner || currentVideoOutput !== changed) return
                mediaDriver?.onFrameworkVideoSurfaceAvailable(this@StandbyMediaUnderlayView, changed)
                if (videoView === owner && currentVideoOutput === changed) updateVideoPlacement()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val destroyed = output ?: return
                output = null
                destroyed.retire()
                if (currentVideoOutput === destroyed) currentVideoOutput = null
                if (renderingOutput === destroyed) renderingOutput = null
                owner.alpha = 0f
                // The framework cannot retain this Surface for a failed player close.
                mediaDriver?.onFrameworkVideoSurfaceDestroyed(this@StandbyMediaUnderlayView, destroyed)
            }
        })
    }

    init {
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        setWillNotDraw(false)
        addView(videoView, videoLayoutParams())
    }

    private fun videoLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT)

    internal fun requireMediaDriverAvailable(driver: AndroidStandbyMediaDriver) {
        check(mediaDriver == null || mediaDriver === driver) { "underlay already has a media driver" }
    }

    internal fun isOwnedBy(driver: AndroidStandbyMediaDriver): Boolean =
        isStandbyViewOwner(driver, mediaDriver, driver.isBoundUnderlay(this))

    internal fun bindMediaDriver(driver: AndroidStandbyMediaDriver, drawable: Drawable?, binding: Long) {
        if (!driver.isBoundUnderlay(this) || driver.underlayBindingGeneration != binding) return
        requireMediaDriverAvailable(driver)
        mediaDriver = driver
        if (!driver.isCurrentUnderlayBinding(this, binding)) return
        drawable?.let { setMediaDrawable(it) }
        if (!driver.isCurrentUnderlayBinding(this, binding)) return
        driver.onUnderlayVisibilityChanged(this, isOutputVisible())
        if (!driver.isCurrentUnderlayBinding(this, binding)) return
        currentVideoOutput?.takeIf { ownsVideoOutput(it) }?.let { driver.onVideoSurfaceAvailable(this, it) }
    }

    internal fun unbindMediaDriver(driver: AndroidStandbyMediaDriver) {
        if (isOwnedBy(driver)) mediaDriver = null
    }

    internal fun setVideoEnabled(driver: AndroidStandbyMediaDriver, enabled: Boolean) {
        val binding = driver.underlayBindingGeneration
        if (!driver.isCurrentUnderlayBinding(this, binding)) return
        val currentView = videoView
        if (!enabled) {
            renderingOutput = null
            currentView.alpha = 0f
        }
        currentView.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!driver.isCurrentUnderlayBinding(this, binding) || videoView !== currentView) return
        if (!enabled) {
            setVideoContentSize(driver, 0, 0)
        } else {
            currentVideoOutput?.takeIf { ownsVideoOutput(it) }?.let { driver.onVideoSurfaceAvailable(this, it) }
        }
    }

    /**
     * @param output candidate framework output lifetime.
     * @return whether it is the current live holder output.
     */
    internal fun ownsVideoOutput(output: StandbyVideoOutput): Boolean =
        currentVideoOutput === output && output.alive && output.surface.isValid &&
            videoView.isAttachedToWindow && videoView.holder.surface === output.surface

    /** Replaces a previously used child only after its player has been released. */
    internal fun replaceVideoOutput(driver: AndroidStandbyMediaDriver) {
        val binding = driver.underlayBindingGeneration
        if (!driver.isCurrentUnderlayBinding(this, binding)) return
        check(driver.isVideoNativeClosed) { "video player must close before replacing its holder" }
        val previous = videoView
        val replacement = createVideoView()
        addView(replacement, 0, videoLayoutParams())
        if (!driver.isCurrentUnderlayBinding(this, binding) || videoView !== previous) {
            if (videoView !== replacement) removeView(replacement)
            return
        }
        val previousVisibility = previous.visibility
        videoView = replacement
        currentVideoOutput = null
        renderingOutput = null
        removeView(previous)
        if (!driver.isCurrentUnderlayBinding(this, binding) || videoView !== replacement) return
        replacement.visibility = previousVisibility
        requestLayout()
    }

    internal fun setVideoContentSize(driver: AndroidStandbyMediaDriver, width: Int, height: Int) {
        if (!isOwnedBy(driver)) return
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
        updateVideoPlacement()
    }

    /**
     * Reveals only an output with current-player rendering evidence.
     * @param driver current playback owner.
     * @param output output associated with that evidence.
     * @param rendering whether the current player has reported rendering.
     */
    internal fun setVideoRendering(driver: AndroidStandbyMediaDriver, output: StandbyVideoOutput, rendering: Boolean) {
        if (!isOwnedBy(driver) || currentVideoOutput !== output) return
        renderingOutput = if (rendering) output else null
        updateVideoPlacement()
    }

    /**
     * Geometry is eligibility, not evidence that the compositor presented a frame.
     * @param output framework output being checked.
     * @return whether the decoded-size child has a finite current placement.
     */
    internal fun isVideoOutputGeometryUsable(output: StandbyVideoOutput): Boolean {
        if (!ownsVideoOutput(output) || videoView.visibility != View.VISIBLE ||
            videoWidth <= 0 || videoHeight <= 0 || videoView.width != videoWidth || videoView.height != videoHeight
        ) return false
        val placement = videoPlacement() ?: return false
        return videoView.pivotX == 0f && videoView.pivotY == 0f &&
            videoView.scaleX == placement.scale && videoView.scaleY == placement.scale &&
            videoView.translationX == placement.translateX && videoView.translationY == placement.translateY
    }

    private fun videoPlacement(): StandbyFrameGeometry.SurfacePlacement? =
        StandbyFrameGeometry.computeSurfacePlacement(
            videoWidth.toFloat(), videoHeight.toFloat(), width.toFloat(), height.toFloat(), currentFraming(),
        )

    private fun updateVideoPlacement() {
        val target = videoView
        val output = currentVideoOutput
        val owner = mediaDriver
        val binding = owner?.underlayBindingGeneration
        val placement = videoPlacement()
        if (placement != null) {
            target.pivotX = 0f
            target.pivotY = 0f
            target.scaleX = placement.scale
            target.scaleY = placement.scale
            target.translationX = placement.translateX
            target.translationY = placement.translateY
        }
        val stillOwned = if (owner == null) mediaDriver == null else
            binding != null && owner.isCurrentUnderlayBinding(this, binding)
        if (videoView !== target || currentVideoOutput !== output || !stillOwned) return
        val usable = output != null && isVideoOutputGeometryUsable(output)
        target.alpha = if (usable && renderingOutput === output) 1f else 0f
        if (output != null && owner != null && binding != null && owner.isCurrentUnderlayBinding(this, binding)) {
            owner.onVideoOutputGeometryChanged(this, output)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Decoded dimensions must not inflate the caller's wrap-content viewport.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoView.visibility != View.GONE && videoWidth > 0 && videoHeight > 0) {
            videoView.measure(
                MeasureSpec.makeMeasureSpec(videoWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(videoHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateVideoPlacement()
    }

    private fun isOutputVisible(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mediaDriver?.onFrameworkUnderlayVisibilityChanged(this, isOutputVisible())
    }

    override fun onDetachedFromWindow() {
        mediaDriver?.onFrameworkUnderlayVisibilityChanged(this, false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        mediaDriver?.onFrameworkUnderlayVisibilityChanged(this, isVisible && isOutputVisible())
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        mediaDriver?.onFrameworkUnderlayVisibilityChanged(this, visibility == View.VISIBLE && isOutputVisible())
    }

    override fun requestRedraw() {
        postInvalidateOnAnimation()
    }

    override fun viewSize(): Pair<Float, Float>? {
        if (width <= 0 || height <= 0) {
            return null
        }
        return width.toFloat() to height.toFloat()
    }

    /**
     * Attaches a decoded still bitmap.
     *
     * Passing `null` clears content so nothing is drawn.
     *
     * @param bitmap decoded bitmap, or `null` to clear.
     */
    fun setBitmap(bitmap: android.graphics.Bitmap?) {
        detachMediaDrawable()
        val old = this.bitmap
        this.bitmap = bitmap
        bitmapUsable = bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
        if (bitmapUsable) {
            val current = bitmap
            if (current != null) {
                coordinator.onContentChanged(current.width.toFloat(), current.height.toFloat(), true)
            }
        } else {
            coordinator.onContentCleared()
        }
        if (old !== bitmap) {
            // Ownership stays with the caller; never recycle here.
        }
    }

    /**
     * Displays a decoded drawable using the same framing as a bitmap.
     *
     * The caller owns and controls the drawable. Its callback is attached to
     * this view so animated frames can invalidate and schedule redraws.
     *
     * @param drawable decoded content with positive intrinsic dimensions.
     */
    fun setMediaDrawable(drawable: Drawable) {
        require(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        require(drawable.callback == null || drawable.callback === this)
        detachMediaDrawable()
        bitmap = null
        bitmapUsable = false
        mediaDrawable = drawable
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.callback = this
        coordinator.onContentChanged(
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat(),
            true,
        )
    }

    /**
     * Removes only the specified drawable, preserving a later replacement.
     *
     * @param drawable drawable previously supplied by its owner.
     */
    fun clearMediaDrawable(drawable: Drawable) {
        if (mediaDrawable === drawable) {
            clearMedia()
        }
    }

    private fun detachMediaDrawable() {
        mediaDrawable?.let { drawable ->
            unscheduleDrawable(drawable)
            if (drawable.callback === this) {
                drawable.callback = null
            }
        }
        mediaDrawable = null
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return who === mediaDrawable || super.verifyDrawable(who)
    }

    /** Clears attached content so nothing is drawn. */
    fun clearMedia() {
        detachMediaDrawable()
        bitmap = null
        bitmapUsable = false
        coordinator.onContentCleared()
    }

    /**
     * Replaces the shared framing.
     *
     * @param framing new shared framing.
     */
    fun setFraming(framing: StandbyFraming) {
        coordinator.onFramingChanged(framing)
        updateVideoPlacement()
    }

    /**
     * Current shared framing.
     *
     * @return current framing.
     */
    fun currentFraming(): StandbyFraming {
        return coordinator.state.framing
    }

    /**
     * Returns decoded bitmap, animated image or video dimensions, not view dimensions.
     * The caller must associate them with the current playback owner and wait for
     * its prepared state before using them for a newly opened source.
     *
     * @return decoded dimensions, or null for unavailable or unsupported content.
     */
    fun decodedContentSize(): Size? {
        if (videoView.visibility == View.VISIBLE) {
            return if (videoWidth > 0 && videoHeight > 0) Size(videoWidth, videoHeight) else null
        }
        val drawable = mediaDrawable
        if (drawable is BitmapDrawable) {
            val image = drawable.bitmap
            return if (image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
                Size(image.width, image.height)
            } else {
                null
            }
        }
        if (drawable is AnimatedImageDrawable && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            return Size(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        val image = bitmap
        if (bitmapUsable && image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
            return Size(image.width, image.height)
        }
        return null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        coordinator.onSizeChanged(width.toFloat(), height.toFloat())
        clipBounds = Rect(0, 0, width, height)
        updateVideoPlacement()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = mediaDrawable
        val current = bitmap
        if (drawable != null) {
            val contentW = drawable.intrinsicWidth.toFloat()
            val contentH = drawable.intrinsicHeight.toFloat()
            if (contentW <= 0f || contentH <= 0f) {
                return
            }
            val content = object : StandbyMediaContent {
                override val usable: Boolean = true
                override val contentWidth: Float = contentW
                override val contentHeight: Float = contentH
                override fun drawClamped(
                    src: StandbyFrameGeometry.SrcWindow,
                    dstLeft: Float,
                    dstTop: Float,
                    dstRight: Float,
                    dstBottom: Float,
                    draw: (StandbyFrameGeometry.SrcWindow) -> Unit,
                ) {
                    draw(src)
                }
            }
            coordinator.renderIfReady(content) { src ->
                val scale = width.toFloat() / (src.right - src.left)
                val saveCount = canvas.save()
                try {
                    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    canvas.scale(scale, scale)
                    canvas.translate(-src.left, -src.top)
                    drawable.draw(canvas)
                } finally {
                    canvas.restoreToCount(saveCount)
                }
            }
            return
        }
        if (!bitmapUsable || current == null || current.isRecycled) {
            return
        }
        val content = object : StandbyMediaContent {
            override val usable: Boolean = bitmapUsable
            override val contentWidth: Float = current.width.toFloat()
            override val contentHeight: Float = current.height.toFloat()
            override fun drawClamped(
                src: StandbyFrameGeometry.SrcWindow,
                dstLeft: Float,
                dstTop: Float,
                dstRight: Float,
                dstBottom: Float,
                draw: (StandbyFrameGeometry.SrcWindow) -> Unit,
            ) {
                draw(src)
            }
        }
        coordinator.renderIfReady(content) { src ->
            val scale = width.toFloat() / (src.right - src.left)
            val saveCount = canvas.save()
            try {
                canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                canvas.scale(scale, scale)
                canvas.translate(-src.left, -src.top)
                canvas.drawBitmap(current, 0f, 0f, bitmapPaint)
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
    }
}
