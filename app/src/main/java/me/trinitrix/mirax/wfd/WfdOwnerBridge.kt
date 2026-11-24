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
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.host.helperPathsAllowed
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService
import me.trinitrix.mirax.shell.MiraxShellUserService
import rikka.shizuku.Shizuku
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
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
    /** Mirrors SocketHelperAdapter GROUP_IO_TIMEOUT_MS for HELPER exchange bound. */
    private const val HELPER_IO_TIMEOUT_MS = 750L
    private const val HELPER_RESPONSE_LIMIT = 512
    // Bionic's MSG_NOSIGNAL is not exposed by public OsConstants.
    private const val MSG_NOSIGNAL = 0x4000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val shellService = AtomicReference<IMiraxShellService?>(null)
    private val activeOwner = AtomicReference(WfdOwner.NONE)
    private val helperGroupAdapter = SocketHelperAdapter()
    private val groupRoute = OwnerGroupRoute(activeOwner) { owner ->
        when (owner) {
            WfdOwner.HELPER -> if (helperPathsAllowed()) helperGroupAdapter else null
            WfdOwner.SHIZUKU -> shellService.get()?.let { service -> ShizukuOwnerAdapter { service } }
            WfdOwner.NONE -> null
        }
    }
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var pendingShizukuCommand: WfdAdvertiseCommand? = null
    private var appContext: Context? = null
    private val armHelperWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirax-wfd-arm-helper").apply { isDaemon = true }
    }
    private val armResults = ArmResultFence { action ->
        val context = appContext ?: return@ArmResultFence false
        SessionHost.dispatchConnectionEvent(context, action)
        true
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "Shizuku user-service binder missing")
                return
            }
            val service = IMiraxShellService.Stub.asInterface(binder)
            groupRoute.updateEndpoint(WfdOwner.SHIZUKU) { shellService.set(service) }
            Log.i(TAG, "Shizuku user-service connected")
            val pending = pendingShizukuCommand
            val generation = armResults.currentGeneration
            pendingShizukuCommand = null
            if (pending != null) {
                if (dispatchShizuku(service, pending)) {
                    lastCommand.set(pending)
                    watchArm(pending.owner)
                } else {
                    lastCommand.compareAndSet(pending, null)
                    reportArmResult(generation, listening = false)
                }
            }
            // Pending work such as the one-time wm size read can run now.
            appContext?.let { ctx -> mainHandler.post { SessionHost.commit(ctx) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user-service disconnected")
            groupRoute.updateEndpoint(WfdOwner.SHIZUKU) { shellService.set(null) }
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
        val previous = groupRoute.switchOwner(owner)
        if (previous != owner) {
            cancelArmWatch()
        }
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
        if (command == null) {
            cancelArmWatch()
            pendingShizukuCommand = null
        }
        if (lastCommand.get() == command) {
            return true
        }
        if (command == null) {
            stopAdvertising()
            lastCommand.set(null)
            return true
        }
        cancelArmWatch()
        val applied = when (command.owner) {
            WfdOwner.HELPER -> if (helperPathsAllowed()) sendHelperAdvertise(command) else false
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

    /**
     * Call off the main thread; the bounded wait can return null while IPC is still in flight.
     *
     * @return a validated current-owner snapshot, or null when unavailable
     */
    fun groupSnapshotV2(): OwnerGroupSnapshot? = groupRoute.snapshot()

    /** Captures the local revision only when the requested owner is active. */
    internal fun ownerRevision(owner: WfdOwner): Long? = groupRoute.revisionFor(owner)

    /** Checks only the local owner and endpoint revision. */
    internal fun isCurrentGroupOwner(snapshot: OwnerGroupSnapshot): Boolean = groupRoute.isCurrent(snapshot)

    /** Submits guarded END and exposes local worker saturation. */
    internal fun submitEndSession(snapshot: OwnerGroupSnapshot): OwnerEndSubmission = groupRoute.submitEnd(snapshot)

    /**
     * @param snapshot observation captured from this bridge
     * @return true only when the local worker accepted the task; it may still be discarded
     *         before dispatch and does not confirm remote queueing or removal
     */
    fun endSessionIfEpoch(snapshot: OwnerGroupSnapshot): Boolean = groupRoute.end(snapshot)

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
        val generation = armResults.begin()
        pollArm(generation, owner, System.currentTimeMillis() + ARM_TIMEOUT_MS)
    }

    private fun pollArm(generation: Int, owner: WfdOwner, deadlineMs: Long) {
        mainHandler.postDelayed(
            {
                if (!armResults.isWatching(generation)) {
                    return@postDelayed
                }
                if (owner == WfdOwner.HELPER) {
                    // Helper LISTENING I/O is timed off-main; never block the main thread.
                    armHelperWorker.execute {
                        val listening = ownerListening(owner)
                        mainHandler.post {
                            onArmPollChecked(generation, owner, deadlineMs, listening)
                        }
                    }
                    return@postDelayed
                }
                onArmPollChecked(generation, owner, deadlineMs, ownerListening(owner))
            },
            ARM_POLL_MS,
        )
    }

    private fun onArmPollChecked(
        generation: Int,
        owner: WfdOwner,
        deadlineMs: Long,
        listening: Boolean,
    ) {
        if (!armResults.isWatching(generation)) {
            return
        }
        if (listening) {
            reportArmResult(generation, listening = true)
            return
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.e(TAG, "arm timed out for $owner")
            reportArmResult(generation, listening = false)
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

    private fun cancelArmWatch() {
        armResults.invalidate()
    }

    private fun reportArmResult(generation: Int, listening: Boolean) {
        val delivery = armResults.finish(generation, listening) ?: return
        mainHandler.post(delivery)
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
        //Trial sends no helper socket traffic; callers map the null to fail-closed results.
        if (!helperPathsAllowed()) return null
        val deadline = SystemClock.elapsedRealtime() + HELPER_IO_TIMEOUT_MS
        return try {
            LocalSocket().use { socket ->
                // LocalSocket allocates its descriptor lazily and does not support connect timeout.
                socket.inputStream
                val fd = socket.fileDescriptor ?: return null
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                // shortcut: pending connect fails closed, add completion polling if retries are needed.
                socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                val bytes = "$command\n".toByteArray(StandardCharsets.UTF_8)
                if (!writeHelper(fd, bytes, deadline)) return@use null
                readHelper(fd, deadline, readAll)
            }
        } catch (err: Exception) {
            Log.d(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun writeHelper(fd: FileDescriptor, bytes: ByteArray, deadline: Long): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            if (!awaitHelperIo(fd, OsConstants.POLLOUT, deadline)) return false
            val written = try {
                Os.sendto(fd, bytes, offset, bytes.size - offset, MSG_NOSIGNAL, null)
            } catch (err: ErrnoException) {
                if (err.errno == OsConstants.EAGAIN || err.errno == OsConstants.EINTR) continue
                throw err
            }
            if (written <= 0) return false
            offset += written
        }
        return true
    }

    private fun readHelper(fd: FileDescriptor, deadline: Long, readAll: Boolean): String? {
        val bytes = ByteArray(HELPER_RESPONSE_LIMIT)
        var size = 0
        while (size < bytes.size) {
            if (!awaitHelperIo(fd, OsConstants.POLLIN, deadline)) return null
            val count = try {
                Os.read(fd, bytes, size, bytes.size - size)
            } catch (err: ErrnoException) {
                if (err.errno == OsConstants.EAGAIN || err.errno == OsConstants.EINTR) continue
                throw err
            }
            if (count <= 0) {
                return if (readAll && size > 0) {
                    String(bytes, 0, size, StandardCharsets.UTF_8)
                } else {
                    null
                }
            }
            if (!readAll) {
                for (index in size until size + count) {
                    if (bytes[index] == '\n'.code.toByte()) {
                        return String(bytes, 0, index, StandardCharsets.UTF_8)
                    }
                }
            }
            size += count
        }
        if (readAll) {
            return String(bytes, 0, size, StandardCharsets.UTF_8)
        }
        Log.w(TAG, "helper response exceeded limit")
        return null
    }

    private fun awaitHelperIo(fd: FileDescriptor, events: Int, deadline: Long): Boolean {
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                Log.w(TAG, "helper I/O deadline exceeded")
                return false
            }
            val descriptor = StructPollfd().apply {
                this.fd = fd
                this.events = events.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(descriptor), remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (err: ErrnoException) {
                if (err.errno == OsConstants.EINTR) continue
                throw err
            }
            if (ready <= 0) {
                Log.w(TAG, "helper I/O timed out")
                return false
            }
            val mask = if (events == OsConstants.POLLIN) events or OsConstants.POLLHUP else events
            if ((descriptor.revents.toInt() and mask) == 0) {
                Log.w(TAG, "helper I/O closed or failed")
                return false
            }
            return true
        }
    }

    private fun unbindShizukuQuietly() {
        cancelArmWatch()
        pendingShizukuCommand = null
        val args = boundArgs
        boundArgs = null
        groupRoute.updateEndpoint(WfdOwner.SHIZUKU) { shellService.set(null) }
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
