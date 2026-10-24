package org.adaway.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Isolated toybox child only: no su, listening sockets, installed service or network changes. */
@RunWith(AndroidJUnit4.class)
public class BundledExecutableIdentityDeviceTest {
    @Test public void inheritedDescriptorSerializesTransactionsAndReleasesAfterOwnerExit() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File library = new File(context.getApplicationInfo().nativeLibraryDir);
        File helper = new File(library, "liblistener_probe.so");
        if (!helper.isFile()) helper = new File(library, "libwebserver_exec.so");
        assertTrue("Packaged native lock CLI is missing", helper.isFile());
        File lock = File.createTempFile("adaway-lifecycle-", ".lock", context.getCacheDir());
        String invoke = "LD_LIBRARY_PATH=" + quote(library.getAbsolutePath()) + " " + quote(helper.getAbsolutePath());
        String acquire = "exec 9>" + quote(lock.getAbsolutePath()) + "; " + invoke + " --lifecycle-lock 9";
        Process owner = null;
        try {
            owner = new ProcessBuilder("/system/bin/sh", "-c", acquire
                    + " || exit $?; (exec 9>&-; echo DAEMON_READY; exec sleep 5) & echo HELD:$!; read ignored").start();
            BufferedReader receipts = new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8));
            String first = receipts.readLine(), second = receipts.readLine();
            assertNotNull("Daemon did not acknowledge closing inherited FD 9", second);
            assertTrue("Missing daemon readiness receipt", "DAEMON_READY".equals(first) || "DAEMON_READY".equals(second));
            String held = "DAEMON_READY".equals(first) ? second : first;
            assertNotNull("Native helper did not acquire the inherited descriptor", held);
            assertTrue(held, held.startsWith("HELD:"));
            long daemon = Long.parseLong(held.substring("HELD:".length()));
            assertEquals("Overlapping transaction acquired the lock", 75, shellExit(acquire));
            owner.getOutputStream().write('\n'); owner.getOutputStream().flush();
            assertTrue("Owner did not exit", owner.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, owner.exitValue());
            assertEquals("Fixture daemon must still exist for the inheritance check", 0,
                    shellExit("kill -0 " + daemon));
            assertEquals("Daemon inherited FD 9 or the exited owner retained the lock", 0, shellExit(acquire));

            owner = new ProcessBuilder("/system/bin/sh", "-c", acquire + " || exit $?; echo HELD; read ignored").start();
            assertEquals("HELD", new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8)).readLine());
            owner.destroy();
            assertTrue("Terminated owner did not exit", owner.waitFor(3, TimeUnit.SECONDS));
            assertEquals("Kernel did not release the terminated owner's lock", 0, shellExit(acquire));
        } finally {
            if (owner != null) owner.destroy();
            assertTrue(lock.delete());
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static int shellExit(String command) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        try {
            assertTrue("Lock fixture timed out", process.waitFor(10, TimeUnit.SECONDS));
            return process.exitValue();
        } finally { process.destroy(); }
    }

    @Test public void verifiesActualKernelImageAndRejectsSubstringArgument() throws Exception {
        String toybox = new File("/system/bin/toybox").getCanonicalPath();
        Process owned = new ProcessBuilder("/system/bin/sh", "-c", "echo $$; exec " + toybox + " sleep 30").start();
        long ownedPid = childPid(owned);
        // This process carries the target name only as an argument and must survive stopCommand.
        Process foreign = new ProcessBuilder("/system/bin/sh", "-c", "echo $$; read ignored", toybox).start();
        long foreignPid = childPid(foreign);
        try {
            List<String> result = List.of();
            for (int attempt = 0; attempt < 20 && result.isEmpty(); attempt++) {
                result = shell(BundledExecutableIdentity.scanPidCommand(toybox, ownedPid, true));
                if (result.isEmpty()) Thread.sleep(50);
            }
            String expected = BundledExecutableIdentity.sha256(new File(toybox));
            assertTrue(result.toString(), BundledExecutableIdentity.allImagesMatch(result, expected));
            assertFalse(BundledExecutableIdentity.allImagesMatch(result, "0".repeat(64)));
            assertTrue(shell(BundledExecutableIdentity.scanPidCommand(toybox, foreignPid, true)).isEmpty());
            shell(BundledExecutableIdentity.stopPidCommand(toybox, foreignPid));
            shell(BundledExecutableIdentity.stopPidCommand(toybox, ownedPid));
            assertTrue("owned child did not terminate", owned.waitFor(3, TimeUnit.SECONDS));
            assertTrue("argument substring must not authorize killing", foreign.isAlive());
        } finally {
            owned.destroy(); foreign.destroy();
        }
    }

    private static long childPid(Process process) throws Exception {
        return Long.parseLong(new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)).readLine());
    }

    private static String readOutput(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new java.io.IOException("Fixture output too large");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static List<String> shell(String command) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        assertTrue("shell timed out", process.waitFor(10, TimeUnit.SECONDS));
        String output = readOutput(process.getInputStream()).trim();
        assertEquals(output, 0, process.exitValue());
        return output.isEmpty() ? List.of() : Arrays.asList(output.split("\\r?\\n"));
    }
}
