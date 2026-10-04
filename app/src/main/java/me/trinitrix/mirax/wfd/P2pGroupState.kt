package me.trinitrix.mirax.wfd

/**
 * P2P group state reported by the privileged WFD owner.
 *
 * Wire form: `DOWN`, `PENDING`, `UP <peer>`, or `UP <peer> OWNER`.
 */
sealed interface P2pGroupState {
    /** No group is formed. */
    data object Down : P2pGroupState

    /** A group is formed but the source address is not known yet. */
    data object Pending : P2pGroupState

    /**
     * A group is formed with the Miracast peer at [sourceAddress].
     *
     * Attributes:
     *     sourceAddress: IPv4 of the Windows peer.
     *     phoneIsOwner: True when this phone is the P2P group owner (DHCP).
     *     RTSP still dials the Windows peer on 7236 either way.
     */
    data class Up(
        val sourceAddress: String,
        val phoneIsOwner: Boolean = false,
    ) : P2pGroupState

    companion object {
        private const val UP_PREFIX = "UP "
        private const val OWNER_SUFFIX = "OWNER"

        /**
         * Parse the owner's group-state line. Unknown or missing input is [Down].
         */
        fun parse(raw: String?): P2pGroupState {
            val line = raw?.trim().orEmpty()
            return when {
                line.startsWith(UP_PREFIX) && line.length > UP_PREFIX.length -> {
                    val rest = line.substring(UP_PREFIX.length).trim()
                    val parts = rest.split(Regex("\\s+"))
                    val address = parts.firstOrNull().orEmpty()
                    if (address.isEmpty()) {
                        Down
                    } else {
                        val owner = parts.getOrNull(1).equals(OWNER_SUFFIX, ignoreCase = true)
                        Up(address, phoneIsOwner = owner)
                    }
                }
                line == "PENDING" -> Pending
                else -> Down
            }
        }
    }
}
