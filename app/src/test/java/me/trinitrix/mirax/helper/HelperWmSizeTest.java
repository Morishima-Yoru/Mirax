package me.trinitrix.mirax.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Checks the real wm-size subprocess boundary with local child processes. */
public class HelperWmSizeTest {
    /** A hung subprocess cannot hold the helper command loop indefinitely. */
    @Test(timeout = 9000)
    public void stalledProcess_isDestroyedBeforeNextQuery() throws Exception {
        Process stalled = startChild("sleep");
        try {
            assertEquals("", Helper.readWmSize(stalled));
            assertFalse(stalled.isAlive());
            // Keep the reply ready so this assertion does not time a cold JVM startup.
            Process ready = new HeldPipeProcess(new ByteArrayInputStream(
                    "size 2176x1812\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals("size 2176x1812", Helper.readWmSize(ready));
        } finally {
            if (stalled.isAlive()) stalled.destroyForcibly();
        }
    }

    /** A child that exits while a descendant holds the pipe must not stall the read. */
    @Test(timeout = 9000)
    public void exitedChildWithPipeHeldByDescendant_returnsOutput() throws Exception {
        Process child = startChild("replyHold");
        try {
            assertEquals("size 2176x1812", Helper.readWmSize(child));
            assertFalse(child.isAlive());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /** An open pipe with no EOF and no further writers must not stall the read. */
    @Test(timeout = 9000)
    public void pipeHeldOpenWithoutEof_returnsAvailableBytes() throws Exception {
        PipedInputStream reader = new PipedInputStream(65536);
        PipedOutputStream writer = new PipedOutputStream(reader);
        writer.write("size 9x9\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        try {
            assertEquals("size 9x9", Helper.readWmSize(new HeldPipeProcess(reader)));
        } finally {
            writer.close();
        }
    }

    /** Output beyond 8 KiB is truncated instead of growing the helper heap. */
    @Test(timeout = 9000)
    public void oversizedOutput_isTruncated() throws Exception {
        byte[] flood = new byte[8192 + 4096];
        Arrays.fill(flood, (byte) 'x');
        PipedInputStream reader = new PipedInputStream(65536);
        PipedOutputStream writer = new PipedOutputStream(reader);
        writer.write(flood);
        writer.close();
        assertEquals(8192, Helper.readWmSize(new HeldPipeProcess(reader)).length());
    }

    private static Process startChild(String action) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classes = new File(Child.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getAbsolutePath();
        return new ProcessBuilder(java, "-cp", classes,
                Child.class.getName(), action).redirectErrorStream(true).start();
    }

    /** An isolated executable standing in for the external wm binary. */
    public static final class Child {
        /** Emits a wm-shaped result, remains unresponsive, or exits leaving a pipe holder. */
        public static void main(String[] args) throws Exception {
            if ("sleep".equals(args[0])) {
                TimeUnit.SECONDS.sleep(30);
            } else if ("replyHold".equals(args[0])) {
                String java = System.getProperty("java.home") + File.separator + "bin"
                        + File.separator + "java";
                String classes = new File(Child.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).getAbsolutePath();
                new ProcessBuilder(java, "-cp", classes, Child.class.getName(), "hold")
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectErrorStream(true)
                        .start();
                System.out.println("size 2176x1812");
                System.out.flush();
            } else if ("hold".equals(args[0])) {
                TimeUnit.SECONDS.sleep(20);
            } else {
                System.out.println("size 2176x1812");
            }
        }
    }

    /** An already-exited process over a test-held pipe; no subprocess is spawned. */
    private static final class HeldPipeProcess extends Process {
        private final InputStream pipe;

        /** Wraps the given pipe as the stdout of an exited process. */
        HeldPipeProcess(InputStream pipe) {
            this.pipe = pipe;
        }

        /** Returns the held pipe as the process stdout. */
        @Override
        public InputStream getInputStream() {
            return pipe;
        }

        /** Returns a throwaway stream; stdin is never used. */
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        /** Returns an empty stream; stderr is never used. */
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /** Reports immediate success; the process is already gone. */
        @Override
        public int waitFor() {
            return 0;
        }

        /** Reports immediate success; the process is already gone. */
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        /** Reports success; the process is already gone. */
        @Override
        public int exitValue() {
            return 0;
        }

        /** No-op; there is no subprocess to destroy. */
        @Override
        public void destroy() {
        }

        /** No-op; there is no subprocess to destroy. */
        @Override
        public Process destroyForcibly() {
            return this;
        }

        /** Reports exited; only the pipe may still be open. */
        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
