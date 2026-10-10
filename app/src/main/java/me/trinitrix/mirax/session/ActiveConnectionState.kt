package me.trinitrix.mirax.session

import android.util.Log

/**
 * Tracks the ephemeral state of an in-progress or active P2P/RTSP connection attempt.
 * Destroyed and recreated when a connection ends or is reset.
 */
internal class ActiveConnectionState {
    private companion object {
        private const val TAG = "ActiveConnectionState"
    }
    var connected: Boolean = false
    var negotiating: Boolean = false
    var selectedMode: VideoMode? = null
    var connectionAdvertisedModes: Set<VideoMode>? = null
    var connectionOfferFrozen: Boolean = false
    var connectionPreferredMode: VideoMode? = null
    var backEndsConnectionPending: Boolean = false
    var bottomHandleExpanded: Boolean = false
    var awayOnHomeScreen: Boolean = false

    fun onPrePlayProgress(advertisedModes: Set<VideoMode>) {
        negotiating = true
        freezeConnectionAdvertisedModes(advertisedModes)
    }

    fun freezeConnectionAdvertisedModes(advertisedModes: Set<VideoMode>) {
        if (connectionAdvertisedModes == null) {
            connectionAdvertisedModes = advertisedModes
        }
    }

    fun freezeConnectionOffer(
        reading: WmSizeReading?,
        baseModes: Set<VideoMode>,
        autoAddWmSizeOnConnect: Boolean,
        touchEnabled: Boolean,
        pictureRotationDegrees: Int,
        customModes: List<VideoMode>,
        savedPreferredMode: VideoMode?,
    ) {
        if (connectionOfferFrozen) return
        negotiating = true
        val injected = if (autoAddWmSizeOnConnect) {
            modeFromWmSize(reading, touchEnabled, pictureRotationDegrees)
        } else {
            null
        }
        val savedPreferred = customModes.firstOrNull() ?: savedPreferredMode?.takeIf { it in baseModes }
        connectionPreferredMode = injected ?: savedPreferred
        connectionAdvertisedModes = if (injected != null) baseModes + injected else baseModes
        connectionOfferFrozen = true
    }

    private fun modeFromWmSize(reading: WmSizeReading?, touchEnabled: Boolean, pictureRotationDegrees: Int): VideoMode? {
        if (reading == null) return null
        val (width, height) = CapsAndProvisioning.visiblePictureAxes(
            reading.chosenWidth,
            reading.chosenHeight,
            pictureRotationDegrees,
        )
        val refreshHz = if (touchEnabled) 30 else 60
        return PreferredModeCorrection.correct(width, height, refreshHz)
            ?: run {
                Log.w(TAG, "wm size $width×$height@$refreshHz exceeds Level 5.1, dropping")
                null
            }
    }

    fun onSourceSelectedMode(mode: VideoMode, fallbackModes: Set<VideoMode>) {
        freezeConnectionAdvertisedModes(fallbackModes)
        selectedMode = mode
        val allowed = connectionAdvertisedModes
        if (allowed != null && mode !in allowed) {
            connectionAdvertisedModes = allowed + mode
        }
    }

    fun enterPlay(): Boolean {
        connected = true
        awayOnHomeScreen = false
        return true
    }

    sealed interface BackResult {
        data object ExpandHandle : BackResult
        data object RequestEnd : BackResult
        data object ArmedSecondBack : BackResult
    }

    fun onSystemBack(bottomHandleEnabled: Boolean): BackResult {
        if (!connected) return BackResult.ArmedSecondBack
        if (bottomHandleEnabled) {
            return if (!bottomHandleExpanded) {
                bottomHandleExpanded = true
                BackResult.ExpandHandle
            } else {
                BackResult.RequestEnd
            }
        }
        if (backEndsConnectionPending) {
            backEndsConnectionPending = false
            return BackResult.RequestEnd
        }
        backEndsConnectionPending = true
        return BackResult.ArmedSecondBack
    }
}
