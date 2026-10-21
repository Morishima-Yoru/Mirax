package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Verifies PCM handoff and the playback backlog through the player interface. */
class LpcmAudioPlayerTest {
    @Test
    fun submittedPcmIsDeliveredToOutputAndStopReleasesIt() {
        val output = RecordingOutput()
        val player = LpcmAudioPlayer { output }

        player.start()
        player.submit(byteArrayOf(1, 2, 3, 4))
        assertThat(output.writeStarted.await(2, TimeUnit.SECONDS)).isTrue()
        output.resumeWrite.countDown()
        assertThat(output.lastWritten.await(2, TimeUnit.SECONDS)).isTrue()
        player.stop()

        assertThat(output.closed.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(output.written).hasSize(1)
        assertThat(output.written[0]).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun burstDoesNotLeaveMoreThan120MillisecondsOfOldPcmQueued() {
        val output = RecordingOutput(lastByte = 101)
        val player = LpcmAudioPlayer { output }

        player.start()
        player.submit(ByteArray(1920) { 1 })
        assertThat(output.writeStarted.await(2, TimeUnit.SECONDS)).isTrue()
        for (value in 2..101) {
            player.submit(ByteArray(1920) { value.toByte() })
        }
        output.resumeWrite.countDown()
        assertThat(output.lastWritten.await(2, TimeUnit.SECONDS)).isTrue()
        player.stop()

        assertThat(output.closed.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(output.written.size).isAtMost(13)
        assertThat(output.written.last()[0]).isEqualTo(101.toByte())
    }

    @Test
    fun burstOfLongPcmFramesKeepsQueuedAudioUnder120Milliseconds() {
        val output = RecordingOutput(lastByte = 101)
        val player = LpcmAudioPlayer { output }

        player.start()
        player.submit(ByteArray(3840) { 1 })
        assertThat(output.writeStarted.await(2, TimeUnit.SECONDS)).isTrue()
        for (value in 2..101) {
            player.submit(ByteArray(3840) { value.toByte() })
        }
        output.resumeWrite.countDown()
        assertThat(output.lastWritten.await(2, TimeUnit.SECONDS)).isTrue()
        player.stop()

        assertThat(output.closed.await(2, TimeUnit.SECONDS)).isTrue()
        // One 10 ms frame can be in flight in addition to 120 ms of queued PCM.
        assertThat(output.written.sumOf { it.size }).isAtMost(1920 * 13)
        assertThat(output.written.last()[0]).isEqualTo(101.toByte())
    }

    /** Records played PCM while allowing a test to hold a write in progress. */
    private class RecordingOutput(private val lastByte: Int = 1) : PcmOutput {
        val writeStarted = CountDownLatch(1)
        val resumeWrite = CountDownLatch(1)
        val lastWritten = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val written = CopyOnWriteArrayList<ByteArray>()

        override fun start(): Boolean = true

        override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
            writeStarted.countDown()
            if (!resumeWrite.await(2, TimeUnit.SECONDS)) return -1
            written.add(pcm.copyOfRange(offset, offset + length))
            if (pcm[offset].toInt() == lastByte) lastWritten.countDown()
            return length
        }

        override fun close() {
            closed.countDown()
        }
    }
}
