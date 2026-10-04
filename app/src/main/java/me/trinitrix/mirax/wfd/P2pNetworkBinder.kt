package me.trinitrix.mirax.wfd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Binds sockets onto the Wi-Fi Direct P2P path so Android does not route
 * RFC 1918 Miracast traffic to the default STA/cellular network.
 *
 * Prefer binding to the local P2P IPv4 (works for a normal app UID). Fall back
 * to `SO_BINDTODEVICE` for shell/Shizuku where that call is permitted.
 */
object P2pNetworkBinder {
    private const val TAG = "MiraxP2pBinder"

    fun getActiveP2pInterface(): String? {
        try {
            var p2pInterfaceWithAddress: String? = null
            var upP2pInterface: String? = null
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                val name = ni.name ?: continue
                if (!name.contains("p2p", ignoreCase = true)) {
                    continue
                }
                val hasIpv4Address = ni.inetAddresses.toList().any {
                    it is Inet4Address && !it.isLoopbackAddress
                }
                if (hasIpv4Address && ni.isUp) {
                    return name
                }
                if (hasIpv4Address) {
                    p2pInterfaceWithAddress = name
                } else if (ni.isUp && upP2pInterface == null) {
                    upP2pInterface = name
                }
            }
            if (p2pInterfaceWithAddress != null) {
                Log.i(TAG, "using P2P interface with IPv4 before link-up: $p2pInterfaceWithAddress")
                return p2pInterfaceWithAddress
            }
            return upP2pInterface
        } catch (err: Exception) {
            Log.w(TAG, "failed getting network interfaces", err)
        }
        return null
    }

    /** Local IPv4 on the active P2P interface, or null when none is up yet. */
    fun localP2pAddress(): Inet4Address? {
        val name = getActiveP2pInterface() ?: return null
        return try {
            val ni = NetworkInterface.getByName(name) ?: return null
            ni.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
        } catch (err: Exception) {
            Log.w(TAG, "localP2pAddress failed", err)
            null
        }
    }

    private fun extractFd(target: Any): FileDescriptor? {
        try {
            val m = target.javaClass.getMethod("getFileDescriptor$")
            m.isAccessible = true
            val fd = m.invoke(target) as? FileDescriptor
            if (fd != null && fd.valid()) return fd
        } catch (_: Exception) {
        }

        try {
            val getImpl = target.javaClass.getDeclaredMethod("getImpl")
            getImpl.isAccessible = true
            val impl = getImpl.invoke(target)
            if (impl != null) {
                val m = impl.javaClass.getMethod("getFileDescriptor")
                m.isAccessible = true
                val fd = m.invoke(impl) as? FileDescriptor
                if (fd != null && fd.valid()) return fd

                val f = impl.javaClass.getDeclaredField("fd")
                f.isAccessible = true
                val fdDirect = f.get(impl) as? FileDescriptor
                if (fdDirect != null && fdDirect.valid()) return fdDirect
            }
        } catch (_: Exception) {
        }

        return null
    }

    fun bind(context: Context?, socket: Any): Boolean {
        val local = localP2pAddress()
        if (local != null && socket is Socket && !socket.isBound) {
            try {
                socket.bind(InetSocketAddress(local, 0))
                Log.i(TAG, "bound socket to local P2P address $local")
                bindConnectivityManager(context, socket)
                return true
            } catch (err: Exception) {
                Log.w(TAG, "bind to local P2P address failed", err)
            }
        }

        val p2pIface = getActiveP2pInterface() ?: run {
            Log.w(TAG, "no active p2p interface found")
            return false
        }

        val fd = extractFd(socket)
        if (fd == null || !fd.valid()) {
            Log.w(TAG, "extractFd returned null/invalid for $socket (bound=${(socket as? Socket)?.isBound})")
            return false
        }

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
        return bound
    }

    private fun bindConnectivityManager(context: Context?, socket: Socket) {
        if (context == null) {
            return
        }
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val network = findP2pNetwork(cm) ?: return
            network.bindSocket(socket)
            Log.i(TAG, "Network.bindSocket to P2P network")
        } catch (err: Exception) {
            Log.d(TAG, "ConnectivityManager.bindSocket unavailable", err)
        }
    }

    private fun findP2pNetwork(cm: ConnectivityManager): Network? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue
            }
            val lp = cm.getLinkProperties(network) ?: continue
            val iface = lp.interfaceName ?: continue
            if (iface.contains("p2p", ignoreCase = true)) {
                return network
            }
        }
        return null
    }
}
