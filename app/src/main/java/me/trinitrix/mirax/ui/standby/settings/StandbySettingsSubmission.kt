package me.trinitrix.mirax.ui.standby.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Serializes one panel's writes and delivers results only to the current binding.
 * Call its methods on the UI thread; [dispatcher] must dispatch to that thread
 * even when already on it.
 *
 * @param dispatcher non-immediate UI dispatcher used for writes and callbacks.
 * @param render displays a confirmed selection.
 * @param setInputEnabled enables or disables all options.
 * @param logFailure logs the original gateway exception.
 */
internal class StandbySettingsSubmission(
    private val dispatcher: CoroutineDispatcher,
    private val render: (StandbySettingsPanelModel) -> Unit,
    private val setInputEnabled: (Boolean) -> Unit,
    private val logFailure: (Exception) -> Unit,
) {
    private class Binding(
        val scope: CoroutineScope,
        val onStateChanged: ((StandbySettingsPanelModel) -> Unit)?,
        val onLocaleChange: () -> Unit,
        val onError: (Exception) -> Unit,
    )

    var confirmed: StandbySettingsPanelModel = StandbySettingsPanelModel()
        private set

    private var binding: Binding? = null
    private var revision = 0L
    private var pending = false
    private var pendingJob: Job? = null

    /** Replaces the binding without cancelling its host scope. */
    fun bind(
        scope: CoroutineScope,
        onStateChanged: ((StandbySettingsPanelModel) -> Unit)?,
        onLocaleChange: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val previousJob = pendingJob
        binding = Binding(scope, onStateChanged, onLocaleChange, onError)
        previousJob?.cancel()
        render(confirmed)
        setInputEnabled(!pending)
    }

    /** Invalidates callbacks and cancels only the outstanding child job. */
    fun unbind() {
        binding = null
        pendingJob?.cancel()
    }

    /** Displays state supplied by the host and invalidates older completions. */
    fun updateConfirmed(model: StandbySettingsPanelModel) {
        if (confirmed != model) {
            confirmed = model
            revision++
        }
        render(confirmed)
    }

    /**
     * Submits a choice while preventing concurrent writes from this panel.
     *
     * @param model proposed selection.
     * @param changesLocale whether a successful write needs locale rebuilding.
     * @param persist gateway write to await.
     * @return child job, or `null` when input is unavailable.
     */
    fun submit(
        model: StandbySettingsPanelModel,
        changesLocale: Boolean,
        persist: suspend () -> Unit,
    ): Job? {
        val currentBinding = binding
        if (pending || currentBinding == null) {
            render(confirmed)
            return null
        }

        val submittedRevision = revision
        pending = true
        render(confirmed)
        setInputEnabled(false)
        val job = currentBinding.scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                try {
                    currentCoroutineContext().ensureActive()
                    // Let RadioGroup finish its original checked-change callback.
                    yield()
                    persist()
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    logFailure(failure)
                    if (binding === currentBinding) {
                        currentBinding.onError(failure)
                    }
                    return@launch
                }

                val alreadyRendered = confirmed.theme == model.theme && confirmed.language == model.language
                if (binding === currentBinding && (revision == submittedRevision || alreadyRendered)) {
                    if (revision == submittedRevision) {
                        confirmed = model
                    }
                    render(confirmed)
                    currentBinding.onStateChanged?.invoke(confirmed)
                    currentCoroutineContext().ensureActive()
                    if (changesLocale && binding === currentBinding && confirmed.language == model.language) {
                        currentBinding.onLocaleChange()
                    }
                }
            } finally {
                pending = false
                pendingJob = null
                if (binding != null) {
                    setInputEnabled(true)
                }
            }
        }
        pendingJob = job.takeUnless { it.isCompleted }
        return job
    }
}
