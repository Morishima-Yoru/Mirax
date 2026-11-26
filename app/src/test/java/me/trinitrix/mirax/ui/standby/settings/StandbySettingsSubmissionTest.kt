package me.trinitrix.mirax.ui.standby.settings

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import org.junit.Test

/** JVM checks for the submission path used by the settings panel. */
class StandbySettingsSubmissionTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    private class FakeControl(override val viewId: Int, override var label: String) :
        StandbySettingsPanelA11y.OptionControl {
        override var checked = false
        override var description: String? = null
        override var minWidthPx = 0
        override var minimumWidthPx = 0
        override var minHeightPx = 0
        override var minimumHeightPx = 0
        override var nextUp = viewId
        override var nextDown = viewId
        override var nextLeft = viewId
        override var nextRight = viewId
        override var nextForward = viewId
        override var focusableInTouchMode = false
    }

    private class Fixture {
        val dispatcher = ManualDispatcher()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + dispatcher)
        val follow = FakeControl(1, "Follow")
        val english = FakeControl(2, "English")
        val events = mutableListOf<String>()
        val states = mutableListOf<StandbySettingsPanelModel>()
        val errors = mutableListOf<Exception>()
        val logged = mutableListOf<Exception>()
        val renders = mutableListOf<StandbySettingsPanelModel>()
        val enabledChanges = mutableListOf<Boolean>()
        var enabled = true
        val submission = StandbySettingsSubmission(
            dispatcher = dispatcher,
            render = { model ->
                renders.add(model)
                StandbySettingsPanelA11y.applySelection(follow, "Follow", " selected", model.language == null)
                StandbySettingsPanelA11y.applySelection(
                    english,
                    "English",
                    " selected",
                    model.language == StandbyLanguage.ENGLISH,
                )
            },
            setInputEnabled = {
                enabled = it
                enabledChanges.add(it)
            },
            logFailure = { logged.add(it) },
        )

        fun bind(label: String = "first", afterState: (() -> Unit)? = null) {
            submission.bind(
                scope = scope,
                onStateChanged = {
                    states.add(it)
                    events.add("$label:state")
                    afterState?.invoke()
                },
                onLocaleChange = { events.add("$label:locale") },
                onError = {
                    errors.add(it)
                    events.add("$label:error")
                },
            )
        }
    }

    @Test
    fun languageWaitsForPersistenceThenReportsStateBeforeLocale() {
        val fixture = Fixture()
        fixture.bind(afterState = { fixture.events.add("first:state-end") })
        val gate = CompletableDeferred<Unit>()
        val selected = fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH)

        fixture.follow.checked = false
        fixture.english.checked = true
        fixture.submission.submit(selected, changesLocale = true) { gate.await() }
        fixture.dispatcher.drain()

        assertThat(fixture.events).isEmpty()
        assertThat(fixture.enabled).isFalse()
        assertThat(fixture.submission.confirmed.language).isNull()
        assertThat(fixture.follow.checked).isTrue()
        assertThat(fixture.english.checked).isFalse()
        assertThat(fixture.follow.description).isEqualTo("Follow selected")
        assertThat(fixture.english.description).isEqualTo("English")

        gate.complete(Unit)
        fixture.dispatcher.drain()

        assertThat(fixture.states).containsExactly(selected)
        assertThat(fixture.events).containsExactly("first:state", "first:state-end", "first:locale").inOrder()
        assertThat(fixture.enabled).isTrue()
        assertThat(fixture.english.checked).isTrue()
        assertThat(fixture.english.label).isEqualTo("English selected")
        assertThat(fixture.english.description).isEqualTo("English selected")
        assertThat(fixture.follow.description).isEqualTo("Follow")
    }

    @Test
    fun themeSuccessDoesNotRequestLocale() {
        val fixture = Fixture()
        fixture.bind()
        val selected = fixture.submission.confirmed.withTheme(StandbyThemeId.HARDLINE)
        fixture.submission.submit(selected, changesLocale = false) { }
        fixture.dispatcher.drain()

        assertThat(fixture.states).containsExactly(selected)
        assertThat(fixture.events).containsExactly("first:state")
        assertThat(fixture.enabled).isTrue()
    }

    @Test
    fun failureRestoresConfirmedSelectionAndReportsOriginalCause() {
        val fixture = Fixture()
        val original = StandbySettingsPanelModel().withTheme(StandbyThemeId.LANDSCAPE)
        fixture.submission.updateConfirmed(original)
        fixture.bind()
        val gate = CompletableDeferred<Unit>()
        val failure = IOException("write failed")
        fixture.follow.checked = false
        fixture.english.checked = true
        val job = checkNotNull(
            fixture.submission.submit(original.withLanguage(StandbyLanguage.ENGLISH), true) { gate.await() },
        )
        fixture.dispatcher.drain()

        gate.completeExceptionally(failure)
        fixture.dispatcher.drain()

        assertThat(job.isCompleted).isTrue()
        assertThat(job.isCancelled).isFalse()
        assertThat(fixture.submission.confirmed).isEqualTo(original)
        assertThat(fixture.renders.last()).isEqualTo(original)
        assertThat(fixture.follow.checked).isTrue()
        assertThat(fixture.follow.label).isEqualTo("Follow selected")
        assertThat(fixture.follow.description).isEqualTo("Follow selected")
        assertThat(fixture.english.checked).isFalse()
        assertThat(fixture.english.label).isEqualTo("English")
        assertThat(fixture.english.description).isEqualTo("English")
        assertThat(fixture.enabled).isTrue()
        assertThat(fixture.events).containsExactly("first:error")
        assertThat(fixture.states).isEmpty()
        val reported = fixture.errors.single()
        assertThat(reported).isInstanceOf(IOException::class.java)
        assertThat(reported.message).isEqualTo("write failed")
        assertThat(reported === failure || reported.cause === failure).isTrue()
        assertThat(fixture.logged.single()).isSameInstanceAs(reported)
    }

    @Test
    fun themeFailureDoesNotReportSuccess() {
        val fixture = Fixture()
        fixture.bind()
        val original = fixture.submission.confirmed
        val failure = IOException("theme write failed")
        fixture.submission.submit(original.withTheme(StandbyThemeId.HARDLINE), false) { throw failure }
        fixture.dispatcher.drain()

        assertThat(fixture.submission.confirmed).isEqualTo(original)
        assertThat(fixture.states).isEmpty()
        assertThat(fixture.events).containsExactly("first:error")
        assertThat(fixture.errors.single()).isSameInstanceAs(failure)
        assertThat(fixture.logged.single()).isSameInstanceAs(failure)
        assertThat(fixture.enabled).isTrue()
    }

    @Test
    fun cancellationPropagatesWithoutNotificationsAndRestoresInput() {
        val fixture = Fixture()
        fixture.bind()
        val gate = CompletableDeferred<Unit>()
        val original = fixture.submission.confirmed
        val job = checkNotNull(
            fixture.submission.submit(original.withTheme(StandbyThemeId.HARDLINE), false) { gate.await() },
        )
        fixture.dispatcher.drain()

        gate.cancel()
        fixture.dispatcher.drain()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.owner.isActive).isTrue()
        assertThat(fixture.submission.confirmed).isEqualTo(original)
        assertThat(fixture.enabled).isTrue()
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.errors).isEmpty()
        assertThat(fixture.logged).isEmpty()
    }

    @Test
    fun alreadyCancelledScopeDoesNotLeaveInputDisabled() {
        val fixture = Fixture()
        fixture.bind()
        fixture.owner.cancel()
        var wrote = false
        val job = checkNotNull(
            fixture.submission.submit(fixture.submission.confirmed.withTheme(StandbyThemeId.HARDLINE), false) {
                wrote = true
            },
        )
        fixture.dispatcher.drain()

        assertThat(job.isCancelled).isTrue()
        assertThat(wrote).isFalse()
        assertThat(fixture.enabled).isTrue()
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.logged).isEmpty()
    }

    @Test
    fun rebindAndRapidSelectionCannotPublishOrOvertakeOldWrite() {
        val fixture = Fixture()
        fixture.bind()
        val oldGate = CompletableDeferred<Unit>()
        val oldJob = checkNotNull(
            fixture.submission.submit(fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH), true) {
                withContext(NonCancellable) { oldGate.await() }
            },
        )
        fixture.dispatcher.drain()

        var rapidWriteRan = false
        val rapid = fixture.submission.submit(
            fixture.submission.confirmed.withTheme(StandbyThemeId.LANDSCAPE),
            false,
        ) { rapidWriteRan = true }
        assertThat(rapid).isNull()
        assertThat(rapidWriteRan).isFalse()
        assertThat(fixture.events).isEmpty()

        fixture.bind("second")
        val newer = StandbySettingsPanelModel().withTheme(StandbyThemeId.HARDLINE)
        fixture.submission.updateConfirmed(newer)
        var rejectedWriteRan = false
        val rejected = fixture.submission.submit(newer.withTheme(StandbyThemeId.LANDSCAPE), false) {
            rejectedWriteRan = true
        }
        assertThat(rejected).isNull()
        assertThat(rejectedWriteRan).isFalse()
        assertThat(fixture.enabled).isFalse()
        assertThat(fixture.owner.isActive).isTrue()
        assertThat(oldJob.isCancelled).isTrue()

        oldGate.complete(Unit)
        fixture.dispatcher.drain()
        assertThat(fixture.submission.confirmed).isEqualTo(newer)
        assertThat(fixture.renders.last()).isEqualTo(newer)
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.enabled).isTrue()

        val finalSelection = newer.withLanguage(StandbyLanguage.ENGLISH)
        fixture.submission.submit(finalSelection, true) { }
        fixture.dispatcher.drain()
        assertThat(fixture.states).containsExactly(finalSelection)
        assertThat(fixture.events).containsExactly("second:state", "second:locale").inOrder()
    }

    @Test
    fun detachCancelsOnlyChildAndSuppressesLateViewAndHostCalls() {
        val fixture = Fixture()
        fixture.bind()
        val gate = CompletableDeferred<Unit>()
        val job = checkNotNull(
            fixture.submission.submit(fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH), true) {
                withContext(NonCancellable) { gate.await() }
            },
        )
        fixture.dispatcher.drain()
        fixture.submission.unbind()
        val renderCount = fixture.renders.size
        val enabledCount = fixture.enabledChanges.size

        gate.complete(Unit)
        fixture.dispatcher.drain()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.owner.isActive).isTrue()
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.renders).hasSize(renderCount)
        assertThat(fixture.enabledChanges).hasSize(enabledCount)
        fixture.bind("reattached")
        assertThat(fixture.enabled).isTrue()
    }

    @Test
    fun rebindFromStateCallbackSuppressesOldLocaleCallback() {
        val fixture = Fixture()
        fixture.bind(afterState = { fixture.bind("second") })
        fixture.submission.submit(fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH), true) { }
        fixture.dispatcher.drain()

        assertThat(fixture.events).containsExactly("first:state")
        assertThat(fixture.owner.isActive).isTrue()
        assertThat(fixture.enabled).isTrue()
    }

    @Test
    fun hostRenderingStoredChoiceBeforeReturnStillGetsSuccessAndLocale() {
        val fixture = Fixture()
        fixture.bind()
        val gate = CompletableDeferred<Unit>()
        val selected = fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH)
        fixture.submission.submit(selected, true) { gate.await() }
        fixture.dispatcher.drain()
        val observed = StandbySettingsPanelModel(language = StandbyLanguage.ENGLISH)
        fixture.submission.updateConfirmed(observed)

        gate.complete(Unit)
        fixture.dispatcher.drain()

        assertThat(fixture.states).containsExactly(observed)
        assertThat(fixture.events).containsExactly("first:state", "first:locale").inOrder()
        assertThat(fixture.submission.confirmed).isEqualTo(observed)
    }

    @Test
    fun newerHostRenderCannotBeOverwrittenByPendingCompletion() {
        val fixture = Fixture()
        fixture.bind()
        val gate = CompletableDeferred<Unit>()
        fixture.submission.submit(fixture.submission.confirmed.withLanguage(StandbyLanguage.ENGLISH), true) {
            gate.await()
        }
        fixture.dispatcher.drain()
        val newer = StandbySettingsPanelModel().withTheme(StandbyThemeId.HARDLINE)
        fixture.submission.updateConfirmed(newer)

        gate.complete(Unit)
        fixture.dispatcher.drain()

        assertThat(fixture.submission.confirmed).isEqualTo(newer)
        assertThat(fixture.renders.last()).isEqualTo(newer)
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.enabled).isTrue()
    }
}
