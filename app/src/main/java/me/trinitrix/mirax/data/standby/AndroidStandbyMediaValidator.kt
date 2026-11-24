package me.trinitrix.mirax.data.standby

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Content-based [StandbyMediaValidator] using real platform decoders.
 *
 * Classification comes from decoded content only, never from file names or
 * extensions: [ImageDecoder] proves still versus animated drawables, and
 * [MediaMetadataRetriever] must yield a usable video frame before reporting
 * video. Audio-only or frame-less media is rejected with `null`.
 *
 * Runs on the caller's thread; the store already invokes validators on its IO
 * dispatcher, so no dispatcher switch or main-thread decode happens here.
 * Only the exception class name is logged, never paths or content.
 *
 * shortcut: any [AnimatedImageDrawable] (including animated WebP) is reported
 * as GIF for looping playback, revisit when format-specific animated stills
 * are needed.
 *
 * No claim is made about all codecs, size or memory caps, or full import UI.
 */
class AndroidStandbyMediaValidator : StandbyMediaValidator {

    override suspend fun inspect(candidate: File): StandbyMediaKind? {
        currentCoroutineContext().ensureActive()
        if (!candidate.isFile || candidate.length() == 0L) {
            return null
        }
        val imageKind = decodeImageKind(candidate)
        if (imageKind != null) {
            return imageKind
        }
        currentCoroutineContext().ensureActive()
        return decodeVideoKind(candidate)
    }

    /**
     * Decodes one drawable to separate still images from animation.
     *
     * Never holds a started animated drawable: animation is proven by
     * [ImageDecoder.ImageInfo.isAnimated] in the header, then the usable
     * first frame is proven by [ImageDecoder.decodeBitmap] with the same
     * 1x1 low-RAM probe settings. Only a successfully decoded non-empty
     * first-frame bitmap yields GIF; truncated or corrupt frames throw
     * during that real decode and fall through to `null`. The unstarted
     * [AnimatedImageDrawable] path is kept only as a consistency fallback
     * and never started. A validation-only static bitmap is recycled once
     * its size is proven; no drawable or bitmap is exposed or stored.
     *
     * @param candidate store-owned staged file to inspect.
     * @return IMAGE for a static drawable, GIF for animation, else `null`.
     */
    private fun decodeImageKind(candidate: File): StandbyMediaKind? {
        // Control-flow probe: thrown from the header listener once animation
        // is proven, so the full drawable decode aborts before pixel
        // allocation. The kind is only reported after a real first-frame
        // bitmap decode below succeeds.
        class AnimatedProbe : Exception()
        var animatedHeader = false
        val drawable = try {
            val source = ImageDecoder.createSource(candidate)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                if (info.isAnimated) {
                    animatedHeader = true
                    throw AnimatedProbe()
                }
                // Minimal probe allocation; kind only needs decodability proof.
                decoder.setTargetSize(1, 1)
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        } catch (probe: AnimatedProbe) {
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Expected fallthrough for video bytes and corrupt input.
            Log.d(TAG, IMAGE_FALLTHROUGH + e.javaClass.simpleName)
            return null
        }
        if (animatedHeader) {
            return decodeAnimatedFirstFrame(candidate)
        }
        if (drawable is AnimatedImageDrawable) {
            // Defensive: header did not flag animation but the decoder did.
            // Still requires a real first frame; never starts the drawable.
            return decodeAnimatedFirstFrame(candidate)
        }
        val bitmapDrawable = drawable as? BitmapDrawable
        if (bitmapDrawable != null) {
            val bitmap = bitmapDrawable.bitmap
            try {
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    return StandbyMediaKind.IMAGE
                }
                return null
            } finally {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            return StandbyMediaKind.IMAGE
        }
        return null
    }

    /**
     * Proves one usable animated first frame; header animation alone is not enough.
     *
     * Decodes with the same 1x1 low-RAM probe and software allocation so the
     * proof bitmap stays small and recyclable. Only a non-empty bitmap yields
     * GIF; truncated or corrupt frames throw during this real decode and fall
     * through to `null` via the shared image fallthrough log below.
     *
     * @param candidate store-owned staged file to inspect.
     * @return GIF for a decodable animated first frame, else `null`.
     */
    private fun decodeAnimatedFirstFrame(candidate: File): StandbyMediaKind? {
        val firstFrame = try {
            val source = ImageDecoder.createSource(candidate)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (!info.isAnimated) {
                    throw IllegalStateException(ANIMATED_HEADER_MISMATCH)
                }
                decoder.setTargetSize(1, 1)
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Frame bytes undecodable: same fallthrough as corrupt input.
            Log.d(TAG, IMAGE_FALLTHROUGH + e.javaClass.simpleName)
            return null
        }
        try {
            if (firstFrame.width > 0 && firstFrame.height > 0) {
                return StandbyMediaKind.GIF
            }
            return null
        } finally {
            if (!firstFrame.isRecycled) {
                firstFrame.recycle()
            }
        }
    }

    /**
     * Requires one usable decoded video frame, not metadata alone.
     *
     * @param candidate store-owned staged file to inspect.
     * @return VIDEO when a real frame decodes, else `null`.
     */
    private fun decodeVideoKind(candidate: File): StandbyMediaKind? {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(candidate.absolutePath)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, VIDEO_UNUSABLE + e.javaClass.simpleName)
                return null
            }
            val frame = try {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, VIDEO_UNUSABLE + e.javaClass.simpleName)
                return null
            }
            if (frame == null) {
                Log.w(TAG, VIDEO_UNUSABLE + NO_FRAME)
                return null
            }
            try {
                if (frame.width <= 0 || frame.height <= 0) {
                    return null
                }
                return StandbyMediaKind.VIDEO
            } finally {
                if (!frame.isRecycled) {
                    frame.recycle()
                }
            }
        } finally {
            try {
                retriever.release()
            } catch (e: RuntimeException) {
                // Best-effort release; outcome already decided above.
                Log.d(TAG, RELEASE_SKIPPED + e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val TAG = "StandbyValidator"
        const val IMAGE_FALLTHROUGH = "image decode not usable; trying video frame: "
        const val ANIMATED_HEADER_MISMATCH = "animated-header-mismatch"
        const val VIDEO_UNUSABLE = "video frame not usable: "
        const val NO_FRAME = "no-frame"
        const val RELEASE_SKIPPED = "retriever release skipped: "
    }
}
