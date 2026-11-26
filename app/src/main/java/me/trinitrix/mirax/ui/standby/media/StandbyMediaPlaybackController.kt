package me.trinitrix.mirax.ui.standby.media

import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable, silent looping media driver. All methods and listener callbacks run
 * serialized on the main thread; callbacks may also occur synchronously.
 */
interface StandbyPlayerDriver {
    /**
     * Starts a non-blocking open for one owner. Video must be muted and video
     * and animated images must loop without changing other audio.
     *
     * @param file store-owned media file.
     * @param isVideo whether the file is a video.
     * @param isAnimated whether the file is an animated image.
     * @param token unique identity for this open.
     * @param listener callbacks for this owner. Asynchronous observer cancellation
     * must be passed to [StandbyPlayerListener.onObserverCancelled] with the
     * original cause; it must not be reported as a source failure.
     */
    fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener)

    /** Requests prepared looping media from the beginning; [StandbyPlayerListener.onRendering] confirms output. */
    fun restart()

    /** Pauses prepared looping media. */
    fun pause()

    /**
     * Requests release without making this driver terminal.
     * A failed attempt must not send later terminal callbacks, even after a retry;
     * the listener identifies the owner, not an individual release attempt.
     *
     * @return true if this playback owner is stopped and detached; otherwise
     * completion is reported through the original owner's listener before another
     * open is allowed. Native drawable reclamation may occur later.
     */
    fun release(): Boolean
}

/** Events for the original file and token supplied to [StandbyPlayerDriver.open]. */
interface StandbyPlayerListener {
    /**
     * Reports that the owner is prepared, even if video dimensions are not yet known.
     * @param file original file.
     * @param token original open identity.
     */
    fun onPrepared(file: File, token: Long)

    /**
     * Reports actual rendered output.
     * @param file original file.
     * @param token original open identity.
     */
    fun onRendering(file: File, token: Long)

    /**
     * Reports output loss before the driver starts preparing again.
     * @param file original file.
     * @param token original open identity.
     */
    fun onInterrupted(file: File, token: Long)

    /**
     * Reports an owner failure without changing persistent storage. The cause
     * may reflect resource, permission, or cleanup issues and does not by itself
     * establish corrupt media; the host must classify it before changing stored media.
     * @param file original file.
     * @param token original open identity.
     * @param cause original failure.
     */
    fun onError(file: File, token: Long, cause: Throwable)

    /**
     * Reports completion of an asynchronous release.
     * @param file original file.
     * @param token original open identity.
     */
    fun onReleased(file: File, token: Long)

    /**
     * Reports a failed release; the driver remains available for retry.
     * @param file original file.
     * @param token original open identity.
     * @param cause original release failure.
     */
    fun onReleaseFailed(file: File, token: Long, cause: Throwable)

    /**
     * Cancels the matching owner after an asynchronous observer callback throws.
     * This is not a media source failure.
     * @param file original file.
     * @param token original open identity.
     * @param cause original observer cancellation.
     */
    fun onObserverCancelled(file: File, token: Long, cause: CancellationException)
}

/** Observable playback and decoder ownership state. */
enum class StandbyPlaybackState {
    IDLE,
    PREPARING,
    READY,
    STARTING,
    PLAYING,
    PAUSED,
    STOPPING,
    ERROR,
    RELEASED,
}

/**
 * Serializes one media owner, including asynchronous decode cancellation.
 * All public methods and callbacks must run on the same main thread. Observer
 * exceptions are logged except cancellation and reentrant driver failures. A
 * cancellation during STOPPING is rethrown after attempting the close.
 *
 * @param driver injectable, reusable platform driver.
 * @param onError optional callback with the original owner and cause. Failures
 * may concern resources, permissions, or cleanup and do not establish corruption;
 * the host must classify the cause before changing stored media. An asynchronous
 * cleanup failure reports the same cause again with it suppressed.
 * @param onStateChanged optional callback for subsequent state changes.
 */
