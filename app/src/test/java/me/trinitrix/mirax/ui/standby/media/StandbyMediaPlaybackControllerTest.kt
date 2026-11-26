package me.trinitrix.mirax.ui.standby.media

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Behaviour regression for the real [StandbyMediaPlaybackController].
 *
 * Uses a fake [StandbyPlayerDriver] with explicit `playing` and `owned`
 * resource state to prove public state matches driver reality: detach or
 * release failures never fake IDLE, failed cleanup keeps the handle for
 * retry, and replacement never opens a second decoder. JVM only; no device
 * and no real decoder.
 */
class StandbyMediaPlaybackControllerTest {

    private class FakeDriver : StandbyPlayerDriver {
        val opens = mutableListOf<Triple<File, Boolean, Boolean>>()
        var restarts = 0
        var pauses = 0
        var releases = 0
        var playing = false
        var owned = false
        var failPause = false
        var failNextOpen: RuntimeException? = null
        var failNextRestart: RuntimeException? = null
        var failNextRelease: RuntimeException? = null
        var failReleaseOnRestartFailure: RuntimeException? = null
        private var file: File? = null
        private var token = 0L
        private var listener: StandbyPlayerListener? = null

        override fun open(
            file: File,
            isVideo: Boolean,
            isAnimated: Boolean,
            token: Long,
            listener: StandbyPlayerListener,
        ) {
            check(!owned) { "open while another owner is live" }
            owned = true
            this.file = file
            this.token = token
            this.listener = listener
            failNextOpen?.let {
                failNextOpen = null
                throw it
            }
            opens.add(Triple(file, isVideo, isAnimated))
            listener.onPrepared(file, token)
        }

        override fun restart() {
            failNextRestart?.let {
                failNextRestart = null
                failNextRelease = failReleaseOnRestartFailure
                failReleaseOnRestartFailure = null
                playing = true
                throw it
            }
            check(owned) { "restart without open" }
            restarts++
            playing = true
            file?.let { listener?.onRendering(it, token) }
        }

        override fun pause() {
            if (failPause) {
                failPause = false
                throw RuntimeException("pause boom")
            }
            pauses++
            playing = false
        }

        override fun release(): Boolean {
            releases++
            failNextRelease?.let {
                failNextRelease = null
                throw it
            }
            playing = false
            owned = false
            return true
        }
    }

    private class AsyncDriver : StandbyPlayerDriver {
        data class Request(
            val file: File,
            val isVideo: Boolean,
            val isAnimated: Boolean,
            val token: Long,
            val listener: StandbyPlayerListener,
        )

        val opens = mutableListOf<Request>()
        val events = mutableListOf<String>()
        var owner: Request? = null
            private set
        var releases = 0
        var pauses = 0
        var restarts = 0
        var projectionAudioVolume = 7
        var completeReleaseSynchronously = false
        var failNextReleaseSynchronously: Throwable? = null
        var throwNextRelease: RuntimeException? = null
        var persistentReleaseFailure: Throwable? = null

        override fun open(
            file: File,
            isVideo: Boolean,
            isAnimated: Boolean,
            token: Long,
            listener: StandbyPlayerListener,
        ) {
            check(owner == null) { "open while another owner is live" }
            val request = Request(file, isVideo, isAnimated, token, listener)
            owner = request
            opens.add(request)
            events.add("open")
        }

        override fun restart() {
            check(owner != null)
            restarts++
            events.add("restart")
        }

        override fun pause() {
            check(owner != null)
            pauses++
            events.add("pause")
        }

        override fun release(): Boolean {
            val request = checkNotNull(owner)
            releases++
            events.add("release")
            throwNextRelease?.let {
                throwNextRelease = null
                throw it
            }
            val failure = failNextReleaseSynchronously ?: persistentReleaseFailure
            if (failure != null) {
                failNextReleaseSynchronously = null
                request.listener.onReleaseFailed(request.file, request.token, failure)
            } else if (completeReleaseSynchronously) {
                owner = null
                request.listener.onReleased(request.file, request.token)
            }
            return false
        }

        fun prepared(request: Request) = request.listener.onPrepared(request.file, request.token)
        fun rendering(request: Request) = request.listener.onRendering(request.file, request.token)
        fun interrupted(request: Request) = request.listener.onInterrupted(request.file, request.token)
        fun error(request: Request, cause: Throwable) =
            request.listener.onError(request.file, request.token, cause)

        fun finishRelease(request: Request) {
            check(owner === request)
            owner = null
            request.listener.onReleased(request.file, request.token)
        }

        fun failRelease(request: Request, cause: Throwable) {
            check(owner === request)
            request.listener.onReleaseFailed(request.file, request.token, cause)
        }
    }

    @Test
    fun videoAttachOpensMutedLoopFromBeginning() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        val file = File("clip")

