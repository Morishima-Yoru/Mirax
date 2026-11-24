package me.trinitrix.mirax.data.standby

/**
 * Shared spatial framing for standby custom media.
 *
 * Centre is normalized to [0, 1] on each axis with centre priority.
 * Zoom is a finite positive scale factor applied as clamped aspect fill.
 * No posture presets are stored here.
 *
 * @param centerX normalized horizontal centre in [0, 1].
 * @param centerY normalized vertical centre in [0, 1].
 * @param zoom finite positive zoom factor.
 */
data class StandbyFraming(
    val centerX: Float,
    val centerY: Float,
    val zoom: Float,
) {
    init {
        require(centerX in 0.0f..1.0f) { "centerX normalized" }
        require(centerY in 0.0f..1.0f) { "centerY normalized" }
        require(zoom.isFinite() && zoom > 0.0f) { "zoom finite positive" }
    }

    companion object {
        /** Default centred framing at unity zoom. */
        val CENTERED = StandbyFraming(0.5f, 0.5f, 1.0f)
    }
}
