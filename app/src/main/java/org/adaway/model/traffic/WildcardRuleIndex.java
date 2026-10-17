package org.adaway.model.traffic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, conservative prefilter for wildcard domain rules.
 *
 * Each wildcard is stored in exactly one bucket selected from a literal
 * substring it MUST contain in order to match. This lets queries with no
 * plausible wildcard anchors skip the entire rule set. Patterns with no
 * literal bytes (for example '*' and '??') are always tested. The existing
 * glob matcher and longest-pattern / lexical tie ordering remain unchanged.
 *
 * This is an index, not a replacement for the domain parser. Input rule keys
 * are expected to be canonicalized by RuleSnapshotRepository first.
 */
final class WildcardRuleIndex {
    private static final int ANCHOR_WIDTH = 3;
    private final Map<String, List<String>> byAnchor = new HashMap<>();
    private final List<String> noLiteralAnchor = new ArrayList<>();

    WildcardRuleIndex(Iterable<String> ruleKeys) {
        for (String pattern : ruleKeys) {
            if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) continue;
            String anchor = requiredAnchor(pattern);
            if (anchor.isEmpty()) {
                noLiteralAnchor.add(pattern);
            } else {
                byAnchor.computeIfAbsent(anchor, unused -> new ArrayList<>()).add(pattern);
            }
        }
    }

    /** Returns the same winner as a full wildcard scan, or null. */
    String findBest(String host) {
        return search(host).winner;
    }

    /** Package-private diagnostic for deterministic performance tests. */
    int candidatesExamined(String host) {
        return search(host).examined;
    }

    private SearchResult search(String host) {
        if (host == null || host.isEmpty()) return new SearchResult(null, 0);
        String best = null;
        int examined = 0;
        for (String pattern : noLiteralAnchor) {
            examined++;
            if (globMatches(pattern, host) && better(pattern, best)) best = pattern;
        }
        // Enumerate host substrings of the only three index widths. Once a
        // matching bucket is visited, it is never visited a second time.
        Set<String> seenAnchors = new HashSet<>();
        for (int width = 1; width <= ANCHOR_WIDTH && width <= host.length(); width++) {
            for (int start = 0; start + width <= host.length(); start++) {
                String anchor = host.substring(start, start + width);
                if (!seenAnchors.add(anchor)) continue;
                List<String> candidateRules = byAnchor.get(anchor);
                if (candidateRules == null) continue;
                for (String pattern : candidateRules) {
                    examined++;
                    if (globMatches(pattern, host) && better(pattern, best)) best = pattern;
                }
            }
        }
        return new SearchResult(best, examined);
    }

    private static final class SearchResult {
        final String winner;
        final int examined;
        SearchResult(String winner, int examined) {
            this.winner = winner;
            this.examined = examined;
        }
    }

    private static boolean better(String candidate, String best) {
        return best == null || candidate.length() > best.length()
                || (candidate.length() == best.length() && candidate.compareTo(best) < 0);
    }

    /** Longest uninterrupted literal, capped at three characters. */
    private static String requiredAnchor(String pattern) {
        int bestStart = 0, bestLength = 0, start = 0;
        for (int i = 0; i <= pattern.length(); i++) {
            if (i == pattern.length() || pattern.charAt(i) == '*'
                    || pattern.charAt(i) == '?') {
                int length = i - start;
                if (length > bestLength) {
                    bestStart = start;
                    bestLength = length;
                }
                start = i + 1;
            }
        }
        return pattern.substring(bestStart, bestStart + Math.min(bestLength, ANCHOR_WIDTH));
    }

    /** The prior RuleSnapshotRepository wildcard matching semantics. */
    private static boolean globMatches(String pattern, String host) {
        int p = 0, h = 0, star = -1, retry = 0;
        while (h < host.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?'
                    || pattern.charAt(p) == host.charAt(h))) {
                p++; h++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; retry = h;
            } else if (star >= 0) {
                p = star + 1;
                h = ++retry;
            } else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
