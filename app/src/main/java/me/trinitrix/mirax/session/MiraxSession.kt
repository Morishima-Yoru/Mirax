package me.trinitrix.mirax.session

/**
 * Mirax product session: the single test seam for screen phase, WFD owner,
 * tile state, widget status, resolved language, effective broadcast name,
 * preferred mode, standard-mode checklist, the next advertisement set, the
 * WFD advertise command the privileged owner must apply, connection events
 * through PLAY (selected mode and picture phase), system Back confirm while
 * connected, and the picture bottom-handle outputs.
 *
 * Activities, the Quick Settings tile, and the home-screen widget only render
 * [snapshot] outputs and forward [SessionAction]s. Privileged work (wm size,
 * WFD advertise) is never performed in the app process; this module only
 * decides who may own WFD, whether wm size may be read, and which name and
 * mode set the owner should receive. RTSP encode/decode stays behind this
 * seam; views never interpret RTSP themselves.
 *
 * A *stay* is the lifetime of one [MiraxSession] instance (the app process
 * from this open). Shizuku permission is requested at most once per stay.
 *
 * Language, display name, preferred-mode text, and standard-mode checks remain
 * editable while the phase is frozen. The session does not write the system
 * device name. A group drop before PLAY does not change the next advertisement
 * set and does not latch extra modes.
 */
