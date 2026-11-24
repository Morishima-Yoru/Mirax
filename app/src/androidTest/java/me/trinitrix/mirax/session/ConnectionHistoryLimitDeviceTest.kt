package me.trinitrix.mirax.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Limit check for [ConnectionHistoryImpl] without touching real persistence.
 * Uses a cancelled scope plus a no-IO store, so load and persist never run.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionHistoryLimitDeviceTest {

    @Test
    fun fiveHundredOneRuns_keepsNewestDropsOldest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        val store = NoIoStore()
        val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)

        for (index in 0..500) {
            history.start("host-$index", emptyList())
            history.finish(true, emptyList())
        }

        val runs = history.runs()
        assertEquals(500, runs.size)
        assertEquals("host-500", runs.first().remoteHost)
        assertTrue(runs.none { it.remoteHost == "host-0" })
        assertEquals(0, store.accesses)
    }

    /** DataStore boundary that fails on any real IO attempt. */
    private class NoIoStore : DataStore<Preferences> {
        var accesses = 0
            private set

        override val data: Flow<Preferences>
            get() {
                accesses++
                return flow {
                    throw AssertionError("DataStore read blocked")
                }
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            accesses++
            throw AssertionError("DataStore write blocked")
        }
    }
}
