package me.trinitrix.mirax.ui.standby.foreground

/** Returns the width available beside Matter's centered action. */
internal fun matterFooterSideWidth(width: Int, edge: Int, corner: Int, actionHalf: Int, gap: Int): Int =
    (width / 2 - edge - corner - actionHalf - gap).coerceAtLeast(0)
