package me.trinitrix.mirax.shell

import android.os.Handler
import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.wfd.PrimarySinkBeacon
import kotlin.system.exitProcess

/**
 * Shizuku user-service that owns WFD advertise and `wm size` under shell UID.
 *
 * The Mirax app process only binds this service; it never calls
 * `WifiP2pManager.setWfdInfo` itself.
 */
class MiraxShellUserService : IMiraxShellService.Stub() {
    private val beacon = PrimarySinkBeacon(Looper.getMainLooper())
    private val beaconReady: Boolean = run {
        val ok = beacon.initialize()
        if (!ok) {
            Log.e(TAG, "PrimarySinkBeacon initialize failed")
        }
        ok
    }

    override fun advertise(broadcastName: String?, modesCsv: String?) {
        if (!beaconReady) {
            Log.e(TAG, "advertise ignored; beacon not initialized")
            return
        }
        Log.i(TAG, "advertise name=\"$broadcastName\" modes=$modesCsv")
        beacon.startAdvertising(broadcastName.orEmpty())
    }

    override fun stopAdvertise() {
        Log.i(TAG, "stopAdvertise")
        beacon.stopAdvertising()
    }

    override fun endSession() {
        Log.i(TAG, "endSession")
        beacon.endSession()
    }

    override fun groupState(): String = beacon.groupState()

    override fun wmSize(displayId: Int): String = Helper.wmSize(displayId)

    override fun forgetAllPairings() {
        Log.i(TAG, "forgetAllPairings")
        beacon.forgetAllPairings()
    }

    override fun getPairingState(): String = beacon.getPairingState().name

    override fun isListening(): Boolean = beaconReady && beacon.isListening

    override fun destroy() {
        Log.i(TAG, "destroy")
        beacon.stopAdvertising()
        // Queued behind the stop on the same looper so the P2P teardown is sent first.
        Handler(Looper.getMainLooper()).post { exitProcess(0) }
    }

    companion object {
        private const val TAG = "MiraxShellService"
    }
}
