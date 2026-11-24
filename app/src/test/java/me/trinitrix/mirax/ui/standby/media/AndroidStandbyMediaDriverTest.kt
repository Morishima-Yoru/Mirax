package me.trinitrix.mirax.ui.standby.media

import android.graphics.drawable.AnimatedImageDrawable
import com.google.common.truth.Truth.assertThat
import java.io.File
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM checks for file boundaries and the shared framing policy.
 * No Android decoder or device runs.
 */
class AndroidStandbyMediaDriverTest {

    /** Pure bookkeeping identity, not an Android Surface lifetime. */
    private class VideoOutputIdentity

    /** Pure native-owner identity; a successful callback is not device release proof. */
    private class VideoPlayerIdentity

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun storeOwned_directChildAccepted() {
        val storageDir = temporaryFolder.newFolder("standby-media")
        val file = File(storageDir, "active.bin")
        assertThat(AndroidStandbyMediaDriverPolicy.isStoreOwned(file, storageDir)).isTrue()
    }

    @Test
    fun storeOwned_outsideRejected() {
        val storageDir = temporaryFolder.newFolder("standby-media")
        val outside = temporaryFolder.newFile("outside.bin")
        assertThat(AndroidStandbyMediaDriverPolicy.isStoreOwned(outside, storageDir)).isFalse()
    }

    @Test
    fun storeOwned_siblingPrefixRejected() {
        val storageDir = temporaryFolder.newFolder("standby-media")
        val sibling = File(storageDir.parentFile, "standby-media-evil.bin")
        assertThat(AndroidStandbyMediaDriverPolicy.isStoreOwned(sibling, storageDir)).isFalse()
    }

