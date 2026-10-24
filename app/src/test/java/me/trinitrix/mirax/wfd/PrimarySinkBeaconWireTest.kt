package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Producer-side wire encoding of the privileged beacon's group-state line,
 * exercised through the [PrimarySinkBeacon.formatGroupState] seam.
 *
 * Platform callbacks that feed the name (`onConnectionRequested`,
 * `requestGroupInfo`) need a device and stay out of this JVM suite.
 */
class PrimarySinkBeaconWireTest {

    private fun format(name: String?, owner: Boolean = false): String =
        PrimarySinkBeacon.formatGroupState("192.168.137.1", owner, name)

    @Test
    fun formatGroupState_missingAddress_doesNotPublishReadySource() {
        assertThat(PrimarySinkBeacon.formatGroupState(null, false, "Source PC")).isEqualTo("DOWN")
        assertThat(PrimarySinkBeacon.formatGroupState("", true, "Source PC")).isEqualTo("DOWN")
    }

    @Test
    fun formatGroupState_missingName_keepsLegacyHeader() {
        assertThat(format(null)).isEqualTo("UP 192.168.137.1")
        assertThat(format("")).isEqualTo("UP 192.168.137.1")
        assertThat(format("  \t\r\n ")).isEqualTo("UP 192.168.137.1")
        assertThat(format(null, owner = true)).isEqualTo("UP 192.168.137.1 OWNER")
    }

    @Test
    fun formatGroupState_name_appendsTabSegment() {
        assertThat(format("DESKTOP-ABC1234")).isEqualTo("UP 192.168.137.1\tDESKTOP-ABC1234")
        assertThat(format("Alice's Surface", owner = true))
            .isEqualTo("UP 192.168.137.1 OWNER\tAlice's Surface")
    }

    @Test
    fun formatGroupState_delimitersBecomeSpaces_unicodeKept() {
        assertThat(format("PC\twith\ttabs")).isEqualTo("UP 192.168.137.1\tPC with tabs")
        assertThat(format("PC\r\nLine")).isEqualTo("UP 192.168.137.1\tPC  Line")
        assertThat(format("  Desk top  ")).isEqualTo("UP 192.168.137.1\tDesk top")
        assertThat(format("書桌 電腦-é")).isEqualTo("UP 192.168.137.1\t書桌 電腦-é")
    }

    @Test
    fun formatGroupState_roundTripsThroughOwnerParser() {
        assertThat(P2pGroupState.parse(format("Alice's Surface", owner = true))).isEqualTo(
            P2pGroupState.Up(
                "192.168.137.1",
                phoneIsOwner = true,
                sourceDeviceName = "Alice's Surface",
            ),
        )
        assertThat(P2pGroupState.parse(format(null)))
            .isEqualTo(P2pGroupState.Up("192.168.137.1"))
    }
}
