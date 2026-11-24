package me.trinitrix.mirax.ui.standby.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ShapeDrawable
import android.util.Base64
import android.util.Size
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the read-only metadata exposed to the native framing editor. */
@RunWith(AndroidJUnit4::class)
class StandbyMediaUnderlayViewTest {
    @Test
    fun bitmapSizeIsAvailableOnlyWhileUsable() = onMain {
        val view = StandbyMediaUnderlayView(ApplicationProvider.getApplicationContext<Context>())
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        assertThat(view.decodedContentSize()).isNull()

        view.setBitmap(bitmap)
        assertThat(view.decodedContentSize()).isEqualTo(Size(320, 180))

        bitmap.recycle()
        assertThat(view.decodedContentSize()).isNull()
        view.clearMedia()
        assertThat(view.decodedContentSize()).isNull()
    }

    @Test
    fun drawableSizeReplacesBitmapSizeAndClearsWithDrawable() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = StandbyMediaUnderlayView(context)
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val replacement = Bitmap.createBitmap(125, 75, Bitmap.Config.ARGB_8888)
        replacement.density = 160
        val drawable = BitmapDrawable(context.resources, replacement).apply { setTargetDensity(320) }
        view.setBitmap(original)
        view.setMediaDrawable(drawable)

        assertThat(drawable.intrinsicWidth).isEqualTo(250)
        assertThat(view.decodedContentSize()).isEqualTo(Size(125, 75))
        replacement.recycle()
        assertThat(view.decodedContentSize()).isNull()
        view.clearMediaDrawable(drawable)
        assertThat(view.decodedContentSize()).isNull()
        original.recycle()
    }

    @Test
    fun animatedImageReportsDecodedFixtureDimensions() = onMain {
        val view = StandbyMediaUnderlayView(ApplicationProvider.getApplicationContext<Context>())
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertThat(drawable).isInstanceOf(AnimatedImageDrawable::class.java)
        view.setMediaDrawable(drawable)
        assertThat(view.decodedContentSize()).isEqualTo(Size(1, 1))
        view.clearMediaDrawable(drawable)
    }

    @Test
    fun arbitraryDrawableDoesNotInventDecodedSize() = onMain {
        val view = StandbyMediaUnderlayView(ApplicationProvider.getApplicationContext<Context>())
        val drawable = ShapeDrawable().apply {
            intrinsicWidth = 50
            intrinsicHeight = 30
        }
        view.setMediaDrawable(drawable)
        assertThat(view.decodedContentSize()).isNull()
        view.clearMediaDrawable(drawable)
    }

    @Test
    fun visibleVideoWithoutDimensionsDoesNotExposeOlderBitmapSize() = onMain {
        val view = StandbyMediaUnderlayView(ApplicationProvider.getApplicationContext<Context>())
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        view.setBitmap(bitmap)
        view.getChildAt(0).visibility = View.VISIBLE
        assertThat(view.decodedContentSize()).isNull()

        view.getChildAt(0).visibility = View.GONE
        assertThat(view.decodedContentSize()).isEqualTo(Size(320, 180))
        view.clearMedia()
        bitmap.recycle()
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
