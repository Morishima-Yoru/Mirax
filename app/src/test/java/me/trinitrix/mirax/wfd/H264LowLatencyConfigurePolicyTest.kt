package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.wfd.H264LowLatencyConfigurePolicy.Phase
import org.junit.Test

class H264LowLatencyConfigurePolicyTest {
    @Test
    fun errorUnsupported_isNegative1010() {
        assertThat(H264LowLatencyConfigurePolicy.ERROR_UNSUPPORTED).isEqualTo(-1010)
    }

    @Test
    fun usesLowLatency_onlyOnFirstPhase() {
        assertThat(H264LowLatencyConfigurePolicy.usesLowLatency(Phase.WITH_LOW_LATENCY)).isTrue()
        assertThat(H264LowLatencyConfigurePolicy.usesLowLatency(Phase.WITHOUT_LOW_LATENCY)).isFalse()
    }

    @Test
    fun nextPhase_afterLowLatencyUnsupported_retriesWithoutLowLatency() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITH_LOW_LATENCY,
            isCodecException = true,
            errorCode = H264LowLatencyConfigurePolicy.ERROR_UNSUPPORTED,
        )
        assertThat(next).isEqualTo(Phase.WITHOUT_LOW_LATENCY)
    }

    @Test
    fun nextPhase_afterLowLatencyCodecException_retriesWithoutLowLatency() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITH_LOW_LATENCY,
            isCodecException = true,
            errorCode = null,
        )
        assertThat(next).isEqualTo(Phase.WITHOUT_LOW_LATENCY)
    }

    @Test
    fun nextPhase_afterLowLatencyWrappedFailure_retriesWithoutLowLatency() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITH_LOW_LATENCY,
            isCodecException = false,
            errorCode = null,
        )
        assertThat(next).isEqualTo(Phase.WITHOUT_LOW_LATENCY)
    }

    @Test
    fun nextPhase_afterOmitLowLatencyFailure_givesUp() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITHOUT_LOW_LATENCY,
            isCodecException = true,
            errorCode = H264LowLatencyConfigurePolicy.ERROR_UNSUPPORTED,
        )
        assertThat(next).isNull()
    }
}
