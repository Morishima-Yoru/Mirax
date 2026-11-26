package me.trinitrix.mirax

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.OverlayPermissionReport
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TestConnectionHistory
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WidgetStatus
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.wfd.SinkProvisioningFence
import me.trinitrix.mirax.wfd.SinkWmReadCoordinator
import me.trinitrix.mirax.wfd.SinkWmReadScope
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

    @Test
    fun provisioningWmSize_readsOffMainAndHoldsAdvertiseUntilCalibrated() {
        val fixture = CalibrationFixture(provisioningConsumed = false)
        val initial = fixture.commit()
        assertThat(initial.phase).isEqualTo(ScreenPhase.ARMING)
        assertThat(fixture.displaysRead).isEmpty()
        assertThat(fixture.ownerCommands).isEmpty()
        assertThat(fixture.sinkCommands).isEmpty()

        fixture.runWorker()
        assertThat(fixture.displaysRead).containsExactly(null)
        assertThat(fixture.session.snapshot().preferredMode).isNull()
        assertThat(fixture.ownerCommands).isEmpty()
        assertThat(fixture.refreshes).isEqualTo(0)

        fixture.runMain()
        val calibrated = VideoMode(2176, 1812, 60)
        assertThat(fixture.ownerCommands.single()?.modes).contains(calibrated)
        assertThat(fixture.sinkCommands.single()?.modes).contains(calibrated)
        assertThat(fixture.published.last().preferredModeText).isEqualTo("2176×1812@60")
        assertThat(fixture.refreshes).isEqualTo(1)
    }

    @Test
    fun useThisScreen_readsOffMainAndCommitsCalibratedResolutionOnMain() {
        val fixture = CalibrationFixture()
        fixture.reading = WmSizeReading(1080, 2400)
        val immediate = fixture.useThisScreen(2)
        assertThat(immediate.miraxDisplayId).isEqualTo(2)
        assertThat(immediate.preferredMode).isNull()
        fixture.commit()
        assertThat(fixture.displaysRead).isEmpty()
        assertThat(fixture.ownerCommands).isEmpty()
        assertThat(fixture.sinkCommands).isEmpty()
        assertThat(fixture.refreshes).isEqualTo(0)

        fixture.runWorker()
        assertThat(fixture.displaysRead).containsExactly(2)
        assertThat(fixture.refreshes).isEqualTo(0)
        assertThat(fixture.ownerCommands).isEmpty()

        fixture.runMain()
        val calibrated = VideoMode(2400, 1080, 60)
        assertThat(fixture.refreshes).isEqualTo(1)
        assertThat(fixture.ownerCommands.single()?.modes).contains(calibrated)
        assertThat(fixture.sinkCommands.single()?.modes).contains(calibrated)
        assertThat(fixture.published.last().preferredMode).isEqualTo(calibrated)

        fixture.session.handle(SessionAction.FreezeConnectionOffer(null))
        assertThat(fixture.session.snapshot().connectionPreferredMode).isEqualTo(calibrated)
        assertThat(fixture.session.snapshot().connectionOfferModes).contains(calibrated)
    }

    @Test
    fun useThisScreen_does_not_overwrite_a_later_manual_resolution() {
        val manual = VideoMode(1920, 1080, 60)
        for (previousMode in listOf(null, manual)) {
            val fixture = CalibrationFixture(preferredMode = previousMode)
            fixture.reading = WmSizeReading(1080, 2400)
            fixture.useThisScreen(2)
            fixture.commit()
            assertThat(fixture.ownerCommands).isEmpty()

            fixture.runWorker()
            fixture.session.handle(SessionAction.PreferredModeFieldEdited)
            fixture.session.handle(SessionAction.CommitPreferredModeText("1920x1080@60"))
            fixture.runMain()

            assertThat(fixture.session.snapshot().preferredModeText).isEqualTo("1920×1080@60")
            assertThat(fixture.ownerCommands.single()?.modes).doesNotContain(VideoMode(2400, 1080, 60))
            assertThat(fixture.sinkCommands).containsExactlyElementsIn(fixture.ownerCommands)
            assertThat(fixture.refreshes).isEqualTo(0)
        }
    }

    @Test
    fun provisioningReleasesHandoffAtJoinedDeadlineWithoutApplyingLateRead() {
        val fixture = CalibrationFixture(provisioningConsumed = false)
        val originalDeadline = fixture.nowMs + 25L
        val originalRead = fixture.requestPlainWmReading(originalDeadline)
        val initialModes = fixture.commit().nextAdvertisementModes
        assertThat(fixture.ownerCommands).isEmpty()

        fixture.nowMs = originalDeadline
        fixture.runMain()
        assertThat(fixture.displaysRead).isEmpty()
        assertThat(fixture.activeWmRequest()).isSameInstanceAs(originalRead)
        assertThat(fixture.ownerCommands.single()?.modes).isEqualTo(initialModes)
        assertThat(fixture.sinkCommands).containsExactlyElementsIn(fixture.ownerCommands)

        fixture.runWorker()
        fixture.runMain()
        assertThat(fixture.displaysRead).containsExactly(null)
        assertThat(fixture.activeWmRequest()).isNull()
        assertThat(fixture.session.snapshot().preferredMode).isNull()
        assertThat(fixture.ownerCommands.map { it?.modes }.toSet()).containsExactly(initialModes)
        assertThat(fixture.refreshes).isEqualTo(0)
    }

    @Test
    fun provisioningRejectsOldScopeOnMainAndCommitsFreshRead() {
        for (rebind in listOf(true, false)) {
            val fixture = CalibrationFixture(provisioningConsumed = false)
            fixture.commit()
            fixture.runWorker()
            if (rebind) {
                fixture.ownerRevision++
            } else {
                fixture.fence = fixture.fence.copy(observedSource = SinkAttemptSource(1, 2L))
            }
            fixture.reading = WmSizeReading(1080, 2400)
            fixture.runMain()
            assertThat(fixture.session.snapshot().preferredMode).isNull()
            assertThat(fixture.ownerCommands).isEmpty()
            assertThat(fixture.refreshes).isEqualTo(0)

            fixture.runWorker()
            fixture.runMain()
            assertThat(fixture.displaysRead).containsExactly(null, null)
            assertThat(fixture.ownerCommands.single()?.modes).contains(VideoMode(2400, 1080, 60))
            assertThat(fixture.sinkCommands).containsExactlyElementsIn(fixture.ownerCommands)
            assertThat(fixture.refreshes).isEqualTo(1)
        }
    }

    @Test
    fun useThisScreen_releasesHandoffAtDeadlineWithoutRefreshingLateResult() {
        val fixture = CalibrationFixture()
        fixture.useThisScreen(2)
        val initialModes = fixture.commit().nextAdvertisementModes
        val deadline = checkNotNull(fixture.activeWmRequest()).deadlineMs
        fixture.nowMs = deadline - 1L
        fixture.runMain()
        assertThat(fixture.ownerCommands).isEmpty()

        fixture.nowMs = deadline
        fixture.runMain()
        assertThat(fixture.ownerCommands.single()?.modes).isEqualTo(initialModes)
        assertThat(fixture.sinkCommands).containsExactlyElementsIn(fixture.ownerCommands)
        fixture.runWorker()
        fixture.runMain()
        assertThat(fixture.session.snapshot().preferredMode).isNull()
        assertThat(fixture.ownerCommands).hasSize(1)
        assertThat(fixture.refreshes).isEqualTo(0)
    }

    private class CalibrationFixture(
        provisioningConsumed: Boolean = true,
        preferredMode: VideoMode? = null,
    ) {
        val session = MiraxSession(
            SessionSettings(advertisingEnabled = true, provisioningConsumed = provisioningConsumed, preferredMode = preferredMode),
            TestConnectionHistory(),
        ).apply { report(PrivilegeReport(helperRunning = true)) }
        val ownerCommands = mutableListOf<WfdAdvertiseCommand?>()
        val sinkCommands = mutableListOf<WfdAdvertiseCommand?>()
        val published = mutableListOf<SessionSnapshot>()
        val displaysRead = mutableListOf<Int?>()
        var reading = WmSizeReading(1812, 2176)
        var fence = SinkProvisioningFence(1, null, null, null)
        var ownerRevision = 1L
        var nowMs = 1_000L
        var refreshes = 0
            private set

        private val workerTasks = ArrayDeque<Runnable>()
        private val mainTasks = mutableListOf<Pair<Long, () -> Unit>>()
        private val reader = SinkWmReadCoordinator(Executor { workerTasks.addLast(it) }) { displayId ->
            displaysRead += displayId
            reading
        }
        private val calibration = SessionWmCalibration(
            sessionNow = { session },
            nowMs = { nowMs },
            postMain = { delayMs, action -> mainTasks.add(nowMs + delayMs to action) },
            scopeNow = ::scope,
            requestReading = reader::request,
            activeRequest = reader::activeRequest,
        )

        fun commit(): SessionSnapshot {
            calibration.requestProvisioning(session, { 90 }, { commit() }, ::refresh)
            return SessionHost.commitAdvertising(
                session = session,
                syncAdvertise = { command ->
                    ownerCommands += command
                    true
                },
                applyEffects = { session.handle(SessionAction.AcknowledgeEffects) },
                publish = { published += it },
                syncSink = { sinkCommands += it },
                holdAdvertiseHandoff = { calibration.holdAdvertiseHandoff(session, it) },
            )
        }

        fun useThisScreen(displayId: Int): SessionSnapshot =
            calibration.useThisScreen(session, displayId, { 90 }, { commit() }, ::refresh)

        fun requestPlainWmReading(deadlineMs: Long): SinkWmReadCoordinator.Request =
            checkNotNull(reader.request(scope(WfdOwner.HELPER), deadlineMs))

        fun activeWmRequest(): SinkWmReadCoordinator.Request? = reader.activeRequest()

        fun runWorker() = workerTasks.removeFirst().run()

        fun runMain() {
            while (true) {
                val next = mainTasks.withIndex().minByOrNull { it.value.first } ?: return
                if (next.value.first > nowMs) return
                mainTasks.removeAt(next.index).second()
            }
        }

        private fun scope(owner: WfdOwner): SinkWmReadScope = SinkWmReadScope(owner, fence, ownerRevision)

        private fun refresh() {
            refreshes++
        }
    }
}
