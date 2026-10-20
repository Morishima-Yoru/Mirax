package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the AdvertisingState deep module (issue #2).
 *
 * Seam under test: [AdvertisingState] — feed WFD ownership, privilege, and
 * beacon events; assert phase progression from IDLE through ADVERTISING,
 * BEACON_LISTENING to DENIED, and back to IDLE on owner loss.
 * Non-legal transitions are swallowed internally.
 */
class AdvertisingStateTest {

    private fun freshState() = AdvertisingState()

    @Test
    fun initialPhaseIsIdle() {
        val state = freshState()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun enablingWithOwnerMovesToAdvertising() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.ADVERTISING)
    }

    @Test
    fun enablingWithoutOwnerStaysIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.NONE, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun enablingWithWifiDisabledStaysIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = false)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun beaconListeningMovesToBeaconListening() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconListening(WfdOwner.HELPER)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.BEACON_LISTENING)
    }

    @Test
    fun beaconFailedMovesToDenied() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconFailed()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.DENIED)
    }

    @Test
    fun ownerNoneResetsToIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconListening(WfdOwner.HELPER)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.BEACON_LISTENING)
        state.onOwnerNone()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun disablingWhileAdvertisingMovesToIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.ADVERTISING)
        state.setAdvertising(enabled = false, owner = WfdOwner.HELPER, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun beaconFailedFromIdleDoesNothing() {
        val state = freshState()
        state.onBeaconFailed()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun beaconListeningFromIdleDoesNothing() {
        val state = freshState()
        state.onBeaconListening(WfdOwner.HELPER)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun ownerNoneFromDeniedMovesToIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconFailed()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.DENIED)
        state.onOwnerNone()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun disablingFromDeniedMovesToIdle() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconFailed()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.DENIED)
        state.setAdvertising(enabled = false, owner = WfdOwner.HELPER, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.IDLE)
    }

    @Test
    fun reEnablingFromDeniedMovesToAdvertising() {
        val state = freshState()
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        state.onBeaconFailed()
        assertThat(state.phase).isEqualTo(AdvertisingPhase.DENIED)
        state.setAdvertising(enabled = true, owner = WfdOwner.HELPER, wifiEnabled = true)
        assertThat(state.phase).isEqualTo(AdvertisingPhase.ADVERTISING)
    }
}