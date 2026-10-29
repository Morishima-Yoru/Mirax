package me.trinitrix.mirax.wfd

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.host.PrivilegeProbe
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.TestConnectionHistory
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import org.junit.After
import org.junit.Test

/**
 * Host-side wire helpers and a recording owner for the advertise command.
 * Does not exercise real Wi-Fi Direct APIs on the JVM.
 */
class WfdOwnerBridgeTest {

    @Test
    fun encodeModes_usesAsciiAndStableOrder() {
        val encoded = WfdOwnerBridge.encodeModes(
            setOf(
                VideoMode(1920, 1080, 60),
                VideoMode(1280, 720, 60),
                VideoMode(1920, 1080, 30),
            ),
        )
        assertThat(encoded).isEqualTo("1280x720@60,1920x1080@30,1920x1080@60")
        assertThat(encoded).doesNotContain("×")
    }

    @Test
    fun recordingOwner_receivesSessionAdvertiseCommand() {
        val session = MiraxSession(SessionSettings(advertisingEnabled = true), TestConnectionHistory())
        session.report(PrivilegeReport(helperRunning = true))
        session.report(DeviceNameReport("Desk Fold"))
        val owner = RecordingWfdOwner()
        owner.apply(session.snapshot().wfdAdvertise)
        assertThat(owner.last).isEqualTo(
            WfdAdvertiseCommand(
                owner = WfdOwner.HELPER,
                broadcastName = "Desk Fold",
                modes = session.snapshot().nextAdvertisementModes,
            ),
        )
        owner.apply(null)
        assertThat(owner.last).isNull()
    }

    @Test
    fun renameAdvertiseCommand_isDistinctSoOwnerSyncWouldReapply() {
        val a = WfdAdvertiseCommand(
            owner = WfdOwner.HELPER,
            broadcastName = "Old",
            modes = setOf(VideoMode(1920, 1080, 60)),
        )
        val b = a.copy(broadcastName = "New")
        assertThat(a).isNotEqualTo(b)
        assertThat(b.broadcastName).isEqualTo("New")
    }

    @After
    fun tearDown() {
        WfdOwnerBridge.resetForTests()
    }

    @Test
    fun helperSync_isOffMainAndConflated_andGuardsStaleFailureAfterOff() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val exchanged = mutableListOf<String>()
        val armResults = mutableListOf<Boolean>()
        var onAdvertiseExchange: (() -> String?)? = null

        WfdOwnerBridge.ioRunner = { task -> ioTasks.addLast(task) }
        WfdOwnerBridge.mainPoster = { task -> mainTasks.addLast(task) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.armResultConsumer = { listening -> armResults += listening }
        WfdOwnerBridge.helperExchange = { command, _ ->
            exchanged += command
            if (command.startsWith("ADVERTISE")) {
                onAdvertiseExchange?.invoke() ?: "OK"
            } else {
                "OK"
            }
        }

        val cmd1 = WfdAdvertiseCommand(
            owner = WfdOwner.HELPER,
            broadcastName = "First",
            modes = setOf(VideoMode(1920, 1080, 60)),
        )
        val cmd2 = cmd1.copy(broadcastName = "Second")

        // Hand-off returns true immediately without running helper socket I/O on caller thread.
        assertThat(WfdOwnerBridge.sync(context, cmd1)).isTrue()
        assertThat(exchanged).isEmpty()

        // Rapid update conflates pending helper commands to the latest state.
        assertThat(WfdOwnerBridge.sync(context, cmd2)).isTrue()
        while (ioTasks.isNotEmpty()) {
            ioTasks.removeFirst().run()
        }
        assertThat(exchanged).containsExactly("ADVERTISE	Second	1920x1080@60")

        // If user turns broadcast off while a slow ADVERTISE is in flight, stale failure must not
        // emit BeaconFailed and STOP_ADVERTISE must still run.
        exchanged.clear()
        val cmd3 = cmd1.copy(broadcastName = "Third")
        onAdvertiseExchange = {
            assertThat(WfdOwnerBridge.sync(context, null)).isTrue()
            "ERR failed"
        }
        assertThat(WfdOwnerBridge.sync(context, cmd3)).isTrue()
        while (ioTasks.isNotEmpty()) {
            ioTasks.removeFirst().run()
        }
        while (mainTasks.isNotEmpty()) {
            mainTasks.removeFirst().run()
        }
        assertThat(exchanged).containsExactly(
            "ADVERTISE	Third	1920x1080@60",
            "STOP_ADVERTISE",
        ).inOrder()
        assertThat(armResults).isEmpty()
    }

    @Test
    fun helperAdvertiseFailure_postsBeaconFailedOnMainOnce_andAllowsRetry() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val armResults = mutableListOf<Boolean>()
        var advertiseResponse = "ERR boom"

