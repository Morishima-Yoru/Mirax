package me.trinitrix.mirax.shell;

interface IMiraxShellService {
    void advertise(String broadcastName, String modesCsv) = 1;
    void stopAdvertise() = 2;
    void endSession() = 3;
    String groupState() = 4;
    String wmSize(int displayId) = 5;
    void forgetAllPairings() = 6;
    String getPairingState() = 7;
    /** True after startListening succeeded for the current advertise request. */
    boolean isListening() = 8;
    /** Launch only the exported Mirax entry activity for the current user. */
    void bringProjectionToFront() = 9;
    String groupSnapshotV2() = 10;
    /** True means the owner handler accepted the runnable, not that removal occurred. */
    boolean endSessionIfEpoch(String epoch) = 11;
    void destroy() = 16777114;
}
