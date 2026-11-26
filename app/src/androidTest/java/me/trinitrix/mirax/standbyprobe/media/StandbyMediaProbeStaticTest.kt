package me.trinitrix.mirax.standbyprobe.media

import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Static PNG probe: decodes trusted bytes on a real attached view.
 */
@RunWith(AndroidJUnit4::class)
class StandbyMediaProbeStaticTest {

    @Test
    fun staticPng_showsUnderLegibleForeground_andReleases() {
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT)
        // Probe guard only; not a product limit.
        assertTrue(StandbyMediaProbeFixtures.isTrustedSize(bytes))
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var probe: StandbyMediaProbeView
            scenario.onActivity { host ->
                probe = StandbyMediaProbeView(host)
                host.contentRoot.removeAllViews()
                host.contentRoot.addView(probe)
            }
            val evidence = runBlocking {
                probe.showStaticImage(bytes)
            }
            assertNotNull(evidence)
            val nonNullEvidence = checkNotNull(evidence)
            assertTrue(nonNullEvidence.contentWidth > 0)
            assertTrue(nonNullEvidence.contentHeight > 0)
            // Byte count is transport size only, not process memory.
            assertEquals(bytes.size, nonNullEvidence.sourceBytes)
            scenario.onActivity {
                val state = probe.snapshot()
                assertTrue(state.hasMedia)
                assertTrue(state.isAttachedToWindow)
                // Foreground diagnostic stays observable over media.
                assertTrue(probe.diagnosticText().isNotEmpty())
                probe.pauseForInspect()
                assertTrue(probe.snapshot().isPausedForInspect)
                probe.resumeFromBeginning()
                probe.releaseProbe()
                assertTrue(!probe.snapshot().hasMedia)
            }
        }
    }
}