        WfdOwnerBridge.ioRunner = { task -> ioTasks.addLast(task) }
        WfdOwnerBridge.mainPoster = { task -> mainTasks.addLast(task) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.armResultConsumer = { listening -> armResults += listening }
        WfdOwnerBridge.helperExchange = { command, _ ->
            if (command.startsWith("ADVERTISE")) advertiseResponse else "OK"
        }

        val cmd = WfdAdvertiseCommand(
            owner = WfdOwner.HELPER,
            broadcastName = "Desk",
            modes = setOf(VideoMode(1280, 720, 60)),
        )
        assertThat(WfdOwnerBridge.sync(context, cmd)).isTrue()
        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        assertThat(armResults).isEmpty()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(armResults).containsExactly(false)

        // A completed failed hand-off must not suppress an identical retry as an accepted no-op.
        advertiseResponse = "OK"
        assertThat(WfdOwnerBridge.sync(context, cmd)).isTrue()
        assertThat(ioTasks).isNotEmpty()
    }

    @Test
    fun pollArm_helperListeningRunsOffMain_andVerifiesWatchGenerationOnMain() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val delayedTasks = ArrayDeque<Runnable>()
        val exchanged = mutableListOf<String>()
        val armResults = mutableListOf<Boolean>()

        WfdOwnerBridge.ioRunner = { task -> ioTasks.addLast(task) }
        WfdOwnerBridge.mainPoster = { task -> mainTasks.addLast(task) }
        WfdOwnerBridge.delayedMainPoster = { task, _ -> delayedTasks.addLast(task) }
        WfdOwnerBridge.armResultConsumer = { listening -> armResults += listening }
        WfdOwnerBridge.helperExchange = { command, _ ->
            exchanged += command
            if (command == "LISTENING") "YES" else "OK"
        }

        val cmd = WfdAdvertiseCommand(
            owner = WfdOwner.HELPER,
            broadcastName = "Desk",
            modes = setOf(VideoMode(1280, 720, 60)),
        )
        WfdOwnerBridge.sync(context, cmd)
        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        exchanged.clear()

        // Running the scheduled poll on main must not block on LISTENING directly; it schedules IO.
        assertThat(delayedTasks).isNotEmpty()
        delayedTasks.removeFirst().run()
        assertThat(exchanged).doesNotContain("LISTENING")
        assertThat(ioTasks).isNotEmpty()

