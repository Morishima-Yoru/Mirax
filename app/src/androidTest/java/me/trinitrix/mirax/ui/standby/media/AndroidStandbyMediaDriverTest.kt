package me.trinitrix.mirax.ui.standby.media

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.data.standby.AndroidStandbyMediaValidator
import me.trinitrix.mirax.data.standby.StandbyConfirmResult
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.StandbyStageResult
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoder and window-output checks, including a platform-only control. */
@RunWith(AndroidJUnit4::class)
class AndroidStandbyMediaDriverTest {
    /** Tracks bitmap ownership during a bounded PixelCopy request. */
    private enum class PixelRequestState { QUEUED, CLAIMED, SUBMITTED, CANCELLED }

    /** Exercises the generated video on a platform TextureView without the standby driver. */
    @Test
    fun platformTextureView_rendersTheSameGeneratedAvcWithoutStandbyDriver() = assertPlatformVideo(useTextureView = true)

    /** Exercises the generated video on a platform SurfaceView without the standby driver. */
    @Test
    fun platformSurfaceView_rendersTheSameGeneratedAvcWithoutStandbyDriver() = assertPlatformVideo(useTextureView = false)

    /** Checks SurfaceView playback liveness without reading pixels. */
    @Test
    fun platformSurfaceView_keepsLoopingWithoutReadback() =
        assertPlatformVideo(useTextureView = false, readPixels = false)

    /** Samples platform SurfaceView video through its activity window compositor. */
    @Test
    fun platformSurfaceView_compositorCaptureRendersGeneratedAvc() =
        assertPlatformVideo(useTextureView = false, compositorCapture = true)

