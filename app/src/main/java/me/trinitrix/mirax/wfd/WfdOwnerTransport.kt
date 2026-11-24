package me.trinitrix.mirax.wfd

import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner

/**
 * Common deep interface for privileged WFD owners.
 *
 * Implements the architecture principle: "two adapters = real seam".
 * Adapters:
 * - [ShizukuOwnerAdapter] (Binder IPC)
 * - [SocketHelperAdapter] (LocalSocket ASCII)
 */
interface WfdOwnerTransport {
    val owner: WfdOwner

    fun advertise(command: WfdAdvertiseCommand): Boolean

    fun stopAdvertise()

    fun isListening(): Boolean

    fun groupState(): String?

    /** @return a validated owner-issued snapshot, or null when unavailable */
    fun groupSnapshotV2(): OwnerGroupSnapshot?

    /**
     * @param snapshot owner observation used for the guarded request
     * @return true when the owner confirmed handler queueing, not removal; false may mean an
     *         unknown outcome and must not trigger an automatic retry
     */
    fun endSessionIfEpoch(snapshot: OwnerGroupSnapshot): Boolean

    fun endSession()

    fun forgetAllPairings()

    fun pairingState(): String

    fun wmSize(displayId: Int?): String?
}
