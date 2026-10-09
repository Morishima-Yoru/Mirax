package me.trinitrix.mirax.session

/**
 * Screen phase exposed by the Mirax session.
 */
enum class ScreenPhase {
    FROZEN,
    READY,
    /**
     * User asked to broadcast, but the privileged owner has not confirmed
     * `startListening` yet. Must not look like a live Win+K target.
     */
    ARMING,
    ADVERTISING,
    /** P2P group is up and RTSP has not reached PLAY. Tile stays advertising. */
    CONNECTING,
    CONNECTED,
    /**
     * User left broadcast on but Wi-Fi is off; [SessionSnapshot.advertisingEnabled]
     * stays true until they stop or Wi-Fi returns.
     */
    WIFI_PAUSED,
}

/**
 * Who currently owns WFD advertise privileges for Mirax.
 */
enum class WfdOwner {
    NONE,
    /**
     * Privileged helper socket (rooted `su` `app_process`, or any process that
     * already listens on the helper socket).
     */
    HELPER,
    /** Samsung / OEM shell path via authorized Shizuku. */
    SHIZUKU,
}

/**
 * Quick Settings tile appearance derived from the session.
 */
enum class TileState {
    GRAY,
    OFF,
    /** Advertise requested; listen not confirmed yet. */
    ARMING,
    ADVERTISING,
    CONNECTED,
}

/**
 * Home-screen widget status derived from the session.
 */
enum class WidgetStatus {
    UNAVAILABLE,
    OFF,
    ADVERTISING,
    CONNECTED,
}

/**
 * How the picture sits on the panel. Persisted; default [PROPORTIONAL].
 * Does not change the next advertisement set.
 */
enum class PictureScale {
    /** Whole picture visible, aspect unchanged, centered (等比). */
    PROPORTIONAL,
    /** Enlarged until both panel axes are met, aspect unchanged, center-aligned (鋪滿). */
    CENTER_CROP,
    /** All four edges meet the panel; aspect not kept (拉伸). */
    MATCH_EDGES,
    /** One picture pixel per panel pixel, top-left aligned (原寸). */
    ACTUAL,
}

/**
 * User language choice persisted across stays.
 */
enum class LanguagePreference {
    FOLLOW_SYSTEM,
    TRADITIONAL_CHINESE,
    ENGLISH,
}

/**
 * Resolved UI language the host must apply.
 *
 * Follow-system maps Traditional Chinese systems to [TRADITIONAL_CHINESE]
 * and every other system language (including Simplified Chinese) to [ENGLISH].
 */
enum class AppLanguage {
    TRADITIONAL_CHINESE,
    ENGLISH,
}

/**
 * Privilege path report fed into the session by the environment.
 *
 * Args:
 *     shizukuServiceRunning: Whether the Shizuku service process is up.
 *     shizukuAuthorized: Whether Mirax is authorized for Shizuku.
 *     helperRunning: Whether the privileged helper socket is up (root-started
 *     or any other process listening on the helper socket).
 *     rootAvailable: Whether `su` can run as UID 0.
 */
data class PrivilegeReport(
    val shizukuServiceRunning: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val helperRunning: Boolean = false,
    val rootAvailable: Boolean = false,
)

/**
 * System locale classification fed by the host (not read from Android inside tests).
 *
 * Args:
 *     isTraditionalChinese: True when the primary system locale is Traditional Chinese.
 */
data class SystemLocaleReport(
    val isTraditionalChinese: Boolean = false,
)

/**
 * Current phone device name fed by the host. Mirax never writes this value back.
 *
 * Args:
 *     deviceName: Name observed from the environment (e.g. Settings.Global.DEVICE_NAME).
 */
data class DeviceNameReport(
    val deviceName: String = "",
)

/**
 * Whether the device Wi-Fi radio is on. Fed by the host; tests supply literals.
 */
data class WifiReport(
    val enabled: Boolean = true,
)

/**
 * One video mode: width, height, and refresh in hertz.
 *
 * Equality is exact; the advertisement set deduplicates identical modes.
 */
data class VideoMode(
    val width: Int,
    val height: Int,
    val refreshHz: Int,
) {
    /**
     * Professional display form used in the preferred-mode field and the display card.
     */
    fun format(): String = "${width}×${height}@${refreshHz}"
}

/**
 * Parsed `wm size` query result. Override size wins when present.
 * Visible-picture axis swap for rotation is applied by the session, not here.
 */
