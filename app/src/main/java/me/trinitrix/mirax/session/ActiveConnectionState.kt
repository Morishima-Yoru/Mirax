package me.trinitrix.mirax.session

import android.util.Log

/**
 * Tracks the ephemeral state of an in-progress or active P2P/RTSP connection attempt.
 * Destroyed and recreated when a connection ends or is reset.
 * Deep module: exposes a small interface (ConnectionPhase) over a complex lifecycle implementation.
 */
internal class ActiveConnectionState {
    private companion object {
        private const val TAG = "ActiveConnectionState"
    }

    private var _phase: ConnectionPhase = ConnectionPhase.NONE
    val phase: ConnectionPhase
        get() = _phase

    val isConnected: Boolean
        get() = _phase == ConnectionPhase.PLAYING

    private var _selectedMode: VideoMode? = null
    val selectedMode: VideoMode?
        get() = _selectedMode

    private var connectionAdvertisedModesInternal: Set<VideoMode>? = null
    val connectionAdvertisedModes: Set<VideoMode>?
        get() = connectionAdvertisedModesInternal

    private var connectionPreferredModeInternal: VideoMode? = null
    val connectionPreferredMode: VideoMode?
        get() = connectionPreferredModeInternal

    var awayOnHomeScreen: Boolean = false
        private set

    val awayOnHomeScreenState: Boolean
        get() = awayOnHomeScreen

    fun setAwayOnHomeScreen(value: Boolean) {
        awayOnHomeScreen = value
    }

    var controlPanelExpanded: Boolean = false
        private set

    val controlPanelExpandedState: Boolean
        get() = controlPanelExpanded

    fun setControlPanelExpanded(value: Boolean) {
        controlPanelExpanded = value
        if (!value) {
            backEndsConnectionPending = false
        }
    }

    var backEndsConnectionPending: Boolean = false
        private set

    val backEndsConnectionPendingState: Boolean
        get() = backEndsConnectionPending

    fun setBackEndsConnectionPending(value: Boolean) {
        backEndsConnectionPending = value
    }

    fun onPrePlayProgress(advertisedModes: Set<VideoMode>) {
        if (_phase == ConnectionPhase.NONE) {
            _phase = ConnectionPhase.NEGOTIATING
            freezeConnectionAdvertisedModes(advertisedModes)
        }
    }

    fun freezeConnectionAdvertisedModes(advertisedModes: Set<VideoMode>) {
        if (connectionAdvertisedModesInternal == null) {
            connectionAdvertisedModesInternal = advertisedModes
        }
    }

    fun freezeOffer(
        offeredModes: Set<VideoMode>,
        preferredMode: VideoMode?,
        touchEnabled: Boolean,
        rotationDegrees: Int,
    ) {
        if (_phase == ConnectionPhase.ENDED) {
            return
        }
        _phase = ConnectionPhase.OFFER_FROZEN
        connectionPreferredModeInternal = preferredMode
        connectionAdvertisedModesInternal = offeredModes
        _selectedMode = preferredMode
    }

    fun onPlayStarted() {
        if (_phase == ConnectionPhase.OFFER_FROZEN || _phase == ConnectionPhase.NEGOTIATING || _phase == ConnectionPhase.NONE) {
            _phase = ConnectionPhase.PLAYING
            awayOnHomeScreen = false
            backEndsConnectionPending = false
        } else {
            Log.w(TAG, "onPlayStarted called in invalid phase $_phase, ending connection")
            _phase = ConnectionPhase.ENDED
        }
    }

    fun onConnectionEnded() {
        _phase = ConnectionPhase.ENDED
        backEndsConnectionPending = false
        controlPanelExpanded = false
        awayOnHomeScreen = false
        _selectedMode = null
        connectionAdvertisedModesInternal = null
        connectionPreferredModeInternal = null
    }

    fun onSourceSelectedMode(mode: VideoMode, fallbackModes: Set<VideoMode>) {
        connectionAdvertisedModesInternal = fallbackModes
        _selectedMode = mode
        val allowed = connectionAdvertisedModesInternal
        if (allowed != null && mode !in allowed) {
            connectionAdvertisedModesInternal = allowed + mode
        }
    }

    fun enterPlay(): Boolean {
        awayOnHomeScreen = false
        return true
    }

    fun enterMockConnected(mode: VideoMode = VideoMode(1920, 1080, 60)) {
        _phase = ConnectionPhase.PLAYING
        _selectedMode = mode
        connectionPreferredModeInternal = mode
        connectionAdvertisedModesInternal = setOf(mode)
        awayOnHomeScreen = false
        backEndsConnectionPending = false
        controlPanelExpanded = false
    }

    sealed interface BackResult {
        data object ExpandControlPanel : BackResult
        data object RequestEnd : BackResult
        data object ArmedSecondBack : BackResult
    }

    fun onSystemBack(): BackResult {
        if (_phase != ConnectionPhase.PLAYING) {
            return BackResult.ArmedSecondBack
        }
        if (!controlPanelExpanded) {
            if (backEndsConnectionPending) {
                backEndsConnectionPending = false
                return BackResult.RequestEnd
            }
            controlPanelExpanded = true
            return BackResult.ExpandControlPanel
        }
        controlPanelExpanded = false
        backEndsConnectionPending = true
        return BackResult.ArmedSecondBack
    }
}
