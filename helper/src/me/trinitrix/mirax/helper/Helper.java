package me.trinitrix.mirax.helper;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.util.Log;
import me.trinitrix.mirax.wfd.PrimarySinkBeacon;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * adb-started shell helper. Listens on the abstract {@code mirax-helper} socket
 * and applies WFD advertise / wm-size commands. Never starts Shizuku.
 *
 * Start with:
 * {@code adb shell "CLASSPATH=/data/local/tmp/mirax-helper.jar app_process /system/bin
 * me.trinitrix.mirax.helper.Helper"}
 */
public final class Helper {
    public static final String SOCKET_NAME = "mirax-helper";
    private static final String TAG = "MiraxHelper";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static PrimarySinkBeacon beacon;

    private Helper() {}

    public static void main(String[] args) {
        Log.i(TAG, "starting uid=" + android.os.Process.myUid());
        try {
            Looper.prepareMainLooper();
        } catch (Exception ignored) {
        }
        beacon = new PrimarySinkBeacon(Looper.getMainLooper());
        if (!beacon.initialize()) {
            Log.e(TAG, "WFD beacon init failed");
            return;
        }
        Thread server = new Thread(Helper::serve, "mirax-helper-socket");
        server.setDaemon(true);
        server.start();
        Log.i(TAG, "listening on " + SOCKET_NAME);
        Looper.loop();
    }

    private static void serve() {
        try (LocalServerSocket server = new LocalServerSocket(SOCKET_NAME)) {
            while (RUNNING.get()) {
                try (LocalSocket client = server.accept()) {
                    handleClient(client);
                } catch (Exception err) {
                    if (RUNNING.get()) {
                        Log.w(TAG, "client exchange failed", err);
                    }
                }
            }
        } catch (Exception err) {
            Log.e(TAG, "server failed", err);
        }
    }

    private static void handleClient(LocalSocket client) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null || line.isEmpty()) {
            return;
        }
        OutputStream out = client.getOutputStream();
        if ("STOP".equals(line)) {
            stopProcess();
            writeLine(out, "OK");
            return;
        }
        if (line.startsWith("WM_SIZE")) {
            String displayId = line.substring("WM_SIZE".length()).trim();
            writeLine(out, wmSize(displayId.isEmpty() ? -1 : Integer.parseInt(displayId)));
            return;
        }
        if ("GROUP".equals(line)) {
            writeLine(out, beacon.groupState());
            return;
        }
        if ("END".equals(line)) {
            beacon.endSession();
            writeLine(out, "OK");
            return;
        }
        if ("STOP_ADVERTISE".equals(line)) {
            beacon.stopAdvertising();
            writeLine(out, "OK");
            return;
        }
        if (line.startsWith("ADVERTISE\t") || line.startsWith("ADVERTISE ")) {
            String payload = line.startsWith("ADVERTISE\t")
                    ? line.substring("ADVERTISE\t".length())
                    : line.substring("ADVERTISE ".length());
            int tab = payload.indexOf('\t');
            String name;
            String modes;
            if (tab >= 0) {
                name = payload.substring(0, tab);
                modes = payload.substring(tab + 1);
            } else {
                name = payload;
                modes = "";
            }
            Log.i(TAG, "advertise name=\"" + name + "\" modes=" + modes);
            beacon.startAdvertising(name);
            writeLine(out, "OK");
            return;
        }
        writeLine(out, "ERR unknown");
    }

    /**
     * Run the {@code wm size} query as the current (shell) process.
     *
     * @param displayId display to query, or a negative value for plain {@code wm size}
     * @return the command output, or an empty string on failure
     */
    public static String wmSize(int displayId) {
        try {
            ProcessBuilder builder = displayId < 0
                    ? new ProcessBuilder("wm", "size")
                    : new ProcessBuilder("wm", "size", "-d", Integer.toString(displayId));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String row;
                while ((row = reader.readLine()) != null) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(row);
                }
                output = text.toString();
            }
            process.waitFor();
            return output;
        } catch (Exception err) {
            Log.w(TAG, "wm size failed", err);
            return "";
        }
    }

    private static void stopProcess() {
        RUNNING.set(false);
        try {
            beacon.stopAdvertising();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "stop requested");
        // Exit after the response flush; the client close path returns first.
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "mirax-helper-exit").start();
    }

    private static void writeLine(OutputStream out, String text) throws Exception {
        out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
