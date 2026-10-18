package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.adaway.model.source.DomainSourceParser;
import org.junit.Test;

/** Same imported source, decision metadata and exact DNS bytes as the Windows CLI. */
public class DiagnosticDnsPolicyVectorsTest {
    @Test public void actualParserSnapshotAndRelayMatchSharedPolicyVectors() throws Exception {
        Path path = Path.of("webserver/jni/diagnostic_dns_policy_vectors.tsv");
        if (!Files.exists(path)) path = Path.of("../webserver/jni/diagnostic_dns_policy_vectors.tsv");
        int cases = 0;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] f = line.split("\t");
            List<DomainRule> records = new ArrayList<>();
            for (String raw : (f[2].equals("-") ? "" : f[2].replace("\\n", "\n")).split("\n")) {
                for (DomainSourceParser.Entry entry : DomainSourceParser.parse(raw, false, true).entries) {
                    records.add(new DomainRule(entry.domain,
                        entry.kind == DomainSourceParser.Kind.ALLOW ? TrafficCoreContract.Action.PASS
                        : entry.kind == DomainSourceParser.Kind.REPLACE ? TrafficCoreContract.Action.REPLACE
                        : TrafficCoreContract.Action.NXDOMAIN, entry.scope, entry.address, 2, records.size()+1L, false));
                }
            }
            RuleSnapshotRepository.Snapshot snapshot = RuleSnapshotRepository.prepareRules(1, records);
            assertNotNull(f[0], snapshot);
            int type = Integer.parseInt(f[1]);
            RuleSnapshotRepository.Resolution hit = snapshot.resolve("example.com", type, 1);
            assertEquals(f[0], Integer.parseInt(f[3]), hit.decision.action.ordinal());
            assertEquals(f[0], Long.parseLong(f[4]), hit.rule == null ? 0 : hit.rule.sourceId);
            assertEquals(f[0], Integer.parseInt(f[5]), hit.rule == null ? 0 : hit.rule.scope.ordinal());
            assertEquals(1, hit.generation);
            byte[] query = DiagnosticDnsCodec.query(0x5353, type);
            int[] calls = {0};
            byte[] answer = DiagnosticDnsRelay.resolveFromSnapshotOrUpstream(query, snapshot, q -> {
                assertArrayEquals(query, q); calls[0]++;
                byte[] reply = q.clone(); reply[2] = (byte)0x81; return reply;
            });
            assertTrue(f[0], DiagnosticDnsCodec.matchesResponse(query, answer));
            if (f[6].equals("-")) {
                assertEquals(f[0], 1, calls[0]);
                byte[] expected = query.clone(); expected[2] = (byte)0x81;
                assertArrayEquals(f[0], expected, answer);
            } else {
                assertEquals(f[0], 0, calls[0]);
                assertArrayEquals(f[0], hex(f[6]), answer);
            }
            cases++;
        }
        assertEquals(16, cases);
    }
    static byte[] hex(String value) {
        byte[] bytes = new byte[value.length()/2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(value.substring(2*i,2*i+2),16);
        return bytes;
    }
}
