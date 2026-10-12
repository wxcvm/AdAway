package org.adaway.model.traffic;

final class DiagnosticPacketFixture {
    static byte[] checksum(byte[] packet) {
        packet[10] = packet[11] = 0;
        long sum = 0;
        for (int i = 0; i < (packet[0] & 15) * 4; i += 2)
            sum += ((packet[i] & 255) << 8) | (packet[i + 1] & 255);
        while (sum > 65535) sum = (sum & 65535) + (sum >>> 16);
        int value = (int) ~sum & 65535;
        packet[10] = (byte) (value >>> 8); packet[11] = (byte) value;
        return packet;
    }
}
