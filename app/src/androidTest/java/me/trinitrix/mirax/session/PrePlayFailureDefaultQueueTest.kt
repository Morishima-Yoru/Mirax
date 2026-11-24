package me.trinitrix.mirax.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.wfd.rtsp.RtspFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/** Observes an isolated owner's production clock and queue without an Activity or singleton. */
@RunWith(AndroidJUnit4::class)
class PrePlayFailureDefaultQueueTest {
    /** Require a natural listener null, never a read-induced expiry or cleanup notification. */
    @Test
    fun defaultMainQueueNaturallyExpiresVisibleEpisode() {
        val mainLooper = Looper.getMainLooper()
            ?: throw AssumptionViolatedException("INCOMPLETE: main Looper unavailable")
        val testHandler = Handler(mainLooper)
        val owner = PrePlayFailureEpisodeOwner()
        val budgetMs = PrePlayFailureEpisode.VISIBLE_BUDGET_MS
        val windowMs = 4L * budgetMs
        val notifications = ConcurrentLinkedQueue<EpisodeObservation>()
        val naturalNull = CountDownLatch(1)
        val controlClock = AtomicReference<ClockObservation?>(null)
        val control = Runnable {
            val clock = ClockObservation(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                Looper.myLooper() == mainLooper)
            controlClock.set(clock)
            Log.i(TAG, "control elapsedMs=${clock.elapsedMs} uptimeMs=${clock.uptimeMs} main=${clock.onMain}")
        }
        var initial: List<EpisodeObservation> = emptyList()
        var frozen: List<EpisodeObservation>? = null
        var visibleBefore: ClockObservation? = null
        var visibleAfter: ClockObservation? = null
        var attemptAccepted = false
        var visibilityAccepted = false
        var controlAccepted = false
        var controlDueUptime = 0L
        var primaryFailure: Throwable? = null
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        Log.i(TAG, "fixture appClass=${app.javaClass.name} package=${app.packageName} " +
            "owner=local-default helperBudgetMs=5000 visibleBudgetMs=$budgetMs windowMs=$windowMs")
        try {
            assertEquals("The production visible budget must remain 7500ms", 7500L, budgetMs)
            onMain(testHandler, "setup") {
                owner.setEpisodeListener { snapshot ->
                    notifications.add(EpisodeObservation(snapshot,
                        ClockObservation(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                            Looper.myLooper() == mainLooper)))
                    if (snapshot == null) naturalNull.countDown()
                }
                val source = SinkAttemptSource(1, 1L)
                attemptAccepted = owner.onPrePlayAttemptStarted(PrePlayAttemptId(1L), source)
                owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
                initial = notifications.toList()
                visibleBefore = ClockObservation(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                    Looper.myLooper() == mainLooper)
                visibilityAccepted = owner.onForegroundVisibilityChanged(true)
                visibleAfter = ClockObservation(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                    Looper.myLooper() == mainLooper)
                controlDueUptime = SystemClock.uptimeMillis() + 2L * budgetMs
                controlAccepted = testHandler.postAtTime(control, controlDueUptime)
            }
            val before = visibleBefore ?: throw AssumptionViolatedException("INCOMPLETE: visibility start unobserved")
            val after = visibleAfter ?: throw AssumptionViolatedException("INCOMPLETE: visibility end unobserved")
            val first = initial.singleOrNull()
            Log.i(TAG, "armed initialCount=${initial.size} attempt=${first?.snapshot?.attemptId?.value} " +
                "cause=${first?.snapshot?.cause} remainingMs=${first?.snapshot?.remainingMs} " +
                "initialElapsedMs=${first?.clock?.elapsedMs} initialUptimeMs=${first?.clock?.uptimeMs} " +
                "initialMain=${first?.clock?.onMain} beforeElapsedMs=${before.elapsedMs} " +
                "beforeUptimeMs=${before.uptimeMs} afterElapsedMs=${after.elapsedMs} " +
                "afterUptimeMs=${after.uptimeMs} controlDueUptimeMs=$controlDueUptime")
            assertTrue("Default owner rejected the local attempt", attemptAccepted)
            assertTrue("Default owner rejected foreground visibility", visibilityAccepted)
            assertEquals("Exactly one hidden initial notification is required", 1, initial.size)
            val initialEvent = first ?: throw AssertionError("Initial notification missing")
            val snapshot = initialEvent.snapshot ?: throw AssertionError("Initial notification was null")
            assertTrue("Initial notification did not run on main", initialEvent.clock.onMain)
            assertEquals(PrePlayAttemptId(1L), snapshot.attemptId)
            assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, snapshot.cause)
            assertEquals(budgetMs, snapshot.remainingMs)
            if (!controlAccepted || !before.onMain || !after.onMain ||
                after.elapsedMs < before.elapsedMs || after.uptimeMs < before.uptimeMs
            ) throw AssumptionViolatedException("INCOMPLETE: control post or visibility clock fixture failed")

            val deadlineElapsed = after.elapsedMs + windowMs
            // Recheck elapsed time across bounded latch waits; never poll the owner.
            while (naturalNull.count > 0L) {
                val remaining = deadlineElapsed - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                naturalNull.await(minOf(250L, remaining), TimeUnit.MILLISECONDS)
            }
            val captured = onMain(testHandler, "seal") { notifications.toList() to controlClock.get() }
            frozen = captured.first
            val observed = captured.first
            observed.forEach { event ->
                Log.i(TAG, "natural snapshot=${if (event.snapshot == null) "null" else "present"} " +
                    "attempt=${event.snapshot?.attemptId?.value} cause=${event.snapshot?.cause} " +
                    "remainingMs=${event.snapshot?.remainingMs} elapsedMs=${event.clock.elapsedMs} " +
                    "uptimeMs=${event.clock.uptimeMs} main=${event.clock.onMain}")
            }
            assertTrue("An episode callback ran off main", observed.all { it.clock.onMain })
            assertEquals("The hidden initial snapshot must be the only non-null notification",
                initial, observed.filter { it.snapshot != null })
            val nulls = observed.filter { it.snapshot == null }
            if (nulls.isNotEmpty()) assertEquals("Exactly one natural null is required", 1, nulls.size)
            val expiry = nulls.singleOrNull()
            if (expiry == null || expiry.clock.elapsedMs > deadlineElapsed) {
                val controlObserved = captured.second
                val controlWithinWindow = controlObserved != null && controlObserved.onMain &&
                    controlObserved.uptimeMs >= controlDueUptime && controlObserved.elapsedMs <= deadlineElapsed
                Log.i(TAG, "result=finiteWindow naturalNullWithinWindow=false controlWithinWindow=$controlWithinWindow " +
                    "deadlineElapsedMs=$deadlineElapsed endElapsedMs=${SystemClock.elapsedRealtime()} " +
                    "controlElapsedMs=${controlObserved?.elapsedMs} controlUptimeMs=${controlObserved?.uptimeMs}")
                if (controlWithinWindow) {
                    throw AssertionError("No natural null within the 30000ms observation window despite later main control")
                }
                throw AssumptionViolatedException("INCOMPLETE: no timely null and no control within the finite window")
            }
            val earliestElapsed = before.elapsedMs + budgetMs
            val latestElapsed = after.elapsedMs + budgetMs
            if (expiry.clock.elapsedMs < earliestElapsed) {
                Log.i(TAG, "result=early nullElapsedMs=${expiry.clock.elapsedMs} earliestElapsedMs=$earliestElapsed")
                throw AssertionError("Natural expiry was provably earlier than the visible budget")
            }
            if (expiry.clock.elapsedMs < latestElapsed) {
                Log.i(TAG, "result=ambiguous nullElapsedMs=${expiry.clock.elapsedMs} " +
                    "earliestElapsedMs=$earliestElapsed latestElapsedMs=$latestElapsed")
                throw AssumptionViolatedException("INCOMPLETE: natural null lies inside the visibility clock bounds")
            }
            assertTrue("Natural null must not precede the upper visibility bound plus budget",
                expiry.clock.elapsedMs >= latestElapsed)
        } catch (error: Throwable) {
            primaryFailure = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "result=exception observationsFrozen=${frozen != null}", error)
            throw error
        } finally {
            // Permit bounded cleanup even when the observation wait was interrupted.
            val interrupted = Thread.interrupted()
            try {
                var stopAccepted = false
                onMain(testHandler, "cleanup") {
                    if (frozen == null) frozen = notifications.toList()
                    try {
                        owner.setEpisodeListener(null)
                        stopAccepted = owner.onExplicitStop()
                    } finally {
                        testHandler.removeCallbacks(control)
                    }
                }
                if (!stopAccepted) throw AssumptionViolatedException("INCOMPLETE: default owner rejected cleanup stop")
                Log.i(TAG, "cleanupComplete=true stopAccepted=true frozenCount=${frozen?.size}")
            } catch (cleanupError: Throwable) {
                Log.e(TAG, "cleanupComplete=false result=INCOMPLETE", cleanupError)
                val primary = primaryFailure
                if (primary == null) throw cleanupError
                if (primary !== cleanupError) primary.addSuppressed(cleanupError)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        Log.i(TAG, "result=PASS contract=default-main-queue-natural-expiry")
    }

    /** A real clock pair, including callback thread identity; never an injected clock. */
    private data class ClockObservation(val elapsedMs: Long, val uptimeMs: Long, val onMain: Boolean)

    /** Only the listener's argument is retained; no owner queries or mutations occur in the listener. */
    private data class EpisodeObservation(val snapshot: PrePlayFailureSnapshot?, val clock: ClockObservation)

    /**
     * Posts without global-idle waiting and hands the original failure back to the test thread.
     * A timeout removes/cancels only unstarted work; an already running action cannot be cancelled.
     * @param handler Test-owned handler bound to the real main Looper.
     * @param phase Static fixture phase for error evidence.
     * @param block Short main-thread action, never a blocking wait.
     * @return The action's result within the 5000ms fixture budget, not a product deadline.
     */
    private fun <T> onMain(handler: Handler, phase: String, block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Bounded main work must be awaited off main" }
        val pending = AtomicBoolean(true)
        val future = FutureTask<T> { block() }
        val task = Runnable { if (pending.compareAndSet(true, false)) future.run() }
        try {
            if (!handler.post(task)) throw AssumptionViolatedException("INCOMPLETE: main rejected $phase")
            return future.get(5L, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            Log.e(TAG, "main phase=$phase actionFailed=true", cause)
            throw cause
        } catch (error: TimeoutException) {
            Log.e(TAG, "main phase=$phase fixtureTimeout=true running=${!pending.get()}", error)
            throw AssumptionViolatedException("INCOMPLETE: $phase exceeded the 5000ms main fixture budget", error)
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "main phase=$phase handoffFailed=true", error)
            throw error
        } finally {
            if (pending.compareAndSet(true, false)) {
                handler.removeCallbacks(task)
                future.cancel(false)
            }
        }
    }

    private companion object {
        const val TAG = "STANDBY_EXPIRY"
    }
}
