package me.trinitrix.mirax.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Checks connection history through its public interface with controlled DataStore timing. */
class ConnectionHistoryImplTest {
    private val key = stringPreferencesKey("connection_runs")
    private val oldRun = ConnectionRun(true, 1, 2, "old", emptyList(), emptyList(), "")

    @Test
    fun finish_before_load_keeps_old_and_new_runs_after_restart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf(key to Gson().toJson(listOf(oldRun))))
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            history.start("new", emptyList())
            history.finish(true, emptyList())
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new")

            store.allowRead.complete(Unit)
            await { history.runs().size == 2 }
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new", "old").inOrder()
            await { store.savedHosts() == listOf("new", "old") }
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { restarted.runs().size == 2 }
            assertThat(restarted.runs().map { it.remoteHost }).containsExactly("new", "old").inOrder()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun transient_read_failure_recovers_and_preserves_old_and_new_runs(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf(key to Gson().toJson(listOf(oldRun))))
            store.failNextRead = true
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            history.start("new", emptyList())
            history.finish(true, emptyList())
            store.allowRead.complete(Unit)

            await { store.savedHosts() == listOf("new", "old") }
            assertThat(store.readAttempts.get()).isAtLeast(2)
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { restarted.runs().size == 2 }
            assertThat(restarted.runs().map { it.remoteHost }).containsExactly("new", "old").inOrder()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun transient_read_failure_does_not_resurrect_cleared_runs(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf(key to Gson().toJson(listOf(oldRun))))
            store.failNextRead = true
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            history.clear()
            store.allowRead.complete(Unit)

            await { store.completedWrites.get() > 0 && store.savedHosts().isEmpty() }
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            restarted.start("after-clear", emptyList())
            restarted.finish(true, emptyList())
            await { store.savedHosts() == listOf("after-clear") }
            assertThat(restarted.runs().map { it.remoteHost }).containsExactly("after-clear")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clear_before_load_stays_empty_after_restart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf(key to Gson().toJson(listOf(oldRun))))
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            history.clear()
            store.allowRead.complete(Unit)
            await { store.savedHosts().isEmpty() }
            assertThat(history.runs()).isEmpty()
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { restarted.runs().isEmpty() }
            assertThat(restarted.runs()).isEmpty()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clear_while_previous_write_is_pending_stays_empty_after_restart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf(key to Gson().toJson(listOf(oldRun))))
            store.allowRead.complete(Unit)
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { history.runs().map { it.remoteHost } == listOf("old") }
            store.pauseNextWrite = true
            history.start("new", emptyList())
            history.finish(true, emptyList())
            withTimeout(20_000) { store.writeStarted.await() }
            history.clear()
            assertThat(history.runs()).isEmpty()
            store.allowWrite.complete(Unit)
            await { store.completedWrites.get() >= 2 && store.savedHosts().isEmpty() }
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { restarted.runs().isEmpty() }
            assertThat(restarted.runs()).isEmpty()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun more_than_five_hundred_runs_keeps_newest_after_restart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = GatedStore(mutablePreferencesOf())
            store.allowRead.complete(Unit)
            val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            for (index in 0..500) {
                history.start("host-$index", emptyList())
                history.finish(true, emptyList())
            }
            assertThat(history.runs()).hasSize(500)
            assertThat(history.runs().first().remoteHost).isEqualTo("host-500")
            await { store.savedHosts().firstOrNull() == "host-500" && store.savedHosts().size == 500 }
            val restarted: ConnectionHistory = ConnectionHistoryImpl(store, scope)
            await { restarted.runs().size == 500 }
            assertThat(restarted.runs().map { it.remoteHost }).doesNotContain("host-0")
            assertThat(restarted.runs().first().remoteHost).isEqualTo("host-500")
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(10)
        }
    }

    /** DataStore boundary with a paused initial read and serialized updates. */
    private class GatedStore(initial: Preferences) : DataStore<Preferences> {
        val allowRead = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val completedWrites = AtomicInteger()
        val readAttempts = AtomicInteger()
        @Volatile var failNextRead = false
        @Volatile var pauseNextWrite = false
        private val stored = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = flow {
            withTimeout(20_000) { allowRead.await() }
            readAttempts.incrementAndGet()
            if (failNextRead) {
                failNextRead = false
                throw IOException("transient read failure")
            }
            emitAll(stored)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                if (pauseNextWrite) {
                    pauseNextWrite = false
                    writeStarted.complete(Unit)
                    withTimeout(20_000) { allowWrite.await() }
                }
                transform(stored.value).also {
                    stored.value = it
                    completedWrites.incrementAndGet()
                }
            }

        fun savedHosts(): List<String> {
            val json = stored.value[stringPreferencesKey("connection_runs")] ?: return emptyList()
            return Gson().fromJson<List<ConnectionRun>>(json, ConnectionHistoryImpl.getRunsType())
                .map { it.remoteHost }
        }
    }
}
