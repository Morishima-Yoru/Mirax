package me.trinitrix.mirax.session

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Clears finished runs without changing the active run.
     *
     * @return A cold [Completable] that serializes the clear with other writes on subscription.
     * Completion means the clear was persisted. If the clear fails, it rolls back in memory
     * before signaling the error to the subscriber.
     */
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
    private val stateLock = ReentrantLock()
    private val persistMutex = Mutex()
    private val runsKey = stringPreferencesKey(RUNS_KEY)
    // Non-clear writes wait for credible initial read (including empty store).
    // Read/parse failures mark not-credible and drop the write instead of
    // blind-writing unknown old data; a later finish triggers a guarded retry.
    // shortcut: retry is best-effort single-flight, upgrade when transient recovery needs backoff.
    private var initialLoadGate = CompletableDeferred<Unit>()
    private var initialCredible = false
    private var loadRetryInFlight = false
    private var pendingClearRecovery: ClearRecovery? = null

    private data class ClearRecovery(
        val previousJson: String?,
        val captured: Boolean,
        val mergeStoredRuns: Boolean,
    )

    private fun markInitialLoadFailed(
        cause: Exception,
        generation: Long,
        gate: CompletableDeferred<Unit>,
    ) {
        stateLock.withLock {
            if (clearGeneration == generation && initialLoadGate === gate && !initialCredible) {
                loadRetryInFlight = false
                gate.completeExceptionally(cause)
            }
        }
    }

    // In-memory state, guarded by stateLock.
    private var openRun: OpenConnectionRun? = null
    private val runs: MutableList<ConnectionRun> = mutableListOf()
    // Incremented on clear under stateLock. Load drops stale payload when changed.
    private var clearGeneration = 0L
    // Incremented on every persist request under stateLock. Stale writes drop.
    private var persistRequestedVersion = 0L
    private var hasUnpersistedRuns = false
    private var pendingSnapshot: PersistSnapshot? = null
    private var persistDrainJob: Job? = null
    private var persistDrainVersion = 0L

    /** One versioned snapshot and the read gate that protects it. */
    private data class PersistSnapshot(
        val runs: List<ConnectionRun>,
        val version: Long,
        val gate: CompletableDeferred<Unit>,
        val credible: Boolean,
    )

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
    }

    private fun loadRuns() {
        val attempt = stateLock.withLock { clearGeneration to initialLoadGate }
        loadRunsAttempt(attempt.first, attempt.second)
    }

    private fun scheduleLoadRetry() {
        val attempt = stateLock.withLock {
            if (initialCredible || loadRetryInFlight || !initialLoadGate.isCompleted) {
                null
            } else {
                val gate = CompletableDeferred<Unit>()
                initialLoadGate = gate
                loadRetryInFlight = true
                clearGeneration to gate
            }
        }
        if (attempt != null) loadRunsAttempt(attempt.first, attempt.second)
    }

    private fun loadRunsAttempt(generation: Long, gate: CompletableDeferred<Unit>) {
        coroutineScope.launch {
            try {
                readAndMerge(generation, gate)
            } catch (e: CancellationException) {
                markInitialLoadFailed(e, generation, gate)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadRuns failed, keeping in-memory history", e)
                markInitialLoadFailed(e, generation, gate)
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) markInitialLoadFailed(cause, generation, gate)
        }
    }

    /**
     * Validates the supported on-disk shape before Gson can coerce values.
     * @param json Persisted history JSON.
     */
    private fun validateHistoryJson(json: String) {
        val runFields = setOf("succeeded", "startedAtEpochMs", "endedAtEpochMs", "remoteHost", "metadata", "configuration", "log")
        val factFields = setOf("label", "value")
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            reader.beginArray()
            while (reader.hasNext()) {
                validateObject(reader, runFields) { field ->
                    when (field) {
                        "succeeded" -> {
                            if (reader.peek() != JsonToken.BOOLEAN) throw IOException("Invalid history boolean")
                            reader.nextBoolean()
                        }
                        "startedAtEpochMs", "endedAtEpochMs" -> {
                            if (reader.peek() != JsonToken.NUMBER || reader.nextString().toLongOrNull() == null) {
                                throw IOException("Invalid history timestamp")
                            }
                        }
                        "remoteHost", "log" -> validateString(reader)
                        "metadata", "configuration" -> {
                            reader.beginArray()
                            while (reader.hasNext()) validateObject(reader, factFields) { validateString(reader) }
                            reader.endArray()
                        }
                    }
                }
            }
            reader.endArray()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing history data")
        }
    }

    /**
     * Rejects missing, repeated, or unsupported object fields.
     * @param reader Strict JSON reader positioned at an object.
     * @param fields Required supported field names.
     * @param validate Validates and consumes each field value.
     */
    private inline fun validateObject(reader: JsonReader, fields: Set<String>, validate: (String) -> Unit) {
        reader.beginObject()
        val remaining = fields.toMutableSet()
        while (reader.hasNext()) {
            val name = reader.nextName()
            // shortcut: unknown fields block writes, upgrade when lossless preservation is supported.
            if (!remaining.remove(name)) throw IOException("Unsupported or repeated history field: $name")
            validate(name)
        }
        reader.endObject()
        if (remaining.isNotEmpty()) throw IOException("Missing history fields: $remaining")
    }

    /**
     * Rejects null and Gson's number-to-string coercion.
     * @param reader Strict JSON reader positioned at a string value.
     */
    private fun validateString(reader: JsonReader) {
        if (reader.peek() != JsonToken.STRING) throw IOException("Invalid history string")
        reader.nextString()
    }

    private suspend fun readAndMerge(startGeneration: Long, gate: CompletableDeferred<Unit>) {
        // Do not hold persistMutex while a DataStore read is suspended.
        val prefs = try {
            dataStore.data.first()
        } catch (failure: IOException) {
            val canRetryInitialRead = stateLock.withLock {
                startGeneration == 0L && clearGeneration == startGeneration && initialLoadGate === gate &&
                    !initialCredible && !loadRetryInFlight && pendingClearRecovery == null
            }
            if (!canRetryInitialRead) throw failure
            Log.w(TAG, "Retrying initial connection history read", failure)
            currentCoroutineContext().ensureActive()
            dataStore.data.first()
        }
        val json = prefs[runsKey]
        val loadedRuns = if (json == null) {
            emptyList()
        } else {
            validateHistoryJson(json)
            gson.fromJson<List<ConnectionRun>>(json, Companion.getRunsType())
                ?: throw IOException("Connection history decoded as null")
        }
        persistMutex.withLock {
            val version = stateLock.withLock state@{
                if (clearGeneration != startGeneration || initialLoadGate !== gate || initialCredible) {
                    return@state null
                }
                val recovery = pendingClearRecovery
                // shortcut: ambiguous baselines block writes, upgrade when reliable reconciliation is available.
                if (recovery != null && (!recovery.captured || recovery.previousJson != json)) {
                    throw IOException("Cannot reconcile history after failed clear")
                }
                if (recovery == null || recovery.mergeStoredRuns) {
                    // Pending runs have not reached disk, so identical stored runs remain distinct.
                    runs.addAll(loadedRuns)
                    trimToLimitLocked()
                }
                pendingClearRecovery = null
                initialCredible = true
                loadRetryInFlight = false
                gate.complete(Unit)
                if (hasUnpersistedRuns) ++persistRequestedVersion else null
            }
            if (version != null) writeSnapshot(version)
        }
    }

    private fun requestPersist(version: Long) {
        writeSnapshot(version)
    }

    private fun writeSnapshot(version: Long) {
        val job = stateLock.withLock {
            if (version != persistRequestedVersion || (pendingSnapshot?.version ?: Long.MIN_VALUE) >= version) return
            // Capture only after rejecting stale callers, under the same version lock.
            pendingSnapshot = PersistSnapshot(runs.toList(), version, initialLoadGate, initialCredible)
            if (persistDrainJob == null) startPersistDrainLocked() else null
        }
        job?.start()
    }

    /**
     * Installs a lazy drain while holding stateLock, before its body can run.
     * @return The lazy drain job.
     */
    private fun startPersistDrainLocked(): Job {
        persistDrainVersion = checkNotNull(pendingSnapshot).version
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) { drainSnapshots() }
        persistDrainJob = job
        job.invokeOnCompletion {
            val restart = stateLock.withLock {
                if (persistDrainJob !== job) return@withLock null
                persistDrainJob = null
                val pending = pendingSnapshot
                // Only newer work may restart after completion or pre-dispatch cancellation.
                if (pending != null && pending.version > persistDrainVersion &&
                    pending.version == persistRequestedVersion &&
                    coroutineScope.coroutineContext[Job]?.isActive != false
                ) {
                    startPersistDrainLocked()
                } else {
                    null
                }
            }
            restart?.start()
        }
        return job
    }

    /** Persists one in-flight snapshot and coalesces requests received while suspended. */
    private suspend fun drainSnapshots() {
        while (true) {
            val snapshot = stateLock.withLock {
                val next = pendingSnapshot ?: return
                pendingSnapshot = null
                persistDrainVersion = next.version
                next
            }
            if (!snapshot.credible) {
                try {
                    snapshot.gate.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "persistRuns dropped: no credible read, keeping store untouched", e)
                    continue
                }
            }

            val retry = persistMutex.withLock {
                val allowed = stateLock.withLock {
                    initialCredible && pendingClearRecovery == null && snapshot.version == persistRequestedVersion
                }
                if (!allowed) return@withLock false
                try {
                    dataStore.edit { prefs ->
                        prefs[runsKey] = gson.toJson(snapshot.runs)
                    }
                    stateLock.withLock {
                        if (snapshot.version == persistRequestedVersion) hasUnpersistedRuns = false
                    }
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Retrying connection history write", e)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "persistRuns failed, keeping in-memory runs", e)
                    false
                }
            }
            if (retry) {
                // Back off outside persistMutex so a clear can invalidate this snapshot.
                delay(250)
                stateLock.withLock {
                    if (snapshot.version == persistRequestedVersion && initialCredible &&
                        pendingClearRecovery == null && pendingSnapshot == null
                    ) {
                        pendingSnapshot = snapshot
                    }
                }
            }
        }
    }

    private class OpenConnectionRun(
        val remoteHost: String,
        val startedAtEpochMs: Long,
        val configuration: List<ConnectionRunFact>,
        // Guarded by stateLock plus linesLock for iterator safety.
        val lines: MutableList<String> = mutableListOf(),
        val linesLock: ReentrantLock = ReentrantLock(),
    )

    private data class ClearState(
        val previousRuns: List<ConnectionRun>,
        val hadUnpersistedRuns: Boolean,
        val hadTrustedHistory: Boolean,
        val previousRecovery: ClearRecovery?,
        val previousGate: CompletableDeferred<Unit>,
        val clearGate: CompletableDeferred<Unit>,
    )

    override fun start(host: String, configuration: List<ConnectionRunFact>) {
        val autoVersion: Long?
        val needsRetry: Boolean
        stateLock.withLock {
            if (openRun != null) {
                finishLocked(succeeded = false, metadata = listOf(ConnectionRunFact("outcome", "closed by a newer attempt")))
                autoVersion = ++persistRequestedVersion
                hasUnpersistedRuns = true
            } else {
                autoVersion = null
            }
            openRun = OpenConnectionRun(
                remoteHost = host,
                startedAtEpochMs = System.currentTimeMillis(),
                configuration = configuration,
            )
            needsRetry = autoVersion != null && (!initialCredible || pendingClearRecovery != null)
        }
        if (needsRetry) scheduleLoadRetry()
        if (autoVersion != null) writeSnapshot(autoVersion)
    }

    override fun log(line: String) {
        stateLock.withLock {
            val run = openRun ?: return
            val text = line.trim()
            if (text.isEmpty()) return
            run.linesLock.withLock {
                if (run.lines.size >= MAX_LOG_LINES) {
                    run.lines.removeAt(0)
                }
                run.lines.add(text)
            }
        }
    }

    override fun finish(succeeded: Boolean, metadata: List<ConnectionRunFact>) {
        val version: Long
        val needsRetry: Boolean
        stateLock.withLock {
            finishLocked(succeeded, metadata) ?: return
            version = ++persistRequestedVersion
            hasUnpersistedRuns = true
            // Safe retry: blind writes stay dropped, but a later finish may re-read.
            needsRetry = !initialCredible || pendingClearRecovery != null
        }
        if (needsRetry) scheduleLoadRetry()
        requestPersist(version)
    }

    /**
     * Append one finished run under stateLock, returns it for persistence.
     * Caller must hold stateLock.
     */
    private fun finishLocked(succeeded: Boolean, metadata: List<ConnectionRunFact>): ConnectionRun? {
        val run = openRun ?: return null
        openRun = null
        val linesCopy = run.linesLock.withLock { run.lines.toList() }
        val finishedRun = ConnectionRun(
            succeeded = succeeded,
            startedAtEpochMs = run.startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            remoteHost = run.remoteHost,
            metadata = metadata,
            configuration = run.configuration,
            log = linesCopy.joinToString("\n"),
        )
        runs.add(0, finishedRun)
        trimToLimitLocked()
        return finishedRun
    }

    private fun trimToLimitLocked() {
        while (runs.size > MAX_RUNS) {
            runs.removeAt(runs.lastIndex)
        }
    }

    override fun runs(): List<ConnectionRun> = stateLock.withLock { runs.toList() }

    override fun activeRun(): ConnectionRun? {
        // Snapshot openRun under lock, then copy log lines safely.
        val snapshot: OpenConnectionRun? = stateLock.withLock { openRun }
        return snapshot?.let {
            val linesCopy = it.linesLock.withLock { it.lines.toList() }
            ConnectionRun(
                succeeded = false,
                startedAtEpochMs = it.startedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                remoteHost = it.remoteHost,
                metadata = emptyList(),
                configuration = it.configuration,
                log = linesCopy.joinToString("\n"),
            )
        }
    }

    override fun currentHandshakeLog(): String {
        val snapshot = stateLock.withLock { openRun } ?: return ""
        return snapshot.linesLock.withLock { snapshot.lines.joinToString("\n") }
    }

    override fun clear(): Completable {
        return Completable.fromAction {
            kotlinx.coroutines.runBlocking {
                persistMutex.withLock {
                    val emptyJson = gson.toJson(emptyList<ConnectionRun>())
                    // Establish the memory boundary while persistence remains gated on the edit.
                    val clearState = stateLock.withLock {
                        val state = ClearState(
                            previousRuns = runs.toList(),
                            hadUnpersistedRuns = hasUnpersistedRuns,
                            hadTrustedHistory = initialCredible,
                            previousRecovery = pendingClearRecovery,
                            previousGate = initialLoadGate,
                            clearGate = CompletableDeferred(),
                        )
                        runs.clear()
                        hasUnpersistedRuns = false
                        pendingSnapshot = null
                        clearGeneration++
                        persistRequestedVersion++
                        initialCredible = false
                        loadRetryInFlight = false
                        initialLoadGate = state.clearGate
                        state
                    }
                    var transformEntered = false
                    var captured = false
                    var previousJson: String? = null
                    suspend fun writeEmptyHistory() {
                        dataStore.edit { prefs ->
                            transformEntered = true
                            previousJson = prefs[runsKey]
                            captured = true
                            prefs[runsKey] = emptyJson
                        }
                    }
                    try {
                        try {
                            writeEmptyHistory()
                        } catch (failure: IOException) {
                            if (transformEntered) throw failure
                            Log.w(TAG, "Retrying connection history clear before transform", failure)
                            currentCoroutineContext().ensureActive()
                            writeEmptyHistory()
                        }
                        stateLock.withLock {
                            pendingClearRecovery = null
                            initialCredible = true
                            clearState.previousGate.complete(Unit)
                            clearState.clearGate.complete(Unit)
                        }
                    } catch (e: Exception) {
                        stateLock.withLock {
                            runs.addAll(clearState.previousRuns)
                            trimToLimitLocked()
                            hasUnpersistedRuns = hasUnpersistedRuns || clearState.hadUnpersistedRuns
                            persistRequestedVersion++
                            val previousRecovery = clearState.previousRecovery
                            // An already-empty store cannot reveal whether a clear committed.
                            val comparable = captured && (previousJson != emptyJson || clearState.previousRuns.isEmpty())
                            pendingClearRecovery = if (previousRecovery == null) {
                                ClearRecovery(previousJson, comparable, !clearState.hadTrustedHistory)
                            } else if (previousRecovery.captured && comparable &&
                                previousRecovery.previousJson == previousJson
                            ) {
                                previousRecovery
                            } else {
                                ClearRecovery(null, false, previousRecovery.mergeStoredRuns)
                            }
                            clearState.previousGate.completeExceptionally(e)
                            clearState.clearGate.completeExceptionally(e)
                        }
                        scheduleLoadRetry()
                        Log.e(TAG, "clear failed, history remains blocked until reconciled", e)
                        throw e
                    }
                }
            }
        }.subscribeOn(Schedulers.io())
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