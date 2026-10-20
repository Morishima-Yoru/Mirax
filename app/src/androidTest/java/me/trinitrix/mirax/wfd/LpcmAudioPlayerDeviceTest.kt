package me.trinitrix.mirax.wfd

import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the real Android audio output when PCM is submitted and stopped. */
@RunWith(AndroidJUnit4::class)
class LpcmAudioPlayerDeviceTest {
    @Test
    fun submittedPcmStartsDevicePlaybackAndStopReleasesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val player = LpcmAudioPlayer()
        val initialPlaybackCount = audioManager.activePlaybackConfigurations.size
        try {
            player.start()
            repeat(20) { player.submit(ByteArray(1920)) }

            assertThat(waitForPlayback(audioManager, initialPlaybackCount, active = true)).isTrue()
        } finally {
            player.stop()
        }
        assertThat(waitForPlayback(audioManager, initialPlaybackCount, active = false)).isTrue()
    }

    /**
     * Waits for the player's playback configuration to appear or disappear.
     *
     * @param audioManager the device's media playback manager.
     * @param initialCount count of active playback configurations before starting the player.
     * @param active whether the expected state is playing.
     * @return true if the expected playback state is observed within two seconds.
     */
    private fun waitForPlayback(audioManager: AudioManager, initialCount: Int, active: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val playing = audioManager.activePlaybackConfigurations.size > initialCount
            if (playing == active) return true
            SystemClock.sleep(20)
        }
        return false
    }
}
