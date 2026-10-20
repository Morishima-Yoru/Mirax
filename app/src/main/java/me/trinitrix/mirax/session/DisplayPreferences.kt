package me.trinitrix.mirax.session

/**
 * Encapsulates UI display preferences: picture scale, bottom handle,
 * floating ball, touch enable, debug messages, and auto-stop timer.
 */
internal class DisplayPreferences(
    initialBottomHandleEnabled: Boolean = true,
    initialFloatingBallEnabled: Boolean = true,
    initialPictureScale: PictureScale = PictureScale.PROPORTIONAL,
    initialTouchEnabled: Boolean = true,
    initialShowDebugMessages: Boolean = true,
    initialCameraCutoutAffectsLayout: Boolean = false,
    initialBroadcastAutoStopMinutes: Int = 1,
    initialShowDebugOverlay: Boolean = false,
) {
    var bottomHandleEnabled: Boolean = initialBottomHandleEnabled
        private set

    var floatingBallEnabled: Boolean = initialFloatingBallEnabled
        private set

    var pictureScale: PictureScale = initialPictureScale
        private set

    var touchEnabled: Boolean = initialTouchEnabled
        private set

    var showDebugMessages: Boolean = initialShowDebugMessages
        private set

    var cameraCutoutAffectsLayout: Boolean = initialCameraCutoutAffectsLayout
        private set

    var broadcastAutoStopMinutes: Int = clampAutoStopMinutes(initialBroadcastAutoStopMinutes)
        private set

    var showDebugOverlay: Boolean = initialShowDebugOverlay
        private set

    fun setBottomHandleEnabled(enabled: Boolean) {
        bottomHandleEnabled = enabled
    }

    fun setFloatingBallEnabled(enabled: Boolean) {
        floatingBallEnabled = enabled
    }

    fun setPictureScale(scale: PictureScale) {
        pictureScale = scale
    }

    fun setTouchEnabled(enabled: Boolean) {
        touchEnabled = enabled
    }

    fun setShowDebugMessages(enabled: Boolean) {
        showDebugMessages = enabled
    }

    fun setShowDebugOverlay(enabled: Boolean) {
        showDebugOverlay = enabled
    }

    fun setCameraCutoutAffectsLayout(enabled: Boolean) {
        cameraCutoutAffectsLayout = enabled
    }

    fun setBroadcastAutoStopMinutes(minutes: Int) {
        broadcastAutoStopMinutes = clampAutoStopMinutes(minutes)
    }

    companion object {
        fun clampAutoStopMinutes(minutes: Int): Int = minutes.coerceIn(1, 180)
    }
}
