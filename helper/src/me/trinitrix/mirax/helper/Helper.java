package me.trinitrix.mirax.helper;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.util.Log;
import me.trinitrix.mirax.wfd.PrimarySinkBeacon;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Privileged helper process. Listens on the abstract {@code mirax-helper} socket
 * and applies WFD advertise / wm-size commands. Never starts Shizuku.
 *
 * Typical start on a rooted device (from Mirax via {@code su}):
 * {@code CLASSPATH=<installed-apk> app_process /system/bin me.trinitrix.mirax.helper.Helper}
 * An optional first argument is a Wi-Fi Direct name to advertise immediately.
 */
public final class Helper {
    public static final String SOCKET_NAME = "mirax-helper";
    public static final int COMMAND_TIMEOUT_MS = 5000;
    private static final String TAG = "MiraxHelper";
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            task -> {
                Thread thread = new Thread(task, "mirax-helper-timeouts");
                thread.setDaemon(true);
                return thread;
            });

    /** Maximum concurrent clients; the fifth connection is rejected instead of queued. */
    static final int MAX_CLIENTS = 4;
    /** Cap for wm-size output; keeps a rogue child from growing the helper heap. */
    static final int WM_OUTPUT_LIMIT_BYTES = 8 * 1024;
    /** Bounded wait for one wm-size query before the child is killed. */
    static final int WM_TIMEOUT_MS = 3000;
    /** Poll step while waiting for staged pipe bytes or child exit. */
    static final int WM_POLL_MS = 20;
    /** Client worker threads; SynchronousQueue gives zero queueing and fast reject. */
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger(0);
    private static final ThreadPoolExecutor CLIENTS = new ThreadPoolExecutor(
            MAX_CLIENTS,
            MAX_CLIENTS,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(task,
                        "mirax-helper-client-" + CLIENT_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static PrimarySinkBeacon beacon;

    private Helper() {}

    public static void main(String[] args) {
        Log.i(TAG, "starting uid=" + android.os.Process.myUid());
        try {
            Looper.prepareMainLooper();
        } catch (Exception ignored) {
        }
        // Bind first — RootHelper probes UID while beacon init is still running.
        final LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET_NAME);
        } catch (Exception err) {
            Log.e(TAG, "bind " + SOCKET_NAME + " failed", err);
            return;
        }
        Thread accept = new Thread(() -> serve(server), "mirax-helper-socket");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "listening on " + SOCKET_NAME);

        beacon = new PrimarySinkBeacon(Looper.getMainLooper());
        if (!beacon.initialize()) {
            Log.e(TAG, "WFD beacon init failed");
            RUNNING.set(false);
            try {
                server.close();
            } catch (IOException err) {
                Log.w(TAG, "close helper socket after beacon init failure failed", err);
            }
            return;
        }
        if (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            Log.i(TAG, "advertise from argument \"" + args[0] + "\"");
            beacon.startAdvertising(args[0]);
        }
        Looper.loop();
    }

    /**
     * Accepts client connections and hands each one to a bounded worker pool.
     *
     * <p>The accept thread never runs commands itself, so one slow or partial
     * client cannot starve later clients (for example the UID probe). At most
     * {@link #MAX_CLIENTS} clients run concurrently; extra connections are
     * closed immediately. Beacon calls stay on worker threads and remain safe
     * because {@link PrimarySinkBeacon} posts every mutation to its looper and
     * only shares volatile reads.
     *
     * @param server bound helper socket
     */
    private static void serve(LocalServerSocket server) {
        try (server) {
            while (RUNNING.get()) {
                LocalSocket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException err) {
                    if (RUNNING.get()) Log.w(TAG, "accept failed", err);
                    continue;
                }
                if (!offerClient(() -> handleAccepted(accepted))) {
                    try {
                        accepted.close();
                    } catch (IOException closeErr) {
                        Log.w(TAG, "closing rejected client failed", closeErr);
                    }
                }
            }
        } catch (Exception err) {
            Log.e(TAG, "server failed", err);
        } finally {
            shutdownExecutors();
        }
    }

    /**
     * Offers a task to the bounded client pool without queueing.
     *
     * @param task client work to run
     * @return true when the pool accepted the task, false when all workers are busy
     */
    static boolean offerClient(Runnable task) {
        try {
            CLIENTS.execute(task);
            return true;
        } catch (RejectedExecutionException err) {
            Log.w(TAG, "client rejected: pool full", err);
            return false;
        }
    }

    /**
     * Handles one accepted client under its own socket timeout and deadline.
     *
     * <p>The per-client watchdog closes a stuck connection after
     * {@link #COMMAND_TIMEOUT_MS}; workers are daemon threads owned by this process.
     *
     * @param client accepted client socket, always closed here
     */
    static void handleAccepted(LocalSocket client) {
        try (LocalSocket owned = client) {
            owned.setSoTimeout(COMMAND_TIMEOUT_MS);
            ScheduledFuture<?> deadline = TIMEOUTS.schedule(() -> {
                try {
                    owned.close();
                } catch (IOException err) {
                    Log.w(TAG, "closing timed-out client failed", err);
                }
            }, COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                handleClient(owned);
            } finally {
                deadline.cancel(false);
            }
        } catch (Exception err) {
            Log.w(TAG, "client exchange failed", err);
        }
    }

    /**
     * Shuts down helper executors after the accept loop ends.
     *
     * <p>Runs at most once per process: the accept loop only ends on socket
     * close or process stop, and all workers are daemon threads, so shutdown
     * never blocks device operation.
     */
    static void shutdownExecutors() {
        CLIENTS.shutdown();
        TIMEOUTS.shutdown();
    }

    /**
     * Reads one complete command frame, rejecting EOF before a newline.
     *
     * @param input client stream
     * @return decoded command
     * @throws IOException on incomplete or oversized input
     */
    static String readCommand(InputStream input) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                String line = frame.toString(StandardCharsets.UTF_8);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (line.isEmpty()) throw new IOException("empty helper command");
                return line;
            }
            if (frame.size() >= 4096) throw new IOException("helper command too long");
            frame.write(value);
        }
        throw new IOException("incomplete helper command");
    }

    private static void handleClient(LocalSocket client) throws Exception {
        String line = readCommand(client.getInputStream());
        OutputStream out = client.getOutputStream();
        if ("UID".equals(line)) {
            writeLine(out, Integer.toString(android.os.Process.myUid()));
            return;
        }
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
        if ("LISTENING".equals(line)) {
            writeLine(out, beacon.isListening() ? "YES" : "NO");
            return;
        }
        if ("FORGET_PAIRINGS".equals(line)) {
            beacon.forgetAllPairings();
            writeLine(out, "OK");
            return;
        }
        if ("PAIRING_STATE".equals(line)) {
            writeLine(out, beacon.getPairingState().name());
            return;
        }
        if ("END".equals(line)) {
            beacon.endSession();
            writeLine(out, "OK");
            return;
        }
        if ("STOP_ADVERTISE".equals(line)) {
            CountDownLatch stopped = new CountDownLatch(1);
            beacon.stopAdvertising(stopped::countDown);
            writeLine(out, stopped.await(COMMAND_TIMEOUT_MS - 500, TimeUnit.MILLISECONDS)
                    ? "OK" : "ERR stop timed out");
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
            return readWmSize(builder.start());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "wm size interrupted", err);
            return "";
        } catch (Exception err) {
            Log.w(TAG, "wm size failed", err);
            return "";
        }
    }

    /**
     * Reads the output of a wm-size child process with a bounded poll loop.
     *
     * <p>Never calls a blocking read: each iteration only consumes bytes
     * already reported by {@code available()}, capped at
     * {@link #WM_OUTPUT_LIMIT_BYTES}. The loop ends when the child exits with
     * no bytes left, when output hits the cap, or when the deadline expires.
     * A live child on timeout is killed and yields an empty string, while an
     * exited child with a descendant-held pipe still returns staged bytes.
     * Interruption kills the child and propagates.
     *
     * @param process started wm-size process
     * @return decoded command output, or an empty string if the process does not finish
     * @throws IOException when output cannot be read
     * @throws InterruptedException when the current thread is interrupted
     */
    static String readWmSize(Process process) throws IOException, InterruptedException {
        final long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(WM_TIMEOUT_MS);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        boolean timedOut = false;
        try (InputStream stdout = process.getInputStream()) {
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("wm size read interrupted");
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        timedOut = true;
                        Log.w(TAG, "wm size timed out");
                        break;
                    }
                    int ready;
                    try {
                        ready = stdout.available();
                    } catch (IOException err) {
                        Log.w(TAG, "wm size stream closed", err);
                        break;
                    }
                    if (ready > 0) {
                        int room = WM_OUTPUT_LIMIT_BYTES - captured.size();
                        if (room <= 0) {
                            break;
                        }
                        int want = Math.min(Math.min(ready, chunk.length), room);
                        int got = stdout.read(chunk, 0, want);
                        if (got < 0) {
                            break;
                        }
                        captured.write(chunk, 0, got);
                        if (captured.size() >= WM_OUTPUT_LIMIT_BYTES) {
                            break;
                        }
                        continue;
                    }
                    if (!process.isAlive()) {
                        // The child is gone and no bytes are staged; a descendant
                        // may still hold the pipe, so return staged bytes instead
                        // of blocking for EOF.
                        break;
                    }
                    Thread.sleep(WM_POLL_MS);
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        if (timedOut) {
            return "";
        }
        String text = captured.toString(StandardCharsets.UTF_8);
        int tail = text.length();
        while (tail > 0 && (text.charAt(tail - 1) == '\n' || text.charAt(tail - 1) == '\r')) {
            tail--;
        }
        return text.substring(0, tail);
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
