package me.trinitrix.mirax.wfd

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.util.Log
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
 * Host adapter that applies [WfdAdvertiseCommand] to the privileged owner.
 *
 * The app process only talks over a local socket (helper) or a Shizuku
 * user-service binder. It never calls `setWfdInfo` or other
 * `CONFIGURE_WIFI_DISPLAY` APIs.
 */
object WfdOwnerBridge {
    private const val TAG = "MiraxWfdBridge"

    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val shellService = AtomicReference<IMiraxShellService?>(null)
    private var userServiceBound = false
    private var pendingShizukuCommand: WfdAdvertiseCommand? = null
    private var boundArgs: Shizuku.UserServiceArgs? = null

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "Shizuku user-service binder missing")
                return
            }
            val service = IMiraxShellService.Stub.asInterface(binder)
            shellService.set(service)
            userServiceBound = true
            val pending = pendingShizukuCommand
            if (pending != null) {
                pendingShizukuCommand = null
                if (!dispatchShizuku(service, pending)) {
                    // Allow a later sync() with the same command to retry.
                    lastCommand.compareAndSet(pending, null)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService.set(null)
            userServiceBound = false
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
        if (lastCommand.get() == command) {
            return
        }
        if (command == null) {
            stopAll()
            lastCommand.set(null)
            return
        }
        val applied = when (command.owner) {
            WfdOwner.HELPER -> {
                unbindShizukuQuietly()
                sendHelperAdvertise(command)
            }
            WfdOwner.SHIZUKU -> {
                sendHelperStopAdvertiseQuietly()
                ensureShizukuAndAdvertise(context.applicationContext, command)
            }
            WfdOwner.NONE -> {
                stopAll()
                true
            }
        }
        if (applied) {
            lastCommand.set(command)
        }
    }

    private fun stopAll() {
        pendingShizukuCommand = null
        sendHelperStopAdvertiseQuietly()
        val service = shellService.get()
        if (service != null) {
            try {
                service.stopAdvertise()
            } catch (err: Exception) {
                Log.w(TAG, "Shizuku stopAdvertise failed", err)
            }
        }
        unbindShizukuQuietly()
    }

    /**
     * @return true when the advertise request was handed to a ready owner
     */
    private fun ensureShizukuAndAdvertise(context: Context, command: WfdAdvertiseCommand): Boolean {
        if (!shizukuReady()) {
            Log.w(TAG, "Shizuku not ready for advertise")
            return false
        }
        val service = shellService.get()
        if (service != null && userServiceBound) {
            return dispatchShizuku(service, command)
        }
        pendingShizukuCommand = command
        return try {
            if (!userServiceBound) {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, MiraxShellUserService::class.java.name),
                )
                    .daemon(false)
                    .processNameSuffix("wfd")
                    .version(1)
                boundArgs = args
                Shizuku.bindUserService(args, userServiceConnection)
            }
            // Bind is async; pending command is applied in onServiceConnected.
            true
        } catch (err: Exception) {
            Log.w(TAG, "bindUserService failed", err)
            pendingShizukuCommand = null
            false
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
        val line = "ADVERTISE\t${sanitizeName(command.broadcastName)}\t${encodeModes(command.modes)}\n"
        return exchangeHelper(line)
    }

    private fun sendHelperStopAdvertiseQuietly() {
        try {
            exchangeHelper("STOP_ADVERTISE\n")
        } catch (err: Exception) {
            Log.d(TAG, "helper STOP_ADVERTISE not delivered", err)
        }
    }

    private fun exchangeHelper(command: String): Boolean {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                socket.outputStream.write(command.toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readLine()
                }
            }
            true
        } catch (err: Exception) {
            Log.d(TAG, "helper exchange failed: ${command.trim()}", err)
            false
        }
    }

    private fun unbindShizukuQuietly() {
        if (!userServiceBound && shellService.get() == null && boundArgs == null) {
            pendingShizukuCommand = null
            return
        }
        val args = boundArgs
        if (args != null) {
            try {
                Shizuku.unbindUserService(args, userServiceConnection, true)
            } catch (err: Exception) {
                Log.d(TAG, "unbindUserService failed", err)
            }
        }
        shellService.set(null)
        userServiceBound = false
        boundArgs = null
        pendingShizukuCommand = null
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