data class WmSizeReading(
    val physicalWidth: Int,
    val physicalHeight: Int,
    val overrideWidth: Int? = null,
    val overrideHeight: Int? = null,
) {
    /** Width taken from Override size when present, otherwise Physical size. */
    val chosenWidth: Int
        get() = overrideWidth ?: physicalWidth

    /** Height taken from Override size when present, otherwise Physical size. */
    val chosenHeight: Int
        get() = overrideHeight ?: physicalHeight
}

/**
 * Display id of the window hosting Mirax, fed by the host (tests supply literals).
 */
data class MiraxDisplayReport(
    val displayId: Int = 0,
)

/**
 * Current rotation of the picture the user sees, in degrees.
 *
 * Args:
 *     degrees: One of 0, 90, 180, or 270. Views forward the host observation;
 *         tests supply literals.
 */
data class PictureRotationReport(
    val degrees: Int = 0,
)

/**
 * One row in the standard-mode checklist.
 */
data class StandardModeRow(
    val mode: VideoMode,
    val checked: Boolean,
)

/**
 * One labeled fact stored with a connection run. Labels and values are English.
 *
 * Args:
 *     label: Short English name.
 *     value: English value captured with the run.
 */
data class ConnectionRunFact(
    val label: String,
    val value: String,
)

/**
 * One finished connection attempt: outcome, time, source, the settings in
 * force when it started, and the English diagnostic log.
 *
 * Args:
 *     succeeded: True when playback started.
 *     startedAtEpochMs: Wall clock when the attempt began.
 *     endedAtEpochMs: Wall clock when the attempt closed.
 *     remoteHost: Source address, or blank when unknown.
 *     metadata: Facts known at the end.
 *     configuration: Settings snapshotted at the start.
 *     log: English lines joined by newlines.
 */
data class ConnectionRun(
    val succeeded: Boolean,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val remoteHost: String,
    val metadata: List<ConnectionRunFact>,
    val configuration: List<ConnectionRunFact>,
    val log: String,
)

/**
 * Overlay ("display over other apps") observation fed by the host.
 *
 * Args:
 *     granted: Whether [Settings.canDrawOverlays] is true for Mirax.
 *     canPrompt: Whether the host can still present the manage-overlay UI.
 *         When false, the reminder button opens the system overlay settings page.
 */
data class OverlayPermissionReport(
    val granted: Boolean = false,
    val canPrompt: Boolean = true,
)

/**
 * Persisted settings the session reads and updates.
 *
 * Args:
 *     advertisingEnabled: Whether the user wants WFD advertising on.
 *     languagePreference: Follow-system, pin Traditional Chinese, or pin English.
 *     displayNameOverride: Non-blank custom broadcast name, or null to follow the device name.
 *     preferredMode: Accepted preferred mode, or null when none.
 *     checkedStandardModes: Standard modes the user wants in the next advertisement set.
 *     maxVideoBitrateBps: Cap that filters the standard-mode list and is
 *         advertised as `microsoft_max_bitrate` on the next RTSP connection.
 *     provisioningConsumed: Whether the one-time preferred-mode provisioning chance is gone.
 *     bottomHandleEnabled: Whether the picture bottom handle is shown while connected.
 *     floatingBallEnabled: Whether the floating ball may appear when leaving to home.
 *     pictureScale: How the picture sits on the panel (等比 / 鋪滿 / 拉伸 / 原寸).
 *     customModes: Ordered custom resolutions. First is preferred when auto wm size is off.
 *     autoAddWmSizeOnConnect: When true, the connection offer prefers the current wm size.
 *     touchEnabled: When true, M3 advertises HIDC. When false, the picture is display-only.
 *     showDebugMessages: When true, the connecting dashboard shows the live handshake
 *         log. Recording into the connection run is unchanged either way.
 *     cameraCutoutAffectsLayout: When true, dashboard padding also clears the display
 *         cutout. When false (default), only system bars inset the layout.
 */
