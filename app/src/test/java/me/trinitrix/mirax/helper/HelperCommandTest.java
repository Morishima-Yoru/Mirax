package me.trinitrix.mirax.helper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.Test;

/** Validates helper command framing before privileged execution. */
public class HelperCommandTest {
    /** Root and shell do not depend on a working package lookup. */
    @Test
    public void privilegedPeers_skipPackageLookup() {
        IntUnaryOperator unavailable = uid -> {
            throw new AssertionError("Privileged peer must not query packages");
        };
        assertTrue(Helper.isAuthorizedPeer(0, unavailable));
        assertTrue(Helper.isAuthorizedPeer(2000, unavailable));
    }

    /** The exact installed Mirax UID preserves the platform's existing shared-UID trust model. */
    @Test
    public void exactMiraxUid_isAuthorized() {
        assertTrue(Helper.isAuthorizedPeer(10289, uid -> 10289));
    }

    /** An unrelated UID or missing installation cannot authorize a caller. */
    @Test
    public void unrelatedOrMissingMiraxUid_isDenied() {
        assertFalse(Helper.isAuthorizedPeer(10291, uid -> 10289));
        assertFalse(Helper.isAuthorizedPeer(10289, uid -> -1));
        assertFalse(Helper.isAuthorizedPeer(1000, uid -> {
            throw new AssertionError("Non-application system UID must not query packages");
        }));
    }

    /** A secondary user's full kernel UID, not only its appId, selects the package lookup. */
    @Test
    public void secondaryUser_passesFullPeerUidToLookup() {
        AtomicInteger queriedUid = new AtomicInteger(-1);
        assertTrue(Helper.isAuthorizedPeer(1_010_289, uid -> {
            queriedUid.set(uid);
            return 1_010_289;
        }));
        assertEquals(1_010_289, queriedUid.get());
        assertFalse(Helper.isAuthorizedPeer(1_010_289, uid -> 10289));
        assertFalse(Helper.isAuthorizedPeer(10289, uid -> 1_010_289));
    }

    /** A public package-manager failure cannot open the privileged command dispatcher. */
    @Test
    public void packageLookupFailure_isDenied() {
        assertFalse(Helper.isAuthorizedPeer(10289, uid -> {
            throw new IllegalStateException("package manager unavailable");
        }));
    }

    /** A recycled or temporarily uninstalled identity must be checked again on the next connection. */
    @Test
    public void changedPackageOwnership_doesNotReuseAuthorization() {
        AtomicInteger installedUid = new AtomicInteger(10289);
        AtomicInteger lookups = new AtomicInteger();
        IntUnaryOperator lookup = uid -> {
            lookups.incrementAndGet();
            return installedUid.get();
        };
        assertTrue(Helper.isAuthorizedPeer(10289, lookup));
        installedUid.set(10315);
        assertFalse(Helper.isAuthorizedPeer(10289, lookup));
        installedUid.set(-1);
        assertFalse(Helper.isAuthorizedPeer(10289, lookup));
        installedUid.set(10289);
        assertTrue(Helper.isAuthorizedPeer(10289, lookup));
        assertEquals(4, lookups.get());
    }

    /** A legitimate new Mirax UID is accepted without retaining the previous numeric UID. */
    @Test
    public void reinstalledMirax_usesCurrentUid() {
        AtomicInteger installedUid = new AtomicInteger(10289);
        IntUnaryOperator lookup = uid -> installedUid.get();
        assertTrue(Helper.isAuthorizedPeer(10289, lookup));
        installedUid.set(10315);
        assertFalse(Helper.isAuthorizedPeer(10289, lookup));
        assertTrue(Helper.isAuthorizedPeer(10315, lookup));
    }

    /** Accepts a complete command frame. */
    @Test
    public void completeCommand_isAccepted() throws Exception {
        assertEquals("UID", Helper.readCommand(bytes("UID\n")));
    }

    /** The exact no-argument command reaches only the fixed launcher. */
    @Test
    public void showProjection_withoutArgumentsLaunchesAndAcknowledges() throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        AtomicInteger launches = new AtomicInteger();
        Helper.handleCommand(Helper.readCommand(bytes("SHOW_PROJECTION\n")), response,
                launches::incrementAndGet);
        assertEquals(1, launches.get());
        assertEquals("OK\n", response.toString(StandardCharsets.UTF_8));
    }

    /** Prefixes, whitespace, targets, flags, and shell syntax cannot broaden the command. */
    @Test
    public void showProjection_withArgumentsOrDifferentSpellingIsRejected() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        for (String command : new String[] {
                "show_projection", "SHOW_PROJECTION_EXTRA", " SHOW_PROJECTION",
                "SHOW_PROJECTION ", "SHOW_PROJECTION\t",
                "SHOW_PROJECTION me.trinitrix.mirax/.PictureActivity",
                "SHOW_PROJECTION\tother.package/.Activity",
                "SHOW_PROJECTION --user 0", "SHOW_PROJECTION -f 0",
                "SHOW_PROJECTION; id",
        }) {
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            Helper.handleCommand(command, response, launches::incrementAndGet);
            assertEquals(command, "ERR unknown\n", response.toString(StandardCharsets.UTF_8));
        }
        assertEquals(0, launches.get());
    }

    /** Failed launches must not send the success acknowledgement. */
    @Test
    public void showProjection_launchFailureDoesNotAcknowledge() {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        assertThrows(IllegalStateException.class, () -> Helper.handleCommand(
                "SHOW_PROJECTION", response, () -> {
                    throw new IllegalStateException("am start failed");
                }));
        assertEquals(0, response.size());
    }

    /** The command never routes to a private activity or accepts an arbitrary user or intent. */
    @Test
    public void projectionLaunchCommand_hasFixedTargetCurrentUserAndFlags() {
        assertArrayEquals(new String[] {
                "/system/bin/am", "start", "--user", "current",
                "-n", "me.trinitrix.mirax/.MainActivity", "-f", "0x30020000",
        }, Helper.projectionLaunchCommand());
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
