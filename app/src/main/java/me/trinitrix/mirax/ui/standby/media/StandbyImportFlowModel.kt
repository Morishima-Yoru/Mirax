package me.trinitrix.mirax.ui.standby.media

import android.util.Log
import java.io.File
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind

/**
 * Injectable store seam behind [StandbyImportFlowModel].
 *
 * The real implementation delegates to `StandbyMediaStore` staging and
 * confirmation. Fakes stand in during JVM tests.
 *
 * Transaction contract (mirrors `StandbyMediaStore.confirm`/`discard`):
 * - [StandbyConfirmOutcome.Committed] means the swap committed.
 * - [StandbyConfirmOutcome.Rejected] means definitely not committed and safe
 *   to discard (maps `Stale`/`CandidateInvalid`/definite `false`).
 * - [StandbyConfirmOutcome.Uncertain] means unknown (maps `Unavailable`,
 *   commit-then-throw, or any confirmation exception). The caller must
 *   preserve the candidate, must not discard, must not claim a definite
 *   failure, and the host adapter must reread via [readActive] before deciding.
 *
 * Only store-owned staged files are ever passed to [discard]; the user
 * original is never passed in. This model never deletes files directly and
 * never uses `File.delete`. Corrupt bytes are never deleted or quarantined
 * here: `StandbyMediaStore.snapshot`/`validateActive` preserve files and
 * metadata on `Broken`, so the model only changes presentation, never storage.
 */
interface StandbyImportStore {
    /**
     * Stages source bytes for preview and validation.
     *
     * @param source source file chosen by the user; never deleted or mutated.
     * @return staged file plus observed kind, or `null` on definite failure.
     * @throws java.util.concurrent.CancellationException on cancellation; the
     *   caller preserves the previous staged candidate.
     * @throws kotlinx.coroutines.CancellationException on cancellation; the
     *   caller preserves the previous staged candidate.
     */
    fun stage(source: File): StagedPreview?

    /**
     * Confirms the staged file and swaps the active background.
     *
     * Host adapter mapping: `Committed` -> [StandbyConfirmOutcome.Committed],
     * `Stale`/`CandidateInvalid` -> [StandbyConfirmOutcome.Rejected],
     * `Unavailable` or any exception (possible commit-then-throw) ->
     * [StandbyConfirmOutcome.Uncertain]. `Committed` carries the persisted
     * `StandbyActive` pair, so the model publishes
     * [StandbyConfirmOutcome.Committed.persistedFraming], never the request:
     * an idempotent duplicate returns the persisted framing without another
     * commit. No production adapter is wired yet; without a safe host adapter
     * the caller must treat unknown as uncertain.
     *
     * @param staged staged file from [stage]; store-owned only.
     * @param framing framing to publish with the swap.
     * @return commit outcome; never a bare boolean.
     */
    fun confirm(staged: File, framing: StandbyFraming): StandbyConfirmOutcome

    /** Discards a staged file without touching the active background. */
    fun discard(staged: File)

    /**
     * Rereads the active pointer for reconciliation.
     *
     * Adapter mapping from `StandbyMediaStore.snapshot`/`validateActive`:
     * readable record maps to [StandbyActiveRead.Available] (`file` is `null`
     * when theme-only), proven decode failure maps to
     * [StandbyActiveRead.Corrupt] carrying the failed identity, anything
     * unreadable maps to [StandbyActiveRead.Unreadable]. Only [Available] and
     * [Corrupt] count as proof; [Unreadable] keeps pending work alive.
     *
     * Host wiring gap (do not paper over): `snapshot` is metadata-only and
     * never decodes, so it cannot yield [Corrupt]; `validateActive` genuinely
     * decodes and returns `StandbySnapshot.Broken(framing, generation)`
     * without any file identity, so a real adapter cannot reliably recover
     * the corrupt file from that snapshot alone. Hosts must not pass the
     * model's current `activeFile` as the failing identity. Until a serial
     * host path supplies the proven identity, `readActive` must stay
     * [Unreadable]/[Available] and corruption fallback for an uncertain
     * transaction must stay [StandbyReconcileOutcome.Unresolved]. The
     * default keeps older fakes compiling and forces reconciliation to wait;
     * real adapters must override this.
     *
     * @return the classified active pointer.
     */
    fun readActive(): StandbyActiveRead {
        // The default keeps older fakes compiling and forces reconciliation to
        // wait; real adapters must override this.
        return StandbyActiveRead.Unreadable
    }
}