data class SessionSettings(
    val advertisingEnabled: Boolean = false,
    val languagePreference: LanguagePreference = LanguagePreference.FOLLOW_SYSTEM,
    val displayNameOverride: String? = null,
    val preferredMode: VideoMode? = null,
    val checkedStandardModes: Set<VideoMode> = StandardVideoModes.DEFAULT_CHECKED,
    val maxVideoBitrateBps: Long = StandardVideoModes.BITRATE_CAP_BPS,
    val provisioningConsumed: Boolean = false,
    val bottomHandleEnabled: Boolean = true,
    val floatingBallEnabled: Boolean = true,
    val pictureScale: PictureScale = PictureScale.PROPORTIONAL,
    val customModes: List<VideoMode> = emptyList(),
    val autoAddWmSizeOnConnect: Boolean = true,
    val touchEnabled: Boolean = true,
    val showDebugMessages: Boolean = true,
    val cameraCutoutAffectsLayout: Boolean = false,
    /** Minutes of advertising before it turns itself off. Default one minute. */
    val broadcastAutoStopMinutes: Int = 1,
    /** Show debug overlay with stream info, frame tree, and crop controls. */
    val showDebugOverlay: Boolean = false,
)

/**
 * One-shot effects the host must perform; the session never starts Shizuku or the helper.
 */
sealed interface SessionEffect {
    /**
     * Show the shared "Shizuku is not open" toast after a gray tile probe failed.
     * Host must not open the authorization dialog for this effect.
     */
    data object ShowShizukuNotOpenToast : SessionEffect

    /**
     * Advertise arm failed: privileged owner did not reach listening.
     * Host shows a short toast; the session already cleared the switch.
     */
    data object ShowAdvertiseFailedToast : SessionEffect

    /**
     * Read plain `wm size` (no display id) for one-time preferred-mode provisioning.
     * Host feeds the result with [SessionAction.ApplyProvisioningWmSize].
     */
    data object ReadPlainWmSizeForProvisioning : SessionEffect

    /**
     * Ask the host to confirm ending this connection (picture panel or second Back).
     */
    data object ConfirmEndConnection : SessionEffect

    /**
     * Toast after the first system Back while connected: one more Back ends the connection.
     * Unused when the picture status panel is enabled; kept for tests that assert legacy
     * handle-off sequencing without a panel.
     */
    data object ShowPressBackAgainToEndToast : SessionEffect

    /**
     * Drop the active Miracast session without turning advertising off.
     * Host closes the current RTSP client; the listen beacon stays up.
     */
    data object DropActiveConnection : SessionEffect

    /**
     * Ask the host to present the package-scoped manage-overlay UI.
     * Used on first open and when the reminder button can still prompt.
     */
    data object RequestOverlayPermission : SessionEffect

    /**
     * Open the system "display over other apps" settings when the manage UI
     * will no longer be shown.
     */
    data object OpenOverlaySettings : SessionEffect

    /**
     * Bring the fullscreen projection activity to the front (floating-ball tap).
     */
    data object BringProjectionToFront : SessionEffect

    /**
     * Wi-Fi is off while the user tried to turn broadcast on; show enable Wi-Fi UI.
     */
    data object PromptEnableWifi : SessionEffect
}

/**
 * Desired Primary Sink advertise command for the privileged WFD owner.
 *
 * Null on [SessionSnapshot.wfdAdvertise] means the phone must not be a
 * connectable sink. The app process never applies this; the owner
 * ([WfdOwner.SHIZUKU] or [WfdOwner.HELPER]) does.
 *
 * [modes] is the same set as [SessionSnapshot.nextAdvertisementModes] for
 * later RTSP; the listen beacon itself only needs Primary Sink identity.
 */
data class WfdAdvertiseCommand(
    val owner: WfdOwner,
    val broadcastName: String,
    val modes: Set<VideoMode>,
)

/**
 * Observable session output for Activities and status surfaces.
 *
 * [effectiveBroadcastName] is the single name Wi-Fi Direct and the RTSP friendly
 * name must use. When [displayNameFollowsDevice] is true, [displayNameFieldHint]
 * shows the current device name in gray and is not a saved override.
 *
 * [nextAdvertisementModes] is the set for the next advertise — not a priority order.
 * The resolution UI only renders these outputs and forwards edit, leave-field, and
 * use-this-screen actions.
 *
 * [wfdAdvertise] is the continuous desired state for the privileged owner:
 * non-null when broadcast is on and a WFD owner exists.
 *
 * [selectedMode] is the mode the source chose for the current connection when
 * it belongs to the advertised set for that connection; null otherwise.
 * [showPicture] is true only in the connected (PLAY) phase so the host can
 * present the fullscreen picture surface. [pictureScale] is how that picture
 * sits on the panel; placement uses [PicturePlacement] with negotiated picture
 * axes, not a padded decoder buffer.
 *
 * [showBottomHandle] is true only while connected and the bottom-handle setting
 * is on. [bottomHandleExpanded] is whether the handle panel is open.
 * [handleResolutionText] and [handleRefreshRateHz] are the handle content for
 * the current selected mode (empty / null when the handle is not shown).
 *
 * [showOverlayPermissionReminder] is true only when overlay permission is
 * missing — never when permission is granted and the user merely turned the
 * floating ball off. [showFloatingBall] is true only while connected, away on
 * the phone home screen, overlay granted, and the floating-ball switch on.
 */
