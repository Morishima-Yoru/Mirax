package me.trinitrix.mirax

/**
 * Parses `wm size` command output into a [me.trinitrix.mirax.session.WmSizeReading].
 *
 * Override size is preferred when present; axes are never swapped.
 */
object WmSizeParser {
    private val PHYSICAL = Regex("""Physical size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val OVERRIDE = Regex("""Override size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Parse stdout from `wm size` or `wm size -d <displayId>`.
     *
     * Returns:
     *     Reading when Physical size is present; null when unparseable.
     */
    fun parse(output: String): me.trinitrix.mirax.session.WmSizeReading? {
        val physical = PHYSICAL.find(output) ?: return null
        val physicalWidth = physical.groupValues[1].toIntOrNull() ?: return null
        val physicalHeight = physical.groupValues[2].toIntOrNull() ?: return null
        val override = OVERRIDE.find(output)
        val overrideWidth = override?.groupValues?.get(1)?.toIntOrNull()
        val overrideHeight = override?.groupValues?.get(2)?.toIntOrNull()
        return me.trinitrix.mirax.session.WmSizeReading(
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
            overrideWidth = overrideWidth,
            overrideHeight = overrideHeight,
        )
    }
}
