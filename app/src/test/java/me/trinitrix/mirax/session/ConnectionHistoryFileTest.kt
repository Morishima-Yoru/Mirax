package me.trinitrix.mirax.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Checks real DataStore files across closed and recreated history/storage scopes. */
class ConnectionHistoryFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** A completed run survives reopening, and a persisted clear remains empty after reopening. */
    @Test
    fun completed_run_and_clear_survive_reopening_real_datastore(): Unit = runBlocking {
        val file = File(temporaryFolder.root, "connection-history.preferences_pb")
        val key = stringPreferencesKey("connection_runs")
        repeat(3) { generation ->
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                val history: ConnectionHistory = ConnectionHistoryImpl(store, scope)
                when (generation) {
                    0 -> {
                        history.start("persisted-source", emptyList())
                        history.finish(true, emptyList())
                        withTimeout(20_000) {
                            store.data.first { it[key]?.contains("persisted-source") == true }
                        }
                    }
                    1 -> {
                        withTimeout(20_000) {
                            while (history.runs().isEmpty()) delay(10)
                        }
                        assertThat(history.runs().map { it.remoteHost }).containsExactly("persisted-source")
                        history.clear()
                        withTimeout(20_000) {
                            store.data.onEach { println("clear persisted JSON: ${it[key]}") }
                                .first { it[key] == "[]" }
                        }
                    }
                    else -> {
                        assertThat(withTimeout(20_000) { store.data.first()[key] }).isEqualTo("[]")
                        history.start("after-clear", emptyList())
                        history.finish(true, emptyList())
                        withTimeout(20_000) {
                            store.data.first { it[key]?.contains("after-clear") == true }
                        }
                        assertThat(history.runs().map { it.remoteHost }).containsExactly("after-clear")
                    }
                }
            } finally {
                job.cancelAndJoin()
            }
        }
    }
}
