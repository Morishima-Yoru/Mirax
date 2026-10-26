package me.trinitrix.mirax.wfd

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.host.HelperClient
import me.trinitrix.mirax.host.HelperUnavailableException
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService
import me.trinitrix.mirax.shell.MiraxShellUserService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Host adapter between the app process and the privileged WFD owner.
 *
 * Owners, in priority order used by the session:
 * - [WfdOwner.SHIZUKU]: shell-UID user-service binder.
 * - [WfdOwner.HELPER]: local-socket helper, root when available or manual ADB shell otherwise.
 *
 * Advertise hand-off alone is not enough for the UI: the bridge watches until
 * the owner reports listening (or times out) and feeds
 * [SessionAction.BeaconListening] / [SessionAction.BeaconFailed].
 */
object WfdOwnerBridge {
    private const val TAG = "MiraxWfdBridge"
    private const val USER_SERVICE_VERSION = 5
    private const val ARM_POLL_MS = 250L
    private const val ARM_TIMEOUT_MS = 4_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val shellService = AtomicReference<IMiraxShellService?>(null)
    private val activeOwner = AtomicReference(WfdOwner.NONE)
    private val armWatchGeneration = AtomicInteger(0)
    private val armReported = AtomicBoolean(false)
    private val helperSyncGeneration = AtomicInteger(0)
    private val pendingHelperTarget = AtomicReference<HelperTarget?>(null)
    private val helperDrainScheduled = AtomicBoolean(false)
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var userServiceConnection: ServiceConnection? = null
    private var pendingShizukuCommand: WfdAdvertiseCommand? = null
    private var appContext: Context? = null

    /** Latest owner command, including Shizuku hand-off after the helper stop barrier. */
    private data class HelperTarget(
        val generation: Int,
        val command: WfdAdvertiseCommand?,
    )

    internal var helperExchange: (command: String, readAll: Boolean) -> String? = ::defaultExchangeHelper
    internal var ioRunner: (Runnable) -> Unit = { task -> ioScope.launch { task.run() } }
    internal var mainPoster: (Runnable) -> Unit = { task -> mainHandler.post(task) }
    internal var delayedMainPoster: (Runnable, Long) -> Unit =
        { task, delayMs -> mainHandler.postDelayed(task, delayMs) }
    internal var clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
    internal var armResultConsumer: (Boolean) -> Unit = ::defaultDispatchArmResult
    internal var shizukuAdvertiser: (Context, WfdAdvertiseCommand) -> Boolean = ::advertiseViaShizuku

    /**
     * Reset bridge state and restore production seams for unit tests.
     */
    internal fun resetForTests() {
        armWatchGeneration.incrementAndGet()
        armReported.set(true)
        helperSyncGeneration.incrementAndGet()
        pendingHelperTarget.set(null)
        helperDrainScheduled.set(false)
        lastCommand.set(null)
        shellService.set(null)
        activeOwner.set(WfdOwner.NONE)
        boundArgs = null
        userServiceConnection = null
        pendingShizukuCommand = null
        appContext = null
        helperExchange = ::defaultExchangeHelper
        ioRunner = { task -> ioScope.launch { task.run() } }
        mainPoster = { task -> mainHandler.post(task) }
        delayedMainPoster = { task, delayMs -> mainHandler.postDelayed(task, delayMs) }
        clockMs = { System.nanoTime() / 1_000_000L }
        armResultConsumer = ::defaultDispatchArmResult
        shizukuAdvertiser = ::advertiseViaShizuku
    }