/**
 * Reliable confirmation outcome for the import transaction.
 *
 * Replaces the old `Boolean` seam which could not distinguish
 * commit-then-throw/`Unavailable` from a definite rejection and led callers
 * to blind-discard a possibly-committed candidate.
 */
sealed interface StandbyConfirmOutcome {
    /**
     * Swap committed with the store-persisted pair.
     *
     * Both fields come from the persisted record (`StandbyActive`), never
     * from the request: an idempotent duplicate returns the persisted
     * framing without overwriting it, so callers must publish
     * [persistedFraming], not the requested framing.
     */
    data class Committed(val activeFile: File, val persistedFraming: StandbyFraming) : StandbyConfirmOutcome

    /** Definitely not committed; safe to discard the staged copy. */
    data object Rejected : StandbyConfirmOutcome

    /**
     * Unknown result (`Unavailable`, exception, commit-then-throw).
     *
     * Caller must preserve the candidate, must not discard, must not claim a
     * definite failure; host must reread via `snapshot` before deciding.
     */
    data object Uncertain : StandbyConfirmOutcome
}

/**
 * Classified reread of the active pointer for reconciliation.
 *
 * The adapter maps a readable store record to [Available] and anything
 * unreadable to [Unreadable]. A record with proven decode failure maps to
 * [Corrupt] only when the host can supply the exact failed file identity;
 * the current `StandbyMediaStore.snapshot`/`validateActive` contract cannot
 * do that (`snapshot` never decodes; `Broken` carries only framing/generation,
 * never the file), so real hosts must keep returning [Unreadable]/[Available]
 * until a serial identity-carrying path exists. Only [Available] and [Corrupt]
 * count as proof; [Unreadable] keeps the pending reconciliation alive. Host
 * callbacks are not ordered against this reread: identity comparison below
 * decides stale versus same, never blind trust.
 */
sealed interface StandbyActiveRead {
    /**
     * Readable pointer; [file] is `null` when theme-only.
     *
     * @param file currently active store-owned file, or `null` when absent.
     * @param framing stored or last-known framing carried by the record.
     */
    data class Available(val file: File?, val framing: StandbyFraming) : StandbyActiveRead

    /**
     * Proven decode failure of one identity; never infer from reread alone.
     *
     * Real hosts cannot build this from `snapshot`/`Broken` today: those
     * carry no file identity, so they must not pass the model's current
     * `activeFile` as the failing identity. Fakes may construct it directly.
     *
     * @param file store-owned active file that failed validation.
     * @param framing stored framing carried by the record.
     */
    data class Corrupt(val file: File?, val framing: StandbyFraming) : StandbyActiveRead

    /**
     * Pointer unreadable or unknown; proves nothing, preserves everything.
     *
     * Stays pending so the host can retry serially. This model never claims
     * cross-thread ordering with host decode callbacks; hosts must deliver
     * corruption reports with the failing identity and reread serially before
     * acting.
     */
    data object Unreadable : StandbyActiveRead
}

/**
 * Decision returned by [StandbyImportFlowModel.confirm].
 *
 * Never a bare boolean: [Uncertain] is distinct from [Rejected] so callers
 * cannot mistake an ambiguous transaction for a definite failure.
 */
sealed interface StandbyImportDecision {
    /** Swap committed. */
    data object Committed : StandbyImportDecision

    /** Definitely not committed; the staged copy was safely released. */
    data object Rejected : StandbyImportDecision

