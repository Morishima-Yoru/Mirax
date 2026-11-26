package me.trinitrix.mirax.ui.standby.foreground

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Non-interactive broadcast indicator shared by the A and D status rows. */
internal class StandbyStatusDot(context: Context) : View(context) {
    private val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(StandbyForegroundKit.dpToPx(context, 1f).toInt().coerceAtLeast(1), 0xFFE0E4DF.toInt())
    }

    init {
        tag = "foreground_status_dot"
        background = dot
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setAdvertising(false)
    }

    /** Updates the outline in native pixels without replacing the indicator. */
    fun setOutlineWidth(width: Int) {
        dot.setStroke(width.coerceAtLeast(1), 0xFFE0E4DF.toInt())
    }

    /** Updates the fill without replacing the indicator view. */
    fun setAdvertising(enabled: Boolean) {
        dot.setColor(if (enabled) 0xFFE0E4DF.toInt() else Color.TRANSPARENT)
    }
}
