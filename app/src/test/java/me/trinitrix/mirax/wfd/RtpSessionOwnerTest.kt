package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.net.Socket
import org.junit.Test

/** Checks receiver and audio ownership using unconnected sockets as identities only. */
class RtpSessionOwnerTest {
    /** Rejects stale cleanup after another RTSP session has replaced the receiver. */
    @Test
    fun staleOwnerStopLeavesNewReceiverAndAudioRunning() {
        val receiver = RecordingReceiver()
        Socket().use { oldOwner ->
            Socket().use { newOwner ->
                receiver.start(oldOwner)
                receiver.start(newOwner)
                val stopsBeforeCleanup = receiver.stopCount

                receiver.sessions.stop(expectedOwner = oldOwner)

                assertThat(receiver.receiverOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.audioOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.stopCount).isEqualTo(stopsBeforeCleanup)
                assertThat(receiver.startCount).isEqualTo(2)
            }
        }
    }

    /** Rejects a late start from a cancelled generation without touching the active session. */
    @Test
    fun staleGenerationStartLeavesCurrentReceiverAndAudioRunning() {
        val receiver = RecordingReceiver()
        Socket().use { oldOwner ->
            Socket().use { newOwner ->
                receiver.start(newOwner)
                val stopsBeforeLateStart = receiver.stopCount
                val startsBeforeLateStart = receiver.startCount

                val started = receiver.start(oldOwner, isCurrent = { false })

                assertThat(started).isFalse()
                assertThat(receiver.receiverOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.audioOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.stopCount).isEqualTo(stopsBeforeLateStart)
                assertThat(receiver.startCount).isEqualTo(startsBeforeLateStart)
            }
        }
    }

    /** Allows the owning session to release its receiver and audio. */
    @Test
    fun matchingOwnerStopClosesReceiverAndAudio() {
        val receiver = RecordingReceiver()
        Socket().use { owner ->
            receiver.start(owner)
            val stopsBeforeCleanup = receiver.stopCount

            receiver.sessions.stop(expectedOwner = owner)

            assertThat(receiver.receiverOwner).isNull()
            assertThat(receiver.audioOwner).isNull()
            assertThat(receiver.stopCount).isEqualTo(stopsBeforeCleanup + 1)
            assertThat(receiver.startCount).isEqualTo(1)
        }
    }

    /** Allows explicit controller shutdown without an expected session identity. */
    @Test
    fun unconditionalStopClosesReceiverAndAudio() {
        val receiver = RecordingReceiver()
        Socket().use { owner ->
            receiver.start(owner)
            val stopsBeforeShutdown = receiver.stopCount

            receiver.sessions.stop()

            assertThat(receiver.receiverOwner).isNull()
            assertThat(receiver.audioOwner).isNull()
            assertThat(receiver.stopCount).isEqualTo(stopsBeforeShutdown + 1)
            assertThat(receiver.startCount).isEqualTo(1)
        }
    }

    /** Replaces both resources and stops the previous receiver once before starting the new one. */
    @Test
    fun newOwnerReplacesReceiverAndAudio() {
        val receiver = RecordingReceiver()
        Socket().use { oldOwner ->
            Socket().use { newOwner ->
                receiver.start(oldOwner)
                val stopsBeforeReplacement = receiver.stopCount

                val started = receiver.start(newOwner)

                assertThat(started).isTrue()
                assertThat(receiver.receiverOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.audioOwner).isSameInstanceAs(newOwner)
                assertThat(receiver.stopCount).isEqualTo(stopsBeforeReplacement + 1)
                assertThat(receiver.startCount).isEqualTo(2)
            }
        }
    }

    /** Records externally visible receiver/audio resources and lifecycle callback counts. */
    private class RecordingReceiver {
        var receiverOwner: Socket? = null
            private set
        var audioOwner: Socket? = null
            private set
        var startCount = 0
            private set
        var stopCount = 0
            private set
        val sessions = RtpSessionOwner {
            stopCount++
            receiverOwner = null
            audioOwner = null
        }

        /**
         * Creates both fake resources through the same boundary used by the controller.
         *
         * @param owner unconnected RTSP identity for the fake resources.
         * @param isCurrent predicate supplied to the ownership boundary.
         * @return whether the boundary accepted this receiver start.
         */
        fun start(owner: Socket, isCurrent: () -> Boolean = { true }): Boolean =
            sessions.start(owner, isCurrent) {
                receiverOwner = owner
                audioOwner = owner
                startCount++
            }
    }
}
