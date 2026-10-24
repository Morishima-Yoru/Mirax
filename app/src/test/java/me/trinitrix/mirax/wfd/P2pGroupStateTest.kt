package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class P2pGroupStateTest {
    @Test
    fun parse_upClient() {
        val state = P2pGroupState.parse("UP 192.168.137.1")
        assertThat(state).isEqualTo(P2pGroupState.Up("192.168.137.1", phoneIsOwner = false))
    }

    @Test
    fun parse_upOwner() {
        val state = P2pGroupState.parse("UP 192.168.138.248 OWNER")
        assertThat(state).isEqualTo(P2pGroupState.Up("192.168.138.248", phoneIsOwner = true))
    }

    @Test
    fun parse_preservesSourceNameWithoutTreatingItAsOwnerFlag() {
        val state = P2pGroupState.parse("UP 192.168.137.1\tOWNER")

        assertThat(state).isEqualTo(
            P2pGroupState.Up("192.168.137.1", sourceDeviceName = "OWNER"),
        )
    }

    @Test
    fun parse_preservesFullSourceNameAndOwnerRole() {
        val state = P2pGroupState.parse("UP 192.168.138.248 OWNER\tAlice's 工作電腦")

        assertThat(state).isEqualTo(
            P2pGroupState.Up("192.168.138.248", phoneIsOwner = true, sourceDeviceName = "Alice's 工作電腦"),
        )
    }

    @Test
    fun parse_missingOrBlankSourceNameRemainsUnknown() {
        for (line in listOf("UP 192.168.137.1", "UP 192.168.137.1\t", "UP 192.168.137.1\t  ")) {
            assertThat(P2pGroupState.parse(line)).isEqualTo(P2pGroupState.Up("192.168.137.1"))
        }
    }

    @Test
    fun parse_nameCannotReplaceMissingSourceAddress() {
        assertThat(P2pGroupState.parse("UP \tAlice's Surface")).isEqualTo(P2pGroupState.Down)
    }

    @Test
    fun parse_pendingAndDown() {
        assertThat(P2pGroupState.parse("PENDING")).isEqualTo(P2pGroupState.Pending)
        assertThat(P2pGroupState.parse("DOWN")).isEqualTo(P2pGroupState.Down)
        assertThat(P2pGroupState.parse(null)).isEqualTo(P2pGroupState.Down)
    }
}
