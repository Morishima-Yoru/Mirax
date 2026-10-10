package me.trinitrix.mirax.wfd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class InMemoryTransportStream(
    inputBytes: ByteArray = ByteArray(0),
) : TransportStream {
    private val input = ByteArrayInputStream(inputBytes)
    private val output = ByteArrayOutputStream()
    private var closed = false

    override val inputStream: InputStream get() = input
    override val outputStream: OutputStream get() = output
    override val isClosed: Boolean get() = closed

    override fun close() {
        closed = true
    }

    fun writtenBytes(): ByteArray = output.toByteArray()

    fun writtenAscii(): String = output.toString("US-ASCII")
}
