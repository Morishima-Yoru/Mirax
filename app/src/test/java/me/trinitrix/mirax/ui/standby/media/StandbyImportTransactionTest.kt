package me.trinitrix.mirax.ui.standby.media

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CancellationException as JvmCancellationException
import kotlinx.coroutines.CancellationException
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import org.junit.Test

/**
 * Transaction-safety checks for [StandbyImportFlowModel].
 *
 * The host `StandbyMediaStore` adapter is not wired yet, so these fakes
 * carry a real active pointer: probes can commit before throwing, and
 * [reconcile] rereads that pointer before any discard. Uncertain work never
 * claims the background is unchanged and deferred cancel/restage/corrupt
 * intents never delete before proof.
 */
class StandbyImportTransactionTest {

    private class PointerTxStore(
        var staged: StagedPreview? = null,
        var activeFile: File? = null,
        var activeFraming: StandbyFraming = StandbyFraming.CENTERED,
        var pointerReadable: Boolean = true,
        var corruptFile: File? = null,
        var corruptFraming: StandbyFraming = StandbyFraming.CENTERED,
        var commitOnConfirm: Boolean = false,
        var confirmThrowable: Throwable? = null,
        var stageThrowable: Throwable? = null,
        var readThrowable: Throwable? = null,
        var failDiscards: Set<File> = emptySet(),
    ) : StandbyImportStore {
        val discarded = mutableListOf<File>()

        override fun stage(source: File): StagedPreview? {
            stageThrowable?.let { throw it }
            return staged
        }

        override fun confirm(staged: File, framing: StandbyFraming): StandbyConfirmOutcome {
            if (commitOnConfirm && confirmThrowable != null) {
                // Real commit-then-throw: pointer flips, then the throw hides it.
                activeFile = staged
                activeFraming = framing
            }
            confirmThrowable?.let { throw it }
            return StandbyConfirmOutcome.Uncertain
        }

        override fun discard(staged: File) {
            if (failDiscards.contains(staged)) {
                throw IllegalStateException("discard boom")
            }
            // A pointer-guarded fake: deleting the active file is forbidden;
            // corrupt storage is never deleted here either.
            check(staged != activeFile) { "fake refused to delete active $staged" }
            discarded.add(staged)
        }

        override fun readActive(): StandbyActiveRead {
            readThrowable?.let { throw it }
            corruptFile?.let { return StandbyActiveRead.Corrupt(it, corruptFraming) }
            if (!pointerReadable) return StandbyActiveRead.Unreadable
            return StandbyActiveRead.Available(activeFile, activeFraming)
        }
    }

    private fun preview(name: String): StagedPreview {
        return StagedPreview(File(name), StandbyMediaKind.IMAGE, StandbyFraming.CENTERED)
    }

