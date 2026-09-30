package me.trinitrix.mirax.wfd

/**
 * One contact inside a UIBC touch report.
 *
 * [id] is the slot in the report, from 0 up to the contact maximum minus one.
 * [x] and [y] are picture pixels, origin at the top left.
 */
data class UibcContact(
    val id: Int,
    val x: Int,
    val y: Int,
    val tip: Boolean,
)

/**
 * Wi-Fi Display UIBC HIDC packets for general touch.
 *
 * The 4-byte header is version 0, no timestamp, input category HIDC, and the
 * length of the whole TCP payload. The HIDC body is USB path, HID type,
 * usage (0 input report, 1 report descriptor), a big-endian length, then the
 * HID bytes. Touch uses a digitizer / touch-screen collection whose logical
 * range is the negotiated picture size.
 */
object UibcPackets {
    const val HID_SINGLE_TOUCH: Int = 2
    const val HID_MULTI_TOUCH: Int = 3
    const val USAGE_INPUT_REPORT: Int = 0
    const val USAGE_REPORT_DESCRIPTOR: Int = 1
    const val MAX_CONTACTS: Int = 10

    fun maxContacts(hidType: Int): Int {
        return if (hidType == HID_SINGLE_TOUCH) 1 else MAX_CONTACTS
    }

    /**
     * Touch-screen report descriptor for [pictureWidth] by [pictureHeight].
     */
    fun touchDescriptor(pictureWidth: Int, pictureHeight: Int, maxContacts: Int): ByteArray {
        val contacts = maxContacts.coerceIn(1, MAX_CONTACTS)
        val width = pictureWidth.coerceAtLeast(1)
        val height = pictureHeight.coerceAtLeast(1)
        val out = ArrayList<Int>()
        out.addAll(listOf(0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01, 0x85, 0x01))
        repeat(contacts) {
            out.addAll(listOf(0x09, 0x22, 0xA1, 0x02))
            out.addAll(listOf(0x09, 0x42, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02))
            out.addAll(listOf(0x75, 0x07, 0x95, 0x01, 0x81, 0x03))
            out.addAll(listOf(0x09, 0x51, 0x15, 0x00, 0x25, contacts - 1, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02))
            out.addAll(listOf(0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, width and 0xFF, (width shr 8) and 0xFF))
            out.addAll(listOf(0x75, 0x10, 0x95, 0x01, 0x81, 0x02))
            out.addAll(listOf(0x09, 0x31, 0x15, 0x00, 0x26, height and 0xFF, (height shr 8) and 0xFF))
            out.addAll(listOf(0x81, 0x02, 0xC0))
        }
        out.addAll(listOf(0x09, 0x54, 0x15, 0x00, 0x25, contacts, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02, 0xC0))
        return out.map { it.toByte() }.toByteArray()
    }

    /**
     * One input report. [contacts] may include a lift (tip clear). Slots that
     * are absent stay clear and are not part of the contact count.
     */
    fun touchReport(contacts: List<UibcContact>, maxContacts: Int): ByteArray {
        val count = maxContacts.coerceIn(1, MAX_CONTACTS)
        val byId = HashMap<Int, UibcContact>()
        for (contact in contacts) {
            if (contact.id in 0 until count) {
                byId[contact.id] = contact
            }
        }
        val out = ByteArray(1 + count * 6 + 1)
        out[0] = 1
        var reported = 0
        for (slot in 0 until count) {
            val contact = byId[slot]
            val offset = 1 + slot * 6
            if (contact != null) {
                reported++
                out[offset] = if (contact.tip) 1 else 0
                out[offset + 1] = slot.toByte()
                putLe16(out, offset + 2, contact.x)
                putLe16(out, offset + 4, contact.y)
            } else {
                out[offset + 1] = slot.toByte()
            }
        }
        out[out.size - 1] = reported.toByte()
        return out
    }

    /**
     * Wrap a HID payload as one UIBC HIDC TCP message.
     */
    fun hidcPacket(hidType: Int, usage: Int, payload: ByteArray): ByteArray {
        val total = 4 + 5 + payload.size
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
