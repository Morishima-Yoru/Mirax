package me.trinitrix.mirax.wfd

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.trinitrix.mirax.PictureActivity
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.WmSizeParser
import me.trinitrix.mirax.session.ConnectionRunFact
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.wfd.rtsp.MpegTsDepacketizer
import me.trinitrix.mirax.wfd.rtsp.RtspMessageBuffer
import me.trinitrix.mirax.wfd.rtsp.RtspSinkSession
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import me.trinitrix.mirax.wfd.rtsp.WfdVideoFormatCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * App-process Miracast receive path. The WFD source listens on its control
 * port; once the owner reports a formed P2P group, this sink dials the source,
 * runs RTSP through PLAY, and demuxes RTP. The app never calls [setWfdInfo];
 * the privileged owner only beacons and reports the group. Views do not
 * interpret RTSP — events go through [SessionHost] into
 * [me.trinitrix.mirax.session.MiraxSession].
 */
object SinkConnectionController {
    private const val TAG = "MiraxSink"
    private const val GROUP_POLL_MS = 500L
    private const val DIAL_TIMEOUT_MS = 3_000
    private const val DIAL_WINDOW_MS = 20_000L
    private const val READ_TIMEOUT_MS = 1_000
    private const val POKE_AFTER_MS = 2_000L
    private const val LOG_LIMIT = 700

    val decoder: H264SurfaceDecoder = H264SurfaceDecoder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val activeClient = AtomicReference<Socket?>(null)
    private val endedByUser = AtomicBoolean(false)
    private val lastTouchEnabled = AtomicBoolean(true)
    private val connectionRunOpen = AtomicBoolean(false)
    private var serveSelected: VideoMode? = null
    private var rtpSocket: DatagramSocket? = null
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
        UibcTouchChannel.context = appContext
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

    /**
     * End this connection while broadcast stays on: close RTSP and drop the
     * P2P group. The sink does not redial until the owner reports a new group.
     */
    fun dropActiveConnection() {
        endedByUser.set(true)
        closeQuietly(activeClient.get())
        WfdOwnerBridge.endSession()
    }

