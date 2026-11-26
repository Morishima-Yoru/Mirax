package me.trinitrix.mirax.ui.standby

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.trinitrix.mirax.MainActivity
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyConfirmResult
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.StandbyMediaValidator
import me.trinitrix.mirax.data.standby.StandbySnapshot
import me.trinitrix.mirax.data.standby.StandbyStageResult
import me.trinitrix.mirax.ui.standby.framing.StandbyFramingEditorView
import me.trinitrix.mirax.ui.standby.media.AndroidStandbyMediaDriver
import me.trinitrix.mirax.ui.standby.media.StandbyMediaUnderlayView
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerDriver
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerListener
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the retained owner, real store and editor with controlled player callbacks. */
@RunWith(AndroidJUnit4::class)
class StandbyMediaOwnerTest {
    /** One-shot failure position relative to applying the real Preferences transform. */
    private enum class UpdateFailure { BEFORE, AFTER }

    private class PreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        private val attempts = AtomicInteger()
        private val writes = AtomicInteger()
        @Volatile var updateGate: CompletableDeferred<Unit>? = null
        @Volatile var nextFailure: UpdateFailure? = null
        @Volatile var readable = true
        val updateWaiting = CountDownLatch(1)
        val currentPreferences: Preferences get() = state.value.toPreferences()
        val updateAttempts: Int get() = attempts.get()
        val appliedUpdates: Int get() = writes.get()
        override val data: Flow<Preferences> = flow {
            if (!readable) throw IOException("simulated unreadable preferences")
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                attempts.incrementAndGet()
                updateGate?.let {
                    updateWaiting.countDown()
                    it.await()
                }
                val failure = nextFailure
                nextFailure = null
                if (failure != null) readable = false
                if (failure == UpdateFailure.BEFORE) throw IOException("simulated pre-update failure")
                transform(state.value).also {
                    state.value = it
                    writes.incrementAndGet()
                    if (failure == UpdateFailure.AFTER) throw IOException("simulated post-update failure")
                }
            }
    }

    private data class Open(val file: File, val token: Long, val listener: StandbyPlayerListener)

    private class ControlledDriver : StandbyPlayerDriver {
        val opens = LinkedBlockingQueue<Open>()
        var releaseFailures = 0
        var pendingRelease = false
        var releaseCalls = 0

        override fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener) {
            opens.add(Open(file, token, listener))
        }

        override fun restart() = Unit
        override fun pause() = Unit
        override fun release(): Boolean {
            releaseCalls += 1
            if (releaseFailures > 0) {
                releaseFailures -= 1
                throw IOException("simulated close failure")
            }
            return !pendingRelease
        }

        fun awaitOpen(): Open = checkNotNull(opens.poll(10, TimeUnit.SECONDS)) { "playback did not open" }
    }

    private class Recorder(private val context: Context) : StandbyMediaHostCallbacks {
        lateinit var owner: StandbyMediaOwner
        lateinit var view: StandbyMediaUnderlayView
        var lease = 0L
        var editor: StandbyFramingEditorView? = null
        var edit: StandbyMediaEdit? = null
        var ready = false
        var size: Size? = null
        val staged = CountDownLatch(1)
        val committed = CountDownLatch(1)
        var unknown: CountDownLatch? = null
        var lastNotice: Int? = null

        override fun onMediaPresentation(ready: Boolean, decodedSize: Size?, edit: StandbyMediaEdit?, staging: Boolean) {
            this.ready = ready
            this.size = decodedSize
            this.edit = edit
            if (edit != null && editor == null) {
                editor = StandbyFramingEditorView(context, view, edit.model, {}, { owner.confirm(lease, it) }, {})
                layout(checkNotNull(editor))
            }
            decodedSize?.let { editor?.setContentSize(it.width.toFloat(), it.height.toFloat()) }
            editor?.setPlaybackReady(ready)
            if (edit != null && !staging) staged.countDown()
            if (edit?.status == StandbyMediaEditStatus.COMMITTED) committed.countDown()
            if (edit?.status == StandbyMediaEditStatus.UNKNOWN) unknown?.countDown()
        }

        override fun onMediaNotice(message: Int, failedFile: File?, token: Long?) {
            lastNotice = message
        }
    }

    private class Fixture(
        val dir: File,
        val source: File,
        val store: StandbyMediaStore,
        val preferences: PreferencesStore,
        val oldFile: File,
        val driver: ControlledDriver,
        val owner: StandbyMediaOwner,
        val recorder: Recorder,
        val first: Open,
    )

    @Test
    fun pausedReplacementOpensEditorAndRejectsStartingErrorAndStaleCallbacks() {
        val fixture = createFixture()
        try {
            onMain {
                preparedAndRendering(fixture.first)
                fixture.owner.setVisible(fixture.recorder.lease, false)
                fixture.owner.importDocument(fixture.recorder.lease, Uri.fromFile(fixture.source))
            }
            assertThat(fixture.recorder.staged.await(10, TimeUnit.SECONDS)).isTrue()
            onMain {
                assertThat(fixture.recorder.editor).isNotNull()
                assertThat(confirmButton(fixture.recorder).isEnabled).isFalse()
                fixture.owner.setVisible(fixture.recorder.lease, true)
            }
            val candidate = fixture.driver.awaitOpen()
            assertThat(candidate.file).isNotEqualTo(fixture.first.file)
            onMain {
                candidate.listener.onPrepared(candidate.file, candidate.token)
                assertThat(fixture.recorder.size).isNotNull()
                assertThat(fixture.recorder.ready).isFalse()
                assertThat(confirmButton(fixture.recorder).isEnabled).isFalse()
                fixture.owner.confirm(fixture.recorder.lease, StandbyFraming.CENTERED)
                confirmButton(fixture.recorder).performClick()
                assertThat(fixture.recorder.edit?.status).isEqualTo(StandbyMediaEditStatus.EDITING)

                fixture.first.listener.onRendering(fixture.first.file, fixture.first.token)
                fixture.first.listener.onError(fixture.first.file, fixture.first.token, IOException("stale"))
                candidate.listener.onRendering(candidate.file, candidate.token + 1)
                candidate.listener.onRendering(fixture.first.file, candidate.token)
                assertThat(fixture.recorder.ready).isFalse()
                candidate.listener.onRendering(candidate.file, candidate.token)
                assertThat(fixture.recorder.ready).isTrue()
                assertThat(confirmButton(fixture.recorder).isEnabled).isTrue()
                candidate.listener.onError(candidate.file, candidate.token, IOException("playback failed"))
                assertThat(fixture.recorder.ready).isFalse()
                assertThat(confirmButton(fixture.recorder).isEnabled).isFalse()
                fixture.owner.confirm(fixture.recorder.lease, StandbyFraming.CENTERED)
                assertThat(fixture.recorder.edit?.status).isEqualTo(StandbyMediaEditStatus.EDITING)
            }
            val snapshot = runBlocking { fixture.store.snapshot() } as StandbySnapshot.Available
            assertThat(snapshot.active.file).isEqualTo(fixture.oldFile)
            assertThat(fixture.oldFile.exists()).isTrue()
            assertThat(candidate.file.exists()).isTrue()
        } finally {
            closeFixture(fixture)
        }
    }

    @Test
    fun playbackFailureDoesNotResolvePendingCommit() {
        val fixture = createFixture()
        val gate = CompletableDeferred<Unit>()
        try {
            onMain {
                preparedAndRendering(fixture.first)
                fixture.owner.setVisible(fixture.recorder.lease, false)
                fixture.owner.importDocument(fixture.recorder.lease, Uri.fromFile(fixture.source))
            }
            assertThat(fixture.recorder.staged.await(10, TimeUnit.SECONDS)).isTrue()
            onMain { fixture.owner.setVisible(fixture.recorder.lease, true) }
            val candidate = fixture.driver.awaitOpen()
            fixture.preferences.updateGate = gate
            onMain {
                preparedAndRendering(candidate)
                assertThat(confirmButton(fixture.recorder).isEnabled).isTrue()
                confirmButton(fixture.recorder).performClick()
            }
            assertThat(fixture.preferences.updateWaiting.await(10, TimeUnit.SECONDS)).isTrue()
            onMain {
                candidate.listener.onError(candidate.file, candidate.token, IOException("playback failed during save"))
                assertThat(fixture.recorder.edit?.status).isEqualTo(StandbyMediaEditStatus.COMMITTING)
                assertThat(checkNotNull(fixture.recorder.editor).isCommitPending).isTrue()
                assertThat(confirmButton(fixture.recorder).isEnabled).isFalse()
            }
            gate.complete(Unit)
            assertThat(fixture.recorder.committed.await(10, TimeUnit.SECONDS)).isTrue()
            val snapshot = runBlocking { fixture.store.snapshot() } as StandbySnapshot.Available
            assertThat(snapshot.active.file).isEqualTo(candidate.file)
        } finally {
            gate.complete(Unit)
            closeFixture(fixture)
        }
    }

    /** A pre-update failure stays uncertain until the original handle can publish on reliable reads. */
    @Test
    fun unknownPreCommitRemainsPendingUntilSameCandidateRetry() {
        assertUnknownRecovery(UpdateFailure.BEFORE)
    }

    /** A commit-then-throw resolves from its real persisted tuple without another publication. */
    @Test
    fun unknownCommittedOutcomeResolvesFromReadbackWithoutRepublish() {
        assertUnknownRecovery(UpdateFailure.AFTER)
    }

    @Test
    fun failedReleaseRetriesBeforeNewLeaseAndOldReleaseCannotAffectIt() {
        val fixture = createFixture()
        val oldLease = fixture.recorder.lease
        try {
            onMain {
                preparedAndRendering(fixture.first)
                fixture.driver.releaseFailures = 2
                assertThat(runCatching { fixture.owner.release(oldLease) }.isFailure).isTrue()
                val nextView = StandbyMediaUnderlayView(fixture.recorder.view.context)
                nextView.setBitmap(Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888))
                assertThat(runCatching { fixture.owner.acquire(nextView, fixture.recorder) }.isFailure).isTrue()
                fixture.recorder.view = nextView
                val nextLease = fixture.owner.acquire(nextView, fixture.recorder)
                fixture.recorder.lease = nextLease
                fixture.owner.synchronize(nextLease)
                fixture.owner.setVisible(nextLease, true)
                fixture.owner.release(oldLease)
            }
            val replacement = fixture.driver.awaitOpen()
            onMain {
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                fixture.first.listener.onRendering(fixture.first.file, fixture.first.token)
                preparedAndRendering(replacement)
                assertThat(fixture.recorder.ready).isTrue()
                assertThat(fixture.recorder.size).isNotNull()
                val occupied = StandbyMediaUnderlayView(fixture.recorder.view.context)
                val otherDriver = AndroidStandbyMediaDriver(fixture.recorder.view.context, fixture.dir, fixture.store)
                otherDriver.bindUnderlay(occupied)
                assertThat(runCatching { fixture.owner.acquire(occupied, fixture.recorder) }.isFailure).isTrue()
                assertThat(otherDriver.isBoundUnderlay(occupied)).isTrue()
                otherDriver.bindUnderlay(null)
            }
            val reopened = fixture.driver.awaitOpen()
            onMain {
                fixture.owner.release(oldLease)
                preparedAndRendering(reopened)
                assertThat(fixture.recorder.ready).isTrue()
            }
        } finally {
            closeFixture(fixture)
        }
    }

    @Test
    fun lateCleanupRegistrationPostsReadyOnceAfterAsyncRelease() {
        val fixture = createFixture()
        val results = mutableListOf<StandbyMediaCleanupResult>()
        try {
            onMain {
                val (pending, _) = beginAsyncCleanup(fixture)
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                val cancel = fixture.owner.awaitCleanup(pending) { results.add(it) }
                assertThat(cancel).isNotNull()
                assertThat(results).isEmpty()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertThat(results).containsExactly(StandbyMediaCleanupResult.READY)
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertThat(results).containsExactly(StandbyMediaCleanupResult.READY)
        } finally {
            closeFixture(fixture)
        }
    }

    @Test
    fun cancelledOrSupersededCleanupCannotAcquireOverNewLease() {
        val fixture = createFixture()
        val results = mutableListOf<StandbyMediaCleanupResult>()
        try {
            onMain {
                val oldLease = fixture.recorder.lease
                val (pending, view) = beginAsyncCleanup(fixture)
                val cancel = checkNotNull(fixture.owner.awaitCleanup(pending) { results.add(it) })
                cancel()
                val cancelPosted = checkNotNull(fixture.owner.awaitCleanup(pending) { results.add(it) })
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                cancelPosted()
                checkNotNull(fixture.owner.awaitCleanup(pending) { results.add(it) })
                fixture.driver.pendingRelease = false
                fixture.recorder.view = view
                val lease = fixture.owner.acquire(view, fixture.recorder)
                fixture.recorder.lease = lease
                fixture.owner.synchronize(lease)
                fixture.owner.setVisible(lease, true)
                fixture.owner.release(oldLease)
                assertThat(fixture.owner.awaitCleanup(pending) { error("stale cleanup") }).isNull()
            }
            val replacement = fixture.driver.awaitOpen()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                preparedAndRendering(replacement)
                assertThat(fixture.recorder.ready).isTrue()
                assertThat(results).isEmpty()
            }
        } finally {
            fixture.driver.pendingRelease = false
            closeFixture(fixture)
        }
    }

    @Test
    fun olderHostCannotReplaceTheNewerCleanupWaiter() {
        val fixture = createFixture()
        val results = mutableListOf<StandbyMediaCleanupResult>()
        try {
            onMain {
                val (oldPending, view) = beginAsyncCleanup(fixture)
                checkNotNull(fixture.owner.awaitCleanup(oldPending) { error("stale waiter") })
                val failure = runCatching { fixture.owner.acquire(view, fixture.recorder) }.exceptionOrNull()
                val newPending = failure as StandbyMediaCleanupPendingException
                checkNotNull(fixture.owner.awaitCleanup(newPending) { results.add(it) })
                assertThat(fixture.owner.awaitCleanup(oldPending) { error("stale registration") }).isNull()
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                assertThat(results).isEmpty()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertThat(results).containsExactly(StandbyMediaCleanupResult.READY)
        } finally {
            closeFixture(fixture)
        }
    }

    @Test
    fun asynchronousCleanupFailureNotifiesOnceWithoutRetry() {
        val fixture = createFixture()
        val results = mutableListOf<StandbyMediaCleanupResult>()
        try {
            onMain {
                val (pending, _) = beginAsyncCleanup(fixture)
                checkNotNull(fixture.owner.awaitCleanup(pending) { results.add(it) })
                fixture.first.listener.onReleaseFailed(fixture.first.file, fixture.first.token, IOException("async close"))
                assertThat(results).isEmpty()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertThat(results).containsExactly(StandbyMediaCleanupResult.FAILED)
                assertThat(fixture.driver.releaseCalls).isEqualTo(2)
            }
        } finally {
            onMain {
                fixture.driver.pendingRelease = false
                val view = StandbyMediaUnderlayView(fixture.recorder.view.context)
                fixture.recorder.lease = fixture.owner.acquire(view, fixture.recorder)
            }
            closeFixture(fixture)
        }
    }

    @Test
    fun startedActivityReacquiresOnceAfterAsyncCleanup() {
        val fixture = createFixture()
        val oldLease = fixture.recorder.lease
        withAsyncCleanupActivity(fixture) { scenario ->
            scenario.onActivity { activity ->
                assertThat(activityField("mediaLease").get(activity)).isNull()
                assertThat(activityField("mediaCleanupCancel").get(activity)).isNotNull()
                fixture.driver.pendingRelease = false
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                assertThat(activityField("mediaLease").get(activity)).isNull()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val lease = activityField("mediaLease").get(activity) as Long?
                assertThat(lease).isNotNull()
                assertThat(activityField("mediaCleanupCancel").get(activity)).isNull()
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                fixture.owner.release(oldLease)
                assertThat(activityField("mediaLease").get(activity)).isEqualTo(lease)
                assertThat(fixture.driver.releaseCalls).isEqualTo(2)
            }
        }
    }

    @Test
    fun stoppedActivityCancelsAsyncCleanupRetry() {
        val fixture = createFixture()
        withAsyncCleanupActivity(fixture) { scenario ->
            lateinit var stopped: MainActivity
            scenario.onActivity { activity ->
                stopped = activity
                assertThat(activityField("mediaCleanupCancel").get(activity)).isNotNull()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            onMain {
                val waiter = StandbyMediaOwner::class.java.getDeclaredField("cleanupWaiter").apply { isAccessible = true }
                assertThat(waiter.get(fixture.owner)).isNull()
                fixture.driver.pendingRelease = false
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertThat(activityField("mediaLease").get(stopped)).isNull()
                assertThat(fixture.driver.releaseCalls).isEqualTo(2)
                assertThat(fixture.driver.opens).isEmpty()
            }
        }
    }

    @Test
    fun staleActivityInstanceCannotConsumeCleanupNotification() {
        val fixture = createFixture()
        withAsyncCleanupActivity(fixture) { scenario ->
            val instanceField = MainActivity::class.java.getDeclaredField("instance").apply { isAccessible = true }
            val original = instanceField.get(null)
            var activity: MainActivity? = null
            try {
                scenario.onActivity {
                    activity = it
                    instanceField.set(null, null)
                    fixture.driver.pendingRelease = false
                    fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onMain {
                    assertThat(activityField("mediaLease").get(checkNotNull(activity))).isNull()
                    assertThat(fixture.driver.releaseCalls).isEqualTo(2)
                    assertThat(fixture.driver.opens).isEmpty()
                }
            } finally {
                onMain { instanceField.set(null, original) }
            }
        }
    }

    @Test
    fun staleActivityRequestGenerationCannotConsumeCleanupNotification() {
        val fixture = createFixture()
        withAsyncCleanupActivity(fixture) { scenario ->
            scenario.onActivity { activity ->
                val generation = activityField("mediaAcquireGeneration")
                generation.setLong(activity, generation.getLong(activity) + 1)
                fixture.driver.pendingRelease = false
                fixture.first.listener.onReleased(fixture.first.file, fixture.first.token)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertThat(activityField("mediaLease").get(activity)).isNull()
                assertThat(fixture.driver.releaseCalls).isEqualTo(2)
                assertThat(fixture.driver.opens).isEmpty()
            }
        }
    }

    @Test
    fun activityDoesNotRetryAfterAsyncCleanupError() {
        val fixture = createFixture()
        withAsyncCleanupActivity(fixture) { scenario ->
            scenario.onActivity {
                fixture.first.listener.onReleaseFailed(fixture.first.file, fixture.first.token, IOException("async close"))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertThat(activityField("mediaLease").get(activity)).isNull()
                assertThat(activityField("mediaCleanupCancel").get(activity)).isNull()
                assertThat(fixture.driver.releaseCalls).isEqualTo(2)
                assertThat(fixture.driver.opens).isEmpty()
            }
        }
    }

    private fun withAsyncCleanupActivity(fixture: Fixture, test: (ActivityScenario<MainActivity>) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val ownerField = MiraxApp::class.java.getDeclaredField("standbyMediaOwner").apply { isAccessible = true }
        val original = ownerField.get(app)
        try {
            onMain {
                prepareFailedRelease(fixture)
                ownerField.set(app, fixture.owner)
            }
            ActivityScenario.launch(MainActivity::class.java).use(test)
        } finally {
            onMain {
                fixture.driver.pendingRelease = false
                ownerField.set(app, original)
            }
            closeFixture(fixture)
        }
    }

    private fun activityField(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun beginAsyncCleanup(fixture: Fixture): Pair<StandbyMediaCleanupPendingException, StandbyMediaUnderlayView> {
        prepareFailedRelease(fixture)
        val view = StandbyMediaUnderlayView(fixture.recorder.view.context)
        view.setBitmap(Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888))
        val failure = runCatching { fixture.owner.acquire(view, fixture.recorder) }.exceptionOrNull()
        return (failure as StandbyMediaCleanupPendingException) to view
    }

    private fun prepareFailedRelease(fixture: Fixture) {
        preparedAndRendering(fixture.first)
        fixture.driver.releaseFailures = 1
        fixture.driver.pendingRelease = true
        assertThat(runCatching { fixture.owner.release(fixture.recorder.lease) }.isFailure).isTrue()
    }

    /** Exercises only controlled read availability and one ambiguous real-store update, never injected owner outcomes. */
    private fun assertUnknownRecovery(failure: UpdateFailure) {
        val initialFraming = StandbyFraming(0.25f, 0.75f, 1.4f)
        val requested = StandbyFraming(0.7f, 0.3f, 2.2f)
        val fixture = createFixture(initialFraming)
        var settled = CountDownLatch(0)
        var primaryFailure: Throwable? = null
        var restoreInterrupt = false

        /** @param error Retains the first exact throwable and supplements it without losing interruption. */
        fun retainFailure(error: Throwable) {
            restoreInterrupt = Thread.interrupted() || error is InterruptedException || restoreInterrupt
            val primary = primaryFailure
            if (primary == null) primaryFailure = error
            else if (primary !== error) primary.addSuppressed(error)
        }

        try {
            val oldActive = (runBlocking { fixture.store.snapshot() } as StandbySnapshot.Available).active
            val oldPreferences = fixture.preferences.currentPreferences
            val oldBytes = fixture.oldFile.readBytes()
            assertThat(oldActive.framing).isEqualTo(initialFraming)
            assertThat(oldActive.file).isEqualTo(fixture.oldFile)
            settled = fixture.recorder.staged
            onMain {
                preparedAndRendering(fixture.first)
                fixture.owner.setVisible(fixture.recorder.lease, false)
                fixture.owner.importDocument(fixture.recorder.lease, Uri.fromFile(fixture.source))
            }
            assertThat(fixture.recorder.staged.await(10, TimeUnit.SECONDS)).isTrue()
            onMain { fixture.owner.setVisible(fixture.recorder.lease, true) }
            val candidate = fixture.driver.awaitOpen()
            val candidateBytes = candidate.file.readBytes()
            val attemptsBeforeCommit = fixture.preferences.updateAttempts
            val writesBeforeCommit = fixture.preferences.appliedUpdates
            assertThat(attemptsBeforeCommit).isEqualTo(1)
            assertThat(writesBeforeCommit).isEqualTo(1)
            assertThat(fixture.preferences.currentPreferences).isEqualTo(oldPreferences)
            assertThat(candidate.file).isNotEqualTo(fixture.oldFile)
            lateinit var originalEdit: StandbyMediaEdit
            val firstUnknown = CountDownLatch(1)
            settled = firstUnknown
            onMain {
                preparedAndRendering(candidate)
                assertThat(fixture.recorder.ready).isTrue()
                originalEdit = checkNotNull(fixture.recorder.edit)
                assertThat(originalEdit.status).isEqualTo(StandbyMediaEditStatus.EDITING)
                assertThat(originalEdit.candidate.file).isEqualTo(candidate.file)
                assertThat(originalEdit.candidate.framing).isEqualTo(oldActive.framing)
                assertThat(originalEdit.model.committed).isEqualTo(oldActive.framing)
                fixture.recorder.unknown = firstUnknown
                fixture.preferences.nextFailure = failure
                fixture.owner.confirm(fixture.recorder.lease, requested)
            }
            assertThat(firstUnknown.await(10, TimeUnit.SECONDS)).isTrue()
            val handle = originalEdit.candidate
            val expectedActive = StandbyActive(handle.file, handle.kind, requested, handle.generation)
            assertThat(expectedActive.generation).isGreaterThan(oldActive.generation)
            // Expected value only; recovery changes availability, never the stored Preferences.
            val expectedCommittedPreferences = oldPreferences.toMutablePreferences().apply {
                this[stringPreferencesKey("standby_media_basename")] = handle.file.name
                this[stringPreferencesKey("standby_media_kind")] = handle.kind.name
                this[longPreferencesKey("standby_media_generation")] = handle.generation
                this[floatPreferencesKey("standby_framing_center_x")] = requested.centerX
                this[floatPreferencesKey("standby_framing_center_y")] = requested.centerY
                this[floatPreferencesKey("standby_framing_zoom")] = requested.zoom
            }.toPreferences()
            val storedAfterFailure = fixture.preferences.currentPreferences
            val attemptsAfterFailure = fixture.preferences.updateAttempts
            val writesAfterFailure = fixture.preferences.appliedUpdates
            assertThat(attemptsAfterFailure).isEqualTo(attemptsBeforeCommit + 1)
            assertThat(writesAfterFailure).isEqualTo(writesBeforeCommit + if (failure == UpdateFailure.AFTER) 1 else 0)
            assertThat(storedAfterFailure).isEqualTo(
                if (failure == UpdateFailure.BEFORE) oldPreferences else expectedCommittedPreferences,
            )

            fun assertUnreadableUnknown() {
                assertThat(runBlocking { fixture.store.snapshot() }).isEqualTo(StandbySnapshot.Unavailable)
                assertThat(fixture.preferences.currentPreferences).isEqualTo(storedAfterFailure)
                assertThat(fixture.preferences.updateAttempts).isEqualTo(attemptsAfterFailure)
                assertThat(fixture.preferences.appliedUpdates).isEqualTo(writesAfterFailure)
                assertThat(fixture.oldFile.isFile).isTrue()
                assertThat(candidate.file.isFile).isTrue()
                assertThat(fixture.oldFile.readBytes()).isEqualTo(oldBytes)
                assertThat(candidate.file.readBytes()).isEqualTo(candidateBytes)
                onMain {
                    assertRetainedEdit(fixture, originalEdit, requested, StandbyMediaEditStatus.UNKNOWN)
                    assertThat(fixture.recorder.lastNotice).isEqualTo(R.string.standby_media_import_uncertain)
                }
            }

            assertUnreadableUnknown()
            onMain {
                candidate.listener.onError(candidate.file, candidate.token, IOException("playback failed while unknown"))
                assertThat(fixture.recorder.ready).isFalse()
                assertThat(fixture.recorder.lastNotice).isEqualTo(R.string.standby_media_playback_failed)
                assertRetainedEdit(fixture, originalEdit, requested, StandbyMediaEditStatus.UNKNOWN)
                fixture.owner.cancel(fixture.recorder.lease)
                fixture.owner.confirm(fixture.recorder.lease, initialFraming)
                assertRetainedEdit(fixture, originalEdit, requested, StandbyMediaEditStatus.UNKNOWN)
            }
            val retryUnknown = CountDownLatch(1)
            settled = retryUnknown
            onMain {
                fixture.recorder.unknown = retryUnknown
                fixture.owner.retryUnknown(fixture.recorder.lease)
            }
            assertThat(retryUnknown.await(10, TimeUnit.SECONDS)).isTrue()
            assertUnreadableUnknown()

            fixture.preferences.readable = true
            val recovered = (runBlocking { fixture.store.snapshot() } as StandbySnapshot.Available).active
            assertThat(recovered).isEqualTo(if (failure == UpdateFailure.BEFORE) oldActive else expectedActive)
            assertThat(fixture.preferences.currentPreferences).isEqualTo(storedAfterFailure)
            settled = fixture.recorder.committed
            onMain { fixture.owner.retryUnknown(fixture.recorder.lease) }
            assertThat(fixture.recorder.committed.await(10, TimeUnit.SECONDS)).isTrue()
            onMain {
                assertRetainedEdit(fixture, originalEdit, requested, StandbyMediaEditStatus.COMMITTED)
                assertThat(fixture.driver.opens).isEmpty()
            }
            val active = (runBlocking { fixture.store.validateActive() } as StandbySnapshot.Available).active
            assertThat(active).isEqualTo(expectedActive)
            if (failure == UpdateFailure.BEFORE) {
                assertThat(fixture.preferences.updateAttempts).isEqualTo(attemptsAfterFailure + 1)
                assertThat(fixture.preferences.appliedUpdates).isEqualTo(writesAfterFailure + 1)
                assertThat(fixture.preferences.currentPreferences).isEqualTo(expectedCommittedPreferences)
                assertThat(fixture.oldFile.exists()).isFalse()
            } else {
                assertThat(fixture.preferences.updateAttempts).isEqualTo(attemptsAfterFailure)
                assertThat(fixture.preferences.appliedUpdates).isEqualTo(writesAfterFailure)
                assertThat(fixture.preferences.currentPreferences).isEqualTo(storedAfterFailure)
            }
            assertThat(candidate.file.isFile).isTrue()
            assertThat(candidate.file.readBytes()).isEqualTo(candidateBytes)
        } catch (error: Throwable) {
            retainFailure(error)
        } finally {
            restoreInterrupt = Thread.interrupted() || restoreInterrupt
            var settledSuccessfully = false
            try {
                try {
                    settledSuccessfully = settled.await(10, TimeUnit.SECONDS)
                    check(settledSuccessfully) { "Owner work unsettled; owned fixture files retained" }
                } catch (error: Throwable) {
                    retainFailure(error)
                }
                try {
                    if (settledSuccessfully) closeFixture(fixture)
                    else onMain { fixture.owner.release(fixture.recorder.lease) }
                } catch (error: Throwable) {
                    retainFailure(error)
                }
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
        }
        primaryFailure?.let { throw it }
    }

    /** Checks actual callback state and retained identities, without inferring editor/model pending state. */
    private fun assertRetainedEdit(
        fixture: Fixture,
        original: StandbyMediaEdit,
        requested: StandbyFraming,
        status: StandbyMediaEditStatus,
    ) {
        val current = checkNotNull(fixture.recorder.edit)
        assertThat(current.status).isEqualTo(status)
        assertThat(current.candidate).isSameInstanceAs(original.candidate)
        assertThat(current.model).isSameInstanceAs(original.model)
        assertThat(current.model.committed).isEqualTo(original.candidate.framing)
        assertThat(current.requested).isEqualTo(requested)
    }

    private fun createFixture(initialFraming: StandbyFraming = StandbyFraming.CENTERED): Fixture {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Mirax)
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "standby-owner-").toFile()
        val source = File.createTempFile("standby-source-", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(2)) }
        val preferences = PreferencesStore()
        val store = StandbyMediaStore(preferences, StandbyMediaValidator { StandbyMediaKind.VIDEO }, dir)
        val oldFile = runBlocking {
            val stage = store.stageContent({ ByteArrayInputStream(byteArrayOf(1)) }, initialFraming)
                as StandbyStageResult.Staged
            val result = store.confirm(stage.candidate, initialFraming) as StandbyConfirmResult.Committed
            checkNotNull(result.active.file)
        }
        val driver = ControlledDriver()
        val recorder = Recorder(context)
        lateinit var owner: StandbyMediaOwner
        onMain {
            val view = StandbyMediaUnderlayView(context)
            view.setBitmap(Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888))
            owner = StandbyMediaOwner(context, store, dir, driver)
            recorder.owner = owner
            recorder.view = view
            recorder.lease = owner.acquire(view, recorder)
            owner.synchronize(recorder.lease)
            owner.setVisible(recorder.lease, true)
        }
        return Fixture(dir, source, store, preferences, oldFile, driver, owner, recorder, driver.awaitOpen())
    }

    private fun closeFixture(fixture: Fixture) {
        onMain { fixture.owner.release(fixture.recorder.lease) }
        fixture.source.delete()
        fixture.dir.deleteRecursively()
    }

    private fun preparedAndRendering(open: Open) {
        open.listener.onPrepared(open.file, open.token)
        open.listener.onRendering(open.file, open.token)
    }

    private fun confirmButton(recorder: Recorder): Button = checkNotNull(findButton(checkNotNull(recorder.editor)))

    private fun findButton(view: View): Button? {
        if (view is Button && view.text == view.context.getString(R.string.standby_media_confirm)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private companion object {
        fun layout(view: View) {
            val density = view.resources.displayMetrics.density
            val width = (480 * density + 0.5f).toInt()
            val height = (760 * density + 0.5f).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
    }
}
