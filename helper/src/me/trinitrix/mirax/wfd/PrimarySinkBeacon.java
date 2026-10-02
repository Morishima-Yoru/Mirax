package me.trinitrix.mirax.wfd;

import android.content.AttributionSource;
import android.content.Context;
import android.net.MacAddress;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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
    private final Set<String> approverMacs = new HashSet<>();
    private Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private volatile boolean wanted;
    private volatile boolean advertising;
    private volatile String groupState = GROUP_DOWN;
    private volatile String lastName = "";
    private boolean groupFormed;
    private boolean forgetSavedGroups;
    private long lastArmedAt;
    private volatile PairingState pairingState = PairingState.UNPAIRED;
    private String pendingPairingMac = null;
    private Runnable wpsTimeoutRunnable = null;

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
    }

    /**
     * Initialise Wi-Fi P2P with a shell attribution context.
     *
     * @return true when the P2P channel is ready
     */
    public boolean initialize() {
        try {
            context = systemContext();
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
            lastName = broadcastName == null ? "" : broadcastName;
            applyDeviceName(lastName);
            if (!wanted) {
                wanted = true;
                groupFormed = false;
                groupState = GROUP_DOWN;
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
            groupFormed = false;
            groupState = GROUP_DOWN;
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
        return groupState;
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

    private void pollOnce() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, this::onConnectionInfo);
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
        if (!groupFormed && SystemClock.elapsedRealtime() - lastArmedAt >= RELISTEN_INTERVAL_MS) {
            armSink();
        }
    }

    private void onConnectionInfo(WifiP2pInfo info) {
        if (!wanted) {
            return;
        }
        boolean formed = info != null && info.groupFormed;
        if (!formed) {
            if (groupFormed) {
                Log.i(TAG, "P2P group down");
                groupFormed = false;
                groupState = GROUP_DOWN;
                // Do NOT forget saved groups here - preserve persistent group for reinvocation
                // forgetSavedGroups is only set to true when user explicitly calls forgetAllPairings()
                armSink();
            }
            return;
        }
        if (!groupFormed) {
            groupFormed = true;
            groupState = GROUP_PENDING;
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
        if (groupState.startsWith(GROUP_UP_PREFIX)) {
            return;
        }
        String source = info.isGroupOwner ? p2pNeighbor() : hostOf(info.groupOwnerAddress);
        if (source != null) {
            groupState = GROUP_UP_PREFIX + source;
            Log.i(TAG, "source address " + source);
        }
    }

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
        manager.setWfdInfo(channel, wfd, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (announce) {
                    Log.i(TAG, "setWfdInfo success");
                }
                admitThenListen(announce);
            }

            @Override
            public void onFailure(int reason) {
                advertising = false;
                Log.e(TAG, "setWfdInfo FAILED reason=" + reason);
            }
        });
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

    private void clearWfdInfo() {
        try {
            final WifiP2pWfdInfo wfd = new WifiP2pWfdInfo();
            wfd.setEnabled(false);
            manager.setWfdInfo(channel, wfd, logged("clearWfdInfo"));
        } catch (Throwable err) {
            Log.w(TAG, "clearWfdInfo failed", err);
        }
    }

    private void removeGroupQuietly(String label) {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.removeGroup(channel, logged(label));
        } catch (Throwable ignored) {
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
     * Arm the approver based on pairing state, then listen.
     * - UNPAIRED: Do NOT register approver. Let system dialog + WPS PBC handle pairing.
     * - PAIRED: Register broadcast approver for reinvocation via persistent group.
     */
    private void admitThenListen(boolean announce) {
        if (pairingState == PairingState.PAIRED) {
            // For paired state, register broadcast approver for reinvocation
            armApproverForPairedDevices();
        } else {
            // UNPAIRED: Do NOT register broadcast approver.
            // Let Android show system confirmation dialog, then trigger WPS PBC.
            // Windows DAF requires WPS PBC completion to populate WifiDirectDisplay
            // dynamic target and allow inbound RTSP 7236.
            Log.i(TAG, "UNPAIRED state: allowing system dialog + WPS PBC for Windows trust pairing");
        }
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

    private void startListening(boolean announce) {
        if (!wanted || manager == null || channel == null) {
            return;
        }
        manager.startListening(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (!advertising) {
                    Log.i(TAG, "startListening success; Primary Sink on port "
                            + RTSP_CONTROL_PORT);
                }
                advertising = true;
            }

            @Override
            public void onFailure(int reason) {
                if (announce) {
                    advertising = false;
                    Log.e(TAG, "startListening failed reason=" + reason);
                }
            }
        });
    }

    /**
     * Register external approver ONLY for PAIRED devices (reinvocation).
     * For UNPAIRED state, we do NOT register broadcast approver - this allows
     * the system dialog to show for proper WPS PBC pairing with Windows.
     * Windows requires WPS PBC completion to populate WifiDirectDisplay
     * dynamic target and allow inbound RTSP on port 7236.
     */
    private void armApproverForPairedDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || manager == null || channel == null || !wanted) {
            return;
        }
        // Only register broadcast approver for PAIRED state (reinvocation)
        if (pairingState != PairingState.PAIRED) {
            Log.d(TAG, "Not PAIRED, skipping broadcast approver to allow WPS PBC flow");
            return;
        }
        String mac = MacAddress.BROADCAST_ADDRESS.toString();
        if (!approverMacs.add(mac)) {
            return;
        }
        try {
            manager.addExternalApprover(channel, MacAddress.BROADCAST_ADDRESS, approver);
            Log.i(TAG, "addExternalApprover for reinvocation " + mac);
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

    private void watchPeer(WifiP2pDevice device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || device == null || device.deviceAddress == null) {
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
     * Check if a peer MAC address is in one of our persistent groups.
     * Since we delete persistent groups on startup, this will typically be false.
     */
    private boolean isPeerInPersistentGroup(String macAddress) {
        return persistentGroupMacs.contains(macAddress);
    }

    private final Set<String> persistentGroupMacs = new HashSet<>();

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
                    Log.i(TAG, "P2P connection request type=" + requestType + " from " + address
                            + (device != null ? " \"" + device.deviceName + "\"" : "")
                            + " pairingState=" + pairingState);

                    if (!wanted) {
                        setConnectionResult(address, WifiP2pManager.CONNECTION_REQUEST_REJECT);
                        return;
                    }

                    // For first-time pairing (UNPAIRED state), accept connection AND start WPS PBC
                    if (pairingState == PairingState.UNPAIRED) {
                        Log.i(TAG, "First-time connection: accepting + starting WPS PBC for " + address);
                        setConnectionResult(address, WifiP2pManager.CONNECTION_REQUEST_ACCEPT);
                        startWpsPbc(address);
                    } else {
                        // Already paired or reinvoking: accept directly
                        Log.i(TAG, "Reinvoking or paired connection: accepting directly");
                        setConnectionResult(address, WifiP2pManager.CONNECTION_REQUEST_ACCEPT);
                    }
                }

                @Override
                public void onPinGenerated(MacAddress deviceAddress, String pin) {
                    Log.i(TAG, "WPS PIN generated for " + deviceAddress);
                }
            };

    private void setConnectionResult(String address, int result) {
        try {
            manager.setConnectionRequestResult(
                    channel, MacAddress.fromString(address), result, logged("connection request result"));
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
        try {
            Process process = new ProcessBuilder("ip", "-4", "neigh", "show")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String row;
                while ((row = reader.readLine()) != null) {
                    text.append(row).append('\n');
                }
            }
            process.waitFor();
            return parseP2pNeighbor(text.toString());
        } catch (Exception err) {
            Log.w(TAG, "ip neigh failed", err);
            return null;
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context ctx = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        AttributionSource shellSource = new AttributionSource.Builder(2000)
                .setPackageName("com.android.shell")
                .build();
        setField(ctx, "mAttributionSource", shellSource);
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
