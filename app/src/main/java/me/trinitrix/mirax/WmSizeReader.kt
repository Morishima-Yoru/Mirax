package me.trinitrix.mirax

import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.wfd.WfdOwnerBridge

/**
 * Host-side `wm size` reader. Runs only when the session allows ([canReadWmSize]).
 * The query runs inside the WFD owner — the Shizuku user-service or the adb
 * helper — never in the app process, which is not shell.
 */
object WmSizeReader {
    /**
     * Read plain `wm size` (no display id) for provisioning.
     */
    fun readPlain(): WmSizeReading? = read(displayId = null)

    /**
     * Read `wm size` for a specific display id (Mirax window's display).
     */
    fun readForDisplay(displayId: Int): WmSizeReading? = read(displayId = displayId)

    private fun read(displayId: Int?): WmSizeReading? {
        val output = WfdOwnerBridge.wmSize(displayId) ?: return null
        return WmSizeParser.parse(output)
    }
}
