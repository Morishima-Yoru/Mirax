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
                0x05, 0x0D,
                0x09, 0x04,
                0xA1.toByte(), 0x01,
                0x85.toByte(), 0x01,
                0x09, 0x22,
                0xA1.toByte(), 0x02,
                0x09, 0x42,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0x75, 0x07,
                0x95.toByte(), 0x01,
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
                0x09, 0x54,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x08,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0xC0.toByte(),
            ),
        )
    }

    @Test
    fun touchReport_downAndLift_keepTheLastPixelWithTipClear() {
        val down = UibcPackets.touchReport(
            listOf(UibcContact(id = 0, x = 100, y = 200, tip = true)),
            maxContacts = 1,
        )
        assertThat(down).isEqualTo(
            byteArrayOf(0x01, 0x01, 0x00, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x01),
        )
        val lift = UibcPackets.touchReport(
            listOf(UibcContact(id = 0, x = 100, y = 200, tip = false)),
            maxContacts = 1,
        )
        assertThat(lift).isEqualTo(
            byteArrayOf(0x01, 0x00, 0x00, 0x64, 0x00, 0xC8.toByte(), 0x00, 0x01),
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
}
