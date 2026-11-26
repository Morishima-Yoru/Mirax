package me.trinitrix.mirax.ui.standby.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import me.trinitrix.mirax.ui.standby.BigStandbyHost

/**
 * Android sensor and frame sources for the lifecycle-scoped standby runtime.
 *
 * @param context context used only to obtain application-scoped sensor services.
 * @param host current activity-owned surface, if present.
 * @param displayDegrees current display rotation in degrees.
 */
class AndroidStandbyMotion(
    context: Context,
    private val host: () -> BigStandbyHost?,
    displayDegrees: () -> Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = StandbyMotionState()
    private var framesRunning = false
    private var previousFrameNanos = 0L
    private var frameCallback: Choreographer.FrameCallback? = null
    private val sensor = AndroidStandbyParallaxSource(
        context.applicationContext,
        onTilt = { x, y ->
            state.ticket()?.let { ticket -> handler.post { state.acceptTilt(ticket, x, y) } }
        },
        onResetRequest = {
            state.ticket()?.let { ticket -> handler.post { state.acceptReset(ticket) } }
        },
        displayDegrees = displayDegrees,
    )
    private val parallax = object : StandbyParallaxSource {
        override fun start() {
            state.start()
            sensor.start()
        }

        override fun stop() {
            state.stop()
            sensor.stop()
        }

        override fun reset() {
            sensor.reset()
            state.resetTilt()
        }
    }
    private val drift = object : StandbyOledDriftSource {
        override fun start() {
            if (framesRunning) return
            framesRunning = true
            previousFrameNanos = 0L
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (frameCallback !== this) return
                    if (!framesRunning) return
                    val deltaSeconds = if (previousFrameNanos == 0L) {
                        0.0
                    } else {
                        (frameTimeNanos - previousFrameNanos) / 1_000_000_000.0
                    }
                    previousFrameNanos = frameTimeNanos
                    host()?.let { view ->
                        val offsets = state.advance(deltaSeconds, view.width, view.height)
                        view.updateMatterMotion(offsets.canvasX, offsets.canvasY, offsets.infoX, offsets.infoY)
                    }
                    if (frameCallback === this && framesRunning) {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }

        override fun stop() {
            val retiredCallback = frameCallback
            frameCallback = null
            retiredCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            framesRunning = false
            previousFrameNanos = 0L
        }

        override fun resetOffsets() {
            host()?.updateMatterMotion(0f, 0f, 0f, 0f)
        }
    }

    /** Existing runtime policy, backed by real Android sources. */
    val runtime = StandbyRuntimeHost(StandbySensorController(parallax, drift))

    /** Invalidates queued tilt and informs the sensor mapper of rotation or fold changes. */
    fun notifyDisplayChanged() {
        state.beginDisplayChange()
        try {
            sensor.notifyDisplayChanged()
        } finally {
            state.endDisplayChange()
        }
    }
}
