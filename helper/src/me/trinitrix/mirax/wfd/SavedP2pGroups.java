package me.trinitrix.mirax.wfd;

/**
 * Saved Wi-Fi Direct groups the sink must forget.
 *
 * A persistent group lets the original source reinvoke without a new WPS
 * exchange. Every other source then finds the beacon and stops on the system
 * confirmation dialog. Temporary network ids are not saved credentials.
 */
public final class SavedP2pGroups {
    /** Framework fallback approver: any source without its own registration. */
    public static final String ALL_SOURCES_MAC = "ff:ff:ff:ff:ff:ff";

    private SavedP2pGroups() {}

    /**
     * Network ids to delete.
     *
     * Args:
     *     networkIds: Ids reported for saved groups. Negative ids are temporary
     *         and are omitted. Null is an empty list.
     * Returns:
     *     Every remaining id, in the same order. Dropping one leaves a
     *     credential that can still be reinvoked.
     */
    public static int[] persistentNetworkIds(int[] networkIds) {
        if (networkIds == null || networkIds.length == 0) {
            return new int[0];
        }
        int count = 0;
        for (int networkId : networkIds) {
            if (networkId >= 0) {
                count++;
            }
        }
        int[] persistent = new int[count];
        int index = 0;
        for (int networkId : networkIds) {
            if (networkId >= 0) {
                persistent[index++] = networkId;
            }
        }
        return persistent;
    }
}
