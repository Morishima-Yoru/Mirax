package me.trinitrix.mirax.wfd



import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque



/**
 * Decodes H.264 access units onto an attached [Surface].
 * Picture placement is owned by the picture activity via [me.trinitrix.mirax.session.PicturePlacement].
 *
 * Output is drained on a dedicated thread so sparse RTP (static desktop) cannot
 * leave decoded frames stuck undequeued for seconds.
 */
class H264SurfaceDecoder {
    private val pending = ArrayDeque<ByteArray>()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var videoW: Int = 0
    private var videoH: Int = 0
    private var videoFps: Int = 60
    private var awaitingKeyframe: Boolean = true
    private var frames: Long = 0
    private var submitted: Long = 0
    private var lastKeyframeRequestMs: Long = 0
    private var lastStatsMs: Long = 0
    private var lastTouchLagLogMs: Long = 0
    private var lastSubmitLagLogMs: Long = 0
    private var drainThread: Thread? = null
    private val submitTimesMs = ArrayDeque<Long>()



    @Volatile
    private var drainAlive: Boolean = false



    /**
     * ElapsedRealtime ms of the latest UIBC touch write. Decoder logs
     * touch-to-frame lag once after the next output release.
     */
    @Volatile
    var lastTouchElapsedRealtimeMs: Long = 0L



    /** ElapsedRealtime ms of the latest released output frame, or 0. */
    @Volatile
    var lastFrameElapsedRealtimeMs: Long = 0L
        private set



    /** ElapsedRealtime ms when the sink last sent wfd_idr_request, or 0. */
    @Volatile
    private var lastIdrRequestElapsedMs: Long = 0L



    // Public getters for debug overlay
    val decoderName: String get() = codec?.name ?: "none"
    val inputWidth: Int get() = videoW
    val inputHeight: Int get() = videoH
    val inputFps: Int get() = videoFps
    val framesDecoded: Long get() = frames
    val pendingFrames: Int get() = pending.size
    val isAwaitingKeyframe: Boolean get() = awaitingKeyframe



    @Volatile
    var onFormat: ((width: Int, height: Int, fps: Int) -> Unit)? = null



    /** Record that an RTSP IDR request was flushed toward the source. */
    fun markIdrRequested() {
        // Keep the earliest outstanding request so RTT is not reset by retries.
        if (lastIdrRequestElapsedMs == 0L) {
            lastIdrRequestElapsedMs = SystemClock.elapsedRealtime()
        }
    }



    /**
     * Fired when a new decoder cannot start until the source sends an IDR.
     * The callback must not re-enter this decoder.
     */
    @Volatile
    var onNeedKeyframe: (() -> Unit)? = null



    // Debug stats for overlay (rolling window)
    private var currentFps: Int = 0
    private var currentBitrateKbps: Long = 0
    @Volatile
    private var lastSubmitToFrameMs: Long = 0
    private var rateWindowBytes: Long = 0
    private var rateWindowFrames: Long = 0
    private var rateWindowStartMs: Long = 0

    data class DebugStats(
        val decoderName: String,
        val inputWidth: Int,
        val inputHeight: Int,
        val inputFps: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val framesDecoded: Long,
        val framesDropped: Long,
        val pendingFrames: Int,
        val awaitingKeyframe: Boolean,
        val currentBitrateKbps: Long,
        val currentFps: Int,
        /** Decoder queue latency: input submit → output release, ms. */
        val submitToFrameMs: Long,
    )

    fun getDebugStats(outputWidth: Int = 0, outputHeight: Int = 0): DebugStats {
        synchronized(this) {
            refreshRateWindowLocked()
            return DebugStats(
                decoderName = decoderName,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inputFps = inputFps,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                framesDecoded = framesDecoded,
                framesDropped = 0,
                pendingFrames = pendingFrames,
                awaitingKeyframe = isAwaitingKeyframe,
                currentBitrateKbps = currentBitrateKbps,
                currentFps = currentFps,
                submitToFrameMs = lastSubmitToFrameMs,
            )
        }
    }

