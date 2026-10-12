package org.adaway.model.traffic;

import java.io.IOException;

/**
 * Bounded UDP relay protocol for the isolated 198.18.0.1/32 test route.
 * Never opens network sockets itself and never relays arbitrary IP targets.
 */
public final class DiagnosticUdpRelay {
    public static final int PORT = 53536;
    public static final int MAX_PAYLOAD = 256;

    @FunctionalInterface
    public interface Exchange {
        byte[] exchange(byte[] request) throws IOException;
    }

    private DiagnosticUdpRelay() { }

    /** Return an IPv4 packet to write to TUN, or null on any unsafe condition. */
    public static byte[] process(byte[] packet, int available, Exchange exchange) {
        if (exchange == null) return null;
        byte[] payload = DiagnosticUdpEcho.payload(packet, available, PORT);
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD)
            return null;
        try {
            byte[] upstream = exchange.exchange(payload);
            if (upstream == null || upstream.length == 0
                    || upstream.length > MAX_PAYLOAD) return null;
            return DiagnosticUdpEcho.replyWithPayload(packet, available, PORT, upstream);
        } catch (IOException | RuntimeException ignored) {
            // A diagnostic must never kill the TUN read loop.
            return null;
        }
    }
}
