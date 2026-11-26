package me.trinitrix.mirax.host

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
        return exchangeBounded(command, readAll, timeoutMs, maxSingleLineBytes = null)
    }

    /**
     * Bounded single-line variant of [exchange]: caps the first response line in bytes.
     *
     * @param command command without the trailing newline
     * @param timeoutMs maximum wait for the entire exchange
     * @param maxSingleLineBytes maximum bytes accepted before the line terminator,
     *     counting raw UTF-8 bytes and excluding the terminator; null keeps legacy behavior
     * @return the response from the helper
     * @throws IOException if the exchange fails, expires, or exceeds the bound
     */
    @Throws(IOException::class)
    fun exchangeBounded(
        command: String,
        timeoutMs: Int = Helper.COMMAND_TIMEOUT_MS,
        maxSingleLineBytes: Int?,
    ): String {
        return exchangeBounded(command, false, timeoutMs, maxSingleLineBytes)
    }

    @Throws(IOException::class)
    private fun exchangeBounded(
        command: String,
        readAll: Boolean,
        timeoutMs: Int,
        maxSingleLineBytes: Int?,
    ): String {
        if (maxSingleLineBytes != null) require(maxSingleLineBytes > 0) { "Bound must be positive" }
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
                val response = if (maxSingleLineBytes != null && !readAll) {
                    readBoundedLine(socket.inputStream, maxSingleLineBytes, command)
                } else {
                    socket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                        if (readAll) reader.readText() else reader.readLine()
                    }
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

    /**
     * Reads one `\n`-terminated line capped at `maxBytes` raw bytes.
     *
     * Stops at the first `\n` and strips one trailing `\r`; a missing terminator
     * or a line longer than the bound fails closed without decoding the excess.
     *
     * @param input helper response stream
     * @param maxBytes maximum raw UTF-8 bytes accepted before the terminator
     * @param command command label for diagnostics
     * @return decoded line without the terminator
     * @throws IOException when the line is missing, truncated, or oversized
     */
    @Throws(IOException::class)
    internal fun readBoundedLine(input: InputStream, maxBytes: Int, command: String): String {
        val frame = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) throw IOException("Helper truncated response to $command")
            if (value == '\n'.code) break
            if (frame.size() >= maxBytes) throw IOException("Helper response exceeded limit for $command")
            frame.write(value)
        }
        var line = frame.toString(StandardCharsets.UTF_8.name())
        if (line.endsWith("\r")) line = line.substring(0, line.length - 1)
        return line
    }
}