    fun updateDebugStats() {
        synchronized(this) {
            refreshRateWindowLocked()
        }
    }

    /** Caller must hold the decoder monitor. */
    private fun refreshRateWindowLocked() {
        val now = SystemClock.elapsedRealtime()
        if (rateWindowStartMs == 0L) {
            rateWindowStartMs = now
            return
        }
        val elapsed = now - rateWindowStartMs
        if (elapsed < 400L) {
            return
        }
        val bytes = rateWindowBytes
        val decoded = rateWindowFrames
        rateWindowBytes = 0
        rateWindowFrames = 0
        rateWindowStartMs = now
        // Sparse desktop can pause briefly; keep last rate instead of flashing 0.
        if (bytes > 0L || decoded > 0L) {
            currentBitrateKbps = (bytes * 8L) / elapsed
            currentFps = ((decoded * 1000L) / elapsed).toInt()
        }
    }



    fun attachSurface(surface: Surface?) {
        synchronized(this) {
            val previous = this.surface
            val ready = surface != null && surface.isValid
            if (!ready) {
                this.surface = null
                stopCodec()
                if (videoW > 0) {
                    awaitingKeyframe = true
                    requestKeyframeSoon()
                }
                return
            }
            val next = surface ?: return
            this.surface = next
            if (next === previous && codec != null) {
                drainPending()
                return
            }
            if (codec != null) {
                try {
                    codec?.setOutputSurface(next)
                    awaitingKeyframe = false
                    Log.i(TAG, "output surface replaced")
                    return
                } catch (err: Exception) {
                    Log.e(TAG, "setOutputSurface failed", err)
                    stopCodec()
                }
            }
            if (videoW > 0) {
                awaitingKeyframe = true
                requestKeyframeSoon()
            }
            drainPending()
        }
    }



    fun setFormat(width: Int, height: Int, fps: Int) {
        synchronized(this) {
            val changed = videoW != 0 && (width != videoW || height != videoH)
            videoW = width
            videoH = height
            videoFps = if (fps > 0) fps else 60
            onFormat?.invoke(videoW, videoH, videoFps)
            if (changed) {
                stopCodec()
                awaitingKeyframe = true
                requestKeyframeSoon()
            }
            drainPending()
        }
    }



    fun submitAccessUnit(au: ByteArray) {
        synchronized(this) {
            val isIdr = hasNal(au, 5)
            if (awaitingKeyframe && !isIdr) {
                // Need an IDR before any later P/B frame is decodable.
                requestKeyframeSoon()
                maybeLogStats("await-idr")
                return
            }
            if (isIdr) {
                // IDR is a clean catch-up point: drop backlog without a freeze-wait.
                pending.clear()
                submitTimesMs.clear()
                awaitingKeyframe = false
                val idrAt = lastIdrRequestElapsedMs
                if (idrAt > 0L) {
                    val rtt = SystemClock.elapsedRealtime() - idrAt
                    lastIdrRequestElapsedMs = 0L
                    Log.i(TAG, "keyframe queued ${au.size} idr_to_keyframe_ms=$rtt")
                } else {
                    Log.i(TAG, "keyframe queued ${au.size}")
                }
                pending.addLast(au)
                drainPending()
                return
            }
            // Soft backlog: ask Windows for an IDR soon, but keep decoding.
            // Hard backlog: freeze for resync. Do NOT treat sparse Miracast
            // (static desktop often <5 fps) as an output stall - that path was
            // flooding wfd_idr_request every ~1s and inflating encode lag.
            if (pending.size >= HARD_PENDING_FRAMES) {
                pending.clear()
                submitTimesMs.clear()
                awaitingKeyframe = true
                Log.w(TAG, "hard backlog; wait for IDR")
                requestKeyframeSoon(force = true)
                drainPending()
                return
            }
            // Soft backlog alone must not request IDR: at 60 fps a brief spike
            // to SOFT_PENDING_FRAMES is normal and was flooding Windows every
            // KEYFRAME_REQUEST_MIN_MS. Soft-stall covers true output freezes.
            pending.addLast(au)
            drainPending()
            maybeLogStats("submit")
        }
    }



