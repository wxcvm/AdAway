package org.adaway.model.traffic;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.google.common.net.InetAddresses;
import org.adaway.model.source.DomainSourceParser;

/** Immutable effective domain rules, published after a successful source/database sync. */
public final class RuleSnapshotRepository {
    public static final int MAX_RULES = 500000;
    public static final class Snapshot {
        public final long generation;
        public final Map<String, TrafficCoreContract.Action> rules;
        public final Map<String, String> replacements;
        private final WildcardRuleIndex wildcardIndex;
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules) {
            this(generation, rules, Collections.emptyMap());
        }
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules, Map<String, String> replacements) {
            this.generation = generation;
            // The constructor is private; only maps already owned by this repository enter here.
            this.rules = Collections.unmodifiableMap(rules);
            this.replacements = Collections.unmodifiableMap(replacements);
            // Construct once per immutable generation, never on packet arrival.
            this.wildcardIndex = new WildcardRuleIndex(rules.keySet());
        }
        /**
         * Retrieve the replacement chosen by the SAME exact-then-wildcard
         * precedence used in decide(). Exact non-replacement entries must
         * suppress any matching wildcard replacement.
         */
        public String replacementFor(String host) {
            String canonical = canonicalDomain(host);
            if (canonical == null) return null;
            String direct = replacements.get(canonical);
            if (direct != null || rules.containsKey(canonical)) return direct;
            String wildcard = wildcardIndex.findBest(canonical);
            return wildcard == null ? null : replacements.get(wildcard);
        }

        /** Pure DNS policy response; does not touch sockets, TUN or DNS settings. */
        public byte[] answerDns(byte[] query) {
            return DnsPolicyWire.respond(query, this);
        }

        public TrafficCoreContract.Decision decide(String host, int type, int qclass) {
            String canonical = canonicalDomain(host);
            TrafficCoreContract.Action action = canonical == null ? null : rules.get(canonical);
            String address = canonical == null ? null : replacements.get(canonical);
            // Existing explicit wildcard replacements are preserved; exact entries take precedence.
            if (canonical != null && action == null) {
                String chosen = wildcardIndex.findBest(canonical);
                if (chosen != null) {
                    action = rules.get(chosen);
                    address = replacements.get(chosen);
                }
            }
            int family = address == null ? 0 : InetAddresses.forString(address).getAddress().length == 4 ? 4 : 6;
            return TrafficCoreContract.decide(new TrafficCoreContract.Input(type, qclass, canonical != null,
                    true, 0, action == TrafficCoreContract.Action.PASS,
                    action == TrafficCoreContract.Action.NXDOMAIN || action == TrafficCoreContract.Action.REFUSED
                            || action == TrafficCoreContract.Action.ZERO_ADDRESS, family,
                    action == TrafficCoreContract.Action.REFUSED ? 1 : action == TrafficCoreContract.Action.ZERO_ADDRESS ? 2 : 0));
        }
    }
    private final AtomicReference<Snapshot> current = new AtomicReference<>(
            new Snapshot(0, Collections.emptyMap()));
    public Snapshot read() { return current.get(); }

    public boolean publish(long generation, Map<String, TrafficCoreContract.Action> parsed) {
        return publishEffective(generation, parsed, Collections.emptyMap());
    }
    public boolean publishEffective(long generation, Map<String, TrafficCoreContract.Action> parsed,
                                    Map<String, String> replacements) {
        Snapshot next = prepareEffective(generation, parsed, replacements);
        return next != null && publishPrepared(next);
    }
    public static Snapshot prepareEffective(long generation, Map<String, TrafficCoreContract.Action> parsed,
                                             Map<String, String> replacements) {
        if (generation < 1 || parsed == null || parsed.size() > MAX_RULES || replacements == null
                || replacements.size() > MAX_RULES) return null;
        Map<String, TrafficCoreContract.Action> owned = new HashMap<>();
        for (Map.Entry<String, TrafficCoreContract.Action> entry : parsed.entrySet()) {
            String domain = canonicalRule(entry.getKey());
            if (domain == null || entry.getValue() == null || owned.put(domain, entry.getValue()) != null)
                return null; // invalid update cannot erase or partly replace the last valid dataset
        }
        Map<String, String> addresses = new HashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String domain = canonicalRule(entry.getKey());
            if (domain == null || owned.get(domain) != TrafficCoreContract.Action.REPLACE
                    || entry.getValue() == null || !InetAddresses.isInetAddress(entry.getValue())
                    || addresses.put(domain, entry.getValue()) != null) return null;
        }
        for (Map.Entry<String, TrafficCoreContract.Action> entry : owned.entrySet()) {
            if (entry.getValue() == TrafficCoreContract.Action.REPLACE && !addresses.containsKey(entry.getKey())) return null;
        }
        return new Snapshot(generation, owned, addresses);
    }
    public boolean publishPrepared(Snapshot next) {
        if (next == null) return false;
        while (true) {
            Snapshot before = current.get();
            if (next.generation <= before.generation) return false;
            if (current.compareAndSet(before, next)) return true;
        }
    }

    private static String canonicalDomain(String raw) {
        return DomainSourceParser.canonicalDomain(raw);
    }
    private static String canonicalRule(String raw) {
        if (raw == null) return null;
        if (raw.indexOf('*') < 0 && raw.indexOf('?') < 0) return canonicalDomain(raw);
        String domain = raw.toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        return canonicalDomain(domain.replace('*', 'a').replace('?', 'a')) == null ? null : domain;
    }
}
