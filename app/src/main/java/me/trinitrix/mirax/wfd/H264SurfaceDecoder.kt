package me.trinitrix.mirax.wfd

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Decodes H.264 access units onto an attached [Surface].
 * Aspect-fit layout is owned by the picture activity.
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

    @Volatile
    var onFormat: ((width: Int, height: Int, fps: Int) -> Unit)? = null

    fun attachSurface(surface: Surface?) {
        synchronized(this) {
            this.surface = surface
            if (surface == null) {
                stopCodec()
            } else {
                drainPending()
            }
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
            }
            drainPending()
        }
    }

    fun submitAccessUnit(au: ByteArray) {
        synchronized(this) {
            if (awaitingKeyframe && !hasNal(au, 5) && pending.isEmpty()) {
                // Wait for an IDR before starting the decoder.
            } else {
                if (awaitingKeyframe && hasNal(au, 5)) {
                    pending.clear()
                }
                while (pending.size > 90) {
                    pending.removeFirst()
                }
                pending.addLast(au)
            }
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
            queue(active, au)
            releaseOutput(active)
        }
        releaseOutput(active)
    }

    private fun ensureCodec() {
        if (codec != null || surface == null) {
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6_000_000)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, videoFps)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            frames = 0
            Log.i(TAG, "decoder started ${videoW}x${videoH}@$videoFps")
        } catch (err: Exception) {
            Log.e(TAG, "decoder configure failed", err)
            stopCodec()
        }
    }

    private fun queue(codec: MediaCodec, au: ByteArray) {
        try {
            var index = codec.dequeueInputBuffer(20_000)
            if (index < 0) {
                releaseOutput(codec)
                index = codec.dequeueInputBuffer(20_000)
            }
            if (index < 0) {
                return
            }
            val buffer: ByteBuffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(au)
            val flags = if (isKeyframe(au)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, au.size, frames * (1_000_000L / videoFps), flags)
        } catch (err: Exception) {
            Log.e(TAG, "queue failed", err)
            stopCodec()
            awaitingKeyframe = true
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
