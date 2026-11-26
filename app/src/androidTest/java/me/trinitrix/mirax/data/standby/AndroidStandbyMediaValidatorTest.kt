package me.trinitrix.mirax.data.standby

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device validation for the content-based standby media validator.
 *
 * Fixture bytes are copied only into this run's app-private cache files; no
 * user storage, WFD state, or other shared settings are touched. Cleanup is
 * confined to files created here. The optional video frame check requires the
 * parent-supplied `standby_probe_video_uri` instrumentation argument pointing
 * at a provisioned synthetic MP4; without it the video test assumes away.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStandbyMediaValidatorTest {

    private val validator = AndroidStandbyMediaValidator()

    @Test
    fun pngBytes_decodeToImage() = runBlocking {
        val target = writeFixture("validator-png", StandbyMediaProbeFixtures.SMALL_PNG_BASE64)
        try {
            assertEquals(StandbyMediaKind.IMAGE, validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun twoFrameGif_decodeToGif() = runBlocking {
        val target =
            writeFixture("validator-gif", StandbyMediaProbeFixtures.SMALL_GIF_BASE64)
        try {
            assertEquals(StandbyMediaKind.GIF, validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun invalidNonEmpty_rejected() = runBlocking {
        val target = createOwnedFile("validator-invalid")
        try {
            target.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
            assertNull(validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun empty_rejected() = runBlocking {
        val target = createOwnedFile("validator-empty")
        try {
            target.writeBytes(ByteArray(0))
            assertNull(validator.inspect(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun mp4_frameDecodesToVideo() = runBlocking {
        val arg =
            InstrumentationRegistry.getArguments().getString("standby_probe_video_uri")
        Assume.assumeTrue(
            "No standby_probe_video_uri; video frame check NOT RUN",
            !arg.isNullOrBlank(),
        )
        val source = File(requireNotNull(arg).removePrefix("file://"))
        Assume.assumeTrue("Provisioned synthetic MP4 is missing", source.isFile)
        assertEquals(StandbyMediaKind.VIDEO, validator.inspect(source))
    }

    /**
     * Decodes one base64 fixture into an app-private cache file owned here.
     *
     * @param name file prefix inside this test's cache directory.
     * @param base64 trusted synthetic fixture payload.
     * @return newly created cache file with fixture bytes.
     */
    private fun writeFixture(name: String, base64: String): File {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val target = createOwnedFile(name)
        target.writeBytes(bytes)
        return target
    }

    /**
     * Creates an empty app-private cache file owned by this test.
     *
     * @param name file prefix inside this test's cache directory.
     * @return newly created empty file.
     */
    private fun createOwnedFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile(name, ".probe", context.cacheDir)
        file.deleteOnExit()
        return file
    }
}