    /**
     * Checks native video output without the production controller.
     *
     * @param useTextureView Whether to use TextureView rather than SurfaceView.
     * @param readPixels Whether to verify output with pixels.
     * @param compositorCapture Whether to capture the owned window instead of using PixelCopy.
     */
    private fun assertPlatformVideo(useTextureView: Boolean, readPixels: Boolean = true, compositorCapture: Boolean = false) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storageDir = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "platform-video-").toFile()
        AutoCloseable { check(storageDir.deleteRecursively()) { "failed to remove platform video fixture" } }.use {
            val file = File(storageDir, "fixture.mp4")
            writeVideo(file)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Log.i("StandbyPlatformControl", "t=${SystemClock.elapsedRealtime()} fixture bytes=${file.length()} sha256=$hash")

            val available = CountDownLatch(1)
            val prepared = CountDownLatch(1)
            val rendering = CountDownLatch(1)
            val updated = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val expectedHost = AtomicReference<StandbyProbeActivity?>(null)
            var otherHost: StandbyProbeActivity? = null
            var view: View? = null
            var texture: SurfaceTexture? = null
            var surface: Surface? = null
            var holderSurface: Surface? = null
            var holderAlive = false
            var holderCallback: SurfaceHolder.Callback? = null
            var player: MediaPlayer? = null
            var focusObserver: ViewTreeObserver? = null
            var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
            var frameCount = 0

            fun recordFailure(cause: Throwable) {
                if (!failure.compareAndSet(null, cause)) {
                    failure.get()?.let { if (it !== cause) it.addSuppressed(cause) }
                }
                Log.e("StandbyPlatformControl", "t=${SystemClock.elapsedRealtime()} failure", cause)
                available.countDown()
                prepared.countDown()
                rendering.countDown()
                updated.countDown()
            }

            fun trace(host: StandbyProbeActivity, event: String) {
                val current = view
                Log.i(
                    "StandbyPlatformControl",
                    "t=${SystemClock.elapsedRealtime()} $event host=${System.identityHashCode(host)} " +
                        "destroyed=${host.isDestroyed} finishing=${host.isFinishing} focus=${host.hasWindowFocus()} " +
                        "renderer=${if (useTextureView) "TextureView" else "SurfaceView"} " +
                        "view=${System.identityHashCode(current)} attached=${current?.isAttachedToWindow} " +
                        "size=${current?.width}x${current?.height} hw=${current?.isHardwareAccelerated} " +
                        "texture=${System.identityHashCode((current as? TextureView)?.surfaceTexture)} " +
                        "holderSurface=${System.identityHashCode(holderSurface)} holderAlive=$holderAlive frames=$frameCount",
                )
            }

            fun releasePlayback() {
                val oldPlayer = player.also { player = null }
                val oldSurface = surface.also { surface = null }
                AutoCloseable { oldSurface?.release() }.use { oldPlayer?.release() }
            }

            fun onMain(action: () -> Unit) {
                val result = AtomicReference<Result<Unit>?>(null)
                instrumentation.runOnMainSync { result.set(runCatching(action)) }
                checkNotNull(result.get()).getOrThrow()
            }

            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val token = SystemClock.elapsedRealtimeNanos().toString()
            val intent = Intent(instrumentation.targetContext, StandbyProbeActivity::class.java)
                .putExtra("platformControlToken", token)
            val lifecycle = ActivityLifecycleCallback { activity, stage ->
                if (activity is StandbyProbeActivity && activity.intent.getStringExtra("platformControlToken") == token) {
                    val original = expectedHost.get()
                    if (original == null) {
                        expectedHost.set(activity)
                    } else if (activity !== original && activity !== otherHost) {
                        otherHost = activity
                        recordFailure(AssertionError(
                            "HOST_RECREATED old=${System.identityHashCode(original)} new=${System.identityHashCode(activity)}",
                        ))
                    }
                    Log.i(
                        "StandbyPlatformControl",
                        "t=${SystemClock.elapsedRealtime()} lifecycle=$stage host=${System.identityHashCode(activity)} " +
                            "expected=${System.identityHashCode(expectedHost.get())} " +
                            "destroyed=${activity.isDestroyed} finishing=${activity.isFinishing}",
                    )
                }
            }
            onMain { monitor.addLifecycleCallback(lifecycle) }
            runCatching { AutoCloseable { onMain { monitor.removeLifecycleCallback(lifecycle) } }.use {
                ActivityScenario.launch<StandbyProbeActivity>(intent).use { scenario ->
                    AutoCloseable {
                        onMain {
                            AutoCloseable { view?.let { (it.parent as? ViewGroup)?.removeView(it) } }.use {
                                AutoCloseable { releasePlayback() }.use {
                                    AutoCloseable {
                                        when (val current = view) {
                                            is TextureView -> current.surfaceTextureListener = null
                                            is SurfaceView -> holderCallback?.let { current.holder.removeCallback(it) }
                                            else -> Unit
                                        }
                                    }.use {
                                        val listener = focusListener
                                        focusObserver?.let { if (it.isAlive && listener != null) it.removeOnWindowFocusChangeListener(listener) }
                                    }
                                }
                            }
                        }
                    }.use platformPlayback@ {
                        fun onHost(action: (StandbyProbeActivity) -> Unit) {
                            try {
                                onUi(scenario) { host ->
                                    val original = expectedHost.get()
                                    if (host !== original) {
                                        val cause = AssertionError(
                                            "HOST_RECREATED old=${System.identityHashCode(original)} new=${System.identityHashCode(host)}",
                                        )
                                        recordFailure(cause)
                                        throw checkNotNull(failure.get())
                                    }
                                    val stage = monitor.getLifecycleStageOf(host)
                                    if (host.isDestroyed || host.isFinishing || stage != Stage.RESUMED) {
                                        val cause = AssertionError("HOST_NOT_LIVE host=${System.identityHashCode(host)} stage=$stage")
                                        recordFailure(cause)
                                        throw checkNotNull(failure.get())
                                    }
                                    failure.get()?.let { throw it }
                                    action(host)
                                }
                            } catch (cause: Throwable) {
                                recordFailure(cause)
                                throw checkNotNull(failure.get())
                            }
                        }

                        fun await(latch: CountDownLatch, name: String, timeoutNanos: Long = TimeUnit.SECONDS.toNanos(5)) {
                            val signalled = latch.await(timeoutNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                            if (!signalled) recordFailure(AssertionError("$name timed out"))
                            failure.get()?.let { throw it }
                        }

                        onHost { host ->
                            val current: View = if (useTextureView) TextureView(host) else SurfaceView(host)
                            view = current
                            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focus -> trace(host, "focusChanged=$focus") }
                            focusListener = listener
                            focusObserver = host.window.decorView.viewTreeObserver.also { it.addOnWindowFocusChangeListener(listener) }

                            fun checkLive() {
                                check(
                                    failure.get() == null && expectedHost.get() === host &&
                                        !host.isDestroyed && !host.isFinishing &&
                                        monitor.getLifecycleStageOf(host) == Stage.RESUMED,
                                )
                                check(current.isAttachedToWindow && current.isHardwareAccelerated)
                            }

                            fun startPlayback(output: Surface, holder: SurfaceHolder?) {
                                try {
                                    checkLive()
                                    check(output.isValid)
                                    check(player == null) { "platform surface was created twice" }
                                    val media = MediaPlayer().also { player = it }
                                    media.setOnErrorListener { _, what, extra ->
                                        recordFailure(AssertionError("MediaPlayer error what=$what extra=$extra"))
                                        true
                                    }
                                    media.setOnInfoListener { _, what, extra ->
                                        trace(host, "MEDIA_INFO what=$what extra=$extra")
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering.countDown()
                                        true
                                    }
                                    media.setOnPreparedListener { ready ->
                                        try {
                                            checkLive()
                                            ready.setVolume(0f, 0f)
                                            ready.isLooping = true
                                            check(ready.isLooping)
                                            trace(host, "PREPARED setVolume(0,0) configured loop=true")
                                            ready.start()
                                            trace(host, "PLAYER_STARTED")
                                            prepared.countDown()
                                        } catch (cause: Throwable) {
                                            recordFailure(cause)
                                        }
                                    }
                                    media.setDataSource(file.absolutePath)
                                    if (holder == null) media.setSurface(output) else media.setDisplay(holder)
                                    media.prepareAsync()
                                    available.countDown()
                                } catch (cause: Throwable) {
                                    recordFailure(cause)
                                }
                            }

                            when (current) {
                                is TextureView -> current.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                        texture = st
                                        trace(host, "SURFACE_AVAILABLE ${width}x$height")
                                        runCatching {
                                            checkLive()
                                            check(surface == null && player == null) { "platform texture was created twice" }
                                            startPlayback(Surface(st).also { surface = it }, null)
                                        }.onFailure(::recordFailure)
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                        trace(host, "SURFACE_SIZE ${width}x$height texture=${System.identityHashCode(st)}")
                                    }

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        runCatching {
                                            trace(host, "SURFACE_DESTROYED texture=${System.identityHashCode(st)}")
                                            recordFailure(AssertionError("platform video surface destroyed unexpectedly"))
                                        }.onFailure(::recordFailure)
                                        runCatching { releasePlayback() }.onFailure(::recordFailure)
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                        if (failure.get() != null) return
                                        if (st !== texture || expectedHost.get() !== host || host.isDestroyed || host.isFinishing) {
                                            recordFailure(AssertionError(
                                                "STALE_FRAME host=${System.identityHashCode(host)} " +
                                                    "texture=${System.identityHashCode(st)} expected=${System.identityHashCode(texture)}",
                                            ))
                                            return
                                        }
                                        frameCount++
                                        if (frameCount <= 3 || frameCount % 20 == 0) trace(host, "FRAME_UPDATED")
                                        updated.countDown()
                                    }
                                }
                                is SurfaceView -> {
                                    val callback = object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            runCatching {
                                                checkLive()
                                                check(!holderAlive && player == null) { "platform holder was created twice" }
                                                val output = holder.surface
                                                holderSurface = output
                                                holderAlive = true
                                                trace(host, "HOLDER_CREATED holder=${System.identityHashCode(holder)}")
                                                startPlayback(output, holder)
                                            }.onFailure(::recordFailure)
                                        }

                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                            trace(host, "HOLDER_CHANGED ${width}x$height format=$format")
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            holderAlive = false
                                            runCatching {
                                                trace(host, "HOLDER_DESTROYED holder=${System.identityHashCode(holder)}")
                                                recordFailure(AssertionError("platform video holder destroyed unexpectedly"))
                                            }.onFailure(::recordFailure)
                                            runCatching { releasePlayback() }.onFailure(::recordFailure)
                                        }
                                    }
                                    holderCallback = callback
                                    current.holder.addCallback(callback)
                                }
                                else -> error("unexpected platform video view")
                            }
                            host.contentRoot.setBackgroundColor(Color.BLACK)
                            host.contentRoot.addView(current, FrameLayout.LayoutParams(160, 120))
                            trace(host, "VIEW_ADDED")
                        }
                        await(available, "surface")
                        await(prepared, "prepared and started")
                        await(rendering, "video rendering start")
                        if (useTextureView) await(updated, "surface update")

                        fun checkOutput(host: StandbyProbeActivity, current: View) {
                            failure.get()?.let { throw it }
                            check(expectedHost.get() === host && view === current && !host.isDestroyed && !host.isFinishing)
                            check(monitor.getLifecycleStageOf(host) == Stage.RESUMED)
                            check(current.isAttachedToWindow && current.isHardwareAccelerated && current.width > 0 && current.height > 0)
                            when (current) {
                                is TextureView -> check(current.surfaceTexture === texture && texture?.isReleased == false)
                                is SurfaceView -> check(holderAlive && current.holder.surface === holderSurface && holderSurface.isValid)
                                else -> error("unexpected platform video view")
                            }
                        }

                        if (!readPixels) {
                            var ownedView: SurfaceView? = null
                            var ownedPlayer: MediaPlayer? = null
                            var previousPosition = -1
                            var wrapCount = 0
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (System.nanoTime() < deadline && wrapCount < 2) {
                                onHost { host ->
                                    val current = checkNotNull(view as? SurfaceView)
                                    val media = checkNotNull(player)
                                    if (ownedView == null) {
                                        ownedView = current
                                        ownedPlayer = media
                                    }
                                    check(current === ownedView && media === ownedPlayer)
                                    checkOutput(host, current)
                                    check(media.isPlaying && media.isLooping)
                                    val duration = media.duration
                                    val position = media.currentPosition
                                    check(duration > 0 && position >= 0 && System.nanoTime() < deadline)
                                    if (previousPosition >= 0 && previousPosition - position > duration / 2) wrapCount++
                                    previousPosition = position
                                    trace(host, "STATE_ONLY_NO_READBACK currentPosition=$position duration=$duration " +
                                        "wrapCount=$wrapCount player=${System.identityHashCode(media)}")
                                }
                                if (wrapCount < 2 && System.nanoTime() < deadline) SystemClock.sleep(50)
                            }
                            failure.get()?.let { throw it }
                            assertTrue("platform SurfaceView did not complete two position wraps within five seconds", wrapCount >= 2)
                            return@platformPlayback
                        }

                        val handler = Handler(Looper.getMainLooper())
                        val source = if (useTextureView) "WINDOW" else "SURFACE"
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        var red = false
                        var green = false
                        var compositorFrames = 0
                        while (System.nanoTime() < deadline && !(red && green)) {
                            if (compositorCapture) {
                                lateinit var window: Window
                                lateinit var root: FrameLayout
                                val windowRect = Rect()
                                val videoRect = Rect()
                                val background = IntArray(2)
                                onHost { host ->
                                    val current = checkNotNull(view as? SurfaceView)
                                    checkOutput(host, current)
                                    window = host.window
                                    root = host.contentRoot
                                    val decor = window.decorView
                                    val token = checkNotNull(decor.windowToken)
                                    check(decor.isAttachedToWindow && root.isAttachedToWindow &&
                                        current.rootView === decor && root.rootView === decor &&
                                        current.windowToken === token && root.windowToken === token)
                                    check(root.childCount == 1 && root.getChildAt(0) === current)
                                    val location = IntArray(2)
                                    decor.getLocationInWindow(location)
                                    check(location[0] == 0 && location[1] == 0)
                                    windowRect.set(0, 0, decor.width, decor.height)
                                    current.getLocationInWindow(location)
                                    videoRect.set(location[0], location[1], location[0] + current.width, location[1] + current.height)
                                    root.getLocationInWindow(location)
                                    val rootRect = Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
                                    check(windowRect.contains(rootRect) && rootRect.contains(videoRect))
                                    if (rootRect.right > videoRect.right) {
                                        background[0] = (videoRect.right + rootRect.right) / 2
                                        background[1] = videoRect.centerY()
                                    } else {
                                        check(rootRect.bottom > videoRect.bottom) { "no visible background outside the video" }
                                        background[0] = videoRect.centerX()
                                        background[1] = (videoRect.bottom + rootRect.bottom) / 2
                                    }
                                    check(rootRect.contains(background[0], background[1]) &&
                                        !videoRect.contains(background[0], background[1]))
                                    trace(host, "COMPOSITOR_REQUEST window=${System.identityHashCode(window)} " +
                                        "windowRect=$windowRect videoRect=$videoRect center=${videoRect.centerX()},${videoRect.centerY()} " +
                                        "background=${background[0]},${background[1]}")
                                }
                                check(System.nanoTime() < deadline) { "compositor deadline expired before request" }
                                // Binder capture has no per-call timeout; after a runner timeout the parent must stop the owned app.
                                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot(window)) { "owned window capture returned null" }
                                val color = try {
                                    Log.i("StandbyPlatformControl", "COMPOSITOR_BUFFER window=${System.identityHashCode(window)} " +
                                        "size=${screenshot.width}x${screenshot.height} config=${screenshot.config} windowRect=$windowRect")
                                    check(screenshot.width == windowRect.width() && screenshot.height == windowRect.height())
                                    val x = videoRect.centerX()
                                    val y = videoRect.centerY()
                                    check(x in 0 until screenshot.width && y in 0 until screenshot.height &&
                                        background[0] in 0 until screenshot.width && background[1] in 0 until screenshot.height)
                                    val readable = if (screenshot.config == Bitmap.Config.HARDWARE) {
                                        checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false))
                                    } else screenshot
                                    try {
                                        val pixel = readable.getPixel(x, y)
                                        val backgroundPixel = readable.getPixel(background[0], background[1])
                                        compositorFrames++
                                        Log.i("StandbyPlatformControl", "COMPOSITOR_FRAME frame=$compositorFrames " +
                                            "cpuConfig=${readable.config} center=$x,$y rgb=${Color.red(pixel)},${Color.green(pixel)},${Color.blue(pixel)} " +
                                            "background=${background[0]},${background[1]} color=$backgroundPixel")
                                        check(backgroundPixel == Color.BLACK) { "owned window background was not black: $backgroundPixel" }
                                        pixel
                                    } finally {
                                        if (readable !== screenshot) readable.recycle()
                                    }
                                } finally {
                                    screenshot.recycle()
                                }
                                onHost { host ->
                                    val current = checkNotNull(view as? SurfaceView)
                                    checkOutput(host, current)
                                    val decor = host.window.decorView
                                    check(host.window === window && host.contentRoot === root && decor.isAttachedToWindow &&
                                        current.rootView === decor && current.windowToken === decor.windowToken &&
                                        decor.width == windowRect.width() && decor.height == windowRect.height() &&
                                        root.childCount == 1 && root.getChildAt(0) === current)
                                    val location = IntArray(2)
                                    current.getLocationInWindow(location)
                                    check(videoRect == Rect(location[0], location[1], location[0] + current.width, location[1] + current.height))
                                }
                                if (System.nanoTime() >= deadline) break
                                red = red || (Color.red(color) > 100 && Color.red(color) > Color.green(color) + 30)
                                green = green || (Color.green(color) > 100 && Color.green(color) > Color.red(color) + 30)
                                if (!(red && green)) SystemClock.sleep(50)
                                continue
                            }
                            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            val requestState = AtomicReference(PixelRequestState.QUEUED)
                            val copied = CountDownLatch(1)
                            val result = AtomicInteger(-1)
                            val pixel = AtomicInteger()
                            fun cancelPendingCopy() {
                                if (requestState.compareAndSet(PixelRequestState.QUEUED, PixelRequestState.CANCELLED)) {
                                    bitmap.recycle()
                                } else {
                                    requestState.compareAndSet(PixelRequestState.CLAIMED, PixelRequestState.CANCELLED)
                                }
                            }
                            try {
                                val posted = handler.post {
                                    if (requestState.compareAndSet(PixelRequestState.QUEUED, PixelRequestState.CLAIMED)) {
                                        var requested = false
                                        try {
                                            val host = checkNotNull(expectedHost.get())
                                            val current = checkNotNull(view)
                                            checkOutput(host, current)
                                            val listener = PixelCopy.OnPixelCopyFinishedListener { status ->
                                                try {
                                                    checkOutput(host, current)
                                                    result.set(status)
                                                    if (status == PixelCopy.SUCCESS) {
                                                        val color = bitmap.getPixel(0, 0)
                                                        pixel.set(color)
                                                        trace(host, "${source}_PIXEL rgb=${Color.red(color)},${Color.green(color)},${Color.blue(color)}")
                                                    } else {
                                                        trace(host, "${source}_PIXELCOPY status=$status")
                                                    }
                                                } catch (cause: Throwable) {
                                                    recordFailure(cause)
                                                } finally {
                                                    runCatching { bitmap.recycle() }.onFailure(::recordFailure)
                                                    copied.countDown()
                                                }
                                            }
                                            val windowRect = if (current is TextureView) {
                                                val location = IntArray(2)
                                                current.getLocationInWindow(location)
                                                val x = location[0] + current.width / 2
                                                val y = location[1] + current.height / 2
                                                Rect(x, y, x + 1, y + 1)
                                            } else {
                                                null
                                            }
                                            check(System.nanoTime() < deadline) { "$source PixelCopy deadline expired before request" }
                                            if (!requestState.compareAndSet(PixelRequestState.CLAIMED, PixelRequestState.SUBMITTED)) {
                                                return@post
                                            }
                                            when (current) {
                                                is TextureView -> PixelCopy.request(host.window, checkNotNull(windowRect), bitmap, listener, handler)
                                                is SurfaceView -> PixelCopy.request(current, bitmap, listener, handler)
                                                else -> error("unexpected platform video view")
                                            }
                                            requested = true
                                        } catch (cause: Throwable) {
                                            recordFailure(cause)
                                        } finally {
                                            if (!requested) {
                                                runCatching { bitmap.recycle() }.onFailure(::recordFailure)
                                                copied.countDown()
                                            }
                                        }
                                    }
                                }
                                if (!posted) {
                                    recordFailure(AssertionError("main thread rejected $source PixelCopy"))
                                    copied.countDown()
                                }
                                val signalled = copied.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                                if (!signalled) {
                                    val timeout = AssertionError("$source PixelCopy timed out")
                                    runCatching { cancelPendingCopy() }.onFailure(timeout::addSuppressed)
                                    recordFailure(timeout)
                                }
                                failure.get()?.let { throw it }
                            } finally {
                                runCatching { cancelPendingCopy() }.onFailure(::recordFailure)
                            }
                            failure.get()?.let { throw it }
                            if (!useTextureView && result.get() == PixelCopy.ERROR_SOURCE_NO_DATA) {
                                if (System.nanoTime() < deadline) SystemClock.sleep(50)
                                continue
                            }
                            assertEquals("$source PixelCopy failed", PixelCopy.SUCCESS, result.get())
                            val color = pixel.get()
                            if (System.nanoTime() >= deadline) break
                            red = red || (Color.red(color) > 100 && Color.red(color) > Color.green(color) + 30)
                            green = green || (Color.green(color) > 100 && Color.green(color) > Color.red(color) + 30)
                            if (!(red && green)) SystemClock.sleep(50)
                        }
                        failure.get()?.let { throw it }
                        if (compositorCapture) Log.i(
                            "StandbyPlatformControl", "COMPOSITOR_CONCLUSION frames=$compositorFrames red=$red green=$green both=${red && green}",
                        )
                        val output = if (compositorCapture) "window compositor" else if (useTextureView) "window" else "surface"
                        assertTrue("platform video did not render both encoded colors in $output", red && green)
                    }
                }
                failure.get()?.let { throw it }
            } }.onFailure { cause ->
                recordFailure(cause)
                throw checkNotNull(failure.get())
            }
        }
    }

    @Test
    fun staticImage_decodesAndRendersItsGradient() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        val pixel = awaitPixel(fixture, 0.2f, 0.2f) { color ->
            near(Color.red(color), 51) && near(Color.green(color), 70)
        }
        assertTrue("image content was not rendered", Color.green(pixel) > Color.red(pixel))
        assertEquals(StandbyFraming.CENTERED, onUi(fixture.scenario) { fixture.view.currentFraming() })
    }

    @Test
    fun resizeAndFraming_recomputeRealImageOutput() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        awaitPixel(fixture, 0.2f, 0.2f) { color ->
            near(Color.red(color), 51) && near(Color.green(color), 70)
        }

        val resized = CountDownLatch(1)
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right - left == 120 && bottom - top == 160) resized.countDown()
        }
        try {
            onUi(fixture.scenario) {
                fixture.view.addOnLayoutChangeListener(listener)
                fixture.view.layoutParams = FrameLayout.LayoutParams(120, 160)
            }
            assertTrue("resized underlay was not laid out", resized.await(5, TimeUnit.SECONDS))
        } finally {
            onUi(fixture.scenario) { fixture.view.removeOnLayoutChangeListener(listener) }
        }
        awaitPixel(fixture, 0.2f, 0.2f) { color ->
            near(Color.red(color), 70) && near(Color.green(color), 51)
        }

        val framing = StandbyFraming(0.75f, 0.5f, 2f)
        onUi(fixture.scenario) { fixture.view.setFraming(framing) }
        awaitPixel(fixture, 0.2f, 0.2f) { color ->
            near(Color.red(color), 163) && near(Color.green(color), 89)
        }
        assertEquals(framing, onUi(fixture.scenario) { fixture.view.currentFraming() })
        assertEquals(StandbyPlaybackState.READY, onUi(fixture.scenario) { fixture.controller.state })
    }

    @Test
    fun onePixelBitmap_edgeFramingAtFourTimesZoomFillsView() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
        try {
            withFixture({}) { fixture ->
                try {
                    val cases = listOf(
                        StandbyFraming(0f, 0f, 4f) to Color.RED,
                        StandbyFraming(1f, 0f, 4f) to Color.GREEN,
                        StandbyFraming(0f, 1f, 4f) to Color.BLUE,
                        StandbyFraming(1f, 1f, 4f) to Color.MAGENTA,
                    )
                    val points = listOf(
                        0.01f to 0.01f, 0.99f to 0.01f,
                        0.01f to 0.99f, 0.99f to 0.99f, 0.5f to 0.5f,
                    )
                    for ((framing, color) in cases) {
                        onUi(fixture.scenario) {
                            bitmap.eraseColor(color)
                            fixture.view.setBitmap(bitmap)
                            fixture.view.setFraming(framing)
                        }
                        assertEquals(framing, onUi(fixture.scenario) { fixture.view.currentFraming() })
                        for ((x, y) in points) {
                            awaitPixel(fixture, x, y) { pixel ->
                                near(Color.red(pixel), Color.red(color)) &&
                                    near(Color.green(pixel), Color.green(color)) &&
                                    near(Color.blue(pixel), Color.blue(color))
                            }
                        }
                    }
                } finally {
                    onUi(fixture.scenario) { fixture.view.setBitmap(null) }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun finiteGif_keepsRenderingBothFramesBeyondItsEncodedLoops() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        SystemClock.sleep(600)
        assertGifFramesChange(fixture)
        assertEquals(StandbyPlaybackState.PLAYING, onUi(fixture.scenario) { fixture.controller.state })
        fixture.observer.throwIfFailed()
    }

    @Test
    fun gifPauseResumeAndRestart_waitForActualPlayback() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        actAndAwait(fixture, StandbyPlaybackState.PAUSED) { fixture.controller.pause() }
        SystemClock.sleep(300)
        val pausedPixel = capturePixel(fixture, 0.5f, 0.5f)
        repeat(5) {
            SystemClock.sleep(60)
            assertEquals("GIF changed while paused", pausedPixel, capturePixel(fixture, 0.5f, 0.5f))
        }
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.controller.resume() }
        assertGifFramesChange(fixture)
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.controller.restart() }
        assertGifFramesChange(fixture)
    }

    @Test
    fun gifResumeRestartAndShow_beginAtFirstFrame() = withFixture(::writeSlowGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        awaitActivityAction(fixture.scenario) {
            fixture.observer.stateHook = { state ->
                val drawable = fixture.view.mediaDrawable as? AnimatedImageDrawable
                Log.i("StandbyGifFirstFrame", "state=$state at=${System.nanoTime()} drawable=${System.identityHashCode(drawable)} running=${drawable?.isRunning} visible=${fixture.view.visibility}")
            }
        }
        fun transition(expected: StandbyPlaybackState, action: () -> Unit) {
            fixture.observer.clearStates()
            awaitActivityAction(fixture.scenario) { action() }
            fixture.observer.awaitState(expected)
        }
        fun firstFrameAfter(label: String, action: () -> Unit) {
            var startedAt = 0L
            transition(StandbyPlaybackState.PLAYING) {
                startedAt = System.nanoTime()
                Log.i("StandbyGifFirstFrame", "action=$label at=$startedAt drawable=${System.identityHashCode(fixture.view.mediaDrawable)}")
                action()
            }
            assertGifFirstFrame(fixture, label, startedAt)
        }
        awaitPixel(fixture, 0.5f, 0.5f) { color -> Color.blue(color) > Color.red(color) + 40 }
        transition(StandbyPlaybackState.PAUSED) { fixture.controller.pause() }
        firstFrameAfter("resume") { fixture.controller.resume() }

        awaitPixel(fixture, 0.5f, 0.5f) { color -> Color.blue(color) > Color.red(color) + 40 }
        firstFrameAfter("restart") { fixture.controller.restart() }
        awaitPixel(fixture, 0.5f, 0.5f) { color -> Color.blue(color) > Color.red(color) + 40 }
        transition(StandbyPlaybackState.PREPARING) { fixture.view.visibility = View.INVISIBLE }
        firstFrameAfter("show") { fixture.view.visibility = View.VISIBLE }
    }

    @Test
    fun gifPinnedSource_replaysAfterStoreReplacementAndDeletesAfterRelease() = withFixture(::writeSlowGif) { fixture ->
        fun publishGif(): File = runBlocking {
            val staged = fixture.store.stageContent({ fixture.file.inputStream() }, StandbyFraming.CENTERED)
                as StandbyStageResult.Staged
            val committed = fixture.store.confirm(staged.candidate) as StandbyConfirmResult.Committed
            checkNotNull(committed.active.file)
        }

        val sourceA = publishGif()
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            fixture.controller.attach(sourceA, isVideo = false, isAnimated = true)
        }
        val original = onUi(fixture.scenario) {
            checkNotNull(fixture.view.mediaDrawable as? AnimatedImageDrawable)
        }
        awaitPixel(fixture, 0.5f, 0.5f) { color -> Color.blue(color) > Color.red(color) + 40 }
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.view.visibility = View.GONE }

        val sourceB = publishGif()
        assertTrue("replacement did not publish a distinct source", sourceA != sourceB)
        assertTrue("pinned GIF was deleted during replacement", sourceA.isFile)
        assertTrue("replacement GIF is missing", sourceB.isFile)
        var startedAt = 0L
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            startedAt = System.nanoTime()
            fixture.view.visibility = View.VISIBLE
        }
        assertGifFirstFrame(fixture, "show after store replacement", startedAt)
        onUi(fixture.scenario) {
            val replay = checkNotNull(fixture.view.mediaDrawable as? AnimatedImageDrawable)
            assertTrue("GIF replay reused the old drawable", replay !== original)
        }
        assertTrue("replayed GIF lost its pin before release", sourceA.isFile)

        actAndAwait(fixture, StandbyPlaybackState.RELEASED) { fixture.controller.release() }
        assertFalse("retired GIF survived its final source release", sourceA.exists())
        assertTrue("active replacement GIF was deleted", sourceB.isFile)
    }

    @Test
    fun gifReplacementAndRelease_stopAndDetachActualDrawables() = withFixture(::writeGif) { fixture ->
        val still = File(checkNotNull(fixture.file.parentFile), "still.png")
        writeGradient(still)
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val first = onUi(fixture.scenario) {
            val drawable = fixture.view.mediaDrawable as AnimatedImageDrawable
            assertTrue(drawable.isRunning && drawable.callback === fixture.view)
            drawable
        }

        actAndAwait(fixture, StandbyPlaybackState.READY) {
            fixture.controller.attach(still, isVideo = false, isAnimated = false)
        }
        onUi(fixture.scenario) {
            assertFalse(first.isRunning)
            assertEquals(null, first.callback)
            assertTrue(fixture.view.mediaDrawable !== first)
        }

        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val second = onUi(fixture.scenario) {
            val drawable = fixture.view.mediaDrawable as AnimatedImageDrawable
            assertTrue(drawable !== first && drawable.isRunning && drawable.callback === fixture.view)
            drawable
        }
        actAndAwait(fixture, StandbyPlaybackState.RELEASED) { fixture.controller.release() }
        onUi(fixture.scenario) {
            assertFalse(second.isRunning)
            assertEquals(null, second.callback)
            assertEquals(null, fixture.view.mediaDrawable)
        }
    }

    @Test
    fun hiddenUnderlay_interruptsAndRestartsGifWhenShown() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.view.visibility = View.INVISIBLE }
        assertFalse(onUi(fixture.scenario) { fixture.view.isShown })
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.view.visibility = View.VISIBLE }
        assertGifFramesChange(fixture)
    }

    @Test
    fun underlayDetachAndRebind_interruptsThenRendersAgain() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) {
            fixture.driver.bindUnderlay(null)
            fixture.host.contentRoot.removeView(fixture.view)
        }
        assertFalse(onUi(fixture.scenario) { fixture.view.isAttachedToWindow })
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            fixture.host.contentRoot.addView(fixture.view, FrameLayout.LayoutParams(160, 120))
            fixture.driver.bindUnderlay(fixture.view)
        }
        assertTrue(onUi(fixture.scenario) { fixture.view.isAttachedToWindow })
        assertGifFramesChange(fixture)
    }

    @Test
    fun interruptedGif_sameViewAndDetachRebindSurviveReentrantUnbind() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        try {
            for (detachAgain in listOf(false, true)) {
                var rebound = false
                val failure = AtomicReference<Throwable?>(null)
                onUi(fixture.scenario) {
                    fixture.observer.stateHook = { state ->
                        if (state == StandbyPlaybackState.PREPARING && !rebound) {
                            rebound = true
                            try {
                                if (detachAgain) fixture.driver.bindUnderlay(null)
                                fixture.driver.bindUnderlay(fixture.view)
                            } catch (cause: Throwable) {
                                failure.set(cause)
                            }
                        }
                    }
                }
                actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.driver.bindUnderlay(null) }
                assertTrue("interruption did not rebind", rebound)
                assertEquals(null, failure.get())
                onUi(fixture.scenario) {
                    assertTrue(fixture.view.isOwnedBy(fixture.driver))
                    val image = fixture.view.mediaDrawable as AnimatedImageDrawable
                    assertTrue(image.isRunning && image.callback === fixture.view)
                }
                assertGifFramesChange(fixture)
            }
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
        }
    }

    @Test
    fun reentrantGifRebindCancellation_rollsBackAndCanRestart() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val cancellation = java.util.concurrent.CancellationException("starting observer")
        var rebound = false
        try {
            val failure = onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING && !rebound) {
                        rebound = true
                        fixture.driver.bindUnderlay(fixture.view)
                    } else if (state == StandbyPlaybackState.STARTING && rebound) {
                        throw cancellation
                    }
                }
                try {
                    fixture.driver.bindUnderlay(null)
                    null
                } catch (cause: java.util.concurrent.CancellationException) {
                    cause
                } finally {
                    fixture.observer.stateHook = null
                }
            }
            assertTrue("same-view rebind was not reached", rebound)
            assertTrue(failure === cancellation)
            onUi(fixture.scenario) {
                assertFalse(fixture.view.isOwnedBy(fixture.driver))
                assertFalse(fixture.driver.isBoundUnderlay(fixture.view))
                assertEquals(null, fixture.view.mediaDrawable)
                assertEquals(StandbyPlaybackState.PREPARING, fixture.controller.state)
            }
            actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.driver.bindUnderlay(fixture.view) }
            assertGifFramesChange(fixture)
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
        }
    }

    @Test
    fun gifRollback_finishesDetachWhenInterruptedObserverCancelsAgain() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val first = java.util.concurrent.CancellationException("starting")
        val second = java.util.concurrent.CancellationException("rollback")
        var interruptions = 0
        try {
            val failure = onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING) {
                        interruptions++
                        if (interruptions == 1) fixture.driver.bindUnderlay(fixture.view) else throw second
                    } else if (state == StandbyPlaybackState.STARTING) {
                        throw first
                    }
                }
                try {
                    fixture.driver.bindUnderlay(null)
                    null
                } catch (cause: java.util.concurrent.CancellationException) {
                    cause
                } finally {
                    fixture.observer.stateHook = null
                }
            }
            assertTrue(failure === first)
            assertTrue(first.suppressed.any { it === second })
            assertEquals(2, interruptions)
            onUi(fixture.scenario) {
                assertFalse(fixture.view.isOwnedBy(fixture.driver))
                assertFalse(fixture.driver.isBoundUnderlay(fixture.view))
                assertEquals(null, fixture.view.mediaDrawable)
            }
            actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.driver.bindUnderlay(fixture.view) }
            assertGifFramesChange(fixture)
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
        }
    }

    @Test
    fun gifRollback_reentrantRebindDoesNotRecurse() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val cancellation = java.util.concurrent.CancellationException("starting")
        var rebinds = 0
        try {
            val failure = onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING) {
                        rebinds++
                        fixture.driver.bindUnderlay(fixture.view)
                    } else if (state == StandbyPlaybackState.STARTING) {
                        throw cancellation
                    }
                }
                try {
                    fixture.driver.bindUnderlay(null)
                    null
                } catch (cause: java.util.concurrent.CancellationException) {
                    cause
                } finally {
                    fixture.observer.stateHook = null
                }
            }
            assertTrue(failure === cancellation)
            assertEquals(2, rebinds)
            onUi(fixture.scenario) {
                assertFalse(fixture.view.isOwnedBy(fixture.driver))
                assertFalse(fixture.driver.isBoundUnderlay(fixture.view))
            }
            actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.driver.bindUnderlay(fixture.view) }
            assertGifFramesChange(fixture)
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
        }
    }

    @Test
    fun gifRollback_alternatingViewsCannotReenterRecursively() = withFixture(::writeGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        val other = onUi(fixture.scenario) {
            StandbyMediaUnderlayView(fixture.host).also {
                fixture.host.contentRoot.addView(it, FrameLayout.LayoutParams(160, 120))
            }
        }
        val cancellation = java.util.concurrent.CancellationException("starting")
        var rebinds = 0
        try {
            val failure = onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING) {
                        rebinds++
                        fixture.driver.bindUnderlay(if (rebinds % 2 == 1) other else fixture.view)
                    } else if (state == StandbyPlaybackState.STARTING) {
                        throw cancellation
                    }
                }
                try {
                    fixture.driver.bindUnderlay(null)
                    null
                } catch (cause: java.util.concurrent.CancellationException) {
                    cause
                } finally {
                    fixture.observer.stateHook = null
                }
            }
            assertTrue(failure === cancellation)
            assertEquals(2, rebinds)
            onUi(fixture.scenario) {
                assertFalse(fixture.driver.isBoundUnderlay(fixture.view))
                assertFalse(fixture.driver.isBoundUnderlay(other))
                assertFalse(fixture.view.isOwnedBy(fixture.driver))
                assertFalse(other.isOwnedBy(fixture.driver))
            }
            actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.driver.bindUnderlay(fixture.view) }
        } finally {
            onUi(fixture.scenario) {
                fixture.observer.stateHook = null
                fixture.host.contentRoot.removeView(other)
            }
        }
        assertGifFramesChange(fixture)
    }

    @Test
    fun drawerLikeOverlayAndSameViewBind_doNotRestartGif() = withFixture(::writeSlowGif) { fixture ->
        attach(fixture, isVideo = false, isAnimated = true, expected = StandbyPlaybackState.PLAYING)
        awaitPixel(fixture, 0.5f, 0.5f) { color -> Color.blue(color) > Color.red(color) + 40 }
        val blueObservedAt = System.nanoTime()
        val laidOut = CountDownLatch(1)
        val overlay = onUi(fixture.scenario) {
            View(fixture.host).apply {
                setBackgroundColor(Color.BLACK)
                addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    if (right > left && bottom > top) laidOut.countDown()
                }
            }
        }
        try {
            fixture.observer.clearStates()
            onUi(fixture.scenario) {
                fixture.host.contentRoot.addView(overlay, FrameLayout.LayoutParams(40, 120))
                fixture.driver.bindUnderlay(fixture.view)
            }
            assertTrue("overlay was not laid out", laidOut.await(5, TimeUnit.SECONDS))
            val afterOverlay = capturePixel(fixture, 0.5f, 0.5f)
            assertTrue(
                "overlay evidence was delayed beyond the frame interval",
                System.nanoTime() - blueObservedAt < TimeUnit.SECONDS.toNanos(1),
            )
            assertTrue("overlay or same-view bind restarted GIF", Color.blue(afterOverlay) > Color.red(afterOverlay) + 40)
            fixture.observer.assertNoStateChange(1, TimeUnit.SECONDS)
            assertEquals(StandbyPlaybackState.PLAYING, onUi(fixture.scenario) { fixture.controller.state })
            assertGifFramesChange(fixture)
        } finally {
            onUi(fixture.scenario) { fixture.host.contentRoot.removeView(overlay) }
        }
    }

    @Test
    fun video_rendersOnAttachedSurfaceView() = withFixture(::writeVideo) { fixture ->
        val window = onUi(fixture.scenario) { host ->
            fixture.view.setFraming(StandbyFraming(0.9f, 0.1f, 1.7f))
            host.window
        }
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val (binding, token) = onUi(fixture.scenario) {
            fixture.driver.underlayBindingGeneration to checkNotNull(window.decorView.windowToken)
        }
        val output = assertVideoFramesChange(fixture, fixture.driver)
        val extractor = MediaExtractor()
        val durationUs = AutoCloseable { extractor.release() }.use {
            extractor.setDataSource(fixture.file.absolutePath)
            val track = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            track.getLong(MediaFormat.KEY_DURATION)
        }
        check(durationUs in 1L..TimeUnit.MINUTES.toMicros(1)) { "invalid test video duration" }
        Log.i("StandbyVideoOutput", "loopInterval durationUs=$durationUs output=${System.identityHashCode(output)} binding=$binding")
        SystemClock.sleep(TimeUnit.MICROSECONDS.toMillis(durationUs) + 500)
        assertTrue("loop observation changed output lifetime", assertVideoFramesChange(fixture, fixture.driver) === output)
        onUi(fixture.scenario) { host ->
            assertTrue(host === fixture.host && host.window === window && window.decorView.windowToken === token)
            assertEquals(binding, fixture.driver.underlayBindingGeneration)
            assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
        }
        actAndAwait(fixture, StandbyPlaybackState.RELEASED) { fixture.controller.release() }
        assertTrue("public release retained a native video owner", onUi(fixture.scenario) { fixture.driver.isVideoNativeClosed })
    }

    @Test
    fun failedNativeCloseRetiresOldViewAndRetriesBeforeRebinding() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val output = onUi(fixture.scenario) { checkNotNull(fixture.view.currentVideoOutput) }
        val replacement = onUi(fixture.scenario) { StandbyMediaUnderlayView(fixture.host) }
        val failure = IllegalStateException("simulated native close failure")
        val cleanup = FixtureCleanup()
        try {
            fixture.observer.expectedError = failure
            onUi(fixture.scenario) {
                fixture.driver.beforeVideoReleaseForTest = { throw failure }
                val thrown = runCatching { fixture.controller.pause() }.exceptionOrNull()
                assertTrue(thrown === failure)
                assertFalse(fixture.driver.isVideoNativeClosed)
                fixture.driver.retireUnderlay(fixture.view)
                val strongReference = AndroidStandbyMediaDriver::class.java.getDeclaredField("activeUnderlay")
                strongReference.isAccessible = true
                assertEquals(null, strongReference.get(fixture.driver))
                fixture.host.contentRoot.removeView(fixture.view)
            }
            val destroyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < destroyDeadline && onUi(fixture.scenario) { output.alive }) SystemClock.sleep(40)
            onUi(fixture.scenario) {
                assertEquals("expected close failure was not reported to observer", 1, fixture.observer.expectedErrorCount)
                assertTrue("reported error instance did not match injected failure", fixture.observer.lastExpectedError === failure)
                assertFalse("framework holder did not retire", output.alive)
                assertFalse("holder loss was mistaken for native release", fixture.driver.isVideoNativeClosed)
                assertEquals(StandbyPlaybackState.ERROR, fixture.controller.state)
                assertTrue(runCatching { fixture.driver.bindUnderlay(replacement) }.isFailure)
                fixture.driver.beforeVideoReleaseForTest = null
                fixture.controller.attach(null, isVideo = false, isAnimated = false)
            }
            val retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < retryDeadline &&
                onUi(fixture.scenario) { fixture.controller.state != StandbyPlaybackState.IDLE }
            ) SystemClock.sleep(40)
            onUi(fixture.scenario) {
                assertEquals(StandbyPlaybackState.IDLE, fixture.controller.state)
                assertTrue(fixture.driver.isVideoNativeClosed)
                fixture.driver.bindUnderlay(null)
                fixture.host.contentRoot.addView(replacement, FrameLayout.LayoutParams(160, 120))
                fixture.driver.bindUnderlay(replacement)
                assertTrue(fixture.driver.isBoundUnderlay(replacement))
                fixture.driver.onFrameworkVideoSurfaceDestroyed(fixture.view, output)
                assertTrue(fixture.driver.isBoundUnderlay(replacement))
                fixture.driver.bindUnderlay(null)
                fixture.host.contentRoot.removeView(replacement)
                assertEquals("unexpected duplicate error delivery", 1, fixture.observer.expectedErrorCount)
            }
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { onUi(fixture.scenario) { fixture.driver.beforeVideoReleaseForTest = null } }
                cleanup.attempt { fixture.observer.expectedError = null }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    @Test
    fun frameworkHiddenVisibilityCancellationReleasesAndAllowsReattach() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        val cancellation = java.util.concurrent.CancellationException("hidden observer")
        val observed = AtomicReference<Throwable?>(null)
        fixture.observer.clearStates()
        onUi(fixture.scenario) {
            fixture.observer.stateHook = { state ->
                if (state == StandbyPlaybackState.PREPARING) {
                    observed.set(cancellation)
                    throw cancellation
                }
            }
            fixture.view.visibility = View.INVISIBLE
        }
        fixture.observer.awaitState(StandbyPlaybackState.IDLE)
        assertTrue(observed.get() === cancellation)
        onUi(fixture.scenario) {
            fixture.observer.stateHook = null
            assertEquals(null, fixture.view.mediaDrawable)
            fixture.view.visibility = View.VISIBLE
        }
        fixture.observer.throwIfFailed()
        actAndAwait(fixture, StandbyPlaybackState.READY) {
            fixture.controller.attach(fixture.file, isVideo = false, isAnimated = false)
        }
    }

    @Test
    fun frameworkVisibleReadyCancellationReleasesAndAllowsReattach() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.view.visibility = View.INVISIBLE }
        val cancellation = java.util.concurrent.CancellationException("visible observer")
        val observed = AtomicReference<Throwable?>(null)
        fixture.observer.clearStates()
        onUi(fixture.scenario) {
            fixture.observer.stateHook = { state ->
                if (state == StandbyPlaybackState.READY) {
                    observed.set(cancellation)
                    throw cancellation
                }
            }
            fixture.view.visibility = View.VISIBLE
        }
        fixture.observer.awaitState(StandbyPlaybackState.IDLE)
        assertTrue(observed.get() === cancellation)
        onUi(fixture.scenario) {
            fixture.observer.stateHook = null
            assertEquals(null, fixture.view.mediaDrawable)
        }
        fixture.observer.throwIfFailed()
        actAndAwait(fixture, StandbyPlaybackState.READY) {
            fixture.controller.attach(fixture.file, isVideo = false, isAnimated = false)
        }
    }

    @Test
    fun frameworkVisibilityOldCancellationDoesNotCloseReentrantReplacement() = withFixture(::writeGradient) { fixture ->
        val replacement = File(checkNotNull(fixture.file.parentFile), "framework-replacement.png")
        writeGradient(replacement)
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        val cancellation = java.util.concurrent.CancellationException("old visibility observer")
        val observed = AtomicReference<Throwable?>(null)
        val callbackReached = CountDownLatch(1)
        var replaced = false
        fixture.observer.clearStates()
        onUi(fixture.scenario) {
            fixture.observer.stateHook = { state ->
                if (state == StandbyPlaybackState.PREPARING && !replaced) {
                    replaced = true
                    fixture.controller.attach(replacement, isVideo = false, isAnimated = false)
                    observed.set(cancellation)
                    callbackReached.countDown()
                    throw cancellation
                }
            }
            fixture.view.visibility = View.INVISIBLE
        }
        assertTrue("visibility callback was not reached", callbackReached.await(5, TimeUnit.SECONDS))
        assertTrue(observed.get() === cancellation)
        onUi(fixture.scenario) {
            fixture.observer.stateHook = null
            assertEquals(StandbyPlaybackState.PREPARING, fixture.controller.state)
            assertTrue(fixture.view.isOwnedBy(fixture.driver))
        }
        fixture.observer.throwIfFailed()
        actAndAwait(fixture, StandbyPlaybackState.READY) { fixture.view.visibility = View.VISIBLE }
        assertTrue(onUi(fixture.scenario) { fixture.view.mediaDrawable != null })
    }

    @Test
    fun frameworkVideoRenderingCancellationReleasesAndAllowsReattach() = withFixture(::writeVideo) { fixture ->
        val cancellation = java.util.concurrent.CancellationException("video rendering observer")
        val observed = AtomicReference<Throwable?>(null)
        val cancelledOutput = AtomicReference<StandbyVideoOutput?>(null)
        fixture.observer.clearStates()
        val cleanup = FixtureCleanup()
        try {
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PLAYING) {
                        cancelledOutput.set(checkNotNull(fixture.view.currentVideoOutput))
                        observed.set(cancellation)
                        throw cancellation
                    }
                }
                fixture.controller.attach(fixture.file, isVideo = true, isAnimated = false)
            }
            fixture.observer.awaitState(StandbyPlaybackState.IDLE)
            assertTrue(observed.get() === cancellation)
            onUi(fixture.scenario) {
                assertTrue(fixture.driver.isVideoNativeClosed)
                assertEquals(View.GONE, checkNotNull(findSurfaceView(fixture.view)).visibility)
            }
        } catch (error: Throwable) {
            Log.e("StandbyVideoOutput", "frameworkVideoRenderingCancellation failed", error)
            cleanup.record(error)
        } finally {
            cleanup.attempt {
                try {
                    onUi(fixture.scenario) { fixture.observer.stateHook = null }
                } catch (resetError: Throwable) {
                    Log.e("StandbyVideoOutput", "stateHook reset failed", resetError)
                    throw resetError
                }
            }
            cleanup.restoreInterrupt()
        }
        cleanup.throwIfFailed()
        fixture.observer.throwIfFailed()
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            fixture.controller.attach(fixture.file, isVideo = true, isAnimated = false)
        }
        onUi(fixture.scenario) {
            val current = checkNotNull(fixture.view.currentVideoOutput)
            val old = checkNotNull(cancelledOutput.get())
            assertTrue(current !== old)
            fixture.driver.onFrameworkVideoSurfaceAvailable(fixture.view, old)
            assertTrue(fixture.view.currentVideoOutput === current && fixture.view.ownsVideoOutput(current))
            assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
        }
    }

    @Test
    fun frameworkHolderDestroyedCancellationSettlesOwnerAndAllowsReattach() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val cancellation = java.util.concurrent.CancellationException("holder destroyed observer")
        val observed = AtomicReference<Throwable?>(null)
        val child = onUi(fixture.scenario) { checkNotNull(findSurfaceView(fixture.view)) }
        val output = onUi(fixture.scenario) { checkNotNull(fixture.view.currentVideoOutput) }
        fixture.observer.clearStates()
        val cleanup = FixtureCleanup()
        try {
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING) {
                        observed.set(cancellation)
                        throw cancellation
                    }
                }
                fixture.view.removeView(child)
            }
            fixture.observer.awaitState(StandbyPlaybackState.IDLE)
            assertTrue(observed.get() === cancellation)
            onUi(fixture.scenario) {
                assertFalse(output.alive)
                assertTrue(fixture.driver.isVideoNativeClosed)
                assertEquals(StandbyPlaybackState.IDLE, fixture.controller.state)
            }
        } catch (error: Throwable) {
            Log.e("StandbyVideoOutput", "frameworkHolderDestroyedCancellation failed", error)
            cleanup.record(error)
        } finally {
            cleanup.attempt {
                try {
                    onUi(fixture.scenario) { fixture.observer.stateHook = null }
                } catch (resetError: Throwable) {
                    Log.e("StandbyVideoOutput", "stateHook reset failed", resetError)
                    throw resetError
                }
            }
            cleanup.restoreInterrupt()
        }
        cleanup.throwIfFailed()
        fixture.observer.throwIfFailed()
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            fixture.view.addView(child, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            fixture.controller.attach(fixture.file, isVideo = true, isAnimated = false)
        }
    }

    @Test
    fun nativePlayerRetirementDoesNotReleaseFrameworkHolderSurface() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val output = onUi(fixture.scenario) { checkNotNull(fixture.view.currentVideoOutput) }
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) {
            fixture.driver.onUnderlayVisibilityChanged(fixture.view, false)
        }
        onUi(fixture.scenario) {
            val child = checkNotNull(findSurfaceView(fixture.view))
            assertTrue("player was not retired", fixture.driver.isVideoNativeClosed)
            assertTrue("driver invalidated a framework-owned Surface", output.alive && output.surface.isValid)
            assertTrue(child.holder.surface === output.surface && fixture.view.currentVideoOutput === output)
        }
        actAndAwait(fixture, StandbyPlaybackState.IDLE) {
            fixture.controller.attach(null, isVideo = false, isAnimated = false)
        }
    }

    @Test
    fun frameworkStaleOutputDestroyedKeepsReplacementVideoOwner() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val old = onUi(fixture.scenario) { checkNotNull(fixture.view.currentVideoOutput) }
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.controller.restart() }
        onUi(fixture.scenario) {
            val current = checkNotNull(fixture.view.currentVideoOutput)
            assertTrue(current !== old)
            assertFalse(old.alive)
            fixture.driver.onFrameworkVideoSurfaceDestroyed(fixture.view, old)
            assertTrue(fixture.view.currentVideoOutput === current && current.alive && current.surface.isValid)
            assertTrue(fixture.view.isOwnedBy(fixture.driver))
            assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
        }
        fixture.observer.throwIfFailed()
        assertVideoFramesChange(fixture, fixture.driver)
    }

    @Test
    fun initialStillReadyObserverCancellationDetachesAndAllowsAnotherAttach() = withFixture(::writeGradient) { fixture ->
        verifyInitialObserverCancellation(fixture, isVideo = false)
    }

    @Test
    fun initialVideoStartingObserverCancellationReleasesAndAllowsAnotherAttach() = withFixture(::writeVideo) { fixture ->
        verifyInitialObserverCancellation(fixture, isVideo = true)
    }

    private fun verifyInitialObserverCancellation(fixture: Fixture, isVideo: Boolean) {
        val still = File(checkNotNull(fixture.file.parentFile), "other.png")
        writeGradient(still)
        val otherObserver = StateObserver()
        val other = onUi(fixture.scenario) {
            val view = StandbyMediaUnderlayView(fixture.host)
            fixture.host.contentRoot.addView(view, FrameLayout.LayoutParams(160, 120))
            val driver = AndroidStandbyMediaDriver(fixture.host, checkNotNull(still.parentFile), fixture.store)
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { _, _, cause -> otherObserver.onError(cause) },
                onStateChanged = { state -> otherObserver.onStateChanged(state) },
            )
            driver.bindUnderlay(view)
            controller.attach(still, isVideo = false, isAnimated = false)
            Triple(view, driver, controller)
        }
        try {
            otherObserver.awaitState(StandbyPlaybackState.READY)
            val otherImage = onUi(fixture.scenario) { checkNotNull(other.first.mediaDrawable) }
            val cancellation = java.util.concurrent.CancellationException("initial observer")
            val observed = AtomicReference<Throwable?>(null)
            val target = if (isVideo) StandbyPlaybackState.STARTING else StandbyPlaybackState.READY
            fixture.observer.clearStates()
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == target) {
                        observed.set(cancellation)
                        throw cancellation
                    }
                }
                fixture.controller.attach(fixture.file, isVideo = isVideo, isAnimated = false)
            }
            fixture.observer.awaitState(StandbyPlaybackState.IDLE)
            assertTrue(observed.get() === cancellation)
            onUi(fixture.scenario) {
                fixture.observer.stateHook = null
                assertEquals(null, fixture.view.mediaDrawable)
                assertEquals(View.GONE, checkNotNull(findSurfaceView(fixture.view)).visibility)
                assertTrue(other.first.isOwnedBy(other.second))
                assertTrue(other.first.mediaDrawable === otherImage)
                assertTrue(otherImage.callback === other.first)
                assertEquals(StandbyPlaybackState.READY, other.third.state)
            }
            fixture.observer.throwIfFailed()
            otherObserver.throwIfFailed()
            actAndAwait(fixture, StandbyPlaybackState.READY) {
                fixture.controller.attach(still, isVideo = false, isAnimated = false)
            }
            assertTrue(onUi(fixture.scenario) { fixture.view.mediaDrawable != null })
            actAndAwait(fixture, StandbyPlaybackState.RELEASED) { fixture.controller.release() }
            assertEquals(null, onUi(fixture.scenario) { fixture.view.mediaDrawable })
            otherObserver.throwIfFailed()
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
            otherObserver.clearStates()
            val state = onUi(fixture.scenario) {
                other.third.release()
                other.third.state
            }
            if (state != StandbyPlaybackState.RELEASED) {
                otherObserver.awaitState(StandbyPlaybackState.RELEASED, cleanup = true)
            }
            onUi(fixture.scenario) {
                other.second.bindUnderlay(null)
                fixture.host.contentRoot.removeView(other.first)
            }
        }
    }

    @Test
    fun initialStillCancellationDoesNotDetachReentrantNewViewOwner() = withFixture(::writeGradient) { fixture ->
        val replacementFile = File(checkNotNull(fixture.file.parentFile), "replacement.png")
        writeGradient(replacementFile)
        val nextObserver = StateObserver()
        val next = onUi(fixture.scenario) {
            val driver = AndroidStandbyMediaDriver(fixture.host, checkNotNull(replacementFile.parentFile), fixture.store)
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { _, _, cause -> nextObserver.onError(cause) },
                onStateChanged = { state -> nextObserver.onStateChanged(state) },
            )
            controller.attach(replacementFile, isVideo = false, isAnimated = false)
            driver to controller
        }
        val cancellation = java.util.concurrent.CancellationException("old observer")
        val observed = AtomicReference<Throwable?>(null)
        try {
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.READY) {
                        fixture.controller.release()
                        fixture.driver.bindUnderlay(null)
                        next.first.bindUnderlay(fixture.view)
                        observed.set(cancellation)
                        throw cancellation
                    }
                }
                fixture.controller.attach(fixture.file, isVideo = false, isAnimated = false)
            }
            nextObserver.awaitState(StandbyPlaybackState.READY)
            assertTrue(observed.get() === cancellation)
            onUi(fixture.scenario) {
                fixture.observer.stateHook = null
                fixture.controller.release()
                fixture.driver.bindUnderlay(null)
                assertTrue(fixture.view.isOwnedBy(next.first))
                assertTrue(checkNotNull(fixture.view.mediaDrawable).callback === fixture.view)
                assertEquals(StandbyPlaybackState.READY, next.second.state)
            }
            fixture.observer.throwIfFailed()
            nextObserver.throwIfFailed()
        } finally {
            onUi(fixture.scenario) { fixture.observer.stateHook = null }
            nextObserver.clearStates()
            val state = onUi(fixture.scenario) {
                next.second.release()
                next.second.state
            }
            if (state != StandbyPlaybackState.RELEASED) {
                nextObserver.awaitState(StandbyPlaybackState.RELEASED, cleanup = true)
            }
            onUi(fixture.scenario) { next.first.bindUnderlay(null) }
        }
    }

    @Test
    fun video_restartUsesFreshHolderOutputAndRendersAgain() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val original = assertVideoFramesChange(fixture, fixture.driver)

        actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.controller.restart() }
        onUi(fixture.scenario) {
            val current = checkNotNull(fixture.view.currentVideoOutput)
            assertTrue("video restart reused its old holder lifetime", current !== original)
            assertFalse(original.alive)
            fixture.driver.onVideoOutputGeometryChanged(fixture.view, original)
            assertTrue(fixture.view.currentVideoOutput === current && current.alive && current.surface.isValid)
        }
        fixture.observer.assertNoStateChange(300, TimeUnit.MILLISECONDS)
        assertVideoFramesChange(fixture, fixture.driver)
    }

    /** Checks prepared native positions around public pause/resume, not the first displayed frame's PTS. */
    @Test
    fun video_initialStartAndResume_beginAtZeroBeforeNativeStart() = withFixture(::writeVideo) { fixture ->
        /** Retains native identity and the getter snapshots taken before start. */
        data class StartWitness(
            val player: MediaPlayer,
            val output: StandbyVideoOutput,
            val binding: Long,
            val position: Int,
            val isPlaying: Boolean,
        )

        val starts = mutableListOf<StartWitness>()
        val callbackFailures = LinkedBlockingQueue<Throwable>()
        val cleanup = FixtureCleanup()
        try {
            val playerField = AndroidStandbyMediaDriver::class.java.getDeclaredField("player").apply { isAccessible = true }
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.STARTING) {
                        try {
                            check(Looper.myLooper() == Looper.getMainLooper())
                            val player = checkNotNull(playerField.get(fixture.driver) as? MediaPlayer) {
                                "no native MediaPlayer at STARTING"
                            }
                            val witness = StartWitness(
                                player, checkNotNull(fixture.view.currentVideoOutput), fixture.driver.underlayBindingGeneration,
                                player.currentPosition, player.isPlaying,
                            )
                            starts.add(witness)
                            Log.i("StandbyVideoStart", "STARTING pid=${android.os.Process.myPid()} start=${starts.size} " +
                                "player=${System.identityHashCode(player)} output=${System.identityHashCode(witness.output)} " +
                                "binding=${witness.binding} position=${witness.position} isPlaying=${witness.isPlaying} " +
                                "controller=${System.identityHashCode(fixture.controller)} state=${fixture.controller.state} " +
                                "driver=${System.identityHashCode(fixture.driver)} owned=${fixture.view.isOwnedBy(fixture.driver)}")
                            assertEquals(StandbyPlaybackState.STARTING, fixture.controller.state)
                            assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, witness.binding))
                            assertTrue(fixture.view.ownsVideoOutput(witness.output))
                            assertTrue("unexpected extra native start", starts.size <= 2)
                            assertEquals("prepared native position was not zero", 0, witness.position)
                            assertFalse("native player was already playing at STARTING", witness.isPlaying)
                        } catch (error: Throwable) {
                            Log.e("StandbyVideoStart", "STARTING witness failed", error)
                            // Retain observer errors even if later UI access fails.
                            callbackFailures.offer(error)
                        }
                    }
                }
            }
            attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
            val initialOutput = assertVideoFramesChange(fixture, fixture.driver)
            val first = onUi(fixture.scenario) {
                callbackFailures.peek()?.let { throw it }
                assertEquals("initial start witness count", 1, starts.size)
                starts.single().also { assertTrue(it.output === initialOutput) }
            }
            var advanced = false
            val advanceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < advanceDeadline && !advanced) {
                advanced = onUi(fixture.scenario) {
                    callbackFailures.peek()?.let { throw it }
                    fixture.observer.throwIfFailed()
                    assertEquals(1, starts.size)
                    assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
                    assertTrue("initial native player was replaced", playerField.get(fixture.driver) === first.player)
                    assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, first.binding))
                    assertTrue(fixture.view.currentVideoOutput === first.output && first.output.alive)
                    val position = first.player.currentPosition
                    if (position > 0) Log.i("StandbyVideoStart", "ADVANCED pid=${android.os.Process.myPid()} " +
                        "player=${System.identityHashCode(first.player)} output=${System.identityHashCode(first.output)} " +
                        "binding=${first.binding} position=$position state=${fixture.controller.state}")
                    position > 0
                }
                if (!advanced) SystemClock.sleep(40)
            }
            assertTrue("initial native position did not advance before pause", advanced)

            actAndAwait(fixture, StandbyPlaybackState.PAUSED) { fixture.controller.pause() }
            onUi(fixture.scenario) {
                callbackFailures.peek()?.let { throw it }
                assertEquals(StandbyPlaybackState.PAUSED, fixture.controller.state)
                assertTrue("public pause retained a native video owner", fixture.driver.isVideoNativeClosed)
                assertEquals(1, starts.size)
            }
            val retireDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < retireDeadline && onUi(fixture.scenario) { first.output.alive }) SystemClock.sleep(40)
            onUi(fixture.scenario) {
                assertTrue("native video owner reopened before resume", fixture.driver.isVideoNativeClosed)
                assertFalse("paused holder lifetime did not retire", first.output.alive)
            }

            actAndAwait(fixture, StandbyPlaybackState.PLAYING) { fixture.controller.resume() }
            val resumedOutput = assertVideoFramesChange(fixture, fixture.driver)
            onUi(fixture.scenario) {
                callbackFailures.peek()?.let { throw it }
                assertEquals("initial/resume start witness count", 2, starts.size)
                val resumed = starts[1]
                assertTrue("resume reused the old native player", resumed.player !== first.player)
                assertTrue(playerField.get(fixture.driver) === resumed.player)
                assertTrue(resumed.output === resumedOutput && resumed.output !== first.output)
                assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, resumed.binding))
                assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
            }
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { onUi(fixture.scenario) { fixture.observer.stateHook = null } }
                cleanup.attempt {
                    while (true) {
                        val error = callbackFailures.poll() ?: break
                        cleanup.record(error)
                    }
                }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    /** Checks real host stop/resume and prestart zero, not first-displayed-frame PTS. */
    @Test
    fun video_activityStopAndResume_retiresNativePlayerAndStartsAtZero() = withFixture(::writeVideo) { fixture ->
        /** Retains native identity and snapshots taken before native start. */
        data class StartWitness(
            val player: MediaPlayer,
            val output: StandbyVideoOutput,
            val binding: Long,
            val position: Int,
            val isPlaying: Boolean,
        )
        /** @param start Original action origin. @return Remaining five-second acceptance budget. */
        fun remaining(start: Long): Long {
            val nanos = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - start)
            check(nanos > 0) { "host lifecycle action exceeded five seconds" }
            return nanos
        }
        val starts = mutableListOf<StartWitness>()
        val callbackFailures = LinkedBlockingQueue<Throwable>()
        val resumedPlaying = CountDownLatch(1)
        val cleanup = FixtureCleanup()
        try {
            val playerField = AndroidStandbyMediaDriver::class.java.getDeclaredField("player").apply { isAccessible = true }
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    try {
                        check(Looper.myLooper() == Looper.getMainLooper())
                        if (state == StandbyPlaybackState.STARTING) {
                            check(starts.size < 2) { "unexpected extra native start" }
                            val stage = monitor.getLifecycleStageOf(fixture.host)
                            check(stage != Stage.STOPPED) { "native start while host was stopped" }
                            val player = checkNotNull(playerField.get(fixture.driver) as? MediaPlayer)
                            val witness = StartWitness(
                                player, checkNotNull(fixture.view.currentVideoOutput), fixture.driver.underlayBindingGeneration,
                                player.currentPosition, player.isPlaying,
                            )
                            starts.add(witness)
                            Log.i("StandbyVideoHost", "STARTING host=${System.identityHashCode(fixture.host)} stage=$stage " +
                                "start=${starts.size} player=${System.identityHashCode(player)} output=${System.identityHashCode(witness.output)} " +
                                "binding=${witness.binding} position=${witness.position} isPlaying=${witness.isPlaying}")
                            assertEquals(StandbyPlaybackState.STARTING, fixture.controller.state)
                            assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, witness.binding))
                            assertTrue(fixture.view.ownsVideoOutput(witness.output))
                            assertEquals("prepared native position was not zero", 0, witness.position)
                            assertFalse("native player was already playing at STARTING", witness.isPlaying)
                        }
                        if (state == StandbyPlaybackState.ERROR ||
                            state == StandbyPlaybackState.PLAYING && starts.size == 2) resumedPlaying.countDown()
                    } catch (error: Throwable) {
                        Log.e("StandbyVideoHost", "lifecycle playback witness failed", error)
                        callbackFailures.offer(error)
                        resumedPlaying.countDown()
                    }
                }
            }
            attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
            callbackFailures.peek()?.let { throw it }
            val initialOutput = assertVideoFramesChange(fixture, fixture.driver)
            val first = onUi(fixture.scenario) { host ->
                callbackFailures.peek()?.let { throw it }
                fixture.observer.throwIfFailed()
                assertTrue(host === fixture.host && host.contentRoot.getChildAt(0) === fixture.view)
                assertEquals(Stage.RESUMED, monitor.getLifecycleStageOf(host))
                assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
                assertEquals(1, starts.size)
                starts.single().also {
                    assertTrue(it.output === initialOutput && fixture.view.ownsVideoOutput(it.output))
                    assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, it.binding))
                    assertTrue(playerField.get(fixture.driver) === it.player && it.player.isPlaying)
                }
            }
            val stopStartedAt = System.nanoTime()
            remaining(stopStartedAt)
            fixture.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            remaining(stopStartedAt)
            assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, fixture.scenario.state)
            remaining(stopStartedAt)
            onUi(fixture.scenario) { host ->
                callbackFailures.peek()?.let { throw it }
                fixture.observer.throwIfFailed()
                val stage = monitor.getLifecycleStageOf(host)
                assertTrue(host === fixture.host && host.contentRoot.getChildAt(0) === fixture.view)
                assertEquals(Stage.STOPPED, stage)
                assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, first.binding))
                assertTrue("stopped host retained a native video owner", fixture.driver.isVideoNativeClosed)
                assertEquals(null, playerField.get(fixture.driver))
                assertFalse("stopped holder lifetime did not retire", first.output.alive)
                assertEquals(StandbyPlaybackState.PREPARING, fixture.controller.state)
                assertEquals("native start while stopped", 1, starts.size)
                Log.i("StandbyVideoHost", "STOPPED host=${System.identityHashCode(host)} stage=$stage " +
                    "driver=${System.identityHashCode(fixture.driver)} player=${System.identityHashCode(first.player)} " +
                    "output=${System.identityHashCode(first.output)} nativeClosed=${fixture.driver.isVideoNativeClosed} " +
                    "playerNull=${playerField.get(fixture.driver) == null} outputAlive=${first.output.alive} starts=${starts.size}")
            }
            remaining(stopStartedAt)
            fixture.observer.clearStates()
            val resumeStartedAt = System.nanoTime()
            remaining(resumeStartedAt)
            fixture.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            remaining(resumeStartedAt)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, fixture.scenario.state)
            remaining(resumeStartedAt)
            callbackFailures.peek()?.let { throw it }
            fixture.observer.throwIfFailed()
            assertTrue("resumed host did not play within five seconds",
                resumedPlaying.await(remaining(resumeStartedAt), TimeUnit.NANOSECONDS))
            remaining(resumeStartedAt)
            val resumed = onUi(fixture.scenario) { host ->
                callbackFailures.peek()?.let { throw it }
                fixture.observer.throwIfFailed()
                val stage = monitor.getLifecycleStageOf(host)
                assertTrue(host === fixture.host && host.contentRoot.getChildAt(0) === fixture.view)
                assertEquals(Stage.RESUMED, stage)
                assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
                assertEquals("initial/resume start witness count", 2, starts.size)
                starts[1].also {
                    assertTrue("resume reused the old native player", it.player !== first.player)
                    assertTrue("resume reused the old output", it.output !== first.output)
                    assertTrue(playerField.get(fixture.driver) === it.player && it.player.isPlaying)
                    assertTrue(fixture.driver.isCurrentUnderlayBinding(fixture.view, it.binding))
                    assertTrue(fixture.view.ownsVideoOutput(it.output))
                    Log.i("StandbyVideoHost", "RESUMED host=${System.identityHashCode(host)} stage=$stage " +
                        "player=${System.identityHashCode(it.player)} output=${System.identityHashCode(it.output)} state=${fixture.controller.state}")
                }
            }
            remaining(resumeStartedAt)
            val resumedOutput = assertVideoFramesChange(fixture, fixture.driver)
            onUi(fixture.scenario) {
                callbackFailures.peek()?.let { throw it }
                fixture.observer.throwIfFailed()
                assertEquals(2, starts.size)
                assertEquals(StandbyPlaybackState.PLAYING, fixture.controller.state)
                assertTrue(resumed.output === resumedOutput)
                assertTrue(playerField.get(fixture.driver) === resumed.player && resumed.player.isPlaying)
            }
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { onUi(fixture.scenario) { fixture.observer.stateHook = null } }
                cleanup.attempt {
                    while (true) {
                        val error = callbackFailures.poll() ?: break
                        cleanup.record(error)
                    }
                }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    @Test
    fun preparedObserverCancellation_rollsBackOnlyItsOwnViewBinding() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.driver.bindUnderlay(null) }
        val cancellation = java.util.concurrent.CancellationException("prepared observer")
        val failure = onUi(fixture.scenario) {
            fixture.observer.stateHook = { state -> if (state == StandbyPlaybackState.READY) throw cancellation }
            try {
                fixture.driver.bindUnderlay(fixture.view)
                null
            } catch (cause: java.util.concurrent.CancellationException) {
                cause
            } finally {
                fixture.observer.stateHook = null
            }
        }
        assertTrue(failure === cancellation)
        onUi(fixture.scenario) {
            assertFalse(fixture.view.isOwnedBy(fixture.driver))
            assertFalse(fixture.driver.isBoundUnderlay(fixture.view))
            assertEquals(null, fixture.view.mediaDrawable)
        }
        actAndAwait(fixture, StandbyPlaybackState.READY) { fixture.driver.bindUnderlay(fixture.view) }
        assertTrue(onUi(fixture.scenario) { fixture.view.isOwnedBy(fixture.driver) })
    }

    @Test
    fun occupiedTargetDuringInterruption_doesNotLeaveHalfBoundDriver() = withFixture(::writeGradient) { fixture ->
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        val target = onUi(fixture.scenario) {
            val view = StandbyMediaUnderlayView(fixture.host)
            fixture.host.contentRoot.addView(view, FrameLayout.LayoutParams(160, 120))
            view to AndroidStandbyMediaDriver(fixture.host, checkNotNull(fixture.file.parentFile), fixture.store)
        }
        var claimed = false
        val claimFailure = AtomicReference<Throwable?>(null)
        try {
            val failure = onUi(fixture.scenario) {
                fixture.observer.stateHook = { state ->
                    if (state == StandbyPlaybackState.PREPARING && !claimed) {
                        claimed = true
                        try {
                            target.second.bindUnderlay(target.first)
                        } catch (cause: Throwable) {
                            claimFailure.set(cause)
                        }
                    }
                }
                try {
                    fixture.driver.bindUnderlay(target.first)
                    null
                } catch (cause: IllegalStateException) {
                    cause
                } finally {
                    fixture.observer.stateHook = null
                }
            }
            assertTrue("target was not claimed during interruption", claimed)
            assertEquals(null, claimFailure.get())
            assertTrue("target takeover did not reject the old driver", failure is IllegalStateException)
            onUi(fixture.scenario) {
                assertTrue(target.first.isOwnedBy(target.second))
                assertFalse(fixture.driver.isBoundUnderlay(target.first))
            }
            actAndAwait(fixture, StandbyPlaybackState.READY) { fixture.driver.bindUnderlay(fixture.view) }
            onUi(fixture.scenario) {
                assertTrue(fixture.view.isOwnedBy(fixture.driver))
                assertTrue(target.first.isOwnedBy(target.second))
            }
        } finally {
            onUi(fixture.scenario) {
                fixture.observer.stateHook = null
                try {
                    check(target.second.release()) { "unused target driver cleanup is pending" }
                } finally {
                    target.second.bindUnderlay(null)
                    fixture.host.contentRoot.removeView(target.first)
                }
            }
        }
    }

    @Test
    fun decodedStillRebind_canHandViewToVideoDuringPreparedCallback() = withFixture(::writeGradient) { fixture ->
        handoffStillToVideo(fixture, duringRebind = true)
    }

    @Test
    fun oldUnbind_cannotDisableVideoAfterInterruptedHandoff() = withFixture(::writeGradient) { fixture ->
        handoffStillToVideo(fixture, duringRebind = false)
    }

    private fun handoffStillToVideo(fixture: Fixture, duringRebind: Boolean) {
        val video = File(checkNotNull(fixture.file.parentFile), "handoff.mp4")
        writeVideo(video)
        attach(fixture, isVideo = false, isAnimated = false, expected = StandbyPlaybackState.READY)
        if (duringRebind) {
            actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.driver.bindUnderlay(null) }
        }
        val nextObserver = StateObserver()
        val next = onUi(fixture.scenario) {
            val driver = AndroidStandbyMediaDriver(fixture.host, checkNotNull(fixture.file.parentFile), fixture.store)
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { _, _, cause -> nextObserver.onError(cause) },
                onStateChanged = { state -> nextObserver.onStateChanged(state) },
            )
            driver to controller
        }
        val handoffFailure = AtomicReference<Throwable?>(null)
        var handedOff = false
        val cleanup = FixtureCleanup()
        try {
            onUi(fixture.scenario) {
                next.second.attach(video, isVideo = true, isAnimated = false)
                fixture.observer.stateHook = { state ->
                    val handoffState = if (duringRebind) StandbyPlaybackState.READY else StandbyPlaybackState.PREPARING
                    if (state == handoffState && !handedOff) {
                        handedOff = true
                        try {
                            fixture.controller.release()
                            fixture.driver.bindUnderlay(null)
                            next.first.bindUnderlay(fixture.view)
                        } catch (cause: Throwable) {
                            handoffFailure.set(cause)
                        }
                    }
                }
                fixture.driver.bindUnderlay(if (duringRebind) fixture.view else null)
            }
            assertTrue("callback did not hand off the view", handedOff)
            handoffFailure.get()?.let { throw it }
            nextObserver.awaitState(StandbyPlaybackState.PLAYING)
            val rejected = onUi(fixture.scenario) {
                fixture.controller.release()
                fixture.driver.bindUnderlay(null)
                try {
                    fixture.driver.bindUnderlay(fixture.view)
                    false
                } catch (cause: IllegalStateException) {
                    true
                }
            }
            assertTrue("old driver acquired the new owner's view", rejected)
            onUi(fixture.scenario) {
                assertTrue(fixture.view.isOwnedBy(next.first))
                val child = checkNotNull(findSurfaceView(fixture.view))
                val output = checkNotNull(fixture.view.currentVideoOutput)
                assertEquals(View.VISIBLE, child.visibility)
                assertTrue(child.isAttachedToWindow && fixture.view.ownsVideoOutput(output))
            }
            assertVideoFramesChange(fixture, next.first)
            nextObserver.throwIfFailed()
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { onUi(fixture.scenario) { fixture.observer.stateHook = null } }
                cleanup.attempt { nextObserver.clearStates() }
                cleanup.attempt {
                    val state = onUi(fixture.scenario) {
                        next.second.release()
                        next.second.state
                    }
                    if (state != StandbyPlaybackState.RELEASED) {
                        nextObserver.awaitState(StandbyPlaybackState.RELEASED, cleanup = true)
                    }
                }
                cleanup.attempt { onUi(fixture.scenario) { next.first.bindUnderlay(null) } }
                cleanup.attempt { handoffFailure.get()?.let { throw it } }
                cleanup.attempt { nextObserver.throwIfFailed() }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    @Test
    fun video_surfaceDestructionAllowsSynchronousUnderlayRebind() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val surfaceView = onUi(fixture.scenario) { checkNotNull(findSurfaceView(fixture.view)) }
        val original = onUi(fixture.scenario) { checkNotNull(fixture.view.currentVideoOutput) }
        val rebindFailure = AtomicReference<Throwable?>(null)
        var rebound = false
        onUi(fixture.scenario) {
            fixture.observer.stateHook = { state ->
                if (state == StandbyPlaybackState.PREPARING && !rebound) {
                    rebound = true
                    try {
                        fixture.driver.bindUnderlay(null)
                        fixture.driver.bindUnderlay(fixture.view)
                    } catch (cause: Exception) {
                        rebindFailure.set(cause)
                    }
                }
            }
        }
        val cleanup = FixtureCleanup()
        try {
            actAndAwait(fixture, StandbyPlaybackState.PREPARING) { fixture.view.removeView(surfaceView) }
            assertTrue("surface destruction did not trigger the rebind", rebound)
            rebindFailure.get()?.let { throw it }
            assertTrue("a child was replaced during destruction", onUi(fixture.scenario) { fixture.view.childCount == 0 })
            onUi(fixture.scenario) {
                assertFalse(original.alive)
                assertTrue(fixture.driver.isVideoNativeClosed)
                fixture.observer.stateHook = null
            }
            actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
                fixture.view.addView(surfaceView, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            onUi(fixture.scenario) {
                assertTrue(fixture.view.childCount == 1)
                assertTrue(findSurfaceView(fixture.view) === surfaceView)
                val replacement = checkNotNull(fixture.view.currentVideoOutput)
                assertTrue("destroyed holder lifetime was reused", replacement !== original)
                assertTrue(replacement.alive && replacement.surface.isValid)
            }
            assertVideoFramesChange(fixture, fixture.driver)
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { onUi(fixture.scenario) { fixture.observer.stateHook = null } }
                cleanup.attempt { rebindFailure.get()?.let { throw it } }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    @Test
    fun video_detachAndRebind_recreatesHolderOutputAndRendersAgain() = withFixture(::writeVideo) { fixture ->
        attach(fixture, isVideo = true, isAnimated = false, expected = StandbyPlaybackState.PLAYING)
        val original = assertVideoFramesChange(fixture, fixture.driver)

        actAndAwait(fixture, StandbyPlaybackState.PREPARING) {
            fixture.host.contentRoot.removeView(fixture.view)
        }
        assertFalse(onUi(fixture.scenario) { fixture.view.isAttachedToWindow })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && onUi(fixture.scenario) { original.alive }) {
            fixture.observer.throwIfFailed()
            SystemClock.sleep(40)
        }
        onUi(fixture.scenario) {
            assertFalse("detached holder lifetime was not retired", original.alive)
            assertTrue("holder loss was mistaken for player retirement", fixture.driver.isVideoNativeClosed)
        }
        fixture.observer.assertNoStateChange(300, TimeUnit.MILLISECONDS)
        assertEquals(StandbyPlaybackState.PREPARING, onUi(fixture.scenario) { fixture.controller.state })

        onUi(fixture.scenario) { fixture.driver.bindUnderlay(null) }
        actAndAwait(fixture, StandbyPlaybackState.PLAYING) {
            fixture.host.contentRoot.addView(fixture.view, FrameLayout.LayoutParams(160, 120))
            fixture.driver.bindUnderlay(fixture.view)
        }
        onUi(fixture.scenario) {
            val child = checkNotNull(findSurfaceView(fixture.view)) { "no SurfaceView after rebind" }
            val recreated = checkNotNull(fixture.view.currentVideoOutput)
            assertTrue("rebound video is not attached", child.isAttachedToWindow && fixture.view.ownsVideoOutput(recreated))
            assertTrue("holder lifetime was not recreated", recreated !== original)
            assertTrue(recreated.alive && recreated.surface.isValid)
        }
        assertVideoFramesChange(fixture, fixture.driver)
        assertEquals(StandbyPlaybackState.PLAYING, onUi(fixture.scenario) { fixture.controller.state })
    }

    /** Exercises one JPEG sample; bitmap allocation is pixel storage, not a process RAM limit. */
    @Test
    fun jpegContentWithoutExtension_commitsRendersAndReleases() = withFixture(::writeSolidJpeg) { fixture ->
        val sourceBytes = fixture.file.readBytes()
        val sourceSha = MessageDigest.getInstance("SHA-256").digest(sourceBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val staged = runBlocking {
            fixture.store.stageContent({ fixture.file.inputStream() }, StandbyFraming.CENTERED)
        } as StandbyStageResult.Staged
        assertEquals(StandbyMediaKind.IMAGE, staged.candidate.kind)
        val committed = runBlocking { fixture.store.confirm(staged.candidate) } as StandbyConfirmResult.Committed
        assertEquals(StandbyMediaKind.IMAGE, committed.active.kind)
        val committedFile = checkNotNull(committed.active.file)
        val committedSha = MessageDigest.getInstance("SHA-256").digest(committedFile.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(sourceSha, committedSha)

        actAndAwait(fixture, StandbyPlaybackState.READY) {
            fixture.controller.attach(committedFile, isVideo = false, isAnimated = false)
        }
        awaitPixel(fixture, 0.5f, 0.5f) { color ->
            Color.green(color) > Color.red(color) + 40 && Color.green(color) > Color.blue(color) + 40
        }
        val (dimensions, config, allocationBytes) = onUi(fixture.scenario) {
            val bitmap = checkNotNull((fixture.view.mediaDrawable as? BitmapDrawable)?.bitmap) {
                "JPEG output is not a BitmapDrawable"
            }
            Triple(bitmap.width to bitmap.height, bitmap.config?.name ?: "unknown", bitmap.allocationByteCount)
        }
        Log.i(
            "StandbyJpegProbe",
            "sourceBytes=${sourceBytes.size} sourceSha=$sourceSha committedSha=$committedSha " +
                "decodedWidth=${dimensions.first} decodedHeight=${dimensions.second} " +
                "decodedConfig=$config decodedAllocationBytes=$allocationBytes",
        )
        assertEquals(80, dimensions.first)
        assertEquals(80, dimensions.second)
        assertTrue("decoded JPEG has no pixel allocation", allocationBytes > 0)

        actAndAwait(fixture, StandbyPlaybackState.RELEASED) { fixture.controller.release() }
        assertTrue("JPEG drawable survived release", onUi(fixture.scenario) { fixture.view.mediaDrawable == null })
        assertTrue("active JPEG source was deleted by driver release", committedFile.isFile)
    }

    private class Fixture(
        val scenario: ActivityScenario<StandbyProbeActivity>,
        val host: StandbyProbeActivity,
        val view: StandbyMediaUnderlayView,
        val driver: AndroidStandbyMediaDriver,
        val controller: StandbyMediaPlaybackController,
        val file: File,
        val store: StandbyMediaStore,
        val observer: StateObserver,
    )

    /** Transfers public controller events and original errors to the test thread. */
    private class StateObserver {
        private val states = LinkedBlockingQueue<StandbyPlaybackState>()
        private val failure = AtomicReference<Throwable?>(null)
        var stateHook: ((StandbyPlaybackState) -> Unit)? = null
        @Volatile
        var expectedError: Throwable? = null
        @Volatile
        var expectedErrorCount: Int = 0
            private set
        @Volatile
        var lastExpectedError: Throwable? = null
            private set

        fun onStateChanged(state: StandbyPlaybackState) {
            states.offer(state)
            stateHook?.invoke(state)
        }

        fun onError(cause: Throwable) {
            val expected = expectedError
            if (expected != null && cause === expected && expectedErrorCount == 0) {
                expectedErrorCount++
                lastExpectedError = cause
            } else {
                val primary = failure.get()
                if (primary == null) {
                    failure.compareAndSet(null, cause)
                } else if (primary !== cause && primary.suppressed.none { it === cause }) {
                    primary.addSuppressed(cause)
                }
            }
            states.offer(StandbyPlaybackState.ERROR)
        }

        fun clearStates() = states.clear()

        fun awaitState(expected: StandbyPlaybackState, cleanup: Boolean = false) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                if (!cleanup) throwIfFailed()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw AssertionError("timed out waiting for $expected")
                val state = states.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("timed out waiting for $expected")
                if (!cleanup) throwIfFailed()
                if (state == expected) return
                if (state == StandbyPlaybackState.ERROR) {
                    throw AssertionError("controller entered ERROR while waiting for $expected", failure.get())
                }
            }
        }

        fun assertNoStateChange(duration: Long, unit: TimeUnit) {
            throwIfFailed()
            val changed = states.poll(duration, unit)
            throwIfFailed()
            assertEquals("unexpected playback state change", null, changed)
        }

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("standby playback failed", it) }
        }
    }

    /** Retains original failures while independently assessing this fixture's closure authority. */
    private class FixtureCleanup {
        var failure: Throwable? = null
            private set
        private var interrupted = false

        /** @param error Exact first failure, or a supplemental failure to preserve as suppressed evidence. */
        fun record(error: Throwable) {
            if (error is InterruptedException) interrupted = true
            val primary = failure
            if (primary == null) failure = error
            else if (primary !== error && primary.suppressed.none { it === error }) primary.addSuppressed(error)
        }

        /** @param action One applicable cleanup step. @return Whether that step completed without throwing. */
        fun attempt(action: () -> Unit): Boolean {
            if (Thread.interrupted()) {
                interrupted = true
                record(InterruptedException("fixture cleanup interrupted"))
            }
            return try {
                action()
                true
            } catch (error: Throwable) {
                record(error)
                false
            }
        }

        /** @return Whether all captured closure results and the original body permit owned-file deletion. */
        fun permitsDeletion(nativeReleased: Boolean, underlayRetired: Boolean, hostClosed: Boolean, metadataJoined: Boolean): Boolean =
            failure == null && nativeReleased && underlayRetired && hostClosed && metadataJoined

        /** Restores interruption only after the remaining cleanup steps have been attempted. */
        fun restoreInterrupt() {
            if (interrupted) Thread.currentThread().interrupt()
        }

        /** Rethrows the original instance, including every supplemental cleanup failure. */
        fun throwIfFailed() {
            failure?.let { throw it }
        }
    }

    /** Pure checks of the actual retention/deletion predicates, without fixture or native allocation. */
    private fun checkFixtureCleanup() {
        val clean = FixtureCleanup()
        check(clean.permitsDeletion(true, true, true, true))
        check(!clean.permitsDeletion(false, true, true, true) && !clean.permitsDeletion(true, true, true, false))
        val first = AssertionError("original fixture failure")
        val second = IllegalStateException("supplemental cleanup failure")
        val failed = FixtureCleanup()
        failed.record(first)
        failed.record(second)
        failed.record(first)
        failed.record(second)
        check(failed.failure === first && first.suppressed.single() === second)
        check(!failed.permitsDeletion(true, true, true, true))

        val observer = StateObserver()
        val expected = IllegalStateException("expected error")
        val unexpected = IllegalArgumentException("unexpected error")
        observer.expectedError = expected
        check(observer.expectedErrorCount == 0 && observer.lastExpectedError == null)
        observer.onError(expected)
        check(observer.expectedErrorCount == 1 && observer.lastExpectedError === expected)
        observer.throwIfFailed()
        observer.onError(unexpected)
        check(runCatching { observer.throwIfFailed() }.exceptionOrNull()?.cause === unexpected)
    }

    /** Attempts native retirement before metadata settlement; uncertain or failed fixtures are retained. */
    private fun withFixture(writeFile: (File) -> Unit, test: (Fixture) -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        checkFixtureCleanup()
        val cleanup = FixtureCleanup()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val storageDir = Files.createTempDirectory(cache.toPath(), "standby-driver-").toFile()
        var metadataJob: kotlinx.coroutines.Job? = null
        var scenario: ActivityScenario<StandbyProbeActivity>? = null
        var observer: StateObserver? = null
        var host: StandbyProbeActivity? = null
        var view: StandbyMediaUnderlayView? = null
        var driver: AndroidStandbyMediaDriver? = null
        var controller: StandbyMediaPlaybackController? = null
        var scenarioLaunchStarted = false
        var nativeConstructionStarted = false
        try {
            val job = SupervisorJob()
            metadataJob = job
            val preferences = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
            ) { File(storageDir, "metadata.preferences_pb") }
            val store = StandbyMediaStore(preferences, AndroidStandbyMediaValidator(), storageDir)
            val file = File(storageDir, "fixture.bin")
            writeFile(file)
            scenarioLaunchStarted = true
            val currentScenario = ActivityScenario.launch(StandbyProbeActivity::class.java)
            scenario = currentScenario
            val events = StateObserver()
            observer = events
            val laidOut = CountDownLatch(1)
            val fixture = onUi(currentScenario) {
                val activity = it
                host = activity
                activity.contentRoot.setBackgroundColor(Color.BLACK)
                val underlay = StandbyMediaUnderlayView(activity)
                view = underlay
                underlay.addOnLayoutChangeListener { child, left, top, right, bottom, _, _, _, _ ->
                    if (child.isAttachedToWindow && right > left && bottom > top) laidOut.countDown()
                }
                activity.contentRoot.addView(underlay, FrameLayout.LayoutParams(160, 120))
                nativeConstructionStarted = true
                val platformDriver = AndroidStandbyMediaDriver(activity, storageDir, store)
                driver = platformDriver
                val playback = StandbyMediaPlaybackController(
                    platformDriver,
                    onError = { _, _, cause -> events.onError(cause) },
                    onStateChanged = { state -> events.onStateChanged(state) },
                )
                controller = playback
                platformDriver.bindUnderlay(underlay)
                Fixture(currentScenario, activity, underlay, platformDriver, playback, file, store, events)
            }
            assertTrue("underlay was not attached and laid out", laidOut.await(5, TimeUnit.SECONDS))
            test(fixture)
            events.throwIfFailed()
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                val nativeReleased = cleanup.attempt {
                    val currentScenario = scenario
                    if (currentScenario == null) {
                        check(!scenarioLaunchStarted && !nativeConstructionStarted) { "host/native construction did not settle" }
                    } else {
                        val events = checkNotNull(observer)
                        events.clearStates()
                        val state = onUi(currentScenario) {
                            val current = controller
                            if (current != null) {
                                current.release()
                                current.state
                            } else {
                                val platform = driver
                                check(platform != null || !nativeConstructionStarted) { "native construction incomplete" }
                                if (platform != null) check(platform.release()) { "partial native release remains pending" }
                                null
                            }
                        }
                        if (state != null) {
                            if (state != StandbyPlaybackState.RELEASED) {
                                events.awaitState(StandbyPlaybackState.RELEASED, cleanup = true)
                            }
                            check(onUi(currentScenario) { controller?.state == StandbyPlaybackState.RELEASED }) {
                                "public controller release did not settle"
                            }
                        }
                    }
                }
                val unbound = cleanup.attempt {
                    val currentScenario = scenario
                    if (currentScenario != null) onUi(currentScenario) { driver?.bindUnderlay(null) }
                    else check(driver == null && !nativeConstructionStarted)
                }
                val removed = cleanup.attempt {
                    val currentScenario = scenario
                    if (currentScenario != null) onUi(currentScenario) {
                        view?.let { underlay ->
                            checkNotNull(host).contentRoot.removeView(underlay)
                            check(underlay.parent == null) { "underlay retirement incomplete" }
                        }
                    } else check(view == null)
                }
                val hostClosed = cleanup.attempt {
                    val currentScenario = scenario
                    if (currentScenario != null) currentScenario.close()
                    else check(!scenarioLaunchStarted) { "activity launch did not supply a closable host" }
                }
                val metadataJoined = cleanup.attempt {
                    metadataJob?.let { job ->
                        cleanup.attempt { job.cancel() }
                        runBlocking { kotlinx.coroutines.withTimeout(10_000L) { job.join() } }
                    }
                }
                cleanup.attempt {
                    if (cleanup.permitsDeletion(nativeReleased, unbound && removed, hostClosed, metadataJoined)) {
                        check(storageDir.deleteRecursively()) { "failed to remove test fixture directory" }
                    }
                }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    private fun attach(fixture: Fixture, isVideo: Boolean, isAnimated: Boolean, expected: StandbyPlaybackState) {
        actAndAwait(fixture, expected) { fixture.controller.attach(fixture.file, isVideo, isAnimated) }
    }

    private fun actAndAwait(fixture: Fixture, expected: StandbyPlaybackState, action: () -> Unit) {
        fixture.observer.clearStates()
        onUi(fixture.scenario) { action() }
        fixture.observer.awaitState(expected)
    }

    /** Runs only the supplied UI action on the activity thread. */
    private fun <T> onUi(scenario: ActivityScenario<StandbyProbeActivity>, action: (StandbyProbeActivity) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        val result = AtomicReference<Result<T>?>(null)
        scenario.onActivity { activity -> result.set(runCatching { action(activity) }) }
        return checkNotNull(result.get()).getOrThrow()
    }

    /** Samples the activity window; a request hook uses the bounded activity action. */
    private fun capturePixel(fixture: Fixture, x: Float, y: Float, onRequest: (() -> Unit)? = null): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val result = AtomicInteger(-1)
        val copied = CountDownLatch(1)
        val requestState = AtomicReference(PixelRequestState.QUEUED)
        var finished = false
        try {
            val request: (StandbyProbeActivity) -> Unit = {
                if (requestState.compareAndSet(PixelRequestState.QUEUED, PixelRequestState.CLAIMED)) {
                    var requested = false
                    try {
                        val location = IntArray(2)
                        fixture.view.getLocationInWindow(location)
                        val px = location[0] + (fixture.view.width * x).toInt().coerceIn(0, fixture.view.width - 1)
                        val py = location[1] + (fixture.view.height * y).toInt().coerceIn(0, fixture.view.height - 1)
                        onRequest?.invoke()
                        PixelCopy.request(
                            fixture.host.window,
                            Rect(px, py, px + 1, py + 1),
                            bitmap,
                            { value -> result.set(value); copied.countDown() },
                            Handler(Looper.getMainLooper()),
                        )
                        requested = true
                    } finally {
                        if (!requested) bitmap.recycle()
                    }
                }
            }
            if (onRequest == null) onUi(fixture.scenario, request) else awaitActivityAction(fixture.scenario, action = request)
            finished = copied.await(5, TimeUnit.SECONDS)
            assertTrue("window PixelCopy timed out", finished)
            assertEquals("window PixelCopy failed", PixelCopy.SUCCESS, result.get())
            return bitmap.getPixel(0, 0)
        } finally {
            if (finished || requestState.compareAndSet(PixelRequestState.QUEUED, PixelRequestState.CANCELLED)) bitmap.recycle()
        }
    }

    private fun awaitPixel(fixture: Fixture, x: Float, y: Float, matches: (Int) -> Boolean): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var pixel: Int
        do {
            fixture.observer.throwIfFailed()
            pixel = capturePixel(fixture, x, y)
            if (matches(pixel)) return pixel
            SystemClock.sleep(40)
        } while (System.nanoTime() < deadline)
        throw AssertionError("unexpected window pixel: ${Integer.toHexString(pixel)}")
    }

    /** Captures the identities and geometry of one owned production output. */
    private data class VideoWindowOutput(
        val window: Window,
        val decor: View,
        val token: android.os.IBinder,
        val root: FrameLayout,
        val child: SurfaceView,
        val output: StandbyVideoOutput,
        val binding: Long,
        val windowBounds: Rect,
        val rootBounds: Rect,
        val viewportBounds: Rect,
        val contentWidth: Int,
        val contentHeight: Int,
        val placement: StandbyFrameGeometry.SurfacePlacement,
        val outside: Pair<Int, Int>?,
    )

    /**
     * Observes real video colors through only the fixture's owned window compositor.
     * @param fixture current test-owned host and underlay.
     * @param expectedDriver actual current owner, including a handoff driver.
     * @return the same live framework output witnessed throughout both colors.
     */
    private fun assertVideoFramesChange(fixture: Fixture, expectedDriver: AndroidStandbyMediaDriver): StandbyVideoOutput {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        fun boundsInWindow(view: View): Rect {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        val observed = onUi(fixture.scenario) { host ->
            check(host === fixture.host && !host.isDestroyed && !host.isFinishing)
            check(monitor.getLifecycleStageOf(host) == Stage.RESUMED)
            val window = host.window
            val decor = window.decorView
            val token = checkNotNull(decor.windowToken)
            val root = host.contentRoot
            val child = checkNotNull(findSurfaceView(fixture.view)) { "no SurfaceView in production underlay" }
            val output = checkNotNull(fixture.view.currentVideoOutput) { "no live production output" }
            val size = checkNotNull(fixture.view.decodedContentSize())
            val windowBounds = boundsInWindow(decor)
            check(windowBounds.left == 0 && windowBounds.top == 0 && !windowBounds.isEmpty)
            val rootBounds = boundsInWindow(root)
            val viewport = boundsInWindow(fixture.view)
            check(!viewport.isEmpty && windowBounds.contains(rootBounds) && rootBounds.contains(viewport))
            // Sample adjacent overflow, not distant background beyond the oversized child.
            val outside = when {
                rootBounds.bottom > viewport.bottom + 1 -> viewport.centerX() to viewport.bottom + 1
                rootBounds.right > viewport.right + 1 -> viewport.right + 1 to viewport.centerY()
                else -> null
            }
            VideoWindowOutput(
                window, decor, token, root, child, output, expectedDriver.underlayBindingGeneration,
                windowBounds, rootBounds, viewport, size.width, size.height,
                checkNotNull(StandbyFrameGeometry.computeSurfacePlacement(
                    size.width.toFloat(), size.height.toFloat(), fixture.view.width.toFloat(), fixture.view.height.toFloat(),
                    fixture.view.currentFraming(),
                )),
                outside,
            )
        }
        fun checkOutput(host: StandbyProbeActivity) {
            fixture.observer.throwIfFailed()
            check(host === fixture.host && !host.isDestroyed && !host.isFinishing &&
                monitor.getLifecycleStageOf(host) == Stage.RESUMED)
            check(host.window === observed.window && observed.window.decorView === observed.decor &&
                host.contentRoot === observed.root)
            val token = observed.token
            check(observed.decor.windowToken === token)
            check(observed.decor.isAttachedToWindow && observed.root.isAttachedToWindow &&
                fixture.view.isAttachedToWindow && fixture.view.isShown && observed.child.isAttachedToWindow &&
                observed.child.isHardwareAccelerated)
            check(observed.root.rootView === observed.decor && fixture.view.rootView === observed.decor &&
                observed.child.rootView === observed.decor && observed.root.windowToken === token &&
                fixture.view.windowToken === token && observed.child.windowToken === token)
            check(observed.root.childCount == 1 && observed.root.getChildAt(0) === fixture.view &&
                fixture.view.childCount == 1 && fixture.view.getChildAt(0) === observed.child)
            check(expectedDriver.isCurrentUnderlayBinding(fixture.view, observed.binding) &&
                fixture.view.currentVideoOutput === observed.output && fixture.view.ownsVideoOutput(observed.output) &&
                fixture.view.isVideoOutputGeometryUsable(observed.output))
            check(boundsInWindow(observed.decor) == observed.windowBounds && boundsInWindow(observed.root) == observed.rootBounds &&
                boundsInWindow(fixture.view) == observed.viewportBounds)
            check(observed.child.width == observed.contentWidth && observed.child.height == observed.contentHeight &&
                observed.child.left == 0 && observed.child.top == 0 && observed.child.alpha == 1f &&
                observed.child.visibility == View.VISIBLE && observed.child.pivotX == 0f && observed.child.pivotY == 0f)
            val placement = checkNotNull(StandbyFrameGeometry.computeSurfacePlacement(
                observed.contentWidth.toFloat(), observed.contentHeight.toFloat(), fixture.view.width.toFloat(),
                fixture.view.height.toFloat(), fixture.view.currentFraming(),
            ))
            check(placement == observed.placement && abs(observed.child.scaleX - placement.scale) < 0.0001f &&
                abs(observed.child.scaleY - placement.scale) < 0.0001f &&
                abs(observed.child.translationX - placement.translateX) < 0.01f &&
                abs(observed.child.translationY - placement.translateY) < 0.01f)
            check(fixture.view.clipChildren && fixture.view.clipToPadding &&
                fixture.view.clipBounds == Rect(0, 0, fixture.view.width, fixture.view.height))
        }
        var red = false
        var green = false
        var frames = 0
        while (System.nanoTime() < deadline && !(red && green)) {
            onUi(fixture.scenario) { host -> checkOutput(host) }
            check(System.nanoTime() < deadline) { "owned video window deadline expired before capture" }
            // Binder capture has no per-call timeout; the parent owns the host watchdog.
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot(observed.window)) {
                "owned video window capture returned null"
            }
            val pixel = try {
                check(screenshot.width == observed.windowBounds.width() && screenshot.height == observed.windowBounds.height())
                val readable = if (screenshot.config == Bitmap.Config.HARDWARE) {
                    checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false))
                } else screenshot
                try {
                    val x = observed.viewportBounds.centerX()
                    val y = observed.viewportBounds.centerY()
                    check(x in 0 until readable.width && y in 0 until readable.height)
                    val color = readable.getPixel(x, y)
                    val outsideColor = observed.outside?.let { (outsideX, outsideY) ->
                        check(observed.rootBounds.contains(outsideX, outsideY) &&
                            !observed.viewportBounds.contains(outsideX, outsideY) &&
                            outsideX in 0 until readable.width && outsideY in 0 until readable.height)
                        readable.getPixel(outsideX, outsideY).also {
                            check(it == Color.BLACK) { "video escaped the underlay clip: $it" }
                        }
                    }
                    frames++
                    Log.i("StandbyVideoOutput", "COMPOSITOR_FRAME t=${SystemClock.elapsedRealtimeNanos()} frame=$frames " +
                        "host=${System.identityHashCode(fixture.host)} window=${System.identityHashCode(observed.window)} " +
                        "output=${System.identityHashCode(observed.output)} surface=${System.identityHashCode(observed.output.surface)} " +
                        "token=${System.identityHashCode(observed.token)} binding=${observed.binding} " +
                        "viewport=${observed.viewportBounds} placement=${observed.placement} " +
                        "sample=$x,$y rgb=${Color.red(color)},${Color.green(color)},${Color.blue(color)} " +
                        "outside=${observed.outside} outsideColor=$outsideColor config=${readable.config}")
                    color
                } finally {
                    if (readable !== screenshot) readable.recycle()
                }
            } finally {
                screenshot.recycle()
            }
            onUi(fixture.scenario) { host -> checkOutput(host) }
            if (System.nanoTime() >= deadline) break
            red = red || (Color.red(pixel) > 100 && Color.red(pixel) > Color.green(pixel) + 30)
            green = green || (Color.green(pixel) > 100 && Color.green(pixel) > Color.red(pixel) + 30)
            if (!(red && green)) SystemClock.sleep(50)
        }
        assertTrue("video did not render both encoded colors through its owned window", red && green)
        return observed.output
    }

    /** Checks for the first frame within one second of the requested action. */
    private fun assertGifFirstFrame(fixture: Fixture, label: String, startedAt: Long) {
        val deadline = startedAt + TimeUnit.SECONDS.toNanos(1)
        var pixel: Int
        do {
            var requestedAt = 0L
            pixel = capturePixel(fixture, 0.5f, 0.5f) { requestedAt = System.nanoTime() }
            val observedAt = System.nanoTime()
            Log.i("StandbyGifFirstFrame", "phase=$label action=$startedAt request=$requestedAt observed=$observedAt pixel=${Integer.toHexString(pixel)}")
            if (observedAt <= deadline && Color.red(pixel) > Color.blue(pixel) + 40) return
            if (observedAt < deadline) SystemClock.sleep(40)
        } while (System.nanoTime() < deadline)
        throw AssertionError("GIF did not restart on its first frame: phase=$label pixel=${Integer.toHexString(pixel)}")
    }

    /** Observes both encoded GIF colors through the activity window. */
    private fun assertGifFramesChange(fixture: Fixture) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var red = false
        var blue = false
        while (System.nanoTime() < deadline && !(red && blue)) {
            fixture.observer.throwIfFailed()
            val pixel = capturePixel(fixture, 0.5f, 0.5f)
            red = red || Color.red(pixel) > Color.blue(pixel) + 40
            blue = blue || Color.blue(pixel) > Color.red(pixel) + 40
            if (!(red && blue)) SystemClock.sleep(50)
        }
        assertTrue("GIF did not render both frames", red && blue)
    }

    private fun near(actual: Int, expected: Int): Boolean = abs(actual - expected) <= 15

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun writeGif(file: File) {
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
        check(StandbyMediaProbeFixtures.isTrustedSize(bytes))
        file.writeBytes(bytes)
    }

    /** Lengthens both known frame delays to make a drawer-induced restart visible. */
    private fun writeSlowGif(file: File) {
        val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_GIF_BASE64, Base64.DEFAULT)
        check(StandbyMediaProbeFixtures.isTrustedSize(bytes))
        var delaysChanged = 0
        for (index in 0 until bytes.size - 5) {
            if (bytes[index] == 0x21.toByte() && bytes[index + 1] == 0xf9.toByte() &&
                bytes[index + 2] == 4.toByte() && bytes[index + 4] == 10.toByte() &&
                bytes[index + 5] == 0.toByte()
            ) {
                bytes[index + 4] = 200.toByte()
                delaysChanged++
            }
        }
        check(delaysChanged == 2) { "unexpected GIF frame delays" }
        file.writeBytes(bytes)
    }

    /** Writes a bounded JPEG fixture without relying on the filename suffix. */
    private fun writeSolidJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Writes a known square gradient so actual aspect fill and framing are observable. */
    private fun writeGradient(file: File) {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 80) {
                for (x in 0 until 80) {
                    bitmap.setPixel(x, y, Color.rgb(x * 255 / 79, y * 255 / 79, 30))
                }
            }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Encodes a cache-owned AVC/MP4 without requiring a rendering surface. */
    private fun writeVideo(file: File) {
        checkFixtureCleanup()
        val cleanup = FixtureCleanup()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 64, 64).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoder = codecList.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).let { capabilities ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                        capabilities.isFormatSupported(format)
                }
        }
        val codec = MediaCodec.createByCodecName(
            checkNotNull(encoder) { "No flexible YUV420 AVC encoder supports the 64x64 test fixture" }.name,
        )
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            try {
                val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerStarted = false
                try {
                    val info = MediaCodec.BufferInfo()
                    var track = -1
                    var ended = false
                    val encodeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)

                    fun drain() {
                        while (true) {
                            check(System.nanoTime() < encodeDeadline) { "encoder output timed out" }
                            val index = codec.dequeueOutputBuffer(info, 0)
                            when {
                                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    check(!muxerStarted) { "encoder changed format twice" }
                                    track = muxer.addTrack(codec.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                                index >= 0 -> {
                                    try {
                                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            check(muxerStarted) { "encoder output arrived before its format" }
                                            val buffer = checkNotNull(codec.getOutputBuffer(index))
                                            buffer.position(info.offset)
                                            buffer.limit(info.offset + info.size)
                                            muxer.writeSampleData(track, buffer, info)
                                        }
                                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) ended = true
                                    } catch (error: Throwable) {
                                        cleanup.record(error)
                                        throw error
                                    } finally {
                                        cleanup.attempt { codec.releaseOutputBuffer(index, false) }
                                    }
                                    cleanup.throwIfFailed()
                                    if (ended) return
                                }
                                else -> error("unexpected encoder output result: $index")
                            }
                        }
                    }

                    try {
                        var frame = 0
                        while (frame < 20) {
                            check(System.nanoTime() < encodeDeadline) { "encoder input timed out" }
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val size = checkNotNull(codec.getInputBuffer(index)).capacity()
                                checkNotNull(codec.getInputImage(index)).use { image ->
                                    check(image.format == ImageFormat.YUV_420_888 && image.width == 64 && image.height == 64)
                                    val planes = image.planes
                                    check(planes.size == 3) { "unexpected encoder input planes" }
                                    // Limited-range BT.601 Y, U, V for red and green.
                                    val colors = if (frame / 4 % 2 == 0) {
                                        intArrayOf(82, 90, 240)
                                    } else {
                                        intArrayOf(145, 54, 34)
                                    }
                                    planes.forEachIndexed { planeIndex, plane ->
                                        val dimension = if (planeIndex == 0) 64 else 32
                                        val buffer = plane.buffer
                                        val start = buffer.position()
                                        for (y in 0 until dimension) {
                                            for (x in 0 until dimension) {
                                                buffer.put(
                                                    start + y * plane.rowStride + x * plane.pixelStride,
                                                    colors[planeIndex].toByte(),
                                                )
                                            }
                                        }
                                    }
                                }
                                val flags = if (frame == 19) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                codec.queueInputBuffer(index, 0, size, frame * 50_000L, flags)
                                frame++
                            } else {
                                check(index == MediaCodec.INFO_TRY_AGAIN_LATER) { "unexpected encoder input result: $index" }
                            }
                            drain()
                        }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (!ended && System.nanoTime() < deadline) {
                            drain()
                            if (!ended) SystemClock.sleep(10)
                        }
                        check(ended && muxerStarted) { "encoder did not finish the MP4 fixture" }
                    } catch (error: Throwable) {
                        cleanup.record(error)
                    } finally {
                        if (muxerStarted) cleanup.attempt { muxer.stop() }
                    }
                } catch (error: Throwable) {
                    cleanup.record(error)
                } finally {
                    cleanup.attempt { muxer.release() }
                }
            } catch (error: Throwable) {
                cleanup.record(error)
            } finally {
                cleanup.attempt { codec.stop() }
            }
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            cleanup.attempt { codec.release() }
            cleanup.restoreInterrupt()
        }
        cleanup.throwIfFailed()

        val extractor = MediaExtractor()
        var decoderSupported = false
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            checkNotNull(videoTrack) { "generated MP4 has no AVC track" }
            decoderSupported = codecList.findDecoderForFormat(videoTrack) != null
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            cleanup.attempt { extractor.release() }
            cleanup.restoreInterrupt()
        }
        cleanup.throwIfFailed()
        // Apply the existing assumption after cleanup, so cleanup failures cannot become skips.
        assumeTrue(
            "No decoder supports the generated AVC test fixture",
            decoderSupported,
        )
    }
}
