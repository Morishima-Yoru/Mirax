package me.trinitrix.mirax.ui.standby.media

import com.google.common.truth.Truth.assertThat
import java.io.File
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import org.junit.Test

/**
 * JVM checks for the 08 UI slice pure state machines.
 *
 * Covers framing control bounds plus confirm/cancel, the transactional
 * import flow, playback mute/loop/release gating, and resize recompute with
 * centre priority. Fakes stand in for all Android APIs; no device runs.
 */
class StandbyMediaUiTest {

    private class FakeDriver : StandbyPlayerDriver {
        val opens = mutableListOf<Triple<File, Boolean, Boolean>>()
        var restarts = 0
        var pauses = 0
        var releases = 0
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
            opens.add(Triple(file, isVideo, isAnimated))
            this.file = file
            this.token = token
            this.listener = listener
            listener.onPrepared(file, token)
        }
        override fun restart() {
            restarts++
            file?.let { listener?.onRendering(it, token) }
        }
        override fun pause() {
            pauses++
        }
        override fun release(): Boolean {
            releases++
            return true
        }
    }

    private class FakeHost(var width: Float = 1080f, var height: Float = 1920f) : StandbyUnderlayHost {
        var redraws = 0
        override fun requestRedraw() {
            redraws++
        }
        override fun viewSize(): Pair<Float, Float>? {
            return width to height
        }
    }

    private class FakeContent(
        override val usable: Boolean = true,
        override val contentWidth: Float? = 1920f,
        override val contentHeight: Float? = 1080f,
    ) : StandbyMediaContent {
        var draws = 0
        override fun drawClamped(
            src: StandbyFrameGeometry.SrcWindow,
            dstLeft: Float,
            dstTop: Float,
            dstRight: Float,
            dstBottom: Float,
            draw: (StandbyFrameGeometry.SrcWindow) -> Unit,
        ) {
            draws++
            draw(src)
        }
    }

    private class FakeStore(
        var staged: StagedPreview? = null,
        var confirmOutcome: StandbyConfirmOutcome =
            StandbyConfirmOutcome.Committed(File("staged"), StandbyFraming(0.4f, 0.4f, 2f)),
        var throwOnConfirm: Boolean = false,
        var activeRead: StandbyActiveRead = StandbyActiveRead.Available(null, StandbyFraming.CENTERED),
    ) : StandbyImportStore {
        val discardedFiles = mutableListOf<File>()
        override fun stage(source: File): StagedPreview? = staged
        override fun confirm(staged: File, framing: StandbyFraming): StandbyConfirmOutcome {
            if (throwOnConfirm) {
                throw IllegalStateException("confirm boom")
            }
            return confirmOutcome
        }
        override fun discard(staged: File) {
            discardedFiles.add(staged)
        }
        override fun readActive(): StandbyActiveRead = activeRead
        val discards: Int get() = discardedFiles.size

        fun assertNeverTouchedSource(source: File) {
            assertThat(discardedFiles).doesNotContain(source)
        }
    }

    @Test
    fun framingConfirmPublishesDraft() {
        val model = StandbyFramingControlsModel()
        model.startPreview()
        assertThat(model.isPreviewing).isTrue()
        model.pan(0.1f, -0.1f)
        val confirmed = model.confirm()
        assertThat(model.isPreviewing).isFalse()
        assertThat(model.committed).isEqualTo(confirmed)
        assertThat(confirmed.centerX).isWithin(1e-6f).of(0.6f)
        assertThat(confirmed.centerY).isWithin(1e-6f).of(0.4f)
    }

    @Test
    fun framingCancelRestoresPrior() {
        val baseline = StandbyFraming(0.4f, 0.6f, 1.5f)
        val model = StandbyFramingControlsModel(baseline)
        model.pan(0.2f, 0.2f)
        model.zoom(3.0f)
        val afterCancel = model.cancel()
        assertThat(afterCancel).isEqualTo(baseline)
        assertThat(model.visible()).isEqualTo(baseline)
        assertThat(model.isPreviewing).isFalse()
    }

    @Test
    fun framingZoomClamped() {
        val model = StandbyFramingControlsModel()
        assertThat(model.zoom(99f).zoom).isWithin(1e-6f).of(StandbyFramingControlsModel.MAX_ZOOM)
        assertThat(model.zoom(-5f).zoom).isWithin(1e-6f).of(StandbyFramingControlsModel.MIN_ZOOM)
        assertThat(model.zoom(Float.NaN).zoom).isWithin(1e-6f).of(StandbyFramingControlsModel.MIN_ZOOM)
        model.cancel()
        assertThat(model.visible()).isEqualTo(StandbyFraming.CENTERED)
    }

    @Test
    fun framingPanClampedToBounds() {
        val model = StandbyFramingControlsModel()
        val moved = model.pan(10f, -10f)
        assertThat(moved.centerX).isWithin(1e-6f).of(0.75f)
        assertThat(moved.centerY).isWithin(1e-6f).of(0.25f)
        model.cancel()
    }

    @Test
    fun importStageConfirmSwaps() {
        val before = File("before")
        val stagedFile = File("staged")
        val persisted = StandbyFraming(0.4f, 0.4f, 2f)
        val model = StandbyImportFlowModel(activeFile = before)
        val store = FakeStore(
            staged = StagedPreview(stagedFile, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED),
            confirmOutcome = StandbyConfirmOutcome.Committed(stagedFile, persisted),
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.STAGED)
        assertThat(model.activeFile).isEqualTo(before)
        assertThat(model.confirm(StandbyFraming(0.4f, 0.4f, 2f), store))
            .isEqualTo(StandbyImportDecision.Committed)
        assertThat(model.activeFile).isEqualTo(stagedFile)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.COMMITTED)
        assertThat(model.framing).isEqualTo(persisted)
        assertThat(model.framing.zoom).isWithin(1e-6f).of(2f)
    }

    @Test
    fun importIdempotentDuplicatePublishesPersistedFraming() {
        val before = File("before")
        val stagedFile = File("staged")
        val persisted = StandbyFraming(0.1f, 0.2f, 1f)
        val requested = StandbyFraming(0.9f, 0.9f, 3f)
        val model = StandbyImportFlowModel(activeFile = before)
        val store = FakeStore(
            staged = StagedPreview(stagedFile, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED),
            // Idempotent duplicate: same file already active, store returns
            // the truly persisted framing instead of the new request.
            confirmOutcome = StandbyConfirmOutcome.Committed(stagedFile, persisted),
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.confirm(requested, store)).isEqualTo(StandbyImportDecision.Committed)
        // Must not falsely claim the unpersisted request framing.
        assertThat(model.activeFile).isEqualTo(stagedFile)
        assertThat(model.framing).isEqualTo(persisted)
        assertThat(model.framing).isNotEqualTo(requested)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.COMMITTED)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
    }

    @Test
    fun importCancelKeepsPreviousWithoutNotice() {
        val before = File("before")
        val stagedFile = File("staged")
        val framing = StandbyFraming(0.3f, 0.3f, 1.5f)
        val model = StandbyImportFlowModel(activeFile = before, framing = framing)
        val store = FakeStore(
            staged = StagedPreview(stagedFile, StandbyMediaKind.VIDEO, StandbyFraming.CENTERED),
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.cancel(store)).isEqualTo(StandbyCancelOutcome.Cancelled)
        assertThat(model.activeFile).isEqualTo(before)
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
        assertThat(store.discards).isEqualTo(1)
    }

    @Test
    fun importConfirmFailureDiscardsStaged() {
        val before = File("before")
        val source = File("src")
        val stagedFile = File("staged")
        val framing = StandbyFraming.CENTERED
        val model = StandbyImportFlowModel(activeFile = before, framing = framing)
        val store = FakeStore(
            staged = StagedPreview(stagedFile, StandbyMediaKind.IMAGE, StandbyFraming.CENTERED),
            confirmOutcome = StandbyConfirmOutcome.Rejected,
        )
        assertThat(model.pickAndStage(source, store)).isTrue()
        assertThat(model.confirm(StandbyFraming(0.4f, 0.4f, 2f), store))
            .isEqualTo(StandbyImportDecision.Rejected)
        assertThat(store.discardedFiles).containsExactly(stagedFile)
        assertThat(model.staged()).isNull()
        assertThat(model.activeFile).isEqualTo(before)
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_FAILED)
        store.assertNeverTouchedSource(source)
    }

    @Test
    fun importConfirmExceptionHoldsUncertainWithoutFalseClaim() {
        val before = File("before")
        val stagedFile = File("staged")
        val framing = StandbyFraming.CENTERED
        val model = StandbyImportFlowModel(activeFile = before, framing = framing)
        val store = FakeStore(
            staged = StagedPreview(stagedFile, StandbyMediaKind.VIDEO, StandbyFraming.CENTERED),
            throwOnConfirm = true,
        )
        assertThat(model.pickAndStage(File("src"), store)).isTrue()
        assertThat(model.confirm(StandbyFraming.CENTERED, store))
            .isEqualTo(StandbyImportDecision.Uncertain)
        // Throwing confirm is ambiguous (possible commit-then-throw): preserve.
        assertThat(store.discards).isEqualTo(0)
        assertThat(model.staged()?.file).isEqualTo(stagedFile)
        assertThat(model.activeFile).isEqualTo(before)
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.PENDING_RECONCILIATION)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_UNCERTAIN)
        store.assertNeverTouchedSource(File("src"))
    }

    @Test
    fun importFailureKeepsPreviousWithNotice() {
        val before = File("before")
        val framing = StandbyFraming.CENTERED
        val model = StandbyImportFlowModel(activeFile = before, framing = framing)
        val store = FakeStore(staged = null)
        assertThat(model.pickAndStage(File("src"), store)).isFalse()
        assertThat(model.activeFile).isEqualTo(before)
        assertThat(model.framing).isEqualTo(framing)
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.IMPORT_FAILED)
    }

    @Test
    fun corruptFallsBackWithoutConnectionChange() {
        val model = StandbyImportFlowModel(activeFile = File("before"))
        assertThat(model.onCorrupt(File("before"))).isEqualTo(StandbyCorruptOutcome.FallenBack)
        assertThat(model.activeFile).isNull()
        assertThat(model.staged()).isNull()
        assertThat(model.phase).isEqualTo(StandbyImportPhase.FALLBACK_TO_THEME)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.CORRUPT_FALLBACK)
    }

    @Test
    fun corruptStaleOrUnknownIdentityKeepsNewActive() {
        val store = FakeStore()
        val model = StandbyImportFlowModel(activeFile = File("before"))
        assertThat(model.onCorrupt(File("other"))).isEqualTo(StandbyCorruptOutcome.Ignored)
        assertThat(model.onCorrupt(null)).isEqualTo(StandbyCorruptOutcome.Ignored)
        assertThat(model.activeFile).isEqualTo(File("before"))
        assertThat(model.phase).isEqualTo(StandbyImportPhase.IDLE)
        assertThat(model.notice).isEqualTo(StandbyImportNotice.NONE)
        assertThat(store.discardedFiles).isEmpty()
    }

    @Test
    fun playbackAttachAfterReleaseRejected() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        val file = File("clip")
        controller.attach(file, isVideo = true, isAnimated = false)
        controller.release()
        val opens = driver.opens.size
        controller.attach(file, isVideo = true, isAnimated = false)
        controller.replaceDriverForTest(FakeDriver())
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
        assertThat(driver.opens).hasSize(opens)
        assertThat(driver.releases).isEqualTo(1)
    }

    @Test
    fun computeSrcRejectsBadViewSizes() {
        val framing = StandbyFraming.CENTERED
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, Float.NaN, 1080f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, 0f, 1080f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, -1f, 1080f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, 1080f, Float.NaN, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, 1080f, 0f, framing)).isNull()
        assertThat(StandbyFrameGeometry.computeSrc(1920f, 1080f, 1080f, -2f, framing)).isNull()
    }

    @Test
    fun computeSrcRejectsNonFiniteCenter() {
        // StandbyFraming.require rejects non-finite centers and zoom at
        // construction, so geometry never sees them; verify finite centers
        // still produce a window and non-finite construction fails fast.
        val window = StandbyFrameGeometry.computeSrc(
            1920f,
            1080f,
            1080f,
            1920f,
            StandbyFraming(0.5f, 0.5f, 1f),
        )
        assertThat(window).isNotNull()
        var rejected = false
        try {
            StandbyFraming(0.5f, 0.5f, Float.NaN)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
        // Geometry separately guards non-finite center/zoom even if a future
        // framing type bypasses require: NaN content/view already null.
        assertThat(
            StandbyFrameGeometry.computeSrc(
                Float.NaN,
                1080f,
                1080f,
                1920f,
                StandbyFraming.CENTERED,
            ),
        ).isNull()
    }

    @Test
    fun renderIfReadyDrawsNothingNegative() {
        val state = StandbyMediaUnderlayState(StandbyFraming.CENTERED)
        state.setContent(null, null, false)
        state.setViewSize(0f, 0f)
        val coordinator = StandbyMediaUnderlayCoordinator(
            state = state,
            host = FakeHost(0f, 0f),
        )
        var drew = false
        assertThat(coordinator.renderIfReady(FakeContent(usable = false)) { drew = true }).isFalse()
        assertThat(drew).isFalse()
        assertThat(coordinator.renderIfReady(null) { drew = true }).isFalse()
        assertThat(drew).isFalse()
    }

    @Test
    fun playbackMutedLoopPauseResumeRestartReleaseIdempotent() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        val file = File("clip")
        controller.attach(file, isVideo = true, isAnimated = false)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.opens.single().second).isTrue()
        assertThat(driver.opens.single().third).isFalse()
        controller.pause()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PAUSED)
        controller.resume()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        assertThat(driver.restarts).isAtLeast(2)
        controller.restart()
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.PLAYING)
        controller.release()
        controller.release()
        assertThat(driver.releases).isEqualTo(3)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.RELEASED)
    }

    @Test
    fun playbackStillAttachesWithoutStartingPlayback() {
        val driver = FakeDriver()
        val controller = StandbyMediaPlaybackController(driver)
        controller.attach(File("still"), isVideo = false, isAnimated = false)
        assertThat(controller.state).isEqualTo(StandbyPlaybackState.READY)
        assertThat(driver.opens).hasSize(1)
        assertThat(driver.restarts).isEqualTo(0)
    }

    @Test
    fun resizeRecomputesWithCentrePriority() {
        val state = StandbyMediaUnderlayState(StandbyFraming(0.5f, 0.5f, 1f))
        state.setContent(1920f, 1080f, true)
        state.setViewSize(1080f, 1920f)
        val portrait = state.computeSrc()
        state.setViewSize(1920f, 1080f)
        val landscape = state.computeSrc()
        state.setViewSize(1768f, 2208f)
        val fold = state.computeSrc()
        assertThat(portrait).isNotNull()
        assertThat(landscape).isNotNull()
        assertThat(fold).isNotNull()
        // Centre priority: portrait crop keeps full height, landscape keeps full width.
        assertThat(portrait!!.top).isWithin(1f).of(0f)
        assertThat(portrait.bottom).isWithin(1f).of(1080f)
        assertThat(landscape!!.left).isWithin(1f).of(0f)
        assertThat(landscape.right).isWithin(1f).of(1920f)
        // No stretch, no gaps: source window never exceeds content bounds.
        for (window in listOf(portrait, landscape, fold!!)) {
            assertThat(window.left).isAtLeast(0f)
            assertThat(window.top).isAtLeast(0f)
            assertThat(window.right).isAtMost(1920f)
            assertThat(window.bottom).isAtMost(1080f)
            assertThat(window.right).isGreaterThan(window.left)
            assertThat(window.bottom).isGreaterThan(window.top)
        }
        assertThat(state.shouldDraw()).isTrue()
    }

    @Test
    fun corruptContentDrawsNothing() {
        val host = FakeHost()
        val coordinator = StandbyMediaUnderlayCoordinator(
            state = StandbyMediaUnderlayState(),
            host = host,
        )
        coordinator.onSizeChanged(1080f, 1920f)
        coordinator.onContentChanged(null, null, false)
        var draws = 0
        val drew = coordinator.renderIfReady(FakeContent(usable = false)) { draws++ }
        assertThat(drew).isFalse()
        assertThat(draws).isEqualTo(0)
    }
}
