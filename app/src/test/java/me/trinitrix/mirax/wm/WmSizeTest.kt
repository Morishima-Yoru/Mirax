package me.trinitrix.mirax.wm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Host-side parser for `wm size` stdout. */
class WmSizeTest {
    @Test
    fun prefersOverrideSize_keepsAxes() {
        val reading = WmSize.parse(
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
        val reading = WmSize.parse("Physical size: 1812x2176\n")
        assertThat(reading).isNotNull()
        checkNotNull(reading)
        assertThat(reading.chosenWidth).isEqualTo(1812)
        assertThat(reading.chosenHeight).isEqualTo(2176)
        assertThat(reading.overrideWidth).isNull()
    }
}
