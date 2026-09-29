package com.secondscreen.receiver;

import android.content.AttributionSource;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.secondscreen.wfd.Capabilities;
import com.secondscreen.wfd.MpegTsDepacketizer;
import com.secondscreen.wfd.RtspSession;
import com.secondscreen.wfd.SpsParser;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Shell-UID Miracast sink. Advertises a Wi-Fi Display primary sink, speaks RTSP, and forwards
 * H.264 access units to {@link PlayerActivity}.
 */
public class MiracastReceiver {
    static final String TAG = "MiracastRx";
    static final int RTSP_PORT = 7236;
    static final int PLAYER_PORT = 19723;
    static final int CONTROL_PORT = 19724;

    static Context context;
    static WifiP2pManager manager;
    static WifiP2pManager.Channel channel;
    static Handler handler;
    static volatile boolean running = true;
    static volatile boolean broadcasting = false;
    static volatile String wfdState = "unknown";
    static volatile String link = "idle";
    static volatile String videoText = "";
    static volatile String wifiState = "0";
    static volatile String deviceName = "";
    static volatile RtspSession session = new RtspSession();
    static volatile MpegTsDepacketizer demux = new MpegTsDepacketizer();
    static volatile DatagramSocket rtpSocket;
    static volatile int rtpPackets;
    static volatile int accessUnits;
    static volatile boolean spsLogged;
    static final HashSet<String> approverMacs = new HashSet<String>();
    static final PlayerLink player = new PlayerLink();
    static final WifiP2pManager.ExternalApproverRequestListener approver =
            new WifiP2pManager.ExternalApproverRequestListener() {
                @Override
                public void onAttached(MacAddress deviceAddress) {
                    log("approver attached " + deviceAddress);
                }

                @Override
                public void onDetached(MacAddress deviceAddress, int reason) {
                    log("approver detached " + deviceAddress + " reason=" + reason);
                    if (reason == WifiP2pManager.ExternalApproverRequestListener
                            .APPROVER_DETACH_REASON_REPLACE) {
                        return;
                    }
                    if (deviceAddress != null) {
                        synchronized (approverMacs) {
                            approverMacs.remove(deviceAddress.toString());
                        }
                    }
                    if (broadcasting) {
                        armAllSourcesApprover();
                    }
                }

                @Override
                public void onConnectionRequested(int requestType, WifiP2pConfig config, WifiP2pDevice device) {
                    String address = device != null && device.deviceAddress != null
                            ? device.deviceAddress
                            : (config != null ? config.deviceAddress : "");
                    log("P2P request type=" + requestType + " from " + address
                            + (device != null ? " name=" + device.deviceName : ""));
                    try {
                        manager.setConnectionRequestResult(
                                channel,
                                MacAddress.fromString(address),
                                WifiP2pManager.CONNECTION_REQUEST_ACCEPT,
                                loggedAction("accept"));
                    } catch (Throwable t) {
                        log("accept failed " + t);
                    }
                }

                @Override
                public void onPinGenerated(MacAddress deviceAddress, String pin) {
                    log("WPS pin for " + deviceAddress + " " + pin);
                }
            };

