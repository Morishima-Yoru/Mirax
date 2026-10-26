package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Verifies observable preparation, PCM delivery and transitions at the output boundary. */
class LpcmAudioPlayerTest {
    /** Watchdog for busy-loop regressions; behavioral deadlines still use virtual time. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val scheduler = AudioTestScheduler()
    private val output = RecordingOutput(scheduler)
    private val player = LpcmAudioPlayer(
        nowMillis = { scheduler.nowMillis },
        createDispatcher = { scheduler },
        createOutput = { output },
    )

    /** Releases the current connection even when a behavioral assertion fails. */
    @After
    fun stopPlayback() {
        player.stop()
        scheduler.runCurrent()
    }

    /** Keeps a silent connection prepared without starting playback. */
    @Test
    fun connectionWithoutPcmDoesNotStartPlayback() {
        player.start()
        scheduler.runCurrent()
        scheduler.advanceBy(5_000)

        assertThat(output.prepareCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(0)
        assertThat(output.bytes()).isEmpty()
    }

    /** Opens playback at the PCM frame threshold regardless of submission count. */
    @Test
    fun prefillCountsStereoFramesRatherThanInputItems() {
        player.start()
        player.submit(pcm(7_199))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(0)

        player.submit(pcm(1))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(1)
        assertThat(output.bytes()).isNotEmpty()
    }

    /** Coalesces producer notifications while the initial frame threshold remains unmet. */
    @Test
    fun insufficientPrefillDoesNotWakeTheAudioWorkerForEveryInputBlock() {
        player.start()
        scheduler.runCurrent()
        val before = scheduler.executedTasks
        repeat(14) {
            player.submit(pcm(480))
            scheduler.runCurrent()
        }

        assertThat(output.startCount).isEqualTo(0)
        assertThat(scheduler.executedTasks).isEqualTo(before)
        player.submit(pcm(480))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(1)
    }

    /** Avoids per-input worker wakeups while recovery is still waiting for sufficient PCM. */
    @Test
    fun recoveryPrefillCoalescesInputUntilEnoughAudioCanActuallyResume() {
        output.consumeImmediately = true
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        val before = scheduler.executedTasks
        repeat(14) {
            player.submit(pcm(480))
            scheduler.runCurrent()
        }

        assertThat(output.startCount).isEqualTo(1)
        assertThat(scheduler.executedTasks).isEqualTo(before)
        player.submit(pcm(480))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(2)
    }

    /** Releases sparse PCM at the connection deadline rather than restarting its timer. */
    @Test
    fun sparseAudioStartsAtTheOriginalConnectionDeadline() {
        player.start()
        scheduler.runCurrent()
        scheduler.advanceBy(1_000)
        player.submit(pcm(960))
        scheduler.runCurrent()
        scheduler.advanceBy(499)
        assertThat(output.startCount).isEqualTo(0)

        scheduler.advanceBy(1)
        assertThat(output.startCount).isEqualTo(1)
        assertThat(output.bytes()).isNotEmpty()
    }

    /** Plays the first late-arriving PCM without imposing another preparation interval. */
    @Test
    fun firstAudioAfterExpiredDeadlineDoesNotWaitAgain() {
        player.start()
        scheduler.runCurrent()
        scheduler.advanceBy(5_000)
        player.submit(pcm(480))
        scheduler.runCurrent()

        assertThat(output.startCount).isEqualTo(1)
        assertThat(output.bytes()).isNotEmpty()
    }

    /** Keeps the startup envelope continuous across chunks and preserves later samples. */
    @Test
    fun startupFadeSpansChunksAndThenLeavesBothChannelsUnchanged() {
        val original = pcm(7_200, left = -32_768, right = 16_384)
        player.start()
        for (offset in original.indices step 1_920) {
            player.submit(original.copyOfRange(offset, minOf(offset + 1_920, original.size)))
        }
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, 0, 0)).isEqualTo(0)
        assertThat(sample(actual, 0, 1)).isEqualTo(0)
        assertThat(sample(actual, 1_200, 0)).isIn(-16_400..-16_380)
        assertThat(sample(actual, 1_200, 1)).isIn(8_190..8_200)
        assertThat(sample(actual, 2_399, 0)).isEqualTo(-32_768)
        assertThat(sample(actual, 2_399, 1)).isEqualTo(16_384)
        assertThat(actual.copyOfRange(2_400 * 4, 7_000 * 4))
            .isEqualTo(original.copyOfRange(2_400 * 4, 7_000 * 4))
        assertThat(sample(original, 0, 0)).isEqualTo(-32_768)
    }

    /** Preserves the requested startup envelope when native writes accept only a prefix. */
    @Test
    fun shortWritesDoNotApplyTheStartupGainTwice() {
        output.maxWriteBytes = 28
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, 1_200, 0)).isIn(5_000..5_010)
        assertThat(sample(actual, 2_399, 0)).isEqualTo(10_000)
        assertThat(sample(actual, 2_400, 1)).isEqualTo(-10_000)
        assertThat(output.writeSizes.all { it % 4 == 0 && it <= 1_920 }).isTrue()
    }

    /** Verifies exact startup gains across uneven input and single-frame short writes. */
    @Test
    fun variableInputAndOneFrameWritesPreserveTheExactStartupWaveform() {
        val original = pcm(7_200, left = -32_768, right = 32_767)
        output.maxWriteBytes = 4
        player.start()
        var offsetFrames = 0
        for (frames in listOf(1, 63, 64, 65, 2_399, 2_400, 2_208)) {
            player.submit(original.copyOfRange(offsetFrames * 4, (offsetFrames + frames) * 4))
            offsetFrames += frames
        }
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(actual.size).isEqualTo((7_200 - 64) * 4)
        val expected = ByteBuffer.wrap(original.copyOf(actual.size)).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2_400) { frame ->
            val gain = frame * 32_768 / 2_399
            expected.putShort(frame * 4, (-32_768 * gain / 32_768).toShort())
            expected.putShort(frame * 4 + 2, (32_767 * gain / 32_768).toShort())
        }
        assertThat(actual).isEqualTo(expected.array())
    }

    /** Preserves stereo endpoints when a retained splice overlaps the startup fade. */
    @Test
    fun startupAndSpliceOverlapPreservesEveryOutputByteAcrossShortWrites() {
        output.maxWriteBytes = 4
        output.afterWrite = {
            output.afterWrite = null
            player.submit(pcm(48_000, left = -32_768, right = 32_767))
        }
        player.start()
        player.submit(pcm(7_200, left = 32_767, right = -32_768))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(actual.size / 4).isGreaterThan(2_400)
        val expected = ByteBuffer.allocate(actual.size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(actual.size / 4) { frame ->
            val startupGain = minOf(32_768, frame * 32_768 / 2_399)
            val tailGain = if (frame in 416..479) (479 - frame) * 32_768 / 63 else 32_768
            val spliceGain = if (frame in 480..543) (frame - 480) * 32_768 / 63 else 32_768
            val gain = minOf(startupGain, tailGain, spliceGain)
            val left = if (frame < 480) 32_767 else -32_768
            val right = if (frame < 480) -32_768 else 32_767
            expected.putShort((left * gain / 32_768).toShort())
            expected.putShort((right * gain / 32_768).toShort())
        }
        assertThat(actual).isEqualTo(expected.array())
    }

    /** Rejects malformed frame input without advancing the preparation threshold. */
    @Test
    fun emptyOrMisalignedInputDoesNotSatisfyPrefill() {
        player.start()
        player.submit(ByteArray(0))
        player.submit(ByteArray(28_801))
        scheduler.runCurrent()
        scheduler.advanceBy(1_500)

        assertThat(output.startCount).isEqualTo(0)
        assertThat(output.bytes()).isEmpty()
    }

    /** Retains playback while native output still has queued audio. */
    @Test
    fun temporarilyEmptyQueueDoesNotRebufferWhileDeviceHasAudio() {
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        scheduler.advanceBy(50)
        player.submit(pcm(480))
        scheduler.runCurrent()

        assertThat(output.startCount).isEqualTo(1)
        assertThat(sample(output.bytes(), 7_200, 0)).isEqualTo(10_000)
    }

    /** Fades resumed queued PCM after the device drains while its worker cannot run. */
    @Test
    fun drainedDeviceWithQueuedPcmRestartsTheRecoveryFade() {
        var resumedAtFrame = 0
        output.afterWrite = {
            if (output.bytes().size / 4 >= 2_880) {
                output.afterWrite = null
                resumedAtFrame = output.bytes().size / 4
                scheduler.advanceBy(200)
                output.underruns++
            }
        }
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(resumedAtFrame).isAtLeast(2_880)
        assertThat(sample(actual, resumedAtFrame, 0)).isEqualTo(0)
        assertThat(sample(actual, resumedAtFrame, 1)).isEqualTo(0)
        assertThat(sample(actual, resumedAtFrame + 1_200, 0)).isIn(5_000..5_010)
        assertThat(sample(actual, resumedAtFrame + 2_399, 0)).isEqualTo(10_000)
        assertThat(sample(actual, resumedAtFrame + 2_399, 1)).isEqualTo(-10_000)
    }

    /** Old producer callbacks must not fill a replacement connection's prebuffer. */
    @Test
    fun staleProducerCannotSubmitIntoAReplacementConnection() {
        val staleInput = player.startInput()
        scheduler.runCurrent()
        player.stop()
        val currentInput = player.startInput()
        currentInput(pcm(7_199))
        staleInput(pcm(1, left = -30_000, right = 30_000))
        scheduler.runCurrent()

        assertThat(output.startCount).isEqualTo(0)
        currentInput(pcm(1))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(1)
        assertThat(sample(output.bytes(), 2_400, 0)).isEqualTo(10_000)
    }

    /** A failed connection's input stays inert after a later worker starts successfully. */
    @Test
    fun failedStartInputCannotBecomeTheNextConnectionsInput() {
        var failWorker = true
        val controlled = LpcmAudioPlayer(
            nowMillis = { scheduler.nowMillis },
            createDispatcher = {
                if (failWorker) throw IllegalStateException("Worker unavailable")
                scheduler
            },
            createOutput = { output },
        )
        try {
            val failedInput = controlled.startInput()
            failWorker = false
            val currentInput = controlled.startInput()
            currentInput(pcm(7_199))
            failedInput(pcm(1))
            scheduler.runCurrent()

            assertThat(output.startCount).isEqualTo(0)
            assertThat(output.bytes()).isEmpty()
        } finally {
            controlled.stop()
            scheduler.runCurrent()
        }
    }

    /** Native counter query failure closes the output without starting or accepting later input. */
    @Test
    fun underrunQueryFailureReleasesTheOutputAndRejectsLaterInput() {
        output.underrunFailure = IllegalStateException("Counter unavailable")
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(0)
        assertThat(output.bytes()).isEmpty()
    }

    /** A reported underrun during a short write must reshape raw, not previously faded, PCM. */
    @Test
    fun underrunDuringShortWriteRestartsFadeFromTheUnprocessedSuffix() {
        output.maxWriteBytes = 4
        output.afterWrite = {
            if (output.bytes().size == 1_200 * 4) {
                output.afterWrite = null
                output.underruns++
            }
        }
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, 1_200, 0)).isEqualTo(0)
        assertThat(sample(actual, 1_200, 1)).isEqualTo(0)
        assertThat(sample(actual, 2_400, 0)).isIn(5_000..5_010)
        assertThat(sample(actual, 3_599, 0)).isEqualTo(10_000)
        assertThat(sample(actual, 3_599, 1)).isEqualTo(-10_000)
    }

    /** Immediate head progress without a new underrun is not a reason to restart the gain. */
    @Test
    fun immediatePlaybackHeadProgressDoesNotInventUnderruns() {
        output.consumeImmediately = true
        output.underruns = 7
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, 1_200, 0)).isIn(5_000..5_010)
        assertThat(sample(actual, 2_400, 0)).isEqualTo(10_000)
        assertThat(sample(actual, 6_000, 1)).isEqualTo(-10_000)
    }

    /** Smooths the retained output splice rather than samples that were discarded. */
    @Test
    fun overflowFadesTheRetainedAudibleTailAndTheNewHead() {
        var framesBeforeBurst = 0
        output.maxWriteBytes = 28
        output.afterWrite = {
            output.afterWrite = null
            framesBeforeBurst = output.requestedBlockFrames
            player.submit(pcm(48_000, left = 20_000, right = -20_000))
        }
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, framesBeforeBurst - 1, 0)).isGreaterThan(0)
        assertThat(sample(actual, framesBeforeBurst + 63, 0)).isEqualTo(0)
        assertThat(sample(actual, framesBeforeBurst + 63, 1)).isEqualTo(0)
        assertThat(sample(actual, framesBeforeBurst + 64, 0)).isEqualTo(0)
        assertThat(sample(actual, framesBeforeBurst + 65, 0)).isGreaterThan(0)
        assertThat(actual.size / 4).isAtMost(12_000 + 480)
    }

    /** Fades the controllable tail and requires preparation before a faded recovery. */
    @Test
    fun supplyInterruptionFadesOutAndRecoveryPrefillsThenFadesIn() {
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        scheduler.advanceBy(200)

        val interrupted = output.bytes()
        assertThat(sample(interrupted, interrupted.size / 4 - 1, 0)).isEqualTo(0)
        assertThat(sample(interrupted, interrupted.size / 4 - 1, 1)).isEqualTo(0)
        assertThat(output.startCount).isEqualTo(1)

        player.submit(pcm(7_199))
        scheduler.runCurrent()
        assertThat(output.startCount).isEqualTo(1)
        player.submit(pcm(1))
        scheduler.runCurrent()

        val recovered = output.bytes()
        assertThat(output.startCount).isEqualTo(2)
        assertThat(sample(recovered, interrupted.size / 4, 0)).isEqualTo(0)
        assertThat(sample(recovered, interrupted.size / 4 + 2_399, 0)).isEqualTo(10_000)
    }

    /** Bounds quiet-point search so loud input still catches up and delivers recent PCM. */
    @Test
    fun loudAudioCatchUpDoesNotWaitForAQuietPointOrLoseTheLatestPcm() {
        output.consumeImmediately = true
        val newest = pcm(480, left = 21_000, right = -21_000)
        player.start()
        player.submit(pcm(24_000))
        player.submit(newest)
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(actual.size / 4).isAtLeast(7_200)
        assertThat(actual.size / 4).isLessThan(12_000)
        assertThat(sample(actual, actual.size / 4 - 100, 0)).isEqualTo(21_000)
        assertThat(sample(actual, actual.size / 4 - 1, 0)).isEqualTo(0)
        assertThat(output.writeSizes.all { it <= 1_920 && it % 4 == 0 }).isTrue()
    }

    /** Chooses a quiet splice only when both stereo channel amplitudes permit it. */
    @Test
    fun catchUpPrefersAQuietPointOnlyWhenBothChannelsAreQuiet() {
        val source = ByteBuffer.allocate(12_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(12_000) { frame ->
            source.putShort((frame + 1_000).toShort())
            source.putShort((-frame - 1_000).toShort())
        }
        source.putShort(1_280 * 4, 0)
        source.putInt(1_300 * 4, 0)
        player.start()
        player.submit(source.array())
        scheduler.runCurrent()

        val actual = output.bytes()
        assertThat(sample(actual, 2_399, 0)).isEqualTo(1_300 + 2_399 + 1_000)
        assertThat(sample(actual, 2_399, 1)).isEqualTo(-1_300 - 2_399 - 1_000)
    }

    /** Bounds pending playback across repeated bursts while retaining the newest audio. */
    @Test
    fun repeatedBurstsKeepTheLatestAudioWithinThePendingFrameBudget() {
        output.afterWrite = {
            output.afterWrite = null
            repeat(1_000) { player.submit(pcm(960)) }
            player.submit(pcm(480, left = 21_000, right = -21_000))
        }
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        scheduler.advanceBy(500)

        val actual = output.bytes()
        assertThat(actual.size / 4).isAtMost(12_000 + 480)
        assertThat(sample(actual, actual.size / 4 - 100, 0)).isEqualTo(21_000)
        assertThat(sample(actual, actual.size / 4 - 100, 1)).isEqualTo(-21_000)
    }

    /** Measures the recovery deadline from starvation instead of the next producer write. */
    @Test
    fun recoveryDeadlineStartsAtStarvationNotAtTheNextSubmission() {
        output.consumeImmediately = true
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        scheduler.advanceBy(1_000)
        player.submit(pcm(480))
        scheduler.runCurrent()
        scheduler.advanceBy(499)
        assertThat(output.startCount).isEqualTo(1)

        scheduler.advanceBy(1)
        assertThat(output.startCount).isEqualTo(2)
    }

    /** Handles a single-frame tail without dividing a fade ramp by zero. */
    @Test
    fun oneFrameAfterTimeoutIsSafelyFadedWithoutAnInvalidRampDenominator() {
        player.start()
        scheduler.runCurrent()
        scheduler.advanceBy(1_500)
        player.submit(pcm(1, left = -32_768, right = 32_767))
        scheduler.runCurrent()
        scheduler.advanceBy(5_000)

        assertThat(output.startCount).isEqualTo(1)
        assertThat(output.bytes()).isEqualTo(ByteArray(4))
    }

    /** Releases an idle prepared output when the connection is cancelled. */
    @Test
    fun stopReleasesPreparedOutputWithoutStartingIt() {
        player.start()
        scheduler.runCurrent()
        player.stop()
        scheduler.runCurrent()

        assertThat(output.startCount).isEqualTo(0)
        assertThat(output.interruptCount).isEqualTo(1)
        assertThat(output.closeCount).isEqualTo(1)
    }

    /** Closes failed output and rejects further PCM for that connection. */
    @Test
    fun outputFailureStopsAcceptingThisConnectionsPcm() {
        output.writeResult = -6
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(1)
        assertThat(output.bytes()).isEmpty()
    }

    /** Cancels queued startup before the worker can create native output. */
    @Test
    fun cancellationBeforeDispatchDoesNotOpenAnOutput() {
        player.start()
        player.stop()
        scheduler.runCurrent()

        assertThat(output.prepareCount).isEqualTo(0)
        assertThat(output.closeCount).isEqualTo(0)
    }

    /** Closes output even when device preparation does not succeed. */
    @Test
    fun failedPreparationStillReleasesItsOutput() {
        output.prepareResult = false
        player.start()
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(0)
    }

    /** Contains worker creation failure at the connection boundary. */
    @Test
    fun dispatcherCreationFailureDoesNotEscapeToTheConnectionCaller() {
        var attempts = 0
        val failingPlayer = LpcmAudioPlayer(
            nowMillis = { scheduler.nowMillis },
            createDispatcher = {
                attempts++
                throw IllegalStateException("Audio worker unavailable")
            },
            createOutput = { output },
        )
        try {
            failingPlayer.start()
            failingPlayer.submit(pcm(7_200))
            assertThat(attempts).isEqualTo(1)
            assertThat(output.prepareCount).isEqualTo(0)
        } finally {
            failingPlayer.stop()
        }
    }

    /** Closes output when the platform cannot start playback. */
    @Test
    fun failedStartReleasesItsOutput() {
        output.startResult = false
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.bytes()).isEmpty()
    }

    /** Rejects invalid native write counts without retry loops or duplicate release. */
    @Test
    fun invalidWriteResultsAbortWithoutSpinningOrReleasingTwice() {
        for ((index, result) in listOf(0, -6, 1, 20_000).withIndex()) {
            output.writeResult = result
            player.start()
            player.submit(pcm(7_200))
            scheduler.runCurrent()

            assertThat(output.closeCount).isEqualTo(index + 1)
        }
        assertThat(output.bytes()).isEmpty()
    }

    /** Records the accepted waveform and advances playback only as virtual time passes. */
    private class RecordingOutput(private val scheduler: AudioTestScheduler) : PcmOutput {
        private val recorded = ByteArrayOutputStream()
        private var playing = false
        private var played = 0L
        private var lastUpdateMillis = 0L
        var prepareCount = 0
        var startCount = 0
        var interruptCount = 0
        var closeCount = 0
        var maxWriteBytes = Int.MAX_VALUE
        var writeResult: Int? = null
        var prepareResult = true
        var startResult = true
        var consumeImmediately = false
        var underruns = 0
        var underrunFailure: RuntimeException? = null
        var afterWrite: (() -> Unit)? = null
        var requestedBlockFrames = 0
            private set
        val writeSizes = mutableListOf<Int>()

        /** @return a snapshot of all successfully accepted PCM bytes. */
        fun bytes(): ByteArray = recorded.toByteArray()

        override fun prepare(): Boolean {
            prepareCount++
            return prepareResult
        }

        override fun start(): Boolean {
            updateProgress()
            playing = true
            startCount++
            return startResult
        }

        override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
            updateProgress()
            writeResult?.let { return it }
            requestedBlockFrames = (offset + length) / 4
            val accepted = minOf(length, maxWriteBytes)
            recorded.write(pcm, offset, accepted)
            writeSizes.add(accepted)
            afterWrite?.invoke()
            return accepted
        }

        override fun playedFrames(): Long {
            updateProgress()
            if (consumeImmediately) played = recorded.size() / 4L
            return played
        }

        override fun underrunCount(): Int {
            underrunFailure?.let { throw it }
            return underruns
        }

        override fun interrupt() {
            updateProgress()
            playing = false
            interruptCount++
        }

        override fun close() {
            playing = false
            closeCount++
        }

        /** Consumes only frames that were available during the elapsed virtual interval. */
        private fun updateProgress() {
            if (playing) {
                played = minOf(recorded.size() / 4L, played + (scheduler.nowMillis - lastUpdateMillis) * 48)
            }
            lastUpdateMillis = scheduler.nowMillis
        }
    }

    private companion object {
        /** @return constant, signed stereo PCM with distinguishable channels. */
        fun pcm(frames: Int, left: Int = 10_000, right: Int = -10_000): ByteArray =
            ByteArray(frames * 4).also { bytes ->
                repeat(frames) { frame ->
                    bytes[frame * 4] = left.toByte()
                    bytes[frame * 4 + 1] = (left shr 8).toByte()
                    bytes[frame * 4 + 2] = right.toByte()
                    bytes[frame * 4 + 3] = (right shr 8).toByte()
                }
            }

        /** @return one signed little-endian channel sample from an accepted frame. */
        fun sample(pcm: ByteArray, frame: Int, channel: Int): Int {
            val offset = frame * 4 + channel * 2
            return ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
        }
    }
}
