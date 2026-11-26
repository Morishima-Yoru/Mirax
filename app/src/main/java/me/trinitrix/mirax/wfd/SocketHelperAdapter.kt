package me.trinitrix.mirax.wfd

import android.util.Log
import me.trinitrix.mirax.host.HelperClient
import me.trinitrix.mirax.host.HelperUnavailableException
import me.trinitrix.mirax.host.helperPathsAllowed
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import java.io.IOException

class SocketHelperAdapter(
    private val groupExchange: ((String) -> String?)? = null,
) : WfdOwnerTransport {
    override val owner: WfdOwner = WfdOwner.HELPER

    override fun advertise(command: WfdAdvertiseCommand): Boolean {
        val line = "ADVERTISE\t${sanitizeName(command.broadcastName)}\t${WfdOwnerBridge.encodeModes(command.modes)}"
        return exchangeHelper(line) == "OK"
    }

    override fun stopAdvertise() {
        sendHelperQuietly("STOP_ADVERTISE")
    }

    override fun isListening(): Boolean = exchangeHelper("LISTENING") == "YES"

    override fun groupState(): String? = exchangeHelper("GROUP")

    override fun groupSnapshotV2(): OwnerGroupSnapshot? =
        OwnerGroupSnapshot.parse(owner, exchangeGroup("GROUP_V2"))

    override fun endSessionIfEpoch(snapshot: OwnerGroupSnapshot): Boolean {
        if (!snapshot.canEndBy(owner)) return false
        return exchangeGroup("END_V2 ${snapshot.epoch}") == "QUEUED"
    }

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
        if (exchangeHelper(command) != "OK") Log.w(TAG, "helper command failed: $command")
    }

    private fun exchangeGroup(command: String): String? {
        if (groupExchange != null) {
            return try {
                groupExchange.invoke(command)
            } catch (err: Exception) {
                Log.w(TAG, "V2 helper exchange unavailable or outcome unknown", err)
                null
            }
        }
        if (!helperPathsAllowed()) return null
        return try {
            HelperClient.exchangeBounded(command, GROUP_IO_TIMEOUT_MS, GROUP_RESPONSE_LIMIT).also { response ->
                if (response.startsWith("ERR")) Log.w(TAG, "helper rejected $command: $response")
            }
        } catch (err: HelperUnavailableException) {
            Log.d(TAG, "Helper absent: $command", err)
            null
        } catch (err: IOException) {
            Log.w(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun exchangeHelper(command: String, readAll: Boolean = false): String? {
        if (!helperPathsAllowed()) return null
        return try {
            HelperClient.exchange(command, readAll, GROUP_IO_TIMEOUT_MS).also { response ->
                if (response.startsWith("ERR")) Log.w(TAG, "helper rejected $command: $response")
            }
        } catch (err: HelperUnavailableException) {
            Log.d(TAG, "Helper absent: $command", err)
            null
        } catch (err: IOException) {
            Log.w(TAG, "helper exchange failed: $command", err)
            null
        }
    }

    private fun sanitizeName(name: String): String = name.replace('\t', ' ').replace('\n', ' ')

    companion object {
        private const val TAG = "SocketHelperAdapter"
        private const val GROUP_IO_TIMEOUT_MS = 750
        private const val GROUP_RESPONSE_LIMIT = 512
    }
}
