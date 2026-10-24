package me.trinitrix.mirax.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
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
            assertEquals("size 2176x1812", Helper.readWmSize(startChild("reply")));
        } finally {
            if (stalled.isAlive()) stalled.destroyForcibly();
        }
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
        /** Emits a wm-shaped result or remains unresponsive. */
        public static void main(String[] args) throws Exception {
            if ("sleep".equals(args[0])) {
                TimeUnit.SECONDS.sleep(30);
            } else {
                System.out.println("size 2176x1812");
            }
        }
    }
}