data class SessionSnapshot(
    val phase: ScreenPhase,
    val wfdOwner: WfdOwner,
    val tileState: TileState,
    val widgetStatus: WidgetStatus,
    val advertisingEnabled: Boolean,
    val shouldRequestShizukuPermission: Boolean,
    val canReadWmSize: Boolean,
    /**
     * True after a privilege owner failed to arm listening, or while frozen
     * with no owner — the host should surface the Compatibility page.
     */
    val showCompatibilityNotice: Boolean = false,
    val rootAvailable: Boolean = false,
    val effects: List<SessionEffect> = emptyList(),
    val languagePreference: LanguagePreference = LanguagePreference.FOLLOW_SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val effectiveBroadcastName: String = "",
    val displayNameOverride: String? = null,
    val displayNameFollowsDevice: Boolean = true,
    val displayNameFieldHint: String = "",
    val preferredMode: VideoMode? = null,
    val preferredModeText: String = "",
    val canUseThisScreen: Boolean = false,
    val standardModes: List<StandardModeRow> = emptyList(),
    val nextAdvertisementModes: Set<VideoMode> = emptySet(),
    val currentResolutionText: String = "",
    val miraxDisplayId: Int = 0,
    val maxVideoBitrateBps: Long = StandardVideoModes.BITRATE_CAP_BPS,
    val wfdAdvertise: WfdAdvertiseCommand? = null,
    val selectedMode: VideoMode? = null,
    val showPicture: Boolean = false,
    val bottomHandleEnabled: Boolean = true,
    val showBottomHandle: Boolean = false,
    val bottomHandleExpanded: Boolean = false,
    val handleResolutionText: String = "",
    val handleRefreshRateHz: Int? = null,
    val floatingBallEnabled: Boolean = true,
    val showOverlayPermissionReminder: Boolean = false,
    val showFloatingBall: Boolean = false,
    val pictureScale: PictureScale = PictureScale.PROPORTIONAL,
    /**
     * Finished connection runs, newest first. The open run is omitted until
     * it is closed.
     */
    val connectionRuns: List<ConnectionRun> = emptyList(),
    /** Ordered custom resolutions shown on the picture page. */
    val customModes: List<VideoMode> = emptyList(),
    val autoAddWmSizeOnConnect: Boolean = true,
    val touchEnabled: Boolean = true,
    val showDebugMessages: Boolean = true,
    /**
     * When true, dashboard padding clears the punch-hole / notch. Default false
     * so the cutout does not shift the layout.
     */
    val cameraCutoutAffectsLayout: Boolean = false,
    /** Minutes until an active broadcast turns itself off. */
    val broadcastAutoStopMinutes: Int = 1,
    /** Show debug overlay with stream info, frame tree, and crop controls. */
    val showDebugOverlay: Boolean = false,
    /**
     * English handshake lines for the open attempt. Empty unless the phase
     * is [ScreenPhase.CONNECTING] and [showDebugMessages] is true. The underlying
     * connection run still records every line while the switch is off.
     */
    val handshakeLog: String = "",
    /**
     * Modes frozen for the current connection's M3 offer, or null before the
     * offer is frozen. May include a wm-size mode that is not in the saved set.
     */
    val connectionOfferModes: Set<VideoMode>? = null,
    /** Preferred mode for this connection's M3, or null before the offer is frozen. */
    val connectionPreferredMode: VideoMode? = null,
)

/**
 * User actions and stay lifecycle events forwarded by the UI.
 */
sealed interface SessionAction {
    /** App process opened the Mirax UI for this stay. */
    data object OpenApp : SessionAction

    /** User tapped retry on the frozen waiting screen. */
    data object Retry : SessionAction

    /** Automatic privilege re-check while waiting. */
    data object AutoWait : SessionAction

    /** User toggled the advertising switch. */
    data class SetAdvertising(val enabled: Boolean) : SessionAction

