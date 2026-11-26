package me.trinitrix.mirax.host

import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the production platform selection. */
class SessionPlatformBindingTest {
    /** Confirms that the default binding selects the Android adapter. */
    @Test
    fun defaultBindingUsesAndroidPlatform() {
        assertTrue(SessionPlatformBinding.platform is AndroidSessionPlatform)
    }
}