        controller.attach(file, isVideo = true, isAnimated = false)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.opens.single().first).isEqualTo(file)
        // The driver receives the media kind; its contract requires muted looping.
        assertThat(driver.opens.single().second).isTrue()
        assertThat(driver.opens.single().third).isFalse()
        assertThat(driver.restarts).isEqualTo(1)
    }

    @Test
    fun videoToStillStopsPreviousPlayback() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        controller.attach(File("still"), isVideo = false, isAnimated = false)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.READY)
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.opens).hasSize(2)
        assertThat(driver.restarts).isEqualTo(1)
    }

    @Test
    fun videoToNullStopsPreviousPlayback() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        controller.attach(null, isVideo = false, isAnimated = false)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        assertThat(driver.releases).isEqualTo(1)
    }

    @Test
    fun detachThenReattachOpensAgainOnSameDriver() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("one"), isVideo = true, isAnimated = false)
        controller.attach(null, isVideo = false, isAnimated = false)
        controller.attach(File("two"), isVideo = true, isAnimated = false)

        // Driver contract: open works after detach release on one controller.
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.opens).hasSize(2)
        assertThat(driver.opens[1].first).isEqualTo(File("two"))
    }

    @Test
    fun videoToVideoReplacesPreviousOutput() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("one"), isVideo = true, isAnimated = false)
        controller.attach(File("two"), isVideo = true, isAnimated = false)

        // The previous owner closes before the second open.
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.opens).hasSize(2)
        assertThat(driver.opens[1].first).isEqualTo(File("two"))
    }

    @Test
    fun gifAttachOpensMutedLoop() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("anim"), isVideo = false, isAnimated = true)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.opens.single().second).isFalse()
        assertThat(driver.opens.single().third).isTrue()
    }

    @Test
    fun gifToStillStopsPreviousPlayback() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)

        controller.attach(File("anim"), isVideo = false, isAnimated = true)
        controller.attach(File("still"), isVideo = false, isAnimated = false)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.READY)
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.restarts).isEqualTo(1)
    }

    @Test
    fun attachWithoutDriverNeverReportsPlaying() {
        val controller = StandbyMediaPlaybackController(null)

        controller.attach(File("clip"), isVideo = true, isAnimated = false)

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
    }

    @Test
    fun detachReleaseFailureKeepsPlayingUnknownAndRetainsAttachment() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRelease = RuntimeException("detach boom")

        var thrown: RuntimeException? = null
        try {
            controller.attach(File("still"), isVideo = false, isAnimated = false)
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("detach boom")
        // Unknown outcome: driver may still be playing, never fake IDLE.
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.playing).isTrue()
        assertThat(driver.owned).isTrue()
    }

    @Test
    fun detachFailureRetryReleaseSucceedsToTerminal() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRelease = RuntimeException("detach boom")
        try {
            controller.attach(File("still"), isVideo = false, isAnimated = false)
        } catch (ignored: RuntimeException) {
        }
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)

        // Retry path stays available and terminates only on success.
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        controller.release()
        assertThat(driver.releases).isEqualTo(2)
    }

    @Test
    fun attachOpenFailureCleansUpNewOutputAndGoesIdle() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        driver.failNextOpen = RuntimeException("open boom")

        var thrown: RuntimeException? = null
        try {
            controller.attach(File("clip"), isVideo = true, isAnimated = false)
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("open boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
    }

    @Test
    fun attachRestartFailureCleansUpNewOutputAndGoesIdle() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        driver.failNextRestart = RuntimeException("restart boom")

        var thrown: RuntimeException? = null
        try {
            controller.attach(File("clip"), isVideo = true, isAnimated = false)
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("restart boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        // Partial start was cleaned up, not left running.
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
    }

    @Test
    fun attachCleanupFailureKeepsOriginalAndReportsErrorWithSuppressed() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        driver.failNextRestart = RuntimeException("restart boom")
        driver.failReleaseOnRestartFailure = RuntimeException("cleanup boom")

        var thrown: RuntimeException? = null
        try {
            controller.attach(File("two"), isVideo = true, isAnimated = false)
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("restart boom")
        assertThat(thrown?.suppressed?.map { it.message }).contains("cleanup boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        // The partially started owner is retained for a later release retry.
        assertThat(driver.owned).isTrue()
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun releaseFailureRetainsHandleAllowsRetryAndKeepsOriginalError() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRelease = RuntimeException("release boom")

        var thrown: RuntimeException? = null
        try {
            controller.release()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("release boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        // Handle retained: decoder not dropped, playback outcome unknown.
        assertThat(driver.owned).isTrue()
        assertThat(driver.playing).isTrue()

        // Other operations stay rejected while cleanup is pending.
        controller.attach(File("other"), isVideo = true, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
        controller.replaceDriverForTest(FakeDriver())
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)

        // Retry succeeds and only then becomes terminal/idempotent.
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        controller.release()
        assertThat(driver.releases).isEqualTo(2)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun replacementWaitsForRetriedOldOwnerReleaseBeforeOpeningNewDriver() {
        val first = AsyncDriver()
        val second = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(first, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        val old = first.opens.single()
        val failure = IllegalStateException("replace boom")
        first.throwNextRelease = failure

        val thrown = try {
            controller.replaceDriverForTest(second)
            null
        } catch (cause: IllegalStateException) {
            cause
        }
        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(errors).containsExactly(Triple(old.file, old.token, failure))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(first.owner).isSameInstanceAs(old)
        assertThat(second.owner).isNull()
        assertThat(second.opens).isEmpty()

        controller.attach(File("two"), isVideo = true, isAnimated = false)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(first.releases).isEqualTo(2)
        assertThat(first.owner).isSameInstanceAs(old)
        assertThat(first.opens).hasSize(1)
        assertThat(second.owner).isNull()
        assertThat(second.opens).isEmpty()

        first.finishRelease(old)
        val replacement = second.opens.single()
        assertThat(first.owner).isNull()
        assertThat(second.owner).isSameInstanceAs(replacement)
        assertThat(replacement.file).isEqualTo(File("two"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(second.owner).isSameInstanceAs(replacement)
        second.finishRelease(replacement)
        assertThat(second.owner).isNull()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun pauseResumeRestartFromBeginning() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val attachedRestarts = driver.restarts

        controller.pause()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
        assertThat(driver.pauses).isEqualTo(1)

        controller.resume()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.restarts).isEqualTo(attachedRestarts + 1)

        controller.restart()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.restarts).isEqualTo(attachedRestarts + 2)
    }

    @Test
    fun pauseFailureStopsImmediatelyWhenCleanupSucceeds() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failPause = true

        var thrown: RuntimeException? = null
        try {
            controller.pause()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("pause boom")
        // Hide/background path: best-effort release succeeded, nothing left running.
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        assertThat(driver.releases).isEqualTo(1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun pauseFailureRetainsHandleWhenCleanupAlsoFails() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failPause = true
        driver.failNextRelease = RuntimeException("pause cleanup boom")

        var thrown: RuntimeException? = null
        try {
            controller.pause()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("pause boom")
        assertThat(thrown?.suppressed?.map { it.message }).contains("pause cleanup boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owned).isTrue()
        assertThat(driver.playing).isTrue()

        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.playing).isFalse()
    }

    @Test
    fun resumeFailureStopsPartialStartWhenCleanupSucceeds() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        controller.pause()
        driver.failNextRestart = RuntimeException("resume boom")

        var thrown: RuntimeException? = null
        try {
            controller.resume()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("resume boom")
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun resumeFailureRetainsHandleWhenCleanupAlsoFails() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        controller.pause()
        driver.failNextRestart = RuntimeException("resume boom")
        driver.failReleaseOnRestartFailure = RuntimeException("resume cleanup boom")

        var thrown: RuntimeException? = null
        try {
            controller.resume()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("resume boom")
        assertThat(thrown?.suppressed?.map { it.message }).contains("resume cleanup boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owned).isTrue()

        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun restartFailureStopsPartialStartWhenCleanupSucceeds() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRestart = RuntimeException("restart boom")

        var thrown: RuntimeException? = null
        try {
            controller.restart()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("restart boom")
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun restartFailureRetainsHandleWhenCleanupAlsoFails() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRestart = RuntimeException("restart boom")
        driver.failReleaseOnRestartFailure = RuntimeException("restart cleanup boom")

        var thrown: RuntimeException? = null
        try {
            controller.restart()
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertThat(thrown?.message).isEqualTo("restart boom")
        assertThat(thrown?.suppressed?.map { it.message }).contains("restart cleanup boom")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owned).isTrue()

        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun replaceDriverReleasesPreviousAndResetsToIdle() {
        val first = FakeDriver()
        val controller = StandbyMediaPlaybackController(first)
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        val second = FakeDriver()

        controller.replaceDriverForTest(second)

        assertThat(first.releases).isEqualTo(1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        controller.attach(File("two"), isVideo = true, isAnimated = false)
        assertThat(second.opens).hasSize(1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun asyncPreparationStartsOnlyAfterPreparedAndPlaysOnlyAfterRendering() {
        val driver = AsyncDriver()
        val states = mutableListOf<StandbyPlaybackState>()
        val controller = StandbyMediaPlaybackController(driver, onStateChanged = { states.add(it) })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val request = driver.opens.single()

        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.rendering(request)
        assertThat(driver.restarts).isEqualTo(0)
        driver.prepared(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        assertThat(driver.events).containsExactly("open", "restart").inOrder()
        driver.rendering(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(states).containsExactly(
            StandbyPlaybackState.PREPARING,
            StandbyPlaybackState.STARTING,
            StandbyPlaybackState.PLAYING,
        ).inOrder()
    }

    @Test
    fun hideDuringPreparationWaitsForCloseAndRejectsOldCallbacks() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Throwable>()
        val controller = StandbyMediaPlaybackController(driver, onError = { _, _, cause -> errors.add(cause) })
        val file = File("same")
        controller.attach(file, isVideo = true, isAnimated = false)
        val old = driver.opens.single()
        controller.pause()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(driver.pauses).isEqualTo(0)
        controller.resume()
        driver.prepared(old)
        driver.rendering(old)
        driver.error(old, IllegalStateException("stale"))
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.restarts).isEqualTo(0)
        assertThat(errors).isEmpty()

        old.listener.onReleased(file, old.token + 1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        driver.finishRelease(old)
        val current = driver.opens.last()
        assertThat(current.token).isNotEqualTo(old.token)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.prepared(old)
        driver.error(old, IllegalStateException("stale again"))
        driver.rendering(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        assertThat(errors).isEmpty()
        driver.prepared(current)
        driver.rendering(current)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun replacementWaitsForCloseAndKeepsOnlyLatestSelection() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        val first = driver.opens.single()
        driver.prepared(first)
        driver.rendering(first)
        controller.attach(File("two"), isVideo = true, isAnimated = false)
        controller.attach(File("three"), isVideo = false, isAnimated = true)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(first)
        val next = driver.opens.last()
        assertThat(next.file).isEqualTo(File("three"))
        assertThat(next.isVideo).isFalse()
        assertThat(next.isAnimated).isTrue()
        assertThat(next.token).isNotEqualTo(first.token)
        driver.rendering(first)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.prepared(next)
        driver.rendering(next)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun staticReplacementPreparesWithoutStartingAndNullWaitsForClose() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("video"), isVideo = true, isAnimated = false)
        val video = driver.opens.single()
        controller.attach(File("still"), isVideo = false, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(video)
        val still = driver.opens.last()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.prepared(still)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.READY)
        assertThat(driver.restarts).isEqualTo(0)
        controller.attach(null, isVideo = false, isAnimated = false)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        driver.finishRelease(still)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
    }

    @Test
    fun interruptionRequiresFreshPreparedAndRenderingEvidence() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val request = driver.opens.single()
        driver.prepared(request)
        driver.rendering(request)
        driver.interrupted(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.rendering(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driver.prepared(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        assertThat(driver.restarts).isEqualTo(2)
        driver.rendering(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.opens).hasSize(1)
    }

    @Test
    fun sameFileAndKindDoesNotReopenButChangedKindDoes() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        val file = File("media")
        controller.attach(file, isVideo = true, isAnimated = false)
        val first = driver.opens.single()
        controller.attach(file, isVideo = true, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.releases).isEqualTo(0)
        controller.attach(file, isVideo = false, isAnimated = true)
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(first)
        assertThat(driver.opens).hasSize(2)
        assertThat(driver.opens.last().token).isNotEqualTo(first.token)
    }

    @Test
    fun asyncFailureKeepsOriginalCauseAndRetriesFailedCleanup() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val request = driver.opens.single()
        val original = java.util.concurrent.CancellationException("cancelled")
        val cleanup = IllegalStateException("cleanup")
        driver.error(request, original)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(errors.single().first).isEqualTo(request.file)
        assertThat(errors.single().second).isEqualTo(request.token)
        assertThat(errors.single().third).isSameInstanceAs(original)
        driver.failRelease(request, cleanup)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        request.listener.onReleased(request.file, request.token)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(original.suppressed.toList()).containsExactly(cleanup)
        assertThat(errors.last().third).isSameInstanceAs(original)
        controller.attach(File("other"), isVideo = true, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(driver.releases).isEqualTo(2)
        driver.finishRelease(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.owner).isNull()
    }

    @Test
    fun terminalReleaseWaitsForOldOwnerAndIgnoresStaleCompletion() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("old"), isVideo = true, isAnimated = false)
        val old = driver.opens.single()
        controller.attach(File("new"), isVideo = true, isAnimated = false)
        controller.release()
        old.listener.onReleased(File("wrong"), old.token)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        controller.attach(File("later"), isVideo = true, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
    }

    @Test
    fun replacementDriverWaitsForOldOwnerAndDoesNotTouchProjectionAudio() {
        val first = AsyncDriver()
        val second = AsyncDriver()
        val controller = StandbyMediaPlaybackController(first)
        controller.attach(File("video"), isVideo = true, isAnimated = false)
        val old = first.opens.single()
        driverPrepareAndRender(first, old)
        controller.replaceDriverForTest(second)
        controller.attach(File("gif"), isVideo = false, isAnimated = true)
        assertThat(second.opens).isEmpty()
        first.finishRelease(old)
        val current = second.opens.single()
        driverPrepareAndRender(second, current)
        controller.pause()
        second.finishRelease(current)
        assertThat(first.projectionAudioVolume).isEqualTo(7)
        assertThat(second.projectionAudioVolume).isEqualTo(7)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
    }

    @Test
    fun activeCallbacksRequireBothFileAndToken() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Throwable>()
        val controller = StandbyMediaPlaybackController(driver, onError = { _, _, cause -> errors.add(cause) })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val request = driver.opens.single()
        val wrongFile = File("other")
        val wrongToken = request.token + 1
        request.listener.onPrepared(wrongFile, request.token)
        request.listener.onPrepared(request.file, wrongToken)
        request.listener.onRendering(wrongFile, request.token)
        request.listener.onError(request.file, wrongToken, IllegalStateException("stale"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        assertThat(driver.restarts).isEqualTo(0)
        assertThat(errors).isEmpty()
        driver.prepared(request)
        request.listener.onRendering(request.file, wrongToken)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        driver.rendering(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun restartClosesBeforeReopenAndRequiresFreshRendering() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val old = driver.opens.single()
        driverPrepareAndRender(driver, old)
        controller.restart()
        assertThat(driver.events).containsExactly("open", "restart", "pause", "release").inOrder()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        driver.finishRelease(old)
        val current = driver.opens.last()
        assertThat(current.token).isNotEqualTo(old.token)
        driver.prepared(current)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        driver.rendering(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        driver.rendering(current)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun synchronousCloseCallbackCanOpenReplacementWithoutLosingItsState() {
        val driver = AsyncDriver()
        driver.completeReleaseSynchronously = true
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        controller.attach(File("two"), isVideo = true, isAnimated = false)
        val current = driver.opens.last()
        assertThat(driver.opens).hasSize(2)
        assertThat(driver.owner).isSameInstanceAs(current)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        driverPrepareAndRender(driver, current)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
    }

    @Test
    fun failedCleanupRetryPreservesAndRethrowsTheOriginalCause() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        val original = RuntimeException("open")
        val firstCleanup = RuntimeException("first cleanup")
        val secondCleanup = RuntimeException("second cleanup")
        driver.failNextOpen = original
        driver.failNextRelease = firstCleanup
        try {
            controller.attach(File("clip"), isVideo = true, isAnimated = false)
        } catch (cause: RuntimeException) {
            assertThat(cause).isSameInstanceAs(original)
        }
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        driver.failNextRelease = secondCleanup
        var retryFailure: Throwable? = null
        try {
            controller.release()
        } catch (cause: RuntimeException) {
            retryFailure = cause
        }
        assertThat(retryFailure).isSameInstanceAs(original)
        assertThat(original.suppressed.toList()).containsExactly(firstCleanup, secondCleanup).inOrder()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun observerCanHideBeforeOpenWithoutReleasingAnUnopenedDriver() {
        val driver = AsyncDriver()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.PREPARING) controller.pause()
        })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        assertThat(driver.opens).isEmpty()
        assertThat(driver.releases).isEqualTo(0)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
    }

    @Test
    fun observerCanHideBeforeRestartWithoutPausingAnUnstartedDriver() {
        val driver = AsyncDriver()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.STARTING) controller.pause()
        })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        val request = driver.opens.single()
        driver.prepared(request)
        assertThat(driver.restarts).isEqualTo(0)
        assertThat(driver.pauses).isEqualTo(0)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        driver.finishRelease(request)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
    }

    @Test
    fun stoppingObserverFailureDoesNotPreventReleaseOrReplacement() {
        val driver = FakeDriver()
        var stoppingNotifications = 0
        val controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.STOPPING) {
                stoppingNotifications++
                throw IllegalStateException("observer")
            }
        })
        controller.attach(File("old"), isVideo = true, isAnimated = false)
        controller.attach(File("new"), isVideo = true, isAnimated = false)
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.opens.last().first).isEqualTo(File("new"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        controller.release()
        assertThat(stoppingNotifications).isEqualTo(2)
        assertThat(driver.releases).isEqualTo(2)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun stoppingObserverCancellationStillReleasesAndPreservesCancellation() {
        val driver = FakeDriver()
        val cancellation = java.util.concurrent.CancellationException("observer cancelled")
        val controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.STOPPING) throw cancellation
        })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        var thrown: Throwable? = null
        try {
            controller.release()
        } catch (cause: java.util.concurrent.CancellationException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(driver.releases).isEqualTo(1)
        assertThat(driver.playing).isFalse()
        assertThat(driver.owned).isFalse()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun errorObserverFailureDoesNotReplaceCleanupCauseOrOwner() {
        val driver = FakeDriver()
        val cleanup = IllegalStateException("cleanup")
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
            throw IllegalStateException("observer")
        })
        val old = File("old")
        controller.attach(old, isVideo = true, isAnimated = false)
        driver.failNextRelease = cleanup
        var thrown: Throwable? = null
        try {
            controller.attach(File("new"), isVideo = true, isAnimated = false)
        } catch (cause: RuntimeException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(cleanup)
        assertThat(errors.single().first).isEqualTo(old)
        assertThat(errors.single().third).isSameInstanceAs(cleanup)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.owned).isTrue()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun observerCancellationKeepsOriginalAndDriverFailure() {
        val driver = FakeDriver()
        val driverFailure = IllegalStateException("open")
        val cancellation = java.util.concurrent.CancellationException("observer cancelled")
        driver.failNextOpen = driverFailure
        val controller = StandbyMediaPlaybackController(driver, onError = { _, _, _ -> throw cancellation })
        var thrown: Throwable? = null
        try {
            controller.attach(File("clip"), isVideo = true, isAnimated = false)
        } catch (cause: java.util.concurrent.CancellationException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancellation.suppressed.toList()).containsExactly(driverFailure)
        assertThat(driver.owned).isFalse()
    }

    @Test
    fun stateObserverCancellationPreservesCleanupFailure() {
        val driver = FakeDriver()
        val cleanup = IllegalStateException("cleanup")
        val cancellation = java.util.concurrent.CancellationException("observer cancelled")
        val controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.ERROR) throw cancellation
        })
        controller.attach(File("clip"), isVideo = true, isAnimated = false)
        driver.failNextRelease = cleanup
        var thrown: Throwable? = null
        try {
            controller.release()
        } catch (cause: java.util.concurrent.CancellationException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancellation.suppressed.toList()).containsExactly(cleanup)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun reentrantObserverDoesNotSwallowOrMisattributeDriverFailure() {
        val driver = FakeDriver()
        val cleanup = IllegalStateException("cleanup")
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        var replace = false
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state ->
                if (replace && state == StandbyPlaybackState.STARTING) {
                    driver.failNextRelease = cleanup
                    controller.pause()
                }
            },
        )
        controller.attach(File("old"), isVideo = true, isAnimated = false)
        replace = true
        val newFile = File("new")
        var thrown: Throwable? = null
        try {
            controller.attach(newFile, isVideo = true, isAnimated = false)
        } catch (cause: RuntimeException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(cleanup)
        assertThat(errors.single().first).isEqualTo(newFile)
        assertThat(errors.single().third).isSameInstanceAs(cleanup)
        assertThat(driver.owned).isTrue()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun asyncErrorSelectionWaitsForOldOwnerAndIgnoresStaleCallbacks() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        val next = File("next")
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val original = IllegalStateException("decode")

        driver.error(old, original)
        controller.attach(next, isVideo = true, isAnimated = false)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(driver.opens).hasSize(1)
        assertThat(errors).containsExactly(Triple(old.file, old.token, original))

        driver.finishRelease(old)
        val replacement = driver.opens.last()
        assertThat(driver.opens).hasSize(2)
        assertThat(replacement.file).isEqualTo(next)
        assertThat(replacement.token).isNotEqualTo(old.token)
        driver.error(old, IllegalStateException("stale"))
        driver.prepared(old)
        old.listener.onReleased(old.file, old.token)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        assertThat(driver.owner).isSameInstanceAs(replacement)
        assertThat(errors).hasSize(1)
    }

    @Test
    fun selectionAfterReleasedSourceErrorOpensNewOwner() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        driver.finishRelease(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owner).isNull()

        controller.attach(File("next"), isVideo = true, isAnimated = false)

        assertThat(driver.opens).hasSize(2)
        assertThat(driver.owner?.file).isEqualTo(File("next"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun selectionRetriesFailedCleanupWithoutDroppingReplacement() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val original = IllegalStateException("decode")
        val firstCleanup = IllegalStateException("cleanup one")
        val secondCleanup = IllegalStateException("cleanup two")
        driver.error(old, original)
        driver.failRelease(old, firstCleanup)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        old.listener.onReleased(old.file, old.token)
        assertThat(driver.owner).isSameInstanceAs(old)

        controller.attach(File("next"), isVideo = true, isAnimated = false)
        assertThat(driver.releases).isEqualTo(2)
        assertThat(driver.opens).hasSize(1)
        driver.failRelease(old, secondCleanup)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(original.suppressed.toList()).containsExactly(firstCleanup, secondCleanup).inOrder()
        assertThat(errors.map { it.first to it.second }).containsExactly(
            old.file to old.token, old.file to old.token, old.file to old.token,
        )
        assertThat(errors.map { it.third }).containsExactly(original, original, original)

        controller.attach(File("next"), isVideo = true, isAnimated = false)
        assertThat(driver.releases).isEqualTo(3)
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(old)
        assertThat(driver.owner?.file).isEqualTo(File("next"))
        assertThat(driver.opens).hasSize(2)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun cleanupOnlyFailureRetainsFirstCauseAcrossRetries() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        controller.attach(File("next"), isVideo = false, isAnimated = false)
        val first = IllegalStateException("first cleanup")
        val second = IllegalStateException("second cleanup")
        driver.failRelease(old, first)
        controller.attach(File("next"), isVideo = false, isAnimated = false)
        driver.failRelease(old, second)

        assertThat(errors.map { it.third }).containsExactly(first, first)
        assertThat(errors.map { it.first to it.second }).containsExactly(old.file to old.token, old.file to old.token)
        assertThat(first.suppressed.toList()).containsExactly(second)
        assertThat(driver.opens).hasSize(1)
        controller.attach(File("next"), isVideo = false, isAnimated = false)
        driver.finishRelease(old)
        assertThat(driver.owner?.file).isEqualTo(File("next"))
    }

    @Test
    fun terminalReleaseDiscardsPendingSelectionEvenAfterFailure() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        controller.attach(File("next"), isVideo = true, isAnimated = false)
        controller.release()
        controller.attach(File("ignored"), isVideo = false, isAnimated = false)
        driver.failRelease(old, IllegalStateException("cleanup"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.opens).hasSize(1)

        controller.release()
        driver.finishRelease(old)
        controller.attach(File("ignored"), isVideo = false, isAnimated = false)
        driver.error(old, IllegalStateException("stale"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.owner).isNull()
    }

    @Test
    fun stoppingObserverSelectionSurvivesSourceErrorAndPause() {
        val driver = AsyncDriver()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.STOPPING) {
                controller.attach(File("next"), isVideo = true, isAnimated = false)
                controller.pause()
            }
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        assertThat(driver.opens).hasSize(1)
        driver.finishRelease(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
        assertThat(driver.owner).isNull()

        controller.resume()
        assertThat(driver.owner?.file).isEqualTo(File("next"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun detachDuringFailedOwnerCleanupCancelsPendingSelection() {
        val driver = AsyncDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        controller.attach(File("next"), isVideo = false, isAnimated = false)
        controller.attach(null, isVideo = false, isAnimated = false)
        driver.finishRelease(old)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.owner).isNull()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
    }

    @Test
    fun releasedErrorStateObserverCanSelectNewOwnerReentrantly() {
        val driver = AsyncDriver()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.ERROR) {
                controller.attach(File("next"), isVideo = true, isAnimated = false)
            }
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        driver.finishRelease(old)
        val replacement = driver.opens.last()
        assertThat(driver.opens).hasSize(2)
        assertThat(replacement.file).isEqualTo(File("next"))
        old.listener.onReleaseFailed(old.file, old.token, IllegalStateException("stale cleanup"))
        assertThat(driver.owner).isSameInstanceAs(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun sourceErrorObserverCanSelectAfterSynchronousCloseWithoutLosingOwner() {
        val driver = AsyncDriver().apply { completeReleaseSynchronously = true }
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
            controller.attach(File("next"), isVideo = true, isAnimated = false)
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val original = IllegalStateException("decode")
        driver.error(old, original)

        assertThat(errors).containsExactly(Triple(old.file, old.token, original))
        assertThat(driver.opens).hasSize(2)
        val replacement = driver.opens.last()
        assertThat(replacement.file).isEqualTo(File("next"))
        driver.error(old, IllegalStateException("stale"))
        assertThat(driver.owner).isSameInstanceAs(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun synchronousCleanupFailureObserverDefersRetryUntilReleaseReturns() {
        val driver = AsyncDriver().apply {
            failNextReleaseSynchronously = IllegalStateException("cleanup")
            completeReleaseSynchronously = true
        }
        val cleanup = checkNotNull(driver.failNextReleaseSynchronously)
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state ->
                if (state == StandbyPlaybackState.ERROR) {
                    controller.attach(File("next"), isVideo = true, isAnimated = false)
                }
            },
        )
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val original = IllegalStateException("decode")
        driver.error(old, original)

        assertThat(driver.events).containsExactly("open", "release", "release", "open").inOrder()
        assertThat(driver.owner?.file).isEqualTo(File("next"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        assertThat(original.suppressed.toList()).containsExactly(cleanup)
        assertThat(errors.map { it.first to it.second }).containsExactly(old.file to old.token, old.file to old.token)
        assertThat(errors.map { it.third }).containsExactly(original, original)
    }

    @Test
    fun synchronousCleanupCallbackObserverCancellationIsNotSwallowed() {
        val cleanup = IllegalStateException("cleanup")
        val cancellation = java.util.concurrent.CancellationException("observer")
        val driver = AsyncDriver().apply {
            failNextReleaseSynchronously = cleanup
            completeReleaseSynchronously = true
        }
        var cancel = true
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state -> if (cancel && state == StandbyPlaybackState.ERROR) throw cancellation },
        )
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val thrown = try {
            controller.attach(File("next"), isVideo = false, isAnimated = false)
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancellation.suppressed.toList()).containsExactly(cleanup)
        assertThat(cleanup.suppressed.toList()).doesNotContain(cancellation)
        assertThat(errors).containsExactly(Triple(old.file, old.token, cleanup))
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        cancel = false
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun asyncSourceErrorDoesNotSwallowSynchronousCleanupObserverCancellation() {
        val original = IllegalStateException("decode")
        val cleanup = IllegalStateException("cleanup")
        val cancellation = java.util.concurrent.CancellationException("observer")
        val driver = AsyncDriver().apply {
            failNextReleaseSynchronously = cleanup
            completeReleaseSynchronously = true
        }
        var cancel = true
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state -> if (cancel && state == StandbyPlaybackState.ERROR) throw cancellation },
        )
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val thrown = try {
            driver.error(old, original)
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancellation.suppressed.toList()).containsExactly(original)
        assertThat(original.suppressed.toList()).containsExactly(cleanup)
        assertThat(original.suppressed.toList()).doesNotContain(cancellation)
        assertThat(errors).containsExactly(
            Triple(old.file, old.token, original), Triple(old.file, old.token, original),
        )
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        cancel = false
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun thrownCleanupAndObserverCancellationStillReportOriginalOwner() {
        for (sourceError in listOf(false, true)) {
            val original = IllegalStateException("decode")
            val cleanup = IllegalStateException("cleanup")
            val cancellation = java.util.concurrent.CancellationException("observer")
            val driver = AsyncDriver().apply {
                throwNextRelease = cleanup
                completeReleaseSynchronously = true
            }
            val errors = mutableListOf<Triple<File, Long, Throwable>>()
            var cancel = true
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
                onStateChanged = { state -> if (cancel && state == StandbyPlaybackState.ERROR) throw cancellation },
            )
            controller.attach(File("old"), isVideo = false, isAnimated = false)
            val old = driver.opens.single()
            val thrown = try {
                if (sourceError) driver.error(old, original) else {
                    controller.attach(File("next"), isVideo = false, isAnimated = false)
                }
                null
            } catch (cause: java.util.concurrent.CancellationException) {
                cause
            }
            val expected = if (sourceError) original else cleanup
            assertThat(thrown).isSameInstanceAs(cancellation)
            assertThat(errors).containsExactly(Triple(old.file, old.token, expected))
            assertThat(cancellation.suppressed.toList()).contains(expected)
            assertThat(driver.owner).isSameInstanceAs(old)
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
            cancel = false
            controller.release()
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        }
    }

    @Test
    fun thrownCleanupFailureObserverRetriesWithoutLosingNewOwnerOrCause() {
        val cleanup = IllegalStateException("cleanup")
        val driver = AsyncDriver().apply {
            throwNextRelease = cleanup
            completeReleaseSynchronously = true
        }
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state ->
                if (state == StandbyPlaybackState.ERROR) {
                    controller.attach(File("latest"), isVideo = true, isAnimated = false)
                }
            },
        )
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val thrown = try {
            controller.attach(File("next"), isVideo = false, isAnimated = false)
            null
        } catch (cause: IllegalStateException) {
            cause
        }
        assertThat(thrown).isSameInstanceAs(cleanup)
        assertThat(errors).containsExactly(Triple(old.file, old.token, cleanup))
        assertThat(driver.events).containsExactly("open", "release", "release", "open").inOrder()
        assertThat(driver.owner?.file).isEqualTo(File("latest"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun repeatedFailureObserverSelectionRetriesOnceAndKeepsPendingOwner() {
        val cleanup = IllegalStateException("cleanup")
        val driver = AsyncDriver().apply {
            persistentReleaseFailure = cleanup
            completeReleaseSynchronously = true
        }
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.STOPPING || state == StandbyPlaybackState.ERROR) {
                controller.attach(File("next"), isVideo = true, isAnimated = false)
            }
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val original = IllegalStateException("decode")
        driver.error(old, original)
        assertThat(driver.releases).isEqualTo(2)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(original.suppressed.toList()).containsExactly(cleanup)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)

        driver.persistentReleaseFailure = null
        controller.attach(File("next"), isVideo = true, isAnimated = false)
        assertThat(driver.releases).isEqualTo(3)
        assertThat(driver.owner?.file).isEqualTo(File("next"))
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun terminalReleaseInsideSynchronousFailureDoesNotOpenSelection() {
        val driver = AsyncDriver().apply {
            failNextReleaseSynchronously = IllegalStateException("cleanup")
            completeReleaseSynchronously = true
        }
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (state == StandbyPlaybackState.ERROR) {
                controller.release()
                controller.attach(File("ignored"), isVideo = true, isAnimated = false)
            }
        })
        controller.attach(File("old"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.error(old, IllegalStateException("decode"))
        assertThat(driver.releases).isEqualTo(2)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.owner).isNull()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        controller.release()
        assertThat(driver.releases).isEqualTo(2)
    }

    @Test
    fun synchronousPreparedObserverCancellationReleasesWithoutSourceError() {
        for (video in listOf(false, true)) {
            val driver = FakeDriver()
            val cancellation = java.util.concurrent.CancellationException("observer")
            val errors = mutableListOf<Triple<File, Long, Throwable>>()
            var cancel = true
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
                onStateChanged = { state ->
                    val target = if (video) StandbyPlaybackState.STARTING else StandbyPlaybackState.READY
                    if (cancel && state == target) throw cancellation
                },
            )
            val thrown = try {
                controller.attach(File("one"), isVideo = video, isAnimated = false)
                null
            } catch (cause: java.util.concurrent.CancellationException) {
                cause
            }
            assertThat(thrown).isSameInstanceAs(cancellation)
            assertThat(errors).isEmpty()
            assertThat(driver.owned).isFalse()
            assertThat(driver.releases).isEqualTo(1)
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
            cancel = false
            controller.attach(File("two"), isVideo = false, isAnimated = false)
            assertThat(driver.owned).isTrue()
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.READY)
            controller.release()
            assertThat(driver.owned).isFalse()
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        }
    }

    @Test
    fun synchronousRenderingObserverCancellationPropagatesAfterRelease() {
        val driver = FakeDriver()
        val cancellation = java.util.concurrent.CancellationException("render observer")
        val errors = mutableListOf<Throwable>()
        val controller = StandbyMediaPlaybackController(
            driver,
            onError = { _, _, cause -> errors.add(cause) },
            onStateChanged = { state -> if (state == StandbyPlaybackState.PLAYING) throw cancellation },
        )
        val caught = try {
            controller.attach(File("one"), isVideo = true, isAnimated = false)
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(errors).isEmpty()
        assertThat(driver.owned).isFalse()
        assertThat(driver.playing).isFalse()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun asynchronousPreparedObserverCancellationWaitsForCleanupAndAllowsReplacement() {
        for (video in listOf(false, true)) {
            val driver = AsyncDriver()
            val cancellation = java.util.concurrent.CancellationException("observer")
            val errors = mutableListOf<Triple<File, Long, Throwable>>()
            val controller = StandbyMediaPlaybackController(
                driver,
                onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
                onStateChanged = { state ->
                    val target = if (video) StandbyPlaybackState.STARTING else StandbyPlaybackState.READY
                    if (state == target) throw cancellation
                },
            )
            controller.attach(File("one"), isVideo = video, isAnimated = false)
            val old = driver.opens.single()
            val caught = dispatchStandbyAsyncEvent(controller, old.file, old.token) { driver.prepared(old) }
            assertThat(caught).isSameInstanceAs(cancellation)
            assertThat(errors).isEmpty()
            assertThat(driver.restarts).isEqualTo(0)
            assertThat(driver.owner).isSameInstanceAs(old)
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
            controller.attach(File("two"), isVideo = false, isAnimated = false)
            assertThat(driver.opens).hasSize(1)
            driver.finishRelease(old)
            val replacement = driver.opens.last()
            assertThat(replacement.file).isEqualTo(File("two"))
            assertThat(driver.owner).isSameInstanceAs(replacement)
            old.listener.onObserverCancelled(old.file, old.token, cancellation)
            assertThat(driver.owner).isSameInstanceAs(replacement)
            controller.release()
            driver.finishRelease(replacement)
            assertThat(driver.owner).isNull()
            assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
            assertThat(errors).isEmpty()
        }
    }

    @Test
    fun synchronousNewOwnerCancellationPropagatesThroughAsyncReleaseCallback() {
        val first = AsyncDriver()
        val second = FakeDriver()
        val cancellation = java.util.concurrent.CancellationException("new observer")
        val errors = mutableListOf<Throwable>()
        val controller = StandbyMediaPlaybackController(
            first,
            onError = { _, _, cause -> errors.add(cause) },
            onStateChanged = { state -> if (state == StandbyPlaybackState.READY) throw cancellation },
        )
        controller.attach(File("one"), isVideo = true, isAnimated = false)
        val old = first.opens.single()
        controller.replaceDriverForTest(second)
        controller.attach(File("two"), isVideo = false, isAnimated = false)
        val caught = dispatchStandbyAsyncEvent(controller, old.file, old.token) { first.finishRelease(old) }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(first.owner).isNull()
        assertThat(second.owned).isFalse()
        assertThat(second.opens).hasSize(1)
        assertThat(errors).isEmpty()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun observerCancellationKeepsFailedCleanupOwnerUntilRetryCompletes() {
        val driver = AsyncDriver()
        val cancellation = java.util.concurrent.CancellationException("observer")
        val cleanup = IllegalStateException("cleanup")
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(
            driver,
            onError = { file, token, cause -> errors.add(Triple(file, token, cause)) },
            onStateChanged = { state -> if (state == StandbyPlaybackState.READY) throw cancellation },
        )
        controller.attach(File("one"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        driver.throwNextRelease = cleanup
        val caught = dispatchStandbyAsyncEvent(controller, old.file, old.token) { driver.prepared(old) }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(errors).containsExactly(Triple(old.file, old.token, cleanup))
        assertThat(driver.owner).isSameInstanceAs(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.attach(File("two"), isVideo = false, isAnimated = false)
        assertThat(driver.opens).hasSize(1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STOPPING)
        driver.finishRelease(old)
        val replacement = driver.opens.last()
        assertThat(replacement.file).isEqualTo(File("two"))
        assertThat(driver.owner).isSameInstanceAs(replacement)
        controller.release()
        driver.finishRelease(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun cancelledOldCallbackDoesNotCloseReentrantReplacementOwner() {
        val driver = AsyncDriver().apply { completeReleaseSynchronously = true }
        val cancellation = java.util.concurrent.CancellationException("old observer")
        val errors = mutableListOf<Throwable>()
        lateinit var controller: StandbyMediaPlaybackController
        controller = StandbyMediaPlaybackController(
            driver,
            onError = { _, _, cause -> errors.add(cause) },
            onStateChanged = { state ->
                if (state == StandbyPlaybackState.READY) {
                    controller.attach(File("two"), isVideo = false, isAnimated = false)
                    throw cancellation
                }
            },
        )
        controller.attach(File("one"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val caught = dispatchStandbyAsyncEvent(controller, old.file, old.token) { driver.prepared(old) }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(driver.opens).hasSize(2)
        val replacement = driver.opens.last()
        assertThat(replacement.file).isEqualTo(File("two"))
        assertThat(driver.owner).isSameInstanceAs(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
        assertThat(errors).isEmpty()
        controller.release()
        assertThat(driver.owner).isNull()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun sourceCancellationStillReportsOriginalFileTokenAndCause() {
        val driver = AsyncDriver()
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        controller.attach(File("one"), isVideo = false, isAnimated = false)
        val old = driver.opens.single()
        val sourceFailure = java.util.concurrent.CancellationException("decode")
        val caught = dispatchStandbyAsyncEvent(controller, old.file, old.token) { driver.error(old, sourceFailure) }
        assertThat(caught).isNull()
        assertThat(errors).containsExactly(Triple(old.file, old.token, sourceFailure))
        assertThat(driver.owner).isSameInstanceAs(old)
        driver.finishRelease(old)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        controller.attach(File("two"), isVideo = false, isAnimated = false)
        val replacement = driver.opens.last()
        assertThat(replacement.file).isEqualTo(File("two"))
        controller.release()
        driver.finishRelease(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun synchronousDriverCancellationStillReportsSourceFailure() {
        val driver = FakeDriver()
        val sourceFailure = java.util.concurrent.CancellationException("start failed")
        driver.failNextRestart = sourceFailure
        val errors = mutableListOf<Triple<File, Long, Throwable>>()
        val controller = StandbyMediaPlaybackController(driver, onError = { file, token, cause ->
            errors.add(Triple(file, token, cause))
        })
        val source = File("video")
        val caught = try {
            controller.attach(source, isVideo = true, isAnimated = false)
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(caught).isSameInstanceAs(sourceFailure)
        assertThat(errors.single().first).isEqualTo(source)
        assertThat(errors.single().second).isGreaterThan(0L)
        assertThat(errors.single().third).isSameInstanceAs(sourceFailure)
        assertThat(driver.owned).isFalse()
        controller.release()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun preparingObserverCancellationDoesNotLeaveUnopenedGhost() {
        val driver = AsyncDriver()
        val cancellation = java.util.concurrent.CancellationException("preparing")
        var cancel = true
        val controller = StandbyMediaPlaybackController(driver, onStateChanged = { state ->
            if (cancel && state == StandbyPlaybackState.PREPARING) throw cancellation
        })
        val caught = try {
            controller.attach(File("one"), isVideo = false, isAnimated = false)
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(driver.opens).isEmpty()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.IDLE)
        cancel = false
        controller.attach(File("two"), isVideo = false, isAnimated = false)
        val replacement = driver.opens.single()
        controller.release()
        driver.finishRelease(replacement)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    private fun driverPrepareAndRender(driver: AsyncDriver, request: AsyncDriver.Request) {
        driver.prepared(request)
        driver.rendering(request)
    }
}
