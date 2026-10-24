package org.adaway.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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

    private static List<String> shell(String command) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        assertTrue("shell timed out", process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(output, 0, process.exitValue());
        return output.isEmpty() ? List.of() : Arrays.asList(output.split("\\r?\\n"));
    }
}
