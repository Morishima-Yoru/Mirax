package me.trinitrix.mirax.wfd

import me.trinitrix.mirax.session.SessionAction

/**
 * Fences queued arm results independently of whether polling has completed.
 *
 * @param dispatch Receives each current result once and returns whether it was delivered.
 *     A false return keeps the ticket queued so a later retry can deliver it.
 *     Exceptions propagate to the caller after the ticket is consumed.
 */
internal class ArmResultFence(private val dispatch: (SessionAction) -> Boolean) {
    private enum class State { IDLE, WATCHING, QUEUED }

    private var generation = 0
    private var state = State.IDLE

    /** The ticket to capture before dispatching a pending owner command. */
    val currentGeneration: Int
        @Synchronized get() = generation

    /** @return A new watching ticket, invalidating any previous result. */
    @Synchronized
    fun begin(): Int {
        generation++
        state = State.WATCHING
        return generation
    }

    /** @return Whether this ticket may still poll or finish. */
    @Synchronized
    fun isWatching(ticket: Int): Boolean = ticket == generation && state == State.WATCHING

    /**
     * Stops polling without invalidating the queued result.
     *
     * @param ticket Ticket captured by the poll or pending-command callback.
     * @param listening Whether the owner reported readiness rather than failure.
     * @return A guarded one-shot delivery, or null for stale or completed work.
     */
    @Synchronized
    fun finish(ticket: Int, listening: Boolean): Runnable? {
        if (!isWatching(ticket)) return null
        state = State.QUEUED
        val action = if (listening) SessionAction.BeaconListening else SessionAction.BeaconFailed
        return Runnable { deliver(ticket, action) }
    }

    /** Invalidates both polling and queued delivery, even when no command remains. */
    @Synchronized
    fun invalidate() {
        generation++
        state = State.IDLE
    }

    /** Consumes before dispatch; restores QUEUED when delivery cannot run. */
    @Synchronized
    private fun deliver(ticket: Int, action: SessionAction) {
        if (ticket != generation || state != State.QUEUED) return
        state = State.IDLE
        val delivered = dispatch(action)
        if (!delivered && ticket == generation && state == State.IDLE) {
            state = State.QUEUED
        }
    }
}
