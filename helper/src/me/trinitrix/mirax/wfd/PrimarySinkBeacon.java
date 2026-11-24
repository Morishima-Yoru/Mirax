package me.trinitrix.mirax.wfd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Shell-UID Wi-Fi Display Primary Sink beacon.
 *
 * Calls {@link WifiP2pManager#setWfdInfo} and {@link WifiP2pManager#startListening}
 * so stock Windows Win+K can list this phone, keeps listening while no group is
 * formed, accepts the source's P2P connection request, and exposes the formed
 * group's source address. Implements WPS Push Button Configuration for trust
 * pairing with Windows. Persistent groups are preserved across sessions so
 * reinvocation works without re-pairing. Must run as shell (UID 2000) or root
 * — never from the Mirax app process. Does not touch Samsung SmartMirroring
 * or SecondScreenPlayer.
 */
public final class PrimarySinkBeacon {
    private static final String TAG = "MiraxWfdBeacon";
    /** Miracast RTSP control port advertised in the WFD IE. */
    public static final int RTSP_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT_MBPS = 50;
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long NEIGHBOR_TIMEOUT_MS = 750;
    private static final int NEIGHBOR_OUTPUT_LIMIT = 65536;
    /** Android shuts an idle P2P interface down after 150 s; re-arm well before that. */
    private static final long RELISTEN_INTERVAL_MS = 30_000;
    /** WPS pairing timeout. */
    private static final long WPS_TIMEOUT_MS = 120_000;

    /** No P2P group is formed. */
    public static final String GROUP_DOWN = "DOWN";
    /** A group is formed but the source address is not known yet. */
    public static final String GROUP_PENDING = "PENDING";
    /** Prefix of the group state once the source address is known: {@code UP <ipv4>}. */
    public static final String GROUP_UP_PREFIX = "UP ";

    /** Pairing state for trust management. */
    public enum PairingState {
        UNPAIRED,      // No persistent group, first connection will trigger WPS
        PAIRING,       // WPS in progress
        PAIRED         // Persistent group exists, reinvocation allowed
    }

    private final Handler handler;
    private final GroupTracker groupTracker = new GroupTracker();
    private final NeighborQuery neighborQuery;
    private final Set<String> approverMacs = new HashSet<>();
    private Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private volatile boolean wanted;
    private volatile boolean advertising;
    private volatile String lastName = "";
    private boolean forgetSavedGroups;
    private long lastArmedAt;
    private volatile PairingState pairingState = PairingState.UNPAIRED;
    private String pendingPairingMac = null;
    private Runnable wpsTimeoutRunnable = null;

    private static final Pattern EPOCH_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,18}");

    /**
     * @param epoch untrusted epoch token
     * @return whether the token has the exact owner epoch wire format
     */
    public static boolean isValidGroupEpoch(String epoch) {
        return epoch != null && EPOCH_PATTERN.matcher(epoch).matches();
    }

    /**
     * Handler-confined observations with an atomic cross-thread snapshot.
     * Unobserved replacement groups with the same address remain indistinguishable.
     */
    static final class GroupTracker {
        /** Immutable group state and its owner-issued identity. */
        static final class Snapshot {
            final String epoch;
            final String state;
            final boolean phoneIsOwner;

            Snapshot(String epoch, String state, boolean phoneIsOwner) {
                this.epoch = epoch;
                this.state = state;
                this.phoneIsOwner = phoneIsOwner;
            }
        }

        /** Identity of one asynchronous connection-info request. */
        static final class Request {
            final long generation;
            final long sequence;

            Request(long generation, long sequence) {
                this.generation = generation;
                this.sequence = sequence;
            }
        }

        private final String nonce = UUID.randomUUID().toString();
        private long epochCounter;
        private long generation;
        private long requestSequence;
        private long acceptedSequence;
        private boolean wanted;
        private boolean consumed;
        private volatile Snapshot snapshot = new Snapshot(nextEpoch(), GROUP_DOWN, false);

        private String nextEpoch() {
            return nonce + "-" + ++epochCounter;
        }

        Snapshot snapshot() {
            return snapshot;
        }

        boolean isFormed() {
            return !GROUP_DOWN.equals(snapshot.state);
        }

        void setWanted(boolean nextWanted) {
            if (wanted == nextWanted) {
                return;
            }
            wanted = nextWanted;
            generation++;
            snapshot = new Snapshot(nextEpoch(), GROUP_DOWN, false);
        }

        Request request() {
            return new Request(generation, ++requestSequence);
        }

        boolean accept(Request request, Runnable callback) {
            if (!wanted || request.generation != generation
                    || request.sequence <= acceptedSequence || request.sequence > requestSequence) {
                return false;
            }
            acceptedSequence = request.sequence;
            callback.run();
            return true;
        }

        void observe(boolean formed, boolean phoneIsOwner, String source) {
            String state = !formed ? GROUP_DOWN : source == null ? GROUP_PENDING
                    : GROUP_UP_PREFIX + source + (phoneIsOwner ? " OWNER" : "");
            boolean owner = formed && phoneIsOwner;
            Snapshot current = snapshot;
            // A reset or formed-state change does not prove the prior removal has finished.
            if (!formed) {
                consumed = false;
            }
            if (!current.state.equals(state) || current.phoneIsOwner != owner) {
                snapshot = new Snapshot(nextEpoch(), state, owner);
            }
        }

        boolean acceptsNeighbor(Request request, String epoch) {
            Snapshot current = snapshot;
            return wanted && request.generation == generation && request.sequence == acceptedSequence
                    && current.epoch.equals(epoch) && current.phoneIsOwner && isFormed();
        }

        /**
         * @param epoch observed group identity
         * @param remove starts removal and receives success and failure callbacks
         * @return handler runnable that consumes the epoch before removal
         */
        Runnable guardedEnd(String epoch, BiConsumer<Runnable, Runnable> remove) {
            return () -> {
                Snapshot current = snapshot;
                if (!wanted || !isValidGroupEpoch(epoch) || GROUP_DOWN.equals(current.state)
                        || !current.epoch.equals(epoch) || consumed) {
                    return;
                }
                consumed = true;
                long endGeneration = ++generation;
                boolean[] completed = {false};
                Runnable onSuccess = () -> completed[0] = true;
                Runnable onFailure = () -> {
                    if (completed[0]) {
                        return;
                    }
                    completed[0] = true;
                    Snapshot latest = snapshot;
                    if (wanted && consumed && generation == endGeneration && latest.epoch.equals(epoch)) {
                        consumed = false;
                        snapshot = new Snapshot(nextEpoch(), latest.state, latest.phoneIsOwner);
                    }
                };
                // Wi-Fi Direct has no atomic group-ID check at removeGroup execution.
                try {
                    remove.accept(onSuccess, onFailure);
                } catch (RuntimeException | Error err) {
                    onFailure.run();
                    throw err;
                }
            };
        }
    }

    /** One outstanding owner-neighbor query; all state is confined to the beacon handler. */
    static final class NeighborQuery {
        private final GroupTracker tracker;
        private final Executor worker;
        private final Consumer<Runnable> post;
        private final BiFunction<Runnable, Long, Boolean> schedule;
        private final Supplier<String> lookup;
        private boolean running;
        private boolean expired;
        private long ticket;

        NeighborQuery(GroupTracker tracker, Executor worker, Consumer<Runnable> post,
                BiFunction<Runnable, Long, Boolean> schedule, Supplier<String> lookup) {
            this.tracker = tracker;
            this.worker = worker;
            this.post = post;
            this.schedule = schedule;
            this.lookup = lookup;
        }

        void request(GroupTracker.Request request) {
            GroupTracker.Snapshot current = tracker.snapshot();
            if (running || !tracker.acceptsNeighbor(request, current.epoch)) {
                return;
            }
            running = true;
            expired = false;
            long nextTicket = ++ticket;
            try {
                if (!schedule.apply(() -> {
                    if (running && ticket == nextTicket) {
                        expired = true;
                    }
                }, NEIGHBOR_TIMEOUT_MS)) {
                    running = false;
                    Log.w(TAG, "neighbor timeout could not be scheduled");
                    return;
                }
                worker.execute(() -> {
                    String address = null;
                    try {
                        address = lookup.get();
                    } catch (Throwable err) {
                        Log.w(TAG, "neighbor lookup failed", err);
                    }
                    String result = address;
                    post.accept(() -> finish(nextTicket, request, current.epoch, result));
                });
            } catch (RuntimeException err) {
                running = false;
                Log.w(TAG, "neighbor scheduling failed", err);
            }
        }

        private void finish(long finishedTicket, GroupTracker.Request request, String epoch, String address) {
            if (!running || ticket != finishedTicket) {
                return;
            }
            running = false;
            if (expired || address == null || !tracker.acceptsNeighbor(request, epoch)) {
                return;
            }
            GroupTracker.Snapshot previous = tracker.snapshot();
            tracker.observe(true, true, address);
            if (previous != tracker.snapshot()) {
                Log.i(TAG, "source address " + address + " phoneIsOwner=true");
            }
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!wanted) {
                return;
            }
            pollOnce();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public PrimarySinkBeacon(Looper looper) {
        this.handler = new Handler(looper);
        this.neighborQuery = new NeighborQuery(groupTracker, task -> {
            Thread worker = new Thread(task, "mirax-neighbor");
            worker.setDaemon(true);
            worker.start();
        }, task -> {
            if (!handler.post(task)) {
                Log.w(TAG, "neighbor result could not be posted");
            }
        }, (task, delay) -> handler.postDelayed(task, delay), PrimarySinkBeacon::p2pNeighbor);
    }

    /**
     * Whether {@link WifiP2pManager#startListening} has succeeded for the
     * current advertise request.
     */
    public boolean isListening() {
        return advertising;
    }

    /**
     * Optional callback for the first listen success or terminal arm failure
     * of the current advertise request. Cleared after one terminal delivery.
     */
    public void setArmCallback(ArmCallback callback) {
        armCallback = callback;
    }

    /** Terminal result of arming the Primary Sink listen beacon. */
    public interface ArmCallback {
        void onListening();

        void onFailed(String detail);
    }

    private volatile ArmCallback armCallback;

    /**
     * Initialise Wi-Fi P2P with a shell attribution context (adb helper / Shizuku).
     *
     * @return true when the P2P channel is ready
     */
    public boolean initialize() {
        return initialize(null);
    }

    /**
     * Initialise Wi-Fi P2P.
     *
     * @param appContext when non-null, use this application context. When null,
     *     build a shell attribution context for adb/Shizuku.
     * @return true when the P2P channel is ready
     */
    public boolean initialize(Context appContext) {
        try {
            context = appContext != null
                    ? appContext.getApplicationContext()
                    : systemContext();
            manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) {
                Log.e(TAG, "WifiP2pManager is null");
                return false;
            }
            channel = manager.initialize(
                    context,
                    handler.getLooper(),
                    () -> Log.w(TAG, "Wi-Fi Direct channel disconnected"));
            return channel != null;
        } catch (Throwable err) {
            Log.e(TAG, "initialize failed", err);
            return false;
        }
    }

    /**
     * Advertise as a Miracast Primary Sink under [broadcastName] and keep the
     * sink connectable until {@link #stopAdvertising()}.
     *
     * @param broadcastName Wi-Fi Direct device name Win+K must show
     */
    public void startAdvertising(final String broadcastName) {
        handler.post(() -> {
            String nextName = broadcastName == null ? "" : broadcastName;
            boolean rename = wanted && !nextName.equals(lastName);
            lastName = nextName;
            if (rename && manager != null && channel != null) {
                // Already listening: stop first so probe responses pick up the
                // new Wi-Fi Direct name. setDeviceName alone is often sticky
                // until the next listen / group cycle (connect then disconnect).
                try {
                    manager.stopListening(channel, logged("stopListening-rename"));
                } catch (Throwable err) {
                    Log.w(TAG, "stopListening before rename failed", err);
                }
                advertising = false;
            }
            applyDeviceName(lastName);
            if (!wanted) {
                wanted = true;
                groupTracker.setWanted(true);
                removeGroupQuietly("removeGroup");
            }
            // Clear any leftover approvers from previous runs
            removeApprovers();
            // Do NOT delete persistent groups here - preserve them for reinvocation.
            // forgetSavedGroups is only set to true by forgetAllPairings() (user action).
            armSink();
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_INTERVAL_MS);
        });
    }

    /** Stop listening, drop any group, and clear the WFD IE so the phone is not a connectable sink. */
    public void stopAdvertising() {
        handler.post(() -> {
            wanted = false;
            advertising = false;
            groupTracker.setWanted(false);
            clientConnectAttempted.clear();
            handler.removeCallbacks(poll);
            if (manager == null || channel == null) {
                return;
            }
            try {
                manager.stopListening(channel, logged("stopListening"));
                removeGroupQuietly("removeGroup");
                clearWfdInfo();
                removeApprovers();
            } catch (Throwable err) {
                Log.w(TAG, "stopAdvertising failed", err);
            }
            Log.i(TAG, "broadcast off");
        });
    }

    /** End the current connection's P2P group; advertising resumes once the group is down. */
    public void endSession() {
        handler.post(() -> removeGroupQuietly("endSession"));
    }

    /**
     * Queue an epoch-guarded group removal; success only means the runnable was queued.
     *
     * @param epoch owner-issued group epoch
     * @return true if the request was valid and queued
     */
    public boolean endSessionIfEpoch(String epoch) {
        return isValidGroupEpoch(epoch)
                && handler.post(groupTracker.guardedEnd(epoch,
                        (onSuccess, onFailure) -> removeGroupQuietly("endSessionV2", onSuccess, onFailure)));
    }

    /**
     * Forget all paired devices by deleting all persistent groups.
     * The next connection will require WPS pairing again.
     */
    public void forgetAllPairings() {
        handler.post(() -> {
            Log.i(TAG, "User requested forget all pairings");
            forgetSavedGroups = true;
            pairingState = PairingState.UNPAIRED;
            if (manager != null && channel != null) {
                requestPersistentGroupDeletion(() -> {
                    Log.i(TAG, "All persistent groups deleted, trust reset");
                    // Re-arm sink to update WFD IE and approver
                    if (wanted) {
                        armSink();
                    }
                });
            }
        });
    }

    /**
     * Get current pairing state.
     */
    public PairingState getPairingState() {
        return pairingState;
    }

    public boolean isAdvertising() {
        return advertising;
    }

    /**
     * @return {@link #GROUP_DOWN}, {@link #GROUP_PENDING}, or {@code UP <source address>}
     */
    public String groupState() {
        return groupTracker.snapshot().state;
    }

    /** @return a versioned group state and epoch from the same snapshot */
    public String groupSnapshotV2() {
        GroupTracker.Snapshot current = groupTracker.snapshot();
        return "V2 " + current.epoch + " " + current.state;
    }

    /**
     * First usable IPv4 neighbour on a P2P interface from {@code ip -4 neigh show} output.
     *
     * @param output command output, one neighbour per line
     * @return the address, or null when no P2P neighbour is usable yet
     */
    public static String parseP2pNeighbor(String output) {
        if (output == null) {
            return null;
        }
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains(" dev p2p") || !line.contains(" lladdr ")) {
                continue;
            }
            if (line.endsWith("FAILED") || line.endsWith("INCOMPLETE")) {
                continue;
            }
            int space = line.indexOf(' ');
            String address = space < 0 ? line : line.substring(0, space);
            if (address.indexOf('.') > 0) {
                return address;
            }
        }
        return null;
    }

    /**
     * @param output currently available process output
     * @return the first peer from a complete line, or null
     */
    static String parseAvailableP2pNeighbor(String output) {
        int end = output.lastIndexOf('\n');
        return end < 0 ? null : parseP2pNeighbor(output.substring(0, end));
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void pollOnce() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            GroupTracker.Request request = groupTracker.request();
            manager.requestConnectionInfo(channel,
                    info -> groupTracker.accept(request, () -> onConnectionInfo(info, request)));
            manager.requestPeers(channel, peers -> {
                if (peers == null) {
                    return;
                }
                for (WifiP2pDevice device : peers.getDeviceList()) {
                    watchPeer(device);
                }
            });
        } catch (Throwable err) {
            Log.w(TAG, "poll failed", err);
        }
        if (!groupTracker.isFormed() && SystemClock.elapsedRealtime() - lastArmedAt >= RELISTEN_INTERVAL_MS) {
            armSink();
        }
    }

    private void onConnectionInfo(WifiP2pInfo info, GroupTracker.Request request) {
        if (!wanted) {
            return;
        }
        if (info == null) {
            Log.w(TAG, "connection info unavailable");
            return;
        }
        boolean formed = info.groupFormed;
        if (!formed) {
            boolean wasFormed = groupTracker.isFormed();
            groupTracker.observe(false, false, null);
            if (wasFormed) {
                Log.i(TAG, "P2P group down");
                clientConnectAttempted.clear();
                // Do NOT forget saved groups here - preserve persistent group for reinvocation
                // forgetSavedGroups is only set to true when user explicitly calls forgetAllPairings()
                armSink();
            }
            return;
        }
        if (!groupTracker.isFormed()) {
            groupTracker.observe(true, info.isGroupOwner, null);
            Log.i(TAG, "P2P group up phoneIsOwner=" + info.isGroupOwner
                    + " owner=" + info.groupOwnerAddress);
            if (pairingState != PairingState.PAIRED) {
                PairingState previousPairingState = pairingState;
                pairingState = PairingState.PAIRED;
                if (wpsTimeoutRunnable != null) {
                    handler.removeCallbacks(wpsTimeoutRunnable);
                    wpsTimeoutRunnable = null;
                }
                pendingPairingMac = null;
                Log.i(TAG, "P2P group formed; pairing state " + previousPairingState + " -> PAIRED");
            }
        }
        if (info.isGroupOwner) {
            if (!groupTracker.snapshot().phoneIsOwner) {
                groupTracker.observe(true, true, null);
            }
            neighborQuery.request(request);
            return;
        }
        String source = hostOf(info.groupOwnerAddress);
        GroupTracker.Snapshot previous = groupTracker.snapshot();
        groupTracker.observe(true, false, source);
        if (source != null && previous != groupTracker.snapshot()) {
            Log.i(TAG, "source address " + source + " phoneIsOwner=false");
        }
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void armSink() {
        if (manager == null || channel == null) {
            return;
        }
        lastArmedAt = SystemClock.elapsedRealtime();
        final WifiP2pWfdInfo wfd = new WifiP2pWfdInfo();
        wfd.setEnabled(true);
        wfd.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
        wfd.setSessionAvailable(true);
        wfd.setControlPort(RTSP_CONTROL_PORT);
        wfd.setMaxThroughput(MAX_THROUGHPUT_MBPS);
        try {
            wfd.setContentProtectionSupported(false);
        } catch (Throwable ignored) {
        }
        // Advertise WPS Push Button Configuration support for Windows pairing
        setWpsConfigMethods(wfd);
        final boolean announce = !advertising;
        if (announce) {
            Log.i(TAG, "WFD info " + wfd + " name=\"" + lastName + "\" pairing=" + pairingState);
        }
        try {
            manager.setWfdInfo(channel, wfd, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (announce) {
                        Log.i(TAG, "setWfdInfo success");
                    }
                    // Driver hint: sink mode (2) shortens scan dwell so STA+P2P
                    // concurrency hurts Miracast less. Hidden WifiManager API.
                    setMiracastModeSink();
                    admitThenListen(announce);
                }

                @Override
                public void onFailure(int reason) {
                    advertising = false;
                    Log.e(TAG, "setWfdInfo FAILED reason=" + reason);
                    notifyArmFailed("setWfdInfo failed reason=" + reason);
                }
            });
        } catch (SecurityException err) {
            // Some GSIs keep CONFIGURE_WIFI_DISPLAY as signature-only and
            // never grant it to shell UID 2000 (unlike OEM builds Mirax targets).
            advertising = false;
            Log.e(TAG, "setWfdInfo denied for this UID; Miracast sink cannot advertise", err);
            notifyArmFailed("setWfdInfo denied");
        }
    }

    /**
     * Set WPS config methods in WFD IE to advertise PBC support.
     * Windows requires this to initiate pairing.
     */
    private void setWpsConfigMethods(WifiP2pWfdInfo wfd) {
        try {
            // WPS_CONFIG_PUSH_BUTTON = 0x0080, WPS_CONFIG_KEYPAD = 0x0008
            Method setWps = WifiP2pWfdInfo.class.getMethod("setWpsConfigMethodsSupported", int.class);
            setWps.invoke(wfd, 0x0080 | 0x0008); // PBC + Keypad
            Log.d(TAG, "WPS config methods set: PBC + Keypad");
        } catch (NoSuchMethodException e) {
            // API < 29 doesn't have this method, ignore
            Log.d(TAG, "setWpsConfigMethodsSupported not available on this API level");
        } catch (Throwable err) {
            Log.w(TAG, "setWpsConfigMethodsSupported failed", err);
        }
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void clearWfdInfo() {
        try {
            final WifiP2pWfdInfo wfd = new WifiP2pWfdInfo();
            wfd.setEnabled(false);
            manager.setWfdInfo(channel, wfd, logged("clearWfdInfo"));
        } catch (Throwable err) {
            Log.w(TAG, "clearWfdInfo failed", err);
        }
        setMiracastModeDisabled();
    }

    /**
     * Ask the Wi-Fi driver to optimize for Miracast sink (mode 2). No-op when
     * the hidden {@code WifiManager#setMiracastMode} is missing.
     */
    private void setMiracastModeSink() {
        setMiracastMode(2, "sink");
    }

    private void setMiracastModeDisabled() {
        setMiracastMode(0, "off");
    }

    private void setMiracastMode(int mode, String label) {
        if (context == null) {
            return;
        }
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            Method set = WifiManager.class.getMethod("setMiracastMode", int.class);
            set.invoke(wifi, mode);
            Log.i(TAG, "setMiracastMode " + label + " (" + mode + ")");
        } catch (NoSuchMethodException ignored) {
            // Hidden API absent on this WifiManager (common on GSI).
        } catch (Throwable err) {
            Log.w(TAG, "setMiracastMode " + label + " failed: " + err.getMessage());
        }
    }

    private void removeGroupQuietly(String label) {
        removeGroupQuietly(label, null, null);
    }

    private void removeGroupQuietly(String label, Runnable onSuccess, Runnable onFailure) {
        if (manager == null || channel == null) {
            if (onFailure != null) {
                Log.w(TAG, label + " unavailable");
                onFailure.run();
            }
            return;
        }
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, label + " success");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, label + " failed reason=" + reason);
                    if (onFailure != null) {
                        onFailure.run();
                    }
                }
            });
        } catch (Throwable err) {
            Log.w(TAG, label + " failed", err);
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    /**
     * Check for existing persistent groups to determine pairing state.
     * If persistent groups exist, we're PAIRED and can reinvoke.
     * If no persistent groups, we're UNPAIRED and need WPS for first connection.
     */
    private void checkPersistentGroups() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            Class<?> listenerClass = Class.forName(
                    "android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method.getName(), args);
                        }
                        if ("onPersistentGroupInfoAvailable".equals(method.getName())) {
                            Object groups = args == null || args.length == 0 ? null : args[0];
                            int[] persistent = SavedP2pGroups.persistentNetworkIds(networkIdsOf(groups));
                            if (persistent.length > 0) {
                                pairingState = PairingState.PAIRED;
                                Log.i(TAG, "Found " + persistent.length + " persistent group(s), state=PAIRED");
                            } else {
                                pairingState = PairingState.UNPAIRED;
                                Log.i(TAG, "No persistent groups, state=UNPAIRED");
                            }
                        }
                        return null;
                    });
            Method request = WifiP2pManager.class.getMethod(
                    "requestPersistentGroupInfo",
                    WifiP2pManager.Channel.class,
                    listenerClass);
            request.invoke(manager, channel, listener);
        } catch (Throwable err) {
            Log.w(TAG, "requestPersistentGroupInfo unavailable", err);
            pairingState = PairingState.UNPAIRED;
        }
    }

    /**
     * Arm the broadcast ExternalApprover (GO intent 0 + WPS on first pair), then
     * listen. Persistent-group reinvoke still keeps prior GO role until the user
     * forgets pairings — intent only applies on fresh negotiation.
     */
    private void admitThenListen(boolean announce) {
        armBroadcastApprover();
        Runnable listen = () -> startListening(announce);
        // Only delete persistent groups if explicitly requested (forgetAllPairings)
        if (forgetSavedGroups) {
            forgetSavedGroups = false;
            if (!requestPersistentGroupDeletion(listen)) {
                listen.run();
            }
        } else {
            listen.run();
        }
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void startListening(boolean announce) {
        if (!wanted || manager == null || channel == null) {
            return;
        }
        try {
            manager.startListening(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (!advertising) {
                        Log.i(TAG, "startListening success; Primary Sink on port "
                                + RTSP_CONTROL_PORT);
                    }
                    advertising = true;
                    notifyArmListening();
                }

                @Override
                public void onFailure(int reason) {
                    if (announce) {
                        advertising = false;
                        Log.e(TAG, "startListening failed reason=" + reason);
                        notifyArmFailed("startListening failed reason=" + reason);
                    }
                }
            });
        } catch (SecurityException denied) {
            // Shell-UID callers hold NEARBY_WIFI_DEVICES; otherwise fail closed
            // instead of letting the exception escape the arm path.
            Log.w(TAG, "startListening denied", denied);
            advertising = false;
            notifyArmFailed("startListening denied");
        }
    }

    private void notifyArmListening() {
        ArmCallback callback = armCallback;
        armCallback = null;
        if (callback != null) {
            callback.onListening();
        }
    }

    private void notifyArmFailed(String detail) {
        ArmCallback callback = armCallback;
        armCallback = null;
        if (callback != null) {
            callback.onFailed(detail == null ? "arm failed" : detail);
        }
    }

    /**
     * Always register the broadcast ExternalApprover while advertising so we can
     * force {@code groupOwnerIntent=0} (prefer Windows as GO). Requires API 33+.
     * On HA1CSQTM (API 30) this is a no-op; MTK WifiP2pService still rewrites
     * AUTO (-1) → 14 and the phone stays GO. UNPAIRED still runs WPS PBC from
     * {@link #onConnectionRequested} when the approver path is live.
     */
    private void armApproverForPairedDevices() {
        armBroadcastApprover();
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void armBroadcastApprover() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || manager == null || channel == null || !wanted) {
            return;
        }
        String mac = MacAddress.BROADCAST_ADDRESS.toString();
        if (!approverMacs.add(mac)) {
            return;
        }
        try {
            manager.addExternalApprover(channel, MacAddress.BROADCAST_ADDRESS, approver);
            Log.i(TAG, "addExternalApprover broadcast pairingState=" + pairingState);
        } catch (Throwable err) {
            approverMacs.remove(mac);
            Log.w(TAG, "addExternalApprover failed for " + mac, err);
        }
    }

    /**
        * Issue WPS Push Button Configuration before group formation for an
        * external-approver flow. Pairing state changes only after the group forms.
     */
    private void startWpsPbc(String deviceAddress) {
        if (manager == null || channel == null) {
            return;
        }
        final String pairingMac = deviceAddress;
        try {
            // Cancel any existing timeout
            if (wpsTimeoutRunnable != null) {
                handler.removeCallbacks(wpsTimeoutRunnable);
            }

            pairingState = PairingState.PAIRING;
            pendingPairingMac = pairingMac;

            // Set timeout for WPS pairing (120s)
            wpsTimeoutRunnable = () -> {
                Log.w(TAG, "WPS pairing timed out for " + pairingMac);
                pairingState = PairingState.UNPAIRED;
                pendingPairingMac = null;
            };
            handler.postDelayed(wpsTimeoutRunnable, WPS_TIMEOUT_MS);

            // Use reflection for startWps with WPS_PBC
            Class<?> wpsInfoClass = Class.forName("android.net.wifi.WpsInfo");
            Object wpsConfig = wpsInfoClass.getDeclaredConstructor().newInstance();
            Field setupField = wpsInfoClass.getField("setup");
            setupField.setInt(wpsConfig, 0); // 0 = WPS_PBC

            Method startWps = WifiP2pManager.class.getMethod(
                    "startWps",
                    WifiP2pManager.Channel.class,
                    wpsInfoClass,
                    WifiP2pManager.ActionListener.class);

            WifiP2pManager.ActionListener wpsListener = new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WPS PBC request accepted for " + pairingMac);
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "WPS PBC failed reason=" + reason + " for " + pairingMac);
                    if (wpsTimeoutRunnable != null) {
                        handler.removeCallbacks(wpsTimeoutRunnable);
                        wpsTimeoutRunnable = null;
                    }
                    pairingState = PairingState.UNPAIRED;
                    pendingPairingMac = null;
                    setConnectionResult(pairingMac, WifiP2pManager.CONNECTION_REQUEST_REJECT);
                }
            };

            startWps.invoke(manager, channel, wpsConfig, wpsListener);
            Log.i(TAG, "WPS PBC initiated for " + deviceAddress);
        } catch (Throwable err) {
            Log.w(TAG, "startWps failed", err);
            if (wpsTimeoutRunnable != null) {
                handler.removeCallbacks(wpsTimeoutRunnable);
            }
            pairingState = PairingState.UNPAIRED;
            pendingPairingMac = null;
        }
    }

    /**
     * @return true when the list request was sent and {@code after} will run
     *     from that callback
     */
    private boolean requestPersistentGroupDeletion(Runnable after) {
        if (manager == null || channel == null) {
            return false;
        }
        try {
            Class<?> listenerClass = Class.forName(
                    "android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method.getName(), args);
                        }
                        if ("onPersistentGroupInfoAvailable".equals(method.getName())) {
                            Object groups = args == null || args.length == 0 ? null : args[0];
                            deletePersistentGroups(groups, after);
                        }
                        return null;
                    });
            Method request = WifiP2pManager.class.getMethod(
                    "requestPersistentGroupInfo",
                    WifiP2pManager.Channel.class,
                    listenerClass);
            request.invoke(manager, channel, listener);
            return true;
        } catch (Throwable err) {
            Log.w(TAG, "requestPersistentGroupInfo unavailable", err);
            return false;
        }
    }

    private void deletePersistentGroups(Object groupList, Runnable after) {
        try {
            int[] persistent = SavedP2pGroups.persistentNetworkIds(networkIdsOf(groupList));
            if (persistent.length == 0) {
                Log.i(TAG, "no persistent groups");
            } else {
                Method delete = WifiP2pManager.class.getMethod(
                        "deletePersistentGroup",
                        WifiP2pManager.Channel.class,
                        int.class,
                        WifiP2pManager.ActionListener.class);
                for (int netId : persistent) {
                    Log.i(TAG, "deletePersistentGroup netId=" + netId);
                    delete.invoke(
                            manager,
                            channel,
                            netId,
                            logged("deletePersistentGroup " + netId));
                }
            }
        } catch (Throwable err) {
            Log.w(TAG, "deletePersistentGroup failed", err);
        }
        if (after != null) {
            after.run();
        }
    }

    private static int[] networkIdsOf(Object groupList) throws Exception {
        if (groupList == null) {
            return new int[0];
        }
        Method getGroupList = groupList.getClass().getMethod("getGroupList");
        Object raw = getGroupList.invoke(groupList);
        if (!(raw instanceof Collection<?> groups)) {
            return new int[0];
        }
        int count = 0;
        for (Object group : groups) {
            if (group instanceof WifiP2pGroup) {
                count++;
            }
        }
        int[] ids = new int[count];
        int index = 0;
        for (Object group : groups) {
            if (group instanceof WifiP2pGroup wifiGroup) {
                ids[index++] = wifiGroup.getNetworkId();
            }
        }
        return ids;
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return proxy == (args != null && args.length > 0 ? args[0] : null);
        }
        if ("toString".equals(name)) {
            return "PersistentGroupInfoListener";
        }
        return null;
    }

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void watchPeer(WifiP2pDevice device) {
        if (device == null || device.deviceAddress == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API 30–32: no ExternalApprover. WifiP2pServiceImpl maps AUTO (-1)
            // → 14 when STA is on 5 GHz, so listen-only joins leave Phh as GO.
            // Explicit connect with groupOwnerIntent=0 skips that rewrite.
            maybeConnectAsClient(device);
            return;
        }
        // Only add external approver for devices in persistent groups (reinvocation).
        // For new connections, let system dialog handle user confirmation.
        if (!isPeerInPersistentGroup(device.deviceAddress)) {
            return;
        }
        if (!approverMacs.add(device.deviceAddress)) {
            return;
        }
        try {
            manager.addExternalApprover(channel, MacAddress.fromString(device.deviceAddress), approver);
        } catch (Throwable err) {
            approverMacs.remove(device.deviceAddress);
            Log.w(TAG, "addExternalApprover failed for " + device.deviceAddress, err);
        }
    }

    /**
     * Drive GO negotiation ourselves with {@code groupOwnerIntent=0} so Windows
     * can become GO. Only Miracast sources (Win+K) are chased.
     */
    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void maybeConnectAsClient(WifiP2pDevice device) {
        if (!wanted || groupTracker.isFormed() || manager == null || channel == null) {
            return;
        }
        WifiP2pWfdInfo wfd = device.getWfdInfo();
        if (wfd == null || !wfd.isEnabled()) {
            return;
        }
        int type = wfd.getDeviceType();
        if (type != WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE
                && type != WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK) {
            return;
        }
        if (!clientConnectAttempted.add(device.deviceAddress)) {
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 0;
        config.wps = new WpsInfo();
        config.wps.setup = WpsInfo.PBC;
        Log.i(TAG, "connect as client → " + device.deviceAddress
                + " name=\"" + device.deviceName + "\" goIntent=0");
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "connect-client started for " + device.deviceAddress);
                }

                @Override
                public void onFailure(int reason) {
                    clientConnectAttempted.remove(device.deviceAddress);
                    Log.w(TAG, "connect-client failed reason=" + reason
                            + " peer=" + device.deviceAddress);
                }
            });
        } catch (Throwable err) {
            clientConnectAttempted.remove(device.deviceAddress);
            Log.w(TAG, "connect as client failed", err);
        }
    }

    /**
     * Check if a peer MAC address is in one of our persistent groups.
     * Since we delete persistent groups on startup, this will typically be false.
     */
    private boolean isPeerInPersistentGroup(String macAddress) {
        return persistentGroupMacs.contains(macAddress);
    }

    private final Set<String> persistentGroupMacs = new HashSet<>();
    /** Peers we already issued {@link #maybeConnectAsClient} for (API &lt; 33). */
    private final Set<String> clientConnectAttempted = new HashSet<>();

    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void removeApprovers() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        for (String mac : approverMacs) {
            try {
                manager.removeExternalApprover(channel, MacAddress.fromString(mac), logged("removeExternalApprover"));
            } catch (Throwable ignored) {
            }
        }
        approverMacs.clear();
    }

    private final WifiP2pManager.ExternalApproverRequestListener approver =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ? null
                    : new WifiP2pManager.ExternalApproverRequestListener() {
                @Override
                public void onAttached(MacAddress deviceAddress) {
                    Log.d(TAG, "approver attached " + deviceAddress);
                }

                @Override
                public void onDetached(MacAddress deviceAddress, int reason) {
                    Log.i(TAG, "approver detached " + deviceAddress + " reason=" + reason);
                    // REPLACE means a second registration is already stored.
                    // REMOVE is how the framework drops the approver after ACCEPT.
                    if (reason == WifiP2pManager.ExternalApproverRequestListener
                            .APPROVER_DETACH_REASON_REPLACE) {
                        return;
                    }
                    if (deviceAddress != null) {
                        approverMacs.remove(deviceAddress.toString());
                    }
                    if (wanted) {
                        armApproverForPairedDevices();
                    }
                }

                @Override
                public void onConnectionRequested(int requestType, WifiP2pConfig config, WifiP2pDevice device) {
                    String address = device != null && device.deviceAddress != null
                            ? device.deviceAddress
                            : (config != null ? config.deviceAddress : null);
                    if (address == null) {
                        return;
                    }
                    // Prefer client (Windows as GO). Phh/MTK often wins GO with
                    // AUTO intent; Fold stays client. AOSP WifiDisplay uses MIN.
                    if (config != null) {
                        int before = config.groupOwnerIntent;
                        config.groupOwnerIntent = 0;
                        Log.i(TAG, "P2P connection request type=" + requestType + " from " + address
                                + (device != null ? " \"" + device.deviceName + "\"" : "")
                                + " pairingState=" + pairingState
                                + " goIntent " + before + "→0");
                    } else {
                        Log.i(TAG, "P2P connection request type=" + requestType + " from " + address
                                + (device != null ? " \"" + device.deviceName + "\"" : "")
                                + " pairingState=" + pairingState);
                    }

                    if (!wanted) {
                        setConnectionResult(address, WifiP2pManager.CONNECTION_REQUEST_REJECT);
                        return;
                    }

                    // For first-time pairing (UNPAIRED state), accept connection AND start WPS PBC
                    if (pairingState == PairingState.UNPAIRED) {
                        Log.i(TAG, "First-time connection: accepting + starting WPS PBC for " + address);
                        setConnectionResult(address, config, WifiP2pManager.CONNECTION_REQUEST_ACCEPT);
                        startWpsPbc(address);
                    } else {
                        // Already paired or reinvoking: accept directly
                        Log.i(TAG, "Reinvoking or paired connection: accepting directly");
                        setConnectionResult(address, config, WifiP2pManager.CONNECTION_REQUEST_ACCEPT);
                    }
                }

                @Override
                public void onPinGenerated(MacAddress deviceAddress, String pin) {
                    Log.i(TAG, "WPS PIN generated for " + deviceAddress);
                }
            };

    private void setConnectionResult(String address, int result) {
        setConnectionResult(address, null, result);
    }

    /**
     * Accept/reject a peer request. Prefer the overload that keeps our mutated
     * {@link WifiP2pConfig} (groupOwnerIntent=0) so Phh does not always become GO.
     */
    // Runs as shell UID via Shizuku/app_process; permission is granted to UID 2000, not the app UID.
    @SuppressLint("MissingPermission")
    private void setConnectionResult(String address, WifiP2pConfig config, int result) {
        try {
            MacAddress peer = MacAddress.fromString(address);
            if (config != null) {
                try {
                    Method withConfig = WifiP2pManager.class.getMethod(
                            "setConnectionRequestResult",
                            WifiP2pManager.Channel.class,
                            MacAddress.class,
                            int.class,
                            WifiP2pConfig.class,
                            WifiP2pManager.ActionListener.class);
                    withConfig.invoke(
                            manager,
                            channel,
                            peer,
                            result,
                            config,
                            logged("connection request result+config"));
                    return;
                } catch (NoSuchMethodException missing) {
                    // Older API: mutate config in place and use the 3-arg form.
                }
            }
            manager.setConnectionRequestResult(
                    channel, peer, result, logged("connection request result"));
        } catch (Throwable err) {
            Log.w(TAG, "setConnectionRequestResult failed", err);
        }
    }

    private void applyDeviceName(String name) {
        if (manager == null || channel == null || name == null || name.isEmpty()) {
            return;
        }
        // Wi-Fi Direct name only — never Settings.Global.DEVICE_NAME.
        try {
            Method setDeviceName = WifiP2pManager.class.getMethod(
                    "setDeviceName",
                    WifiP2pManager.Channel.class,
                    String.class,
                    WifiP2pManager.ActionListener.class);
            setDeviceName.invoke(manager, channel, name, logged("setDeviceName"));
        } catch (Throwable err) {
            Log.w(TAG, "setDeviceName unavailable", err);
        }
    }

    private static String hostOf(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    private static String p2pNeighbor() {
        Process process = null;
        try {
            process = new ProcessBuilder("ip", "-4", "neigh", "show")
                    .redirectErrorStream(true)
                    .start();
            // shortcut: a full pipe times out; add bounded concurrent draining if discovery needs it.
            if (!process.waitFor(NEIGHBOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "ip neigh timed out");
                return null;
            }
            if (process.exitValue() != 0) {
                Log.w(TAG, "ip neigh exited with " + process.exitValue());
                return null;
            }
            try (InputStream input = process.getInputStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int available;
                while ((available = input.available()) > 0) {
                    if (text.size() >= NEIGHBOR_OUTPUT_LIMIT) {
                        Log.w(TAG, "ip neigh output exceeded limit");
                        return null;
                    }
                    int count = input.read(buffer, 0, Math.min(available,
                            Math.min(buffer.length, NEIGHBOR_OUTPUT_LIMIT - text.size())));
                    if (count <= 0) {
                        break;
                    }
                    text.write(buffer, 0, count);
                }
                return parseAvailableP2pNeighbor(text.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "ip neigh interrupted", err);
            return null;
        } catch (Exception err) {
            Log.w(TAG, "ip neigh failed", err);
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Exception err) {
                    Log.w(TAG, "ip neigh destroy failed", err);
                }
                try (InputStream output = process.getInputStream();
                        InputStream error = process.getErrorStream();
                        OutputStream input = process.getOutputStream()) {
                    // Closing all three pipes also runs when one close fails.
                } catch (Exception err) {
                    Log.w(TAG, "ip neigh pipe cleanup failed", err);
                }
            }
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context ctx = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        // AttributionSource arrived in API 31; on Android 11 only the package
        // name fields exist and must be enough for shell-UID WifiP2pManager.
        if (Build.VERSION.SDK_INT >= 31) {
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderClass.getConstructor(int.class).newInstance(2000);
            builderClass.getMethod("setPackageName", String.class)
                    .invoke(builder, "com.android.shell");
            Object shellSource = builderClass.getMethod("build").invoke(builder);
            setField(ctx, "mAttributionSource", shellSource);
        }
        setField(ctx, "mOpPackageName", "com.android.shell");
        setField(ctx, "mBasePackageName", "com.android.shell");
        return ctx;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static WifiP2pManager.ActionListener logged(final String label) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, label + " success");
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, label + " failed reason=" + reason);
            }
        };
    }
}
