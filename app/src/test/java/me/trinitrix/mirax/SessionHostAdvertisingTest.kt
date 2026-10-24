package me.trinitrix.mirax

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.OverlayPermissionReport
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TestConnectionHistory
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WidgetStatus
import org.junit.Test

/** Tests the real session through the host's advertising commit boundary. */
class SessionHostAdvertisingTest {
    @Test
    fun rejectedAdvertise_publishesOnlyFinalOffStateAndShowsOneFailure() {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = true),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        session.handle(SessionAction.RequestOverlayPermission)
        val published = mutableListOf<SessionSnapshot>()
        val effects = mutableListOf<SessionEffect>()
        val commands = mutableListOf<Boolean>()
        val sinkCommands = mutableListOf<Boolean>()

        val result = SessionHost.commitAdvertising(
            session = session,
            syncAdvertise = { command ->
                commands += command != null
                command == null
            },
            applyEffects = { snapshot ->
                effects += snapshot.effects
                session.handle(SessionAction.AcknowledgeEffects)
            },
            publish = { published += it },
            syncSink = { sinkCommands += it != null },
        )

        assertThat(commands).containsExactly(true, false).inOrder()
        assertThat(effects).containsExactly(
            SessionEffect.RequestOverlayPermission,
            SessionEffect.ShowAdvertiseFailedToast,
        ).inOrder()
        assertThat(published).containsExactly(result)
        assertThat(result.advertisingEnabled).isFalse()
        assertThat(result.tileState).isEqualTo(TileState.OFF)
        assertThat(result.wfdAdvertise).isNull()
        assertThat(result.widgetStatus).isEqualTo(WidgetStatus.OFF)
        assertThat(sinkCommands).containsExactly(false)
    }

    @Test
    fun armTimeout_afterAcceptedHandoff_publishesOffAndEndsActiveConnectionOnce() {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = true),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        val published = mutableListOf<SessionSnapshot>()
        val effects = mutableListOf<SessionEffect>()
        val sinkCommands = mutableListOf<Boolean>()
        val syncAdvertise: (WfdAdvertiseCommand?) -> Boolean = { true }
        val applyEffects: (SessionSnapshot) -> Unit = { snapshot ->
            effects += snapshot.effects
            session.handle(SessionAction.AcknowledgeEffects)
        }
        val publish: (SessionSnapshot) -> Unit = { published += it }
        val syncSink: (WfdAdvertiseCommand?) -> Unit = { sinkCommands += it != null }

        val arming = SessionHost.commitAdvertising(session, syncAdvertise, applyEffects, publish, syncSink)
        assertThat(arming.phase).isEqualTo(ScreenPhase.ARMING)
        assertThat(arming.tileState).isEqualTo(TileState.ARMING)
        assertThat(arming.advertisingEnabled).isTrue()
        assertThat(sinkCommands).containsExactly(true)

        session.enterMockConnected()
        session.report(OverlayPermissionReport(granted = true))
        session.handle(SessionAction.LeftProjectionToHome)
        assertThat(session.snapshot().showFloatingBall).isTrue()
        session.handle(SessionAction.BeaconFailed)
        val failed = SessionHost.commitAdvertising(session, syncAdvertise, applyEffects, publish, syncSink)
        SessionHost.commitAdvertising(session, syncAdvertise, applyEffects, publish, syncSink)

        assertThat(failed.advertisingEnabled).isFalse()
        assertThat(failed.tileState).isEqualTo(TileState.OFF)
        assertThat(failed.widgetStatus).isEqualTo(WidgetStatus.OFF)
        assertThat(failed.showPicture).isFalse()
        assertThat(failed.showFloatingBall).isFalse()
        assertThat(failed.wfdAdvertise).isNull()
        assertThat(published).containsExactly(arming, failed, failed).inOrder()
        assertThat(sinkCommands).containsExactly(true, false, false).inOrder()
        assertThat(effects).containsExactly(
            SessionEffect.ShowAdvertiseFailedToast,
            SessionEffect.DropActiveConnection,
        )
    }

    @Test
    fun listeningConfirmed_keepsBroadcastOn_withoutFailureToast() {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = true),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        val published = mutableListOf<SessionSnapshot>()
        val effects = mutableListOf<SessionEffect>()
        val sinkCommands = mutableListOf<Boolean>()
        val syncAdvertise: (WfdAdvertiseCommand?) -> Boolean = { true }
        val applyEffects: (SessionSnapshot) -> Unit = { snapshot ->
            effects += snapshot.effects
            session.handle(SessionAction.AcknowledgeEffects)
        }
        val publish: (SessionSnapshot) -> Unit = { published += it }
        val syncSink: (WfdAdvertiseCommand?) -> Unit = { sinkCommands += it != null }

        SessionHost.commitAdvertising(session, syncAdvertise, applyEffects, publish, syncSink)
        session.handle(SessionAction.BeaconListening)
        val listening = SessionHost.commitAdvertising(session, syncAdvertise, applyEffects, publish, syncSink)

        assertThat(listening.phase).isEqualTo(ScreenPhase.ADVERTISING)
        assertThat(listening.tileState).isEqualTo(TileState.ADVERTISING)
        assertThat(listening.advertisingEnabled).isTrue()
        assertThat(published.last()).isEqualTo(listening)
        assertThat(sinkCommands).containsExactly(true, true).inOrder()
        assertThat(effects).isEmpty()
    }
}
