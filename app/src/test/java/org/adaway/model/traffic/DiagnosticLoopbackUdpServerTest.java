package org.adaway.model.traffic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

public class DiagnosticLoopbackUdpServerTest {
    @Test public void realLoopbackRelayRespondsAndStops() throws Exception {
        DiagnosticLoopbackUdpServer server = new DiagnosticLoopbackUdpServer();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            socket.send(new DatagramPacket(new byte[]{1,2,3}, 3, InetAddress.getByName("127.0.0.1"), server.port()));
            byte[] received = new byte[4]; DatagramPacket reply = new DatagramPacket(received, received.length);
            socket.receive(reply);
            assertEquals(3, reply.getLength());
            assertArrayEquals(new byte[]{0x5b,2,3}, java.util.Arrays.copyOf(received, 3));
            assertTrue(reply.getAddress().isLoopbackAddress());
            assertEquals(server.port(), reply.getPort());
        } finally { server.close(); }
        assertEquals(-1, server.port());
    }
}
