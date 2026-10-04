package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.PictureTouchMap
import org.junit.Test

class UibcPacketsTest {
    @Test
    fun hidcPacket_isVersionZeroHidc_withLengthOfTheWholeMessage() {
        val packet = UibcPackets.hidcPacket(
            hidType = UibcPackets.HID_MULTI_TOUCH,
            usage = UibcPackets.USAGE_INPUT_REPORT,
            payload = byteArrayOf(0x0A),
        )
        assertThat(packet).isEqualTo(
            byteArrayOf(
                0x00, 0x01, 0x00, 0x0A,
                0x01, 0x03, 0x00, 0x00, 0x01,
                0x0A,
            ),
        )
    }

    @Test
    fun touchDescriptor_for1080pOneContact_matchesTheDigitizerCollection() {
        val descriptor = UibcPackets.touchDescriptor(1920, 1080, maxContacts = 1)
        assertThat(descriptor).isEqualTo(
            byteArrayOf(
                // Touch Screen (Report ID 1)
                0x05, 0x0D,
                0x09, 0x04,
                0xA1.toByte(), 0x01,
                0x85.toByte(), 0x01,
                0x05, 0x0D,
                0x09, 0x22,
                0xA1.toByte(), 0x02,
                0x09, 0x42,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0x09, 0x32,
                0x81.toByte(), 0x02,
                0x95.toByte(), 0x06,
                0x81.toByte(), 0x03,
                0x09, 0x51,
                0x15, 0x00,
                0x25, 0x00,
                0x75, 0x08,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0x05, 0x01,
                0x09, 0x30,
                0x15, 0x00,
                0x26, 0x80.toByte(), 0x07,
                0x75, 0x10,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0x09, 0x31,
                0x15, 0x00,
                0x26, 0x38, 0x04,
                0x81.toByte(), 0x02,
                0xC0.toByte(),
                // Scan Time (16-bit LE)
                0x05, 0x0D,
                0x55, 0x0C,
                0x66, 0x01, 0x10,
                0x47, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
                0x27, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
                0x75, 0x10,
                0x95.toByte(), 0x01,
                0x09, 0x56,
                0x81.toByte(), 0x02,
                // Contact Count
                0x09, 0x54,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x08,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0x85.toByte(), 0x03,
                0x09, 0x55,
                0xB1.toByte(), 0x02,
                0xC0.toByte(),
            ),
        )
    }

    @Test
    fun touchDescriptor_laterFingerAndContactCountStayOnTheDigitizerPage() {
        val descriptor = UibcPackets.touchDescriptor(100, 100, maxContacts = 2)
        assertThat(indexOf(descriptor, byteArrayOf(0xC0.toByte(), 0x05, 0x0D, 0x09, 0x22))).isAtLeast(0)
        assertThat(indexOf(descriptor, byteArrayOf(0xC0.toByte(), 0x05, 0x0D, 0x55.toByte(), 0x0C))).isAtLeast(0)
        assertThat(indexOf(descriptor, byteArrayOf(0x09, 0x54, 0x15, 0x00))).isAtLeast(0)
        assertThat(indexOf(descriptor, byteArrayOf(0x09, 0x02, 0xA1.toByte(), 0x01))).isEqualTo(-1)
    }

    @Test
    fun touchDescriptor_contactCountMaximumAndPenUnitsMatchTheInputReport() {
        val contacts = 10
        val descriptor = UibcPackets.touchDescriptor(1812, 2176, contacts)
        assertThat(
            indexOf(descriptor, byteArrayOf(0x85.toByte(), 0x03, 0x09, 0x55, 0xB1.toByte(), 0x02)),
        ).isAtLeast(0)
        assertThat(indexOf(descriptor, byteArrayOf(0x09, 0x02, 0xA1.toByte(), 0x01))).isEqualTo(-1)
        val touchBits = reportBits(descriptor, reportId = 1, feature = false)
        assertThat(touchBits % 8).isEqualTo(0)
        val report = UibcPackets.touchReport(emptyList(), contacts, scanTimeUs100 = 0)
        assertThat(report.size).isEqualTo(1 + touchBits / 8)
    }

