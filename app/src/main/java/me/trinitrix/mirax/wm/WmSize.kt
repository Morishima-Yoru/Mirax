package me.trinitrix.mirax.wm

import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.wfd.WfdOwnerBridge

/**
 * `wm size` for the session: parse owner stdout, or ask the WFD owner to run it.
 *
 * Override size is preferred when present; axes are never swapped. The query
 * runs inside the Shizuku user-service or the rooted helper, never in the app process.
 */
object WmSize {
    private val PHYSICAL = Regex("""Physical size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val OVERRIDE = Regex("""Override size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Parse stdout from `wm size` or `wm size -d <displayId>`.
     *
     * Returns a reading when Physical size is present; null when unparseable.
     */
    fun parse(output: String): WmSizeReading? {
        val physical = PHYSICAL.find(output) ?: return null
        val physicalWidth = physical.groupValues[1].toIntOrNull() ?: return null
        val physicalHeight = physical.groupValues[2].toIntOrNull() ?: return null
        val override = OVERRIDE.find(output)
        val overrideWidth = override?.groupValues?.get(1)?.toIntOrNull()
        val overrideHeight = override?.groupValues?.get(2)?.toIntOrNull()
        return WmSizeReading(
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
            overrideWidth = overrideWidth,
            overrideHeight = overrideHeight,
        )
    }

    /** Plain `wm size` (no display id) for provisioning. */
    fun readPlain(): WmSizeReading? = read(displayId = null)

    /** `wm size` for the display hosting the Mirax window. */
    fun readForDisplay(displayId: Int): WmSizeReading? = read(displayId = displayId)

    private fun read(displayId: Int?): WmSizeReading? {
        val output = WfdOwnerBridge.wmSize(displayId) ?: return null
        return parse(output)
    }
}
