package me.trinitrix.mirax.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Validates helper command framing before privileged execution. */
public class HelperCommandTest {
    /** Existing root, shell, and installed Mirax clients remain authorized. */
    @Test
    public void authorizedPeers_includeRootShellAndExactMiraxUid() {
        assertTrue(Helper.isAuthorizedPeer(0, 10289));
        assertTrue(Helper.isAuthorizedPeer(2000, 10289));
        assertTrue(Helper.isAuthorizedPeer(10289, 10289));
    }

    /** An unrelated UID cannot inherit access, even if package lookup failed. */
    @Test
    public void unrelatedPeers_areDeniedBeforeReadingCommands() {
        assertFalse(Helper.isAuthorizedPeer(10291, 10289));
        assertFalse(Helper.isAuthorizedPeer(10289, -1));
        assertFalse(Helper.isAuthorizedPeer(1000, 1000));
    }

    /** Accepts a complete command frame. */
    @Test
    public void completeCommand_isAccepted() throws Exception {
        assertEquals("UID", Helper.readCommand(bytes("UID\n")));
    }

    /** A disconnected client must not execute its unfinished command. */
    @Test
    public void incompleteCommand_isRejected() throws Exception {
        assertThrows(IOException.class, () -> Helper.readCommand(bytes("STOP")));
        assertEquals("UID", Helper.readCommand(bytes("UID\n")));
    }

    /** Oversized input must not monopolize the privileged helper. */
    @Test
    public void oversizedCommand_isRejected() {
        assertThrows(IOException.class, () -> Helper.readCommand(bytes("x".repeat(4097) + "\n")));
    }

    /** A full worker pool rejects extra clients instead of queueing them. */
    @Test(timeout = 9000)
    public void fullPool_rejectsExtraClient() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(Helper.MAX_CLIENTS);
        for (int index = 0; index < Helper.MAX_CLIENTS; index++) {
            assertTrue(Helper.offerClient(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(8, TimeUnit.SECONDS));
                } catch (InterruptedException err) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        assertFalse(Helper.offerClient(() -> {
        }));
        release.countDown();
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
