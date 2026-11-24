package me.trinitrix.mirax.standbyprobe.media

/**
 * Probe-only center-priority aspect-fill frame.
 *
 * @param scale Uniform scale applied to content (no stretch).
 * @param scaledWidth Content width after scale.
 * @param scaledHeight Content height after scale.
 * @param offsetX Left edge of scaled content relative to viewport left.
 * @param offsetY Top edge of scaled content relative to viewport top.
 */
data class StandbyMediaFrame(
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
    val offsetX: Float,
    val offsetY: Float
)

/**
 * Transformation parameters for applying aspect-fill to a TextureView.
 *
 * @param scaleX Horizontal scale factor.
 * @param scaleY Vertical scale factor.
 * @param translateX Horizontal translation offset in px.
 * @param translateY Vertical translation offset in px.
 */
data class StandbyMediaTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

/**
 * Smallest public pure geometry model for the standby media probe.
 *
 * Center-priority aspect fill: uniform scale covers the viewport so there is
 * no stretch and no uncovered gap. Optional pan is clamped so cover is kept.
 * This is probe geometry only, not a production global framework.
 */
object StandbyMediaFrameGeometry {
    /**
     * Compute aspect-fill placement for content inside a viewport.
     *
     * @param viewportWidth Viewport width in px, must be finite and positive.
     * @param viewportHeight Viewport height in px, must be finite and positive.
     * @param contentWidth Content width in px, must be finite and positive.
     * @param contentHeight Content height in px, must be finite and positive.
     * @param panX Desired horizontal pan in px, clamped to keep cover.
     * @param panY Desired vertical pan in px, clamped to keep cover.
     * @param zoom Extra uniform zoom multiplier, clamped to at least 1 to keep cover;
     * must be finite and positive.
     * @return Frame with uniform scale and centered offsets.
     */
    fun layout(
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        zoom: Float = 1f
    ): StandbyMediaFrame {
        requireFinitePositive(viewportWidth, "viewportWidth")
        requireFinitePositive(viewportHeight, "viewportHeight")
        requireFinitePositive(contentWidth, "contentWidth")
        requireFinitePositive(contentHeight, "contentHeight")
        requireFinitePositive(zoom, "zoom")
        requireFinite(panX, "panX")
        requireFinite(panY, "panY")
        val effectiveZoom = maxOf(1f, zoom)
        val baseScale = maxOf(viewportWidth / contentWidth, viewportHeight / contentHeight)
        val scale = baseScale * effectiveZoom
        val scaledWidth = contentWidth * scale
        val scaledHeight = contentHeight * scale
        require(scale.isFinite()) { "scale must be finite" }
        require(scaledWidth.isFinite()) { "scaledWidth must be finite" }
        require(scaledHeight.isFinite()) { "scaledHeight must be finite" }
        val maxPanX = maxOf(0f, (scaledWidth - viewportWidth) / 2f)
        val maxPanY = maxOf(0f, (scaledHeight - viewportHeight) / 2f)
        val clampedX = panX.coerceIn(-maxPanX, maxPanX)
        val clampedY = panY.coerceIn(-maxPanY, maxPanY)
        val offsetX = (viewportWidth - scaledWidth) / 2f + clampedX
        val offsetY = (viewportHeight - scaledHeight) / 2f + clampedY
        require(offsetX.isFinite()) { "offsetX must be finite" }
        require(offsetY.isFinite()) { "offsetY must be finite" }
        return StandbyMediaFrame(
            scale = scale,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    /**
     * Compute scale and translation factors to apply to a TextureView Matrix.
     *
     * @param viewportWidth Viewport width in px, must be finite and positive.
     * @param viewportHeight Viewport height in px, must be finite and positive.
     * @param contentWidth Content width in px, must be finite and positive.
     * @param contentHeight Content height in px, must be finite and positive.
     * @param panX Desired horizontal pan in px, clamped to keep cover.
     * @param panY Desired vertical pan in px, clamped to keep cover.
     * @param zoom Extra uniform zoom multiplier, must be finite and positive.
     * @return Transform factors with uniform aspect fill.
     */
    fun computeTransform(
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        zoom: Float = 1f
    ): StandbyMediaTransform {
        val frame = layout(viewportWidth, viewportHeight, contentWidth, contentHeight, panX, panY, zoom)
        return StandbyMediaTransform(
            scaleX = frame.scaledWidth / viewportWidth,
            scaleY = frame.scaledHeight / viewportHeight,
            translateX = frame.offsetX,
            translateY = frame.offsetY
        )
    }

    private fun requireFinitePositive(value: Float, name: String) {
        require(value.isFinite()) { "$name must be finite" }
        require(value > 0f) { "$name must be positive" }
    }

    private fun requireFinite(value: Float, name: String) {
        require(value.isFinite()) { "$name must be finite" }
    }
}
