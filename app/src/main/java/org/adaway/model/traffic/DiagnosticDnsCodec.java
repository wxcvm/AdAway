package org.adaway.model.traffic;

/**
 * Diagnostic-only DNS message validation. Exactly one fixed A or AAAA question for
 * example.com is permitted. This is not a recursive/open DNS proxy and must
 * not be used with default TUN routes.
 */
public final class DiagnosticDnsCodec {
    public static final int PORT = 53537;
    public static final int MAX_DATAGRAM = 512;
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    // DNS label form: example.com. + QTYPE=A + QCLASS=IN
    private static final byte[] QUESTION = {
            7, 'e','x','a','m','p','l','e',
            3, 'c','o','m', 0,
            0, 1, 0, 1
    };
    private static final int HEADER_LENGTH = 12;

    private DiagnosticDnsCodec() { }

    public static byte[] query(int transactionId) {
        return query(transactionId, TYPE_A);
    }

    public static byte[] query(int transactionId, int type) {
        if (type != TYPE_A && type != TYPE_AAAA)
            throw new IllegalArgumentException("Unsupported diagnostic DNS type");
        byte[] value = new byte[HEADER_LENGTH + QUESTION.length];
        value[0] = (byte) (transactionId >>> 8);
        value[1] = (byte) transactionId;
        value[2] = 1; // RD: recursion requested; the query has no EDNS/extensions
        value[5] = 1; // exactly one question
        System.arraycopy(QUESTION, 0, value, HEADER_LENGTH, QUESTION.length);
        value[HEADER_LENGTH + QUESTION.length - 3] = (byte) type;
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
        return matchesHeaderAndQuestion(query, response)
                && (response[2] & 0x02) == 0 && validRecords(response);
    }

    /** Only a matching, bounded UDP reply may request one TCP retry. */
    public static boolean shouldRetryOverTcp(byte[] query, byte[] response) {
        return matchesHeaderAndQuestion(query, response) && (response[2] & 0x02) != 0;
    }

    private static boolean matchesHeaderAndQuestion(byte[] query, byte[] response) {
        if (!isAllowedQuery(query) || response == null
                || response.length < HEADER_LENGTH + QUESTION.length
                || response.length > MAX_DATAGRAM) return false;
        if (query[0] != response[0] || query[1] != response[1]) return false;
        // QR=1 and OPCODE=0. Ignore AA/RA/rcode for diagnostics.
        if ((response[2] & 0x80) == 0 || (response[2] & 0x78) != 0) return false;
        if ((response[4] & 0xff) != 0 || (response[5] & 0xff) != 1)
            return false;
        // Match the original type as well as name/class. A and AAAA permits
        // and replies are never interchangeable, even with the same ID.
        for (int i = HEADER_LENGTH; i < HEADER_LENGTH + QUESTION.length; i++)
            if (query[i] != response[i]) return false;
        return true;
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
    }

    private static int nameEnd(byte[] message, int offset) {
        boolean[] visited = new boolean[message.length];
        int end = -1, expanded = 1;
        while (offset < message.length) {
            if (visited[offset]) return -1;
            visited[offset] = true;
            int size = message[offset] & 255;
            if (size == 0) return end < 0 ? offset + 1 : end;
            if ((size & 192) == 192) {
                if (offset + 1 >= message.length) return -1;
                if (end < 0) end = offset + 2;
                offset = ((size & 63) << 8) | (message[offset + 1] & 255);
            } else {
                if ((size & 192) != 0 || offset + size + 1 >= message.length) return -1;
                expanded += size + 1;
                if (expanded > 255) return -1;
                offset += size + 1;
            }
        }
        return -1;
    }

    private static boolean validRecords(byte[] message) {
        int offset = HEADER_LENGTH + QUESTION.length;
        int count = unsigned16(message, 6) + unsigned16(message, 8) + unsigned16(message, 10);
        for (int i = 0; i < count; i++) {
            offset = nameEnd(message, offset);
            if (offset < 0 || offset + 10 > message.length) return false;
            offset += 10 + unsigned16(message, offset + 8);
            if (offset > message.length) return false;
        }
        return offset == message.length;
    }

    private static boolean matchesQuestion(byte[] message) {
        if (message.length < HEADER_LENGTH + QUESTION.length) return false;
        for (int i = 0; i < QUESTION.length; i++) {
            if (i == QUESTION.length - 3) {
                if (message[HEADER_LENGTH + i] != TYPE_A
                        && message[HEADER_LENGTH + i] != TYPE_AAAA) return false;
                continue;
            }
            if (message[HEADER_LENGTH + i] != QUESTION[i]) return false;
        }
        return true;
    }

    public static int responseCode(byte[] response) {
        return response == null || response.length < HEADER_LENGTH
                ? -1 : response[3] & 0x0f;
    }
}
