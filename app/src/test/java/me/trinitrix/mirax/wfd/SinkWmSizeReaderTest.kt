package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WmSizeReading
import org.junit.Test

/** Bounded WM worker and source-registration integration coverage. */
class SinkWmSizeReaderTest {
    private val scope = SinkWmReadScope(WfdOwner.SHIZUKU, SinkProvisioningFence(1, null, null, null), 1L)

    @Test
    fun provisioningResultRequiresOriginalIdentityBeforeDeadline() {
        var now = 1_000L
        val deadline = now + 2_000L
        assertThat(isCurrentSinkWmReading(now, deadline, eligible = true, sameIdentity = true)).isTrue()
        assertThat(isCurrentSinkWmReading(now, deadline, eligible = true, sameIdentity = false)).isFalse()
        assertThat(isCurrentSinkWmReading(now, deadline, eligible = false, sameIdentity = true)).isFalse()
        now = deadline
        assertThat(isCurrentSinkWmReading(now, deadline, eligible = true, sameIdentity = true)).isFalse()
        now += 1_000L
        assertThat(isCurrentSinkWmReading(now, deadline, eligible = true, sameIdentity = true)).isFalse()
    }

    @Test
    fun provisioningReadRejectsRebindWithoutGroupAndAcceptsFreshRead() {
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { null }
        route.switchOwner(WfdOwner.SHIZUKU)
        val fence = SinkProvisioningFence(1, null, null, null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        val freshDone = CountDownLatch(1)
        val calls = AtomicInteger()
        val applied = AtomicReference<WmSizeReading?>()
        val executor = Executor { task -> Thread(task).apply { isDaemon = true }.start() }
        val oldReading = WmSizeReading(1920, 1080)
        val freshReading = WmSizeReading(1280, 720)
        val coordinator = SinkWmReadCoordinator(executor) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                oldReading
            } else {
                freshReading
            }
        }
        fun applyIfCurrent(request: SinkWmReadCoordinator.Request, done: CountDownLatch) {
            request.onComplete {
                val current = isCurrentSinkWmScope(
                    request.scope, WfdOwner.SHIZUKU, fence, route.revisionFor(WfdOwner.SHIZUKU),
                )
                if (isCurrentSinkWmReading(10L, request.deadlineMs, eligible = true, sameIdentity = current)) {
                    applied.set(request.result())
                }
                done.countDown()
            }
        }
        try {
            val original = scope.copy(fence = fence, ownerRevision = checkNotNull(route.revisionFor(scope.owner)))
            val old = checkNotNull(coordinator.request(original, 10_000L))
            applyIfCurrent(old, oldDone)
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            route.updateEndpoint(WfdOwner.SHIZUKU) {}
            release.countDown()
            assertThat(oldDone.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(old.result()).isEqualTo(oldReading)
            assertThat(applied.get()).isNull()
            val freshScope = original.copy(ownerRevision = checkNotNull(route.revisionFor(scope.owner)))
            assertThat(isCurrentSinkWmScope(freshScope, scope.owner, fence, null)).isFalse()
            val fresh = checkNotNull(coordinator.request(freshScope, 10_000L))
            applyIfCurrent(fresh, freshDone)
            assertThat(freshDone.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(applied.get()).isEqualTo(freshReading)
            assertThat(calls.get()).isEqualTo(2)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun completedBusyReadStillHandsOffToTheNewScopeWithinOriginalDeadline() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val reading = WmSizeReading(1920, 1080, 1280, 720)
        val coordinator = SinkWmReadCoordinator(Executor { tasks.addLast(it) }) { reading }
        val old = checkNotNull(coordinator.request(scope, 100L))
        val replacement = scope.copy(owner = WfdOwner.HELPER)
        var clockReads = 0
        val next = checkNotNull(coordinator.requestBeforeDeadline(replacement, 100L, {
            clockReads++
            if (clockReads == 2) {
                tasks.removeFirst().run()
                assertThat(coordinator.activeRequest()).isNull()
                assertThat(old.result()).isEqualTo(reading)
            }
            10L
        }) { true })
        assertThat(next.scope).isEqualTo(replacement)
        assertThat(next.deadlineMs).isEqualTo(100L)
        assertThat(tasks).hasSize(1)
        tasks.removeFirst().run()
        assertThat(next.result()).isEqualTo(reading)
    }

    @Test
    fun completedBusyReadDoesNotExtendTheDeadline() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val coordinator = SinkWmReadCoordinator(Executor { tasks.addLast(it) }) { null }
        checkNotNull(coordinator.request(scope, 100L))
        var clockReads = 0
        val replacement = scope.copy(owner = WfdOwner.HELPER)
        val next = coordinator.requestBeforeDeadline(replacement, 100L, {
            clockReads++
            if (clockReads == 2) tasks.removeFirst().run()
            if (clockReads >= 2) 100L else 10L
        }) { true }
        assertThat(next).isNull()
        assertThat(tasks).isEmpty()
    }

    @Test
    fun blockedReadIsSingleFlightAndDifferentDisplaysAreNotConfused() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val notified = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val executor = Executor { task ->
            Thread(task).also { thread ->
                thread.isDaemon = true
                worker.set(thread)
                thread.start()
            }
        }
        val reading = WmSizeReading(1920, 1080, 1280, 720)
        val coordinator = SinkWmReadCoordinator(executor) { displayId ->
            calls.incrementAndGet()
            check(displayId == null)
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            reading
        }
        try {
            val first = checkNotNull(coordinator.request(scope, 10_000L))
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val joined = checkNotNull(coordinator.request(scope, 12_000L))
            assertThat(joined).isSameInstanceAs(first)
            assertThat(joined.deadlineMs).isEqualTo(10_000L)
            assertThat(isCurrentSinkWmReading(10_001L, joined.deadlineMs, true, true)).isFalse()
            assertThat(coordinator.request(scope, 10_000L, 7)).isNull()
            assertThat(coordinator.request(scope.copy(owner = WfdOwner.HELPER), 10_000L)).isNull()
            val replacement = SinkAttemptSource(1, 2L)
            val replacementScope = scope.copy(fence = scope.fence.copy(observedSource = replacement))
            assertThat(coordinator.request(replacementScope, 10_000L)).isNull()
            val observation = OwnerGroupSnapshot(
                WfdOwner.SHIZUKU,
                P2pGroupState.Pending,
                "01234567-89ab-cdef-0123-456789abcdef-1",
                2L,
            )
            assertThat(
                coordinator.request(scope.copy(fence = scope.fence.copy(observation = observation)), 10_000L),
            ).isNull()
            assertThat(first.await(0)).isFalse()
            first.onComplete { notified.countDown() }
            assertThat(calls.get()).isEqualTo(1)
            release.countDown()
            assertThat(first.await(5_000)).isTrue()
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(first.result()).isEqualTo(reading)
            assertThat(coordinator.activeRequest()).isNull()
        } finally {
            release.countDown()
            worker.get()?.join(5_000L)
        }
    }

