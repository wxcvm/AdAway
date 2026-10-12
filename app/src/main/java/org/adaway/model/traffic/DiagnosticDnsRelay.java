package org.adaway.model.traffic;

import java.io.IOException;

/**
 * A single fixed DNS diagnostic over the scoped TUN /32.
 *
 * No general DNS requests, arbitrary query names or arbitrary upstream IPs
 * are accepted. The transport selects a DNS server from Android's actual
 * underlying network at execution time.
 */
public final class DiagnosticDnsRelay {
    private DiagnosticDnsRelay() { }

    @FunctionalInterface
    public interface Upstream {
        byte[] exchange(byte[] query) throws IOException;
    }

    public static byte[] process(byte[] packet, int length, Upstream upstream) {
        if (upstream == null) return null;
        byte[] query = DiagnosticUdpEcho.payload(
                packet, length, DiagnosticDnsCodec.PORT);
        if (!DiagnosticDnsCodec.isAllowedQuery(query)) return null;
        try {
            byte[] answer = upstream.exchange(query);
            if (!DiagnosticDnsCodec.matchesResponse(query, answer)) return null;
            return DiagnosticUdpEcho.replyWithPayload(packet, length,
                    DiagnosticDnsCodec.PORT, answer);
        } catch (IOException | RuntimeException ignored) {
            // Timeout or a broken response may not terminate the TUN thread.
            return null;
        }
    }
}
