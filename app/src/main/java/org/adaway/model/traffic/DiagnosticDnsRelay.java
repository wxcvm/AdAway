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

    /**
     * Apply the last published user rules to the already-authorized fixed DNS
     * test BEFORE trying the underlying resolver. If no rule applies, forward
     * the same original query, never synthesize success for a network failure.
     * This does not add routes or broaden the diagnostic DNS whitelist.
     */
    public static byte[] resolveFromSnapshotOrUpstream(
            byte[] query, RuleSnapshotRepository.Snapshot snapshot,
            Upstream upstream) throws IOException {
        if (!DiagnosticDnsCodec.isAllowedQuery(query) || upstream == null)
            throw new IOException("Unsupported diagnostic DNS query");
        byte[] synthesized = snapshot == null ? null : snapshot.answerDns(query);
        return synthesized != null ? synthesized : upstream.exchange(query);
    }

    public static byte[] process(byte[] packet, int length, DiagnosticDnsAuthorization permits,
                                 int ownerUid, Upstream upstream) {
        if (upstream == null || permits == null) return null;
        byte[] query = DiagnosticUdpEcho.payload(
                packet, length, DiagnosticDnsCodec.PORT);
        if (!DiagnosticDnsCodec.isAllowedQuery(query)) return null;
        if (!permits.consume(ownerUid, DiagnosticUdpEcho.sourcePort(packet, length,
                DiagnosticDnsCodec.PORT), query)) return null;
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
