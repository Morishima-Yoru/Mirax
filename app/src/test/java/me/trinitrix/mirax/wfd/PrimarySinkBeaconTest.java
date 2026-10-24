package me.trinitrix.mirax.wfd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.p2p.WifiP2pManager;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Checks the actual framework callback bodies without claiming Wi-Fi hardware coverage. */
public class PrimarySinkBeaconTest {
    /** A delayed success cannot make a stopped helper report that it is listening. */
    @Test
    public void delayedListeningSuccess_afterStop_isIgnored() {
        PrimarySinkBeacon beacon = new PrimarySinkBeacon(null);
        beacon.listeningListener(true, 0).onSuccess();
        assertFalse(beacon.isListening());
    }

    /** The current lifecycle still reports the genuine listening callback normally. */
    @Test
    public void currentListeningSuccess_isAccepted() throws Exception {
        PrimarySinkBeacon beacon = new PrimarySinkBeacon(null);
        field("wanted").setBoolean(beacon, true);
        beacon.listeningListener(true, 0).onSuccess();
        assertTrue(beacon.isListening());
    }

    /** A delayed failure cannot consume the callback belonging to a newer advertisement. */
    @Test
    public void oldWfdFailure_doesNotConsume_newGenerationCallback() throws Exception {
        PrimarySinkBeacon beacon = new PrimarySinkBeacon(null);
        WifiP2pManager.ActionListener old = beacon.wfdInfoListener(true, 0);
        field("wanted").setBoolean(beacon, true);
        field("advertisingGeneration").setLong(beacon, 1L);
        AtomicInteger failures = new AtomicInteger();
        beacon.setArmCallback(new PrimarySinkBeacon.ArmCallback() {
            @Override
            public void onListening() {}

            @Override
            public void onFailed(String detail) { failures.incrementAndGet(); }
        });
        old.onFailure(1);
        assertEquals(0, failures.get());
    }

    /** @return real lifecycle field used to model an owner-looper transition. */
    private static Field field(String name) throws Exception {
        Field field = PrimarySinkBeacon.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
