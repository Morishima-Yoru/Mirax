package me.trinitrix.mirax.shell;

interface IMiraxShellService {
    void advertise(String broadcastName, String modesCsv);
    void stopAdvertise();
    void destroy();
}
