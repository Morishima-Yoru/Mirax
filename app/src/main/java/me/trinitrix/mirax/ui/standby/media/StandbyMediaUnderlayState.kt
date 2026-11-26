package me.trinitrix.mirax.ui.standby.media

import me.trinitrix.mirax.data.standby.StandbyFraming

/**
 * Pure underlay state behind [StandbyMediaUnderlayView].
 *
 * Holds the shared framing plus current content and view sizes, and answers
 * whether anything should be drawn. Empty or corrupt content draws nothing so
 * the theme layer above takes over; no placeholder is ever invented here.
 * Fully JVM testable with no Android types.
 *
 * @param framing current shared framing.
 */
class StandbyMediaUnderlayState(
    framing: StandbyFraming = StandbyFraming.CENTERED,
) {
    /** Current shared framing. */
    var framing: StandbyFraming = framing
        private set

    private var contentW: Float? = null
    private var contentH: Float? = null
    private var usable: Boolean = false
    private var viewW: Float = 0f
    private var viewH: Float = 0f

    /**
     * Records decoded content dimensions and usability.
     *
     * @param width content width in pixels, or `null` when unknown.
     * @param height content height in pixels, or `null` when unknown.
     * @param usable true only when bytes genuinely decoded.
     */
    fun setContent(width: Float?, height: Float?, usable: Boolean) {
        if (width != null && height != null && usable &&
            width.isFinite() && height.isFinite() && width > 0f && height > 0f
        ) {
            contentW = width
            contentH = height
            this.usable = true
        } else {
            contentW = null
            contentH = null
            this.usable = false
        }
    }

    /** Clears content; afterwards nothing is drawn. */
    fun clearContent() {
        contentW = null
        contentH = null
        usable = false
    }

    /**
     * Records the current view size.
     *
     * The same framing is kept; callers recompute the source window instead
     * of reopening any decoder.
     *
     * @param width view width in pixels.
     * @param height view height in pixels.
     */
    fun setViewSize(width: Float, height: Float) {
        viewW = width
        viewH = height
    }

    /**
     * Replaces the shared framing.
     *
     * @param framing new shared framing.
     */
    fun updateFraming(framing: StandbyFraming) {
        this.framing = framing
    }

    /**
     * True only when a genuine source window exists for the current sizes.
     *
     * @return true when the view should draw media.
     */
    fun shouldDraw(): Boolean {
        return computeSrc() != null
    }

    /**
     * Computes the clamped aspect-fill source window, or `null` when empty,
     * corrupt, or unsized so the caller draws nothing.
     *
     * @return source window, or `null` to draw nothing.
     */
    fun computeSrc(): StandbyFrameGeometry.SrcWindow? {
        val contentW = contentW
        val contentH = contentH
        if (!usable || contentW == null || contentH == null) {
            return null
        }
        return StandbyFrameGeometry.computeSrc(contentW, contentH, viewW, viewH, framing)
    }
}
