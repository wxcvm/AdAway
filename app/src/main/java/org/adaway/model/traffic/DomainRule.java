package org.adaway.model.traffic;

/** Immutable persisted rule identity; source IDs are local numbers, never subscription URLs. */
public final class DomainRule {
    public enum Scope { EXACT, SUFFIX, WILDCARD }
    public final String domain, address;
    public final TrafficCoreContract.Action action;
    public final Scope scope;
    public final long sourceId, order;
    public final boolean legacy;

    public DomainRule(String domain, TrafficCoreContract.Action action, Scope scope,
                      String address, long sourceId, long order, boolean legacy) {
        this.domain = domain; this.action = action; this.scope = scope; this.address = address;
        this.sourceId = sourceId; this.order = order; this.legacy = legacy;
    }
    public boolean user() { return sourceId == 1; }

    /** Positive means a wins: user, action, scope, specificity, source, order, lexical. */
    static int compare(DomainRule a, DomainRule b) {
        if (b == null) return 1;
        if (a.user() != b.user()) return a.user() ? 1 : -1;
        if (!a.user() && rank(a.action) != rank(b.action)) return rank(a.action) - rank(b.action);
        if (a.scope != b.scope) return b.scope.ordinal() - a.scope.ordinal();
        if (a.domain.length() != b.domain.length()) return a.domain.length() - b.domain.length();
        if (a.sourceId != b.sourceId) return Long.compare(b.sourceId, a.sourceId);
        if (a.order != b.order) return a.user() ? Long.compare(a.order, b.order) : Long.compare(b.order, a.order);
        int name = b.domain.compareTo(a.domain);
        if (name != 0) return name;
        // Total ordering for defensive duplicate callers, independent of insertion/hash order.
        if (a.action != b.action) return rank(a.action) - rank(b.action);
        if (a.address == null || b.address == null) return 0;
        byte[] aa = com.google.common.net.InetAddresses.forString(a.address).getAddress();
        byte[] bb = com.google.common.net.InetAddresses.forString(b.address).getAddress();
        if (aa.length != bb.length) return bb.length - aa.length;
        for (int i = 0; i < aa.length; i++) {
            int cmp = (bb[i] & 255) - (aa[i] & 255);
            if (cmp != 0) return cmp;
        }
        return 0;
    }
    private static int rank(TrafficCoreContract.Action a) {
        return a == TrafficCoreContract.Action.PASS ? 3 : a == TrafficCoreContract.Action.REPLACE ? 2 : 1;
    }
}
