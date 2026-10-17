package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.Test;

public class WildcardRuleIndexTest {
    private static boolean legacyMatch(String pattern, String host) {
        int p = 0, h = 0, star = -1, retry = 0;
        while (h < host.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?'
                    || pattern.charAt(p) == host.charAt(h))) { p++; h++; }
            else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; retry = h;
            } else if (star >= 0) {
                p = star + 1; h = ++retry;
            } else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static String legacyWinner(List<String> rules, String host) {
        String winner = null;
        for (String pattern : rules) {
            if (legacyMatch(pattern, host) &&
                    (winner == null || pattern.length() > winner.length()
                    || (pattern.length() == winner.length()
                        && pattern.compareTo(winner) < 0))) {
                winner = pattern;
            }
        }
        return winner;
    }

    @Test public void indexedResultsExactlyMatchLegacyGlobForSeededCorpus() {
        List<String> patterns = new ArrayList<>(List.of(
                "*", "??", "*.example.com", "a??.test", "foo*bar.test",
                "b*c*d.test", "*abc*", "host*one.test", "*x", "?*.test"));
        Random random = new Random(20261017L);
        String literal = "abcfoemxt.123";
        for (int i = 0; i < 2500; i++) {
            StringBuilder pattern = new StringBuilder();
            int length = 1 + random.nextInt(22);
            for (int j = 0; j < length; j++) {
                int kind = random.nextInt(10);
                pattern.append(kind == 0 ? '*' : kind == 1 ? '?'
                        : literal.charAt(random.nextInt(literal.length())));
            }
            patterns.add(pattern.toString());
        }
        WildcardRuleIndex index = new WildcardRuleIndex(patterns);
        for (int i = 0; i < 5000; i++) {
            StringBuilder host = new StringBuilder();
            int length = 1 + random.nextInt(28);
            for (int j = 0; j < length; j++)
                host.append(literal.charAt(random.nextInt(literal.length())));
            String domain = host.toString();
            assertEquals(domain, legacyWinner(patterns, domain), index.findBest(domain));
        }
    }

    @Test public void unrelatedDomainDoesNotScanHundredThousandWildcardRules() {
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < 100000; i++)
            patterns.add("host" + i + "*.example.test");
        WildcardRuleIndex index = new WildcardRuleIndex(patterns);
        assertNull(index.findBest("completely.different.invalid"));
        assertEquals(0, index.candidatesExamined("completely.different.invalid"));
        assertNotNull(index.findBest("host1712x.example.test"));
        // Without global rarity selection, a common *.example.test suffix
        // causes all 100000 patterns to be examined for one matching host.
        assertTrue(index.candidatesExamined("host1712x.example.test") < 1000);
    }

    @Test public void snapshotUsesWildcardIndexWithoutChangingExactPrecedence() {
        RuleSnapshotRepository repository = new RuleSnapshotRepository();
        assertTrue(repository.publish(1, Map.of(
                "*.example.test", TrafficCoreContract.Action.NXDOMAIN,
                "ads.example.test", TrafficCoreContract.Action.PASS)));
        TrafficCoreContract.Decision exact =
                repository.read().decide("ads.example.test", 1, 1);
        assertEquals(TrafficCoreContract.Action.PASS, exact.action);
        TrafficCoreContract.Decision wildcard =
                repository.read().decide("other.example.test", 1, 1);
        assertEquals(TrafficCoreContract.Action.NXDOMAIN, wildcard.action);
        assertEquals(TrafficCoreContract.Reason.BLOCK_RULE, wildcard.reason);
        assertEquals(TrafficCoreContract.Action.PASS,
                repository.read().decide("unmatched.invalid", 1, 1).action);
    }
}
