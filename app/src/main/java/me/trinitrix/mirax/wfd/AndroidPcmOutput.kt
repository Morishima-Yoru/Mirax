package me.trinitrix.mirax.wfd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** Owns one streaming AudioTrack, including cancellation racing with native preparation. */
internal class AndroidPcmOutput : PcmOutput {
    @Volatile private var track: AudioTrack? = null
    private var preparing = false
    @Volatile private var interrupted = false
    private var closed = false
    private var previousHead = 0L
    private var headWrap = 0L

    /** Actual capacity, for diagnostics and device-side verification. */
    @Volatile var bufferCapacityFrames = 0
        private set

    /** Actual streaming threshold, independent from the allocated capacity. */
    @Volatile var startThresholdFrames = 0
        private set

    /** Whether this particular output has entered the platform's playing state. */
    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** @return true after allocating the output without calling play. */
    override fun prepare(): Boolean {
        synchronized(this) {
            if (closed || interrupted || preparing || track != null) return false
            preparing = true
        }
        var candidate: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                Log.e(TAG, "AudioTrack buffer size unavailable: $minimum")
                return false
            }
            val created = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, MIN_BUFFER_BYTES))
                .build()
            candidate = created
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack did not initialize")
                return false
            }
            val threshold = created.setStartThresholdInFrames(1)
            if (threshold != 1) {
                Log.e(TAG, "AudioTrack rejected sparse-audio start threshold: $threshold")
                return false
            }
            val capacity = created.bufferCapacityInFrames
            val accepted = synchronized(this) {
                if (closed || interrupted) false else {
                    bufferCapacityFrames = capacity
                    startThresholdFrames = threshold
                    previousHead = 0
                    headWrap = 0
                    track = created
                    true
                }
            }
            if (!accepted) return false
            candidate = null
            Log.i(TAG, "AudioTrack prepared capacity_frames=$capacity start_threshold_frames=$threshold")
            return true
        } catch (err: RuntimeException) {
            Log.e(TAG, "AudioTrack preparation failed", err)
            return false
        } finally {
            candidate?.let { releaseTrack(it) }
            synchronized(this) { preparing = false }
        }
    }

    /** @return true if playback started without a concurrent stop request. */
    override fun start(): Boolean {
        if (interrupted) return false
        val current = track ?: return false
        return try {
            current.play()
            if (interrupted) {
                current.pause()
                false
            } else true
        } catch (err: RuntimeException) {
            Log.e(TAG, "AudioTrack start failed", err)
            false
        }
    }

    /**
     * Writes without holding a lifecycle monitor, allowing interrupt to pause the track.
     *
     * @param pcm source samples.
     * @param offset first byte to write.
     * @param length requested byte count.
     * @return accepted bytes or the platform error code.
     */
    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        if (interrupted) return AudioTrack.ERROR_INVALID_OPERATION
        val current = track ?: return AudioTrack.ERROR_INVALID_OPERATION
        return current.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
    }

    /** @return unsigned playback-head frames extended across the 32-bit hardware wrap. */
    override fun playedFrames(): Long {
        val current = track ?: return synchronized(this) { headWrap + previousHead }
        if (interrupted) return synchronized(this) { headWrap + previousHead }
        val head = current.playbackHeadPosition.toLong() and 0xffff_ffffL
        return synchronized(this) {
            if (track === current && !interrupted) {
                if (head < previousHead) headWrap += 1L shl 32
                previousHead = head
            }
            headWrap + previousHead
        }
    }

    /** Pauses to interrupt a blocked write; a late preparation result is rejected as well. */
    override fun interrupt() {
        val current = synchronized(this) {
            interrupted = true
            track
        }
        try {
            current?.pause()
        } catch (err: RuntimeException) {
            Log.w(TAG, "AudioTrack pause during interruption failed", err)
        }
    }

    /** Detaches ownership before release; repeated close calls cannot release the same track. */
    override fun close() {
        val current = synchronized(this) {
            if (closed) return
            closed = true
            interrupted = true
            val previous = track
            track = null
            previous
        }
        current?.let { releaseTrack(it) }
    }

    /** @param current detached track to release even if pausing or flushing fails. */
    private fun releaseTrack(current: AudioTrack) {
        try {
            current.pause()
        } catch (err: RuntimeException) {
            Log.w(TAG, "AudioTrack pause before release failed", err)
        }
        try {
            current.flush()
        } catch (err: RuntimeException) {
            Log.w(TAG, "AudioTrack flush before release failed", err)
        }
        try {
            current.release()
        } catch (err: RuntimeException) {
            Log.e(TAG, "AudioTrack release failed", err)
        }
    }

    private companion object {
        const val TAG = "MiraxAudio"
        const val SAMPLE_RATE = 48_000
        const val MIN_BUFFER_BYTES = 19_200
    }
}
