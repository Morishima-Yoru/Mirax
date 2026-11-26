package me.trinitrix.mirax

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * Runs an action against the current scenario activity without waiting for global idle.
 * An action already running on the main thread cannot be cancelled on timeout.
 * @param scenario Active activity scenario.
 * @param timeoutMillis Maximum wait, shortened only for the timeout self-check.
 * @param action Action to run on the main thread.
 */
internal fun <T : Activity> awaitActivityAction(
    scenario: ActivityScenario<T>,
    timeoutMillis: Long = 5_000,
    action: (T) -> Unit,
) {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Activity action must be awaited off the main thread" }
    val completed = CountDownLatch(1)
    val pending = AtomicBoolean(true)
    val handler = Handler(Looper.getMainLooper())
    var failure: Throwable? = null
    val task = Runnable {
        if (pending.compareAndSet(true, false)) {
            try {
                scenario.onActivity { action(it) }
            } catch (error: Throwable) {
                Log.e(WindowEvidence.LOG_TAG, "Activity action threw", error)
                failure = error
            } finally {
                completed.countDown()
            }
        }
    }
    check(handler.post(task)) { "Main thread rejected activity action" }
    try {
        check(completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "Activity action timed out after ${timeoutMillis}ms" }
    } catch (error: Throwable) {
        if (pending.compareAndSet(true, false)) handler.removeCallbacks(task)
        if (error is InterruptedException) Thread.currentThread().interrupt()
        Log.e(WindowEvidence.LOG_TAG, "Bounded activity action failed", error)
        throw error
    }
    failure?.let { throw it }
}

/**
 * Captures the currently tested activity window to a PNG file.
 *
 * Uses [PixelCopy.request] on the activity window only, never full-screen
 * automation, so no other app can leak into the evidence. The output lives
 * under the target app external files dir, which the parent runner pulls.
 */
object WindowEvidence {

    /** Log tag shared with the standby probe tests. */
    const val LOG_TAG = "MiraxStandbyProbe"

    /** Maximum seconds to wait for the PixelCopy callback on the instrumentation thread. */
    private const val CAPTURE_TIMEOUT_SECONDS = 5L

    /**
     * Result of one window capture.
     *
     * @param file PNG file under the target app external files dir.
     * @param widthPx Captured window width.
     * @param heightPx Captured window height.
     * @param pixelCopyResult Raw PixelCopy result code, always SUCCESS here.
     * @param bytesWritten PNG file size in bytes.
     */
    data class Capture(
        val file: File,
        val widthPx: Int,
        val heightPx: Int,
        val pixelCopyResult: Int,
        val bytesWritten: Long,
    )

