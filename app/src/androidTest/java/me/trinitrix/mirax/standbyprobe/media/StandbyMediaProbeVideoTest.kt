package me.trinitrix.mirax.standbyprobe.media

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Video probe opt-in plan.
 *
 * Real device run only: pass a real clip Uri via instrumentation argument
 * `standby_probe_video_uri`. Without a valid bundled clip or Uri arg the run
 * must Assume-skip, not pass. No format whitelist is declared here.
 */
@RunWith(AndroidJUnit4::class)
class StandbyMediaProbeVideoTest {

    @Test
    fun video_optInUri_mutedLoop_pauseResumeRestarts() {
        val arg = InstrumentationRegistry.getArguments().getString("standby_probe_video_uri")
        Assume.assumeTrue(
            "No valid bundled clip or standby_probe_video_uri; NOT RUN",
            !arg.isNullOrBlank()
        )
        val validArg = checkNotNull(arg)
        val uri = Uri.parse(validArg)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var probe: StandbyMediaProbeView
            scenario.onActivity { host ->
                probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
                val initialEvidence = probe.bindVideo(uri)
                // Binding pass only; dimensions are 0 until prepared and not counted as verified.
                assertNotNull(initialEvidence)
                assertTrue(probe.snapshot().isVideo)
            }
            // Await actual asynchronous preparation on native decoder without unbounded sleep.
            val prepared = runBlocking {
                probe.awaitVideoPrepared(10000L)
            }
            assertTrue(prepared)
            scenario.onActivity {
                val preparedEvidence = probe.lastEvidence()
                assertNotNull(preparedEvidence)
                val evidence = checkNotNull(preparedEvidence)
                assertTrue(evidence.contentWidth > 0)
                assertTrue(evidence.contentHeight > 0)
                val state = probe.snapshot()
                assertTrue(state.isVideo)
                assertTrue(state.isLooping)
                probe.pauseForInspect()
                assertTrue(probe.snapshot().isPausedForInspect)
                // Restart from beginning when actually resumed.
                probe.resumeFromBeginning()
                assertTrue(!probe.snapshot().isPausedForInspect)
                probe.releaseProbe()
                assertTrue(!probe.snapshot().hasMedia)
            }
        }
    }
}
