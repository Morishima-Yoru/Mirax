package me.trinitrix.mirax.ui.standby.media

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaSourceLease
import me.trinitrix.mirax.data.standby.StandbyMediaStore

/** File-boundary and framing checks shared with the playback tests. */
object AndroidStandbyMediaDriverPolicy {
    /**
     * Checks that a candidate is a direct canonical child of the store.
     *
     * @param file candidate file.
     * @param storageDir store directory.
     * @return whether the file is a direct child.
     */
    fun isStoreOwned(file: File, storageDir: File): Boolean = try {
        file.canonicalFile.parentFile == storageDir.canonicalFile
    } catch (e: IOException) {
        Log.w(TAG, "canonical path unavailable", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "canonical path rejected", e)
        false
    }

    /**
     * Validates a nonempty store file without modifying it.
     *
     * @param file candidate file.
     * @param storageDir store directory.
     * @return the validated file.
     */
    @Throws(IOException::class)
    fun requireUsableStoreFile(file: File, storageDir: File): File {
        require(isStoreOwned(file, storageDir)) { "not a direct store child" }
        if (!file.isFile || file.length() == 0L) throw IOException("unusable standby file")
        return file
    }

    /**
     * Computes the shared clamped source window.
     *
     * @param contentW decoded width.
     * @param contentH decoded height.
     * @param viewW view width.
     * @param viewH view height.
     * @param framing shared framing.
     * @return source window, or null for unusable inputs.
     */
    fun computeSourceWindow(
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
        framing: StandbyFraming,
    ): StandbyFrameGeometry.SrcWindow? =
        StandbyFrameGeometry.computeSrc(contentW, contentH, viewW, viewH, framing)

    private const val TAG = "StandbyMediaPolicy"
}

/**
 * Tracks one player's events without assuming their order.
 * Geometry only establishes output eligibility, never a rendered frame.
 * @param output framework output lifetime belonging to the player.
 */
internal class StandbyVideoSignals<T>(private val output: T) {
    private var prepared = false
    private var started = false
    private var sized = false
    private var renderingInfo = false
    private var outputUsable = false
    private var reported = false

    /**
     * Checks whether a rendered video has lost its dimensions.
     * @param width video width.
     * @param height video height.
     * @return whether the player must be interrupted.
     */
    fun hasLostSize(width: Int, height: Int): Boolean = reported && (width <= 0 || height <= 0)

    /** @return true for the first prepared event, regardless of video dimensions. */
    fun onPrepared(): Boolean {
        if (prepared) return false
        prepared = true
        return true
    }

    /** Records the start of the prepared player. */
    fun onStarted() {
        check(prepared && !started)
        started = true
    }

    /**
     * Records the latest dimensions.
     * @param width video width.
     * @param height video height.
     * @return whether rendering is newly established.
     */
    fun onSizeChanged(width: Int, height: Int): Boolean {
        sized = width > 0 && height > 0
        return reportIfReady()
    }

    /** @return whether the rendering-start event establishes rendering. */
    fun onRenderingInfo(): Boolean {
        if (!started) return false
        renderingInfo = true
        return reportIfReady()
    }

    /**
     * Records geometry eligibility only for this player's framework lifetime.
     * @param changed output associated with the geometry.
     * @param usable whether that live output has a finite applied placement.
     * @return whether real rendering information and all prerequisites now agree.
     */
    fun onOutputGeometryChanged(changed: T, usable: Boolean): Boolean {
        if (changed !== output) return false
        outputUsable = usable
        return reportIfReady()
    }

    private fun reportIfReady(): Boolean {
        if (reported || !prepared || !started || !sized || !renderingInfo || !outputUsable) return false
        reported = true
        return true
    }
}

/**
 * Checks whether a reentrant callback kept the same owner and generation.
 * @param owner original owner.
 * @param current current owner.
 * @param generation original generation.
 * @param currentGeneration current generation.
 * @return whether both identities are unchanged.
 */
internal fun <T> isUnchangedStandbyOwner(owner: T?, current: T?, generation: Long, currentGeneration: Long): Boolean =
    owner === current && generation == currentGeneration

/**
 * Checks both sides of an underlay binding after a callback.
 * @param driver expected driver.
 * @param owner driver currently registered by the view.
 * @param driverStillBound whether the driver still points to that view.
 * @return whether the binding is still owned by the driver.
 */
internal fun <T> isStandbyViewOwner(driver: T, owner: T?, driverStillBound: Boolean): Boolean =
    driver === owner && driverStillBound

/**
 * Stops and detaches an image before its owner reference can be discarded.
 * @param owner currently owned image, if any.
 * @param stop stops the owned image.
 * @param detach removes the image from its view.
 * @return null after successful cleanup.
 */
internal inline fun <T> releaseStandbyImageOwner(
    owner: T?,
    stop: (T) -> Unit,
    detach: (T) -> Unit,
): T? {
    if (owner != null) {
        stop(owner)
        detach(owner)
    }
    return null
}

/**
 * Forgets a player only after its native release returns normally.
 * @param owner currently retained player.
 * @param release releases that player, never its borrowed output.
 * @return null after successful release; a throw leaves the caller's owner intact.
 */
internal inline fun <T> releaseStandbyVideoOwner(owner: T?, release: (T) -> Unit): T? {
    if (owner != null) release(owner)
    return null
}

