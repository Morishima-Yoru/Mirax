package me.trinitrix.mirax.ui.standby

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CancellationException
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerDriver
import me.trinitrix.mirax.ui.standby.media.StandbyPlayerListener
import org.junit.Test

/** Checks source identity and cancellation forwarding at the host boundary. */
class StandbyTrackedDriverTest {
    private class FakeDriver : StandbyPlayerDriver {
        val listeners = mutableMapOf<Long, StandbyPlayerListener>()
        override fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener) {
            listeners[token] = listener
        }
        override fun restart() = Unit
        override fun pause() = Unit
        override fun release(): Boolean = true
    }

    private open class Listener : StandbyPlayerListener {
        override fun onPrepared(file: File, token: Long) = Unit
        override fun onRendering(file: File, token: Long) = Unit
        override fun onInterrupted(file: File, token: Long) = Unit
        override fun onError(file: File, token: Long, cause: Throwable) = Unit
        override fun onReleased(file: File, token: Long) = Unit
        override fun onReleaseFailed(file: File, token: Long, cause: Throwable) = Unit
        override fun onObserverCancelled(file: File, token: Long, cause: CancellationException) = Unit
    }

    @Test
    fun staleCallbacksCannotPrepareOrReleaseTheReplacementToken() {
        val driver = FakeDriver()
        var notifications = 0
        val tracked = StandbyTrackedDriver(driver) { notifications++ }
        val file = File("background")
        tracked.open(file, false, false, 1L, Listener())
        val old = requireNotNull(driver.listeners[1L])
        old.onPrepared(file, 1L)
        assertThat(tracked.isPrepared(file)).isTrue()
        tracked.open(file, false, false, 2L, Listener())
        val current = requireNotNull(driver.listeners[2L])
        old.onPrepared(file, 1L)
        assertThat(tracked.isPrepared(file)).isFalse()
        current.onPrepared(file, 2L)
        old.onReleased(file, 1L)
        assertThat(tracked.isPrepared(file)).isTrue()
        assertThat(notifications).isEqualTo(2)
        current.onInterrupted(file, 2L)
        assertThat(tracked.isPrepared(file)).isFalse()
    }

    @Test
    fun currentErrorRemainsIdentifiableAcrossSynchronousRelease() {
        val driver = FakeDriver()
        val tracked = StandbyTrackedDriver(driver) {}
        val file = File("background")
        var observed = false
        val listener = object : Listener() {
            override fun onError(file: File, token: Long, cause: Throwable) {
                tracked.release()
                observed = tracked.isCurrentError(file, token)
            }
        }
        tracked.open(file, false, false, 3L, listener)
        requireNotNull(driver.listeners[3L]).onError(file, 3L, IllegalStateException("failed"))
        assertThat(observed).isTrue()
        assertThat(tracked.isCurrentError(file, 3L)).isFalse()
    }

    @Test
    fun cancellationKeepsTheOriginalCauseAndInvalidatesPreparation() {
        val driver = FakeDriver()
        val tracked = StandbyTrackedDriver(driver) {}
        val file = File("background")
        val cancellation = CancellationException("cancelled")
        var received: CancellationException? = null
        val listener = object : Listener() {
            override fun onObserverCancelled(file: File, token: Long, cause: CancellationException) {
                received = cause
            }
        }
        tracked.open(file, false, false, 4L, listener)
        val callback = requireNotNull(driver.listeners[4L])
        callback.onPrepared(file, 4L)
        callback.onObserverCancelled(file, 4L, cancellation)
        assertThat(tracked.isPrepared(file)).isFalse()
        assertThat(received).isSameInstanceAs(cancellation)
    }
}
