package me.trinitrix.mirax.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun clear(): Completable
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

    // In-memory state
    private var openRun: OpenConnectionRun? = null
    private val runs: MutableList<ConnectionRun> = mutableListOf()

    companion object {
        private const val MAX_RUNS = 500
        private const val MAX_LOG_LINES = 200
        private const val RUNS_KEY = "connection_runs"
        @JvmStatic
        fun getRunsType(): java.lang.reflect.Type = object : TypeToken<List<ConnectionRun>>() {}.type
    }

    init {
        loadRuns()
    }

    private fun loadRuns() {
        coroutineScope.launch(Dispatchers.IO) {
            val flow: kotlinx.coroutines.flow.Flow<Preferences> = dataStore.data
            val prefs: Preferences = flow.first()
            val json = prefs[stringPreferencesKey("connection_runs")] ?: ""
            if (json.isNotBlank()) {
                val loadedRuns = gson.fromJson<List<ConnectionRun>>(json, Companion.getRunsType())
                runs.clear()
                runs.addAll(loadedRuns)
            }
        }
    }

    private fun persistRuns() {
        coroutineScope.launch(Dispatchers.IO) {
            val json = gson.toJson(runs)
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection_runs")] = json
            }
        }
    }

    private class OpenConnectionRun(
        val remoteHost: String,
        val startedAtEpochMs: Long,
        val configuration: List<ConnectionRunFact>,
        val lines: MutableList<String> = mutableListOf(),
    )

    override fun start(host: String, configuration: List<ConnectionRunFact>) {
        if (openRun != null) {
            finish(succeeded = false, metadata = listOf(ConnectionRunFact("outcome", "closed by a newer attempt")))
        }
        openRun = OpenConnectionRun(
            remoteHost = host,
            startedAtEpochMs = System.currentTimeMillis(),
            configuration = configuration,
        )
    }

    override fun log(line: String) {
        val run = openRun ?: return
        val text = line.trim()
        if (text.isEmpty()) return
        if (run.lines.size >= MAX_LOG_LINES) {
            run.lines.removeAt(0)
        }
        run.lines.add(text)
    }

    override fun finish(succeeded: Boolean, metadata: List<ConnectionRunFact>) {
        val run = openRun ?: return
        openRun = null
        val finishedRun = ConnectionRun(
            succeeded = succeeded,
            startedAtEpochMs = run.startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            remoteHost = run.remoteHost,
            metadata = metadata,
            configuration = run.configuration,
            log = run.lines.joinToString("\n"),
        )
        runs.add(0, finishedRun)
        trimToLimit()
        persistRuns()
    }

    private fun trimToLimit() {
        while (runs.size > MAX_RUNS) {
            runs.removeLast()
        }
    }

    override fun runs(): List<ConnectionRun> = runs.toList()

    override fun activeRun(): ConnectionRun? {
        return openRun?.let {
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

    override fun currentHandshakeLog(): String = openRun?.lines?.joinToString("\n") ?: ""

    override fun clear(): Completable {
        return Completable.fromAction {
            runs.clear()
            persistRuns()
        }.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
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