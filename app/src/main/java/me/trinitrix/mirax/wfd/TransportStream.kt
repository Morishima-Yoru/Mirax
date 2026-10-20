package me.trinitrix.mirax.wfd

import java.io.InputStream
import java.io.OutputStream

/**
 * Byte-stream abstraction for WFD RTSP control channel and RTP media delivery.
 *
 * Implements the architecture seam enabling deterministic in-memory regression tests
 * for Windows burst pacing and IDR packet drops without opening live network sockets.
 */
interface TransportStream {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isClosed: Boolean

    fun close()
}
