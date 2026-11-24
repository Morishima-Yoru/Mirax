package me.trinitrix.mirax.ui.standby

/** Main-thread lease guard for the process-scoped media owner. */
internal class StandbyHostLease {
    private var generation = 0L
    private var current: Long? = null

    /** Replaces the previous host and returns the new lease. */
    fun acquire(): Long {
        generation += 1
        current = generation
        return generation
    }

    /** Returns whether the lease still owns the shared output. */
    fun isCurrent(lease: Long): Boolean = current == lease

    /**
     * Restores the previous owner only if the failed lease is still current.
     *
     * @param failedLease Lease whose binding failed.
     * @param previousLease Lease to restore, or null if there was no previous owner.
     */
    fun restore(failedLease: Long, previousLease: Long?) {
        if (current == failedLease) current = previousLease
    }

    /** Runs cleanup only if the caller still owns the shared output. */
    fun release(lease: Long, cleanup: () -> Unit) {
        if (current != lease) return
        current = null
        cleanup()
    }
}
