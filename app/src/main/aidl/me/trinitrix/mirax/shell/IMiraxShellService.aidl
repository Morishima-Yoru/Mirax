package me.trinitrix.mirax.shell;

interface IMiraxShellService {
    void advertise(String broadcastName, String modesCsv) = 1;
    void stopAdvertise() = 2;
    void endSession() = 3;
    String groupState() = 4;
    String wmSize(int displayId) = 5;
    void forgetAllPairings() = 6;
    String getPairingState() = 7;
    void destroy() = 16777114;
}
