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
        // Pick the rarest REQUIRED literal anchor per pattern. Choosing
        // the longest run's first trigram would make common suffixes such as
        // *.example.test concentrate into a single enormous bucket.
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> wildcardPatterns = new ArrayList<>();
        for (String pattern : ruleKeys) {
            if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) continue;
            wildcardPatterns.add(pattern);
            for (String anchor : candidateAnchors(pattern))
                occurrences.merge(anchor, 1, Integer::sum);
        }
        for (String pattern : wildcardPatterns) {
            String selected = "";
            int count = Integer.MAX_VALUE;
            for (String anchor : candidateAnchors(pattern)) {
                int frequency = occurrences.get(anchor);
                if (frequency < count ||
                        (frequency == count && anchor.compareTo(selected) < 0)) {
                    count = frequency;
                    selected = anchor;
                }
            }
            if (selected.isEmpty()) noLiteralAnchor.add(pattern);
            else byAnchor.computeIfAbsent(selected, unused -> new ArrayList<>()).add(pattern);
        }
    }

    List<String> findMatches(String host) {
        List<String> matches = new ArrayList<>();
        for (String pattern : noLiteralAnchor) if (globMatches(pattern, host)) matches.add(pattern);
        Set<String> seen = new HashSet<>();
        for (int width = 1; width <= ANCHOR_WIDTH && width <= host.length(); width++) {
            for (int at = 0; at + width <= host.length(); at++) {
                String anchor = host.substring(at, at + width);
                if (!seen.add(anchor)) continue;
                List<String> bucket = byAnchor.get(anchor);
                if (bucket != null) for (String pattern : bucket)
                    if (globMatches(pattern, host)) matches.add(pattern);
            }
        }
        return matches;
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

    /**
     * Every required anchor at the strongest available literal width.
     * No candidate may cross '*' or '?' because matching text can expand.
     * HashSet avoids counting the same literal twice for one rule.
     */
    private static Set<String> candidateAnchors(String pattern) {
        for (int width = ANCHOR_WIDTH; width >= 1; width--) {
            Set<String> anchors = new HashSet<>();
            int start = 0;
            for (int at = 0; at <= pattern.length(); at++) {
                if (at == pattern.length() || pattern.charAt(at) == '*'
                        || pattern.charAt(at) == '?') {
                    for (int i = start; i + width <= at; i++)
                        anchors.add(pattern.substring(i, i + width));
                    start = at + 1;
                }
            }
            if (!anchors.isEmpty()) return anchors;
        }
        return new HashSet<>();
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
