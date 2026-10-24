package me.trinitrix.mirax.wfd

import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi

/** Runs the player's dispatcher and delay deadlines on one explicitly advanced clock. */
@OptIn(InternalCoroutinesApi::class)
internal class AudioTestScheduler : CoroutineDispatcher(), Delay {
    private val pending = PriorityQueue<ScheduledTask>(compareBy({ it.timeMillis }, { it.order }))
    private var nextOrder = 0L
    var nowMillis = 0L
        private set

    /** @param block continuation to run at the current virtual instant. */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        schedule(0, block)
    }

    /** Schedules a cancellable coroutine delay without using a wall-clock timer. */
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = schedule(timeMillis, Runnable { if (continuation.isActive) continuation.resume(Unit) })
        continuation.invokeOnCancellation { task.cancelled = true }
    }

    /** @return a handle that cancels the virtual timeout. */
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = schedule(timeMillis, block)
        return object : DisposableHandle {
            override fun dispose() {
                task.cancelled = true
            }
        }
    }

    /** Runs ready continuations; a bounded step count exposes accidental player busy loops. */
    fun runCurrent() {
        var steps = 0
        while (pending.peek()?.timeMillis?.let { it <= nowMillis } == true) {
            check(++steps <= 10_000) { "Audio worker did not suspend" }
            val task = pending.remove()
            if (!task.cancelled) task.block.run()
        }
    }

    /** @param millis virtual time to advance, including intervening deadlines. */
    fun advanceBy(millis: Long) {
        require(millis >= 0)
        val target = nowMillis + millis
        while (true) {
            val next = pending.peek() ?: break
            if (next.timeMillis > target) break
            nowMillis = next.timeMillis
            runCurrent()
        }
        nowMillis = target
        runCurrent()
    }

    /** @return a scheduled continuation in deterministic insertion order. */
    private fun schedule(delayMillis: Long, block: Runnable): ScheduledTask {
        val task = ScheduledTask(nowMillis + delayMillis.coerceAtLeast(0), nextOrder++, block)
        pending.add(task)
        return task
    }

    /** A cancellable test-clock deadline. */
    private class ScheduledTask(val timeMillis: Long, val order: Long, val block: Runnable) {
        var cancelled = false
    }
}
