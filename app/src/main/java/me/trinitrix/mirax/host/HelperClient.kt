package me.trinitrix.mirax.host

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exchanges one newline-delimited command with the privileged helper. */
object HelperClient {
    private const val TAG = "MiraxHelperClient"
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mirax-helper-client-timeouts").apply { isDaemon = true }
    }

    /**
     * @param command command without the trailing newline
     * @param readAll whether the response may contain multiple lines
     * @return the response from the helper
     * @throws IOException if the exchange fails or expires
     */
    @Throws(IOException::class)
    fun exchange(command: String, readAll: Boolean = false): String {
        LocalSocket().use { socket ->
            val deadline = deadlines.schedule({
                try {
                    socket.close()
                } catch (err: IOException) {
                    Log.w(TAG, "closing timed-out exchange failed", err)
                }
            }, Helper.COMMAND_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            try {
                socket.setSoTimeout(Helper.COMMAND_TIMEOUT_MS)
                socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                socket.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                val response = socket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    if (readAll) reader.readText() else reader.readLine()
                }
                if (response.isNullOrEmpty()) throw IOException("Helper returned an empty response to $command")
                return response
            } catch (err: Exception) {
                throw IOException("Helper exchange failed or timed out: $command", err)
            } finally {
                deadline.cancel(false)
            }
        }
    }
}
