package org.adaway.model.traffic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DiagnosticPacketPumpTest {
    @Test public void networkStallCannotBlockReaderOrDirectEchoAndQueuesAreBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        CountDownLatch echo = new CountDownLatch(1);
        AtomicInteger canceled = new AtomicInteger();
        DiagnosticPacketPump pump = new DiagnosticPacketPump(packet -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return packet;
        }, packet -> echo.countDown(), () -> { canceled.incrementAndGet(); release.countDown(); }, () -> {});
        try {
            assertTrue(pump.submit(new byte[]{1}, 1));
            assertTrue(pump.submit(new byte[]{2}, 1));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < DiagnosticPacketPump.CAPACITY; i++)
                assertTrue(pump.submit(new byte[]{3}, 1));
            assertFalse(pump.submit(new byte[]{4}, 1));
            assertEquals(1, pump.dropped());
            assertTrue(pump.respond(new byte[]{9}));
            assertTrue(echo.await(2, TimeUnit.SECONDS));
        } finally { pump.close(); }
        assertTrue(pump.awaitStopped(2000));
        assertEquals(1, canceled.get());
        assertFalse(pump.submit(new byte[]{1}, 1));
        assertFalse(pump.respond(new byte[]{1}));
    }

    @Test public void lateNetworkResponsesCannotWriteAfterClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        DiagnosticPacketPump pump = new DiagnosticPacketPump(packet -> {
            entered.countDown();
            while (true) {
                try { release.await(); break; } catch (InterruptedException ignored) { }
            }
            return packet;
        }, packet -> writes.incrementAndGet(), release::countDown, () -> {});
        pump.submit(new byte[]{1}, 1);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pump.close();
        assertTrue(pump.awaitStopped(2000));
        assertEquals(0, writes.get());
    }
}
