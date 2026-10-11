package me.trinitrix.mirax.wfd

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.trinitrix.mirax.PictureActivity
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.wm.WmSize
import me.trinitrix.mirax.session.ConnectionRunFact
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.PreferredModeCorrection
import me.trinitrix.mirax.session.StandardVideoModes
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.wfd.rtsp.MpegTsDepacketizer
import me.trinitrix.mirax.wfd.rtsp.RtcpChannel
import me.trinitrix.mirax.wfd.rtsp.RtspMessageBuffer
import me.trinitrix.mirax.wfd.rtsp.RtspSinkSession
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
import me.trinitrix.mirax.wfd.rtsp.WfdVideoFormatCodec
import java.io.OutputStream
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
 * App-process Miracast receive path.
 *
 * Windows Miracast sources listen on RTSP 7236; the sink always dials that
 * peer after the P2P group is up (GO role only assigns IPs). The app never
 * calls `setWfdInfo`; the privileged owner only beacons and reports the
 * group. Views do not interpret RTSP — events go through [SessionHost] into
 * [me.trinitrix.mirax.session.MiraxSession].
 */
object SinkConnectionController {
    private const val TAG = "MiraxSink"
    private const val GROUP_POLL_MS = 500L
    private const val DIAL_TIMEOUT_MS = 3_000
    private const val DIAL_WINDOW_MS = 20_000L
    private const val PAIRING_WAIT_MS = 20_000L
    // Short so RTSP keepalives / IDR flushes are not stuck behind a full second.
    private const val READ_TIMEOUT_MS = 50
    private const val POKE_AFTER_MS = 2_000L
    /**
     * Interactive sessions need enough microsoft_max_bitrate that Windows keeps
     * encoding during motion. A user floor of 1 Mbps made the source go sparse
     * (few frames/sec) even when UIBC was active; 8 Mbps still measured ~2 Mbps
     * on device, so the interactive floor/cap sit higher.
     */
    private const val INTERACTIVE_BITRATE_FLOOR_BPS = 12_000_000L
    private const val INTERACTIVE_BITRATE_CAP_BPS = 20_000_000L
    /** Interactive refresh ceiling — resolution stays Settings/wm-owned. */
    private const val INTERACTIVE_MAX_FPS = 30
    /**
     * Gap since the previous UIBC input before a touch may request one IDR.
     * Idle-only: mid-gesture IDR was ~500ms Windows RTT and caused cursor drift
     * while UIBC reports piled up behind each keyframe.
     */
    private const val TOUCH_IDLE_IDR_INPUT_GAP_MS = 1_500L
    /** Idle-resume IDR rate limit. */
    private const val TOUCH_IDLE_IDR_MIN_INTERVAL_MS = 4_000
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
    private val idrNeeded = AtomicBoolean(false)
    private val lastTouchIdleIdrMs = AtomicInteger(0)
    private val lastUibcActivityMs = AtomicInteger(0)
    private val rtspIdrLock = Any()
    private val playSession = AtomicReference<RtspSinkSession?>(null)
    private val playOutput = AtomicReference<OutputStream?>(null)
    /** Last microsoft latency mode reported by the source (log / diagnostics). */
    @Volatile
    private var lastLatencyModeSeen: String = ""
    private var serveSelected: VideoMode? = null
    private var rtpSocket: DatagramSocket? = null
    private val rtcpChannel = RtcpChannel()
    private var appContext: Context? = null

    init {
        decoder.onNeedKeyframe = { idrNeeded.set(true) }
    }

