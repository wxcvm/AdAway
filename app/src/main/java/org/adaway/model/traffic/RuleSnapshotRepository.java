package org.adaway.model.traffic;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable domain-rule publication contract, not wired to the production rule loaders. */
public final class RuleSnapshotRepository {
    public static final class Snapshot {
        public final long generation;
        public final Map<String, TrafficCoreContract.Action> rules;
        private Snapshot(long generation, Map<String, TrafficCoreContract.Action> rules) {
            this.generation = generation;
            this.rules = Collections.unmodifiableMap(new HashMap<>(rules));
        }
    }
    private final AtomicReference<Snapshot> current = new AtomicReference<>(
            new Snapshot(0, Collections.emptyMap()));
    public Snapshot read() { return current.get(); }

    public boolean publish(long generation, Map<String, TrafficCoreContract.Action> parsed) {
        if (generation < 1 || parsed == null || parsed.size() > 100000) return false;
        Map<String, TrafficCoreContract.Action> owned = new HashMap<>();
        for (Map.Entry<String, TrafficCoreContract.Action> entry : parsed.entrySet()) {
            String domain = canonicalDomain(entry.getKey());
            if (domain == null || entry.getValue() == null || owned.put(domain, entry.getValue()) != null)
                return false; // invalid update cannot erase or partly replace the last valid dataset
        }
        Snapshot next = new Snapshot(generation, owned);
        while (true) {
            Snapshot before = current.get();
            if (generation <= before.generation) return false;
            if (current.compareAndSet(before, next)) return true;
        }
    }

    private static String canonicalDomain(String raw) {
        if (raw == null || raw.length() > 254) return null;
        String domain = raw.toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (domain.isEmpty() || domain.length() > 253) return null;
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9_-]+")) return null;
        }
        return domain;
    }
}
