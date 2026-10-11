package me.trinitrix.mirax.wfd

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class SocketHelperAdapter : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.HELPER

    override fun advertise(command: WfdAdvertiseCommand): Boolean {
        val line = "ADVERTISE	${sanitizeName(command.broadcastName)}	${WfdOwnerBridge.encodeModes(command.modes)}"
        return exchangeHelper(line) != null
    }

    override fun stopAdvertise() {
        sendHelperQuietly("STOP_ADVERTISE")
    }

    override fun isListening(): Boolean = exchangeHelper("LISTENING") == "YES"

    override fun groupState(): String? = exchangeHelper("GROUP")

    override fun endSession() {
        sendHelperQuietly("END")
    }

    override fun forgetAllPairings() {
        sendHelperQuietly("FORGET_PAIRINGS")
    }

    override fun pairingState(): String {
        return when (val state = exchangeHelper("PAIRING_STATE")) {
            "UNPAIRED", "PAIRING", "PAIRED" -> state
            else -> "UNPAIRED"
        }
    }

    override fun wmSize(displayId: Int?): String? {
        val cmd = if (displayId == null) "WM_SIZE" else "WM_SIZE $displayId"
        return exchangeHelper(cmd, readAll = true)
    }

    private fun sendHelperQuietly(command: String) {
        exchangeHelper(command)
    }

    private fun exchangeHelper(command: String, readAll: Boolean = false): String? {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                socket.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    if (readAll) reader.readText() else reader.readLine()
                }
            }
        } catch (err: Exception) {
            Log.d(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun sanitizeName(name: String): String = name.replace('	', ' ').replace('\n', ' ')

    companion object {
        private const val TAG = "SocketHelperAdapter"
    }
}
