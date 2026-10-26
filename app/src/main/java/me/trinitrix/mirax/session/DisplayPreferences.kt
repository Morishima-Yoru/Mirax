package me.trinitrix.mirax.session

/**
 * Encapsulates UI display preferences: picture scale, floating ball,
 * touch enable, debug messages, auto-stop timer, control panel, and orientation lock.
 */
internal class DisplayPreferences(
    initialFloatingBallEnabled: Boolean = true,
    initialPictureScale: PictureScale = PictureScale.PROPORTIONAL,
    initialTouchEnabled: Boolean = true,
    initialShowDebugMessages: Boolean = true,
    initialCameraCutoutAffectsLayout: Boolean = false,
    initialBroadcastAutoStopMinutes: Int = 1,
    initialShowDebugOverlay: Boolean = false,
    initialControlPanelEnabled: Boolean = true,
    initialControlPanelEntry: ControlPanelEntry = ControlPanelEntry.FLOATING_BUTTON,
    initialOrientationLockEnabled: Boolean = false,
) {

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

    var controlPanelEnabled: Boolean = initialControlPanelEnabled
        private set

    var controlPanelEntry: ControlPanelEntry = initialControlPanelEntry
        private set

    var orientationLockEnabled: Boolean = initialOrientationLockEnabled
        private set

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

    /** Record whether the picture control panel is available. */
    fun setControlPanelEnabled(enabled: Boolean) {
        controlPanelEnabled = enabled
    }

    /** Record how the projection control panel opens. */
    fun setControlPanelEntry(entry: ControlPanelEntry) {
        controlPanelEntry = entry
    }

    /** Record whether projection orientation is locked. */
    fun setOrientationLockEnabled(enabled: Boolean) {
        orientationLockEnabled = enabled
    }

    companion object {
        fun clampAutoStopMinutes(minutes: Int): Int = minutes.coerceIn(1, 180)
    }
}
