package me.trinitrix.mirax.wfd

import android.util.Log
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService

class ShizukuOwnerAdapter(
    private val serviceProvider: () -> IMiraxShellService?,
) : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.SHIZUKU

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

    override fun stopAdvertise() {
        try {
            serviceProvider()?.stopAdvertise()
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku stopAdvertise failed", err)
        }
    }

    override fun isListening(): Boolean {
        return try {
            serviceProvider()?.isListening == true
        } catch (err: Exception) {
            Log.d(TAG, "isListening via Shizuku failed", err)
            false
        }
    }

    override fun groupState(): String? {
        return try {
            serviceProvider()?.groupState()
        } catch (err: Exception) {
            Log.d(TAG, "groupState via Shizuku failed", err)
            null
        }
    }

    override fun endSession() {
        try {
            serviceProvider()?.endSession()
        } catch (err: Exception) {
            Log.w(TAG, "endSession via Shizuku failed", err)
        }
    }

    override fun forgetAllPairings() {
        try {
            serviceProvider()?.forgetAllPairings()
        } catch (err: Exception) {
            Log.w(TAG, "forgetAllPairings via Shizuku failed", err)
        }
    }

    override fun pairingState(): String {
        return try {
            serviceProvider()?.pairingState ?: "UNPAIRED"
        } catch (err: Exception) {
            Log.d(TAG, "pairingState via Shizuku failed", err)
            "UNPAIRED"
        }
    }

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
