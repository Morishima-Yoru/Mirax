package me.trinitrix.mirax

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Host-side parser for `wm size` stdout. */
class WmSizeParserTest {
    @Test
    fun prefersOverrideSize_keepsAxes() {
        val reading = WmSizeParser.parse(
            """
            Physical size: 904x2316
            Override size: 1812x2176
            """.trimIndent(),
        )
        assertThat(reading).isNotNull()
        checkNotNull(reading)
        assertThat(reading.chosenWidth).isEqualTo(1812)
        assertThat(reading.chosenHeight).isEqualTo(2176)
    }

    @Test
    fun physicalOnly_unchanged() {
        val reading = WmSizeParser.parse("Physical size: 1812x2176\n")
        assertThat(reading).isNotNull()
        checkNotNull(reading)
        assertThat(reading.chosenWidth).isEqualTo(1812)
        assertThat(reading.chosenHeight).isEqualTo(2176)
        assertThat(reading.overrideWidth).isNull()
    }
}
