package me.trinitrix.mirax

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import me.trinitrix.mirax.session.PrivilegeReport
import rikka.shizuku.Shizuku
import java.io.IOException

/**
 * Environment adapter that observes Shizuku and the helper without starting them.
 *
 * Never installs, starts, or keeps Shizuku alive. Never spawns the helper when a
 * privilege path disappears.
 */
object PrivilegeProbe {
    private const val TAG = "MiraxPrivilege"
    private const val HELPER_SOCKET_NAME = "mirax-helper"
    private const val REQUEST_CODE = 0x4D58

    /**
     * Observe current privilege paths.
     *
     * @param context Application or activity context (reserved for future binders).
     * @return Privilege report for the Mirax session.
     */
    @Suppress("UNUSED_PARAMETER")
    fun probe(context: Context): PrivilegeReport {
        val shizuku = probeShizuku()
        return PrivilegeReport(
            shizukuServiceRunning = shizuku.serviceRunning,
            shizukuAuthorized = shizuku.authorized,
            helperRunning = probeHelper(),
        )
    }

    /**
     * Ask Shizuku for permission when the session says this stay should request once.
     *
     * @param context Activity context; unused by Shizuku.requestPermission but kept
     *     for a stable host-facing signature.
     */
    @Suppress("UNUSED_PARAMETER")
    fun requestShizukuPermission(context: Context) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.i(TAG, "Shizuku binder unavailable; skip permission request")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (err: IllegalStateException) {
            Log.w(TAG, "Shizuku permission request failed", err)
        } catch (err: RuntimeException) {
            Log.w(TAG, "Shizuku permission request failed", err)
        }
    }

    /**
     * Ask a running helper to stop after Shizuku takes exclusive WFD ownership.
     *
     * Does not start a replacement helper if the socket is already gone.
     */
    fun requestStopHelper() {
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(HELPER_SOCKET_NAME))
                socket.outputStream.write("STOP\n".toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            }
        } catch (err: IOException) {
            Log.i(TAG, "Helper stop signal not delivered (helper may already be gone)", err)
        }
    }

    private data class ShizukuProbe(
        val serviceRunning: Boolean,
        val authorized: Boolean,
    )

    private fun probeShizuku(): ShizukuProbe {
        return try {
            if (!Shizuku.pingBinder()) {
                return ShizukuProbe(serviceRunning = false, authorized = false)
            }
            val authorized =
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ShizukuProbe(serviceRunning = true, authorized = authorized)
        } catch (err: IllegalStateException) {
            Log.w(TAG, "Shizuku probe failed", err)
            ShizukuProbe(serviceRunning = false, authorized = false)
        } catch (err: RuntimeException) {
            Log.w(TAG, "Shizuku probe failed", err)
            ShizukuProbe(serviceRunning = false, authorized = false)
        }
    }

    private fun probeHelper(): Boolean {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(HELPER_SOCKET_NAME))
                true
            }
        } catch (err: IOException) {
            Log.d(TAG, "Helper socket not reachable", err)
            false
        }
    }
}
