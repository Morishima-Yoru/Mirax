package me.trinitrix.mirax.wfd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MS-WDHCE] hardware-cursor UDP side channel.
 *
 * Decouples pointer motion from Miracast video encode so UIBC-driven cursor
 * updates are not stuck behind sparse desktop frames.
 */
object MicrosoftCursorChannel {
    private const val TAG = "MiraxCursor"
    /** Keep off RTP+1 (19001) so RTCP receiver reports can use the WFD pair. */
    const val UDP_PORT: Int = 19_002
    private const val MAX_CURSOR_EDGE: Int = 64
    private const val TOUCH_CURSOR_LOG_INTERVAL_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var shapeBuffer: ByteArray? = null
    private var shapeFilled: BooleanArray? = null
    private var shapeExpected: Int = 0
    private var shapeId: Int = -1
    private var lastHotspotX: Int = 0
    private var lastHotspotY: Int = 0
    private var lastTouchCursorLogMs: Long = 0L

    @Volatile
    var onPosition: ((x: Int, y: Int) -> Unit)? = null

    @Volatile
    var onBitmap: ((bitmap: Bitmap?, hotspotX: Int, hotspotY: Int) -> Unit)? = null

    @Volatile
    var onStatus: ((String) -> Unit)? = null

    fun capabilityValue(): String {
        // Must not start with bare "none" — that token alone means unsupported.
        // ABNF: xor-support SP x-max SP y-max SP port (port is 4HEXDIG).
        val edge = MAX_CURSOR_EDGE.toString(16).padStart(4, '0')
        val portHex = UDP_PORT.toString(16).padStart(4, '0')
        return "full 0x$edge 0x$edge $portHex"
    }

    fun open(context: Context?) {
        if (!running.compareAndSet(false, true)) {
            return
        }
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 64 * 1024
                bind(InetSocketAddress(UDP_PORT))
            }
            P2pNetworkBinder.bind(context, sock)
            socket = sock
            thread = Thread({
                val buf = ByteArray(64 * 1024)
                var packets = 0L
                var parsed = 0L
                var lastStatsMs = 0L
                while (running.get() && !sock.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        packets++
                        if (packets == 1L) {
                            onStatus?.invoke("hardware cursor UDP on $UDP_PORT")
                            Log.i(TAG, "first cursor packet from ${packet.address} len=${packet.length}")
                            logPacketHex(buf, packet.length, "first")
                        }
                        if (handlePacket(buf, packet.length)) {
                            parsed++
                        } else if (packets <= 5L) {
                            logPacketHex(buf, packet.length, "unparsed#$packets")
                        }
                        val now = SystemClock.uptimeMillis()
                        if (now - lastStatsMs >= 2_000L) {
                            lastStatsMs = now
                            Log.i(TAG, "stats pkts=$packets parsed=$parsed")
                        }
                    } catch (_: Exception) {
                        if (sock.isClosed || !running.get()) {
                            break
                        }
                    }
                }
            }, "mirax-cursor").also {
                it.isDaemon = true
                it.start()
            }
            Log.i(TAG, "listening on $UDP_PORT")
        } catch (err: Exception) {
            running.set(false)
            Log.e(TAG, "cursor bind failed on $UDP_PORT", err)
            onStatus?.invoke("hardware cursor bind failed")
        }
    }

    fun close() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread = null
        shapeBuffer = null
        shapeFilled = null
        shapeExpected = 0
        shapeId = -1
        mainHandler.post {
            onBitmap?.invoke(null, 0, 0)
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int): Boolean {
        val msg = MicrosoftCursorPackets.parse(packet, length) ?: return false
        when (msg) {
            is MicrosoftCursorPackets.Message.Position -> {
                maybeLogTouchToCursor()
                mainHandler.post { onPosition?.invoke(msg.x, msg.y) }
            }
            is MicrosoftCursorPackets.Message.ShapeStart -> {
                if (msg.imageType == MicrosoftCursorPackets.IMAGE_DISABLED) {
                    resetShape()
                    mainHandler.post { onBitmap?.invoke(null, 0, 0) }
                    mainHandler.post { onPosition?.invoke(msg.x, msg.y) }
                    return true
                }
                shapeId = msg.imageId
                shapeExpected = msg.totalImageBytes.coerceAtLeast(0)
                shapeBuffer = ByteArray(shapeExpected)
                shapeFilled = BooleanArray(shapeExpected)
                lastHotspotX = msg.hotspotX
                lastHotspotY = msg.hotspotY
                copyChunk(0, msg.imageChunk)
                mainHandler.post { onPosition?.invoke(msg.x, msg.y) }
                maybeDecodeShape()
            }
            is MicrosoftCursorPackets.Message.ShapeContinue -> {
                if (msg.imageId != shapeId) {
                    return true
                }
                copyChunk(msg.payloadOffset, msg.imageChunk)
                maybeDecodeShape()
            }
        }
        return true
    }

    private fun resetShape() {
        shapeBuffer = null
        shapeFilled = null
        shapeExpected = 0
        shapeId = -1
    }

    private fun copyChunk(offset: Int, chunk: ByteArray) {
        val dest = shapeBuffer ?: return
        val filled = shapeFilled ?: return
        if (chunk.isEmpty() || offset < 0 || offset >= dest.size) {
            return
        }
        val n = minOf(chunk.size, dest.size - offset)
        System.arraycopy(chunk, 0, dest, offset, n)
        for (i in 0 until n) {
            filled[offset + i] = true
        }
    }

    private fun maybeDecodeShape() {
        val buf = shapeBuffer ?: return
        val filled = shapeFilled ?: return
        if (shapeExpected <= 0 || filled.any { !it }) {
            return
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(buf, 0, shapeExpected)
        } catch (_: Exception) {
            null
        } ?: return
        val hx = lastHotspotX
        val hy = lastHotspotY
        mainHandler.post { onBitmap?.invoke(bitmap, hx, hy) }
        Log.i(TAG, "cursor shape ${bitmap.width}x${bitmap.height} id=$shapeId")
    }

    private fun maybeLogTouchToCursor() {
    }

    private fun logPacketHex(packet: ByteArray, length: Int, label: String) {
        val n = minOf(length, 64)
        val hex = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) {
                hex.append(' ')
            }
            hex.append(String.format("%02x", packet[i].toInt() and 0xFF))
        }
        Log.i(TAG, "packet $label len=$length hex=$hex")
    }
}