/**
 * Checks whether an output event still concerns selected or unclosed native use.
 * @param output event's original lifetime.
 * @param selected currently available lifetime.
 * @param bound lifetime still bound to a retained player.
 * @return whether the event may affect this driver's output.
 */
internal fun <T> isStandbyVideoOutputReferenced(output: T, selected: T?, bound: T?): Boolean =
    output === selected || output === bound

/**
 * Requires independent positive retirement of every owner before release completion.
 * @param outputClosed whether all player, output and drawable owners are detached.
 * @param loading whether IO settlement is still pending.
 * @param inputClosed whether the original input has closed.
 * @param sourceReleased whether the original source lease has released.
 * @param closeFailed whether an explicit retry is still required.
 * @return whether the old owner may be completed or replaced.
 */
internal fun isStandbyReleaseSettled(
    outputClosed: Boolean,
    loading: Boolean,
    inputClosed: Boolean,
    sourceReleased: Boolean,
    closeFailed: Boolean,
): Boolean = outputClosed && !loading && inputClosed && sourceReleased && !closeFailed

/**
 * Delivers a platform callback without treating observer cancellation as a media failure.
 * @param listener owner listener.
 * @param file original file.
 * @param token original open identity.
 * @param callback event to deliver.
 * @return the original cancellation, if the observer cancelled.
 */
internal inline fun dispatchStandbyAsyncEvent(
    listener: StandbyPlayerListener,
    file: File,
    token: Long,
    callback: () -> Unit,
): CancellationException? = try {
    callback()
    null
} catch (cause: CancellationException) {
    try {
        listener.onObserverCancelled(file, token, cause)
    } catch (cleanup: Throwable) {
        if (cleanup !== cause && cause.suppressed.none { it === cleanup }) {
            if (cleanup.suppressed.any { it === cause }) {
                Log.w("StandbyMediaDriver", "Observer cancellation cleanup failed", cleanup)
            } else {
                cause.addSuppressed(cleanup)
            }
        }
    }
    cause
}

/**
 * Delivers a listener event according to its explicit framework or synchronous origin.
 * @param listener owner listener.
 * @param file original file.
 * @param token original open identity.
 * @param frameworkCallback whether the event came from a framework callback.
 * @param callback event to deliver.
 * @return the observer cancellation for a framework callback, otherwise null.
 */
internal inline fun dispatchStandbyEntryEvent(
    listener: StandbyPlayerListener,
    file: File,
    token: Long,
    frameworkCallback: Boolean,
    callback: () -> Unit,
): CancellationException? {
    if (frameworkCallback) return dispatchStandbyAsyncEvent(listener, file, token, callback)
    callback()
    return null
}

/**
 * Main-thread platform driver for the controller's single playback owner.
 *
 * File access and image decoding run on IO. A native decode in progress cannot
 * be interrupted, so release waits for its result before allowing another open.
 * GIF owners retain a source pin across visibility interruptions. Release waits
 * for pending decoding, image detachment and asynchronous pin release; native
 * drawable reclamation is not timed.
 * The framework owns the holder Surface; this driver borrows it until its player closes.
 *
 * @param context application context used to validate the private data root.
 * @param storageDir actual app-private store directory.
 * @param store the source authority for the same dedicated directory.
 */