class MiraxSession(
    initialSettings: SessionSettings = SessionSettings(),
) {
    private var advertisingEnabled: Boolean = initialSettings.advertisingEnabled
    private var languagePreference: LanguagePreference = initialSettings.languagePreference
    private var displayNameOverride: String? = normalizeOverride(initialSettings.displayNameOverride)
    private var preferredMode: VideoMode? = initialSettings.preferredMode
    private var checkedStandardModes: Set<VideoMode> =
        initialSettings.checkedStandardModes.toSet()
    private var maxVideoBitrateBps: Long = initialSettings.maxVideoBitrateBps
    private var provisioningConsumed: Boolean =
        initialSettings.provisioningConsumed || initialSettings.preferredMode != null
    private var bottomHandleEnabled: Boolean = initialSettings.bottomHandleEnabled
    private var privilege: PrivilegeReport = PrivilegeReport()
    private var systemLocale: SystemLocaleReport = SystemLocaleReport()
    private var deviceName: String = ""
    private var miraxDisplayId: Int = 0
    private var connected: Boolean = false
    private var selectedMode: VideoMode? = null
    /** Modes frozen for the current connection's RTSP advertisement. */
    private var connectionAdvertisedModes: Set<VideoMode>? = null
    /**
     * After the first system Back while connected, the next Back ends this
     * connection. Cleared only by the next Back or when this connection ends.
     */
    private var backEndsConnectionPending: Boolean = false
    private var bottomHandleExpanded: Boolean = false
    private var permissionRequestedThisStay: Boolean = false
    private var pendingPermissionRequest: Boolean = false
    private var pendingEffects: List<SessionEffect> = emptyList()
    private var provisioningReadRequested: Boolean = false

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
            clearConnectionEphemerals()
            provisioningReadRequested = false
            pendingEffects = pendingEffects.filterNot {
                it is SessionEffect.ReadPlainWmSizeForProvisioning
            }
        } else {
            maybeRequestProvisioningRead()
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
     * Feed the display id of the window hosting Mirax.
     *
     * Args:
     *     report: Display id from the host; tests supply literals.
     */
    fun report(report: MiraxDisplayReport) {
        miraxDisplayId = report.displayId
    }

    /**
     * Apply a user or lifecycle action.
     *
     * Args:
     *     action: Open, retry, advertising toggle, language, display name,
     *     resolution, or connection event.
     */
    fun handle(action: SessionAction) {
        when (action) {
            SessionAction.OpenApp -> onOpenApp()
            SessionAction.Retry, SessionAction.AutoWait -> {
                pendingPermissionRequest = false
                maybeRequestProvisioningRead()
            }
            is SessionAction.SetAdvertising -> setAdvertising(action.enabled)
            SessionAction.TileTap -> onTileTap()
            SessionAction.AcknowledgeEffects -> {
                // Keep an outstanding provisioning read until the host applies it.
                pendingEffects = pendingEffects.filterIsInstance<
                    SessionEffect.ReadPlainWmSizeForProvisioning
                    >()
            }
            SessionAction.BecameDiscoverable -> {
                freezeConnectionAdvertisedModes()
            }
            SessionAction.PrePlayProgress -> {
                freezeConnectionAdvertisedModes()
            }
            is SessionAction.SourceSelectedMode -> {
                onSourceSelectedMode(action.mode)
            }
            SessionAction.EnteredPlay, SessionAction.ConnectionEstablished -> {
                enterPlay()
            }
            SessionAction.ConnectionEnded -> {
                endConnectionState(emitDrop = false)
            }
            SessionAction.PrePlayGroupDropped -> {
                // Intentionally no-op for the advertisement set: a pre-PLAY drop
                // must not latch extra modes or rewrite the saved set.
                endConnectionState(emitDrop = false)
            }
            is SessionAction.SetLanguagePreference -> {
                languagePreference = action.preference
            }
            is SessionAction.SetDisplayNameOverride -> {
                displayNameOverride = normalizeOverride(action.value)
            }
            SessionAction.PreferredModeFieldEdited -> {
                consumeProvisioning()
            }
            is SessionAction.CommitPreferredModeText -> {
                commitPreferredModeText(action.text)
            }
            is SessionAction.SetStandardModeChecked -> {
                setStandardModeChecked(action.mode, action.checked)
            }
            is SessionAction.UseThisScreen -> {
                useThisScreen(action.reading)
            }
            is SessionAction.ApplyProvisioningWmSize -> {
                applyProvisioningWmSize(action.reading)
            }
            SessionAction.SystemBack -> {
                onSystemBack()
            }
            SessionAction.EndConnection -> {
                endConnectionFromUser()
            }
            is SessionAction.SetBottomHandleEnabled -> {
                bottomHandleEnabled = action.enabled
                if (!action.enabled) {
                    bottomHandleExpanded = false
                }
            }
            SessionAction.ToggleBottomHandleExpanded -> {
                if (connected && bottomHandleEnabled) {
                    bottomHandleExpanded = !bottomHandleExpanded
                }
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
        val canRead = owner != WfdOwner.NONE
        val nextModes = resolveNextAdvertisementModes()
        val standardRows = StandardVideoModes.catalog(maxVideoBitrateBps).map { mode ->
            StandardModeRow(mode = mode, checked = mode in checkedStandardModes)
        }
        val resolutionText = when {
            connected && selectedMode != null -> selectedMode!!.format()
            else -> preferredMode?.format().orEmpty()
        }
        val handleResolution = selectedMode?.let { "${it.width}×${it.height}" }.orEmpty()
        val handleRefresh = selectedMode?.refreshHz
        val showHandle = phase == ScreenPhase.CONNECTED && bottomHandleEnabled
        return SessionSnapshot(
            phase = phase,
            wfdOwner = owner,
            tileState = resolveTile(owner, phase),
            widgetStatus = resolveWidget(owner, phase),
            advertisingEnabled = advertisingEnabled,
            shouldRequestShizukuPermission = pendingPermissionRequest,
            canReadWmSize = canRead,
            helperStartCommand = HELPER_START_COMMAND,
            effects = pendingEffects,
            languagePreference = languagePreference,
            appLanguage = resolveAppLanguage(),
            effectiveBroadcastName = effectiveName,
            displayNameOverride = displayNameOverride,
            displayNameFollowsDevice = followsDevice,
            displayNameFieldHint = if (followsDevice) deviceName else "",
            preferredMode = preferredMode,
            preferredModeText = preferredMode?.format().orEmpty(),
            canUseThisScreen = canRead,
            standardModes = standardRows,
            nextAdvertisementModes = nextModes,
            currentResolutionText = resolutionText,
            miraxDisplayId = miraxDisplayId,
            maxVideoBitrateBps = maxVideoBitrateBps,
            wfdAdvertise = resolveWfdAdvertise(owner, effectiveName, nextModes),
            selectedMode = selectedMode,
            showPicture = phase == ScreenPhase.CONNECTED,
            bottomHandleEnabled = bottomHandleEnabled,
            showBottomHandle = showHandle,
            bottomHandleExpanded = showHandle && this.bottomHandleExpanded,
            handleResolutionText = if (showHandle) handleResolution else "",
            handleRefreshRateHz = if (showHandle) handleRefresh else null,
        )
    }

    /**
     * Settings to persist across process death.
     */
    fun exportSettings(): SessionSettings {
        return SessionSettings(
            advertisingEnabled = advertisingEnabled,
            languagePreference = languagePreference,
            displayNameOverride = displayNameOverride,
            preferredMode = preferredMode,
            checkedStandardModes = checkedStandardModes,
            maxVideoBitrateBps = maxVideoBitrateBps,
            provisioningConsumed = provisioningConsumed,
            bottomHandleEnabled = bottomHandleEnabled,
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
        maybeRequestProvisioningRead()
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
            endConnectionState(emitDrop = false)
        }
    }

    private fun onSystemBack() {
        if (!connected) {
            return
        }
        if (backEndsConnectionPending) {
            endConnectionFromUser()
            return
        }
        backEndsConnectionPending = true
        pendingEffects = pendingEffects + SessionEffect.ShowPressBackAgainToEndToast
    }

    private fun endConnectionFromUser() {
        if (!connected) {
            return
        }
        endConnectionState(emitDrop = true)
    }

    /**
     * Leave the connected phase. [emitDrop] asks the host to tear down the
     * active RTSP client while advertising stays as the user left it.
     */
    private fun endConnectionState(emitDrop: Boolean) {
        val wasConnected = connected
        connected = false
        clearConnectionEphemerals()
        if (emitDrop && wasConnected) {
            if (SessionEffect.DropActiveConnection !in pendingEffects) {
                pendingEffects = pendingEffects + SessionEffect.DropActiveConnection
            }
        }
    }

    private fun freezeConnectionAdvertisedModes() {
        if (connectionAdvertisedModes == null) {
            connectionAdvertisedModes = resolveNextAdvertisementModes()
        }
    }

    private fun onSourceSelectedMode(mode: VideoMode) {
        freezeConnectionAdvertisedModes()
        val allowed = connectionAdvertisedModes ?: resolveNextAdvertisementModes()
        if (mode in allowed) {
            selectedMode = mode
        }
    }

    private fun enterPlay() {
        if (resolveOwner(privilege) != WfdOwner.NONE && advertisingEnabled) {
            connected = true
        }
    }

    private fun clearConnectionEphemerals() {
        selectedMode = null
        connectionAdvertisedModes = null
        backEndsConnectionPending = false
        bottomHandleExpanded = false
    }

    private fun commitPreferredModeText(text: String) {
        // Leaving the field after any non-default content also consumes provisioning
        // when the user had typed something (edit already consumes; blank clear too).
        val fallbackRefresh = preferredMode?.refreshHz ?: 60
        when (val result = PreferredModeCorrection.parse(text, fallbackRefresh)) {
            PreferredModeCorrection.ParseResult.Cleared -> {
                preferredMode = null
                consumeProvisioning()
            }
            PreferredModeCorrection.ParseResult.Unparseable -> {
                // Restore last accepted; field text comes from preferredMode in snapshot.
            }
            is PreferredModeCorrection.ParseResult.Accepted -> {
                preferredMode = result.mode
                consumeProvisioning()
            }
        }
    }

    private fun setStandardModeChecked(mode: VideoMode, checked: Boolean) {
        val catalog = StandardVideoModes.catalog(maxVideoBitrateBps)
        if (mode !in catalog) {
            return
        }
        checkedStandardModes = if (checked) {
            checkedStandardModes + mode
        } else {
            checkedStandardModes - mode
        }
    }

    private fun useThisScreen(reading: WmSizeReading) {
        if (resolveOwner(privilege) == WfdOwner.NONE) {
            return
        }
        consumeProvisioning()
        val refresh = preferredMode?.refreshHz ?: 60
        val corrected = PreferredModeCorrection.correct(
            reading.chosenWidth,
            reading.chosenHeight,
            refresh,
        )
        if (corrected != null) {
            preferredMode = corrected
        }
    }

    private fun applyProvisioningWmSize(reading: WmSizeReading) {
        if (provisioningConsumed) {
            return
        }
        if (resolveOwner(privilege) == WfdOwner.NONE) {
            return
        }
        if (preferredMode != null) {
            consumeProvisioning()
            return
        }
        val corrected = PreferredModeCorrection.correct(
            reading.chosenWidth,
            reading.chosenHeight,
            60,
        )
        if (corrected != null) {
            preferredMode = corrected
        }
        consumeProvisioning()
        pendingEffects = pendingEffects.filterNot {
            it is SessionEffect.ReadPlainWmSizeForProvisioning
        }
        provisioningReadRequested = false
    }

    private fun maybeRequestProvisioningRead() {
        if (provisioningConsumed || preferredMode != null) {
            return
        }
        if (resolveOwner(privilege) == WfdOwner.NONE) {
            return
        }
        if (provisioningReadRequested) {
            return
        }
        if (SessionEffect.ReadPlainWmSizeForProvisioning in pendingEffects) {
            return
        }
        provisioningReadRequested = true
        pendingEffects = pendingEffects + SessionEffect.ReadPlainWmSizeForProvisioning
    }

    private fun consumeProvisioning() {
        provisioningConsumed = true
        provisioningReadRequested = false
        pendingEffects = pendingEffects.filterNot {
            it is SessionEffect.ReadPlainWmSizeForProvisioning
        }
    }

    private fun resolveNextAdvertisementModes(): Set<VideoMode> {
        val checked = checkedStandardModes.intersect(
            StandardVideoModes.catalog(maxVideoBitrateBps).toSet(),
        )
        val preferred = preferredMode
        return if (preferred == null) {
            checked
        } else {
            checked + preferred
        }
    }

    private fun resolveWfdAdvertise(
        owner: WfdOwner,
        broadcastName: String,
        modes: Set<VideoMode>,
    ): WfdAdvertiseCommand? {
        if (!advertisingEnabled || owner == WfdOwner.NONE) {
            return null
        }
        return WfdAdvertiseCommand(
            owner = owner,
            broadcastName = broadcastName,
            modes = modes,
        )
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
