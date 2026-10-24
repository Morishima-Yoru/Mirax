package me.trinitrix.mirax.session

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ConnectionHistory interface defining the contract for connection run storage.
 * Deep module: exposes a small interface hiding complex persistence logic.
 */
interface ConnectionHistory {
    /** Starts a run for [host] with its [configuration]. */
    fun start(host: String, configuration: List<ConnectionRunFact>)

    /** Adds [line] to the current run. */
    fun log(line: String)

    /** Finishes the current run with [succeeded] and [metadata]. */
    fun finish(succeeded: Boolean, metadata: List<ConnectionRunFact>)

    /** Returns finished runs, newest first. */
    fun runs(): List<ConnectionRun>

    /** Returns the active run, if any. */
    fun activeRun(): ConnectionRun?

    /** Returns the active handshake log. */
    fun currentHandshakeLog(): String

    /** Clears finished runs and schedules their persistence. */
    fun clear()
}

/**
 * ConnectionHistory implementation with DataStore persistence.
 * - 500 runs limit
 * - DataStore JSON persistence
 * - Clear API for advanced settings
 */
class ConnectionHistoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val coroutineScope: CoroutineScope,
) : ConnectionHistory {

    private val gson = Gson()

    private val stateLock = Any()
    private var openRun: OpenConnectionRun? = null
    private val runs: MutableList<ConnectionRun> = mutableListOf()
    private var loaded = false
    private var changedBeforeLoad = false
    private var clearedBeforeLoad = false
    private val pendingWrites = Channel<List<ConnectionRun>>(Channel.CONFLATED)

    companion object {
        private const val TAG = "ConnectionHistory"
        private const val MAX_RUNS = 500
        private const val MAX_LOG_LINES = 200
        private const val RUNS_KEY = "connection_runs"
        @JvmStatic
        fun getRunsType(): java.lang.reflect.Type = object : TypeToken<List<ConnectionRun>>() {}.type
    }

    init {
        loadRuns()
        coroutineScope.launch(Dispatchers.IO) {
            for (snapshot in pendingWrites) {
                try {
                    val json = gson.toJson(snapshot)
                    dataStore.edit { prefs ->
                        prefs[stringPreferencesKey(RUNS_KEY)] = json
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    Log.w(TAG, "Failed to persist connection history", err)
                }
            }
        }
    }

    private fun loadRuns() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val json = dataStore.data.first()[stringPreferencesKey(RUNS_KEY)] ?: ""
                val saved = if (json.isBlank()) emptyList() else
                    gson.fromJson<List<ConnectionRun>>(json, getRunsType()) ?: emptyList()
                synchronized(stateLock) {
                    if (!clearedBeforeLoad) {
                        runs.addAll(saved)
                        trimToLimit()
                    }
                    loaded = true
                    if (changedBeforeLoad) persistRuns()
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                Log.w(TAG, "Failed to load connection history", err)
                // Never replace unreadable saved history with a partial in-memory list.
            }
        }
    }

    private fun persistRuns() {
        if (!loaded) {
            changedBeforeLoad = true
            return
        }
        pendingWrites.trySend(runs.toList())
    }

    private class OpenConnectionRun(
        val remoteHost: String,
        val startedAtEpochMs: Long,
        val configuration: List<ConnectionRunFact>,
        val lines: MutableList<String> = mutableListOf(),
    )

    override fun start(host: String, configuration: List<ConnectionRunFact>) = synchronized(stateLock) {
        if (openRun != null) {
            finish(succeeded = false, metadata = listOf(ConnectionRunFact("outcome", "closed by a newer attempt")))
        }
        openRun = OpenConnectionRun(
            remoteHost = host,
            startedAtEpochMs = System.currentTimeMillis(),
            configuration = configuration.toList(),
        )
    }

    override fun log(line: String) = synchronized(stateLock) {
        val run = openRun ?: return@synchronized
        val text = line.trim()
        if (text.isEmpty()) return@synchronized
        if (run.lines.size >= MAX_LOG_LINES) {
            run.lines.removeAt(0)
        }
        run.lines.add(text)
    }

    override fun finish(succeeded: Boolean, metadata: List<ConnectionRunFact>) = synchronized(stateLock) {
        val run = openRun ?: return@synchronized
        openRun = null
        val finishedRun = ConnectionRun(
            succeeded = succeeded,
            startedAtEpochMs = run.startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            remoteHost = run.remoteHost,
            metadata = metadata.toList(),
            configuration = run.configuration,
            log = run.lines.joinToString("\n"),
        )
        runs.add(0, finishedRun)
        trimToLimit()
        persistRuns()
    }

    private fun trimToLimit() {
        while (runs.size > MAX_RUNS) {
            runs.removeAt(runs.lastIndex)
        }
    }

    override fun runs(): List<ConnectionRun> = synchronized(stateLock) { runs.toList() }

    override fun activeRun(): ConnectionRun? = synchronized(stateLock) {
        openRun?.let {
            ConnectionRun(
                succeeded = false,
                startedAtEpochMs = it.startedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                remoteHost = it.remoteHost,
                metadata = emptyList(),
                configuration = it.configuration,
                log = it.lines.joinToString("\n"),
            )
        }
    }

    override fun currentHandshakeLog(): String = synchronized(stateLock) {
        openRun?.lines?.joinToString("\n") ?: ""
    }

    /** Clear finished runs now and schedule their DataStore update. */
    override fun clear() = synchronized(stateLock) {
        runs.clear()
        if (!loaded) clearedBeforeLoad = true
        persistRuns()
    }
}

/**
 * Factory for creating ConnectionHistory implementation with DataStore.
 */
class ConnectionHistoryFactory(
    private val dataStore: DataStore<Preferences>,
    private val coroutineScope: CoroutineScope,
) {
    fun create(): ConnectionHistory {
        return ConnectionHistoryImpl(dataStore, coroutineScope)
    }
}
