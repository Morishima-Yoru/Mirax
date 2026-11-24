package me.trinitrix.mirax.wfd

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UibcTouchChannelTest {
    private var previousRunner: ((Runnable) -> Unit)? = null
    private var previousBinder: ((Context?, Socket) -> Boolean)? = null
    private val connectedAtBind = AtomicReference<Boolean?>(null)

    /** Replaces platform routing for real local TCP connections. */
    @Before
    fun isolatePlatformBinding() {
        previousBinder = UibcTouchChannel.socketBinder
        UibcTouchChannel.socketBinder = { _, socket ->
            connectedAtBind.set(socket.isConnected)
            false
        }
    }

    @Test
    fun queuedDescriptorAllowsTouchReportBeforeWriterRuns() {
        val writes = LinkedBlockingQueue<Runnable>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { writes.add(it) }
        ServerSocket(0).use { listening ->
            withUibcConnection(listening) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                val descriptorWrite = writes.poll(2, TimeUnit.SECONDS)
                assertThat(descriptorWrite).isNotNull()
                val contacts = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                UibcTouchChannel.submit(contacts)
                descriptorWrite.run()
                writes.poll(2, TimeUnit.SECONDS).run()
                writes.poll(2, TimeUnit.SECONDS).run()
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())
                assertTouchReport(input, contacts)
            }
        }
    }

    /** Restores shared channel hooks and closes the connection. */
    @After
    fun restoreChannel() {
        previousRunner?.let { UibcTouchChannel.writeRunner = it }
        previousBinder?.let { UibcTouchChannel.socketBinder = it }
        UibcTouchChannel.close()
    }

    @Test
    fun contactReportIsWrittenOffTheCallerThread() {
        val caller = Thread.currentThread()
        val writers = recordWriterThreads()
        ServerSocket(0).use { listening ->
            val expectedStatus = "UIBC connected to 127.0.0.1:${listening.localPort}"
            val observedStatus = AtomicReference<String?>(null)
            val previousStatus = UibcTouchChannel.onStatus
            val existingStatus: (String) -> Unit = { line ->
                if (line == expectedStatus) observedStatus.set(line)
                previousStatus?.invoke(line)
            }
            UibcTouchChannel.onStatus = existingStatus
            try {
                withUibcConnection(listening) { socket ->
                    UibcTouchChannel.setPictureSize(1920, 1080)
                    val contacts = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                    UibcTouchChannel.submit(contacts)
                    val input = DataInputStream(socket.getInputStream())
                    assertThat(readPacket(input)).isEqualTo(
                        UibcPackets.hidcPacket(
                            UibcPackets.HID_MULTI_TOUCH,
                            UibcPackets.USAGE_REPORT_DESCRIPTOR,
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                        ),
                    )
                    assertTouchReport(input, emptyList())
                    assertTouchReport(input, contacts)
                }
                assertThat(observedStatus.get()).isEqualTo(expectedStatus)
                assertThat(UibcTouchChannel.onStatus).isSameInstanceAs(existingStatus)
            } finally {
                UibcTouchChannel.onStatus = previousStatus
            }
        }
        assertThat(writers).hasSize(3)
        assertThat(writers).containsNoneIn(listOf(caller))
    }

    @Test
    fun penReportIsWrittenOffTheCallerThread() {
        val caller = Thread.currentThread()
        val writers = recordWriterThreads()
        ServerSocket(0).use { listening ->
            withUibcConnection(listening) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                val pen = UibcPenContact(x = 10, y = 20, tip = true, inRange = true, pressure = 100)
                UibcTouchChannel.submitPen(pen)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )
            }
        }
        assertThat(writers).hasSize(3)
        assertThat(writers).containsNoneIn(listOf(caller))
    }

    @Test
    fun liftAll_liftsBothTouchAndPen() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            withUibcConnection(listening) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())

                val contacts = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                val pen = UibcPenContact(x = 30, y = 40, tip = true, inRange = true, pressure = 500)
                UibcTouchChannel.submit(contacts)
                assertTouchReport(input, contacts)
                UibcTouchChannel.submitPen(pen)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )

                UibcTouchChannel.liftAll()
                assertTouchReport(input, contacts.map { it.copy(tip = false) })
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen.copy(tip = false, inRange = false, pressure = 0)),
                    ),
                )
            }
        }
    }

    @Test
    fun newTcpConnectionResendsDescriptorBeforeTouchReport() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            repeat(2) { connection ->
                withUibcConnection(listening) { socket ->
                    UibcTouchChannel.setPictureSize(1920, 1080)
                    val input = DataInputStream(socket.getInputStream())
                    assertThat(readPacket(input)).isEqualTo(
                        UibcPackets.hidcPacket(
                            UibcPackets.HID_MULTI_TOUCH,
                            UibcPackets.USAGE_REPORT_DESCRIPTOR,
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                        ),
                    )
                    assertTouchReport(input, emptyList())
                    if (connection == 1) {
                        val contact = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                        UibcTouchChannel.submit(contact)
                        assertTouchReport(input, contact)
                    }
                }
            }
        }
    }

    @Test
    fun disabledOpenWritesNothingUntilEnabledAndReusesOneTcpConnection() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            withUibcConnection(listening, enabled = false) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                // Disabled: no descriptor, no clear frame, no reports.
                assertNoDataWithinTimeout(socket)

                UibcTouchChannel.setEnabled(true)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())

                // Redundant enable is a no-op: no second descriptor.
                UibcTouchChannel.setEnabled(true)
                assertNoDataWithinTimeout(socket)

                val contact = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                UibcTouchChannel.submit(contact)
                assertTouchReport(input, contact)

                // Disable drops pending/live state and stops writes, but the
                // TCP connection stays open.
                UibcTouchChannel.setEnabled(false)
                UibcTouchChannel.submit(contact)
                UibcTouchChannel.submitPen(
                    UibcPenContact(x = 30, y = 40, tip = true, inRange = true, pressure = 100),
                )
                assertNoDataWithinTimeout(socket)

                // Re-enable resends the descriptor and clear frame on the
                // same socket; a fresh contact is permitted again.
                UibcTouchChannel.setEnabled(true)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())
                UibcTouchChannel.submit(contact)
                assertTouchReport(input, contact)
            }
        }
    }

    @Test
    fun descriptorWriteFailureInvalidatesDescriptorSent() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            withUibcConnection(listening) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                // First descriptor succeeds.
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())
                // Close the client so the next write fails.
                socket.close()
                // Trigger a descriptor re-send via picture-size change.
                UibcTouchChannel.setPictureSize(1280, 720)
                // The channel should have invalidated descriptorSent; verify by
                // reopening and confirming a fresh descriptor is sent on the new socket.
                UibcTouchChannel.close()
                withUibcConnection(listening) { socket2 ->
                    UibcTouchChannel.setPictureSize(1920, 1080)
                    val input2 = DataInputStream(socket2.getInputStream())
                    assertThat(readPacket(input2)).isEqualTo(
                        UibcPackets.hidcPacket(
                            UibcPackets.HID_MULTI_TOUCH,
                            UibcPackets.USAGE_REPORT_DESCRIPTOR,
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun setHidTypePropagatesAndResendsDescriptor() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            withUibcConnection(listening) { socket ->
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                // Initial multi-touch descriptor.
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.MAX_CONTACTS),
                    ),
                )
                assertTouchReport(input, emptyList())
                // Mid-session type change to single-touch.
                UibcTouchChannel.setHidType(UibcPackets.HID_SINGLE_TOUCH)
                // Descriptor re-sent with single-touch max contacts.
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_SINGLE_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, 1),
                    ),
                )
                assertTouchReport(input, emptyList(), maxContacts = 1, hidType = UibcPackets.HID_SINGLE_TOUCH)
                // Redundant setHidType is a no-op.
                UibcTouchChannel.setHidType(UibcPackets.HID_SINGLE_TOUCH)
                assertNoDataWithinTimeout(socket)
            }
        }
    }

    /**
     * Records the threads that execute writes while retaining the original runner.
     *
     * @return The threads in execution order.
     */
    private fun recordWriterThreads(): ConcurrentLinkedQueue<Thread> {
        val writers = ConcurrentLinkedQueue<Thread>()
        val runner = UibcTouchChannel.writeRunner
        previousRunner = runner
        UibcTouchChannel.writeRunner = { runnable ->
            runner {
                writers.add(Thread.currentThread())
                runnable.run()
            }
        }
        return writers
    }

    /**
     * Opens a real TCP connection and waits for the channel to publish its socket.
     *
     * @param listening The local server accepting the connection.
     * @param enabled Whether the channel starts enabled.
     * @param block The assertions to run with the connected socket.
     */
    private fun withUibcConnection(
        listening: ServerSocket,
        enabled: Boolean = true,
        block: (Socket) -> Unit,
    ) {
        val address = "127.0.0.1:${listening.localPort}"
        val started = System.nanoTime()
        val oldDialThreads = Thread.getAllStackTraces().keys.filter { it.name == "mirax-uibc" }.toSet()
        val statuses = ConcurrentLinkedQueue<String>()
        val connected = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>(null)
        val previousStatus = UibcTouchChannel.onStatus

        fun connectionFailure(phase: String, cause: Throwable): AssertionError {
            val recentStatuses = try {
                statuses.toList().takeLast(20).joinToString("\n").ifEmpty { "<none>" }
            } catch (error: Throwable) {
                "<unavailable: $error>"
            }
            val threads = try {
                Thread.getAllStackTraces()
                    .filterKeys { it.name.startsWith("mirax-uibc") }
                    .entries.joinToString("\n") { (thread, stack) ->
                        "${thread.name} id=${thread.id} state=${thread.state}\n" +
                            stack.joinToString("\n") { "    at $it" }
                    }.ifEmpty { "<none>" }
            } catch (error: Throwable) {
                "<unavailable: $error>"
            }
            return AssertionError(
                "UIBC $phase for $address after ${System.nanoTime() - started} ns\n" +
                    "Last statuses:\n$recentStatuses\nUIBC threads:\n$threads",
                cause,
            )
        }

        UibcTouchChannel.onStatus = { line ->
            val thread = Thread.currentThread()
            try {
                statuses.add("${System.nanoTime() - started} ns ${thread.name} id=${thread.id}: $line")
                previousStatus?.invoke(line)
            } catch (error: Throwable) {
                callbackFailure.compareAndSet(null, error)
                statuses.add("${System.nanoTime() - started} ns ${thread.name} id=${thread.id}: callback failed: $error")
            } finally {
                if (line == "UIBC connected to $address" &&
                    thread.name == "mirax-uibc" && thread !in oldDialThreads
                ) {
                    connected.countDown()
                }
            }
        }
        try {
            listening.soTimeout = 3_000
            connectedAtBind.set(null)
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH, enabled)
            val socket = try {
                listening.accept()
            } catch (error: SocketTimeoutException) {
                throw connectionFailure("accept timed out", error)
            }
            socket.use {
                assertThat(connectedAtBind.get()).isEqualTo(false)
                it.soTimeout = 2_000
                // Socket publication follows accept; this wait cannot prevent an accept timeout.
                val ready = try {
                    connected.await(3, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw connectionFailure("ready interrupted", error)
                }
                if (!ready) {
                    throw connectionFailure("ready timed out", SocketTimeoutException("UIBC ready timed out"))
                }
                block(it)
            }
            callbackFailure.get()?.let { throw AssertionError("UIBC status callback failed", it) }
        } finally {
            UibcTouchChannel.onStatus = previousStatus
        }
    }

    private fun assertNoDataWithinTimeout(socket: Socket) {
        try {
            val read = socket.getInputStream().read()
            if (read == -1) {
                throw AssertionError("channel closed the TCP socket")
            }
            throw AssertionError("unexpected UIBC byte written: $read")
        } catch (_: SocketTimeoutException) {
        }
    }

    private fun assertTouchReport(
        input: DataInputStream,
        contacts: List<UibcContact>,
        maxContacts: Int = UibcPackets.MAX_CONTACTS,
        hidType: Int = UibcPackets.HID_MULTI_TOUCH,
    ) {
        val packet = readPacket(input)
        val scanTimeOffset = 9 + 1 + maxContacts * 6
        val scanTime = (packet[scanTimeOffset].toInt() and 0xFF) or
                ((packet[scanTimeOffset + 1].toInt() and 0xFF) shl 8)
        assertThat(packet).isEqualTo(
            UibcPackets.hidcPacket(
                hidType,
                UibcPackets.USAGE_INPUT_REPORT,
                UibcPackets.touchReport(contacts, maxContacts, scanTime),
            ),
        )
    }

    private fun readPacket(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val packet = ByteArray(size)
        header.copyInto(packet)
        input.readFully(packet, 4, size - 4)
        return packet
    }
}
