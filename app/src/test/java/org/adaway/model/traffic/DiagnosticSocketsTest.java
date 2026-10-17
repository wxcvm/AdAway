package org.adaway.model.traffic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DiagnosticSocketsTest {
    @Test public void closeUnblocksRealReceiveAndRefusesRacingOpens() throws Exception {
        DiagnosticSockets registry = new DiagnosticSockets();
        DatagramSocket socket = new DatagramSocket(0, java.net.InetAddress.getLoopbackAddress());
        registry.register(socket);
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Thread receiver = new Thread(() -> {
            started.countDown();
            try { socket.receive(new DatagramPacket(new byte[1], 1)); }
            catch (java.io.IOException expected) { }
            finally { stopped.countDown(); }
        });
        receiver.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            registry.close();
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertTrue(socket.isClosed());
            DatagramSocket late = new DatagramSocket();
            try { registry.register(late); fail("late socket accepted"); }
            catch (java.io.IOException expected) { assertTrue(late.isClosed()); }
        } finally { registry.close(); receiver.join(2000); }
    }
}
