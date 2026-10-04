package me.trinitrix.mirax.wfd

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
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
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirax-uibc-write").apply { isDaemon = true }
    }

    /**
     * Runs a socket write off the caller.
     *
     * Picture input arrives on the main thread. Writing the TCP socket there
     * throws [android.os.NetworkOnMainThreadException] and the report never
     * leaves the phone. Tests replace this so they can observe the hand-off.
     */
    internal var writeRunner: (Runnable) -> Unit = { runnable -> writer.execute(runnable) }

    var context: android.content.Context? = null

    /** Optional English status line, for the connection log. */
    var onStatus: ((String) -> Unit)? = null

    fun open(host: String, port: Int, hidType: Int) {
        close()
        val next = Channel(hidType = hidType)
        state.set(next)
        Thread({
            val socket = Socket()
            try {
                socket.bind(null)
                P2pNetworkBinder.bind(context, socket)
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
            if (contacts.isEmpty() && current.last.isEmpty()) {
                return
            }
            current.last = contacts
            if (current.socket == null || !current.descriptorSent) {
                current.pending = contacts
                return
            }
            writeContacts(current, contacts)
        }
    }

    fun submitPen(pen: UibcPenContact) {
        val current = state.get()
        synchronized(current) {
            if (current.lastPen == pen) {
                return
            }
            current.lastPen = pen
            if (current.socket == null || !current.descriptorSent) {
                current.pendingPen = pen
                return
            }
            writePen(current, pen)
        }
    }

    fun liftAll() {
        val current = state.get()
        synchronized(current) {
            val lifted = current.last.map { it.copy(tip = false) }
            if (lifted.isNotEmpty()) {
                current.last = lifted
                if (current.socket == null || !current.descriptorSent) {
                    current.pending = lifted
                } else {
                    writeContacts(current, lifted)
                    current.last = emptyList()
                }
            }
            val pen = current.lastPen
            if (pen != null && (pen.tip || pen.inRange)) {
                val liftedPen = pen.copy(tip = false, inRange = false, pressure = 0)
                current.lastPen = liftedPen
                if (current.socket == null || !current.descriptorSent) {
                    current.pendingPen = liftedPen
                } else {
                    writePen(current, liftedPen)
                    current.lastPen = null
                }
            }
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
        val pendingContacts = current.pending
        if (pendingContacts != null) {
            writeContacts(current, pendingContacts)
            current.pending = null
        }
        val pendingPen = current.pendingPen
        if (pendingPen != null) {
            writePen(current, pendingPen)
            current.pendingPen = null
        }
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
        // Windows keeps the first input report as the device's initial report.
        // A descriptor alone does not create the virtual touch device, so the
        // reports that follow are dropped until this clear frame has been seen.
        writeContacts(current, emptyList())
    }

    private fun writeContacts(current: Channel, contacts: List<UibcContact>) {
        val socket = current.socket ?: return
        val report = UibcPackets.touchReport(contacts, UibcPackets.maxContacts(current.hidType))
        Log.d(TAG, "writeContacts: ${contacts.size} contacts (reported=${report[report.size - 1]})")
        write(socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_INPUT_REPORT, report))
    }

    private fun writePen(current: Channel, pen: UibcPenContact) {
        val socket = current.socket ?: return
        val report = UibcPackets.penReport(pen)
        Log.d(TAG, "writePen: tip=${pen.tip} inRange=${pen.inRange} x=${pen.x} y=${pen.y} p=${pen.pressure} barrel=${pen.barrel} eraser=${pen.eraser}")
        write(socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_INPUT_REPORT, report))
    }

    private fun write(socket: Socket, packet: ByteArray) {
        val target = socket
        writeRunner {
            try {
                val output = target.getOutputStream()
                output.write(packet)
                output.flush()
                Log.v(TAG, "wrote ${packet.size} bytes (usage=${packet[6]})")
            } catch (err: Exception) {
                Log.w(TAG, "UIBC write failed", err)
            }
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
        var pendingPen: UibcPenContact? = null
        var last: List<UibcContact> = emptyList()
        var lastPen: UibcPenContact? = null
        var closed: Boolean = false
    }
}
