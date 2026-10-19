package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class DiagnosticDnsCodecTest {
    private static byte[] response(byte[] question) {
        byte[] answer = Arrays.copyOf(question, question.length + 16);
        answer[2] = (byte) 0x81; // QR=1 RD=1
        answer[3] = (byte) 0x80; // RA=1
        answer[7] = 1;          // one answer
        int at = question.length;
        answer[at] = (byte) 0xc0; answer[at + 1] = 0x0c;
        answer[at + 2] = 0; answer[at + 3] = 1;
        answer[at + 4] = 0; answer[at + 5] = 1;
        answer[at + 6] = 0; answer[at + 7] = 0;
        answer[at + 8] = 0; answer[at + 9] = 30;
        answer[at + 10] = 0; answer[at + 11] = 4;
        answer[at + 12] = (byte) 93;
        answer[at + 13] = (byte) 184;
        answer[at + 14] = (byte) 215;
        answer[at + 15] = 14;
        return answer;
    }

    @Test public void fixedQuestionIncludesOnlyExampleComAOrAaaa() {
        byte[] q = DiagnosticDnsCodec.query(0x1234);
        assertEquals(29, q.length);
        assertEquals(0x12, q[0] & 255);
        assertEquals(0x34, q[1] & 255);
        assertTrue(DiagnosticDnsCodec.isAllowedQuery(q));
        q[13] = 'x';
        assertFalse(DiagnosticDnsCodec.isAllowedQuery(q));
        byte[] aaaa = DiagnosticDnsCodec.query(0x1234, DiagnosticDnsCodec.TYPE_AAAA);
        assertTrue(DiagnosticDnsCodec.isAllowedQuery(aaaa));
        assertEquals(28, aaaa[26]);
        for (int type : new int[]{0, 15, 255, 256, 65535}) {
            try { DiagnosticDnsCodec.query(1, type); fail("type " + type); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void validatedResponseNeedsMatchingIdNameAndQuestion() {
        byte[] q = DiagnosticDnsCodec.query(0x1357);
        byte[] answer = response(q);
        assertTrue(DiagnosticDnsCodec.matchesResponse(q, answer));
        assertEquals(0, DiagnosticDnsCodec.responseCode(answer));
        answer[0]++;
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, answer));
        answer = response(q);
        answer[12] = (byte)0xc0;
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, answer));
        answer = response(q);
        answer[2] = 1; // no QR
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, answer));
    }

    @Test public void malformedDnsIsFailClosed() {
        byte[] q = DiagnosticDnsCodec.query(1);
        byte[] a = response(q);
        a[2] |= 2; // TC truncation
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, a));
        a = response(q);
        a[5] = 2;
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, a));
        a = response(q);
        a[2] |= 0x08; // unexpected OPCODE
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, a));
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, new byte[513]));
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, null));
        assertFalse(DiagnosticDnsCodec.isAllowedQuery(new byte[10]));
    }

    @Test public void rejectsTruncatedRecordsPointersAndTrailingBytes() {
        byte[] q = DiagnosticDnsCodec.query(1), good = response(q);
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, Arrays.copyOf(good, good.length - 1)));
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, Arrays.copyOf(good, good.length + 1)));
        byte[] loop = good.clone();
        loop[q.length + 1] = (byte) q.length;
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, loop));
        byte[] missing = q.clone(); missing[2] = (byte) 0x81; missing[7] = 1;
        assertFalse(DiagnosticDnsCodec.matchesResponse(q, missing));
        java.util.Random random = new java.util.Random(53537);
        for (int i = 0; i < 10000; i++) {
            byte[] packet = new byte[random.nextInt(514)]; random.nextBytes(packet);
            DiagnosticDnsCodec.matchesResponse(q, packet); // malformed wire must never throw
        }
    }
}
