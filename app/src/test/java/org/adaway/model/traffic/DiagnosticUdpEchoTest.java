package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class DiagnosticUdpEchoTest {
    private static byte[] sample() {
        byte[] packet = new byte[32];
        packet[0] = 0x45; // IPv4 IHL=5
        packet[2] = 0; packet[3] = 32;
        packet[8] = 64;
        packet[9] = 17; // UDP
        packet[12] = (byte)198; packet[13] = 18;
        packet[14] = 0; packet[15] = 2; // diagnostic client
        packet[16] = (byte)198; packet[17] = 18;
        packet[18] = 0; packet[19] = 1; // diagnostic server
        packet[20] = 0x12; packet[21] = 0x34;
        packet[22] = (byte)(DiagnosticUdpEcho.PORT >>> 8);
        packet[23] = (byte)DiagnosticUdpEcho.PORT;
        packet[24] = 0; packet[25] = 12;
        packet[28] = 't'; packet[29] = 'e';
        packet[30] = 's'; packet[31] = 't';
        return DiagnosticPacketFixture.checksum(packet);
    }

    private static long sum(byte[] b, int from, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int word = (b[from+i] & 0xff) << 8;
            if (i + 1 < len) word |= b[from+i+1] & 0xff;
            sum += word;
        }
        while ((sum >>> 16) > 0) sum = (sum & 0xffff) + (sum >>> 16);
        return sum;
    }

    @Test public void respondsToDedicatedPortAndAddressOnly() {
        byte[] request = sample();
        byte[] reply = DiagnosticUdpEcho.reply(request, request.length);
        assertNotNull(reply);
        assertEquals(32, reply.length);
        assertEquals(1, reply[15]);
        assertEquals(2, reply[19]);
        assertEquals(DiagnosticUdpEcho.PORT >>> 8, reply[20] & 0xff);
        assertEquals(0x12, reply[22] & 0xff);
        assertEquals(0x34, reply[23] & 0xff);
        assertArrayEquals(Arrays.copyOfRange(request, 28, 32),
                          Arrays.copyOfRange(reply, 28, 32));
        assertEquals(0xffff, sum(reply, 0, 20)); // IPv4 header checksum
        long pseudo = sum(reply, 12, 8) + 17 + 12;
        long udpSum = pseudo;
        for (int i = 20; i < 32; i += 2) {
            udpSum += ((reply[i]&0xff) << 8) | (reply[i+1]&0xff);
        }
        while ((udpSum >>> 16) != 0) udpSum = (udpSum & 0xffff) + (udpSum >>> 16);
        assertEquals(0xffff, udpSum); // UDP pseudoheader checksum
    }

    @Test public void discardsUnexpectedDestinationsAndProtocols() {
        byte[] p = sample();
        p[19] = 3;
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[9] = 6; // TCP
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[23] = 53;
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
    }

    @Test public void discardsFragmentsMalformedAndOverlargePackets() {
        byte[] p = sample();
        p[6] = 0x20; // MF
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[6] = 0x01; // fragment offset
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[3] = 120; // truncated payload
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[25] = 100; // truncated UDP
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        p[0] = 0x65; // IPv6 version
        assertNull(DiagnosticUdpEcho.reply(p,p.length));
        p = sample();
        assertNull(DiagnosticUdpEcho.reply(p,p.length-1));
        assertNull(DiagnosticUdpEcho.reply(new byte[1501],1501));
        assertNull(DiagnosticUdpEcho.reply(null,32));
    }

    @Test public void rejectsZeroChecksumAndMalformedFuzzWithoutThrowing() {
        byte[] p = sample();
        p[10] = p[11] = 0;
        assertNull(DiagnosticUdpEcho.reply(p, p.length));
        java.util.Random random = new java.util.Random(4784);
        for (int i = 0; i < 10000; i++) {
            byte[] bytes = new byte[random.nextInt(2049)];
            random.nextBytes(bytes);
            assertNull(DiagnosticUdpEcho.reply(bytes, bytes.length));
        }
        for (int i = 0; i < 1000; i++) {
            // Valid packets remain a positive control after randomized payloads.
            byte[] positive = sample();
            positive[28] = (byte) random.nextInt();
            byte[] response = DiagnosticUdpEcho.reply(positive, positive.length);
            assertNotNull(response);
            assertEquals(positive[28], response[28]);
            assertEquals(0xffff, sum(response, 0, 20));
        }
    }
}
