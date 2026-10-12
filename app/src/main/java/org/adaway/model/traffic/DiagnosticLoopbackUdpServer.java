package org.adaway.model.traffic;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Private-to-the-device loopback endpoint to prove TUN -> protected UDP socket
 * -> UDP server -> protected socket -> TUN round trip. It accepts a small
 * payload and toggles its first byte, so this path is distinguishable from
 * the direct in-TUN echo responder. No Internet destinations are accessed.
 */
public final class DiagnosticLoopbackUdpServer implements Closeable {
    private final DatagramSocket socket;
    private final Thread worker;
    private volatile boolean closed;

    public DiagnosticLoopbackUdpServer() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        socket.setSoTimeout(500);
        worker = new Thread(this::serve, "diagnostic-udp-loopback");
        worker.setDaemon(true);
        worker.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        byte[] bytes = new byte[DiagnosticUdpRelay.MAX_PAYLOAD + 1];
        while (!closed) {
            try {
                DatagramPacket received = new DatagramPacket(bytes, bytes.length);
                socket.receive(received);
                if (!allowed(received)) continue;
                byte[] reply = new byte[received.getLength()];
                System.arraycopy(bytes, 0, reply, 0, reply.length);
                reply[0] ^= 0x5a;
                socket.send(new DatagramPacket(reply, reply.length,
                        received.getAddress(), received.getPort()));
            } catch (SocketTimeoutException ignored) {
                // Wake periodically so cancellation can close the service.
            } catch (SocketException exception) {
                if (!closed) break;
            } catch (IOException exception) {
                if (!closed) break;
            }
        }
        close();
    }

    private static boolean allowed(DatagramPacket packet) {
        return packet.getAddress().isLoopbackAddress() && packet.getLength() > 0
                && packet.getLength() <= DiagnosticUdpRelay.MAX_PAYLOAD;
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
