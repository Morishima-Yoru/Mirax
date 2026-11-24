package me.trinitrix.mirax.data.standby

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active standby media pointer plus the single shared framing.
 *
 * [file] is `null` when no usable custom media is active. In that case the
 * surface falls back to the selected theme and [framing] is the last known or
 * default value. [generation] orders media commits; larger wins.
 *
 * @param file store-owned app-private copy, or `null` when absent.
 * @param kind playback kind for [file], or `null` when absent.
 * @param framing single shared spatial framing.
 * @param generation monotonic media generation, `0` when never committed.
 */
data class StandbyActive(
    val file: File?,
    val kind: StandbyMediaKind?,
    val framing: StandbyFraming,
    val generation: Long,
) {
    /** True only when both [file] and [kind] are present. */
    val hasMedia: Boolean = file != null && kind != null
}

/**
 * Store-owned staged candidate awaiting explicit user confirmation.
 *
 * This handle is untrusted: callers can forge it, and the staged bytes can
 * be replaced after staging. [StandbyMediaStore.confirm] therefore re-runs
 * the injected validator on the IO dispatcher immediately before publish
 * and commits only freshly validated bytes, so a forged or tampered handle
 * can never publish undecodable content. No same-UID filesystem adversary
 * (including root) is defended against; the guarantee is only that
 * confirmation never trusts the staged claim.
 *
 * @param generation ordering token; stale tokens never replace newer commits.
 * @param file staged app-private copy for preview and validation only.
 * @param kind kind observed at staging time; confirmation revalidates.
 * @param framing proposed framing captured at staging time.
 */
data class StagedCandidate(
    val generation: Long,
    val file: File,
    val kind: StandbyMediaKind,
    val framing: StandbyFraming,
)

/**
 * Outcome of [StandbyMediaStore.stageContent].
 */
sealed interface StandbyStageResult {
    /**
     * Candidate staged, validated, and ready for preview.
     *
     * No active metadata changed.
     */
    data class Staged(val candidate: StagedCandidate) : StandbyStageResult

    /** Bytes are not usable; validator returned `null` or threw. */
    data object InvalidContent : StandbyStageResult

    /** Source could not be opened or streamed; prior pair untouched. */
    data object SourceFailed : StandbyStageResult
}

/**
 * Outcome of [StandbyMediaStore.confirm].
 */
sealed interface StandbyConfirmResult {
    /** Committed; idempotent duplicate returns persisted active without another commit. */
    data class Committed(val active: StandbyActive) : StandbyConfirmResult

    /** Different newer or equal-generation candidate is active; safe orphans may be pruned. */
    data object Stale : StandbyConfirmResult

    /** Candidate was not committed and cannot be published. */
    data object CandidateInvalid : StandbyConfirmResult

    /**
     * Ambiguous transaction, unreadable metadata, or an unverified active file.
     *
     * Files are preserved unless a transaction reread proves the candidate
     * never became active. The caller must re-query via
     * [StandbyMediaStore.snapshot] and must not assume either outcome.
     */
    data object Unavailable : StandbyConfirmResult
}

/**
 * Classified read of the standby media record for UI fallback decisions.
 *
 * Corrupt or missing content maps to [Broken] so the surface can fall back
 * to the selected theme. A file-specific notice requires [Broken.failedFile];
 * metadata corruption without a known source must only fall back. [NeverSet]
 * and [Unavailable] also fall back. No branch ever deletes any file.
 */
sealed interface StandbySnapshot {
    /** No media record was ever written. */
    data object NeverSet : StandbySnapshot

    /** Readable record; [StandbyActive.file] is `null` when media is absent. */
    data class Available(val active: StandbyActive) : StandbySnapshot

    /**
     * Pointer or content is unusable.
     *
     * @param framing last known framing.
     * @param generation generation of the failed read, or zero if unrecoverable.
     * @param failedFile original failed file, or null when metadata cannot identify one.
     */
    data class Broken(
        val framing: StandbyFraming,
        val generation: Long,
        val failedFile: File? = null,
    ) : StandbySnapshot

    /** Metadata could not be read; every file is preserved, retry is safe. */
    data object Unavailable : StandbySnapshot
}

/** A source protected from store-initiated deletion until its owner releases it. */
internal interface StandbyMediaSourceLease {
    /** Canonical file for IO; preserve the original file separately for callback identity. */
    val file: File

    /** Releases once despite caller cancellation; later calls retry deferred cleanup. */
    suspend fun release()
}

