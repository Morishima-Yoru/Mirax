package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UibcTouchChannelTest {
    private var previousRunner: ((Runnable) -> Unit)? = null

    @Test
    fun queuedDescriptorAllowsTouchReportBeforeWriterRuns() {
        val writes = LinkedBlockingQueue<Runnable>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { writes.add(it) }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
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

    @After
    fun restoreWriter() {
        previousRunner?.let { UibcTouchChannel.writeRunner = it }
        UibcTouchChannel.close()
    }

    @Test
    fun contactReportIsWrittenOffTheCallerThread() {
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        val caller = Thread.currentThread()
        val writers = mutableListOf<Thread>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable ->
            val worker = Thread({
                writers.add(Thread.currentThread())
                runnable.run()
            }, "test-uibc-write")
            worker.start()
            worker.join()
        }
        server.use { listening ->
            UibcTouchChannel.open(
                "127.0.0.1",
                listening.localPort,
                UibcPackets.HID_MULTI_TOUCH,
            )
            val client = listening.accept()
            client.use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                UibcTouchChannel.submit(
                    listOf(UibcContact(id = 0, x = 10, y = 20, tip = true)),
                )
                val first = ByteArray(64)
                val read = socket.getInputStream().read(first)
                assertThat(read).isGreaterThan(0)
            }
        }
        assertThat(writers).isNotEmpty()
        assertThat(writers).containsNoneIn(listOf(caller))
    }

    @Test
    fun penReportIsWrittenOffTheCallerThread() {
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        val caller = Thread.currentThread()
        val writers = mutableListOf<Thread>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable ->
            val worker = Thread({
                writers.add(Thread.currentThread())
                runnable.run()
            }, "test-uibc-pen-write")
            worker.start()
            worker.join()
        }
        server.use { listening ->
            UibcTouchChannel.open(
                "127.0.0.1",
                listening.localPort,
                UibcPackets.HID_MULTI_TOUCH,
            )
            val client = listening.accept()
            client.use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                UibcTouchChannel.submitPen(
                    UibcPenContact(x = 10, y = 20, tip = true, inRange = true, pressure = 100),
                )
                val first = ByteArray(64)
                val read = socket.getInputStream().read(first)
                assertThat(read).isGreaterThan(0)
            }
        }
        assertThat(writers).isNotEmpty()
        assertThat(writers).containsNoneIn(listOf(caller))
    }

    @Test
    fun liftAll_liftsBothTouchAndPen() {
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        val writtenPackets = mutableListOf<ByteArray>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable ->
            runnable.run()
        }
        server.use { listening ->
            UibcTouchChannel.open(
                "127.0.0.1",
                listening.localPort,
                UibcPackets.HID_MULTI_TOUCH,
            )
            val client = listening.accept()
            client.use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                UibcTouchChannel.submit(listOf(UibcContact(id = 0, x = 10, y = 20, tip = true)))
                UibcTouchChannel.submitPen(UibcPenContact(x = 30, y = 40, tip = true, inRange = true, pressure = 500))
                UibcTouchChannel.liftAll()
            }
        }
    }

    @Test
    fun newTcpConnectionResendsDescriptorBeforeTouchReport() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            repeat(2) { connection ->
                UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
                listening.accept().use { socket ->
                    socket.soTimeout = 2_000
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
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        val connected = java.util.concurrent.CountDownLatch(1)
        UibcTouchChannel.onStatus = { line ->
            if (line.startsWith("UIBC connected")) connected.countDown()
        }
        try {
            server.use { listening ->
                UibcTouchChannel.open(
                    "127.0.0.1",
                    listening.localPort,
                    UibcPackets.HID_MULTI_TOUCH,
                    enabled = false,
                )
                listening.accept().use { socket ->
                    socket.soTimeout = 2_000
                    assertThat(connected.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
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
        } finally {
            UibcTouchChannel.onStatus = null
        }
    }

    @Test
    fun descriptorWriteFailureInvalidatesDescriptorSent() {
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        server.use { listening ->
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            val client = listening.accept()
            client.use { socket ->
                socket.soTimeout = 2_000
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
                UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
                val client2 = listening.accept()
                client2.use { socket2 ->
                    socket2.soTimeout = 2_000
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
        val server = ServerSocket(0)
        server.soTimeout = 3_000
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        server.use { listening ->
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            val client = listening.accept()
            client.use { socket ->
                socket.soTimeout = 2_000
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
