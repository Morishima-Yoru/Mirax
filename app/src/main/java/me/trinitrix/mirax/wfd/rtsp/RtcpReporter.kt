package me.trinitrix.mirax.wfd.rtsp

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * RFC3550 RTCP receiver reports for Microsoft Miracast bitrate modulation.
 *
 * Win11 uses RR loss/RTT to raise or cut encode bitrate. A wrong destination
 * or inflated jitter makes the source sit at ~2–3 Mbps with ~500 ms burst
 * pacing — seen on this Phh sink while Samsung Fold 5 stays healthy.
 */
class RtcpReporter(
    private val localSsrc: Int = Random.nextInt(),
) {
    @Volatile
    var remoteAddress: InetAddress? = null

    @Volatile
    var remoteRtcpPort: Int = -1

    private val lock = Any()
    private var remoteSsrc: Int = 0
    private var packetsReceived: Long = 0
    private var expectedPrior: Long = 0
    private var receivedPrior: Long = 0
    private var highestSeq: Int = -1
    private var seqCycles: Int = 0
    private var baseSeq: Int = -1
    private var lastSrNtpMid: Int = 0
    private var lastSrArrivalElapsedMs: Long = 0
    private var lastSendElapsedMs: Long = 0
    private var rrSent: Long = 0
    private var srReceived: Long = 0
    private var lastFraction: Int = 0
    private var lastCumulativeLost: Long = 0
    private var lastDlsrMs: Long = 0
    private var lastLoggedDest: String = ""

    fun onRtpPacket(packet: ByteArray, length: Int) {
        if (length < 12) {
            return
        }
        if (((packet[0].toInt() ushr 6) and 0x3) != 2) {
            return
        }
        val seq = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val ssrc =
            ((packet[8].toInt() and 0xff) shl 24) or
                ((packet[9].toInt() and 0xff) shl 16) or
                ((packet[10].toInt() and 0xff) shl 8) or
                (packet[11].toInt() and 0xff)
        synchronized(lock) {
            if (remoteSsrc != 0 && remoteSsrc != ssrc) {
                // New stream — do not treat the seq jump as massive loss (that
                // pinned Win11 ABR at 2–3 Mbps on this Phh sink).
                Log.w(TAG, "RTP SSRC ${remoteSsrc.toUInt()} → ${ssrc.toUInt()}; reset RR")
                packetsReceived = 0
                expectedPrior = 0
                receivedPrior = 0
                seqCycles = 0
                baseSeq = -1
                highestSeq = -1
                lastFraction = 0
                lastCumulativeLost = 0
            }
            remoteSsrc = ssrc
            if (baseSeq < 0) {
                baseSeq = seq
                highestSeq = seq
            } else {
                val udelta = (seq - highestSeq) and 0xffff
                if (udelta < 0x8000) {
                    if (seq < highestSeq) {
                        seqCycles += 0x10000
                    }
                    highestSeq = seq
                }
            }
            packetsReceived++
        }
    }

    fun onRtcpPacket(packet: ByteArray, length: Int) {
        if (length < 8) {
            return
        }
        var offset = 0
        while (offset + 8 <= length) {
            val pt = packet[offset + 1].toInt() and 0xff
            val lenWords = ((packet[offset + 2].toInt() and 0xff) shl 8) or
                (packet[offset + 3].toInt() and 0xff)
            val packetBytes = (lenWords + 1) * 4
            if (packetBytes < 8 || offset + packetBytes > length) {
                break
            }
            if (pt == PT_SR && packetBytes >= 28) {
                val ntpMid =
                    ((packet[offset + 10].toInt() and 0xff) shl 24) or
                        ((packet[offset + 11].toInt() and 0xff) shl 16) or
                        ((packet[offset + 12].toInt() and 0xff) shl 8) or
                        (packet[offset + 13].toInt() and 0xff)
                synchronized(lock) {
                    lastSrNtpMid = ntpMid
                    lastSrArrivalElapsedMs = SystemClock.elapsedRealtime()
                    srReceived++
                    if (srReceived == 1L || srReceived % 20L == 0L) {
                        Log.i(TAG, "RTCP SR #$srReceived ntp_mid=0x${ntpMid.toUInt().toString(16)}")
                    }
                }
            }
            offset += packetBytes
        }
    }

    /**
     * Send a receiver report when enough time has passed and a destination is known.
     *
     * Returns:
     *     true when a datagram was written.
     */
    fun maybeSend(socket: DatagramSocket, force: Boolean = false): Boolean {
        val dest = remoteAddress ?: return false
        val port = remoteRtcpPort
        if (port !in 1..65535) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSendElapsedMs < SEND_INTERVAL_MS) {
            return false
        }
        val report = synchronized(lock) { buildReceiverReportLocked(now) } ?: return false
        return try {
            socket.send(DatagramPacket(report, report.size, dest, port))
            lastSendElapsedMs = now
            rrSent++
            if (rrSent == 1L || rrSent % 20L == 0L) {
                val sr = synchronized(lock) { srReceived }
                val dlsrMs = synchronized(lock) { lastDlsrMs }
                Log.i(
                    TAG,
                    "RTCP RR #$rrSent → ${dest.hostAddress}:$port " +
                        "frac=$lastFraction lost=$lastCumulativeLost jitter=0 " +
                        "sr=$sr dlsr_ms=$dlsrMs",
                )
            }
            true
        } catch (err: Exception) {
            Log.w(TAG, "RTCP RR send failed: ${err.message}")
            false
        }
    }

    private fun buildReceiverReportLocked(nowElapsedMs: Long): ByteArray? {
        if (baseSeq < 0 || packetsReceived == 0L) {
            return null
        }
        val extendedMax = seqCycles + highestSeq
        val expected = (extendedMax - baseSeq + 1).toLong()
        val expectedInterval = expected - expectedPrior
        val receivedInterval = packetsReceived - receivedPrior
        expectedPrior = expected
        receivedPrior = packetsReceived
        val lostInterval = expectedInterval - receivedInterval
        val fraction = if (expectedInterval <= 0L || lostInterval <= 0L) {
            0
        } else {
            ((lostInterval shl 8) / expectedInterval).toInt().coerceIn(0, 255)
        }
        val cumulativeLost = (expected - packetsReceived).coerceAtLeast(0L).coerceAtMost(0xffffffL)
        lastFraction = fraction
        lastCumulativeLost = cumulativeLost
        val lsr = lastSrNtpMid
        val delayMs = if (lsr == 0 || lastSrArrivalElapsedMs == 0L) {
            0L
        } else {
            (nowElapsedMs - lastSrArrivalElapsedMs).coerceAtLeast(0L)
        }
        lastDlsrMs = delayMs
        val dlsr = if (delayMs == 0L) 0 else ((delayMs * 65536L) / 1000L).toInt()
        // Report jitter=0 until we have a real 90 kHz arrival clock. Mixing
        // elapsedRealtime with RTP timestamps produced huge jitter and kept
        // Win11 bitrate modulation pinned near 2–3 Mbps on this sink.
        val jitter = 0
        val out = ByteArray(RR_BYTES)
        out[0] = (0x80 or 1).toByte() // V=2, RC=1
        out[1] = PT_RR.toByte()
        out[2] = 0
        out[3] = 7 // length in 32-bit words minus one
        writeInt(out, 4, localSsrc)
        writeInt(out, 8, remoteSsrc)
        out[12] = fraction.toByte()
        out[13] = ((cumulativeLost ushr 16) and 0xff).toByte()
        out[14] = ((cumulativeLost ushr 8) and 0xff).toByte()
        out[15] = (cumulativeLost and 0xff).toByte()
        writeInt(out, 16, extendedMax)
        writeInt(out, 20, jitter)
        writeInt(out, 24, lsr)
        writeInt(out, 28, dlsr)
        return out
    }

    companion object {
        private const val TAG = "MiraxRtcp"
        private const val PT_SR = 200
        private const val PT_RR = 201
        private const val RR_BYTES = 32
        const val SEND_INTERVAL_MS = 500L

        private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = ((value ushr 24) and 0xff).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xff).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xff).toByte()
            buf[offset + 3] = (value and 0xff).toByte()
        }
    }
}

