package me.trinitrix.mirax.ui.standby.settings

import com.google.common.truth.Truth.assertThat
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM checks of confirmed language writes and submission callbacks. */
class SessionStandbyLanguageGatewayTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun unknownRawIsNotOverwrittenUntilExplicitChoice() = runBlocking {
        var raw: String? = "FUTURE_LANGUAGE"
        val writes = mutableListOf<String?>()
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { writes.add(it); raw = it; true },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertThat(gateway.currentChoice()).isNull()
        assertThat(gateway.choiceFlow.first()).isNull()
        assertThat(raw).isEqualTo("FUTURE_LANGUAGE")
        assertThat(writes).isEmpty()
        gateway.selectLanguage(StandbyLanguage.ENGLISH)
        assertThat(raw).isEqualTo("ENGLISH")
        assertThat(gateway.currentChoice()).isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(gateway.choiceFlow.first()).isEqualTo(StandbyLanguage.ENGLISH)
        gateway.selectLanguage(null)
        assertThat(raw).isEqualTo("FOLLOW_SYSTEM")
    }

    @Test
    fun failedCommitRestoresTheExactUnknownRaw() = runBlocking {
        var raw: String? = "FUTURE_LANGUAGE"
        val outcomes = ArrayDeque(listOf(false, true))
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; outcomes.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val failure = assertThrows(LanguagePersistenceException::class.java) {
            runBlocking { gateway.selectLanguage(StandbyLanguage.ENGLISH) }
        }
        assertThat(failure.uncertain).isFalse()
        assertThat(raw).isEqualTo("FUTURE_LANGUAGE")
        assertThat(gateway.currentChoice()).isNull()
    }

    @Test
    fun failedCommitAndRestoreRemainExplicitlyUncertain() = runBlocking {
        var raw: String? = "FUTURE_LANGUAGE"
        val outcomes = ArrayDeque(listOf(false, false, true))
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; outcomes.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val failure = assertThrows(LanguagePersistenceException::class.java) {
            runBlocking { gateway.selectLanguage(StandbyLanguage.ENGLISH) }
        }
        assertThat(failure.uncertain).isTrue()
        assertThat(failure.cause?.suppressed).isNotEmpty()
        val rebound = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; true },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertThrows(LanguagePersistenceException::class.java) { runBlocking { gateway.currentChoice() } }
        assertThrows(LanguagePersistenceException::class.java) { runBlocking { rebound.currentChoice() } }
        gateway.selectLanguage(StandbyLanguage.TRADITIONAL_CHINESE)
        assertThat(rebound.currentChoice()).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun restoreReadbackMismatchRemainsUncertain() = runBlocking {
        var raw: String? = "FUTURE_LANGUAGE"
        var reads = 0
        val outcomes = ArrayDeque(listOf(false, true, true))
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { reads++; if (reads == 3) "MISMATCH" else raw },
            writeRaw = { raw = it; outcomes.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val failure = assertThrows(LanguagePersistenceException::class.java) {
            runBlocking { gateway.selectLanguage(StandbyLanguage.ENGLISH) }
        }
        assertThat(failure.uncertain).isTrue()
        assertThrows(LanguagePersistenceException::class.java) { runBlocking { gateway.currentChoice() } }
        gateway.selectLanguage(null)
        assertThat(gateway.currentChoice()).isNull()
    }

    @Test
    fun submissionDispatchesAndChangesLocaleOnlyAfterConfirmedCommit() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val events = mutableListOf<String>()
        var raw: String? = null
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { events.add("commit"); raw = it; true },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val submission = StandbySettingsSubmission(dispatcher, {}, {}, { events.add("error") })
        submission.bind(scope, { events.add("dispatch") }, { events.add("locale") }, { events.add("failure") })
        submission.submit(StandbySettingsPanelModel().withLanguage(StandbyLanguage.ENGLISH), true) {
            gateway.selectLanguage(StandbyLanguage.ENGLISH)
        }
        assertThat(events).isEmpty()
        dispatcher.drain()
        assertThat(events).containsExactly("commit", "dispatch", "locale").inOrder()
        scope.cancel()
    }

    @Test
    fun failedSubmissionDoesNotDispatchOrChangeLocale() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val events = mutableListOf<String>()
        var raw: String? = null
        val outcomes = ArrayDeque(listOf(false, true))
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; outcomes.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val submission = StandbySettingsSubmission(dispatcher, {}, {}, { events.add("error") })
        submission.bind(scope, { events.add("dispatch") }, { events.add("locale") }, { events.add("failure") })
        submission.submit(StandbySettingsPanelModel().withLanguage(StandbyLanguage.ENGLISH), true) {
            gateway.selectLanguage(StandbyLanguage.ENGLISH)
        }
        dispatcher.drain()
        assertThat(events).containsExactly("error", "failure").inOrder()
        assertThat(raw).isNull()
        scope.cancel()
    }

    @Test
    fun rebindSuppressesOldCallbacksAndReloadsACommittedCancelledChoice() = runBlocking {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var raw: String? = null
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; true },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val submission = StandbySettingsSubmission(dispatcher, {}, {}, { events.add("error") })
        submission.bind(scope, { events.add("old dispatch") }, { events.add("old locale") }, {})
        submission.submit(StandbySettingsPanelModel().withLanguage(StandbyLanguage.ENGLISH), true) {
            withContext(NonCancellable) {
                gate.await()
                gateway.selectLanguage(StandbyLanguage.ENGLISH)
            }
        }
        dispatcher.drain()
        submission.bind(scope, { events.add("new dispatch") }, { events.add("new locale") }, {})
        assertThat(submission.submit(StandbySettingsPanelModel().withLanguage(null), true) {}).isNull()
        gate.complete(Unit)
        dispatcher.drain()
        assertThat(events).isEmpty()
        val reloaded = gateway.currentChoice()
        assertThat(reloaded).isEqualTo(StandbyLanguage.ENGLISH)
        submission.updateConfirmed(StandbySettingsPanelModel().withLanguage(reloaded))
        submission.submit(submission.confirmed.withLanguage(null), true) { gateway.selectLanguage(null) }
        dispatcher.drain()
        assertThat(events).containsExactly("new dispatch", "new locale").inOrder()
        assertThat(raw).isEqualTo("FOLLOW_SYSTEM")
        scope.cancel()
    }

    @Test
    fun cancellationAfterIoStartedDoesNotDeliverSuccess() = runBlocking {
        val dispatcher = ManualDispatcher()
        var raw: String? = null
        var delivered = false
        val gateway = SessionStandbyLanguageGateway(
            readRaw = { raw },
            writeRaw = { raw = it; true },
            ioDispatcher = dispatcher,
        )
        val job = launch {
            gateway.selectLanguage(StandbyLanguage.ENGLISH)
            delivered = true
        }
        yield()
        dispatcher.drain()
        job.cancel()
        yield()
        assertThat(raw).isEqualTo("ENGLISH")
        assertThat(delivered).isFalse()
    }
}
