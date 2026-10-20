package org.adaway.model.traffic;

import java.util.Arrays;

/** Strict benchmark-only IPv6 UDP codec. Extension/fragment headers are unsupported. */
final class DiagnosticUdp6 {
    private DiagnosticUdp6() { }
    private static int word(byte[] bytes, int at) {
        return ((bytes[at] & 255) << 8) | (bytes[at + 1] & 255);
    }
    private static void put(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 8); bytes[at + 1] = (byte) value;
    }
    private static long sum(byte[] bytes, int at, int size) {
        long sum = 0;
        for (int i = 0; i < size; i += 2)
            sum += ((bytes[at + i] & 255) << 8) | (i + 1 < size ? bytes[at + i + 1] & 255 : 0);
        return sum;
    }
    private static int checksum(long sum) {
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int) ~sum & 65535;
    }
    private static boolean address(byte[] bytes, int at, int last) {
        if (word(bytes, at) != 0x2001 || word(bytes, at + 2) != 2 || (bytes[at + 15] & 255) != last)
            return false;
        for (int i = 4; i < 15; i++) if (bytes[at + i] != 0) return false;
        return true;
    }
    static boolean valid(byte[] bytes, int size, int port) {
        if (bytes == null || size < 48 || size > 1500 || size > bytes.length) return false;
        if ((bytes[0] & 0xf0) != 0x60 || bytes[6] != 17 || bytes[7] == 0) return false;
        int udp = size - 40;
        return word(bytes, 4) == udp && word(bytes, 44) == udp
                && address(bytes, 8, 2) && address(bytes, 24, 1)
                && word(bytes, 40) != 0 && word(bytes, 42) == port && word(bytes, 46) != 0
                && checksum(sum(bytes, 8, 32) + udp + 17 + sum(bytes, 40, udp)) == 0;
    }
    static byte[] payload(byte[] bytes, int size, int port) {
        return valid(bytes, size, port) ? Arrays.copyOfRange(bytes, 48, size) : null;
    }
    static int sourcePort(byte[] bytes, int size, int port) {
        return valid(bytes, size, port) ? word(bytes, 40) : -1;
    }
    static byte[] reply(byte[] request, int size, int port, byte[] response) {
        if (!valid(request, size, port) || response == null || response.length > 1452) return null;
        byte[] result = Arrays.copyOf(request, 48 + response.length);
        for (int i = 0; i < 16; i++) {
            result[8 + i] = request[24 + i]; result[24 + i] = request[8 + i];
        }
        result[7] = 64;
        put(result, 4, 8 + response.length); put(result, 44, 8 + response.length);
        put(result, 40, port); put(result, 42, word(request, 40)); put(result, 46, 0);
        System.arraycopy(response, 0, result, 48, response.length);
        int check = checksum(sum(result, 8, 32) + 8 + response.length + 17 + sum(result, 40, 8 + response.length));
        put(result, 46, check == 0 ? 65535 : check);
        return result;
    }
}
