package me.trinitrix.mirax.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

/**
 * Concurrency regression tests for [ConnectionHistoryImpl].
 *
 * Static risks covered (no device, no sleeps; ordering driven by latches
 * plus a manual dispatcher, bounded latch awaits only):
 * - pending load racing with begin/log/finish keeps both old and new runs;
 * - overlapping async persists honor event order, older snapshots never win;
 * - explicit clear wins over late load/write arrivals;
 * - unreadable or corrupt store never wipes in-memory history;
 * - shared-memory access stays consistent under threads.
 */
class ConnectionHistoryConcurrencyTest {

    private val gson = Gson()

    private fun sampleRun(host: String, startedAt: Long = 1_000L): ConnectionRun =
        ConnectionRun(
            succeeded = true,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + 10L,
            remoteHost = host,
            metadata = listOf(ConnectionRunFact("k", "v")),
            configuration = listOf(ConnectionRunFact("c", "1")),
            log = "hello",
        )

    /** Manual dispatcher: every launched coroutine queues until the test pumps it. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private val lock = Any()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(lock) { queue.addLast(block) }
        }

        /** Returns the number of dispatched tasks waiting to run. */
        fun queuedCount(): Int = synchronized(lock) { queue.size }

        fun pumpOne(): Boolean {
            val next = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: return false
            next.run()
            return true
        }

