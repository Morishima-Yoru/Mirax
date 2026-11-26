package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.ArrayDeque
import me.trinitrix.mirax.session.SessionAction
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tests ticket delivery with a deterministic queue, not the Android bridge or owner IPC. */
class ArmResultFenceTest {
    /** Cancellation rejects either queued outcome while allowing a subsequent ticket. */
    @Test
    fun queuedResults_cancelBeforeDrain_discardsOldAndDeliversNew() {
        for (listening in listOf(true, false)) {
            val delivered = mutableListOf<SessionAction>()
            val queue = ArrayDeque<Runnable>()
            val fence = ArmResultFence { delivered.add(it) }
            val oldTicket = fence.begin()
            queue.addLast(requireNotNull(fence.finish(oldTicket, listening)))

            fence.invalidate()
            queue.removeFirst().run()
            assertThat(delivered).isEmpty()

            val newTicket = fence.begin()
            queue.addLast(requireNotNull(fence.finish(newTicket, listening)))
            queue.removeFirst().run()
            val expected = if (listening) SessionAction.BeaconListening else SessionAction.BeaconFailed
            assertThat(delivered).containsExactly(expected)
        }
    }

    /** Rearming invalidates a queued result without relying on command or owner identity. */
    @Test
    fun queuedResults_rearmBeforeDrain_deliversOnlyNewTicket() {
        for (listening in listOf(true, false)) {
            val delivered = mutableListOf<SessionAction>()
            val queue = ArrayDeque<Runnable>()
            val fence = ArmResultFence { delivered.add(it) }
            val oldTicket = fence.begin()
            queue.addLast(requireNotNull(fence.finish(oldTicket, listening)))

            val newTicket = fence.begin()
            assertThat(newTicket).isNotEqualTo(oldTicket)
            queue.addLast(requireNotNull(fence.finish(newTicket, listening)))
            queue.removeFirst().run()
            assertThat(delivered).isEmpty()
            queue.removeFirst().run()
            val expected = if (listening) SessionAction.BeaconListening else SessionAction.BeaconFailed
            assertThat(delivered).containsExactly(expected)
        }
    }

    /** Completing a ticket stops polling, but its guarded result remains deliverable. */
    @Test
    fun finish_stopsPolling_withoutInvalidatingDelivery() {
        for (listening in listOf(true, false)) {
            val delivered = mutableListOf<SessionAction>()
            val queue = ArrayDeque<Runnable>()
            val fence = ArmResultFence { delivered.add(it) }
            val ticket = fence.begin()
            assertThat(fence.isWatching(ticket)).isTrue()

            queue.addLast(requireNotNull(fence.finish(ticket, listening)))
            assertThat(fence.currentGeneration).isEqualTo(ticket)
            assertThat(fence.isWatching(ticket)).isFalse()
            assertThat(delivered).isEmpty()
            queue.removeFirst().run()
            val expected = if (listening) SessionAction.BeaconListening else SessionAction.BeaconFailed
            assertThat(delivered).containsExactly(expected)
        }
    }

    /** Repeated completion and execution cannot dispatch the same ticket twice. */
    @Test
    fun duplicateFinish_andRepeatedRun_dispatchOnce() {
        val delivered = mutableListOf<SessionAction>()
        val queue = ArrayDeque<Runnable>()
        val fence = ArmResultFence { delivered.add(it) }
        val ticket = fence.begin()
        val delivery = requireNotNull(fence.finish(ticket, listening = true))
        assertThat(fence.finish(ticket, listening = false)).isNull()
        queue.addLast(delivery)
        queue.addLast(delivery)

        queue.removeFirst().run()
        queue.removeFirst().run()
        assertThat(fence.finish(ticket, listening = true)).isNull()
        assertThat(delivered).containsExactly(SessionAction.BeaconListening)
    }

    /** A stale poll cannot complete or stop the new arm. */
    @Test
    fun oldTicket_cannotFinishNewArm() {
        val delivered = mutableListOf<SessionAction>()
        val queue = ArrayDeque<Runnable>()
        val fence = ArmResultFence { delivered.add(it) }
        val oldTicket = fence.begin()
        val newTicket = fence.begin()

        assertThat(fence.isWatching(oldTicket)).isFalse()
        assertThat(fence.finish(oldTicket, listening = false)).isNull()
        assertThat(fence.isWatching(newTicket)).isTrue()
        queue.addLast(requireNotNull(fence.finish(newTicket, listening = true)))
        queue.removeFirst().run()
        assertThat(delivered).containsExactly(SessionAction.BeaconListening)
    }

    /** Dispatch may reenter begin; no post-dispatch cleanup may clear that new ticket. */
    @Test
    fun dispatch_reentrantBegin_preservesNewArm() {
        val delivered = mutableListOf<SessionAction>()
        val queue = ArrayDeque<Runnable>()
        lateinit var fence: ArmResultFence
        var newTicket: Int? = null
        fence = ArmResultFence { action ->
            delivered.add(action)
            if (action == SessionAction.BeaconListening) {
                newTicket = fence.begin()
            }
            true
        }
        val ticket = fence.begin()
        queue.addLast(requireNotNull(fence.finish(ticket, listening = true)))
        queue.removeFirst().run()

        val rearmedTicket = requireNotNull(newTicket)
        assertThat(fence.isWatching(rearmedTicket)).isTrue()
        assertThat(fence.currentGeneration).isEqualTo(rearmedTicket)
        queue.addLast(requireNotNull(fence.finish(rearmedTicket, listening = false)))
        queue.removeFirst().run()
        assertThat(delivered).containsExactly(
            SessionAction.BeaconListening,
            SessionAction.BeaconFailed,
        ).inOrder()
    }

    /** A failed/no-op dispatch keeps the ticket queued so a later retry can deliver it. */
    @Test
    fun dispatchNoOp_keepsQueuedTicket_retryDelivers() {
        val delivered = mutableListOf<SessionAction>()
        var allowDelivery = false
        val fence = ArmResultFence { action ->
            if (!allowDelivery) return@ArmResultFence false
            delivered.add(action)
            true
        }
        val ticket = fence.begin()
        val delivery = requireNotNull(fence.finish(ticket, listening = true))

        delivery.run()
        assertThat(delivered).isEmpty()
        assertThat(fence.currentGeneration).isEqualTo(ticket)

        allowDelivery = true
        delivery.run()
        assertThat(delivered).containsExactly(SessionAction.BeaconListening)
    }

    /** Observer failure propagates after consumption, so rerunning cannot replay it. */
    @Test
    fun dispatchFailure_propagates_withoutReplayingConsumedTicket() {
        val failure = IllegalStateException("dispatch failed")
        var attempts = 0
        val queue = ArrayDeque<Runnable>()
        val fence = ArmResultFence {
            attempts++
            throw failure
        }
        val ticket = fence.begin()
        val delivery = requireNotNull(fence.finish(ticket, listening = true))
        queue.addLast(delivery)
        queue.addLast(delivery)

        val thrown = assertThrows(IllegalStateException::class.java) { queue.removeFirst().run() }
        assertThat(thrown).isSameInstanceAs(failure)
        queue.removeFirst().run()
        assertThat(attempts).isEqualTo(1)
    }
}