class StandbyMediaPlaybackController(
    private var driver: StandbyPlayerDriver? = null,
    private val onError: ((File, Long, Throwable) -> Unit)? = null,
    private val onStateChanged: ((StandbyPlaybackState) -> Unit)? = null,
) : StandbyPlayerListener {
    /** Current playback state. */
    var state: StandbyPlaybackState = StandbyPlaybackState.IDLE
        private set

    private data class Media(val file: File, val isVideo: Boolean, val isAnimated: Boolean) {
        val loops: Boolean get() = isVideo || isAnimated
    }

    private class Owner(val media: Media, val token: Long, val terminalOnFailure: Boolean) {
        var opening = false
        var openInvoked = false
        var startIssued = false
        var starting = false
        var propagateOpenFailure = false
        var releasing = false
        var retryRequested = false
        // A synchronous failure observer may repeatedly request a retry.
        var retryingFromCallback = false
        var propagateCloseFailure = false
        var failure: Throwable? = null
    }

    private var desired: Media? = null
    private var active: Owner? = null
    private var closing: Owner? = null
    private var paused = false
    /** Whether a visibility pause still prevents opening the selected media. */
    internal val isPaused: Boolean get() = paused
    private var terminalRequested = false
    private var terminalOnNextOpenFailure = false
    private var failureState: StandbyPlaybackState? = null
    private var replacementPending = false
    private var replacementDriver: StandbyPlayerDriver? = null
    // Reentrant observer calls can propagate driver failures through the callback.
    private var observerDepth = 0
    private val observerDriverFailures = mutableListOf<Throwable>()

    /**
     * Selects media, closing the previous owner before opening a replacement.
     * A selection after a cleanup failure retries the retained owner first.
     *
     * @param file store-owned media file, or null to detach.
     * @param isVideo whether the file is a video.
     * @param isAnimated whether the file is an animated image.
     */
    fun attach(file: File?, isVideo: Boolean, isAnimated: Boolean) {
        if (terminalRequested) return
        val media = file?.let { Media(it, isVideo, isAnimated) }
        val changed = media != desired
        if (!changed && failureState == null && state != StandbyPlaybackState.ERROR &&
            (active != null || closing != null || paused)
        ) return
        desired = media
        failureState = null
        terminalOnNextOpenFailure = false
        val owner = closing
        if (active != null) {
            closeActive(pauseFirst = false, propagate = true)
        } else if (owner == null) {
            settle(propagate = true)
        } else if (state == StandbyPlaybackState.ERROR) {
            if (owner.releasing) {
                if (!owner.retryingFromCallback) owner.retryRequested = true
            } else {
                retryClose(owner, propagate = true)
            }
        }
    }

    /** Hides playback, waiting for the old owner to close before any resume. */
    fun pause() {
        if (terminalRequested || state == StandbyPlaybackState.ERROR || paused) return
        paused = true
        val pauseFirst = state == StandbyPlaybackState.STARTING || state == StandbyPlaybackState.PLAYING
        if (active != null) {
            closeActive(pauseFirst = pauseFirst, propagate = true)
        } else if (closing == null) {
            settle(propagate = true)
        }
    }

    /** Reopens the selected media from the beginning after the old owner closes. */
    fun resume() {
        if (terminalRequested || state == StandbyPlaybackState.ERROR || !paused) return
        paused = false
        terminalOnNextOpenFailure = true
        if (closing == null) settle(propagate = true)
    }

    /** Reopens the selected media from the beginning with new render evidence. */
    fun restart() {
        if (terminalRequested || state == StandbyPlaybackState.ERROR || desired == null) return
        paused = false
        terminalOnNextOpenFailure = true
        val pauseFirst = state == StandbyPlaybackState.STARTING || state == StandbyPlaybackState.PLAYING
        if (active != null) {
            closeActive(pauseFirst = pauseFirst, propagate = true)
        } else if (closing == null) {
            settle(propagate = true)
        }
    }

    /** Requests terminal release; failed cleanup can be retried with this method. */
    fun release() {
        if (state == StandbyPlaybackState.RELEASED) return
        terminalRequested = true
        desired = null
        replacementPending = false
        replacementDriver = null
        val owner = closing
        when {
            owner != null && state == StandbyPlaybackState.ERROR -> {
                if (owner.releasing) {
                    if (!owner.retryingFromCallback) owner.retryRequested = true
                } else {
                    retryClose(owner, propagate = true)
                }
            }
            owner != null -> Unit
            active != null -> closeActive(pauseFirst = false, propagate = true)
            else -> settle(propagate = true)
        }
    }

    /**
     * Replaces the test driver only after its previous owner is fully closed.
     *
     * @param driver replacement driver, or null to detach it.
     */
    fun replaceDriverForTest(driver: StandbyPlayerDriver?) {
        if (terminalRequested || state == StandbyPlaybackState.ERROR) return
        desired = null
        replacementPending = true
        replacementDriver = driver
        if (active != null) {
            closeActive(pauseFirst = false, propagate = true)
        } else if (closing == null) {
            settle(propagate = true)
        }
    }

    override fun onPrepared(file: File, token: Long) {
        val owner = activeOwner(file, token) ?: return
        if (state != StandbyPlaybackState.PREPARING) return
        if (!owner.media.loops) {
            changeOwnerState(owner, StandbyPlaybackState.READY)
            return
        }
        changeOwnerState(owner, StandbyPlaybackState.STARTING)
        if (active !== owner || state != StandbyPlaybackState.STARTING) return
        owner.startIssued = true
        owner.starting = true
        try {
            driver?.restart()
        } catch (cause: Throwable) {
            trackDriverFailure(cause)
            if (cause is CancellationException && active !== owner) throw cause
            val propagate = owner.opening && owner.propagateOpenFailure
            failActive(owner, cause, synchronous = propagate)
            if (propagate) throw cause
        } finally {
            owner.starting = false
        }
    }

    override fun onRendering(file: File, token: Long) {
        val owner = activeOwner(file, token) ?: return
        if (state != StandbyPlaybackState.STARTING) return
        changeOwnerState(owner, StandbyPlaybackState.PLAYING)
    }

    override fun onInterrupted(file: File, token: Long) {
        val owner = activeOwner(file, token) ?: return
        owner.startIssued = false
        changeOwnerState(owner, StandbyPlaybackState.PREPARING)
    }

    override fun onError(file: File, token: Long, cause: Throwable) {
        val owner = activeOwner(file, token) ?: return
        failActive(owner, cause, synchronous = false)
    }

    override fun onReleased(file: File, token: Long) {
        if (state != StandbyPlaybackState.STOPPING) return
        val owner = closingOwner(file, token) ?: return
        completeClose(owner, propagate = owner.releasing && owner.propagateCloseFailure)
    }

    override fun onReleaseFailed(file: File, token: Long, cause: Throwable) {
        if (state != StandbyPlaybackState.STOPPING) return
        val owner = closingOwner(file, token) ?: return
        val original = owner.failure
        if (original != null && original !== cause && original.suppressed.none { it === cause }) {
            original.addSuppressed(cause)
        }
        val failure = original ?: cause
        owner.failure = failure
        preserveFailure(failure) {
            transitionToState(StandbyPlaybackState.ERROR) { report(owner, failure) }
        }
    }

    override fun onObserverCancelled(file: File, token: Long, cause: CancellationException) {
        val owner = activeOwner(file, token) ?: return
        if (desired == owner.media) desired = null
        preserveFailure(cause) { closeActive(pauseFirst = false, propagate = false) }
    }

    private fun activeOwner(file: File, token: Long): Owner? =
        active?.takeIf { it.media.file == file && it.token == token }

    private fun changeOwnerState(owner: Owner, next: StandbyPlaybackState) {
        try {
            changeState(next)
        } catch (cause: CancellationException) {
            if (owner.opening || owner.starting) {
                preserveFailure(cause) { onObserverCancelled(owner.media.file, owner.token, cause) }
            }
            throw cause
        }
    }

    private fun closingOwner(file: File, token: Long): Owner? =
        closing?.takeIf { it.media.file == file && it.token == token }

    private fun changeState(next: StandbyPlaybackState) {
        if (state == next) return
        state = next
        notifyObserver("State") { onStateChanged?.invoke(next) }
    }

    private fun settle(propagate: Boolean) {
        if (closing != null || active != null) return
        if (terminalRequested) {
            driver = null
            desired = null
            changeState(StandbyPlaybackState.RELEASED)
            return
        }
        if (replacementPending) {
            driver = replacementDriver
            replacementDriver = null
            replacementPending = false
        }
        val failed = failureState
        if (failed != null) {
            failureState = null
            changeState(failed)
            return
        }
        val media = desired
        val currentDriver = driver
        when {
            media == null -> changeState(StandbyPlaybackState.IDLE)
            paused -> changeState(StandbyPlaybackState.PAUSED)
            currentDriver == null -> changeState(StandbyPlaybackState.IDLE)
            else -> {
                val token = nextToken.updateAndGet {
                    check(it < Long.MAX_VALUE) { "Standby playback token exhausted" }
                    it + 1
                }
                val owner = Owner(media, token, terminalOnNextOpenFailure)
                terminalOnNextOpenFailure = false
                active = owner
                try {
                    changeState(StandbyPlaybackState.PREPARING)
                } catch (cause: CancellationException) {
                    preserveFailure(cause) { onObserverCancelled(media.file, token, cause) }
                    throw cause
                }
                if (active !== owner || state != StandbyPlaybackState.PREPARING) return
                owner.opening = true
                owner.openInvoked = true
                owner.propagateOpenFailure = propagate
                try {
                    currentDriver.open(media.file, media.isVideo, media.isAnimated, token, this)
                } catch (cause: Throwable) {
                    trackDriverFailure(cause)
                    if (cause is CancellationException && active !== owner) throw cause
                    if (active === owner) failActive(owner, cause, synchronous = propagate)
                    if (propagate) throw cause
                } finally {
                    owner.opening = false
                }
            }
        }
    }

    private fun closeActive(pauseFirst: Boolean, propagate: Boolean) {
        val owner = active ?: return
        active = null
        closing = owner
        transitionToState(StandbyPlaybackState.STOPPING) {
            if (closing !== owner || state != StandbyPlaybackState.STOPPING) return@transitionToState
            if (!owner.openInvoked) {
                completeClose(owner, propagate)
                return@transitionToState
            }
            if (pauseFirst && owner.media.loops && owner.startIssued) {
                try {
                    driver?.pause()
                } catch (cause: Throwable) {
                    trackDriverFailure(cause)
                    terminalRequested = true
                    desired = null
                    owner.failure = cause
                    preserveFailure(cause) {
                        releaseOwner(owner, propagate = false)
                        report(owner, cause)
                    }
                    if (propagate) throw cause
                    return@transitionToState
                }
            }
            releaseOwner(owner, propagate)
        }
    }

    private fun retryClose(owner: Owner, propagate: Boolean) {
        owner.retryRequested = false
        transitionToState(StandbyPlaybackState.STOPPING) {
            if (closing === owner && state == StandbyPlaybackState.STOPPING && !owner.releasing) {
                releaseOwner(owner, propagate)
            }
        }
    }

    private fun releaseOwner(owner: Owner, propagate: Boolean) {
        owner.releasing = true
        owner.propagateCloseFailure = propagate
        var thrown: Throwable? = null
        try {
            try {
                val complete = driver?.release() ?: true
                if (complete && closing === owner && state != StandbyPlaybackState.ERROR) {
                    completeClose(owner, propagate)
                }
            } catch (cause: Throwable) {
                trackDriverFailure(cause)
                if (closing !== owner) throw cause
                val original = owner.failure
                if (cause is CancellationException) {
                    if (state != StandbyPlaybackState.ERROR) {
                        owner.failure = original ?: cause
                        if (original != null && original !== cause &&
                            cause.suppressed.none { it === original } && original.suppressed.none { it === cause }
                        ) cause.addSuppressed(original)
                        preserveFailure(cause) {
                            transitionToState(StandbyPlaybackState.ERROR) { report(owner, original ?: cause) }
                        }
                    }
                    throw cause
                }
                if (original != null) trackDriverFailure(original)
                if (original != null && original !== cause && original.suppressed.none { it === cause }) {
                    original.addSuppressed(cause)
                }
                val failure = original ?: cause
                owner.failure = failure
                preserveFailure(failure) {
                    transitionToState(StandbyPlaybackState.ERROR) {
                        if (original == null || propagate) report(owner, failure)
                    }
                }
                if (propagate) throw failure
            }
        } catch (cause: Throwable) {
            thrown = cause
        } finally {
            owner.releasing = false
        }
        if (owner.retryRequested && closing === owner && state == StandbyPlaybackState.ERROR) {
            owner.retryRequested = false
            owner.retryingFromCallback = true
            try {
                retryClose(owner, propagate = false)
            } catch (cause: Throwable) {
                val original = thrown
                if (original == null) {
                    thrown = cause
                } else if (original !== cause) {
                    if (original is CancellationException) {
                        if (cause.suppressed.any { it === original }) {
                            Log.w(TAG, "Cleanup retry failed during cancellation", cause)
                        } else if (original.suppressed.none { it === cause }) {
                            original.addSuppressed(cause)
                        }
                    } else if (cause is CancellationException || cause.suppressed.any { it === original }) {
                        if (cause.suppressed.none { it === original }) cause.addSuppressed(original)
                        thrown = cause
                    } else if (original.suppressed.none { it === cause }) {
                        original.addSuppressed(cause)
                    }
                }
            } finally {
                owner.retryingFromCallback = false
            }
        }
        thrown?.let { throw it }
    }

    private fun completeClose(owner: Owner, propagate: Boolean) {
        if (closing !== owner) return
        closing = null
        settle(propagate)
    }

    private fun failActive(owner: Owner, cause: Throwable, synchronous: Boolean) {
        if (active !== owner) return
        desired = null
        owner.failure = cause
        if (synchronous && owner.terminalOnFailure) terminalRequested = true
        failureState = if (synchronous) StandbyPlaybackState.IDLE else StandbyPlaybackState.ERROR
        preserveFailure(cause) {
            try {
                closeActive(pauseFirst = false, propagate = false)
            } catch (closeFailure: Throwable) {
                preserveFailure(closeFailure) { report(owner, cause) }
                throw closeFailure
            }
            report(owner, cause)
        }
    }

    private fun report(owner: Owner, cause: Throwable) {
        Log.w(TAG, "Standby media failed", cause)
        preserveFailure(cause) {
            notifyObserver("Error") { onError?.invoke(owner.media.file, owner.token, cause) }
        }
    }

    private inline fun transitionToState(next: StandbyPlaybackState, action: () -> Unit) {
        val cancellation = try {
            changeState(next)
            null
        } catch (cause: CancellationException) {
            cause
        }
        if (cancellation == null) {
            action()
        } else {
            preserveFailure(cancellation) { action() }
            throw cancellation
        }
    }

    private inline fun preserveFailure(original: Throwable, action: () -> Unit) {
        try {
            action()
        } catch (other: Throwable) {
            if (original === other) throw original
            if (original is CancellationException) {
                if (original.suppressed.none { it === other }) original.addSuppressed(other)
                throw original
            }
            if (other.suppressed.none { it === original }) other.addSuppressed(original)
            throw other
        }
    }

    private inline fun notifyObserver(name: String, callback: () -> Unit) {
        observerDepth++
        try {
            callback()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (observerDriverFailures.any { it === cause }) throw cause
            Log.w(TAG, "$name observer failed", cause)
        } finally {
            observerDepth--
            if (observerDepth == 0) observerDriverFailures.clear()
        }
    }

    private fun trackDriverFailure(cause: Throwable) {
        if (observerDepth > 0) observerDriverFailures.add(cause)
    }

    private companion object {
        const val TAG = "StandbyPlayback"
        val nextToken = AtomicLong(0)
    }
}
