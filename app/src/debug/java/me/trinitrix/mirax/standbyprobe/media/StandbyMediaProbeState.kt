package me.trinitrix.mirax.standbyprobe.media

/**
 * Inspectable public probe contract snapshot.
 *
 * @param hasMedia True when an image, GIF, or video surface is attached.
 * @param isAnimated True when current media is an animated drawable.
 * @param isLooping True when GIF repeat or video repeat is forced to infinite.
 * @param isPausedForInspect True after pause for inspection.
 * @param isVideo True when a video URI is bound.
 * @param isAttachedToWindow True when the probe view is attached.
 * @param isPlaying True when the underlying animated drawable or media player is actively running.
 */
data class StandbyMediaProbeState(
    val hasMedia: Boolean,
    val isAnimated: Boolean,
    val isLooping: Boolean,
    val isPausedForInspect: Boolean,
    val isVideo: Boolean,
    val isAttachedToWindow: Boolean,
    val isPlaying: Boolean = false
)
