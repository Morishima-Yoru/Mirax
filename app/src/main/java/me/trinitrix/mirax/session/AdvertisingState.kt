package me.trinitrix.mirax.session

/**
 * Encapsulates WFD ownership and listen-beacon state machine.
 * Deep module: exposes a small interface (AdvertisingPhase) over a complex lifecycle implementation.
 */
internal class AdvertisingState(
    initialAdvertisingEnabled: Boolean = false,
) {
    private var _phase: AdvertisingPhase = if (initialAdvertisingEnabled) AdvertisingPhase.ADVERTISING else AdvertisingPhase.IDLE
    val phase: AdvertisingPhase
        get() = _phase

    private var advertisingEnabled: Boolean = initialAdvertisingEnabled
        private set

    val advertisingEnabledState: Boolean
        get() = advertisingEnabled

    private var beaconListening: Boolean = false
        private set

    fun setAdvertising(enabled: Boolean, owner: WfdOwner, wifiEnabled: Boolean): SetAdvertisingResult {
        if (enabled && owner == WfdOwner.NONE) {
            return SetAdvertisingResult.NoOwner
        }
        if (enabled && !wifiEnabled) {
            return SetAdvertisingResult.WifiDisabled
        }
        advertisingEnabled = enabled
        if (!enabled) {
            _phase = AdvertisingPhase.IDLE
            beaconListening = false
        } else {
            _phase = AdvertisingPhase.ADVERTISING
            beaconListening = false
        }
        return SetAdvertisingResult.Applied(turnedOff = !enabled)
    }

    fun onBeaconListening(owner: WfdOwner) {
        if (!advertisingEnabled || owner == WfdOwner.NONE) {
            return
        }
        beaconListening = true
        _phase = AdvertisingPhase.BEACON_LISTENING
    }

    fun onBeaconFailed(): Boolean {
        if (!advertisingEnabled) {
            beaconListening = false
            return false
        }
        advertisingEnabled = false
        beaconListening = false
        _phase = AdvertisingPhase.DENIED
        return true
    }

    fun onOwnerNone() {
        beaconListening = false
        if (_phase != AdvertisingPhase.IDLE) {
            _phase = AdvertisingPhase.IDLE
        }
    }

    fun isDeniedByPrivilege(): Boolean {
        return _phase == AdvertisingPhase.DENIED
    }

    sealed interface SetAdvertisingResult {
        data object NoOwner : SetAdvertisingResult
        data object WifiDisabled : SetAdvertisingResult
        data class Applied(val turnedOff: Boolean) : SetAdvertisingResult
    }
}