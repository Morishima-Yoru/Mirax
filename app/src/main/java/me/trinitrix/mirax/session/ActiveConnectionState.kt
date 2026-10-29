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

    var picturePresented: Boolean = false
        private set

    /** Confirm that the connected picture reached the foreground, not merely that launch was requested. */
    fun onProjectionPresented() {
        if (isConnected) {
            picturePresented = true
            awayOnHomeScreen = false
        }
    }

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

    /** Set panel visibility. */
    fun setControlPanelExpanded(value: Boolean) {
        controlPanelExpanded = value
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
        } else {
            Log.w(TAG, "onPlayStarted called in invalid phase $_phase, ending connection")
            _phase = ConnectionPhase.ENDED
        }
    }

    fun onConnectionEnded() {
        _phase = ConnectionPhase.ENDED
        picturePresented = false
        controlPanelExpanded = false
        awayOnHomeScreen = false
        _selectedMode = null
        connectionAdvertisedModesInternal = null
        connectionPreferredModeInternal = null
    }

    fun onSourceSelectedMode(mode: VideoMode, fallbackModes: Set<VideoMode>) {
        freezeConnectionAdvertisedModes(fallbackModes)
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

    /** Enter a connected session for debug verification with [mode]. */
    fun enterMockConnected(mode: VideoMode = VideoMode(1920, 1080, 60)) {
        _phase = ConnectionPhase.PLAYING
        picturePresented = false
        _selectedMode = mode
        connectionPreferredModeInternal = mode
        connectionAdvertisedModesInternal = setOf(mode)
        awayOnHomeScreen = false
        controlPanelExpanded = false
    }

    /** Close an expanded control panel without affecting the connection. */
    fun onSystemBack() {
        if (isConnected) {
            controlPanelExpanded = false
        }
    }
}
