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
    fun initialPhase_isVendorFirst() {
        assertThat(H264LowLatencyConfigurePolicy.initialPhase())
            .isEqualTo(Phase.WITH_VENDOR_LOW_LATENCY)
    }

    @Test
    fun usesAndroidLowLatency_onlyOnAndroidPhase() {
        assertThat(
            H264LowLatencyConfigurePolicy.usesAndroidLowLatency(Phase.WITH_VENDOR_LOW_LATENCY),
        ).isFalse()
        assertThat(H264LowLatencyConfigurePolicy.usesAndroidLowLatency(Phase.WITH_LOW_LATENCY))
            .isTrue()
        assertThat(
            H264LowLatencyConfigurePolicy.usesAndroidLowLatency(Phase.WITHOUT_LOW_LATENCY),
        ).isFalse()
    }

    @Test
    fun usesVendorLowLatency_onlyOnVendorPhase() {
        assertThat(
            H264LowLatencyConfigurePolicy.usesVendorLowLatency(Phase.WITH_VENDOR_LOW_LATENCY),
        ).isTrue()
        assertThat(H264LowLatencyConfigurePolicy.usesVendorLowLatency(Phase.WITH_LOW_LATENCY))
            .isFalse()
        assertThat(
            H264LowLatencyConfigurePolicy.usesVendorLowLatency(Phase.WITHOUT_LOW_LATENCY),
        ).isFalse()
    }

    @Test
    fun lowLatencyLabel_coversAllPhases() {
        assertThat(H264LowLatencyConfigurePolicy.lowLatencyLabel(Phase.WITH_LOW_LATENCY))
            .isEqualTo("true")
        assertThat(H264LowLatencyConfigurePolicy.lowLatencyLabel(Phase.WITH_VENDOR_LOW_LATENCY))
            .isEqualTo("vendor")
        assertThat(H264LowLatencyConfigurePolicy.lowLatencyLabel(Phase.WITHOUT_LOW_LATENCY))
            .isEqualTo("omitted")
    }

    @Test
    fun nextPhase_afterVendorLowLatency_retriesAndroid() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITH_VENDOR_LOW_LATENCY,
            isCodecException = true,
            errorCode = H264LowLatencyConfigurePolicy.ERROR_UNSUPPORTED,
        )
        assertThat(next).isEqualTo(Phase.WITH_LOW_LATENCY)
    }

    @Test
    fun nextPhase_afterAndroidLowLatency_retriesWithoutKeys() {
        val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
            phase = Phase.WITH_LOW_LATENCY,
            isCodecException = true,
            errorCode = H264LowLatencyConfigurePolicy.ERROR_UNSUPPORTED,
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

    @Test
    fun retryReason_vendorThenAndroidThenExhausted() {
        assertThat(H264LowLatencyConfigurePolicy.retryReason(Phase.WITH_VENDOR_LOW_LATENCY))
            .contains("vdec-lowlatency")
        assertThat(H264LowLatencyConfigurePolicy.retryReason(Phase.WITH_LOW_LATENCY))
            .contains("KEY_LOW_LATENCY")
        assertThat(H264LowLatencyConfigurePolicy.retryReason(Phase.WITHOUT_LOW_LATENCY)).isNull()
    }
}
