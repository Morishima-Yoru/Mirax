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
    private val lastProbeTime = AtomicReference<Long>(0L)
    private const val PROBE_COOLDOWN_MS = 30_000L // 30 seconds between probes

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
     * Check if enough time has passed since the last probe to allow a new one.
     */
    private fun canProbe(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastProbeTime.get()
        return (now - last) >= PROBE_COOLDOWN_MS
    }

    /**
     * Update the last probe time to now.
     */
    private fun updateProbeTime() {
        lastProbeTime.set(System.currentTimeMillis())
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
            if (!canProbe()) {
                return@Thread
            }
            updateProbeTime()
            
            val rooted = available()
            val previous = rootCached.getAndSet(rooted)
            var helperChanged = false
            if (rooted) {
                helperChanged = ensureStartedBlocking(appContext)
            }
            if (previous != rooted || helperChanged) {
                mainHandler.post(onChanged)
            }
        }, "mirax-root").start()
    }

    /**
     * Ensure the helper socket belongs to a root process when `su` is available.
     * Blocking; intended for the root worker thread.
     */
    private fun ensureStartedBlocking(context: Context): Boolean {
        if (!availableCached() && !available()) {
            return false
        }
        if (!starting.compareAndSet(false, true)) {
            return false
        }
        var helperChanged = false
        return try {
            if (PrivilegeProbe.helperUid() == 0) {
                return false
            }
            if (PrivilegeProbe.isHelperRunning()) {
                Log.i(TAG, "replacing non-root helper with root helper")
                PrivilegeProbe.requestStopHelper()
                helperChanged = true
                var stopped = false
                repeat(10) {
                    if (!stopped) {
                        if (!PrivilegeProbe.isHelperRunning()) {
                            stopped = true
                        } else {
                            Thread.sleep(200)
                        }
                    }
                }
                if (!stopped) {
                    Log.w(TAG, "helper socket remained up after stop request")
                    return helperChanged
                }
            }
            val apk = context.applicationInfo.sourceDir
            val log = File(context.cacheDir, "mirax-helper-root.log").absolutePath
            val remote =
                "CLASSPATH=$apk app_process /system/bin " +
                    "${Helper::class.java.name} </dev/null >$log 2>&1 &"
            Log.i(TAG, "starting helper via su")
            val process = ProcessBuilder("su", "-c", remote)
                .redirectErrorStream(true)
                .start()
            helperChanged = true
            process.waitFor(3, TimeUnit.SECONDS)
            var ready = false
            repeat(40) {
                if (!ready) {
                    if (PrivilegeProbe.helperUid() == 0) {
                        ready = true
                    } else {
                        Thread.sleep(250)
                    }
                }
            }
            if (!ready) {
                Log.w(TAG, "root helper did not report UID 0; log=$log")
            }
            helperChanged
        } catch (err: Exception) {
            Log.w(TAG, "su helper start failed", err)
            helperChanged
        } finally {
            starting.set(false)
        }
    }
}
