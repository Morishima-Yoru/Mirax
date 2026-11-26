package me.trinitrix.mirax.standbyprobe.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.trinitrix.mirax.R
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Minimal probe surface for standby custom media.
 *
 * Displays decoded PNG bytes or GIF bytes on an [ImageView] under a legible
 * foreground label, or binds an opt-in video [Uri] on a [TextureView] with a
 * muted looping platform [MediaPlayer] and no controls. GIF playback forces
 * infinite repeat even when the encoded loop count is finite.
 *
 * Decoding runs on a worker dispatcher; view attach runs on the main thread.
 * A generation counter discards late image or video callbacks after replace
 * or release. Owned [Surface] instances are released on every replacement,
 * surface destroy, detach, and error path.
 *
 * File byte counts are transport size only and must not be read as process memory.
 * Video is opt-in: when no valid bundled clip or instrumentation argument Uri is
 * supplied, callers must Assume-skip instead of asserting. See androidTest names
 * for the exact opt-in plan.
 */
class StandbyMediaProbeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView: ImageView = ImageView(context).apply {
        // Platform aspect-fill without stretch; uniform scale covers viewport.
        scaleType = ImageView.ScaleType.CENTER_CROP
        adjustViewBounds = false
    }
    private val textureView: TextureView = TextureView(context).apply {
        visibility = GONE
    }
    private val foregroundView: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        // Legibility over media is part of the probe contract.
        setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt())
    }

    private var generation: Int = 0
    private var animatedDrawable: AnimatedImageDrawable? = null
    private var attachedBitmap: Bitmap? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    @Volatile private var videoPrepared: Boolean = false
    private var videoContentWidth: Int = 0
    private var videoContentHeight: Int = 0
    private var videoUri: Uri? = null
    private var pausedForInspect: Boolean = false
    private var isWindowHidden: Boolean = false
    private var lastEvidence: StandbyMediaProbeEvidence? = null
    private var diagnostic: CharSequence = ""

    /** Probe framing params applied to the actual video transform. */
    private var probePanX: Float = 0f
    private var probePanY: Float = 0f
    private var probeZoom: Float = 1f

    init {
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foregroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setDiagnosticIdle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyVideoTransform()
    }

    /**
     * Set probe framing used by the actual video transform.
     *
     * @param panX Horizontal pan in px, clamped to keep cover.
     * @param panY Vertical pan in px, clamped to keep cover.
     * @param zoom Uniform zoom multiplier, must be finite and positive.
     */
    fun setFraming(panX: Float, panY: Float, zoom: Float) {
        require(panX.isFinite()) { "panX must be finite" }
        require(panY.isFinite()) { "panY must be finite" }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be positive" }
        probePanX = panX
        probePanY = panY
        probeZoom = zoom
        applyVideoTransform()
    }

    /**
     * Show decoded static image bytes.
     *
     * Decode runs on a worker dispatcher; attach runs on the main thread.
     * Stale results after replace or release are recycled, never attached.
     *
     * @param bytes Trusted probe payload; guarded by probe size only.
     * @return Observed evidence, or null when rejected before or after decode.
     */
    suspend fun showStaticImage(bytes: ByteArray): StandbyMediaProbeEvidence? {
        if (bytes.isEmpty() || !StandbyMediaProbeFixtures.isTrustedSize(bytes)) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "probe payload rejected size=" + bytes.size)
                setDiagnosticSize()
            }
            return null
        }
        val myGeneration = withContext(Dispatchers.Main) {
            generation += 1
            releaseVideoLocked()
            releaseImageLocked()
            pausedForInspect = false
            imageView.visibility = VISIBLE
            textureView.visibility = GONE
            generation
        }
        val startMs = SystemClock.uptimeMillis()
        var ownedBitmap: Bitmap? = null
        try {
            val bitmap: Bitmap = try {
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    ImageDecoder.decodeBitmap(source)
                }
            } catch (e: ImageDecoder.DecodeException) {
                Log.e(TAG, "static decode failed", e)
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        setDiagnosticDecode()
                    }
                }
                return null
            } catch (e: IOException) {
                Log.e(TAG, "static decode io failed", e)
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        setDiagnosticDecode()
                    }
                }
                return null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "static decode rejected", e)
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        setDiagnosticDecode()
                    }
                }
                return null
            } catch (e: IllegalStateException) {
                Log.e(TAG, "static decode failed", e)
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        setDiagnosticDecode()
                    }
                }
                return null
            } catch (e: SecurityException) {
                Log.e(TAG, "static decode blocked", e)
                withContext(Dispatchers.Main) {
                    if (myGeneration == generation) {
                        setDiagnosticDecode()
                    }
                }
                return null
            }
            ownedBitmap = bitmap
            return withContext(Dispatchers.Main) {
                if (myGeneration != generation) {
                    ownedBitmap.let {
                        if (!it.isRecycled) {
                            it.recycle()
                        }
                    }
                    ownedBitmap = null
                    null
                } else if (bitmap.width <= 0 || bitmap.height <= 0) {
                    ownedBitmap.let {
                        if (!it.isRecycled) {
                            it.recycle()
                        }
                    }
                    ownedBitmap = null
                    setDiagnosticDecode()
                    null
                } else {
                    imageView.visibility = VISIBLE
                    textureView.visibility = GONE
                    imageView.setImageBitmap(bitmap)
                    attachedBitmap = bitmap
                    ownedBitmap = null
                    animatedDrawable = null
                    val evidence = StandbyMediaProbeEvidence(
                        contentWidth = bitmap.width,
                        contentHeight = bitmap.height,
                        sourceBytes = bytes.size,
                        decodeMs = SystemClock.uptimeMillis() - startMs,
                        deviceModel = Build.MODEL ?: "unknown",
                        sdkInt = Build.VERSION.SDK_INT,
                        detail = "static"
                    )
                    lastEvidence = evidence
                    setDiagnosticShown(evidence)
                    evidence
                }
            }
        } finally {
            ownedBitmap?.let {
                try {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "stale bitmap recycle failed", e)
                }
            }
        }
    }

    /**
     * Show GIF bytes with forced infinite loop.
     *
     * Decode runs on a worker dispatcher; attach runs on the main thread.
     * Static fallback drawables are shown but callers must not count them
     * as loop proof; tests require an animated drawable.
     *
     * @param bytes Trusted probe payload; finite encoded loops still loop forever.
     * @return Observed evidence, or null when rejected before or after decode.
     */
    suspend fun showGif(bytes: ByteArray): StandbyMediaProbeEvidence? {
        if (bytes.isEmpty() || !StandbyMediaProbeFixtures.isTrustedSize(bytes)) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "probe payload rejected size=" + bytes.size)
                setDiagnosticSize()
            }
            return null
        }
        val myGeneration = withContext(Dispatchers.Main) {
            generation += 1
            releaseVideoLocked()
            releaseImageLocked()
            pausedForInspect = false
            imageView.visibility = VISIBLE
            textureView.visibility = GONE
            generation
        }
        val startMs = SystemClock.uptimeMillis()
        val drawable: android.graphics.drawable.Drawable = try {
            withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeDrawable(source)
            }
        } catch (e: ImageDecoder.DecodeException) {
            Log.e(TAG, "gif decode failed", e)
            withContext(Dispatchers.Main) {
                if (myGeneration == generation) {
                    setDiagnosticDecode()
                }
            }
            return null
        } catch (e: IOException) {
            Log.e(TAG, "gif decode io failed", e)
            withContext(Dispatchers.Main) {
                if (myGeneration == generation) {
                    setDiagnosticDecode()
                }
            }
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "gif decode rejected", e)
            withContext(Dispatchers.Main) {
                if (myGeneration == generation) {
                    setDiagnosticDecode()
                }
            }
            return null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "gif decode failed", e)
            withContext(Dispatchers.Main) {
                if (myGeneration == generation) {
                    setDiagnosticDecode()
                }
            }
            return null
        } catch (e: SecurityException) {
            Log.e(TAG, "gif decode blocked", e)
            withContext(Dispatchers.Main) {
                if (myGeneration == generation) {
                    setDiagnosticDecode()
                }
            }
            return null
        }
        return withContext(Dispatchers.Main) {
            if (myGeneration != generation) {
                null
            } else {
                val animated = drawable as? AnimatedImageDrawable
                if (animated == null) {
                    val fallbackWidth = drawable.intrinsicWidth
                    val fallbackHeight = drawable.intrinsicHeight
                    if (fallbackWidth <= 0 || fallbackHeight <= 0) {
                        setDiagnosticDecode()
                        null
                    } else {
                        imageView.visibility = VISIBLE
                        textureView.visibility = GONE
                        imageView.setImageDrawable(drawable)
                        animatedDrawable = null
                        val evidence = StandbyMediaProbeEvidence(
                            contentWidth = fallbackWidth,
                            contentHeight = fallbackHeight,
                            sourceBytes = bytes.size,
                            decodeMs = SystemClock.uptimeMillis() - startMs,
                            deviceModel = Build.MODEL ?: "unknown",
                            sdkInt = Build.VERSION.SDK_INT,
                            detail = "gif-fallback-static"
                        )
                        lastEvidence = evidence
                        setDiagnosticShown(evidence)
                        evidence
                    }
                } else {
                    animated.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    imageView.visibility = VISIBLE
                    textureView.visibility = GONE
                    imageView.setImageDrawable(animated)
                    animatedDrawable = animated
                    // Pause or hidden window during decode must prevent start; resume restarts.
                    if (!pausedForInspect && !isWindowHidden) {
                        try {
                            animated.start()
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "gif start failed", e)
                        }
                    }
                    val evidence = StandbyMediaProbeEvidence(
                        contentWidth = animated.intrinsicWidth.coerceAtLeast(0),
                        contentHeight = animated.intrinsicHeight.coerceAtLeast(0),
                        sourceBytes = bytes.size,
                        decodeMs = SystemClock.uptimeMillis() - startMs,
                        deviceModel = Build.MODEL ?: "unknown",
                        sdkInt = Build.VERSION.SDK_INT,
                        detail = "gif-loop-infinite"
                    )
                    lastEvidence = evidence
                    setDiagnosticShown(evidence)
                    evidence
                }
            }
        }
    }

    /**
     * Bind an opt-in video Uri for muted looping playback.
     *
     * Must be called on the main thread. No controls are attached. Start waits
     * for [TextureView] surface; binding evidence carries zero dimensions and
     * must not be counted as verified. Prepared evidence carries real size.
     *
     * @param uri Real device video Uri from instrumentation args.
     * @return Binding evidence, or null when rejected before prepare.
     */
    fun bindVideo(uri: Uri): StandbyMediaProbeEvidence? {
        generation += 1
        val myGeneration = generation
        releaseVideoLocked()
        releaseImageLocked()
        pausedForInspect = false
        videoUri = uri
        imageView.visibility = GONE
        textureView.visibility = VISIBLE

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                available: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (myGeneration != generation) {
                    return
                }
                applyVideoTransform()
                if (!isWindowHidden) {
                    startVideoLocked(available, myGeneration)
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                if (myGeneration == generation) {
                    releaseVideoPlayback()
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // No-op for probe.
            }
        }
        textureView.surfaceTextureListener = listener

        val surfaceTexture = textureView.surfaceTexture
        if (textureView.isAvailable && surfaceTexture != null) {
            applyVideoTransform()
            if (!isWindowHidden) {
                startVideoLocked(surfaceTexture, myGeneration)
            }
        }
        // Binding pass only; full dimensions arrive after prepare.
        val evidence = StandbyMediaProbeEvidence(
            contentWidth = 0,
            contentHeight = 0,
            sourceBytes = 0,
            decodeMs = 0L,
            deviceModel = Build.MODEL ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            detail = "video-binding:" + uri.toString()
        )
        lastEvidence = evidence
        setDiagnosticShown(evidence)
        return evidence
    }

    /**
     * Await prepared video size within a bounded timeout.
     *
     * @param timeoutMs Bounded wait in ms.
     * @return True when prepared with positive dimensions.
     */
    suspend fun awaitVideoPrepared(timeoutMs: Long = 5000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = withContext(Dispatchers.Main) {
                videoPrepared && videoContentWidth > 0 && videoContentHeight > 0
            }
            if (ready) {
                return true
            }
            delay(50L)
        }
        return withContext(Dispatchers.Main) {
            videoPrepared && videoContentWidth > 0 && videoContentHeight > 0
        }
    }

    /**
     * Pause visible animation or video for inspection.
     */
    fun pauseForInspect() {
        pausedForInspect = true
        pausePlayback()
    }

    /**
     * Restart from the beginning when actually resumed.
     */
    fun resumeFromBeginning() {
        pausedForInspect = false
        if (!isWindowHidden) {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        try {
            animatedDrawable?.let { animated ->
                if (animated.isRunning) {
                    animated.stop()
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "gif pause failed", e)
        }
        val player = mediaPlayer
        if (player != null && videoPrepared) {
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video pause failed", e)
            }
        }
    }

    private fun resumePlayback() {
        try {
            animatedDrawable?.let { animated ->
                try {
                    animated.stop()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "gif restart stop failed", e)
                }
                try {
                    animated.start()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "gif resume failed", e)
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "gif resume failed", e)
        }
        val player = mediaPlayer
        val uri = videoUri
        if (uri != null && player != null && videoPrepared) {
            try {
                player.seekTo(0)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video resume seek failed", e)
                return
            }
            try {
                player.start()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video resume failed", e)
            }
        } else if (uri != null && player != null) {
            // Preparing: do not seek or start; onPrepared will start once ready.
        } else if (uri != null && textureView.isAvailable && textureView.surfaceTexture != null) {
            val texture = textureView.surfaceTexture
            if (texture != null) {
                startVideoLocked(texture, generation)
            }
        }
    }

    /**
     * Release drawables and player; safe to call repeatedly.
     */
    fun releaseProbe() {
        generation += 1
        releaseImageLocked()
        releaseVideoLocked()
        videoUri = null
        pausedForInspect = false
    }

    /**
     * Inspectable public probe contract snapshot.
     *
     * @return Current attachment and playback flags.
     */
    fun snapshot(): StandbyMediaProbeState {
        val animated = animatedDrawable
        val player = mediaPlayer
        val playing = try {
            (animated?.isRunning == true) || (player?.isPlaying == true)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "snapshot playing check failed", e)
            false
        }
        // Looping covers GIF infinite repeat and video muted loop flag.
        val looping = try {
            (animated?.repeatCount == AnimatedImageDrawable.REPEAT_INFINITE) ||
                (player?.isLooping == true)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "snapshot looping check failed", e)
            false
        }
        val hasMedia = imageView.drawable != null || player != null || videoUri != null
        return StandbyMediaProbeState(
            hasMedia = hasMedia,
            isAnimated = animated != null,
            isLooping = looping,
            isPausedForInspect = pausedForInspect,
            isVideo = videoUri != null,
            isAttachedToWindow = isAttachedToWindow,
            isPlaying = playing
        )
    }

    /**
     * Last observed evidence, or null before any show call.
     *
     * @return Evidence copy or null.
     */
    fun lastEvidence(): StandbyMediaProbeEvidence? {
        return lastEvidence
    }

    /**
     * Localized diagnostic string currently shown in the foreground.
     *
     * @return Foreground text.
     */
    fun diagnosticText(): CharSequence {
        return diagnostic
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val hidden = visibility != VISIBLE
        isWindowHidden = hidden
        if (hidden) {
            // Window hidden without detach; pause actual resource.
            pausePlayback()
        } else {
            if (!pausedForInspect && (imageView.drawable != null || videoUri != null)) {
                resumePlayback()
            }
        }
    }

    override fun onDetachedFromWindow() {
        try {
            releaseProbe()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "probe release on detach failed", e)
        } finally {
            super.onDetachedFromWindow()
        }
    }

    private fun startVideoLocked(surfaceTexture: SurfaceTexture, myGeneration: Int) {
        if (myGeneration != generation) {
            return
        }
        val uri = videoUri ?: return
        val startMs = SystemClock.uptimeMillis()
        var localSurface: Surface? = null
        var localPlayer: MediaPlayer? = null
        try {
            val surface = Surface(surfaceTexture)
            localSurface = surface
            val player = MediaPlayer()
            localPlayer = player
            player.setSurface(surface)
            player.setVolume(0f, 0f)
            player.isLooping = true
            player.setDataSource(context, uri)
            player.setOnVideoSizeChangedListener { changed, width, height ->
                if (myGeneration != generation || mediaPlayer !== changed) {
                    return@setOnVideoSizeChangedListener
                }
                videoContentWidth = width
                videoContentHeight = height
                applyVideoTransform()
            }
            player.setOnPreparedListener { prepared ->
                if (myGeneration != generation || mediaPlayer !== prepared) {
                    try {
                        prepared.release()
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "stale prepared release failed", e)
                    }
                    return@setOnPreparedListener
                }
                videoContentWidth = prepared.videoWidth
                videoContentHeight = prepared.videoHeight
                videoPrepared = true
                applyVideoTransform()
                val evidence = StandbyMediaProbeEvidence(
                    contentWidth = prepared.videoWidth,
                    contentHeight = prepared.videoHeight,
                    sourceBytes = 0,
                    decodeMs = SystemClock.uptimeMillis() - startMs,
                    deviceModel = Build.MODEL ?: "unknown",
                    sdkInt = Build.VERSION.SDK_INT,
                    detail = "video-prepared"
                )
                lastEvidence = evidence
                setDiagnosticShown(evidence)
                if (pausedForInspect || isWindowHidden) {
                    return@setOnPreparedListener
                }
                try {
                    prepared.start()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "video start failed", e)
                }
            }
            player.setOnErrorListener { errored, what, extra ->
                if (myGeneration != generation || mediaPlayer !== errored) {
                    try {
                        errored.release()
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "stale error release failed", e)
                    }
                    return@setOnErrorListener true
                }
                Log.e(TAG, "video error what=" + what + " extra=" + extra)
                releaseVideoPlayback()
                setDiagnosticDecode()
                true
            }
            // Assign before async prepare so callbacks can verify identity.
            videoSurface = surface
            mediaPlayer = player
            videoPrepared = false
            localSurface = null
            localPlayer = null
            player.prepareAsync()
        } catch (e: Exception) {
            when (e) {
                is IllegalArgumentException,
                is IllegalStateException,
                is SecurityException,
                is IOException -> {
                    Log.e(TAG, "video bind failed (${e.javaClass.simpleName})", e)
                    if (myGeneration == generation) {
                        releaseVideoPlayback()
                        setDiagnosticDecode()
                    }
                    localPlayer?.let { p ->
                        try {
                            p.release()
                        } catch (releaseError: IllegalStateException) {
                            Log.e(TAG, "video player cleanup failed", releaseError)
                        }
                    }
                    localSurface?.let { s ->
                        try {
                            s.release()
                        } catch (releaseError: IllegalStateException) {
                            Log.e(TAG, "video surface cleanup failed", releaseError)
                        }
                    }
                }
                else -> throw e
            }
        }
    }

    private fun applyVideoTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        val contentWidth = videoContentWidth
        val contentHeight = videoContentHeight
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return
        }
        try {
            val transform = StandbyMediaFrameGeometry.computeTransform(
                viewportWidth = viewWidth.toFloat(),
                viewportHeight = viewHeight.toFloat(),
                contentWidth = contentWidth.toFloat(),
                contentHeight = contentHeight.toFloat(),
                panX = probePanX,
                panY = probePanY,
                zoom = probeZoom
            )
            val matrix = Matrix()
            matrix.setScale(transform.scaleX, transform.scaleY)
            matrix.postTranslate(transform.translateX, transform.translateY)
            textureView.setTransform(matrix)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "video transform rejected", e)
        }
    }

    private fun releaseImageLocked() {
        try {
            animatedDrawable?.let { animated ->
                try {
                    if (animated.isRunning) {
                        animated.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "gif stop on release failed", e)
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "gif stop on release failed", e)
        }
        animatedDrawable = null
        imageView.setImageDrawable(null)
        val bitmap = attachedBitmap
        attachedBitmap = null
        if (bitmap != null) {
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "bitmap recycle failed", e)
            }
        }
    }

    private fun releaseVideoPlayback() {
        videoPrepared = false
        videoContentWidth = 0
        videoContentHeight = 0
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                player.setOnPreparedListener(null)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video listener clear failed", e)
            }
            try {
                player.setOnErrorListener(null)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video listener clear failed", e)
            }
            try {
                player.setOnVideoSizeChangedListener(null)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video listener clear failed", e)
            }
            // Do not call stop(): it throws when idle or preparing.
            // Release plus listener clear safely drops preparing playback.
            try {
                player.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video release failed", e)
            }
        }
        val surface = videoSurface
        videoSurface = null
        if (surface != null) {
            try {
                surface.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "video surface release failed", e)
            }
        }
    }

    private fun releaseVideoLocked() {
        textureView.surfaceTextureListener = null
        // Clear binding so snapshot no longer reports video after replace or release.
        // Surface destroy and pause keep videoUri via releaseVideoPlayback for resume.
        videoUri = null
        releaseVideoPlayback()
    }

    private fun setDiagnosticIdle() {
        foregroundView.text = context.getString(R.string.standby_media_probe_idle)
        diagnostic = foregroundView.text
    }

    private fun setDiagnosticSize() {
        foregroundView.text = context.getString(R.string.standby_media_probe_error_size)
        diagnostic = foregroundView.text
    }

    private fun setDiagnosticShown(evidence: StandbyMediaProbeEvidence) {
        val text = context.getString(
            R.string.standby_media_probe_shown,
            evidence.contentWidth,
            evidence.contentHeight,
            evidence.sourceBytes,
            evidence.decodeMs
        )
        foregroundView.text = text
        diagnostic = text
    }

    private fun setDiagnosticDecode() {
        foregroundView.text = context.getString(R.string.standby_media_probe_error_decode)
        diagnostic = foregroundView.text
        // Keep last evidence for future bounds; do not fabricate new dimensions.
    }

    companion object {
        private const val TAG: String = "StandbyMediaProbe"
    }
}