    @Test
    fun requireUsableStoreFile_missingThrows() {
        val storageDir = temporaryFolder.newFolder("standby-media")
        val missing = File(storageDir, "missing.bin")
        var failed = false
        try {
            AndroidStandbyMediaDriverPolicy.requireUsableStoreFile(missing, storageDir)
        } catch (e: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    @Test
    fun gifRepeat_forcesInfinite() {
        assertThat(AnimatedImageDrawable.REPEAT_INFINITE).isEqualTo(-1)
    }

    @Test
    fun geometry_reusesSharedFraming() {
        val direct = StandbyFrameGeometry.computeSrc(
            1920f,
            1080f,
            1080f,
            1920f,
            StandbyFraming.CENTERED,
        )
        val viaPolicy = AndroidStandbyMediaDriverPolicy.computeSourceWindow(
            1920f,
            1080f,
            1080f,
            1920f,
            StandbyFraming.CENTERED,
        )
        assertThat(viaPolicy).isEqualTo(direct)
        assertThat(viaPolicy).isNotNull()
    }

    @Test
    fun surfacePlacement_matchesSharedSourceWindowAcrossResizes() {
        val contentWidth = 1920f
        val contentHeight = 1080f
        val framings = listOf(StandbyFraming(0.9f, 0.1f, 1.7f), StandbyFraming(0f, 1f, 4f))
        for (framing in framings) {
            for ((width, height) in listOf(1080f to 1920f, 1920f to 1080f, 1000f to 1000f, 1768f to 2208f)) {
                val source = checkNotNull(
                    StandbyFrameGeometry.computeSrc(contentWidth, contentHeight, width, height, framing),
                )
                val placement = checkNotNull(
                    StandbyFrameGeometry.computeSurfacePlacement(contentWidth, contentHeight, width, height, framing),
                )
                fun mapX(x: Float) = x * placement.scale + placement.translateX
                fun mapY(y: Float) = y * placement.scale + placement.translateY
                assertThat(mapX(source.left)).isWithin(0.01f).of(0f)
                assertThat(mapX(source.right)).isWithin(0.01f).of(width)
                assertThat(mapY(source.top)).isWithin(0.01f).of(0f)
                assertThat(mapY(source.bottom)).isWithin(0.01f).of(height)
                assertThat(placement.scale).isWithin(0.0001f).of(height / (source.bottom - source.top))
                assertThat(mapX(0f)).isAtMost(0.01f)
                assertThat(mapY(0f)).isAtMost(0.01f)
                assertThat(mapX(contentWidth)).isAtLeast(width - 0.01f)
                assertThat(mapY(contentHeight)).isAtLeast(height - 0.01f)
            }
        }
    }

    @Test
    fun surfacePlacement_fixedAsymmetricCropKeepsUniformNaturalProportions() {
        val framing = StandbyFraming(0.9f, 0.1f, 2f)
        val source = checkNotNull(StandbyFrameGeometry.computeSrc(1920f, 1080f, 200f, 100f, framing))
        assertThat(source.left).isWithin(0.001f).of(960f)
        assertThat(source.top).isWithin(0.001f).of(0f)
        assertThat(source.right).isWithin(0.001f).of(1920f)
        assertThat(source.bottom).isWithin(0.001f).of(480f)
        val placement = checkNotNull(StandbyFrameGeometry.computeSurfacePlacement(1920f, 1080f, 200f, 100f, framing))
        assertThat(placement.scale).isWithin(0.0001f).of(200f / 960f)
        assertThat(placement.translateX).isWithin(0.001f).of(-200f)
        assertThat(placement.translateY).isWithin(0.001f).of(0f)
        assertThat(placement.translateX + 1920f * placement.scale).isWithin(0.001f).of(200f)
        assertThat(placement.translateY + 1080f * placement.scale).isWithin(0.001f).of(225f)
    }

    @Test
    fun surfacePlacement_rejectsInvalidInputsAndNonfiniteBounds() {
        val framing = StandbyFraming.CENTERED
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThat(StandbyFrameGeometry.computeSurfacePlacement(invalid, 1080f, 200f, 100f, framing)).isNull()
            assertThat(StandbyFrameGeometry.computeSurfacePlacement(1920f, invalid, 200f, 100f, framing)).isNull()
            assertThat(StandbyFrameGeometry.computeSurfacePlacement(1920f, 1080f, invalid, 100f, framing)).isNull()
            assertThat(StandbyFrameGeometry.computeSurfacePlacement(1920f, 1080f, 200f, invalid, framing)).isNull()
        }
        assertThat(StandbyFrameGeometry.computeSurfacePlacement(Float.MAX_VALUE, 1f, 100f, 100f, framing)).isNull()
        assertThrows(IllegalArgumentException::class.java) {
            StandbyFraming(Float.NaN, 0.5f, 1f)
        }
    }

    @Test
    fun videoPreparedWithoutDimensions_waitsForSizeGeometryAndRenderingInfo() {
        val output = VideoOutputIdentity()
        val signals = StandbyVideoSignals(output)
        assertThat(signals.onSizeChanged(0, 0)).isFalse()
        assertThat(signals.hasLostSize(0, 0)).isFalse()
        assertThat(signals.onRenderingInfo()).isFalse()
        assertThat(signals.onPrepared()).isTrue()
        assertThat(signals.onPrepared()).isFalse()
        signals.onStarted()
        assertThat(signals.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(signals.onRenderingInfo()).isFalse()
        assertThat(signals.onSizeChanged(640, 480)).isTrue()
        assertThat(signals.hasLostSize(0, 0)).isTrue()
        assertThat(signals.hasLostSize(640, 480)).isFalse()
        assertThat(signals.onSizeChanged(640, 480)).isFalse()
    }

    @Test
    fun controllerStartsPreparedVideoBeforeDimensionsAndWaitsForRenderingEvidence() {
        val driver = object : StandbyPlayerDriver {
            val output = VideoOutputIdentity()
            val signals = StandbyVideoSignals(output)
            lateinit var file: File
            lateinit var listener: StandbyPlayerListener
            var token = 0L
            var starts = 0

            override fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener) {
                this.file = file
                this.token = token
                this.listener = listener
            }

            override fun restart() {
                signals.onStarted()
                starts++
            }

            override fun pause() = Unit
            override fun release(): Boolean = true

            fun prepared() {
                if (signals.onPrepared()) listener.onPrepared(file, token)
            }

            fun size(width: Int, height: Int) {
                if (signals.hasLostSize(width, height)) {
                    listener.onInterrupted(file, token)
                } else if (signals.onSizeChanged(width, height)) {
                    listener.onRendering(file, token)
                }
            }

            fun geometry() {
                if (signals.onOutputGeometryChanged(output, true)) listener.onRendering(file, token)
            }

            fun renderingInfo() {
                if (signals.onRenderingInfo()) listener.onRendering(file, token)
            }
        }
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("video"), isVideo = true, isAnimated = false)
        driver.size(0, 0)
        driver.prepared()
        assertThat(driver.starts).isEqualTo(1)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        driver.geometry()
        driver.renderingInfo()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.STARTING)
        driver.size(640, 480)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        driver.size(0, 0)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PREPARING)
    }

    @Test
    fun videoInfoBeforeGeometry_reportsOnlyWithCurrentUsableOutput() {
        val output = VideoOutputIdentity()
        val signals = StandbyVideoSignals(output)
        assertThat(signals.onSizeChanged(640, 480)).isFalse()
        assertThat(signals.onPrepared()).isTrue()
        signals.onStarted()
        assertThat(signals.onRenderingInfo()).isFalse()
        assertThat(signals.onOutputGeometryChanged(VideoOutputIdentity(), true)).isFalse()
        assertThat(signals.onOutputGeometryChanged(output, false)).isFalse()
        assertThat(signals.onOutputGeometryChanged(output, true)).isTrue()
        assertThat(signals.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(signals.onRenderingInfo()).isFalse()
    }

    @Test
    fun videoGeometryBeforeInfo_doesNotManufactureRenderingEvidence() {
        val output = VideoOutputIdentity()
        val signals = StandbyVideoSignals(output)
        assertThat(signals.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(signals.onSizeChanged(640, 480)).isFalse()
        assertThat(signals.onPrepared()).isTrue()
        signals.onStarted()
        assertThat(signals.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(signals.onRenderingInfo()).isTrue()
        assertThat(signals.onRenderingInfo()).isFalse()
    }

    @Test
    fun freshVideoSignals_doNotInheritThePreviousPlayerEvidence() {
        val oldOutput = VideoOutputIdentity()
        val old = StandbyVideoSignals(oldOutput)
        old.onSizeChanged(640, 480)
        old.onPrepared()
        old.onStarted()
        old.onOutputGeometryChanged(oldOutput, true)
        assertThat(old.onRenderingInfo()).isTrue()

        val output = VideoOutputIdentity()
        val current = StandbyVideoSignals(output)
        assertThat(current.onRenderingInfo()).isFalse()
        current.onPrepared()
        current.onStarted()
        current.onSizeChanged(640, 480)
        assertThat(current.onOutputGeometryChanged(oldOutput, true)).isFalse()
        assertThat(current.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(current.onRenderingInfo()).isTrue()
        assertThat(current.hasLostSize(0, 480)).isTrue()
        assertThat(current.onOutputGeometryChanged(oldOutput, false)).isFalse()
        assertThat(current.onRenderingInfo()).isFalse()

        val nextPlayer = StandbyVideoSignals(output)
        nextPlayer.onPrepared()
        nextPlayer.onStarted()
        assertThat(nextPlayer.onSizeChanged(640, 480)).isFalse()
        assertThat(nextPlayer.onOutputGeometryChanged(output, true)).isFalse()
        assertThat(nextPlayer.onRenderingInfo()).isTrue()
    }

    @Test
    fun surfaceInterruptionRejectsReentrantOwnerOrGenerationChanges() {
        val owner = VideoOutputIdentity()
        assertThat(isUnchangedStandbyOwner(owner, owner, 1L, 1L)).isTrue()
        assertThat(isUnchangedStandbyOwner(owner, VideoOutputIdentity(), 1L, 1L)).isFalse()
        assertThat(isUnchangedStandbyOwner(owner, owner, 1L, 2L)).isFalse()
        assertThat(isUnchangedStandbyOwner(owner, null, 1L, 1L)).isFalse()
    }

    @Test
    fun frameworkCallbackDispatcherPreservesOriginalOwnerAndCancellation() {
        val file = File("old")
        val token = 42L
        val cancellation = java.util.concurrent.CancellationException("observer")
        val cancellations = mutableListOf<Triple<File, Long, java.util.concurrent.CancellationException>>()
        val errors = mutableListOf<Throwable>()
        val listener = object : StandbyPlayerListener {
            override fun onPrepared(file: File, token: Long) = Unit
            override fun onRendering(file: File, token: Long) = Unit
            override fun onInterrupted(file: File, token: Long) { throw cancellation }
            override fun onError(file: File, token: Long, cause: Throwable) {
                errors.add(cause)
                throw cancellation
            }
            override fun onReleased(file: File, token: Long) = Unit
            override fun onReleaseFailed(file: File, token: Long, cause: Throwable) = Unit
            override fun onObserverCancelled(file: File, token: Long, cause: java.util.concurrent.CancellationException) {
                cancellations.add(Triple(file, token, cause))
            }
        }
        val caught = dispatchStandbyEntryEvent(listener, file, token, frameworkCallback = true) {
            listener.onInterrupted(file, token)
        }
        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(cancellations).containsExactly(Triple(file, token, cancellation))
        assertThat(errors).isEmpty()

        val synchronous = try {
            dispatchStandbyEntryEvent(listener, file, token, frameworkCallback = false) {
                listener.onInterrupted(file, token)
            }
            null
        } catch (cause: java.util.concurrent.CancellationException) {
            cause
        }
        assertThat(synchronous).isSameInstanceAs(cancellation)
        assertThat(cancellations).containsExactly(Triple(file, token, cancellation))
        assertThat(errors).isEmpty()

        val platformFailure = IllegalStateException("surface release failed")
        val afterFailure = dispatchStandbyEntryEvent(listener, file, token, frameworkCallback = true) {
            listener.onError(file, token, platformFailure)
        }
        assertThat(afterFailure).isSameInstanceAs(cancellation)
        assertThat(errors).containsExactly(platformFailure)
        assertThat(cancellations).containsExactly(
            Triple(file, token, cancellation), Triple(file, token, cancellation),
        )
    }

    @Test
    fun releaseFenceRequiresOutputInputSourceAndFailureToSettle() {
        assertThat(isStandbyReleaseSettled(true, false, true, true, false)).isTrue()
        assertThat(isStandbyReleaseSettled(false, false, true, true, false)).isFalse()
        assertThat(isStandbyReleaseSettled(true, true, true, true, false)).isFalse()
        assertThat(isStandbyReleaseSettled(true, false, false, true, false)).isFalse()
        assertThat(isStandbyReleaseSettled(true, false, true, false, false)).isFalse()
        assertThat(isStandbyReleaseSettled(true, false, true, true, true)).isFalse()
    }

    @Test
    fun failedVideoReleaseRetainsOriginalPlayerUntilExplicitRetry() {
        val original = VideoPlayerIdentity()
        val replacement = VideoPlayerIdentity()
        var player: VideoPlayerIdentity? = original
        val released = mutableListOf<VideoPlayerIdentity>()
        val failure = IllegalStateException("native release failed")
        val caught = runCatching {
            player = releaseStandbyVideoOwner(player) { throw failure }
        }.exceptionOrNull()
        assertThat(caught).isSameInstanceAs(failure)
        assertThat(player).isSameInstanceAs(original)
        assertThat(isStandbyReleaseSettled(player == null, false, true, true, false)).isFalse()
        player = releaseStandbyVideoOwner(player) { released.add(it) }
        assertThat(player).isNull()
        assertThat(released).containsExactly(original)
        assertThat(released).doesNotContain(replacement)
        assertThat(isStandbyReleaseSettled(player == null, false, true, true, false)).isTrue()
    }

    @Test
    fun staleOutputDoesNotReferenceReplacementButBoundOldOutputStillNeedsRetirement() {
        val old = VideoOutputIdentity()
        val replacement = VideoOutputIdentity()
        assertThat(isStandbyVideoOutputReferenced(old, replacement, replacement)).isFalse()
        assertThat(isStandbyVideoOutputReferenced(old, replacement, old)).isTrue()
        assertThat(isStandbyVideoOutputReferenced(old, old, null)).isTrue()
        assertThat(isStandbyVideoOutputReferenced(old, null, old)).isTrue()
        assertThat(isStandbyVideoOutputReferenced(old, null, null)).isFalse()
    }

    @Test
    fun lateInputSuccessCannotClearFailedNativeCloseOrAuthorizeReplacement() {
        val original = VideoPlayerIdentity()
        var player: VideoPlayerIdentity? = original
        val failure = IllegalStateException("native release failed")
        val caught = runCatching {
            player = releaseStandbyVideoOwner(player) { throw failure }
        }.exceptionOrNull()
        assertThat(caught).isSameInstanceAs(failure)
        assertThat(isStandbyReleaseSettled(player == null, false, true, true, true)).isFalse()
        player = releaseStandbyVideoOwner(player) {}
        assertThat(isStandbyReleaseSettled(player == null, false, true, true, true)).isFalse()
        assertThat(isStandbyReleaseSettled(player == null, false, true, true, false)).isTrue()
        assertThat(isUnchangedStandbyOwner(original, original, 1L, 2L)).isFalse()
        assertThat(isUnchangedStandbyOwner(original, original, 2L, 2L)).isTrue()
    }

    @Test
    fun underlayOwnershipRequiresBothViewAndDriverIdentity() {
        val old = VideoOutputIdentity()
        val replacement = VideoOutputIdentity()
        assertThat(isStandbyViewOwner(old, old, true)).isTrue()
        assertThat(isStandbyViewOwner(old, replacement, true)).isFalse()
        assertThat(isStandbyViewOwner(old, old, false)).isFalse()
        assertThat(isStandbyViewOwner(old, null, true)).isFalse()
    }

    @Test
    fun imageOwnerRelease_stopsDetachesAndForgetsOwner() {
        val events = mutableListOf<String>()
        var owner: String? = "gif"
        owner = releaseStandbyImageOwner(
            owner,
            stop = { events.add("stop:$it") },
            detach = { events.add("detach:$it") },
        )
        assertThat(owner).isNull()
        assertThat(events).containsExactly("stop:gif", "detach:gif").inOrder()
    }

    @Test
    fun imageOwnerRelease_retainsOwnerWhenDetachThrowsAndCanRetry() {
        val failure = IllegalStateException("detach failed")
        val events = mutableListOf<String>()
        var owner: String? = "gif"
        var thrown: Throwable? = null
        try {
            owner = releaseStandbyImageOwner(
                owner,
                stop = { events.add("stop:$it") },
                detach = { throw failure },
            )
        } catch (cause: IllegalStateException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(owner).isEqualTo("gif")
        owner = releaseStandbyImageOwner(
            owner,
            stop = { events.add("stop:$it") },
            detach = { events.add("detach:$it") },
        )
        assertThat(owner).isNull()
        assertThat(events).containsExactly("stop:gif", "stop:gif", "detach:gif").inOrder()
    }

    @Test
    fun controllerRetainsGifOwnerAfterCleanupFailureAndRetries() {
        val driver = object : StandbyPlayerDriver {
            var owner: String? = null
            var failDetach = true
            lateinit var file: File
            lateinit var listener: StandbyPlayerListener
            var token = 0L

            override fun open(file: File, isVideo: Boolean, isAnimated: Boolean, token: Long, listener: StandbyPlayerListener) {
                owner = "gif"
                this.file = file
                this.token = token
                this.listener = listener
                listener.onPrepared(file, token)
            }

            override fun restart() = listener.onRendering(file, token)
            override fun pause() = Unit
            override fun release(): Boolean {
                owner = releaseStandbyImageOwner(
                    owner,
                    stop = {},
                    detach = {
                        if (failDetach) {
                            failDetach = false
                            throw IllegalStateException("detach failed")
                        }
                    },
                )
                return true
            }
        }
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("gif"), isVideo = false, isAnimated = true)
        var thrown: Throwable? = null
        try {
            controller.attach(File("still"), isVideo = false, isAnimated = false)
        } catch (cause: IllegalStateException) {
            thrown = cause
        }
        assertThat(thrown?.message).isEqualTo("detach failed")
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.ERROR)
        assertThat(driver.owner).isEqualTo("gif")
        controller.release()
        assertThat(driver.owner).isNull()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun imageOwnerRelease_retainsOwnerWhenStopThrowsAndCanRetry() {
        val failure = IllegalStateException("stop failed")
        val events = mutableListOf<String>()
        var owner: String? = "gif"
        var thrown: Throwable? = null
        try {
            owner = releaseStandbyImageOwner(
                owner,
                stop = { throw failure },
                detach = { events.add("detach:$it") },
            )
        } catch (cause: IllegalStateException) {
            thrown = cause
        }
        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(owner).isEqualTo("gif")
        assertThat(events).isEmpty()
        owner = releaseStandbyImageOwner(
            owner,
            stop = { events.add("stop:$it") },
            detach = { events.add("detach:$it") },
        )
        assertThat(owner).isNull()
        assertThat(events).containsExactly("stop:gif", "detach:gif").inOrder()
    }

    @Test
    fun storeOwned_parentTraversalRejected() {
        val storageDir = temporaryFolder.newFolder("standby-media")
        val escaped = File(storageDir, "../outside.bin")
        assertThat(AndroidStandbyMediaDriverPolicy.isStoreOwned(escaped, storageDir)).isFalse()
    }
}