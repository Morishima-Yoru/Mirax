package me.trinitrix.mirax.wfd

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

/** Checks real output configuration and progress; silence does not establish audible UX quality. */
@RunWith(AndroidJUnit4::class)
class LpcmAudioPlayerDeviceTest {
    @Test
    fun preparedOutputDoesNotPlayAndSparsePcmCanAdvanceBeforeCapacityIsFilled() {
        val output = AndroidPcmOutput()
        try {
            assertThat(output.prepare()).isTrue()
            assertThat(output.bufferCapacityFrames).isAtLeast(4_800)
            assertThat(output.startThresholdFrames).isEqualTo(1)
            assertThat(output.isPlaying).isFalse()
            assertThat(output.playedFrames()).isEqualTo(0L)

            assertThat(output.start()).isTrue()
            val sparsePcm = ByteArray(960 * 4)
            assertThat(output.write(sparsePcm, 0, sparsePcm.size)).isEqualTo(sparsePcm.size)
            assertThat(waitUntil { output.playedFrames() > 0 }).isTrue()
        } finally {
            output.interrupt()
            output.close()
        }
        assertThat(output.isPlaying).isFalse()
    }

    @Test
    fun playerUsesDedicatedWorkerAndStopReleasesItsOutput() {
        val output = AndroidPcmOutput()
        val released = CountDownLatch(1)
        val observedPriority = AtomicInteger(Int.MAX_VALUE)
        val player = LpcmAudioPlayer {
            object : PcmOutput by output {
                override fun prepare(): Boolean {
                    check(Thread.currentThread().name == "mirax-audio")
                    observedPriority.set(Process.getThreadPriority(Process.myTid()))
                    return output.prepare()
                }

                override fun close() {
                    try {
                        output.close()
                    } finally {
                        released.countDown()
                    }
                }
            }
        }
        try {
            player.start()
            repeat(20) { player.submit(ByteArray(1_920)) }
            assertThat(waitUntil { output.isPlaying && output.playedFrames() > 0 }).isTrue()
            assertThat(observedPriority.get()).isIn(
                listOf(Process.THREAD_PRIORITY_AUDIO, Process.THREAD_PRIORITY_DEFAULT),
            )
        } finally {
            player.stop()
        }
        assertThat(released.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(output.isPlaying).isFalse()
    }

    @Test
    fun interruptionBeforePreparationDoesNotOpenAnAudioTrack() {
        val output = AndroidPcmOutput()
        try {
            output.interrupt()
            assertThat(output.prepare()).isFalse()
            assertThat(output.bufferCapacityFrames).isEqualTo(0)
            assertThat(output.isPlaying).isFalse()
        } finally {
            output.close()
        }
    }

    /**
     * Waits for observable platform progress, not merely a play request or global player count.
     *
     * @param condition the device-side observation to await.
     * @return whether the condition became true within two seconds.
     */
    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(10)
        }
        return condition()
    }
}
