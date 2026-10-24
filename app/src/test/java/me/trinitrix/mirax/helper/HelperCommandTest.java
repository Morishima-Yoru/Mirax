package me.trinitrix.mirax.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Validates helper command framing before privileged execution. */
public class HelperCommandTest {
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

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
