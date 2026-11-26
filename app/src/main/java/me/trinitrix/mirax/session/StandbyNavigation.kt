package me.trinitrix.mirax.session

/**
 * Single source-of-truth destination for dual-home and nested section navigation.
 */
enum class StandbyDestination {
    STANDBY,
    DASHBOARD,
    HISTORY,
    GENERAL,
    LANGUAGE,
    PICTURE,
    CUSTOM,
    MODES,
    SCALE,
    ADVANCED,
    ABOUT,
}

/**
 * Pure typed navigation state integrating homes, origins, and hierarchy.
 *
 * @property current Currently active screen.
 * @property homeOrigin Origin home used when nested screens return.
 */
data class StandbyNavigationState(
    val current: StandbyDestination = StandbyDestination.STANDBY,
    val homeOrigin: StandbyDestination = StandbyDestination.STANDBY,
) {
    /**
     * Switch to another home root. Replaces both current and root origin.
     *
     * @param home Target home root (must be STANDBY or DASHBOARD).
     * @return State with [current] and [homeOrigin] replaced.
     */
    fun openHome(home: StandbyDestination): StandbyNavigationState {
        val root = if (home == StandbyDestination.DASHBOARD) {
            StandbyDestination.DASHBOARD
        } else {
            StandbyDestination.STANDBY
        }
        return copy(current = root, homeOrigin = root)
    }

    /**
     * Open a destination. If [destination] is a home, it replaces the root.
     *
     * @param destination Target screen.
     * @return Updated navigation state.
     */
    fun navigateTo(destination: StandbyDestination): StandbyNavigationState {
        return if (destination == StandbyDestination.STANDBY || destination == StandbyDestination.DASHBOARD) {
            openHome(destination)
        } else {
            copy(current = destination)
        }
    }

    /**
     * Resolve the logical parent destination for backward navigation.
     *
     * @return Parent destination, or null if already at a home root.
     */
    fun parent(): StandbyDestination? {
        return when (current) {
            StandbyDestination.STANDBY, StandbyDestination.DASHBOARD -> null
            StandbyDestination.HISTORY, StandbyDestination.GENERAL,
            StandbyDestination.PICTURE, StandbyDestination.ADVANCED,
            StandbyDestination.ABOUT -> homeOrigin
            StandbyDestination.LANGUAGE -> StandbyDestination.GENERAL
            StandbyDestination.CUSTOM, StandbyDestination.MODES,
            StandbyDestination.SCALE -> StandbyDestination.PICTURE
        }
    }

    /**
     * Step back to parent destination, preserving home origin.
     *
     * @return State navigated to parent, or null if already at a root.
     */
    fun stepUp(): StandbyNavigationState? {
        val parentDest = parent() ?: return null
        return copy(current = parentDest)
    }
}

/**
 * Pure 2-second confirmation tracker for root Back exit.
 *
 * @param timeoutMs Timeout window in milliseconds.
 * @param clock Function providing current time in milliseconds.
 */
class RootExitConfirmation(
    private val timeoutMs: Long = 2_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var pendingSince: Long? = null

    /**
     * Whether a confirmation request is currently active within the timeout window.
     */
    val isPending: Boolean
        get() {
            val since = pendingSince ?: return false
            val now = clock()
            return now - since in 0..timeoutMs
        }

    /**
     * Arm or consume confirmation on Back press.
     *
     * @return True if this is the second press within timeout (caller should exit);
     *         False if this is the first press (caller should show toast).
     */
    fun onPress(): Boolean {
        if (isPending) {
            reset()
            return true
        }
        pendingSince = clock()
        return false
    }

    /**
     * Cancel pending exit on navigation, backgrounding, or overlay events.
     */
    fun reset() {
        pendingSince = null
    }
}