    /**
     * Captures the window of the activity currently driven by [scenario].
     *
     * Must be called on the instrumentation thread after the attached draw is
     * observed. The PixelCopy callback only records the result; the bitmap is
     * compressed on the instrumentation thread after success. On timeout the
     * bitmap is left for GC because PixelCopy may still use it.
     *
     * @param scenario Active scenario owning the window under test.
     * @param label Short label embedded in the unique filename.
     * @return Capture metadata including the exact absolute file path.
     */
    fun <T : Activity> capture(scenario: ActivityScenario<T>, label: String): Capture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var targetWindow: Window? = null
        var windowWidth = 0
        var windowHeight = 0
        awaitActivityAction(scenario) { activity ->
            val window = activity.window
            targetWindow = window
            val decor = window?.decorView
            windowWidth = decor?.width ?: 0
            windowHeight = decor?.height ?: 0
        }
        val window = targetWindow
        if (window == null) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=NULL_WINDOW")
            throw AssertionError("Window evidence unavailable: activity window was null.")
        }
        if (windowWidth <= 0 || windowHeight <= 0) {
            Log.e(
                LOG_TAG,
                "windowEvidence label=" + label + " result=NO_SIZE width=" + windowWidth +
                    " height=" + windowHeight,
            )
            throw AssertionError(
                "Window evidence unavailable: decor size was " + windowWidth + "x" + windowHeight + ".",
            )
        }
        val baseDir = instrumentation.targetContext.getExternalFilesDir(null)
        if (baseDir == null) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=NO_EXTERNAL_DIR")
            throw AssertionError("Window evidence unavailable: target external files dir was null.")
        }
        val outputDir = File(baseDir, "window-evidence")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=MKDIR_FAILED")
            throw AssertionError("Window evidence unavailable: cannot create window-evidence dir.")
        }
        val safe = label.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(32).ifEmpty { "probe" }
        val outputFile = try {
            File.createTempFile("standby-render-" + safe + "-", ".png", outputDir)
        } catch (e: IOException) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=TEMP_FILE_FAILED", e)
            throw AssertionError("Window evidence unavailable: cannot create temp PNG file.", e)
        }
        val bitmap = Bitmap.createBitmap(windowWidth, windowHeight, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val copyResult = AtomicInteger(-1)
        val handler = Handler(Looper.getMainLooper())
        try {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    copyResult.set(result)
                    latch.countDown()
                },
                handler,
            )
        } catch (e: IllegalArgumentException) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=REQUEST_REJECTED", e)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            throw AssertionError("Window evidence unavailable: PixelCopy rejected the window.", e)
        }
        val completed = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            Log.e(LOG_TAG, "windowEvidence label=" + label + " result=TIMEOUT")
            throw AssertionError("Window evidence unavailable: PixelCopy timed out after 5s.")
        }
        try {
            if (copyResult.get() != PixelCopy.SUCCESS) {
                Log.e(LOG_TAG, "windowEvidence label=" + label + " result=" + copyResult.get())
                throw AssertionError(
                    "Window evidence unavailable: PixelCopy result=" + copyResult.get() + ".",
                )
            }
            try {
                FileOutputStream(outputFile).use { stream ->
                    val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.flush()
                    if (!compressed) {
                        throw IOException("Bitmap compression returned false.")
                    }
                }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "windowEvidence label=" + label + " result=WRITE_FAILED", e)
                throw AssertionError("Window evidence unavailable: PNG write failed.", e)
            }
            val bytes = outputFile.length()
            if (bytes <= 0) {
                Log.e(LOG_TAG, "windowEvidence label=" + label + " result=EMPTY_PNG")
                throw AssertionError("Window evidence unavailable: PNG file is empty.")
            }
            Log.i(
                LOG_TAG,
                "windowEvidence label=" + label + " result=SUCCESS path=" + outputFile.absolutePath +
                    " width=" + windowWidth + " height=" + windowHeight + " bytes=" + bytes,
            )
            return Capture(outputFile, windowWidth, windowHeight, copyResult.get(), bytes)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}

/** Observes only the isolated App's root-confirmation Toast; no accessibility event or arbitrary text escapes. */
internal object RootToastPrompt {
    private const val ORIGIN_PACKAGE = "me.trinitrix.mirax.vanillaprobe"
    private const val TOAST_CLASS = "android.widget.Toast"
    private const val ROOT_MS = 2_000L

    /** Qualified scalar evidence; uptime and elapsedRealtime remain separate clock domains. */
    data class Evidence(
        val inputBeforeElapsedMs: Long,
        val inputAfterElapsedMs: Long,
        val inputBeforeUptimeMs: Long,
        val inputAfterUptimeMs: Long,
        val eventUptimeMs: Long,
        val observedUptimeMs: Long,
        val observedElapsedMs: Long,
    )

    /** Private SDK-return brackets, not prompt evidence by themselves. */
    private data class Input(val beforeElapsed: Long, val afterElapsed: Long, val beforeUptime: Long, val afterUptime: Long)

