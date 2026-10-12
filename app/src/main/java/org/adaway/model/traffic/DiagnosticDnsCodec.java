package org.adaway.model.traffic;

import java.util.Arrays;

/**
 * Diagnostic-only DNS message validation. Exactly one fixed A question for
 * example.com is permitted. This is not a recursive/open DNS proxy and must
 * not be used with default TUN routes.
 */
public final class DiagnosticDnsCodec {
    public static final int PORT = 53537;
    public static final int MAX_DATAGRAM = 512;
    // DNS label form: example.com. + QTYPE=A + QCLASS=IN
    private static final byte[] QUESTION = {
            7, 'e','x','a','m','p','l','e',
            3, 'c','o','m', 0,
            0, 1, 0, 1
    };
    private static final int HEADER_LENGTH = 12;

    private DiagnosticDnsCodec() { }

    public static byte[] query(int transactionId) {
        byte[] value = new byte[HEADER_LENGTH + QUESTION.length];
        value[0] = (byte) (transactionId >>> 8);
        value[1] = (byte) transactionId;
        value[2] = 1; // RD: recursion requested; the query has no EDNS/extensions
        value[5] = 1; // exactly one question
        System.arraycopy(QUESTION, 0, value, HEADER_LENGTH, QUESTION.length);
        return value;
    }

    public static boolean isAllowedQuery(byte[] message) {
        if (message == null || message.length != HEADER_LENGTH + QUESTION.length)
            return false;
        if ((message[2] & 0xff) != 1 || message[3] != 0
                || message[4] != 0 || (message[5] & 0xff) != 1) return false;
        for (int i = 6; i < HEADER_LENGTH; ++i) {
            if (message[i] != 0) return false;
        }
        return matchesQuestion(message);
    }

    public static boolean matchesResponse(byte[] query, byte[] response) {
        if (!isAllowedQuery(query) || response == null
                || response.length < HEADER_LENGTH + QUESTION.length
                || response.length > MAX_DATAGRAM) return false;
        if (query[0] != response[0] || query[1] != response[1]) return false;
        // QR=1, OPCODE=0, not truncated. Ignore AA/RA/rcode for diagnostics.
        if ((response[2] & 0x80) == 0 || (response[2] & 0x78) != 0
                || (response[2] & 0x02) != 0) return false;
        if ((response[4] & 0xff) != 0 || (response[5] & 0xff) != 1)
            return false;
        return matchesQuestion(response);
    }

    private static boolean matchesQuestion(byte[] message) {
        if (message.length < HEADER_LENGTH + QUESTION.length) return false;
        for (int i = 0; i < QUESTION.length; i++) {
            if (message[HEADER_LENGTH + i] != QUESTION[i]) return false;
        }
        return true;
    }

    public static int responseCode(byte[] response) {
        return response == null || response.length < HEADER_LENGTH
                ? -1 : response[3] & 0x0f;
    }
}
