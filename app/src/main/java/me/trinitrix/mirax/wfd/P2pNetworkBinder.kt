package me.trinitrix.mirax.wfd

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.Socket

/**
 * Binds sockets to the Wi-Fi Direct P2P network so Android does not route
 * RFC 1918 traffic (such as 192.168.137.x) to default cellular or non-P2P networks.
 */
object P2pNetworkBinder {
    private const val TAG = "MiraxP2pBinder"

    fun getActiveP2pInterface(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                val name = ni.name ?: continue
                if (name.contains("p2p", ignoreCase = true) && ni.isUp) {
                    return name
                }
            }
        } catch (err: Exception) {
            Log.w(TAG, "failed getting network interfaces", err)
        }
        return null
    }

    private fun extractFd(target: Any): FileDescriptor? {
        // Try getFileDescriptor$() on Socket/DatagramSocket
        try {
            val m = target.javaClass.getMethod("getFileDescriptor$")
            m.isAccessible = true
            val fd = m.invoke(target) as? FileDescriptor
            if (fd != null && fd.valid()) return fd
        } catch (_: Exception) {}

        // Try getImpl() -> getFileDescriptor()
        try {
            val getImpl = target.javaClass.getDeclaredMethod("getImpl")
            getImpl.isAccessible = true
            val impl = getImpl.invoke(target)
            if (impl != null) {
                val m = impl.javaClass.getMethod("getFileDescriptor")
                m.isAccessible = true
                val fd = m.invoke(impl) as? FileDescriptor
                if (fd != null && fd.valid()) return fd

                // Or direct field fd
                val f = impl.javaClass.getDeclaredField("fd")
                f.isAccessible = true
                val fdDirect = f.get(impl) as? FileDescriptor
                if (fdDirect != null && fdDirect.valid()) return fdDirect
            }
        } catch (_: Exception) {}

        return null
    }

    fun bind(context: Context?, socket: Any) {
        val p2pIface = getActiveP2pInterface() ?: run {
            Log.w(TAG, "no active p2p interface found")
            return
        }

        val fd = extractFd(socket)
        if (fd == null || !fd.valid()) {
            Log.w(TAG, "extractFd returned null/invalid for $socket (bound=${(socket as? Socket)?.isBound})")
            return
        }

        // Try Libcore.os.setsockoptIfreq or Os.setsockoptIfreq
        var bound = false
        try {
            val libcoreClass = Class.forName("libcore.io.Libcore")
            val osObj = libcoreClass.getField("os").get(null)
            for (m in osObj.javaClass.methods) {
                if (m.name == "setsockoptIfreq" && m.parameterTypes.size == 4) {
                    m.isAccessible = true
                    m.invoke(osObj, fd, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, p2pIface)
                    Log.i(TAG, "successfully bound socket to $p2pIface via Libcore.os.setsockoptIfreq")
                    bound = true
                    break
                }
            }
        } catch (err: Exception) {
            Log.w(TAG, "Libcore bind failed", err)
        }

        if (!bound) {
            for (m in Os::class.java.methods) {
                if (m.name.startsWith("setsockopt") && m.parameterTypes.contains(String::class.java)) {
                    try {
                        m.isAccessible = true
                        m.invoke(null, fd, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, p2pIface)
                        Log.i(TAG, "successfully bound socket to $p2pIface via Os.${m.name}")
                        bound = true
                        break
                    } catch (err: Exception) {
                        Log.w(TAG, "Os.${m.name} bind failed", err)
                    }
                }
            }
        }

        if (!bound) {
            Log.w(TAG, "failed to bind socket to $p2pIface (no method matched)")
        }
    }
}
