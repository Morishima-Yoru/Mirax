package me.trinitrix.mirax.standbyprobe.media

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for probe edge cases:
 * - Bad payload rejection without crash or unhandled exception.
 * - Idempotent release calls.
 * - Media replacement (video replaced by static image clears stale surfaces).
 * - Paused prepare does not auto-start playback upon completion.
 * - Activity stop / window hidden pauses actual resource without detach.
 */
@RunWith(AndroidJUnit4::class)
class StandbyMediaProbeRegressionTest {

    @Test
    fun badPayload_rejectedSafelyWithDiagnostic() {
        val badBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var probe: StandbyMediaProbeView
            scenario.onActivity { host ->
                probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
            }
            val result = runBlocking {
                probe.showStaticImage(badBytes)
            }
            assertNull(result)
            scenario.onActivity {
                assertTrue(probe.diagnosticText().isNotEmpty())
                assertTrue(!probe.snapshot().hasMedia)
            }
        }
    }

    @Test
    fun releaseProbe_isIdempotent() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
                // Repeated release must never throw or leak state.
                probe.releaseProbe()
                probe.releaseProbe()
                probe.releaseProbe()
                val state = probe.snapshot()
                assertTrue(!state.hasMedia)
                assertTrue(!state.isPlaying)
            }
        }
    }

    @Test
    fun pendingInvalidBindingReplacement_staticImageSafelyReplacesVideo() {
        // Pending invalid video URI replacement check only; not valid video decode proof.
        val pngBytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var probe: StandbyMediaProbeView
            scenario.onActivity { host ->
                probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
                // Bind video first.
                probe.bindVideo(Uri.parse("android.resource://me.trinitrix.mirax/raw/fake_clip"))
                assertTrue(probe.snapshot().isVideo)
            }
            // Now show static image over it.
            val evidence = runBlocking {
                probe.showStaticImage(pngBytes)
            }
            assertNotNull(evidence)
            scenario.onActivity {
                val state = probe.snapshot()
                assertTrue(state.hasMedia)
                assertTrue(!state.isVideo)
                probe.releaseProbe()
            }
        }
    }

    @Test
    fun activityStop_pausesAnimationWithoutDetach() {
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
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
            scenario.onActivity {
                assertTrue(probe.snapshot().isPlaying)
            }
            // Moving activity to CREATED stops the activity and hides the window.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                // Actual resource must be paused, but view remains attached.
                assertTrue(probe.snapshot().isAttachedToWindow)
                assertTrue(!probe.snapshot().isPlaying)
            }
        }
    }

    @Test
    fun pausedPrepare_preventsAutoStart() {
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
                probe.bindVideo(uri)
                // Immediately pause while prepare is still asynchronous in flight.
                probe.pauseForInspect()
                assertTrue(probe.snapshot().isPausedForInspect)
            }
            val prepared = runBlocking {
                probe.awaitVideoPrepared(10000L)
            }
            assertTrue(prepared)
            scenario.onActivity {
                // Must remain paused, not playing.
                assertTrue(!probe.snapshot().isPlaying)
                probe.releaseProbe()
            }
        }
    }
}
