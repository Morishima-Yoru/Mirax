package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrimarySinkBeaconGroupTrackerTest {
    @Test
    fun queuedEnd_doesNotRemoveReplacementGroup() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val epochA = tracker.snapshot().epoch
        var removals = 0
        val queued = tracker.guardedEnd(epochA) { _, _ -> removals++ }

        tracker.observe(false, false, null)
        tracker.observe(true, false, "192.168.49.2")
        queued.run()

        assertThat(removals).isEqualTo(0)
        assertThat(tracker.snapshot().state).isEqualTo("UP 192.168.49.2")
    }

    @Test
    fun guardedEnd_consumesOnceAndInvalidatesOutstandingCallbacks() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val oldRequest = tracker.request()
        val epoch = tracker.snapshot().epoch
        var removals = 0
        val first = tracker.guardedEnd(epoch) { _, _ -> removals++ }
        val duplicate = tracker.guardedEnd(epoch) { _, _ -> removals++ }
        first.run()
        duplicate.run()
        var pairingSideEffects = 0
        val accepted = tracker.accept(oldRequest) {
            pairingSideEffects++
            tracker.observe(false, false, null)
        }

        assertThat(removals).isEqualTo(1)
        assertThat(accepted).isFalse()
        assertThat(pairingSideEffects).isEqualTo(0)
        assertThat(tracker.snapshot().state).isEqualTo("UP 192.168.49.1")
    }

    @Test
    fun failedEnd_publishesRetryEpochButNeverReusesOldEpoch() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val oldEpoch = tracker.snapshot().epoch
        var failure: Runnable? = null
        var removals = 0
        tracker.guardedEnd(oldEpoch) { _, callback ->
            removals++
            failure = callback
        }.run()
        tracker.guardedEnd(oldEpoch) { _, _ -> removals++ }.run()
        requireNotNull(failure).run()
        val retryEpoch = tracker.snapshot().epoch
        assertThat(retryEpoch).isNotEqualTo(oldEpoch)
        tracker.guardedEnd(oldEpoch) { _, _ -> removals++ }.run()
        tracker.guardedEnd(retryEpoch) { _, _ -> removals++ }.run()
        requireNotNull(failure).run()
        tracker.guardedEnd(retryEpoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(2)
        assertThat(tracker.snapshot().epoch).isEqualTo(retryEpoch)
    }

    @Test
    fun thrownEnd_releasesOnlyItsEpochForRetry() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val epoch = tracker.snapshot().epoch
        val failure = runCatching {
            tracker.guardedEnd(epoch) { _, _ -> throw IllegalStateException("remove failed") }.run()
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(tracker.snapshot().epoch).isNotEqualTo(epoch)
    }

    @Test
    fun successThenLateFailure_keepsEpochConsumed() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val epoch = tracker.snapshot().epoch
        var success: Runnable? = null
        var failure: Runnable? = null
        var removals = 0
        tracker.guardedEnd(epoch) { onSuccess, onFailure ->
            removals++
            success = onSuccess
            failure = onFailure
        }.run()
        requireNotNull(success).run()
        requireNotNull(failure).run()
        tracker.guardedEnd(epoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(1)
        assertThat(tracker.snapshot().epoch).isEqualTo(epoch)
    }

    @Test
    fun formedStateChanges_cannotRearmBeforeObservedDown() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, null)
        var success: Runnable? = null
        var removals = 0
        tracker.guardedEnd(tracker.snapshot().epoch) { onSuccess, _ ->
            removals++
            success = onSuccess
        }.run()
        tracker.observe(true, false, "192.168.49.1")
        tracker.guardedEnd(tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        requireNotNull(success).run()
        tracker.observe(true, false, "192.168.49.2")
        tracker.guardedEnd(tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(1)

        tracker.observe(false, false, null)
        tracker.observe(true, false, "192.168.49.2")
        tracker.guardedEnd(tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(2)
    }

    @Test
    fun resetWithoutObservedDown_cannotRearmUntilDownObserved() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        var removals = 0
        tracker.guardedEnd(tracker.snapshot().epoch) { onSuccess, _ ->
            removals++
            onSuccess.run()
        }.run()
        tracker.setWanted(false)
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        tracker.guardedEnd(tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(1)

        tracker.setWanted(false)
        tracker.setWanted(true)
        val resetEpoch = tracker.snapshot().epoch
        tracker.observe(false, false, null)
        assertThat(tracker.snapshot().epoch).isEqualTo(resetEpoch)
        tracker.observe(true, false, "192.168.49.1")
        tracker.guardedEnd(tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(2)
    }

    @Test
    fun failureAfterFormedStateChange_doesNotRearmDifferentEpoch() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, null)
        var failure: Runnable? = null
        var removals = 0
        tracker.guardedEnd(tracker.snapshot().epoch) { _, onFailure ->
            removals++
            failure = onFailure
        }.run()
        tracker.observe(true, false, "192.168.49.1")
        val newerEpoch = tracker.snapshot().epoch
        requireNotNull(failure).run()
        assertThat(tracker.snapshot().epoch).isEqualTo(newerEpoch)
        tracker.guardedEnd(newerEpoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(1)
    }

    @Test
    fun lateEndFailure_doesNotChangeReplacementOrReset() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        var failure: Runnable? = null
        tracker.guardedEnd(tracker.snapshot().epoch) { _, callback -> failure = callback }.run()
        tracker.observe(false, false, null)
        tracker.observe(true, false, "192.168.49.1")
        val replacementEpoch = tracker.snapshot().epoch
        requireNotNull(failure).run()
        assertThat(tracker.snapshot().epoch).isEqualTo(replacementEpoch)

        tracker.guardedEnd(replacementEpoch) { _, callback -> failure = callback }.run()
        tracker.setWanted(false)
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val resetEpoch = tracker.snapshot().epoch
        requireNotNull(failure).run()
        assertThat(tracker.snapshot().epoch).isEqualTo(resetEpoch)
    }

    @Test
    fun observations_rollOnlyForRealChangesAndReset() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        tracker.observe(true, false, "192.168.49.1")
        val first = tracker.snapshot().epoch
        tracker.observe(true, false, "192.168.49.1")
        tracker.setWanted(true)
        assertThat(tracker.snapshot().epoch).isEqualTo(first)

        tracker.observe(true, false, "192.168.49.2")
        val changedPeer = tracker.snapshot().epoch
        assertThat(changedPeer).isNotEqualTo(first)
        tracker.observe(true, true, "192.168.49.2")
        val beforeDown = tracker.snapshot().epoch
        assertThat(beforeDown).isNotEqualTo(changedPeer)
        var removals = 0
        val oldEnd = tracker.guardedEnd(beforeDown) { _, _ -> removals++ }
        tracker.observe(false, false, null)
        tracker.observe(true, true, "192.168.49.2")
        val afterDown = tracker.snapshot().epoch
        assertThat(afterDown).isNotEqualTo(beforeDown)
        oldEnd.run()
        assertThat(removals).isEqualTo(0)

        val queued = tracker.guardedEnd(afterDown) { _, _ -> removals++ }
        tracker.setWanted(false)
        val stopped = tracker.snapshot().epoch
        tracker.setWanted(false)
        assertThat(tracker.snapshot().epoch).isEqualTo(stopped)
        tracker.setWanted(true)
        tracker.observe(true, true, "192.168.49.2")
        queued.run()
        assertThat(removals).isEqualTo(0)
        assertThat(tracker.snapshot().epoch).isNotEqualTo(afterDown)
    }

    @Test
    fun pendingRoleAndOwnerNeighborChanges_getNewEpochs() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        val other = PrimarySinkBeacon.GroupTracker()
        assertThat(tracker.snapshot().epoch).isNotEqualTo(other.snapshot().epoch)
        tracker.setWanted(true)
        tracker.observe(true, false, null)
        val clientPending = tracker.snapshot().epoch
        tracker.observe(true, true, null)
        val ownerPending = tracker.snapshot().epoch
        assertThat(ownerPending).isNotEqualTo(clientPending)
        tracker.observe(true, true, "192.168.49.2")
        val neighborA = tracker.snapshot().epoch
        tracker.observe(true, true, "192.168.49.3")
        assertThat(tracker.snapshot().epoch).isNotEqualTo(neighborA)
    }

    @Test
    fun olderDuplicateAndPriorLifecycleCallbacks_haveNoSideEffects() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        val older = tracker.request()
        val newer = tracker.request()
        var pairingSideEffects = 0
        assertThat(tracker.accept(newer) {
            tracker.observe(true, false, "192.168.49.2")
            pairingSideEffects++
        }).isTrue()
        val epoch = tracker.snapshot().epoch
        assertThat(tracker.accept(older) {
            tracker.observe(false, false, null)
            pairingSideEffects++
        }).isFalse()
        assertThat(tracker.accept(newer) { pairingSideEffects++ }).isFalse()
        assertThat(tracker.snapshot().epoch).isEqualTo(epoch)
        assertThat(pairingSideEffects).isEqualTo(1)

        val priorLifecycle = tracker.request()
        tracker.setWanted(false)
        tracker.setWanted(true)
        assertThat(tracker.accept(priorLifecycle) { pairingSideEffects++ }).isFalse()
        assertThat(pairingSideEffects).isEqualTo(1)
        assertThat(tracker.snapshot().state).isEqualTo("DOWN")
    }

    @Test
    fun availableNeighborOutput_ignoresIncompleteLines() {
        val peer = "192.168.49.2 dev p2p0 lladdr 02:00:00:00:00:01 REACHABLE"
        assertThat(PrimarySinkBeacon.parseAvailableP2pNeighbor(peer)).isNull()
        assertThat(PrimarySinkBeacon.parseAvailableP2pNeighbor("$peer\n192.168.49.3 dev p2p0 lladdr"))
            .isEqualTo("192.168.49.2")
        assertThat(PrimarySinkBeacon.parseAvailableP2pNeighbor(
            "192.168.49.4 dev p2p0 lladdr 02:00:00:00:00:02 FAILED\n",
        )).isNull()
    }

    @Test
    fun slowNeighbor_keepsHandlerAvailableForEndAndStop() {
        val fixture = NeighborFixture()
        fixture.start("192.168.49.2")
        fixture.request()
        fixture.request()
        assertThat(fixture.workers).hasSize(1)
        var removals = 0
        fixture.tracker.guardedEnd(fixture.tracker.snapshot().epoch) { _, _ -> removals++ }.run()
        fixture.tracker.setWanted(false)
        fixture.workers.removeFirst().run()
        fixture.posts.removeFirst().run()
        assertThat(removals).isEqualTo(1)
        assertThat(fixture.tracker.snapshot().state).isEqualTo("DOWN")
    }

    @Test
    fun expiredNeighbor_keepsOneWorkerUntilItReturns() {
        val fixture = NeighborFixture()
        fixture.start("192.168.49.2")
        fixture.address = "192.168.49.3"
        val epoch = fixture.tracker.snapshot().epoch
        fixture.request()
        fixture.timers.removeLast().run()
        fixture.request()
        assertThat(fixture.workers).hasSize(1)
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(epoch)
        fixture.request()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().state).isEqualTo("UP 192.168.49.3 OWNER")
    }

    @Test
    fun neighborFailure_keepsUpAndNewPeerChangesEpochOnce() {
        val fixture = NeighborFixture()
        fixture.start("192.168.49.2")
        val original = fixture.tracker.snapshot().epoch
        fixture.address = null
        fixture.request()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(original)
        assertThat(fixture.tracker.snapshot().state).isEqualTo("UP 192.168.49.2 OWNER")

        fixture.address = "192.168.49.3"
        fixture.request()
        fixture.complete()
        val changed = fixture.tracker.snapshot().epoch
        assertThat(changed).isNotEqualTo(original)
        fixture.request()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(changed)
    }

    @Test
    fun oldOrExpiredNeighborResults_cannotChangeNewGroup() {
        val fixture = NeighborFixture()
        fixture.start("192.168.49.2")
        fixture.address = "192.168.49.3"
        fixture.request()
        fixture.tracker.observe(false, false, null)
        fixture.tracker.observe(true, true, "192.168.49.2")
        val replacement = fixture.tracker.snapshot().epoch
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(replacement)

        fixture.request()
        fixture.timers.removeLast().run()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(replacement)
        fixture.request()
        fixture.tracker.setWanted(false)
        fixture.tracker.setWanted(true)
        fixture.tracker.observe(true, true, "192.168.49.2")
        val reset = fixture.tracker.snapshot().epoch
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(reset)
    }

    @Test
    fun olderNeighborSequence_isRejectedAfterNewerConnectionInfo() {
        val fixture = NeighborFixture()
        fixture.start("192.168.49.2")
        val epoch = fixture.tracker.snapshot().epoch
        fixture.address = "192.168.49.3"
        fixture.request()
        val newer = fixture.tracker.request()
        assertThat(fixture.tracker.accept(newer) {}).isTrue()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().epoch).isEqualTo(epoch)
        fixture.request()
        fixture.complete()
        assertThat(fixture.tracker.snapshot().state).isEqualTo("UP 192.168.49.3 OWNER")
    }

    private class NeighborFixture {
        val tracker = PrimarySinkBeacon.GroupTracker()
        val workers = java.util.ArrayDeque<Runnable>()
        val posts = java.util.ArrayDeque<Runnable>()
        val timers = java.util.ArrayDeque<Runnable>()
        var address: String? = null
        val query = PrimarySinkBeacon.NeighborQuery(
            tracker,
            { task -> workers.addLast(task) },
            { task -> posts.addLast(task) },
            { task, _ ->
                timers.add(task)
                true
            },
            { address },
        )

        fun start(peer: String) {
            tracker.setWanted(true)
            tracker.observe(true, true, peer)
        }

        fun request() {
            val request = tracker.request()
            assertThat(tracker.accept(request) { query.request(request) }).isTrue()
        }

        fun complete() {
            workers.removeFirst().run()
            posts.removeFirst().run()
        }
    }

    @Test
    fun invalidUnknownAndDownEpochs_doNotRemove() {
        val tracker = PrimarySinkBeacon.GroupTracker()
        tracker.setWanted(true)
        val downEpoch = tracker.snapshot().epoch
        var removals = 0
        tracker.guardedEnd(downEpoch) { _, _ -> removals++ }.run()
        tracker.observe(true, false, null)
        tracker.guardedEnd("invalid") { _, _ -> removals++ }.run()
        tracker.guardedEnd(downEpoch) { _, _ -> removals++ }.run()
        assertThat(removals).isEqualTo(0)
    }
}
