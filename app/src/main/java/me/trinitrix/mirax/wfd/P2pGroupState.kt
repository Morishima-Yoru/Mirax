package me.trinitrix.mirax.wfd

/**
 * P2P group state reported by the privileged WFD owner.
 *
 * Wire form: `DOWN`, `PENDING`, or `UP <source address>`.
 */
sealed interface P2pGroupState {
    /** No group is formed. */
    data object Down : P2pGroupState

    /** A group is formed but the source address is not known yet. */
    data object Pending : P2pGroupState

    /**
     * A group is formed with the Miracast source at [sourceAddress].
     *
     * Attributes:
     *     sourceAddress: IPv4 address of the source's RTSP control endpoint.
     */
    data class Up(val sourceAddress: String) : P2pGroupState

    companion object {
        private const val UP_PREFIX = "UP "

        /**
         * Parse the owner's group-state line. Unknown or missing input is [Down].
         */
        fun parse(raw: String?): P2pGroupState {
            val line = raw?.trim().orEmpty()
            return when {
                line.startsWith(UP_PREFIX) && line.length > UP_PREFIX.length ->
                    Up(line.substring(UP_PREFIX.length).trim())
                line == "PENDING" -> Pending
                else -> Down
            }
        }
    }
}
