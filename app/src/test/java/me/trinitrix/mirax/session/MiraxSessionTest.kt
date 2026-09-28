package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the Mirax session seam (issue #2).
 *
 * Seam under test: [MiraxSession] — feed settings, privilege reports, and
 * user actions; assert phase, WFD owner, tile state, and widget status.
 */
class MiraxSessionTest {

    @Test
    fun freshInstall_advertisingIsOff() {
        val session = MiraxSession()
        assertThat(session.snapshot().advertisingEnabled).isFalse()
    }

    @Test
    fun noOwner_phaseIsFrozen_tileGray_widgetUnavailable() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.NONE)
        assertThat(snap.tileState).isEqualTo(TileState.GRAY)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.UNAVAILABLE)
    }

    @Test
    fun frozen_cannotTurnAdvertisingOn_andCannotReadWmSize() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        session.handle(SessionAction.SetAdvertising(true))
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.canReadWmSize).isFalse()
    }

    @Test
    fun openApp_shizukuRunningUnauthorized_requestsPermissionOnce() {
        val session = MiraxSession()
        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false),
        )
        session.handle(SessionAction.OpenApp)
        assertThat(session.snapshot().shouldRequestShizukuPermission).isTrue()

        session.handle(SessionAction.Retry)
        assertThat(session.snapshot().shouldRequestShizukuPermission).isFalse()

        session.handle(SessionAction.AutoWait)
        assertThat(session.snapshot().shouldRequestShizukuPermission).isFalse()
    }

    @Test
    fun openApp_shizukuNotRunning_doesNotRequestPermission() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        session.handle(SessionAction.OpenApp)
        assertThat(session.snapshot().shouldRequestShizukuPermission).isFalse()
    }

    @Test
    fun newStay_canRequestPermissionAgain() {
        val first = MiraxSession()
        first.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false),
        )
        first.handle(SessionAction.OpenApp)
        assertThat(first.snapshot().shouldRequestShizukuPermission).isTrue()
        first.handle(SessionAction.Retry)
        assertThat(first.snapshot().shouldRequestShizukuPermission).isFalse()

        val second = MiraxSession()
        second.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false),
        )
        second.handle(SessionAction.OpenApp)
        assertThat(second.snapshot().shouldRequestShizukuPermission).isTrue()
    }

    @Test
    fun helperOnly_ownerIsHelper_readyWhenAdvertisingOff() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        val snap = session.snapshot()
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.HELPER)
        assertThat(snap.phase).isEqualTo(ScreenPhase.READY)
        assertThat(snap.tileState).isEqualTo(TileState.OFF)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.OFF)
        assertThat(snap.canReadWmSize).isTrue()
    }

    @Test
    fun helperOnly_advertisingOn_phaseIsAdvertising() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        val snap = session.snapshot()
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.HELPER)
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.tileState).isEqualTo(TileState.ADVERTISING)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.ADVERTISING)
    }

    @Test
    fun shizukuReady_takesOwnership_stopsHelper_keepsSwitch() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.HELPER)

        session.report(
            PrivilegeReport(
                shizukuServiceRunning = true,
                shizukuAuthorized = true,
                helperRunning = true,
            ),
        )
        val snap = session.snapshot()
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.SHIZUKU)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.effects).contains(SessionEffect.StopHelper)
    }

    @Test
    fun ownerDisappears_returnsToFrozen_switchPreserved_noSpawnEffects() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.report(PrivilegeReport())
        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.NONE)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.tileState).isEqualTo(TileState.GRAY)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.UNAVAILABLE)
        assertThat(snap.effects).isEmpty()
    }

    @Test
    fun helperStartCommand_isSharedAndOnlyStartsHelper() {
        val session = MiraxSession()
        val command = session.snapshot().helperStartCommand
        assertThat(command).isEqualTo(MiraxSession.HELPER_START_COMMAND)
        assertThat(command.lowercase()).contains("adb shell")
        assertThat(command.lowercase()).doesNotContain("shizuku")
        assertThat(command).contains("mirax")
    }

    @Test
    fun shizukuServiceUpButUnauthorized_withHelper_ownerRemainsHelper() {
        val session = MiraxSession()
        session.report(
            PrivilegeReport(
                shizukuServiceRunning = true,
                shizukuAuthorized = false,
                helperRunning = true,
            ),
        )
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.HELPER)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.READY)
    }

    @Test
    fun shizukuReady_meansServiceAndAuthorized() {
        val session = MiraxSession()
        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false),
        )
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.NONE)

        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = true),
        )
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.SHIZUKU)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.READY)
    }

    @Test
    fun unfrozen_userCanToggleAdvertising() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SetAdvertising(true))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        session.handle(SessionAction.SetAdvertising(false))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.READY)
    }

    // --- Issue #3: language and effective broadcast name ---

    @Test
    fun followSystem_traditionalChineseSystem_resolvesTraditionalChinese() {
        val session = MiraxSession()
        session.report(SystemLocaleReport(isTraditionalChinese = true))
        val snap = session.snapshot()
        assertThat(snap.languagePreference).isEqualTo(LanguagePreference.FOLLOW_SYSTEM)
        assertThat(snap.appLanguage).isEqualTo(AppLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun followSystem_nonTraditionalSystem_includingSimplified_resolvesEnglish() {
        val session = MiraxSession()
        session.report(SystemLocaleReport(isTraditionalChinese = false))
        assertThat(session.snapshot().appLanguage).isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun pinTraditionalChinese_overridesSystemLocale() {
        val session = MiraxSession()
        session.report(SystemLocaleReport(isTraditionalChinese = false))
        session.handle(SessionAction.SetLanguagePreference(LanguagePreference.TRADITIONAL_CHINESE))
        val snap = session.snapshot()
        assertThat(snap.languagePreference).isEqualTo(LanguagePreference.TRADITIONAL_CHINESE)
        assertThat(snap.appLanguage).isEqualTo(AppLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun pinEnglish_overridesTraditionalChineseSystem() {
        val session = MiraxSession()
        session.report(SystemLocaleReport(isTraditionalChinese = true))
        session.handle(SessionAction.SetLanguagePreference(LanguagePreference.ENGLISH))
        val snap = session.snapshot()
        assertThat(snap.languagePreference).isEqualTo(LanguagePreference.ENGLISH)
        assertThat(snap.appLanguage).isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun returnToFollowSystem_usesLatestLocaleReport() {
        val session = MiraxSession(
            SessionSettings(languagePreference = LanguagePreference.ENGLISH),
        )
        session.report(SystemLocaleReport(isTraditionalChinese = true))
        session.handle(SessionAction.SetLanguagePreference(LanguagePreference.FOLLOW_SYSTEM))
        assertThat(session.snapshot().appLanguage).isEqualTo(AppLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun withoutOverride_effectiveNameFollowsDeviceNameReports() {
        val session = MiraxSession()
        session.report(DeviceNameReport("Fold-One"))
        assertThat(session.snapshot().effectiveBroadcastName).isEqualTo("Fold-One")
        assertThat(session.snapshot().displayNameFollowsDevice).isTrue()
        assertThat(session.snapshot().displayNameOverride).isNull()

        session.report(DeviceNameReport("Fold-Two"))
        assertThat(session.snapshot().effectiveBroadcastName).isEqualTo("Fold-Two")
        assertThat(session.snapshot().displayNameFollowsDevice).isTrue()
    }

    @Test
    fun savedNonEmptyOverride_stopsFollowing_evenIfEqualToDeviceName() {
        val session = MiraxSession()
        session.report(DeviceNameReport("Phone-A"))
        session.handle(SessionAction.SetDisplayNameOverride("Phone-A"))
        var snap = session.snapshot()
        assertThat(snap.effectiveBroadcastName).isEqualTo("Phone-A")
        assertThat(snap.displayNameFollowsDevice).isFalse()
        assertThat(snap.displayNameOverride).isEqualTo("Phone-A")

        session.report(DeviceNameReport("Phone-B"))
        snap = session.snapshot()
        assertThat(snap.effectiveBroadcastName).isEqualTo("Phone-A")
        assertThat(snap.displayNameFollowsDevice).isFalse()
    }

    @Test
    fun emptyOrWhitespaceOverride_clearsAndResumesFollowing() {
        val session = MiraxSession(
            SessionSettings(displayNameOverride = "Custom"),
        )
        session.report(DeviceNameReport("Device-X"))
        assertThat(session.snapshot().effectiveBroadcastName).isEqualTo("Custom")

        session.handle(SessionAction.SetDisplayNameOverride("   "))
        var snap = session.snapshot()
        assertThat(snap.displayNameOverride).isNull()
        assertThat(snap.displayNameFollowsDevice).isTrue()
        assertThat(snap.effectiveBroadcastName).isEqualTo("Device-X")

        session.handle(SessionAction.SetDisplayNameOverride(""))
        snap = session.snapshot()
        assertThat(snap.displayNameFollowsDevice).isTrue()
        assertThat(snap.effectiveBroadcastName).isEqualTo("Device-X")
    }

    @Test
    fun following_fieldHintIsDeviceName_notVariableToken() {
        val session = MiraxSession()
        session.report(DeviceNameReport("Living Room Fold"))
        val snap = session.snapshot()
        assertThat(snap.displayNameFollowsDevice).isTrue()
        assertThat(snap.displayNameFieldHint).isEqualTo("Living Room Fold")
        assertThat(snap.displayNameFieldHint).doesNotContain("$")
        assertThat(snap.effectiveBroadcastName).isEqualTo("Living Room Fold")
    }

    @Test
    fun frozen_languageAndDisplayNameRemainEditable() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        session.report(SystemLocaleReport(isTraditionalChinese = false))
        session.report(DeviceNameReport("Frozen-Phone"))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)

        session.handle(SessionAction.SetLanguagePreference(LanguagePreference.TRADITIONAL_CHINESE))
        session.handle(SessionAction.SetDisplayNameOverride("Sink-1"))
        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.appLanguage).isEqualTo(AppLanguage.TRADITIONAL_CHINESE)
        assertThat(snap.effectiveBroadcastName).isEqualTo("Sink-1")
        assertThat(snap.displayNameFollowsDevice).isFalse()
    }
}
