package me.trinitrix.mirax.ui.standby

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Checks that an old activity cannot release its replacement's output. */
class StandbyHostLeaseTest {
    @Test
    fun staleHostCleanupDoesNotRun() {
        val leases = StandbyHostLease()
        val calls = mutableListOf<String>()
        val old = leases.acquire()
        val replacement = leases.acquire()

        leases.release(old) { calls.add("old cleanup") }
        assertThat(calls).isEmpty()
        assertThat(leases.isCurrent(replacement)).isTrue()

        leases.release(replacement) { calls.add("current cleanup") }
        assertThat(calls).containsExactly("current cleanup")
        assertThat(leases.isCurrent(replacement)).isFalse()
    }

    @Test
    fun failedReplacementRestoresOnlyItsOwnPreviousLease() {
        val leases = StandbyHostLease()
        val first = leases.acquire()
        val failed = leases.acquire()
        leases.restore(failed, first)
        assertThat(leases.isCurrent(first)).isTrue()

        val newer = leases.acquire()
        assertThat(newer).isGreaterThan(failed)
        leases.restore(failed, first)
        assertThat(leases.isCurrent(newer)).isTrue()
    }

    /** Checks that rolling back the first owner does not reuse its lease. */
    @Test
    fun failedFirstHostRestoresUnownedWithoutReusingLease() {
        val leases = StandbyHostLease()
        val failed = leases.acquire()

        leases.restore(failed, null)
        assertThat(leases.isCurrent(failed)).isFalse()

        val newer = leases.acquire()
        assertThat(newer).isGreaterThan(failed)
        var cleaned = false
        leases.release(failed) { cleaned = true }
        assertThat(cleaned).isFalse()
        assertThat(leases.isCurrent(newer)).isTrue()
    }
}