    /**
     * Privileged owner confirmed Wi-Fi Direct listening for the current
     * advertise request. Until this arrives, [ScreenPhase] stays [ScreenPhase.ARMING]
     * even when [SessionSnapshot.advertisingEnabled] is true.
     */
    data object BeaconListening : SessionAction

    /**
     * Privileged owner failed to arm the Primary Sink beacon. Clears the
     * advertising switch and emits [SessionEffect.ShowAdvertiseFailedToast].
     */
    data object BeaconFailed : SessionAction

    /**
     * Quick Settings tile click after the host refreshed [PrivilegeReport].
     *
     * With a WFD owner, toggles advertising. With no owner (gray tile), emits
     * [SessionEffect.ShowShizukuNotOpenToast] and does not request authorization.
     * A gray-tile probe that finds Shizuku ready is handled by the host via
     * [SetAdvertising] `(true)` after the privilege report, not by this action alone.
     */
    data object TileTap : SessionAction

    /** Host consumed one-shot effects. */
    data object AcknowledgeEffects : SessionAction

    /**
     * Sink became discoverable (WFD listening / RTSP accept ready).
     * Does not change the next advertisement set.
     */
    data object BecameDiscoverable : SessionAction

    /**
     * Progress toward PLAY (P2P group up, RTSP negotiating).
     *
     * Dashboard phase becomes [ScreenPhase.CONNECTING]. The tile and widget
     * stay advertising because the beacon is still up.
     */
    data object PrePlayProgress : SessionAction

    /**
     * Source selected a video mode during RTSP. Accepted only when the mode
     * belongs to the advertisement set for this connection.
     */
    data class SourceSelectedMode(val mode: VideoMode) : SessionAction

    /** Connection reached PLAY. */
    data object EnteredPlay : SessionAction

    /**
     * Alias for [EnteredPlay] kept for earlier hosts and tests.
     */
    data object ConnectionEstablished : SessionAction

    /** Current connection ended. Broadcast stays on when the user left it on. */
    data object ConnectionEnded : SessionAction

    /**
     * Start one connection run and snapshot the settings in force now.
     *
     * Args:
     *     remoteHost: Source address for this attempt. Blank when it is not known yet.
     */
    data class BeginConnectionRun(val remoteHost: String) : SessionAction

    /**
     * Append one English line to the open connection run.
     *
     * Args:
     *     line: English diagnostic text. Ignored when no run is open.
     */
    data class AppendConnectionLog(val line: String) : SessionAction

    /**
     * Close the open connection run.
     *
     * Args:
     *     succeeded: True only when playback started.
     *     metadata: English facts known at the end, such as the selected mode.
     */
    data class FinishConnectionRun(
        val succeeded: Boolean,
        val metadata: List<ConnectionRunFact> = emptyList(),
    ) : SessionAction

    /**
     * Wi-Fi Direct group dropped before PLAY. Must not change the next advertisement set
     * and must not latch extra fallback modes.
     */
    data object PrePlayGroupDropped : SessionAction

    /** User chose follow-system, Traditional Chinese, or English. */
    data class SetLanguagePreference(val preference: LanguagePreference) : SessionAction

    /**
     * User saved a display-name field value.
     *
     * Blank or whitespace-only clears the override and resumes following the device name.
     */
    data class SetDisplayNameOverride(val value: String) : SessionAction

    /**
     * User edited the preferred-mode text field (any change from the install-default empty).
     * Consumes one-time provisioning without writing a preferred mode.
     */
    data object PreferredModeFieldEdited : SessionAction

    /**
     * User left the preferred-mode field. Commits parse/correction, or restores the last
     * accepted mode when the text is unparseable. Blank clears the preferred mode.
     */
    data class CommitPreferredModeText(val text: String) : SessionAction

    /**
     * Toggle a standard mode in the checklist. Does not consume provisioning.
     */
    data class SetStandardModeChecked(val mode: VideoMode, val checked: Boolean) : SessionAction

    /**
     * Toggle several catalog modes at once. Unknown modes are ignored.
     * Does not consume provisioning.
     */
    data class SetStandardModesChecked(
        val modes: Collection<VideoMode>,
        val checked: Boolean,
    ) : SessionAction

    /**
     * "Use this screen" after the host read `wm size` for the Mirax display.
     * Unavailable while frozen; host must not call this when [SessionSnapshot.canUseThisScreen]
     * is false.
     */
    data class UseThisScreen(val reading: WmSizeReading) : SessionAction

