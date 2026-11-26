package me.trinitrix.mirax.wfd

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.trinitrix.mirax.PictureActivity
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.wfd.rtsp.MpegTsDepacketizer
import me.trinitrix.mirax.wfd.rtsp.RtcpChannel
import me.trinitrix.mirax.wfd.rtsp.RtspMessageBuffer
import me.trinitrix.mirax.wfd.rtsp.RtspFailure
import me.trinitrix.mirax.wfd.rtsp.RtspSinkSession
import me.trinitrix.mirax.wfd.rtsp.terminalFailureToForward
import me.trinitrix.mirax.wfd.rtsp.WfdCapabilityTable
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.SinkAttemptSource

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
    private const val MAIN_CALLBACK_WAIT_MS = 2_000L
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
    private val groupSequence = AtomicLong(0L)
    private val runSequence = AtomicLong(0L)
    private val stopSequence = AtomicLong(0L)
    private val lastCommand = AtomicReference<WfdAdvertiseCommand?>(null)
    private val activeClient = AtomicReference<Socket?>(null)
    private val endedByUser = SinkUserEnd()
    private val automaticEnd = SinkAutomaticEnd()
    private val sourceBinding = AtomicReference<SinkSourceBinding?>(null)
    private val provenDownBinding = AtomicReference<SinkSourceBinding?>(null)
    private val registeredBinding = AtomicReference<SinkSourceBinding?>(null)
    private val rtspIdrLock = Any()
    private data class PlayState(
        val session: RtspSinkSession,
        val output: OutputStream,
        val runToken: SinkRunToken,
        val idrNeeded: AtomicBoolean = AtomicBoolean(false),
        val lastTouchIdleIdrMs: AtomicInteger = AtomicInteger(0),
        val lastUibcActivityMs: AtomicInteger = AtomicInteger(0),
    )
    private val playState = AtomicReference<PlayState?>(null)
    private data class PendingSourceRegistration(
        val source: SinkAttemptSource,
        val expected: Pair<SinkAttemptSource?, SinkRunToken?>,
        val stopSequence: Long,
        val request: SinkSourceRegistration,
        val callback: Runnable,
        val deadline: Long,
        var timeoutReported: Boolean = false,
    )
    private var rtpSocket: DatagramSocket? = null
    private val audioPlayer = LpcmAudioPlayer()
    private var rtcpChannel: RtcpChannel? = null
    private var appContext: Context? = null
    private val eventArbiter = SinkEventArbiter(
        onAction = { action ->
            if (action is SessionAction.AppendConnectionLog || action is SessionAction.FinishConnectionRun) {
                SessionHost.onSinkHistoryEvent(action)
            } else {
                SessionHost.dispatchConnectionEvent(checkNotNull(appContext), action)
            }
        },
        onAttempt = SessionHost::onSinkAttemptStarted,
        onDrop = SessionHost::onSinkGroupDropped,
        onTerminal = SessionHost::onRtspTerminal,
        onDialTimeout = SessionHost::onTcpEstablishmentTimeout,
    )

    init {
        decoder.onNeedKeyframe = { playState.get()?.idrNeeded?.set(true) }
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
        val state = playState.get() ?: return
        if (!isCurrentRun(state.runToken)) return
        val now = SystemClock.elapsedRealtime()
        val nowInt = now.toInt()
        val previousActivity = state.lastUibcActivityMs.getAndSet(nowInt)
        val inputGapMs = if (previousActivity == 0) {
            TOUCH_IDLE_IDR_INPUT_GAP_MS
        } else {
            (nowInt - previousActivity).toLong()
        }

        if (inputGapMs < TOUCH_IDLE_IDR_INPUT_GAP_MS) {
            return
        }
        val previousIdr = state.lastTouchIdleIdrMs.get()
        if (nowInt - previousIdr < TOUCH_IDLE_IDR_MIN_INTERVAL_MS) {
            return
        }
        if (!state.lastTouchIdleIdrMs.compareAndSet(previousIdr, nowInt)) {
            return
        }
        state.idrNeeded.set(true)
        writeIdrIfNeeded(state)
    }

    /**
     * Sync the receive path to the session advertise command.
     *
     * Args:
     *     context: Application context for launching the picture activity.
     *     command: Non-null while broadcast is on and an owner exists.
     */
    fun sync(context: Context, command: WfdAdvertiseCommand?) {
        check(Looper.getMainLooper()?.isCurrentThread == true)
        appContext = context.applicationContext
        UibcTouchChannel.context = appContext
        if (command == null) {
            if (lastCommand.getAndSet(null) != null || running.get()) {
                stop("advertise off")
            }
            return
        }
        val previous = lastCommand.get()
        if (previous == command && running.get()) {
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
        check(Looper.getMainLooper()?.isCurrentThread == true)
        stopSequence.incrementAndGet()
        automaticEnd.clear()
        val observed = sourceBinding.getAndSet(null)
        val registered = registeredBinding.getAndSet(null)
        val observation = selectSinkEndObservation(eventArbiter.source, registered, observed)
        endedByUser.begin(observation, WfdOwnerBridge::submitEndSession)
        eventArbiter.retireSource()
        closeQuietly(activeClient.getAndSet(null))
        resetPlayback()
        SessionHost.onSinkStopped()
    }

    private fun start(command: WfdAdvertiseCommand) {
        val stoppedGeneration = stop("restart")
        if (generation.get() != stoppedGeneration || running.get() || lastCommand.get() != command) return
        val gen = generation.incrementAndGet()
        eventArbiter.start(gen)
        running.set(true)
        Thread({ watchGroup(command, gen) }, "mirax-sink").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun alive(gen: Int): Boolean = running.get() && generation.get() == gen

    /** Captures registered and observed identities for asynchronous provisioning. */
    internal fun provisioningFence(): SinkProvisioningFence {
        val binding = sourceBinding.get()
        return SinkProvisioningFence(generation.get(), eventArbiter.source, binding?.source, binding?.observation)
    }

    private fun watchGroup(command: WfdAdvertiseCommand, gen: Int) {
        postGenerationAction(gen, SessionAction.BecameDiscoverable)
        var groupUp = false
        var groupSource: SinkAttemptSource? = null
        var groupObservation: OwnerGroupSnapshot? = null
        var groupRun: SinkRunToken? = null
        var pendingSource: PendingSourceRegistration? = null
        var pairingBlocked = false
        var sessionRan = false
        var groupPlayed = false

        fun retireObservedGroup(source: SinkAttemptSource, runToken: SinkRunToken?, played: Boolean) {
            if (groupSource != source) return
            groupUp = false
            groupSource = null
            groupObservation = null
            groupRun = null
            pairingBlocked = false
            sessionRan = false
            groupPlayed = false
            postGroupDrop(source, runToken, played)
        }

        fun supersedeObservedGroup() {
            pendingSource?.let {
                it.request.supersede()
                mainHandler.removeCallbacks(it.callback)
            }
            pendingSource = null
            val oldSource = groupSource
            val binding = sourceBinding.get()
            if (binding?.source == oldSource) sourceBinding.compareAndSet(binding, null)
            if (oldSource != null) {
                mainHandler.post {
                    if (alive(oldSource.generation) && eventArbiter.isSource(oldSource) &&
                        sourceBinding.get()?.source != oldSource
                    ) eventArbiter.retireSource()
                }
            }
            groupUp = false
            groupSource = null
            groupObservation = null
            groupRun = null
            pairingBlocked = false
            sessionRan = false
            groupPlayed = false
        }

        fun submitRegistration(
            binding: SinkSourceBinding,
            expected: Pair<SinkAttemptSource?, SinkRunToken?>,
            observedStop: Long,
        ): PendingSourceRegistration {
            val registration = SinkSourceRegistration()
            val callback = Runnable {
                eventArbiter.registerSource(
                    binding.source, expected.first, expected.second, registration,
                    appContext != null && alive(gen) && stopSequence.get() == observedStop &&
                        sourceBinding.get() == binding && !endedByUser.get(),
                )
                if (registration.result() == true && eventArbiter.isSource(binding.source)) {
                    registeredBinding.set(binding)
                }
            }
            val pending = PendingSourceRegistration(
                binding.source, expected, observedStop, registration, callback,
                SystemClock.elapsedRealtime() + MAIN_CALLBACK_WAIT_MS,
            )
            if (!mainHandler.post(callback)) {
                registration.complete(false)
                Log.w(TAG, "Source registration could not be posted")
            }
            return pending
        }

        while (alive(gen)) {
            val observedStop = stopSequence.get()
            val observed = WfdOwnerBridge.groupSnapshotV2()
            if (!alive(gen)) break
            if (stopSequence.get() != observedStop) continue
            if (observed == null) {
                SystemClock.sleep(GROUP_POLL_MS)
                continue
            }
            if (!WfdOwnerBridge.isCurrentGroupOwner(observed)) continue
            val userEndResolved = endedByUser.observe(
                observed,
                submit = { snapshot ->
                    if (alive(gen) && stopSequence.get() == observedStop) {
                        WfdOwnerBridge.submitEndSession(snapshot)
                    } else {
                        OwnerEndSubmission.STALE
                    }
                },
                scheduleRetry = ::scheduleEndRetry,
            )
            automaticEnd.observe(
                observed, ::isCurrentAutomaticEnd, WfdOwnerBridge::submitEndSession, ::scheduleEndRetry,
            )
            val previous = groupObservation
            if (previous != null && compareSinkGroup(previous, observed) == SinkGroupRelation.SUPERSEDED) {
                supersedeObservedGroup()
            }
            val state = observed.state
            if (userEndResolved && state != P2pGroupState.Down) {
                mainHandler.post {
                    if (alive(gen) && stopSequence.get() == observedStop) endedByUser.clear()
                }
            }
            when (state) {
                is P2pGroupState.Up, P2pGroupState.Pending -> {
                    if (endedByUser.get()) {
                        SystemClock.sleep(GROUP_POLL_MS)
                        continue
                    }
                    if (!groupUp) {
                        groupUp = true
                        pairingBlocked = false
                        sessionRan = false
                        groupPlayed = false
                        groupRun = null
                        val seq = groupSequence.incrementAndGet()
                        val source = SinkAttemptSource(gen, seq)
                        val binding = SinkSourceBinding(source, observed)
                        val previousBinding = sourceBinding.get()
                        if (previousBinding != null && previousBinding.source.generation != gen) {
                            if (!alive(previousBinding.source.generation)) sourceBinding.compareAndSet(previousBinding, null)
                            groupUp = false
                            continue
                        }
                        if (!alive(gen) || !sourceBinding.compareAndSet(previousBinding, binding)) {
                            groupUp = false
                            continue
                        }
                        if (!alive(gen) || stopSequence.get() != observedStop || endedByUser.get()) {
                            sourceBinding.compareAndSet(binding, null)
                            groupUp = false
                            continue
                        }
                        val expected = eventArbiter.source to eventArbiter.current
                        groupSource = source
                        groupObservation = observed
                        pendingSource = submitRegistration(binding, expected, observedStop)
                    }
                    val pending = pendingSource
                    if (pending != null) {
                        var accepted = pending.request.result()
                        if (accepted == null && !pending.timeoutReported &&
                            SystemClock.elapsedRealtime() >= pending.deadline
                        ) {
                            pending.timeoutReported = true
                            if (pending.request.cancelBeforeCommit()) {
                                mainHandler.removeCallbacks(pending.callback)
                                pending.request.complete(false)
                            }
                            if (alive(gen) && stopSequence.get() == pending.stopSequence) {
                                Log.w(TAG, "Source registration timed out (gen=$gen seq=${pending.source.groupSequence})")
                            }
                            accepted = pending.request.result()
                        }
                        if (accepted == null) {
                            SystemClock.sleep(GROUP_POLL_MS)
                            continue
                        }
                        pendingSource = null
                        val source = pending.source
                        if (!accepted || !isCurrentSource(source)) {
                            val observedSource = eventArbiter.source
                            val observedRun = eventArbiter.current
                            val binding = sourceBinding.get()
                            if (!accepted && binding != null && binding.source == source && alive(gen) &&
                                stopSequence.get() == pending.stopSequence && !endedByUser.get() &&
                                observedSource != source &&
                                (observedSource == null ||
                                    (observedSource == pending.expected.first && observedRun == pending.expected.second))
                            ) {
                                pendingSource = submitRegistration(binding, observedSource to observedRun, observedStop)
                                SystemClock.sleep(GROUP_POLL_MS)
                            } else {
                                sessionRan = true
                            }
                            continue
                        }
                        postSourceLog(source, "P2P group up (gen=$gen seq=${source.groupSequence})")
                    }
                    if (state is P2pGroupState.Up && !sessionRan && !pairingBlocked && !endedByUser.get()) {
                        val source = groupSource
                        if (source == null) {
                            mainHandler.post {
                                if (alive(gen)) Log.e(TAG, "Missing sink attempt source for P2P group")
                            }
                            return
                        }
                        val observation = groupObservation ?: continue
                        var observedDown = false
                        var superseded = false
                        if (!awaitPairingCompletion(source, observation, { observedDown = true }, { superseded = true })) {
                            when {
                                observedDown -> retireObservedGroup(source, groupRun, played = false)
                                superseded -> supersedeObservedGroup()
                                else -> pairingBlocked = true
                            }
                            continue
                        }
                        if (!isCurrentSource(source)) continue
                        when (observeBoundGroup(source, observation)) {
                            SinkGroupRelation.DOWN -> {
                                retireObservedGroup(source, groupRun, played = false)
                                continue
                            }
                            SinkGroupRelation.SUPERSEDED -> {
                                supersedeObservedGroup()
                                continue
                            }
                            SinkGroupRelation.UNKNOWN -> continue
                            SinkGroupRelation.SAME -> Unit
                        }
                        val confirmed = observation.state as? P2pGroupState.Up ?: continue
                        val runToken = SinkRunToken(source, runSequence.incrementAndGet(), observation)
                        groupRun = runToken
                        beginConnectionRun(runToken, confirmed)
                        note(
                            runToken,
                            if (confirmed.phoneIsOwner) {
                                "P2P group up (phone is GO); dialing Windows RTSP"
                            } else {
                                "P2P group up (phone is client); dialing source"
                            },
                        )
                        // Windows listens on 7236 whether or not it is GO.
                        val dialResult = dial(
                            confirmed.sourceAddress, gen, runToken,
                            { observedDown = true }, { superseded = true },
                        )
                        when (dialResult) {
                            is SinkDialResult.Connected -> {
                                sessionRan = true
                                val result = serve(
                                    dialResult.socket, command, gen, runToken,
                                    { observedDown = true }, { superseded = true },
                                )
                                groupPlayed = result.reachedPlay
                                val finalRelation = if (observedDown) {
                                    SinkGroupRelation.DOWN
                                } else if (superseded) {
                                    SinkGroupRelation.SUPERSEDED
                                } else {
                                    awaitSinkGroupRelation(
                                        isCurrent = { isCurrentRun(runToken) },
                                        observe = { observeBoundGroup(source, observation) },
                                        wait = { SystemClock.sleep(GROUP_POLL_MS) },
                                    )
                                }
                                val confirmedSame = finalRelation == SinkGroupRelation.SAME
                                observedDown = finalRelation == SinkGroupRelation.DOWN
                                superseded = finalRelation == SinkGroupRelation.SUPERSEDED
                                postSinkTerminal(
                                    token = runToken,
                                    relation = finalRelation,
                                    failure = result.terminalFailure,
                                    reachedPlay = result.reachedPlay,
                                    originatingStopSequence = observedStop,
                                    enqueue = { callback -> mainHandler.post(callback) },
                                    fence = {
                                        SinkTerminalFence(
                                            activeGeneration = if (running.get()) generation.get() else null,
                                            stopSequence = stopSequence.get(),
                                            latestGroupSequence = groupSequence.get(),
                                            observedBinding = sourceBinding.get(),
                                            currentRun = eventArbiter.current,
                                            userEnded = endedByUser.get(),
                                            originalOwnerCurrent = WfdOwnerBridge.isCurrentGroupOwner(observation),
                                            provenDownBinding = provenDownBinding.get(),
                                        )
                                    },
                                    deliver = { token, failure -> eventArbiter.terminal(token, failure) },
                                )
                                finishConnectionRun(runToken, result.reachedPlay, result.selected, result.touchEnabled)
                                when {
                                    observedDown -> retireObservedGroup(source, runToken, result.reachedPlay)
                                    superseded -> supersedeObservedGroup()
                                    confirmedSame -> postEndSession(runToken, result.reachedPlay)
                                    else -> Unit
                                }
                            }
                            is SinkDialResult.NoConnection -> {
                                if (dialResult.timeout != null) {
                                    mainHandler.post {
                                        if (isCurrentRun(runToken)) eventArbiter.dialFinished(runToken, dialResult)
                                    }
                                }
                                finishConnectionRun(runToken, succeeded = false, selected = null, touchEnabled = null)
                                when {
                                    observedDown -> retireObservedGroup(source, runToken, played = false)
                                    superseded -> supersedeObservedGroup()
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
                P2pGroupState.Down -> {
                    pendingSource?.let {
                        it.request.abandon()
                        mainHandler.removeCallbacks(it.callback)
                    }
                    pendingSource = null
                    if (groupUp) {
                        groupUp = false
                        val source = groupSource
                        val runToken = groupRun ?: eventArbiter.current?.takeIf { it.source == source }
                        val binding = sourceBinding.get()
                        if (binding != null && binding.source == source) {
                            val relation = when {
                                binding.observation.ownerRevision == null -> SinkGroupRelation.UNKNOWN
                                !WfdOwnerBridge.isCurrentGroupOwner(binding.observation) -> SinkGroupRelation.SUPERSEDED
                                else -> compareSinkGroup(binding.observation, observed)
                            }
                            clearSinkSourceBinding(binding, relation, sourceBinding, provenDownBinding)
                        }
                        groupSource = null
                        groupObservation = null
                        groupRun = null
                        if (source != null && alive(gen)) {
                            postGroupDrop(source, runToken, played = groupPlayed)
                        }
                    }
                    if (userEndResolved) {
                        mainHandler.post {
                            if (alive(gen) && stopSequence.get() == observedStop) endedByUser.clear()
                        }
                    }
                }
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
        pendingSource?.let {
            it.request.abandon()
            mainHandler.removeCallbacks(it.callback)
        }
    }

    private fun observeBoundGroup(
        source: SinkAttemptSource,
        observation: OwnerGroupSnapshot,
        snapshot: () -> OwnerGroupSnapshot? = WfdOwnerBridge::groupSnapshotV2,
    ): SinkGroupRelation {
        val binding = sourceBinding.get() ?: return SinkGroupRelation.SUPERSEDED
        if (binding.source != source || binding.observation != observation) return SinkGroupRelation.SUPERSEDED
        val observed = snapshot()
        val relation = when {
            observation.ownerRevision == null -> SinkGroupRelation.UNKNOWN
            !WfdOwnerBridge.isCurrentGroupOwner(observation) -> SinkGroupRelation.SUPERSEDED
            else -> compareSinkGroup(observation, observed)
        }
        if (sourceBinding.get() != binding) return SinkGroupRelation.SUPERSEDED
        if (relation == SinkGroupRelation.DOWN || relation == SinkGroupRelation.SUPERSEDED) {
            clearSinkSourceBinding(binding, relation, sourceBinding, provenDownBinding)
        }
        return relation
    }

    private fun awaitPairingCompletion(
        source: SinkAttemptSource,
        observation: OwnerGroupSnapshot,
        onGroupDown: () -> Unit,
        onSuperseded: () -> Unit,
    ): Boolean {
        val initialState = WfdOwnerBridge.pairingState()
        if (!isCurrentSource(source)) return false
        if (initialState != "PAIRING") return true
        val deadline = SystemClock.elapsedRealtime() + PAIRING_WAIT_MS
        postSourceLog(source, "Waiting for WPS pairing before RTSP dial")
        while (isCurrentSource(source) && SystemClock.elapsedRealtime() < deadline) {
            when (observeBoundGroup(source, observation)) {
                SinkGroupRelation.DOWN -> {
                    onGroupDown()
                    return false
                }
                SinkGroupRelation.SUPERSEDED -> {
                    onSuperseded()
                    return false
                }
                SinkGroupRelation.UNKNOWN -> {
                    SystemClock.sleep(GROUP_POLL_MS)
                    continue
                }
                SinkGroupRelation.SAME -> Unit
            }
            if (!isCurrentSource(source)) return false
            val pairingState = WfdOwnerBridge.pairingState()
            if (!isCurrentSource(source)) return false
            when (pairingState) {
                "PAIRED" -> {
                    postSourceLog(source, "WPS pairing ready; starting RTSP dial")
                    return true
                }
                "UNPAIRED" -> {
                    postSourceLog(source, "WPS pairing did not complete; skipping RTSP dial", warning = true)
                    return false
                }
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
        postSourceLog(source, "Timed out waiting for WPS pairing; skipping RTSP dial", warning = true)
        return false
    }

    private fun dial(
        host: String,
        gen: Int,
        runToken: SinkRunToken,
        onGroupDown: () -> Unit,
        onSuperseded: () -> Unit,
    ): SinkDialResult {
        val observation = runToken.observation ?: return SinkDialResult.NoConnection()
        val deadline = SystemClock.elapsedRealtime() + DIAL_WINDOW_MS
        val window = SinkDialWindow()
        var attempt = 0
        fun observe(): SinkGroupRelation {
            val relation = observeBoundGroup(runToken.source, observation)
            when (relation) {
                SinkGroupRelation.DOWN -> onGroupDown()
                SinkGroupRelation.SUPERSEDED -> onSuperseded()
                else -> Unit
            }
            return relation
        }
        while (alive(gen) && isCurrentSource(runToken.source) && SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val socket = Socket()
            var connectStarted = false
            try {
                if (!P2pNetworkBinder.bind(appContext, socket)) {
                    window.otherFailure()
                    closeQuietly(socket)
                    if (!alive(gen)) return SinkDialResult.NoConnection()
                    val relation = observe()
                    if (relation == SinkGroupRelation.DOWN || relation == SinkGroupRelation.SUPERSEDED) {
                        return SinkDialResult.NoConnection()
                    }
                    if (attempt == 1 || attempt % 10 == 0) {
                        note(runToken, "Waiting for P2P interface before RTSP dial (attempt $attempt)")
                    }
                    SystemClock.sleep(GROUP_POLL_MS)
                    continue
                }
                val address = InetSocketAddress(host, PrimarySinkBeacon.RTSP_CONTROL_PORT)
                connectStarted = true
                try {
                    socket.connect(address, DIAL_TIMEOUT_MS)
                } catch (err: Exception) {
                    window.connectFailed(err)
                    throw err
                }
                if (!alive(gen) || !isCurrentSource(runToken.source)) {
                    closeQuietly(socket)
                    return SinkDialResult.NoConnection()
                }
                val relation = observe()
                if (relation == SinkGroupRelation.DOWN || relation == SinkGroupRelation.SUPERSEDED) {
                    closeQuietly(socket)
                    return SinkDialResult.NoConnection()
                }
                note(runToken, "RTSP connected to $host:${PrimarySinkBeacon.RTSP_CONTROL_PORT} (attempt $attempt)")
                return SinkDialResult.Connected(socket)
            } catch (err: Exception) {
                if (!connectStarted) window.otherFailure()
                closeQuietly(socket)
                if (attempt == 1 || attempt % 5 == 0) {
                    noteWarn(runToken, "RTSP dial $host attempt $attempt failed: ${err.message}")
                }
            }
            val relation = observe()
            if (relation == SinkGroupRelation.DOWN || relation == SinkGroupRelation.SUPERSEDED) {
                return SinkDialResult.NoConnection()
            }
            SystemClock.sleep(GROUP_POLL_MS)
        }
        val expired = SystemClock.elapsedRealtime() >= deadline
        val relation = if (expired && isCurrentSource(runToken.source)) observe() else SinkGroupRelation.UNKNOWN
        if (expired && alive(gen)) noteWarn(runToken, "RTSP dial window expired before connection")
        return window.finish(expired, isCurrentRun(runToken), relation == SinkGroupRelation.SAME)
    }

    private data class ServeResult(
        val reachedPlay: Boolean,
        val selected: VideoMode?,
        val touchEnabled: Boolean?,
        val terminalFailure: RtspFailure? = null,
    )

    private fun registerClient(runToken: SinkRunToken, socket: Socket): Boolean {
        val requestLock = Any()
        var cancelled = false
        var accepted = false
        val registered = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                var previous: Socket? = null
                synchronized(requestLock) {
                    if (!cancelled && isCurrentRun(runToken) && eventArbiter.run == runToken) {
                        previous = activeClient.getAndSet(socket)
                        accepted = true
                    }
                }
                val oldClient = previous
                if (oldClient != null && oldClient !== socket) {
                    closeQuietly(oldClient)
                    resetPlayback()
                }
            } finally {
                registered.countDown()
            }
        }
        val completed = posted && registered.await(MAIN_CALLBACK_WAIT_MS, TimeUnit.MILLISECONDS)
        return synchronized(requestLock) {
            if (!completed || !accepted || !isCurrentRun(runToken)) {
                cancelled = true
                activeClient.compareAndSet(socket, null)
                false
            } else {
                true
            }
        }
    }

    /** Runs one RTSP session and returns its run-local result. */
    private fun serve(
        socket: Socket,
        command: WfdAdvertiseCommand,
        gen: Int,
        runToken: SinkRunToken,
        onGroupDown: () -> Unit,
        onSuperseded: () -> Unit,
    ): ServeResult {
        if (!registerClient(runToken, socket)) {
            closeQuietly(socket)
            return ServeResult(false, null, null)
        }
        var reachedPlay = false
        var lastSelected: VideoMode? = null
        var lastLatencyMode = ""
        val offer = connectionOffer(command, runToken, onGroupDown, onSuperseded) ?: run {
            activeClient.compareAndSet(socket, null)
            closeQuietly(socket)
            return ServeResult(false, null, null)
        }
        val advertiseBitrate = if (offer.touchEnabled) {
            maxOf(offer.maxVideoBitrateBps, INTERACTIVE_BITRATE_FLOOR_BPS)
                .coerceAtMost(INTERACTIVE_BITRATE_CAP_BPS)
        } else {
            offer.maxVideoBitrateBps
        }
        if (offer.touchEnabled) {
            note(
                runToken,
                "interactive offer bitrate=${advertiseBitrate / 1_000}kbps " +
                    "microsoft_max_bitrate=$advertiseBitrate " +
                    "preferred=${offer.preferred?.format() ?: "none"} modes=${offer.modes.size}",
            )
        }
        val session = RtspSinkSession(
            WfdCapabilityTable(
                offer.modes,
                offer.preferred,
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
        var uibcEnableSent = false
        var lastUibcHidType = 0
        val groupWatch = SinkLiveGroupReader.watch(GROUP_POLL_MS)
        fun groupChanged(): Boolean {
            val sample = groupWatch.poll(SystemClock.elapsedRealtime()) ?: return false
            val relation = runToken.observation?.let { observation ->
                observeBoundGroup(runToken.source, observation) { sample.snapshot }
            } ?: SinkGroupRelation.UNKNOWN
            return when (relation) {
                SinkGroupRelation.DOWN -> {
                    onGroupDown()
                    true
                }
                SinkGroupRelation.SUPERSEDED -> {
                    onSuperseded()
                    true
                }
                SinkGroupRelation.SAME, SinkGroupRelation.UNKNOWN -> false
            }
        }
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val currentState = PlayState(session, output, runToken)
            mainHandler.post {
                if (isCurrentRun(runToken) && activeClient.get() === socket) {
                    playState.set(currentState)
                }
            }
            loop@ while (isCurrentRun(runToken) && !socket.isClosed) {
                if (groupChanged()) break@loop
                val n = try {
                    input.read(chunk)
                } catch (_: SocketTimeoutException) {
                    if (!sawData && !poked && SystemClock.elapsedRealtime() - connectedAt >= POKE_AFTER_MS) {
                        poked = true
                        output.write(OPTIONS_POKE.toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                        note(runToken, "RTSP poke OPTIONS")
                    }
                    if (!isCurrentRun(runToken)) break@loop
                    if (session.state == "ERROR" || session.checkExchangeTimeout()) {
                        note(runToken, "RTSP session ERROR")
                        break@loop
                    }
                    writeIdrIfNeeded(currentState)
                    if (!uibcEnableSent) {
                        val m15 = session.requestUibcEnable()
                        if (m15.isNotEmpty()) {
                            try {
                                output.write(m15.toByteArray(StandardCharsets.US_ASCII))
                                output.flush()
                                uibcEnableSent = true
                                note(runToken, "RTSP >> M15 wfd_uibc_setting: enable")
                            } catch (err: Exception) {
                                noteWarn(runToken, "M15 UIBC enable failed", err)
                            }
                        }
                    }
                    continue@loop
                }
                if (!isCurrentRun(runToken)) break@loop
                if (n < 0) {
                    note(runToken, "RTSP closed by source")
                    break
                }
                sawData = true
                buffer.append(chunk, n)
                while (true) {
                    if (!isCurrentRun(runToken) || groupChanged()) break@loop
                    val message = buffer.next() ?: break
                    noteExchange(runToken, incoming = true, message, offer.preferred)
                    for (reply in session.handle(message)) {
                        noteExchange(runToken, incoming = false, reply, offer.preferred)
                        output.write(reply.toByteArray(StandardCharsets.US_ASCII))
                    }
                    output.flush()
                    if (!isCurrentRun(runToken)) break@loop
                    val selected = session.selectedMode
                    if (selected != null && selected != lastSelected) {
                        lastSelected = selected
                        note(runToken, "source selected ${selected.width}x${selected.height}@${selected.refreshHz}")
                        postRunAction(runToken, SessionAction.SourceSelectedMode(selected))
                        if (offer.touchEnabled) {
                            postRunEffect(runToken) {
                                UibcTouchChannel.setPictureSize(selected.width, selected.height)
                            }
                        }
                    }
                    val latencyMode = session.latencyMode
                    if (latencyMode.isNotEmpty() && latencyMode != lastLatencyMode) {
                        lastLatencyMode = latencyMode
                        note(runToken, "source latency mode $latencyMode")
                    }
                    if (uibcNoted) {
                        val enabled = session.uibcEnabled
                        postRunEffect(runToken) { UibcTouchChannel.setEnabled(enabled) }
                        if (session.uibcHidType != lastUibcHidType) {
                            lastUibcHidType = session.uibcHidType
                            val hidType = lastUibcHidType
                            postRunEffect(runToken) { UibcTouchChannel.setHidType(hidType) }
                        }
                    }
                    val uibcDecision = "UIBC decision: uibcNoted=$uibcNoted " +
                        "uibcPort=${session.uibcPort} uibcEnabled=${session.uibcEnabled} " +
                        "touchEnabled=${offer.touchEnabled}"
                    postRunEffect(runToken) { Log.d("MiraxUibc", uibcDecision) }
                    if (!uibcNoted && session.uibcPort > 0 && offer.touchEnabled) {
                        uibcNoted = true
                        val host = (socket.remoteSocketAddress as? java.net.InetSocketAddress)
                            ?.address
                            ?.hostAddress
                        note(runToken, "UIBC port ${session.uibcPort} type ${session.uibcHidType}")
                        if (host != null) {
                            val port = session.uibcPort
                            val hidType = session.uibcHidType
                            val enabled = session.uibcEnabled
                            val selectedMode = session.selectedMode
                            postRunEffect(runToken) {
                                UibcTouchChannel.onStatus = { line -> note(runToken, line) }
                                UibcTouchChannel.open(host, port, hidType, enabled = enabled)
                                selectedMode?.let {
                                    UibcTouchChannel.setPictureSize(it.width, it.height)
                                }
                            }
                        }
                    }
                    if (session.state == "PLAYING" && !reachedPlay) {
                        reachedPlay = true
                        note(runToken, "playback started")
                        val mode = session.selectedMode ?: VideoMode(1920, 1080, 60)
                        val rtpPort = session.rtpPort()
                        val rtcpPort = session.rtcpPort()
                        val remoteRtcpPort = session.rtcpServerPort
                        postRunEffect(runToken) {
                            decoder.setFormat(mode.width, mode.height, mode.refreshHz)
                            if (isCurrentRun(runToken)) {
                                val rtcp = startRtcp(rtcpPort, remoteRtcpPort, runToken)
                                if (isCurrentRun(runToken)) {
                                    startRtp(rtpPort, demux, gen, runToken, rtcp)
                                    if (isCurrentRun(runToken)) startHardwareCursor(runToken)
                                }
                            }
                        }
                        postRunAction(runToken, SessionAction.EnteredPlay)
                    }
                    // Latency role switching disabled: interactive M3 advertises
                    // microsoft_latency_management_capability: none.
                    if (session.state == "TEARDOWN" || session.state == "ERROR") {
                        note(runToken, "RTSP session ${session.state}")
                        break@loop
                    }
                }
                writeIdrIfNeeded(currentState)
            }
        } catch (err: Exception) {
            if (alive(gen)) {
                noteWarn(runToken, "RTSP session ended", err)
            }
        } finally {
            mainHandler.post {
                if (activeClient.compareAndSet(socket, null)) resetPlayback()
            }
            closeQuietly(socket)
        }
        return ServeResult(reachedPlay, lastSelected, offer.touchEnabled, terminalFailureToForward(session, reachedPlay))
    }

    private fun writeIdrIfNeeded(state: PlayState) {
        synchronized(rtspIdrLock) {
            val runToken = state.runToken
            if (!state.idrNeeded.get() || playState.get() !== state || !isCurrentRun(runToken)) return
            val request = state.session.requestIdr()
            if (request.isEmpty()) return
            try {
                state.output.write(request.toByteArray(StandardCharsets.US_ASCII))
                state.output.flush()
                state.idrNeeded.set(false)
                synchronized(decoder) {
                    if (playState.get() === state && isCurrentRun(runToken)) decoder.markIdrRequested()
                }
                note(runToken, "RTSP >> IDR refresh")
            } catch (err: Exception) {
                noteWarn(runToken, "IDR refresh failed", err)
            }
        }
    }

    private fun startRtcp(localPort: Int, remotePort: Int, runToken: SinkRunToken): RtcpChannel? {
        if (remotePort <= 0) {
            note(runToken, "RTCP not negotiated (no server_port pair)")
            return null
        }
        val previousRtcp = rtcpChannel
        val channel = RtcpChannel()
        channel.start(localPort, remotePort, appContext)
        if (!isCurrentRun(runToken) || eventArbiter.run != runToken || rtcpChannel !== previousRtcp) {
            channel.stop()
            return null
        }
        stopRtcp()
        rtcpChannel = channel
        note(runToken, "RTCP RR → source :$remotePort (local $localPort)")
        return channel
    }

    private fun stopRtcp() {
        rtcpChannel?.stop()
        rtcpChannel = null
    }

    private fun startRtp(
        port: Int,
        demux: MpegTsDepacketizer,
        gen: Int,
        runToken: SinkRunToken,
        rtcp: RtcpChannel?,
    ) {
        val previousSocket = rtpSocket
        var socket: DatagramSocket? = null
        try {
            val opened = DatagramSocket(null)
            socket = opened
            opened.reuseAddress = true
            // Win11 bursts need enough receive buffer to avoid loss-driven ABR stalls.
            opened.receiveBufferSize = 1024 * 1024
            opened.bind(InetSocketAddress(port))
            P2pNetworkBinder.bind(appContext, opened)
            if (isCurrentRun(runToken) && eventArbiter.run == runToken && rtpSocket === previousSocket) {
                stopRtp()
                rtpSocket = opened
                audioPlayer.start()
                note(runToken, "RTP listening on $port")
                startRtpReader(opened, demux, gen, runToken, rtcp)
            } else {
                closeQuietly(opened)
            }
        } catch (err: Exception) {
            closeQuietly(socket)
            if (rtpSocket === previousSocket) stopRtp()
            noteWarn(runToken, "RTP bind failed on $port", err)
        }
    }

    private fun startRtpReader(
        socket: DatagramSocket,
        demux: MpegTsDepacketizer,
        gen: Int,
        runToken: SinkRunToken,
        rtcp: RtcpChannel?,
    ) {
        Thread({
            val buf = ByteArray(64 * 1024)
            var packets = 0L
            var lastRtpElapsed = 0L
            var lastRtpGapLogMs = 0L
            var auSinceGap = 0
            while (alive(gen) && isCurrentRun(runToken) && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (!isCurrentRun(runToken)) break
                    val now = SystemClock.elapsedRealtime()
                    if (++packets == 1L) {
                        note(runToken, "first RTP packet from ${packet.address}")
                    } else {
                        val gap = now - lastRtpElapsed
                        if (gap >= 80L) {
                            val up = SystemClock.uptimeMillis()
                            if (up - lastRtpGapLogMs >= 200L) {
                                lastRtpGapLogMs = up
                                val detail = "RTP gap_ms=$gap au_since=$auSinceGap from=${packet.address}"
                                postRunEffect(runToken) { Log.w(TAG, detail) }
                                auSinceGap = 0
                            }
                        }
                    }
                    lastRtpElapsed = now
                    rtcp?.onRtp(packet.data, packet.length, packet.address)
                    demux.pushRtp(packet.data, packet.length)
                    while (true) {
                        val au = demux.poll() ?: break
                        auSinceGap++
                        synchronized(decoder) {
                            if (isCurrentRun(runToken)) decoder.submitAccessUnit(au)
                        }
                    }
                    while (true) {
                        val pcm = demux.pollAudio() ?: break
                        if (isCurrentRun(runToken) && rtpSocket === socket) audioPlayer.submit(pcm)
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
    }

    private fun stopRtp() {
        closeQuietly(rtpSocket)
        rtpSocket = null
        audioPlayer.stop()
    }

    private fun startHardwareCursor(runToken: SinkRunToken) {
        if (!WfdCapabilityTable.ADVERTISE_HARDWARE_CURSOR) {
            note(runToken, "hardware cursor off (local echo)")
            return
        }
        MicrosoftCursorChannel.onStatus = { line -> note(runToken, line) }
        MicrosoftCursorChannel.open(appContext)
        note(runToken, "hardware cursor advertised on ${MicrosoftCursorChannel.UDP_PORT}")
    }

    private fun stopHardwareCursor() {
        MicrosoftCursorChannel.close()
        MicrosoftCursorChannel.onStatus = null
    }

    private fun resetPlayback() {
        playState.set(null)
        UibcTouchChannel.close()
        UibcTouchChannel.onStatus = null
        stopHardwareCursor()
        stopRtp()
        stopRtcp()
        decoder.reset()
    }

    private fun stop(reason: String): Int {
        Log.i(TAG, "stop ($reason)")
        stopSequence.incrementAndGet()
        automaticEnd.clear()
        val stoppedGeneration = generation.incrementAndGet()
        running.set(false)
        sourceBinding.set(null)
        registeredBinding.set(null)
        eventArbiter.stop()
        closeQuietly(activeClient.getAndSet(null))
        resetPlayback()
        SessionHost.onSinkStopped()
        if (generation.get() == stoppedGeneration && !running.get()) {
            PictureActivity.finishIfShowing()
        }
        return stoppedGeneration
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

    private fun scheduleEndRetry(retry: () -> Unit): Boolean {
        val posted = mainHandler.postDelayed({ retry() }, GROUP_POLL_MS / 2)
        if (!posted) Log.w(TAG, "Guarded end retry could not be posted")
        return posted
    }

    private fun isCurrentAutomaticEnd(token: SinkRunToken, stoppedAt: Long): Boolean =
        stopSequence.get() == stoppedAt && isCurrentRun(token) &&
            token.observation?.let(WfdOwnerBridge::isCurrentGroupOwner) == true

    private fun postEndSession(runToken: SinkRunToken, played: Boolean) {
        val stoppedAt = stopSequence.get()
        mainHandler.post {
            dispatchSinkEnd(
                original = runToken.observation,
                isCurrent = { isCurrentAutomaticEnd(runToken, stoppedAt) },
                submit = {
                    automaticEnd.submit(runToken, stoppedAt, ::isCurrentAutomaticEnd, WfdOwnerBridge::submitEndSession)
                },
                finishLocalStream = {
                    PictureActivity.finishIfShowing()
                    if (played) eventArbiter.action(runToken, SessionAction.ConnectionEnded)
                },
            )
        }
    }

    private fun isCurrentSource(source: SinkAttemptSource): Boolean =
        alive(source.generation) && sourceBinding.get()?.source == source &&
            eventArbiter.isSource(source) && !endedByUser.get()

    private fun postGenerationAction(gen: Int, action: SessionAction) {
        val ctx = appContext ?: return
        mainHandler.post {
            if (alive(gen)) SessionHost.dispatchConnectionEvent(ctx, action)
        }
    }

    private fun postSourceLog(source: SinkAttemptSource, line: String, warning: Boolean = false) {
        mainHandler.post {
            if (isCurrentSource(source)) {
                if (warning) Log.w(TAG, line) else Log.i(TAG, line)
            }
        }
    }

    private fun isCurrentRun(runToken: SinkRunToken): Boolean =
        isCurrentSource(runToken.source) && eventArbiter.isRun(runToken)

    private fun postRunAction(runToken: SinkRunToken, action: SessionAction) {
        mainHandler.post {
            if (isCurrentRun(runToken)) eventArbiter.action(runToken, action)
        }
    }

    private fun postRunEffect(runToken: SinkRunToken, effect: () -> Unit) {
        mainHandler.post {
            if (isCurrentRun(runToken) && eventArbiter.run == runToken) effect()
        }
    }

    private fun postGroupDrop(source: SinkAttemptSource, runToken: SinkRunToken?, played: Boolean) {
        mainHandler.post {
            if (alive(source.generation) && !endedByUser.get() && eventArbiter.isSource(source)) {
                eventArbiter.drop(source, runToken, played)
            }
        }
    }

    private data class ConnectionOffer(
        val modes: Set<VideoMode>,
        val preferred: VideoMode?,
        val broadcastName: String,
        val touchEnabled: Boolean,
        val maxVideoBitrateBps: Long,
    )

    /** Reads wm size and freezes the current run's M3 offer before RTSP. */
    private fun connectionOffer(
        fallback: WfdAdvertiseCommand,
        runToken: SinkRunToken,
        onGroupDown: () -> Unit,
        onSuperseded: () -> Unit,
    ): ConnectionOffer? {
        val initialDeadline = SystemClock.elapsedRealtime() + MAIN_CALLBACK_WAIT_MS
        val observation = runToken.observation ?: return null
        val observedRevision = observation.ownerRevision ?: return null
        val revision = WfdOwnerBridge.ownerRevision(observation.owner) ?: return null
        if (observedRevision != revision) {
            onSuperseded()
            return null
        }
        val scope = SinkWmReadScope(observation.owner, provisioningFence(), revision)
        fun currentWmScope(): Boolean = isCurrentSinkWmScope(
            scope, observation.owner, provisioningFence(), WfdOwnerBridge.ownerRevision(observation.owner),
        )
        val completedRead = SinkWmSizeReader.requestBeforeDeadline(
            scope, initialDeadline, SystemClock::elapsedRealtime,
        ) { isCurrentRun(runToken) && currentWmScope() } ?: return null
        val deadline = minOf(initialDeadline, completedRead.deadlineMs)
        if (!completedRead.await(deadline - SystemClock.elapsedRealtime()) ||
            SystemClock.elapsedRealtime() >= deadline || !isCurrentRun(runToken) || !currentWmScope()
        ) {
            noteWarn(runToken, "Offer WM read timed out or was superseded")
            return null
        }
        var relation: SinkGroupRelation
        do {
            relation = observeBoundGroup(runToken.source, observation) {
                SinkOfferGroupReader.snapshot(deadline - SystemClock.elapsedRealtime())
            }
            when (relation) {
                SinkGroupRelation.DOWN -> {
                    onGroupDown()
                    return null
                }
                SinkGroupRelation.SUPERSEDED -> {
                    onSuperseded()
                    return null
                }
                SinkGroupRelation.UNKNOWN -> {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0 || !isCurrentRun(runToken)) return null
                    SystemClock.sleep(minOf(GROUP_POLL_MS, remaining))
                }
                SinkGroupRelation.SAME -> Unit
            }
        } while (relation == SinkGroupRelation.UNKNOWN)
        if (SystemClock.elapsedRealtime() >= deadline || !isCurrentRun(runToken)) return null
        val reading = completedRead.result()
        val latch = CountDownLatch(1)
        val request = SinkOfferRequest<ConnectionOffer>()
        val ctx = appContext
        if (!mainHandler.post {
            if (!request.start()) {
                latch.countDown()
                return@post
            }
            try {
                if (ctx != null && request.isActive() &&
                    isCurrentRun(runToken) && eventArbiter.run == runToken
                ) {
                    val snap = SessionHost.freezeConnectionOffer(
                        ctx,
                        reading,
                        canCommit = {
                            // Re-observe at claim time; worker-loop SAME can go stale before main commit.
                            beginSinkOfferCommit(
                                request,
                                observeBoundGroup(runToken.source, observation),
                                isCurrent = {
                                    SystemClock.elapsedRealtime() < deadline && isCurrentRun(runToken) &&
                                        currentWmScope() && eventArbiter.run == runToken
                                },
                                isOwnerCurrent = { WfdOwnerBridge.isCurrentGroupOwner(observation) },
                            )
                        },
                        isCurrent = {
                            isCurrentRun(runToken) && currentWmScope() && eventArbiter.run == runToken &&
                                WfdOwnerBridge.isCurrentGroupOwner(observation)
                        },
                    ) ?: return@post
                    if (!request.isActive() || !isCurrentRun(runToken) ||
                        !eventArbiter.offerFrozen(runToken, snap.touchEnabled)
                    ) return@post
                    request.complete(
                        ConnectionOffer(
                            modes = snap.connectionOfferModes ?: fallback.modes,
                            preferred = snap.connectionPreferredMode,
                            broadcastName = snap.effectiveBroadcastName.ifEmpty { fallback.broadcastName },
                            touchEnabled = snap.touchEnabled,
                            maxVideoBitrateBps = snap.maxVideoBitrateBps,
                        ),
                    )
                    if (isCurrentRun(runToken)) SessionHost.refreshSinkDiagnostics()
                }
            } finally {
                request.fail()
                latch.countDown()
            }
        }) return null
        val decision = awaitSinkOffer(
            request = request,
            remainingMs = deadline - SystemClock.elapsedRealtime(),
            pollMs = GROUP_POLL_MS,
            waitMs = { waitMs -> latch.await(waitMs, TimeUnit.MILLISECONDS) },
            isCurrent = { isCurrentRun(runToken) },
            onPendingPoll = {
                val relation = observeBoundGroup(runToken.source, runToken.observation)
                when (relation) {
                    SinkGroupRelation.DOWN -> onGroupDown()
                    SinkGroupRelation.SUPERSEDED -> onSuperseded()
                    else -> Unit
                }
            },
        )
        return when (decision) {
            is SinkOfferDecision.Completed -> decision.offer
            else -> {
                noteWarn(runToken, "Offer freeze failed or was cancelled before commit")
                null
            }
        }
    }

    private fun beginConnectionRun(runToken: SinkRunToken, confirmed: P2pGroupState.Up) {
        mainHandler.post {
            if (isCurrentSource(runToken.source)) {
                eventArbiter.begin(runToken, confirmed.sourceAddress, confirmed.sourceDeviceName)
            }
        }
    }

    private fun finishConnectionRun(
        runToken: SinkRunToken,
        succeeded: Boolean,
        selected: VideoMode?,
        touchEnabled: Boolean?,
    ) {
        mainHandler.post { eventArbiter.finish(runToken, succeeded, selected, touchEnabled) }
    }

    private fun note(runToken: SinkRunToken, line: String) {
        mainHandler.post {
            if (isCurrentRun(runToken) && eventArbiter.log(runToken, "I/MiraxSink: $line")) {
                Log.i(TAG, line)
                if (isCurrentRun(runToken)) SessionHost.refreshSinkDiagnostics()
            }
        }
    }

    private fun noteWarn(runToken: SinkRunToken, line: String, err: Exception? = null) {
        val detail = err?.message?.let { "$line: $it" } ?: line
        mainHandler.post {
            if (isCurrentRun(runToken) && eventArbiter.log(runToken, "W/MiraxSink: $detail")) {
                if (err != null) Log.w(TAG, line, err) else Log.w(TAG, line)
                if (isCurrentRun(runToken)) SessionHost.refreshSinkDiagnostics()
            }
        }
    }

    private fun noteExchange(
        runToken: SinkRunToken,
        incoming: Boolean,
        message: String,
        preferred: VideoMode?,
    ) {
        val start = message.lineSequence().firstOrNull().orEmpty().trim()
        val method = start.substringBefore(' ')
        when {
            incoming && method == "OPTIONS" -> note(runToken, "M1 OPTIONS from source")
            !incoming && method == "OPTIONS" -> note(runToken, "M2 OPTIONS to source")
            incoming && method == "GET_PARAMETER" -> {
                val asked = message.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("wfd_") || it.startsWith("microsoft_") }
                    .take(8)
                    .joinToString(" ")
                note(runToken, "M3 GET_PARAMETER $asked".trim())
            }
            !incoming && start.startsWith("RTSP/1.0") && message.contains("wfd_video_formats") -> {
                val label = preferred?.let { "${it.width}x${it.height}@${it.refreshHz}" } ?: "none"
                note(runToken, "M3 reply preferred $label")
            }
            !incoming && message.contains("microsoft_max_bitrate") -> {
                val match = MICROSOFT_MAX_BITRATE_LINE.find(message)
                if (match != null) {
                    note(runToken, "M3 advertise microsoft_max_bitrate=${match.groupValues[1]}")
                }
            }
            incoming && method == "SET_PARAMETER" -> note(runToken, "M4 SET_PARAMETER")
        }
        val arrow = if (incoming) "RTSP << " else "RTSP >> "
        note(runToken, arrow + oneLine(message))
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
