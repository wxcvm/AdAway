package org.adaway.model.traffic;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class DnsPolicyWireTest {
    private static int u16(byte[] bytes, int at) {
        return ((bytes[at] & 255) << 8) | (bytes[at + 1] & 255);
    }

    private static byte[] query(String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] hdr = new byte[12];
        hdr[0] = 0x12;
        hdr[1] = 0x34;
        hdr[2] = 1; // RD
        hdr[5] = 1; // QDCOUNT
        out.write(hdr, 0, hdr.length);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        out.write(type >>> 8);
        out.write(type & 0xff);
        out.write(0);
        out.write(1); // IN
        return out.toByteArray();
    }

    private static RuleSnapshotRepository.Snapshot snapshot(
            Map<String, TrafficCoreContract.Action> rules, Map<String, String> replacements) {
        RuleSnapshotRepository repo = new RuleSnapshotRepository();
        assertTrue(repo.publishEffective(1, rules, replacements));
        return repo.read();
    }

    @Test public void nxdomainAndRefusedPreserveTransactionAndQuestion() {
        Map<String, TrafficCoreContract.Action> map = new HashMap<>();
        map.put("blocked.test", TrafficCoreContract.Action.NXDOMAIN);
        map.put("refused.test", TrafficCoreContract.Action.REFUSED);
        RuleSnapshotRepository.Snapshot rules = snapshot(map, Map.of());
        for (String domain : new String[]{"blocked.test", "refused.test"}) {
            byte[] request = query(domain, 1);
            byte[] response = rules.answerDns(request);
            assertNotNull(response);
            assertEquals(request.length, response.length);
            assertEquals(0x12, response[0] & 255);
            assertEquals(0x34, response[1] & 255);
            assertEquals(0x81, response[2] & 255); // QR and RD, not RA
            assertEquals(domain.equals("blocked.test") ? 3 : 5, response[3] & 255);
            assertEquals(1, u16(response, 4));
            assertEquals(0, u16(response, 6));
            assertEquals(0, u16(response, 8));
            assertEquals(0, u16(response, 10));
            for (int at = 12; at < request.length; at++)
                assertEquals(request[at], response[at]);
        }
    }

    @Test public void zeroAddressAAndAaaaUseZeroTtlAndCorrectSize() {
        RuleSnapshotRepository.Snapshot rules = snapshot(
                Map.of("zero.test", TrafficCoreContract.Action.ZERO_ADDRESS), Map.of());
        for (int type : new int[]{1,28}) {
            byte[] request = query("zero.test", type);
            byte[] response = rules.answerDns(request);
            int width = type == 1 ? 4 : 16;
            assertNotNull(response);
            assertEquals(request.length + 12 + width, response.length);
            assertEquals(1, u16(response, 6));
            int at = request.length;
            assertEquals(0xc0, response[at] & 255);
            assertEquals(0x0c, response[at + 1] & 255);
            assertEquals(type, u16(response, at + 2));
            assertEquals(1, u16(response, at + 4));
            for (int i = 6; i < 10; i++) assertEquals(0, response[at + i]); // TTL=0
            assertEquals(width, u16(response, at + 10));
            for (int i = 0; i < width; i++) assertEquals(0, response[at + 12 + i]);
        }
    }

    @Test public void exactAndWildcardReplacementsAgreeWithPublishedDecisions() {
        RuleSnapshotRepository.Snapshot rules = snapshot(Map.of(
                "*.ads.test", TrafficCoreContract.Action.REPLACE,
                "allow.ads.test", TrafficCoreContract.Action.PASS,
                "six.test", TrafficCoreContract.Action.REPLACE
        ), Map.of("*.ads.test", "203.0.113.9", "six.test", "2001:db8::1"));

        assertNull(rules.answerDns(query("allow.ads.test",1))); // exact allow beats wildcard
        byte[] request = query("X.ADS.TEST",1);
        byte[] response = rules.answerDns(request);
        assertNotNull(response);
        int at = request.length;
        assertArrayEquals(new byte[]{(byte) 203,0,113,9},
                new byte[]{response[at+12],response[at+13],response[at+14],response[at+15]});
        assertNull(rules.answerDns(query("x.ads.test",28))); // family mismatch
        assertNull(rules.answerDns(query("six.test",1)));
        byte[] v6 = rules.answerDns(query("six.test",28));
        assertNotNull(v6);
        assertEquals(16, u16(v6, v6.length - 18));
        assertEquals(1, v6[v6.length - 1]);
    }

    @Test public void uninitializedRulesAndOrdinaryPassFailOpen() {
        RuleSnapshotRepository repo = new RuleSnapshotRepository();
        byte[] request = query("ordinary.test",1);
        assertNull(repo.read().answerDns(request)); // no installed generation
        RuleSnapshotRepository.Snapshot valid = snapshot(
                Map.of("ordinary.test", TrafficCoreContract.Action.PASS),Map.of());
        assertNull(valid.answerDns(request));
    }

    @Test public void rejectEdnsMultiQuestionPointerMalformedOrUnknownType() {
        RuleSnapshotRepository.Snapshot rules = snapshot(
                Map.of("example.test", TrafficCoreContract.Action.NXDOMAIN), Map.of());
        byte[] good = query("example.test", 1);
        assertNotNull(rules.answerDns(good));
        assertNull(rules.answerDns(query("example.test", 15))); // MX unsupported
        byte[] bad = good.clone(); bad[11] = 1; // EDNS/additional
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[5] = 2;
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[2] = (byte) 0x81; // already a response
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[3] = 0x10; // unsupported DNS flag
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[12] = (byte)0xc0; // pointer in QNAME
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[13] = 1; // corrupt question
        assertNull(rules.answerDns(bad));
        bad = good.clone(); bad[bad.length - 1] = 3; // not class IN
        assertNull(rules.answerDns(bad));
        bad = java.util.Arrays.copyOf(good, good.length - 1);
        assertNull(rules.answerDns(bad));
        assertNull(rules.answerDns(null));
    }

    @Test public void doNotBlockWildcardDomainWithAnExactAllow() {
        RuleSnapshotRepository.Snapshot rules = snapshot(
                Map.of("*.block.test", TrafficCoreContract.Action.NXDOMAIN,
                        "safe.block.test", TrafficCoreContract.Action.PASS),
                Map.of());
        assertNotNull(rules.answerDns(query("other.block.test",1)));
        assertNull(rules.answerDns(query("safe.block.test",1)));
    }
}
