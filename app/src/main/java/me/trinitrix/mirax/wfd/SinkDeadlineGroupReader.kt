package me.trinitrix.mirax.wfd

import android.util.Log
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A nonblocking, monotonic, single-flight group observation for an RTSP run. */
internal class SinkLiveGroupWatch(
    private val executor: Executor,
    private val read: () -> OwnerGroupSnapshot?,
    private val intervalMs: Long,
    private val logFailure: (String, Exception) -> Unit,
) {
    data class Sample(val snapshot: OwnerGroupSnapshot?)

    private var nextDueMs = 0L
    private var busy = false
    private var sample: Sample? = null

    /** Returns a completed observation and starts at most one due read. */
    @Synchronized
    fun poll(nowMs: Long): Sample? {
        val completed = sample
        sample = null
        if (completed == null && !busy && nowMs >= nextDueMs) {
            busy = true
            nextDueMs = nowMs + intervalMs
            try {
                executor.execute {
                    val observed = try {
                        read()
                    } catch (err: Exception) {
                        logFailure("Live group observation failed", err)
                        null
                    }
                    synchronized(this) {
                        sample = Sample(observed)
                        busy = false
                    }
                }
            } catch (err: RejectedExecutionException) {
                busy = false
                logFailure("Live group observation worker busy", err)
            }
        }
        return completed
    }
}

/** Shares one bounded observation worker across RTSP runs. */
internal object SinkLiveGroupReader {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "mirax-sink-live-group").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun watch(intervalMs: Long): SinkLiveGroupWatch = SinkLiveGroupWatch(
        worker, WfdOwnerBridge::groupSnapshotV2, intervalMs,
    ) { message, err -> Log.w("SinkLiveGroupReader", message, err) }
}

/** Bounded single-flight observation for the remaining offer deadline. */
internal class SinkDeadlineGroupReader(
    private val executor: Executor,
    private val read: () -> OwnerGroupSnapshot?,
    private val logFailure: (String, Exception) -> Unit,
    private val awaitResult: (FutureTask<OwnerGroupSnapshot?>, Long) -> OwnerGroupSnapshot? = { task, timeout ->
        task.get(timeout, TimeUnit.MILLISECONDS)
    },
) {
    private val busy = AtomicBoolean(false)

    /** Returns null for unavailable, busy, late, or interrupted observations. */
    fun snapshot(timeoutMs: Long): OwnerGroupSnapshot? {
        if (timeoutMs <= 0 || !busy.compareAndSet(false, true)) return null
        val task = FutureTask<OwnerGroupSnapshot?> {
            try {
                read()
            } finally {
                busy.set(false)
            }
        }
        try {
            executor.execute(task)
        } catch (err: RejectedExecutionException) {
            busy.set(false)
            logFailure("Group observation worker busy", err)
            return null
        }
        return try {
            awaitResult(task, timeoutMs)
        } catch (err: TimeoutException) {
            logFailure("Group observation exceeded the offer deadline", err)
            null
        } catch (err: InterruptedException) {
            Thread.currentThread().interrupt()
            logFailure("Group observation interrupted", err)
            null
        } catch (err: ExecutionException) {
            logFailure("Group observation failed", err)
            null
        }
    }
}

/** One process-wide worker; a stalled observation cannot spawn replacement workers. */
internal object SinkOfferGroupReader {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>(),
        { task -> Thread(task, "mirax-offer-group").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val reader = SinkDeadlineGroupReader(
        worker,
        WfdOwnerBridge::groupSnapshotV2,
        logFailure = { message, err -> Log.w("SinkOfferGroupReader", message, err) },
    )

    fun snapshot(timeoutMs: Long): OwnerGroupSnapshot? = reader.snapshot(timeoutMs)
}
