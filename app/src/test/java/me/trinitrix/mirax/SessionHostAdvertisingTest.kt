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
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WidgetStatus
import me.trinitrix.mirax.session.WmSizeReading
import org.junit.After
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

    @After
    fun tearDown() {
        SessionHost.resetForTests()
    }

    @Test
    fun provisioningWmSize_readsOffMainAndHoldsAdvertiseUntilCalibrated() {
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        var plainReadCount = 0
        SessionHost.ioRunner = { task -> ioTasks.addLast(task) }
        SessionHost.mainPoster = { task -> mainTasks.addLast(task) }
        SessionHost.plainWmSizeReader = {
            plainReadCount++
            WmSizeReading(physicalWidth = 1812, physicalHeight = 2176, overrideWidth = null, overrideHeight = null)
        }

        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = false),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))

        val handedOffCommands = mutableListOf<WfdAdvertiseCommand?>()
        val sinkCommands = mutableListOf<WfdAdvertiseCommand?>()
        val published = mutableListOf<SessionSnapshot>()

        fun runCommit(allowProvisioningRead: Boolean): SessionSnapshot {
            if (allowProvisioningRead) {
                SessionHost.maybeScheduleProvisioningWmSize(
                    session = session,
                    rotationDegrees = { 90 },
                    onCalibratedOnMain = { runCommit(allowProvisioningRead = false) },
                )
            }
            return SessionHost.commitAdvertising(
                session = session,
                syncAdvertise = { command ->
                    handedOffCommands += command
                    true
                },
                applyEffects = { snapshot ->
                    session.handle(SessionAction.AcknowledgeEffects)
                },
                publish = { published += it },
                syncSink = { sinkCommands += it },
            )
        }

        val initial = runCommit(allowProvisioningRead = true)
        // Must not block main thread on wm size or advertise uncalibrated modes first.
        assertThat(plainReadCount).isEqualTo(0)
        assertThat(handedOffCommands).isEmpty()
        assertThat(sinkCommands).isEmpty()
        assertThat(initial.phase).isEqualTo(ScreenPhase.ARMING)

        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        assertThat(plainReadCount).isEqualTo(1)
        assertThat(handedOffCommands).isEmpty()

        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(handedOffCommands).hasSize(1)
        assertThat(handedOffCommands.single()?.modes).contains(VideoMode(2176, 1812, 60))
        assertThat(sinkCommands.single()?.modes).contains(VideoMode(2176, 1812, 60))
        assertThat(published.last().preferredModeText).isEqualTo("2176×1812@60")
    }

    @Test
    fun useThisScreen_readsOffMainAndCommitsCalibratedResolutionOnMain() {
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        var displayReadCount = 0
        SessionHost.ioRunner = { task -> ioTasks.addLast(task) }
        SessionHost.mainPoster = { task -> mainTasks.addLast(task) }
        SessionHost.displayWmSizeReader = { displayId ->
            assertThat(displayId).isEqualTo(2)
            displayReadCount++
            WmSizeReading(physicalWidth = 1080, physicalHeight = 2400, overrideWidth = null, overrideHeight = null)
        }

        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = true),
            TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        val handedOffCommands = mutableListOf<WfdAdvertiseCommand?>()
        var refreshed = false

        SessionHost.scheduleUseThisScreen(
            session = session,
            displayId = 2,
            rotationDegrees = { 90 },
            onCalibratedOnMain = {
                SessionHost.commitAdvertising(
                    session = session,
                    syncAdvertise = { cmd ->
                        handedOffCommands += cmd
                        true
                    },
                    applyEffects = { session.handle(SessionAction.AcknowledgeEffects) },
                    publish = {},
                    syncSink = {},
                )
                refreshed = true
            },
        )

        assertThat(displayReadCount).isEqualTo(0)
        assertThat(handedOffCommands).isEmpty()
        assertThat(refreshed).isFalse()

        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        assertThat(displayReadCount).isEqualTo(1)
        assertThat(refreshed).isFalse()

        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(refreshed).isTrue()
        assertThat(handedOffCommands.single()?.modes).contains(VideoMode(2400, 1080, 60))
    }

    @Test
    fun useThisScreen_does_not_overwrite_a_later_manual_resolution() {
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        SessionHost.ioRunner = { ioTasks.addLast(it) }
        SessionHost.mainPoster = { mainTasks.addLast(it) }
        SessionHost.displayWmSizeReader = { WmSizeReading(1080, 2400, null, null) }
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = true), TestConnectionHistory(),
        )
        session.report(PrivilegeReport(helperRunning = true))
        SessionHost.scheduleUseThisScreen(session, 2, { 90 }, {})
        session.handle(SessionAction.PreferredModeFieldEdited)
        session.handle(SessionAction.CommitPreferredModeText("1920x1080@60"))
        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(session.snapshot().preferredModeText).isEqualTo("1920×1080@60")
    }
}
