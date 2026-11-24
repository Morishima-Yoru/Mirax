package me.trinitrix.mirax.standbyprobe.media

/**
 * Tiny synthetic trusted fixtures for the standby media probe.
 *
 * Both are kept small so runs stay cheap.
 * The GIF payload encodes 2 frames with a finite loop count of 1; the probe
 * forces infinite repeat to verify runtime loop behavior overrides finite loop.
 * Byte counts are transport size only and must not be read as process memory.
 *
 * shortcut: fixture size guard is probe-only, revisit after real-device
 * bounds are measured.
 */
object StandbyMediaProbeFixtures {
    /** Max accepted probe payload bytes (probe guard only, not a product limit). */
    const val MAX_PROBE_BYTES: Int = 64 * 1024

    /** 1x1 transparent PNG, base64 encoded. */
    const val SMALL_PNG_BASE64: String =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /**
     * 1x1 two-frame animated GIF with finite loop (loop count 1), base64 encoded.
     * Decoded by ImageDecoder as AnimatedImageDrawable.
     */
    const val SMALL_GIF_BASE64: String =
        "R0lGODlhAQABAIAAAP8AAAAA/yH/C05FVFNDQVBFMi4wAwEBAAAh+QQACgAAACwAAAAAAQABAAACAkQBACH5BAAKAAAALAAAAAABAAEAAAICTAEAOw=="

    /**
     * Check a probe payload stays inside the trusted probe guard.
     *
     * @param bytes Payload bytes.
     * @return True when non-empty and within [MAX_PROBE_BYTES].
     */
    fun isTrustedSize(bytes: ByteArray): Boolean {
        return bytes.isNotEmpty() && bytes.size <= MAX_PROBE_BYTES
    }
}
