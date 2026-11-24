package me.trinitrix.mirax.helper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HelperGroupV2ProtocolTest {
    private val epoch = "01234567-89ab-cdef-0123-456789abcdef-1"

    @Test
    fun commands_areStrictAndOnlyValidEndIsQueued() {
        val ended = mutableListOf<String>()
        val snapshot = "V2 $epoch UP 192.168.49.1"
        fun exchange(line: String): String? = Helper.groupV2Response(
            line,
            { snapshot },
            { value ->
                ended.add(value)
                true
            },
        )

        assertThat(exchange("GROUP_V2")).isEqualTo(snapshot)
        listOf(
            "GROUP_V2 extra", "GROUP_V2x", "END_V2", "END_V2 ", "END_V2 bad",
            "END_V2 $epoch extra", "END_V2  $epoch", "END_V2 $epoch\tEND",
        ).forEach { assertThat(exchange(it)).isEqualTo("ERR malformed") }
        assertThat(ended).isEmpty()
        assertThat(exchange("END_V2 $epoch")).isEqualTo("QUEUED")
        assertThat(ended).containsExactly(epoch)
        assertThat(exchange("END")).isNull()
        assertThat(exchange("GROUP")).isNull()
        assertThat(exchange("UNKNOWN")).isNull()
        assertThat(Helper.groupV2Response("END_V2 $epoch", { snapshot }, { false }))
            .isEqualTo("ERR unavailable")
    }
}