    fun reset() {
        synchronized(this) {
            pending.clear()
            stopCodec()
            videoW = 0
            videoH = 0
            awaitingKeyframe = true
            frames = 0
            submitted = 0
            submitTimesMs.clear()
        }
    }



    private fun drainPending() {
        val surfaceReady = surface != null && surface!!.isValid
        if (videoW <= 0 || videoH <= 0) {
            return
        }
        if (codec == null && !surfaceReady) {
            return
        }
        ensureCodec()
        val active = codec ?: return
        while (true) {
            val au = pending.pollFirst() ?: break
            if (awaitingKeyframe && !hasNal(au, 5) && !hasNal(au, 7) && !hasNal(au, 8)) {
                continue
            }
            if (hasNal(au, 5)) {
                awaitingKeyframe = false
            }
            if (!queue(active, au)) {
                // Input full: put the AU back and stop; do not skip mid-GOP.
                pending.addFirst(au)
                break
            }
            releaseOutput(active)
        }
        releaseOutput(active)
    }



    private fun ensureCodec() {
        val output = surface
        if (codec != null || output == null) {
            return
        }
        var phase = H264LowLatencyConfigurePolicy.Phase.WITH_LOW_LATENCY
        while (true) {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    videoW,
                    videoH,
                )
                // Cap near the MTK default; 6_000_000 is rejected and logged as unused.
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_000_000)
                if (H264LowLatencyConfigurePolicy.usesAndroidLowLatency(phase)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (H264LowLatencyConfigurePolicy.usesVendorLowLatency(phase)) {
                    format.setInteger(H264LowLatencyConfigurePolicy.VENDOR_LOW_LATENCY_KEY, 1)
                }
                // Realtime priority (Moonlight). OPERATING_RATE is unsupported on this MTK.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
                created = try {
                    MediaCodec.createByCodecName(PREFERRED_MTK_AVC)
                } catch (_: Exception) {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }
                created.configure(format, output, null, 0)
                created.start()
                codec = created
                frames = 0
                submitted = 0
                startDrainThread()
                Log.i(
                    TAG,
                    "decoder started ${created.name} ${videoW}x${videoH}@$videoFps " +
                        "lowLatency=${H264LowLatencyConfigurePolicy.lowLatencyLabel(phase)} " +
                        "priority=realtime",
                )
                return
            } catch (err: Exception) {
                try {
                    created?.stop()
                } catch (_: Exception) {
                }
                try {
                    created?.release()
                } catch (_: Exception) {
                }
                val codecErr = err as? MediaCodec.CodecException
                val next = H264LowLatencyConfigurePolicy.nextPhaseAfterFailure(
                    phase = phase,
                    isCodecException = codecErr != null,
                    errorCode = codecErr?.errorCode,
                )
                val reason = H264LowLatencyConfigurePolicy.retryReason(phase)
                if (next != null && reason != null) {
                    Log.w(TAG, reason, err)
                    phase = next
                    continue
                }
                Log.e(TAG, "decoder configure failed", err)
                stopCodec()
                return
            }
        }
    }



    /**
     * @return false when the codec has no free input buffer; caller must keep [au].
     */
    private fun queue(codec: MediaCodec, au: ByteArray): Boolean {
        try {
            var index = codec.dequeueInputBuffer(0)
            if (index < 0) {
                releaseOutput(codec)
                // Brief wait only; a long block stalls RTP and grows socket latency.
                index = codec.dequeueInputBuffer(2_000)
            }
            if (index < 0) {
                return false
            }
            val buffer: ByteBuffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            buffer.put(au)
            val flags = if (isKeyframe(au)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            // Tight PTS spacing (1 ms/frame). Wall-clock PTS and PTS=0 both
            // correlated with MTK WAIT-timeout stalls on this device.
            val ptsUs = frames * 1_000L
            codec.queueInputBuffer(index, 0, au.size, ptsUs, flags)
            submitted++
            rateWindowBytes += au.size.toLong()
            submitTimesMs.addLast(SystemClock.elapsedRealtime())
            while (submitTimesMs.size > 120) {
                submitTimesMs.removeFirst()
            }
            return true
        } catch (err: Exception) {
            Log.e(TAG, "queue failed", err)
            stopCodec()
            awaitingKeyframe = true
            requestKeyframeSoon()
            return false
        }
    }



    private fun releaseOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (err: Exception) {
                Log.e(TAG, "dequeue output failed", err)
                stopCodec()
                awaitingKeyframe = true
                requestKeyframeSoon()
                return
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            }
            if (index < 0) {
                return
            }
            val show = surface != null && surface!!.isValid
            codec.releaseOutputBuffer(index, show)
            frames++
            rateWindowFrames++
            val nowElapsed = SystemClock.elapsedRealtime()
            lastFrameElapsedRealtimeMs = nowElapsed
            maybeLogSubmitToFrameLag()
            maybeLogTouchToFrameLag()
        }
    }



    private fun maybeLogSubmitToFrameLag() {
        val submittedAt = submitTimesMs.pollFirst() ?: return
        val lagMs = SystemClock.elapsedRealtime() - submittedAt
        lastSubmitToFrameMs = lagMs
        val now = SystemClock.uptimeMillis()
        if (now - lastSubmitLagLogMs < SUBMIT_LAG_LOG_INTERVAL_MS) {
            return
        }
        lastSubmitLagLogMs = now
        Log.i(TAG, "submit_to_frame_ms=$lagMs")
    }



    private fun maybeLogTouchToFrameLag() {
        val touchAt = lastTouchElapsedRealtimeMs
        if (touchAt <= 0L) {
            return
        }
        val lagMs = SystemClock.elapsedRealtime() - touchAt
        // Stale stamps from gaps between gestures (adb / finger lift) are not
        // interaction latency. Keep the stamp so a fresher touch can overwrite.
        if (lagMs < 0L || lagMs > 250L) {
            if (lagMs > 1_000L) {
                lastTouchElapsedRealtimeMs = 0L
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchLagLogMs < TOUCH_LAG_LOG_INTERVAL_MS) {
            return
        }
        lastTouchLagLogMs = now
        lastTouchElapsedRealtimeMs = 0L
        Log.i(TAG, "touch_to_frame_ms=$lagMs")
    }



    private fun startDrainThread() {
        if (drainAlive) {
            return
        }
        drainAlive = true
        drainThread = Thread({
            while (drainAlive) {
                synchronized(this@H264SurfaceDecoder) {
                    val active = codec
                    if (active != null) {
                        releaseOutput(active)
                        // Sparse Windows encode can leave AUs parked in pending while
                        // input buffers are full; keep trying to feed after each drain.
                        drainPending()
                        maybeNudgeStalledPipeline()
                        maybeLogStats("drain")
                    }
                }
                try {
                    Thread.sleep(DRAIN_SLEEP_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "mirax-h264-out").also {
            it.isDaemon = true
            it.start()
        }
    }



    private fun stopDrainThread() {
        // Never join here: callers hold the decoder monitor and the drain thread
        // needs that same monitor to finish its current releaseOutput pass.
        drainAlive = false
        drainThread?.interrupt()
        drainThread = null
    }



    private fun maybeLogStats(where: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastStatsMs < STATS_INTERVAL_MS) {
            return
        }
        lastStatsMs = now
        Log.i(
            TAG,
            "stats where=$where out=$frames in=$submitted pending=${pending.size} " +
                "awaitIdr=$awaitingKeyframe",
        )
    }



    /**
     * Soft stall nudge: if outputs freeze while work is queued, ask for an IDR
     * without dropping the GOP. Distinct from the old hard output-stall freeze
     * that flooded Windows with keyframe requests on static desktops.
     */
    private fun maybeNudgeStalledPipeline() {
        if (videoW <= 0 || awaitingKeyframe) {
            return
        }
        val lastFrame = lastFrameElapsedRealtimeMs
        if (lastFrame <= 0L) {
            return
        }
        val idleMs = SystemClock.elapsedRealtime() - lastFrame
        if (idleMs < OUTPUT_SOFT_STALL_MS) {
            return
        }
        // One in-flight AU is normal; only nudge when the pipeline is actually
        // backed up (pending queue or several unreleased outputs).
        val inFlight = submitted - frames
        val workQueued = pending.isNotEmpty() || inFlight >= 3
        if (!workQueued) {
            return
        }
        if (idleMs >= OUTPUT_HARD_STALL_MS) {
            // MTK can park several AUs forever while Windows also goes quiet;
            // drop the wedged GOP and wait for a fresh IDR.
            pending.clear()
            submitTimesMs.clear()
            awaitingKeyframe = true
            Log.w(
                TAG,
                "hard stall ${idleMs}ms pending=${pending.size} inFlight=$inFlight; wait for IDR",
            )
            requestKeyframeSoon()
            return
        }
        Log.w(
            TAG,
            "soft stall ${idleMs}ms pending=${pending.size} inFlight=$inFlight " +
                "in=$submitted out=$frames",
        )
        requestKeyframeSoon()
    }



    /** Ask the source for an IDR, rate-limited so encode spikes stay rare. */
    private fun requestKeyframeSoon(force: Boolean = false) {
        if (videoW <= 0) {
            return
        }
        val now = SystemClock.uptimeMillis()
        val minIntervalMs = if (force) 500L else KEYFRAME_REQUEST_MIN_MS
        if (now - lastKeyframeRequestMs < minIntervalMs) {
            return
        }
        lastKeyframeRequestMs = now
        onNeedKeyframe?.invoke()
    }



    private fun stopCodec() {
        stopDrainThread()
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }



    companion object {
        private const val TAG = "MiraxH264"
        /** Soft catch-up nudge (~200 ms at 60 fps) without freezing decode. */
        private const val SOFT_PENDING_FRAMES = 12



        /**
         * Freeze for IDR when backlog is ~400 ms at 60 fps.
         * Dropping mid-GOP tears the picture; a hard cut waits for the next keyframe.
         */
        private const val HARD_PENDING_FRAMES = 24



        /** Minimum gap between wfd_idr_request SET_PARAMETERs. */
        private const val KEYFRAME_REQUEST_MIN_MS = 4_000L
        /** Soft IDR nudge when decode/output freezes while work is queued. */
        private const val OUTPUT_SOFT_STALL_MS = 700L
        /** Drop wedged in-flight AUs and await IDR after this freeze. */
        private const val OUTPUT_HARD_STALL_MS = 2_000L
        private const val DRAIN_SLEEP_MS = 2L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val TOUCH_LAG_LOG_INTERVAL_MS = 500L
        private const val SUBMIT_LAG_LOG_INTERVAL_MS = 500L
        private const val PREFERRED_MTK_AVC = "OMX.MTK.VIDEO.DECODER.AVC"



        private fun isKeyframe(au: ByteArray): Boolean = hasNal(au, 5) || hasNal(au, 7)



        private fun hasNal(au: ByteArray, type: Int): Boolean {
            var i = 0
            while (i + 4 < au.size) {
                val start = when {
                    au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte() -> i + 3
                    i + 4 < au.size &&
                        au[i] == 0.toByte() && au[i + 1] == 0.toByte() &&
                        au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (start < 0) {
                    i++
                    continue
                }
                if ((au[start].toInt() and 0x1F) == type) {
                    return true
                }
                i = start + 1
            }
            return false
        }
    }
}