    private fun seedUncertain(
        candidate: String = "staged",
        before: File = File("before"),
        framing: StandbyFraming = StandbyFraming.CENTERED,
    ): Triple<StandbyImportFlowModel, PointerTxStore, File> {
        val stagedFile = File(candidate)
        val model = StandbyImportFlowModel(activeFile = before, framing = framing)
        val store = PointerTxStore(staged = preview(candidate), activeFile = before)
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.confirm(framing, store)).isEqualTo(StandbyImportDecision.Uncertain)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_UNCERTAIN)
        return Triple(model, store, stagedFile)
    }

    @Test
    fun commitThenThrowStaleCorruptKeepsNewActive() {
        val framing = StandbyFraming(0.4f, 0.4f, 2f)
        val (model, store, stagedFile) = seedUncertain(framing = framing)
        // Simulate the hidden commit the throw concealed: pointer now active.
        store.activeFile = stagedFile
        store.activeFraming = framing

        assertThat(model.cancel(store)).isEqualTo(StandbyCancelOutcome.Deferred)
        assertThat(model.pickAndStage(File("next"), store)).isFalse()
        // Old-background failure callback after a new commit: stale identity.
        assertThat(model.onCorrupt(File("before"))).isEqualTo(StandbyCorruptOutcome.Deferred)
        assertThat(store.discarded).isEmpty()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_UNCERTAIN)

        // Readable pointer proves health unknown, never corruption: the stale
        // report is dropped, the newly proven active pair stands, no notice.
        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(
            StandbyReconcileOutcome.Committed(stagedFile, framing, File("next")),
        )
        assertThat(model.activeFile).isEqualTo(stagedFile)
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.COMMITTED)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
        assertThat(model.staged()).isNull()
        assertThat(store.discarded).isEmpty()
        assertThat(store.activeFile).isEqualTo(stagedFile)
    }

    @Test
    fun commitThenThrowSameIdentityProvenCorruptFallsBackKeepsStorage() {
        val framing = StandbyFraming(0.4f, 0.4f, 2f)
        val (model, store, stagedFile) = seedUncertain(framing = framing)
        store.activeFile = stagedFile
        store.activeFraming = framing

        assertThat(model.onCorrupt(stagedFile)).isEqualTo(StandbyCorruptOutcome.Deferred)
        // Proven decode failure for the same committed identity.
        store.corruptFile = stagedFile
        store.corruptFraming = framing
        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(StandbyReconcileOutcome.CorruptFallback(null))
        assertThat(model.activeFile).isNull()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.FALLBACK_TO_THEME)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.CORRUPT_FALLBACK)
        assertThat(model.staged()).isNull()
        // Presentation only: fake pointer/file retained, committed active
        // file never passed to discard.
        assertThat(store.activeFile).isEqualTo(stagedFile)
        assertThat(store.discarded).isEmpty()
    }

    @Test
    fun corruptWithoutIdentityOrMismatchNeverTouchesNewActive() {
        val (model, _, stagedFile) = seedUncertain()
        assertThat(model.onCorrupt(null)).isEqualTo(StandbyCorruptOutcome.Ignored)
        // Pending pointer truth is still unknown: a non-null identity cannot
        // be judged stale yet, only recorded for reconciliation proof.
        assertThat(model.onCorrupt(File("other"))).isEqualTo(StandbyCorruptOutcome.Deferred)
        assertThat(model.activeFile).isEqualTo(File("before"))
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_UNCERTAIN)
    }

    @Test
    fun mixedCorruptIdentitiesStayConservative() {
        val framing = StandbyFraming(0.4f, 0.4f, 2f)
        val (model, store, stagedFile) = seedUncertain(framing = framing)
        // Pending identities are recorded for proof, not judged against the
        // still-unknown pointer; each non-null report defers.
        assertThat(model.onCorrupt(File("old-a"))).isEqualTo(StandbyCorruptOutcome.Deferred)
        assertThat(model.onCorrupt(File("old-b"))).isEqualTo(StandbyCorruptOutcome.Deferred)
        // Proven corrupt pointer matches neither callback nor candidate:
        // stay unresolved, so neither identity is misreported.
        store.corruptFile = File("elsewhere")
        store.corruptFraming = framing
        assertThat(model.reconcile(store)).isEqualTo(StandbyReconcileOutcome.Unresolved)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
    }

    @Test
    fun trueCommitThenThrowRereadProvesCommit() {
        val framing = StandbyFraming(0.4f, 0.4f, 2f)
        val before = File("before")
        val model = StandbyImportFlowModel(activeFile = before)
        val store = PointerTxStore(
            staged = preview("staged"),
            activeFile = before,
            commitOnConfirm = true,
            confirmThrowable = IllegalStateException("commit-then-throw"),
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.confirm(framing, store)).isEqualTo(StandbyImportDecision.Uncertain)
        assertThat(store.discarded).isEmpty()
        // Throw hid the flip, but the pointer really committed.
        assertThat(store.activeFile).isEqualTo(File("staged"))

        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(
            StandbyReconcileOutcome.Committed(File("staged"), framing, null),
        )
        assertThat(model.activeFile).isEqualTo(File("staged"))
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.COMMITTED)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
        assertThat(store.discarded).isEmpty()
    }

    @Test
    fun uncertainRereadNotCommittedReleasesSafely() {
        val (model, store, stagedFile) = seedUncertain()
        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(
            StandbyReconcileOutcome.NotCommitted(File("before"), StandbyFraming.CENTERED, null),
        )
        assertThat(store.discarded).containsExactly(stagedFile)
        assertThat(model.staged()).isNull()
        assertThat(model.activeFile).isEqualTo(File("before"))
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_FAILED)
    }

    @Test
    fun uncertainRereadUnreadableKeepsPending() {
        val (model, store, stagedFile) = seedUncertain()
        store.pointerReadable = false
        assertThat(model.reconcile(store)).isEqualTo(StandbyReconcileOutcome.Unresolved)
        assertThat(store.discarded).isEmpty()
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_UNCERTAIN)
    }

    @Test
    fun deferredCancelAppliesOnlyAfterProofOfNonCommit() {
        val (model, store, stagedFile) = seedUncertain()
        assertThat(model.cancel(store)).isEqualTo(StandbyCancelOutcome.Deferred)
        assertThat(store.discarded).isEmpty()
        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(
            StandbyReconcileOutcome.NotCommitted(File("before"), StandbyFraming.CENTERED, null),
        )
        assertThat(store.discarded).containsExactly(stagedFile)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
    }

    @Test
    fun discardFailureKeepsHandleForRetry() {
        val stagedFile = File("staged")
        val model = StandbyImportFlowModel(activeFile = File("before"))
        val store = PointerTxStore(
            staged = preview("staged"),
            failDiscards = setOf(stagedFile),
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.cancel(store)).isEqualTo(StandbyCancelOutcome.Cancelled)
        assertThat(store.discarded).isEmpty()
        assertThat(model.orphanedStaged()).containsExactly(stagedFile)
        assertThat(model.staged()).isNull()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        store.failDiscards = emptySet()
        assertThat(model.retryPendingCleanup(store)).isEqualTo(0)
        assertThat(model.orphanedStaged()).isEmpty()
    }

    @Test
    fun uncertainDefersCleanupRetry() {
        val (model, store, _) = seedUncertain()
        store.failDiscards = setOf(File("staged"))
        assertThat(model.retryPendingCleanup(store)).isEqualTo(0)
        assertThat(model.orphanedStaged()).isEmpty()
        assertThat(store.discarded).isEmpty()
    }

    @Test
    fun staleCorruptReportAfterProvenCommitReconciledAsCommitted() {
        val framing = StandbyFraming(0.4f, 0.4f, 2f)
        val (model, store, stagedFile) = seedUncertain(framing = framing)
        store.activeFile = stagedFile
        store.activeFraming = framing
        // Pending identity is only recorded; it cannot yet be called stale.
        assertThat(model.onCorrupt(File("before"))).isEqualTo(StandbyCorruptOutcome.Deferred)
        // Readable pointer proves the candidate committed but never proves
        // health: the unmatched report is dropped as unproven, never fallback.
        val outcome = model.reconcile(store)
        assertThat(outcome).isEqualTo(
            StandbyReconcileOutcome.Committed(stagedFile, framing, null),
        )
        assertThat(model.activeFile).isEqualTo(stagedFile)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.COMMITTED)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
    }

    @Test
    fun corruptWithoutIdentityNeverFallsBackOrClearsStaged() {
        val model = StandbyImportFlowModel(activeFile = File("before"))
        val seed = PointerTxStore(staged = preview("staged"))
        assertThat(model.pickAndStage(File("src"), seed)).isTrue()
        assertThat(model.onCorrupt(null)).isEqualTo(StandbyCorruptOutcome.Ignored)
        assertThat(model.onCorrupt(File("other"))).isEqualTo(StandbyCorruptOutcome.Ignored)
        assertThat(model.staged()?.file).isEqualTo(File("staged"))
        assertThat(model.activeFile).isEqualTo(File("before"))
        assertThat(model.phase).isEqualTo(StandbyImportPhase.STAGED)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
    }

    @Test
    fun corruptSameIdentityFallsBackKeepsStorageAndStaged() {
        val model = StandbyImportFlowModel(activeFile = File("before"))
        val seed = PointerTxStore(staged = preview("staged"))
        assertThat(model.pickAndStage(File("src"), seed)).isTrue()
        assertThat(model.onCorrupt(File("before"))).isEqualTo(StandbyCorruptOutcome.FallenBack)
        assertThat(model.activeFile).isNull()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.FALLBACK_TO_THEME)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.CORRUPT_FALLBACK)
        // Presentation only: candidate slot untouched, no storage deletion API.
        assertThat(model.staged()?.file).isEqualTo(File("staged"))
    }

    @Test
    fun kotlinxCancellationDuringConfirmStaysPending() {
        val (model, store, stagedFile) = seedUncertain()
        store.confirmThrowable = CancellationException("cancelled")
        store.pointerReadable = false
        // A later cancellation around the commit point keeps pending intact.
        var thrown: Throwable? = null
        try {
            model.reconcile(
                object : StandbyImportStore by store {
                    override fun readActive(): StandbyActiveRead {
                        throw CancellationException("cancelled")
                    }
                },
            )
        } catch (e: CancellationException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
    }

    @Test
    fun jvmCancellationPropagatesFromStage() {
        val first = preview("staged-1")
        val model = StandbyImportFlowModel(activeFile = File("before"))
        val seed = PointerTxStore(staged = first)
        assertThat(model.pickAndStage(File("src"), seed)).isTrue()
        val store = PointerTxStore(
            staged = preview("staged-2"),
            stageThrowable = JvmCancellationException("cancelled"),
        )
        var thrown: Throwable? = null
        try {
            model.pickAndStage(File("src"), store)
        } catch (e: JvmCancellationException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(model.staged()?.file).isEqualTo(first.file)
    }
}
