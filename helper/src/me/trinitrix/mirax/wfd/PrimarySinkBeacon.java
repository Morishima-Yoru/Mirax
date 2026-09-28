package me.trinitrix.mirax.wfd;

import android.content.AttributionSource;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shell-UID Wi-Fi Display Primary Sink beacon.
 *
 * Calls {@link WifiP2pManager#setWfdInfo} and {@link WifiP2pManager#startListening}
 * so stock Windows Win+K can list this phone. Must run as shell (UID 2000) or
 * root — never from the Mirax app process. Does not touch Samsung SmartMirroring
 * or SecondScreenPlayer.
 */
public final class PrimarySinkBeacon {
    private static final String TAG = "MiraxWfdBeacon";
    /** Miracast RTSP control port advertised in the WFD IE. */
    public static final int RTSP_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT_MBPS = 50;

    private final Handler handler;
    private Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private volatile boolean advertising;
    private volatile String lastName = "";

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
     * Advertise as a Miracast Primary Sink under [broadcastName].
     *
     * @param broadcastName Wi-Fi Direct device name Win+K must show
     */
    public void startAdvertising(final String broadcastName) {
        handler.post(() -> {
            lastName = broadcastName == null ? "" : broadcastName;
            applyDeviceName(lastName);
            armSink();
        });
    }

    /** Stop listening and clear the WFD IE so the phone is not a connectable sink. */
    public void stopAdvertising() {
        handler.post(() -> {
            advertising = false;
            if (manager == null || channel == null) {
                return;
            }
            try {
                manager.stopListening(channel, logged("stopListening"));
                clearWfdInfo();
            } catch (Throwable err) {
                Log.w(TAG, "stopAdvertising failed", err);
            }
            Log.i(TAG, "broadcast off");
        });
    }

    public boolean isAdvertising() {
        return advertising;
    }

    private void armSink() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.removeGroup(channel, logged("removeGroup"));
        } catch (Throwable ignored) {
        }
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
        Log.i(TAG, "WFD info " + wfd + " name=\"" + lastName + "\"");
        manager.setWfdInfo(channel, wfd, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "setWfdInfo success");
                manager.startListening(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        advertising = true;
                        Log.i(TAG, "startListening success; Primary Sink on port "
                                + RTSP_CONTROL_PORT);
                    }

                    @Override
                    public void onFailure(int reason) {
                        advertising = false;
                        Log.e(TAG, "startListening failed reason=" + reason);
                    }
                });
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

    private void applyDeviceName(String name) {
        if (context == null || name == null) {
            return;
        }
        // Wi-Fi Direct name only — never Settings.Global.DEVICE_NAME.
        try {
            Settings.Global.putString(
                    context.getContentResolver(),
                    "wifi_p2p_device_name",
                    name);
        } catch (Throwable err) {
            Log.w(TAG, "wifi_p2p_device_name write failed", err);
        }
        if (manager != null && channel != null) {
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
        try {
            Settings.Global.putInt(context.getContentResolver(), "wifi_display_on", 1);
        } catch (Throwable err) {
            Log.w(TAG, "wifi_display_on write failed", err);
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