    /**
     * Unknown result; the candidate is held in
     * [StandbyImportPhase.PENDING_RECONCILIATION] until
     * [StandbyImportFlowModel.reconcile] proves the pointer.
     */
    data object Uncertain : StandbyImportDecision
}

/**
 * Outcome returned by [StandbyImportFlowModel.cancel].
 *
 * While reconciliation is pending the cancel is recorded and deferred, never
 * executed, so a possibly-committed candidate is never deleted.
 */
sealed interface StandbyCancelOutcome {
    /** Staged copy released; previous background retained with no notice. */
    data object Cancelled : StandbyCancelOutcome

    /** Reconciliation pending; the request is recorded until proof arrives. */
    data object Deferred : StandbyCancelOutcome

    /** Nothing staged or pending; no-op. */
    data object NothingPending : StandbyCancelOutcome
}

/**
 * Outcome returned by [StandbyImportFlowModel.onCorrupt].
 *
 * The failing identity must come from the error callback itself; a pending
 * `null`, unknown, or mismatched identity is ignored as stale and never
 * clears storage. Storage is never deleted or quarantined here: files and
 * metadata stay intact and only presentation falls back.
 */
sealed interface StandbyCorruptOutcome {
    /** Same active identity failed; presentation fell back, storage kept. */
    data object FallenBack : StandbyCorruptOutcome

    /** Stale, unknown, or mismatched identity; ignored without changes. */
    data object Ignored : StandbyCorruptOutcome

    /** Reconciliation pending; the report is recorded until proof arrives. */
    data object Deferred : StandbyCorruptOutcome
}

/**
 * Outcome returned by [StandbyImportFlowModel.reconcile].
 *
 * [restageSource] carries a restage request deferred during uncertainty so
 * the host can retry it explicitly after the pointer is proven; the model
 * never auto-stages inside reconciliation.
 */
sealed interface StandbyReconcileOutcome {
    /**
     * Pointer proves the candidate committed; active and framing synced.
     *
     * @param activeFile proven active file.
     * @param framing proven framing.
     * @param restageSource deferred restage request, or `null`.
     */
    data class Committed(
        val activeFile: File?,
        val framing: StandbyFraming,
        val restageSource: File? = null,
    ) : StandbyReconcileOutcome

    /**
     * Pointer proves the candidate never committed; it was safely released.
     *
     * @param activeFile proven active file.
     * @param framing proven framing.
     * @param restageSource deferred restage request, or `null`.
     */
    data class NotCommitted(
        val activeFile: File?,
        val framing: StandbyFraming,
        val restageSource: File? = null,
    ) : StandbyReconcileOutcome

    /**
     * A deferred corruption report was applied after proof.
     *
     * @param restageSource deferred restage request, or `null`.
     */
    data class CorruptFallback(val restageSource: File? = null) : StandbyReconcileOutcome

    /** Pointer still unreadable; everything preserved, still pending. */
    data object Unresolved : StandbyReconcileOutcome

    /** No uncertain work; no-op. */
    data object NothingPending : StandbyReconcileOutcome
}

/**
 * Staged preview description.
 *
 * @param file store-owned staged copy for preview only.
 * @param kind media kind observed at staging time.
 * @param framing proposed framing captured at staging time.
 */
data class StagedPreview(
    val file: File,
    val kind: StandbyMediaKind,
    val framing: StandbyFraming,
)

/**
 * Localized notice emitted by the import flow.
 */
enum class StandbyImportNotice {
    /** No notice. */
    NONE,

    /**
     * Source could not be read or staged, or a definite rejection released
     * a candidate while the proven active pair is unchanged.
     */
    IMPORT_FAILED,

    /**
     * Transaction result unknown; the previous background may have changed.
     * Never paired with text claiming the background is unchanged.
     */
    IMPORT_UNCERTAIN,

    /** Stored media was corrupt; fell back to the theme. */
    CORRUPT_FALLBACK,
}

/**
 * Import phase for the transactional standby media flow.
 */
enum class StandbyImportPhase {
    /** No staged candidate; active background and framing untouched. */
    IDLE,