    @Test
    fun registrationDoesNotBlockOnWmAndLateReadCannotMutateDroppedSource() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posted = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val executor = Executor { task ->
            Thread(task).also { thread ->
                thread.isDaemon = true
                worker.set(thread)
                thread.start()
            }
        }
        val coordinator = SinkWmReadCoordinator(executor) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            WmSizeReading(1920, 1080)
        }
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val source = SinkAttemptSource(1, 1L)
        var attempts = 0
        var applied = 0
        lateinit var arbiter: SinkEventArbiter
        arbiter = SinkEventArbiter(
            onAction = { action ->
                if (action == SessionAction.PrePlayProgress) {
                    val request = checkNotNull(coordinator.request(scope, 10_000L))
                    request.onComplete {
                        queue.add { if (arbiter.isSource(source)) applied++ }
                        posted.countDown()
                    }
                }
            },
            onAttempt = { attempts++ },
            onDrop = {},
            onTerminal = { _, _ -> },
            isMainThread = { true },
        )
        try {
            arbiter.start(1)
            val registration = SinkSourceRegistration()
            arbiter.registerSource(source, null, null, registration, allowed = true)
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(registration.result()).isTrue()
            assertThat(attempts).isEqualTo(1)
            assertThat(coordinator.request(scope, 10_000L)).isSameInstanceAs(coordinator.activeRequest())
            assertThat(arbiter.drop(source)).isTrue()
            release.countDown()
            assertThat(posted.await(5, TimeUnit.SECONDS)).isTrue()
            queue.poll()?.invoke()
            assertThat(applied).isEqualTo(0)
            assertThat(attempts).isEqualTo(1)
        } finally {
            release.countDown()
            worker.get()?.join(5_000L)
        }
    }
}
