package me.trinitrix.mirax.ui.standby.runtime

/**
 * Parallax input source owned by the Android layer.
 *
 * The controller only calls [start], [stop], and [reset]; it never reads
 * system animation preferences or writes system settings.
 */
interface StandbyParallaxSource {
    /** Starts delivering tilt/pointer samples. */
    fun start()

    /** Stops delivering samples. */
    fun stop()

    /** Clears smoothed offsets back to zero. */
    fun reset()
}

/**
 * OLED drift source owned by the Android layer.
 *
 * Same contract as [StandbyParallaxSource]: lifecycle-scoped only.
 */
interface StandbyOledDriftSource {
    /** Starts advancing drift offsets. */
    fun start()

    /** Stops advancing drift offsets. */
    fun stop()

    /** Clears drift offsets back to zero. */
    fun resetOffsets()
}

/**
 * Single pure lifecycle controller for standby sensors and OLED drift.
 *
 * On any non-[StandbyVisibilityState.Visible] state (including
 * [StandbyVisibilityState.Projecting]) it stops and resets both sources so
 * no standby work runs while invisible; on return to [Visible] it restarts
 * them. It holds no reference to the stream decoder or audio path, so
 * projection decoding stays unaffected (spec S36-S37, issue 07). It reads no
 * animation preference and writes no system setting; all effects flow
 * through the injected [parallax] and [drift] fakes in tests.
 *
 * @param parallax parallax sensor handle.
 * @param drift OLED drift handle.
 */
class StandbySensorController(
    private val parallax: StandbyParallaxSource,
    private val drift: StandbyOledDriftSource,
) {
    private var holding = false

    /**
     * Applies a visibility transition.
     *
     * Idempotent: repeated calls with the same effective gate cause no
     * extra source calls.
     *
     * @param state new lifecycle visibility.
     */
    fun onVisibilityChanged(state: StandbyVisibilityState) {
        val want = StandbyMotionGate.shouldHoldSensors(state)
        if (want == holding) return
        holding = want
        if (want) {
            parallax.start()
            drift.start()
        } else {
            parallax.stop()
            parallax.reset()
            drift.stop()
            drift.resetOffsets()
        }
    }

    /**
     * Returns true while sources are held.
     *
     * @return current hold state.
     */
    fun isHolding(): Boolean = holding
}
