package me.trinitrix.mirax.ui.standby.foreground

import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM checks for the prototype Matter plate status codes. */
class MatterStatusWordTest {
    @Test
    fun statusWord_followsPrototypePhaseTable() {
        assertEquals("DISCONN", matterStatusWord(ScreenPhase.READY, null))
        assertEquals("ARMING", matterStatusWord(ScreenPhase.ARMING, null))
        assertEquals("STANDBY", matterStatusWord(ScreenPhase.ADVERTISING, null))
        assertEquals("ESTABLISHING", matterStatusWord(ScreenPhase.CONNECTING, null))
        assertEquals("CONNECTED", matterStatusWord(ScreenPhase.CONNECTED, null))
        assertEquals("WAIT_WIFI", matterStatusWord(ScreenPhase.WIFI_PAUSED, null))
        assertEquals("UNAVAILABLE", matterStatusWord(ScreenPhase.FROZEN, null))
    }

    @Test
    fun statusWord_failureEpisodeOverridesPhase() {
        for (phase in ScreenPhase.values()) {
            for (cause in PrePlayFailureCause.values()) {
                assertEquals("ERROR", matterStatusWord(phase, cause))
            }
        }
    }
}