    /** Candidate staged and awaiting explicit user confirmation. */
    STAGED,

    /**
     * Ambiguous transaction; candidate plus prior pair preserved until
     * [StandbyImportFlowModel.reconcile] proves the pointer. No discard,
     * replacement, cancel, or corruption handling may run before proof.
     */
    PENDING_RECONCILIATION,

    /** Swap committed; staged file is now active. */
    COMMITTED,

    /** Stored media corrupt; fell back to the theme. */
    FALLBACK_TO_THEME,
}

/**
 * Pure transactional import state machine.
 *
 * Pick, stage, verify, confirm, then swap. Cancellation and definite failure
 * retain the previous background and framing with no notice for cancel and a
 * localized notice for failure. Corruption falls back to the theme without
 * changing the connection. Fully JVM testable with no Android types.
 *
 * Uncertainty: when confirmation returns [StandbyConfirmOutcome.Uncertain]
 * or throws (possible commit-then-throw), the model enters
 * [StandbyImportPhase.PENDING_RECONCILIATION] with
 * [StandbyImportNotice.IMPORT_UNCERTAIN] and holds the candidate plus the
 * prior pair. While pending, [pickAndStage] refuses new work (recording the
 * source for retry), [cancel] and [onCorrupt] only record deferred intents,
 * and no discard runs. [reconcile] resolves via [StandbyImportStore.readActive]:
 * proof of commit syncs the true active pair, proof of non-commit safely
 * releases the candidate and then honors deferred intents, and unreadable
 * pointers stay pending with everything preserved. Cancellation is
 * rethrown with pending state intact and never lets a later cancel delete.
 *
 * Ownership: staged slots always hold store-owned copies. The user source is
 * never passed to [StandbyImportStore.discard] and is never deleted. Files whose
 * discard fails are retained in [orphanedStaged] for retry instead of lost.
 * Corruption never deletes storage: only the failing active identity reported
 * by the error callback may trigger presentation fallback, and then only when
 * the trusted pointer still carries that same identity.
 *
 * @param activeFile currently active background, or `null` when theme-only.
 * @param framing currently committed framing.
 */
