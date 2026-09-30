package me.trinitrix.mirax.wfd

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Dials the source UIBC port and sends general-touch HIDC.
 *
 * The descriptor goes out before any contact report. Turning touch off lifts
 * the contacts already down; it does not close the TCP connection.
 */
object UibcTouchChannel {
    private const val TAG = "MiraxUibc"
    private val state = AtomicReference(Channel())

    /** Optional English status line, for the connection log. */
    var onStatus: ((String) -> Unit)? = null

    fun open(host: String, port: Int, hidType: Int) {
        close()
        val next = Channel(hidType = hidType)
        state.set(next)
        Thread({
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 3_000)
                synchronized(next) {
                    if (next.closed) {
                        socket.close()
                        return@Thread
                    }
                    next.socket = socket
                    status("UIBC connected to $host:$port")
                    sendDescriptor(next)
                    flushPending(next)
                }
            } catch (err: Exception) {
                Log.w(TAG, "UIBC dial $host:$port failed", err)
                status("UIBC dial $host:$port failed: ${err.message}")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }, "mirax-uibc").apply { isDaemon = true }.start()
    }

    fun setPictureSize(width: Int, height: Int) {
        val current = state.get()
        synchronized(current) {
            if (current.pictureWidth == width && current.pictureHeight == height) {
                return
            }
            current.pictureWidth = width
            current.pictureHeight = height
            current.descriptorSent = false
            sendDescriptor(current)
            flushPending(current)
        }
    }

    fun submit(contacts: List<UibcContact>) {
        val current = state.get()
        synchronized(current) {
            current.last = contacts
            if (current.socket == null || !current.descriptorSent) {
                current.pending = contacts
                return
            }
            writeContacts(current, contacts)
        }
    }

    fun liftAll() {
        val current = state.get()
        synchronized(current) {
            val lifted = current.last.map { it.copy(tip = false) }
            if (lifted.isEmpty()) {
                return
            }
            current.last = lifted
            if (current.socket == null || !current.descriptorSent) {
                current.pending = lifted
                return
            }
            writeContacts(current, lifted)
            current.last = emptyList()
        }
    }

    fun close() {
        val current = state.getAndSet(Channel())
        synchronized(current) {
            current.closed = true
            try {
                current.socket?.close()
            } catch (_: Exception) {
            }
            current.socket = null
        }
    }

    private fun flushPending(current: Channel) {
        if (!current.descriptorSent) {
            return
        }
        val pending = current.pending ?: return
        writeContacts(current, pending)
        current.pending = null
    }

    private fun sendDescriptor(current: Channel) {
        val socket = current.socket ?: return
        if (current.descriptorSent || current.pictureWidth <= 0 || current.pictureHeight <= 0) {
            return
        }
        val descriptor = UibcPackets.touchDescriptor(
            current.pictureWidth,
            current.pictureHeight,
            UibcPackets.maxContacts(current.hidType),
        )
        write(socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_REPORT_DESCRIPTOR, descriptor))
        current.descriptorSent = true
    }

    private fun writeContacts(current: Channel, contacts: List<UibcContact>) {
        val socket = current.socket ?: return
        val report = UibcPackets.touchReport(contacts, UibcPackets.maxContacts(current.hidType))
        write(socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_INPUT_REPORT, report))
    }

    private fun write(socket: Socket, packet: ByteArray) {
        try {
            val output = socket.getOutputStream()
            output.write(packet)
            output.flush()
        } catch (err: Exception) {
            Log.w(TAG, "UIBC write failed", err)
        }
    }

    private fun status(line: String) {
        Log.i(TAG, line)
        onStatus?.invoke(line)
    }

    private class Channel(
        val hidType: Int = UibcPackets.HID_MULTI_TOUCH,
    ) {
        var socket: Socket? = null
        var pictureWidth: Int = 0
        var pictureHeight: Int = 0
        var descriptorSent: Boolean = false
        var pending: List<UibcContact>? = null
        var last: List<UibcContact> = emptyList()
        var closed: Boolean = false
    }
}
