package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;

public class DiagnosticDnsSharedVectorsTest {
    @Test public void sharedNativeVectorsMatchTheActualDiagnosticCodec() throws Exception {
        Path path = Paths.get("../webserver/jni/diagnostic_dns_vectors.tsv");
        if (!Files.exists(path)) path = Paths.get("webserver/jni/diagnostic_dns_vectors.tsv");
        int cases = 0;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cells = line.split("\t");
            byte[] q = hex(cells[1]), r = hex(cells[2]);
            int actual = DiagnosticDnsCodec.matchesResponse(q, r) ? 1
                : DiagnosticDnsCodec.shouldRetryOverTcp(q, r) ? 2 : 0;
            assertEquals(cells[0], Integer.parseInt(cells[3]), actual); cases++;
        }
        assertTrue(cases >= 20);
    }
    private static byte[] hex(String value) {
        if (value.equals("-")) return new byte[0];
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
