package me.trinitrix.mirax.host

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertThrows
import org.junit.Test

/** Byte-boundary checks for the opt-in V2 single-line helper read. */
class HelperClientBoundedLineTest {
    @Test
    fun boundedLine_acceptsExactly512BytesAndStripsTerminator() {
        val payload = "a".repeat(512)
        val line = HelperClient.readBoundedLine(
            ByteArrayInputStream("$payload\ntrailing".toByteArray(StandardCharsets.UTF_8)),
            512,
            "GROUP_V2",
        )
        assertThat(line).isEqualTo(payload)
        assertThat(line.toByteArray(StandardCharsets.UTF_8)).hasLength(512)
    }

    @Test
    fun boundedLine_rejects513thByteAndTruncatedStream() {
        val oversized = ByteArrayInputStream(("b".repeat(513) + "\n").toByteArray(StandardCharsets.UTF_8))
        assertThrows(IOException::class.java) { HelperClient.readBoundedLine(oversized, 512, "GROUP_V2") }
        val truncated = ByteArrayInputStream("V2 partial".toByteArray(StandardCharsets.UTF_8))
        assertThrows(IOException::class.java) { HelperClient.readBoundedLine(truncated, 512, "GROUP_V2") }
    }

    @Test
    fun boundedLine_rejectsNonPositiveBound() {
        assertThrows(IllegalArgumentException::class.java) {
            HelperClient.exchangeBounded("GROUP_V2", 750, 0)
        }
    }
}