    /**
     * Interactive UIBC catch-up: after a real input idle gap request a single
     * IDR so the first touch after pause is not waiting on a frozen desktop
     * encode.
     *
     * Continuous motion must NOT request IDR — Windows IDR RTT (~500ms) plus a
     * backed-up UIBC write queue is what felt like lag then cursor drift.
     *
     * Latency: M3 answers `low` and we may send one post-PLAY SET_PARAMETER
     * when the source still announces `high`. Repeated mid-scroll nudges used
     * to stall encode; keep that path single-shot per session.
     */
    fun requestIdrForTouch() {
        val now = SystemClock.elapsedRealtime()
        val nowInt = now.toInt()
        val previousActivity = lastUibcActivityMs.getAndSet(nowInt)
        val inputGapMs = if (previousActivity == 0) {
            TOUCH_IDLE_IDR_INPUT_GAP_MS
        } else {
            (nowInt - previousActivity).toLong()
        }

        if (inputGapMs < TOUCH_IDLE_IDR_INPUT_GAP_MS) {
            return
        }
        val previousIdr = lastTouchIdleIdrMs.get()
        if (nowInt - previousIdr < TOUCH_IDLE_IDR_MIN_INTERVAL_MS) {
            return
        }
        if (!lastTouchIdleIdrMs.compareAndSet(previousIdr, nowInt)) {
            return
        }
        idrNeeded.set(true)
        flushIdrNow()
    }

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
                        if (!awaitPairingCompletion(gen)) {
                            finishConnectionRun(succeeded = false, selected = null)
                            continue
                        }
                        beginConnectionRun(state.sourceAddress)
                        note(
                            if (state.phoneIsOwner) {
                                "P2P group up (phone is GO); dialing Windows RTSP"
                            } else {
                                "P2P group up (phone is client); dialing source"
                            },
                        )
                        // Windows listens on 7236 whether or not it is GO.
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

