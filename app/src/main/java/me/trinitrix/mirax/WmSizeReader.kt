package me.trinitrix.mirax

import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import me.trinitrix.mirax.session.WmSizeReading
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Host-side `wm size` reader. Runs only when the session allows ([canReadWmSize]).
 * Prefers Shizuku's elevated process; falls back to the helper socket. The app
 * process is never shell.
 */
object WmSizeReader {
    private const val TAG = "MiraxWmSize"
    private const val HELPER_SOCKET_NAME = "mirax-helper"

    /**
     * Read plain `wm size` (no display id) for provisioning.
     */
    fun readPlain(): WmSizeReading? = read(displayId = null)

    /**
     * Read `wm size` for a specific display id (Mirax window's display).
     */
    fun readForDisplay(displayId: Int): WmSizeReading? = read(displayId = displayId)

    private fun read(displayId: Int?): WmSizeReading? {
        return readViaShizuku(displayId) ?: readViaHelper(displayId)
    }

    private fun readViaShizuku(displayId: Int?): WmSizeReading? {
        return try {
            if (!Shizuku.pingBinder()) {
                return null
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val args = if (displayId == null) {
                arrayOf("wm", "size")
            } else {
                arrayOf("wm", "size", "-d", displayId.toString())
            }
            val process = newShizukuProcess(args) ?: return null
            val inputStream = processInputStream(process) ?: return null
            val output = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            waitForProcess(process, 5, TimeUnit.SECONDS)
            WmSizeParser.parse(output)
        } catch (err: Exception) {
            Log.w(TAG, "wm size via Shizuku failed", err)
            null
        }
    }

    private fun readViaHelper(displayId: Int?): WmSizeReading? {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(HELPER_SOCKET_NAME))
                val command = if (displayId == null) {
                    "WM_SIZE\n"
                } else {
                    "WM_SIZE $displayId\n"
                }
                socket.outputStream.write(command.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                val output = BufferedReader(InputStreamReader(socket.inputStream)).use { it.readText() }
                WmSizeParser.parse(output)
            }
        } catch (err: Exception) {
            Log.d(TAG, "wm size via helper failed", err)
            null
        }
    }

    /**
     * Shizuku 13 keeps [Shizuku.newProcess] private; invoke it reflectively for a
     * one-shot shell query. A dedicated user-service can replace this later.
     */
    private fun newShizukuProcess(args: Array<String>): Any? {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, args, null, null)
    }

    private fun processInputStream(process: Any): InputStream? {
        return try {
            process.javaClass.getMethod("getInputStream").invoke(process) as? InputStream
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku process has no input stream", err)
            null
        }
    }

    private fun waitForProcess(process: Any, timeout: Long, unit: TimeUnit) {
        try {
            val waitFor = process.javaClass.methods.firstOrNull { method ->
                method.name == "waitFor" && method.parameterTypes.size == 2
            }
            if (waitFor != null) {
                val ok = waitFor.invoke(process, timeout, unit) as Boolean
                if (!ok) {
                    process.javaClass.getMethod("destroy").invoke(process)
                }
                return
            }
            process.javaClass.getMethod("waitFor").invoke(process)
        } catch (err: Exception) {
            Log.w(TAG, "Shizuku process wait failed", err)
        }
    }
}
