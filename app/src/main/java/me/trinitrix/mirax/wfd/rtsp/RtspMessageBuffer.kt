package me.trinitrix.mirax.wfd.rtsp

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Accumulates bytes from the RTSP control connection and yields complete
 * messages: the header block plus a `Content-Length` body. Partial reads stay
 * buffered until the rest arrives.
 */
class RtspMessageBuffer {
    private val pending = ByteArrayOutputStream()

    /** Append [length] bytes from [data]. */
    fun append(data: ByteArray, length: Int) {
        pending.write(data, 0, length)
    }

    /**
     * Returns:
     *     The next complete message, or null when more bytes are needed.
     */
    fun next(): String? {
        val data = pending.toByteArray()
        val headerEnd = indexOfBlankLine(data)
        if (headerEnd < 0) {
            return null
        }
        val header = String(data, 0, headerEnd, StandardCharsets.US_ASCII)
        val bodyStart = headerEnd + 4
        val total = bodyStart + contentLength(header)
        if (data.size < total) {
            return null
        }
        pending.reset()
        pending.write(data, total, data.size - total)
        return String(data, 0, total, StandardCharsets.US_ASCII)
    }

    private fun contentLength(header: String): Int {
        for (line in header.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
        }
        return 0
    }

    private fun indexOfBlankLine(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) {
                return i
            }
        }
        return -1
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
        const val MAX_BODY = 1024 * 1024
    }
}
