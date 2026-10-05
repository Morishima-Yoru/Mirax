package me.trinitrix.mirax.wfd

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService
import me.trinitrix.mirax.shell.MiraxShellUserService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    private const val USER_SERVICE_VERSION = 4
    private const val ARM_POLL_MS = 250L
    private const val ARM_TIMEOUT_MS = 4_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val shellService = AtomicReference<IMiraxShellService?>(null)
    private val activeOwner = AtomicReference(WfdOwner.NONE)
    private val armWatchGeneration = AtomicInteger(0)
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var pendingShizukuCommand: WfdAdvertiseCommand? = null
    private var appContext: Context? = null

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
                    lastCommand.compareAndSet(pending, null)
                    reportArmResult(listening = false)
                }
            }
            // Pending work such as the one-time wm size read can run now.
            appContext?.let { ctx -> mainHandler.post { SessionHost.commit(ctx) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user-service disconnected")
            shellService.set(null)
            boundArgs = null
            // The new service process has not advertised anything yet.
            lastCommand.set(null)
            cancelArmWatch()
            appContext?.let { ctx -> mainHandler.post { SessionHost.commit(ctx) } }
        }
    }

    /**
     * Keep the Shizuku user-service bound exactly while Shizuku owns WFD.
     *
     * Args:
     *     context: Any context; the application context is kept.
     *     owner: Current WFD owner from the session.
     */
    fun syncOwner(context: Context, owner: WfdOwner) {
        appContext = context.applicationContext
        val previous = activeOwner.getAndSet(owner)
        if (owner == WfdOwner.SHIZUKU) {
            ensureShizukuBound(context.applicationContext)
        } else if (previous == WfdOwner.SHIZUKU || boundArgs != null) {
            unbindShizukuQuietly()
        }
    }

    /**
     * Sync the session's desired advertise state to the current owner.
     *
     * Args:
     *     context: Application context (package name for the user-service).
     *     command: Desired advertise command, or null to stop.
     *
     * Returns:
     *     false when an advertise request could not be handed to an owner.
     *     Stop requests and no-ops return true.
     */
    fun sync(context: Context, command: WfdAdvertiseCommand?): Boolean {
        appContext = context.applicationContext
        if (lastCommand.get() == command) {
            return true
        }
        if (command == null) {
            cancelArmWatch()
            stopAdvertising()
            lastCommand.set(null)
            return true
        }
        val applied = when (command.owner) {
            WfdOwner.HELPER -> sendHelperAdvertise(command)
            WfdOwner.SHIZUKU -> {
                sendHelperQuietly("STOP_ADVERTISE")
                advertiseViaShizuku(context.applicationContext, command)
            }
            WfdOwner.NONE -> {
                cancelArmWatch()
                stopAdvertising()
                true
            }
        }
        if (!applied) {
            cancelArmWatch()
            return false
        }
        lastCommand.set(command)
        watchArm(command.owner)
        return true
    }

    /**
     * Current P2P group state from the owner: `DOWN`, `PENDING`, or `UP <source>`.
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
            WfdOwner.HELPER -> sendHelperQuietly("END")
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
        sendHelperQuietly("STOP_ADVERTISE")
        try {
            shellService.get()?.stopAdvertise()
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku stopAdvertise failed", err)
        }
    }

    private fun watchArm(owner: WfdOwner) {
        val generation = armWatchGeneration.incrementAndGet()
        pollArm(generation, owner, System.currentTimeMillis() + ARM_TIMEOUT_MS)
    }

    private fun pollArm(generation: Int, owner: WfdOwner, deadlineMs: Long) {
        mainHandler.postDelayed(
            {
                if (armWatchGeneration.get() != generation) {
                    return@postDelayed
                }
                if (ownerListening(owner)) {
                    reportArmResult(listening = true)
                    return@postDelayed
                }
                if (System.currentTimeMillis() >= deadlineMs) {
                    Log.e(TAG, "arm timed out for $owner")
                    reportArmResult(listening = false)
                    return@postDelayed
                }
                pollArm(generation, owner, deadlineMs)
            },
            ARM_POLL_MS,
        )
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

    private fun cancelArmWatch() {
        armWatchGeneration.incrementAndGet()
    }

    private fun reportArmResult(listening: Boolean) {
        cancelArmWatch()
        val ctx = appContext ?: return
        val action =
            if (listening) {
                SessionAction.BeaconListening
            } else {
                SessionAction.BeaconFailed
            }
        mainHandler.post {
            SessionHost.dispatchConnectionEvent(ctx, action)
        }
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
            Shizuku.bindUserService(args, userServiceConnection)
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
        return exchangeHelper(line) != null
    }

    private fun sendHelperQuietly(command: String) {
        exchangeHelper(command)
    }

    private fun exchangeHelper(command: String, readAll: Boolean = false): String? {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                socket.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    if (readAll) reader.readText() else reader.readLine()
                }
            }
        } catch (err: Exception) {
            Log.d(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun unbindShizukuQuietly() {
        pendingShizukuCommand = null
        val args = boundArgs
        boundArgs = null
        shellService.set(null)
        lastCommand.set(null)
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, userServiceConnection, true)
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
