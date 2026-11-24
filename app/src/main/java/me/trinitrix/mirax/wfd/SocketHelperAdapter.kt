package me.trinitrix.mirax.wfd

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets

class SocketHelperAdapter(
    private val groupExchange: ((String) -> String?)? = null,
) : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.HELPER

    override fun advertise(command: WfdAdvertiseCommand): Boolean {
        val line = "ADVERTISE	${sanitizeName(command.broadcastName)}	${WfdOwnerBridge.encodeModes(command.modes)}"
        return exchangeHelper(line) != null
    }

    override fun stopAdvertise() {
        sendHelperQuietly("STOP_ADVERTISE")
    }

    override fun isListening(): Boolean = exchangeHelper("LISTENING") == "YES"

    override fun groupState(): String? = exchangeHelper("GROUP")

    override fun groupSnapshotV2(): OwnerGroupSnapshot? =
        OwnerGroupSnapshot.parse(owner, exchangeGroup("GROUP_V2"))

    override fun endSessionIfEpoch(snapshot: OwnerGroupSnapshot): Boolean {
        if (!snapshot.canEndBy(owner)) return false
        return exchangeGroup("END_V2 ${snapshot.epoch}") == "QUEUED"
    }

    override fun endSession() {
        sendHelperQuietly("END")
    }

    override fun forgetAllPairings() {
        sendHelperQuietly("FORGET_PAIRINGS")
    }

    override fun pairingState(): String {
        return when (val state = exchangeHelper("PAIRING_STATE")) {
            "UNPAIRED", "PAIRING", "PAIRED" -> state
            else -> "UNPAIRED"
        }
    }

    override fun wmSize(displayId: Int?): String? {
        val cmd = if (displayId == null) "WM_SIZE" else "WM_SIZE $displayId"
        return exchangeHelper(cmd, readAll = true)
    }

    private fun sendHelperQuietly(command: String) {
        exchangeHelper(command)
    }

    private fun exchangeGroup(command: String): String? {
        if (groupExchange != null) return groupExchange.invoke(command)
        return try {
            exchangeTimed(command)
        } catch (err: Exception) {
            Log.w(TAG, "V2 helper exchange unavailable or outcome unknown", err)
            null
        }
    }

    private fun writeGroup(fd: FileDescriptor, bytes: ByteArray, deadline: Long): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            if (!awaitGroupIo(fd, OsConstants.POLLOUT, deadline)) return false
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

    private fun readGroup(fd: FileDescriptor, deadline: Long, readAll: Boolean = false): String? {
        val bytes = ByteArray(GROUP_RESPONSE_LIMIT)
        var size = 0
        while (size < bytes.size) {
            if (!awaitGroupIo(fd, OsConstants.POLLIN, deadline)) return null
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
        Log.w(TAG, "V2 helper response exceeded limit")
        return null
    }

    private fun awaitGroupIo(fd: FileDescriptor, events: Int, deadline: Long): Boolean {
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                Log.w(TAG, "V2 helper I/O deadline exceeded")
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
                Log.w(TAG, "V2 helper I/O timed out")
                return false
            }
            val mask = if (events == OsConstants.POLLIN) events or OsConstants.POLLHUP else events
            if ((descriptor.revents.toInt() and mask) == 0) {
                Log.w(TAG, "V2 helper I/O closed or failed")
                return false
            }
            return true
        }
    }

    private fun exchangeHelper(command: String, readAll: Boolean = false): String? {
        return try {
            exchangeTimed(command, readAll)
        } catch (err: Exception) {
            Log.d(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun exchangeTimed(command: String, readAll: Boolean = false): String? {
        val deadline = SystemClock.elapsedRealtime() + GROUP_IO_TIMEOUT_MS
        return LocalSocket().use { socket ->
            // LocalSocket allocates its descriptor lazily and does not support connect timeout.
            socket.inputStream
            val fd = socket.fileDescriptor ?: return@use null
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            // shortcut: pending connect fails closed, add completion polling if retries are needed.
            socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
            val bytes = "$command\n".toByteArray(StandardCharsets.UTF_8)
            if (!writeGroup(fd, bytes, deadline)) return@use null
            readGroup(fd, deadline, readAll)
        }
    }

    private fun sanitizeName(name: String): String = name.replace('	', ' ').replace('\n', ' ')

    companion object {
        private const val TAG = "SocketHelperAdapter"
        private const val GROUP_IO_TIMEOUT_MS = 750L
        private const val GROUP_RESPONSE_LIMIT = 512
        // Bionic's MSG_NOSIGNAL is not exposed by public OsConstants.
        private const val MSG_NOSIGNAL = 0x4000
    }
}
