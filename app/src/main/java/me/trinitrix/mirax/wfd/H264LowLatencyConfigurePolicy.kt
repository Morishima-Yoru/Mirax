package me.trinitrix.mirax.wfd

/**
 * Pure decision helper for H.264 surface [android.media.MediaFormat.KEY_LOW_LATENCY]
 * configure retry.
 *
 * Some MediaTek vendor decoders reject low-latency with CodecException `-1010`
 * (`ERROR_UNSUPPORTED`). Callers try with LL first, then omit the key once.
 */
internal object H264LowLatencyConfigurePolicy {
    /** Android / OMX ERROR_UNSUPPORTED as seen when KEY_LOW_LATENCY is rejected. */
    const val ERROR_UNSUPPORTED: Int = -1010

    /** Configure attempt phases for the low-latency retry ladder. */
    enum class Phase {
        /** First attempt: set KEY_LOW_LATENCY=1. */
        WITH_LOW_LATENCY,

        /** Second attempt after LL configure failure: omit KEY_LOW_LATENCY. */
        WITHOUT_LOW_LATENCY,
    }

    /** Whether [phase] should set KEY_LOW_LATENCY. */
    fun usesLowLatency(phase: Phase): Boolean = phase == Phase.WITH_LOW_LATENCY

    /**
     * Next configure phase after a failure, or null when the caller should give up.
     *
     * After [Phase.WITH_LOW_LATENCY] fails — including the known MTK CodecException
     * [ERROR_UNSUPPORTED] (`-1010`) and vendor-wrapped exceptions — advances to
     * [Phase.WITHOUT_LOW_LATENCY]. A failure while already omitting LL ends the ladder.
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
            Phase.WITH_LOW_LATENCY -> Phase.WITHOUT_LOW_LATENCY
            Phase.WITHOUT_LOW_LATENCY -> null
        }
    }
}