    private fun awaitPairingCompletion(gen: Int): Boolean {
        val initialState = WfdOwnerBridge.pairingState()
        if (initialState != "PAIRING") {
            return true
        }
        val deadline = SystemClock.elapsedRealtime() + PAIRING_WAIT_MS
        Log.i(TAG, "Waiting for WPS pairing before RTSP dial")
        while (alive(gen) && SystemClock.elapsedRealtime() < deadline) {
            if (P2pGroupState.parse(WfdOwnerBridge.groupState()) !is P2pGroupState.Up) {
                return false
            }
            when (WfdOwnerBridge.pairingState()) {
                "PAIRED" -> {
                    Log.i(TAG, "WPS pairing ready; starting RTSP dial")
                    return true
                }
                "UNPAIRED" -> {
                    Log.w(TAG, "WPS pairing did not complete; skipping RTSP dial")
                    return false
                }
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
        Log.w(TAG, "Timed out waiting for WPS pairing; skipping RTSP dial")
        return false
    }

    private fun dial(host: String, gen: Int): Socket? {
        val deadline = SystemClock.elapsedRealtime() + DIAL_WINDOW_MS
        var attempt = 0
        while (alive(gen) && SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val socket = Socket()
            try {
                if (!P2pNetworkBinder.bind(appContext, socket)) {
                    closeQuietly(socket)
                    if (!alive(gen)) {
                        return null
                    }
                    if (P2pGroupState.parse(WfdOwnerBridge.groupState()) !is P2pGroupState.Up) {
                        noteWarn("P2P group ended before the interface became ready")
                        return null
                    }
                    if (attempt == 1 || attempt % 10 == 0) {
                        note("Waiting for P2P interface before RTSP dial (attempt $attempt)")
                    }
                    SystemClock.sleep(GROUP_POLL_MS)
                    continue
                }
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
        if (alive(gen)) {
            noteWarn("RTSP dial window expired before connection")
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
        var lastLatencyMode = ""
        val offer = connectionOffer(command)
        // Settings own resolution. Interactive only remaps refresh >30 → 30 so
        // Win11 cannot pick *@60 (that path pinned ~550 ms bursts + sub‑Mbps ABR
        // on HA1CSQTM). Do not inject or lock width/height.
        val advertiseModes = offer.modes
            .plus(if (offer.touchEnabled) {
                remappedInteractiveRefresh(offer.modes)
            } else {
                emptySet()
            })
            .toSet()
        val advertisePreferred = if (offer.touchEnabled) {
            offer.preferred?.let { remappedInteractiveRefresh(it) } ?: offer.preferred
        } else {
            offer.preferred
        }
        val advertiseBitrate = if (offer.touchEnabled) {
            maxOf(offer.maxVideoBitrateBps, INTERACTIVE_BITRATE_FLOOR_BPS)
                .coerceAtMost(INTERACTIVE_BITRATE_CAP_BPS)
        } else {
            offer.maxVideoBitrateBps
        }
        if (offer.touchEnabled) {
            note(
                "interactive offer bitrate=${advertiseBitrate / 1_000}kbps " +
                    "microsoft_max_bitrate=$advertiseBitrate " +
                    "preferred=${advertisePreferred?.format() ?: "none"} modes=${advertiseModes.size}",
            )
        }
        val session = RtspSinkSession(
            WfdCapabilityTable(
                advertiseModes,
                advertisePreferred,
                offer.broadcastName,
                offer.touchEnabled,
                advertiseBitrate,
            ),
        )
        val demux = MpegTsDepacketizer()
        val buffer = RtspMessageBuffer()
        val chunk = ByteArray(8192)
        val connectedAt = SystemClock.elapsedRealtime()
        var sawData = false
        var poked = false
        var uibcNoted = false
        idrNeeded.set(false)
        lastLatencyModeSeen = ""
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            playSession.set(session)
            playOutput.set(output)
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
                    writeIdrIfNeeded(session, output)
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
                    noteExchange(incoming = true, message, offer.preferred)
                    for (reply in session.handle(message)) {
                        noteExchange(incoming = false, reply, offer.preferred)
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
                    val latencyMode = session.latencyMode
                    if (latencyMode.isNotEmpty() && latencyMode != lastLatencyMode) {
                        lastLatencyMode = latencyMode
                        lastLatencyModeSeen = latencyMode
                        note("source latency mode $latencyMode")
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
                        startRtcp(session)
                        startRtp(session.rtpPort(), demux, gen)
                        startHardwareCursor()
                        postAction(SessionAction.EnteredPlay)
                    }
                    // Latency role switching disabled: interactive M3 advertises
                    // microsoft_latency_management_capability: none.
                    if (session.state == "TEARDOWN" || session.state == "ERROR") {
                        note("RTSP session ${session.state}")
                        break@loop
                    }
                }
                writeIdrIfNeeded(session, output)
            }
        } catch (err: Exception) {
            if (alive(gen)) {
                noteWarn("RTSP session ended", err)
            }
        } finally {
            playSession.compareAndSet(session, null)
            playOutput.set(null)
            UibcTouchChannel.close()
            stopHardwareCursor()
            stopRtp()
            stopRtcp()
            decoder.reset()
            activeClient.compareAndSet(socket, null)
            closeQuietly(socket)
        }
        return reachedPlay
    }

    private fun flushIdrNow() {
        val session = playSession.get() ?: return
        val output = playOutput.get() ?: return
        writeIdrIfNeeded(session, output)
    }

    private fun writeIdrIfNeeded(session: RtspSinkSession, output: OutputStream) {
        synchronized(rtspIdrLock) {
            if (!idrNeeded.get()) {
                return
            }
            val request = session.requestIdr()
            if (request.isEmpty()) {
                return
            }
            try {
                output.write(request.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                idrNeeded.set(false)
                decoder.markIdrRequested()
                note("RTSP >> IDR refresh")
            } catch (err: Exception) {
                noteWarn("IDR refresh failed", err)
            }
        }
    }

    private fun startRtcp(session: RtspSinkSession) {
        stopRtcp()
        val remoteRtcp = session.rtcpServerPort
        if (remoteRtcp <= 0) {
            note("RTCP not negotiated (no server_port pair)")
            return
        }
        rtcpChannel.start(session.rtcpPort(), remoteRtcp, appContext)
        note("RTCP RR → source :$remoteRtcp (local ${session.rtcpPort()})")
    }

    private fun stopRtcp() {
        rtcpChannel.stop()
    }

    private fun startRtp(port: Int, demux: MpegTsDepacketizer, gen: Int) {
        stopRtp()
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                // Win11 bursts ~1s of AUs; undersized rcvbuf → drops → RTCP
                // loss → ABR stuck near 2–3 Mbps (Fold/Samsung does not show this).
                receiveBufferSize = 1024 * 1024
                bind(InetSocketAddress(port))
            }
            P2pNetworkBinder.bind(appContext, socket)
            rtpSocket = socket
            note("RTP listening on $port")
            Thread({
                val buf = ByteArray(64 * 1024)
                var packets = 0L
                var lastRtpElapsed = 0L
                var lastRtpGapLogMs = 0L
                var auSinceGap = 0
                while (alive(gen) && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val now = SystemClock.elapsedRealtime()
                        if (++packets == 1L) {
                            note("first RTP packet from ${packet.address}")
                        } else {
                            val gap = now - lastRtpElapsed
                            if (gap >= 80L) {
                                val up = SystemClock.uptimeMillis()
                                if (up - lastRtpGapLogMs >= 200L) {
                                    lastRtpGapLogMs = up
                                    Log.w(
                                        TAG,
                                        "RTP gap_ms=$gap au_since=${auSinceGap} from=${packet.address}",
                                    )
                                    auSinceGap = 0
                                }
                            }
                        }
                        lastRtpElapsed = now
                        rtcpChannel.onRtp(packet.data, packet.length, packet.address)
                        demux.pushRtp(packet.data, packet.length)
                        while (true) {
                            val au = demux.poll() ?: break
                            auSinceGap++
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

    private fun startHardwareCursor() {
        if (!WfdCapabilityTable.ADVERTISE_HARDWARE_CURSOR) {
            note("hardware cursor off (local echo)")
            return
        }
        MicrosoftCursorChannel.onStatus = { line -> note(line) }
        MicrosoftCursorChannel.open(appContext)
        note("hardware cursor advertised on ${MicrosoftCursorChannel.UDP_PORT}")
    }

    private fun stopHardwareCursor() {
        MicrosoftCursorChannel.close()
        MicrosoftCursorChannel.onStatus = null
    }

    private fun stop(reason: String) {
        Log.i(TAG, "stop ($reason)")
        generation.incrementAndGet()
        running.set(false)
        closeQuietly(activeClient.getAndSet(null))
        UibcTouchChannel.close()
        stopHardwareCursor()
        stopRtp()
        stopRtcp()
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
        val maxVideoBitrateBps: Long,
    )

    /**
     * Keep width/height; cap refresh at [INTERACTIVE_MAX_FPS] for touch sessions.
     */
    private fun remappedInteractiveRefresh(mode: VideoMode): VideoMode {
        val remapped = if (mode.refreshHz <= INTERACTIVE_MAX_FPS) {
            mode
        } else {
            VideoMode(mode.width, mode.height, INTERACTIVE_MAX_FPS)
        }
        val level51Compliant = PreferredModeCorrection.correct(
            remapped.width, remapped.height, remapped.refreshHz
        )
        return level51Compliant ?: run {
            Log.w(TAG, "interactive remap ${mode.format()} exceeds Level 5.1, keeping original")
            mode
        }
    }

    private fun remappedInteractiveRefresh(modes: Set<VideoMode>): Set<VideoMode> =
        modes.map(::remappedInteractiveRefresh).toSet()

    /**
     * Read wm size and freeze the M3 offer on the main thread before RTSP.
     * Falls back to the listen-time command if the session does not answer.
     */
    private fun connectionOffer(fallback: WfdAdvertiseCommand): ConnectionOffer {
        val reading = WfdOwnerBridge.wmSize(null)?.let { WmSize.parse(it) }
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
                            maxVideoBitrateBps = snap.maxVideoBitrateBps,
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
                maxVideoBitrateBps = StandardVideoModes.BITRATE_CAP_BPS,
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
            !incoming && message.contains("microsoft_max_bitrate") -> {
                val match = MICROSOFT_MAX_BITRATE_LINE.find(message)
                if (match != null) {
                    note("M3 advertise microsoft_max_bitrate=${match.groupValues[1]}")
                }
            }
            incoming && method == "SET_PARAMETER" -> note("M4 SET_PARAMETER")
        }
        val arrow = if (incoming) "RTSP << " else "RTSP >> "
        note(arrow + oneLine(message))
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

    private val MICROSOFT_MAX_BITRATE_LINE =
        Regex("microsoft_max_bitrate:\\s*(\\d+)", RegexOption.IGNORE_CASE)
}
