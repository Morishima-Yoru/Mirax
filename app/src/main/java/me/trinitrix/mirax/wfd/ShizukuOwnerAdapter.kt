package me.trinitrix.mirax.wfd

import android.util.Log
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService

/**
 * WFD owner transport backed by the Shizuku shell service.
 *
 * @param serviceProvider Supplies the currently bound service, if available.
 */
class ShizukuOwnerAdapter(
    private val serviceProvider: () -> IMiraxShellService?,
) : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.SHIZUKU

    /** Advertises [command] via Shizuku; returns whether the request succeeded. */
    override fun advertise(command: WfdAdvertiseCommand): Boolean {
        val service = serviceProvider() ?: return false
        return try {
            service.advertise(command.broadcastName, WfdOwnerBridge.encodeModes(command.modes))
            true
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku advertise failed", err)
            false
        }
    }

    /** Stops Shizuku advertising if a service is bound. */
    override fun stopAdvertise() {
        try {
            serviceProvider()?.stopAdvertise()
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku stopAdvertise failed", err)
        }
    }

    /** Returns whether the Shizuku service is listening. */
    override fun isListening(): Boolean {
        return try {
            serviceProvider()?.isListening == true
        } catch (err: Exception) {
            Log.d(TAG, "isListening via Shizuku failed", err)
            false
        }
    }

    /** Returns the current group state, or null if unavailable. */
    override fun groupState(): String? {
        return try {
            serviceProvider()?.groupState()
        } catch (err: Exception) {
            Log.d(TAG, "groupState via Shizuku failed", err)
            null
        }
    }

    /** Ends the current WFD session. */
    override fun endSession() {
        try {
            serviceProvider()?.endSession()
        } catch (err: Exception) {
            Log.w(TAG, "endSession via Shizuku failed", err)
        }
    }

    /** Forgets all saved WFD pairings. */
    override fun forgetAllPairings() {
        try {
            serviceProvider()?.forgetAllPairings()
        } catch (err: Exception) {
            Log.w(TAG, "forgetAllPairings via Shizuku failed", err)
        }
    }

    /** Returns the pairing state, defaulting to unpaired if unavailable. */
    override fun pairingState(): String {
        return try {
            serviceProvider()?.pairingState ?: "UNPAIRED"
        } catch (err: Exception) {
            Log.d(TAG, "pairingState via Shizuku failed", err)
            "UNPAIRED"
        }
    }

    /** Returns the window-manager size for [displayId], or null on failure. */
    override fun wmSize(displayId: Int?): String? {
        val id = displayId ?: -1
        return try {
            serviceProvider()?.wmSize(id)
        } catch (err: Exception) {
            Log.w(TAG, "wm size via Shizuku failed", err)
            null
        }
    }

    companion object {
        private const val TAG = "ShizukuOwnerAdapter"
    }
}
