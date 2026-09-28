package me.trinitrix.mirax.wfd

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.PictureActivity
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.wfd.rtsp.MpegTsDepacketizer
import me.trinitrix.mirax.wfd.rtsp.RtspSinkSession
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import me.trinitrix.mirax.wfd.rtsp.WfdVideoFormatCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * App-process Miracast receive path: RTSP on the advertised control port, RTP
 * demux, and session connection events. The app never calls [setWfdInfo]; the
 * privileged owner only beacons. Views do not interpret RTSP — events go through
 * [SessionHost] into [me.trinitrix.mirax.session.MiraxSession].
 */
object SinkConnectionController {
    private const val TAG = "MiraxSink"
    private const val RTSP_PORT = 7236

    val decoder: H264SurfaceDecoder = H264SurfaceDecoder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private var listenThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var rtpSocket: DatagramSocket? = null
    private var activeClient: Socket? = null
    private var reachedPlay: Boolean = false
    private var appContext: Context? = null

    /**
     * Sync the receive path to the session advertise command.
     *
     * Args:
     *     context: Application context for launching the picture activity.
     *     command: Non-null while broadcast is on and an owner exists.
     */
    fun sync(context: Context, command: WfdAdvertiseCommand?) {
        appContext = context.applicationContext
        if (command == null) {
            if (lastCommand.getAndSet(null) != null || running.get()) {
                stop("advertise off")
            }
            return
        }
        val previous = lastCommand.get()
        if (previous != null &&
            previous.broadcastName == command.broadcastName &&
            previous.modes == command.modes &&
            running.get()
        ) {
            lastCommand.set(command)
            return
        }
        lastCommand.set(command)
        start(command)
    }

    private fun start(command: WfdAdvertiseCommand) {
        stop("restart")
        val gen = generation.incrementAndGet()
        running.set(true)
        reachedPlay = false
        listenThread = Thread({
            try {
                val server = ServerSocket(RTSP_PORT)
                serverSocket = server
                server.soTimeout = 1000
                Log.i(TAG, "RTSP listening on $RTSP_PORT")
                postAction(SessionAction.BecameDiscoverable)
                while (running.get() && generation.get() == gen) {
                    try {
                        val client = server.accept()
                        activeClient = client
                        serveClient(client, command, gen)
                    } catch (_: SocketTimeoutException) {
                        // Allow stop checks.
                    } catch (err: Exception) {
                        if (running.get() && generation.get() == gen) {
                            Log.w(TAG, "accept failed", err)
                        }
                    } finally {
                        activeClient = null
                    }
                }
            } catch (err: Exception) {
                if (generation.get() == gen) {
                    Log.e(TAG, "RTSP server failed", err)
                }
            }
        }, "mirax-rtsp").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun serveClient(client: Socket, command: WfdAdvertiseCommand, gen: Int) {
        reachedPlay = false
        var lastSelected: VideoMode? = null
        postAction(SessionAction.PrePlayProgress)
        val preferred = command.modes.firstOrNull { !WfdVideoFormatCodec.isStandardMode(it) }
        val caps = WfdCapabilityTable(command.modes, preferred, command.broadcastName)
        val session = RtspSinkSession(caps)
        val demux = MpegTsDepacketizer()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            while (running.get() && generation.get() == gen && !client.isClosed) {
                val message = readRtspMessage(input) ?: break
                val replies = session.handle(message)
                for (reply in replies) {
                    output.write(reply.toByteArray(StandardCharsets.US_ASCII))
                }
                output.flush()
                val selected = session.selectedMode
                if (selected != null && selected != lastSelected) {
                    lastSelected = selected
                    postAction(SessionAction.SourceSelectedMode(selected))
                }
                if (session.state == "PLAYING" && !reachedPlay) {
                    reachedPlay = true
                    val mode = session.selectedMode
                        ?: VideoMode(1920, 1080, 60)
                    decoder.setFormat(mode.width, mode.height, mode.refreshHz)
                    startRtp(session.rtpPort(), demux, gen)
                    postAction(SessionAction.EnteredPlay)
                    openPicture()
                }
                if (session.state == "TEARDOWN" || session.state == "ERROR") {
                    break
                }
            }
        } catch (err: Exception) {
            if (generation.get() == gen) {
                Log.w(TAG, "RTSP client ended", err)
            }
        } finally {
            stopRtp()
            decoder.reset()
            try {
                client.close()
            } catch (_: Exception) {
            }
            if (running.get() && generation.get() == gen) {
                if (reachedPlay) {
                    postAction(SessionAction.ConnectionEnded)
                } else {
                    postAction(SessionAction.PrePlayGroupDropped)
                }
            }
            reachedPlay = false
            if (generation.get() == gen) {
                closePicture()
            }
        }
    }

    private fun startRtp(port: Int, demux: MpegTsDepacketizer, gen: Int) {
        stopRtp()
        try {
            val socket = DatagramSocket(port)
            rtpSocket = socket
            Thread({
                val buf = ByteArray(64 * 1024)
                while (running.get() && generation.get() == gen && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        demux.pushRtp(packet.data, packet.length)
                        while (true) {
                            val au = demux.poll() ?: break
                            decoder.submitAccessUnit(au)
                        }
                    } catch (_: Exception) {
                        if (running.get() && generation.get() == gen && !socket.isClosed) {
                            break
                        }
                    }
                }
            }, "mirax-rtp").also {
                it.isDaemon = true
                it.start()
            }
        } catch (err: Exception) {
            Log.e(TAG, "RTP bind failed on $port", err)
        }
    }

    private fun stopRtp() {
        try {
            rtpSocket?.close()
        } catch (_: Exception) {
        }
        rtpSocket = null
    }

    private fun stop(reason: String) {
        Log.i(TAG, "stop ($reason)")
        generation.incrementAndGet()
        running.set(false)
        try {
            activeClient?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        stopRtp()
        decoder.reset()
        listenThread = null
        closePicture()
    }

    private fun openPicture() {
        val ctx = appContext ?: return
        mainHandler.post {
            ctx.startActivity(
                Intent(ctx, PictureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun closePicture() {
        mainHandler.post {
            PictureActivity.finishIfShowing()
        }
    }

    private fun postAction(action: SessionAction) {
        val ctx = appContext ?: return
        mainHandler.post {
            SessionHost.dispatchConnectionEvent(ctx, action)
        }
    }

    private fun readRtspMessage(input: BufferedInputStream): String? {
        val headerLines = ArrayList<String>()
        while (true) {
            val line = readLine(input) ?: return if (headerLines.isEmpty()) null else null
            if (line.isEmpty()) {
                break
            }
            headerLines.add(line)
        }
        if (headerLines.isEmpty()) {
            return null
        }
        var contentLength = 0
        for (line in headerLines) {
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = try {
                    line.substringAfter(':').trim().toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }
        }
        val sb = StringBuilder()
        for (line in headerLines) {
            sb.append(line).append("\r\n")
        }
        sb.append("\r\n")
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = input.read(body, offset, contentLength - offset)
                if (n < 0) {
                    break
                }
                offset += n
            }
            sb.append(String(body, 0, offset, StandardCharsets.US_ASCII))
        }
        return sb.toString()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString(StandardCharsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                buf.write(b)
            }
        }
        return buf.toString(StandardCharsets.US_ASCII.name())
    }
}
