package me.trinitrix.mirax.ui.standby

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyConfirmResult
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.StandbySnapshot
import me.trinitrix.mirax.data.standby.StandbyStageResult
import me.trinitrix.mirax.ui.standby.media.AndroidStandbyMediaDriver
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import me.trinitrix.mirax.ui.standby.media.StandbyMediaPlaybackController
import me.trinitrix.mirax.ui.standby.media.StandbyMediaUnderlayView
import me.trinitrix.mirax.ui.standby.media.StandbyPlaybackState
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerDriver

/** State of an import whose candidate and draft survive activity replacement. */
internal enum class StandbyMediaEditStatus { EDITING, COMMITTING, UNKNOWN, COMMITTED }

/**
 * Identifies the cleanup attempt that must finish before acquisition.
 *
 * @param generation identity of the retired lease's cleanup.
 * @param attempt identity of the acquisition awaiting that cleanup.
 */
internal class StandbyMediaCleanupPendingException(internal val generation: Long, internal val attempt: Long) :
    IllegalStateException("standby output cleanup is pending")

/** Terminal outcome of a pending cleanup attempt. */
internal enum class StandbyMediaCleanupResult { READY, FAILED }

/** Retained import transaction; the store remains the authority for publication. */
internal data class StandbyMediaEdit(
    val candidate: StagedCandidate,
    val model: StandbyFramingControlsModel,
    val status: StandbyMediaEditStatus,
    val requested: StandbyFraming? = null,
)

/**
 * Whether cancel may restore EDITING after a failed discard.
 *
 * Staging is ignored on purpose: a concurrent stage must not orphan the staged
 * file without a retry handle when the edit slot is still free.
 *
 * @param edit current retained edit, or null when the slot is free.
 * @return true when EDITING may be restored.
 */
internal fun shouldRestoreEditingAfterDiscardFailure(edit: StandbyMediaEdit?): Boolean =
    edit == null

/** Activity-owned recipient; cleared when its media lease is released. */
internal interface StandbyMediaHostCallbacks {
    fun onMediaPresentation(ready: Boolean, decodedSize: Size?, edit: StandbyMediaEdit?, staging: Boolean)
    fun onMediaNotice(message: Int, failedFile: File? = null, token: Long? = null)
}

/**
 * Process-scoped single playback owner and retained import transaction.
 *
 * All public calls and playback callbacks are serialized on the main thread.
 * The driver receives only an application context; activity views are held only
 * while their current lease is attached.
 *
 * @param context application context.
 * @param store existing transactional media authority.
 * @param storageDir the same private directory used by [store].
 * @param playbackDriver optional controllable driver for ownership tests.
 */