/**
 * Transactional store for one active custom media plus one shared framing.
 *
 * Storage model: a single active basename plus kind and framing tuple in one
 * `DataStore<Preferences>` record, with bytes in one store-owned file under
 * [storageDir]. Staged filenames are generated (`standby_<gen>_<hex>.bin`);
 * caller destination names and URIs are never accepted for staging.
 * Streaming uses bounded 8 KiB chunks; content is never held fully in memory
 * and no size or format caps are guessed. Staged data is fsynced before any
 * publication, so confirmation only publishes already-durable bytes.
 *
 * Every instance using the same canonical dedicated [storageDir] must share
 * one logical DataStore authority for its active pointer.
 * Single-process serialization: every instance sharing one [storageDir]
 * takes the same process-wide mutex for generation allocation, confirmation,
 * discard, and framing updates, so two instances can never allocate the same
 * generation and a discard can never slip between another instance's reread
 * and prune. Generations stay unique and monotonic across instances even when
 * both stage before either confirms. As a second line, the publish
 * transaction re-checks the actual generation atomically inside
 * [DataStore.updateData], so even an unsynchronized writer (another process
 * or a direct record edit) can never let an older candidate overwrite a
 * newer commit; the loser observes [StandbyConfirmResult.Stale].
 * Multi-process file locking is not claimed.
 *
 * Lifecycle: [stageContent] copies and revalidates without touching metadata;
 * [confirm] revalidates on the IO dispatcher and then publishes candidate
 * plus framing in one conditional `updateData` transaction; [discard] removes
 * only a staged file proven to be non-active. A staged handle is untrusted
 * input: confirmation revalidates the bytes rather than the staged claim.
 *
 * Cancellation contract, stated exactly: cancellation before or during
 * staging retires only the partial staged file, rethrows, commits nothing,
 * and retains the old pair. Cancellation before the publication section of
 * [confirm] (including during revalidation) also commits nothing and
 * preserves the staged file for retry. Once the publication section starts,
 * it runs under [NonCancellable] and cancellation is no longer honored
 * there: a commit may or may not have happened, cancellation is never
 * assumed to roll back an already committed `DataStore` write, and the
 * outcome is resolved by a bounded reread of the trusted pointer. Only proof
 * that the candidate is not active permits deleting it; every uncertain or
 * unreadable reread preserves both files. The publication `NonCancellable`
 * scope covers only update, reread, and safe prune.
 *
 * Deletion discipline: only files this store created (tracked per directory
 * in this process) are ever removed as staged orphans, and only with proof
 * they are not active; the replaced old file is removed only after a proven
 * commit and only because the trusted pointer named it. Pre-existing files,
 * including coincidental name-pattern matches this store never created, are
 * never touched, and corrupt metadata never triggers any deletion. There is
 * no sweeper or quota. Snapshot and active-validation file checks and active
 * decoding log exceptions with their causes, which may include paths.
 * shortcut: process death or failed cleanup may leave retired files, add durable GC when reclamation is needed.
 *
 * Real wall-power-loss ordering across the file plus the preferences record
 * is unverified; this seam does not claim multi-file atomic durability.
 *
 * @param dataStore caller-supplied preferences store; this lane creates no singletons.
 * @param validator required real decoding contract; no always-valid default.
 * @param storageDir dedicated app-private directory owned by this store.
 * @param ioDispatcher dispatcher for file, validator, and DataStore IO.
 */
