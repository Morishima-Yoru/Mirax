package me.trinitrix.mirax.wfd

import me.trinitrix.mirax.wfd.rtsp.WfdEdid

/**
 * One contact inside a UIBC touch report.
 *
 * [id] is the stable contact identifier, from 0 up to the contact maximum minus
 * one. It follows the contact and is independent of the entry's position in the
 * packed report.
 * [x] and [y] are picture pixels, origin at the top left.
 */
data class UibcContact(
    val id: Int,
    val x: Int,
    val y: Int,
    val tip: Boolean,
)

/**
 * One contact inside a UIBC pen / stylus report.
 *
 * [x] and [y] are picture pixels, origin at the top left.
 * [tip] indicates contact with the screen surface.
 * [inRange] indicates whether the pen is detectable (hovering or touching).
 * [barrel] indicates the side button is depressed.
 * [eraser] indicates eraser mode or eraser button is active.
 * [pressure] is the tip pressure, normalized to 0..4095.
 */
data class UibcPenContact(
    val x: Int,
    val y: Int,
    val tip: Boolean,
    val inRange: Boolean,
    val barrel: Boolean = false,
    val eraser: Boolean = false,
    val pressure: Int = 0,
)

/**
 * Wi-Fi Display UIBC HIDC packets for general touch and pen / stylus.
 *
 * The 4-byte header is version 0, no timestamp, input category HIDC, and the
 * length of the whole TCP payload. The HIDC body is USB path, HID type,
 * usage (0 input report, 1 report descriptor), a big-endian length, then the
 * HID bytes. Touch uses a digitizer / touch-screen collection whose logical
 * range is the negotiated picture size, accompanied by an integrated pen collection.
 */
object UibcPackets {
    const val HID_SINGLE_TOUCH: Int = 2
    const val HID_MULTI_TOUCH: Int = 3
    const val USAGE_INPUT_REPORT: Int = 0
    const val USAGE_REPORT_DESCRIPTOR: Int = 1
    const val MAX_CONTACTS: Int = 10
    const val MAX_PEN_PRESSURE: Int = 4095

    fun maxContacts(hidType: Int): Int {
        return if (hidType == HID_SINGLE_TOUCH) 1 else MAX_CONTACTS
    }

    /**
     * Touch-screen report descriptor for [pictureWidth] by [pictureHeight].
     *
     * One application collection with Report ID 1 for contact input. Adding a
     * feature report or a second top-level collection prevents Windows MiraDisp
     * from forwarding touch on the tested source.
     */
    fun touchDescriptor(pictureWidth: Int, pictureHeight: Int, maxContacts: Int): ByteArray {
        val contacts = maxContacts.coerceIn(1, MAX_CONTACTS)
        val width = pictureWidth.coerceAtLeast(1)
        val height = pictureHeight.coerceAtLeast(1)
        val out = ArrayList<Int>()
        out.addAll(listOf(0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01, 0x85, 0x01))
        repeat(contacts) {
            // X/Y switch the page to Generic Desktop. Each finger has to
            // select Digitizer again or later contacts are not fingers.
            out.addAll(listOf(0x05, 0x0D, 0x09, 0x22, 0xA1, 0x02))
            out.addAll(listOf(0x09, 0x42, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02))
            // In Range and Confidence share the flag byte with Tip. Windows drops
            // a contact that never sets In Range, and ignores tip-down without Confidence.
            out.addAll(listOf(0x09, 0x32, 0x81, 0x02))
            out.addAll(listOf(0x09, 0x47, 0x81, 0x02))
            out.addAll(listOf(0x95, 0x05, 0x81, 0x03))
            out.addAll(listOf(0x09, 0x51, 0x15, 0x00, 0x25, contacts - 1, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02))
            // Restore the globals after Y so coordinate units do not apply to the next finger's flags.
            out.addAll(listOf(0xA4, 0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, width and 0xFF, (width shr 8) and 0xFF))
            out.addAll(listOf(0x35, 0x00, 0x46, WfdEdid.H_IMAGE_MM and 0xFF, WfdEdid.H_IMAGE_MM shr 8, 0x55, 0x0F, 0x65, 0x11))
            out.addAll(listOf(0x75, 0x10, 0x95, 0x01, 0x81, 0x02))
            out.addAll(listOf(0x09, 0x31, 0x15, 0x00, 0x26, height and 0xFF, (height shr 8) and 0xFF))
            out.addAll(listOf(0x46, WfdEdid.V_IMAGE_MM and 0xFF, WfdEdid.V_IMAGE_MM shr 8))
            out.addAll(listOf(0x81, 0x02, 0xB4, 0xC0))
        }
        // Scan Time: Usage 0x56, 16-bit, in 100-microsecond units (mandatory for Windows touch driver)
        out.addAll(listOf(
            0x05, 0x0D,        // USAGE_PAGE (Digitizers)
            0x55, 0x0C,        // UNIT_EXPONENT (-4)
            0x66, 0x01, 0x10,  // UNIT (Seconds)
            0x47, 0xFF, 0xFF, 0x00, 0x00, // PHYSICAL_MAXIMUM (65535)
            0x27, 0xFF, 0xFF, 0x00, 0x00, // LOGICAL_MAXIMUM (65535)
            0x75, 0x10,        // REPORT_SIZE (16)
            0x95, 0x01,        // REPORT_COUNT (1)
            0x09, 0x56,        // USAGE (Scan Time)
            0x81, 0x02,        // INPUT (Data, Var, Abs)
            0x55, 0x00,
            0x65, 0x00,
            0x35, 0x00,
            0x45, 0x00,
            0x09, 0x54,        // USAGE (Contact count)
            0x15, 0x00,        // LOGICAL_MINIMUM (0)
            0x25, contacts,    // LOGICAL_MAXIMUM (contacts)
            0x75, 0x08,        // REPORT_SIZE (8)
            0x95, 0x01,        // REPORT_COUNT (1)
            0x81, 0x02,        // INPUT (Data, Var, Abs)
            0xC0,              // END_COLLECTION (Touch Screen Application)
        ))
        // One application collection only. Windows MiraDisp does not create the
        // virtual HID device when a second pen collection is appended here.
        return out.map { it.toByte() }.toByteArray()
    }

