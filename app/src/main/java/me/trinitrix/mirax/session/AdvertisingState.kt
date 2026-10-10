package me.trinitrix.mirax.session

/**
 * Encapsulates WFD ownership and listen-beacon state machine.
 */
internal class AdvertisingState(
    initialAdvertisingEnabled: Boolean = false,
) {
    var advertisingEnabled: Boolean = initialAdvertisingEnabled
        private set

    var beaconListening: Boolean = false
        private set

    var advertiseDeniedByPrivilege: Boolean = false
        private set

    fun setAdvertising(enabled: Boolean, owner: WfdOwner, wifiEnabled: Boolean): SetAdvertisingResult {
        if (enabled && owner == WfdOwner.NONE) {
            return SetAdvertisingResult.NoOwner
        }
        if (enabled && !wifiEnabled) {
            return SetAdvertisingResult.WifiDisabled
        }
        advertisingEnabled = enabled
        beaconListening = false
        return SetAdvertisingResult.Applied(turnedOff = !enabled)
    }

    fun onBeaconListening(owner: WfdOwner) {
        if (!advertisingEnabled || owner == WfdOwner.NONE) {
            return
        }
        beaconListening = true
        advertiseDeniedByPrivilege = false
    }

    fun onBeaconFailed(): Boolean {
        if (!advertisingEnabled) {
            beaconListening = false
            return false
        }
        advertisingEnabled = false
        beaconListening = false
        advertiseDeniedByPrivilege = true
        return true
    }

    fun onOwnerNone() {
        beaconListening = false
    }

    fun resolvePhase(owner: WfdOwner, connected: Boolean, wifiEnabled: Boolean, negotiating: Boolean): ScreenPhase {
        if (owner == WfdOwner.NONE) {
            return ScreenPhase.FROZEN
        }
        if (connected) {
            return ScreenPhase.CONNECTED
        }
        if (advertisingEnabled && !wifiEnabled) {
            return ScreenPhase.WIFI_PAUSED
        }
        if (advertisingEnabled && negotiating) {
            return ScreenPhase.CONNECTING
        }
        if (!advertisingEnabled) {
            return ScreenPhase.READY
        }
        return if (beaconListening) {
            ScreenPhase.ADVERTISING
        } else {
            ScreenPhase.ARMING
        }
    }

    sealed interface SetAdvertisingResult {
        data object NoOwner : SetAdvertisingResult
        data object WifiDisabled : SetAdvertisingResult
        data class Applied(val turnedOff: Boolean) : SetAdvertisingResult
    }
}
