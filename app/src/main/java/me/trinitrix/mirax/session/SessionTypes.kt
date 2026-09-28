package me.trinitrix.mirax.session

/**
 * Screen phase exposed by the Mirax session.
 */
enum class ScreenPhase {
    FROZEN,
    READY,
    ADVERTISING,
    CONNECTED,
}

/**
 * Who currently owns WFD advertise privileges for Mirax.
 */
enum class WfdOwner {
    NONE,
    SHIZUKU,
    HELPER,
}

/**
 * Quick Settings tile appearance derived from the session.
 */
enum class TileState {
    GRAY,
    OFF,
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
 *     helperRunning: Whether the adb helper process is running.
 */
data class PrivilegeReport(
    val shizukuServiceRunning: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val helperRunning: Boolean = false,
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
 * Persisted settings the session reads and updates.
 *
 * Args:
 *     advertisingEnabled: Whether the user wants WFD advertising on.
 *     languagePreference: Follow-system, pin Traditional Chinese, or pin English.
 *     displayNameOverride: Non-blank custom broadcast name, or null to follow the device name.
 */
data class SessionSettings(
    val advertisingEnabled: Boolean = false,
    val languagePreference: LanguagePreference = LanguagePreference.FOLLOW_SYSTEM,
    val displayNameOverride: String? = null,
)

/**
 * One-shot effects the host must perform; the session never starts Shizuku or the helper.
 */
sealed interface SessionEffect {
    data object StopHelper : SessionEffect

    /**
     * Show the shared "Shizuku is not open" toast after a gray tile probe failed.
     * Host must not open the authorization dialog for this effect.
     */
    data object ShowShizukuNotOpenToast : SessionEffect
}

/**
 * Observable session output for Activities and status surfaces.
 *
 * [effectiveBroadcastName] is the single name Wi-Fi Direct and the RTSP friendly
 * name must use. When [displayNameFollowsDevice] is true, [displayNameFieldHint]
 * shows the current device name in gray and is not a saved override.
 */
data class SessionSnapshot(
    val phase: ScreenPhase,
    val wfdOwner: WfdOwner,
    val tileState: TileState,
    val widgetStatus: WidgetStatus,
    val advertisingEnabled: Boolean,
    val shouldRequestShizukuPermission: Boolean,
    val canReadWmSize: Boolean,
    val helperStartCommand: String,
    val effects: List<SessionEffect> = emptyList(),
    val languagePreference: LanguagePreference = LanguagePreference.FOLLOW_SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val effectiveBroadcastName: String = "",
    val displayNameOverride: String? = null,
    val displayNameFollowsDevice: Boolean = true,
    val displayNameFieldHint: String = "",
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
     * Quick Settings tile click after the host refreshed [PrivilegeReport].
     *
     * With a WFD owner, toggles advertising. With no owner (gray tile), emits
     * [SessionEffect.ShowShizukuNotOpenToast] and does not request authorization.
     * A gray-tile probe that finds Shizuku ready is handled by the host via
     * [SetAdvertising] `(true)` after the privilege report, not by this action alone.
     */
    data object TileTap : SessionAction

    /** Host consumed one-shot effects (e.g. StopHelper). */
    data object AcknowledgeEffects : SessionAction

    /** Connection reached PLAY. */
    data object ConnectionEstablished : SessionAction

    /** Current connection ended. */
    data object ConnectionEnded : SessionAction

    /** User chose follow-system, Traditional Chinese, or English. */
    data class SetLanguagePreference(val preference: LanguagePreference) : SessionAction

    /**
     * User saved a display-name field value.
     *
     * Blank or whitespace-only clears the override and resumes following the device name.
     */
    data class SetDisplayNameOverride(val value: String) : SessionAction
}