class AndroidStandbyMediaDriver(
    context: Context,
    private val storageDir: File,
    private val store: StandbyMediaStore,
) : StandbyPlayerDriver {
    /** Retains one original callback identity and its pinned GIF source until cleanup completes. */
    private class Request(
        val file: File,
        val isVideo: Boolean,
        val isAnimated: Boolean,
        val token: Long,
        val listener: StandbyPlayerListener,
    ) {
        var sourceLease: StandbyMediaSourceLease? = null
        var imagePrepared = false
        var replayNeeded = false
        var restartPending = false
    }

    private sealed interface Opened {
        class Image(val drawable: Drawable) : Opened
        class Video(val input: FileInputStream) : Opened
    }

    private val privateDataDir = context.applicationContext.dataDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeUnderlay: StandbyMediaUnderlayView? = null
    private var retiredUnderlay: WeakReference<StandbyMediaUnderlayView>? = null
    private var underlay: StandbyMediaUnderlayView?
        get() = activeUnderlay ?: retiredUnderlay?.get()
        set(view) {
            activeUnderlay = view
            retiredUnderlay = null
        }
    internal var underlayBindingGeneration = 0L
        private set
    private var detachingUnderlay: StandbyMediaUnderlayView? = null
    private var isRollingBackUnderlay = false
    private var visible = false
    private var videoOutput: StandbyVideoOutput? = null
    private var lastVideoOutput: WeakReference<StandbyVideoOutput>? = null
    private var request: Request? = null
    private var closing: Request? = null
    private var pendingReleaseGeneration = 0L
    private var closeFailure: Throwable? = null
    private val closeFailed: Boolean get() = closeFailure != null
    private var generation = 0L
    private var loading = false
    private var input: FileInputStream? = null
    private var drawable: Drawable? = null
    private var animationCallback: Animatable2.AnimationCallback? = null
    private var animationActive = false
    private var player: MediaPlayer? = null
    private var videoOutputBound: StandbyVideoOutput? = null
    private var videoReleaseInProgress = false
    private var playerPrepared = false
    private var playerStarted = false
    private var videoEpoch = 0L
    private var videoBinding = 0L
    private var videoSignals: StandbyVideoSignals<StandbyVideoOutput>? = null
    /** Positive native retirement is independent of framework Surface validity. */
    internal val isVideoNativeClosed: Boolean get() = !videoReleaseInProgress && player == null && videoOutputBound == null
    internal var beforeVideoReleaseForTest: (() -> Unit)? = null

    /**
     * @param view underlay to check.
     * @return whether this driver still points to the underlay.
     */
    internal fun isBoundUnderlay(view: StandbyMediaUnderlayView): Boolean = underlay === view

    /**
     * Keeps old holder callbacks available without strongly retaining a retired activity.
     *
     * @param view the lease's retired underlay.
     */
    internal fun retireUnderlay(view: StandbyMediaUnderlayView) {
        requireMainThread()
        if (activeUnderlay === view) {
            retiredUnderlay = WeakReference(view)
            activeUnderlay = null
        }
    }

    /**
     * @param view underlay to check.
     * @param binding generation captured before a callback.
     * @return whether both sides still own the same binding generation.
     */
    internal fun isCurrentUnderlayBinding(view: StandbyMediaUnderlayView, binding: Long): Boolean =
        underlayBindingGeneration == binding && view.isOwnedBy(this)

    /**
     * Binds the sole output view without taking ownership of its holder Surface.
     * Rebinding from a rollback callback is ignored; retry after rollback returns.
     *
     * @param view underlay, or null to detach.
     */
    fun bindUnderlay(view: StandbyMediaUnderlayView?) {
        requireMainThread()
        if (view != null && isRollingBackUnderlay) {
            Log.w(TAG, "Ignoring underlay rebind during rollback")
            return
        }
        if (underlay === view) {
            if (view != null && detachingUnderlay === view && view.isOwnedBy(this)) {
                check(!closeFailed && !videoReleaseInProgress) { "standby output release must be retried before rebinding" }
                val binding = ++underlayBindingGeneration
                detachingUnderlay = null
                bindOwnedView(view, binding)
            }
            return
        }
        check(!closeFailed && !videoReleaseInProgress) { "standby output release must be retried before rebinding" }
        view?.requireMediaDriverAvailable(this)
        val binding = ++underlayBindingGeneration
        val previous = underlay
        if (previous != null) {
            if (!previous.isOwnedBy(this)) return
            detachingUnderlay = previous
            var detached = false
            var interruptionFailure: Throwable? = null
            try {
                try {
                    onUnderlayVisibilityChanged(previous, false)
                } catch (cause: Throwable) {
                    interruptionFailure = cause
                }
                try {
                    if (isCurrentUnderlayBinding(previous, binding)) {
                        check(!closeFailed) { "standby output release must be retried before rebinding" }
                        drawable?.let { previous.clearMediaDrawable(it) }
                        if (isCurrentUnderlayBinding(previous, binding)) {
                            previous.setVideoEnabled(this, false)
                            if (isCurrentUnderlayBinding(previous, binding)) {
                                previous.unbindMediaDriver(this)
                                underlay = null
                                videoOutput = null
                                visible = false
                                detached = true
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    val original = interruptionFailure
                    if (original == null) throw cleanup
                    if (original !== cleanup && original.suppressed.none { it === cleanup }) {
                        if (cleanup.suppressed.any { it === original }) {
                            Log.w(TAG, "Underlay detach failed", cleanup)
                        } else {
                            original.addSuppressed(cleanup)
                        }
                    }
                }
                interruptionFailure?.let { throw it }
                if (!detached) return
            } finally {
                if (underlayBindingGeneration == binding) detachingUnderlay = null
            }
        }
        underlay = null
        videoOutput = null
        visible = false
        if (view != null) {
            view.requireMediaDriverAvailable(this)
            underlay = view
            bindOwnedView(view, binding)
        }
    }

    private fun bindOwnedView(view: StandbyMediaUnderlayView, binding: Long) {
        try {
            view.bindMediaDriver(this, drawable, binding)
            if (isCurrentUnderlayBinding(view, binding)) view.setVideoEnabled(this, request?.isVideo == true)
        } catch (cause: Throwable) {
            if (underlayBindingGeneration == binding && underlay === view) {
                val previousRollback = isRollingBackUnderlay
                isRollingBackUnderlay = true
                try {
                    if (view.isOwnedBy(this)) {
                        bindUnderlay(null)
                    } else {
                        underlay = null
                        videoOutput = null
                        visible = false
                    }
                } catch (cleanup: Throwable) {
                    if (cleanup !== cause && cause.suppressed.none { it === cleanup }) {
                        if (cleanup.suppressed.any { it === cause }) {
                            Log.w(TAG, "Underlay rollback failed", cleanup)
                        } else {
                            cause.addSuppressed(cleanup)
                        }
                    }
                } finally {
                    isRollingBackUnderlay = previousRollback
                }
            }
            throw cause
        }
    }

    override fun open(
        file: File,
        isVideo: Boolean,
        isAnimated: Boolean,
        token: Long,
        listener: StandbyPlayerListener,
    ) {
        requireMainThread()
        check(request == null && closing == null && isReleaseSettled(null)) {
            "previous standby output has not been released"
        }
        val current = Request(file, isVideo, isAnimated, token, listener)
        request = current
        generation += 1
        underlay?.setVideoEnabled(this, isVideo)
        maybeLoad()
    }

    override fun restart() {
        requireMainThread()
        val current = checkNotNull(request) { "no standby request" }
        check(visible) { "standby output is not visible" }
        if (current.isVideo) {
            val currentPlayer = checkNotNull(player) { "video is not prepared" }
            val output = checkNotNull(videoOutputBound) { "video output disappeared" }
            check(playerPrepared && !playerStarted && isCurrentVideo(current, videoEpoch, currentPlayer, output, videoBinding)) {
                "video restart requires a fresh player and live output"
            }
            checkNotNull(videoSignals).onStarted()
            playerStarted = true
            currentPlayer.start()
        } else if (current.isAnimated) {
            if (current.replayNeeded) {
                current.restartPending = true
                // Keep the old prepared image through synchronous STARTING observers, not through replay.
                releaseImage()
                maybeLoad()
                return
            }
            val animated = drawable as? AnimatedImageDrawable
                ?: error("animation is not prepared")
            check(animated.callback === underlay) { "underlay no longer owns the drawable" }
            stopAnimation()
            val animationEpoch = generation
            var startingAnimation = true
            val callback = object : Animatable2.AnimationCallback() {
                override fun onAnimationStart(drawable: Drawable) {
                    if (request === current && generation == animationEpoch && this === animationCallback &&
                        this@AndroidStandbyMediaDriver.drawable === drawable && animationActive && visible &&
                        underlay?.isOwnedBy(this@AndroidStandbyMediaDriver) == true
                    ) {
                        if (startingAnimation) {
                            current.listener.onRendering(current.file, current.token)
                        } else {
                            notifyAsync(current) { current.listener.onRendering(current.file, current.token) }
                        }
                    }
                }

                override fun onAnimationEnd(drawable: Drawable) {
                    if (request === current && generation == animationEpoch && this === animationCallback &&
                        this@AndroidStandbyMediaDriver.drawable === drawable && animationActive && visible &&
                        underlay?.isOwnedBy(this@AndroidStandbyMediaDriver) == true
                    ) {
                        notifyAsync(current) {
                            current.listener.onError(
                                current.file, current.token,
                                IOException("infinite standby animation ended unexpectedly"),
                            )
                        }
                    }
                }
            }
            animationCallback = callback
            animationActive = true
            animated.registerAnimationCallback(callback)
            try {
                animated.start()
            } finally {
                startingAnimation = false
            }
        }
    }

    override fun pause() {
        requireMainThread()
        if (playerStarted) player?.pause()
        stopAnimation()
    }

    override fun release(): Boolean {
        requireMainThread()
        val current = request ?: closing
        if (current == null) {
            check(isReleaseSettled(null)) { "standby output retirement is unproven" }
            return true
        }
        request = null
        closing = current
        generation += 1
        pendingReleaseGeneration = 0L
        try {
            releaseOutput()
            check(isVideoNativeClosed && drawable == null) { "standby native output is still retained" }
            closeFailure = null
        } catch (cause: Throwable) {
            rememberCloseFailure(cause)
            Log.e(TAG, "standby output release failed", cause)
            throw cause
        }
        if (loading) {
            pendingReleaseGeneration = generation
            return false
        }
        if (input != null || current.sourceLease != null) {
            pendingReleaseGeneration = generation
            closeInputForRelease(current)
            return false
        }
        check(isReleaseSettled(current)) { "standby output retirement is unproven" }
        closing = null
        return true
    }

    private fun isReleaseSettled(current: Request?): Boolean = isStandbyReleaseSettled(
        outputClosed = isVideoNativeClosed && drawable == null,
        loading = loading,
        inputClosed = input == null,
        sourceReleased = current?.sourceLease == null,
        closeFailed = closeFailed,
    )

    /** Completes only the current explicit release attempt after independent IO settlement. */
    private fun completePendingRelease(current: Request) {
        if (pendingReleaseGeneration == 0L ||
            !isUnchangedStandbyOwner(current, closing, pendingReleaseGeneration, generation) || !isReleaseSettled(current)
        ) return
        pendingReleaseGeneration = 0L
        closing = null
        notifyAsync(current) { current.listener.onReleased(current.file, current.token) }?.let { throw it }
    }

    private fun rememberCloseFailure(cause: Throwable) {
        pendingReleaseGeneration = 0L
        val original = closeFailure
        if (original == null) {
            closeFailure = cause
        } else if (original !== cause && original.suppressed.none { it === cause }) {
            if (cause.suppressed.any { it === original }) {
                Log.w(TAG, "Additional standby release failure", cause)
            } else {
                original.addSuppressed(cause)
            }
        }
    }

    internal fun onUnderlayVisibilityChanged(view: StandbyMediaUnderlayView, isVisible: Boolean) =
        handleUnderlayVisibilityChanged(view, isVisible, frameworkCallback = false)

    /**
     * Handles a view lifecycle event, containing observer cancellation and requesting owner cleanup.
     * @param view originating underlay.
     * @param isVisible whether its output is visible.
     */
    internal fun onFrameworkUnderlayVisibilityChanged(view: StandbyMediaUnderlayView, isVisible: Boolean) =
        handleUnderlayVisibilityChanged(view, isVisible, frameworkCallback = true)

    private fun handleUnderlayVisibilityChanged(
        view: StandbyMediaUnderlayView,
        isVisible: Boolean,
        frameworkCallback: Boolean,
    ) {
        requireMainThread()
        if (!view.isOwnedBy(this) || visible == isVisible) return
        visible = isVisible
        val current = request ?: return
        if (!isVisible) {
            generation += 1
            if (current.isAnimated && current.imagePrepared) {
                current.replayNeeded = true
                current.restartPending = false
            }
            try {
                stopAnimation()
                releaseVideo()
            } catch (e: Throwable) {
                rememberCloseFailure(e)
                Log.e(TAG, "standby output interruption failed", e)
                notifyEntryListener(current, frameworkCallback) {
                    current.listener.onError(current.file, current.token, e)
                }
                return
            }
            notifyEntryListener(current, frameworkCallback) {
                current.listener.onInterrupted(current.file, current.token)
            }
        } else if (drawable != null || (current.isAnimated && current.imagePrepared)) {
            notifyEntryListener(current, frameworkCallback) {
                current.listener.onPrepared(current.file, current.token)
            }
        } else {
            maybeLoad()
        }
    }

    internal fun onVideoSurfaceAvailable(view: StandbyMediaUnderlayView, available: StandbyVideoOutput) =
        handleVideoSurfaceAvailable(view, available, frameworkCallback = false)

    /**
     * Accepts a real framework-created lifetime without owning its Surface.
     * @param view originating underlay.
     * @param output available framework output.
     */
    internal fun onFrameworkVideoSurfaceAvailable(view: StandbyMediaUnderlayView, output: StandbyVideoOutput) =
        handleVideoSurfaceAvailable(view, output, frameworkCallback = true)

    private fun handleVideoSurfaceAvailable(
        view: StandbyMediaUnderlayView,
        available: StandbyVideoOutput,
        frameworkCallback: Boolean,
    ) {
        requireMainThread()
        val binding = underlayBindingGeneration
        if (closeFailed || !isCurrentUnderlayBinding(view, binding) || !view.ownsVideoOutput(available)) return
        val previous = videoOutputBound
        if (previous != null && previous !== available) {
            val current = request
            generation += 1
            val interruptedGeneration = generation
            try {
                releaseVideo()
            } catch (cause: Throwable) {
                rememberCloseFailure(cause)
                Log.e(TAG, "standby surface replacement failed", cause)
                if (current != null) notifyEntryListener(current, frameworkCallback) {
                    current.listener.onError(current.file, current.token, cause)
                }
                return
            }
            if (current != null && notifyEntryListener(current, frameworkCallback) {
                current.listener.onInterrupted(current.file, current.token)
            } != null) return
            if (!isCurrentUnderlayBinding(view, binding) ||
                !isUnchangedStandbyOwner(current, request, interruptedGeneration, generation) ||
                !view.ownsVideoOutput(available)
            ) return
        }
        videoOutput = available
        maybeLoad()
    }

    internal fun onVideoSurfaceDestroyed(view: StandbyMediaUnderlayView, destroyed: StandbyVideoOutput) =
        handleVideoSurfaceDestroyed(view, destroyed, frameworkCallback = false)

    /**
     * Retires native use when possible; holder destruction cannot be retained.
     * @param view originating underlay.
     * @param output already invalidated framework lifetime.
     */
    internal fun onFrameworkVideoSurfaceDestroyed(view: StandbyMediaUnderlayView, output: StandbyVideoOutput) =
        handleVideoSurfaceDestroyed(view, output, frameworkCallback = true)

    private fun handleVideoSurfaceDestroyed(
        view: StandbyMediaUnderlayView,
        destroyed: StandbyVideoOutput,
        frameworkCallback: Boolean,
    ) {
        requireMainThread()
        if (!isStandbyVideoOutputReferenced(destroyed, videoOutput, videoOutputBound)) return
        if (!view.isOwnedBy(this) && videoOutputBound !== destroyed) return
        val wasCurrent = videoOutput === destroyed
        if (wasCurrent) videoOutput = null
        val current = request
        if (videoOutputBound !== destroyed) {
            if (wasCurrent && current?.isVideo == true) {
                generation += 1
                notifyEntryListener(current, frameworkCallback) {
                    current.listener.onInterrupted(current.file, current.token)
                }
            }
            return
        }
        generation += 1
        val closingOwner = closing
        try {
            releaseVideo()
        } catch (cause: Throwable) {
            rememberCloseFailure(cause)
            Log.e(TAG, "standby holder destroyed before proven native release", cause)
            if (current != null) {
                notifyEntryListener(current, frameworkCallback) {
                    current.listener.onError(current.file, current.token, cause)
                }
            } else if (closingOwner != null && closing === closingOwner) {
                notifyEntryListener(closingOwner, frameworkCallback) {
                    closingOwner.listener.onReleaseFailed(closingOwner.file, closingOwner.token, cause)
                }
            }
            return
        }
        if (current != null) notifyEntryListener(current, frameworkCallback) {
            current.listener.onInterrupted(current.file, current.token)
        }
    }

    /**
     * Rechecks eligibility without manufacturing a player rendering event.
     * @param view originating underlay.
     * @param output lifetime whose placement changed.
     */
    internal fun onVideoOutputGeometryChanged(view: StandbyMediaUnderlayView, output: StandbyVideoOutput) {
        requireMainThread()
        val current = request ?: return
        val candidate = player ?: return
        val signals = videoSignals ?: return
        val epoch = videoEpoch
        val binding = videoBinding
        if (isCurrentUnderlayBinding(view, binding) && isCurrentVideo(current, epoch, candidate, output, binding) &&
            signals.onOutputGeometryChanged(output, view.isVideoOutputGeometryUsable(output))
        ) reportVideoRendering(current, epoch, candidate, output, binding)
    }

    private fun maybeLoad() {
        val current = request ?: return
        val view = underlay ?: return
        if (!visible || !view.isOwnedBy(this) || loading || closeFailed || input != null || closing != null) return
        if (drawable != null || !isVideoNativeClosed) return
        if (current.isVideo) {
            val available = videoOutput ?: return
            if (!view.ownsVideoOutput(available)) return
            if (available === lastVideoOutput?.get()) {
                videoOutput = null
                view.replaceVideoOutput(this)
                return
            }
        }
        val epoch = generation
        val ownerView = WeakReference(view)
        loading = true
        scope.launch {
            var failure: Throwable? = null
            var openedInput: FileInputStream? = null
            var openedImage: Drawable? = null
            var openedLease = current.sourceLease
            var imagePrepared = false
            try {
                val opened = withContext(Dispatchers.IO + NonCancellable) {
                    val privateRoot = privateDataDir.canonicalFile
                    val directory = storageDir.canonicalFile
                    require(directory.toPath().startsWith(privateRoot.toPath())) {
                        "store directory is outside private app data"
                    }
                    AndroidStandbyMediaDriverPolicy.requireUsableStoreFile(current.file, directory)
                    if (current.isVideo) {
                        val stream = FileInputStream(current.file)
                        openedInput = stream
                        Opened.Video(stream)
                    } else {
                        val source = if (current.isAnimated) {
                            val lease = openedLease ?: store.pinSource(current.file).also { openedLease = it }
                            lease.file
                        } else {
                            current.file
                        }
                        val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(source))
                        openedImage = decoded
                        Opened.Image(decoded)
                    }
                }
                input = openedInput
                if (request === current && generation == epoch && visible &&
                    ownerView.get()?.isOwnedBy(this@AndroidStandbyMediaDriver) == true
                ) {
                    when (opened) {
                        is Opened.Image -> {
                            openedImage = null
                            imagePrepared = attachImage(current, opened.drawable)
                        }
                        is Opened.Video -> prepareVideo(current, epoch, opened.input)
                    }
                } else if (opened is Opened.Image) {
                    drawable = opened.drawable
                    openedImage = null
                    releaseImage()
                }
            } catch (e: CancellationException) {
                failure = e
            } catch (e: Exception) {
                failure = e
            } finally {
                // Save acquired pins even when returning from IO is cancelled or decoding fails.
                if (current.sourceLease == null) current.sourceLease = openedLease
                val unattachedImage = openedImage
                if (unattachedImage != null) {
                    drawable = unattachedImage
                    try {
                        releaseImage()
                    } catch (e: Exception) {
                        if (failure == null) failure = e else if (failure !== e) failure.addSuppressed(e)
                    }
                }
                if (input == null) input = openedInput
                val ownedInput = input
                if (ownedInput != null) {
                    try {
                        withContext(Dispatchers.IO + NonCancellable) { ownedInput.close() }
                        if (input === ownedInput) input = null
                    } catch (e: Exception) {
                        if (failure == null) failure = e else failure.addSuppressed(e)
                    }
                }
                if (closing === current && !closeFailed && isVideoNativeClosed && input == null && drawable == null) {
                    val ownedLease = current.sourceLease
                    if (ownedLease != null) {
                        try {
                            withContext(Dispatchers.IO + NonCancellable) { ownedLease.release() }
                            if (current.sourceLease === ownedLease) current.sourceLease = null
                        } catch (e: Exception) {
                            if (failure == null) failure = e else if (failure !== e) failure.addSuppressed(e)
                        }
                    }
                }
                loading = false
                val sourceFailure = failure
                val closingRequest = closing
                if (closingRequest != null) {
                    if (closeFailed) {
                        if (failure != null) {
                            rememberCloseFailure(failure)
                            Log.w(TAG, "standby load failed during release", failure)
                        }
                    } else if (!isReleaseSettled(closingRequest)) {
                        val cause = failure ?: IOException("standby load cleanup failed")
                        rememberCloseFailure(cause)
                        notifyAsync(closingRequest) {
                            closingRequest.listener.onReleaseFailed(closingRequest.file, closingRequest.token, cause)
                        }?.let { throw it }
                    } else {
                        if (failure != null) Log.w(TAG, "standby load failed during release", failure)
                        completePendingRelease(closingRequest)
                    }
                } else if (sourceFailure != null && request === current &&
                    (generation == epoch || input != null || drawable != null)
                ) {
                    notifyAsync(current) {
                        current.listener.onError(current.file, current.token, sourceFailure)
                    }?.let { throw it }
                } else {
                    if (failure != null) Log.w(TAG, "discarded standby load failed", failure)
                    if (generation != epoch) maybeLoad()
                }
            }
            if (failure == null && imagePrepared && request === current && generation == epoch && visible &&
                view.isOwnedBy(this@AndroidStandbyMediaDriver)
            ) {
                notifyAsync(current) {
                    if (current.isAnimated && current.imagePrepared) {
                        if (current.restartPending) {
                            current.replayNeeded = false
                            current.restartPending = false
                            try {
                                restart()
                            } catch (cause: CancellationException) {
                                throw cause
                            } catch (cause: Throwable) {
                                current.listener.onError(current.file, current.token, cause)
                            }
                        }
                    } else {
                        current.imagePrepared = true
                        current.listener.onPrepared(current.file, current.token)
                    }
                }?.let { throw it }
            }
        }
    }

    private fun attachImage(current: Request, decoded: Drawable): Boolean {
        if (decoded is AnimatedImageDrawable) drawable = decoded
        if (decoded.intrinsicWidth <= 0 || decoded.intrinsicHeight <= 0) {
            throw IOException("invalid decoded dimensions")
        }
        if (current.isAnimated && decoded !is AnimatedImageDrawable) {
            throw IOException("expected an animated drawable")
        }
        if (!current.isAnimated && decoded !is BitmapDrawable) {
            throw IOException("expected a static bitmap")
        }
        drawable = decoded
        if (decoded is AnimatedImageDrawable) {
            decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
        val view = underlay
        val binding = underlayBindingGeneration
        if (view != null && isCurrentUnderlayBinding(view, binding)) {
            view.setMediaDrawable(decoded)
            return request === current && visible && isCurrentUnderlayBinding(view, binding)
        }
        return false
    }

    private fun prepareVideo(current: Request, epoch: Long, source: FileInputStream) {
        val output = checkNotNull(videoOutput) { "video surface disappeared" }
        val view = checkNotNull(underlay) { "video underlay disappeared" }
        val binding = underlayBindingGeneration
        check(isCurrentUnderlayBinding(view, binding) && view.ownsVideoOutput(output)) { "video output is stale" }
        lastVideoOutput = WeakReference(output)
        val currentPlayer = MediaPlayer()
        val signals = StandbyVideoSignals(output)
        player = currentPlayer
        videoOutputBound = output
        videoEpoch = epoch
        videoBinding = binding
        videoSignals = signals
        playerPrepared = false
        playerStarted = false
        currentPlayer.setOnPreparedListener { ready ->
            if (!isCurrentVideo(current, epoch, ready, output, binding)) return@setOnPreparedListener
            val rendering: Boolean
            val prepared: Boolean
            try {
                ready.isLooping = true
                ready.setVolume(0f, 0f)
                if (ready.trackInfo.none { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO }) {
                    throw IOException("standby file has no video track")
                }
                val width = ready.videoWidth
                val height = ready.videoHeight
                rendering = width > 0 && height > 0 && updateVideoSize(current, signals, width, height)
                if (!isCurrentVideo(current, epoch, ready, output, binding)) return@setOnPreparedListener
                playerPrepared = true
                prepared = signals.onPrepared()
            } catch (cause: Exception) {
                notifyAsync(current) { current.listener.onError(current.file, current.token, cause) }
                return@setOnPreparedListener
            }
            if (rendering) reportVideoRendering(current, epoch, ready, output, binding)
            if (prepared && isCurrentVideo(current, epoch, ready, output, binding)) {
                notifyAsync(current) { current.listener.onPrepared(current.file, current.token) }
            }
        }
        currentPlayer.setOnVideoSizeChangedListener { changed, width, height ->
            if (isCurrentVideo(current, epoch, changed, output, binding)) {
                if (signals.hasLostSize(width, height)) {
                    interruptVideo(current)
                } else {
                    val rendering = try {
                        updateVideoSize(current, signals, width, height)
                    } catch (cause: Exception) {
                        notifyAsync(current) { current.listener.onError(current.file, current.token, cause) }
                        false
                    }
                    if (rendering) reportVideoRendering(current, epoch, changed, output, binding)
                }
            }
        }
        currentPlayer.setOnInfoListener { changed, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                isCurrentVideo(current, epoch, changed, output, binding) && playerStarted
            ) {
                if (signals.onRenderingInfo()) reportVideoRendering(current, epoch, changed, output, binding)
                true
            } else {
                false
            }
        }
        currentPlayer.setOnErrorListener { changed, what, extra ->
            if (isCurrentVideo(current, epoch, changed, output, binding)) {
                notifyAsync(current) {
                    current.listener.onError(
                        current.file, current.token,
                        IOException("standby video error: what=$what extra=$extra"),
                    )
                }
            }
            true
        }
        currentPlayer.setSurface(output.surface)
        currentPlayer.setDataSource(source.fd)
        currentPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        currentPlayer.prepareAsync()
    }

    private fun updateVideoSize(
        current: Request,
        signals: StandbyVideoSignals<StandbyVideoOutput>,
        width: Int,
        height: Int,
    ): Boolean {
        val view = underlay ?: return false
        val output = videoOutputBound ?: return false
        val candidate = player ?: return false
        val epoch = videoEpoch
        val binding = videoBinding
        if (!isCurrentVideo(current, epoch, candidate, output, binding) || videoSignals !== signals) return false
        view.setVideoContentSize(this, width, height)
        if (!isCurrentVideo(current, epoch, candidate, output, binding) || videoSignals !== signals) return false
        val rendering = signals.onSizeChanged(width, height)
        return signals.onOutputGeometryChanged(output, view.isVideoOutputGeometryUsable(output)) || rendering
    }

    private fun reportVideoRendering(
        current: Request,
        epoch: Long,
        candidate: MediaPlayer,
        output: StandbyVideoOutput,
        binding: Long,
    ) {
        val view = underlay ?: return
        if (!isCurrentVideo(current, epoch, candidate, output, binding) || !view.isVideoOutputGeometryUsable(output)) return
        view.setVideoRendering(this, output, true)
        if (isCurrentVideo(current, epoch, candidate, output, binding) && view.isVideoOutputGeometryUsable(output)) {
            notifyAsync(current) { current.listener.onRendering(current.file, current.token) }
        }
    }

    private fun interruptVideo(current: Request) {
        generation += 1
        val interruptedGeneration = generation
        try {
            releaseVideo()
        } catch (e: Throwable) {
            rememberCloseFailure(e)
            Log.e(TAG, "standby video interruption failed", e)
            notifyAsync(current) { current.listener.onError(current.file, current.token, e) }
            return
        }
        notifyAsync(current) { current.listener.onInterrupted(current.file, current.token) }
        if (isUnchangedStandbyOwner(current, request, interruptedGeneration, generation)) {
            try {
                maybeLoad()
            } catch (e: Exception) {
                notifyAsync(current) { current.listener.onError(current.file, current.token, e) }
            }
        }
    }

    private fun isCurrentVideo(
        current: Request,
        epoch: Long,
        candidate: MediaPlayer,
        output: StandbyVideoOutput,
        binding: Long,
    ): Boolean = request === current && generation == epoch && videoEpoch == epoch && visible && !closeFailed &&
        player === candidate && videoOutput === output && videoOutputBound === output &&
        underlay?.let { isCurrentUnderlayBinding(it, binding) && it.ownsVideoOutput(output) } == true

    private fun stopAnimation() {
        val animated = drawable as? AnimatedImageDrawable ?: return
        animationActive = false
        val callback = animationCallback
        // stop() posts against the live callback list on Android.
        if (callback != null) {
            animated.unregisterAnimationCallback(callback)
            animationCallback = null
        }
        animated.stop()
    }

    private fun releaseVideo() {
        check(!videoReleaseInProgress) { "standby native release is already in progress" }
        videoReleaseInProgress = true
        videoEpoch = 0L
        try {
            videoOutputBound?.let { underlay?.setVideoRendering(this, it, false) }
            player = releaseStandbyVideoOwner(player) { retained ->
                beforeVideoReleaseForTest?.invoke()
                retained.release()
            }
            // A dead holder is not a release receipt; clear only after native release.
            videoOutputBound = null
            playerPrepared = false
            playerStarted = false
            videoBinding = 0L
            videoSignals = null
            underlay?.setVideoContentSize(this, 0, 0)
        } catch (cause: Throwable) {
            rememberCloseFailure(cause)
            throw cause
        } finally {
            videoReleaseInProgress = false
        }
    }

    private fun releaseImage() {
        drawable = releaseStandbyImageOwner(
            drawable,
            stop = { stopAnimation() },
            detach = { image -> underlay?.takeIf { it.isOwnedBy(this) }?.clearMediaDrawable(image) },
        )
    }

    private fun releaseOutput() {
        releaseImage()
        releaseVideo()
        underlay?.setVideoEnabled(this, false)
    }

    /**
     * Closes the input and pinned source behind the existing asynchronous release fence.
     * @param current closing owner retaining its original callback identity.
     */
    private fun closeInputForRelease(current: Request) {
        val ownedInput = input
        val ownedLease = current.sourceLease
        loading = true
        scope.launch {
            var inputClosed = false
            var sourceReleased = false
            val failure = try {
                withContext(Dispatchers.IO + NonCancellable) {
                    ownedInput?.close()
                    inputClosed = true
                    ownedLease?.release()
                    sourceReleased = true
                }
                null
            } catch (e: Throwable) {
                e
            }
            if (inputClosed && input === ownedInput) input = null
            if (sourceReleased && current.sourceLease === ownedLease) current.sourceLease = null
            loading = false
            if (failure != null) {
                val awaiting = pendingReleaseGeneration != 0L &&
                    isUnchangedStandbyOwner(current, closing, pendingReleaseGeneration, generation)
                rememberCloseFailure(failure)
                Log.e(TAG, "standby input close failed", failure)
                if (awaiting) {
                    notifyAsync(current) {
                        current.listener.onReleaseFailed(current.file, current.token, failure)
                    }?.let { throw it }
                }
            } else if (closing === current) {
                completePendingRelease(current)
                if (closing === current) Log.w(TAG, "Standby input settled without proven output retirement", closeFailure)
            }
        }
    }

    private inline fun notifyEntryListener(
        current: Request,
        frameworkCallback: Boolean,
        callback: () -> Unit,
    ): CancellationException? {
        val cancellation = dispatchStandbyEntryEvent(
            current.listener, current.file, current.token, frameworkCallback, callback,
        )
        if (cancellation != null) Log.w(TAG, "Standby observer cancelled", cancellation)
        return cancellation
    }

    private inline fun notifyAsync(current: Request, callback: () -> Unit): CancellationException? {
        val cancellation = dispatchStandbyAsyncEvent(current.listener, current.file, current.token, callback)
        if (cancellation != null) Log.w(TAG, "Standby observer cancelled", cancellation)
        return cancellation
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "must run on main thread" }
    }

    private companion object {
        const val TAG = "StandbyMediaDriver"
    }
}
