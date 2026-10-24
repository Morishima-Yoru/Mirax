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

    @Test
    fun connectionWithoutPcmDoesNotStartPlayback() {
        player.start()
        scheduler.runCurrent()
        scheduler.advanceBy(5_000)

        assertThat(output.prepareCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(0)
        assertThat(output.bytes()).isEmpty()
    }

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

    @Test
    fun cancellationBeforeDispatchDoesNotOpenAnOutput() {
        player.start()
        player.stop()
        scheduler.runCurrent()

        assertThat(output.prepareCount).isEqualTo(0)
        assertThat(output.closeCount).isEqualTo(0)
    }

    @Test
    fun failedPreparationStillReleasesItsOutput() {
        output.prepareResult = false
        player.start()
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.startCount).isEqualTo(0)
    }

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

    @Test
    fun failedStartReleasesItsOutput() {
        output.startResult = false
        player.start()
        player.submit(pcm(7_200))
        scheduler.runCurrent()

        assertThat(output.closeCount).isEqualTo(1)
        assertThat(output.bytes()).isEmpty()
    }

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