        fun pumpAll(maxSteps: Int = 100_000): Int {
            var steps = 0
            while (pumpOne()) {
                steps++
                check(steps <= maxSteps) { "dispatcher did not settle" }
            }
            return steps
        }
    }

    /** Controllable DataStore fake with serialized, single-transform updates. */
    private class FakeHistoryStore(
        initialJson: String? = null,
        @Volatile var readGate: CompletableDeferred<Unit>? = null,
        @Volatile var failReads: Boolean = false,
        @Volatile var failWrites: Boolean = false,
    ) : DataStore<Preferences> {
        private val gson = Gson()
        private val runsKey = stringPreferencesKey("connection_runs")
        private val stateLock = Any()
        private val updateMutex = Mutex()
        private var stored: Preferences = if (initialJson == null) {
            emptyPreferences()
        } else {
            mutablePreferencesOf(runsKey to initialJson)
        }
        val writes: MutableList<String> = mutableListOf()
        @Volatile var editCalls: Int = 0
        @Volatile var readCalls: Int = 0
        val writeArrived = CountDownLatch(1)
        val clearArrived = CountDownLatch(1)

        /** When set, the next matching edit parks until [parkGate] opens. */
        @Volatile var parkMarker: String? = null
        @Volatile var parkGate: CompletableDeferred<Unit>? = null
        @Volatile var parkClearGate: CompletableDeferred<Unit>? = null
        @Volatile var failBeforeTransform: Boolean = false
        @Volatile var cancelWrites: Boolean = false
        @Volatile var throwAfterClearCommit: Boolean = false

        override val data: Flow<Preferences> = flow {
            val current = synchronized(stateLock) {
                readCalls++
                stored
            }
            readGate?.await()
            if (failReads) throw java.io.IOException("unreadable")
            emit(current)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            updateMutex.withLock {
                val before = synchronized(stateLock) {
                    editCalls++
                    if (failBeforeTransform) throw java.io.IOException("read before transform failed")
                    stored
                }
                val next = transform(before)
                val nextJson = next[runsKey]
                val marker = parkMarker
                val gate = parkGate
                if (marker != null && gate != null && nextJson?.contains(marker) == true) {
                    parkMarker = null
                    gate.await()
                }
                val isClear = nextJson == gson.toJson(emptyList<ConnectionRun>())
                val clearGate = parkClearGate
                if (clearGate != null && isClear) {
                    parkClearGate = null
                    clearArrived.countDown()
                    clearGate.await()
                }
                synchronized(stateLock) {
                    if (cancelWrites) throw CancellationException("write cancelled")
                    if (failWrites) throw java.io.IOException("write failed")
                    stored = next
                    nextJson?.let { writes.add(it) }
                    if (writes.isNotEmpty()) writeArrived.countDown()
                }
                // Defensive fault injection only; real DataStore aborts a failed transaction.
                if (isClear && throwAfterClearCommit) throw java.io.IOException("committed then threw")
                next
            }

        fun storedJson(): String? = synchronized(stateLock) { stored[runsKey] }
    }

    private fun historyOf(store: FakeHistoryStore, dispatcher: ManualDispatcher): ConnectionHistoryImpl {
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        return ConnectionHistoryImpl(store, scope)
    }

    @Test(timeout = 30_000L)
    fun initialLoadCancelledBeforeDispatch_nextFinishMergesAndPersists() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            val initialLoad = parent.children.single()
            assertThat(initialLoad.isActive).isTrue()
            assertThat(store.readCalls).isEqualTo(0)
            initialLoad.cancel(CancellationException("cancel initial load before dispatch"))
            dispatcher.pumpAll()
            assertThat(initialLoad.isCancelled).isTrue()
            assertThat(parent.isActive).isTrue()
            assertThat(store.readCalls).isEqualTo(0)
            assertThat(store.editCalls).isEqualTo(0)

            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(store.readCalls).isEqualTo(1)
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
        } finally {
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    @Test(timeout = 30_000L)
    fun retryCancelledBeforeDispatch_nextFinishRetriesAndPersists() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)), failReads = true, failWrites = true)
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            dispatcher.pumpAll()
            assertThat(store.readCalls).isEqualTo(1)
            assertThat(parent.isActive).isTrue()

            val failedClear = history.clear().test()
            try {
                assertThat(failedClear.await(5L, TimeUnit.SECONDS)).isTrue()
                failedClear.assertError(java.io.IOException::class.java).assertNotComplete()
                val retry = parent.children.single()
                assertThat(retry.isActive).isTrue()
                assertThat(store.readCalls).isEqualTo(1)
                retry.cancel(CancellationException("cancel retry before dispatch"))
                dispatcher.pumpAll()
                assertThat(retry.isCancelled).isTrue()
                assertThat(parent.isActive).isTrue()
                assertThat(store.readCalls).isEqualTo(1)
                assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))

                store.failReads = false
                store.failWrites = false
                history.start("new-host", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                dispatcher.pumpAll()
                assertThat(store.readCalls).isEqualTo(2)
                assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
                val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
                assertThat(stored.map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
            } finally {
                failedClear.dispose()
            }
        } finally {
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    @Test(timeout = 30_000L)
    fun staleLoadCancellationAfterClear_doesNotBlockNewWrites() {
        val readGate = CompletableDeferred<Unit>()
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(sampleRun("old-host"))), readGate = readGate)
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            val initialLoad = parent.children.single()
            assertThat(store.readCalls).isEqualTo(0)
            assertThat(initialLoad.isActive).isTrue()
            val successfulClear = history.clear().test()
            try {
                assertThat(successfulClear.await(5L, TimeUnit.SECONDS)).isTrue()
                successfulClear.assertComplete().assertNoErrors()
                assertThat(store.storedJson()).isEqualTo("[]")

                initialLoad.cancel(CancellationException("cancel stale load after clear"))
                dispatcher.pumpAll()
                assertThat(initialLoad.isCancelled).isTrue()
                assertThat(parent.isActive).isTrue()
                assertThat(store.readCalls).isEqualTo(0)
                history.start("after-clear", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                dispatcher.pumpAll()
                assertThat(history.runs().map { it.remoteHost }).containsExactly("after-clear")
                val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
                assertThat(stored.map { it.remoteHost }).containsExactly("after-clear")
            } finally {
                successfulClear.dispose()
            }
        } finally {
            readGate.complete(Unit)
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    @Test(timeout = 30_000L)
    fun staleLoadCancellationWhileRetryPending_keepsNewGate() {
        val readGate = CompletableDeferred<Unit>()
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)), readGate = readGate, failWrites = true)
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            val initialLoad = parent.children.single()
            val failedClear = history.clear().test()
            try {
                assertThat(failedClear.await(5L, TimeUnit.SECONDS)).isTrue()
                failedClear.assertError(java.io.IOException::class.java).assertNotComplete()
                val children = parent.children.toList()
                assertThat(children).hasSize(2)
                val retry = children.single { it !== initialLoad }
                initialLoad.cancel(CancellationException("cancel stale load before dispatch"))
                assertThat(dispatcher.pumpOne()).isTrue()
                assertThat(initialLoad.isCancelled).isTrue()
                assertThat(parent.isActive).isTrue()
                assertThat(retry.isActive).isTrue()
                assertThat(store.readCalls).isEqualTo(0)

                assertThat(dispatcher.pumpOne()).isTrue()
                assertThat(store.readCalls).isEqualTo(1)
                store.failWrites = false
                history.start("new-host", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                dispatcher.pumpAll()
                assertThat(store.readCalls).isEqualTo(1)
                assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))

                readGate.complete(Unit)
                dispatcher.pumpAll()
                val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
                assertThat(stored.map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
                assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
            } finally {
                failedClear.dispose()
            }
        } finally {
            readGate.complete(Unit)
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    /** Cancellation or synchronous completion must not strand later requests. */
    @Test(timeout = 30_000L)
    fun persistDrainCancellationAndImmediateCompletion_nextFinishPersists() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            dispatcher.pumpAll()

            history.start("first", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            val firstWriter = parent.children.single()
            firstWriter.cancel(CancellationException("cancel writer before dispatch"))
            history.start("second", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(firstWriter.isCancelled).isTrue()
            assertThat(store.editCalls).isEqualTo(1)
            val firstStored = gson.fromJson<List<ConnectionRun>>(store.storedJson(), ConnectionHistoryImpl.getRunsType())
            assertThat(firstStored.map { it.remoteHost }).isEqualTo(listOf("second", "first", "old-host"))

            history.start("third", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            val secondWriter = parent.children.single()
            secondWriter.cancel(CancellationException("cancel writer before dispatch"))
            dispatcher.pumpAll()
            assertThat(secondWriter.isCancelled).isTrue()
            assertThat(store.editCalls).isEqualTo(1)
            history.start("fourth", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(parent.isActive).isTrue()
            val stored = gson.fromJson<List<ConnectionRun>>(store.storedJson(), ConnectionHistoryImpl.getRunsType())
            assertThat(stored.map { it.remoteHost }).isEqualTo(listOf("fourth", "third", "second", "first", "old-host"))
            assertThat(stored).isEqualTo(history.runs())

            val immediateStore = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
            val immediateHistory = ConnectionHistoryImpl(immediateStore, CoroutineScope(Dispatchers.Unconfined + parent))
            immediateHistory.start("immediate-one", emptyList())
            immediateHistory.finish(succeeded = true, metadata = emptyList())
            immediateHistory.start("immediate-two", emptyList())
            immediateHistory.finish(succeeded = true, metadata = emptyList())
            assertThat(immediateStore.editCalls).isEqualTo(2)
            val immediateStored = gson.fromJson<List<ConnectionRun>>(
                immediateStore.storedJson(), ConnectionHistoryImpl.getRunsType(),
            )
            assertThat(immediateStored.map { it.remoteHost })
                .isEqualTo(listOf("immediate-two", "immediate-one", "old-host"))
        } finally {
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    /** A clear boundary must discard old pending work while preserving newer requests. */
    @Test(timeout = 30_000L)
    fun pendingBeforeClear_multipleNewFinishesPersistOnlyAfterClear() {
        val initialJson = gson.toJson(listOf(sampleRun("old-host")))
        val store = FakeHistoryStore(initialJson = initialJson)
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        val clearGate = CompletableDeferred<Unit>()
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            dispatcher.pumpAll()
            history.start("before-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            store.parkClearGate = clearGate
            val clear = history.clear().test()
            try {
                assertThat(store.clearArrived.await(5L, TimeUnit.SECONDS)).isTrue()
                assertThat(history.runs()).isEmpty()
                history.start("after-one", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                history.start("after-two", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                dispatcher.pumpAll()
                assertThat(store.editCalls).isEqualTo(1)
                assertThat(store.writes).isEmpty()
                assertThat(store.storedJson()).isEqualTo(initialJson)

                clearGate.complete(Unit)
                assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
                clear.assertComplete().assertNoErrors()
                dispatcher.pumpAll()
                assertThat(store.writes.first()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))
                val stored = gson.fromJson<List<ConnectionRun>>(store.storedJson(), ConnectionHistoryImpl.getRunsType())
                assertThat(stored.map { it.remoteHost }).isEqualTo(listOf("after-two", "after-one"))
                assertThat(stored).isEqualTo(history.runs())
            } finally {
                clearGate.complete(Unit)
                clear.await(5L, TimeUnit.SECONDS)
                clear.dispose()
            }
        } finally {
            clearGate.complete(Unit)
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    /** Suspended initial reads must not create unbounded persistence work. */
    @Test(timeout = 30_000L)
    fun pendingLoad_burstKeepsJobsBoundedAndPersistsLatestRuns() {
        val gate = CompletableDeferred<Unit>()
        val initialJson = gson.toJson(listOf(sampleRun("old-host")))
        val store = FakeHistoryStore(initialJson = initialJson, readGate = gate)
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        val totalRuns = 600
        val retainedRuns = 500
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            dispatcher.pumpAll()
            assertThat(store.readCalls).isEqualTo(1)
            assertThat(gate.isCompleted).isFalse()

            repeat(3) { index ->
                history.start("host-$index", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
            }
            dispatcher.pumpAll()
            val warmupJobs = parent.children.count { it.isActive }
            assertThat(warmupJobs).isAtLeast(1)
            assertThat(history.runs().map { it.remoteHost }).isEqualTo(listOf("host-2", "host-1", "host-0"))
            assertThat(store.storedJson()).isEqualTo(initialJson)
            assertThat(store.editCalls).isEqualTo(0)

            for (index in 3 until totalRuns) {
                history.start("host-$index", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
            }
            val queuedBeforePump = dispatcher.queuedCount()
            dispatcher.pumpAll()
            assertThat(gate.isCompleted).isFalse()
            assertThat(store.readCalls).isEqualTo(1)
            assertThat(store.editCalls).isEqualTo(0)
            assertThat(store.storedJson()).isEqualTo(initialJson)
            val blockedJobs = parent.children.count { it.isActive }
            // Allow load/retry and writer overhead, never one job per finished run.
            assertThat(blockedJobs).isAtMost(warmupJobs + 2)
            assertThat(blockedJobs).isAtMost(6)
            assertThat(queuedBeforePump).isAtMost(3)
            assertThat(dispatcher.queuedCount()).isEqualTo(0)

            val expectedHosts = (totalRuns - 1 downTo totalRuns - retainedRuns).map { "host-$it" }
            assertThat(history.runs().map { it.remoteHost }).isEqualTo(expectedHosts)
            gate.complete(Unit)
            dispatcher.pumpAll()
            val stored = gson.fromJson<List<ConnectionRun>>(store.storedJson(), ConnectionHistoryImpl.getRunsType())
            assertThat(stored.map { it.remoteHost }).isEqualTo(expectedHosts)
            assertThat(stored).isEqualTo(history.runs())
        } finally {
            gate.complete(Unit)
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    @Test(timeout = 30_000L)
    fun pendingLoad_thenFinish_keepsOldAndNew() {
        val gate = CompletableDeferred<Unit>()
        val oldRun = sampleRun("old-host", startedAt = 100L)
        val store = FakeHistoryStore(
            initialJson = gson.toJson(listOf(oldRun)),
            readGate = gate,
        )
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        // Queue initial load but keep data.first() pending; no credible read yet.
        dispatcher.pumpOne()

        // New finish races the pending load; gated write must not touch store yet.
        history.start("new-host", emptyList())
        history.log("line")
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        // In-memory keeps the new run, store old snapshot untouched until unlock.
        assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")
        assertThat(store.writes).isEmpty()
        assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))

        gate.complete(Unit)
        dispatcher.pumpAll()

        val hosts = history.runs().map { it.remoteHost }
        assertThat(hosts).containsAtLeast("old-host", "new-host")
        assertThat(history.runs().first().remoteHost).isEqualTo("new-host")
        val storedHosts = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(storedHosts.map { it.remoteHost }).containsAtLeast("old-host", "new-host")
        assertThat(store.writes).isNotEmpty()
    }

    /** A suspended edit must finish without accumulating unbounded persistence work. */
    @Test(timeout = 30_000L)
    fun parkedEdit_burstKeepsJobsBoundedAndPersistsLatestRuns() {
        val gate = CompletableDeferred<Unit>()
        val oldRun = sampleRun("old-host")
        val initialJson = gson.toJson(listOf(oldRun))
        val store = FakeHistoryStore(initialJson = initialJson)
        store.parkMarker = "\"remoteHost\":\"host-0\""
        store.parkGate = gate
        val dispatcher = ManualDispatcher()
        val parent = SupervisorJob()
        val totalRuns = 600
        val retainedRuns = 500
        try {
            val history = ConnectionHistoryImpl(store, CoroutineScope(dispatcher + parent))
            dispatcher.pumpAll()
            assertThat(store.readCalls).isEqualTo(1)
            assertThat(history.runs()).containsExactly(oldRun)

            history.start("host-0", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            val firstSnapshot = history.runs()
            assertThat(firstSnapshot.map { it.remoteHost }).isEqualTo(listOf("host-0", "old-host"))
            assertThat(firstSnapshot.last()).isEqualTo(oldRun)
            dispatcher.pumpAll()
            assertThat(store.parkMarker).isNull()
            assertThat(gate.isCompleted).isFalse()
            assertThat(store.editCalls).isEqualTo(1)
            assertThat(store.writes).isEmpty()
            assertThat(store.storedJson()).isEqualTo(initialJson)
            val warmupJobs = parent.children.count { it.isActive }
            assertThat(warmupJobs).isAtLeast(1)

            for (index in 1 until totalRuns) {
                history.start("host-$index", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
            }
            val queuedBeforePump = dispatcher.queuedCount()
            dispatcher.pumpAll()
            assertThat(gate.isCompleted).isFalse()
            assertThat(store.editCalls).isEqualTo(1)
            assertThat(store.writes).isEmpty()
            assertThat(store.storedJson()).isEqualTo(initialJson)
            val blockedJobs = parent.children.count { it.isActive }
            // Allow writer overhead, never one job per finished run.
            assertThat(blockedJobs).isAtMost(warmupJobs + 2)
            assertThat(blockedJobs).isAtMost(6)
            assertThat(queuedBeforePump).isAtMost(3)
            assertThat(dispatcher.queuedCount()).isEqualTo(0)

            val expectedHosts = (totalRuns - 1 downTo totalRuns - retainedRuns).map { "host-$it" }
            assertThat(history.runs().map { it.remoteHost }).isEqualTo(expectedHosts)
            gate.complete(Unit)
            dispatcher.pumpAll()
            val firstStored = gson.fromJson<List<ConnectionRun>>(store.writes.first(), ConnectionHistoryImpl.getRunsType())
            assertThat(firstStored).isEqualTo(firstSnapshot)
            val stored = gson.fromJson<List<ConnectionRun>>(store.storedJson(), ConnectionHistoryImpl.getRunsType())
            assertThat(stored.map { it.remoteHost }).isEqualTo(expectedHosts)
            assertThat(stored).isEqualTo(history.runs())
        } finally {
            gate.complete(Unit)
            parent.cancel()
            dispatcher.pumpAll()
        }
    }

    @Test(timeout = 30_000L)
    fun overlappingPersists_reverseCompletion_newerStillWins() {
        val store = FakeHistoryStore(initialJson = gson.toJson(emptyList<ConnectionRun>()))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()

        // First run's write parks inside the store holding the persist mutex.
        store.parkMarker = "\"remoteHost\":\"host-one\""
        val releaseFirst = CompletableDeferred<Unit>()
        store.parkGate = releaseFirst

        history.start("host-one", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpOne()
        dispatcher.pumpOne()

        history.start("host-two", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        // Release the stale first write last: it must not overwrite the newer snapshot.
        releaseFirst.complete(Unit)
        dispatcher.pumpAll()

        assertThat(store.writeArrived.await(10L, TimeUnit.SECONDS)).isTrue()
        val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(stored.map { it.remoteHost }).isEqualTo(listOf("host-two", "host-one"))
        assertThat(history.runs().map { it.remoteHost }).isEqualTo(listOf("host-two", "host-one"))
    }

    @Test(timeout = 30_000L)
    fun clear_thenLateLoadAndWrite_doNotResurrect() {
        val loadGate = CompletableDeferred<Unit>()
        val store = FakeHistoryStore(
            initialJson = gson.toJson(listOf(sampleRun("old-host", startedAt = 100L))),
            readGate = loadGate,
        )
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)

        // The read captures old data, while an old write waits on the initial gate.
        dispatcher.pumpOne()
        history.start("pending-before-clear", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            assertThat(history.runs()).isEmpty()
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))

            history.start("after-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            loadGate.complete(Unit)
            dispatcher.pumpAll()

            assertThat(history.runs().map { it.remoteHost }).containsExactly("after-clear")
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("after-clear")
        } finally {
            loadGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun unreadableStore_doesNotWipeNewRuns() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        store.failReads = true
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        history.start("new-host", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        // Gated write drops on read failure: in-memory keeps new, store untouched.
        // No blind edit is allowed while old data remains unknown.
        assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")
        assertThat(store.writes).isEmpty()
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))
    }

    @Test(timeout = 30_000L)
    fun corruptStoreJson_doesNotWipeNewRuns() {
        val store = FakeHistoryStore(initialJson = "{not valid json")
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        history.start("new-host", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        // Gated write drops on parse failure: in-memory keeps new, store untouched.
        assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")
        assertThat(store.writes).isEmpty()
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(store.storedJson()).isEqualTo("{not valid json")
    }

    @Test(timeout = 30_000L)
    fun failedFirstRead_thenRecover_retryMergesOldAndNewWithoutBlindWrite() {
        val oldRun = sampleRun("old-host", startedAt = 100L)
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        store.failReads = true
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        history.start("new-host", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        // Still failing: zero DataStore edits, new run held in memory only.
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")

        // Recover and retry via the next finish: old plus new merge and persist.
        store.failReads = false
        history.start("new-host-2", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(stored.map { it.remoteHost }).isEqualTo(listOf("new-host-2", "new-host", "old-host"))
        assertThat(history.runs().map { it.remoteHost }).isEqualTo(listOf("new-host-2", "new-host", "old-host"))
    }

    @Test(timeout = 30_000L)
    fun failedFirstRead_thenClear_recoversWrites() {
        val oldRun = sampleRun("old-host", startedAt = 100L)
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        store.failReads = true
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            assertThat(history.runs()).isEmpty()
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))

            // The clear must establish a credible gate even while reads still fail.
            history.start("after-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("after-clear")
            assertThat(history.runs().map { it.remoteHost }).containsExactly("after-clear")
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun duplicateStoredRuns_allKeptNewFirst() {
        val duplicate = sampleRun("same-host", startedAt = 100L)
        val loadGate = CompletableDeferred<Unit>()
        val store = FakeHistoryStore(
            initialJson = gson.toJson(listOf(duplicate, duplicate)),
            readGate = loadGate,
        )
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        // Gate keeps the new finish in memory without persisting unknown old data.
        history.start("new-host", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()
        assertThat(store.editCalls).isEqualTo(0)

        loadGate.complete(Unit)
        dispatcher.pumpAll()

        // No dedup: identical records can be distinct real runs.
        assertThat(history.runs()).hasSize(3)
        assertThat(history.runs().map { it.remoteHost }).isEqualTo(listOf("new-host", "same-host", "same-host"))
        val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(stored).hasSize(3)
        assertThat(stored.map { it.remoteHost }).isEqualTo(listOf("new-host", "same-host", "same-host"))
    }

    @Test(timeout = 30_000L)
    fun clearLinearizes_parkedClearThenNewFinish_newWins() {
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(sampleRun("old-host", startedAt = 100L))))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        dispatcher.pumpAll()

        // Park the clear edit after its memory boundary but before it commits.
        val releaseClear = CompletableDeferred<Unit>()
        store.parkClearGate = releaseClear
        val clear = history.clear().test()
        try {
            assertThat(store.clearArrived.await(5L, TimeUnit.SECONDS)).isTrue()
            history.start("new-after-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            releaseClear.complete(Unit)
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            dispatcher.pumpAll()

            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-after-clear")
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("new-after-clear")
        } finally {
            releaseClear.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun clear_preservesOpenRunAndLog() {
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(sampleRun("old-host"))))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        history.start("active-host", emptyList())
        history.log("handshake")

        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            assertThat(history.activeRun()?.remoteHost).isEqualTo("active-host")
            assertThat(history.currentHandshakeLog()).isEqualTo("handshake")
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))

            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("active-host")
            assertThat(stored.single().log).isEqualTo("handshake")
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun staleReadFailure_afterClear_doesNotBlockWrites() {
        val readGate = CompletableDeferred<Unit>()
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(sampleRun("old-host"))), readGate = readGate)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpOne()

        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            store.failReads = true
            readGate.complete(Unit)
            dispatcher.pumpAll()

            history.start("after-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("after-clear")
        } finally {
            readGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun openRun_readsStayConsistentUnderThreads() {
        val store = FakeHistoryStore()
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()

        history.start("host", emptyList())
        val startGate = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = List(8) { index ->
            thread(isDaemon = true) {
                try {
                    check(startGate.await(5L, TimeUnit.SECONDS)) { "start gate timed out" }
                    repeat(50) { history.log("line-$index-$it") }
                    history.activeRun()
                    history.currentHandshakeLog()
                    history.runs()
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        try {
            startGate.countDown()
            workers.forEach { it.join(5_000L) }
            assertThat(workers.any { it.isAlive }).isFalse()
            assertThat(errors).isEmpty()
        } finally {
            startGate.countDown()
        }

        history.finish(succeeded = true, metadata = emptyList())
        val finished = history.runs().single()
        assertThat(finished.remoteHost).isEqualTo("host")
        val lines = finished.log.split("\n")
        assertThat(lines.size).isAtMost(200)
        assertThat(lines).isNotEmpty()
    }

    @Test(timeout = 30_000L)
    fun clearFailure_leavesOldHistoryAndBlocksWritesUntilRecovery() {
        val oldRun1 = sampleRun("old-host-1", startedAt = 100L)
        val oldRun2 = sampleRun("old-host-2", startedAt = 200L)
        val store = FakeHistoryStore(
            initialJson = gson.toJson(listOf(oldRun1, oldRun2))
        )
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()

        val clearGate = CompletableDeferred<Unit>()
        store.parkClearGate = clearGate
        val editsBeforeClear = store.editCalls
        val clear = history.clear().test()
        try {
            assertThat(store.clearArrived.await(5L, TimeUnit.SECONDS)).isTrue()
            store.failWrites = true
            store.failReads = true
            clearGate.complete(Unit)
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()

            assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun1, oldRun2)))
            assertThat(history.runs().map { it.remoteHost }).containsExactly("old-host-2", "old-host-1")

            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(store.editCalls).isEqualTo(editsBeforeClear + 1)
            assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun1, oldRun2)))

            store.failReads = false
            store.failWrites = false
            history.start("new-host-2", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("new-host-2", "new-host", "old-host-2", "old-host-1")
        } finally {
            clearGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun clearFailure_withConcurrentFinish_reconcilesWithoutAnotherFinish() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        val clearGate = CompletableDeferred<Unit>()
        store.parkClearGate = clearGate
        val clear = history.clear().test()
        try {
            assertThat(store.clearArrived.await(5L, TimeUnit.SECONDS)).isTrue()
            history.start("during-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            store.failWrites = true
            clearGate.complete(Unit)
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            store.failWrites = false
            dispatcher.pumpAll()

            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("during-clear", "old-host").inOrder()
            assertThat(history.runs().map { it.remoteHost }).containsExactly("during-clear", "old-host").inOrder()
        } finally {
            clearGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun clearFailure_newFinishThenReadResumes_reconciles() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        val editsBeforeClear = store.editCalls
        store.failWrites = true
        val clear = history.clear().test()
        val readGate = CompletableDeferred<Unit>()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            store.failWrites = false
            store.readGate = readGate
            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(store.editCalls).isEqualTo(editsBeforeClear + 1)
            assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))

            readGate.complete(Unit)
            dispatcher.pumpAll()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
        } finally {
            readGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun failedClear_beforeInitialRead_mergesPendingAndDuplicateStoredRuns() {
        val duplicate = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(duplicate, duplicate)), failReads = true)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        history.start("before-clear", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()

        val clearGate = CompletableDeferred<Unit>()
        store.parkClearGate = clearGate
        val clear = history.clear().test()
        try {
            assertThat(store.clearArrived.await(5L, TimeUnit.SECONDS)).isTrue()
            history.start("during-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            store.failWrites = true
            clearGate.complete(Unit)
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            dispatcher.pumpAll()
            assertThat(history.runs().map { it.remoteHost }).containsExactly("during-clear", "before-clear").inOrder()
            assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(duplicate, duplicate)))

            store.failReads = false
            store.failWrites = false
            history.start("after-recovery", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly(
                "after-recovery", "during-clear", "before-clear", "old-host", "old-host",
            ).inOrder()
        } finally {
            clearGate.complete(Unit)
            dispatcher.pumpAll()
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun clearFailsBeforeTransform_keepsWritesBlockedWhenBaselineUnknown() {
        val oldRun = sampleRun("old-host")
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(oldRun)))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        val editsBeforeClear = store.editCalls
        store.failBeforeTransform = true
        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            store.failBeforeTransform = false
            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            assertThat(store.editCalls).isEqualTo(editsBeforeClear + 1)
            assertThat(store.storedJson()).isEqualTo(gson.toJson(listOf(oldRun)))
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun defensiveCommitThenThrow_neverRestoresOrOverwritesUnknownDiskState() {
        val store = FakeHistoryStore(initialJson = gson.toJson(listOf(sampleRun("old-host"))))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        val editsBeforeClear = store.editCalls
        // Defensive fault injection: this outcome violates the real DataStore failure contract.
        store.throwAfterClearCommit = true
        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))
            assertThat(history.runs().map { it.remoteHost }).containsExactly("old-host")

            store.throwAfterClearCommit = false
            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(store.editCalls).isEqualTo(editsBeforeClear + 1)
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun defensiveEmptyCommitThenThrow_doesNotResurrectPendingRuns() {
        val store = FakeHistoryStore(initialJson = gson.toJson(emptyList<ConnectionRun>()))
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        history.start("pending-before-clear", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        val editsBeforeClear = store.editCalls
        // Defensive fault injection: this outcome violates the real DataStore failure contract.
        store.throwAfterClearCommit = true
        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(java.io.IOException::class.java).assertNotComplete()
            store.throwAfterClearCommit = false
            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            assertThat(store.editCalls).isEqualTo(editsBeforeClear + 1)
            assertThat(store.storedJson()).isEqualTo(gson.toJson(emptyList<ConnectionRun>()))
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host", "pending-before-clear").inOrder()
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun validLoad_keepsOriginalJsonDuplicatesAndLimitWithoutEditing() {
        val original = " \n" + gson.toJson(List(501) { sampleRun("duplicate") }) + "\n"
        val store = FakeHistoryStore(initialJson = original)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()

        assertThat(history.runs()).hasSize(500)
        assertThat(history.runs().map { it.remoteHost }.toSet()).containsExactly("duplicate")
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(store.storedJson()).isEqualTo(original)
    }

    @Test(timeout = 30_000L)
    fun validLongBoundariesAndEmptyFields_loadAndPersistNewRun() {
        val oldRun = sampleRun("").copy(
            startedAtEpochMs = Long.MIN_VALUE,
            endedAtEpochMs = Long.MAX_VALUE,
            metadata = emptyList(),
            configuration = emptyList(),
            log = "",
        )
        val original = gson.toJson(listOf(oldRun))
        val store = FakeHistoryStore(initialJson = original)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        assertThat(history.runs()).containsExactly(oldRun)
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(store.storedJson()).isEqualTo(original)

        history.start("new-host", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()
        val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(stored).hasSize(2)
        assertThat(stored.last()).isEqualTo(oldRun)
    }

    @Test(timeout = 30_000L)
    fun unsupportedRunAndFactFields_blockWritesWithoutChangingJson() {
        val valid = gson.toJson(listOf(sampleRun("old-host")))
        val cases = listOf(
            valid.replace("\"remoteHost\":\"old-host\"", "\"remoteHost\":\"old-host\",\"future\":{\"revision\":2}"),
            valid.replace("\"label\":\"k\"", "\"futureFact\":[1,2],\"label\":\"k\""),
        )
        cases.forEach { original ->
            assertThat(original).isNotEqualTo(valid)
            val store = FakeHistoryStore(initialJson = original)
            val dispatcher = ManualDispatcher()
            val history = historyOf(store, dispatcher)
            dispatcher.pumpAll()
            assertThat(history.runs()).isEmpty()
            assertThat(store.editCalls).isEqualTo(0)
            assertThat(store.storedJson()).isEqualTo(original)

            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")
            assertThat(store.editCalls).isEqualTo(0)
            assertThat(store.storedJson()).isEqualTo(original)
        }
    }

    @Test(timeout = 30_000L)
    fun malformedRequiredFields_neverOpenGateOrOverwriteStore() {
        val valid = gson.toJson(listOf(sampleRun("old-host")))
        val metadata = gson.toJson(listOf(ConnectionRunFact("k", "v")))
        val configuration = gson.toJson(listOf(ConnectionRunFact("c", "1")))
        val cases = listOf(
            "", " ", "null", "[null]", "[{}]", "[[]]", "[] trailing",
            valid.replace("\"succeeded\":true", "\"succeeded\":\"true\""),
            valid.replace("\"succeeded\":true", "\"succeeded\":null"),
            valid.replace("\"succeeded\":true", "\"succeeded\":0"),
            valid.replace("\"succeeded\":true", "\"succeeded\":true,\"succeeded\":false"),
            valid.replace("\"startedAtEpochMs\":1000", "\"startedAtEpochMs\":1.5"),
            valid.replace("\"startedAtEpochMs\":1000", "\"startedAtEpochMs\":\"1000\""),
            valid.replace("\"startedAtEpochMs\":1000", "\"startedAtEpochMs\":1e3"),
            valid.replace("\"startedAtEpochMs\":1000", "\"startedAtEpochMs\":-9223372036854775809"),
            valid.replace("\"endedAtEpochMs\":1010", "\"endedAtEpochMs\":9223372036854775808"),
            valid.replace("\"endedAtEpochMs\":1010", "\"endedAtEpochMs\":null"),
            valid.replace("\"remoteHost\":\"old-host\"", "\"remoteHost\":null"),
            valid.replace("\"remoteHost\":\"old-host\"", "\"remoteHost\":42"),
            valid.replace("\"log\":\"hello\"", "\"log\":123"),
            valid.replace("\"log\":\"hello\"", "\"log\":null"),
            valid.replace("\"metadata\":$metadata", "\"metadata\":null"),
            valid.replace("\"metadata\":$metadata", "\"metadata\":{}"),
            valid.replace("\"metadata\":$metadata", "\"metadata\":[null]"),
            valid.replace("\"configuration\":$configuration", "\"configuration\":null"),
            valid.replace("\"configuration\":$configuration", "\"configuration\":[null]"),
            valid.replace("\"label\":\"k\"", "\"label\":null"),
            valid.replace("\"value\":\"v\"", "\"value\":null"),
            valid.replace("\"value\":\"v\"", "\"value\":42"),
            valid.replace("\"label\":\"k\",", ""),
        )
        cases.forEach { original ->
            assertThat(original).isNotEqualTo(valid)
            val store = FakeHistoryStore(initialJson = original)
            val dispatcher = ManualDispatcher()
            val history = historyOf(store, dispatcher)
            dispatcher.pumpAll()
            assertThat(history.runs()).isEmpty()
            history.start("new-host", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()

            assertThat(history.runs().map { it.remoteHost }).containsExactly("new-host")
            assertThat(store.editCalls).isEqualTo(0)
            assertThat(store.storedJson()).isEqualTo(original)
        }
    }

    @Test(timeout = 30_000L)
    fun unsupportedHistory_explicitClearRestoresWrites() {
        val valid = gson.toJson(listOf(sampleRun("old-host")))
        val original = valid.replace("\"label\":\"k\"", "\"futureFact\":true,\"label\":\"k\"")
        assertThat(original).isNotEqualTo(valid)
        val store = FakeHistoryStore(initialJson = original)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        history.start("blocked", emptyList())
        history.finish(succeeded = true, metadata = emptyList())
        dispatcher.pumpAll()
        assertThat(store.editCalls).isEqualTo(0)
        assertThat(store.storedJson()).isEqualTo(original)

        store.failWrites = true
        val failed = history.clear().test()
        try {
            assertThat(failed.await(5L, TimeUnit.SECONDS)).isTrue()
            failed.assertError(java.io.IOException::class.java).assertNotComplete()
            dispatcher.pumpAll()
            assertThat(history.runs().map { it.remoteHost }).containsExactly("blocked")
            assertThat(store.editCalls).isEqualTo(1)
            assertThat(store.storedJson()).isEqualTo(original)
        } finally {
            failed.dispose()
        }
        store.failWrites = false
        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertComplete().assertNoErrors()
            assertThat(store.storedJson()).isEqualTo("[]")
            history.start("after-clear", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
            dispatcher.pumpAll()
            val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
            assertThat(stored.map { it.remoteHost }).containsExactly("after-clear")
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun sessionClear_handlesFailureAndSuccessWithoutGlobalError() {
        val cause = java.io.IOException("clear failed")
        var calls = 0
        var observed: Throwable? = null
        var completed = false
        val history = object : ConnectionHistory by TestConnectionHistory() {
            override fun clear(): Completable {
                calls++
                return if (calls == 1) {
                    Completable.error(cause).doOnError { observed = it }
                } else {
                    Completable.complete().doOnComplete { completed = true }
                }
            }
        }
        val previousHandler = RxJavaPlugins.getErrorHandler()
        val globalErrors = mutableListOf<Throwable>()
        try {
            RxJavaPlugins.setErrorHandler { globalErrors.add(it) }
            val session = MiraxSession(connectionHistory = history)
            session.handle(SessionAction.ClearConnectionHistory)
            assertThat(observed).isSameInstanceAs(cause)
            assertThat(globalErrors).isEmpty()
            session.handle(SessionAction.ClearConnectionHistory)
            assertThat(calls).isEqualTo(2)
            assertThat(completed).isTrue()
            assertThat(globalErrors).isEmpty()
        } finally {
            RxJavaPlugins.setErrorHandler(previousHandler)
        }
    }

    @Test(timeout = 30_000L)
    fun clearCancellationBeforeCommit_propagatesAndKeepsDisk() {
        val original = gson.toJson(listOf(sampleRun("old-host")))
        val store = FakeHistoryStore(initialJson = original)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        store.cancelWrites = true
        val clear = history.clear().test()
        try {
            assertThat(clear.await(5L, TimeUnit.SECONDS)).isTrue()
            clear.assertError(CancellationException::class.java).assertNotComplete()
            store.cancelWrites = false
            dispatcher.pumpAll()
            assertThat(store.editCalls).isEqualTo(1)
            assertThat(store.storedJson()).isEqualTo(original)
            assertThat(history.runs().map { it.remoteHost }).containsExactly("old-host")
        } finally {
            clear.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun twoFailedClears_reconcileWithoutRewritingLoadedHistory() {
        val original = gson.toJson(listOf(sampleRun("old-host")))
        val store = FakeHistoryStore(initialJson = original)
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()
        store.failWrites = true
        store.failReads = true
        val first = history.clear().test()
        try {
            assertThat(first.await(5L, TimeUnit.SECONDS)).isTrue()
            first.assertError(java.io.IOException::class.java).assertNotComplete()
            val second = history.clear().test()
            try {
                assertThat(second.await(5L, TimeUnit.SECONDS)).isTrue()
                second.assertError(java.io.IOException::class.java).assertNotComplete()
                store.failWrites = false
                store.failReads = false
                dispatcher.pumpAll()
                assertThat(store.editCalls).isEqualTo(2)
                assertThat(store.storedJson()).isEqualTo(original)
                assertThat(history.runs().map { it.remoteHost }).containsExactly("old-host")

                history.start("new-host", emptyList())
                history.finish(succeeded = true, metadata = emptyList())
                dispatcher.pumpAll()
                val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
                assertThat(stored.map { it.remoteHost }).containsExactly("new-host", "old-host").inOrder()
            } finally {
                second.dispose()
            }
        } finally {
            first.dispose()
        }
    }

    @Test(timeout = 30_000L)
    fun fiveHundredOneRuns_keepsNewestDropsOldest() {
        val store = FakeHistoryStore()
        val dispatcher = ManualDispatcher()
        val history = historyOf(store, dispatcher)
        dispatcher.pumpAll()

        repeat(501) { index ->
            history.start("host-$index", emptyList())
            history.finish(succeeded = true, metadata = emptyList())
        }
        dispatcher.pumpAll()

        val runs = history.runs()
        assertThat(runs).hasSize(500)
        assertThat(runs.first().remoteHost).isEqualTo("host-500")
        assertThat(runs.last().remoteHost).isEqualTo("host-1")
        val stored = gson.fromJson(store.storedJson(), ConnectionHistoryImpl.getRunsType()) as List<ConnectionRun>
        assertThat(stored).hasSize(500)
        assertThat(stored.first().remoteHost).isEqualTo("host-500")
    }
}