    /**
     * Collects before one strict public Back, then returns only a timely owned presentation notification.
     * @param device Existing serial UiDevice.
     * @param packageName Locked isolated App package, never the renderer package.
     * @param prompt Exact resource read from the locked Main.
     * @param rootPhaseStart Existing C phase origin, or null for B2's actual-input origin.
     * @param beforeInput Existing prerequisite deadline check; it never injects input.
     * @return Qualified scalar event/input brackets, never a recycled event or nullable fallback.
     */
    fun observe(
        device: UiDevice,
        packageName: String,
        prompt: String,
        rootPhaseStart: Long?,
        beforeInput: () -> Unit,
    ): Evidence {
        check(packageName == ORIGIN_PACKAGE && prompt.isNotEmpty()) { "ROOT_TOAST fixture=INCOMPLETE isolated prompt required" }
        checkClassifier()
        beforeInput()
        val pending = AtomicBoolean(true)
        val inputPending = AtomicBoolean(true)
        val abandoned = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val inputStart = AtomicLong(-1L)
        val input = AtomicReference<Input?>(null)
        val matched = AtomicReference<Evidence?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val completed = CountDownLatch(1)

        /** Retains the first original error and identity-safe secondary failures. @param error Original input/wait error or payload-free collection marker. */
        fun record(error: Throwable) {
            if (error is InterruptedException) interrupted.set(true)
            synchronized(failure) {
                val primary = failure.get()
                if (primary == null) failure.set(error) else if (primary !== error && primary.suppressed.none { it === error }) {
                    primary.addSuppressed(error)
                }
            }
        }

        /** Checks the original root clock, or B2's existing pre-input clock before injection starts. @return Remaining bounded wait. */
        fun remaining(): Long {
            val start = rootPhaseStart ?: inputStart.get().takeIf { it >= 0L }
            if (start == null) {
                beforeInput()
                return 25L
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            check(elapsed in 0L until ROOT_MS) { "ROOT_TOAST original root confirmation deadline expired" }
            return ROOT_MS - elapsed
        }

        // The platform swallows wait interruption; keep the caller interruptible without cancelling begun input.
        val ownerJob = kotlinx.coroutines.Job()
        val worker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + ownerJob).launch {
            if (!pending.compareAndSet(true, false)) return@launch
            var returnedEvent: AccessibilityEvent? = null
            try {
                val automation = checkNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(
                    Configurator.getInstance().uiAutomationFlags,
                )) { "ROOT_TOAST fixture=INCOMPLETE existing accessibility connection unavailable" }
                beforeInput()
                val waitMillis = if (rootPhaseStart == null) ROOT_MS else remaining()
                returnedEvent = automation.executeAndWaitForEvent(
                    Runnable {
                        if (inputPending.compareAndSet(true, false)) {
                            try {
                                beforeInput()
                                val beforeElapsed = SystemClock.elapsedRealtime()
                                val beforeUptime = SystemClock.uptimeMillis()
                                inputStart.set(beforeElapsed)
                                val admitted = device.pressKeyCode(android.view.KeyEvent.KEYCODE_BACK)
                                val afterElapsed = SystemClock.elapsedRealtime()
                                val afterUptime = SystemClock.uptimeMillis()
                                input.set(Input(beforeElapsed, afterElapsed, beforeUptime, afterUptime))
                                if (!admitted) throw AssertionError("ROOT_TOAST strict KEYCODE_BACK admission=false")
                                check(afterElapsed >= beforeElapsed && afterUptime >= beforeUptime) {
                                    "ROOT_TOAST non-monotonic input brackets"
                                }
                            } catch (error: Throwable) {
                                // command.run precedes the platform's finally, so no action error may escape here.
                                record(error)
                            } finally {
                                if (Thread.interrupted()) record(InterruptedException("ROOT_TOAST input interrupted"))
                                try {
                                    remaining()
                                } catch (error: Throwable) {
                                    record(error)
                                }
                            }
                        }
                    },
                    { event ->
                        try {
                            val brackets = input.get()
                            if (failure.get() != null || abandoned.get() || brackets == null) false else {
                                val observedUptime = SystemClock.uptimeMillis()
                                val observedElapsed = SystemClock.elapsedRealtime()
                                val qualifies = qualifies(event.eventType, event.className, event.packageName,
                                    event.text, prompt, event.eventTime, brackets.beforeUptime, observedUptime)
                                if (qualifies) {
                                    remaining()
                                    matched.set(Evidence(brackets.beforeElapsed, brackets.afterElapsed,
                                        brackets.beforeUptime, brackets.afterUptime, event.eventTime,
                                        observedUptime, observedElapsed))
                                }
                                qualifies
                            }
                        } catch (error: Throwable) {
                            record(error)
                            false
                        }
                    },
                    waitMillis,
                )
                remaining()
            } catch (_: TimeoutException) {
                // UiDevice 2.3.0 logs this exception's unrelated event dump; never retain it as a cause/suppressed value.
                record(AssertionError("ROOT_TOAST no qualified owned presentation event within original fence"))
            } catch (error: Throwable) {
                record(error)
            } finally {
                try {
                    returnedEvent?.recycle()
                } catch (error: Throwable) {
                    record(error)
                }
            }
        }
        worker.invokeOnCompletion { completed.countDown() }
        ownerJob.complete()
        try {
            try {
                while (!completed.await(minOf(25L, remaining()), TimeUnit.MILLISECONDS)) {
                    remaining()
                }
                remaining()
            } catch (error: Throwable) {
                record(error)
                abandoned.set(true)
                pending.compareAndSet(true, false)
                inputPending.compareAndSet(true, false)
                // Recovery is not an acceptance window; wait for the platform finally without interrupting its command.
                val cleanupStart = SystemClock.elapsedRealtime()
                while (completed.count != 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - cleanupStart
                    if (elapsed !in 0L until 5_000L) break
                    try {
                        completed.await(5_000L - elapsed, TimeUnit.MILLISECONDS)
                    } catch (cleanup: InterruptedException) {
                        record(cleanup)
                    }
                }
                if (completed.count != 0L) record(AssertionError("ROOT_TOAST collector teardown incomplete"))
            }
            failure.get()?.let { throw it }
            val evidence = matched.get() ?: throw AssertionError("ROOT_TOAST qualified presentation evidence missing")
            remaining()
            return evidence
        } finally {
            if (interrupted.get()) Thread.currentThread().interrupt()
        }
    }

    /** Pure event classifier, with no event/text retention. @param type Event type. @param className Event class. @param origin Originating App. @param entries Event text entries inspected only in this call. @param prompt Locked resource text. @param eventUptime Event's uptime timestamp. @param inputUptime Actual input's uptime lower bound. @param observedUptime Uptime at collection. @return Whether all presentation fields and freshness qualify. */
    private fun qualifies(
        type: Int,
        className: CharSequence?,
        origin: CharSequence?,
        entries: List<CharSequence>,
        prompt: String,
        eventUptime: Long,
        inputUptime: Long,
        observedUptime: Long,
    ): Boolean = type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
        android.text.TextUtils.equals(className, TOAST_CLASS) && android.text.TextUtils.equals(origin, ORIGIN_PACKAGE) &&
        entries.any { android.text.TextUtils.equals(it, prompt) } &&
        inputUptime >= 0L && eventUptime in inputUptime..observedUptime

    /** Rejects wrong origin/class/text/type and stale/future uptime values without any input or accessibility connection. */
    private fun checkClassifier() {
        val type = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        val prompt = "expected prompt"
        val text = listOf<CharSequence>(prompt)
        check(qualifies(type, TOAST_CLASS, ORIGIN_PACKAGE, text, prompt, 101L, 100L, 110L))
        check(!qualifies(type, TOAST_CLASS, "com.android.systemui", text, prompt, 101L, 100L, 110L))
        check(!qualifies(type, "android.app.Notification", ORIGIN_PACKAGE, text, prompt, 101L, 100L, 110L))
        check(!qualifies(type, TOAST_CLASS, ORIGIN_PACKAGE, listOf("other prompt"), prompt, 101L, 100L, 110L))
        check(!qualifies(type, TOAST_CLASS, ORIGIN_PACKAGE, listOf("$prompt extra"), prompt, 101L, 100L, 110L))
        check(!qualifies(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, TOAST_CLASS, ORIGIN_PACKAGE, text, prompt, 101L, 100L, 110L))
        check(!qualifies(type, TOAST_CLASS, ORIGIN_PACKAGE, text, prompt, 99L, 100L, 110L))
        check(!qualifies(type, TOAST_CLASS, ORIGIN_PACKAGE, text, prompt, 111L, 100L, 110L))
    }
}
