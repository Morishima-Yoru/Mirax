package me.trinitrix.mirax.wfd

/**
 * P2P group state reported by the privileged WFD owner.
 *
 * Wire form: `DOWN`, `PENDING`, or `UP <peer> [OWNER]`, optionally followed by a tab and peer name.
 */
sealed interface P2pGroupState {
    /** No group is formed. */
    data object Down : P2pGroupState

    /** A group is formed but the source address is not known yet. */
    data object Pending : P2pGroupState

    /**
     * A group is formed with the Miracast peer at [sourceAddress].
     *
     * RTSP still dials the Windows peer on 7236 regardless of the group-owner role.
     *
     * @param sourceAddress IPv4 of the Windows peer
     * @param phoneIsOwner true when this phone is the P2P group owner (DHCP)
     * @param sourceDeviceName peer name supplied by Wi-Fi Direct, or null when unavailable
     */
    data class Up(
        val sourceAddress: String,
        val phoneIsOwner: Boolean = false,
        val sourceDeviceName: String? = null,
    ) : P2pGroupState

    companion object {
        private const val UP_PREFIX = "UP "
        private const val OWNER_SUFFIX = "OWNER"

        /**
         * Parse the owner's group-state line. Unknown or missing input is [Down].
         *
         * @param raw single-line state reported by the privileged owner
         * @return the group state with an optional source device name
         */
        fun parse(raw: String?): P2pGroupState {
            val line = raw?.trim().orEmpty()
            val header = line.substringBefore('\t').trim()
            return when {
                header.startsWith(UP_PREFIX) && header.length > UP_PREFIX.length -> {
                    val rest = header.substring(UP_PREFIX.length).trim()
                    val parts = rest.split(Regex("\\s+"))
                    val address = parts.firstOrNull().orEmpty()
                    if (address.isEmpty()) {
                        Down
                    } else {
                        val owner = parts.getOrNull(1).equals(OWNER_SUFFIX, ignoreCase = true)
                        val name = line.substringAfter('\t', "").trim().ifEmpty { null }
                        Up(address, phoneIsOwner = owner, sourceDeviceName = name)
                    }
                }
                header == "PENDING" -> Pending
                else -> Down
            }
        }
    }
}
