package me.trinitrix.mirax.ui.standby.media

import me.trinitrix.mirax.data.standby.StandbyFraming

/**
 * Injectable drawing surface for [StandbyMediaUnderlayView].
 *
 * Implemented by the Android view; faked in JVM tests to verify framing
 * recompute and draw gating without Android APIs.
 */
interface StandbyUnderlayHost {
    /**
     * Invalidates the surface so the next frame recomputes with the same framing.
     */
    fun requestRedraw()

    /**
     * Current view size in pixels.
     *
     * @return pair of width and height, or `null` when unknown.
     */
    fun viewSize(): Pair<Float, Float>?
}

/**
 * Injectable decoded-content source behind [StandbyMediaUnderlayView].
 *
 * Real implementations wrap a decoded bitmap, animated drawable, or video
 * surface. JVM tests fake this to verify gating.
 */
interface StandbyMediaContent {
    /** True only when bytes genuinely decoded and drawable. */
    val usable: Boolean

    /** Decoded content width in pixels, or `null` when unknown. */
    val contentWidth: Float?

    /** Decoded content height in pixels, or `null` when unknown. */
    val contentHeight: Float?

    /**
     * Draws the computed source window onto the destination rect.
     *
     * No-op when content is unusable; never draws fake content.
     *
     * @param src source window in content pixels.
     * @param dstLeft destination left in view pixels.
     * @param dstTop destination top in view pixels.
     * @param dstRight destination right in view pixels.
     * @param dstBottom destination bottom in view pixels.
     * @param draw callback performing the actual platform draw.
     */
    fun drawClamped(
        src: StandbyFrameGeometry.SrcWindow,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
        draw: (StandbyFrameGeometry.SrcWindow) -> Unit,
    )
}

/**
 * Thin coordinator between the pure [StandbyMediaUnderlayState] and the
 * platform view.
 *
 * Holds no Android types and owns no decoder; the owning view forwards size
 * changes here so the same framing recomputes without reopening a decoder.
 * Empty or corrupt content renders nothing so the theme layer takes over.
 *
 * @param state pure underlay state.
 * @param host injectable redraw hook.
 */
class StandbyMediaUnderlayCoordinator(
    val state: StandbyMediaUnderlayState = StandbyMediaUnderlayState(),
    private val host: StandbyUnderlayHost? = null,
) {
    /**
     * Handles a view size change with the same framing.
     *
     * @param width new view width in pixels.
     * @param height new view height in pixels.
     */
    fun onSizeChanged(width: Float, height: Float) {
        state.setViewSize(width, height)
        host?.requestRedraw()
    }

    /**
     * Handles new decoded content.
     *
     * @param width content width in pixels, or `null` when unknown.
     * @param height content height in pixels, or `null` when unknown.
     * @param usable true only when bytes genuinely decoded.
     */
    fun onContentChanged(width: Float?, height: Float?, usable: Boolean) {
        state.setContent(width, height, usable)
        host?.requestRedraw()
    }

    /** Clears content so nothing is drawn. */
    fun onContentCleared() {
        state.clearContent()
        host?.requestRedraw()
    }

    /**
     * Replaces the shared framing.
     *
     * @param framing new shared framing.
     */
    fun onFramingChanged(framing: StandbyFraming) {
        state.updateFraming(framing)
        host?.requestRedraw()
    }

    /**
     * Draws when genuine content exists, else draws nothing.
     *
     * @param content injectable content source.
     * @param draw platform draw callback receiving the source window.
     * @return true when a draw was issued.
     */
    fun renderIfReady(
        content: StandbyMediaContent?,
        draw: (StandbyFrameGeometry.SrcWindow) -> Unit,
    ): Boolean {
        if (content == null) {
            return false
        }
        val src = state.computeSrc()
        val size = host?.viewSize()
        if (src == null || !content.usable || size == null) {
            return false
        }
        val (viewW, viewH) = size
        if (!viewW.isFinite() || !viewH.isFinite() || viewW <= 0f || viewH <= 0f) {
            return false
        }
        content.drawClamped(
            src = src,
            dstLeft = 0f,
            dstTop = 0f,
            dstRight = viewW,
            dstBottom = viewH,
            draw = draw,
        )
        return true
    }
}
