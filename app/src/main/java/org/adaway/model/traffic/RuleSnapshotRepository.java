package org.adaway.model.traffic;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
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
        private final TypedRuleIndex typedIndex;
        public final List<DomainRule> records;
        private Snapshot(long generation, List<DomainRule> records) {
            this(generation, Collections.emptyMap(), Collections.emptyMap(), records);
        }
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules) {
            this(generation, rules, Collections.emptyMap());
        }
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules, Map<String, String> replacements) {
            this(generation, rules, replacements, null);
        }
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules, Map<String, String> replacements,
                         List<DomainRule> records) {
            this.generation = generation;
            // The constructor is private; only maps already owned by this repository enter here.
            this.rules = Collections.unmodifiableMap(rules);
            this.replacements = Collections.unmodifiableMap(replacements);
            // Construct once per immutable generation, never on packet arrival.
            this.wildcardIndex = new WildcardRuleIndex(rules.keySet());
            this.records = records == null ? Collections.emptyList() : Collections.unmodifiableList(records);
            this.typedIndex = records == null ? null : new TypedRuleIndex(records);
        }
        /**
         * Retrieve the replacement chosen by the SAME exact-then-wildcard
         * precedence used in decide(). Exact non-replacement entries must
         * suppress any matching wildcard replacement.
         */
        public String replacementFor(String host) {
            if (typedIndex != null) {
                String canonical = canonicalDomain(host);
                DomainRule rule = canonical == null ? null : typedIndex.find(canonical);
                return rule != null && rule.action == TrafficCoreContract.Action.REPLACE ? rule.address : null;
            }
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
            if (typedIndex != null) return resolve(host, type, qclass).decision;
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

        public Resolution resolve(String host, int type, int qclass) {
            if (typedIndex == null) return new Resolution(decide(host, type, qclass), null,
                    replacementFor(host), generation);
            String canonical = canonicalDomain(host);
            DomainRule rule = canonical == null ? null : typedIndex.find(canonical);
            String address = rule == null ? null : rule.address;
            int family = address == null ? 0 : InetAddresses.forString(address).getAddress().length == 4 ? 4 : 6;
            TrafficCoreContract.Action action = rule == null ? null : rule.action;
            TrafficCoreContract.Decision decision = TrafficCoreContract.decide(new TrafficCoreContract.Input(
                    type, qclass, canonical != null, true,
                    rule != null && rule.user() && family == 0 ? action == TrafficCoreContract.Action.PASS ? 1 : -1 : 0,
                    action == TrafficCoreContract.Action.PASS, action != null && action != TrafficCoreContract.Action.PASS
                            && action != TrafficCoreContract.Action.REPLACE, family,
                    action == TrafficCoreContract.Action.REFUSED ? 1 : action == TrafficCoreContract.Action.ZERO_ADDRESS ? 2 : 0));
            if (rule != null && rule.user() && decision.action == TrafficCoreContract.Action.REPLACE)
                decision = new TrafficCoreContract.Decision(decision.action, TrafficCoreContract.Reason.USER_OVERRIDE);
            return new Resolution(decision, rule, decision.action == TrafficCoreContract.Action.REPLACE ? address : null, generation);
        }
    }
    public static final class Resolution {
        public final TrafficCoreContract.Decision decision;
        public final DomainRule rule;
        public final String address;
        public final long generation;
        private Resolution(TrafficCoreContract.Decision decision, DomainRule rule, String address, long generation) {
            this.decision = decision; this.rule = rule; this.address = address; this.generation = generation;
        }
    }
    private final AtomicReference<Snapshot> current = new AtomicReference<>(
            new Snapshot(0, Collections.emptyMap()));
    public Snapshot read() { return current.get(); }

    public static Snapshot prepareRules(long generation, List<DomainRule> records) {
        if (generation < 1 || records == null || records.size() > MAX_RULES) return null;
        List<DomainRule> owned = new ArrayList<>(records.size());
        for (DomainRule rule : records) {
            if (rule == null || rule.scope == null || rule.action == null || rule.sourceId < 1
                    || rule.sourceId > Integer.MAX_VALUE || rule.order < 1) return null;
            String domain = canonicalRule(rule.domain);
            if (domain == null || ((domain.contains("*") || domain.contains("?")) != (rule.scope == DomainRule.Scope.WILDCARD))) return null;
            if (rule.action == TrafficCoreContract.Action.REPLACE ? rule.address == null || !InetAddresses.isInetAddress(rule.address)
                    : rule.address != null) return null;
            owned.add(new DomainRule(domain, rule.action, rule.scope, rule.address, rule.sourceId, rule.order, rule.legacy));
        }
        return new Snapshot(generation, owned);
    }

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
