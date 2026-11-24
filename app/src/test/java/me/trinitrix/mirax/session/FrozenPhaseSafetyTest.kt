package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Frozen-phase safety for issue 05 (stories S20-S24, S32-S33).
 *
 * Boundary: public [MiraxSession] seam only ([report], [handle], [snapshot])
 * plus [StandbyNavigation] destinations. No private reflection, no timers,
 * no device or WFD engine proof. UI dialog actions are not proven here.
 */
class FrozenPhaseSafetyTest {
    private fun freshSession(): MiraxSession =
        MiraxSession(SessionSettings(), TestConnectionHistory())

    private fun armedSession(): MiraxSession {
        val session = freshSession()
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SetAdvertising(true))
        return session
    }

    @Test
    fun trueFrozen_staysDistinctFromOfferFreeze() {
        val session = freshSession()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.NONE)

        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.SetAdvertising(true))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ARMING)

        session.handle(
            SessionAction.FreezeConnectionOffer(
                WmSizeReading(physicalWidth = 1280, physicalHeight = 720),
            ),
        )
        val snap = session.snapshot()
        assertThat(snap.phase).isEqualTo(ScreenPhase.CONNECTING)
        assertThat(snap.phase).isNotEqualTo(ScreenPhase.FROZEN)
        assertThat(snap.connectionOfferModes).isNotNull()
    }

    @Test
    fun arming_doesNotFabricateReadinessWithoutBeaconListening() {
        val session = armedSession()
        val snap = session.snapshot()
        assertThat(snap.wfdOwner).isEqualTo(WfdOwner.HELPER)
        assertThat(snap.advertisingEnabled).isTrue()
        assertThat(snap.phase).isEqualTo(ScreenPhase.ARMING)
        assertThat(snap.phase).isNotEqualTo(ScreenPhase.ADVERTISING)

        session.handle(SessionAction.BeaconListening)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
    }

    @Test
    fun wifiPause_keepsIntent_andRestoreStillRequiresReadiness() {
        val session = armedSession()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ARMING)

        session.report(WifiReport(enabled = false))
        val paused = session.snapshot()
        assertThat(paused.phase).isEqualTo(ScreenPhase.WIFI_PAUSED)
        assertThat(paused.advertisingEnabled).isTrue()
        assertThat(paused.wfdAdvertise).isNull()

        session.report(WifiReport(enabled = true))
        val restored = session.snapshot()
        assertThat(restored.advertisingEnabled).isTrue()
        assertThat(restored.phase).isEqualTo(ScreenPhase.ARMING)
        assertThat(restored.phase).isNotEqualTo(ScreenPhase.ADVERTISING)
    }

    @Test
    fun wifiRestore_afterPrivilegeLoss_staysFrozen() {
        val session = armedSession()
        session.handle(SessionAction.BeaconListening)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        session.report(WifiReport(enabled = false))
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.WIFI_PAUSED)
        assertThat(session.snapshot().advertisingEnabled).isTrue()

        session.report(PrivilegeReport())
        val frozen = session.snapshot()
        assertThat(frozen.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(frozen.wfdOwner).isEqualTo(WfdOwner.NONE)
        assertThat(frozen.advertisingEnabled).isTrue()
        assertThat(frozen.wfdAdvertise).isNull()

        session.handle(SessionAction.BeaconListening)
        val paused = session.snapshot()
        assertThat(paused.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(paused.advertisingEnabled).isTrue()
        assertThat(paused.wfdAdvertise).isNull()

        session.report(WifiReport(enabled = true))
        val after = session.snapshot()
        assertThat(after.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(after.phase).isNotEqualTo(ScreenPhase.ARMING)
        assertThat(after.phase).isNotEqualTo(ScreenPhase.ADVERTISING)
        assertThat(after.advertisingEnabled).isTrue()
        assertThat(after.wfdAdvertise).isNull()

        session.handle(SessionAction.BeaconListening)
        val late = session.snapshot()
        assertThat(late.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(late.advertisingEnabled).isTrue()
        assertThat(late.wfdAdvertise).isNull()
    }

    @Test
    fun explicitUserStop_disablesBroadcasting() {
        val session = armedSession()
        session.handle(SessionAction.BeaconListening)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(session.snapshot().wfdAdvertise).isNotNull()

        session.handle(SessionAction.SetAdvertising(false))
        val snap = session.snapshot()
        assertThat(snap.advertisingEnabled).isFalse()
        assertThat(snap.wfdAdvertise).isNull()
        assertThat(snap.phase).isEqualTo(ScreenPhase.READY)

        session.report(WifiReport(enabled = false))
        session.handle(SessionAction.BeaconListening)
        val paused = session.snapshot()
        assertThat(paused.advertisingEnabled).isFalse()
        assertThat(paused.phase).isEqualTo(ScreenPhase.READY)
        assertThat(paused.wfdAdvertise).isNull()

        session.report(WifiReport(enabled = true))
        session.handle(SessionAction.BeaconListening)
        val restored = session.snapshot()
        assertThat(restored.advertisingEnabled).isFalse()
        assertThat(restored.phase).isEqualTo(ScreenPhase.READY)
        assertThat(restored.wfdAdvertise).isNull()
    }

    @Test
    fun frozenInspection_neverTogglesBroadcastIntent() {
        val session = armedSession()
        session.report(PrivilegeReport())
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(session.snapshot().advertisingEnabled).isTrue()

        val first = session.snapshot()
        val second = session.snapshot()
        assertThat(second.advertisingEnabled).isTrue()
        assertThat(second.phase).isEqualTo(first.phase)
        assertThat(second.effects).isEmpty()
    }

    @Test
    fun standbyDestinations_containNoCompatibility() {
        val names = StandbyDestination.entries.map { entry -> entry.name }
        assertThat(names).isNotEmpty()
        assertThat(names).contains(StandbyDestination.STANDBY.name)
        assertThat(names).contains(StandbyDestination.DASHBOARD.name)
        assertThat(names).contains(StandbyDestination.HISTORY.name)
        for (name in names) {
            assertThat(name.contains("COMPAT")).isFalse()
        }
    }

    @Test
    fun privilegeReports_stillAlterLegitimateBehavior() {
        val session = freshSession()
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(session.snapshot().wfdOwner).isEqualTo(WfdOwner.NONE)
        assertThat(session.snapshot().canReadWmSize).isFalse()

        session.report(PrivilegeReport(helperRunning = true))
        val owned = session.snapshot()
        assertThat(owned.wfdOwner).isEqualTo(WfdOwner.HELPER)
        assertThat(owned.canReadWmSize).isTrue()
        assertThat(owned.phase).isEqualTo(ScreenPhase.READY)

        session.report(PrivilegeReport())
        val lost = session.snapshot()
        assertThat(lost.wfdOwner).isEqualTo(WfdOwner.NONE)
        assertThat(lost.canReadWmSize).isFalse()
        assertThat(lost.phase).isEqualTo(ScreenPhase.FROZEN)
    }
    @Test
    fun wifiWarningStopDisablesAdvertisingWhileStayingPaused() {
        val session = armedSession()
        session.handle(SessionAction.BeaconListening)
        assertThat(session.snapshot().phase).isEqualTo(ScreenPhase.ADVERTISING)
        session.report(WifiReport(enabled = false))
        val paused = session.snapshot()
        assertThat(paused.phase).isEqualTo(ScreenPhase.WIFI_PAUSED)
        assertThat(paused.advertisingEnabled).isTrue()

        // Warning switch action (MainActivity.kt:1868-1872): stop advertising, intent off.
        session.handle(SessionAction.SetAdvertising(false))
        val stopped = session.snapshot()
        assertThat(stopped.advertisingEnabled).isFalse()
        assertThat(stopped.wfdAdvertise).isNull()
        // Intent off leaves the paused phase: session reports READY, no advert retained.
        assertThat(stopped.phase).isEqualTo(ScreenPhase.READY)

        session.report(WifiReport(enabled = true))
        val restored = session.snapshot()
        assertThat(restored.advertisingEnabled).isFalse()
        assertThat(restored.wfdAdvertise).isNull()
        assertThat(restored.phase).isEqualTo(ScreenPhase.READY)
    }

    @Test
    fun frozenInfoDialogShizukuPromptFollowsPrivilegeState() {
        val session = MiraxSession(SessionSettings(), TestConnectionHistory())
        session.report(PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = false))
        session.handle(SessionAction.OpenApp)
        val frozen = session.snapshot()
        assertThat(frozen.phase).isEqualTo(ScreenPhase.FROZEN)
        assertThat(frozen.shouldRequestShizukuPermission).isTrue()
        assertThat(frozen.effects).isEmpty()

        session.report(PrivilegeReport(shizukuServiceRunning = true, shizukuAuthorized = true))
        session.handle(SessionAction.OpenApp)
        assertThat(session.snapshot().shouldRequestShizukuPermission).isFalse()
    }

}
