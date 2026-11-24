package me.trinitrix.mirax.data.standby

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof that animation needs a real decodable first frame.
 *
 * Valid two-frame GIF must classify as GIF; the animated-header fixture with
 * a damaged first-frame LZW payload must not classify (rejected to `null`,
 * never a stale GIF that would replace the background). Both tests first
 * assert the animated header natively, and the damaged case additionally
 * asserts the first frame really fails to decode, so neither can go green
 * when the fixture stops reproducing the bug. Fixtures live only in this
 * run's app-private cache and are deleted here; no user media is touched.
 * Written only; device runs are owned by the main agent.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStandbyMediaValidatorAnimatedTest {

    private val validator = AndroidStandbyMediaValidator()

    @Test
    fun validTwoFrameGif_reportsGif() = runBlocking {
        val bytes = validGifBytes()
        assertHeaderIsAnimated(bytes)
        val target = writeBytes("validator-animated-valid", bytes)
        try {
            assertEquals(StandbyMediaKind.GIF, validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun damagedFirstFrameGif_rejectedNotGif() = runBlocking {
        val bytes = corruptedFirstFrameGifBytes()
        // Header still parses as animated (NETSCAPE extension + two image
        // descriptors intact); only the first frame LZW payload is damaged.
        // Prove both premises natively before checking the validator, so this
        // cannot go green when the fixture stops reproducing the bug.
        assertHeaderIsAnimated(bytes)
        assertFirstFrameDecodeFails(bytes)
        val target = writeBytes("validator-animated-truncated", bytes)
        try {
            assertNull(validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    /**
     * Keeps the animated container intact, damages only the first frame payload.
     *
     * Verified layout of the 85-byte two-frame fixture (zero-based):
     * header `GIF89a` 0..5, GCT 13..18, NETSCAPE extension 19..37, first GCE
     * 38..45, first image descriptor `0x2C` at 46, LZW minimum 54, data
     * sub-block size `0x02` at 57, payload 58..59 (`0x44 0x01`), terminator
     * `0x00` at 60, second GCE 61..68, second descriptor `0x2C` at 69,
     * trailer `0x3B` at 84. Only 58..59 are replaced with `0xFF 0xFF`.
     *
     * @return 85-byte GIF with animated header but undecodable first frame.
     */
    private fun corruptedFirstFrameGifBytes(): ByteArray {
        val bytes = validGifBytes()
        check(bytes.size == 85) { "base fixture changed length: ${bytes.size}" }
        check(bytes[46] == 0x2c.toByte()) { "first image descriptor moved" }
        check(bytes[57] == 0x02.toByte()) { "first frame block size moved" }
        check(bytes[60] == 0x00.toByte()) { "first frame terminator moved" }
        check(bytes[69] == 0x2c.toByte()) { "second image descriptor moved" }
        check(bytes[84] == 0x3b.toByte()) { "trailer moved" }
        return bytes.copyOf().also {
            it[58] = 0xff.toByte()
            it[59] = 0xff.toByte()
        }
    }

    /**
     * Proves the corrupted fixture still reports an animated header.
     *
     * @param bytes corrupted animated GIF bytes.
     */
    private fun assertHeaderIsAnimated(bytes: ByteArray) {
        var headerAnimated = false
        var headerSeen = false
        try {
            val source = android.graphics.ImageDecoder.createSource(bytes)
            android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                headerSeen = true
                headerAnimated = info.isAnimated
                decoder.setTargetSize(1, 1)
                decoder.memorySizePolicy = android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        } catch (e: org.junit.AssumptionViolatedException) {
            throw e
        } catch (e: Exception) {
            // Expected when the damaged first frame cannot decode; the header
            // flag captured above is the assertion subject, not this throw.
        }
        org.junit.Assert.assertTrue("corrupted fixture header not seen", headerSeen)
        org.junit.Assert.assertTrue(
            "corrupted fixture lost animation header; regression premise void",
            headerAnimated,
        )
    }

    /**
     * Proves the damaged first frame really cannot decode to a usable bitmap.
     *
     * @param bytes corrupted animated GIF bytes.
     */
    private fun assertFirstFrameDecodeFails(bytes: ByteArray) {
        try {
            val source = android.graphics.ImageDecoder.createSource(bytes)
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(1, 1)
                decoder.memorySizePolicy = android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                org.junit.Assume.assumeTrue(
                    "first frame unexpectedly decodable " +
                        "(${bitmap.width}x${bitmap.height}); premise void, no rejection proof",
                    false,
                )
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (e: org.junit.AssumptionViolatedException) {
            throw e
        } catch (e: Exception) {
            // Premise holds: damaged payload throws during real decode.
        }
    }

    private fun validGifBytes(): ByteArray {
        return Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
    }

    private fun writeBytes(name: String, bytes: ByteArray): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File.createTempFile(name, ".probe", context.cacheDir)
        target.deleteOnExit()
        target.writeBytes(bytes)
        return target
    }
}
