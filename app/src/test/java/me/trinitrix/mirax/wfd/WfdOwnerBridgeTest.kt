package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import org.junit.Test

/**
 * Host-side wire helpers and a recording owner for the advertise command.
 * Does not exercise real Wi-Fi Direct APIs on the JVM.
 */
class WfdOwnerBridgeTest {

    @Test
    fun encodeModes_usesAsciiAndStableOrder() {
        val encoded = WfdOwnerBridge.encodeModes(
            setOf(
                VideoMode(1920, 1080, 60),
                VideoMode(1280, 720, 60),
                VideoMode(1920, 1080, 30),
            ),
        )
        assertThat(encoded).isEqualTo("1280x720@60,1920x1080@30,1920x1080@60")
        assertThat(encoded).doesNotContain("×")
    }

    @Test
    fun recordingOwner_receivesSessionAdvertiseCommand() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.report(DeviceNameReport("Desk Fold"))
        val owner = RecordingWfdOwner()
        owner.apply(session.snapshot().wfdAdvertise)
        assertThat(owner.last).isEqualTo(
            WfdAdvertiseCommand(
                owner = WfdOwner.HELPER,
                broadcastName = "Desk Fold",
                modes = session.snapshot().nextAdvertisementModes,
            ),
        )
        owner.apply(null)
        assertThat(owner.last).isNull()
    }

    @Test
    fun renameAdvertiseCommand_isDistinctSoOwnerSyncWouldReapply() {
        val a = WfdAdvertiseCommand(
            owner = WfdOwner.HELPER,
            broadcastName = "Old",
            modes = setOf(VideoMode(1920, 1080, 60)),
        )
        val b = a.copy(broadcastName = "New")
        assertThat(a).isNotEqualTo(b)
        assertThat(b.broadcastName).isEqualTo("New")
    }

    /**
     * Stand-in for the privileged owner process: records the command the
     * session emits without touching WifiP2pManager.
     */
    private class RecordingWfdOwner {
        var last: WfdAdvertiseCommand? = null
            private set

        fun apply(command: WfdAdvertiseCommand?) {
            last = command
        }
    }
}
