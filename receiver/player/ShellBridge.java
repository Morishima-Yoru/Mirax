package com.secondscreen.receiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The app listens. The shell helper connects in, because an app socket can accept a shell client
 * on this device while the reverse path is blocked.
 */
public final class ShellBridge {
    public static final int PORT = 19724;
    private static final String TAG = "ShellBridge";
    private static ServerSocket server;
    private static volatile String desired = "BROADCAST 0";
    private static volatile String once = "";
    private static volatile String lastStatus = "";
    private static volatile long lastContact;
    private static SharedPreferences prefs;

    private ShellBridge() {}

    public static void start(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences("secondscreen", Context.MODE_PRIVATE);
            if (prefs.getBoolean("broadcast", false)) {
                desired = "BROADCAST 1";
            }
        }
        if (server != null) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "shell-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public static void setBroadcast(boolean on) {
        desired = on ? "BROADCAST 1" : "BROADCAST 0";
        if (prefs != null) {
            prefs.edit().putBoolean("broadcast", on).apply();
        }
    }

    public static void endSession() {
        once = "END";
    }

    /** Ask Windows for a new keyframe after the picture surface comes back. */
    public static void requestIdr() {
        if (once.length() == 0) {
            once = "IDR";
        }
    }

    public static boolean wantsBroadcast() {
        return "BROADCAST 1".equals(desired);
    }

    public static String status() {
        if (System.currentTimeMillis() - lastContact > 2500) {
            return "";
        }
        return lastStatus;
    }

    private static void serve() {
        try {
            ServerSocket listening = new ServerSocket(PORT);
            server = listening;
            while (true) {
                Socket socket = listening.accept();
                socket.setSoTimeout(1000);
                try {
                    String command = once.length() > 0 ? once : desired;
                    once = "";
                    socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line != null && line.startsWith("uid=")) {
                        lastStatus = line;
                        lastContact = System.currentTimeMillis();
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "exchange " + ex.getMessage());
                } finally {
                    socket.close();
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "bridge failed", ex);
            server = null;
        }
    }
}