internal class StandbyMediaOwner(
    context: Context,
    private val store: StandbyMediaStore,
    storageDir: File,
    playbackDriver: StandbyPlayerDriver? = null,
) {
    private data class Target(val file: File, val kind: StandbyMediaKind)
    private data class Binding(
        val lease: Long,
        val view: StandbyMediaUnderlayView,
        val callbacks: StandbyMediaHostCallbacks,
    )
    private data class Notice(val message: Int, val file: File?, val token: Long?)
    private class CleanupWaiter(
        val generation: Long,
        val attempt: Long,
        val callback: (StandbyMediaCleanupResult) -> Unit,
        var posted: Runnable? = null,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val leases = StandbyHostLease()
    private val driver = AndroidStandbyMediaDriver(appContext, storageDir, store)
    private val trackedDriver = StandbyTrackedDriver(playbackDriver ?: driver, ::updateDecodedSize)
    private var controller = newController()
    private var binding: Binding? = null
    private var cleanupPending = false
    private var cleanupInProgress = false
    private var cleanupGeneration = 0L
    private var acquisitionAttempt = 0L
    private var cleanupWaiter: CleanupWaiter? = null
    private var visible = false
    private var active: StandbyActive? = null
    private var baseline = StandbyFraming.CENTERED
    private var activeLoaded = false
    private var loadingActive = false
    private var refreshPending = false
    private var readRevision = 0L
    private var staging = false
    private var edit: StandbyMediaEdit? = null
    private var assigned: Target? = null
    private var playbackState = StandbyPlaybackState.IDLE
    private var playbackSuspended = true
    private var prepared = false
    private var pendingNotice: Notice? = null
    private var decodedSize: Size? = null
    private var mediaReady = false
    private val sizeCheck = Runnable { updateDecodedSize() }

    /**
     * Rebinds the one output to a replacement activity.
     *
     * @param view activity-owned underlay.
     * @param callbacks activity-owned presentation callbacks.
     * @return lease that must accompany subsequent calls.
     * @throws StandbyMediaCleanupPendingException if an earlier cleanup is still running.
     */
    fun acquire(view: StandbyMediaUnderlayView, callbacks: StandbyMediaHostCallbacks): Long {
        check(!cleanupInProgress) { "standby output cleanup is in progress" }
        acquisitionAttempt += 1
        clearCleanupWaiter()
        if (cleanupPending) {
            cleanupInProgress = true
            try {
                controller.release()
                if (controller.state == StandbyPlaybackState.STOPPING) {
                    throw StandbyMediaCleanupPendingException(cleanupGeneration, acquisitionAttempt)
                }
                check(controller.state == StandbyPlaybackState.RELEASED) { "standby output cleanup failed" }
                driver.bindUnderlay(null)
                controller = newController()
                assigned = null
                playbackSuspended = true
                cleanupPending = false
            } finally {
                cleanupInProgress = false
            }
        }
        val previous = binding
        val wasVisible = visible
        visible = false
        if (wasVisible) {
            try {
                controller.pause()
                playbackSuspended = true
            } catch (failure: Throwable) {
                visible = true
                throw failure
            }
        }
        val lease = leases.acquire()
        binding = Binding(lease, view, callbacks)
        try {
            driver.bindUnderlay(view)
        } catch (failure: Throwable) {
            if (leases.isCurrent(lease)) {
                leases.restore(lease, previous?.lease)
                binding = previous
                visible = wasVisible
                if (wasVisible) {
                    try {
                        controller.resume()
                        playbackSuspended = false
                    } catch (rollback: Throwable) {
                        failure.addSuppressed(rollback)
                    }
                }
            }
            throw failure
        }
        return lease
    }

    /**
     * Waits once for the same failed acquisition's cleanup, without retaining a stopped host.
     *
     * @param pending the pending acquisition exception from [acquire].
     * @param callback receives the terminal cleanup result on a later main-loop turn.
     * @return a cancellation action, or null if the cleanup was superseded.
     */
    fun awaitCleanup(
        pending: StandbyMediaCleanupPendingException,
        callback: (StandbyMediaCleanupResult) -> Unit,
    ): (() -> Unit)? {
        if (!cleanupPending || binding != null || pending.generation != cleanupGeneration ||
            pending.attempt != acquisitionAttempt
        ) return null
        if (controller.state != StandbyPlaybackState.STOPPING && controller.state != StandbyPlaybackState.RELEASED &&
            controller.state != StandbyPlaybackState.ERROR
        ) return null
        clearCleanupWaiter()
        val waiter = CleanupWaiter(pending.generation, pending.attempt, callback)
        cleanupWaiter = waiter
        scheduleCleanupResult()
        return { if (cleanupWaiter === waiter) clearCleanupWaiter() }
    }

    /** Publishes retained state after the caller has stored its new lease. */
    fun synchronize(lease: Long) {
        if (!leases.isCurrent(lease)) return
        binding?.view?.setFraming(currentFraming())
        pendingNotice?.let { pending ->
            pendingNotice = null
            binding?.callbacks?.onMediaNotice(pending.message, pending.file, pending.token)
        }
        publish()
        if (leases.isCurrent(lease) && !activeLoaded && !loadingActive) refreshActive()
    }

    /** Stops and detaches only when [lease] still owns the output. */
    fun release(lease: Long) {
        leases.release(lease) {
            val retiringView = binding?.view
            clearCleanupWaiter()
            cleanupGeneration += 1
            cleanupInProgress = true
            cleanupPending = true
            try {
                binding = null
                visible = false
                handler.removeCallbacks(sizeCheck)
                decodedSize = null
                mediaReady = false
                playbackSuspended = true
                var failure: Throwable? = null
                try {
                    controller.pause()
                } catch (error: Throwable) {
                    failure = error
                }
                try {
                    driver.bindUnderlay(null)
                } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
                if (failure != null) {
                    try {
                        if (retiringView != null) driver.retireUnderlay(retiringView)
                    } catch (retireFailure: Throwable) {
                        Log.e(TAG, "Cannot retire standby underlay", retireFailure)
                        if (failure !== retireFailure) failure.addSuppressed(retireFailure)
                    }
                    throw failure
                }
                // pause() may leave STOPPING while native teardown continues; acquire/awaitCleanup
                // only gate on cleanupPending, so keep it until RELEASED/ERROR or a settled idle.
                cleanupPending = when (controller.state) {
                    StandbyPlaybackState.STOPPING,
                    StandbyPlaybackState.RELEASED,
                    StandbyPlaybackState.ERROR,
                    -> true
                    else -> false
                }
            } finally {
                cleanupInProgress = false
            }
        }
    }

    /** Applies the real standby/editor visibility gate for the current host. */
    fun setVisible(lease: Long, visible: Boolean) {
        if (!leases.isCurrent(lease) || this.visible == visible) return
        this.visible = visible
        if (visible) {
            try {
                reconcilePlayback()
            } catch (failure: Throwable) {
                this.visible = false
                playbackSuspended = true
                throw failure
            }
        } else {
            handler.removeCallbacks(sizeCheck)
            decodedSize = null
            mediaReady = false
            playbackSuspended = true
            controller.pause()
            publish()
        }
    }

    /** Starts a validated private candidate from a document-picker URI. */
    fun importDocument(lease: Long, uri: Uri) {
        if (!leases.isCurrent(lease) || staging || edit != null) return
        staging = true
        readRevision += 1
        scope.launch {
            try {
                val snapshot = store.snapshot()
                val framing = when (snapshot) {
                    StandbySnapshot.NeverSet -> StandbyFraming.CENTERED
                    is StandbySnapshot.Available -> snapshot.active.framing
                    is StandbySnapshot.Broken -> snapshot.framing
                    StandbySnapshot.Unavailable -> {
                        notice(R.string.standby_media_read_failed)
                        return@launch
                    }
                }
                when (val result = store.stageContent(
                    openInput = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException("Document provider returned no stream")
                    },
                    framing = framing,
                )) {
                    is StandbyStageResult.Staged -> {
                        baseline = framing
                        val model = StandbyFramingControlsModel(framing)
                        model.startPreview()
                        invalidatePresentation()
                        edit = StandbyMediaEdit(result.candidate, model, StandbyMediaEditStatus.EDITING)
                        publish()
                        if (visible) reconcilePlayback()
                    }
                    StandbyStageResult.InvalidContent, StandbyStageResult.SourceFailed ->
                        notice(R.string.standby_media_import_failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Cannot stage standby media", failure)
                notice(R.string.standby_media_import_failed)
            } finally {
                staging = false
                if (!activeLoaded && edit == null) refreshActive()
                publish()
            }
        }
        publish()
    }

    /** Applies the draft to the one existing preview output. */
    fun preview(lease: Long, framing: StandbyFraming) {
        if (!leases.isCurrent(lease) || edit?.status != StandbyMediaEditStatus.EDITING) return
        binding?.view?.setFraming(framing)
    }

    /** Requests a transaction without prematurely confirming the editor model. */
    fun confirm(lease: Long, framing: StandbyFraming) {
        if (!leases.isCurrent(lease)) return
        val current = edit ?: return
        val target = Target(current.candidate.file, current.candidate.kind)
        if (current.status != StandbyMediaEditStatus.EDITING || !visible || !mediaReady ||
            assigned != target || desiredTarget() != target || !trackedDriver.isPrepared(target.file)
        ) return
        edit = current.copy(status = StandbyMediaEditStatus.COMMITTING, requested = framing)
        commit(current.candidate, framing, wasUncertain = false)
        publish()
    }

    /** Rechecks and safely retries the same candidate after an ambiguous result. */
    fun retryUnknown(lease: Long) {
        if (!leases.isCurrent(lease)) return
        val current = edit ?: return
        val requested = current.requested ?: return
        if (current.status != StandbyMediaEditStatus.UNKNOWN) return
        edit = current.copy(status = StandbyMediaEditStatus.COMMITTING)
        commit(current.candidate, requested, wasUncertain = true)
        publish()
    }

    /** Cancels only an editable candidate and leaves the active pointer untouched. */
    fun cancel(lease: Long) {
        if (!leases.isCurrent(lease)) return
        val current = edit ?: return
        if (current.status != StandbyMediaEditStatus.EDITING) return
        current.model.cancel()
        invalidatePresentation()
        edit = null
        scope.launch {
            try {
                store.discard(current.candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Cannot discard staged standby media", failure)
                notice(R.string.standby_media_discard_failed)
                // Restore when the edit slot is free even if staging is true, so cancel can retry.
                if (shouldRestoreEditingAfterDiscardFailure(edit)) {
                    edit = StandbyMediaEdit(
                        current.candidate,
                        current.model,
                        StandbyMediaEditStatus.EDITING,
                    )
                    publish()
                    if (visible) reconcilePlayback()
                }
                return@launch
            }
            refreshActive()
        }
        binding?.view?.setFraming(baseline)
        try {
            publish()
        } finally {
            if (visible) reconcilePlayback()
        }
    }

    /** Clears a confirmed editor only after its view has completed the commit. */
    fun acknowledgeCommit(lease: Long) {
        if (!leases.isCurrent(lease) || edit?.status != StandbyMediaEditStatus.COMMITTED) return
        edit = null
        binding?.view?.setFraming(currentFraming())
        publish()
    }
    /**
     * Removes the committed custom background so the selected theme shows again.
     *
     * Only reachable while no candidate is staged: staging owns the store transaction
     * and must finish first. A failed clear reports truthfully and leaves the
     * background in place.
     */
    fun clearCustomBackground(lease: Long) {
        if (!leases.isCurrent(lease)) return
        if (edit != null || staging) return
        scope.launch {
            try {
                if (store.clearActive() == StandbySnapshot.Unavailable) {
                    notice(R.string.standby_media_read_failed)
                    return@launch
                }
                refreshActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Cannot clear standby media", failure)
                notice(R.string.standby_media_read_failed)
            }
        }
    }

    private fun commit(candidate: StagedCandidate, framing: StandbyFraming, wasUncertain: Boolean) {
        scope.launch {
            try {
                if (wasUncertain) {
                    val recovered = matchingActive(candidate, framing)
                    if (recovered != null) {
                        commitKnown(candidate, framing, recovered)
                        return@launch
                    }
                }
                when (val result = store.confirm(candidate, framing)) {
                    is StandbyConfirmResult.Committed -> {
                        if (isExpectedCommit(candidate, framing, result.active)) {
                            commitKnown(candidate, framing, result.active)
                        } else {
                            markUnknown(candidate, framing)
                        }
                    }
                    StandbyConfirmResult.Unavailable -> {
                        val recovered = matchingActive(candidate, framing)
                        if (recovered != null) commitKnown(candidate, framing, recovered)
                        else markUnknown(candidate, framing)
                    }
                    StandbyConfirmResult.Stale, StandbyConfirmResult.CandidateInvalid -> {
                        if (wasUncertain && result == StandbyConfirmResult.CandidateInvalid &&
                            store.snapshot() == StandbySnapshot.Unavailable
                        ) {
                            markUnknown(candidate, framing)
                        } else {
                            val current = edit
                            if (current?.candidate == candidate && current.status == StandbyMediaEditStatus.COMMITTING) {
                                invalidatePresentation()
                                edit = null
                                current.model.cancel()
                                if (wasUncertain) {
                                    active = null
                                    activeLoaded = false
                                }
                                try {
                                    notice(if (wasUncertain) R.string.standby_media_import_uncertain else R.string.standby_media_import_failed)
                                    publish()
                                    if (visible) reconcilePlayback()
                                } finally {
                                    try {
                                        store.discard(candidate)
                                    } finally {
                                        refreshActive()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                markUnknown(candidate, framing)
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Cannot confirm standby media", failure)
                markUnknown(candidate, framing)
            }
        }
    }

    private suspend fun matchingActive(candidate: StagedCandidate, framing: StandbyFraming): StandbyActive? {
        val snapshot = store.validateActive()
        val result = (snapshot as? StandbySnapshot.Available)?.active ?: return null
        return result.takeIf { isExpectedCommit(candidate, framing, it) }
    }

    private fun commitKnown(candidate: StagedCandidate, framing: StandbyFraming, result: StandbyActive) {
        val current = edit ?: return
        if (current.candidate != candidate || current.requested != framing) return
        active = result
        baseline = result.framing
        activeLoaded = true
        edit = current.copy(status = StandbyMediaEditStatus.COMMITTED)
        publish()
    }

    private fun markUnknown(candidate: StagedCandidate, framing: StandbyFraming) {
        val current = edit ?: return
        if (current.candidate != candidate || current.requested != framing) return
        edit = current.copy(status = StandbyMediaEditStatus.UNKNOWN)
        notice(R.string.standby_media_import_uncertain)
        publish()
    }

    private fun refreshActive() {
        if (loadingActive) {
            refreshPending = true
            readRevision += 1
            return
        }
        loadingActive = true
        val revision = ++readRevision
        scope.launch {
            try {
                val snapshot = store.validateActive()
                if (revision != readRevision || edit != null || staging) return@launch
                activeLoaded = true
                when (snapshot) {
                    StandbySnapshot.NeverSet -> {
                        active = null
                        baseline = StandbyFraming.CENTERED
                    }
                    is StandbySnapshot.Available -> {
                        active = snapshot.active
                        baseline = snapshot.active.framing
                    }
                    is StandbySnapshot.Broken -> {
                        active = null
                        baseline = snapshot.framing
                        notice(
                            if (snapshot.failedFile == null) R.string.standby_media_read_failed
                            else R.string.standby_media_corrupt_fallback,
                            snapshot.failedFile,
                        )
                    }
                    StandbySnapshot.Unavailable -> {
                        active = null
                        activeLoaded = false
                        notice(R.string.standby_media_read_failed)
                    }
                }
                binding?.view?.setFraming(currentFraming())
                if (visible) reconcilePlayback() else publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Cannot read standby media", failure)
                if (revision == readRevision) notice(R.string.standby_media_read_failed)
            } finally {
                loadingActive = false
                if (refreshPending) {
                    refreshPending = false
                    refreshActive()
                }
            }
        }
    }

    private fun newController() = StandbyMediaPlaybackController(
        driver = trackedDriver,
        onError = ::onPlaybackError,
        onStateChanged = ::onPlaybackStateChanged,
    )

    private fun currentFraming(): StandbyFraming = edit?.model?.visible() ?: active?.framing ?: baseline

    private fun desiredTarget(): Target? {
        val candidate = edit?.candidate
        if (candidate != null) return Target(candidate.file, candidate.kind)
        val current = active ?: return null
        val file = current.file ?: return null
        val kind = current.kind ?: return null
        return Target(file, kind)
    }

    private fun reconcilePlayback() {
        if (!visible) return
        val target = desiredTarget()
        binding?.view?.setFraming(currentFraming())
        if (target != assigned) {
            assigned = target
            prepared = false
            decodedSize = null
            mediaReady = false
            handler.removeCallbacks(sizeCheck)
            try {
                controller.attach(target?.file, target?.kind == StandbyMediaKind.VIDEO, target?.kind == StandbyMediaKind.GIF)
            } catch (failure: Throwable) {
                assigned = null
                playbackSuspended = true
                throw failure
            }
        }
        if (target != null && playbackSuspended && visible && assigned == target) {
            try {
                if (controller.isPaused) controller.resume()
                if (visible && assigned == target && !controller.isPaused &&
                    controller.state != StandbyPlaybackState.ERROR && controller.state != StandbyPlaybackState.RELEASED
                ) playbackSuspended = false
            } catch (failure: Throwable) {
                playbackSuspended = true
                throw failure
            }
        }
        publish()
    }

    private fun onPlaybackStateChanged(state: StandbyPlaybackState) {
        playbackState = state
        prepared = state == StandbyPlaybackState.READY || state == StandbyPlaybackState.STARTING ||
            state == StandbyPlaybackState.PLAYING
        if (prepared) updateDecodedSize() else {
            handler.removeCallbacks(sizeCheck)
            decodedSize = null
            mediaReady = false
            publish()
        }
        if (state == StandbyPlaybackState.RELEASED || state == StandbyPlaybackState.ERROR) scheduleCleanupResult()
    }

    private fun clearCleanupWaiter() {
        cleanupWaiter?.posted?.let(handler::removeCallbacks)
        cleanupWaiter = null
    }

    private fun scheduleCleanupResult() {
        val waiter = cleanupWaiter ?: return
        if (waiter.posted != null || !cleanupPending || waiter.generation != cleanupGeneration ||
            waiter.attempt != acquisitionAttempt
        ) return
        if (controller.state != StandbyPlaybackState.RELEASED && controller.state != StandbyPlaybackState.ERROR) return
        val delivery = Runnable {
            if (cleanupWaiter === waiter) {
                waiter.posted = null
                if (!cleanupPending || waiter.generation != cleanupGeneration ||
                    waiter.attempt != acquisitionAttempt || binding != null
                ) {
                    cleanupWaiter = null
                } else {
                    val result = when (controller.state) {
                        StandbyPlaybackState.RELEASED -> StandbyMediaCleanupResult.READY
                        StandbyPlaybackState.ERROR -> StandbyMediaCleanupResult.FAILED
                        else -> null
                    }
                    if (result != null) {
                        cleanupWaiter = null
                        waiter.callback(result)
                    }
                }
            }
        }
        waiter.posted = delivery
        if (!handler.post(delivery)) {
            clearCleanupWaiter()
            Log.e(TAG, "Cannot post standby cleanup result")
        }
    }

    private fun updateDecodedSize() {
        handler.removeCallbacks(sizeCheck)
        val target = assigned
        val view = binding?.view
        if (!visible || !prepared || target == null || target != desiredTarget() || view == null ||
            !trackedDriver.isPrepared(target.file)
        ) return
        decodedSize = view.decodedContentSize()
        mediaReady = decodedSize != null &&
            (target.kind == StandbyMediaKind.IMAGE || playbackState == StandbyPlaybackState.PLAYING)
        publish()
        if (decodedSize == null) handler.postDelayed(sizeCheck, SIZE_RETRY_MS)
    }

    private fun onPlaybackError(file: File, token: Long, failure: Throwable) {
        Log.e(TAG, "Standby playback failed for $file (owner $token)", failure)
        if (assigned?.file != file || !trackedDriver.isCurrentError(file, token)) return
        mediaReady = false
        decodedSize = null
        handler.removeCallbacks(sizeCheck)
        notice(R.string.standby_media_playback_failed, file, token)
        publish()
    }

    private fun invalidatePresentation() {
        handler.removeCallbacks(sizeCheck)
        decodedSize = null
        mediaReady = false
    }

    private fun publish() {
        binding?.callbacks?.onMediaPresentation(mediaReady, decodedSize, edit, staging)
    }

    private fun notice(message: Int, file: File? = null, token: Long? = null) {
        val recipient = binding?.callbacks
        if (recipient == null) {
            pendingNotice = Notice(message, file, token)
        } else {
            recipient.onMediaNotice(message, file, token)
        }
    }

    private companion object {
        const val TAG = "StandbyMediaOwner"
        const val SIZE_RETRY_MS = 100L
    }
}
