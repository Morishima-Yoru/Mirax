package me.trinitrix.mirax.ui.standby

import java.io.File
import java.util.concurrent.CancellationException
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerDriver
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerListener

/** Tracks the driver's original open identity without changing its lifecycle. */
internal class StandbyTrackedDriver(
    private val driver: StandbyPlayerDriver,
    private val onPrepared: () -> Unit,
) : StandbyPlayerDriver {
    private data class Owner(val file: File, val token: Long)

    private var current: Owner? = null
    private var latestOpen: Owner? = null
    private var prepared = false
    private var dispatchingError: Owner? = null

    /** Returns true only after this file's current token reported prepared. */
    fun isPrepared(file: File): Boolean = current?.let { it.file == file && it == latestOpen && prepared } == true

    /** Verifies an error against the current open, including synchronous release callbacks. */
    fun isCurrentError(file: File, token: Long): Boolean {
        val owner = Owner(file, token)
        return owner == latestOpen && (owner == current || owner == dispatchingError)
    }

    override fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener) {
        val owner = Owner(file, token)
        current = owner
        latestOpen = owner
        prepared = false
        try {
            driver.open(file, isVideo, isAnimated, token, object : StandbyPlayerListener {
                override fun onPrepared(file: File, token: Long) {
                    if (current == owner && owner == Owner(file, token)) prepared = true
                    listener.onPrepared(file, token)
                    if (current == owner && owner == Owner(file, token) && prepared) onPrepared()
                }

                override fun onRendering(file: File, token: Long) = listener.onRendering(file, token)

                override fun onInterrupted(file: File, token: Long) {
                    if (current == owner && owner == Owner(file, token)) prepared = false
                    listener.onInterrupted(file, token)
                }

                override fun onError(file: File, token: Long, cause: Throwable) {
                    val previous = dispatchingError
                    if (current == owner && owner == Owner(file, token)) {
                        prepared = false
                        dispatchingError = owner
                    }
                    try {
                        listener.onError(file, token, cause)
                    } finally {
                        dispatchingError = previous
                    }
                }

                override fun onReleased(file: File, token: Long) {
                    clearIfCurrent(owner, file, token)
                    listener.onReleased(file, token)
                }

                override fun onReleaseFailed(file: File, token: Long, cause: Throwable) {
                    if (current == owner && owner == Owner(file, token)) prepared = false
                    listener.onReleaseFailed(file, token, cause)
                }

                override fun onObserverCancelled(file: File, token: Long, cause: CancellationException) {
                    if (current == owner && owner == Owner(file, token)) prepared = false
                    listener.onObserverCancelled(file, token, cause)
                }
            })
        } catch (failure: Throwable) {
            clearIfCurrent(owner, file, token)
            throw failure
        }
    }

    override fun restart() = driver.restart()

    override fun pause() = driver.pause()

    override fun release(): Boolean {
        val released = current
        val completed = driver.release()
        if (completed && current == released) {
            current = null
            prepared = false
        }
        return completed
    }

    private fun clearIfCurrent(owner: Owner, file: File, token: Long) {
        if (current == owner && owner == Owner(file, token)) {
            current = null
            prepared = false
        }
    }
}
