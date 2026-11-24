package me.trinitrix.mirax.data.standby

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Behavioural JVM tests for [StandbyMediaStore] with real temp files.
 *
 * Uses a serialized [FakePreferencesDataStore] fake (mirroring real
 * `DataStore.updateData` mutual exclusion), fake validators, and real file
 * bytes. Coordination uses [CompletableDeferred] signals only, never
 * `Thread.sleep` polling. Covers pre-confirm immutability, success,
 * discard/confirm interleave, invalid and IO failures, ambiguous
 * transactions, restart, staleness, corrupt metadata, forged and tampered
 * candidates, snapshot classification, validator dispatcher, and stage
 * cancellation.
 */
class StandbyMediaStoreTest {

    /** Serialized in-memory [DataStore] fake with controllable commit hooks. */
    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val updateMutex = Mutex()

        /** When true, reads throw instead of emitting. */
        @Volatile var failReads = false

        /** When true, updates throw. */
        @Volatile var throwOnUpdate = false

        /** When true with [throwOnUpdate], state commits before throwing. */
        @Volatile var commitBeforeThrow = false

        /** When true, a successful update poisons later reads. */
        @Volatile var poisonReadsOnUpdate = false

        /** Counts serialized updates; mirrors real DataStore mutual exclusion. */
        val updateCount = MutableStateFlow(0)

        /** Fires once per serialized update entry; reset by swapping the holder. */
        @Volatile var enteredUpdate = CompletableDeferred<Unit>()

        /** Runs once before an update acquires the mutex. */
        val beforeNextUpdateLock = AtomicReference<(suspend () -> Unit)?>(null)

        /** When non-null, updates suspend here before applying the transform. */
        @Volatile var beforeCommitGate: CompletableDeferred<Unit>? = null

        override val data: Flow<Preferences>
            get() = if (failReads) {
                flow { throw IOException("unreadable") }
            } else {
                state
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            beforeNextUpdateLock.getAndSet(null)?.invoke()
            return updateMutex.withLock {
                updateCount.value = updateCount.value + 1
                enteredUpdate.complete(Unit)
                beforeCommitGate?.await()
                if (throwOnUpdate && !commitBeforeThrow) {
                    throw IOException("before commit")
                }
                val next = transform(state.value)
                state.value = next
                if (poisonReadsOnUpdate) {
                    failReads = true
                }
                if (throwOnUpdate && commitBeforeThrow) {
                    throw IOException("after commit")
                }
                next
            }
        }