/**
 * Owns the local RTCP UDP socket (RTP port + 1) and a send/receive loop.
 */
class RtcpChannel {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var reporter: RtcpReporter = RtcpReporter()

    val stats: RtcpReporter
        get() = reporter

    fun start(localPort: Int, remotePort: Int, bindContext: android.content.Context?) {
        stop()
        // Port must be applied on the post-stop reporter — setRemoteRtcpPort
        // before start() used to be wiped by stop()'s new RtcpReporter().
        reporter = RtcpReporter()
        reporter.remoteRtcpPort = remotePort
        if (!running.compareAndSet(false, true)) {
            return
        }
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 64 * 1024
                soTimeout = 500
                bind(java.net.InetSocketAddress(localPort))
            }
            me.trinitrix.mirax.wfd.P2pNetworkBinder.bind(bindContext, sock)
            socket = sock
            val active = reporter
            thread = Thread({
                val buf = ByteArray(2048)
                Log.i(TAG, "RTCP listening on $localPort → :$remotePort")
                while (running.get() && !sock.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        active.onRtcpPacket(packet.data, packet.length)
                        // Prefer RTP source for RR dest; SR only fills if unknown.
                        if (active.remoteAddress == null) {
                            active.remoteAddress = packet.address
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Fall through to paced RR sends.
                    } catch (_: Exception) {
                        if (sock.isClosed || !running.get()) {
                            break
                        }
                    }
                    active.maybeSend(sock)
                }
            }, "mirax-rtcp").also {
                it.isDaemon = true
                it.start()
            }
        } catch (err: Exception) {
            Log.e(TAG, "RTCP bind failed on $localPort", err)
            running.set(false)
        }
    }

    fun onRtp(packet: ByteArray, length: Int, from: InetAddress) {
        val prev = reporter.remoteAddress
        // Always track the live RTP peer — Phh P2P can disagree with the RTSP
        // peer string, and a stale RR dest starves Win11 bitrate modulation.
        if (prev == null || prev != from) {
            if (prev != null) {
                Log.w(
                    TAG,
                    "RTCP dest follow RTP ${prev.hostAddress} → ${from.hostAddress}",
                )
            }
            reporter.remoteAddress = from
        }
        reporter.onRtpPacket(packet, length)
        socket?.let { reporter.maybeSend(it) }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread = null
        reporter = RtcpReporter()
    }

    companion object {
        private const val TAG = "MiraxRtcp"
    }
}
