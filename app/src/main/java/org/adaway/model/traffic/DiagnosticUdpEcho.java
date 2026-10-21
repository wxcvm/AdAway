package org.adaway.model.traffic;

import java.util.Arrays;

/**
 * Strict, test-only IPv4/IPv6 UDP packet codec.
 *
 * The only accepted endpoints are diagnostic source 198.18.0.2 and
 * destination 198.18.0.1, or IPv6 2001:2::2 to 2001:2::1. No arbitrary host, default route or packet
 * forwarding is possible through this parser.
 */
public final class DiagnosticUdpEcho {
    public static final int PORT = 53535;
    private static final int IPV4_HEADER = 20;
    private static final int UDP_HEADER = 8;
    private static final int MAX_PACKET = 1500;

    private DiagnosticUdpEcho() { }

    private static int u8(byte[] b, int at) { return b[at] & 0xff; }
    private static int u16(byte[] b, int at) {
        return (u8(b, at) << 8) | u8(b, at + 1);
    }
    private static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }
    private static long sum16(byte[] b, int start, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int word = u8(b, start + i) << 8;
            if (i + 1 < len) word |= u8(b, start + i + 1);
            sum += word;
        }
        return sum;
    }
    private static int checksum(long sum) {
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    private static int validatedHeader(byte[] packet, int available, int port) {
        if (packet == null || available < IPV4_HEADER + UDP_HEADER
                || available > MAX_PACKET || available > packet.length) return -1;
        if ((u8(packet, 0) >>> 4) != 4) return -1;
        int hdr = (u8(packet, 0) & 0xf) * 4;
        if (hdr < IPV4_HEADER || hdr > 60 || hdr + UDP_HEADER > available) return -1;
        int total = u16(packet, 2);
        if (total != available || total < hdr + UDP_HEADER) return -1;
        if (!validIpv4(packet, hdr)) return -1;
        int udpLen = u16(packet, hdr + 4);
        if (udpLen != total - hdr || udpLen < UDP_HEADER) return -1;
        if (u16(packet, hdr) == 0 || u16(packet, hdr + 2) != port) return -1;
        // On IPv4 a UDP checksum of zero means "not provided". When present,
        // validate it against the pseudoheader to reject corrupted datagrams.
        int udpChecksum = u16(packet, hdr + 6);
        if (udpChecksum != 0) {
            long pseudo = sum16(packet, 12, 8) + 17 + udpLen;
            if (checksum(pseudo + sum16(packet, hdr, udpLen)) != 0) return -1;
        }
        return hdr;
    }

    private static boolean matchesAddress(byte[] packet, int offset, int last) {
        return u8(packet, offset) == 198 && u8(packet, offset + 1) == 18
                && u8(packet, offset + 2) == 0 && u8(packet, offset + 3) == last;
    }

    private static boolean validIpv4(byte[] packet, int header) {
        return u8(packet, 9) == 17 && (u16(packet, 6) & 0xbfff) == 0
                && matchesAddress(packet, 12, 2) && matchesAddress(packet, 16, 1)
                && checksum(sum16(packet, 0, header)) == 0;
    }

    /** Copy the payload of a packet addressed to an explicitly allowed port. */
    public static byte[] payload(byte[] packet, int available, int port) {
        if (ipv6(packet, available)) return DiagnosticUdp6.payload(packet, available, port);
        int hdr = validatedHeader(packet, available, port);
        if (hdr < 0) return null;
        return Arrays.copyOfRange(packet, hdr + UDP_HEADER, available);
    }

    public static int sourcePort(byte[] packet, int available, int port) {
        if (ipv6(packet, available)) return DiagnosticUdp6.sourcePort(packet, available, port);
        int hdr = validatedHeader(packet, available, port);
        return hdr < 0 ? -1 : u16(packet, hdr);
    }

    /** The original Echo operation, restricted to UDP 53535. */
    public static byte[] reply(byte[] packet, int available) {
        byte[] bytes = payload(packet, available, PORT);
        return bytes == null ? null : replyWithPayload(packet, available, PORT, bytes);
    }

    /**
     * Prepare a checksummed response with swapped IPs/ports and a replacement
     * UDP body. Callers must explicitly select the allowed diagnostic port.
     */
    public static byte[] replyWithPayload(byte[] packet, int available,
                                          int allowedPort, byte[] response) {
        if (ipv6(packet, available)) return DiagnosticUdp6.reply(packet, available, allowedPort, response);
        int hdr = validatedHeader(packet, available, allowedPort);
        if (hdr < 0 || response == null
                || response.length > MAX_PACKET - hdr - UDP_HEADER) return null;
        int total = hdr + UDP_HEADER + response.length;
        byte[] out = Arrays.copyOf(packet, total);
        for (int i = 0; i < 4; ++i) {
            byte tmp = out[12 + i];
            out[12 + i] = out[16 + i];
            out[16 + i] = tmp;
        }
        int from = u16(out, hdr);
        put16(out, hdr, allowedPort);
        put16(out, hdr + 2, from);
        put16(out, 2, total);
        put16(out, hdr + 4, UDP_HEADER + response.length);
        System.arraycopy(response, 0, out, hdr + UDP_HEADER, response.length);
        out[8] = 64;
        put16(out, 10, 0);
        put16(out, 10, checksum(sum16(out, 0, hdr)));
        put16(out, hdr + 6, 0);
        int udpLen = UDP_HEADER + response.length;
        int udpChecksum = checksum(sum16(out, 12, 8) + 17 + udpLen
                + sum16(out, hdr, udpLen));
        put16(out, hdr + 6, udpChecksum == 0 ? 0xffff : udpChecksum);
        return out;
    }

    static boolean ipv6(byte[] bytes, int length) {
        return bytes != null && length > 0 && bytes.length > 0 && (bytes[0] & 0xf0) == 0x60;
    }
}
