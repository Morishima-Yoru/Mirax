package me.trinitrix.mirax.session

/**
 * Mirax product session: the single test seam for screen phase, WFD owner,
 * tile state, widget status, resolved language, and effective broadcast name.
 *
 * Activities, the Quick Settings tile, and the home-screen widget only render
 * [snapshot] outputs and forward [SessionAction]s. Privileged work (wm size,
 * WFD advertise) is never performed in the app process; this module only
 * decides who may own WFD and whether wm size may be read through that owner.
 *
 * A *stay* is the lifetime of one [MiraxSession] instance (the app process
 * from this open). Shizuku permission is requested at most once per stay.
 *
 * Language and display name remain editable while the phase is frozen.
 * The session does not write the system device name.
 */
class MiraxSession(
    initialSettings: SessionSettings = SessionSettings(),
) {
    private var advertisingEnabled: Boolean = initialSettings.advertisingEnabled
    private var languagePreference: LanguagePreference = initialSettings.languagePreference
    private var displayNameOverride: String? = normalizeOverride(initialSettings.displayNameOverride)
    private var privilege: PrivilegeReport = PrivilegeReport()
    private var systemLocale: SystemLocaleReport = SystemLocaleReport()
    private var deviceName: String = ""
    private var connected: Boolean = false
    private var permissionRequestedThisStay: Boolean = false
    private var pendingPermissionRequest: Boolean = false
    private var pendingEffects: List<SessionEffect> = emptyList()

    /**
     * Feed the latest privilege-path observation from the environment.
     *
     * Args:
     *     report: Current Shizuku and helper availability.
     */
    fun report(report: PrivilegeReport) {
        val previousOwner = resolveOwner(privilege)
        val nextOwner = resolveOwner(report)
        if (
            nextOwner == WfdOwner.SHIZUKU &&
            report.helperRunning &&
            previousOwner != WfdOwner.SHIZUKU
        ) {
            pendingEffects = pendingEffects + SessionEffect.StopHelper
        }
        privilege = report
        if (nextOwner == WfdOwner.NONE) {
            connected = false
        }
    }

    /**
     * Feed whether the host system locale is Traditional Chinese.
     *
     * Args:
     *     report: Classification from the activity; tests supply literals.
     */
    fun report(report: SystemLocaleReport) {
        systemLocale = report
    }

    /**
     * Feed the phone's current device name. Mirax never writes this value back.
     *
     * Args:
     *     report: Current device name observed by the host.
     */
    fun report(report: DeviceNameReport) {
        deviceName = report.deviceName
    }

    /**
     * Apply a user or lifecycle action.
     *
     * Args:
     *     action: Open, retry, advertising toggle, language, display name, or connection event.
     */
    fun handle(action: SessionAction) {
        when (action) {
            SessionAction.OpenApp -> onOpenApp()
            SessionAction.Retry, SessionAction.AutoWait -> {
                pendingPermissionRequest = false
            }
            is SessionAction.SetAdvertising -> setAdvertising(action.enabled)
            SessionAction.TileTap -> onTileTap()
            SessionAction.AcknowledgeEffects -> pendingEffects = emptyList()
            SessionAction.ConnectionEstablished -> {
                if (resolveOwner(privilege) != WfdOwner.NONE && advertisingEnabled) {
                    connected = true
                }
            }
            SessionAction.ConnectionEnded -> connected = false
            is SessionAction.SetLanguagePreference -> {
                languagePreference = action.preference
            }
            is SessionAction.SetDisplayNameOverride -> {
                displayNameOverride = normalizeOverride(action.value)
            }
        }
    }

    /**
     * Current observable output for the UI and status surfaces.
     *
     * Returns:
     *     A [SessionSnapshot] derived solely from settings, reports, and actions.
     */
    fun snapshot(): SessionSnapshot {
        val owner = resolveOwner(privilege)
        val phase = resolvePhase(owner)
        val followsDevice = displayNameOverride == null
        val effectiveName = if (followsDevice) deviceName else displayNameOverride.orEmpty()
        return SessionSnapshot(
            phase = phase,
            wfdOwner = owner,
            tileState = resolveTile(owner, phase),
            widgetStatus = resolveWidget(owner, phase),
            advertisingEnabled = advertisingEnabled,
            shouldRequestShizukuPermission = pendingPermissionRequest,
            canReadWmSize = owner != WfdOwner.NONE,
            helperStartCommand = HELPER_START_COMMAND,
            effects = pendingEffects,
            languagePreference = languagePreference,
            appLanguage = resolveAppLanguage(),
            effectiveBroadcastName = effectiveName,
            displayNameOverride = displayNameOverride,
            displayNameFollowsDevice = followsDevice,
            displayNameFieldHint = if (followsDevice) deviceName else "",
        )
    }

    private fun onOpenApp() {
        val shouldAsk =
            privilege.shizukuServiceRunning &&
                !privilege.shizukuAuthorized &&
                !permissionRequestedThisStay
        if (shouldAsk) {
            permissionRequestedThisStay = true
            pendingPermissionRequest = true
        } else {
            pendingPermissionRequest = false
        }
    }

    private fun onTileTap() {
        val owner = resolveOwner(privilege)
        if (owner == WfdOwner.NONE) {
            pendingPermissionRequest = false
            pendingEffects = pendingEffects + SessionEffect.ShowShizukuNotOpenToast
            return
        }
        setAdvertising(!advertisingEnabled)
    }

    private fun setAdvertising(enabled: Boolean) {
        val owner = resolveOwner(privilege)
        if (enabled && owner == WfdOwner.NONE) {
            return
        }
        advertisingEnabled = enabled
        if (!enabled) {
            connected = false
        }
    }

    private fun resolveAppLanguage(): AppLanguage {
        return when (languagePreference) {
            LanguagePreference.TRADITIONAL_CHINESE -> AppLanguage.TRADITIONAL_CHINESE
            LanguagePreference.ENGLISH -> AppLanguage.ENGLISH
            LanguagePreference.FOLLOW_SYSTEM -> {
                if (systemLocale.isTraditionalChinese) {
                    AppLanguage.TRADITIONAL_CHINESE
                } else {
                    AppLanguage.ENGLISH
                }
            }
        }
    }

    private fun resolveOwner(report: PrivilegeReport): WfdOwner {
        val shizukuReady = report.shizukuServiceRunning && report.shizukuAuthorized
        return when {
            shizukuReady -> WfdOwner.SHIZUKU
            report.helperRunning -> WfdOwner.HELPER
            else -> WfdOwner.NONE
        }
    }

    private fun resolvePhase(owner: WfdOwner): ScreenPhase {
        if (owner == WfdOwner.NONE) {
            return ScreenPhase.FROZEN
        }
        if (connected) {
            return ScreenPhase.CONNECTED
        }
        return if (advertisingEnabled) {
            ScreenPhase.ADVERTISING
        } else {
            ScreenPhase.READY
        }
    }

    private fun resolveTile(owner: WfdOwner, phase: ScreenPhase): TileState {
        return when {
            owner == WfdOwner.NONE -> TileState.GRAY
            phase == ScreenPhase.CONNECTED -> TileState.CONNECTED
            phase == ScreenPhase.ADVERTISING -> TileState.ADVERTISING
            else -> TileState.OFF
        }
    }

    private fun resolveWidget(owner: WfdOwner, phase: ScreenPhase): WidgetStatus {
        return when {
            owner == WfdOwner.NONE -> WidgetStatus.UNAVAILABLE
            phase == ScreenPhase.CONNECTED -> WidgetStatus.CONNECTED
            phase == ScreenPhase.ADVERTISING -> WidgetStatus.ADVERTISING
            else -> WidgetStatus.OFF
        }
    }

    companion object {
        /**
         * Shared adb command shown on the waiting screen and in advanced adb.
         * Starts only the Mirax helper; it does not install or manage Shizuku.
         */
        const val HELPER_START_COMMAND: String =
            "adb shell \"CLASSPATH=/data/local/tmp/mirax-helper.jar " +
                "app_process /system/bin me.trinitrix.mirax.helper.Helper\""

        private fun normalizeOverride(value: String?): String? {
            if (value == null) {
                return null
            }
            val trimmed = value.trim()
            return trimmed.ifEmpty { null }
        }
    }
}
