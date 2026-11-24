package me.trinitrix.mirax.ui.standby.runtime

import me.trinitrix.mirax.session.ScreenPhase

/**
 * Testable Big Standby lifecycle host seam for issue 07 (S34-S37).
 *
 * The Activity supplies lifecycle visibility and the attached Matter layer.
 * This host answers the pure-policy questions and tracks sensor ownership:
 *
 * - Foreground Big Standby holds keep-awake, including READY and FROZEN, and
 *   releases it as soon as standby leaves, projects, or backgrounds. It never
 *   dims the app and never writes brightness.
 * - System reduced/disabled animation preferences never stop ambient motion,
 *   and FROZEN never unconditionally pauses it (Matter carries the approved
 *   lattice while frozen; Landscape/Hardline keep their own unavailable
 *   visuals). Hidden/background/projecting pause the ambient pipeline.
 * - Matter sensor sources are held only while its layer is attached and the
 *   visibility gate passes; they are released in pairs otherwise.
 * - The incoming stream decoder and audio path are never referenced here, and
 *   idle accounting is never touched.
 *
 * The Activity maps its real lifecycle to [StandbyVisibilityState] and reports
 * whether the Matter foreground is attached, independently of keep-awake.
 *
 * @param sensors Lifecycle controller for parallax and OLED drift sources.
 */
class StandbyRuntimeHost(
    private val sensors: StandbySensorController = StandbySensorController(
        object : StandbyParallaxSource {
            override fun start() = Unit
            override fun stop() = Unit
            override fun reset() = Unit
        },
        object : StandbyOledDriftSource {
            override fun start() = Unit
            override fun stop() = Unit
            override fun resetOffsets() = Unit
        },
    ),
) {
    private var visibility: StandbyVisibilityState = StandbyVisibilityState.Background
    private var matterLayerAvailable = false
    private var phase: ScreenPhase = ScreenPhase.READY
    private var animationPreference: StandbyAnimationPreference = StandbyAnimationPreference.FULL

    init {
        sensors.onVisibilityChanged(visibility)
    }

    /**
     * Reports the real foreground visibility from the Activity lifecycle.
     *
     * @param state Mapped lifecycle visibility.
     * @param phase Current screen phase for keep-awake reporting.
     */
    fun onVisibilityChanged(state: StandbyVisibilityState, phase: ScreenPhase) {
        visibility = state
        this.phase = phase
        updateSensors()
    }

    /**
     * Reports whether the selected Matter foreground is attached.
     *
     * @param available True for an attached Matter foreground, including over custom media.
     */
    fun onMatterLayerAvailabilityChanged(available: Boolean) {
        matterLayerAvailable = available
        updateSensors()
    }

    private fun updateSensors() {
        sensors.onVisibilityChanged(if (matterLayerAvailable) visibility else StandbyVisibilityState.Hidden)
    }

    /**
     * Reports the injected OS animation preference; it is stored but never
     * lets ambient stop. Nothing here reads or writes system settings.
     *
     * @param preference Injected OS preference.
     */
    fun onAnimationPreferenceChanged(preference: StandbyAnimationPreference) {
        animationPreference = preference
    }

    /**
     * Answers whether Big Standby ambient motion may run now.
     *
     * @return True while foreground-visible, regardless of animation
     * preference or frozen phase.
     */
    fun shouldRunAmbient(): Boolean =
        StandbyAnimationPolicy.shouldAnimate(visibility, animationPreference)

    /**
     * Answers whether sensor sources are currently held.
     *
     * @return True while the Matter layer is attached and foreground-visible.
     */
    fun isHoldingSensors(): Boolean = sensors.isHolding()

    /**
     * Answers whether the window keep-awake flag must be held now.
     *
     * Foreground Big Standby always holds it; projecting, leaving, or
     * backgrounding releases it. Phase never gates holding beyond the
     * visibility mapping the caller provides.
     *
     * @return True while standby is foreground-visible.
     */
    fun shouldHoldKeepAwake(): Boolean =
        StandbyKeepAwakePolicy.shouldHoldKeepAwake(visibility)

    /**
     * Returns the current mapped visibility for lifecycle assertions.
     *
     * @return Latest reported visibility.
     */
    fun currentVisibility(): StandbyVisibilityState = visibility
}