    /** @return a distinct binding callback whose identity rejects callbacks from older bindings. */
    internal fun createShizukuConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (userServiceConnection !== this || activeOwner.get() != WfdOwner.SHIZUKU) return
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "Shizuku user-service binder missing")
                return
            }
            val service = IMiraxShellService.Stub.asInterface(binder)
            shellService.set(service)
            Log.i(TAG, "Shizuku user-service connected")
            val pending = pendingShizukuCommand
            pendingShizukuCommand = null
            if (pending != null) {
                if (dispatchShizuku(service, pending)) {
                    lastCommand.set(pending)
                    watchArm(pending.owner)
                } else {
                    reportArmResult(listening = false)
                }
            }
            // Pending work such as the one-time wm size read can run now.
            appContext?.let { ctx -> mainHandler.post { SessionHost.commit(ctx) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (userServiceConnection !== this || activeOwner.get() != WfdOwner.SHIZUKU) return
            Log.w(TAG, "Shizuku user-service disconnected")
            shellService.set(null)
            boundArgs = null
            userServiceConnection = null
            // The new service process has not advertised anything yet.
            lastCommand.set(null)
            cancelArmWatch()
            appContext?.let { ctx -> mainHandler.post { SessionHost.commit(ctx) } }
        }
    }.also { userServiceConnection = it }

    /**
     * Keep the Shizuku user-service bound exactly while Shizuku owns WFD.
     *
     * @param context Any context; the application context is kept.
     * @param owner Current WFD owner from the session.
     */
    fun syncOwner(context: Context, owner: WfdOwner) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val previous = activeOwner.getAndSet(owner)
        if (previous == WfdOwner.HELPER && owner != WfdOwner.HELPER) {
            helperSyncGeneration.incrementAndGet()
        }
        if (owner == WfdOwner.SHIZUKU) {
            ensureShizukuBound(appCtx)
        } else if (previous == WfdOwner.SHIZUKU || boundArgs != null) {
            unbindShizukuQuietly()
        }
    }

    /**
     * Sync the session's desired advertise state to the current owner.
     *
     * @param context Application context (package name for the user-service).
     * @param command Desired advertise command, or null to stop.
     * @return false when an advertise request could not be handed to an owner;
     *     stop requests, no-ops, and queued helper hand-offs return true.
     */
    fun sync(context: Context, command: WfdAdvertiseCommand?): Boolean {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        if (lastCommand.get() == command) {
            return true
        }
        if (command == null) {
            cancelArmWatch()
            lastCommand.set(null)
            stopAdvertising()
            return true
        }
        val previousOwner = activeOwner.getAndSet(command.owner)
        if (previousOwner == WfdOwner.SHIZUKU && command.owner != WfdOwner.SHIZUKU) {
            unbindShizukuQuietly()
        }
        when (command.owner) {
            WfdOwner.HELPER -> {
                pendingShizukuCommand = null
                cancelArmWatch()
                armReported.set(false)
                lastCommand.set(command)
                enqueueHelperCommand(command)
                return true
            }
            WfdOwner.SHIZUKU -> {
                pendingShizukuCommand = null
                cancelArmWatch()
                armReported.set(false)
                lastCommand.set(command)
                enqueueHelperCommand(command)
                return true
            }
            WfdOwner.NONE -> {
                cancelArmWatch()
                lastCommand.set(null)
                stopAdvertising()
                return true
            }
        }
    }

    /**
     * Current P2P group state: `DOWN`, `PENDING`, or `UP <source> [OWNER]` with an optional tab and name.
     * Safe to call off the main thread.
     */
    fun groupState(): String? {
        return when (activeOwner.get()) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.groupState()
            } catch (err: Exception) {
                Log.d(TAG, "groupState via Shizuku failed", err)
                null
            }
            WfdOwner.HELPER -> exchangeHelper("GROUP")
            WfdOwner.NONE -> null
        }
    }

    /** End the current connection's P2P group; the owner keeps advertising. */
    fun endSession() {
        when (activeOwner.get()) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.endSession()
            } catch (err: Exception) {
                Log.w(TAG, "endSession via Shizuku failed", err)
            }
            WfdOwner.HELPER -> {
                val generation = helperSyncGeneration.get()
                ioRunner(Runnable {
                    if (activeOwner.get() == WfdOwner.HELPER && helperSyncGeneration.get() == generation) {
                        sendHelperQuietly("END")
                    }
                })
            }
            WfdOwner.NONE -> Unit
        }
    }

    /**
     * Forget all paired devices by deleting all persistent groups.
     * The next connection will require WPS pairing again.
     */
    fun forgetAllPairings() {
        when (activeOwner.get()) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.forgetAllPairings()
            } catch (err: Exception) {
                Log.w(TAG, "forgetAllPairings via Shizuku failed", err)
            }
            WfdOwner.HELPER -> sendHelperQuietly("FORGET_PAIRINGS")
            WfdOwner.NONE -> Unit
        }
    }

    /**
     * Get current pairing state: UNPAIRED, PAIRING, or PAIRED.
     */
    fun pairingState(): String {
        return when (activeOwner.get()) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.pairingState ?: "UNPAIRED"
            } catch (err: Exception) {
                Log.d(TAG, "pairingState via Shizuku failed", err)
                "UNPAIRED"
            }
            WfdOwner.HELPER -> when (val state = exchangeHelper("PAIRING_STATE")) {
                "UNPAIRED", "PAIRING", "PAIRED" -> state
                else -> "UNPAIRED"
            }
            WfdOwner.NONE -> "UNPAIRED"
        }
    }

    /**
     * Run `wm size` inside the owner process.
     *
     * Args:
     *     displayId: Display to query, or null for plain `wm size`.
     *
     * Returns:
     *     The command output, or null when no owner can answer yet.
     */
    fun wmSize(displayId: Int?): String? {
        val id = displayId ?: -1
        return when (activeOwner.get()) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.wmSize(id)
            } catch (err: Exception) {
                Log.w(TAG, "wm size via Shizuku failed", err)
                null
            }
            WfdOwner.HELPER -> exchangeHelper(if (displayId == null) "WM_SIZE" else "WM_SIZE $displayId", readAll = true)
            WfdOwner.NONE -> null
        }
    }

    private fun stopAdvertising() {
        pendingShizukuCommand = null
        enqueueHelperCommand(null)
        try {
            shellService.get()?.stopAdvertise()
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku stopAdvertise failed", err)
        }
    }

    /**
     * Enqueue an owner hand-off or stop request on the conflated helper queue.
     *
     * @param command desired owner command, or null to stop helper advertising
     */
    private fun enqueueHelperCommand(command: WfdAdvertiseCommand?) {
        val generation = helperSyncGeneration.incrementAndGet()
        pendingHelperTarget.set(HelperTarget(generation, command))
        scheduleHelperDrain()
    }

    /**
     * Schedule one helper drain task when none is running.
     */
    private fun scheduleHelperDrain() {
        if (!helperDrainScheduled.compareAndSet(false, true)) {
            return
        }
        ioRunner(Runnable { drainHelperCommands() })
    }

    /**
     * Drain conflated helper targets off the caller thread with generation guards.
     */
    private fun drainHelperCommands() {
        try {
            while (true) {
                val target = pendingHelperTarget.getAndSet(null) ?: break
                val command = target.command
                if (command == null) {
                    sendHelperQuietly("STOP_ADVERTISE")
                    continue
                }
                if (command.owner == WfdOwner.SHIZUKU) {
                    val stopped = exchangeHelper("STOP_ADVERTISE") == "OK"
                    mainPoster(Runnable {
                        if (isCurrentHelperTarget(target)) {
                            val context = appContext ?: return@Runnable
                            if (stopped && shizukuAdvertiser(context, command)) {
                                watchArm(WfdOwner.SHIZUKU)
                            } else {
                                reportArmResult(listening = false)
                            }
                        }
                    })
                    continue
                }
                if (!isCurrentHelperTarget(target)) {
                    continue
                }
                val accepted = sendHelperAdvertise(command)
                if (!isCurrentHelperTarget(target)) {
                    if (pendingHelperTarget.get() == null &&
                        (activeOwner.get() != WfdOwner.HELPER || lastCommand.get()?.owner != WfdOwner.HELPER)
                    ) {
                        sendHelperQuietly("STOP_ADVERTISE")
                    }
                    continue
                }
                mainPoster(Runnable {
                    if (isCurrentHelperTarget(target)) {
                        if (accepted) {
                            watchArm(WfdOwner.HELPER)
                        } else {
                            reportArmResult(listening = false)
                        }
                    }
                })
            }
        } finally {
            helperDrainScheduled.set(false)
            if (pendingHelperTarget.get() != null) {
                scheduleHelperDrain()
            }
        }
    }

    /**
     * Check whether a drained helper target is still current.
     *
     * @param target queued helper target to validate
     * @return true when no stop, owner change, or newer command superseded it
     */
    private fun isCurrentHelperTarget(target: HelperTarget): Boolean {
        return helperSyncGeneration.get() == target.generation &&
            activeOwner.get() == target.command?.owner &&
            lastCommand.get() == target.command
    }

    /**
     * Start watching the owner until it reports listening or times out.
     *
     * @param owner owner expected to arm the listen beacon
     */
    private fun watchArm(owner: WfdOwner) {
        armReported.set(false)
        val generation = armWatchGeneration.incrementAndGet()
        pollArm(generation, owner, clockMs() + ARM_TIMEOUT_MS)
    }

    /**
     * Schedule one arm poll; helper listening checks run off the main thread.
     *
     * @param generation watch generation guarding stale polls
     * @param owner owner expected to arm the listen beacon
     * @param deadlineMs monotonic deadline for the watch
     */
    private fun pollArm(generation: Int, owner: WfdOwner, deadlineMs: Long) {
        delayedMainPoster(
            Runnable {
                if (armWatchGeneration.get() != generation) {
                    return@Runnable
                }
                if (owner == WfdOwner.HELPER) {
                    pollHelperArmOffMain(generation, deadlineMs)
                    return@Runnable
                }
                handleArmPollResult(generation, owner, deadlineMs, ownerListening(owner))
            },
            ARM_POLL_MS,
        )
    }

    /**
     * Query helper listening state off the main thread and resume on the main thread.
     *
     * @param generation watch generation guarding stale callbacks
     * @param deadlineMs monotonic deadline for the watch
     */
    private fun pollHelperArmOffMain(generation: Int, deadlineMs: Long) {
        ioRunner(
            Runnable {
                if (armWatchGeneration.get() != generation || activeOwner.get() != WfdOwner.HELPER) {
                    return@Runnable
                }
                val listening = ownerListening(WfdOwner.HELPER)
                mainPoster(
                    Runnable {
                        handleArmPollResult(generation, WfdOwner.HELPER, deadlineMs, listening)
                    },
                )
            },
        )
    }

    /**
     * Handle one arm poll result on the main thread with generation guards.
     *
     * @param generation watch generation guarding stale results
     * @param owner owner expected to arm the listen beacon
     * @param deadlineMs monotonic deadline for the watch
     * @param listening true when the owner reports listening
     */
    private fun handleArmPollResult(
        generation: Int,
        owner: WfdOwner,
        deadlineMs: Long,
        listening: Boolean,
    ) {
        if (armWatchGeneration.get() != generation || activeOwner.get() != owner) {
            return
        }
        if (listening) {
            reportArmResult(listening = true)
            return
        }
        if (clockMs() >= deadlineMs) {
            Log.e(TAG, "arm timed out for $owner")
            reportArmResult(listening = false)
            return
        }
        pollArm(generation, owner, deadlineMs)
    }

    private fun ownerListening(owner: WfdOwner): Boolean {
        return when (owner) {
            WfdOwner.SHIZUKU -> try {
                shellService.get()?.isListening == true
            } catch (err: Exception) {
                Log.d(TAG, "isListening via Shizuku failed", err)
                false
            }
            WfdOwner.HELPER -> exchangeHelper("LISTENING") == "YES"
            WfdOwner.NONE -> false
        }
    }

    /**
     * Cancel the current arm watch and suppress further arm results.
     */
    private fun cancelArmWatch() {
        armReported.set(true)
        armWatchGeneration.incrementAndGet()
    }

    /**
     * Report one arm result for the current watch generation.
     *
     * @param listening true when the owner reports listening
     */
    private fun reportArmResult(listening: Boolean) {
        if (!armReported.compareAndSet(false, true)) {
            return
        }
        val generation = armWatchGeneration.incrementAndGet()
        val owner = activeOwner.get()
        val command = lastCommand.get()
        mainPoster(
            Runnable {
                if (armWatchGeneration.get() == generation && activeOwner.get() == owner) {
                    try {
                        armResultConsumer(listening)
                    } finally {
                        // Let the session stop the failed owner before making an identical retry eligible.
                        if (!listening) lastCommand.compareAndSet(command, null)
                    }
                }
            },
        )
    }

    /**
     * Dispatch an arm result into the session on the main thread.
     *
     * @param listening true for [SessionAction.BeaconListening], false for [SessionAction.BeaconFailed]
     */
    private fun defaultDispatchArmResult(listening: Boolean) {
        val ctx = appContext ?: return
        val action =
            if (listening) {
                SessionAction.BeaconListening
            } else {
                SessionAction.BeaconFailed
            }
        SessionHost.dispatchConnectionEvent(ctx, action)
    }

    /**
     * @return true when the advertise request was handed to a ready owner
     */
    private fun advertiseViaShizuku(context: Context, command: WfdAdvertiseCommand): Boolean {
        if (!shizukuReady()) {
            Log.w(TAG, "Shizuku not ready for advertise")
            return false
        }
        val service = shellService.get()
        if (service != null) {
            return dispatchShizuku(service, command)
        }
        pendingShizukuCommand = command
        ensureShizukuBound(context)
        // Bind is async; the pending command is applied in onServiceConnected.
        return boundArgs != null
    }

    private fun ensureShizukuBound(context: Context) {
        if (boundArgs != null || !shizukuReady()) {
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, MiraxShellUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("wfd")
                .version(USER_SERVICE_VERSION)
            Shizuku.bindUserService(args, createShizukuConnection())
            boundArgs = args
        } catch (err: Exception) {
            Log.w(TAG, "bindUserService failed", err)
        }
    }

    private fun dispatchShizuku(service: IMiraxShellService, command: WfdAdvertiseCommand): Boolean {
        return try {
            service.advertise(command.broadcastName, encodeModes(command.modes))
            true
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku advertise failed", err)
            false
        }
    }

    private fun sendHelperAdvertise(command: WfdAdvertiseCommand): Boolean {
        val line = "ADVERTISE\t${sanitizeName(command.broadcastName)}\t${encodeModes(command.modes)}"
        return exchangeHelper(line) == "OK"
    }

    private fun sendHelperQuietly(command: String) {
        if (exchangeHelper(command) != "OK") Log.w(TAG, "helper command failed: $command")
    }

    private fun exchangeHelper(command: String, readAll: Boolean = false): String? {
        return helperExchange(command, readAll)
    }

    /**
     * Exchange one helper command on the calling worker thread.
     *
     * @param command command without the trailing newline
     * @param readAll whether the response may contain multiple lines
     * @return helper response, or null when the exchange fails
     */
    private fun defaultExchangeHelper(command: String, readAll: Boolean = false): String? {
        return try {
            HelperClient.exchange(command, readAll).also { response ->
                if (response.startsWith("ERR")) Log.w(TAG, "helper rejected $command: $response")
            }
        } catch (err: HelperUnavailableException) {
            if (command == "STOP_ADVERTISE") {
                Log.d(TAG, "Helper absent; already stopped", err)
                return "OK"
            }
            Log.d(TAG, "Helper absent: $command", err)
            null
        } catch (err: IOException) {
            Log.w(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun unbindShizukuQuietly() {
        pendingShizukuCommand = null
        val args = boundArgs
        val connection = userServiceConnection
        boundArgs = null
        userServiceConnection = null
        shellService.set(null)
        lastCommand.set(null)
        if (args != null && connection != null) {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (err: Exception) {
                Log.d(TAG, "unbindUserService failed", err)
            }
        }
    }

    private fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (err: Exception) {
            false
        }
    }

    private fun sanitizeName(name: String): String = name.replace('\t', ' ').replace('\n', ' ')

    /**
     * Encode modes for the owner wire format (ASCII; not the UI × glyph).
     */
    fun encodeModes(modes: Set<VideoMode>): String {
        return modes
            .sortedWith(compareBy({ it.width }, { it.height }, { it.refreshHz }))
            .joinToString(",") { "${it.width}x${it.height}@${it.refreshHz}" }
    }
}
