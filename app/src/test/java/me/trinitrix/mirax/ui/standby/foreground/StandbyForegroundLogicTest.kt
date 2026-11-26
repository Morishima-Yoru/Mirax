package me.trinitrix.mirax.ui.standby.foreground

import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure source-vector checks; these do not verify Android text metrics. */
class StandbyForegroundLogicTest {
    @Test
    fun nominalTextSizes_followFinalPrototypeOverrides() {
        assertEquals(40f, StandbyForegroundKit.TITLE_TEXT_SP, 0f)
        assertEquals(16f, StandbyForegroundKit.RECEIVER_TEXT_SP, 0f)
        assertEquals(20f, StandbyForegroundKit.MATTER_STATUS_TEXT_SP, 0f)
        assertEquals(11f, StandbyForegroundKit.MATTER_CLOCK_TEXT_SP, 0f)
    }

    @Test
    fun matterFooter_reservesActionAndNeverReturnsNegativeWidth() {
        assertEquals(76, matterFooterSideWidth(360, 24, 16, 48, 16))
        assertEquals(345, matterFooterSideWidth(920, 24, 27, 48, 16))
        assertEquals(0, matterFooterSideWidth(100, 24, 16, 48, 16))
    }

    @Test
    fun matterStatusColors_useRealPhaseAndExplicitFailure() {
        assertEquals(0xFF7EF59B.toInt(), StandbyForegroundKit.matterLightColor(ScreenPhase.ADVERTISING, null))
        assertEquals(0xFFFFFFFF.toInt(), StandbyForegroundKit.matterLightColor(ScreenPhase.CONNECTING, null))
        assertEquals(0xFFFF5959.toInt(), StandbyForegroundKit.matterLightColor(ScreenPhase.FROZEN, null))
        assertEquals(
            0xFFFF5959.toInt(),
            StandbyForegroundKit.matterLightColor(ScreenPhase.READY, PrePlayFailureCause.PRE_PLAY_TIMEOUT),
        )
        assertEquals(0xFF999999.toInt(), StandbyForegroundKit.matterLightColor(ScreenPhase.READY, null))
    }
}
