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
}
