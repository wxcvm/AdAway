package org.adaway.model.traffic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

/** Owns both local backends and every in-flight socket as one cancelable session. */
public final class DiagnosticLocalRelay implements AutoCloseable {
    @FunctionalInterface public interface Protector { boolean protect(DatagramSocket socket); }
    private final DiagnosticSockets sockets = new DiagnosticSockets();
    private final DiagnosticLoopbackUdpServer ipv4;
    private final DiagnosticLoopbackUdpServer ipv6;
    private final Protector protector;

    public DiagnosticLocalRelay(Protector protector) throws IOException {
        this.protector = protector;
        ipv4 = new DiagnosticLoopbackUdpServer(false);
        try { ipv6 = new DiagnosticLoopbackUdpServer(true); }
        catch (IOException | RuntimeException error) { ipv4.close(); throw error; }
    }

    DiagnosticLocalRelay(Protector protector, DiagnosticLoopbackUdpServer ipv4, DiagnosticLoopbackUdpServer ipv6) {
        this.protector = protector; this.ipv4 = ipv4; this.ipv6 = ipv6;
    }

    public byte[] exchange(boolean v6, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > DiagnosticUdpRelay.MAX_PAYLOAD)
            throw new IOException("Invalid local diagnostic payload");
        InetAddress loopback = InetAddress.getByName(v6 ? "::1" : "127.0.0.1");
        DiagnosticLoopbackUdpServer backend = v6 ? ipv6 : ipv4;
        // Bind the requested family before protection/connect; never use a wildcard or DNS.
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            sockets.register(socket);
            try {
                if (!protector.protect(socket)) throw new IOException("Socket protection refused");
                socket.connect(loopback, backend.port()); socket.setSoTimeout(900);
                socket.send(new DatagramPacket(payload, payload.length));
                byte[] bytes = new byte[DiagnosticUdpRelay.MAX_PAYLOAD + 1];
                DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
                socket.receive(reply);
                byte[] expected = payload.clone(); expected[0] ^= 0x5a;
                byte[] answer = Arrays.copyOf(bytes, reply.getLength());
                if (!Arrays.equals(expected, answer)) throw new IOException("Stale or invalid local reply");
                return answer;
            } finally { sockets.remove(socket); }
        }
    }

    @Override public void close() {
        sockets.close(); ipv4.close(); ipv6.close();
    }

    public boolean awaitStopped(long milliseconds) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);
        boolean first = ipv4.awaitStopped(milliseconds);
        long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        return ipv6.awaitStopped(left) && first;
    }
}