    @Test
    fun touchReport_downAndLift_keepTheLastPixelWithTipClear() {
        val down = UibcPackets.touchReport(
            listOf(UibcContact(id = 0, x = 100, y = 200, tip = true)),
            maxContacts = 1,
            scanTimeUs100 = 0x1234,
        )
        assertThat(down).isEqualTo(
            byteArrayOf(0x01, 0x03, 0x00, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x34, 0x12, 0x01),
        )
        val lift = UibcPackets.touchReport(
            listOf(UibcContact(id = 0, x = 100, y = 200, tip = false)),
            maxContacts = 1,
            scanTimeUs100 = 0x1234,
        )
        assertThat(lift).isEqualTo(
            byteArrayOf(0x01, 0x00, 0x00, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x34, 0x12, 0x01),
        )
    }

    @Test
    fun penReport_hover_setsInRangeOnly() {
        val hover = UibcPackets.penReport(
            UibcPenContact(x = 100, y = 200, tip = false, inRange = true, pressure = 0),
        )
        assertThat(hover).isEqualTo(
            byteArrayOf(0x02, 0x20, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00),
        )
    }

    @Test
    fun penReport_down_setsTipInRangeAndPressure() {
        val down = UibcPackets.penReport(
            UibcPenContact(x = 100, y = 200, tip = true, inRange = true, pressure = 2048),
        )
        assertThat(down).isEqualTo(
            byteArrayOf(0x02, 0x21, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x08),
        )
    }

    @Test
    fun penReport_barrelAndEraser_setsProperFlags() {
        val report = UibcPackets.penReport(
            UibcPenContact(
                x = 10,
                y = 20,
                tip = true,
                inRange = true,
                barrel = true,
                eraser = true,
                pressure = 1000,
            ),
        )
        assertThat(report).isEqualTo(
            byteArrayOf(0x02, 0x2F, 0x0A, 0x00, 0x14, 0x00, 0xE8.toByte(), 0x03),
        )
    }

    @Test
    fun penReport_outOfRange_clearsFlags() {
        val outOfRange = UibcPackets.penReport(
            UibcPenContact(x = 100, y = 200, tip = false, inRange = false, pressure = 0),
        )
        assertThat(outOfRange).isEqualTo(
            byteArrayOf(0x02, 0x00, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00),
        )
    }

    @Test
    fun pictureTouch_mapsTheViewOntoPicturePixels_andRejectsOutside() {
        assertThat(PictureTouchMap.pixel(50f, 100, 200)).isEqualTo(100)
        assertThat(PictureTouchMap.pixel(0f, 100, 1920)).isEqualTo(0)
        assertThat(PictureTouchMap.pixel(100f, 100, 1920)).isEqualTo(1920)
        assertThat(PictureTouchMap.pixel(-1f, 100, 200)).isNull()
        assertThat(PictureTouchMap.pixel(101f, 100, 200)).isNull()
    }

    private fun reportBits(descriptor: ByteArray, reportId: Int, feature: Boolean): Int {
        var index = 0
        var reportSize = 0
        var reportCount = 0
        var currentId = 0
        var bits = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            index++
            val size = when (prefix and 0x03) {
                0 -> 0
                1 -> 1
                2 -> 2
                else -> 4
            }
            var value = 0
            for (shift in 0 until size) {
                value = value or ((descriptor[index].toInt() and 0xFF) shl (8 * shift))
                index++
            }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (type == 1 && tag == 7) {
                reportSize = value
            } else if (type == 1 && tag == 8) {
                currentId = value
            } else if (type == 1 && tag == 9) {
                reportCount = value
            } else if (type == 0 && tag == 8 && !feature && currentId == reportId) {
                bits += reportSize * reportCount
            } else if (type == 0 && tag == 11 && feature && currentId == reportId) {
                bits += reportSize * reportCount
            }
        }
        return bits
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) {
            return -1
        }
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return start
            }
        }
        return -1
    }
}
