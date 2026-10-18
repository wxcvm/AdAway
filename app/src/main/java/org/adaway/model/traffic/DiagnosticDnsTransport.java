package org.adaway.model.traffic;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** One fixed diagnostic exchange, with a single UDP-to-TCP retry and one total budget. */
public final class DiagnosticDnsTransport {
    public static final int TIMEOUT_MS = 1200;
    private DiagnosticDnsTransport() { }

    public interface Hooks {
        void prepare(DatagramSocket socket) throws IOException;
        void prepare(Socket socket) throws IOException;
    }

    public static byte[] exchange(InetAddress resolver, int port, byte[] query,
            DiagnosticSockets sockets, Hooks hooks) throws IOException {
        return exchange(resolver, port, query, sockets, hooks, TIMEOUT_MS);
    }

    static byte[] exchange(InetAddress resolver, int port, byte[] query,
            DiagnosticSockets sockets, Hooks hooks, int timeoutMs) throws IOException {
        if (resolver == null || port < 1 || port > 65535 || sockets == null || hooks == null
                || timeoutMs < 1 || timeoutMs > TIMEOUT_MS || !DiagnosticDnsCodec.isAllowedQuery(query))
            throw new IOException("Unsupported diagnostic DNS exchange");
        byte[] question = query.clone();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        InetSocketAddress endpoint = new InetSocketAddress(resolver, port);
        try (DatagramSocket socket = new DatagramSocket(null)) {
            sockets.register(socket);
            try {
                hooks.prepare(socket); // Android protect + Network binding, before connect/send.
                remaining(deadline);
                socket.connect(endpoint);
                socket.setSoTimeout(remaining(deadline));
                socket.send(new DatagramPacket(question, question.length));
                byte[] buffer = new byte[DiagnosticDnsCodec.MAX_DATAGRAM + 1];
                DatagramPacket received = new DatagramPacket(buffer, buffer.length);
                socket.receive(received);
                remaining(deadline);
                if (!resolver.equals(received.getAddress()) || received.getPort() != port
                        || received.getLength() > DiagnosticDnsCodec.MAX_DATAGRAM)
                    throw new IOException("Unexpected diagnostic DNS endpoint or size");
                byte[] answer = Arrays.copyOf(buffer, received.getLength());
                if (DiagnosticDnsCodec.matchesResponse(question, answer)) return answer;
                if (!DiagnosticDnsCodec.shouldRetryOverTcp(question, answer))
                    throw new IOException("Unexpected diagnostic DNS response");
            } finally { sockets.remove(socket); }
        }
        try (Socket socket = new Socket()) {
            sockets.register(socket);
            try {
                hooks.prepare(socket);
                socket.connect(endpoint, remaining(deadline));
                socket.setSoTimeout(remaining(deadline));
                byte[] frame = new byte[question.length + 2];
                frame[0] = (byte)(question.length >>> 8); frame[1] = (byte)question.length;
                System.arraycopy(question, 0, frame, 2, question.length);
                // The fixed query is 31 framed bytes; never writes unbounded caller data.
                socket.getOutputStream().write(frame);
                InputStream input = socket.getInputStream();
                byte[] prefix = read(socket, input, 2, deadline);
                int size = ((prefix[0] & 255) << 8) | (prefix[1] & 255);
                if (size < question.length || size > DiagnosticDnsCodec.MAX_DATAGRAM)
                    throw new IOException("Unsupported diagnostic DNS TCP frame size");
                byte[] answer = read(socket, input, size, deadline);
                if (!DiagnosticDnsCodec.matchesResponse(question, answer))
                    throw new IOException("Unexpected diagnostic DNS TCP response");
                return answer;
            } finally { sockets.remove(socket); }
        }
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("Diagnostic DNS deadline exceeded");
        return (int)Math.max(1, (nanos + 999_999L) / 1_000_000L);
    }

    private static byte[] read(Socket socket, InputStream input, int size, long deadline) throws IOException {
        byte[] result = new byte[size];
        for (int offset = 0; offset < size;) {
            socket.setSoTimeout(remaining(deadline));
            int count = input.read(result, offset, size - offset);
            if (count < 0) throw new EOFException("Incomplete diagnostic DNS TCP frame");
            if (count == 0) continue;
            offset += count;
        }
        remaining(deadline);
        return result;
    }
}