class StandbyMediaStore(
    private val dataStore: DataStore<Preferences>,
    private val validator: StandbyMediaValidator,
    private val storageDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dirKey: String = try {
        storageDir.canonicalPath
    } catch (e: Exception) {
        storageDir.absolutePath
    }

    private val sharedLock: Mutex
        get() = lockFor(dirKey)

    /**
     * Pins a nonempty regular canonical direct child, rejecting symbolic-link
     * sources without authorizing deletion.
     * Acquire on an IO dispatcher within the owner scope and release only after
     * playback and its asynchronous cleanup have completed. Store the lease
     * before returning across any cancellable dispatcher switch.
     *
     * @param file source to protect.
     * @return a separately releasable source pin.
     * @throws IOException if the source is unavailable, not regular, or retired.
     * @throws IllegalArgumentException if the source escapes the directory or is a symbolic link.
     */
    internal suspend fun pinSource(file: File): StandbyMediaSourceLease = sharedLock.withLock {
        currentCoroutineContext().ensureActive()
        val directory = storageDir.canonicalFile
        require(directory.path == dirKey) { "standby directory identity changed" }
        val source = file.canonicalFile
        require(source.parentFile == directory) { "standby source is not a direct child" }
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!attributes.isSymbolicLink) { "standby source must not be a symbolic link" }
        if (deferredDeletions.containsKey(source.path) && !sourcePinCounts.containsKey(source.path)) {
            finishDeferredDeletionLocked(source.path)
        }
        if (deferredDeletions.containsKey(source.path)) throw IOException("standby source is retired")
        if (!attributes.isRegularFile || !source.isFile || source.length() <= 0L) {
            throw IOException("standby source is unavailable")
        }
        val count = sourcePinCounts[source.path] ?: 0
        check(count < Int.MAX_VALUE) { "too many standby source pins" }
        val lease = object : StandbyMediaSourceLease {
            override val file: File = source
            private var released = false

            override suspend fun release() {
                withContext(NonCancellable) {
                    withContext(ioDispatcher) {
                        sharedLock.withLock {
                            if (!released) {
                                released = true
                                releaseSourceLocked(source.path)
                            } else if (!sourcePinCounts.containsKey(source.path)) {
                                finishDeferredDeletionLocked(source.path)
                            }
                        }
                    }
                }
            }
        }
        sourcePinCounts[source.path] = count + 1
        lease
    }

    /**
     * Reads the trusted active pointer without decoding content.
     *
     * Metadata-only and fast: checks pointer shape plus file presence, never
     * runs the decoder, so post-commit byte corruption still reads as
     * [StandbySnapshot.Available]. Call [validateActive] on load or reload
     * to genuinely decode the stored bytes.
     *
     * Never-set, available, broken, and unreadable states are distinguished
     * so the UI can choose silent versus truthful-notice fallbacks.
     *
     * @return classified snapshot; files are always preserved.
     */
    suspend fun snapshot(): StandbySnapshot {
        return when (val read = readPointerBounded()) {
            is PointerRead.Unreadable -> StandbySnapshot.Unavailable
            is PointerRead.NoRecord -> StandbySnapshot.NeverSet
            is PointerRead.Corrupt -> {
                // Generation is unrecoverable here; report zero without deleting.
                StandbySnapshot.Broken(StandbyFraming.CENTERED, 0L)
            }
            is PointerRead.Intact -> {
                val record = read.record
                val basename = record.basename
                if (basename == null) {
                    return StandbySnapshot.Available(
                        StandbyActive(null, null, record.framing, record.generation),
                    )
                }
                if (!isOwnedBasename(basename) ||
                    !basename.startsWith("standby_${record.generation}_") || record.kind == null
                ) {
                    return StandbySnapshot.Broken(record.framing, record.generation)
                }
                val file = File(resolvedDir(), basename)
                val exists = try {
                    withContext(ioDispatcher) { file.isFile && file.exists() }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to inspect standby media file", e)
                    false
                }
                if (!exists) {
                    return StandbySnapshot.Broken(record.framing, record.generation, file)
                }
                StandbySnapshot.Available(
                    StandbyActive(file, record.kind, record.framing, record.generation),
                )
            }
        }
    }

    /**
     * Genuinely decodes the stored active on load or reload.
     *
     * Runs the required [validator] on [ioDispatcher] and checks the decoded
     * kind matches the stored kind. Intended for surface load or reload, not
     * per-animation-frame polling. Returns [StandbySnapshot.Broken] for
     * undecodable bytes, kind mismatch, decoder failure, or a vanished file;
     * all files, metadata, and framing are retained unchanged.
     * Never-set and unreadable inputs pass through as [StandbySnapshot.NeverSet]
     * and [StandbySnapshot.Unavailable]. An already broken snapshot is
     * returned unchanged except [StandbySnapshot.Broken] with generation `0`
     * from an unrecoverable corrupt record, which is rechecked once in case
     * a newer commit repaired it. Cancellation propagates via
     * [CancellationException].
     *
     * Never holds the per-directory lock across decoding, so a concurrent
     * commit is never blocked. A mid-validation pointer change never returns
     * an undecoded [StandbySnapshot.Available]: validation restarts against
     * the fresh pointer, bounded by [VALIDATE_RETRIES], and persistent
     * replacement churn yields [StandbySnapshot.Unavailable] with everything
     * preserved. Old-byte corruption is therefore never misreported as
     * new-content corruption, and new content is never trusted without a
     * genuine decode.
     *
     * @return classified snapshot; files and metadata are always preserved.
     */
    suspend fun validateActive(): StandbySnapshot {
        var attempts = 0
        while (true) {
            val before = readPointerBounded()
            if (before is PointerRead.Unreadable) {
                return StandbySnapshot.Unavailable
            }
            if (before is PointerRead.NoRecord) {
                return StandbySnapshot.NeverSet
            }
            if (before is PointerRead.Corrupt) {
                val reread = readPointerBounded()
                if (reread is PointerRead.Intact) {
                    if (attempts >= VALIDATE_RETRIES) {
                        return StandbySnapshot.Unavailable
                    }
                    attempts += 1
                    val verdict = validateOnce(reread.record)
                    if (verdict is ValidationVerdict.Settled) {
                        return verdict.snapshot
                    }
                    continue
                }
                if (reread is PointerRead.Unreadable) {
                    return StandbySnapshot.Unavailable
                }
                if (reread is PointerRead.NoRecord) {
                    return StandbySnapshot.NeverSet
                }
                return StandbySnapshot.Broken(StandbyFraming.CENTERED, 0L)
            }
            if (attempts >= VALIDATE_RETRIES) {
                return StandbySnapshot.Unavailable
            }
            attempts += 1
            // Explicit guard: keeps the smart cast local to this branch.
            if (before !is PointerRead.Intact) {
                return StandbySnapshot.Unavailable
            }
            val verdict = validateOnce(before.record)
            if (verdict is ValidationVerdict.Settled) {
                return verdict.snapshot
            }
        }
    }

    private sealed interface ValidationVerdict {
        data class Settled(val snapshot: StandbySnapshot) : ValidationVerdict
        data object PointerMoved : ValidationVerdict
    }

    private suspend fun validateOnce(record: RecordPointer): ValidationVerdict {
        val basename = record.basename
        if (basename == null) {
            return ValidationVerdict.Settled(
                StandbySnapshot.Available(
                    StandbyActive(null, null, record.framing, record.generation),
                ),
            )
        }
        if (!isOwnedBasename(basename) ||
            !basename.startsWith("standby_${record.generation}_") || record.kind == null
        ) {
            val after = readPointerBounded()
            if (after is PointerRead.Intact &&
                after.record.generation == record.generation &&
                after.record.basename == basename
            ) {
                return ValidationVerdict.Settled(
                    StandbySnapshot.Broken(record.framing, record.generation),
                )
            }
            return ValidationVerdict.PointerMoved
        }
        val file = File(resolvedDir(), basename)
        val exists = try {
            withContext(ioDispatcher) { file.isFile && file.exists() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect standby media file", e)
            return movedOrBroken(record, file)
        }
        if (!exists) {
            return movedOrBroken(record, file)
        }
        val decoded: StandbyMediaKind? = try {
            withContext(ioDispatcher) { validator.inspect(file) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode standby media file", e)
            return movedOrBroken(record, file)
        }
        if (decoded == null || decoded != record.kind) {
            // Byte corruption, kind drift, or decoder failure: retain everything.
            return movedOrBroken(record, file)
        }
        val after = readPointerBounded()
        if (after !is PointerRead.Intact ||
            after.record.generation != record.generation ||
            after.record.basename != basename
        ) {
            // A newer commit (or metadata loss) landed mid-validation; retry
            // against the fresh pointer rather than trusting either verdict.
            return ValidationVerdict.PointerMoved
        }
        return ValidationVerdict.Settled(
            StandbySnapshot.Available(
                StandbyActive(file, record.kind, after.record.framing, record.generation),
            ),
        )
    }

    /**
     * Settles [StandbySnapshot.Broken] only when the pointer is proven
     * unchanged, else signals [ValidationVerdict.PointerMoved] for retry.
     *
     * Prevents a prune-deleted old file (or any mid-validation replacement)
     * from being misreported as new-content corruption.
     *
     * @param record pointer observed before decoding.
     * @param file original file that failed validation.
     * @return settled broken verdict, or a retry signal on replacement.
     */
    private suspend fun movedOrBroken(record: RecordPointer, file: File): ValidationVerdict {
        val after = readPointerBounded()
        if (after is PointerRead.Intact &&
            after.record.generation == record.generation &&
            after.record.basename == record.basename
        ) {
            return ValidationVerdict.Settled(
                StandbySnapshot.Broken(record.framing, record.generation, file),
            )
        }
        return ValidationVerdict.PointerMoved
    }

    /**
     * Streams [openInput] into a new unique store-owned file and validates it.
     *
     * No active metadata changes and no old prune happen here. IO, decode, or
     * validation failure retires only the staged file created by this call
     * and preserves the prior pair exactly. Cancellation retires the partial
     * staged file and rethrows. Physical deletion waits for source pins.
     * Validation runs on [ioDispatcher].
     *
     * @param openInput fresh source stream factory.
     * @param framing proposed framing for preview.
     * @return staged candidate, invalid content, or source failure.
     */
    suspend fun stageContent(
        openInput: () -> InputStream,
        framing: StandbyFraming,
    ): StandbyStageResult {
        try {
            currentCoroutineContext().ensureActive()
            withContext(ioDispatcher) {
                val dir = resolvedDir()
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                    throw IOException("storage unavailable")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return StandbyStageResult.SourceFailed
        }
        val generation = try {
            sharedLock.withLock { allocateGenerationLocked() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return StandbyStageResult.SourceFailed
        }
        if (generation == null) {
            return StandbyStageResult.SourceFailed
        }
        var reservedFile: File? = null
        val stagedFile = try {
            withContext(ioDispatcher) { reserveUniqueFile(generation).also { reservedFile = it } }
        } catch (ce: CancellationException) {
            reservedFile?.let { deleteIfCreated(it) }
            throw ce
        } catch (e: Exception) {
            reservedFile?.let { deleteIfCreated(it) }
            return StandbyStageResult.SourceFailed
        }
        try {
            withContext(ioDispatcher) {
                openInput().use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read > 0) {
                                output.write(buffer, 0, read)
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
        } catch (ce: CancellationException) {
            deleteIfCreated(stagedFile)
            throw ce
        } catch (e: Exception) {
            deleteIfCreated(stagedFile)
            return StandbyStageResult.SourceFailed
        }
        val kind: StandbyMediaKind? = try {
            withContext(ioDispatcher) { validator.inspect(stagedFile) }
        } catch (ce: CancellationException) {
            deleteIfCreated(stagedFile)
            throw ce
        } catch (e: Exception) {
            deleteIfCreated(stagedFile)
            return StandbyStageResult.InvalidContent
        }
        if (kind == null) {
            deleteIfCreated(stagedFile)
            return StandbyStageResult.InvalidContent
        }
        return StandbyStageResult.Staged(StagedCandidate(generation, stagedFile, kind, framing))
    }

    /**
     * Publishes [candidate] plus [framing] in one conditional transaction.
     *
     * Revalidates outside the publish mutex, then checks the latest pointer
     * and file presence together under the lock. An already active candidate
     * returns its persisted record only when decoding agrees with its kind;
     * otherwise the outcome is unavailable and everything is preserved.
     * Duplicate confirmation never overwrites framing or prunes files.
     * Stale generations never replace a newer commit. Cancellation before
     * publication commits nothing and preserves the staged file; cancellation
     * across publication resolves via bounded reread and never blind-deletes.
     *
     * @param candidate staged handle from [stageContent].
     * @param framing framing to commit atomically with the media.
     * @return committed, stale, invalid, or unavailable outcome.
     */
    suspend fun confirm(
        candidate: StagedCandidate,
        framing: StandbyFraming = candidate.framing,
    ): StandbyConfirmResult {
        val inspected = try {
            withContext(ioDispatcher) {
                if (isOwnedFile(candidate.file) && candidate.file.isFile && candidate.file.exists()) {
                    validator.inspect(candidate.file)
                } else {
                    null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null
        }
        sharedLock.withLock {
            currentCoroutineContext().ensureActive()
            val current = readPointerBounded()
            if (!current.isSafeForMutation()) {
                return StandbyConfirmResult.Unavailable
            }
            val owned = isOwnedFile(candidate.file)
            val stillThere = try {
                withContext(ioDispatcher) { owned && candidate.file.isFile && candidate.file.exists() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                false
            }
            if (current is PointerRead.Intact && current.record.basename == candidate.file.name) {
                val kind = current.record.kind
                if (candidate.generation != current.record.generation ||
                    !stillThere || kind == null || inspected != kind
                ) {
                    return StandbyConfirmResult.Unavailable
                }
                return StandbyConfirmResult.Committed(
                    StandbyActive(candidate.file, kind, current.record.framing, current.record.generation),
                )
            }
            val currentGen = if (current is PointerRead.Intact) current.record.generation else 0L
            val oldBasename = if (current is PointerRead.Intact) current.record.basename else null
            val matchesGeneration = candidate.file.name.startsWith("standby_${candidate.generation}_")
            if (current is PointerRead.Intact && candidate.generation <= currentGen) {
                if (owned && matchesGeneration && inspected != null && stillThere) {
                    deleteIfCreatedLocked(candidate.file)
                }
                return StandbyConfirmResult.Stale
            }
            if (!owned || !matchesGeneration ||
                !createdFor(dirKey).contains(candidate.file.name) || inspected == null || !stillThere
            ) {
                return StandbyConfirmResult.CandidateInvalid
            }
            return withContext(NonCancellable) {
                publishLocked(candidate, framing, inspected, oldBasename)
            }
        }
    }

    /**
     * Retires only an uncommitted staged file; never touches active metadata.
     * Physical deletion waits for source pins.
     *
     * Serialized with [confirm] on the shared lock: publication eligibility is
     * revoked immediately, even when metadata is unreadable. An active file or
     * uncertain pointer is preserved; deferred cleanup rechecks the pointer.
     * Caller cancellation is rethrown after retirement finishes.
     * Files this store did not create are never removed.
     *
     * @param candidate staged handle to abandon.
     */
    suspend fun discard(candidate: StagedCandidate) {
        deleteIfCreated(candidate.file)
        currentCoroutineContext().ensureActive()
    }

    /**
     * Commits [framing] while keeping the current media pointer.
     *
     * Single metadata transaction; never deletes any file. Unreadable or
     * corrupt metadata yields [StandbySnapshot.Unavailable] with all files
     * preserved.
     *
     * @param framing new shared framing.
     * @return fresh classified snapshot after the write attempt.
     */
    suspend fun updateFraming(framing: StandbyFraming): StandbySnapshot {
        sharedLock.withLock {
            val current = readPointerBounded()
            if (!current.isSafeForMutation()) {
                return StandbySnapshot.Unavailable
            }
            val committed = withContext(NonCancellable) {
                try {
                    dataStore.updateData { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[KEY_CENTER_X] = framing.centerX
                            this[KEY_CENTER_Y] = framing.centerY
                            this[KEY_ZOOM] = framing.zoom
                            if (!contains(KEY_GENERATION)) {
                                this[KEY_GENERATION] = 0L
                            }
                        }.toPreferences()
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!committed) {
                return StandbySnapshot.Unavailable
            }
            return snapshot()
        }
    }

    private suspend fun publishLocked(
        candidate: StagedCandidate,
        framing: StandbyFraming,
        kind: StandbyMediaKind,
        oldBasename: String?,
    ): StandbyConfirmResult {
        try {
            dataStore.updateData { prefs ->
                val actualGen = try {
                    prefs[KEY_GENERATION] ?: 0L
                } catch (e: Exception) {
                    return@updateData prefs
                }
                if (candidate.generation <= actualGen) {
                    // Lost a race inside the transaction: refuse the overwrite.
                    return@updateData prefs
                }
                prefs.toMutablePreferences().apply {
                    this[KEY_BASENAME] = candidate.file.name
                    this[KEY_KIND] = kind.name
                    this[KEY_CENTER_X] = framing.centerX
                    this[KEY_CENTER_Y] = framing.centerY
                    this[KEY_ZOOM] = framing.zoom
                    this[KEY_GENERATION] = candidate.generation
                }.toPreferences()
            }
        } catch (e: Exception) {
            // updateData runs under NonCancellable, so a throw here (including a
            // commit-then-throw store) is genuinely ambiguous, never a rollback proof.
            return resolveAmbiguous(candidate, framing, kind, oldBasename)
        }
        val refreshed = readPointerBounded()
        if (refreshed !is PointerRead.Intact || !refreshed.isSafeForMutation() ||
            refreshed.record.basename != candidate.file.name
        ) {
            return resolveAmbiguous(candidate, framing, kind, oldBasename)
        }
        onCommitted(candidate.generation)
        pruneIfReplaced(oldBasename, candidate.file.name)
        return StandbyConfirmResult.Committed(
            StandbyActive(candidate.file, kind, refreshed.record.framing, refreshed.record.generation),
        )
    }

    private suspend fun resolveAmbiguous(
        candidate: StagedCandidate,
        framing: StandbyFraming,
        kind: StandbyMediaKind,
        oldBasename: String?,
    ): StandbyConfirmResult {
        val reread = readPointerBounded()
        if (!reread.isSafeForMutation()) {
            return StandbyConfirmResult.Unavailable
        }
        return when (reread) {
            is PointerRead.Intact -> {
                if (reread.record.basename == candidate.file.name) {
                    onCommitted(candidate.generation)
                    pruneIfReplaced(oldBasename, candidate.file.name)
                    val committedKind = reread.record.kind ?: kind
                    StandbyConfirmResult.Committed(
                        StandbyActive(
                            candidate.file,
                            committedKind,
                            reread.record.framing,
                            reread.record.generation,
                        ),
                    )
                } else if (reread.record.generation >= candidate.generation) {
                    // A newer intact commit won: the candidate is stale, never active.
                    deleteIfCreatedLocked(candidate.file)
                    StandbyConfirmResult.Stale
                } else if (reread.record.basename == oldBasename) {
                    // Proven never committed: only then may the staged file go.
                    deleteIfCreatedLocked(candidate.file)
                    StandbyConfirmResult.Unavailable
                } else {
                    // Third state: preserve both files, claim nothing.
                    StandbyConfirmResult.Unavailable
                }
            }
            else -> StandbyConfirmResult.Unavailable
        }
    }

    private suspend fun allocateGenerationLocked(): Long? {
        val known = when (val read = readPointerBounded()) {
            is PointerRead.Intact -> read.record.generation
            is PointerRead.NoRecord -> 0L
            else -> null
        }
        var base = dirWatermarks[dirKey] ?: Long.MIN_VALUE
        if (known != null && known > base) {
            base = known
        }
        if (base == Long.MIN_VALUE) {
            base = 0L
        }
        if (base >= Long.MAX_VALUE - 1L) {
            return null
        }
        val next = base + 1L
        dirWatermarks[dirKey] = next
        return next
    }

    private fun onCommitted(generation: Long) {
        dirWatermarks.merge(dirKey, generation) { first, second ->
            maxOf(first, second)
        }
    }

    private fun pruneIfReplaced(oldBasename: String?, activeBasename: String) {
        // Proof chain: oldBasename came from a trusted intact pointer read and a
        // newer commit was just proven, so this specific file was the store's
        // own prior active copy (exclusive creation at stage time rules out
        // pre-existing files ever being published). Corrupt metadata never
        // reaches this path.
        if (oldBasename != null && oldBasename != activeBasename && isOwnedBasename(oldBasename)) {
            createdFor(dirKey).remove(oldBasename)
            deleteOrDeferLocked(File(resolvedDir(), oldBasename))
        }
    }

    private fun reserveUniqueFile(generation: Long): File {
        val dir = resolvedDir()
        repeat(RESERVE_ATTEMPTS) {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(HEX_SUFFIX_LENGTH)
            val basename = "standby_" + generation.toString() + "_" + suffix + ".bin"
            if (!isOwnedBasename(basename)) {
                return@repeat
            }
            val file = File(dir, basename)
            try {
                // Exclusive creation: never replace an existing file.
                if (file.createNewFile()) {
                    createdFor(dirKey).add(basename)
                    return file
                }
            } catch (e: IOException) {
                // Try another suffix; never replace an existing file.
            }
        }
        throw IOException("unique file unavailable")
    }

    private suspend fun readPointerBounded(): PointerRead {
        return withTimeoutOrNull(POINTER_TIMEOUT_MS) {
            readPointer()
        } ?: PointerRead.Unreadable
    }

    private suspend fun readPointer(): PointerRead {
        val prefs = try {
            withContext(ioDispatcher) { dataStore.data.first() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return PointerRead.Unreadable
        }
        return try {
            parsePointer(prefs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            PointerRead.Corrupt
        }
    }

    private fun parsePointer(prefs: Preferences): PointerRead {
        val basename: String? = prefs[KEY_BASENAME]
        val kindRaw: String? = prefs[KEY_KIND]
        val centerX: Float? = prefs[KEY_CENTER_X]
        val centerY: Float? = prefs[KEY_CENTER_Y]
        val zoom: Float? = prefs[KEY_ZOOM]
        val generation: Long = prefs[KEY_GENERATION] ?: 0L
        if (basename == null && kindRaw == null && centerX == null && centerY == null && zoom == null) {
            if (generation == 0L) {
                return PointerRead.NoRecord
            }
            return PointerRead.Intact(RecordPointer(null, null, StandbyFraming.CENTERED, generation))
        }
        if (basename == null) {
            // A framing-only record is valid only when fully formed and kindless.
            if (kindRaw != null || centerX == null || centerY == null || zoom == null) {
                return PointerRead.Corrupt
            }
            return PointerRead.Intact(
                RecordPointer(null, null, StandbyFraming(centerX, centerY, zoom), generation),
            )
        }
        // A media pointer requires a known kind and complete framing.
        if (centerX == null || centerY == null || zoom == null) {
            return PointerRead.Corrupt
        }
        if (kindRaw == null) {
            return PointerRead.Corrupt
        }
        val kind = try {
            StandbyMediaKind.valueOf(kindRaw)
        } catch (e: IllegalArgumentException) {
            return PointerRead.Corrupt
        }
        return PointerRead.Intact(
            RecordPointer(basename, kind, StandbyFraming(centerX, centerY, zoom), generation),
        )
    }

    private fun resolvedDir(): File {
        return storageDir
    }

    private fun isOwnedBasename(basename: String): Boolean {
        return OWNED_BASENAME_REGEX.matches(basename)
    }

    private fun isOwnedFile(file: File): Boolean {
        if (!isOwnedBasename(file.name)) {
            return false
        }
        return try {
            val parent = file.canonicalFile.parentFile?.canonicalFile
            val owned = storageDir.canonicalFile
            parent == owned
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Revokes publication eligibility before deciding whether cleanup is safe.
     * Acquires the directory mutex; cancellation cannot interrupt retirement.
     * @param file failed or explicitly discarded stage created by this store.
     */
    private suspend fun deleteIfCreated(file: File) {
        withContext(NonCancellable) {
            sharedLock.withLock {
                if (!isOwnedFile(file) || !createdFor(dirKey).remove(file.name)) return@withLock
                val safety = try {
                    deletionSafetyLocked(file)
                } catch (ce: CancellationException) {
                    Log.w(TAG, "Standby file cleanup metadata was cancelled", ce)
                    DeletionSafety.UNKNOWN
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to check standby file cleanup", e)
                    DeletionSafety.UNKNOWN
                }
                when (safety) {
                    DeletionSafety.SAFE -> deleteOrDeferLocked(file)
                    DeletionSafety.ACTIVE -> Unit
                    DeletionSafety.UNKNOWN -> {
                        try {
                            deferredDeletions[file.canonicalPath] = file
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record standby file retirement", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Invalidates an eligible stage while already holding the directory mutex.
     * @param file stage created by this store.
     */
    private fun deleteIfCreatedLocked(file: File) {
        if (!isOwnedFile(file) || !createdFor(dirKey).remove(file.name)) return
        deleteOrDeferLocked(file)
    }

    /**
     * Deletes or defers a file whose retirement was already proven.
     * @param file store-owned file.
     */
    private fun deleteOrDeferLocked(file: File) {
        try {
            if (!isOwnedFile(file)) return
            val path = file.canonicalPath
            if ((sourcePinCounts[path] ?: 0) > 0 || !deleteOwnedQuietly(file)) {
                deferredDeletions[path] = file
            } else {
                deferredDeletions.remove(path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule standby file cleanup", e)
        }
    }

    /**
     * Drops one pin while holding the directory mutex.
     * @param path canonical source path.
     */
    private suspend fun releaseSourceLocked(path: String) {
        val count = checkNotNull(sourcePinCounts[path]) { "standby source pin is missing" }
        if (count > 1) {
            sourcePinCounts[path] = count - 1
            return
        }
        sourcePinCounts.remove(path)
        finishDeferredDeletionLocked(path)
    }

    /**
     * Rechecks and attempts a previously authorized deletion under the mutex.
     * @param path canonical source path.
     */
    private suspend fun finishDeferredDeletionLocked(path: String) {
        val file = deferredDeletions[path] ?: return
        try {
            if (!isOwnedFile(file) || file.canonicalPath != path) {
                Log.w(TAG, "Deferred standby file path changed")
                return
            }
            if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deferredDeletions.remove(path)
                return
            }
            if (!file.isFile) {
                Log.w(TAG, "Deferred standby file is inaccessible or not regular")
                return
            }
            when (deletionSafetyLocked(file)) {
                DeletionSafety.SAFE -> {
                    if (deleteOwnedQuietly(file)) deferredDeletions.remove(path)
                }
                DeletionSafety.ACTIVE -> deferredDeletions.remove(path)
                DeletionSafety.UNKNOWN -> Unit
            }
        } catch (ce: CancellationException) {
            Log.w(TAG, "Deferred standby cleanup was cancelled", ce)
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Deferred standby cleanup failed", e)
        }
    }

    /**
     * Checks the current pointer before retiring or deleting a file.
     * @param file store-owned file.
     * @return whether deletion is safe, active, or uncertain.
     */
    private suspend fun deletionSafetyLocked(file: File): DeletionSafety {
        val read = readPointerBounded()
        if (!read.isSafeForMutation()) {
            Log.w(TAG, "Standby cleanup has no trusted pointer")
            return DeletionSafety.UNKNOWN
        }
        val basename = (read as? PointerRead.Intact)?.record?.basename
        if (basename != null) {
            val active = File(resolvedDir(), basename)
            if (!isOwnedFile(active) || !active.isFile || active.length() <= 0L) {
                Log.w(TAG, "Standby cleanup cannot validate the active file")
                return DeletionSafety.UNKNOWN
            }
            if (active.canonicalPath == file.canonicalPath) return DeletionSafety.ACTIVE
        }
        return DeletionSafety.SAFE
    }

    /**
     * Attempts deletion without treating an indeterminate path as absent.
     * @param file store-owned file.
     * @return true only when deletion or absence is confirmed.
     */
    private fun deleteOwnedQuietly(file: File): Boolean {
        return try {
            if (!isOwnedFile(file)) {
                false
            } else if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                true
            } else if (!file.isFile) {
                Log.w(TAG, "Standby file is inaccessible or not regular")
                false
            } else if (file.delete() || Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                true
            } else {
                Log.w(TAG, "Failed to delete standby file")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete standby file", e)
            false
        }
    }

    /** Current metadata verdict for one previously created file. */
    private enum class DeletionSafety { SAFE, ACTIVE, UNKNOWN }

    private data class RecordPointer(
        val basename: String?,
        val kind: StandbyMediaKind?,
        val framing: StandbyFraming,
        val generation: Long,
    )

    private sealed interface PointerRead {
        data object NoRecord : PointerRead
        data class Intact(val record: RecordPointer) : PointerRead
        data object Corrupt : PointerRead
        data object Unreadable : PointerRead
    }

    /**
     * Checks whether the pointer can safely authorize writes or deletion.
     *
     * @return `true` when the pointer is trustworthy for mutation.
     */
    private fun PointerRead.isSafeForMutation(): Boolean = when (this) {
        PointerRead.NoRecord -> true
        is PointerRead.Intact -> record.basename?.let {
            isOwnedBasename(it) && it.startsWith("standby_${record.generation}_")
        } ?: true
        else -> false
    }

    private companion object {
        const val TAG = "StandbyMediaStore"
        const val COPY_BUFFER_SIZE = 8192
        const val RESERVE_ATTEMPTS = 10
        const val HEX_SUFFIX_LENGTH = 16
        const val POINTER_TIMEOUT_MS = 5_000L
        const val VALIDATE_RETRIES = 4
        val OWNED_BASENAME_REGEX = Regex("^standby_\\d+_[0-9a-f]{16}\\.bin$")
        val KEY_BASENAME = stringPreferencesKey("standby_media_basename")
        val KEY_KIND = stringPreferencesKey("standby_media_kind")
        val KEY_CENTER_X = floatPreferencesKey("standby_framing_center_x")
        val KEY_CENTER_Y = floatPreferencesKey("standby_framing_center_y")
        val KEY_ZOOM = floatPreferencesKey("standby_framing_zoom")
        val KEY_GENERATION = longPreferencesKey("standby_media_generation")
        val dirLocks = ConcurrentHashMap<String, Mutex>()
        val dirWatermarks = ConcurrentHashMap<String, Long>()
        val dirCreated = ConcurrentHashMap<String, MutableSet<String>>()
        val sourcePinCounts = ConcurrentHashMap<String, Int>()
        val deferredDeletions = ConcurrentHashMap<String, File>()

        fun lockFor(dirKey: String): Mutex {
            return dirLocks.getOrPut(dirKey) { Mutex() }
        }

        fun createdFor(dirKey: String): MutableSet<String> {
            return dirCreated.getOrPut(dirKey) { ConcurrentHashMap.newKeySet() }
        }
    }
}
