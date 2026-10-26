package me.trinitrix.mirax.session

import android.util.Log

/**
 * Mirax product session facade: the single external test seam preserving
 * [handle] and [snapshot] while delegating domain responsibilities to
 * focused deep modules:
 * - [AdvertisingState]: WFD ownership and listen beacon lifecycle.
 * - [CapsAndProvisioning]: Video mode catalogs, bitrate cap, custom modes, and one-time wm size provisioning.
 * - [DisplayPreferences]: UI preferences (picture scale, control panel, floating ball, debug toggles).
 * - [ActiveConnectionState]: Ephemeral states of the currently running connection.
 * - [ConnectionDiagnosticsRepository]: Finished and active connection run logs and facts (in-memory).
 * - [ConnectionHistory]: Persisted connection history (DataStore, 500 runs).
 */
class MiraxSession(
    initialSettings: SessionSettings = SessionSettings(),
    private val connectionHistory: ConnectionHistory,
) {
    private val advertisingState = AdvertisingState(initialSettings.advertisingEnabled)
    private val caps = CapsAndProvisioning(
        initialPreferredMode = initialSettings.preferredMode,
        initialCheckedStandardModes = initialSettings.checkedStandardModes,
        initialMaxVideoBitrateBps = initialSettings.maxVideoBitrateBps,
        initialProvisioningConsumed = initialSettings.provisioningConsumed,
        initialCustomModes = initialSettings.customModes,
        initialAutoAddWmSizeOnConnect = initialSettings.autoAddWmSizeOnConnect,
    )
    private val display = DisplayPreferences(
        initialFloatingBallEnabled = initialSettings.floatingBallEnabled,
        initialPictureScale = initialSettings.pictureScale,
        initialTouchEnabled = initialSettings.touchEnabled,
        initialShowDebugMessages = initialSettings.showDebugMessages,
        initialCameraCutoutAffectsLayout = initialSettings.cameraCutoutAffectsLayout,
        initialBroadcastAutoStopMinutes = initialSettings.broadcastAutoStopMinutes,
        initialShowDebugOverlay = initialSettings.showDebugOverlay,
        initialControlPanelEnabled = initialSettings.controlPanelEnabled,
        initialControlPanelEntry = initialSettings.controlPanelEntry,
        initialOrientationLockEnabled = initialSettings.orientationLockEnabled,
    )
    private var activeConnection = ActiveConnectionState()
    private val diagnostics = ConnectionDiagnosticsRepository()

    private var languagePreference: LanguagePreference = initialSettings.languagePreference
    private var displayNameOverride: String? = normalizeOverride(initialSettings.displayNameOverride)
    private var privilege: PrivilegeReport = PrivilegeReport()
    private var wifiEnabled: Boolean = true
    private var systemLocale: SystemLocaleReport = SystemLocaleReport()
    private var deviceName: String = ""
    private var sourceDeviceName: String? = null
    /** Invalidates pending display calibration when the user edits the preferred mode. */
    internal var preferredModeGeneration: Long = 0L
        private set
    private var miraxDisplayId: Int = 0
    private var pictureRotationDegrees: Int = 0
    private var overlayGranted: Boolean = false
    private var overlayCanPrompt: Boolean = true
    private var permissionRequestedThisStay: Boolean = false
    private var pendingPermissionRequest: Boolean = false
    private var pendingEffects: List<SessionEffect> = emptyList()

    private companion object {
        private const val TAG = "MiraxSession"

        fun clampAutoStopMinutes(minutes: Int): Int = minutes.coerceIn(1, 180)

        private fun normalizeOverride(value: String?): String? {
            if (value == null) return null
            val sanitized = BroadcastNameRules.sanitize(value).trim()
            return sanitized.ifEmpty { null }
        }
    }

    fun report(report: PrivilegeReport) {
        val nextOwner = resolveOwner(report)
        val prevOwner = resolveOwner(privilege)
        privilege = report
        if (nextOwner == WfdOwner.NONE) {
            advertisingState.onOwnerNone()
            clearConnectionEphemerals()
            caps.provisioningReadRequested = false
            pendingEffects = pendingEffects.filterNot {
                it is SessionEffect.ReadPlainWmSizeForProvisioning
            }
        } else {
            maybeRequestProvisioningRead()
            // If owner changed from NONE to a valid owner and advertising is enabled,
            // we need to re-apply the advertising state with the new owner
            if (prevOwner == WfdOwner.NONE && advertisingState.advertisingEnabledState) {
                advertisingState.setAdvertising(true, nextOwner, wifiEnabled)
            }
        }
    }

    fun report(report: SystemLocaleReport) {
        systemLocale = report
    }

    fun report(report: DeviceNameReport) {
        deviceName = report.deviceName
    }

    fun report(report: WifiReport) {
        val wasEnabled = wifiEnabled
        wifiEnabled = report.enabled
        if (!report.enabled && advertisingState.advertisingEnabledState) {
            if (activeConnection.isConnected) {
                endConnectionState(emitDrop = true)
            } else if (wasEnabled) {
                clearConnectionEphemerals()
            }
        }
    }

    fun report(report: MiraxDisplayReport) {
        miraxDisplayId = report.displayId
    }

    fun report(report: PictureRotationReport) {
        pictureRotationDegrees = report.degrees
    }

    fun report(report: OverlayPermissionReport) {
        overlayGranted = report.granted
        overlayCanPrompt = report.canPrompt
        maybeEndHomeStayWithoutBall()
    }

    /** Enter a mock connected state with [mode] and a visible source [name]. */
    fun enterMockConnected(mode: VideoMode = VideoMode(1920, 1080, 60), name: String = "Mock Device") {
        activeConnection.enterMockConnected(mode)
        sourceDeviceName = name
    }

    fun handle(action: SessionAction) {
        when (action) {
            SessionAction.OpenApp -> onOpenApp()
            SessionAction.Retry, SessionAction.AutoWait -> {
                pendingPermissionRequest = false
                maybeRequestProvisioningRead()
            }
            is SessionAction.SetAdvertising -> setAdvertising(action.enabled)
            SessionAction.BeaconListening -> onBeaconListening()
            SessionAction.BeaconFailed -> onBeaconFailed()
            SessionAction.TileTap -> onTileTap()
            SessionAction.AcknowledgeEffects -> {
                pendingEffects = pendingEffects.filterIsInstance<
                    SessionEffect.ReadPlainWmSizeForProvisioning
                    >()
            }
            SessionAction.BecameDiscoverable -> {
                activeConnection.freezeConnectionAdvertisedModes(caps.resolveNextAdvertisementModes())
            }
            SessionAction.PrePlayProgress -> {
                activeConnection.onPrePlayProgress(caps.resolveNextAdvertisementModes())
            }
            is SessionAction.SourceSelectedMode -> {
                val mode = action.mode
                // Validate Level 5.1 before accepting; Windows may pick oversized modes.
                if (!H264Level51.fits(mode)) {
                    Log.w(TAG, "SourceSelectedMode ${mode.format()} exceeds Level 5.1, rejecting")
                } else {
                    activeConnection.onSourceSelectedMode(mode, caps.resolveNextAdvertisementModes())
                }
            }
            SessionAction.EnteredPlay, SessionAction.ConnectionEstablished -> {
                enterPlay()
            }
            SessionAction.ConnectionEnded -> {
                endConnectionState(emitDrop = false)
            }
            is SessionAction.BeginConnectionRun -> {
                sourceDeviceName = action.sourceDeviceName?.trim()?.ifEmpty { null }
                activeConnection.onPrePlayProgress(caps.resolveNextAdvertisementModes())
                diagnostics.beginRun(action.remoteHost, configurationBackup())
                connectionHistory.start(action.remoteHost, configurationBackup())
            }
            is SessionAction.AppendConnectionLog -> {
                diagnostics.appendLog(action.line)
                connectionHistory.log(action.line)
            }
            is SessionAction.FinishConnectionRun -> {
                diagnostics.finishRun(action.succeeded, action.metadata)
                connectionHistory.finish(action.succeeded, action.metadata)
            }
            SessionAction.PrePlayGroupDropped -> {
                endConnectionState(emitDrop = false)
            }
            is SessionAction.SetLanguagePreference -> {
                languagePreference = action.preference
            }
            is SessionAction.SetDisplayNameOverride -> {
                displayNameOverride = normalizeOverride(action.value)
            }
            SessionAction.PreferredModeFieldEdited -> {
                preferredModeGeneration++
                consumeProvisioning()
            }
            is SessionAction.CommitPreferredModeText -> {
                preferredModeGeneration++
                caps.commitPreferredModeText(action.text)
            }
            is SessionAction.SetStandardModeChecked -> {
                caps.setStandardModeChecked(action.mode, action.checked)
            }
            is SessionAction.SetStandardModesChecked -> {
                caps.setStandardModesChecked(action.modes, action.checked)
            }
            is SessionAction.UseThisScreen -> {
                if (resolveOwner(privilege) != WfdOwner.NONE) {
                    caps.useThisScreen(action.reading, pictureRotationDegrees)
                }
            }
            is SessionAction.ApplyProvisioningWmSize -> {
                applyProvisioningWmSize(action.reading)
            }
            SessionAction.SystemBack -> {
                activeConnection.onSystemBack()
            }
            SessionAction.RequestEndConnection -> {
                requestEndConnectionFromUser()
            }
            SessionAction.EndConnection -> {
                endConnectionFromUser()
            }
            SessionAction.CollapseControlPanel -> {
                if (activeConnection.isConnected) {
                    activeConnection.setControlPanelExpanded(false)
                }
            }
            is SessionAction.SetControlPanelEnabled -> {
                display.setControlPanelEnabled(action.enabled)
            }
            is SessionAction.SetControlPanelEntry -> {
                display.setControlPanelEntry(action.entry)
            }
            is SessionAction.SetFloatingBallEnabled -> {
                display.setFloatingBallEnabled(action.enabled)
                maybeEndHomeStayWithoutBall()
            }
            is SessionAction.SetShowDebugOverlay -> {
                display.setShowDebugOverlay(action.enabled)
            }
            SessionAction.RequestOverlayPermission -> {
                onRequestOverlayPermission()
            }
            SessionAction.LeftProjectionToHome -> {
                onLeftProjectionToHome()
            }
            SessionAction.OpenedMiraxDashboard -> {
                activeConnection.setAwayOnHomeScreen(false)
            }
            SessionAction.FloatingBallTapped -> {
                onFloatingBallTapped()
            }
            is SessionAction.SetPictureScale -> {
                display.setPictureScale(action.scale)
            }
            is SessionAction.AddCustomMode -> caps.addCustomMode(action.width, action.height, action.refreshHz)
            is SessionAction.RemoveCustomMode -> caps.removeCustomMode(action.mode)
            is SessionAction.MoveCustomMode -> caps.moveCustomMode(action.from, action.to)
            is SessionAction.SetAutoAddWmSizeOnConnect -> {
                caps.setAutoAddWmSizeOnConnect(action.enabled)
            }
            is SessionAction.SetTouchEnabled -> {
                display.setTouchEnabled(action.enabled)
            }
            is SessionAction.SetShowDebugMessages -> {
                display.setShowDebugMessages(action.enabled)
            }
            is SessionAction.SetCameraCutoutAffectsLayout -> {
                display.setCameraCutoutAffectsLayout(action.enabled)
            }
            is SessionAction.SetBroadcastAutoStopMinutes -> {
                display.setBroadcastAutoStopMinutes(action.minutes)
            }
            is SessionAction.SetMaxVideoBitrateBps -> {
                caps.setMaxVideoBitrateBps(action.bps)
            }
            is SessionAction.FreezeConnectionOffer -> freezeConnectionOffer(action.reading)
            SessionAction.ClearConnectionHistory -> {
                try {
                    connectionHistory.clear()
                } catch (err: Exception) {
                    Log.w(TAG, "Failed to clear connection history", err)
                }
            }
            SessionAction.ToggleControlPanel -> {
                activeConnection.setControlPanelExpanded(!activeConnection.controlPanelExpandedState)
            }
            is SessionAction.SetOrientationLock -> {
                display.setOrientationLockEnabled(action.enabled)
            }
            is SessionAction.SetSourceDeviceName -> {
                sourceDeviceName = action.name
            }
        }
    }

fun snapshot(): SessionSnapshot {
        val owner = resolveOwner(privilege)
        val phase = composeScreenPhase(
            owner = owner,
            connected = activeConnection.isConnected,
            wifiEnabled = wifiEnabled,
            advertisingPhase = advertisingState.phase,
            connectionPhase = activeConnection.phase,
        )
        val followsDevice = displayNameOverride == null
        val effectiveName = if (followsDevice) deviceName else displayNameOverride.orEmpty()
        val canRead = owner != WfdOwner.NONE
        val nextModes = caps.resolveNextAdvertisementModes()
        val standardRows = StandardVideoModes.catalog(caps.maxVideoBitrateBps).map { mode ->
            StandardModeRow(mode = mode, checked = mode in caps.checkedStandardModes)
        }
        val resolutionText = (if (activeConnection.isConnected) activeConnection.selectedMode else null)
            ?.format() ?: caps.preferredMode?.format().orEmpty()
        val handleResolution = activeConnection.selectedMode?.let { "${it.width}×${it.height}" }.orEmpty()
        val handleRefresh = activeConnection.selectedMode?.refreshHz
        val showBall =
            phase == ScreenPhase.CONNECTED &&
                activeConnection.awayOnHomeScreenState &&
                overlayGranted &&
                display.floatingBallEnabled
        return SessionSnapshot(
            phase = phase,
            wfdOwner = owner,
            tileState = resolveTile(owner, phase),
            widgetStatus = resolveWidget(owner, phase),
            advertisingEnabled = advertisingState.advertisingEnabledState,
            shouldRequestShizukuPermission = pendingPermissionRequest,
            canReadWmSize = canRead,
            showCompatibilityNotice =
                phase == ScreenPhase.FROZEN || advertisingState.isDeniedByPrivilege(),
            rootAvailable = privilege.rootAvailable,
            effects = pendingEffects,
            languagePreference = languagePreference,
            appLanguage = resolveAppLanguage(),
            effectiveBroadcastName = effectiveName,
            displayNameOverride = displayNameOverride,
            displayNameFollowsDevice = followsDevice,
            displayNameFieldHint = if (followsDevice) deviceName else "",
            preferredMode = caps.preferredMode,
            preferredModeText = caps.preferredMode?.format().orEmpty(),
            canUseThisScreen = canRead,
            standardModes = standardRows,
            microsoftModes = MicrosoftVideoModes.catalog().map { mode ->
                StandardModeRow(mode = mode, checked = mode in caps.checkedStandardModes)
            },
            nextAdvertisementModes = nextModes,
            currentResolutionText = resolutionText,
            miraxDisplayId = miraxDisplayId,
            maxVideoBitrateBps = caps.maxVideoBitrateBps,
            wfdAdvertise = resolveWfdAdvertise(owner, effectiveName, nextModes),
            selectedMode = activeConnection.selectedMode,
            showPicture = phase == ScreenPhase.CONNECTED,
            handleResolutionText = if (activeConnection.isConnected && activeConnection.selectedMode != null) handleResolution else "",
            handleRefreshRateHz = if (activeConnection.isConnected && activeConnection.selectedMode != null) handleRefresh else null,
            floatingBallEnabled = display.floatingBallEnabled,
            showOverlayPermissionReminder = !overlayGranted,
            showFloatingBall = showBall,
            pictureScale = display.pictureScale,
            connectionRuns = connectionHistory.runs(),
            customModes = caps.customModes,
            autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect,
            touchEnabled = display.touchEnabled,
            showDebugMessages = display.showDebugMessages,
            showDebugOverlay = display.showDebugOverlay,
            cameraCutoutAffectsLayout = display.cameraCutoutAffectsLayout,
            broadcastAutoStopMinutes = display.broadcastAutoStopMinutes,
            controlPanelExpanded = activeConnection.controlPanelExpandedState,
            controlPanelEntry = display.controlPanelEntry,
            orientationLockEnabled = display.orientationLockEnabled,
            sourceDeviceName = sourceDeviceName,
            handshakeLog = if (phase == ScreenPhase.CONNECTING && display.showDebugMessages) {
                diagnostics.currentHandshakeLog()
            } else {
                ""
            },
            connectionOfferModes = activeConnection.connectionAdvertisedModes,
            connectionPreferredMode = if (activeConnection.phase == ConnectionPhase.OFFER_FROZEN) activeConnection.connectionPreferredMode else null,
        )
    }

    fun exportSettings(): SessionSettings {
        return SessionSettings(
            advertisingEnabled = advertisingState.advertisingEnabledState,
            languagePreference = languagePreference,
            displayNameOverride = displayNameOverride,
            preferredMode = caps.preferredMode,
            checkedStandardModes = caps.checkedStandardModes,
            maxVideoBitrateBps = caps.maxVideoBitrateBps,
            provisioningConsumed = caps.provisioningConsumed,
            floatingBallEnabled = display.floatingBallEnabled,
            pictureScale = display.pictureScale,
            customModes = caps.customModes,
            autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect,
            touchEnabled = display.touchEnabled,
            showDebugMessages = display.showDebugMessages,
            showDebugOverlay = display.showDebugOverlay,
            cameraCutoutAffectsLayout = display.cameraCutoutAffectsLayout,
            broadcastAutoStopMinutes = display.broadcastAutoStopMinutes,
            controlPanelEnabled = display.controlPanelEnabled,
            controlPanelEntry = display.controlPanelEntry,
            orientationLockEnabled = display.orientationLockEnabled,
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

    private fun onRequestOverlayPermission() {
        if (overlayGranted) return
        if (overlayCanPrompt) {
            enqueueEffect(SessionEffect.RequestOverlayPermission)
        } else {
            enqueueEffect(SessionEffect.OpenOverlaySettings)
        }
    }

    private fun onLeftProjectionToHome() {
        if (!activeConnection.isConnected) return
        if (overlayGranted && display.floatingBallEnabled) {
            activeConnection.setAwayOnHomeScreen(true)
            return
        }
        endConnectionFromUser()
    }

    private fun onFloatingBallTapped() {
        if (!activeConnection.isConnected || !activeConnection.awayOnHomeScreenState) return
        activeConnection.setAwayOnHomeScreen(false)
        pendingEffects = pendingEffects.filterNot { it is SessionEffect.ConfirmEndConnection }
        enqueueEffect(SessionEffect.BringProjectionToFront)
    }

    private fun maybeEndHomeStayWithoutBall() {
        if (activeConnection.isConnected && activeConnection.awayOnHomeScreenState && (!overlayGranted || !display.floatingBallEnabled)) {
            endConnectionFromUser()
        }
    }

    private fun enqueueEffect(effect: SessionEffect) {
        if (effect !in pendingEffects) {
            pendingEffects = pendingEffects + effect
        }
    }

    private fun onTileTap() {
        val owner = resolveOwner(privilege)
        if (owner == WfdOwner.NONE) {
            pendingPermissionRequest = false
            pendingEffects = pendingEffects + SessionEffect.ShowShizukuNotOpenToast
            return
        }
        setAdvertising(!advertisingState.advertisingEnabledState)
    }

    private fun setAdvertising(enabled: Boolean) {
        val owner = resolveOwner(privilege)
        when (val res = advertisingState.setAdvertising(enabled, owner, wifiEnabled)) {
            AdvertisingState.SetAdvertisingResult.NoOwner -> Unit
            AdvertisingState.SetAdvertisingResult.WifiDisabled -> {
                pendingEffects = pendingEffects + SessionEffect.PromptEnableWifi
            }
            is AdvertisingState.SetAdvertisingResult.Applied -> {
                if (res.turnedOff) {
                    endConnectionState(emitDrop = true)
                }
            }
        }
    }

    private fun onBeaconListening() {
        advertisingState.onBeaconListening(resolveOwner(privilege))
    }

    private fun onBeaconFailed() {
        if (advertisingState.onBeaconFailed()) {
            endConnectionState(emitDrop = true)
            enqueueEffect(SessionEffect.ShowAdvertiseFailedToast)
        }
    }

    private fun requestEndConnectionFromUser() {
        if (!activeConnection.isConnected) return
        if (SessionEffect.ConfirmEndConnection !in pendingEffects) {
            pendingEffects = pendingEffects + SessionEffect.ConfirmEndConnection
        }
    }

    private fun endConnectionFromUser() {
        if (!activeConnection.isConnected) return
        endConnectionState(emitDrop = true)
    }

    private fun endConnectionState(emitDrop: Boolean) {
        val wasConnected = activeConnection.isConnected
        clearConnectionEphemerals()
        if (emitDrop && wasConnected) {
            if (SessionEffect.DropActiveConnection !in pendingEffects) {
                pendingEffects = pendingEffects + SessionEffect.DropActiveConnection
            }
        }
    }

    private fun freezeConnectionOffer(reading: WmSizeReading?) {
        if (resolveOwner(privilege) == WfdOwner.NONE || !advertisingState.advertisingEnabledState || !wifiEnabled) {
            return
        }
        val savedModes = caps.resolveNextAdvertisementModes()
        val injectedMode = if (caps.autoAddWmSizeOnConnect) {
            modeFromWmSize(reading, display.touchEnabled, pictureRotationDegrees)
                ?.takeIf { !MicrosoftVideoModes.contains(it) || it in savedModes }
        } else {
            null
        }
        val finalOfferedModes = if (caps.autoAddWmSizeOnConnect && injectedMode != null) {
            savedModes + injectedMode
        } else {
            savedModes
        }
        val preferredMode = injectedMode ?: caps.customModes.firstOrNull() ?: caps.preferredMode
        activeConnection.freezeOffer(
            offeredModes = finalOfferedModes,
            preferredMode = preferredMode,
            touchEnabled = display.touchEnabled,
            rotationDegrees = pictureRotationDegrees,
        )
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

    private fun enterPlay() {
        if (resolveOwner(privilege) != WfdOwner.NONE && advertisingState.advertisingEnabledState) {
            activeConnection.enterPlay()
            activeConnection.onPlayStarted()
            enqueueEffect(SessionEffect.BringProjectionToFront)
        }
    }

    private fun clearConnectionEphemerals() {
        activeConnection = ActiveConnectionState()
        sourceDeviceName = null
    }

    private fun applyProvisioningWmSize(reading: WmSizeReading) {
        if (resolveOwner(privilege) == WfdOwner.NONE) return
        if (caps.applyProvisioningWmSize(reading, pictureRotationDegrees)) {
            pendingEffects = pendingEffects.filterNot {
                it is SessionEffect.ReadPlainWmSizeForProvisioning
            }
        }
    }

    private fun maybeRequestProvisioningRead() {
        if (caps.provisioningConsumed || caps.preferredMode != null) return
        if (resolveOwner(privilege) == WfdOwner.NONE) return
        if (caps.provisioningReadRequested) return
        if (SessionEffect.ReadPlainWmSizeForProvisioning in pendingEffects) return
        caps.provisioningReadRequested = true
        pendingEffects = pendingEffects + SessionEffect.ReadPlainWmSizeForProvisioning
    }

    private fun consumeProvisioning() {
        caps.consumeProvisioning()
        pendingEffects = pendingEffects.filterNot {
            it is SessionEffect.ReadPlainWmSizeForProvisioning
        }
    }

    private fun resolveWfdAdvertise(
        owner: WfdOwner,
        broadcastName: String,
        modes: Set<VideoMode>,
    ): WfdAdvertiseCommand? {
        if (!advertisingState.advertisingEnabledState || !wifiEnabled || owner == WfdOwner.NONE) {
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

    private fun resolveTile(owner: WfdOwner, phase: ScreenPhase): TileState {
        return when {
            owner == WfdOwner.NONE -> TileState.GRAY
            phase == ScreenPhase.CONNECTED -> TileState.CONNECTED
            phase == ScreenPhase.ARMING -> TileState.ARMING
            phase == ScreenPhase.ADVERTISING ||
                phase == ScreenPhase.CONNECTING ||
                phase == ScreenPhase.WIFI_PAUSED -> TileState.ADVERTISING
            else -> TileState.OFF
        }
    }

    private fun resolveWidget(owner: WfdOwner, phase: ScreenPhase): WidgetStatus {
        return when {
            owner == WfdOwner.NONE -> WidgetStatus.UNAVAILABLE
            phase == ScreenPhase.CONNECTED -> WidgetStatus.CONNECTED
            phase == ScreenPhase.ADVERTISING ||
                phase == ScreenPhase.ARMING ||
                phase == ScreenPhase.CONNECTING ||
                phase == ScreenPhase.WIFI_PAUSED -> WidgetStatus.ADVERTISING
            else -> WidgetStatus.OFF
        }
    }

    private fun configurationBackup(): List<ConnectionRunFact> {
        val name = if (displayNameOverride == null) deviceName else displayNameOverride.orEmpty()
        val standards = caps.checkedStandardModes
            .sortedWith(compareBy({ it.width * it.height }, { it.refreshHz }))
            .joinToString(", ") { it.format() }
            .ifEmpty { "none" }
        val customs = CapsAndProvisioning.distinctModes(caps.customModes + listOfNotNull(caps.preferredMode))
            .joinToString(", ") { it.format() }
            .ifEmpty { "none" }
        return listOf(
            ConnectionRunFact("broadcast name", name.ifEmpty { "none" }),
            ConnectionRunFact("custom resolutions", customs),
            ConnectionRunFact("standard modes", standards),
            ConnectionRunFact("touch", if (display.touchEnabled) "on" else "off"),
        )
    }

    private fun composeScreenPhase(
        owner: WfdOwner,
        connected: Boolean,
        wifiEnabled: Boolean,
        advertisingPhase: AdvertisingPhase,
        connectionPhase: ConnectionPhase,
    ): ScreenPhase {
        if (connected) {
            return ScreenPhase.CONNECTED
        }
        if (owner == WfdOwner.NONE) {
            return ScreenPhase.FROZEN
        }
        if (advertisingPhase == AdvertisingPhase.IDLE) {
            return ScreenPhase.READY
        }
        if (!wifiEnabled && advertisingPhase == AdvertisingPhase.ADVERTISING) {
            return ScreenPhase.WIFI_PAUSED
        }
        // Negotiating or offer frozen = connecting
        if (connectionPhase == ConnectionPhase.NEGOTIATING 
            || connectionPhase == ConnectionPhase.OFFER_FROZEN) {
            return ScreenPhase.CONNECTING
        }
        if (advertisingPhase == AdvertisingPhase.BEACON_LISTENING) {
            return ScreenPhase.ADVERTISING
        }
        if (advertisingPhase == AdvertisingPhase.DENIED) {
            return ScreenPhase.READY
        }
        // Default advertising states
        return when {
            advertisingPhase == AdvertisingPhase.ADVERTISING -> ScreenPhase.ARMING
            advertisingPhase == AdvertisingPhase.BEACON_LISTENING -> ScreenPhase.ADVERTISING
            advertisingPhase == AdvertisingPhase.DENIED -> ScreenPhase.FROZEN
            else -> ScreenPhase.READY
        }
    }
}
