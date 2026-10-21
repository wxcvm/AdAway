package org.adaway.util;

import org.junit.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ListenerStartupWaitTest {
    @Test public void realListenerAppearingAfterOldOneSecondBudgetIsAccepted() throws Exception {
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        try (ServerSocket listener = new ServerSocket()) {
            long start = System.nanoTime();
            AtomicBoolean attempted = new AtomicBoolean();
            assertTrue(ListenerStartupWait.await(() -> {
                if (!attempted.get() && System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(1300)) {
                    try { listener.bind(new InetSocketAddress(loop, 0)); attempted.set(true); }
                    catch (java.io.IOException error) { throw new AssertionError(error); }
                }
                if (!attempted.get()) return false;
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress(loop, listener.getLocalPort()), 100);
                    return true;
                } catch (java.io.IOException error) { return false; }
            }, 3500));
            assertTrue(attempted.get());
        }
    }
    @Test public void missingListenerTimesOutAndCancellationIsPreserved() throws Exception {
        long start = System.nanoTime();
        assertFalse(ListenerStartupWait.await(() -> false, 120));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        Thread.currentThread().interrupt();
        try { assertFalse(ListenerStartupWait.await(() -> { fail("Canceled wait performed I/O"); return true; }, 500)); }
        finally { assertTrue(Thread.interrupted()); }
    }
}
