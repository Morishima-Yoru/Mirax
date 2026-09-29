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
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService
import me.trinitrix.mirax.shell.MiraxShellUserService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Host adapter between the app process and the privileged WFD owner.
 *
 * The app process only talks over a local socket (helper) or a Shizuku
 * user-service binder. It never calls `setWfdInfo` or other
 * `CONFIGURE_WIFI_DISPLAY` APIs. While Shizuku owns WFD the user-service stays
 * bound, so `wm size` and the P2P group state are read inside it.
 */
object WfdOwnerBridge {
    private const val TAG = "MiraxWfdBridge"
    private const val USER_SERVICE_VERSION = 3

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val shellService = AtomicReference<IMiraxShellService?>(null)
    private val activeOwner = AtomicReference(WfdOwner.NONE)
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
            if (pending != null && !dispatchShizuku(service, pending)) {
                lastCommand.compareAndSet(pending, null)
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
     */
    fun sync(context: Context, command: WfdAdvertiseCommand?) {
        appContext = context.applicationContext
        if (lastCommand.get() == command) {
            return
        }
        if (command == null) {
            stopAdvertising()
            lastCommand.set(null)
            return
        }
        val applied = when (command.owner) {
            WfdOwner.HELPER -> sendHelperAdvertise(command)
            WfdOwner.SHIZUKU -> {
                sendHelperQuietly("STOP_ADVERTISE")
                advertiseViaShizuku(context.applicationContext, command)
            }
            WfdOwner.NONE -> {
                stopAdvertising()
                true
            }
        }
        if (applied) {
            lastCommand.set(command)
        }
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
