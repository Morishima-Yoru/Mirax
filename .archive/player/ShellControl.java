package com.secondscreen.receiver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Talks to the shell helper on localhost. The helper is the only process that can advertise Miracast. */
public final class ShellControl {
    public static final int PORT = 19724;

    private ShellControl() {}

    public static String exchange(String command) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null ? "" : line;
        } finally {
            socket.close();
        }
    }
}
