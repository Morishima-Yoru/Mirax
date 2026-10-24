package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/** Checks real worker cancellation and output ownership without touching Android hardware. */
class LpcmAudioPlayerLifecycleTest {
    @Test
    fun stopInterruptsBlockedWriteAndReleasesExactlyOnce() {
        val output = BlockingOutput()
        val player = LpcmAudioPlayer { output }
        try {
            player.start()
            player.submit(ByteArray(28_800))
            assertThat(output.writeEntered.await(5, TimeUnit.SECONDS)).isTrue()
            repeat(1_000) { player.submit(ByteArray(3_840)) }

            player.stop()
            assertThat(output.closed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(output.interruptCount.get()).isEqualTo(1)
            assertThat(output.closeCount.get()).isEqualTo(1)
        } finally {
            output.allowClose.countDown()
            player.stop()
        }
    }

    @Test
    fun cancelledIntermediateConnectionCannotBypassTheOldOutputsRelease() {
        val first = BlockingOutput(holdClose = true)
        val next = BlockingOutput()
        val created = AtomicInteger()
        val openedWhileOldWasLive = AtomicInteger()
        val player = LpcmAudioPlayer {
            if (created.getAndIncrement() == 0) first else {
                if (first.closed.count != 0L) openedWhileOldWasLive.incrementAndGet()
                next
            }
        }
        try {
            player.start()
            player.submit(ByteArray(28_800))
            assertThat(first.writeEntered.await(5, TimeUnit.SECONDS)).isTrue()
            player.stop()
            assertThat(first.closeEntered.await(5, TimeUnit.SECONDS)).isTrue()

            player.start()
            player.submit(ByteArray(28_800) { 1 })
            player.stop()
            player.start()
            player.submit(ByteArray(28_800) { 2 })
            first.allowClose.countDown()

            assertThat(next.writeEntered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(openedWhileOldWasLive.get()).isEqualTo(0)
            assertThat(created.get()).isEqualTo(2)
            assertThat(next.firstByte).isEqualTo(0)
            assertThat(next.probeSample).isEqualTo(64)
        } finally {
            first.allowClose.countDown()
            player.stop()
        }
        assertThat(next.closed.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(first.closeCount.get()).isEqualTo(1)
        assertThat(next.closeCount.get()).isEqualTo(1)
    }

    /** Native-write surrogate whose cancellation and release are explicitly observable. */
    private class BlockingOutput(holdClose: Boolean = false) : PcmOutput {
        val writeEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val allowClose = CountDownLatch(if (holdClose) 1 else 0)
        val interruptCount = AtomicInteger()
        val closeCount = AtomicInteger()
        private val interrupted = CountDownLatch(1)
        @Volatile var firstByte: Byte = 0
        @Volatile var probeSample = 0

        override fun prepare(): Boolean = true

        override fun start(): Boolean = true

        override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
            firstByte = pcm[offset]
            val probeOffset = offset + 300 * 4
            probeSample = ((pcm[probeOffset].toInt() and 0xff) or
                (pcm[probeOffset + 1].toInt() shl 8)).toShort().toInt()
            writeEntered.countDown()
            check(interrupted.await(5, TimeUnit.SECONDS)) { "Stop did not interrupt the PCM write" }
            return -1
        }

        override fun playedFrames(): Long = 0

        override fun interrupt() {
            interruptCount.incrementAndGet()
            interrupted.countDown()
        }

        override fun close() {
            closeEntered.countDown()
            check(allowClose.await(5, TimeUnit.SECONDS)) { "Test did not release the close barrier" }
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }
}
