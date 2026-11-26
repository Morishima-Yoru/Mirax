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
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.WfdOwner

/**
 * An owner-issued observation, not a guarantee that the OS group still exists.
 *
 * @property owner Owner which issued the observation.
 * @property state Observed group state.
 * @property epoch Owner-issued group identity.
 * @property ownerRevision Bridge owner revision for guarded dispatch.
 */
data class OwnerGroupSnapshot(
    val owner: WfdOwner,
    val state: P2pGroupState,
    val epoch: String,
    internal val ownerRevision: Long? = null,
) {
    internal fun canEndBy(currentOwner: WfdOwner): Boolean =
        currentOwner != WfdOwner.NONE && owner == currentOwner && state != P2pGroupState.Down &&
            PrimarySinkBeacon.isValidGroupEpoch(epoch)

    companion object {
        /**
         * Parse only the complete V2 wire form.
         *
         * UP may carry an optional tab and sanitized peer name from the same
         * observation (`V2 <epoch> UP <IPv4> [OWNER][\t<name>]`); DOWN/PENDING
         * never carry a name.
         *
         * @param owner Owner which supplied the response.
         * @param raw Untrusted response line.
         * @return The validated observation, or null if unavailable or malformed.
         */
        fun parse(owner: WfdOwner, raw: String?): OwnerGroupSnapshot? {
            if (owner == WfdOwner.NONE || raw == null) return null
            if (raw.any { it == '\r' || it == '\n' }) return null
            val tabIndex = raw.indexOf('\t')
            val header = if (tabIndex < 0) raw else raw.substring(0, tabIndex)
            val name = if (tabIndex < 0) null else raw.substring(tabIndex + 1).trim().ifEmpty { null }
            val parts = header.split(' ')
            if (parts.size < 3 || parts[0] != "V2" ||
                !PrimarySinkBeacon.isValidGroupEpoch(parts[1])
            ) return null
            val state = when {
                parts.size == 3 && parts[2] == "DOWN" && tabIndex < 0 -> P2pGroupState.Down
                parts.size == 3 && parts[2] == "PENDING" && tabIndex < 0 -> P2pGroupState.Pending
                parts.size in 4..5 && parts[2] == "UP" && isIpv4(parts[3]) &&
                    (parts.size == 4 || parts[4] == "OWNER") ->
                    P2pGroupState.Up(parts[3], phoneIsOwner = parts.size == 5, sourceDeviceName = name)
                else -> return null
            }
            return OwnerGroupSnapshot(owner, state, parts[1])
        }

        private fun isIpv4(address: String): Boolean {
            val octets = address.split('.')
            return octets.size == 4 && octets.all { octet ->
                octet.length in 1..3 && octet.all { it in '0'..'9' } &&
                    (octet.toIntOrNull() ?: 256) <= 255
            }
        }
    }
}

/** Result of local guarded-END submission, not a remote removal result. */
internal enum class OwnerEndSubmission {
    ACCEPTED,
    BUSY,
    STALE,
}

/** Serializes owner changes with local guarded-END submission, never with IPC. */
internal class OwnerGroupRoute(
    private val activeOwner: AtomicReference<WfdOwner>,
    private val worker: Executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "mirax-owner-v2").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ),
    private val snapshotTimeoutMs: Long = 1_000L,
    private val transportFor: (WfdOwner) -> WfdOwnerTransport?,
) {
    private data class Target(val owner: WfdOwner, val revision: Long, val transport: WfdOwnerTransport)

    private var revision = 0L

    @Synchronized
    fun switchOwner(owner: WfdOwner): WfdOwner {
        val previous = activeOwner.getAndSet(owner)
        if (previous != owner) revision++
        return previous
    }

    @Synchronized
    fun updateEndpoint(owner: WfdOwner, update: () -> Unit) {
        update()
        if (activeOwner.get() == owner) revision++
    }

    /**
     * Captures the active owner's revision without performing IPC.
     *
     * @param owner Expected active owner.
     * @return Its revision, or null when the owner is unavailable or changed.
     */
    @Synchronized
    fun revisionFor(owner: WfdOwner): Long? =
        if (owner != WfdOwner.NONE && activeOwner.get() == owner) revision else null

    /** Checks the local owner revision without performing IPC. */
    @Synchronized
    fun isCurrent(snapshot: OwnerGroupSnapshot): Boolean =
        snapshot.ownerRevision != null && snapshot.owner == activeOwner.get() && snapshot.ownerRevision == revision

    /** @return a current snapshot, or null on stale, busy, timed-out or unavailable IPC */
    fun snapshot(): OwnerGroupSnapshot? {
        val target = synchronized(this) {
            val owner = activeOwner.get()
            val transport = transportFor(owner) ?: return null
            Target(owner, revision, transport)
        }
        val task = FutureTask { target.transport.groupSnapshotV2() }
        try {
            worker.execute(task)
        } catch (err: RejectedExecutionException) {
            Log.w(TAG, "group snapshot worker busy", err)
            return null
        }
        val observed = try {
            task.get(snapshotTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (err: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "group snapshot interrupted", err)
            return null
        } catch (err: TimeoutException) {
            Log.w(TAG, "group snapshot timed out; worker remains occupied", err)
            return null
        } catch (err: ExecutionException) {
            Log.w(TAG, "group snapshot failed", err)
            return null
        }
        return synchronized(this) {
            if (observed == null || activeOwner.get() != target.owner ||
                revision != target.revision || observed.owner != target.owner
            ) {
                null
            } else {
                observed.copy(ownerRevision = target.revision)
            }
        }
    }

    /**
     * Submit locally without waiting for IPC. Accepted work may be invalidated before dispatch.
     *
     * @param snapshot observation from this route
     * @return true only if the local worker accepted the task, not if the owner queued removal
     */
    fun end(snapshot: OwnerGroupSnapshot): Boolean = submitEnd(snapshot) == OwnerEndSubmission.ACCEPTED

    /** Distinguishes local rejection from accepted work whose remote outcome is unknown. */
    @Synchronized
    fun submitEnd(snapshot: OwnerGroupSnapshot): OwnerEndSubmission {
        val owner = activeOwner.get()
        if (snapshot.ownerRevision != revision || !snapshot.canEndBy(owner)) return OwnerEndSubmission.STALE
        val target = Target(owner, revision, transportFor(owner) ?: return OwnerEndSubmission.STALE)
        return try {
            worker.execute {
                // This orders local dispatch, not remote execution against a later switch.
                val committed = synchronized(this) {
                    activeOwner.get() == target.owner && revision == target.revision
                }
                if (committed) {
                    try {
                        if (!target.transport.endSessionIfEpoch(snapshot)) {
                            Log.w(TAG, "guarded end not confirmed; no automatic retry")
                        }
                    } catch (err: Exception) {
                        Log.w(TAG, "guarded end outcome unknown; no automatic retry", err)
                    }
                }
            }
            OwnerEndSubmission.ACCEPTED
        } catch (err: RejectedExecutionException) {
            Log.w(TAG, "guarded end worker busy", err)
            OwnerEndSubmission.BUSY
        }
    }

    private companion object {
        const val TAG = "OwnerGroupRoute"
    }
}
