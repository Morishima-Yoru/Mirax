package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the Mirax session seam (issues #2–#9, #11–#12).
 *
 * Seam under test: [MiraxSession] — feed settings, privilege reports, user
 * actions, picture rotation, and connection events; assert phase, WFD owner,
 * tile/widget state, next advertisement set, preferred mode (including
 * visible-picture axes), selected mode, picture scale, the WFD advertise
 * command, Back confirm, and bottom-handle outputs. RTSP encode/decode stays
 * behind this seam and is not asserted here. The picture activity is not a
 * second seam. Placement rectangles are covered by [PicturePlacementTest].
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

    // --- Issue #11: preferred size follows the visible picture axes ---

    @Test
    fun useThisScreen_base1812x2176_rotation90_writes2176x1812_keepsRefresh() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.CommitPreferredModeText("1280×720@30"))
        session.report(PictureRotationReport(degrees = 90))
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 30))
    }

    @Test
    fun useThisScreen_rotation270_swapsAxes_rotation0And180_doNot() {
        val at270 = MiraxSession()
        at270.report(PrivilegeReport(helperRunning = true))
        at270.handle(SessionAction.AcknowledgeEffects)
        at270.report(PictureRotationReport(degrees = 270))
        at270.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(at270.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))

        val at0 = MiraxSession()
        at0.report(PrivilegeReport(helperRunning = true))
        at0.handle(SessionAction.AcknowledgeEffects)
        at0.report(PictureRotationReport(degrees = 0))
        at0.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(at0.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))

        val at180 = MiraxSession()
        at180.report(PrivilegeReport(helperRunning = true))
        at180.handle(SessionAction.AcknowledgeEffects)
        at180.report(PictureRotationReport(degrees = 180))
        at180.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(at180.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))
    }

    @Test
    fun provisioning_rotation90_writesSwappedAxes_refresh60_sameRuleAsButton() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().effects)
            .contains(SessionEffect.ReadPlainWmSizeForProvisioning)
        session.report(PictureRotationReport(degrees = 90))
        session.handle(
            SessionAction.ApplyProvisioningWmSize(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))
        assertThat(session.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)
    }

    @Test
    fun preferredMode_saved_laterRotationReport_doesNotRewrite() {
        val session = MiraxSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.AcknowledgeEffects)
        session.report(PictureRotationReport(degrees = 0))
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))

        session.report(PictureRotationReport(degrees = 90))
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))

        // Another "use this screen" after rotation does rewrite.
        session.handle(
            SessionAction.UseThisScreen(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))
    }

    @Test
    fun provisioning_stillOnlyOnce_andOnlyWhilePreferredUntouched() {
        val afterProvision = MiraxSession()
        afterProvision.report(PrivilegeReport(helperRunning = true))
        afterProvision.report(PictureRotationReport(degrees = 90))
        afterProvision.handle(
            SessionAction.ApplyProvisioningWmSize(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(afterProvision.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))
        afterProvision.report(PrivilegeReport())
        afterProvision.report(PrivilegeReport(helperRunning = true))
        afterProvision.report(PictureRotationReport(degrees = 0))
        assertThat(afterProvision.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)
        assertThat(afterProvision.snapshot().preferredMode).isEqualTo(VideoMode(2176, 1812, 60))

        val editedFirst = MiraxSession()
        editedFirst.handle(SessionAction.PreferredModeFieldEdited)
        editedFirst.report(PrivilegeReport(helperRunning = true))
        editedFirst.report(PictureRotationReport(degrees = 90))
        assertThat(editedFirst.snapshot().effects)
            .doesNotContain(SessionEffect.ReadPlainWmSizeForProvisioning)
        assertThat(editedFirst.snapshot().preferredMode).isNull()
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
    fun provisioning_leavingUntouchedEmptyField_doesNotConsumeChance() {
        val session = MiraxSession()
        session.handle(SessionAction.CommitPreferredModeText(""))
        session.handle(SessionAction.CommitPreferredModeText("   "))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().effects)
            .contains(SessionEffect.ReadPlainWmSizeForProvisioning)

        session.handle(
            SessionAction.ApplyProvisioningWmSize(
                WmSizeReading(physicalWidth = 1812, physicalHeight = 2176),
            ),
        )
        assertThat(session.snapshot().preferredMode).isEqualTo(VideoMode(1812, 2176, 60))
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

    @Test
    fun connectionEvents_beforePlay_keepAdvertisingPhase_andAcceptDiscoverableProgress() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.BecameDiscoverable)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().tileState).isEqualTo(TileState.ADVERTISING)

        session.handle(SessionAction.PrePlayProgress)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().widgetStatus).isEqualTo(WidgetStatus.ADVERTISING)
        assertThat(session.snapshot().selectedMode).isNull()
    }

    @Test
    fun sourceSelectedMode_mustBelongToAdvertisedSet_rejectsOthers() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        val advertised = session.snapshot().nextAdvertisementModes
        assertThat(advertised).contains(VideoMode(2176, 1812, 60))
        assertThat(advertised).doesNotContain(VideoMode(2560, 1440, 60))

        session.handle(SessionAction.BecameDiscoverable)
        session.handle(SessionAction.SourceSelectedMode(VideoMode(2560, 1440, 60)))
        assertThat(session.snapshot().selectedMode).isNull()

        session.handle(SessionAction.SourceSelectedMode(VideoMode(2176, 1812, 60)))
        assertThat(session.snapshot().selectedMode).isEqualTo(VideoMode(2176, 1812, 60))
    }

    @Test
    fun enteredPlay_showsConnected_onCardTileAndWidget() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.BecameDiscoverable)
        session.handle(SessionAction.PrePlayProgress)
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1920, 1080, 60)))
        session.handle(SessionAction.EnteredPlay)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(snap.tileState).isEqualTo(TileState.CONNECTED)
        assertThat(snap.widgetStatus).isEqualTo(WidgetStatus.CONNECTED)
        assertThat(snap.selectedMode).isEqualTo(VideoMode(1920, 1080, 60))
        assertThat(snap.currentResolutionText).isEqualTo("1920×1080@60")
        assertThat(snap.showPicture).isTrue()
    }

    @Test
    fun connectionEnded_keepsBroadcastOn_returnsToAdvertising() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1280, 720, 60)))
        session.handle(SessionAction.EnteredPlay)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)

        session.handle(SessionAction.ConnectionEnded)
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.selectedMode).isNull()
        assertThat(snap.showPicture).isFalse()
        assertThat(snap.wfdAdvertise).isNotNull()
    }

    @Test
    fun prePlayGroupDrop_clearsSelectedMode_doesNotChangeNextSet() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        val before = session.snapshot().nextAdvertisementModes
        session.handle(SessionAction.BecameDiscoverable)
        session.handle(SessionAction.SourceSelectedMode(VideoMode(2176, 1812, 60)))
        assertThat(session.snapshot().selectedMode).isEqualTo(VideoMode(2176, 1812, 60))

        session.handle(SessionAction.PrePlayGroupDropped)
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)
        assertThat(session.snapshot().selectedMode).isNull()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().showPicture).isFalse()
    }

    @Test
    fun heightNotMultipleOfSixteen_remainsLegalInAdvertisementSet() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.CommitPreferredModeText("2176×1812@60"))
        val modes = session.snapshot().nextAdvertisementModes
        assertThat(modes).contains(VideoMode(2176, 1812, 60))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(2176, 1812, 60)))
        assertThat(session.snapshot().selectedMode).isEqualTo(VideoMode(2176, 1812, 60))
    }

    @Test
    fun connected_firstSystemBack_onlyToasts_keepsConnectionAndBroadcast() {
        val session = connectedSession()
        session.handle(SessionAction.SystemBack)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showPicture).isTrue()
        assertThat(snap.effects).contains(SessionEffect.ShowPressBackAgainToEndToast)
        assertThat(snap.effects).doesNotContain(SessionEffect.DropActiveConnection)
    }

    @Test
    fun connected_secondSystemBack_endsConnection_keepsBroadcast() {
        val session = connectedSession()
        session.handle(SessionAction.SystemBack)
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.SystemBack)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showPicture).isFalse()
        assertThat(snap.selectedMode).isNull()
        assertThat(snap.wfdAdvertise).isNotNull()
        assertThat(snap.effects).contains(SessionEffect.DropActiveConnection)
        assertThat(snap.effects).doesNotContain(SessionEffect.ShowPressBackAgainToEndToast)
    }

    @Test
    fun backConfirmPromise_survivesOtherActions_untilNextBackOrConnectionEnd() {
        val session = connectedSession()
        session.handle(SessionAction.SystemBack)
        session.handle(SessionAction.AcknowledgeEffects)

        // Other actions must not cancel the promise.
        session.handle(SessionAction.SetBottomHandleEnabled(false))
        session.handle(SessionAction.SetBottomHandleEnabled(true))
        session.handle(SessionAction.ToggleBottomHandleExpanded)
        session.handle(SessionAction.SetLanguagePreference(LanguagePreference.ENGLISH))
        session.handle(SessionAction.AcknowledgeEffects)

        session.handle(SessionAction.SystemBack)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().advertisingEnabled).isTrue()
        assertThat(session.snapshot().effects).contains(SessionEffect.DropActiveConnection)
    }

    @Test
    fun connectionEnded_clearsBackConfirm_soNextBackToastsAgain() {
        val session = connectedSession()
        session.handle(SessionAction.SystemBack)
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.ConnectionEnded)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)

        session.handle(SessionAction.SourceSelectedMode(VideoMode(1920, 1080, 60)))
        session.handle(SessionAction.EnteredPlay)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)

        session.handle(SessionAction.SystemBack)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(session.snapshot().effects).contains(SessionEffect.ShowPressBackAgainToEndToast)
    }

    @Test
    fun endConnection_fromHandle_endsThisConnection_keepsBroadcast() {
        val session = connectedSession()
        session.handle(SessionAction.EndConnection)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showPicture).isFalse()
        assertThat(snap.effects).contains(SessionEffect.DropActiveConnection)
    }

    @Test
    fun bottomHandle_defaultsOn_shownWhileConnected_withResolutionAndRefresh() {
        val session = connectedSession()
        val snap = session.snapshot()
        assertThat(snap.bottomHandleEnabled).isTrue()
        assertThat(snap.showBottomHandle).isTrue()
        assertThat(snap.handleResolutionText).isEqualTo("1920×1080")
        assertThat(snap.handleRefreshRateHz).isEqualTo(60)
        assertThat(session.exportSettings().bottomHandleEnabled).isTrue()
    }

    @Test
    fun bottomHandle_off_hidesHandle_butSecondBackStillEndsConnection() {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, bottomHandleEnabled = false),
        )
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1280, 720, 60)))
        session.handle(SessionAction.EnteredPlay)

        assertThat(session.snapshot().bottomHandleEnabled).isFalse()
        assertThat(session.snapshot().showBottomHandle).isFalse()

        session.handle(SessionAction.SystemBack)
        session.handle(SessionAction.AcknowledgeEffects)
        session.handle(SessionAction.SystemBack)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().advertisingEnabled).isTrue()
    }

    @Test
    fun setBottomHandleEnabled_persistsInExport_andTogglesVisibilityWhileConnected() {
        val session = connectedSession()
        session.handle(SessionAction.SetBottomHandleEnabled(false))
        assertThat(session.snapshot().showBottomHandle).isFalse()
        assertThat(session.exportSettings().bottomHandleEnabled).isFalse()

        session.handle(SessionAction.SetBottomHandleEnabled(true))
        assertThat(session.snapshot().showBottomHandle).isTrue()
        assertThat(session.exportSettings().bottomHandleEnabled).isTrue()
    }

    @Test
    fun bottomHandle_expandCollapse_isSessionOwned_andClearsOnConnectionEnd() {
        val session = connectedSession()
        assertThat(session.snapshot().bottomHandleExpanded).isFalse()

        session.handle(SessionAction.ToggleBottomHandleExpanded)
        assertThat(session.snapshot().bottomHandleExpanded).isTrue()
        assertThat(session.snapshot().showBottomHandle).isTrue()

        session.handle(SessionAction.ToggleBottomHandleExpanded)
        assertThat(session.snapshot().bottomHandleExpanded).isFalse()

        session.handle(SessionAction.ToggleBottomHandleExpanded)
        session.handle(SessionAction.EndConnection)
        assertThat(session.snapshot().showBottomHandle).isFalse()
        assertThat(session.snapshot().bottomHandleExpanded).isFalse()
    }

    @Test
    fun bottomHandle_notShownWhenNotConnected() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().bottomHandleEnabled).isTrue()
        assertThat(session.snapshot().showBottomHandle).isFalse()
    }

    @Test
    fun floatingBall_defaultsOn_andPersistsInExport() {
        val session = MiraxSession()
        assertThat(session.snapshot().floatingBallEnabled).isTrue()
        assertThat(session.exportSettings().floatingBallEnabled).isTrue()

        session.handle(SessionAction.SetFloatingBallEnabled(false))
        assertThat(session.snapshot().floatingBallEnabled).isFalse()
        assertThat(session.exportSettings().floatingBallEnabled).isFalse()

        session.handle(SessionAction.SetFloatingBallEnabled(true))
        assertThat(session.exportSettings().floatingBallEnabled).isTrue()
    }

    @Test
    fun overlayReminder_shownOnlyWhenPermissionMissing() {
        val session = MiraxSession()
        session.report(OverlayPermissionReport(granted = false))
        assertThat(session.snapshot().showOverlayPermissionReminder).isTrue()

        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.SetFloatingBallEnabled(false))
        assertThat(session.snapshot().showOverlayPermissionReminder).isFalse()
    }

    @Test
    fun openApp_requestsOverlayPermissionWhenMissing_includingWhileFrozen() {
        val session = MiraxSession()
        session.report(OverlayPermissionReport(granted = false))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)

        session.handle(SessionAction.OpenApp)
        assertThat(session.snapshot().effects).contains(SessionEffect.RequestOverlayPermission)
    }

    @Test
    fun requestOverlayPermission_promptsWhenAllowed_elseOpensSettings() {
        val session = MiraxSession()
        session.report(OverlayPermissionReport(granted = false, canPrompt = true))
        session.handle(SessionAction.RequestOverlayPermission)
        assertThat(session.snapshot().effects).contains(SessionEffect.RequestOverlayPermission)

        session.handle(SessionAction.AcknowledgeEffects)
        session.report(OverlayPermissionReport(granted = false, canPrompt = false))
        session.handle(SessionAction.RequestOverlayPermission)
        assertThat(session.snapshot().effects).contains(SessionEffect.OpenOverlaySettings)
        assertThat(session.snapshot().effects).doesNotContain(SessionEffect.RequestOverlayPermission)
    }

    @Test
    fun connected_leftToHome_withOverlayAndBallOn_showsFloatingBall_keepsConnection() {
        val session = connectedSession()
        session.report(OverlayPermissionReport(granted = true))
        assertThat(session.snapshot().floatingBallEnabled).isTrue()

        session.handle(SessionAction.LeftProjectionToHome)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showFloatingBall).isTrue()
        assertThat(snap.effects).doesNotContain(SessionEffect.DropActiveConnection)
    }

    @Test
    fun connected_leftToHome_withoutOverlay_endsImmediately_noToast_keepsBroadcast() {
        val session = connectedSession()
        session.report(OverlayPermissionReport(granted = false))

        session.handle(SessionAction.LeftProjectionToHome)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showFloatingBall).isFalse()
        assertThat(snap.effects).contains(SessionEffect.DropActiveConnection)
        assertThat(snap.effects).doesNotContain(SessionEffect.ShowPressBackAgainToEndToast)
    }

    @Test
    fun connected_leftToHome_withBallOff_endsImmediately_noToast_keepsBroadcast() {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, floatingBallEnabled = false),
        )
        session.report(PrivilegeReport(helperRunning = true))
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1920, 1080, 60)))
        session.handle(SessionAction.EnteredPlay)

        session.handle(SessionAction.LeftProjectionToHome)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showFloatingBall).isFalse()
        assertThat(snap.effects).contains(SessionEffect.DropActiveConnection)
        assertThat(snap.effects).doesNotContain(SessionEffect.ShowPressBackAgainToEndToast)
    }

    @Test
    fun connected_openedDashboard_keepsConnection_doesNotShowBall() {
        val session = connectedSession()
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.LeftProjectionToHome)
        assertThat(session.snapshot().showFloatingBall).isTrue()

        session.handle(SessionAction.OpenedMiraxDashboard)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(snap.showFloatingBall).isFalse()
        assertThat(snap.effects).doesNotContain(SessionEffect.DropActiveConnection)
    }

    @Test
    fun floatingBallTap_returnsToProjection_hidesBall() {
        val session = connectedSession()
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.LeftProjectionToHome)
        assertThat(session.snapshot().showFloatingBall).isTrue()

        session.handle(SessionAction.FloatingBallTapped)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(snap.showFloatingBall).isFalse()
        assertThat(snap.effects).contains(SessionEffect.BringProjectionToFront)
    }

    @Test
    fun floatingBall_endConnection_hidesBall_keepsBroadcast_evenIfHandleOff() {
        val session = MiraxSession(
            SessionSettings(
                advertisingEnabled = true,
                bottomHandleEnabled = false,
            ),
        )
        session.report(PrivilegeReport(helperRunning = true))
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1280, 720, 60)))
        session.handle(SessionAction.EnteredPlay)
        session.handle(SessionAction.LeftProjectionToHome)
        assertThat(session.snapshot().showFloatingBall).isTrue()
        assertThat(session.snapshot().showBottomHandle).isFalse()

        session.handle(SessionAction.EndConnection)

        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.showFloatingBall).isFalse()
        assertThat(snap.effects).contains(SessionEffect.DropActiveConnection)
    }

    @Test
    fun connectionEnded_hidesFloatingBall() {
        val session = connectedSession()
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.LeftProjectionToHome)
        assertThat(session.snapshot().showFloatingBall).isTrue()

        session.handle(SessionAction.ConnectionEnded)
        assertThat(session.snapshot().showFloatingBall).isFalse()
    }

    // --- Issue #12: picture scale on the session ---

    @Test
    fun pictureScale_freshInstall_isProportional_andPersistsInExport() {
        val session = MiraxSession()
        assertThat(session.snapshot().pictureScale).isEqualTo(PictureScale.PROPORTIONAL)
        assertThat(session.exportSettings().pictureScale).isEqualTo(PictureScale.PROPORTIONAL)
    }

    @Test
    fun pictureScale_allFourModes_selectable_andExported() {
        val session = MiraxSession()
        for (scale in PictureScale.entries) {
            session.handle(SessionAction.SetPictureScale(scale))
            assertThat(session.snapshot().pictureScale).isEqualTo(scale)
            assertThat(session.exportSettings().pictureScale).isEqualTo(scale)
        }
    }

    @Test
    fun pictureScale_change_doesNotAlterNextAdvertisementSet() {
        val session = MiraxSession(
            SessionSettings(preferredMode = VideoMode(1812, 2176, 60)),
        )
        session.report(PrivilegeReport(helperRunning = true))
        val before = session.snapshot().nextAdvertisementModes
        assertThat(before).isNotEmpty()

        session.handle(SessionAction.SetPictureScale(PictureScale.CENTER_CROP))
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)

        session.handle(SessionAction.SetPictureScale(PictureScale.MATCH_EDGES))
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)

        session.handle(SessionAction.SetPictureScale(PictureScale.ACTUAL))
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)

        session.handle(SessionAction.SetPictureScale(PictureScale.PROPORTIONAL))
        assertThat(session.snapshot().nextAdvertisementModes).isEqualTo(before)
    }

    @Test
    fun pictureScale_changeWhileConnected_isVisibleOnNextSnapshot() {
        val session = connectedSession()
        assertThat(session.snapshot().pictureScale).isEqualTo(PictureScale.PROPORTIONAL)

        session.handle(SessionAction.SetPictureScale(PictureScale.CENTER_CROP))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)
        assertThat(session.snapshot().pictureScale).isEqualTo(PictureScale.CENTER_CROP)
    }

    private fun connectedSession(): MiraxSession {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true))
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SourceSelectedMode(VideoMode(1920, 1080, 60)))
        session.handle(SessionAction.EnteredPlay)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.CONNECTED)
        return session
    }
}
