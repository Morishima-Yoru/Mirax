package me.trinitrix.mirax.wfd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Sends stereo 48 kHz PCM16 to an output without blocking video reception.
 *
 * @param createOutput factory for the Android audio output.
 */
internal class LpcmAudioPlayer(
    private val createOutput: () -> PcmOutput = { AndroidPcmOutput() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pending: Channel<ByteArray>? = null
    private var playbackJob: Job? = null

    /** Starts playback for one connection. */
    @Synchronized
    fun start() {
        stop()
        val output = createOutput()
        if (!output.start()) return
        val frames = Channel<ByteArray>(MAX_BUFFERED_FRAMES)
        pending = frames
        // Enter try/finally before stop() can cancel the playback coroutine.
        playbackJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { play(output, frames) }
    }

    /**
     * Queues PCM in 10 ms blocks, dropping stale audio after 120 ms rather than stalling video.
     *
     * @param pcm 48 kHz stereo PCM16 in little-endian byte order.
     */
    fun submit(pcm: ByteArray) {
        val frames = pending ?: return
        var dropped = false
        for (offset in pcm.indices step PCM_BLOCK_BYTES) {
            val block = if (pcm.size <= PCM_BLOCK_BYTES) pcm else
                pcm.copyOfRange(offset, minOf(offset + PCM_BLOCK_BYTES, pcm.size))
            if (frames.trySend(block).isFailure && frames.tryReceive().isSuccess) {
                if (frames.trySend(block).isSuccess) dropped = true
            }
        }
        if (dropped) Log.w(TAG, "Audio queue overflow; dropping stale samples")
    }

    /** Stops playback and discards all queued PCM from this connection. */
    @Synchronized
    fun stop() {
        pending?.cancel()
        pending = null
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Writes PCM on a coroutine worker; cancellation always releases the audio output.
     *
     * @param output the playback device for this connection.
     * @param frames bounded PCM queue for this connection.
     */
    private suspend fun play(output: PcmOutput, frames: Channel<ByteArray>) {
        var firstPcm = true
        try {
            for (pcm in frames) {
                var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val written = output.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) {
                        Log.e(TAG, "AudioTrack write failed: $written")
                        return
                    }
                    offset += written
                }
                if (firstPcm) {
                    firstPcm = false
                    Log.i(TAG, "First PCM delivered to AudioTrack")
                }
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: RuntimeException) {
            Log.e(TAG, "Audio playback stopped unexpectedly", err)
        } finally {
            output.close()
            synchronized(this) {
                if (pending === frames) {
                    pending = null
                    playbackJob = null
                    frames.cancel()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MiraxAudio"
        // Keep each PCM block at most 10 ms so the bounded queue cannot exceed 120 ms.
        const val PCM_BLOCK_BYTES = 1_920
        const val MAX_BUFFERED_FRAMES = 12
    }
}

/** Android audio-output seam for playing PCM and observing playback during tests. */
internal interface PcmOutput {
    /** @return true if the output started successfully. */
    fun start(): Boolean

    /**
     * Writes PCM to the output.
     *
     * @param pcm 48 kHz stereo PCM16 samples.
     * @param offset first byte to play.
     * @param length number of bytes to play.
     * @return number of PCM bytes accepted, or an error code.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int): Int

    /** Releases the output after playback or cancellation. */
    fun close()
}

/** Streams PCM to the device's media audio track. */
private class AndroidPcmOutput : PcmOutput {
    private var track: AudioTrack? = null

    /** @return true if the device's audio track is playing. */
    override fun start(): Boolean {
        val minBufferSize = AudioTrack.getMinBufferSize(
            48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable: $minBufferSize")
            return false
        }
        val next = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (err: RuntimeException) {
            Log.e(TAG, "AudioTrack creation failed", err)
            return false
        }
        if (next.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            next.release()
            return false
        }
        try {
            next.play()
        } catch (err: IllegalStateException) {
            Log.e(TAG, "AudioTrack could not play", err)
            next.release()
            return false
        }
        track = next
        return true
    }

    /**
     * Writes PCM through AudioTrack.
     *
     * @param pcm 48 kHz stereo PCM16 samples.
     * @param offset first byte to play.
     * @param length number of bytes to play.
     * @return number of bytes accepted by Android, or an AudioTrack error code.
     */
    override fun write(pcm: ByteArray, offset: Int, length: Int): Int =
        track?.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING) ?: AudioTrack.ERROR_INVALID_OPERATION

    /** Pauses, discards buffered audio, and releases the track. */
    override fun close() {
        val current = track ?: return
        track = null
        try {
            current.pause()
            current.flush()
        } catch (err: IllegalStateException) {
            Log.w(TAG, "AudioTrack could not flush on stop", err)
        } finally {
            current.release()
        }
    }

    private companion object {
        const val TAG = "MiraxAudio"
    }
}
