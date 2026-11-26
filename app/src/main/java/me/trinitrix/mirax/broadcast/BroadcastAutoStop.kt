package me.trinitrix.mirax.broadcast

import android.os.Handler
import android.os.Looper
import me.trinitrix.mirax.MainActivity
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSnapshot

/**
 * Pure countdown state for idle auto-stop.
 *
 * Only [ScreenPhase.ADVERTISING] may expire. Every fresh ADVERTISING entry
 * arms the full 1-180 minute delay (non-cumulative); repeat syncs with
 * unchanged minutes keep the original deadline, and leaving the phase
 * disarms. Dependencies are immutable constructor values, so each instance
 * is isolated for tests. Expiry re-checks the live snapshot and delegates to
 * the existing stop path only while still ADVERTISING with advertising on;
 * WFD behaviour follows the delegated stop, which the timer does not control.
 *
 * @param schedule Run [action] after the delay; each call captures its own token.
 * @param cancel Drop the pending schedule, if any.
 * @param snapshotNow Live snapshot at expiry, or null when unavailable.
 * @param stop Existing stop path; production delegates to [SessionHost].
 */
internal class BroadcastAutoStopTimer(
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val snapshotNow: () -> SessionSnapshot?,
    private val stop: () -> Unit,
) {
    private var armedMinutes: Int? = null
    private var armedToken = 0L

    fun sync(snapshot: SessionSnapshot) {
        // Only idle advertising may expire. Arming / connecting must not be
        // cut mid-handshake by the auto-stop clock.
        if (snapshot.phase != ScreenPhase.ADVERTISING) {
            cancel()
            armedMinutes = null
            armedToken++
            return
        }
        val minutes = snapshot.broadcastAutoStopMinutes.coerceIn(1, 180)
        if (armedMinutes == minutes) {
            return
        }
        cancel()
        armedMinutes = minutes
        armedToken++
        val token = armedToken
        schedule(minutes * 60_000L) { fire(token) }
    }

    private fun fire(token: Long) {
        if (token != armedToken) {
            return
        }
        armedMinutes = null
        armedToken++
        val current = snapshotNow() ?: return
        if (current.phase != ScreenPhase.ADVERTISING || !current.advertisingEnabled) {
            return
        }
        stop()
    }
}

/**
 * Idle broadcast auto-stop entry point.
 *
 * Keeps the public [sync] contract; production wiring stays on the
 * main-thread Handler and delegates expiry to the existing stop path.
 */
object BroadcastAutoStop {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingStop: Runnable? = null
    private val timer by lazy {
        BroadcastAutoStopTimer(
            schedule = { delayMs, action ->
                val runnable = Runnable { action() }
                pendingStop?.let { handler.removeCallbacks(it) }
                pendingStop = runnable
                handler.postDelayed(runnable, delayMs)
            },
            cancel = {
                pendingStop?.let { handler.removeCallbacks(it) }
                pendingStop = null
            },
            snapshotNow = { MiraxApp.instance.session.snapshot() },
            stop = {
                pendingStop = null
                val app = MiraxApp.instance
                SessionHost.setAdvertising(app, false)
                MainActivity.refreshIfShowing()
            },
        )
    }

    fun sync(snapshot: SessionSnapshot) {
        timer.sync(snapshot)
    }
}