        /** Replaces the whole persisted record. */
        fun seed(prefs: Preferences) {
            state.value = prefs
        }
    }

    @Rule
    @JvmField
    val folders = TemporaryFolder()

    private fun storageDir(): File {
        return File(folders.root, "standby_owned").apply { mkdirs() }
    }

    private fun storeFor(
        fake: FakePreferencesDataStore,
        dir: File,
        validator: StandbyMediaValidator,
    ): StandbyMediaStore {
        return StandbyMediaStore(fake, validator, dir, Dispatchers.IO)
    }

    private fun bytesOf(vararg values: Int): ByteArray {
        return ByteArray(values.size) { index -> values[index].toByte() }
    }

    private fun inputOf(bytes: ByteArray): () -> InputStream {
        return { bytes.inputStream() }
    }

    private fun commitFirst(store: StandbyMediaStore, bytes: ByteArray, framing: StandbyFraming): StagedCandidate {
        val staged = runBlocking {
            store.stageContent(inputOf(bytes), framing)
        } as StandbyStageResult.Staged
        val confirmed = runBlocking {
            store.confirm(staged.candidate)
        } as StandbyConfirmResult.Committed
        assertThat(confirmed.active.file?.readBytes()).isEqualTo(bytes)
        return staged.candidate
    }

    private fun available(store: StandbyMediaStore): StandbyActive {
        val snapshot = runBlocking { store.snapshot() }
        assertThat(snapshot).isInstanceOf(StandbySnapshot.Available::class.java)
        return (snapshot as StandbySnapshot.Available).active
    }

    /**
     * Captures a pin rejection without leaking an unexpectedly successful pin.
     * @param store store under test.
     * @param file source to pin.
     * @return the rejection, or null when acquisition succeeded.
     */
    private suspend fun pinFailure(store: StandbyMediaStore, file: File): Exception? {
        val lease = try {
            store.pinSource(file)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return e
        }
        lease.release()
        return null
    }

    /**
     * Releases every acquired test pin, preserving all cleanup failures.
     * @param pins leases to release.
     */
    private suspend fun releasePins(pins: List<StandbyMediaSourceLease>) {
        var failure: Throwable? = null
        for (pin in pins) {
            try {
                pin.release()
            } catch (e: Throwable) {
                val previous = failure
                if (previous == null) failure = e else if (previous !== e) previous.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    @Test
    fun crossInstancePins_replacedFileDeletedAfterLastRelease() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val firstStore = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        val secondStore = storeFor(fake, File(dir, "../${dir.name}"), StandbyMediaValidator { StandbyMediaKind.GIF })
        val old = commitFirst(firstStore, bytesOf(1), StandbyFraming.CENTERED).file
        val firstPin = AtomicReference<StandbyMediaSourceLease?>(null)
        val secondPin = AtomicReference<StandbyMediaSourceLease?>(null)
        val candidate = AtomicReference<StagedCandidate?>(null)
        try {
            runBlocking {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) {
                    start.await()
                    firstStore.pinSource(old).also { firstPin.set(it) }
                }
                val second = async(Dispatchers.IO) {
                    start.await()
                    secondStore.pinSource(old).also { secondPin.set(it) }
                }
                start.complete(Unit)
                withTimeout(5_000L) { first.await(); second.await() }
                val staged = secondStore.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidate.set(staged.candidate)
                assertThat(secondStore.confirm(staged.candidate)).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                assertThat(old.exists()).isTrue()
                checkNotNull(firstPin.get()).release()
                checkNotNull(firstPin.get()).release()
                assertThat(old.exists()).isTrue()
                checkNotNull(secondPin.get()).release()
                assertThat(old.exists()).isFalse()
                assertThat(staged.candidate.file.exists()).isTrue()
            }
        } finally {
            runBlocking {
                try {
                    releasePins(listOfNotNull(firstPin.get(), secondPin.get()))
                } finally {
                    candidate.get()?.let { secondStore.discard(it) }
                }
            }
        }
    }

    @Test
    fun pinnedDiscard_invalidatesCandidateBeforeLastRelease() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.GIF })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        var lease: StandbyMediaSourceLease? = null
        try {
            runBlocking {
                lease = store.pinSource(staged.candidate.file)
                val discard = launch(start = CoroutineStart.UNDISPATCHED) {
                    cancel()
                    store.discard(staged.candidate)
                    error("Discard swallowed caller cancellation")
                }
                withTimeout(5_000L) { discard.join() }
                assertThat(discard.isCancelled).isTrue()
                assertThat(staged.candidate.file.exists()).isTrue()
                assertThat(store.confirm(staged.candidate)).isEqualTo(StandbyConfirmResult.CandidateInvalid)
                assertThat(pinFailure(store, staged.candidate.file)).isInstanceOf(IOException::class.java)
                assertThat(fake.updateCount.value).isEqualTo(0)
                checkNotNull(lease).release()
                assertThat(staged.candidate.file.exists()).isFalse()
            }
        } finally {
            runBlocking {
                lease?.release()
                store.discard(staged.candidate)
            }
        }
    }

    @Test
    fun pinSource_rejectsMissingEmptyOutsideAndTraversal() {
        val dir = storageDir()
        val store = storeFor(FakePreferencesDataStore(), dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val empty = File(dir, "empty.bin").apply { createNewFile() }
        val folder = File(dir, "nested").apply { mkdirs() }
        val outside = File(folders.root, "outside.bin").apply { writeBytes(bytesOf(1)) }
        runBlocking {
            assertThat(pinFailure(store, File(dir, "missing.bin"))).isInstanceOf(IOException::class.java)
            assertThat(pinFailure(store, empty)).isInstanceOf(IOException::class.java)
            assertThat(pinFailure(store, folder)).isInstanceOf(IOException::class.java)
            assertThat(pinFailure(store, outside)).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(pinFailure(store, File(dir, "../outside.bin"))).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun pinSource_rejectsSymlinkEscape() {
        val dir = storageDir()
        val store = storeFor(FakePreferencesDataStore(), dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val outside = File(folders.root, "outside-link-target.bin").apply { writeBytes(bytesOf(1)) }
        val link = File(dir, "escaped-link.bin")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: IOException) {
            assumeNoException("Symbolic links unavailable", e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException("Symbolic links unavailable", e)
        } catch (e: SecurityException) {
            assumeNoException("Symbolic links unavailable", e)
        }
        assertThat(Files.isSymbolicLink(link.toPath())).isTrue()
        assertThat(link.toPath().toRealPath()).isEqualTo(outside.toPath().toRealPath())
        runBlocking {
            assertThat(pinFailure(store, link)).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun pinningActiveAndForeignFiles_doesNotAuthorizeDeletion() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val active = commitFirst(store, bytesOf(1), StandbyFraming.CENTERED).file
        val foreign = File(dir, "standby_999_0123456789abcdef.bin").apply { writeBytes(bytesOf(2)) }
        val fixture = File(dir, "fixture.bin").apply { writeBytes(bytesOf(3)) }
        runBlocking {
            val pins = mutableListOf<StandbyMediaSourceLease>()
            try {
                pins.add(store.pinSource(active))
                pins.add(store.pinSource(foreign))
                pins.add(store.pinSource(fixture))
                assertThat(pins.last().file).isEqualTo(fixture.canonicalFile)
                store.discard(StagedCandidate(999, foreign, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED))
                assertThat(foreign.exists()).isTrue()
            } finally {
                releasePins(pins)
            }
        }
        assertThat(active.exists()).isTrue()
        assertThat(foreign.exists()).isTrue()
        assertThat(fixture.exists()).isTrue()
    }

    @Test
    fun deferredDeletion_unreadableAndCorruptPointersPreserveFiles() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val candidates = mutableListOf<StagedCandidate>()
        val pins = mutableListOf<StandbyMediaSourceLease>()
        try {
            runBlocking {
                val first = store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidates.add(first.candidate)
                val firstPin = store.pinSource(first.candidate.file)
                pins.add(firstPin)
                val persisted = fake.data.first()
                fake.failReads = true
                store.discard(first.candidate)
                assertThat(first.candidate.file.exists()).isTrue()
                firstPin.release()
                assertThat(first.candidate.file.exists()).isTrue()
                assertThat(pinFailure(store, first.candidate.file)).isInstanceOf(IOException::class.java)
                fake.failReads = false
                assertThat(fake.data.first()).isEqualTo(persisted)
                assertThat(store.confirm(first.candidate)).isEqualTo(StandbyConfirmResult.CandidateInvalid)
                assertThat(fake.updateCount.value).isEqualTo(0)
                firstPin.release()
                assertThat(first.candidate.file.exists()).isFalse()
                val second = store.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidates.add(second.candidate)
                val secondPin = store.pinSource(second.candidate.file)
                pins.add(secondPin)
                val corrupt = mutablePreferencesOf(stringPreferencesKey("standby_media_basename") to "broken")
                fake.seed(corrupt)
                store.discard(second.candidate)
                assertThat(fake.data.first()).isEqualTo(corrupt)
                assertThat(second.candidate.file.exists()).isTrue()
                secondPin.release()
                assertThat(second.candidate.file.exists()).isTrue()
                assertThat(pinFailure(store, second.candidate.file)).isInstanceOf(IOException::class.java)
                fake.seed(persisted)
                assertThat(store.confirm(second.candidate)).isEqualTo(StandbyConfirmResult.CandidateInvalid)
                assertThat(fake.updateCount.value).isEqualTo(0)
                secondPin.release()
                assertThat(second.candidate.file.exists()).isFalse()
            }
        } finally {
            fake.failReads = false
            fake.seed(emptyPreferences())
            runBlocking {
                try {
                    releasePins(pins)
                } finally {
                    candidates.forEach { store.discard(it) }
                }
            }
        }
    }

    @Test
    fun deferredDeletion_preservesCurrentActivePointer() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        var lease: StandbyMediaSourceLease? = null
        var recovered: StandbyMediaSourceLease? = null
        try {
            runBlocking {
                lease = store.pinSource(staged.candidate.file)
                store.discard(staged.candidate)
                fake.failReads = true
                checkNotNull(lease).release()
                assertThat(staged.candidate.file.exists()).isTrue()
                fake.failReads = false
                fake.seed(mutablePreferencesOf(
                    stringPreferencesKey("standby_media_basename") to staged.candidate.file.name,
                    stringPreferencesKey("standby_media_kind") to "IMAGE",
                    longPreferencesKey("standby_media_generation") to staged.candidate.generation,
                    floatPreferencesKey("standby_framing_center_x") to 0.5f,
                    floatPreferencesKey("standby_framing_center_y") to 0.5f,
                    floatPreferencesKey("standby_framing_zoom") to 1.0f,
                ))
                val snapshot = store.snapshot()
                assertThat(snapshot).isInstanceOf(StandbySnapshot.Available::class.java)
                assertThat((snapshot as StandbySnapshot.Available).active.file).isEqualTo(staged.candidate.file)
                recovered = store.pinSource(staged.candidate.file)
                checkNotNull(recovered).release()
                checkNotNull(lease).release()
                assertThat(staged.candidate.file.exists()).isTrue()
            }
        } finally {
            fake.failReads = false
            fake.seed(emptyPreferences())
            runBlocking {
                try {
                    releasePins(listOfNotNull(lease, recovered))
                } finally {
                    store.discard(staged.candidate)
                }
            }
        }
    }

    @Test
    fun deferredDeletion_preservesFilesDuringUnknownCommit() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val old = commitFirst(store, bytesOf(1), StandbyFraming.CENTERED).file
        var lease: StandbyMediaSourceLease? = null
        val candidates = mutableListOf<StagedCandidate>()
        try {
            runBlocking {
                lease = store.pinSource(old)
                val second = store.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidates.add(second.candidate)
                assertThat(store.confirm(second.candidate)).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                val third = store.stageContent(inputOf(bytesOf(3)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidates.add(third.candidate)
                fake.throwOnUpdate = true
                fake.commitBeforeThrow = true
                fake.poisonReadsOnUpdate = true
                assertThat(store.confirm(third.candidate)).isEqualTo(StandbyConfirmResult.Unavailable)
                store.discard(third.candidate)
                assertThat(third.candidate.file.exists()).isTrue()
                checkNotNull(lease).release()
                assertThat(old.exists()).isTrue()
                assertThat(second.candidate.file.exists()).isTrue()
                assertThat(third.candidate.file.exists()).isTrue()
                assertThat(pinFailure(store, old)).isInstanceOf(IOException::class.java)
                fake.failReads = false
                val updates = fake.updateCount.value
                assertThat(store.confirm(third.candidate)).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                assertThat(fake.updateCount.value).isEqualTo(updates)
                checkNotNull(lease).release()
                assertThat(old.exists()).isFalse()
                assertThat(third.candidate.file.exists()).isTrue()
                fake.throwOnUpdate = false
                fake.poisonReadsOnUpdate = false
                val replacement = store.stageContent(inputOf(bytesOf(4)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                candidates.add(replacement.candidate)
                assertThat(store.confirm(replacement.candidate))
                    .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                assertThat(third.candidate.file.exists()).isFalse()
                val foreign = third.candidate.file
                assertThat(foreign.createNewFile()).isTrue()
                val foreignBytes = bytesOf(5, 6, 7)
                foreign.writeBytes(foreignBytes)
                val foreignPin = store.pinSource(foreign)
                try {
                    assertThat(foreign.readBytes()).isEqualTo(foreignBytes)
                } finally {
                    foreignPin.release()
                }
                assertThat(foreign.readBytes()).isEqualTo(foreignBytes)
                assertThat(replacement.candidate.file.exists()).isTrue()
            }
        } finally {
            fake.failReads = false
            fake.throwOnUpdate = false
            fake.poisonReadsOnUpdate = false
            runBlocking {
                try {
                    lease?.release()
                } finally {
                    candidates.forEach {
                        pinFailure(store, it.file)
                        store.discard(it)
                    }
                }
            }
        }
    }

    @Test
    fun cancelledRelease_waitsForSharedLockAndDeletesRetiredFile() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val store = StandbyMediaStore(fake, StandbyMediaValidator { StandbyMediaKind.IMAGE }, dir, dispatcher)
        var staged: StagedCandidate? = null
        var lease: StandbyMediaSourceLease? = null
        var next: StagedCandidate? = null
        val gate = CompletableDeferred<Unit>()
        try {
            runBlocking {
                val first = store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged
                staged = first.candidate
                lease = store.pinSource(first.candidate.file)
                store.discard(first.candidate)
                assertThat(first.candidate.file.exists()).isTrue()
                next = (store.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED)
                    as StandbyStageResult.Staged).candidate
                fake.enteredUpdate = CompletableDeferred()
                fake.beforeCommitGate = gate
                val confirm = async(Dispatchers.IO) { store.confirm(checkNotNull(next)) }
                try {
                    withTimeout(5_000L) { fake.enteredUpdate.await() }
                    val started = CompletableDeferred<Unit>()
                    val releasing = CompletableDeferred<Unit>()
                    val owner = launch(dispatcher) {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            releasing.complete(Unit)
                            checkNotNull(lease).release()
                        }
                    }
                    try {
                        withTimeout(5_000L) { started.await() }
                        owner.cancel()
                        withTimeout(5_000L) { releasing.await() }
                        withTimeout(5_000L) { withContext(dispatcher) { Unit } }
                        assertThat(owner.isCompleted).isFalse()
                        assertThat(first.candidate.file.exists()).isTrue()
                        gate.complete(Unit)
                        assertThat(withTimeout(5_000L) { confirm.await() })
                            .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                        withTimeout(5_000L) { owner.join() }
                        assertThat(first.candidate.file.exists()).isFalse()
                    } finally {
                        gate.complete(Unit)
                        withContext(NonCancellable) { withTimeout(5_000L) { owner.cancelAndJoin() } }
                    }
                } finally {
                    gate.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000L) { confirm.cancelAndJoin() } }
                }
            }
        } finally {
            gate.complete(Unit)
            fake.beforeCommitGate = null
            try {
                runBlocking {
                    try {
                        lease?.release()
                    } finally {
                        staged?.let { store.discard(it) }
                        next?.let { store.discard(it) }
                    }
                }
            } finally {
                dispatcher.close()
            }
        }
    }

    @Test
    fun stageFailure_preservesPinnedFileUntilRelease() {
        val entered = CompletableDeferred<File>()
        val gate = CompletableDeferred<Unit>()
        val store = storeFor(FakePreferencesDataStore(), storageDir(), StandbyMediaValidator { file ->
            entered.complete(file)
            gate.await()
            null
        })
        val pinnedFile = AtomicReference<File?>(null)
        val lease = AtomicReference<StandbyMediaSourceLease?>(null)
        try {
            runBlocking {
                val stage = async(Dispatchers.IO) {
                    store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
                }
                try {
                    val file = withTimeout(5_000L) { entered.await() }
                    pinnedFile.set(file)
                    withTimeout(5_000L) { lease.set(store.pinSource(file)) }
                    gate.complete(Unit)
                    assertThat(withTimeout(5_000L) { stage.await() }).isEqualTo(StandbyStageResult.InvalidContent)
                    assertThat(file.exists()).isTrue()
                    assertThat(pinFailure(store, file)).isInstanceOf(IOException::class.java)
                } finally {
                    gate.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000L) { stage.cancelAndJoin() } }
                }
            }
        } finally {
            gate.complete(Unit)
            runBlocking { lease.get()?.release() }
        }
        assertThat(checkNotNull(pinnedFile.get()).exists()).isFalse()
    }

    @Test
    fun stageFailure_doesNotDeleteCandidateAlreadyActive() {
        val fake = FakePreferencesDataStore()
        val entered = CompletableDeferred<File>()
        val gate = CompletableDeferred<Unit>()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { file ->
            entered.complete(file)
            gate.await()
            null
        })
        val candidate = AtomicReference<StagedCandidate?>(null)
        try {
            runBlocking {
                val stage = async(Dispatchers.IO) {
                    store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
                }
                try {
                    val file = withTimeout(5_000L) { entered.await() }
                    val generation = file.name.removePrefix("standby_").substringBefore('_').toLong()
                    candidate.set(StagedCandidate(generation, file, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED))
                    fake.seed(mutablePreferencesOf(
                        stringPreferencesKey("standby_media_basename") to file.name,
                        stringPreferencesKey("standby_media_kind") to "IMAGE",
                        longPreferencesKey("standby_media_generation") to generation,
                        floatPreferencesKey("standby_framing_center_x") to 0.5f,
                        floatPreferencesKey("standby_framing_center_y") to 0.5f,
                        floatPreferencesKey("standby_framing_zoom") to 1.0f,
                    ))
                    assertThat((store.snapshot() as StandbySnapshot.Available).active.file).isEqualTo(file)
                    gate.complete(Unit)
                    assertThat(withTimeout(5_000L) { stage.await() }).isEqualTo(StandbyStageResult.InvalidContent)
                    assertThat(file.exists()).isTrue()
                    assertThat((store.snapshot() as StandbySnapshot.Available).active.file).isEqualTo(file)
                } finally {
                    gate.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000L) { stage.cancelAndJoin() } }
                }
            }
        } finally {
            fake.seed(emptyPreferences())
            gate.complete(Unit)
            runBlocking { candidate.get()?.let { store.discard(it) } }
        }
    }

    @Test
    fun stageFailure_unreadablePointerPreservesFileButInvalidatesCandidate() {
        val fake = FakePreferencesDataStore()
        val entered = CompletableDeferred<File>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { file ->
            if (calls.incrementAndGet() == 1) {
                entered.complete(file)
                gate.await()
                null
            } else {
                StandbyMediaKind.IMAGE
            }
        })
        val candidate = AtomicReference<StagedCandidate?>(null)
        try {
            runBlocking {
                val stage = async(Dispatchers.IO) {
                    store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
                }
                try {
                    val file = withTimeout(5_000L) { entered.await() }
                    val generation = file.name.removePrefix("standby_").substringBefore('_').toLong()
                    val forged = StagedCandidate(generation, file, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED)
                    candidate.set(forged)
                    fake.failReads = true
                    gate.complete(Unit)
                    assertThat(withTimeout(5_000L) { stage.await() }).isEqualTo(StandbyStageResult.InvalidContent)
                    assertThat(file.exists()).isTrue()
                    fake.failReads = false
                    assertThat(withTimeout(5_000L) { store.confirm(forged) })
                        .isEqualTo(StandbyConfirmResult.CandidateInvalid)
                    assertThat(pinFailure(store, file)).isInstanceOf(IOException::class.java)
                    assertThat(file.exists()).isFalse()
                } finally {
                    fake.failReads = false
                    gate.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000L) { stage.cancelAndJoin() } }
                }
            }
        } finally {
            fake.failReads = false
            gate.complete(Unit)
            runBlocking {
                candidate.get()?.let {
                    pinFailure(store, it.file)
                    store.discard(it)
                }
            }
        }
    }

    @Test
    fun failedUnpinnedDeletion_keepsRetirementUntilRetry() {
        val store = storeFor(FakePreferencesDataStore(), storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val failing = object : File(staged.candidate.file.path) {
            var allowDelete = false
            override fun delete(): Boolean = allowDelete && super.delete()
        }
        val candidate = staged.candidate.copy(file = failing)
        try {
            runBlocking {
                store.discard(candidate)
                assertThat(staged.candidate.file.exists()).isTrue()
                assertThat(pinFailure(store, staged.candidate.file)).isInstanceOf(IOException::class.java)
                assertThat(staged.candidate.file.exists()).isTrue()
                failing.allowDelete = true
                assertThat(pinFailure(store, staged.candidate.file)).isInstanceOf(IOException::class.java)
                assertThat(staged.candidate.file.exists()).isFalse()
            }
        } finally {
            failing.allowDelete = true
            runBlocking {
                pinFailure(store, staged.candidate.file)
                store.discard(candidate)
            }
        }
    }

    @Test
    fun cancelDuringRevalidationCommitsNothing() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val enteredValidation = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        var confirmCalls = 0
        val store = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                confirmCalls += 1
                if (confirmCalls == 2) {
                    enteredValidation.complete(Unit)
                    releaseValidation.await()
                }
                StandbyMediaKind.IMAGE
            },
        )
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(3, 3)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) { store.confirm(staged.candidate) }
                try {
                    withTimeout(5_000L) { enteredValidation.await() }
                    withTimeout(5_000L) { job.cancelAndJoin() }
                } finally {
                    releaseValidation.complete(Unit)
                }
            }
            assertThat(runBlocking { store.snapshot() }).isEqualTo(StandbySnapshot.NeverSet)
            assertThat(staged.candidate.file.exists()).isTrue()
        } finally {
            runBlocking { store.discard(staged.candidate) }
        }
    }

    @Test
    fun deletedDuringRevalidationStaysInvalid() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val enteredValidation = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        var confirmCalls = 0
        val store = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                confirmCalls += 1
                if (confirmCalls == 2) {
                    enteredValidation.complete(Unit)
                    releaseValidation.await()
                }
                StandbyMediaKind.IMAGE
            },
        )
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(4, 4)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val result = runBlocking {
            var outcome: StandbyConfirmResult? = null
            val job = launch(Dispatchers.IO) { outcome = store.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { enteredValidation.await() }
                assertThat(staged.candidate.file.delete()).isTrue()
            } finally {
                releaseValidation.complete(Unit)
            }
            withTimeout(5_000L) { job.join() }
            outcome
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.CandidateInvalid)
        assertThat(fake.updateCount.value).isEqualTo(0)
        assertThat(runBlocking { store.snapshot() }).isEqualTo(StandbySnapshot.NeverSet)
        assertThat(staged.candidate.file.exists()).isFalse()
    }

    @Test
    fun freshSnapshotIsNeverSet() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        assertThat(runBlocking { store.snapshot() }).isEqualTo(StandbySnapshot.NeverSet)
    }

    @Test
    fun stageLeavesActiveUntouched() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val framing = StandbyFraming(0.5f, 0.5f, 1.0f)
        val oldBytes = bytesOf(1, 2, 3)
        commitFirst(store, oldBytes, framing)
        val before = available(store)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(9, 9, 9)), StandbyFraming(0.2f, 0.3f, 2.0f))
        } as StandbyStageResult.Staged
        try {
            val after = available(store)
            assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
            assertThat(after.file?.readBytes()).isEqualTo(oldBytes)
            assertThat(after.framing).isEqualTo(before.framing)
            assertThat(after.generation).isEqualTo(before.generation)
            assertThat(staged.candidate.file.canonicalPath).isNotEqualTo(before.file?.canonicalPath)
            assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(9, 9, 9))
        } finally {
            runBlocking { store.discard(staged.candidate) }
        }
    }

    @Test
    fun confirmCommitsContentKindFrameAndPrunesOld() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.VIDEO })
        val oldFraming = StandbyFraming(0.5f, 0.5f, 1.0f)
        commitFirst(store, bytesOf(1, 1, 1), oldFraming)
        val oldFile = available(store).file
        assertThat(oldFile).isNotNull()
        val newFraming = StandbyFraming(0.2f, 0.7f, 2.5f)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 3, 4, 5)), newFraming)
        } as StandbyStageResult.Staged
        val result = runBlocking { store.confirm(staged.candidate) }
        assertThat(result).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val active = (result as StandbyConfirmResult.Committed).active
        assertThat(active.kind).isEqualTo(StandbyMediaKind.VIDEO)
        assertThat(active.framing).isEqualTo(newFraming)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2, 3, 4, 5))
        assertThat(oldFile?.exists()).isFalse()
        val reread = available(store)
        assertThat(reread.file?.canonicalPath).isEqualTo(active.file?.canonicalPath)
        assertThat(reread.framing).isEqualTo(newFraming)
    }

    @Test
    fun discardRemovesOnlyStagedFile() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        commitFirst(store, bytesOf(4, 5, 6), StandbyFraming.CENTERED)
        val activeBefore = available(store)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(7, 8)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        runBlocking { store.discard(staged.candidate) }
        assertThat(staged.candidate.file.exists()).isFalse()
        val after = available(store)
        assertThat(after.file?.canonicalPath).isEqualTo(activeBefore.file?.canonicalPath)
        assertThat(after.file?.readBytes()).isEqualTo(bytesOf(4, 5, 6))
    }

    @Test
    fun discardAfterConfirmPreservesFreshActive() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1), StandbyFraming.CENTERED)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val result = runBlocking { store.confirm(staged.candidate) }
        assertThat(result).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        runBlocking { store.discard(staged.candidate) }
        val after = available(store)
        assertThat(after.file?.canonicalPath).isEqualTo(staged.candidate.file.canonicalPath)
        assertThat(after.file?.exists()).isTrue()
    }

    @Test
    fun discardDuringForeignConfirmNeverDeletesFreshActive() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val confirmer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val discarder = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(confirmer, bytesOf(1), StandbyFraming.CENTERED)
        val staged = runBlocking {
            confirmer.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        fake.beforeCommitGate = CompletableDeferred()
        fake.enteredUpdate = CompletableDeferred()
        runBlocking {
            val confirmJob = launch(Dispatchers.IO) { confirmer.confirm(staged.candidate) }
            var discardJob: kotlinx.coroutines.Job? = null
            try {
                withTimeout(5_000L) { fake.enteredUpdate.await() }
                val enteredDiscard = CompletableDeferred<Unit>()
                discardJob = launch(Dispatchers.IO) {
                    enteredDiscard.complete(Unit)
                    discarder.discard(staged.candidate)
                }
                withTimeout(5_000L) { enteredDiscard.await() }
            } finally {
                // Release even on timeout so the gated update can never hang the suite.
                fake.beforeCommitGate?.complete(Unit)
            }
            // Discard was queued behind the shared per-directory lock; releasing
            // lets publication prove active first.
            withTimeout(10_000L) {
                confirmJob.join()
                discardJob?.join()
            }
        }
        val after = available(confirmer)
        assertThat(after.file?.canonicalPath).isEqualTo(staged.candidate.file.canonicalPath)
        assertThat(after.file?.exists()).isTrue()
    }

    @Test
    fun invalidContentPreservesPrior() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val committed = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(committed, bytesOf(1, 2), StandbyFraming.CENTERED)
        val before = available(committed)
        val badStore = storeFor(fake, dir, StandbyMediaValidator { null })
        val result = runBlocking { badStore.stageContent(inputOf(bytesOf(9)), StandbyFraming.CENTERED) }
        assertThat(result).isEqualTo(StandbyStageResult.InvalidContent)
        val after = available(committed)
        assertThat(after.file?.readBytes()).isEqualTo(before.file?.readBytes())
        assertThat(after.framing).isEqualTo(before.framing)
    }

    @Test
    fun validatorThrowPreservesPrior() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val good = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(good, bytesOf(3, 3), StandbyFraming.CENTERED)
        val before = available(good)
        val bad = storeFor(fake, dir, StandbyMediaValidator { throw IOException("decode") })
        val result = runBlocking { bad.stageContent(inputOf(bytesOf(8, 8)), StandbyFraming.CENTERED) }
        assertThat(result).isEqualTo(StandbyStageResult.InvalidContent)
        val after = available(good)
        assertThat(after.file?.readBytes()).isEqualTo(before.file?.readBytes())
    }

    @Test
    fun sourceOpenFailurePreservesPrior() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(5), StandbyFraming.CENTERED)
        val before = available(store)
        val result = runBlocking {
            store.stageContent({ throw IOException("open") }, StandbyFraming.CENTERED)
        }
        assertThat(result).isEqualTo(StandbyStageResult.SourceFailed)
        assertThat(available(store).file?.readBytes()).isEqualTo(before.file?.readBytes())
    }

    @Test
    fun streamIoFailurePreservesPrior() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(6, 6), StandbyFraming.CENTERED)
        val before = available(store)
        val broken: () -> InputStream = {
            object : InputStream() {
                override fun read(): Int {
                    throw IOException("stream")
                }
            }
        }
        val result = runBlocking { store.stageContent(broken, StandbyFraming.CENTERED) }
        assertThat(result).isEqualTo(StandbyStageResult.SourceFailed)
        val after = available(store)
        assertThat(after.file?.readBytes()).isEqualTo(before.file?.readBytes())
        assertThat(after.framing).isEqualTo(before.framing)
    }

    /** Unreadable post-failure metadata cannot prove that the staged handle is disposable. */
    @Test
    fun unknownPreCommitOutcome_preservesFullActiveAndCandidateForRetry() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val oldBytes = bytesOf(1, 2, 3)
        val oldFraming = StandbyFraming(0.25f, 0.75f, 1.5f)
        val old = commitFirst(store, oldBytes, oldFraming)
        val before = available(store)
        val beforePreferences = runBlocking { fake.data.first() }
        val beforeOldBytes = old.file.readBytes()
        assertThat(before).isEqualTo(StandbyActive(old.file, StandbyMediaKind.IMAGE, oldFraming, old.generation))
        assertThat(beforeOldBytes).isEqualTo(oldBytes)
        val newBytes = bytesOf(7, 8, 9, 10)
        val newFraming = StandbyFraming(0.75f, 0.25f, 2.0f)
        val staged = runBlocking {
            store.stageContent(inputOf(newBytes), newFraming)
        } as StandbyStageResult.Staged
        val candidate = staged.candidate
        val candidateFile = candidate.file
        val candidateKind = candidate.kind
        val candidateGeneration = candidate.generation
        val candidateFraming = candidate.framing
        val candidateBytes = candidateFile.readBytes()
        assertThat(candidateKind).isEqualTo(StandbyMediaKind.IMAGE)
        assertThat(candidateGeneration).isGreaterThan(before.generation)
        assertThat(candidateFraming).isEqualTo(newFraming)
        assertThat(candidateBytes).isEqualTo(newBytes)
        val updates = fake.updateCount.value
        try {
            fake.throwOnUpdate = true
            fake.commitBeforeThrow = false
            fake.beforeNextUpdateLock.set { fake.failReads = true }
            val result = runBlocking { store.confirm(candidate) }
            assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
            assertThat(fake.beforeNextUpdateLock.get()).isNull()
            assertThat(fake.updateCount.value).isEqualTo(updates + 1)
            assertThat(fake.failReads).isTrue()
            assertThat(old.file.exists()).isTrue()
            assertThat(old.file.readBytes()).isEqualTo(beforeOldBytes)
            assertThat(candidateFile.exists()).isTrue()
            assertThat(candidateFile.readBytes()).isEqualTo(candidateBytes)

            fake.failReads = false
            assertThat(runBlocking { fake.data.first() }).isEqualTo(beforePreferences)
            assertThat(available(store)).isEqualTo(before)
            assertThat(old.file.exists()).isTrue()
            assertThat(old.file.readBytes()).isEqualTo(beforeOldBytes)
            assertThat(candidateFile.exists()).isTrue()
            assertThat(candidateFile.readBytes()).isEqualTo(candidateBytes)
            assertThat(staged.candidate).isSameInstanceAs(candidate)
            assertThat(candidate.file).isEqualTo(candidateFile)
            assertThat(candidate.kind).isEqualTo(candidateKind)
            assertThat(candidate.generation).isEqualTo(candidateGeneration)
            assertThat(candidate.framing).isEqualTo(candidateFraming)

            fake.throwOnUpdate = false
            val expected = StandbyActive(candidateFile, StandbyMediaKind.IMAGE, newFraming, candidateGeneration)
            val retried = runBlocking { store.confirm(candidate) }
            assertThat(retried).isEqualTo(StandbyConfirmResult.Committed(expected))
            assertThat(fake.updateCount.value).isEqualTo(updates + 2)
            assertThat(available(store)).isEqualTo(expected)
            assertThat(candidateFile.exists()).isTrue()
            assertThat(candidateFile.readBytes()).isEqualTo(newBytes)
            assertThat(old.file.exists()).isFalse()
        } finally {
            fake.failReads = false
            fake.throwOnUpdate = false
            fake.beforeNextUpdateLock.set(null)
        }
    }

    @Test
    fun updateBeforeCommitKeepsOldAndReportsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1, 2, 3), StandbyFraming.CENTERED)
        val before = available(store)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(7, 7, 7)), StandbyFraming(0.1f, 0.1f, 2.0f))
        } as StandbyStageResult.Staged
        fake.throwOnUpdate = true
        fake.commitBeforeThrow = false
        val result = runBlocking { store.confirm(staged.candidate) }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(staged.candidate.file.exists()).isFalse()
        val after = available(store)
        assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
        assertThat(after.file?.readBytes()).isEqualTo(bytesOf(1, 2, 3))
    }

    @Test
    fun updateAfterCommitThenThrowStillCommitsAndPrunes() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1), StandbyFraming.CENTERED)
        val oldFile = available(store).file
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming(0.3f, 0.4f, 1.5f))
        } as StandbyStageResult.Staged
        fake.throwOnUpdate = true
        fake.commitBeforeThrow = true
        val result = runBlocking { store.confirm(staged.candidate) }
        assertThat(result).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val active = (result as StandbyConfirmResult.Committed).active
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2, 2))
        assertThat(oldFile?.exists()).isFalse()
    }

    @Test
    fun poisonedRereadPreservesBothFiles() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1, 1), StandbyFraming.CENTERED)
        val oldFile = available(store).file
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        fake.poisonReadsOnUpdate = true
        val result = runBlocking { store.confirm(staged.candidate) }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(oldFile?.exists()).isTrue()
        assertThat(staged.candidate.file.exists()).isTrue()
    }

    @Test
    fun restartSeesConsistentPair() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val first = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        val framing = StandbyFraming(0.25f, 0.75f, 1.5f)
        commitFirst(first, bytesOf(9, 8, 7), framing)
        val expected = available(first)
        val second = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        val restarted = available(second)
        assertThat(restarted.file?.canonicalPath).isEqualTo(expected.file?.canonicalPath)
        assertThat(restarted.file?.readBytes()).isEqualTo(bytesOf(9, 8, 7))
        assertThat(restarted.framing).isEqualTo(framing)
        assertThat(restarted.generation).isEqualTo(expected.generation)
    }

    @Test
    fun staleCandidateCannotReplaceNewer() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val first = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val second = runBlocking {
            store.stageContent(inputOf(bytesOf(2)), StandbyFraming(0.1f, 0.2f, 3.0f))
        } as StandbyStageResult.Staged
        assertThat(second.candidate.generation).isGreaterThan(first.candidate.generation)
        val committed = runBlocking { store.confirm(second.candidate) }
        assertThat(committed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val stale = runBlocking { store.confirm(first.candidate) }
        assertThat(stale).isEqualTo(StandbyConfirmResult.Stale)
        assertThat(first.candidate.file.exists()).isFalse()
        val active = available(store)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2))
        assertThat(active.file?.canonicalPath).isEqualTo(second.candidate.file.canonicalPath)
    }

    @Test
    fun crossInstanceStagesStayUniqueAndNewestWins() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val storeA = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val storeB = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val stagedA = runBlocking {
            val first = async { storeA.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED) }
            val second = async { storeB.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED) }
            listOf(
                first.await() as StandbyStageResult.Staged,
                second.await() as StandbyStageResult.Staged,
            )
        }
        try {
            val generations = stagedA.map { it.candidate.generation }
            assertThat(generations.toSet().size).isEqualTo(2)
            val ordered = stagedA.sortedBy { it.candidate.generation }
            val newerFirst = runBlocking { storeA.confirm(ordered[1].candidate) }
            assertThat(newerFirst).isInstanceOf(StandbyConfirmResult.Committed::class.java)
            val olderLate = runBlocking { storeB.confirm(ordered[0].candidate) }
            assertThat(olderLate).isEqualTo(StandbyConfirmResult.Stale)
            val active = available(storeA)
            assertThat(active.generation).isEqualTo(ordered[1].candidate.generation)
        } finally {
            runBlocking {
                stagedA.forEach { storeA.discard(it.candidate) }
            }
        }
    }

    /**
     * Exercises the shared-lock precheck; [externalCommitAfterPrecheckRejectsOlderInsideTransaction]
     * exercises the transaction-level generation check.
     */
    @Test
    fun crossInstanceConfirmRacesResolveInsideTransaction() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val enteredOlderValidation = CompletableDeferred<Unit>()
        val releaseOlderValidation = CompletableDeferred<Unit>()
        val storeBInspections = AtomicInteger()
        val storeA = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val storeB = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                if (storeBInspections.incrementAndGet() == 2) {
                    enteredOlderValidation.complete(Unit)
                    releaseOlderValidation.await()
                }
                StandbyMediaKind.IMAGE
            },
        )
        val stagedA = runBlocking {
            storeA.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val stagedB = runBlocking {
            storeB.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        assertThat(stagedB.candidate.generation).isGreaterThan(stagedA.candidate.generation)
        val older = stagedA
        val newer = stagedB
        runBlocking {
            val olderLate = async { storeB.confirm(older.candidate) }
            try {
                // Revalidation precedes the shared lock; hold it until the newer pointer is persisted.
                withTimeout(5_000L) { enteredOlderValidation.await() }
                val newerWin = async { storeA.confirm(newer.candidate) }
                assertThat(withTimeout(5_000L) { newerWin.await() })
                    .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                val persisted = fake.data.first()
                assertThat(persisted[stringPreferencesKey("standby_media_basename")])
                    .isEqualTo(newer.candidate.file.name)
                assertThat(persisted[longPreferencesKey("standby_media_generation")])
                    .isEqualTo(newer.candidate.generation)
            } finally {
                releaseOlderValidation.complete(Unit)
            }
            assertThat(withTimeout(5_000L) { olderLate.await() }).isEqualTo(StandbyConfirmResult.Stale)
        }
        // The shared lock resolves this stale result before another update transaction.
        assertThat(fake.updateCount.value).isEqualTo(1)
        val active = available(storeA)
        assertThat(active.file?.canonicalPath).isEqualTo(newer.candidate.file.canonicalPath)
        assertThat(active.generation).isEqualTo(newer.candidate.generation)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2))
        assertThat(older.candidate.file.exists()).isFalse()
    }

    /** A newer external commit cannot be overwritten after the older precheck. */
    @Test
    fun externalCommitAfterPrecheckRejectsOlderInsideTransaction() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val storeA = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val storeB = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val older = runBlocking {
            storeA.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val newerFraming = StandbyFraming(0.25f, 0.75f, 1.5f)
        val newer = runBlocking {
            storeB.stageContent(inputOf(bytesOf(2, 3)), newerFraming)
        } as StandbyStageResult.Staged
        assertThat(newer.candidate.generation).isGreaterThan(older.candidate.generation)
        val enteredUpdate = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        fake.beforeNextUpdateLock.set {
            enteredUpdate.complete(Unit)
            releaseUpdate.await()
        }

        val (result, externalPointer) = runBlocking {
            val olderConfirm = async(Dispatchers.IO) { storeA.confirm(older.candidate) }
            val committed = try {
                withTimeout(5_000L) { enteredUpdate.await() }
                assertThat(fake.updateCount.value).isEqualTo(0)
                assertThat(fake.data.first()).isEqualTo(emptyPreferences())
                // The external writer bypasses the process lock but shares the DataStore mutex.
                val persisted = withTimeout(5_000L) {
                    fake.updateData { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[stringPreferencesKey("standby_media_basename")] = newer.candidate.file.name
                            this[stringPreferencesKey("standby_media_kind")] = newer.candidate.kind.name
                            this[floatPreferencesKey("standby_framing_center_x")] = newerFraming.centerX
                            this[floatPreferencesKey("standby_framing_center_y")] = newerFraming.centerY
                            this[floatPreferencesKey("standby_framing_zoom")] = newerFraming.zoom
                            this[longPreferencesKey("standby_media_generation")] = newer.candidate.generation
                        }.toPreferences()
                    }
                }
                assertThat(fake.updateCount.value).isEqualTo(1)
                assertThat(fake.data.first()).isEqualTo(persisted)
                persisted
            } finally {
                releaseUpdate.complete(Unit)
            }
            withTimeout(5_000L) { olderConfirm.await() } to committed
        }

        assertThat(result).isEqualTo(StandbyConfirmResult.Stale)
        assertThat(fake.updateCount.value).isEqualTo(2)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(externalPointer)
        val active = available(storeB)
        assertThat(active.generation).isEqualTo(newer.candidate.generation)
        assertThat(active.kind).isEqualTo(newer.candidate.kind)
        assertThat(active.framing).isEqualTo(newerFraming)
        assertThat(active.file?.canonicalPath).isEqualTo(newer.candidate.file.canonicalPath)
        assertThat(newer.candidate.file.exists()).isTrue()
        assertThat(newer.candidate.file.readBytes()).isEqualTo(bytesOf(2, 3))
        assertThat(older.candidate.file.exists()).isFalse()
    }

    /** An older staged candidate can commit before its newer replacement. */
    @Test
    fun crossInstanceOlderCommitBeforeNewerCommitIsValid() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val storeA = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val storeB = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val older = runBlocking {
            storeA.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val newerFraming = StandbyFraming(0.25f, 0.75f, 1.5f)
        val newer = runBlocking {
            storeB.stageContent(inputOf(bytesOf(2)), newerFraming)
        } as StandbyStageResult.Staged
        assertThat(newer.candidate.generation).isGreaterThan(older.candidate.generation)

        val firstCommit = runBlocking { storeA.confirm(older.candidate) }
        assertThat(firstCommit).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val firstActive = available(storeB)
        assertThat(firstActive.generation).isEqualTo(older.candidate.generation)
        assertThat(firstActive.file?.canonicalPath).isEqualTo(older.candidate.file.canonicalPath)
        assertThat(firstActive.file?.readBytes()).isEqualTo(bytesOf(1))
        assertThat(newer.candidate.file.exists()).isTrue()

        val secondCommit = runBlocking { storeB.confirm(newer.candidate) }
        assertThat(secondCommit).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val finalActive = available(storeA)
        assertThat(finalActive.generation).isEqualTo(newer.candidate.generation)
        assertThat(finalActive.file?.canonicalPath).isEqualTo(newer.candidate.file.canonicalPath)
        assertThat(finalActive.file?.readBytes()).isEqualTo(bytesOf(2))
        assertThat(finalActive.framing).isEqualTo(newerFraming)
        assertThat(older.candidate.file.exists()).isFalse()
    }

    @Test
    fun corruptMetadataIsBrokenWithoutDeleting() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1, 2), StandbyFraming.CENTERED)
        val owned = available(store).file
        assertThat(owned).isNotNull()
        fake.seed(
            mutablePreferencesOf(
                stringPreferencesKey("standby_media_basename") to owned?.name.orEmpty(),
                stringPreferencesKey("standby_media_kind") to "BOGUS",
                longPreferencesKey("standby_media_generation") to 41L,
                floatPreferencesKey("standby_framing_center_x") to 0.5f,
                floatPreferencesKey("standby_framing_center_y") to 0.5f,
                floatPreferencesKey("standby_framing_zoom") to 1.0f,
            ),
        )
        val persisted = runBlocking { fake.data.first() }
        val snapshot = runBlocking { store.snapshot() }
        assertThat(snapshot).isEqualTo(StandbySnapshot.Broken(StandbyFraming.CENTERED, 0L))
        assertThat(runBlocking { store.validateActive() }).isEqualTo(snapshot)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(owned?.readBytes()).isEqualTo(bytesOf(1, 2))
        val framed = runBlocking { store.updateFraming(StandbyFraming.CENTERED) }
        assertThat(framed).isEqualTo(StandbySnapshot.Unavailable)
        assertThat(owned?.exists()).isTrue()
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(3)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        try {
            assertThat(runBlocking { store.confirm(staged.candidate) })
                .isEqualTo(StandbyConfirmResult.Unavailable)
            assertThat(staged.candidate.file.exists()).isTrue()
            assertThat(owned?.exists()).isTrue()
        } finally {
            staged.candidate.file.delete()
        }
    }

    @Test
    fun throwingMetadataIsUnavailableAndPreservesFiles() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(3, 4), StandbyFraming.CENTERED)
        val owned = available(store).file
        fake.failReads = true
        assertThat(runBlocking { store.snapshot() }).isEqualTo(StandbySnapshot.Unavailable)
        assertThat(owned?.exists()).isTrue()
        assertThat(runBlocking { store.updateFraming(StandbyFraming.CENTERED) })
            .isEqualTo(StandbySnapshot.Unavailable)
        assertThat(owned?.exists()).isTrue()
    }

    @Test
    fun missingActiveFileIsBroken() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val framing = StandbyFraming(0.2f, 0.2f, 1.5f)
        commitFirst(store, bytesOf(1, 2, 3), framing)
        val active = available(store)
        val persisted = runBlocking { fake.data.first() }
        assertThat(active.file?.delete()).isTrue()
        val broken = StandbySnapshot.Broken(framing, active.generation, active.file)
        assertThat(runBlocking { store.snapshot() }).isEqualTo(broken)
        assertThat(runBlocking { store.validateActive() }).isEqualTo(broken)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
    }

    @Test
    fun framedOnlyRecordIsAvailableWithoutMedia() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val framing = StandbyFraming(0.3f, 0.6f, 2.0f)
        val updated = runBlocking { store.updateFraming(framing) }
        assertThat(updated).isInstanceOf(StandbySnapshot.Available::class.java)
        val active = (updated as StandbySnapshot.Available).active
        assertThat(active.hasMedia).isFalse()
        assertThat(active.framing).isEqualTo(framing)
    }

    @Test
    fun forgedOwnedNameCannotCommit() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1), StandbyFraming.CENTERED)
        val before = available(store)
        val forgedFile = File(dir, "standby_999_0123456789abcdef.bin").apply {
            writeBytes(bytesOf(9, 9))
        }
        val forged = StagedCandidate(999L, forgedFile, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED)
        try {
            assertThat(runBlocking { store.confirm(forged) })
                .isEqualTo(StandbyConfirmResult.CandidateInvalid)
            runBlocking { store.discard(forged) }
            assertThat(forgedFile.exists()).isTrue()
            val after = available(store)
            assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
        } finally {
            forgedFile.delete()
        }
    }

    @Test
    fun tamperedStagedBytesFailRevalidation() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val markerStore = storeFor(
            fake,
            dir,
            StandbyMediaValidator { candidate ->
                if (candidate.readBytes().contentEquals(bytesOf(1, 2, 3))) {
                    StandbyMediaKind.IMAGE
                } else {
                    null
                }
            },
        )
        commitFirst(markerStore, bytesOf(1, 2, 3), StandbyFraming.CENTERED)
        val before = available(markerStore)
        val staged = runBlocking {
            markerStore.stageContent(inputOf(bytesOf(1, 2, 3)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        // Tamper after staging: confirmation must revalidate and refuse.
        staged.candidate.file.writeBytes(bytesOf(9, 9, 9))
        assertThat(runBlocking { markerStore.confirm(staged.candidate) })
            .isEqualTo(StandbyConfirmResult.CandidateInvalid)
        val after = available(markerStore)
        assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
        assertThat(after.file?.readBytes()).isEqualTo(bytesOf(1, 2, 3))
        staged.candidate.file.delete()
    }

    @Test
    fun traversalHandleCannotCommit() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1), StandbyFraming.CENTERED)
        val before = available(store)
        val traversal = StagedCandidate(
            1000L,
            File(dir, "../evil.bin"),
            StandbyMediaKind.IMAGE,
            StandbyFraming.CENTERED,
        )
        assertThat(runBlocking { store.confirm(traversal) })
            .isEqualTo(StandbyConfirmResult.CandidateInvalid)
        assertThat(available(store).file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
    }

    @Test
    fun preExistingPatternMatchIsNeverDeleted() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val squatter = File(dir, "standby_1_aaaaaaaaaaaaaaaa.bin").apply {
            writeBytes(bytesOf(5, 5))
        }
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        try {
            runBlocking { store.discard(staged.candidate) }
            assertThat(squatter.exists()).isTrue()
            assertThat(squatter.readBytes()).isEqualTo(bytesOf(5, 5))
            fake.seed(
                mutablePreferencesOf(
                    stringPreferencesKey("standby_media_basename") to squatter.name,
                    stringPreferencesKey("standby_media_kind") to "BOGUS",
                    longPreferencesKey("standby_media_generation") to 7L,
                    floatPreferencesKey("standby_framing_center_x") to 0.5f,
                    floatPreferencesKey("standby_framing_center_y") to 0.5f,
                    floatPreferencesKey("standby_framing_zoom") to 1.0f,
                ),
            )
            assertThat(runBlocking { store.snapshot() })
                .isInstanceOf(StandbySnapshot.Broken::class.java)
            assertThat(squatter.exists()).isTrue()
        } finally {
            staged.candidate.file.delete()
            squatter.delete()
        }
    }

    @Test
    fun stagedNamesAreAlwaysGenerated() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1, 2, 3)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        try {
            assertThat(staged.candidate.file.parentFile?.canonicalPath)
                .isEqualTo(dir.canonicalPath)
            assertThat(staged.candidate.file.name.matches(Regex("^standby_\\d+_[0-9a-f]{16}\\.bin$")))
                .isTrue()
        } finally {
            runBlocking { store.discard(staged.candidate) }
        }
    }

    @Test
    fun validatorRunsOnInjectedDispatcher() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val createdThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "standby-io-probe").also { createdThreads.add(it) }
        }
        val io = executor.asCoroutineDispatcher()
        try {
            val seenThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
            val store = StandbyMediaStore(
                fake,
                StandbyMediaValidator { candidate ->
                    seenThreads.add(Thread.currentThread())
                    if (candidate.exists()) StandbyMediaKind.IMAGE else null
                },
                dir,
                io,
            )
            val callerThread = Thread.currentThread()
            val staged = runBlocking {
                store.stageContent(inputOf(bytesOf(1, 2)), StandbyFraming.CENTERED)
            } as StandbyStageResult.Staged
            try {
                assertThat(createdThreads).hasSize(1)
                assertThat(seenThreads).hasSize(1)
                assertThat(seenThreads[0]).isSameInstanceAs(createdThreads[0])
                assertThat(seenThreads[0]).isNotSameInstanceAs(callerThread)
                val confirmed = runBlocking { store.confirm(staged.candidate) }
                assertThat(confirmed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                assertThat(seenThreads).hasSize(2)
                assertThat(seenThreads[1]).isSameInstanceAs(createdThreads[0])
            } finally {
                runBlocking { store.discard(staged.candidate) }
            }
        } finally {
            io.close()
            executor.shutdown()
        }
    }

    @Test
    fun stageCancellationRetainsOldPair() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1, 2), StandbyFraming.CENTERED)
        val before = available(store)
        val enteredCopy = CompletableDeferred<Unit>()
        val slow: () -> InputStream = {
            object : InputStream() {
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    enteredCopy.complete(Unit)
                    return 0
                }
                override fun read(): Int {
                    enteredCopy.complete(Unit)
                    return 0
                }
            }
        }
        runBlocking {
            val job = launch(Dispatchers.IO) {
                store.stageContent(slow, StandbyFraming.CENTERED)
            }
            // Wait for proof the staged copy loop is running, then cancel pre-confirm.
            withTimeout(5_000L) { enteredCopy.await() }
            job.cancelAndJoin()
        }
        val after = available(store)
        assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
        assertThat(after.file?.readBytes()).isEqualTo(bytesOf(1, 2))
        assertThat(dir.listFiles()?.map { file -> file.name })
            .containsExactly(before.file?.name)
    }

    @Test
    fun confirmSurvivesCancellationAcrossPublication() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(store, bytesOf(1), StandbyFraming.CENTERED)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming(0.4f, 0.6f, 2.0f))
        } as StandbyStageResult.Staged
        fake.beforeCommitGate = CompletableDeferred()
        fake.enteredUpdate = CompletableDeferred()
        runBlocking {
            val job = launch(Dispatchers.IO) {
                store.confirm(staged.candidate)
            }
            try {
                withTimeout(5_000L) { fake.enteredUpdate.await() }
                job.cancel()
            } finally {
                fake.beforeCommitGate?.complete(Unit)
            }
            withTimeout(10_000L) { job.join() }
        }
        fake.beforeCommitGate = null
        // Publication runs NonCancellable: cancellation across it cannot roll
        // back a commit, so the reread must show the committed pair.
        val active = available(store)
        assertThat(active.file?.canonicalPath).isEqualTo(staged.candidate.file.canonicalPath)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2, 2))
        // Cancellation is unrecoverable for the caller: the outcome was only
        // known via this reread, never assumed. Record the limit here.
        val reread = runBlocking { store.snapshot() }
        assertThat(reread).isInstanceOf(StandbySnapshot.Available::class.java)
    }

    @Test
    fun cancelBeforePublicationCommitsNothing() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val plain = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(plain, bytesOf(1), StandbyFraming.CENTERED)
        val before = available(plain)
        // Only the second candidate's confirmation validator waits; the setup
        // commit and stage above must never block on this gate.
        val enteredValidation = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        var confirmCalls = 0
        val store = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                confirmCalls += 1
                if (confirmCalls == 2) {
                    enteredValidation.complete(Unit)
                    releaseValidation.await()
                }
                StandbyMediaKind.IMAGE
            },
        )
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(2, 2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) { store.confirm(staged.candidate) }
                try {
                    withTimeout(5_000L) { enteredValidation.await() }
                    withTimeout(5_000L) { job.cancelAndJoin() }
                } finally {
                    releaseValidation.complete(Unit)
                }
            }
            val after = available(store)
            assertThat(after.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
            assertThat(after.file?.readBytes()).isEqualTo(bytesOf(1))
            assertThat(staged.candidate.file.exists()).isTrue()
        } finally {
            runBlocking { store.discard(staged.candidate) }
        }
    }

    @Test
    fun updateFramingKeepsMediaBytes() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.VIDEO })
        commitFirst(store, bytesOf(8, 8, 8), StandbyFraming.CENTERED)
        val before = available(store)
        val next = StandbyFraming(0.7f, 0.3f, 2.0f)
        val updated = runBlocking { store.updateFraming(next) }
        assertThat(updated).isInstanceOf(StandbySnapshot.Available::class.java)
        val active = (updated as StandbySnapshot.Available).active
        assertThat(active.framing).isEqualTo(next)
        assertThat(active.file?.canonicalPath).isEqualTo(before.file?.canonicalPath)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(8, 8, 8))
    }

    @Test
    fun parallelStagesSerializeGenerations() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            val first = async { store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED) }
            val second = async { store.stageContent(inputOf(bytesOf(2)), StandbyFraming.CENTERED) }
            listOf(
                first.await() as StandbyStageResult.Staged,
                second.await() as StandbyStageResult.Staged,
            )
        }
        try {
            assertThat(staged.map { it.candidate.generation }.toSet().size).isEqualTo(2)
            assertThat(staged.map { it.candidate.file.name }.toSet().size).isEqualTo(2)
        } finally {
            runBlocking {
                staged.forEach { store.discard(it.candidate) }
            }
        }
    }

    @Test
    fun validateDetectsPostCommitCorruptionWithoutDeleting() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val framing = StandbyFraming(0.2f, 0.3f, 1.5f)
        val goodBytes = bytesOf(1, 2, 3)
        val marker = storeFor(
            fake,
            dir,
            StandbyMediaValidator { candidate ->
                if (candidate.readBytes().contentEquals(goodBytes)) StandbyMediaKind.IMAGE else null
            },
        )
        commitFirst(marker, goodBytes, framing)
        val activeBefore = available(marker)
        val persisted = runBlocking { fake.data.first() }
        val fast = runBlocking { marker.snapshot() }
        assertThat(fast).isInstanceOf(StandbySnapshot.Available::class.java)
        activeBefore.file?.writeBytes(bytesOf(9, 9, 9))
        val verdict = runBlocking { marker.validateActive() }
        assertThat(verdict).isInstanceOf(StandbySnapshot.Broken::class.java)
        val broken = verdict as StandbySnapshot.Broken
        assertThat(broken.framing).isEqualTo(framing)
        assertThat(broken.generation).isEqualTo(activeBefore.generation)
        assertThat(broken.failedFile).isEqualTo(activeBefore.file)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(activeBefore.file?.exists()).isTrue()
        assertThat(activeBefore.file?.readBytes()).isEqualTo(bytesOf(9, 9, 9))
        val reread = runBlocking { marker.snapshot() }
        assertThat(reread).isInstanceOf(StandbySnapshot.Available::class.java)
        activeBefore.file?.writeBytes(goodBytes)
    }

    @Test
    fun validateDecoderExceptionIsBrokenWithoutDelete() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val plain = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(plain, bytesOf(4, 5), StandbyFraming.CENTERED)
        val before = available(plain)
        var calls = 0
        val failing = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                calls += 1
                if (calls <= 1) {
                    StandbyMediaKind.IMAGE
                } else {
                    throw IOException("decoder down")
                }
            },
        )
        // First validation proves the good path while the file is intact.
        assertThat(runBlocking { failing.validateActive() })
            .isInstanceOf(StandbySnapshot.Available::class.java)
        assertThat(runBlocking { failing.validateActive() }).isEqualTo(
            StandbySnapshot.Broken(before.framing, before.generation, before.file),
        )
        assertThat(before.file?.exists()).isTrue()
        assertThat(available(plain).file?.readBytes()).isEqualTo(bytesOf(4, 5))
    }

    @Test
    fun validateKindMismatchReportsOriginalFileWithoutChangingData() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val active = commitFirst(writer, bytesOf(6, 7), StandbyFraming.CENTERED)
        val persisted = runBlocking { fake.data.first() }
        val reader = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.VIDEO })

        assertThat(runBlocking { reader.validateActive() }).isEqualTo(
            StandbySnapshot.Broken(active.framing, active.generation, active.file),
        )
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(active.file.readBytes()).isEqualTo(bytesOf(6, 7))
    }

    @Test
    fun validateDecoderCancellationPropagatesWithoutChangingData() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val active = commitFirst(writer, bytesOf(8, 9), StandbyFraming.CENTERED)
        val persisted = runBlocking { fake.data.first() }
        val reader = storeFor(fake, dir, StandbyMediaValidator {
            throw CancellationException("cancel decode")
        })

        val error = runCatching { runBlocking { reader.validateActive() } }.exceptionOrNull()
        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(active.file.readBytes()).isEqualTo(bytesOf(8, 9))
    }

    @Test
    fun validateGoodActiveStaysAvailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        commitFirst(store, bytesOf(7, 7, 7), StandbyFraming(0.4f, 0.4f, 2.0f))
        val verdict = runBlocking { store.validateActive() }
        assertThat(verdict).isInstanceOf(StandbySnapshot.Available::class.java)
        val active = (verdict as StandbySnapshot.Available).active
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(7, 7, 7))
        assertThat(active.kind).isEqualTo(StandbyMediaKind.GIF)
    }

    @Test
    fun validatePassesThroughNeverSetAndUnavailable() {
        val emptyFake = FakePreferencesDataStore()
        val emptyStore = storeFor(emptyFake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        assertThat(runBlocking { emptyStore.validateActive() })
            .isEqualTo(StandbySnapshot.NeverSet)
        val brokenFake = FakePreferencesDataStore()
        val brokenDir = storageDir()
        val brokenStore = storeFor(brokenFake, brokenDir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(brokenStore, bytesOf(1), StandbyFraming.CENTERED)
        brokenFake.failReads = true
        assertThat(runBlocking { brokenStore.validateActive() })
            .isEqualTo(StandbySnapshot.Unavailable)
    }

    @Test
    fun validateDuringReplacementDecodesFreshPointer() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val enteredDecode = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val decodedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
        var validations = 0
        val validator = StandbyMediaValidator { candidate ->
            validations += 1
            if (validations == 3) {
                enteredDecode.complete(Unit)
                releaseDecode.await()
            }
            decodedPaths.add(candidate.canonicalPath)
            if (candidate.readBytes().contentEquals(bytesOf(1))) {
                StandbyMediaKind.IMAGE
            } else {
                StandbyMediaKind.VIDEO
            }
        }
        val reader = storeFor(fake, dir, validator)
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.VIDEO })
        commitFirst(reader, bytesOf(1), StandbyFraming.CENTERED)
        val newStaged = runBlocking {
            writer.stageContent(inputOf(bytesOf(2, 2, 2)), StandbyFraming(0.6f, 0.6f, 3.0f))
        } as StandbyStageResult.Staged
        fake.beforeCommitGate = CompletableDeferred()
        fake.enteredUpdate = CompletableDeferred()
        val validated = runBlocking {
            val validateJob = async(Dispatchers.IO) { reader.validateActive() }
            try {
                withTimeout(5_000L) { enteredDecode.await() }
                val confirmJob = launch(Dispatchers.IO) { writer.confirm(newStaged.candidate) }
                try {
                    withTimeout(5_000L) { fake.enteredUpdate.await() }
                } finally {
                    fake.beforeCommitGate?.complete(Unit)
                }
                // Release the new commit first, then the old-bytes decode: the
                // stale decode must retry against the fresh pointer.
                withTimeout(5_000L) { confirmJob.join() }
                releaseDecode.complete(Unit)
                withTimeout(10_000L) { validateJob.await() }
            } finally {
                fake.beforeCommitGate?.complete(Unit)
                if (!releaseDecode.isCompleted) {
                    releaseDecode.complete(Unit)
                }
            }
        }
        val fresh = available(writer)
        assertThat(fresh.file?.canonicalPath).isEqualTo(newStaged.candidate.file.canonicalPath)
        assertThat(validated).isInstanceOf(StandbySnapshot.Available::class.java)
        val validatedActive = (validated as StandbySnapshot.Available).active
        assertThat(validatedActive.file?.canonicalPath).isEqualTo(fresh.file?.canonicalPath)
        assertThat(validatedActive.generation).isEqualTo(fresh.generation)
        assertThat(decodedPaths).contains(fresh.file?.canonicalPath)
    }

    @Test
    fun rejectedOldDecodeAfterReplacementValidatesNewFile() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val first = commitFirst(writer, bytesOf(1), StandbyFraming.CENTERED)
        val next = runBlocking {
            writer.stageContent(inputOf(bytesOf(2)), StandbyFraming(0.6f, 0.4f, 2.0f))
        } as StandbyStageResult.Staged
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inspected = java.util.Collections.synchronizedList(mutableListOf<File>())
        val reader = storeFor(fake, dir, StandbyMediaValidator { file ->
            inspected.add(file)
            if (file == first.file) {
                entered.complete(Unit)
                release.await()
                null
            } else if (file == next.candidate.file) {
                StandbyMediaKind.IMAGE
            } else {
                null
            }
        })

        val (validated, committedPrefs) = runBlocking {
            val job = async(Dispatchers.IO) { reader.validateActive() }
            val prefs = try {
                withTimeout(5_000L) { entered.await() }
                val confirmed = withTimeout(5_000L) { writer.confirm(next.candidate) }
                assertThat(confirmed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                fake.data.first()
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { job.await() } to prefs
        }
        val current = available(writer)
        assertThat(validated).isEqualTo(StandbySnapshot.Available(current))
        assertThat(current.file).isEqualTo(next.candidate.file)
        assertThat(current.generation).isEqualTo(next.candidate.generation)
        assertThat(inspected).containsExactly(first.file, next.candidate.file).inOrder()
        assertThat(runBlocking { fake.data.first() }).isEqualTo(committedPrefs)
        assertThat(next.candidate.file.readBytes()).isEqualTo(bytesOf(2))
    }

    @Test
    fun validateReplacementThenCorruptIsBrokenNotAvailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val enteredDecode = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        // First reader inspect is the verify-old-active decode: setup uses
        // the independent plain writer, so no setup call touches this gate.
        val readerDecodes = AtomicInteger(0)
        val validator = StandbyMediaValidator { candidate ->
            if (readerDecodes.incrementAndGet() == 1) {
                enteredDecode.complete(Unit)
                releaseDecode.await()
            }
            val bytes = candidate.readBytes()
            if (bytes.contentEquals(bytesOf(1))) {
                StandbyMediaKind.IMAGE
            } else {
                null
            }
        }
        val plain = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(plain, bytesOf(1), StandbyFraming.CENTERED)
        val reader = storeFor(fake, dir, validator)
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val framing = StandbyFraming(0.6f, 0.4f, 2.0f)
        val newStaged = runBlocking {
            writer.stageContent(inputOf(bytesOf(1)), framing)
        } as StandbyStageResult.Staged
        fake.beforeCommitGate = CompletableDeferred()
        fake.enteredUpdate = CompletableDeferred()
        val validated = runBlocking {
            val validateJob = async(Dispatchers.IO) { reader.validateActive() }
            try {
                withTimeout(5_000L) { enteredDecode.await() }
                val confirmJob = launch(Dispatchers.IO) { writer.confirm(newStaged.candidate) }
                try {
                    withTimeout(5_000L) { fake.enteredUpdate.await() }
                } finally {
                    fake.beforeCommitGate?.complete(Unit)
                }
                withTimeout(5_000L) { confirmJob.join() }
                // Corrupt the fresh file after its commit but before the stale
                // old decode resolves; the retry must decode the new bytes.
                newStaged.candidate.file.writeBytes(bytesOf(9, 9, 9))
                releaseDecode.complete(Unit)
                withTimeout(10_000L) { validateJob.await() }
            } finally {
                fake.beforeCommitGate?.complete(Unit)
                if (!releaseDecode.isCompleted) {
                    releaseDecode.complete(Unit)
                }
            }
        }
        assertThat(validated).isInstanceOf(StandbySnapshot.Broken::class.java)
        val broken = validated as StandbySnapshot.Broken
        assertThat(broken.framing).isEqualTo(framing)
        assertThat(broken.generation).isEqualTo(newStaged.candidate.generation)
        assertThat(broken.failedFile).isEqualTo(newStaged.candidate.file)
        assertThat(newStaged.candidate.file.readBytes()).isEqualTo(bytesOf(9, 9, 9))
        assertThat(available(writer).file?.canonicalPath)
            .isEqualTo(newStaged.candidate.file.canonicalPath)
        newStaged.candidate.file.writeBytes(bytesOf(1))
    }

    @Test
    fun validateChurnBeyondRetryIsUnavailableWithoutDelete() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        // Every stored file decodes fine; churn alone must force Unavailable
        // rather than an undecoded Available. Four per-round gates match the
        // production VALIDATE_RETRIES bound: each decode parks, then the
        // writer publishes the next pointer before that decode resolves.
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        commitFirst(writer, bytesOf(1), StandbyFraming.CENTERED)
        val entered = Array(4) { CompletableDeferred<Unit>() }
        val release = Array(4) { CompletableDeferred<Unit>() }
        val round = AtomicInteger(0)
        val racing = storeFor(
            fake,
            dir,
            StandbyMediaValidator { _ ->
                val current = round.getAndIncrement()
                if (current < entered.size) {
                    entered[current].complete(Unit)
                    release[current].await()
                }
                StandbyMediaKind.IMAGE
            },
        )
        var lastCandidate: StagedCandidate? = null
        val validated = runBlocking {
            val validateJob = async(Dispatchers.IO) { racing.validateActive() }
            try {
                repeat(entered.size) { index ->
                    withTimeout(5_000L) { entered[index].await() }
                    val next = writer.stageContent(
                        inputOf(bytesOf(10 + index)),
                        StandbyFraming(0.2f, 0.2f, 1.2f),
                    ) as StandbyStageResult.Staged
                    val confirmed = withTimeout(10_000L) {
                        writer.confirm(next.candidate, next.candidate.framing)
                    }
                    assertThat(confirmed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                    lastCandidate = next.candidate
                    release[index].complete(Unit)
                }
                withTimeout(10_000L) { validateJob.await() }
            } finally {
                release.forEach { gate ->
                    if (!gate.isCompleted) {
                        gate.complete(Unit)
                    }
                }
            }
        }
        assertThat(validated).isEqualTo(StandbySnapshot.Unavailable)
        // Replaced old files follow normal prune-on-proven-commit semantics;
        // the claim here is only that churn never deletes the final active
        // file or its metadata.
        val final = lastCandidate
        assertThat(final).isNotNull()
        assertThat(final?.file?.exists()).isTrue()
        val reread = runBlocking { writer.snapshot() }
        assertThat(reread).isInstanceOf(StandbySnapshot.Available::class.java)
        val rereadActive = (reread as StandbySnapshot.Available).active
        assertThat(rereadActive.file?.canonicalPath).isEqualTo(final?.file?.canonicalPath)
    }

    @Test
    fun discardDuringRevalidationCannotPublishDeletedFile() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            StandbyMediaKind.IMAGE
        })
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(1, 2)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val persisted = runBlocking { fake.data.first() }
        val result = runBlocking {
            val confirming = async(Dispatchers.IO) { slow.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { entered.await() }
                withTimeout(5_000L) { writer.discard(staged.candidate) }
                assertThat(staged.candidate.file.exists()).isFalse()
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() }
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.CandidateInvalid)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(0)
        assertThat(runBlocking { writer.snapshot() }).isEqualTo(StandbySnapshot.NeverSet)
    }

    @Test
    fun failedRevalidationAfterConcurrentCommitIsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            null
        })
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(3, 4)), StandbyFraming(0.2f, 0.4f, 1.5f))
        } as StandbyStageResult.Staged
        var persisted: Preferences = emptyPreferences()
        val result = runBlocking {
            val confirming = async(Dispatchers.IO) { slow.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { entered.await() }
                assertThat(withTimeout(5_000L) { writer.confirm(staged.candidate) })
                    .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                persisted = fake.data.first()
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() }
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(3, 4))
    }

    @Test
    fun failedRevalidationAfterConcurrentReplacementIsStale() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            null
        })
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(3, 4)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val (result, persisted) = runBlocking {
            val confirming = async(Dispatchers.IO) { slow.confirm(staged.candidate) }
            val saved = try {
                withTimeout(5_000L) { entered.await() }
                assertThat(withTimeout(5_000L) { writer.confirm(staged.candidate) })
                    .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                val newer = withTimeout(5_000L) {
                    writer.stageContent(inputOf(bytesOf(5, 6)), StandbyFraming.CENTERED)
                } as StandbyStageResult.Staged
                assertThat(withTimeout(5_000L) { writer.confirm(newer.candidate) })
                    .isInstanceOf(StandbyConfirmResult.Committed::class.java)
                fake.data.first()
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() } to saved
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.Stale)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(2)
        assertThat(available(writer).file?.readBytes()).isEqualTo(bytesOf(5, 6))
    }

    @Test
    fun activeFileDeletedDuringRevalidationIsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            StandbyMediaKind.IMAGE
        })
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(7, 8)), StandbyFraming(0.3f, 0.4f, 1.5f))
        } as StandbyStageResult.Staged
        val committed = runBlocking { writer.confirm(staged.candidate) } as StandbyConfirmResult.Committed
        val persisted = runBlocking { fake.data.first() }
        val result = runBlocking {
            val confirming = async(Dispatchers.IO) { slow.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { entered.await() }
                assertThat(staged.candidate.file.delete()).isTrue()
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() }
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(runBlocking { writer.snapshot() }).isEqualTo(
            StandbySnapshot.Broken(committed.active.framing, committed.active.generation, staged.candidate.file),
        )
    }

    @Test
    fun corruptPointerDuringFailedRevalidationPreservesFiles() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            null
        })
        val active = commitFirst(writer, bytesOf(9, 10), StandbyFraming.CENTERED)
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(11, 12)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val corrupt = runBlocking { fake.data.first() }.toMutablePreferences().apply {
            this[stringPreferencesKey("standby_media_kind")] = "unknown"
        }.toPreferences()
        val result = runBlocking {
            val confirming = async(Dispatchers.IO) { slow.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { entered.await() }
                fake.seed(corrupt)
            } finally {
                release.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() }
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(corrupt)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(active.file.readBytes()).isEqualTo(bytesOf(9, 10))
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(11, 12))
    }

    @Test
    fun malformedMediaPointerPreservesMetadataAndFiles() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val active = commitFirst(store, bytesOf(16, 17), StandbyFraming.CENTERED)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(18, 19)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val persisted = runBlocking { fake.data.first() }
        val wrongGenerationName = "standby_${active.generation + 10}_0123456789abcdef.bin"
        val unrelated = File(active.file.parentFile, wrongGenerationName).apply {
            writeBytes(bytesOf(20, 21))
        }
        val malformedNames = listOf(
            "../outside.bin",
            "standby_${active.generation}_0123456789abcdeg.bin",
            wrongGenerationName,
        )
        for (name in malformedNames) {
            val corrupt = persisted.toMutablePreferences().apply {
                this[stringPreferencesKey("standby_media_basename")] = name
            }.toPreferences()
            fake.seed(corrupt)
            val broken = StandbySnapshot.Broken(active.framing, active.generation)
            assertThat(runBlocking { store.snapshot() }).isEqualTo(broken)
            assertThat(runBlocking { store.validateActive() }).isEqualTo(broken)
            assertThat(runBlocking { store.confirm(staged.candidate) })
                .isEqualTo(StandbyConfirmResult.Unavailable)
            runBlocking { store.discard(staged.candidate) }
            assertThat(runBlocking { store.updateFraming(StandbyFraming.CENTERED) })
                .isEqualTo(StandbySnapshot.Unavailable)
            assertThat(runBlocking { fake.data.first() }).isEqualTo(corrupt)
            assertThat(fake.updateCount.value).isEqualTo(1)
            assertThat(active.file.readBytes()).isEqualTo(bytesOf(16, 17))
            assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(18, 19))
            assertThat(unrelated.readBytes()).isEqualTo(bytesOf(20, 21))
        }
    }

    @Test
    fun corruptPointerDuringAmbiguousPublishPreservesFiles() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val active = commitFirst(store, bytesOf(22, 23), StandbyFraming.CENTERED)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(24, 25)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val corrupt = runBlocking { fake.data.first() }.toMutablePreferences().apply {
            this[stringPreferencesKey("standby_media_basename")] = "../outside.bin"
            this[longPreferencesKey("standby_media_generation")] = staged.candidate.generation
        }.toPreferences()
        fake.throwOnUpdate = true
        fake.enteredUpdate = CompletableDeferred()
        fake.beforeCommitGate = CompletableDeferred()
        val result = runBlocking {
            val confirming = async(Dispatchers.IO) { store.confirm(staged.candidate) }
            try {
                withTimeout(5_000L) { fake.enteredUpdate.await() }
                fake.seed(corrupt)
            } finally {
                fake.beforeCommitGate?.complete(Unit)
            }
            withTimeout(5_000L) { confirming.await() }
        }
        assertThat(result).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(corrupt)
        assertThat(active.file.readBytes()).isEqualTo(bytesOf(22, 23))
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(24, 25))
    }

    @Test
    fun concurrentDuplicateReturnsFirstPersistedFraming() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val slow = storeFor(fake, dir, StandbyMediaValidator {
            entered.complete(Unit)
            release.await()
            StandbyMediaKind.IMAGE
        })
        val staged = runBlocking {
            writer.stageContent(inputOf(bytesOf(5, 6)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val persistedFraming = StandbyFraming(0.2f, 0.4f, 1.5f)
        val (first, second) = runBlocking {
            val confirming = async(Dispatchers.IO) {
                slow.confirm(staged.candidate, StandbyFraming(0.7f, 0.8f, 2f))
            }
            val committed = try {
                withTimeout(5_000L) { entered.await() }
                withTimeout(5_000L) { writer.confirm(staged.candidate, persistedFraming) }
            } finally {
                release.complete(Unit)
            }
            committed to withTimeout(5_000L) { confirming.await() }
        }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        assertThat(second).isEqualTo(first)
        assertThat((first as StandbyConfirmResult.Committed).active.framing).isEqualTo(persistedFraming)
        assertThat(available(writer)).isEqualTo(first.active)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(5, 6))
    }

    @Test
    fun duplicateConfirmSameCandidateReturnsPersistedActive() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val validations = AtomicInteger()
        val store = storeFor(fake, dir, StandbyMediaValidator {
            validations.incrementAndGet()
            StandbyMediaKind.IMAGE
        })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1, 2)), StandbyFraming(0.2f, 0.3f, 1.5f))
        } as StandbyStageResult.Staged
        val first = runBlocking { store.confirm(staged.candidate) }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val other = runBlocking {
            store.stageContent(inputOf(bytesOf(3, 4)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val persisted = runBlocking { fake.data.first() }
        val writesAfterFirst = fake.updateCount.value
        val validationsBeforeSecond = validations.get()
        val second = runBlocking { store.confirm(staged.candidate) }
        assertThat(validations.get()).isEqualTo(validationsBeforeSecond + 1)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(other.candidate.file.readBytes()).isEqualTo(bytesOf(3, 4))
        assertThat(second).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val firstActive = (first as StandbyConfirmResult.Committed).active
        val secondActive = (second as StandbyConfirmResult.Committed).active
        assertThat(secondActive.file?.canonicalPath).isEqualTo(firstActive.file?.canonicalPath)
        assertThat(secondActive.kind).isEqualTo(firstActive.kind)
        assertThat(secondActive.framing).isEqualTo(firstActive.framing)
        assertThat(secondActive.generation).isEqualTo(firstActive.generation)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(staged.candidate.file.exists()).isTrue()
        val live = available(store)
        assertThat(live.file?.canonicalPath).isEqualTo(firstActive.file?.canonicalPath)
    }

    @Test
    fun duplicateConfirmDifferentFramingKeepsPersistedFraming() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.VIDEO })
        val persistedFraming = StandbyFraming(0.2f, 0.3f, 1.5f)
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(4, 5)), persistedFraming)
        } as StandbyStageResult.Staged
        val first = runBlocking { store.confirm(staged.candidate, persistedFraming) }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val writesAfterFirst = fake.updateCount.value
        val otherFraming = StandbyFraming(0.7f, 0.6f, 2.5f)
        val second = runBlocking { store.confirm(staged.candidate, otherFraming) }
        assertThat(second).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val secondActive = (second as StandbyConfirmResult.Committed).active
        assertThat(secondActive.framing).isEqualTo(persistedFraming)
        assertThat(secondActive.kind).isEqualTo(StandbyMediaKind.VIDEO)
        assertThat(secondActive.file?.canonicalPath)
            .isEqualTo((first as StandbyConfirmResult.Committed).active.file?.canonicalPath)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(staged.candidate.file.exists()).isTrue()
        assertThat(available(store).framing).isEqualTo(persistedFraming)
    }

    @Test
    fun concurrentDuplicateConfirmPublishesOnceBothTruthful() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(7, 8)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val (first, second) = runBlocking {
            val one = async(Dispatchers.IO) { store.confirm(staged.candidate) }
            val two = async(Dispatchers.IO) { store.confirm(staged.candidate) }
            Pair(withTimeout(10_000L) { one.await() }, withTimeout(10_000L) { two.await() })
        }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        assertThat(second).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val firstActive = (first as StandbyConfirmResult.Committed).active
        val secondActive = (second as StandbyConfirmResult.Committed).active
        assertThat(secondActive.file?.canonicalPath).isEqualTo(firstActive.file?.canonicalPath)
        assertThat(secondActive.generation).isEqualTo(firstActive.generation)
        assertThat(secondActive.framing).isEqualTo(firstActive.framing)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.exists()).isTrue()
    }

    @Test
    fun duplicateConfirmAfterCommitValidatorReturnsNullIsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        var validatorFail = false
        val store = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                if (validatorFail) null else StandbyMediaKind.IMAGE
            },
        )
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(1, 2, 3)), StandbyFraming(0.2f, 0.3f, 1.5f))
        } as StandbyStageResult.Staged
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val other = runBlocking {
            store.stageContent(inputOf(bytesOf(4, 5)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val persisted = runBlocking { fake.data.first() }
        val writesAfterFirst = fake.updateCount.value
        validatorFail = true
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(1, 2, 3))
        assertThat(other.candidate.file.readBytes()).isEqualTo(bytesOf(4, 5))
    }

    @Test
    fun duplicateConfirmAfterCommitValidatorThrowsIsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        var validatorThrow = false
        val store = storeFor(
            fake,
            dir,
            StandbyMediaValidator {
                if (validatorThrow) throw IOException("decoder fail") else StandbyMediaKind.VIDEO
            },
        )
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(4, 5, 6)), StandbyFraming(0.4f, 0.5f, 1.8f))
        } as StandbyStageResult.Staged
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val persisted = runBlocking { fake.data.first() }
        val writesAfterFirst = fake.updateCount.value
        validatorThrow = true
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(4, 5, 6))
    }

    @Test
    fun duplicateConfirmAfterCommitFileMissingIsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.GIF })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(7, 8, 9)), StandbyFraming(0.6f, 0.7f, 2.0f))
        } as StandbyStageResult.Staged
        val first = runBlocking { store.confirm(staged.candidate) }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val firstActive = (first as StandbyConfirmResult.Committed).active
        val persisted = runBlocking { fake.data.first() }
        val writesAfterFirst = fake.updateCount.value
        assertThat(staged.candidate.file.delete()).isTrue()
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(staged.candidate.file.exists()).isFalse()
        assertThat(runBlocking { store.snapshot() })
            .isEqualTo(StandbySnapshot.Broken(firstActive.framing, firstActive.generation, firstActive.file))
    }

    @Test
    fun duplicateConfirmUnreadablePointerReturnsUnavailable() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(10, 11)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val first = runBlocking { store.confirm(staged.candidate) }
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val persisted = runBlocking { fake.data.first() }
        val writesAfterFirst = fake.updateCount.value
        fake.failReads = true
        val second = runBlocking { store.confirm(staged.candidate) }
        assertThat(second).isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { store.snapshot() }).isEqualTo(StandbySnapshot.Unavailable)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(10, 11))
        fake.failReads = false
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(writesAfterFirst)
        assertThat(available(store)).isEqualTo((first as StandbyConfirmResult.Committed).active)
    }

    @Test
    fun duplicateConfirmWithChangedDecodedKindIsUnavailable() {
        val fake = FakePreferencesDataStore()
        var decodedKind = StandbyMediaKind.IMAGE
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { decodedKind })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(12, 13)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val persisted = runBlocking { fake.data.first() }
        decodedKind = StandbyMediaKind.VIDEO
        assertThat(runBlocking { store.confirm(staged.candidate) })
            .isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(12, 13))
    }

    @Test
    fun forgedGenerationCannotRepublishOrDeleteCandidate() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(14, 15)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val forged = staged.candidate.copy(generation = staged.candidate.generation + 1)
        assertThat(runBlocking { store.confirm(forged) })
            .isEqualTo(StandbyConfirmResult.CandidateInvalid)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(14, 15))
        val committed = runBlocking { store.confirm(staged.candidate) }
        assertThat(committed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val persisted = runBlocking { fake.data.first() }
        assertThat(runBlocking { store.confirm(forged) })
            .isEqualTo(StandbyConfirmResult.Unavailable)
        assertThat(runBlocking {
            store.confirm(staged.candidate.copy(kind = StandbyMediaKind.VIDEO))
        }).isEqualTo(committed)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.readBytes()).isEqualTo(bytesOf(14, 15))
    }

    @Test
    fun previouslyCommittedThenReplacedCandidateIsStale() {
        val fake = FakePreferencesDataStore()
        val store = storeFor(fake, storageDir(), StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val old = commitFirst(store, bytesOf(20), StandbyFraming.CENTERED)
        val newer = runBlocking {
            store.stageContent(inputOf(bytesOf(21)), StandbyFraming(0.2f, 0.3f, 2f))
        } as StandbyStageResult.Staged
        val committed = runBlocking { store.confirm(newer.candidate) }
        assertThat(committed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        assertThat(old.file.exists()).isFalse()
        val persisted = runBlocking { fake.data.first() }
        assertThat(runBlocking { store.confirm(old) }).isEqualTo(StandbyConfirmResult.Stale)
        assertThat(runBlocking { fake.data.first() }).isEqualTo(persisted)
        assertThat(fake.updateCount.value).isEqualTo(2)
        assertThat(newer.candidate.file.readBytes()).isEqualTo(bytesOf(21))
    }

    @Test
    fun differentCandidateStaleStillProtected() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val firstStaged = runBlocking {
            store.stageContent(inputOf(bytesOf(1)), StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        val secondStaged = runBlocking {
            store.stageContent(inputOf(bytesOf(2)), StandbyFraming(0.1f, 0.2f, 3.0f))
        } as StandbyStageResult.Staged
        // Commit the newer (higher generation) candidate
        assertThat(secondStaged.candidate.generation).isGreaterThan(firstStaged.candidate.generation)
        val committed = runBlocking { store.confirm(secondStaged.candidate) }
        assertThat(committed).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        // Older candidate must still be Stale (not CandidateInvalid)
        val stale = runBlocking { store.confirm(firstStaged.candidate) }
        assertThat(stale).isEqualTo(StandbyConfirmResult.Stale)
        assertThat(firstStaged.candidate.file.exists()).isFalse()
        val active = available(store)
        assertThat(active.file?.readBytes()).isEqualTo(bytesOf(2))
    }

    @Test
    fun concurrentFirstCommitsSecondSeesActive() {
        val fake = FakePreferencesDataStore()
        val dir = storageDir()
        val store = storeFor(fake, dir, StandbyMediaValidator { StandbyMediaKind.IMAGE })
        val staged = runBlocking {
            store.stageContent(inputOf(bytesOf(99, 98)), StandbyFraming(0.5f, 0.5f, 1.5f))
        } as StandbyStageResult.Staged
        // Two concurrent confirms; one will commit, the other must see it as active
        val (first, second) = runBlocking {
            val one = async(Dispatchers.IO) { store.confirm(staged.candidate) }
            val two = async(Dispatchers.IO) { store.confirm(staged.candidate) }
            Pair(withTimeout(10_000L) { one.await() }, withTimeout(10_000L) { two.await() })
        }
        // Both must be Committed (one via publish, one via idempotent)
        assertThat(first).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        assertThat(second).isInstanceOf(StandbyConfirmResult.Committed::class.java)
        val firstActive = (first as StandbyConfirmResult.Committed).active
        val secondActive = (second as StandbyConfirmResult.Committed).active
        assertThat(secondActive.file?.canonicalPath).isEqualTo(firstActive.file?.canonicalPath)
        assertThat(secondActive.generation).isEqualTo(firstActive.generation)
        assertThat(secondActive.framing).isEqualTo(firstActive.framing)
        // Only ONE publish happened
        assertThat(fake.updateCount.value).isEqualTo(1)
        assertThat(staged.candidate.file.exists()).isTrue()
    }
}
