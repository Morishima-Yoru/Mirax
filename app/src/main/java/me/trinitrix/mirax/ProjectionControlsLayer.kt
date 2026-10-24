package me.trinitrix.mirax

import android.content.Context
import android.widget.FrameLayout

/**
 * Keeps a fixed compositor plane above the video while its phone controls move.
 *
 * @param context projection activity supplying the control theme
 */
internal class ProjectionControlsLayer(context: Context) : FrameLayout(context) {
    init {
        // Reserve the fixed viewport in SurfaceView's transparent region, not moving child bounds.
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
