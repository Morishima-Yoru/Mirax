package me.trinitrix.mirax.wfd

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Decodes H.264 access units onto an attached [Surface].
 * Picture placement is owned by the picture activity via [me.trinitrix.mirax.session.PicturePlacement].
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
    private var lastKeyframeRequestMs: Long = 0

    @Volatile
    var onFormat: ((width: Int, height: Int, fps: Int) -> Unit)? = null

    /**
     * Fired when a new decoder cannot start until the source sends an IDR.
     * The callback must not re-enter this decoder.
     */
    @Volatile
    var onNeedKeyframe: (() -> Unit)? = null

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
                return
            }
            if (isIdr) {
                // IDR is a clean catch-up point: drop backlog without a freeze-wait.
                pending.clear()
                awaitingKeyframe = false
                Log.i(TAG, "keyframe queued ${au.size}")
                pending.addLast(au)
                drainPending()
                return
            }
            // Soft backlog: ask Windows for an IDR soon, but keep decoding so the
            // picture does not hitch. Hard backlog: only then freeze for resync.
            if (pending.size >= HARD_PENDING_FRAMES) {
                pending.clear()
                awaitingKeyframe = true
                Log.w(TAG, "hard backlog; wait for IDR")
                requestKeyframeSoon()
                drainPending()
                return
            }
            if (pending.size >= SOFT_PENDING_FRAMES) {
                requestKeyframeSoon()
            }
            pending.addLast(au)
            drainPending()
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
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6_000_000)
                if (H264LowLatencyConfigurePolicy.usesLowLatency(phase)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, videoFps)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
                created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                created.configure(format, output, null, 0)
                created.start()
                codec = created
                frames = 0
                val lowLatencyLabel =
                    if (H264LowLatencyConfigurePolicy.usesLowLatency(phase)) {
                        "true"
                    } else {
                        "omitted"
                    }
                Log.i(
                    TAG,
                    "decoder started ${created.name} ${videoW}x${videoH}@$videoFps " +
                        "lowLatency=$lowLatencyLabel",
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
                if (next != null) {
                    Log.w(
                        TAG,
                        "decoder configure with low-latency failed; " +
                            "retrying without KEY_LOW_LATENCY",
                        err,
                    )
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
                index = codec.dequeueInputBuffer(5_000)
            }
            if (index < 0) {
                releaseOutput(codec)
                index = codec.dequeueInputBuffer(5_000)
            }
            if (index < 0) {
                return false
            }
            val buffer: ByteBuffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            buffer.put(au)
            val flags = if (isKeyframe(au)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            val ptsUs = frames * (1_000_000L / videoFps.coerceAtLeast(1))
            codec.queueInputBuffer(index, 0, au.size, ptsUs, flags)
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
        }
    }

    /** Ask the source for an IDR, but not more than twice a second. */
    private fun requestKeyframeSoon() {
        if (videoW <= 0) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyframeRequestMs < 500) {
            return
        }
        lastKeyframeRequestMs = now
        onNeedKeyframe?.invoke()
    }

    private fun stopCodec() {
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
        /** Ask the source for an IDR while still decoding (~200 ms at 60 fps). */
        private const val SOFT_PENDING_FRAMES = 12

        /**
         * Only freeze for IDR when the backlog is severe (~500 ms at 60 fps).
         * Dropping mid-GOP tears the picture; a hard cut waits for the next keyframe.
         */
        private const val HARD_PENDING_FRAMES = 30

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
