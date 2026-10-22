package org.adaway.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedProcessResponseTest {
    @Test public void toyboxAndStandaloneNcHaveDifferentArgumentShapes() {
        assertEquals(Arrays.asList("/system/bin/toybox", "nc", "-w", "3", "::1", "8686"),
                BoundedProcessResponse.ncCommand("/system/bin/toybox", "nc", "::1", 8686));
        assertEquals(Arrays.asList("/system/bin/nc", "-w", "3", "127.0.0.1", "8686"),
                BoundedProcessResponse.ncCommand("/system/bin/nc", null, "127.0.0.1", 8686));
    }

    @Test public void actualProcessCanFillStderrWithoutBlockingTheResponse() throws Exception {
        Process child = child("success");
        assertEquals("response", BoundedProcessResponse.exchange(child, "request", 3000, 1024));
        assertFalse(child.isAlive());
    }

    @Test public void actualProcessHoldingStdoutOpenTimesOutAndIsTerminated() throws Exception {
        Process child = child("stall");
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> BoundedProcessResponse.exchange(child, "request", 200, 1024));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        assertTrue(child.waitFor(1000, TimeUnit.MILLISECONDS));
    }

    @Test public void oversizedActualResponseIsRejectedAndTheProcessIsTerminated() throws Exception {
        Process child = child("large");
        assertThrows(IOException.class, () -> BoundedProcessResponse.exchange(child, "request", 3000, 1024));
        assertTrue(child.waitFor(1000, TimeUnit.MILLISECONDS));
    }

    @Test public void nonzeroExitCannotPublishAPartialResponse() throws Exception {
        assertThrows(IOException.class, () -> BoundedProcessResponse.exchange(child("failure"), "request", 3000, 1024));
    }

    @Test public void interruptionTerminatesTheActualChildAndIsNotSwallowed() throws Exception {
        Process child = child("stall");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> BoundedProcessResponse.exchange(child, "request", 3000, 1024));
        } finally { Thread.interrupted(); }
        assertTrue(child.waitFor(1000, TimeUnit.MILLISECONDS));
    }

    private static Process child(String mode) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String fixtureClasses = Path.of(PipeFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(executable, "-cp", fixtureClasses, PipeFixture.class.getName(), mode).start();
    }

    /** Real isolated child process: no shell, network, Android, or production services. */
    public static class PipeFixture {
        public static void main(String[] args) throws Exception {
            while (System.in.read() != -1) { /* Wait for the request's EOF. */ }
            if (args[0].equals("stall")) new CountDownLatch(1).await(30, TimeUnit.SECONDS);
            else if (args[0].equals("large")) {
                System.out.write(new byte[2 * 1024 * 1024]);
            } else {
                System.err.write(new byte[128 * 1024]);
                System.out.print("response");
                if (args[0].equals("failure")) System.exit(1);
            }
        }
    }
}