    fun currentScanTime(): Int {
        return ((System.nanoTime() / 100_000) and 0xFFFF).toInt()
    }

    /**
     * One input report. [contacts] may include a lift (tip clear). Valid
     * contacts are packed into the first Contact Count finger entries in
     * ascending [UibcContact.id] order; entries past the reported count stay
     * zero.
     */
    fun touchReport(
        contacts: List<UibcContact>,
        maxContacts: Int,
        scanTimeUs100: Int = currentScanTime(),
    ): ByteArray {
        val count = maxContacts.coerceIn(1, MAX_CONTACTS)
        val byId = HashMap<Int, UibcContact>()
        for (contact in contacts) {
            if (contact.id in 0 until count) {
                byId[contact.id] = contact
            }
        }
        val out = ByteArray(1 + count * 6 + 2 + 1)
        out[0] = 1
        var reported = 0
        for (id in 0 until count) {
            val contact = byId[id] ?: continue
            val offset = 1 + reported * 6
            out[offset] = if (contact.tip) 0x07 else 0x06
            out[offset + 1] = contact.id.toByte()
            putLe16(out, offset + 2, contact.x)
            putLe16(out, offset + 4, contact.y)
            reported++
        }
        val scanOffset = 1 + count * 6
        putLe16(out, scanOffset, scanTimeUs100 and 0xFFFF)
        out[scanOffset + 2] = reported.toByte()
        return out
    }

    /**
     * One pen / stylus input report (Report ID 2).
     */
    fun penReport(pen: UibcPenContact): ByteArray {
        val out = ByteArray(8)
        out[0] = 2
        var flags = 0
        if (pen.tip) flags = flags or 0x01
        if (pen.barrel) flags = flags or 0x02
        if (pen.eraser) flags = flags or 0x0C // bits 2 (Invert) and 3 (Eraser)
        if (pen.inRange) flags = flags or 0x20 // bit 5 is In Range in Windows Pen TLC
        out[1] = flags.toByte()
        putLe16(out, 2, pen.x)
        putLe16(out, 4, pen.y)
        putLe16(out, 6, pen.pressure.coerceIn(0, MAX_PEN_PRESSURE))
        return out
    }

    /**
     * Wrap a HID payload as one UIBC HIDC TCP message.
     */
    fun hidcPacket(hidType: Int, usage: Int, payload: ByteArray): ByteArray {
        val bodyLen = 5 + payload.size
        val pad = if (bodyLen % 2 != 0) 1 else 0
        val total = 4 + bodyLen + pad
        val out = ByteArray(total)
        out[0] = 0
        out[1] = 1
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = 1
        out[5] = hidType.toByte()
        out[6] = usage.toByte()
        out[7] = ((payload.size shr 8) and 0xFF).toByte()
        out[8] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 9)
        return out
    }

    private fun putLe16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
