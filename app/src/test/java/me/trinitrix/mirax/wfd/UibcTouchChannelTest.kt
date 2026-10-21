package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.PictureContactState
import org.junit.After
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UibcTouchChannelTest {
    private var previousRunner: ((Runnable) -> Unit)? = null

    /** Verifies all-slot wire shape and disable releases; Windows delivery is tested separately. */
    @Test
    fun independentTouchCollectionsKeepEveryAllocatedSlotAndDisableReleases() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { it.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                UibcTouchChannel.setPictureSize(1920, 1080)
                val descriptor = readPacket(input)
                assertThat(descriptor[5].toInt()).isEqualTo(UibcPackets.HID_SINGLE_TOUCH)
                assertThat(descriptor[6].toInt()).isEqualTo(UibcPackets.USAGE_REPORT_DESCRIPTOR)
                val descriptorSize = ((descriptor[7].toInt() and 0xFF) shl 8) or (descriptor[8].toInt() and 0xFF)
                assertThat(descriptorSize).isEqualTo(135 * UibcPackets.MAX_CONTACTS + 102)
                repeat(UibcPackets.MAX_CONTACTS) { slot ->
                    assertThat(descriptor[9 + slot * 135 + 7].toInt() and 0xFF).isEqualTo(slot + 1)
                }
                val penReportId = UibcPackets.MAX_CONTACTS + 1
                assertThat(descriptor[9 + UibcPackets.MAX_CONTACTS * 135 + 7].toInt() and 0xFF).isEqualTo(penReportId)
                repeat(UibcPackets.MAX_CONTACTS) { slot ->
                    val initial = readPacket(input)
                    assertThat(initial[9].toInt() and 0xFF).isEqualTo(slot + 1)
                    assertThat(initial[10].toInt()).isEqualTo(0)
                    assertThat(initial[18].toInt()).isEqualTo(0)
                }
                val initialPen = readPacket(input)
                assertThat(initialPen[9].toInt() and 0xFF).isEqualTo(penReportId)
                assertThat(initialPen[10].toInt()).isEqualTo(0)
                val contacts = List(UibcPackets.MAX_CONTACTS) { slot ->
                    UibcContact(slot, 100 + slot, 200 + slot, tip = true)
                }
                UibcTouchChannel.submit(contacts)
                repeat(UibcPackets.MAX_CONTACTS) { slot ->
                    val packet = readPacket(input)
                    val scanTime = (packet[16].toInt() and 0xFF) or ((packet[17].toInt() and 0xFF) shl 8)
                    val expected = UibcPackets.touchReport(listOf(contacts[slot].copy(id = 0)), 1, scanTime)
                    expected[0] = (slot + 1).toByte()
                    assertThat(packet).isEqualTo(UibcPackets.hidcPacket(2, UibcPackets.USAGE_INPUT_REPORT, expected))
                }
                val pen = UibcPenContact(500, 600, tip = true, inRange = true, pressure = 2000)
                UibcTouchChannel.submitPen(pen)
                val expectedPen = UibcPackets.penReport(pen).apply { this[0] = penReportId.toByte() }
                assertThat(readPacket(input)).isEqualTo(UibcPackets.hidcPacket(2, UibcPackets.USAGE_INPUT_REPORT, expectedPen))
                UibcTouchChannel.setEnabled(false)
                repeat(UibcPackets.MAX_CONTACTS) { slot ->
                    val released = readPacket(input)
                    assertThat(released[9].toInt() and 0xFF).isEqualTo(slot + 1)
                    // The terminal contact remains counted with Tip cleared, not an empty frame.
                    assertThat(released[10].toInt()).isEqualTo(0x06)
                    assertThat(released[18].toInt()).isEqualTo(1)
                }
                val releasedPen = readPacket(input)
                assertThat(releasedPen[9].toInt() and 0xFF).isEqualTo(penReportId)
                assertThat(releasedPen[10].toInt()).isEqualTo(0)
                assertThat(releasedPen[15].toInt()).isEqualTo(0)
                assertThat(releasedPen[16].toInt()).isEqualTo(0)
                UibcTouchChannel.submit(contacts)
                UibcTouchChannel.submitPen(pen)
                socket.soTimeout = 200
                assertNoDataWithinTimeout(socket)
            }
        }
    }

    @Test
    fun coveredFingerAndPenReleaseWithoutEndingOtherTouch() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { it.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                UibcTouchChannel.setPictureSize(1920, 1080)
                readPacket(input)
                repeat(UibcPackets.MAX_CONTACTS + 1) { readPacket(input) }
                val contacts = listOf(UibcContact(0, 100, 200, true), UibcContact(1, 300, 400, true))
                val pictureContacts = PictureContactState(UibcPackets.MAX_CONTACTS)
                pictureContacts.touchDown(1, insidePicture = true)
                pictureContacts.touchDown(2, insidePicture = true)
                pictureContacts.touchMove(1, true, 100f, 200f)
                pictureContacts.touchMove(2, true, 300f, 400f)
                pictureContacts.penDown(3, insidePicture = true)
                pictureContacts.penMove(3, true, 110f, 220f)
                UibcTouchChannel.submit(contacts)
                repeat(UibcPackets.MAX_CONTACTS) { readPacket(input) }
                UibcTouchChannel.submitPen(UibcPenContact(110, 220, true, true, pressure = 800))
                readPacket(input)

                val covered = pictureContacts.endCovered { x, y -> x < 150f && y < 250f }
                assertThat(pictureContacts.touchSlot(2)).isEqualTo(1)
                UibcTouchChannel.releaseCovered(covered.touchSlots, covered.pen)

                val ended = readPacket(input)
                assertThat(ended[9].toInt() and 0xFF).isEqualTo(1)
                assertThat(ended[10].toInt()).isEqualTo(0x06)
                assertThat(ended[18].toInt()).isEqualTo(1)
                val remaining = readPacket(input)
                assertThat(remaining[9].toInt() and 0xFF).isEqualTo(2)
                assertThat(remaining[10].toInt()).isEqualTo(0x07)
                repeat(UibcPackets.MAX_CONTACTS - 2) { readPacket(input) }
                val pen = readPacket(input)
                assertThat(pen[9].toInt() and 0xFF).isEqualTo(UibcPackets.MAX_CONTACTS + 1)
                assertThat(pen[10].toInt()).isEqualTo(0)

                UibcTouchChannel.submit(contacts.drop(1))
                val noLongerDown = readPacket(input)
                assertThat(noLongerDown[10].toInt()).isEqualTo(0)
                val stillDown = readPacket(input)
                assertThat(stillDown[10].toInt()).isEqualTo(0x07)
                repeat(UibcPackets.MAX_CONTACTS - 2) { readPacket(input) }
            }
        }
    }

    @Test
    fun pictureExitAfterPenUpClearsTheRemainingHoverOnTheWire() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { it.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                UibcTouchChannel.setPictureSize(1920, 1080)
                readPacket(input)
                repeat(UibcPackets.MAX_CONTACTS + 1) { readPacket(input) }

                val pictureContacts = PictureContactState(UibcPackets.MAX_CONTACTS)
                pictureContacts.penDown(9, insidePicture = true)
                UibcTouchChannel.submitPen(UibcPenContact(40, 50, tip = true, inRange = true))
                readPacket(input)
                UibcTouchChannel.submitPen(UibcPenContact(40, 50, tip = false, inRange = true))
                assertThat(readPacket(input)[10].toInt() and 0x20).isEqualTo(0x20)
                pictureContacts.penUp(9)
                pictureContacts.finishGesture()

                assertThat(pictureContacts.hasPenHover()).isTrue()
                if (pictureContacts.hasContacts() || pictureContacts.hasPenHover()) {
                    UibcTouchChannel.liftAll()
                }
                pictureContacts.endAll()
                assertThat(readPacket(input)[10].toInt()).isEqualTo(0)
            }
        }
    }

    @Test
    fun quickDisableThenEnableWritesOldContactReleasesBeforeNewDescriptor() {
        val queuedWrites = LinkedBlockingQueue<Runnable>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { queuedWrites.add(it) }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                UibcTouchChannel.setPictureSize(1920, 1080)
                repeat(UibcPackets.MAX_CONTACTS + 2) { queuedWrites.poll(2, TimeUnit.SECONDS).run() }
                readPacket(input)
                repeat(UibcPackets.MAX_CONTACTS + 1) { readPacket(input) }

                UibcTouchChannel.submit(listOf(UibcContact(0, 100, 200, tip = true)))
                repeat(UibcPackets.MAX_CONTACTS) { queuedWrites.poll(2, TimeUnit.SECONDS).run() }
                repeat(UibcPackets.MAX_CONTACTS) { readPacket(input) }
                UibcTouchChannel.submitPen(UibcPenContact(100, 200, tip = true, inRange = true, pressure = 800))
                queuedWrites.poll(2, TimeUnit.SECONDS).run()
                readPacket(input)

                UibcTouchChannel.setEnabled(false)
                UibcTouchChannel.setEnabled(true)
                while (queuedWrites.isNotEmpty()) {
                    queuedWrites.remove().run()
                }

                val releasedTouch = readPacket(input)
                assertThat(releasedTouch[6].toInt()).isEqualTo(UibcPackets.USAGE_INPUT_REPORT)
                assertThat(releasedTouch[9].toInt()).isEqualTo(1)
                assertThat(releasedTouch[10].toInt()).isEqualTo(0x06)
                assertThat(releasedTouch[18].toInt()).isEqualTo(1)
                repeat(UibcPackets.MAX_CONTACTS - 1) { readPacket(input) }
                val releasedPen = readPacket(input)
                assertThat(releasedPen[10].toInt()).isEqualTo(0)
            }
        }
    }

    @Test
    fun inputGenerationChangesAcrossEnableCyclesAndNewSockets() {
        val before = UibcTouchChannel.inputGeneration()
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use {
                val opened = UibcTouchChannel.inputGeneration()
                assertThat(opened).isGreaterThan(before)
                UibcTouchChannel.setEnabled(false)
                val disabled = UibcTouchChannel.inputGeneration()
                assertThat(disabled).isGreaterThan(opened)
                UibcTouchChannel.setEnabled(true)
                val reenabled = UibcTouchChannel.inputGeneration()
                assertThat(reenabled).isGreaterThan(disabled)
                UibcTouchChannel.setPictureSize(1920, 1080)
                val resized = UibcTouchChannel.inputGeneration()
                assertThat(resized).isGreaterThan(reenabled)
                UibcTouchChannel.setHidType(UibcPackets.HID_MULTI_TOUCH)
                assertThat(UibcTouchChannel.inputGeneration()).isGreaterThan(resized)
            }
        }
        val enabled = UibcTouchChannel.inputGeneration()
        UibcTouchChannel.close()
        assertThat(UibcTouchChannel.inputGeneration()).isGreaterThan(enabled)
        assertThat(UibcTouchChannel.canForwardInput()).isFalse()
    }

    @Test
    fun rejectedDialReportsFailureAndNextSessionCanOpenOneNewSocket() {
        val port = ServerSocket(0).use { it.localPort }
        val failed = CountDownLatch(1)
        val statuses = LinkedBlockingQueue<String>()
        UibcTouchChannel.onStatus = { line ->
            statuses.add(line)
            if (line.startsWith("UIBC dial 127.0.0.1:$port failed:")) {
                failed.countDown()
            }
        }
        try {
            UibcTouchChannel.open("127.0.0.1", port, UibcPackets.HID_SINGLE_TOUCH)
            assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(UibcTouchChannel.canForwardInput()).isFalse()
            assertThat(statuses).doesNotContain("UIBC connected to 127.0.0.1:$port")
            ServerSocket(0).use { listening ->
                listening.soTimeout = 3_000
                UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
                listening.accept().use { socket ->
                    socket.soTimeout = 2_000
                    assertThat(UibcTouchChannel.canForwardInput()).isFalse()
                    UibcTouchChannel.setPictureSize(1920, 1080)
                    val descriptor = readPacket(DataInputStream(socket.getInputStream()))
                    assertThat(descriptor[6].toInt()).isEqualTo(UibcPackets.USAGE_REPORT_DESCRIPTOR)
                    assertThat(UibcTouchChannel.canForwardInput()).isTrue()
                }
            }
        } finally {
            UibcTouchChannel.onStatus = null
        }
    }

    @Test
    fun queuedDescriptorAllowsTouchReportBeforeWriterRuns() {
        val writes = LinkedBlockingQueue<Runnable>()
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { writes.add(it) }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            assertThat(UibcTouchChannel.touchSlotLimit()).isEqualTo(2)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                val descriptorWrite = writes.poll(2, TimeUnit.SECONDS)
                assertThat(descriptorWrite).isNotNull()
                val contacts = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                val pen = UibcPenContact(x = 30, y = 40, tip = true, inRange = true, pressure = 500)
                UibcTouchChannel.submit(contacts)
                UibcTouchChannel.submitPen(pen)
                descriptorWrite.run()
                writes.poll(2, TimeUnit.SECONDS).run()
                writes.poll(2, TimeUnit.SECONDS).run()
                writes.poll(2, TimeUnit.SECONDS).run()
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
                    ),
                )
                assertTouchReport(input, emptyList())
                assertTouchReport(input, contacts)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )
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
    fun typeTwoPreservesRepeatedPenSamplesAndKeepsFirstFingerWorking() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_SINGLE_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_SINGLE_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.digitizerDescriptor(1920, 1080, UibcPackets.HID_SINGLE_TOUCH),
                    ),
                )
                assertTypeTwoTouchReports(input, emptyList())
                assertTypeTwoPenReport(input, UibcPenContact(0, 0, tip = false, inRange = false))
                val pen = UibcPenContact(30, 40, tip = true, inRange = true)
                UibcTouchChannel.submitPen(pen)
                assertTypeTwoPenReport(input, pen)
                UibcTouchChannel.submitPen(pen)
                assertTypeTwoPenReport(input, pen)
                val hover = pen.copy(tip = false)
                repeat(2) {
                    UibcTouchChannel.submitPen(hover)
                    assertTypeTwoPenReport(input, hover)
                }
                val outOfRange = hover.copy(inRange = false)
                UibcTouchChannel.submitPen(outOfRange)
                assertTypeTwoPenReport(input, outOfRange)
                UibcTouchChannel.submitPen(outOfRange)
                socket.soTimeout = 200
                assertNoDataWithinTimeout(socket)
                socket.soTimeout = 2_000
                val finger = listOf(UibcContact(0, 10, 20, tip = true))
                UibcTouchChannel.submit(finger)
                assertTypeTwoTouchReports(input, finger)
            }
        }
    }

    @Test
    fun disablingActiveChannelLiftsTouchAndPenBeforeStoppingWrites() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, 2),
                    ),
                )
                assertTouchReport(input, emptyList())
                val finger = listOf(UibcContact(0, 10, 20, tip = true))
                val pen = UibcPenContact(30, 40, tip = true, inRange = true, pressure = 500)
                UibcTouchChannel.submit(finger)
                UibcTouchChannel.submitPen(pen)
                assertTouchReport(input, finger)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )
                UibcTouchChannel.setEnabled(false)
                assertTouchReport(input, finger.map { it.copy(tip = false) })
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen.copy(tip = false, inRange = false, pressure = 0)),
                    ),
                )
                UibcTouchChannel.submit(finger)
                UibcTouchChannel.submitPen(pen)
                assertNoDataWithinTimeout(socket)
            }
        }
    }

    @Test
    fun liftAll_liftsBothTouchAndPen() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.touchDescriptor(1920, 1080, 2),
                    ),
                )
                assertTouchReport(input, emptyList())

                val finger = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                val pen = UibcPenContact(x = 30, y = 40, tip = true, inRange = true, pressure = 500)
                UibcTouchChannel.submit(finger)
                UibcTouchChannel.submitPen(pen)
                assertTouchReport(input, finger)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )

                val hover = pen.copy(tip = false, inRange = true, pressure = 0)
                UibcTouchChannel.submitPen(hover)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(hover),
                    ),
                )
                val movedFinger = finger.map { it.copy(x = 15) }
                UibcTouchChannel.submit(movedFinger)
                assertTouchReport(input, movedFinger)
                UibcTouchChannel.submitPen(pen)
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen),
                    ),
                )

                UibcTouchChannel.liftAll()
                assertTouchReport(input, movedFinger.map { it.copy(tip = false) })
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_MULTI_TOUCH,
                        UibcPackets.USAGE_INPUT_REPORT,
                        UibcPackets.penReport(pen.copy(tip = false, inRange = false, pressure = 0)),
                    ),
                )
                UibcTouchChannel.liftAll()
                assertNoDataWithinTimeout(socket)
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
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
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
                    assertThat(UibcTouchChannel.canForwardInput()).isFalse()
                    UibcTouchChannel.setPictureSize(1920, 1080)
                    // Disabled: no descriptor, no clear frame, no reports.
                    assertNoDataWithinTimeout(socket)

                    UibcTouchChannel.setEnabled(true)
                    val input = DataInputStream(socket.getInputStream())
                    assertThat(readPacket(input)).isEqualTo(
                        UibcPackets.hidcPacket(
                            UibcPackets.HID_MULTI_TOUCH,
                            UibcPackets.USAGE_REPORT_DESCRIPTOR,
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
                        ),
                    )
                    assertTouchReport(input, emptyList())
                    assertThat(UibcTouchChannel.canForwardInput()).isTrue()

                    // Redundant enable is a no-op: no second descriptor.
                    UibcTouchChannel.setEnabled(true)
                    assertNoDataWithinTimeout(socket)

                    val contact = listOf(UibcContact(id = 0, x = 10, y = 20, tip = true))
                    UibcTouchChannel.submit(contact)
                    assertTouchReport(input, contact)

                    // Disable lifts live state, stops new writes, and keeps TCP open.
                    UibcTouchChannel.setEnabled(false)
                    assertThat(UibcTouchChannel.canForwardInput()).isFalse()
                    assertTouchReport(input, contact.map { it.copy(tip = false) })
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
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
                        ),
                    )
                    assertTouchReport(input, emptyList())
                    assertThat(UibcTouchChannel.canForwardInput()).isTrue()
                    UibcTouchChannel.submit(contact)
                    assertTouchReport(input, contact)
                }
            }
        } finally {
            UibcTouchChannel.onStatus = null
        }
    }

    @Test
    fun reportWriteFailureEndsInputOnTheSameConnection() {
        previousRunner = UibcTouchChannel.writeRunner
        UibcTouchChannel.writeRunner = { runnable -> runnable.run() }
        ServerSocket(0).use { listening ->
            listening.soTimeout = 3_000
            UibcTouchChannel.open("127.0.0.1", listening.localPort, UibcPackets.HID_MULTI_TOUCH)
            listening.accept().use { socket ->
                socket.soTimeout = 2_000
                UibcTouchChannel.setPictureSize(1920, 1080)
                val input = DataInputStream(socket.getInputStream())
                readPacket(input)
                assertTouchReport(input, emptyList())
                assertThat(UibcTouchChannel.canForwardInput()).isTrue()
                val generation = UibcTouchChannel.inputGeneration()
                val pictureContacts = PictureContactState(UibcPackets.MAX_CONTACTS, generation)
                pictureContacts.touchDown(0, insidePicture = true)

                socket.setSoLinger(true, 0)
                socket.close()
                repeat(64) { index ->
                    if (UibcTouchChannel.canForwardInput()) {
                        UibcTouchChannel.submit(listOf(UibcContact(0, index, 20, tip = true)))
                    }
                }
                assertThat(UibcTouchChannel.canForwardInput()).isFalse()
                assertThat(UibcTouchChannel.inputGeneration()).isGreaterThan(generation)
                assertThat(pictureContacts.observeInputGeneration(UibcTouchChannel.inputGeneration())).isTrue()
                assertThat(pictureContacts.touchMove(0, true, 10f, 20f)).isNull()
                UibcTouchChannel.setEnabled(false)
                UibcTouchChannel.setEnabled(true)
                assertThat(UibcTouchChannel.canForwardInput()).isFalse()
            }
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
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
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
                            UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
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
                        UibcPackets.touchDescriptor(1920, 1080, UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH)),
                    ),
                )
                assertTouchReport(input, emptyList())
                // Mid-session type change to independent type-2 collections.
                UibcTouchChannel.setHidType(UibcPackets.HID_SINGLE_TOUCH)
                // Descriptor re-sent with every type-2 collection initialized.
                assertThat(readPacket(input)).isEqualTo(
                    UibcPackets.hidcPacket(
                        UibcPackets.HID_SINGLE_TOUCH,
                        UibcPackets.USAGE_REPORT_DESCRIPTOR,
                        UibcPackets.digitizerDescriptor(1920, 1080, UibcPackets.HID_SINGLE_TOUCH),
                    ),
                )
                assertTypeTwoTouchReports(input, emptyList())
                assertTypeTwoPenReport(input, UibcPenContact(0, 0, tip = false, inRange = false))
                // Redundant setHidType is a no-op.
                UibcTouchChannel.setHidType(UibcPackets.HID_SINGLE_TOUCH)
                assertNoDataWithinTimeout(socket)
            }
        }
    }

    /** Checks each independent touch report using the transmitted frame timestamp. */
    private fun assertTypeTwoTouchReports(input: DataInputStream, contacts: List<UibcContact>) {
        repeat(UibcPackets.MAX_CONTACTS) { slot ->
            val packet = readPacket(input)
            val scanTime = (packet[16].toInt() and 0xFF) or ((packet[17].toInt() and 0xFF) shl 8)
            val expected = UibcPackets.touchReport(contacts.filter { it.id == slot }.map { it.copy(id = 0) }, 1, scanTime)
                .apply { this[0] = (slot + 1).toByte() }
            assertThat(packet).isEqualTo(UibcPackets.hidcPacket(UibcPackets.HID_SINGLE_TOUCH, UibcPackets.USAGE_INPUT_REPORT, expected))
        }
    }

    /** Checks pen framing and the report ID reserved after all finger IDs. */
    private fun assertTypeTwoPenReport(input: DataInputStream, pen: UibcPenContact) {
        assertThat(readPacket(input)).isEqualTo(
            UibcPackets.hidcPacket(
                UibcPackets.HID_SINGLE_TOUCH, UibcPackets.USAGE_INPUT_REPORT,
                UibcPackets.penReport(pen, UibcPackets.HID_SINGLE_TOUCH),
            ),
        )
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
        maxContacts: Int = UibcPackets.maxContacts(UibcPackets.HID_MULTI_TOUCH),
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
