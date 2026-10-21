package me.trinitrix.mirax.wfd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Transport stream backed by byte arrays for deterministic exchanges.
 *
 * @param inputBytes Bytes supplied to the input stream.
 */
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

    /** @return Bytes written to the output stream. */
    fun writtenBytes(): ByteArray = output.toByteArray()

    /** @return Output decoded as US-ASCII text. */
    fun writtenAscii(): String = output.toString("US-ASCII")
}
