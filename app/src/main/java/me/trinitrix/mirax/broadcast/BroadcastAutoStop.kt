package me.trinitrix.mirax.broadcast

import android.os.Handler
import android.os.Looper
import me.trinitrix.mirax.MainActivity
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSnapshot

/**
 * Turns idle broadcast off after [SessionSnapshot.broadcastAutoStopMinutes].
 * A live connection holds the clock; stopping advertising also drops RTSP so
 * the source is told immediately.
 */
object BroadcastAutoStop {
    private val handler = Handler(Looper.getMainLooper())
    private var armedMinutes: Int? = null

    private val stop = Runnable {
        armedMinutes = null
        val app = MiraxApp.instance
        if (app.session.snapshot().advertisingEnabled) {
            SessionHost.setAdvertising(app, false)
            MainActivity.refreshIfShowing()
        }
    }

    fun sync(snapshot: SessionSnapshot) {
        // Only idle advertising may expire. Arming / connecting must not be
        // cut mid-handshake by the auto-stop clock.
        val idle = snapshot.phase == ScreenPhase.ADVERTISING
        if (!idle) {
            handler.removeCallbacks(stop)
            armedMinutes = null
            return
        }
        val minutes = snapshot.broadcastAutoStopMinutes.coerceIn(1, 180)
        if (armedMinutes == minutes) {
            return
        }
        handler.removeCallbacks(stop)
        armedMinutes = minutes
        handler.postDelayed(stop, minutes * 60_000L)
    }
}
