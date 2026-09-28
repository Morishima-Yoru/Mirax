package me.trinitrix.mirax.shell

import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.wfd.PrimarySinkBeacon

/**
 * Shizuku user-service that owns WFD advertise under shell UID.
 *
 * The Mirax app process only binds this service; it never calls
 * `WifiP2pManager.setWfdInfo` itself.
 */
class MiraxShellUserService : IMiraxShellService.Stub() {
    private val beacon: PrimarySinkBeacon =
        PrimarySinkBeacon(Looper.getMainLooper()).also { sink ->
            if (!sink.initialize()) {
                Log.e(TAG, "PrimarySinkBeacon initialize failed")
            }
        }

    override fun advertise(broadcastName: String?, modesCsv: String?) {
        Log.i(TAG, "advertise name=\"$broadcastName\" modes=$modesCsv")
        beacon.startAdvertising(broadcastName.orEmpty())
    }

    override fun stopAdvertise() {
        Log.i(TAG, "stopAdvertise")
        beacon.stopAdvertising()
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        beacon.stopAdvertising()
        System.exit(0)
    }

    companion object {
        private const val TAG = "MiraxShellService"
    }
}
