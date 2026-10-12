package org.adaway.model.traffic;

import java.util.Arrays;

/**
 * A diagnostic-only bidirectional IPv4 UDP packet responder.
 *
 * Responds exclusively to UDP port 53535 on 198.18.0.1 (the /32 routed into
 * the isolated TUN). All other packets are ignored. This is not a generic
 * forwarder: it must never be paired with a default route.
 *
 * Implements IPv4 and UDP checksums, including the UDP pseudoheader, so the
 * calling socket can actually verify a reply reached it through the TUN.
 */
public final class DiagnosticUdpEcho {
    public static final int PORT = 53535;
    private static final int IPV4_MIN_HEADER = 20;
    private static final int UDP_HEADER = 8;
    private static final int MAX_PACKET = 1500;

    private DiagnosticUdpEcho() {
    }

    private static int u8(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    private static int u16(byte[] bytes, int offset) {
        return (u8(bytes, offset) << 8) | u8(bytes, offset + 1);
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static long sum16(byte[] data, int start, int length) {
        long sum = 0;
        int end = start + length;
        for (int at = start; at + 1 < end; at += 2) {
            sum += u16(data, at);
        }
        if ((length & 1) != 0) {
            sum += u8(data, end - 1) << 8;
        }
        return sum;
    }

    private static int foldedChecksum(long sum) {
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    /** @return a fully checksummed reply, or null for unsafe/irrelevant input. */
    public static byte[] reply(byte[] packet, int available) {
        if (packet == null || available < IPV4_MIN_HEADER + UDP_HEADER
                || available > MAX_PACKET || available > packet.length) {
            return null;
        }
        if ((u8(packet, 0) >>> 4) != 4) return null;
        int headerLen = (u8(packet, 0) & 15) * 4;
        if (headerLen < IPV4_MIN_HEADER || headerLen > 60
                || headerLen > available - UDP_HEADER) {
            return null;
        }
        int total = u16(packet, 2);
        if (total < headerLen + UDP_HEADER || total > available) return null;
        if (u8(packet, 9) != 17) return null; // UDP only

        // Reject fragmented datagrams (MF or nonzero offset). Preserve DF.
        if ((u16(packet, 6) & 0x3fff) != 0) return null;
        if (u8(packet, 16) != 198 || u8(packet, 17) != 18
                || u8(packet, 18) != 0 || u8(packet, 19) != 1) return null;

        int udp = headerLen;
        int udpLen = u16(packet, udp + 4);
        if (udpLen < UDP_HEADER || udpLen > total - headerLen) return null;
        if (u16(packet, udp + 2) != PORT || u16(packet, udp) == 0) return null;

        int exactLength = headerLen + udpLen;
        byte[] out = Arrays.copyOf(packet, exactLength);
        // Reverse the IPv4 endpoints and UDP ports to return the payload.
        for (int i = 0; i < 4; i++) {
            byte b = out[12 + i];
            out[12 + i] = out[16 + i];
            out[16 + i] = b;
        }
        int from = u16(out, udp);
        int to = u16(out, udp + 2);
        put16(out, udp, to);
        put16(out, udp + 2, from);
        put16(out, 2, exactLength);
        out[8] = 64; // fresh TTL
        put16(out, 10, 0);
        put16(out, 10, foldedChecksum(sum16(out, 0, headerLen)));

        put16(out, udp + 6, 0);
        long pseudo = sum16(out, 12, 8) + 17 + udpLen;
        int udpChecksum = foldedChecksum(pseudo + sum16(out, udp, udpLen));
        put16(out, udp + 6, udpChecksum == 0 ? 0xffff : udpChecksum);
        return out;
    }
}
