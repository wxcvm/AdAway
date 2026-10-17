package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public final class DiagnosticUdpRelayTest {
    private static byte[] datagram(byte[] payload) {
        int size = 28 + payload.length;
        byte[] p = new byte[size];
        p[0] = 0x45;
        p[2] = (byte) (size >>> 8);
        p[3] = (byte) size;
        p[8] = 64;
        p[9] = 17;
        p[12] = (byte) 198; p[13] = 18; p[14] = 0; p[15] = 2;
        p[16] = (byte) 198; p[17] = 18; p[18] = 0; p[19] = 1;
        p[20] = 0x12; p[21] = 0x34;
        p[22] = (byte) (DiagnosticUdpRelay.PORT >>> 8);
        p[23] = (byte) DiagnosticUdpRelay.PORT;
        int udpLength = 8 + payload.length;
        p[24] = (byte) (udpLength >>> 8);
        p[25] = (byte) udpLength;
        System.arraycopy(payload, 0, p, 28, payload.length);
        return DiagnosticPacketFixture.checksum(p);
    }

    private static long sum(byte[] p, int from, int length) {
        long result = 0;
        for (int i = 0; i < length; i += 2) {
            result += (p[from + i] & 255) << 8;
            if (i + 1 < length) result += p[from + i + 1] & 255;
        }
        return result;
    }

    private static int fold(long v) {
        while ((v >>> 16) != 0) v = (v & 0xffff) + (v >>> 16);
        return (int) v;
    }

    @Test public void relaysAndRewritesPacketChecksums() {
        byte[] p = datagram(new byte[] {1,2,3});
        final int[] count = {0};
        byte[] answer = DiagnosticUdpRelay.process(p, p.length, bytes -> {
            count[0]++;
            assertArrayEquals(new byte[] {1,2,3}, bytes);
            return new byte[] {4,5,6,7,8};
        });
        assertNotNull(answer);
        assertEquals(33, answer.length);
        assertEquals(1, count[0]);
        assertEquals(1, answer[15]); // IP source is destination of request
        assertEquals(2, answer[19]); // IP destination is source of request
        assertEquals(53536 >>> 8, answer[20] & 255);
        assertEquals(0x12, answer[22] & 255);
        assertEquals(0x34, answer[23] & 255);
        assertArrayEquals(new byte[]{4,5,6,7,8},
                Arrays.copyOfRange(answer, 28, 33));
        assertEquals(0xffff, fold(sum(answer,0,20)));
        assertEquals(0xffff, fold(sum(answer,12,8) + 17 + 13
                + sum(answer,20,13)));
    }

    @Test public void rejectsSpoofedAddressesAndPortsBeforeAnyTransport() {
        byte[] p = datagram(new byte[]{1});
        final int[] calls = {0};
        DiagnosticUdpRelay.Exchange transport = request -> { calls[0]++; return request; };
        p[19] = 3;
        assertNull(DiagnosticUdpRelay.process(p,p.length,transport));
        p = datagram(new byte[]{1});
        p[15] = 3;
        assertNull(DiagnosticUdpRelay.process(p,p.length,transport));
        p = datagram(new byte[]{1});
        p[23] = 53;
        assertNull(DiagnosticUdpRelay.process(p,p.length,transport));
        assertEquals(0,calls[0]);
    }

    @Test public void rejectsEmptyOrOversizedPayloads() {
        byte[] p = datagram(new byte[0]);
        assertNull(DiagnosticUdpRelay.process(p,p.length, b->b));
        p = datagram(new byte[257]);
        assertNull(DiagnosticUdpRelay.process(p,p.length, b->b));
        p = datagram(new byte[]{1});
        assertNull(DiagnosticUdpRelay.process(p,p.length,b->new byte[257]));
        assertNull(DiagnosticUdpRelay.process(p,p.length,b->new byte[0]));
        assertNull(DiagnosticUdpRelay.process(p,p.length,null));
    }

    @Test public void transportFailuresAreIsolated() {
        byte[] p = datagram(new byte[]{1,2});
        assertNull(DiagnosticUdpRelay.process(p,p.length,b -> {
            throw new IOException("closed socket");
        }));
        assertNull(DiagnosticUdpRelay.process(p,p.length,b -> {
            throw new IllegalStateException("timeout");
        }));
    }

    @Test public void rejectsCorruptedChecksumsAndFragments() {
        byte[] p = datagram(new byte[]{1});
        p[6] = 0x20;
        assertNull(DiagnosticUdpRelay.process(p,p.length,b->b));
        p = datagram(new byte[]{1});
        p[10] = 0x12; p[11] = 0x34; // wrong IPv4 checksum
        assertNull(DiagnosticUdpRelay.process(p,p.length,b->b));
        p = datagram(new byte[]{1});
        p[26] = 0x12; p[27] = 0x34; // wrong UDP checksum
        assertNull(DiagnosticUdpRelay.process(p,p.length,b->b));
    }
}
