package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.net.ServerSocket

class UibcTouchChannelTest {
    private var previousRunner: ((Runnable) -> Unit)? = null

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
}
