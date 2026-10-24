package me.trinitrix.mirax.wfd

import android.os.Process
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Buffers stereo 48 kHz PCM16 without blocking RTP reception.
 *
 * @param nowMillis monotonic clock, replaceable with virtual time in player tests.
 * @param createDispatcher factory for an owned, dedicated audio dispatcher.
 * @param createOutput factory for one connection's output.
 */
internal class LpcmAudioPlayer(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val createDispatcher: () -> CoroutineDispatcher = { createAudioDispatcher() },
    private val createOutput: () -> PcmOutput = { AndroidPcmOutput() },
) {
    private val outputOwnership = Mutex()
    @Volatile private var active: Playback? = null

    /** Starts preparation for a new connection; the output opens on its audio worker. */
    @Synchronized
    fun start() {
        stop()
        val dispatcher = try {
            createDispatcher()
        } catch (err: RuntimeException) {
            Log.e(TAG, "Audio worker creation failed", err)
            return
        }
        val playback = Playback(nowMillis() + PRELOAD_TIMEOUT_MS)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        active = playback
        val job = scope.launch {
            outputOwnership.withLock { play(playback) }
        }
        playback.job = job
        job.invokeOnCompletion {
            playback.finish()
            synchronized(this) {
                if (active === playback) active = null
            }
            scope.cancel()
            (dispatcher as? ExecutorCoroutineDispatcher)?.close()
        }
    }

    /**
     * Offers frame-aligned PCM, retaining at most 250 ms of pending audio.
     *
     * @param pcm signed stereo PCM16 in little-endian order.
     */
    fun submit(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (pcm.size % FRAME_BYTES != 0) {
            Log.w(TAG, "Ignoring PCM not aligned to a stereo frame: ${pcm.size}")
            return
        }
        active?.offer(pcm, nowMillis())
    }

    /** Cancels this connection and requests interruption of any blocking output write. */
    @Synchronized
    fun stop() {
        val playback = active ?: return
        active = null
        playback.finish()
        playback.job?.cancel()
        try {
            playback.output?.interrupt()
        } catch (err: RuntimeException) {
            Log.w(TAG, "Audio output interruption failed", err)
        }
    }

    /**
     * Owns an output until its final release, including failed or cancelled preparation.
     *
     * @param playback connection-specific queue and cancellation state.
     */
    private suspend fun play(playback: Playback) {
        var output: PcmOutput? = null
        try {
            currentCoroutineContext().ensureActive()
            val device = createOutput()
            output = device
            if (!playback.attach(device) || !device.prepare()) return
            stream(playback, device)
        } catch (err: CancellationException) {
            throw err
        } catch (err: RuntimeException) {
            Log.e(TAG, "Audio playback stopped unexpectedly", err)
        } finally {
            try {
                output?.close()
            } catch (err: RuntimeException) {
                Log.e(TAG, "Audio output release failed", err)
            }
        }
    }

    /**
     * Gates playback on supply, tracking device progress rather than queue emptiness alone.
     *
     * @param playback the active connection's queue.
     * @param output its prepared PCM output.
     */
    private suspend fun stream(playback: Playback, output: PcmOutput) {
        val block = ByteArray((BLOCK_FRAMES + SPLICE_FRAMES) * FRAME_BYTES)
        val tail = ByteArray(SPLICE_FRAMES * FRAME_BYTES)
        val fades = Fades()
        var buffering = true
        var deadline = playback.preloadDeadline
        var writtenFrames = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (buffering) {
                val pendingFrames = playback.sizeFrames()
                val remaining = deadline - nowMillis()
                if (pendingFrames < PRELOAD_FRAMES && (pendingFrames == 0 || remaining > 0)) {
                    playback.awaitSignal(remaining.takeIf { it > 0 })
                    continue
                }
                if (!output.start()) {
                    Log.e(TAG, "Audio output could not start")
                    return
                }
                buffering = false
                fades.beginStartup()
            }
            val playedFrames = output.playedFrames()
            val count = playback.read(block, tail, playedFrames)
            if (count == DISCONTINUITY) {
                val tailFrames = playback.takeTail()
                if (tailFrames > 0) {
                    fades.apply(tail, tailFrames, fadeOut = true)
                    if (!write(output, tail, tailFrames)) return
                    writtenFrames += tailFrames
                }
                fades.beginSplice()
                continue
            }
            if (count > 0) {
                fades.apply(block, count)
                if (!write(output, block, count)) return
                writtenFrames += count
                continue
            }
            val queuedAtDevice = (writtenFrames - playedFrames).coerceAtLeast(0)
            if (queuedAtDevice > BLOCK_FRAMES) {
                playback.awaitSignal(((queuedAtDevice - BLOCK_FRAMES) * 1_000 + SAMPLE_RATE - 1) / SAMPLE_RATE)
            } else if (!playback.hasQueuedFrames()) {
                val tailFrames = playback.takeTail()
                if (tailFrames > 0) {
                    fades.apply(tail, tailFrames, fadeOut = true)
                    if (!write(output, tail, tailFrames)) return
                    writtenFrames += tailFrames
                }
                buffering = true
                deadline = nowMillis() + PRELOAD_TIMEOUT_MS
            }
        }
    }

    /**
     * Continues short writes without processing the same samples twice.
     *
     * @param output playback device.
     * @param pcm already processed PCM.
     * @param frames number of complete stereo frames.
     * @return whether the entire block was accepted.
     */
    private suspend fun write(output: PcmOutput, pcm: ByteArray, frames: Int): Boolean {
        var offset = 0
        val length = frames * FRAME_BYTES
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            val written = output.write(pcm, offset, length - offset)
            if (written <= 0 || written > length - offset || written % FRAME_BYTES != 0) {
                Log.e(TAG, "AudioTrack write failed or returned an invalid frame count: $written")
                return false
            }
            offset += written
        }
        return true
    }

    /** Connection state shared only at short queue operations, never during output writes. */
    private class Playback(val preloadDeadline: Long) {
        private val queued = ArrayDeque<Chunk>()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private var queuedFrames = 0
        private var tailFrames = 0
        private var discontinuous = false
        private var catchingUp = false
        private var lastCatchUpProgress = -1L
        private var lastDropLogMillis = Long.MIN_VALUE
        private var closed = false
        @Volatile var job: Job? = null
        @Volatile var output: PcmOutput? = null

        /** @return whether the output was attached before cancellation. */
        fun attach(device: PcmOutput): Boolean = synchronized(queued) {
            if (closed) false else {
                output = device
                true
            }
        }

        /**
         * Retains recent input without evicting the worker's audible tail.
         *
         * @param pcm complete PCM frames.
         * @param nowMillis monotonic time for rate-limited drop diagnostics.
         */
        fun offer(pcm: ByteArray, nowMillis: Long) {
            synchronized(queued) {
                if (closed) return
                val capacity = MAX_PENDING_FRAMES - tailFrames
                val inputFrames = pcm.size / FRAME_BYTES
                val keep = minOf(inputFrames, capacity)
                val removed = (queuedFrames + keep - capacity).coerceAtLeast(0)
                discard(removed)
                queued.addLast(Chunk(pcm.copyOfRange(pcm.size - keep * FRAME_BYTES, pcm.size)))
                queuedFrames += keep
                if (removed > 0 || keep < inputFrames) {
                    discontinuous = true
                    if (lastDropLogMillis == Long.MIN_VALUE || nowMillis - lastDropLogMillis >= 1_000) {
                        lastDropLogMillis = nowMillis
                        Log.w(TAG, "Audio queue overflow; retaining recent PCM with a softened splice")
                    }
                }
            }
            changed.trySend(Unit)
        }

        /** @return pending frames including the protected tail, excluding the in-flight write. */
        fun sizeFrames(): Int = synchronized(queued) { queuedFrames + tailFrames }

        /** @return whether source data is available in addition to the protected tail. */
        fun hasQueuedFrames(): Boolean = synchronized(queued) { queuedFrames > 0 }

        /** @return tail frames transferred from the pending budget to the in-flight write. */
        fun takeTail(): Int = synchronized(queued) {
            val frames = tailFrames
            tailFrames = 0
            frames
        }

        /**
         * Atomically selects a splice or a block, preserving an unsubmitted output tail.
         *
         * @param target caller-owned output buffer.
         * @param tail buffer containing the protected tail.
         * @param playedFrames actual output progress, used to pace catch-up.
         * @return writable frames, zero for no block, or DISCONTINUITY before a changed source edge.
         */
        fun read(target: ByteArray, tail: ByteArray, playedFrames: Long): Int = synchronized(queued) {
            val total = queuedFrames + tailFrames
            if (total > HIGH_WATER_FRAMES) catchingUp = true
            if (total <= PRELOAD_FRAMES) catchingUp = false
            if (catchingUp && playedFrames > lastCatchUpProgress) {
                val maximum = minOf(CATCH_UP_FRAMES, total - PRELOAD_FRAMES)
                if (maximum > 0) {
                    discard(findQuietDrop(maximum))
                    discontinuous = true
                    lastCatchUpProgress = playedFrames
                }
            }
            if (discontinuous) {
                discontinuous = false
                return@synchronized DISCONTINUITY
            }
            val frames = minOf(queuedFrames, BLOCK_FRAMES)
            if (frames == 0) return@synchronized 0
            tail.copyInto(target, endIndex = tailFrames * FRAME_BYTES)
            var copied = 0
            while (copied < frames * FRAME_BYTES) {
                val chunk = queued.first()
                val bytes = minOf(chunk.pcm.size - chunk.offset, frames * FRAME_BYTES - copied)
                chunk.pcm.copyInto(target, tailFrames * FRAME_BYTES + copied, chunk.offset, chunk.offset + bytes)
                chunk.offset += bytes
                copied += bytes
                if (chunk.offset == chunk.pcm.size) queued.removeFirst()
            }
            queuedFrames -= frames
            val combined = tailFrames + frames
            tailFrames = minOf(SPLICE_FRAMES, combined)
            val writable = combined - tailFrames
            target.copyInto(tail, 0, writable * FRAME_BYTES, combined * FRAME_BYTES)
            writable
        }

        /**
         * Suspends for supply or a single monotonic deadline; no input means no polling.
         *
         * @param timeoutMillis time to a meaningful deadline, or null for input only.
         */
        suspend fun awaitSignal(timeoutMillis: Long?) {
            if (timeoutMillis == null) changed.receive()
            else withTimeoutOrNull(timeoutMillis) { changed.receive() }
        }

        /** Rejects new input and wakes any suspended playback operation. */
        fun finish() {
            synchronized(queued) {
                closed = true
                queued.clear()
                queuedFrames = 0
                tailFrames = 0
            }
            changed.cancel()
        }

        /** @param frames number of old frames to remove while holding the queue monitor. */
        private fun discard(frames: Int) {
            var remaining = frames
            while (remaining > 0) {
                val chunk = queued.first()
                val count = minOf(remaining, (chunk.pcm.size - chunk.offset) / FRAME_BYTES)
                chunk.offset += count * FRAME_BYTES
                queuedFrames -= count
                remaining -= count
                if (chunk.offset == chunk.pcm.size) queued.removeFirst()
            }
        }

        /**
         * Searches only a bounded window near the desired cut, never the whole waveform.
         *
         * @param maximum greatest permissible source-frame drop.
         * @return a low-amplitude cut, or the bounded fallback when the source stays loud.
         */
        private fun findQuietDrop(maximum: Int): Int {
            val firstCandidate = maxOf(1, maximum - SEARCH_FRAMES + 1)
            var baseFrame = 0
            for (chunk in queued) {
                val frames = (chunk.pcm.size - chunk.offset) / FRAME_BYTES
                val start = maxOf(0, firstCandidate - baseFrame)
                val end = minOf(frames - 1, maximum - baseFrame)
                for (frame in start..end) {
                    val offset = chunk.offset + frame * FRAME_BYTES
                    if (abs(readSample(chunk.pcm, offset)) <= QUIET_AMPLITUDE &&
                        abs(readSample(chunk.pcm, offset + 2)) <= QUIET_AMPLITUDE
                    ) return baseFrame + frame
                }
                baseFrame += frames
                if (baseFrame > maximum) break
            }
            return maximum
        }
    }

    /** Event-only gains; steady playback bypasses the per-frame processing loop entirely. */
    private class Fades {
        private var startupFrame = FADE_IN_FRAMES
        private var spliceFrame = SPLICE_FRAMES

        /** Starts a fresh fade after startup or confirmed supply starvation. */
        fun beginStartup() {
            startupFrame = 0
            spliceFrame = SPLICE_FRAMES
        }

        /** Coalesces pending splice fades without restarting the wider startup fade. */
        fun beginSplice() {
            spliceFrame = 0
        }

        /**
         * Applies the most conservative active gain once to the samples being submitted.
         *
         * @param pcm worker-owned samples.
         * @param frames complete stereo frames in this block.
         * @param fadeOut whether this is the final retained tail before a discontinuity.
         */
        fun apply(pcm: ByteArray, frames: Int, fadeOut: Boolean = false) {
            if (!fadeOut && startupFrame == FADE_IN_FRAMES && spliceFrame == SPLICE_FRAMES) return
            for (frame in 0 until frames) {
                val startupGain = minOf(UNITY_GAIN, startupFrame * UNITY_GAIN / (FADE_IN_FRAMES - 1))
                val spliceGain = minOf(UNITY_GAIN, spliceFrame * UNITY_GAIN / (SPLICE_FRAMES - 1))
                val tailGain = if (!fadeOut) UNITY_GAIN else if (frames == 1) 0 else
                    (frames - frame - 1) * UNITY_GAIN / (frames - 1)
                val gain = minOf(startupGain, spliceGain, tailGain)
                if (gain != UNITY_GAIN) scaleFrame(pcm, frame, gain)
                startupFrame = minOf(FADE_IN_FRAMES, startupFrame + 1)
                spliceFrame = minOf(SPLICE_FRAMES, spliceFrame + 1)
            }
        }
    }

    /** A queued input's unconsumed byte range. */
    private class Chunk(val pcm: ByteArray, var offset: Int = 0)

    private companion object {
        const val TAG = "MiraxAudio"
        const val SAMPLE_RATE = 48_000
        const val FRAME_BYTES = 4
        const val BLOCK_FRAMES = 480
        const val PRELOAD_FRAMES = 7_200
        const val MAX_PENDING_FRAMES = 12_000
        const val PRELOAD_TIMEOUT_MS = 1_500L
        const val FADE_IN_FRAMES = 2_400
        const val SPLICE_FRAMES = 64
        const val HIGH_WATER_FRAMES = 8_640
        const val CATCH_UP_FRAMES = 1_440
        const val SEARCH_FRAMES = 192
        const val QUIET_AMPLITUDE = 256
        const val DISCONTINUITY = -1
        const val UNITY_GAIN = 32_768

        /** @return an owned executor whose priority never affects shared IO workers. */
        fun createAudioDispatcher(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                } catch (err: RuntimeException) {
                    Log.w(TAG, "Audio thread priority unavailable; keeping default priority", err)
                }
                task.run()
            }, "mirax-audio").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /**
         * Applies one common integer gain to the two signed samples of a stereo frame.
         *
         * @param pcm little-endian samples.
         * @param frame frame offset in the buffer.
         * @param gain fixed-point gain from zero through UNITY_GAIN.
         */
        fun scaleFrame(pcm: ByteArray, frame: Int, gain: Int) {
            for (channel in 0..1) {
                val offset = frame * FRAME_BYTES + channel * 2
                val sample = readSample(pcm, offset)
                val scaled = sample * gain / UNITY_GAIN
                pcm[offset] = scaled.toByte()
                pcm[offset + 1] = (scaled shr 8).toByte()
            }
        }

        /** @return one signed little-endian PCM16 sample at a byte offset. */
        fun readSample(pcm: ByteArray, offset: Int): Int =
            ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}

/** Android PCM boundary, including preparation, progress and cancellation of blocking writes. */
internal interface PcmOutput {
    /** @return whether the output is prepared without starting playback. */
    fun prepare(): Boolean

    /** @return whether the prepared output entered playback. */
    fun start(): Boolean

    /**
     * Writes whole stereo frames, possibly accepting only a prefix.
     *
     * @param pcm source samples.
     * @param offset first byte to write.
     * @param length requested byte count.
     * @return accepted byte count, or a non-positive error result.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int): Int

    /** @return monotonic played stereo frames for this output, extending any hardware counter wrap. */
    fun playedFrames(): Long

    /** Requests that an in-flight write return without releasing resources owned by its worker. */
    fun interrupt()

    /** Stops, flushes and releases this output, including partially failed preparation. */
    fun close()
}