    /**
     * Host completed the one-time provisioning `wm size` read (plain, no display id).
     */
    data class ApplyProvisioningWmSize(val reading: WmSizeReading) : SessionAction

    /**
     * System Back on the picture. With the status panel on, the first press opens it;
     * the second asks to confirm ending this connection. With the panel off, the first
     * press arms the second; the second asks to confirm. Broadcast stays on unless
     * the user confirms end.
     */
    data object SystemBack : SessionAction

    /**
     * User chose to end this connection from the status panel, floating ball, or
     * equivalent control. Host shows a confirmation dialog before [EndConnection].
     */
    data object RequestEndConnection : SessionAction

    /**
     * User confirmed ending this connection. Broadcast stays on when the user left it on.
     */
    data object EndConnection : SessionAction

    /**
     * User toggled the bottom-handle setting. Persisted; defaults on.
     */
    data class SetBottomHandleEnabled(val enabled: Boolean) : SessionAction

    /**
     * User tapped the thin bottom-handle control to expand or collapse its panel.
     * Only meaningful while [SessionSnapshot.showBottomHandle] is true.
     */
    data object ToggleBottomHandleExpanded : SessionAction

    /**
     * User dismissed the picture status panel (tap outside the panel).
     */
    data object CollapseBottomHandleExpanded : SessionAction

    /**
     * User toggled the floating-ball setting. Persisted; defaults on.
     */
    data class SetFloatingBallEnabled(val enabled: Boolean) : SessionAction

    /**
     * Dashboard reminder button: ask again for overlay permission, or open
     * system overlay settings when the host can no longer prompt.
     */
    data object RequestOverlayPermission : SessionAction

    /**
     * User left projection for the phone home screen (or another non-Mirax app)
     * while a connection may still be active.
     */
    data object LeftProjectionToHome : SessionAction

    /**
     * User opened Mirax's own dashboard. Not "going home"; connection continues.
     */
    data object OpenedMiraxDashboard : SessionAction

    /**
     * User tapped the floating ball to return to projection.
     */
    data object FloatingBallTapped : SessionAction

    /**
     * User chose how the picture sits on the panel. Persisted; does not change
     * the next advertisement set.
     */
    data class SetPictureScale(val scale: PictureScale) : SessionAction

    /** Append one corrected custom resolution. Duplicates are ignored. */
    data class AddCustomMode(val width: Int, val height: Int, val refreshHz: Int) : SessionAction

    /** Remove one custom resolution. Also clears a matching legacy preferred mode. */
    data class RemoveCustomMode(val mode: VideoMode) : SessionAction

    /**
     * Move a custom resolution from index [from] to index [to].
     * Out-of-range indexes are ignored.
     */
    data class MoveCustomMode(val from: Int, val to: Int) : SessionAction

    /**
     * When true, the next connection offer prefers the wm size read at that moment.
     * Does not rewrite the saved custom list.
     */
    data class SetAutoAddWmSizeOnConnect(val enabled: Boolean) : SessionAction

    /** User allowed or disallowed general touch for the next connection. */
    data class SetTouchEnabled(val enabled: Boolean) : SessionAction

    /** User asked the connecting dashboard to show or hide the live handshake log. */
    data class SetShowDebugMessages(val enabled: Boolean) : SessionAction

    /** User toggled the debug overlay with stream info, frame tree, and crop controls. */
    data class SetShowDebugOverlay(val enabled: Boolean) : SessionAction

    /** User asked whether dashboard padding should clear the camera cutout. */
    data class SetCameraCutoutAffectsLayout(val enabled: Boolean) : SessionAction

    /** Minutes until an active broadcast turns itself off. Clamped to 1–180. */
    data class SetBroadcastAutoStopMinutes(val minutes: Int) : SessionAction

    /**
     * Cap Windows may treat as the sink's max encode bitrate.
     * Clamped to [StandardVideoModes.BITRATE_FLOOR_BPS]–
     * [StandardVideoModes.BITRATE_CAP_BPS]. Takes effect on the next connection.
     */
    data class SetMaxVideoBitrateBps(val bps: Long) : SessionAction

    /**
     * Freeze this connection's M3 offer.
     *
     * When auto-add is on and [reading] is present, the visible-picture wm size
     * becomes the preferred mode for this connection only.
     */
    data class FreezeConnectionOffer(val reading: WmSizeReading?) : SessionAction
}
