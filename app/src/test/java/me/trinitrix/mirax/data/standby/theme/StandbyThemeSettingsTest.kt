package me.trinitrix.mirax.data.standby.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [StandbyThemeId] and [StandbyThemeSettings].
 */
class StandbyThemeSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    /** An I/O failure that coroutine stack-trace recovery cannot copy. */
    private class ReadFailure(id: Int) : IOException("unreadable-$id")

    /** Minimal serialized in-memory [DataStore] fake. */
    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val updateMutex = Mutex()

        @Volatile var failReads = false
        @Volatile var updateCount = 0
            private set
        val readFailure = ReadFailure(1)

        override val data: Flow<Preferences>
            get() = if (failReads) {
                kotlinx.coroutines.flow.flow { throw readFailure }
            } else {
                state
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return updateMutex.withLock {
                updateCount++
                val next = transform(state.value)
                state.value = next
                next
            }
        }

        fun raw(): String? = state.value[StandbyThemeSettings.KEY_THEME_ID]

        fun seedRaw(raw: String?) {
            state.value = if (raw == null) {
                emptyPreferences()
            } else {
                mutablePreferencesOf(StandbyThemeSettings.KEY_THEME_ID to raw)
            }
        }
    }

    @Test
    fun storageValuesAreStableAndNeverDisplayStrings() {
        assertThat(StandbyThemeId.LANDSCAPE.storageValue).isEqualTo("landscape")
        assertThat(StandbyThemeId.MATTER.storageValue).isEqualTo("matter")
        assertThat(StandbyThemeId.HARDLINE.storageValue).isEqualTo("hardline")
    }

    @Test
    fun unsetReadsMatterDefault() {
        val fake = FakePreferencesDataStore()
        val settings = StandbyThemeSettings(fake)
        assertThat(runBlocking { settings.current() }).isEqualTo(StandbyThemeId.MATTER)
        assertThat(runBlocking { settings.themeFlow.first() }).isEqualTo(StandbyThemeId.MATTER)
        assertThat(fake.raw()).isNull()
        assertThat(fake.updateCount).isEqualTo(0)
    }

    @Test
    fun roundTripPersistsStableIdentity() {
        val fake = FakePreferencesDataStore()
        val settings = StandbyThemeSettings(fake)
        runBlocking { settings.setTheme(StandbyThemeId.HARDLINE) }
        assertThat(fake.raw()).isEqualTo("hardline")
        assertThat(runBlocking { settings.current() }).isEqualTo(StandbyThemeId.HARDLINE)
        runBlocking { settings.setTheme(StandbyThemeId.LANDSCAPE) }
        assertThat(runBlocking { settings.current() }).isEqualTo(StandbyThemeId.LANDSCAPE)
    }

    @Test
    fun unknownValueFallsBackWithoutOverwrite() {
        val fake = FakePreferencesDataStore()
        fake.seedRaw("neon-future")
        val settings = StandbyThemeSettings(fake)
        assertThat(runBlocking { settings.current() }).isEqualTo(StandbyThemeId.MATTER)
        assertThat(runBlocking { settings.themeFlow.first() }).isEqualTo(StandbyThemeId.MATTER)
        assertThat(fake.raw()).isEqualTo("neon-future")
        assertThat(fake.updateCount).isEqualTo(0)
    }

    @Test
    fun unreadableStorePropagatesFailure() {
        val fake = FakePreferencesDataStore()
        fake.failReads = true
        val settings = StandbyThemeSettings(fake)
        org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking { settings.current() }
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking { settings.themeFlow.first() }
        }
    }

    @Test
    fun restartRetainsChoice() {
        val fake = FakePreferencesDataStore()
        runBlocking { StandbyThemeSettings(fake).setTheme(StandbyThemeId.LANDSCAPE) }
        val restarted = StandbyThemeSettings(fake)
        assertThat(runBlocking { restarted.current() }).isEqualTo(StandbyThemeId.LANDSCAPE)
    }

    /**
     * Proves `current()` rethrows cancellation instead of mapping it to
     * MATTER: the fake never completes, so only cancellation can terminate
     * the call, and the caught instance must be the caller's job signal.
     */
    @Test
    fun currentPropagatesCancellation() {
        val hanging = object : DataStore<Preferences> by FakePreferencesDataStore() {
            override val data: Flow<Preferences>
                get() = flow {
                    kotlinx.coroutines.currentCoroutineContext()
                        .ensureActive()
                    kotlinx.coroutines.delay(Long.MAX_VALUE)
                }
        }
        val job = kotlinx.coroutines.Job()
        var thrown: Throwable? = null
        try {
            runBlocking(job) {
                val attempt = async { StandbyThemeSettings(hanging).current() }
                job.cancel(CancellationException("test cancel"))
                attempt.await()
            }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(thrown?.message).isEqualTo("test cancel")
    }

    @Test
    fun themeFlowPropagatesCancellation() {
        val fake = object : DataStore<Preferences> by FakePreferencesDataStore() {
            override val data: Flow<Preferences>
                get() = kotlinx.coroutines.flow.flow {
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }
        }
        var thrown: Throwable? = null
        try {
            runBlocking { StandbyThemeSettings(fake).themeFlow.first() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
    }

    @Test
    fun unreadablePropagatesOriginalFailureWithoutEmissionOrWrite() {
        val fake = FakePreferencesDataStore()
        fake.seedRaw("neon-future")
        fake.failReads = true
        val settings = StandbyThemeSettings(fake)
        val emitted = mutableListOf<StandbyThemeId>()

        val currentFailure = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking { settings.current() }
        }
        val flowFailure = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking { settings.themeFlow.onEach { emitted.add(it) }.first() }
        }
        assertThat(currentFailure).isSameInstanceAs(fake.readFailure)
        assertThat(flowFailure).isSameInstanceAs(fake.readFailure)
        assertThat(emitted).isEmpty()
        assertThat(fake.raw()).isEqualTo("neon-future")
        assertThat(fake.updateCount).isEqualTo(0)
    }

    /** Verifies stable identities survive real preferences-file reopening, not Android process recreation. */
    @Test
    fun realPreferencesFileReopenRetainsEachStableThemeIdentity() = runBlocking {
        val file = folder.newFile("theme.preferences_pb")
        for (id in listOf(StandbyThemeId.HARDLINE, StandbyThemeId.LANDSCAPE, StandbyThemeId.MATTER)) {
            val writerJob = SupervisorJob()
            try {
                val writer = PreferenceDataStoreFactory.create(scope = CoroutineScope(writerJob + Dispatchers.IO)) { file }
                StandbyThemeSettings(writer).setTheme(id)
            } finally {
                withContext(NonCancellable) { writerJob.cancelAndJoin() }
            }
            val readerJob = SupervisorJob()
            try {
                val reader = PreferenceDataStoreFactory.create(scope = CoroutineScope(readerJob + Dispatchers.IO)) { file }
                assertThat(StandbyThemeSettings(reader).current()).isEqualTo(id)
                assertThat(reader.data.first()[StandbyThemeSettings.KEY_THEME_ID]).isEqualTo(id.storageValue)
            } finally {
                withContext(NonCancellable) { readerJob.cancelAndJoin() }
            }
        }
    }
}
