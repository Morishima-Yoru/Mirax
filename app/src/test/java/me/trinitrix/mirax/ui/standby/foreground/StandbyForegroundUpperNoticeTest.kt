package me.trinitrix.mirax.ui.standby.foreground

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM checks for upper notice scroll height above the laid-out dock footprint. */
class StandbyForegroundUpperNoticeTest {
    @Test
    fun upperNoticeMaxHeight_reservesMinimumTouchDockAndNeverReturnsNegative() {
        assertEquals(104, upperNoticeMaxHeight(controlsHeight = 180, bottomMargin = 2, dockHeight = 72, gap = 2))
        assertEquals(176, upperNoticeMaxHeight(controlsHeight = 180, bottomMargin = 2, dockHeight = 0, gap = 2))
        assertEquals(0, upperNoticeMaxHeight(controlsHeight = 60, bottomMargin = 4, dockHeight = 72, gap = 2))
    }
}
