package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.io.IOException;
import org.junit.Test;

public final class DiagnosticDnsRelayTest {
    private static byte[] packet(byte[] question) {
        int len = 28 + question.length;
        byte[] packet = new byte[len];
        packet[0] = 0x45;
        packet[2] = (byte)(len >>> 8); packet[3] = (byte)len;
        packet[8] = 64; packet[9] = 17;
        packet[12] = (byte)198; packet[13] = 18; packet[15] = 2;
        packet[16] = (byte)198; packet[17] = 18; packet[19] = 1;
        packet[20] = 0x12; packet[21] = 0x34;
        packet[22] = (byte)(DiagnosticDnsCodec.PORT >>> 8);
        packet[23] = (byte)DiagnosticDnsCodec.PORT;
        packet[24] = (byte)((8 + question.length) >>> 8);
        packet[25] = (byte)(8 + question.length);
        System.arraycopy(question, 0, packet, 28, question.length);
        return packet;
    }

    @Test public void replyIsRepackagedAsDiagnosticUdp() {
        byte[] q = DiagnosticDnsCodec.query(0x18ac);
        byte[] p = packet(q);
        byte[] response = Arrays.copyOf(q, q.length);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        byte[] result = DiagnosticDnsRelay.process(p, p.length, query -> {
            assertArrayEquals(q, query);
            return response;
        });
        assertNotNull(result);
        assertEquals(28 + response.length, result.length);
        assertEquals(1, result[15] & 255);
        assertEquals(2, result[19] & 255);
        assertEquals(DiagnosticDnsCodec.PORT >>> 8, result[20] & 255);
        assertArrayEquals(response, Arrays.copyOfRange(result, 28, result.length));
    }

    @Test public void refusesOtherQueriesAndBadUpstream() {
        byte[] q = DiagnosticDnsCodec.query(4);
        byte[] p = packet(q);
        p[28+13] = 'x'; // changed allowed query name
        final int[] calls = {0};
        assertNull(DiagnosticDnsRelay.process(p, p.length, request -> {
            calls[0]++;
            return request;
        }));
        assertEquals(0, calls[0]);
        p = packet(q);
        assertNull(DiagnosticDnsRelay.process(p, p.length, request -> new byte[0]));
        assertNull(DiagnosticDnsRelay.process(p, p.length, request -> {
            throw new IOException("DNS timeout");
        }));
        assertNull(DiagnosticDnsRelay.process(p, p.length, null));
    }
}
