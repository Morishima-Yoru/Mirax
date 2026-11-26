package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.WfdOwner
import org.junit.Test

class OwnerGroupSnapshotTest {
    private val epoch = "01234567-89ab-cdef-0123-456789abcdef-1"

    @Test
    fun parse_acceptsOnlyCompleteV2States() {
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch DOWN")?.state)
            .isEqualTo(P2pGroupState.Down)
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch PENDING")?.state)
            .isEqualTo(P2pGroupState.Pending)
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.SHIZUKU, "V2 $epoch UP 192.168.49.1")?.state)
            .isEqualTo(P2pGroupState.Up("192.168.49.1"))
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch UP 192.168.49.2 OWNER")?.state)
            .isEqualTo(P2pGroupState.Up("192.168.49.2", phoneIsOwner = true))
    }

    @Test
    fun parse_keepsOptionalPeerNameOnlyOnUp() {
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch UP 192.168.49.2\tAlice's Surface")?.state)
            .isEqualTo(P2pGroupState.Up("192.168.49.2", sourceDeviceName = "Alice's Surface"))
        assertThat(
            OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch UP 192.168.49.2 OWNER\tAlice's Surface")?.state,
        ).isEqualTo(
            P2pGroupState.Up("192.168.49.2", phoneIsOwner = true, sourceDeviceName = "Alice's Surface"),
        )
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch UP 192.168.49.2\t  ")?.state)
            .isEqualTo(P2pGroupState.Up("192.168.49.2"))
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch UP 192.168.49.2\tOWNER")?.state)
            .isEqualTo(P2pGroupState.Up("192.168.49.2", sourceDeviceName = "OWNER"))
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch DOWN\tAlice's Surface"))
            .isNull()
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, "V2 $epoch PENDING\tAlice's Surface"))
            .isNull()
    }

    @Test
    fun parse_rejectsUnknownMalformedAndExtraTokens() {
        val invalid = listOf(
            null, "", "ERR unknown", "DOWN", "V3 $epoch DOWN", "V2 bad DOWN",
            "V2 $epoch UNKNOWN", "V2 $epoch DOWN extra", "V2 $epoch PENDING OWNER",
            "V2 $epoch UP", "V2 $epoch UP 192.168.49.256", "V2 $epoch UP hostname",
            "V2 $epoch UP 192.168.49.1 owner", "V2 $epoch UP 192.168.49.1 OWNER extra",
            "V2  $epoch DOWN", "V2 $epoch DOWN ", "V2 $epoch DOWN\n",
            "V2 $epoch UP\t192.168.49.1 OWNER", "V2 $epoch UP 192.168.49.1\nEND",
        )
        invalid.forEach { raw ->
            assertThat(OwnerGroupSnapshot.parse(WfdOwner.HELPER, raw)).isNull()
        }
        assertThat(OwnerGroupSnapshot.parse(WfdOwner.NONE, "V2 $epoch DOWN")).isNull()
    }
}
