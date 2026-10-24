package me.trinitrix.mirax.wfd

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
    private val epoch = AtomicLong(0L)
    private val inputGeneration = AtomicLong(0L)

    /** @return monotonic channel lifecycle version observed before forwarding a gesture. */
    fun inputGeneration(): Long = inputGeneration.get()

    /** @return whether the connected channel can accept a new picture contact. */
    fun canForwardInput(): Boolean {
        val current = state.get()
        synchronized(current) {
            val socket = current.socket
            return current.enabled && !current.closed && current.descriptorSent &&
                socket != null && socket.isConnected && !socket.isClosed
        }
    }

    /** @return touch slots described by the currently negotiated HIDC type. */
    fun touchSlotLimit(): Int {
        val current = state.get()
        synchronized(current) {
            return UibcPackets.maxContacts(current.hidType)
        }
    }
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

    /** Android route selection, replaced by host tests while retaining real TCP sockets. */
    internal var bindSocket: (android.content.Context?, Socket) -> Boolean = P2pNetworkBinder::bind

    var context: android.content.Context? = null

    /** Optional English status line, for the connection log. */
    var onStatus: ((String) -> Unit)? = null

    /** Open a UIBC channel to [host]:[port] with the negotiated [hidType]. */
    fun open(host: String, port: Int, hidType: Int, enabled: Boolean = true) {
        Log.d(TAG, "open: host=$host port=$port hidType=$hidType enabled=$enabled")
        close()
        val next = Channel(hidType = hidType, enabled = enabled)
        state.set(next)
        Thread({
            val socket = Socket()
            try {
                socket.bind(null)
                bindSocket(context, socket)
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
                } catch (closeErr: Exception) {
                    Log.w(TAG, "failed to close UIBC socket after dial failure", closeErr)
                }
            }
        }, "mirax-uibc").apply { isDaemon = true }.start()
    }

    /** Change input availability, releasing live contacts before disabling. */
    fun setEnabled(enabled: Boolean) {
        val current = state.get()
        synchronized(current) {
            if (current.enabled == enabled) {
                return
            }
            if (!enabled) {
                epoch.incrementAndGet()
            }
            inputGeneration.incrementAndGet()
            if (!enabled) {
                liftActive(current)
            }
            current.enabled = enabled
            Log.i(TAG, "setEnabled=$enabled hidType=${current.hidType}")
            current.pending = null
            current.pendingPen = null
            current.last = emptyList()
            current.lastPen = null
            if (enabled) {
                current.descriptorSent = false
                sendDescriptor(current)
                flushPending(current)
            }
        }
    }

    fun setPictureSize(width: Int, height: Int) {
        val current = state.get()
        synchronized(current) {
            if (current.pictureWidth == width && current.pictureHeight == height) {
                return
            }
            liftActive(current)
            inputGeneration.incrementAndGet()
            current.pictureWidth = width
            current.pictureHeight = height
            current.descriptorSent = false
            sendDescriptor(current)
            flushPending(current)
        }
    }

    /**
     * Propagates a mid-session HID type change.
     *
     * Applies the new type to the channel and re-sends the descriptor when one has
     * already been sent, so the source learns the updated contact limit.
     */
    fun setHidType(hidType: Int) {
        val current = state.get()
        synchronized(current) {
            if (current.hidType == hidType) {
                return
            }
            liftActive(current)
            inputGeneration.incrementAndGet()
            current.hidType = hidType
            if (current.descriptorSent) {
                current.descriptorSent = false
                sendDescriptor(current)
            }
        }
    }

    /**
     * Selects how the contact count maximum is declared and re-sends the
     * descriptor, so an experiment can compare the two forms.
     */
    fun setContactMaximumMode(mode: UibcPackets.ContactMaximumMode) {
        val current = state.get()
        synchronized(current) {
            if (current.maximumMode == mode) {
                return
            }
            current.maximumMode = mode
            if (current.descriptorSent) {
                current.descriptorSent = false
                sendDescriptor(current)
            }
        }
    }

    fun submit(contacts: List<UibcContact>) {
        val current = state.get()
        synchronized(current) {
            if (!current.enabled || current.closed) {
                return
            }
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
            if (!current.enabled || current.closed) {
                return
            }
            if (current.hidType != UibcPackets.HID_MULTI_TOUCH && current.hidType != UibcPackets.HID_SINGLE_TOUCH) {
                if (!current.penUnsupportedLogged) {
                    Log.w(TAG, "pen ignored: unsupported HID type ${current.hidType}")
                    current.penUnsupportedLogged = true
                }
                return
            }
            // Preserve in-range sample cadence even when pixel quantization leaves the state unchanged.
            if (current.lastPen == pen && !pen.inRange && !pen.tip) {
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
            if (!current.enabled || current.closed) {
                return
            }
            liftActive(current)
        }
    }

    /**
     * Release selected contacts without interrupting the other contacts on this socket.
     *
     * @param touchSlots finger TLCs to end
     * @param releasePen whether the pen stream must leave range
     */
    fun releaseCovered(touchSlots: Set<Int>, releasePen: Boolean) {
        val current = state.get()
        synchronized(current) {
            if (!current.enabled || current.closed) {
                return
            }
            val lifted = current.last.filter { it.id in touchSlots && it.tip }.map { it.copy(tip = false) }
            if (lifted.isNotEmpty()) {
                current.last = current.last.filterNot { it.id in touchSlots } + lifted
                if (current.socket == null || !current.descriptorSent) {
                    current.pending = current.last
                } else {
                    writeContacts(current, current.last)
                }
            }
            val pen = current.lastPen
            if (releasePen && pen != null && (pen.tip || pen.inRange)) {
                val liftedPen = pen.copy(tip = false, inRange = false, pressure = 0)
                current.lastPen = liftedPen
                if (current.socket == null || !current.descriptorSent) {
                    current.pendingPen = liftedPen
                } else {
                    writePen(current, liftedPen)
                }
            }
        }
    }

    /**
     * Enqueues releases after the writer epoch advances so disabling input cannot drop them.
     *
     * @param current channel owning the currently active contacts and pen
     */
    private fun liftActive(current: Channel) {
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
        if (pen != null) {
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

    fun close() {
        epoch.incrementAndGet()
        inputGeneration.incrementAndGet()
        val current = state.getAndSet(Channel())
        synchronized(current) {
            current.closed = true
            try {
                current.socket?.close()
            } catch (closeErr: Exception) {
                Log.w(TAG, "failed to close UIBC socket", closeErr)
            }
            current.socket = null
        }
    }

    private fun flushPending(current: Channel) {
        if (!current.descriptorSent || current.closed) {
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
        if (!current.enabled || current.closed) {
            return
        }
        if (current.descriptorSent || current.pictureWidth <= 0 || current.pictureHeight <= 0) {
            return
        }
        val maxContacts = UibcPackets.maxContacts(current.hidType)
        val descriptor = UibcPackets.digitizerDescriptor(
            current.pictureWidth, current.pictureHeight, current.hidType, current.maximumMode,
        )
        if (!write(current, socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_REPORT_DESCRIPTOR, descriptor)) {
                current.descriptorSent = false
            }) {
            current.descriptorSent = false
            Log.w(TAG, "descriptor write failed; will retry on next picture-size update")
            return
        }
        current.descriptorSent = true
        Log.i(TAG, "descriptor sent ${descriptor.size}B ${current.pictureWidth}x${current.pictureHeight} hidType=${current.hidType} contacts=$maxContacts")
        if (current.hidType == UibcPackets.HID_MULTI_TOUCH &&
            current.maximumMode == UibcPackets.ContactMaximumMode.INPUT_REPORT
        ) {
            // The count maximum is an input item, so the source only learns it
            // from this report and not from a GetFeature answer.
            write(
                current,
                socket,
                UibcPackets.hidcPacket(
                    current.hidType,
                    UibcPackets.USAGE_INPUT_REPORT,
                    UibcPackets.contactMaximumReport(maxContacts),
                ),
            )
        }
        // Windows keeps the first input report as the device's initial report.
        // A descriptor alone does not create the virtual touch device, so the
        // reports that follow are dropped until this clear frame has been seen.
        writeContacts(current, emptyList())
        if (current.hidType == UibcPackets.HID_SINGLE_TOUCH) {
            writePen(current, UibcPenContact(0, 0, tip = false, inRange = false))
        }
    }

    private fun writeContacts(current: Channel, contacts: List<UibcContact>) {
        val socket = current.socket ?: return
        Log.i(TAG, "writeContacts: ${contacts.size} contacts")
        for (report in UibcPackets.touchReports(contacts, current.hidType)) {
            write(current, socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_INPUT_REPORT, report))
        }
    }

    private fun writePen(current: Channel, pen: UibcPenContact) {
        val socket = current.socket ?: return
        val report = UibcPackets.penReport(pen, current.hidType)
        Log.i(TAG, "writePen: tip=${pen.tip} inRange=${pen.inRange} x=${pen.x} y=${pen.y} p=${pen.pressure} barrel=${pen.barrel} eraser=${pen.eraser}")
        write(current, socket, UibcPackets.hidcPacket(current.hidType, UibcPackets.USAGE_INPUT_REPORT, report))
    }

    private fun write(current: Channel, socket: Socket, packet: ByteArray, onFailure: () -> Unit = {}): Boolean {
        val target = socket
        val enqueuedEpoch = epoch.get()
        var inlineResult: Boolean? = null
        writeRunner {
            // Read the epoch lock-free: a synchronous writeRunner joins while
            // the caller holds the channel lock, so taking it here deadlocks.
            if (state.get() !== current || enqueuedEpoch != epoch.get()) {
                return@writeRunner
            }
            try {
                val output = target.getOutputStream()
                output.write(packet)
                output.flush()
                inlineResult = true
                Log.i(TAG, "wrote ${packet.size} bytes (usage=${packet[6]})")
            } catch (err: Exception) {
                inlineResult = false
                Log.w(TAG, "UIBC write failed", err)
                if (state.get() === current && epoch.compareAndSet(enqueuedEpoch, enqueuedEpoch + 1)) {
                    current.closed = true
                    current.descriptorSent = false
                    inputGeneration.incrementAndGet()
                    onFailure()
                    try {
                        target.close()
                    } catch (closeErr: Exception) {
                        Log.w(TAG, "failed to close UIBC socket after write failure", closeErr)
                    }
                }
            }
        }
        return inlineResult ?: true
    }

    private fun status(line: String) {
        Log.i(TAG, line)
        onStatus?.invoke(line)
    }

    private class Channel(
        var hidType: Int = UibcPackets.HID_MULTI_TOUCH,
        var enabled: Boolean = true,
    ) {
        var socket: Socket? = null
        var pictureWidth: Int = 0
        var pictureHeight: Int = 0
        var maximumMode: UibcPackets.ContactMaximumMode = UibcPackets.ContactMaximumMode.FEATURE_REPORT
        @Volatile var descriptorSent: Boolean = false
        var pending: List<UibcContact>? = null
        var pendingPen: UibcPenContact? = null
        var last: List<UibcContact> = emptyList()
        var lastPen: UibcPenContact? = null
        var penUnsupportedLogged: Boolean = false
        @Volatile var closed: Boolean = false
    }
}
