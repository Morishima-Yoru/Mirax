package me.trinitrix.mirax.wfd

/**
 * Pure decision helper for H.264 surface low-latency configure retry.
 *
 * Ladder:
 * 1. Android [android.media.MediaFormat.KEY_LOW_LATENCY]
 * 2. MediaTek OMX vendor key [VENDOR_LOW_LATENCY_KEY] (`vdec-lowlatency`)
 * 3. Omit both keys
 *
 * Some MediaTek vendor decoders reject Android low-latency with CodecException
 * `-1010` (`ERROR_UNSUPPORTED`) but still accept the older OMX vendor flag.
 */
internal object H264LowLatencyConfigurePolicy {
    /** Android / OMX ERROR_UNSUPPORTED as seen when KEY_LOW_LATENCY is rejected. */
    const val ERROR_UNSUPPORTED: Int = -1010

    /**
     * MediaTek OMX vendor MediaFormat key mapped to
     * `OMX.MTK.index.param.video.LowLatencyDecode`.
     */
    const val VENDOR_LOW_LATENCY_KEY: String = "vdec-lowlatency"

    /** Configure attempt phases for the low-latency retry ladder. */
    enum class Phase {
        /** First attempt: set KEY_LOW_LATENCY=1. */
        WITH_LOW_LATENCY,

        /** Second attempt: set vendor `vdec-lowlatency=1` only. */
        WITH_VENDOR_LOW_LATENCY,

        /** Third attempt after vendor configure failure: omit both keys. */
        WITHOUT_LOW_LATENCY,
    }

    /** Whether [phase] should set Android KEY_LOW_LATENCY. */
    fun usesAndroidLowLatency(phase: Phase): Boolean = phase == Phase.WITH_LOW_LATENCY

    /** Whether [phase] should set [VENDOR_LOW_LATENCY_KEY]. */
    fun usesVendorLowLatency(phase: Phase): Boolean = phase == Phase.WITH_VENDOR_LOW_LATENCY

    /** Log label for the phase that successfully configured. */
    fun lowLatencyLabel(phase: Phase): String = when (phase) {
        Phase.WITH_LOW_LATENCY -> "true"
        Phase.WITH_VENDOR_LOW_LATENCY -> "vendor"
        Phase.WITHOUT_LOW_LATENCY -> "omitted"
    }

    /**
     * Human-readable reason for retrying after [phase] failed, or null when
     * the ladder is exhausted.
     */
    fun retryReason(phase: Phase): String? = when (phase) {
        Phase.WITH_LOW_LATENCY ->
            "decoder configure with KEY_LOW_LATENCY failed; retrying with vdec-lowlatency"
        Phase.WITH_VENDOR_LOW_LATENCY ->
            "decoder configure with vdec-lowlatency failed; retrying without low-latency keys"
        Phase.WITHOUT_LOW_LATENCY -> null
    }

    /**
     * Next configure phase after a failure, or null when the caller should give up.
     *
     * Args:
     *     phase: Phase that just failed.
     *     isCodecException: Whether the failure was a MediaCodec.CodecException.
     *     errorCode: CodecException.errorCode when available; otherwise null.
     *
     * Returns:
     *     The next phase, or null to stop retrying.
     */
    @Suppress("UNUSED_PARAMETER")
    fun nextPhaseAfterFailure(
        phase: Phase,
        isCodecException: Boolean,
        errorCode: Int? = null,
    ): Phase? {
        return when (phase) {
            Phase.WITH_LOW_LATENCY -> Phase.WITH_VENDOR_LOW_LATENCY
            Phase.WITH_VENDOR_LOW_LATENCY -> Phase.WITHOUT_LOW_LATENCY
            Phase.WITHOUT_LOW_LATENCY -> null
        }
    }
}
