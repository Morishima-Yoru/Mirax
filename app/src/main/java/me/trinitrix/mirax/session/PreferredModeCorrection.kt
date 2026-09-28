package me.trinitrix.mirax.session

/**
 * Parse and correct preferred-mode text: keep legal positive-integer width/height,
 * snap refresh to the allowed set, and shrink along aspect when over H.264 level 5.1.
 */
object PreferredModeCorrection {
    private val ALLOWED_REFRESH: IntArray = intArrayOf(24, 25, 30, 50, 60)

    private val MODE_PATTERN = Regex(
        """^\s*(\d+)\s*[x×X*]\s*(\d+)(?:\s*@\s*(\d+(?:\.\d+)?))?\s*$""",
    )

    /**
     * Snap a refresh rate to the nearest of 24, 25, 30, 50, 60.
     * Exact ties take the higher rate.
     */
    fun snapRefreshHz(raw: Double): Int {
        var best = ALLOWED_REFRESH[0]
        var bestDistance = kotlin.math.abs(raw - best)
        for (candidate in ALLOWED_REFRESH) {
            val distance = kotlin.math.abs(raw - candidate)
            if (distance < bestDistance || (distance == bestDistance && candidate > best)) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Correct width, height, and refresh into a legal preferred mode.
     *
     * Positive-integer dimensions are kept as given (including non-multiples of 16).
     * Refresh is snapped; if the mode exceeds level 5.1, width and height shrink along
     * the current aspect until they fit, then correction runs again.
     */
    fun correct(width: Int, height: Int, refreshHz: Int): VideoMode? {
        if (width <= 0 || height <= 0 || refreshHz <= 0) {
            return null
        }
        var w = width
        var h = height
        var refresh = snapRefreshHz(refreshHz.toDouble())
        var guard = 0
        while (guard < 32) {
            guard += 1
            val candidate = VideoMode(w, h, refresh)
            if (H264Level51.fits(candidate)) {
                return candidate
            }
            val shrunk = shrinkToLevel51(w, h, refresh) ?: return null
            if (shrunk.width == w && shrunk.height == h) {
                return null
            }
            w = shrunk.width
            h = shrunk.height
            refresh = snapRefreshHz(shrunk.refreshHz.toDouble())
        }
        return null
    }

    /**
     * Parse preferred-mode field text.
     *
     * Returns:
     *     Corrected mode, [ParseResult.Cleared] for blank, or [ParseResult.Unparseable].
     */
    fun parse(text: String, fallbackRefreshHz: Int = 60): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ParseResult.Cleared
        }
        val match = MODE_PATTERN.matchEntire(trimmed) ?: return ParseResult.Unparseable
        val width = match.groupValues[1].toIntOrNull() ?: return ParseResult.Unparseable
        val height = match.groupValues[2].toIntOrNull() ?: return ParseResult.Unparseable
        if (width <= 0 || height <= 0) {
            return ParseResult.Unparseable
        }
        val refreshRaw = match.groupValues[3]
        val refreshHint = if (refreshRaw.isEmpty()) {
            fallbackRefreshHz.toDouble()
        } else {
            val parsed = refreshRaw.toDoubleOrNull() ?: return ParseResult.Unparseable
            if (parsed <= 0.0) {
                return ParseResult.Unparseable
            }
            parsed
        }
        val snapped = snapRefreshHz(refreshHint)
        val corrected = correct(width, height, snapped) ?: return ParseResult.Unparseable
        return ParseResult.Accepted(corrected)
    }

    sealed interface ParseResult {
        data class Accepted(val mode: VideoMode) : ParseResult
        data object Cleared : ParseResult
        data object Unparseable : ParseResult
    }

    /**
     * Shrink width and height along the current aspect ratio until the mode fits
     * level 5.1 at [refreshHz]. Uses binary search on a scale factor.
     */
    private fun shrinkToLevel51(width: Int, height: Int, refreshHz: Int): VideoMode? {
        if (width <= 0 || height <= 0) {
            return null
        }
        if (H264Level51.fits(VideoMode(width, height, refreshHz))) {
            return VideoMode(width, height, refreshHz)
        }
        val aspect = width.toDouble() / height.toDouble()
        var lo = 0.0
        var hi = 1.0
        var best: VideoMode? = null
        repeat(48) {
            val mid = (lo + hi) / 2.0
            val w = (width * mid).toInt().coerceAtLeast(1)
            val h = (w / aspect).toInt().coerceAtLeast(1)
            val candidate = VideoMode(w, h, refreshHz)
            if (H264Level51.fits(candidate)) {
                best = candidate
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }
}
