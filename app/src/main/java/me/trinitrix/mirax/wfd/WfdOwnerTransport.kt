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

    fun endSession()

    fun forgetAllPairings()

    fun pairingState(): String

    fun wmSize(displayId: Int?): String?
}
