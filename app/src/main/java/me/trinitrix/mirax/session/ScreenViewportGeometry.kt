package me.trinitrix.mirax.session

/**
 * Deep domain model integrating picture placement geometry and Windows touch mapping.
 *
 * Implements the ubiquitous language from CONTEXT.md:
 * - **畫面 (Picture)**: The rectangle visible to the user, with its top-left as (0, 0).
 * - **一般觸控 (General touch)**: Touch contacts on the active picture belonging to Windows.
 * - **接觸結束 (Contact ended)**: Sliding out of picture or into control areas ends the contact.
 */
class ScreenViewportGeometry(
    val panelWidth: Int,
    val panelHeight: Int,
    val pictureWidth: Int,
    val pictureHeight: Int,
    val scale: PictureScale = PictureScale.PROPORTIONAL,
) {
    val placement: PicturePlacement.Rect = PicturePlacement.place(
        pictureWidth = pictureWidth,
        pictureHeight = pictureHeight,
        panelWidth = panelWidth,
        panelHeight = panelHeight,
        scale = scale,
    )

    private val activePointerIds = mutableSetOf<Int>()

    data class ContactResult(
        val x: Int,
        val y: Int,
        val insidePicture: Boolean,
    )

    /**
     * Maps a raw panel contact (x, y) to negotiated picture pixel coordinates.
     * Returns null if outside the visible picture viewport.
     */
    fun mapPanelContact(panelX: Float, panelY: Float): ContactResult? {
        if (placement.width <= 0 || placement.height <= 0) return null
        val localX = panelX - placement.left
        val localY = panelY - placement.top
        val px = PictureTouchMap.pixel(localX, placement.width, pictureWidth) ?: return null
        val py = PictureTouchMap.pixel(localY, placement.height, pictureHeight) ?: return null
        return ContactResult(x = px, y = py, insidePicture = true)
    }

    /**
     * Clamped mapping for tracking active drags even when moving past the boundary.
     */
    fun mapClampedContact(panelX: Float, panelY: Float): ContactResult? {
        if (placement.width <= 0 || placement.height <= 0) return null
        val localX = panelX - placement.left
        val localY = panelY - placement.top
        val px = PictureTouchMap.clampedPixel(localX, placement.width, pictureWidth) ?: return null
        val py = PictureTouchMap.clampedPixel(localY, placement.height, pictureHeight) ?: return null
        val isInside = panelX >= placement.left && panelX <= (placement.left + placement.width) &&
            panelY >= placement.top && panelY <= (placement.top + placement.height)
        return ContactResult(x = px, y = py, insidePicture = isInside)
    }

    fun onPointerDown(pointerId: Int) {
        activePointerIds.add(pointerId)
    }

    fun onPointerUp(pointerId: Int) {
        activePointerIds.remove(pointerId)
    }

    /**
     * Resets active contacts when picture scale or rotation changes,
     * triggering "接觸結束" (contacts ended) in accordance with CONTEXT.md.
     */
    fun endAllContacts(): Set<Int> {
        val ended = activePointerIds.toSet()
        activePointerIds.clear()
        return ended
    }
}