class StandbyImportFlowModel(
    activeFile: File? = null,
    framing: StandbyFraming = StandbyFraming.CENTERED,
) {
    /** Currently active background, or `null` when theme-only. */
    var activeFile: File? = activeFile
        private set

    /** Currently committed framing. */
    var framing: StandbyFraming = framing
        private set

    /** Current import phase. */
    var phase: StandbyImportPhase = StandbyImportPhase.IDLE
        private set

    /** Latest localized notice. */
    var notice: StandbyImportNotice = StandbyImportNotice.NONE
        private set

    private var staged: StagedPreview? = null
    private var pending: UncertainPending? = null
    private val orphaned = mutableListOf<File>()

    /** Staged candidate awaiting confirmation, or `null` when idle. */
    fun staged(): StagedPreview? = staged

    /** Staged copies whose discard failed; retained for retry, never lost. */
    fun orphanedStaged(): List<File> = orphaned.toList()

    /**
     * Picks a source file and stages it through the store.
     *
     * While reconciliation is pending the request is refused without touching
     * the store: the source is recorded for retry after [reconcile] and the
     * method returns false with phase and notice unchanged. Check [phase]
     * to distinguish this refusal from a definite stage failure. Otherwise
     * previous background and framing stay untouched until confirm. On
     * success the previous staged copy (when distinct) is discarded through
     * the store so no handle leaks; a discard failure retains the old file
     * in [orphanedStaged]. On definite failure (`null`) or staging exception
     * the previous staged candidate is preserved. Cancellation is rethrown
     * with both handles retained.
     *
     * @param source user-chosen source file; never deleted.
     * @param store injectable store seam.
     * @return true when a candidate is staged for preview.
     */
    fun pickAndStage(source: File, store: StandbyImportStore): Boolean {
        val uncertain = pending
        if (uncertain != null) {
            // shortcut: refusal reuses false, phase disambiguates; upgrade to
            // a sealed restage result only if callers need more than phase.
            uncertain.restageSource = source
            Log.w(TAG, "restage deferred until reconciliation proves pointer")
            return false
        }
        val previous = staged
        val preview = try {
            store.stage(source)
        } catch (e: Exception) {
            if (isCancellation(e)) throw e
            Log.w(TAG, "stage failed; preserving previous staged copy", e)
            preserveStagedOnStageFailure(previous)
            return false
        }
        if (preview == null) {
            preserveStagedOnStageFailure(previous)
            return false
        }
        if (previous != null && previous.file != preview.file) {
            try {
                discardGuarded(store, previous.file)
            } catch (e: Exception) {
                // Only cancellation escapes discardGuarded; both handles are
                // retained (old in orphaned, new in the slot), then propagate.
                if (!orphaned.contains(previous.file)) {
                    orphaned.add(previous.file)
                }
                staged = preview
                phase = StandbyImportPhase.STAGED
                notice = StandbyImportNotice.NONE
                throw e
            }
        }
        staged = preview
        phase = StandbyImportPhase.STAGED
        notice = StandbyImportNotice.NONE
        return true
    }

    /**
     * Confirms the staged candidate and swaps the active background.
     *
     * - [StandbyImportDecision.Committed]: publishes the swap.
     * - [StandbyImportDecision.Rejected]: discards the staged copy through
     *   the store before clearing the slot; previous active pair untouched.
     * - [StandbyImportDecision.Uncertain], including confirmation exceptions
     *   (possible commit-then-throw): enters
     *   [StandbyImportPhase.PENDING_RECONCILIATION] with
     *   [StandbyImportNotice.IMPORT_UNCERTAIN], preserving the candidate and
     *   the prior pair for [reconcile]. Never claims the background is
     *   unchanged. Cancellation is rethrown with pending state intact.
     *
     * @param framing framing to publish with the swap.
     * @param store injectable store seam.
     * @return the decision; never a bare boolean.
     */
    fun confirm(framing: StandbyFraming, store: StandbyImportStore): StandbyImportDecision {
        if (pending != null) {
            return StandbyImportDecision.Uncertain
        }
        val candidate = staged ?: run {
            notice = StandbyImportNotice.IMPORT_FAILED
            return StandbyImportDecision.Rejected
        }
        val priorActive = activeFile
        val priorFraming = this.framing
        val outcome = try {
            store.confirm(candidate.file, framing)
        } catch (e: Exception) {
            // Possible commit-then-throw: hold for reconciliation, propagate
            // cancellation with pending state intact.
            enterPending(candidate, priorActive, priorFraming, framing)
            if (isCancellation(e)) throw e
            Log.w(TAG, "confirm threw; holding staged copy as uncertain", e)
            return StandbyImportDecision.Uncertain
        }
        return when (outcome) {
            is StandbyConfirmOutcome.Committed -> {
                // Publish only the store-persisted pair. The request framing
                // must not be assumed: an idempotent duplicate returns the
                // persisted framing without another commit.
                activeFile = outcome.activeFile
                this.framing = outcome.persistedFraming
                staged = null
                phase = StandbyImportPhase.COMMITTED
                notice = StandbyImportNotice.NONE
                StandbyImportDecision.Committed
            }
            StandbyConfirmOutcome.Rejected -> {
                // Definitely not committed: discard before clearing the slot.
                // The previous active background and framing are preserved.
                discardGuarded(store, candidate.file)
                staged = null
                phase = StandbyImportPhase.IDLE
                notice = StandbyImportNotice.IMPORT_FAILED
                StandbyImportDecision.Rejected
            }
            StandbyConfirmOutcome.Uncertain -> {
                enterPending(candidate, priorActive, priorFraming, framing)
                StandbyImportDecision.Uncertain
            }
        }
    }

    /**
     * Cancels the staged candidate without notice.
     *
     * While reconciliation is pending the request is only recorded and
     * returns [StandbyCancelOutcome.Deferred] without touching the store, so
     * a possibly-committed candidate is never deleted. After proof of
     * non-commit [reconcile] applies the deferred cancel safely. Previous
     * background and framing are retained exactly. Discard failure retains
     * the file in [orphanedStaged]. Cancellation during discard propagates
     * with the slot intact.
     *
     * @param store injectable store seam.
     * @return the cancel outcome.
     */
    fun cancel(store: StandbyImportStore): StandbyCancelOutcome {
        val uncertain = pending
        if (uncertain != null) {
            uncertain.cancelRequested = true
            return StandbyCancelOutcome.Deferred
        }
        val candidate = staged ?: run {
            phase = StandbyImportPhase.IDLE
            notice = StandbyImportNotice.NONE
            return StandbyCancelOutcome.NothingPending
        }
        // Only cancellation escapes discardGuarded, leaving the slot intact.
        discardGuarded(store, candidate.file)
        staged = null
        phase = StandbyImportPhase.IDLE
        notice = StandbyImportNotice.NONE
        return StandbyCancelOutcome.Cancelled
    }

    /**
     * Reports stored-media corruption and falls back to the theme.
     *
     * The failing identity must come from the error callback itself via
     * [failedActive]; it is never inferred from [activeFile] at callback
     * time. When no transaction is pending the report is honored only when
     * [failedActive] exactly equals the current [activeFile]; `null`,
     * unknown, or mismatched identities return
     * [StandbyCorruptOutcome.Ignored] with no state change so a healthy new
     * background is never misjudged. When reconciliation is pending the
     * pointer truth is still unknown, so a non-null identity can only be
     * recorded for later proof and returns [StandbyCorruptOutcome.Deferred];
     * `null` while pending carries no decidable identity and is ignored.
     * Storage is never deleted or quarantined here: files and metadata stay
     * intact and only presentation falls back. The staged slot is preserved
     * so a pending candidate is never erased by a corruption report.
     *
     * @param failedActive store-owned active file that failed validation,
     *   or `null` when unknown.
     * @return the corruption handling outcome.
     */
    fun onCorrupt(failedActive: File?): StandbyCorruptOutcome {
        val uncertain = pending
        if (uncertain != null) {
            // Truth is still unknown, so no identity can be judged stale yet:
            // record every non-null callback identity for reconciliation
            // proof, ignore only the undecidable null. Source-of-identity
            // stays with the callback; never read activeFile as a substitute.
            if (failedActive == null) {
                Log.w(TAG, "onCorrupt without identity ignored while pending")
                return StandbyCorruptOutcome.Ignored
            }
            uncertain.corruptReports.add(failedActive)
            return StandbyCorruptOutcome.Deferred
        }
        if (failedActive == null) {
            Log.w(TAG, "onCorrupt without identity ignored")
            return StandbyCorruptOutcome.Ignored
        }
        val current = activeFile
        if (current == null || current != failedActive) {
            Log.w(TAG, "stale corrupt report ignored")
            return StandbyCorruptOutcome.Ignored
        }
        activeFile = null
        phase = StandbyImportPhase.FALLBACK_TO_THEME
        notice = StandbyImportNotice.CORRUPT_FALLBACK
        return StandbyCorruptOutcome.FallenBack
    }

    /**
     * Reconciles a pending uncertain transaction against the store pointer.
     *
     * Adapter-injectable reread: [StandbyActiveRead.Available] proves the
     * pointer (commit vs non-commit) but never proves content health;
     * [StandbyActiveRead.Corrupt] proves decode failure for its identity and
     * falls back presentation with storage kept; [StandbyActiveRead.Unreadable]
     * (or reread failure) returns [StandbyReconcileOutcome.Unresolved] with
     * everything preserved. Pending corruption reports carry callback
     * identities: a report matching the proven pointer without a [Corrupt]
     * reread is unproven and dropped (the host must re-report after a serial
     * [Corrupt] reread for fallback); reports matching nothing are stale and
     * dropped. No branch ever deletes or quarantines active storage.
     * Deferred restage requests are reported via the outcome for the host to
     * retry explicitly. Cancellation propagates with pending state intact.
     *
     * @param store injectable store seam used to reread the pointer.
     * @return the reconciliation outcome.
     */
    fun reconcile(store: StandbyImportStore): StandbyReconcileOutcome {
        val uncertain = pending ?: return StandbyReconcileOutcome.NothingPending
        val read = try {
            store.readActive()
        } catch (e: Exception) {
            if (isCancellation(e)) throw e
            Log.w(TAG, "reread failed; holding uncertain transaction", e)
            return StandbyReconcileOutcome.Unresolved
        }
        if (read is StandbyActiveRead.Corrupt) {
            return resolveProvenCorrupt(store, uncertain, read)
        }
        if (read is StandbyActiveRead.Unreadable) {
            return StandbyReconcileOutcome.Unresolved
        }
        read as StandbyActiveRead.Available
        val truthFile = read.file
        if (truthFile != null && truthFile == uncertain.candidate.file) {
            return resolveCommitted(store, uncertain, read)
        }
        return resolveNotCommitted(store, uncertain, read)
    }

    /**
     * Retries discards for files retained in [orphanedStaged].
     *
     * Refused while reconciliation is pending. Each failure keeps its file
     * listed; only successful discards are removed.
     *
     * @param store injectable store seam.
     * @return remaining orphaned count.
     */
    fun retryPendingCleanup(store: StandbyImportStore): Int {
        if (pending != null) {
            return orphaned.size
        }
        for (file in orphaned.toList()) {
            try {
                store.discard(file)
                orphaned.remove(file)
            } catch (e: Exception) {
                if (isCancellation(e)) throw e
                Log.w(TAG, "retry of orphaned discard failed", e)
            }
        }
        return orphaned.size
    }

    /** Clears the latest notice after the host shows it. */
    fun consumeNotice() {
        notice = StandbyImportNotice.NONE
    }

    private fun resolveProvenCorrupt(
        store: StandbyImportStore,
        uncertain: UncertainPending,
        read: StandbyActiveRead.Corrupt,
    ): StandbyReconcileOutcome {
        val distinctReports = uncertain.corruptReports.toSet()
        val matching = read.file != null &&
            (uncertain.candidate.file == read.file || distinctReports.contains(read.file))
        val restage = uncertain.restageSource
        if (uncertain.cancelRequested) {
            Log.w(TAG, "deferred cancel dropped; proven corrupt takes precedence")
        }
        if (!matching) {
            // Host cross-thread ordering is not atomic here: without a
            // callback identity matching the proven corrupt pointer, keep
            // everything and report unresolved so the host rereads serially.
            Log.w(TAG, "unmatched corrupt pointer; holding pending transaction")
            return StandbyReconcileOutcome.Unresolved
        }
        // Proven decode failure for this same identity: presentation falls
        // back, storage stays intact (never deleted/quarantined here). Only a
        // non-committed candidate is released; a committed candidate is the
        // proven active file itself and must never be passed to discard.
        pending = null
        if (uncertain.candidate.file != read.file) {
            discardGuarded(store, uncertain.candidate.file)
        }
        if (distinctReports.size > 1) {
            Log.w(TAG, "multiple corrupt identities; only proven pointer applied")
        }
        activeFile = null
        framing = read.framing
        staged = null
        phase = StandbyImportPhase.FALLBACK_TO_THEME
        notice = StandbyImportNotice.CORRUPT_FALLBACK
        retryPendingCleanup(store)
        return StandbyReconcileOutcome.CorruptFallback(restage)
    }

    private fun resolveCommitted(
        store: StandbyImportStore,
        uncertain: UncertainPending,
        read: StandbyActiveRead.Available,
    ): StandbyReconcileOutcome {
        if (uncertain.corruptReports.isNotEmpty()) {
            // Readable pointer proves health unknown, never corruption: drop
            // stale/same-identity callback reports without fallback or notice.
            // Host must re-report after a serial Corrupt reread for fallback.
            Log.w(TAG, "corrupt report without proven decode failure dropped as stale")
        }
        activeFile = read.file
        framing = read.framing
        staged = null
        val restage = uncertain.restageSource
        if (uncertain.cancelRequested) {
            Log.w(TAG, "deferred cancel dropped; candidate had committed")
        }
        pending = null
        phase = StandbyImportPhase.COMMITTED
        notice = StandbyImportNotice.NONE
        retryPendingCleanup(store)
        return StandbyReconcileOutcome.Committed(activeFile, framing, restage)
    }

    private fun resolveNotCommitted(
        store: StandbyImportStore,
        uncertain: UncertainPending,
        read: StandbyActiveRead.Available,
    ): StandbyReconcileOutcome {
        // Pointer proves the candidate never became active: safe to release.
        // Failure retains the file in orphanedStaged instead of losing it.
        discardGuarded(store, uncertain.candidate.file)
        val truthFile = read.file
        activeFile = truthFile
        framing = read.framing
        staged = null
        val restage = uncertain.restageSource
        val wantCancel = uncertain.cancelRequested
        pending = null
        if (uncertain.corruptReports.isNotEmpty()) {
            // Non-commit proof plus a pending callback report is unproven:
            // keep the newly proven true active pair and drop the report.
            // Identity stays with the callback; an `Available` reread never
            // proves health and never triggers fallback by itself.
            Log.w(TAG, "corrupt report mismatched proven active; dropped as stale")
        }
        if (wantCancel || restage != null) {
            phase = StandbyImportPhase.IDLE
            notice = StandbyImportNotice.NONE
        } else if (truthFile == uncertain.priorActive && read.framing == uncertain.priorFraming) {
            phase = StandbyImportPhase.IDLE
            notice = StandbyImportNotice.IMPORT_FAILED
        } else {
            // Truth differs from the pre-confirm pair; do not claim it is
            // unchanged.
            phase = StandbyImportPhase.IDLE
            notice = StandbyImportNotice.IMPORT_UNCERTAIN
        }
        retryPendingCleanup(store)
        return StandbyReconcileOutcome.NotCommitted(activeFile, framing, restage)
    }

    private fun enterPending(
        candidate: StagedPreview,
        priorActive: File?,
        priorFraming: StandbyFraming,
        requestedFraming: StandbyFraming,
    ) {
        pending = UncertainPending(candidate, priorActive, priorFraming, requestedFraming)
        staged = candidate
        phase = StandbyImportPhase.PENDING_RECONCILIATION
        notice = StandbyImportNotice.IMPORT_UNCERTAIN
    }

    private fun discardGuarded(store: StandbyImportStore, file: File) {
        try {
            store.discard(file)
        } catch (e: Exception) {
            if (isCancellation(e)) throw e
            Log.w(TAG, "discard failed; handle retained for retry", e)
            if (!orphaned.contains(file)) {
                orphaned.add(file)
            }
        }
    }

    companion object {
        /** Log tag for import flow warnings. */
        private const val TAG = "StandbyImportFlow"

        private fun isCancellation(e: Exception): Boolean {
            return e is kotlinx.coroutines.CancellationException ||
                e is java.util.concurrent.CancellationException
        }
    }

    private data class UncertainPending(
        val candidate: StagedPreview,
        val priorActive: File?,
        val priorFraming: StandbyFraming,
        val requestedFraming: StandbyFraming,
        var cancelRequested: Boolean = false,
        var restageSource: File? = null,
        val corruptReports: MutableList<File> = mutableListOf(),
    )

    private fun preserveStagedOnStageFailure(previous: StagedPreview?) {
        if (previous == null) {
            staged = null
            phase = StandbyImportPhase.IDLE
            notice = StandbyImportNotice.IMPORT_FAILED
        } else {
            // Keep the old pending handle; report the new failure via notice
            // while staying STAGED so the host can retry or cancel.
            staged = previous
            phase = StandbyImportPhase.STAGED
            notice = StandbyImportNotice.IMPORT_FAILED
        }
    }
}
