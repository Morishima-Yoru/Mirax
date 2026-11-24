package me.trinitrix.mirax.standbyprobe.media

import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GIF probe: forced infinite repeat with pause and resume from beginning.
 */
@RunWith(AndroidJUnit4::class)
class StandbyMediaProbeGifTest {

    @Test
    fun gif_forcesInfiniteLoop_pauseAndResumeRestarts() {
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
        assertTrue(StandbyMediaProbeFixtures.isTrustedSize(bytes))
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var probe: StandbyMediaProbeView
            scenario.onActivity { host ->
                probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
            }
            val evidence = runBlocking {
                probe.showGif(bytes)
            }
            assertNotNull(evidence)
            val nonNullEvidence = checkNotNull(evidence)
            // Byte count is transport size only, not process memory.
            assertTrue(nonNullEvidence.sourceBytes == bytes.size)
            scenario.onActivity {
                val state = probe.snapshot()
                // Valid 2+ frame fixture must decode to animated drawable; static fallback is rejected.
                assertTrue(state.isAnimated)
                assertTrue(state.isLooping)
                assertTrue(state.isPlaying)
                probe.pauseForInspect()
                val pausedState = probe.snapshot()
                assertTrue(pausedState.isPausedForInspect)
                assertTrue(!pausedState.isPlaying)
                probe.resumeFromBeginning()
                val resumedState = probe.snapshot()
                assertTrue(!resumedState.isPausedForInspect)
                assertTrue(resumedState.isPlaying)
                probe.releaseProbe()
                val releasedState = probe.snapshot()
                assertTrue(!releasedState.hasMedia)
                assertTrue(!releasedState.isPlaying)
            }
        }
    }
}
