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
 * group's source address. A broadcast-address approver accepts a source that
 * is not already in the peer list, and saved P2P groups are deleted so a new
 * computer is not stuck reinvoking the original development machine. Must run
 * as shell (UID 2000) or root — never from the Mirax app process. Does not
 * touch Samsung SmartMirroring or SecondScreenPlayer.
 */
public final class PrimarySinkBeacon {
    private static final String TAG = "MiraxWfdBeacon";
    /** Miracast RTSP control port advertised in the WFD IE. */
    public static final int RTSP_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT_MBPS = 50;
    private static final long POLL_INTERVAL_MS = 1000;
    /** Android shuts an idle P2P interface down after 150 s; re-arm well before that. */
    private static final long RELISTEN_INTERVAL_MS = 30_000;

    /** No P2P group is formed. */
    public static final String GROUP_DOWN = "DOWN";
    /** A group is formed but the source address is not known yet. */
    public static final String GROUP_PENDING = "PENDING";
    /** Prefix of the group state once the source address is known: {@code UP <ipv4>}. */
    public static final String GROUP_UP_PREFIX = "UP ";

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
            if (!groupFormed) {
                forgetSavedGroups = true;
            }
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
                forgetSavedGroups = true;
                armSink();
            }
            return;
        }
        if (!groupFormed) {
            groupFormed = true;
            groupState = GROUP_PENDING;
            Log.i(TAG, "P2P group up phoneIsOwner=" + info.isGroupOwner
                    + " owner=" + info.groupOwnerAddress);
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
        final boolean announce = !advertising;
        if (announce) {
            Log.i(TAG, "WFD info " + wfd + " name=\"" + lastName + "\"");
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
     * Arm the all-sources approver, drop saved groups when this arm asked for
     * it, then listen. Listening waits until the delete requests are queued so
     * a source cannot reinvoke a group that is about to disappear.
     */
    private void admitThenListen(boolean announce) {
        armAllSourcesApprover();
        Runnable listen = () -> startListening(announce);
        if (!forgetSavedGroups) {
            listen.run();
            return;
        }
        forgetSavedGroups = false;
        if (!requestPersistentGroupDeletion(listen)) {
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
     * Accept a connection from any source.
     *
     * Android delivers a new negotiation or invitation to the approver for
     * that MAC, and falls back to {@link MacAddress#BROADCAST_ADDRESS} when
     * none is registered. Without the fallback the system confirmation dialog
     * is shown, and this shell process cannot press it. The framework removes
     * the approver after the result, so {@code onDetached} arms it again.
     */
    private void armAllSourcesApprover() {
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
            Log.i(TAG, "addExternalApprover " + mac);
        } catch (Throwable err) {
            approverMacs.remove(mac);
            Log.w(TAG, "addExternalApprover failed for " + mac, err);
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
                        armAllSourcesApprover();
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
                    int result = wanted
                            ? WifiP2pManager.CONNECTION_REQUEST_ACCEPT
                            : WifiP2pManager.CONNECTION_REQUEST_REJECT;
                    Log.i(TAG, "P2P connection request type=" + requestType + " from " + address
                            + (device != null ? " \"" + device.deviceName + "\"" : "")
                            + (wanted ? " accepted" : " rejected"));
                    try {
                        manager.setConnectionRequestResult(
                                channel, MacAddress.fromString(address), result, logged("connection request result"));
                    } catch (Throwable err) {
                        Log.w(TAG, "setConnectionRequestResult failed", err);
                    }
                }

                @Override
                public void onPinGenerated(MacAddress deviceAddress, String pin) {
                    Log.i(TAG, "WPS PIN generated for " + deviceAddress);
                }
            };

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
