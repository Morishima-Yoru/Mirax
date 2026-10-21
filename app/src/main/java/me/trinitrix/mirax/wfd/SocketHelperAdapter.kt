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

/** WFD owner transport backed by the local helper socket. */
class SocketHelperAdapter : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.HELPER

    /** Advertises [command] through the helper socket. */
    override fun advertise(command: WfdAdvertiseCommand): Boolean {
        val line = "ADVERTISE	${sanitizeName(command.broadcastName)}	${WfdOwnerBridge.encodeModes(command.modes)}"
        return exchangeHelper(line) != null
    }

    /** Stops helper advertising. */
    override fun stopAdvertise() {
        sendHelperQuietly("STOP_ADVERTISE")
    }

    /** Returns whether the helper is listening. */
    override fun isListening(): Boolean = exchangeHelper("LISTENING") == "YES"

    /** Returns the current group state, or null if unavailable. */
    override fun groupState(): String? = exchangeHelper("GROUP")

    /** Ends the current WFD session. */
    override fun endSession() {
        sendHelperQuietly("END")
    }

    /** Forgets all saved WFD pairings. */
    override fun forgetAllPairings() {
        sendHelperQuietly("FORGET_PAIRINGS")
    }

    /** Returns the pairing state, defaulting to unpaired if unavailable. */
    override fun pairingState(): String {
        return when (val state = exchangeHelper("PAIRING_STATE")) {
            "UNPAIRED", "PAIRING", "PAIRED" -> state
            else -> "UNPAIRED"
        }
    }

    /** Returns the window-manager size for [displayId], or null on failure. */
    override fun wmSize(displayId: Int?): String? {
        val cmd = if (displayId == null) "WM_SIZE" else "WM_SIZE $displayId"
        return exchangeHelper(cmd, readAll = true)
    }

    /** Sends [command] without expecting a response. */
    private fun sendHelperQuietly(command: String) {
        exchangeHelper(command)
    }

    /** Exchanges [command] and reads one line or the entire response when [readAll] is true. */
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

    /** Removes protocol delimiters from [name]. */
    private fun sanitizeName(name: String): String = name.replace('	', ' ').replace('\n', ' ')

    companion object {
        private const val TAG = "SocketHelperAdapter"
    }
}
