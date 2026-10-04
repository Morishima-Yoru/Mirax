package me.trinitrix.mirax.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.trinitrix.mirax.helper.Helper
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts the privileged helper under `su` (UID 0) on rooted devices.
 *
 * Uses the installed APK as `CLASSPATH` so no separate jar push is required.
 * The helper listens on [Helper.SOCKET_NAME] the same way a shell helper would.
 * Probes and starts never run on the caller's thread for more than a cache hit.
 */
object RootHelper {
    private const val TAG = "MiraxRoot"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)
    private val rootCached = AtomicReference<Boolean?>(null)

    /** Last known root probe result; false until the first async probe finishes. */
    fun availableCached(): Boolean = rootCached.get() == true

    /**
     * Whether `su -c id` reports UID 0. Blocking; call off the main thread.
     */
    fun available(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (err: Exception) {
            Log.d(TAG, "root probe failed", err)
            false
        }
    }

    /**
     * Probe root (and start the helper when needed) on a worker thread.
     *
     * Args:
     *     context: Application context.
     *     onChanged: Called on the main thread when the cache or helper state
     *     may have changed and the host should re-probe privileges.
     */
    fun refreshAsync(context: Context, onChanged: () -> Unit) {
        val appContext = context.applicationContext
        Thread({
            val rooted = available()
            val previous = rootCached.getAndSet(rooted)
            var started = false
            if (rooted && !PrivilegeProbe.isHelperRunning()) {
                started = ensureStartedBlocking(appContext)
            }
            if (previous != rooted || started) {
                mainHandler.post(onChanged)
            }
        }, "mirax-root").start()
    }

    /**
     * Start [Helper.main] via `su` when the helper socket is not already up.
     * Blocking; intended for the root worker thread.
     */
    private fun ensureStartedBlocking(context: Context): Boolean {
        if (PrivilegeProbe.isHelperRunning()) {
            return true
        }
        if (!availableCached() && !available()) {
            return false
        }
        if (!starting.compareAndSet(false, true)) {
            return PrivilegeProbe.isHelperRunning()
        }
        return try {
            val apk = context.applicationInfo.sourceDir
            val log = File(context.cacheDir, "mirax-helper-root.log").absolutePath
            val remote =
                "CLASSPATH=$apk app_process /system/bin " +
                    "${Helper::class.java.name} </dev/null >$log 2>&1 &"
            Log.i(TAG, "starting helper via su")
            val process = ProcessBuilder("su", "-c", remote)
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS)
            var ready = false
            repeat(10) {
                if (PrivilegeProbe.isHelperRunning()) {
                    ready = true
                    return@repeat
                }
                Thread.sleep(200)
            }
            if (!ready) {
                Log.w(TAG, "helper socket not up after su start; log=$log")
            }
            ready
        } catch (err: Exception) {
            Log.w(TAG, "su helper start failed", err)
            false
        } finally {
            starting.set(false)
        }
    }
}
