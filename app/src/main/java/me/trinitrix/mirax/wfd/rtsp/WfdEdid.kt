package me.trinitrix.mirax.wfd.rtsp

import me.trinitrix.mirax.session.VideoMode
import java.nio.charset.StandardCharsets

/**
 * One-block EDID whose preferred detailed timing matches [mode].
 * Timings use CVT reduced blanking with a 160-pixel horizontal blank.
 */
object WfdEdid {
    const val H_BLANK: Int = 160
    const val V_BLANK: Int = 52
    const val H_SYNC_OFFSET: Int = 48
    const val H_SYNC_WIDTH: Int = 32
    const val V_SYNC_OFFSET: Int = 3
    const val V_SYNC_WIDTH: Int = 6
    const val H_IMAGE_MM: Int = 146
    const val V_IMAGE_MM: Int = 122

    fun pixelClock10kHz(mode: VideoMode): Int {
        val hTotal = mode.width + H_BLANK
        val vTotal = mode.height + V_BLANK
        val hz = hTotal.toLong() * vTotal * mode.refreshHz
        return ((hz + 5000L) / 10000L).toInt()
    }

    fun block(mode: VideoMode, monitorName: String = "Mirax"): ByteArray {
        val e = ByteArray(128)
        e[0] = 0x00
        e[1] = 0xFF.toByte()
        e[2] = 0xFF.toByte()
        e[3] = 0xFF.toByte()
        e[4] = 0xFF.toByte()
        e[5] = 0xFF.toByte()
        e[6] = 0xFF.toByte()
        e[7] = 0x00
        e[8] = 0x68
        e[9] = 0xC4.toByte()
        e[10] = 0x01
        e[11] = 0x00
        e[16] = 27
        e[17] = (2026 - 1990).toByte()
        e[18] = 1
        e[19] = 3
        e[20] = 0x80.toByte()
        e[21] = 15
        e[22] = 12
        e[23] = 120
        e[24] = 0x0A
        e[25] = 0xEE.toByte()
        e[26] = 0x91.toByte()
        e[27] = 0xA3.toByte()
        e[28] = 0x54
        e[29] = 0x4C
        e[30] = 0x99.toByte()
        e[31] = 0x26
        e[32] = 0x0F
        e[33] = 0x50
        e[34] = 0x54
        var i = 38
        while (i < 54) {
            e[i] = 0x01
            e[i + 1] = 0x01
            i += 2
        }
        putTiming(e, 54, mode)
        putDescriptor(e, 72, 0xFC.toByte(), monitorName.take(13))
        putRange(e, 90)
        putDescriptorHeader(e, 108, 0x10.toByte())
        var sum = 0
        for (idx in 0 until 127) {
            sum = (sum + (e[idx].toInt() and 0xFF)) and 0xFF
        }
        e[127] = ((256 - sum) and 0xFF).toByte()
        return e
    }

    fun parameterValue(mode: VideoMode, monitorName: String = "Mirax"): String {
        val block = block(mode, monitorName)
        val hex = StringBuilder(5 + block.size * 2)
        hex.append("0001 ")
        for (b in block) {
            hex.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return hex.toString()
    }

    private fun putTiming(e: ByteArray, o: Int, mode: VideoMode) {
        val clock = pixelClock10kHz(mode)
        val ha = mode.width
        val hb = H_BLANK
        val va = mode.height
        val vb = V_BLANK
        val hso = H_SYNC_OFFSET
        val hsw = H_SYNC_WIDTH
        val vso = V_SYNC_OFFSET
        val vsw = V_SYNC_WIDTH
        e[o] = (clock and 0xFF).toByte()
        e[o + 1] = ((clock shr 8) and 0xFF).toByte()
        e[o + 2] = (ha and 0xFF).toByte()
        e[o + 3] = (hb and 0xFF).toByte()
        e[o + 4] = ((((ha shr 8) and 0x0F) shl 4) or ((hb shr 8) and 0x0F)).toByte()
        e[o + 5] = (va and 0xFF).toByte()
        e[o + 6] = (vb and 0xFF).toByte()
        e[o + 7] = ((((va shr 8) and 0x0F) shl 4) or ((vb shr 8) and 0x0F)).toByte()
        e[o + 8] = (hso and 0xFF).toByte()
        e[o + 9] = (hsw and 0xFF).toByte()
        e[o + 10] = (((vso and 0x0F) shl 4) or (vsw and 0x0F)).toByte()
        e[o + 11] = ((((hso shr 8) and 0x03) shl 6)
            or (((hsw shr 8) and 0x03) shl 4)
            or (((vso shr 4) and 0x03) shl 2)
            or ((vsw shr 4) and 0x03)).toByte()
        e[o + 12] = (H_IMAGE_MM and 0xFF).toByte()
        e[o + 13] = (V_IMAGE_MM and 0xFF).toByte()
        e[o + 14] = ((((H_IMAGE_MM shr 8) and 0x0F) shl 4) or ((V_IMAGE_MM shr 8) and 0x0F)).toByte()
        e[o + 17] = 0x1E
    }

    private fun putDescriptorHeader(e: ByteArray, o: Int, tag: Byte) {
        e[o + 3] = tag
    }

    private fun putDescriptor(e: ByteArray, o: Int, tag: Byte, text: String) {
        putDescriptorHeader(e, o, tag)
        val raw = text.toByteArray(StandardCharsets.US_ASCII)
        val n = minOf(13, raw.size)
        System.arraycopy(raw, 0, e, o + 5, n)
        if (n < 13) {
            e[o + 5 + n] = 0x0A
            for (i in n + 1 until 13) {
                e[o + 5 + i] = 0x20
            }
        }
    }

    private fun putRange(e: ByteArray, o: Int) {
        putDescriptorHeader(e, o, 0xFD.toByte())
        e[o + 5] = 30
        e[o + 6] = 75
        e[o + 7] = 50
        e[o + 8] = 120
        e[o + 9] = 27
        e[o + 11] = 0x0A
        for (i in 12 until 18) {
            e[o + i] = 0x20
        }
    }
}
