package me.trinitrix.mirax.wfd

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.wm.WmSize

/**
 * Checks a WM result before applying it.
 *
 * @param nowMs Current monotonic time.
 * @param deadlineMs Original deadline.
 * @param eligible Whether the requesting session still permits provisioning.
 * @param sameIdentity Whether the owner and source still match.
 * @return True only while the result is current and before its deadline.
 */
internal fun isCurrentSinkWmReading(
    nowMs: Long,
    deadlineMs: Long,
    eligible: Boolean,
    sameIdentity: Boolean,
): Boolean = eligible && sameIdentity && nowMs < deadlineMs

/**
 * Source and owner that requested a WM read.
 *
 * @property owner Owner at request time.
 * @property fence Controller generation and source at request time.
 * @property ownerRevision Local owner endpoint revision at request time.
 */
internal data class SinkWmReadScope(
    val owner: WfdOwner,
    val fence: SinkProvisioningFence,
    val ownerRevision: Long,
)

/**
 * Checks the captured WM scope against the current local owner and source.
 *
 * @param scope Original WM request identity.
 * @param owner Current session owner.
 * @param fence Current controller source identity.
 * @param ownerRevision Current local route revision, or null when unavailable.
 * @return True only when all identities are current.
 */
internal fun isCurrentSinkWmScope(
    scope: SinkWmReadScope,
    owner: WfdOwner,
    fence: SinkProvisioningFence,
    ownerRevision: Long?,
): Boolean = scope.owner == owner && scope.fence == fence && scope.ownerRevision == ownerRevision

/** Shares one blocking WM read without creating more workers when IPC stalls. */
internal class SinkWmReadCoordinator(
    private val executor: Executor,
    private val read: (Int?) -> WmSizeReading?,
) {
    /** One in-flight read, which may outlive a caller's deadline. */
    internal class Request(
        internal val scope: SinkWmReadScope,
        internal val displayId: Int?,
        internal val deadlineMs: Long,
    ) {
        private val ready = CountDownLatch(1)
        private var done = false
        private var reading: WmSizeReading? = null
        private val listeners = mutableListOf<() -> Unit>()

        /** Waits no longer than the caller's remaining deadline. */
        fun await(timeoutMs: Long): Boolean = ready.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

        /** Returns a reading only after the read has completed. */
        @Synchronized
        fun result(): WmSizeReading? = reading

        /** Registers a completion notification, including already completed reads. */
        fun onComplete(listener: () -> Unit) {
            val notifyNow = synchronized(this) {
                if (done) true else {
                    listeners.add(listener)
                    false
                }
            }
            if (notifyNow) listener()
        }

        internal fun complete(value: WmSizeReading?): List<() -> Unit> {
            val callbacks = synchronized(this) {
                reading = value
                done = true
                listeners.toList().also { listeners.clear() }
            }
            ready.countDown()
            return callbacks
        }
    }

    private var inFlight: Request? = null

    /** Returns the read occupying the sole worker, if any. */
    @Synchronized
    fun activeRequest(): Request? = inFlight

    /** Acquires either the matching read or the exact read blocking it. */
    @Synchronized
    fun requestOrActive(scope: SinkWmReadScope, deadlineMs: Long, displayId: Int? = null): Pair<Request, Boolean>? {
        val active = inFlight
        if (active != null) return active to (active.scope == scope && active.displayId == displayId)
        return request(scope, deadlineMs, displayId)?.let { it to true }
    }

    /** Waits for a differently scoped read without extending the original deadline. */
    fun requestBeforeDeadline(
        scope: SinkWmReadScope,
        deadlineMs: Long,
        nowMs: () -> Long,
        isCurrent: () -> Boolean,
    ): Request? {
        while (nowMs() < deadlineMs && isCurrent()) {
            val (request, matches) = requestOrActive(scope, deadlineMs) ?: return null
            if (matches) return request
            if (!request.await(deadlineMs - nowMs())) return null
        }
        return null
    }

    /**
     * Starts or joins a read without extending its first deadline.
     *
     * @param scope Owner and source requesting the read.
     * @param deadlineMs Deadline for a new read, in monotonic milliseconds.
     * @param displayId Display to query, or null for the plain size.
     * @return The matching request, or null if a different read occupies the worker.
     */
    @Synchronized
    fun request(scope: SinkWmReadScope, deadlineMs: Long, displayId: Int? = null): Request? {
        inFlight?.let { return if (it.scope == scope && it.displayId == displayId) it else null }
        val request = Request(scope, displayId, deadlineMs)
        inFlight = request
        try {
            executor.execute {
                val reading = try {
                    read(displayId)
                } catch (err: Exception) {
                    Log.w(TAG, "WM size read failed", err)
                    null
                }
                synchronized(this) {
                    if (inFlight === request) inFlight = null
                }
                val callbacks = request.complete(reading)
                callbacks.forEach { callback ->
                    try {
                        callback()
                    } catch (err: Exception) {
                        Log.w(TAG, "WM size completion failed", err)
                    }
                }
            }
        } catch (err: RejectedExecutionException) {
            inFlight = null
            Log.w(TAG, "WM size worker busy", err)
            return null
        }
        return request
    }

    private companion object {
        const val TAG = "SinkWmSizeReader"
    }
}

/** Process-wide bounded WM worker shared by provisioning and connection offers. */
internal object SinkWmSizeReader {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task, "mirax-wm-size").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val coordinator = SinkWmReadCoordinator(worker) { displayId ->
        if (displayId == null) WmSize.readPlain() else WmSize.readForDisplay(displayId)
    }

    fun request(scope: SinkWmReadScope, deadlineMs: Long, displayId: Int? = null): SinkWmReadCoordinator.Request? =
        coordinator.request(scope, deadlineMs, displayId)

    fun activeRequest(): SinkWmReadCoordinator.Request? = coordinator.activeRequest()

    fun requestBeforeDeadline(
        scope: SinkWmReadScope,
        deadlineMs: Long,
        nowMs: () -> Long,
        isCurrent: () -> Boolean,
    ): SinkWmReadCoordinator.Request? = coordinator.requestBeforeDeadline(scope, deadlineMs, nowMs, isCurrent)
}