        // Run LISTENING on IO, then cancel before main processes the callback -> stale result ignored.
        ioTasks.removeFirst().run()
        assertThat(exchanged).containsExactly("LISTENING")
        WfdOwnerBridge.sync(context, null)
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(armResults).isEmpty()
    }

    @Test
    fun privilegeProbe_isHelperRunningInlinesHelperUid_andKeeps250msUiTimeout() {
        val recordedTimeouts = mutableListOf<Int>()
        val prev = PrivilegeProbe.exchangeCommand
        try {
            PrivilegeProbe.exchangeCommand = { command, timeoutMs ->
                assertThat(command).isEqualTo("UID")
                recordedTimeouts += timeoutMs
                "0"
            }
            assertThat(PrivilegeProbe.isHelperRunning()).isTrue()
            assertThat(PrivilegeProbe.UI_PROBE_TIMEOUT_MS).isEqualTo(250)
            assertThat(PrivilegeProbe.helperUid(PrivilegeProbe.UI_PROBE_TIMEOUT_MS)).isEqualTo(0)
            assertThat(recordedTimeouts).containsExactly(Helper.COMMAND_TIMEOUT_MS, 250).inOrder()
        } finally {
            PrivilegeProbe.exchangeCommand = prev
        }
    }

    @Test
    fun queuedFailure_cannot_close_a_newer_advertise_generation() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val results = mutableListOf<Boolean>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.armResultConsumer = { results += it }
        WfdOwnerBridge.helperExchange = { _, _ -> "ERR rejected" }
        val command = WfdAdvertiseCommand(WfdOwner.HELPER, "Old", setOf(VideoMode(1280, 720, 60)))

        WfdOwnerBridge.sync(context, command)
        ioTasks.removeFirst().run()
        mainTasks.removeFirst().run()
        assertThat(results).isEmpty()
        WfdOwnerBridge.sync(context, command.copy(broadcastName = "New"))
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()

        assertThat(results).isEmpty()
    }

    @Test
    fun helperListeningWatch_starts_after_advertise_acknowledgment() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val delayedTasks = ArrayDeque<Runnable>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { task, _ -> delayedTasks.addLast(task) }
        WfdOwnerBridge.helperExchange = { _, _ -> "OK" }
        val command = WfdAdvertiseCommand(WfdOwner.HELPER, "Desk", setOf(VideoMode(1280, 720, 60)))

        WfdOwnerBridge.sync(context, command)
        assertThat(delayedTasks).isEmpty()
        ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(delayedTasks).hasSize(1)
    }

    @Test
    fun shizukuAdvertise_waits_for_helper_stop_to_finish() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.shizukuAdvertiser = { _, _ -> calls += "SHIZUKU_ADVERTISE"; true }
        val command = WfdAdvertiseCommand(WfdOwner.SHIZUKU, "Desk", setOf(VideoMode(1280, 720, 60)))

        WfdOwnerBridge.sync(context, command)
        assertThat(calls).isEmpty()
        ioTasks.removeFirst().run()
        assertThat(calls).containsExactly("STOP_ADVERTISE")
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(calls).containsExactly("STOP_ADVERTISE", "SHIZUKU_ADVERTISE").inOrder()
    }

    @Test
    fun unconfirmedHelperStop_does_not_start_Shizuku() {
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val results = mutableListOf<Boolean>()
        var started = false
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.helperExchange = { _, _ -> "ERR stop timed out" }
        WfdOwnerBridge.shizukuAdvertiser = { _, _ -> started = true; true }
        WfdOwnerBridge.armResultConsumer = { results += it }
        WfdOwnerBridge.sync(ContextWrapper(null),
            WfdAdvertiseCommand(WfdOwner.SHIZUKU, "Desk", setOf(VideoMode(1280, 720, 60))))
        ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(started).isFalse()
        assertThat(results).containsExactly(false)
    }

    @Test
    fun endSession_does_not_block_the_main_failure_effect() {
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { it.run() }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.sync(ContextWrapper(null),
            WfdAdvertiseCommand(WfdOwner.HELPER, "Desk", setOf(VideoMode(1280, 720, 60))))
        ioTasks.removeFirst().run()
        calls.clear()
        WfdOwnerBridge.endSession()
        assertThat(calls).isEmpty()
        ioTasks.removeFirst().run()
        assertThat(calls).containsExactly("END")
    }

    @Test
    fun oldShizukuDisconnect_cannot_cancel_helper_listening_watch() {
        val stale = WfdOwnerBridge.createShizukuConnection()
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        val results = mutableListOf<Boolean>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { task, _ -> delayed.addLast(task) }
        WfdOwnerBridge.helperExchange = { command, _ -> if (command == "LISTENING") "YES" else "OK" }
        WfdOwnerBridge.armResultConsumer = { results += it }
        WfdOwnerBridge.sync(ContextWrapper(null),
            WfdAdvertiseCommand(WfdOwner.HELPER, "Desk", setOf(VideoMode(1280, 720, 60))))
        ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        stale.onServiceDisconnected(null)
        delayed.removeFirst().run()
        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        assertThat(results).containsExactly(true)
    }

    @Test
    fun oldShizukuBindingDisconnect_cannot_clear_a_newer_binding_command() {
        val ioTasks = ArrayDeque<Runnable>()
        val mainTasks = ArrayDeque<Runnable>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.mainPoster = { mainTasks.addLast(it) }
        WfdOwnerBridge.delayedMainPoster = { _, _ -> }
        WfdOwnerBridge.helperExchange = { _, _ -> "OK" }
        WfdOwnerBridge.shizukuAdvertiser = { _, _ -> true }
        val command = WfdAdvertiseCommand(WfdOwner.SHIZUKU, "Desk", setOf(VideoMode(1280, 720, 60)))
        WfdOwnerBridge.sync(ContextWrapper(null), command)
        val stale = WfdOwnerBridge.createShizukuConnection()
        WfdOwnerBridge.createShizukuConnection()
        ioTasks.removeFirst().run()
        while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run()
        stale.onServiceDisconnected(null)
        WfdOwnerBridge.sync(ContextWrapper(null), command)
        assertThat(ioTasks).isEmpty()
    }

    /** Dispatch only the fixed helper launch command through the IO worker. */
    @Test
    fun projectionLaunch_isOffMain_andUsesOnlyFixedHelperCommand() {
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        var guardChecks = 0
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, readAll ->
            assertThat(readAll).isFalse()
            calls += command
            "OK"
        }
        WfdOwnerBridge.syncOwner(ContextWrapper(null), WfdOwner.HELPER)

        WfdOwnerBridge.bringProjectionToFront { guardChecks++; true }
        assertThat(calls).isEmpty()
        assertThat(guardChecks).isEqualTo(0)
        assertThat(ioTasks).hasSize(1)
        ioTasks.removeFirst().run()

        assertThat(guardChecks).isEqualTo(1)
        assertThat(calls).containsExactly("SHOW_PROJECTION")
    }

    /** Recheck connection validity when a queued launch reaches its worker. */
    @Test
    fun projectionLaunch_rechecksSessionGuard_onWorker() {
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        var connected = true
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.syncOwner(ContextWrapper(null), WfdOwner.HELPER)

        WfdOwnerBridge.bringProjectionToFront { connected }
        connected = false
        ioTasks.removeFirst().run()

        assertThat(calls).isEmpty()
    }

    /** Drop a queued launch when another privileged owner has taken over. */
    @Test
    fun projectionLaunch_dropsRequest_whenCapturedOwnerIsNoLongerCurrent() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.syncOwner(context, WfdOwner.HELPER)

        WfdOwnerBridge.bringProjectionToFront { true }
        WfdOwnerBridge.syncOwner(context, WfdOwner.NONE)
        ioTasks.removeFirst().run()

        assertThat(calls).isEmpty()
    }

    /** Recheck the captured owner after evaluating the connection guard. */
    @Test
    fun projectionLaunch_checksOwner_afterEvaluatingSessionGuard() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.syncOwner(context, WfdOwner.HELPER)

        WfdOwnerBridge.bringProjectionToFront {
            WfdOwnerBridge.syncOwner(context, WfdOwner.NONE)
            true
        }
        ioTasks.removeFirst().run()

        assertThat(calls).isEmpty()
    }

    /** Keep later launches usable after helper rejection, a missing reply, or an exception. */
    @Test
    fun projectionLaunch_rejectionMissingReplyAndExceptions_doNotPreventNextRequest() {
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.syncOwner(ContextWrapper(null), WfdOwner.HELPER)

        for (response in listOf("ERR unknown", null)) {
            WfdOwnerBridge.helperExchange = { command, _ -> calls += command; response }
            WfdOwnerBridge.bringProjectionToFront { true }
            ioTasks.removeFirst().run()
        }
        WfdOwnerBridge.helperExchange = { command, _ ->
            calls += command
            throw java.io.IOException("helper disconnected")
        }
        WfdOwnerBridge.bringProjectionToFront { true }
        ioTasks.removeFirst().run()
        WfdOwnerBridge.bringProjectionToFront { throw IllegalStateException("session unavailable") }
        ioTasks.removeFirst().run()
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.bringProjectionToFront { true }
        ioTasks.removeFirst().run()

        assertThat(calls).containsExactly(
            "SHOW_PROJECTION", "SHOW_PROJECTION", "SHOW_PROJECTION", "SHOW_PROJECTION",
        )
    }

    /** Never substitute the helper when the selected owner or Shizuku service is unavailable. */
    @Test
    fun projectionLaunch_withoutOwnerOrShizukuService_doesNotFallbackToHelper() {
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }

        for (owner in listOf(WfdOwner.NONE, WfdOwner.SHIZUKU)) {
            WfdOwnerBridge.syncOwner(ContextWrapper(null), owner)
            WfdOwnerBridge.bringProjectionToFront { true }
            ioTasks.removeFirst().run()
        }

        assertThat(calls).isEmpty()
    }

    /** Use only the selected Shizuku owner across launch failure or owner changes. */
    @Test
    fun projectionLaunch_usesShizuku_withoutFallbackAfterFailureOrOwnerChange() {
        val context = ContextWrapper(null)
        val ioTasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        var failLaunch = false
        WfdOwnerBridge.ioRunner = { ioTasks.addLast(it) }
        WfdOwnerBridge.helperExchange = { command, _ -> calls += command; "OK" }
        WfdOwnerBridge.shizukuProjectionLauncher = {
            calls += "SHIZUKU_SHOW_PROJECTION"
            if (failLaunch) throw IllegalStateException("binder disconnected")
        }
        WfdOwnerBridge.syncOwner(context, WfdOwner.SHIZUKU)

        for (fail in listOf(false, true, false)) {
            failLaunch = fail
            WfdOwnerBridge.bringProjectionToFront { true }
            ioTasks.removeFirst().run()
        }
        WfdOwnerBridge.bringProjectionToFront { true }
        WfdOwnerBridge.syncOwner(context, WfdOwner.HELPER)
        ioTasks.removeFirst().run()

        assertThat(calls).containsExactly(
            "SHIZUKU_SHOW_PROJECTION", "SHIZUKU_SHOW_PROJECTION", "SHIZUKU_SHOW_PROJECTION",
        )
    }

    /**
     * Stand-in for the privileged owner process: records the command the
     * session emits without touching WifiP2pManager.
     */
    private class RecordingWfdOwner {
        var last: WfdAdvertiseCommand? = null
            private set

        fun apply(command: WfdAdvertiseCommand?) {
            last = command
        }
    }
}
