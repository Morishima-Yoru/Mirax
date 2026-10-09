package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicrosoftCursorPacketsTest {
    @Test
    fun parsePosition_readsSignedCoordsAfterRtpHeader() {
        val payload = ByteBuffer.allocate(12 + 7).order(ByteOrder.BIG_ENDIAN)
        // Minimal RTP header (12 zero bytes already).
        payload.position(12)
        payload.put(0x01)
        payload.putShort(0x0007)
        payload.putShort(13)
        payload.putShort(10)
        val packet = payload.array()

        val msg = MicrosoftCursorPackets.parse(packet, packet.size)
        assertThat(msg).isInstanceOf(MicrosoftCursorPackets.Message.Position::class.java)
        val pos = msg as MicrosoftCursorPackets.Message.Position
        assertThat(pos.x).isEqualTo(13)
        assertThat(pos.y).isEqualTo(10)
    }

    @Test
    fun parseShapeStart_readsHeaderAndChunk() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val packetMsgSize = (3 + 15 + image.size).toShort()
        val payload = ByteBuffer.allocate(12 + packetMsgSize).order(ByteOrder.BIG_ENDIAN)
        payload.position(12)
        payload.put(0x02)
        payload.putShort(packetMsgSize)
        payload.putInt(image.size)
        payload.putShort(0x1234)
        payload.putShort(12)
        payload.putShort(10)
        payload.put(0x03)
        payload.putShort(18)
        payload.putShort(15)
        payload.put(image)
        val packet = payload.array()

        val msg = MicrosoftCursorPackets.parse(packet, packet.size)
        assertThat(msg).isInstanceOf(MicrosoftCursorPackets.Message.ShapeStart::class.java)
        val shape = msg as MicrosoftCursorPackets.Message.ShapeStart
        assertThat(shape.totalImageBytes).isEqualTo(image.size)
        assertThat(shape.imageId).isEqualTo(0x1234)
        assertThat(shape.x).isEqualTo(12)
        assertThat(shape.y).isEqualTo(10)
        assertThat(shape.imageType).isEqualTo(MicrosoftCursorPackets.IMAGE_COLOR_PNG)
        assertThat(shape.hotspotX).isEqualTo(18)
        assertThat(shape.hotspotY).isEqualTo(15)
        assertThat(shape.imageChunk).isEqualTo(image)
    }

    @Test
    fun capabilityValue_advertisesPortAndMaxEdge() {
        assertThat(MicrosoftCursorChannel.capabilityValue())
            .isEqualTo("full 0x0040 0x0040 4a39")
    }
}
