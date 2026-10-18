package org.adaway.model.traffic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.adaway.model.source.DomainSourceParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class TypedRuleSnapshotTest {
    @Test public void sharedActualParserVectorsPreserveScopeAddressAndProvenance() throws Exception {
        Path file = Path.of("webserver/jni/typed_rule_vectors.tsv");
        if (!Files.exists(file)) file = Path.of("../webserver/jni/typed_rule_vectors.tsv");
        Map<String, List<DomainRule>> cases = new HashMap<>();
        Map<String, Long> orders = new HashMap<>();
        int queries = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f[0].equals("R")) {
                List<DomainRule> rules = cases.computeIfAbsent(f[1], unused -> new ArrayList<>());
                for (DomainSourceParser.Entry entry : DomainSourceParser.parse(f[5], f[3].equals("1"), f[4].equals("1")).entries) {
                    String key = f[1] + "/" + f[2]; long order = orders.merge(key, 1L, Long::sum);
                    rules.add(new DomainRule(entry.domain, entry.kind == DomainSourceParser.Kind.ALLOW
                            ? TrafficCoreContract.Action.PASS : entry.kind == DomainSourceParser.Kind.REPLACE
                            ? TrafficCoreContract.Action.REPLACE : TrafficCoreContract.Action.NXDOMAIN,
                            entry.scope, entry.address, Long.parseLong(f[2]), order, false));
                }
            } else {
                RuleSnapshotRepository.Snapshot snapshot = RuleSnapshotRepository.prepareRules(7, cases.get(f[1]));
                assertNotNull(line, snapshot);
                RuleSnapshotRepository.Resolution result = snapshot.resolve(f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                assertEquals(line, Integer.parseInt(f[5]), result.decision.action.ordinal());
                assertEquals(line, Long.parseLong(f[6]), result.rule == null ? 0 : result.rule.sourceId);
                assertEquals(line, Long.parseLong(f[7]), result.rule == null ? 0 : result.rule.order);
                assertEquals(line, Integer.parseInt(f[8]), result.rule == null ? 0 : result.rule.scope.ordinal());
                assertEquals(line, f[9].equals("-") ? null : f[9], result.address);
                assertEquals(7, result.generation); queries++;
            }
        }
        assertTrue(queries >= 25);
    }
    @Test public void ownershipStaleInvalidUpdatesAndTypedDnsStayConsistent() {
        List<DomainRule> input = new ArrayList<>();
        input.add(new DomainRule("ads.test", TrafficCoreContract.Action.NXDOMAIN, DomainRule.Scope.SUFFIX, null, 2, 1, false));
        RuleSnapshotRepository store = new RuleSnapshotRepository();
        assertTrue(store.publishPrepared(RuleSnapshotRepository.prepareRules(1, input)));
        input.clear();
        assertEquals(TrafficCoreContract.Action.NXDOMAIN, store.read().decide("child.ads.test", 1, 1).action);
        assertFalse(store.publishPrepared(RuleSnapshotRepository.prepareRules(1, input)));
        input.add(new DomainRule("ads.test", TrafficCoreContract.Action.REPLACE, DomainRule.Scope.EXACT, "not-an-ip", 2, 1, false));
        assertNull(RuleSnapshotRepository.prepareRules(2, input));
        assertEquals(1, store.read().generation);
        try { store.read().records.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void threeHundredThousandExactRulesAndWildcardPriorityUseIndexes() {
        List<DomainRule> input = new ArrayList<>();
        for (int i = 0; i < 300000; i++) input.add(new DomainRule("h" + i + ".large.test", TrafficCoreContract.Action.NXDOMAIN,
                DomainRule.Scope.EXACT, null, 2, i + 1, false));
        input.add(new DomainRule("*.large.test", TrafficCoreContract.Action.PASS, DomainRule.Scope.WILDCARD, null, 3, 1, false));
        RuleSnapshotRepository.Snapshot snapshot = RuleSnapshotRepository.prepareRules(1, input);
        assertNotNull(snapshot);
        for (int i = 0; i < 1000; i++) {
            assertEquals(3, snapshot.resolve("h299999.large.test", 1, 1).rule.sourceId);
            assertEquals(TrafficCoreContract.Reason.UNCLASSIFIED, snapshot.decide("absent.other.test", 1, 1).reason);
        }
    }
}
