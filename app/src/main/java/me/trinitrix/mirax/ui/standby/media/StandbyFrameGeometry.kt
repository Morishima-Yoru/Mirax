package me.trinitrix.mirax.ui.standby.media

/**
 * Pure pixel math for clamped aspect fill with centre priority.
 *
 * Scale is `max(viewW / contentW, viewH / contentH) * max(framing.zoom, 1)`, so the
 * view is always fully covered with no stretch and no gaps. Sub-unity zoom
 * is treated as unity: showing more than the content would force per-axis
 * clamping (stretch). The visible
 * window is centred on the normalized framing centre, then clamped inside
 * the content bounds. The same framing recomputes correctly for any view
 * size (portrait, landscape, fold) without reopening a decoder.
 */
object StandbyFrameGeometry {

    /**
     * Visible source window in content pixels.
     *
     * @param left left edge in content pixels.
     * @param top top edge in content pixels.
     * @param right right edge in content pixels.
     * @param bottom bottom edge in content pixels.
     */
    data class SrcWindow(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /** Uniform placement of a decoded-size child, clipped by its viewport. */
    data class SurfacePlacement(
        val scale: Float,
        val translateX: Float,
        val translateY: Float,
    )

    /**
     * Places the full decoded frame using the same source window as images.
     *
     * @param contentW decoded width.
     * @param contentH decoded height.
     * @param viewW viewport width.
     * @param viewH viewport height.
     * @param framing shared framing.
     * @return uniform placement, or null when any resulting bound is unusable.
     */
    fun computeSurfacePlacement(
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
        framing: me.trinitrix.mirax.data.standby.StandbyFraming,
    ): SurfacePlacement? {
        val source = computeSrc(contentW, contentH, viewW, viewH, framing) ?: return null
        val scale = viewW / (source.right - source.left)
        val result = SurfacePlacement(scale, -source.left * scale, -source.top * scale)
        return result.takeIf {
            it.scale.isFinite() && it.scale > 0f &&
                it.translateX.isFinite() && it.translateY.isFinite() &&
                (it.translateX + contentW * scale).isFinite() &&
                (it.translateY + contentH * scale).isFinite()
        }
    }

    /**
     * Computes the source window for aspect fill.
     *
     * Returns `null` when any dimension is not finite positive, or when the
     * framing centre or zoom is not finite, so callers draw nothing instead
     * of fake content.
     *
     * @param contentW decoded content width in pixels.
     * @param contentH decoded content height in pixels.
     * @param viewW view width in pixels.
     * @param viewH view height in pixels.
     * @param framing shared framing configuration.
     * @return source window, or `null` when inputs are unusable.
     */
    fun computeSrc(
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
        framing: me.trinitrix.mirax.data.standby.StandbyFraming,
    ): SrcWindow? {
        if (!contentW.isFinite() || !contentH.isFinite() ||
            !viewW.isFinite() || !viewH.isFinite()
        ) {
            return null
        }
        if (contentW <= 0f || contentH <= 0f || viewW <= 0f || viewH <= 0f) {
            return null
        }
        if (!framing.zoom.isFinite() || framing.zoom <= 0f) {
            return null
        }
        if (!framing.centerX.isFinite() || !framing.centerY.isFinite()) {
            return null
        }
        val baseScale = maxOf(viewW / contentW, viewH / contentH)
        // Sub-unity zoom would show more than the content and force
        // independent axis clamping (stretch); treat it as unity fill.
        val scale = baseScale * framing.zoom.coerceAtLeast(1f)
        if (!scale.isFinite() || scale <= 0f) {
            return null
        }
        val visibleW = viewW / scale
        val visibleH = viewH / scale
        if (!visibleW.isFinite() || !visibleH.isFinite() || visibleW <= 0f || visibleH <= 0f) {
            return null
        }
        val centerX = framing.centerX * contentW
        val centerY = framing.centerY * contentH
        val halfW = visibleW / 2f
        val halfH = visibleH / 2f
        val left = (centerX - halfW).coerceIn(0f, (contentW - visibleW).coerceAtLeast(0f))
        val top = (centerY - halfH).coerceIn(0f, (contentH - visibleH).coerceAtLeast(0f))
        val clampedW = minOf(visibleW, contentW)
        val clampedH = minOf(visibleH, contentH)
        val clampedLeft = left.coerceIn(0f, (contentW - clampedW).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (contentH - clampedH).coerceAtLeast(0f))
        return SrcWindow(
            left = clampedLeft,
            top = clampedTop,
            right = clampedLeft + clampedW,
            bottom = clampedTop + clampedH,
        )
    }
}
