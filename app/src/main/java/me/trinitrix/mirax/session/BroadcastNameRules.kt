package me.trinitrix.mirax.session

/**
 * Characters a broadcast name may not contain.
 *
 * Windows path characters and emoji are stripped so the name can be used as a
 * Wi-Fi Direct device name without a later save step.
 */
object BroadcastNameRules {
    /**
     * Remove forbidden characters. Does not trim; the caller trims on commit.
     *
     * Emoji are dropped by code point so the check runs on the unit-test JDK,
     * which does not accept `\p{Extended_Pictographic}`.
     */
    fun sanitize(value: String): String {
        val kept = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (!forbidden(codePoint)) {
                kept.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return kept.toString()
    }

    private fun forbidden(codePoint: Int): Boolean {
        if (codePoint <= 0x1F) {
            return true
        }
        if (codePoint == '\\'.code || codePoint == '/'.code || codePoint == ':'.code ||
            codePoint == '*'.code || codePoint == '?'.code || codePoint == '"'.code ||
            codePoint == '<'.code || codePoint == '>'.code || codePoint == '|'.code
        ) {
            return true
        }
        if (codePoint == 0x200D || codePoint == 0xFE0F) {
            return true
        }
        if (codePoint in 0x2600..0x27BF) {
            return true
        }
        return codePoint in 0x1F000..0x1FAFF
    }
}
