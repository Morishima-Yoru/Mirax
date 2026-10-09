package me.trinitrix.mirax.wfd

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parse [MS-WDHCE] Microsoft hardware-cursor UDP payloads (after the 12-byte RTP header).
 */
object MicrosoftCursorPackets {
    const val RTP_HEADER_BYTES: Int = 12
    const val MSG_POSITION: Int = 0x01
    const val MSG_SHAPE_START: Int = 0x02
    const val MSG_SHAPE_CONTINUE: Int = 0x03

    const val IMAGE_DISABLED: Int = 0x01
    const val IMAGE_MASKED_COLOR_PNG: Int = 0x02
    const val IMAGE_COLOR_PNG: Int = 0x03

    sealed class Message {
        data class Position(val x: Int, val y: Int) : Message()

        data class ShapeStart(
            val totalImageBytes: Int,
            val imageId: Int,
            val x: Int,
            val y: Int,
            val imageType: Int,
            val hotspotX: Int,
            val hotspotY: Int,
            val imageChunk: ByteArray,
        ) : Message()

        data class ShapeContinue(
            val totalImageBytes: Int,
            val imageId: Int,
            val payloadOffset: Int,
            val imageChunk: ByteArray,
        ) : Message()
    }

    /**
     * Parse one UDP datagram into a cursor message.
     *
     * Args:
     *     packet: Full UDP payload including RTP header.
     *     length: Valid byte count in [packet].
     *
     * Returns:
     *     Parsed message, or null when the packet is truncated or unknown.
     */
    fun parse(packet: ByteArray, length: Int): Message? {
        parseAt(packet, length, RTP_HEADER_BYTES)?.let { return it }
        // Some stacks omit the RTP header; accept bare MS-WDHCE payloads.
        parseAt(packet, length, 0)?.let { return it }
        // Windows 11 often sends fixed 60-byte cursor datagrams that are not
        // cleartext MS-WDHCE. Heuristic: uint32 BE x/y after a 12-byte RTP-like
        // header + 8-byte prefix, when both fall inside a 8K desktop.
        return parseWin11Heuristic(packet, length)
    }

    private fun parseWin11Heuristic(packet: ByteArray, length: Int): Message? {
        // Only probe fixed-size Win11 datagrams; random RTP would false-match.
        if (length != 60 && length != 48) {
            return null
        }
        val candidates = intArrayOf(12, 16, 20, 8, 4)
        for (off in candidates) {
            if (off + 8 > length) {
                continue
            }
            for (order in arrayOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                val x = ByteBuffer.wrap(packet, off, 4).order(order).int
                val y = ByteBuffer.wrap(packet, off + 4, 4).order(order).int
                if (x in 0..8192 && y in 0..8192) {
                    return Message.Position(x, y)
                }
                val xs = ByteBuffer.wrap(packet, off, 2).order(order).short.toInt() and 0xFFFF
                val ys = ByteBuffer.wrap(packet, off + 2, 2).order(order).short.toInt() and 0xFFFF
                if (xs in 0..8192 && ys in 0..8192) {
                    return Message.Position(xs, ys)
                }
            }
        }
        return null
    }

    private fun parseAt(packet: ByteArray, length: Int, offset: Int): Message? {
        if (length < offset + 3) {
            return null
        }
        val body = ByteBuffer.wrap(packet, offset, length - offset)
            .order(ByteOrder.BIG_ENDIAN)
        val msgType = body.get().toInt() and 0xFF
        if (msgType !in MSG_POSITION..MSG_SHAPE_CONTINUE) {
            return null
        }
        val packetMsgSize = body.short.toInt() and 0xFFFF
        val payloadBytes = packetMsgSize - 3
        if (payloadBytes < 0 || body.remaining() < payloadBytes) {
            return null
        }
        return when (msgType) {
            MSG_POSITION -> {
                if (payloadBytes < 4) {
                    return null
                }
                val x = body.short.toInt()
                val y = body.short.toInt()
                Message.Position(x, y)
            }
            MSG_SHAPE_START -> {
                if (payloadBytes < 15) {
                    return null
                }
                val total = body.int
                val imageId = body.short.toInt() and 0xFFFF
                val x = body.short.toInt()
                val y = body.short.toInt()
                val imageType = body.get().toInt() and 0xFF
                val hotspotX = body.short.toInt() and 0xFFFF
                val hotspotY = body.short.toInt() and 0xFFFF
                val chunkLen = payloadBytes - 15
                val chunk = ByteArray(chunkLen.coerceAtLeast(0))
                if (chunk.isNotEmpty()) {
                    body.get(chunk)
                }
                Message.ShapeStart(total, imageId, x, y, imageType, hotspotX, hotspotY, chunk)
            }
            MSG_SHAPE_CONTINUE -> {
                if (payloadBytes < 10) {
                    return null
                }
                val total = body.int
                val imageId = body.short.toInt() and 0xFFFF
                val offset = body.int
                val chunkLen = payloadBytes - 10
                val chunk = ByteArray(chunkLen.coerceAtLeast(0))
                if (chunk.isNotEmpty()) {
                    body.get(chunk)
                }
                Message.ShapeContinue(total, imageId, offset, chunk)
            }
            else -> null
        }
    }
}
