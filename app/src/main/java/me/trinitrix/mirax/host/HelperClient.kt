package me.trinitrix.mirax.host

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A refused or missing helper endpoint; no command was sent.
 * @param cause original connect failure
 */
internal class HelperUnavailableException(cause: IOException) : IOException("Helper is not listening", cause)

/** Exchanges one newline-delimited command with the privileged helper. */
object HelperClient {
    private const val TAG = "MiraxHelperClient"
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mirax-helper-client-timeouts").apply { isDaemon = true }
    }

    /**
     * @param command command without the trailing newline
     * @param readAll whether the response may contain multiple lines
     * @param timeoutMs maximum wait for the entire exchange
     * @return the response from the helper
     * @throws IOException if the exchange fails or expires
     */
    @Throws(IOException::class)
    fun exchange(
        command: String,
        readAll: Boolean = false,
        timeoutMs: Int = Helper.COMMAND_TIMEOUT_MS,
    ): String {
        require(timeoutMs > 0) { "Helper timeout must be positive" }
        LocalSocket().use { socket ->
            val deadline = deadlines.schedule({
                try {
                    socket.close()
                } catch (err: IOException) {
                    Log.w(TAG, "closing timed-out exchange failed", err)
                }
            }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            try {
                try {
                    socket.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                } catch (err: IOException) {
                    // LocalSocket JNI throws plain IOException(strerror), not ErrnoException.
                    val message = err.message
                    if (message != null &&
                        (message == Os.strerror(OsConstants.ENOENT) ||
                            message == Os.strerror(OsConstants.ECONNREFUSED))
                    ) {
                        throw HelperUnavailableException(err)
                    }
                    throw err
                }
                socket.setSoTimeout(timeoutMs)
                socket.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                val response = socket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    if (readAll) reader.readText() else reader.readLine()
                }
                if (response.isNullOrEmpty()) throw IOException("Helper returned an empty response to $command")
                return response
            } catch (err: HelperUnavailableException) {
                throw err
            } catch (err: Exception) {
                throw IOException("Helper exchange failed or timed out: $command", err)
            } finally {
                deadline.cancel(false)
            }
        }
    }
}