    public static void main(String[] args) {
        log("Miracast receiver starting, uid=" + android.os.Process.myUid());
        try {
            Looper.prepareMainLooper();
        } catch (Exception ignored) {
        }
        handler = new Handler(Looper.getMainLooper());
        try {
            context = systemContext();
            manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) {
                log("WifiP2pManager is null");
                return;
            }
            channel = manager.initialize(context, Looper.getMainLooper(), new WifiP2pManager.ChannelListener() {
                @Override
                public void onChannelDisconnected() {
                    log("Wi-Fi Direct channel disconnected");
                }
            });
            startControl();
            startRtsp();
            startRtp(com.secondscreen.wfd.Capabilities.RTP_PORT);
            manager.requestDeviceInfo(channel, new WifiP2pManager.DeviceInfoListener() {
                @Override
                public void onDeviceInfoAvailable(WifiP2pDevice wifiP2pDevice) {
                    if (wifiP2pDevice != null && wifiP2pDevice.deviceName != null) {
                        deviceName = wifiP2pDevice.deviceName;
                        log("device " + deviceName);
                    }
                }
            });
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!running) {
                        return;
                    }
                    pollGroup();
                    handler.postDelayed(this, 1000);
                }
            }, 1000);
            log("idle until the app allows broadcast, uid=" + android.os.Process.myUid());
            Looper.loop();
        } catch (Throwable t) {
            log("fatal " + t);
            t.printStackTrace();
        }
    }

    static synchronized void setBroadcast(final boolean on) {
        if (on == broadcasting) {
            return;
        }
        broadcasting = on;
        if (!on) {
            link = "idle";
            videoText = "";
        } else {
            link = "advertising";
        }
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (on && broadcasting) {
                    prepareDisplay();
                    armSink();
                } else if (!on && !broadcasting) {
                    try {
                        manager.stopListening(channel, loggedAction("stopListening"));
                        manager.removeGroup(channel, loggedAction("removeGroup"));
                    } catch (Throwable t) {
                        log("stop broadcast " + t.getMessage());
                    }
                    closeRtsp();
                    exec("wm", "user-rotation", "-d", "0", "free");
                    exec("wm", "set-ignore-orientation-request", "-d", "0", "reset");
                    log("broadcast off");
                }
            }
        });
    }

    static void endSession() {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.removeGroup(channel, loggedAction("endSession"));
                } catch (Throwable t) {
                    log("end session " + t.getMessage());
                }
                videoText = "";
                if (broadcasting) {
                    link = "advertising";
                    admitThenListen(new Runnable() {
                        @Override
                        public void run() {
                            manager.startListening(channel, loggedAction("relisten"));
                        }
                    });
                }
            }
        });
    }

    static String statusLine() {
        String name = deviceName.length() == 0 ? "ZFold5" : deviceName.replace(' ', '_');
        return "uid=" + android.os.Process.myUid()
                + " wfd=" + wfdState
                + " broadcast=" + (broadcasting ? "1" : "0")
                + " link=" + link
                + " wifi=" + wifiState
                + " video=" + (videoText.length() == 0 ? "none" : videoText)
                + " name=" + name;
    }

    static void startControl() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                controlLoop();
            }
        }, "miracast-control");
        thread.setDaemon(true);
        thread.start();
    }

    static void controlLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", CONTROL_PORT), 400);
                socket.setSoTimeout(800);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String command = reader.readLine();
                if ("BROADCAST 1".equals(command)) {
                    setBroadcast(true);
                } else if ("BROADCAST 0".equals(command)) {
                    setBroadcast(false);
                } else if ("END".equals(command)) {
                    endSession();
                } else if ("IDR".equals(command)) {
                    idrPending = true;
                }
                socket.getOutputStream().write((statusLine() + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (Exception ignored) {
                sleep(500);
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            sleep(400);
        }
    }

    static void prepareDisplay() {
        log(exec("am", "force-stop", "com.samsung.android.smartmirroring").trim());
        log(exec("wm", "set-ignore-orientation-request", "-d", "0", "false").trim());
        log(exec("wm", "user-rotation", "-d", "0", "lock", "1").trim());
        log(exec("input", "keyevent", "224").trim());
        log(exec("wm", "dismiss-keyguard").trim());
        String displays = exec("dumpsys", "window", "displays");
        int idx = displays.indexOf("cur=2176x1812");
        log(idx >= 0 ? "panel logical size is 2176x1812" : "panel rotation did not report 2176x1812");
        log(exec("am", "start", "-n", "com.secondscreen.receiver/.SetupActivity").trim());
    }

    static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context ctx = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        AttributionSource shellSource = new AttributionSource.Builder(2000)
                .setPackageName("com.android.shell")
                .build();
        setField(ctx, "mAttributionSource", shellSource);
        setField(ctx, "mOpPackageName", "com.android.shell");
        setField(ctx, "mBasePackageName", "com.android.shell");
        try {
            Settings.Global.putInt(ctx.getContentResolver(), "wifi_display_on", 1);
        } catch (Throwable t) {
            log("wifi_display_on " + t.getMessage());
        }
        return ctx;
    }

    static void armSink() {
        manager.removeGroup(channel, loggedAction("removeGroup"));
        final android.net.wifi.p2p.WifiP2pWfdInfo wfd = new android.net.wifi.p2p.WifiP2pWfdInfo();
        wfd.setEnabled(true);
        wfd.setDeviceType(android.net.wifi.p2p.WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
        wfd.setSessionAvailable(true);
        wfd.setControlPort(RTSP_PORT);
        wfd.setMaxThroughput(50);
        wfd.setContentProtectionSupported(false);
        log("WFD info " + wfd);
        manager.setWfdInfo(channel, wfd, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                wfdState = "ok";
                log("setWfdInfo success");
                admitThenListen(new Runnable() {
                    @Override
                    public void run() {
                        manager.startListening(channel, new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                link = "advertising";
                                log("startListening success; phone is a Miracast sink on port " + RTSP_PORT);
                                manager.requestDeviceInfo(channel, new WifiP2pManager.DeviceInfoListener() {
                                    @Override
                                    public void onDeviceInfoAvailable(WifiP2pDevice wifiP2pDevice) {
                                        if (wifiP2pDevice != null) {
                                            log("visible as \"" + wifiP2pDevice.deviceName + "\" "
                                                    + wifiP2pDevice.deviceAddress);
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onFailure(int reason) {
                                log("startListening failed " + reason + ", discoverPeers");
                                manager.discoverPeers(channel, loggedAction("discoverPeers"));
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                wfdState = "denied";
                link = "idle";
                log("setWfdInfo FAILED reason=" + reason);
            }
        });
    }

    static int pollCount;
    static boolean groupUp;
    static boolean played;
    static volatile boolean idrPending;
    static long playerGoneAt;
    static boolean hadPlayer;

    static void pollGroup() {
        pollCount++;
        try {
            manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup group) {
                    boolean up = group != null;
                    if (up != groupUp) {
                        groupUp = up;
                        if (up) {
                            played = false;
                            link = "connected";
                            log("P2P group up iface=" + group.getInterface()
                                    + " owner=" + group.isGroupOwner()
                                    + " name=" + group.getNetworkName());
                            bindKnownP2p();
                            if (!group.isGroupOwner()) {
                                dialSource();
                            }
                        } else {
                            log("P2P group down");
                            closeRtsp();
                            if (broadcasting && !played && !Capabilities.offerCommonModes) {
                                Capabilities.offerCommonModes = true;
                                log("2176x1812 was not kept; next connection also offers 1080p60 and 720p60");
                            }
                            played = false;
                            if (broadcasting) {
                                link = "advertising";
                                admitThenListen(new Runnable() {
                                    @Override
                                    public void run() {
                                        manager.startListening(channel, loggedAction("relisten"));
                                    }
                                });
                            } else {
                                link = "idle";
                            }
                        }
                    }
                }
            });
            manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    if (peers == null) {
                        return;
                    }
                    for (WifiP2pDevice device : peers.getDeviceList()) {
                        watchPeer(device);
                    }
                }
            });
            bindKnownP2p();
            if (pollCount % 5 == 0) {
                String wifi = exec("cmd", "wifi", "status");
                wifiState = wifi.indexOf("Wifi is enabled") >= 0 ? "1" : "0";
            }
        } catch (Throwable t) {
            log("poll " + t.getMessage());
        }
    }

    /**
     * Register the broadcast-address approver and delete every saved P2P group,
     * then run {@code thenListen}. A source that is not already in the peer
     * list is accepted by that approver instead of the system dialog.
     */
    static void admitThenListen(Runnable thenListen) {
        armAllSourcesApprover();
        if (!requestPersistentGroupDeletion(thenListen) && thenListen != null) {
            thenListen.run();
        }
    }

    static void armAllSourcesApprover() {
        if (manager == null || channel == null) {
            return;
        }
        String mac = MacAddress.BROADCAST_ADDRESS.toString();
        synchronized (approverMacs) {
            if (!approverMacs.add(mac)) {
                return;
            }
        }
        log("addExternalApprover " + mac);
        try {
            manager.addExternalApprover(channel, MacAddress.BROADCAST_ADDRESS, approver);
        } catch (Throwable t) {
            synchronized (approverMacs) {
                approverMacs.remove(mac);
            }
            log("addExternalApprover " + t);
        }
    }

    static boolean requestPersistentGroupDeletion(final Runnable after) {
        if (manager == null || channel == null) {
            return false;
        }
        try {
            final Class<?> listenerClass = Class.forName(
                    "android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("hashCode".equals(method.getName())) {
                                    return System.identityHashCode(proxy);
                                }
                                if ("equals".equals(method.getName())) {
                                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                                }
                                if ("toString".equals(method.getName())) {
                                    return "PersistentGroupInfoListener";
                                }
                                return null;
                            }
                            if ("onPersistentGroupInfoAvailable".equals(method.getName())) {
                                Object groups = args == null || args.length == 0 ? null : args[0];
                                deletePersistentGroups(groups, after);
                            }
                            return null;
                        }
                    });
            Method request = WifiP2pManager.class.getMethod(
                    "requestPersistentGroupInfo",
                    WifiP2pManager.Channel.class,
                    listenerClass);
            request.invoke(manager, channel, listener);
            return true;
        } catch (Throwable t) {
            log("requestPersistentGroupInfo " + t);
            return false;
        }
    }

    static void deletePersistentGroups(Object groupList, Runnable after) {
        try {
            int[] ids = networkIdsOf(groupList);
            if (ids.length == 0) {
                log("no persistent groups");
            } else {
                Method delete = WifiP2pManager.class.getMethod(
                        "deletePersistentGroup",
                        WifiP2pManager.Channel.class,
                        int.class,
                        WifiP2pManager.ActionListener.class);
                for (int netId : ids) {
                    if (netId < 0) {
                        continue;
                    }
                    log("deletePersistentGroup netId=" + netId);
                    delete.invoke(manager, channel, netId, loggedAction("deletePersistentGroup " + netId));
                }
            }
        } catch (Throwable t) {
            log("deletePersistentGroup " + t);
        }
        if (after != null) {
            after.run();
        }
    }

    static int[] networkIdsOf(Object groupList) throws Exception {
        if (groupList == null) {
            return new int[0];
        }
        Method getGroupList = groupList.getClass().getMethod("getGroupList");
        Object raw = getGroupList.invoke(groupList);
        if (!(raw instanceof Collection<?>)) {
            return new int[0];
        }
        Collection<?> groups = (Collection<?>) raw;
        int count = 0;
        for (Object group : groups) {
            if (group instanceof WifiP2pGroup) {
                count++;
            }
        }
        int[] ids = new int[count];
        int index = 0;
        for (Object group : groups) {
            if (group instanceof WifiP2pGroup) {
                ids[index++] = ((WifiP2pGroup) group).getNetworkId();
            }
        }
        return ids;
    }

    static void watchPeer(WifiP2pDevice device) {
        if (device == null || device.deviceAddress == null) {
            return;
        }
        synchronized (approverMacs) {
            if (!approverMacs.add(device.deviceAddress)) {
                return;
            }
        }
        log("peer " + device.deviceName + " " + device.deviceAddress + " wfd=" + device.getWfdInfo());
        try {
            manager.addExternalApprover(channel, MacAddress.fromString(device.deviceAddress), approver);
        } catch (Throwable t) {
            log("addExternalApprover " + t);
        }
    }

    static void startRtsp() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                rtspLoop();
            }
        }, "miracast-rtsp");
        thread.setDaemon(true);
        thread.start();
    }

    static void rtspLoop() {
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(RTSP_PORT));
            log("RTSP listening 0.0.0.0:" + RTSP_PORT);
        } catch (Exception ex) {
            log("RTSP bind failed " + ex);
            return;
        }
        while (running) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setTcpNoDelay(true);
                bindP2p(socket);
                log("RTSP client " + socket.getRemoteSocketAddress());
                session = new RtspSession();
                demux = new MpegTsDepacketizer();
                spsLogged = false;
                player.reset();
                speak(socket);
            } catch (Exception ex) {
                log("RTSP session ended " + ex);
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
                log("RTSP client closed");
            }
        }
    }

    static volatile boolean dialing;
    static volatile Socket rtspClient;

    static void closeRtsp() {
        Socket socket = rtspClient;
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    static void dialSource() {
        if (dialing) {
            return;
        }
        dialing = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                try {
                    String host = null;
                    for (int attempt = 0; attempt < 12 && host == null; attempt++) {
                        host = gatewayFromP2p();
                        if (host == null) {
                            Thread.sleep(400);
                        }
                    }
                    if (host == null) {
                        log("no group owner address");
                        return;
                    }
                    log("dial RTSP " + host + ":" + RTSP_PORT);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, RTSP_PORT), 4000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(2000);
                    log("dialed " + socket.getRemoteSocketAddress());
                    rtspClient = socket;
                    session = new RtspSession();
                    demux = new MpegTsDepacketizer();
                    spsLogged = false;
                    player.reset();
                    speak(socket);
                } catch (Exception ex) {
                    log("dial failed " + ex);
                } finally {
                    if (rtspClient == socket) {
                        rtspClient = null;
                    }
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                    dialing = false;
                }
            }
        }, "miracast-dial");
        thread.setDaemon(true);
        thread.start();
    }

    static String gatewayFromP2p() {
        String text = exec("ip", "-4", "addr");
        int mark = text.indexOf("p2p");
        if (mark < 0) {
            return null;
        }
        int inet = text.indexOf("inet ", mark);
        if (inet < 0) {
            return null;
        }
        int slash = text.indexOf('/', inet);
        if (slash < 0) {
            return null;
        }
        String ip = text.substring(inet + 5, slash).trim();
        int dot = ip.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return ip.substring(0, dot) + ".1";
    }

    static void speak(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        boolean sawData = false;
        boolean poked = false;
        while (running) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException timeout) {
                if (!sawData && !poked) {
                    poked = true;
                    String poke = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n";
                    out.write(poke.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    log("RTSP poke OPTIONS");
                }
                writeIdrIfNeeded(out);
                continue;
            }
            if (n < 0) {
                return;
            }
            sawData = true;
            pending.write(buf, 0, n);
            byte[] message;
            while ((message = takeRtsp(pending)) != null) {
                String text = new String(message, StandardCharsets.US_ASCII);
                log("RTSP << " + oneLine(text));
                List<String> replies = session.handle(text);
                for (int i = 0; i < replies.size(); i++) {
                    String reply = replies.get(i);
                    log("RTSP >> " + oneLine(reply));
                    out.write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
                if (session.formatChosen()) {
                    videoText = session.width() + "x" + session.height() + "@" + session.fps();
                    link = "connected";
                    log("NEGOTIATED " + videoText);
                    player.ensureFormat(session.width(), session.height(), session.fps());
                }
                if (session.rtpPort() != currentRtpPort()) {
                    startRtp(session.rtpPort());
                }
                if ("PLAYING".equals(session.state())) {
                    played = true;
                    player.ensureFormat(session.width(), session.height(), session.fps());
                    log("PLAYING " + session.width() + "x" + session.height() + "@" + session.fps());
                }
            }
            writeIdrIfNeeded(out);
        }
    }

    static void writeIdrIfNeeded(OutputStream out) {
        if (!idrPending || session == null) {
            return;
        }
        String request = session.requestIdr();
        if (request.length() == 0) {
            return;
        }
        idrPending = false;
        try {
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            log("RTSP >> IDR refresh");
        } catch (Exception ex) {
            idrPending = true;
            log("idr request failed " + ex.getMessage());
        }
    }

    static byte[] takeRtsp(ByteArrayOutputStream pending) {
        byte[] data = pending.toByteArray();
        int headerEnd = indexOfCrlf(data);
        if (headerEnd < 0) {
            return null;
        }
        int bodyStart = headerEnd + 4;
        String header = new String(data, 0, headerEnd, StandardCharsets.US_ASCII);
        int contentLength = 0;
        String[] lines = header.split("\r\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() > 15 && line.substring(0, 15).equalsIgnoreCase("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (contentLength < 0 || contentLength > 1024 * 1024) {
            pending.reset();
            return null;
        }
        if (data.length < bodyStart + contentLength) {
            return null;
        }
        int total = bodyStart + contentLength;
        byte[] message = new byte[total];
        System.arraycopy(data, 0, message, 0, total);
        pending.reset();
        if (total < data.length) {
            pending.write(data, total, data.length - total);
        }
        return message;
    }

    static int indexOfCrlf(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    static void startRtp(final int port) {
        DatagramSocket previous = rtpSocket;
        if (previous != null && previous.getLocalPort() == port && !previous.isClosed()) {
            return;
        }
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setReceiveBufferSize(2 * 1024 * 1024);
            bindP2p(socket);
            rtpSocket = socket;
            if (previous != null) {
                previous.close();
            }
            log("RTP listening " + port);
        } catch (Exception ex) {
            log("RTP bind " + port + " failed " + ex);
            return;
        }
        if (previous == null) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    rtpLoop();
                }
            }, "miracast-rtp");
            thread.setDaemon(true);
            thread.start();
        }
    }

    static int currentRtpPort() {
        DatagramSocket socket = rtpSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    static void rtpLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramSocket socket = rtpSocket;
            if (socket == null) {
                sleep(100);
                continue;
            }
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (Exception ex) {
                sleep(50);
                continue;
            }
            rtpPackets++;
            MpegTsDepacketizer current = demux;
            current.pushRtp(packet.getData(), packet.getLength());
            byte[] au;
            while ((au = current.poll()) != null) {
                accessUnits++;
                if (!spsLogged) {
                    SpsParser.Size size = SpsParser.parseAnnexB(au);
                    if (size != null) {
                        spsLogged = true;
                        videoText = size.width + "x" + size.height;
                        log("SPS " + videoText);
                        player.ensureFormat(size.width, size.height, session.fps());
                    }
                }
                player.sendAu(au);
                if (accessUnits == 1 || accessUnits % 60 == 0) {
                    log("AU " + accessUnits + " rtp=" + rtpPackets + " bytes=" + au.length);
                }
            }
        }
    }

    static void bindKnownP2p() {
        DatagramSocket socket = rtpSocket;
        if (socket != null) {
            bindP2p(socket);
        }
    }

    static int bindLogs;

    static void bindP2p(Object socket) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            Network[] networks = cm.getAllNetworks();
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < networks.length; i++) {
                LinkProperties props = cm.getLinkProperties(networks[i]);
                if (props == null || props.getInterfaceName() == null) {
                    continue;
                }
                seen.append(' ').append(props.getInterfaceName());
                if (props.getInterfaceName().toLowerCase().indexOf("p2p") < 0) {
                    continue;
                }
                if (socket instanceof Socket) {
                    networks[i].bindSocket((Socket) socket);
                } else if (socket instanceof DatagramSocket) {
                    networks[i].bindSocket((DatagramSocket) socket);
                }
                StringBuilder addresses = new StringBuilder();
                for (LinkAddress address : props.getLinkAddresses()) {
                    addresses.append(' ').append(address);
                }
                log("bound " + props.getInterfaceName() + addresses);
                return;
            }
            if (groupUp && bindLogs++ % 10 == 0) {
                log("no p2p network in ConnectivityManager:" + seen);
            }
        } catch (Throwable t) {
            log("bindP2p " + t.getMessage());
        }
    }

    static final class PlayerLink {
        Socket socket;
        DataOutputStream out;
        boolean headerSent;
        int w = com.secondscreen.wfd.Capabilities.WIDTH;
        int h = com.secondscreen.wfd.Capabilities.HEIGHT;
        int fps = com.secondscreen.wfd.Capabilities.FPS;

        synchronized void reset() {
            headerSent = false;
            close();
        }

        synchronized void ensureFormat(int width, int height, int rate) {
            boolean changed = headerSent && (width != w || height != h || rate != fps);
            w = width;
            h = height;
            fps = rate;
            try {
                connect();
                if (!headerSent) {
                    out.write(new byte[] {'W', 'F', 'D', '1'});
                    out.writeShort(w);
                    out.writeShort(h);
                    out.writeShort(fps);
                    out.flush();
                    headerSent = true;
                    log("player format " + w + "x" + h + "@" + fps);
                } else if (changed) {
                    out.writeInt(-1);
                    out.writeShort(w);
                    out.writeShort(h);
                    out.writeShort(fps);
                    out.flush();
                    log("player reformat " + w + "x" + h + "@" + fps);
                }
            } catch (Exception ex) {
                log("player format send " + ex.getMessage());
                close();
            }
        }

        synchronized void sendAu(byte[] au) {
            try {
                connect();
                notePlayerBack();
                if (!headerSent) {
                    ensureFormat(w, h, fps);
                }
                out.writeInt(au.length);
                out.write(au);
                out.flush();
            } catch (Exception ex) {
                close();
                notePlayerGone();
            }
        }

        void connect() throws Exception {
            if (socket != null && socket.isConnected() && !socket.isClosed()) {
                return;
            }
            close();
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", PLAYER_PORT), 500);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            headerSent = false;
            log("player socket connected");
            hadPlayer = true;
            playerGoneAt = 0;
        }

        static void notePlayerBack() {
            playerGoneAt = 0;
        }

        static void notePlayerGone() {
            if (!hadPlayer) {
                return;
            }
            long now = System.currentTimeMillis();
            if (playerGoneAt == 0) {
                playerGoneAt = now;
                return;
            }
            if (now - playerGoneAt < 2000) {
                return;
            }
            playerGoneAt = 0;
            hadPlayer = false;
            dropLinkBecausePlayerDied();
        }

        void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
            socket = null;
            out = null;
            headerSent = false;
        }
    }

    static void dropLinkBecausePlayerDied() {
        log("player process gone; dropping the Windows link");
        played = false;
        videoText = "";
        closeRtsp();
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.removeGroup(channel, loggedAction("playerGone"));
                } catch (Throwable t) {
                    log("player gone " + t.getMessage());
                }
                if (broadcasting) {
                    link = "advertising";
                    admitThenListen(new Runnable() {
                        @Override
                        public void run() {
                            manager.startListening(channel, loggedAction("relisten"));
                        }
                    });
                } else {
                    link = "idle";
                }
            }
        });
    }

    static WifiP2pManager.ActionListener loggedAction(final String name) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log(name + " ok");
            }

            @Override
            public void onFailure(int reason) {
                log(name + " failed " + reason);
            }
        };
    }

    static String exec(String... cmd) {
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            InputStream in = process.getInputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                stdout.write(buf, 0, n);
            }
            process.waitFor();
            return new String(stdout.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    static void setField(Object obj, String fieldName, Object value) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Throwable t) {
            log("setField " + fieldName + " " + t.getMessage());
        }
    }

    static String oneLine(String text) {
        String flat = text.replace('\r', ' ').replace('\n', '|');
        if (flat.length() > 6000) {
            return flat.substring(0, 6000) + "...";
        }
        return flat;
    }

    static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static void log(String message) {
        System.out.println(message);
        Log.i(TAG, message);
        try {
            FileWriter writer = new FileWriter("/data/local/tmp/miracast.log", true);
            writer.write(message);
            writer.write('\n');
            writer.close();
        } catch (Exception ignored) {
        }
    }
}
