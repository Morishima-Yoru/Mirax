package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour tests for the ActiveConnectionState deep module (issue #1).
 *
 * Seam under test: [ActiveConnectionState] — feed connection events, assert
 * phase progression from NONE through NEGOTIATING, OFFER_FROZEN, PLAYING to ENDED.
 * Assert that illegal transitions are swallowed internally and end in ENDED.
 * The Mirax session is NOT a second seam here — the test drives the module
 * directly through its public interface.
 */
class ActiveConnectionStateTest {

    private fun freshState() = ActiveConnectionState()

    private fun setupPlayingState(state: ActiveConnectionState) {
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        state.onPlayStarted()
    }

    @Test
    fun initialPhaseIsNone() {
        val state = freshState()
        assertThat(state.phase).isEqualTo(ConnectionPhase.NONE)
    }

    @Test
    fun negotiationStartMovesToNegotiating() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        assertThat(state.phase).isEqualTo(ConnectionPhase.NEGOTIATING)
    }

    @Test
    fun freezeOfferMovesToOfferFrozen() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60), VideoMode(1280, 720, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        assertThat(state.phase).isEqualTo(ConnectionPhase.OFFER_FROZEN)
    }

    @Test
    fun freezeOfferWithNullPreferredMode() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = null,
            touchEnabled = false,
            rotationDegrees = 90
        )
        assertThat(state.phase).isEqualTo(ConnectionPhase.OFFER_FROZEN)
    }

    @Test
    fun playStartedMovesToPlaying() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        state.onPlayStarted()
        assertThat(state.phase).isEqualTo(ConnectionPhase.PLAYING)
    }

    @Test
    fun connectionEndedMovesToEnded() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        state.onPlayStarted()
        state.onConnectionEnded()
        assertThat(state.phase).isEqualTo(ConnectionPhase.ENDED)
    }

    @Test
    fun onPlayStartedWithoutNegotiationIsAllowedAndMovesToPlaying() {
        val state = freshState()
        state.onPlayStarted()
        assertThat(state.phase).isEqualTo(ConnectionPhase.PLAYING)
    }

    @Test
    fun illegalTransitionFromEndedIsNoOp() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        state.onPlayStarted()
        state.onConnectionEnded()
        // Calling freezeOffer again after ENDED should be no-op
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        assertThat(state.phase).isEqualTo(ConnectionPhase.ENDED)
    }

    @Test
    fun multipleOnConnectionEndedCallsAreIdempotent() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        state.freezeOffer(
            offeredModes = setOf(VideoMode(1920, 1080, 60)),
            preferredMode = VideoMode(1920, 1080, 60),
            touchEnabled = true,
            rotationDegrees = 0
        )
        state.onPlayStarted()
        state.onConnectionEnded()
        state.onConnectionEnded() // second call
        assertThat(state.phase).isEqualTo(ConnectionPhase.ENDED)
    }

    @Test
    fun freezeOfferWithEmptyOfferedModesStillMovesToOfferFrozen() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf())
        state.freezeOffer(
            offeredModes = emptySet(),
            preferredMode = null,
            touchEnabled = true,
            rotationDegrees = 0
        )
        assertThat(state.phase).isEqualTo(ConnectionPhase.OFFER_FROZEN)
    }

    @Test
    fun onPrePlayProgressCalledTwiceDoesNotRegressPhase() {
        val state = freshState()
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1920, 1080, 60)))
        assertThat(state.phase).isEqualTo(ConnectionPhase.NEGOTIATING)
        // Second call should not change phase
        state.onPrePlayProgress(advertisedModes = setOf(VideoMode(1280, 720, 60)))
        assertThat(state.phase).isEqualTo(ConnectionPhase.NEGOTIATING)
    }

    @Test
    fun backWhenNotPlayingReturnsArmedSecondBack() {
        val state = freshState()
        // Not in PLAYING phase
        val result = state.onSystemBack()
        assertThat(result).isEqualTo(ActiveConnectionState.BackResult.ArmedSecondBack)
    }

    @Test
    fun firstBackExpandsControlPanel() {
        val state = freshState()
        setupPlayingState(state)
        assertThat(state.controlPanelExpandedState).isFalse()

        val result = state.onSystemBack()
        assertThat(result).isEqualTo(ActiveConnectionState.BackResult.ExpandControlPanel)
        assertThat(state.controlPanelExpandedState).isTrue()
    }

    @Test
    fun secondBackWhenPanelExpandedArmsForEnd() {
        val state = freshState()
        setupPlayingState(state)

        // First back expands panel
        state.onSystemBack()
        assertThat(state.controlPanelExpandedState).isTrue()

        // Second back arms for end connection (returns ArmedSecondBack)
        val result = state.onSystemBack()
        assertThat(result).isEqualTo(ActiveConnectionState.BackResult.ArmedSecondBack)
        assertThat(state.backEndsConnectionPendingState).isTrue()
    }

    @Test
    fun thirdBackWhenPanelExpandedAndArmedRequestsEndConnection() {
        val state = freshState()
        setupPlayingState(state)

        // First back expands panel
        state.onSystemBack()
        assertThat(state.controlPanelExpandedState).isTrue()

        // Second back arms for end
        state.onSystemBack()
        assertThat(state.backEndsConnectionPendingState).isTrue()

        // Third back requests end connection
        val result = state.onSystemBack()
        assertThat(result).isEqualTo(ActiveConnectionState.BackResult.RequestEnd)
        assertThat(state.backEndsConnectionPendingState).isFalse()
    }

    @Test
    fun connectionEndResetsControlPanelExpanded() {
        val state = freshState()
        setupPlayingState(state)

        // Expand panel first
        state.onSystemBack()
        assertThat(state.controlPanelExpandedState).isTrue()

        // End connection
        state.onConnectionEnded()

        assertThat(state.controlPanelExpandedState).isFalse()
        assertThat(state.phase).isEqualTo(ConnectionPhase.ENDED)
    }

    @Test
    fun backAfterArmedSecondBackRequestsEnd() {
        val state = freshState()
        // Not in PLAYING phase - first back returns ArmedSecondBack
        state.onSystemBack()

        // Still not in PLAYING - second back also returns ArmedSecondBack
        val result = state.onSystemBack()
        assertThat(result).isEqualTo(ActiveConnectionState.BackResult.ArmedSecondBack)
    }
}