    private fun start(command: WfdAdvertiseCommand) {
        stop("restart")
        val gen = generation.incrementAndGet()
        running.set(true)
        Thread({ watchGroup(command, gen) }, "mirax-sink").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun alive(gen: Int): Boolean = running.get() && generation.get() == gen

    private fun watchGroup(command: WfdAdvertiseCommand, gen: Int) {
        postAction(SessionAction.BecameDiscoverable)
        var groupUp = false
        var sessionRan = false
        while (alive(gen)) {
            when (val state = P2pGroupState.parse(WfdOwnerBridge.groupState())) {
                is P2pGroupState.Up, P2pGroupState.Pending -> {
                    if (!groupUp) {
                        groupUp = true
                        sessionRan = false
                        endedByUser.set(false)
                        Log.i(TAG, "P2P group up")
                        postAction(SessionAction.PrePlayProgress)
                    }
                    if (state is P2pGroupState.Up && !sessionRan && !endedByUser.get()) {
                        beginConnectionRun(state.sourceAddress)
                        note("P2P group up")
                        val socket = dial(state.sourceAddress, gen)
                        if (socket != null) {
                            sessionRan = true
                            val reachedPlay = serve(socket, command, gen)
                            finishConnectionRun(reachedPlay, serveSelected)
                            if (alive(gen)) {
                                postAction(
                                    if (reachedPlay) SessionAction.ConnectionEnded
                                    else SessionAction.PrePlayGroupDropped,
                                )
                                closePicture()
                            }
                        } else {
                            finishConnectionRun(succeeded = false, selected = null)
                        }
                    }
                }
                P2pGroupState.Down -> {
                    if (groupUp) {
                        groupUp = false
                        Log.i(TAG, "P2P group down")
                        if (!sessionRan && alive(gen)) {
                            postAction(SessionAction.PrePlayGroupDropped)
                        }
                    }
                    endedByUser.set(false)
                }
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
    }

    private fun dial(host: String, gen: Int): Socket? {
        val deadline = SystemClock.elapsedRealtime() + DIAL_WINDOW_MS
        var attempt = 0
        while (alive(gen) && SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val socket = Socket()
            try {
                socket.bind(null)
                P2pNetworkBinder.bind(appContext, socket)
                socket.connect(InetSocketAddress(host, PrimarySinkBeacon.RTSP_CONTROL_PORT), DIAL_TIMEOUT_MS)
                note("RTSP connected to $host:${PrimarySinkBeacon.RTSP_CONTROL_PORT} (attempt $attempt)")
                return socket
            } catch (err: Exception) {
                closeQuietly(socket)
                if (attempt == 1 || attempt % 5 == 0) {
                    noteWarn("RTSP dial $host attempt $attempt failed: ${err.message}")
                }
            }
            if (P2pGroupState.parse(WfdOwnerBridge.groupState()) !is P2pGroupState.Up) {
                return null
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
        return null
    }

    /**
     * Run one RTSP session on [socket].
     *
     * Returns:
     *     true when the session reached PLAY.
     */
    private fun serve(socket: Socket, command: WfdAdvertiseCommand, gen: Int): Boolean {
        activeClient.set(socket)
        var reachedPlay = false
        var lastSelected: VideoMode? = null
        val offer = connectionOffer(command)
        val session = RtspSinkSession(
            WfdCapabilityTable(
                offer.modes,
                offer.preferred,
                offer.broadcastName,
                offer.touchEnabled,
            ),
        )
        val demux = MpegTsDepacketizer()
        val buffer = RtspMessageBuffer()
        val chunk = ByteArray(8192)
        val connectedAt = SystemClock.elapsedRealtime()
        var sawData = false
        var poked = false
        var uibcNoted = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            loop@ while (alive(gen) && !socket.isClosed) {
                val n = try {
                    input.read(chunk)
                } catch (_: SocketTimeoutException) {
                    if (!sawData && !poked && SystemClock.elapsedRealtime() - connectedAt >= POKE_AFTER_MS) {
                        poked = true
                        output.write(OPTIONS_POKE.toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                        note("RTSP poke OPTIONS")
                    }
                    if (P2pGroupState.parse(WfdOwnerBridge.groupState()) !is P2pGroupState.Up) {
                        note("P2P group gone during RTSP")
                        break@loop
                    }
                    continue@loop
                }
                if (n < 0) {
                    note("RTSP closed by source")
                    break
                }
                sawData = true
                buffer.append(chunk, n)
                while (true) {
                    val message = buffer.next() ?: break
                    noteExchange(incoming = true, message, offer.debug, offer.preferred)
                    for (reply in session.handle(message)) {
                        noteExchange(incoming = false, reply, offer.debug, offer.preferred)
                        output.write(reply.toByteArray(StandardCharsets.US_ASCII))
                    }
                    output.flush()
                    val selected = session.selectedMode
                    if (selected != null && selected != lastSelected) {
                        lastSelected = selected
                        serveSelected = selected
                        note("source selected ${selected.width}x${selected.height}@${selected.refreshHz}")
                        postAction(SessionAction.SourceSelectedMode(selected))
                        if (offer.touchEnabled) {
                            UibcTouchChannel.setPictureSize(selected.width, selected.height)
                        }
                    }
                    if (uibcNoted && session.uibcPort <= 0) {
                        UibcTouchChannel.close()
                        uibcNoted = false
                        note("UIBC disabled by source")
                    }
                    if (!uibcNoted && session.uibcPort > 0 && offer.touchEnabled) {
                        uibcNoted = true
                        val host = (socket.remoteSocketAddress as? java.net.InetSocketAddress)
                            ?.address
                            ?.hostAddress
                        note("UIBC port ${session.uibcPort} type ${session.uibcHidType}")
                        if (host != null) {
                            UibcTouchChannel.onStatus = { line -> note(line) }
                            UibcTouchChannel.open(host, session.uibcPort, session.uibcHidType)
                            session.selectedMode?.let {
                                UibcTouchChannel.setPictureSize(it.width, it.height)
                            }
                        }
                    }
                    if (session.state == "PLAYING" && !reachedPlay) {
                        reachedPlay = true
                        note("playback started")
                        val mode = session.selectedMode ?: VideoMode(1920, 1080, 60)
                        decoder.setFormat(mode.width, mode.height, mode.refreshHz)
                        startRtp(session.rtpPort(), demux, gen)
                        postAction(SessionAction.EnteredPlay)
                        openPicture()
                    }
                    if (session.state == "TEARDOWN" || session.state == "ERROR") {
                        note("RTSP session ${session.state}")
                        break@loop
                    }
                }
            }
        } catch (err: Exception) {
            if (alive(gen)) {
                noteWarn("RTSP session ended", err)
            }
        } finally {
            UibcTouchChannel.close()
            stopRtp()
            decoder.reset()
            activeClient.compareAndSet(socket, null)
            closeQuietly(socket)
        }
        return reachedPlay
    }

    private fun startRtp(port: Int, demux: MpegTsDepacketizer, gen: Int) {
        stopRtp()
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 2 * 1024 * 1024
                bind(InetSocketAddress(port))
            }
            P2pNetworkBinder.bind(appContext, socket)
            rtpSocket = socket
            note("RTP listening on $port")
            Thread({
                val buf = ByteArray(64 * 1024)
                var packets = 0L
                while (alive(gen) && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        if (++packets == 1L) {
                            note("first RTP packet from ${packet.address}")
                        }
                        demux.pushRtp(packet.data, packet.length)
                        while (true) {
                            val au = demux.poll() ?: break
                            decoder.submitAccessUnit(au)
                        }
                    } catch (_: Exception) {
                        if (socket.isClosed) {
                            break
                        }
                    }
                }
            }, "mirax-rtp").also {
                it.isDaemon = true
                it.start()
            }
        } catch (err: Exception) {
            noteWarn("RTP bind failed on $port", err)
        }
    }

    private fun stopRtp() {
        closeQuietly(rtpSocket)
        rtpSocket = null
    }

    private fun stop(reason: String) {
        Log.i(TAG, "stop ($reason)")
        generation.incrementAndGet()
        running.set(false)
        closeQuietly(activeClient.getAndSet(null))
        UibcTouchChannel.close()
        stopRtp()
        decoder.reset()
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

    private data class ConnectionOffer(
        val modes: Set<VideoMode>,
        val preferred: VideoMode?,
        val broadcastName: String,
        val touchEnabled: Boolean,
        val debug: Boolean,
    )

    /**
     * Read wm size and freeze the M3 offer on the main thread before RTSP.
     * Falls back to the listen-time command if the session does not answer.
     */
    private fun connectionOffer(fallback: WfdAdvertiseCommand): ConnectionOffer {
        val reading = WfdOwnerBridge.wmSize(null)?.let { WmSizeParser.parse(it) }
        val latch = CountDownLatch(1)
        val box = AtomicReference<ConnectionOffer>()
        val ctx = appContext
        mainHandler.post {
            try {
                if (ctx != null) {
                    val snap = SessionHost.freezeConnectionOffer(ctx, reading)
                    val modes = snap.connectionOfferModes ?: fallback.modes
                    lastTouchEnabled.set(snap.touchEnabled)
                    box.set(
                        ConnectionOffer(
                            modes = modes,
                            preferred = snap.connectionPreferredMode,
                            broadcastName = snap.effectiveBroadcastName.ifEmpty { fallback.broadcastName },
                            touchEnabled = snap.touchEnabled,
                            debug = snap.showDebugMessages,
                        ),
                    )
                }
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS) || box.get() == null) {
            val preferred = fallback.modes.firstOrNull { !WfdVideoFormatCodec.isStandardMode(it) }
            return ConnectionOffer(
                modes = fallback.modes,
                preferred = preferred,
                broadcastName = fallback.broadcastName,
                touchEnabled = true,
                debug = false,
            )
        }
        return box.get()
    }

    private fun beginConnectionRun(host: String) {
        postAction(SessionAction.BeginConnectionRun(host))
    }

    private fun finishConnectionRun(succeeded: Boolean, selected: VideoMode?) {
        val touch = lastTouchEnabled.get()
        postAction(
            SessionAction.FinishConnectionRun(
                succeeded = succeeded,
                metadata = listOf(
                    ConnectionRunFact("selected mode", selected?.format() ?: "none"),
                    ConnectionRunFact("touch", if (touch) "touch" else "display only"),
                ),
            ),
        )
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        postAction(SessionAction.AppendConnectionLog("I/MiraxSink: $line"))
    }

    private fun noteWarn(line: String, err: Exception? = null) {
        if (err != null) {
            Log.w(TAG, line, err)
        } else {
            Log.w(TAG, line)
        }
        val detail = err?.message?.let { "$line: $it" } ?: line
        postAction(SessionAction.AppendConnectionLog("W/MiraxSink: $detail"))
    }

    private fun noteExchange(
        incoming: Boolean,
        message: String,
        debug: Boolean,
        preferred: VideoMode?,
    ) {
        val start = message.lineSequence().firstOrNull().orEmpty().trim()
        val method = start.substringBefore(' ')
        when {
            incoming && method == "OPTIONS" -> note("M1 OPTIONS from source")
            !incoming && method == "OPTIONS" -> note("M2 OPTIONS to source")
            incoming && method == "GET_PARAMETER" -> {
                val asked = message.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("wfd_") || it.startsWith("microsoft_") }
                    .take(8)
                    .joinToString(" ")
                note("M3 GET_PARAMETER $asked".trim())
            }
            !incoming && start.startsWith("RTSP/1.0") && message.contains("wfd_video_formats") -> {
                val label = preferred?.let { "${it.width}x${it.height}@${it.refreshHz}" } ?: "none"
                note("M3 reply preferred $label")
            }
            incoming && method == "SET_PARAMETER" -> note("M4 SET_PARAMETER")
        }
        if (debug) {
            val arrow = if (incoming) "RTSP << " else "RTSP >> "
            note(arrow + oneLine(message))
        }
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun oneLine(message: String): String {
        val flat = message.trimEnd().replace("\r\n", " | ")
        return if (flat.length > LOG_LIMIT) flat.substring(0, LOG_LIMIT) + "…" else flat
    }

    private const val OPTIONS_POKE = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
}
