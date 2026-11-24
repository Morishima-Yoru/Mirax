package me.trinitrix.mirax.host

import me.trinitrix.mirax.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static gate check for the trial Shizuku-only privilege policy. */
class TrialShizukuOnlyPolicyTest {
    /** Production build keeps helper socket and cached-root paths enabled. */
    @Test
    fun productionDefaultKeepsHelperPaths() {
        assertFalse(BuildConfig.TRIAL_SHIZUKU_ONLY)
        assertTrue(helperPathsAllowed())
    }

    /** Opt-in trial build disables helper socket and cached-root paths. */
    @Test
    fun trialGateDisablesHelperPaths() {
        assertFalse(helperPathsAllowed(trialShizukuOnly = true))
        assertTrue(helperPathsAllowed(trialShizukuOnly = false))
    }
}
