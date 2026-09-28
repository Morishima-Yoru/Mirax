package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the Mirax session seam (issues #2–#6).
 *
 * Seam under test: [MiraxSession] — feed settings, privilege reports, and
 * user actions; assert phase, WFD owner, tile state, widget status, and the
 * WFD advertise command the privileged owner must apply.
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

    // --- Issue #4: shared broadcast switch, tile tap/probe, widget status ---

    @Test
    fun tileAndWidget_coverGrayOffAdvertisingConnectedAndUnavailable() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        assertThat(session.snapshot().tileState).isEqualTo(TileState.GRAY)
        assertThat(session.snapshot().widgetStatus).isEqualTo(WidgetStatus.UNAVAILABLE)

        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().tileState).isEqualTo(TileState.OFF)
        assertThat(session.snapshot().widgetStatus).isEqualTo(WidgetStatus.OFF)

        session.handle(SessionAction.SetAdvertising(true))
        assertThat(session.snapshot().tileState).isEqualTo(TileState.ADVERTISING)
        assertThat(session.snapshot().widgetStatus).isEqualTo(WidgetStatus.ADVERTISING)

        session.handle(SessionAction.ConnectionEstablished)
        assertThat(session.snapshot().tileState).isEqualTo(TileState.CONNECTED)
        assertThat(session.snapshot().widgetStatus).isEqualTo(WidgetStatus.CONNECTED)
    }

    @Test
    fun tileTap_withOwner_togglesAdvertising() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.TileTap)
        assertThat(session.snapshot().advertisingEnabled).isTrue()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)

        session.handle(SessionAction.TileTap)
        assertThat(session.snapshot().advertisingEnabled).isFalse()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.READY)
    }

    @Test
    fun tileTap_withoutOwner_showsShizukuNotOpen_keepsSwitchOff_doesNotRequestPermission() {
        val session = MiraxSession()
        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false),
        )
        session.handle(SessionAction.TileTap)
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.shouldRequestShizukuPermission).isFalse()
        assertThat(snap.effects).contains(SessionEffect.ShowShizukuNotOpenToast)
    }

    @Test
    fun tileTap_shizukuDown_sameToast_switchUnchanged() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        session.handle(SessionAction.TileTap)
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.effects).contains(SessionEffect.ShowShizukuNotOpenToast)
        assertThat(snap.shouldRequestShizukuPermission).isFalse()
    }

    @Test
    fun grayTileProbeSuccess_hostTurnsAdvertisingOn_viaSetAdvertising() {
        // Tile host: report privilege first, then SetAdvertising(true) when owner appears.
        val session = MiraxSession()
        session.report(PrivilegeReport())
        assertThat(session.snapshot().tileState).isEqualTo(TileState.GRAY)

        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = true),
        )
        session.handle(SessionAction.SetAdvertising(true))
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.tileState).isEqualTo(TileState.ADVERTISING)
        assertThat(snap.shouldRequestShizukuPermission).isFalse()
    }

    @Test
    fun advertisingOn_ownerAppears_phaseBecomesAdvertising() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport())
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)

        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().advertisingEnabled).isTrue()
    }

    @Test
    fun turnAdvertisingOff_whileConnected_endsConnection_returnsToReady() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.ConnectionEstablished)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)

        session.handle(SessionAction.SetAdvertising(false))
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.phase).isEqualTo(ScreenPhase.READY)
        assertThat(snap.tileState).isEqualTo(TileState.OFF)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.OFF)
    }

    @Test
    fun connectionEnded_doesNotTurnAdvertisingOff() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.ConnectionEstablished)
        session.handle(SessionAction.ConnectionEnded)
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.tileState).isEqualTo(TileState.ADVERTISING)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.ADVERTISING)
    }

    @Test
    fun tileTap_whileConnected_turnsOffAndEndsConnection() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = true),
        )
        session.handle(SessionAction.ConnectionEstablished)
        session.handle(SessionAction.TileTap)
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.phase).isEqualTo(ScreenPhase.READY)
    }

    // --- Issue #5: preferred mode, standard checklist, advertisement set ---

    @Test
    fun preferredMode_keepsLegalSizes_includingNonMultipleOf16_andFoldSizes() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))

        session.handle(SessionAction.CommitPreferredModeText("2112x1760@60"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2112, 1760, 60))

        session.handle(SessionAction.CommitPreferredModeText("1920×1080@60"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1920, 1080, 60))
    }

    @Test
    fun preferredMode_2112x1760at60_staysItsOwnMode() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("2112×1760@60"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2112, 1760, 60))
        assertThat(session.snapshot().preferredMode).isNotEqualTo(VideoMode(2176, 1812, 60))
    }

    @Test
    fun refresh_snapsToNearestAllowed_tieTakesHigher() {
        assertThat(PreferredModeCorrection.snapRefreshHz(27.0)).isEqualTo(25)
        assertThat(PreferredModeCorrection.snapRefreshHz(27.5)).isEqualTo(30)
        assertThat(PreferredModeCorrection.snapRefreshHz(55.0)).isEqualTo(60)
        assertThat(PreferredModeCorrection.snapRefreshHz(42.0)).isEqualTo(50)

        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("1280×720@55"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1280, 720, 60))
    }

    @Test
    fun preferredMode_overLevel51_shrinksAlongAspect() {
        val session = MiraxSession()
        // Far beyond level 5.1 at 60 Hz; must shrink, not swap to a table mode.
        session.handle(SessionAction.CommitPreferredModeText("7680×4320@60"))
        val mode = session.snapshot().preferredMode
        assertThat(mode).isNotNull()
        checkNotNull(mode)
        assertThat(H264Level51.fits(mode)).isTrue()
        val aspect = 7680.0 / 4320.0
        assertThat(mode.width.toDouble() / mode.height.toDouble()).isWithin(0.05).of(aspect)
        assertThat(mode).isNotEqualTo(VideoMode(1920, 1080, 60))
    }

    @Test
    fun preferredMode_unparseable_restoresLastAccepted_orStaysEmpty() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("not-a-mode"))
        assertThat(session.snapshot().preferredMode).isNull()
        assertThat(session.snapshot().preferredModeText).isEmpty()

        session.handle(SessionAction.CommitPreferredModeText("1280×720@60"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1280, 720, 60))

        session.handle(SessionAction.CommitPreferredModeText("garbage"))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1280, 720, 60))
        assertThat(session.snapshot().preferredModeText).isEqualTo("1280×720@60")
    }

    @Test
    fun preferredMode_blankClearsPreferredMode() {
        val session = MiraxSession(
            SessionSettings(preferredMode = VideoMode(1280, 720, 60), provisioningConsumed = true),
        )
        session.handle(SessionAction.CommitPreferredModeText("   "))
        assertThat(session.snapshot().preferredMode).isNull()
        assertThat(session.snapshot().nextAdvertisementModes)
            .containsExactlyElementsIn(
                setOf(
                    VideoMode(1280, 720, 60),
                    VideoMode(1920, 1080, 30),
                    VideoMode(1920, 1080, 60),
                ),
            )
    }

    @Test
    fun freshInstall_defaultStandardChecks_andNoPreferred_advertisesOnlyChecked() {
        val session = MiraxSession()
        val snap = session.snapshot()
        assertThat(snap.preferredMode).isNull()
        assertThat(snap.nextAdvertisementModes).containsExactly(
            VideoMode(1280, 720, 60),
            VideoMode(1920, 1080, 30),
            VideoMode(1920, 1080, 60),
        )
        val checked = snap.standardModes.filter { it.checked }.map { it.mode }.toSet()
        assertThat(checked).containsExactly(
            VideoMode(1280, 720, 60),
            VideoMode(1920, 1080, 30),
            VideoMode(1920, 1080, 60),
        )
    }

    @Test
    fun withPreferredMode_advertisementSetIsPreferredPlusChecked_deduped() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        var set = session.snapshot().nextAdvertisementModes
        assertThat(set).contains(VideoMode(2176, 1812, 60))
        assertThat(set).contains(VideoMode(1280, 720, 60))
        assertThat(set).contains(VideoMode(1920, 1080, 60))

        // Preferred identical to a checked standard mode appears once.
        session.handle(SessionAction.CommitPreferredModeText("1920×1080@60"))
        set = session.snapshot().nextAdvertisementModes
        assertThat(set.count { it == VideoMode(1920, 1080, 60) }).isEqualTo(1)
        assertThat(set).contains(VideoMode(1280, 720, 60))
    }

    @Test
    fun prePlayGroupDrop_doesNotChangeNextSet_andDoesNotAddExtraModes() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.SetAdvertising(true))
        val before = session.snapshot().nextAdvertisementModes
        assertThat(before).doesNotContain(VideoMode(1280, 720, 30))

        session.handle(SessionAction.PrePlayGroupDropped)
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)

        session.handle(SessionAction.SetAdvertising(false))
        session.handle(SessionAction.SetAdvertising(true))
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)
    }

    @Test
    fun useThisScreen_unavailableWhileFrozen_usesOverrideOrPhysical_keepsRefresh() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText("1280×720@30"))
        assertThat(session.snapshot().canUseThisScreen).isFalse()
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1280, 720, 30))

        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.AcknowledgeEffects)
        assertThat(session.snapshot().canUseThisScreen).isTrue()

        session.report(MiraxDisplayReport(displayId = 0))
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(
                    physicalWidth = 904,
                    physicalHeight = 2316,
                    overrideWidth = 1812,
                    overrideHeight = 2176,
                ),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 30))
    }

    @Test
    fun useThisScreen_withoutOverride_keepsPhysicalAxes_noSwap() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.CommitPreferredModeText("1280×720@60"))
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 904, physicalHeight = 2316),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(904, 2316, 60))
    }

    @Test
    fun provisioning_waitsWithoutShell_readsOnceWhenOwnerAppears() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        assertThat(session.snapshot().canReadWmSize).isFalse()
        assertThat(session.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)

        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().effects)
            .contains(SessionEffect.ReadPlainWmSizeForProvisioning)

        session.handle(
            SessionAction.ApplyProvisioningWmSize(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))
        assertThat(session.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)

        session.report(PrivilegeReport())
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))
    }

    @Test
    fun provisioning_editOrUseThisScreen_consumesChance_standardToggleDoesNot() {
        val edited = MiraxSession()
        edited.handle(SessionAction.PreferredModeFieldEdited)
        edited.report(PrivilegeReport(helperRunning = true))
        assertThat(edited.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)
        assertThat(edited.snapshot().preferredMode).isNull()

        val usedButton = MiraxSession()
        usedButton.report(PrivilegeReport(helperRunning = true))
        usedButton.handle(SessionAction.AcknowledgeEffects)
        usedButton.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(usedButton.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))
        usedButton.report(PrivilegeReport())
        usedButton.report(PrivilegeReport(helperRunning = true))
        assertThat(usedButton.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)

        val toggled = MiraxSession()
        toggled.handle(
            SessionAction.SetStandardModeChecked(VideoMode(1280, 720, 30), checked = true),
        )
        toggled.report(PrivilegeReport(helperRunning = true))
        assertThat(toggled.snapshot().effects)
            .contains(SessionEffect.ReadPlainWmSizeForProvisioning)
    }

    @Test
    fun frozen_canEditPreferredTextAndStandardChecks() {
        val session = MiraxSession()
        session.report(PrivilegeReport())
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)

        session.handle(SessionAction.CommitPreferredModeText("1280×720@60"))
        session.handle(
            SessionAction.SetStandardModeChecked(VideoMode(1920, 1080, 60), checked = false),
        )
        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.preferredMode).isEqualTo(VideoMode(1280, 720, 60))
        assertThat(snap.nextAdvertisementModes).containsExactly(
            VideoMode(1280, 720, 60),
            VideoMode(1920, 1080, 30),
        )
    }

    @Test
    fun standardCatalog_includesCeaVesaHhWithinLevelAndBitrate_oneRowPerMode() {
        val session = MiraxSession()
        val modes = session.snapshot().standardModes.map { it.mode }
        assertThat(modes).contains(VideoMode(1280, 720, 60))
        assertThat(modes).contains(VideoMode(1920, 1200, 60))
        assertThat(modes).contains(VideoMode(800, 480, 60))
        assertThat(modes.toSet().size).isEqualTo(modes.size)
        for (mode in modes) {
            assertThat(H264Level51.fits(mode)).isTrue()
            assertThat(StandardVideoModes.estimatedBitrateBps(mode))
                .isAtMost(session.snapshot().maxVideoBitrateBps)
        }
    }

    @Test
    fun bitrateCap_filtersStandardList_doesNotRewritePreferredMode() {
        val session = MiraxSession(
            SessionSettings(
                preferredMode = VideoMode(1920, 1080, 60),
                maxVideoBitrateBps = 1_000L,
                provisioningConsumed = true,
                checkedStandardModes = emptySet(),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1920, 1080, 60))
        assertThat(session.snapshot().standardModes).isEmpty()
        assertThat(session.snapshot().nextAdvertisementModes)
            .containsExactly(VideoMode(1920, 1080, 60))
    }

    @Test
    fun advertisingOff_withOwner_emitsNoWfdAdvertiseCommand() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.report(DeviceNameReport("Pixel Fold"))
        assertThat(session.snapshot().wfdAdvertise).isNull()
    }

    @Test
    fun advertisingOn_withoutOwner_emitsNoWfdAdvertiseCommand() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport())
        session.report(DeviceNameReport("Pixel Fold"))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(session.snapshot().wfdAdvertise).isNull()
    }

    @Test
    fun helperOwner_advertisingOn_emitsAdvertiseForHelperWithNameAndModes() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.report(DeviceNameReport("Living Room Fold"))
        val snap = session.snapshot()
        val command = snap.wfdAdvertise
        assertThat(command).isNotNull()
        assertThat(command!!.owner).isEqualTo(WfdOwner.HELPER)
        assertThat(command.broadcastName).isEqualTo("Living Room Fold")
        assertThat(command.modes).isEqualTo(snap.nextAdvertisementModes)
        assertThat(command.modes).containsExactly(
            VideoMode(1280, 720, 60),
            VideoMode(1920, 1080, 30),
            VideoMode(1920, 1080, 60),
        )
    }

    @Test
    fun shizukuOwner_advertisingOn_emitsAdvertiseForShizukuWithOverrideName() {
        val session = MiraxSession(
            SessionSettings(
                advertisingEnabled = true,
                displayNameOverride = "Mirax Desk",
            ),
        )
        session.report(
            PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = true),
        )
        session.report(DeviceNameReport("Phone Model Name"))
        val command = session.snapshot().wfdAdvertise
        assertThat(command).isNotNull()
        assertThat(command!!.owner).isEqualTo(WfdOwner.SHIZUKU)
        assertThat(command.broadcastName).isEqualTo("Mirax Desk")
        assertThat(command.broadcastName).doesNotContain("Z Fold")
        assertThat(command.broadcastName).doesNotContain("ZFold")
        assertThat(command.modes).isEqualTo(session.snapshot().nextAdvertisementModes)
    }

    @Test
    fun shizukuTakesOver_stopsHelperThenAdvertisesAsShizuku() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().wfdAdvertise!!.owner).isEqualTo(WfdOwner.HELPER)

        session.report(
            PrivilegeReport(
                shizukuServiceRunning = true,
                shizukuAuthorized = true,
                helperRunning = true,
            ),
        )
        val snap = session.snapshot()
        assertThat(snap.effects).contains(SessionEffect.StopHelper)
        assertThat(snap.wfdAdvertise).isNotNull()
        assertThat(snap.wfdAdvertise!!.owner).isEqualTo(WfdOwner.SHIZUKU)
    }

    @Test
    fun turnAdvertisingOff_clearsWfdAdvertiseCommand() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().wfdAdvertise).isNotNull()

        session.handle(SessionAction.SetAdvertising(false))
        assertThat(session.snapshot().wfdAdvertise).isNull()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.READY)
    }

    @Test
    fun ownerLostWhileAdvertising_clearsWfdAdvertiseCommand() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().wfdAdvertise).isNotNull()

        session.report(PrivilegeReport())
        assertThat(session.snapshot().advertisingEnabled).isTrue()
        assertThat(session.snapshot().wfdAdvertise).isNull()
    }

    @Test
    fun preferredModesPassThroughAdvertiseCommand_unchangedPolicy() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        val snap = session.snapshot()
        assertThat(snap.wfdAdvertise!!.modes).isEqualTo(snap.nextAdvertisementModes)
        assertThat(snap.wfdAdvertise!!.modes).contains(VideoMode(2176, 1812, 60))
    }
}
