package me.trinitrix.mirax.session

import android.util.Log

/**
 * Mirax product session facade: the single external test seam preserving
 * [handle] and [snapshot] while delegating domain responsibilities to
 * focused deep modules:
 * - [AdvertisingState]: WFD ownership and listen beacon lifecycle.
 * - [CapsAndProvisioning]: Video mode catalogs, bitrate cap, custom modes, and one-time wm size provisioning.
 * - [DisplayPreferences]: UI preferences (picture scale, bottom handle, floating ball, debug toggles).
 * - [ActiveConnectionState]: Ephemeral states of the currently running connection.
 * - [ConnectionDiagnosticsRepository]: Finished and active connection run logs and facts.
 */
class MiraxSession(
    initialSettings: SessionSettings = SessionSettings(),
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
        initialBottomHandleEnabled = initialSettings.bottomHandleEnabled,
        initialFloatingBallEnabled = initialSettings.floatingBallEnabled,
        initialPictureScale = initialSettings.pictureScale,
        initialTouchEnabled = initialSettings.touchEnabled,
        initialShowDebugMessages = initialSettings.showDebugMessages,
        initialCameraCutoutAffectsLayout = initialSettings.cameraCutoutAffectsLayout,
        initialBroadcastAutoStopMinutes = initialSettings.broadcastAutoStopMinutes,
        initialShowDebugOverlay = initialSettings.showDebugOverlay,
    )
    private var activeConnection = ActiveConnectionState()
    private val diagnostics = ConnectionDiagnosticsRepository()

    private var languagePreference: LanguagePreference = initialSettings.languagePreference
    private var displayNameOverride: String? = normalizeOverride(initialSettings.displayNameOverride)
    private var privilege: PrivilegeReport = PrivilegeReport()
    private var wifiEnabled: Boolean = true
    private var systemLocale: SystemLocaleReport = SystemLocaleReport()
    private var deviceName: String = ""
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
        privilege = report
        if (nextOwner == WfdOwner.NONE) {
            advertisingState.onOwnerNone()
            activeConnection = ActiveConnectionState()
            caps.provisioningReadRequested = false
            pendingEffects = pendingEffects.filterNot {
                it is SessionEffect.ReadPlainWmSizeForProvisioning
            }
        } else {
            maybeRequestProvisioningRead()
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
        if (!report.enabled && advertisingState.advertisingEnabled) {
            if (activeConnection.connected) {
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
                activeConnection.negotiating = true
                diagnostics.beginRun(action.remoteHost, configurationBackup())
            }
            is SessionAction.AppendConnectionLog -> diagnostics.appendLog(action.line)
            is SessionAction.FinishConnectionRun -> diagnostics.finishRun(action.succeeded, action.metadata)
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
                consumeProvisioning()
            }
            is SessionAction.CommitPreferredModeText -> {
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
                onSystemBack()
            }
            SessionAction.RequestEndConnection -> {
                requestEndConnectionFromUser()
            }
            SessionAction.EndConnection -> {
                endConnectionFromUser()
            }
            is SessionAction.SetBottomHandleEnabled -> {
                display.setBottomHandleEnabled(action.enabled)
                if (!action.enabled) {
                    activeConnection.bottomHandleExpanded = false
                }
            }
            SessionAction.ToggleBottomHandleExpanded -> {
                if (activeConnection.connected && display.bottomHandleEnabled) {
                    activeConnection.bottomHandleExpanded = !activeConnection.bottomHandleExpanded
                }
            }
            SessionAction.CollapseBottomHandleExpanded -> {
                if (activeConnection.connected && display.bottomHandleEnabled) {
                    activeConnection.bottomHandleExpanded = false
                }
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
                activeConnection.awayOnHomeScreen = false
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
        }
    }

    fun snapshot(): SessionSnapshot {
        val owner = resolveOwner(privilege)
        val phase = advertisingState.resolvePhase(
            owner = owner,
            connected = activeConnection.connected,
            wifiEnabled = wifiEnabled,
            negotiating = activeConnection.negotiating,
        )
        val followsDevice = displayNameOverride == null
        val effectiveName = if (followsDevice) deviceName else displayNameOverride.orEmpty()
        val canRead = owner != WfdOwner.NONE
        val nextModes = caps.resolveNextAdvertisementModes()
        val standardRows = StandardVideoModes.catalog(caps.maxVideoBitrateBps).map { mode ->
            StandardModeRow(mode = mode, checked = mode in caps.checkedStandardModes)
        }
        val resolutionText = when {
            activeConnection.connected && activeConnection.selectedMode != null -> activeConnection.selectedMode!!.format()
            else -> caps.preferredMode?.format().orEmpty()
        }
        val handleResolution = activeConnection.selectedMode?.let { "${it.width}×${it.height}" }.orEmpty()
        val handleRefresh = activeConnection.selectedMode?.refreshHz
        val showHandle = phase == ScreenPhase.CONNECTED && display.bottomHandleEnabled
        val showBall =
            phase == ScreenPhase.CONNECTED &&
                activeConnection.awayOnHomeScreen &&
                overlayGranted &&
                display.floatingBallEnabled
        return SessionSnapshot(
            phase = phase,
            wfdOwner = owner,
            tileState = resolveTile(owner, phase),
            widgetStatus = resolveWidget(owner, phase),
            advertisingEnabled = advertisingState.advertisingEnabled,
            shouldRequestShizukuPermission = pendingPermissionRequest,
            canReadWmSize = canRead,
            showCompatibilityNotice =
                phase == ScreenPhase.FROZEN || advertisingState.advertiseDeniedByPrivilege,
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
            nextAdvertisementModes = nextModes,
            currentResolutionText = resolutionText,
            miraxDisplayId = miraxDisplayId,
            maxVideoBitrateBps = caps.maxVideoBitrateBps,
            wfdAdvertise = resolveWfdAdvertise(owner, effectiveName, nextModes),
            selectedMode = activeConnection.selectedMode,
            showPicture = phase == ScreenPhase.CONNECTED,
            bottomHandleEnabled = display.bottomHandleEnabled,
            showBottomHandle = showHandle,
            bottomHandleExpanded = showHandle && activeConnection.bottomHandleExpanded,
            handleResolutionText = if (showHandle) handleResolution else "",
            handleRefreshRateHz = if (showHandle) handleRefresh else null,
            floatingBallEnabled = display.floatingBallEnabled,
            showOverlayPermissionReminder = !overlayGranted,
            showFloatingBall = showBall,
            pictureScale = display.pictureScale,
            connectionRuns = diagnostics.runs(),
            customModes = caps.customModes,
            autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect,
            touchEnabled = display.touchEnabled,
            showDebugMessages = display.showDebugMessages,
            showDebugOverlay = display.showDebugOverlay,
            cameraCutoutAffectsLayout = display.cameraCutoutAffectsLayout,
            broadcastAutoStopMinutes = display.broadcastAutoStopMinutes,
            handshakeLog = if (phase == ScreenPhase.CONNECTING && display.showDebugMessages) {
                diagnostics.currentHandshakeLog()
            } else {
                ""
            },
            connectionOfferModes = activeConnection.connectionAdvertisedModes,
            connectionPreferredMode = if (activeConnection.connectionOfferFrozen) activeConnection.connectionPreferredMode else null,
        )
    }

    fun exportSettings(): SessionSettings {
        return SessionSettings(
            advertisingEnabled = advertisingState.advertisingEnabled,
            languagePreference = languagePreference,
            displayNameOverride = displayNameOverride,
            preferredMode = caps.preferredMode,
            checkedStandardModes = caps.checkedStandardModes,
            maxVideoBitrateBps = caps.maxVideoBitrateBps,
            provisioningConsumed = caps.provisioningConsumed,
            bottomHandleEnabled = display.bottomHandleEnabled,
            floatingBallEnabled = display.floatingBallEnabled,
            pictureScale = display.pictureScale,
            customModes = caps.customModes,
            autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect,
            touchEnabled = display.touchEnabled,
            showDebugMessages = display.showDebugMessages,
            showDebugOverlay = display.showDebugOverlay,
            cameraCutoutAffectsLayout = display.cameraCutoutAffectsLayout,
            broadcastAutoStopMinutes = display.broadcastAutoStopMinutes,
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
        if (!activeConnection.connected) return
        if (overlayGranted && display.floatingBallEnabled) {
            activeConnection.awayOnHomeScreen = true
            return
        }
        endConnectionFromUser()
    }

    private fun onFloatingBallTapped() {
        if (!activeConnection.connected || !activeConnection.awayOnHomeScreen) return
        activeConnection.awayOnHomeScreen = false
        activeConnection.bottomHandleExpanded = false
        activeConnection.backEndsConnectionPending = false
        pendingEffects = pendingEffects.filterNot { it is SessionEffect.ConfirmEndConnection }
        enqueueEffect(SessionEffect.BringProjectionToFront)
    }

    private fun maybeEndHomeStayWithoutBall() {
        if (activeConnection.connected && activeConnection.awayOnHomeScreen && (!overlayGranted || !display.floatingBallEnabled)) {
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
        setAdvertising(!advertisingState.advertisingEnabled)
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

    private fun onSystemBack() {
        if (!activeConnection.connected) return
        when (activeConnection.onSystemBack(display.bottomHandleEnabled)) {
            ActiveConnectionState.BackResult.ExpandHandle -> Unit
            ActiveConnectionState.BackResult.RequestEnd -> requestEndConnectionFromUser()
            ActiveConnectionState.BackResult.ArmedSecondBack -> Unit
        }
    }

    private fun requestEndConnectionFromUser() {
        if (!activeConnection.connected) return
        if (SessionEffect.ConfirmEndConnection !in pendingEffects) {
            pendingEffects = pendingEffects + SessionEffect.ConfirmEndConnection
        }
    }

    private fun endConnectionFromUser() {
        if (!activeConnection.connected) return
        endConnectionState(emitDrop = true)
    }

    private fun endConnectionState(emitDrop: Boolean) {
        val wasConnected = activeConnection.connected
        activeConnection = ActiveConnectionState()
        if (emitDrop && wasConnected) {
            if (SessionEffect.DropActiveConnection !in pendingEffects) {
                pendingEffects = pendingEffects + SessionEffect.DropActiveConnection
            }
        }
    }

    private fun freezeConnectionOffer(reading: WmSizeReading?) {
        if (resolveOwner(privilege) == WfdOwner.NONE || !advertisingState.advertisingEnabled || !wifiEnabled || activeConnection.connected) {
            return
        }
        activeConnection.freezeConnectionOffer(
            reading = reading,
            baseModes = caps.resolveNextAdvertisementModes(),
            autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect,
            touchEnabled = display.touchEnabled,
            pictureRotationDegrees = pictureRotationDegrees,
            customModes = caps.customModes,
            savedPreferredMode = caps.preferredMode,
        )
    }

    private fun enterPlay() {
        if (resolveOwner(privilege) != WfdOwner.NONE && advertisingState.advertisingEnabled) {
            activeConnection.enterPlay()
            enqueueEffect(SessionEffect.BringProjectionToFront)
        }
    }

    private fun clearConnectionEphemerals() {
        activeConnection = ActiveConnectionState()
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
        if (!advertisingState.advertisingEnabled || !wifiEnabled || owner == WfdOwner.NONE) {
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
}
