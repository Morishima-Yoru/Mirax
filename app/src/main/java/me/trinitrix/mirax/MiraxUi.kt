package me.trinitrix.mirax

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton

/** Shared Material 3 styling used by the dashboard and projection overlays. */
object MiraxUi {
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun weight(value: Int): Typeface = Typeface.create(Typeface.DEFAULT, value, false)

    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Int,
        stroke: Int = 0,
        strokeDp: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(context.getColor(fill))
            if (stroke != 0) {
                setStroke(dp(context, strokeDp).coerceAtLeast(1), context.getColor(stroke))
            }
        }
    }

    fun scrimColor(context: Context): Int =
        ColorUtils.setAlphaComponent(context.getColor(R.color.mirax_background), 0xB2)

    fun clipRipple(context: Context, view: View) {
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        val typed = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)) {
            view.foreground = context.getDrawable(typed.resourceId)
        }
    }

    fun stopActionButton(context: Context, label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context).apply {
            text = label
            textSize = 16f
            typeface = weight(700)
            isAllCaps = false
            cornerRadius = dp(context, 14)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(context, 56)
            minimumHeight = dp(context, 56)
            stateListAnimator = null
            elevation = 0f
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.mirax_stop))
            setTextColor(context.getColor(R.color.mirax_on_surface))
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 56),
            )
        }
    }
}
