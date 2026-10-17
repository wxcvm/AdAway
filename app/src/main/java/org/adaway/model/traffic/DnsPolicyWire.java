package org.adaway.model.traffic;

import com.google.common.net.InetAddresses;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stateless DNS/UDP response encoder driven by an immutable, already-published
 * domain rule snapshot. This class NEVER opens a socket, changes a route,
 * enables a VPN or intercepts traffic. Returning null means "not handled":
 * the eventual transport must forward normally or fail according to its own
 * explicit network policy, rather than silently drop unrelated DNS requests.
 *
 * Scope v1: one uncompressed ASCII IN A/AAAA question, no EDNS, DNSSEC,
 * multiple questions or extra sections. Synthetic answers use TTL=0 so
 * changing rule generations never leaves a newly added exception behind in
 * a downstream cache. No negative SOA is emitted for local NXDOMAIN.
 */
public final class DnsPolicyWire {
    private static final int DNS_HEADER = 12;
    public static final int MAX_UDP_MESSAGE = 512;

    private DnsPolicyWire() { }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    /** Parses a strict subset, rejecting compression pointers in questions. */
    private static final class Question {
        final String name;
        final int type;
        Question(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    private static Question parse(byte[] message) {
        if (message == null || message.length < DNS_HEADER + 7
                || message.length > MAX_UDP_MESSAGE) return null;
        // Exactly one ordinary query. Only the RD bit is permitted; reject
        // CD/AD, Z, nonzero OPCODE, TC, QR and reserved bits.
        if ((message[2] & 0xfe) != 0 || message[3] != 0
                || u16(message, 4) != 1 || u16(message, 6) != 0
                || u16(message, 8) != 0 || u16(message, 10) != 0)
            return null;
        int offset = DNS_HEADER;
        StringBuilder name = new StringBuilder(80);
        int wireNameLength = 1;
        boolean hasLabel = false;
        while (offset < message.length) {
            int size = message[offset++] & 0xff;
            if (size == 0) break;
            // A compressed QNAME is not accepted: no ambiguity or pointer loops.
            if (size > 63 || offset + size > message.length
                    || wireNameLength + size + 1 > 255) return null;
            if (hasLabel) name.append('.');
            for (int i = 0; i < size; i++) {
                int c = message[offset + i] & 0xff;
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-')) return null;
                if ((i == 0 || i == size - 1) && c == '-') return null;
                name.append((char) c);
            }
            offset += size;
            wireNameLength += size + 1;
            hasLabel = true;
        }
        if (!hasLabel || name.length() > 253 || offset + 4 != message.length)
            return null;
        int type = u16(message, offset);
        int qclass = u16(message, offset + 2);
        if (qclass != 1 || (type != 1 && type != 28)) return null;
        return new Question(name.toString().toLowerCase(Locale.ROOT), type);
    }

    /**
     * Returns a complete DNS message for a rule hit, null for PASS or an
     * invalid/unsupported request. The caller owns the returned byte array.
     */
    public static byte[] respond(byte[] query, RuleSnapshotRepository.Snapshot snapshot) {
        if (snapshot == null || snapshot.generation < 1) return null;
        Question question = parse(query);
        if (question == null) return null;

        TrafficCoreContract.Decision decision = snapshot.decide(
                question.name, question.type, 1);
        if (decision == null || decision.action == TrafficCoreContract.Action.PASS)
            return null;

        int rcode = 0;
        byte[] address = null;
        switch (decision.action) {
            case NXDOMAIN:
                rcode = 3;
                break;
            case REFUSED:
                rcode = 5;
                break;
            case ZERO_ADDRESS:
                address = new byte[question.type == 1 ? 4 : 16];
                break;
            case REPLACE:
                String value = snapshot.replacementFor(question.name);
                if (value == null) return null;
                address = InetAddresses.forString(value).getAddress();
                if (address.length != (question.type == 1 ? 4 : 16))
                    return null;
                break;
            default:
                return null;
        }

        int answerBytes = address == null ? 0 : 12 + address.length;
        if (query.length + answerBytes > MAX_UDP_MESSAGE) return null;
        byte[] response = new byte[query.length + answerBytes];
        System.arraycopy(query, 0, response, 0, query.length);
        // Preserve transaction ID and the request's RD flag, set QR=1.
        // RA=0 because this synthetic local policy is not a recursive resolver.
        response[2] = (byte) (0x80 | (query[2] & 1));
        response[3] = (byte) rcode;
        response[6] = 0;
        response[7] = (byte) (address == null ? 0 : 1);

        if (address != null) {
            int offset = query.length;
            response[offset++] = (byte) 0xc0;
            response[offset++] = 0x0c; // points to the exact original QNAME
            response[offset++] = (byte) (question.type >>> 8);
            response[offset++] = (byte) question.type;
            response[offset++] = 0;
            response[offset++] = 1; // IN
            offset += 4; // TTL=0, no cached stale rule generation
            response[offset++] = 0;
            response[offset++] = (byte) address.length;
            System.arraycopy(address, 0, response, offset, address.length);
        }
        return response;
    }
}
