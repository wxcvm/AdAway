package org.adaway.model.traffic;

/** Proposed shared v1 contract. Pure decisions; no sockets, routes or DNS synthesis. */
public final class TrafficCoreContract {
    public static final int VERSION = 1;
    private TrafficCoreContract() { }
    public enum Action { PASS, NXDOMAIN, REFUSED, ZERO_ADDRESS, REPLACE }
    public enum Reason { UNCLASSIFIED, USER_OVERRIDE, ALLOW_EXCEPTION, REPLACEMENT_RULE, BLOCK_RULE, INVALID_DATA, UNSUPPORTED_CLASS }
    public enum ModeState { UNKNOWN, FOREIGN_VPN, COEXIST_PREPARING, COEXIST_VERIFIED, STANDALONE_ACTIVE, DEGRADED, RESTORING }
    public static final class Decision {
        public final Action action;
        public final Reason reason;
        public Decision(Action action, Reason reason) { this.action = action; this.reason = reason; }
    }
    public static final class Input {
        public final int type, qclass, override, replacementFamily, blockMode;
        public final boolean questionValid, rulesValid, allow, block;
        public Input(int type, int qclass, boolean questionValid, boolean rulesValid, int override,
                     boolean allow, boolean block, int replacementFamily, int blockMode) {
            this.type = type; this.qclass = qclass; this.questionValid = questionValid;
            this.rulesValid = rulesValid; this.override = override; this.allow = allow;
            this.block = block; this.replacementFamily = replacementFamily; this.blockMode = blockMode;
        }
    }
    public static Decision decide(Input input) {
        if (input == null || !input.questionValid || !input.rulesValid || input.type < 1
                || input.type > 65535 || input.override < -1 || input.override > 1
                || input.blockMode < 0 || input.blockMode > 2)
            return new Decision(Action.PASS, Reason.INVALID_DATA);
        if (input.qclass != 1) return new Decision(Action.PASS, Reason.UNSUPPORTED_CLASS);
        if (input.override == 1) return new Decision(Action.PASS, Reason.USER_OVERRIDE);
        if (input.override == -1) return blocked(input, Reason.USER_OVERRIDE);
        if (input.allow) return new Decision(Action.PASS, Reason.ALLOW_EXCEPTION);
        if (input.replacementFamily != 0) {
            int family = input.type == 1 ? 4 : input.type == 28 ? 6 : 0;
            return family != 0 && input.replacementFamily == family
                    ? new Decision(Action.REPLACE, Reason.REPLACEMENT_RULE)
                    : new Decision(Action.PASS, Reason.INVALID_DATA);
        }
        if (input.block) return blocked(input, Reason.BLOCK_RULE);
        return new Decision(Action.PASS, Reason.UNCLASSIFIED);
    }
    private static Decision blocked(Input input, Reason reason) {
        if (input.blockMode == 0) return new Decision(Action.NXDOMAIN, reason);
        if (input.blockMode == 1 || (input.type != 1 && input.type != 28))
            return new Decision(Action.REFUSED, reason);
        return new Decision(Action.ZERO_ADDRESS, reason);
    }
    /** Cache eligibility, not a cache: generations and full question scope remain part of the key. */
    public static boolean cacheable(int qclass, int rcode, boolean dnssec, boolean edns, boolean personalized) {
        return qclass == 1 && (rcode == 0 || rcode == 3) && !dnssec && !edns && !personalized;
    }
    public static final class FlowKey {
        private final byte[] source, destination;
        public final int family, protocol, sourcePort, destinationPort;
        public final long sessionGeneration;
        public FlowKey(int family, int protocol, byte[] source, byte[] destination,
                       int sourcePort, int destinationPort, long sessionGeneration) {
            int width = family == 4 ? 4 : family == 6 ? 16 : 0;
            if (width == 0 || source == null || destination == null || source.length != width
                    || destination.length != width || (protocol != 6 && protocol != 17)
                    || sourcePort < 1 || sourcePort > 65535 || destinationPort < 1 || destinationPort > 65535
                    || sessionGeneration < 1) throw new IllegalArgumentException("Invalid flow key");
            this.family = family; this.protocol = protocol; this.source = source.clone();
            this.destination = destination.clone(); this.sourcePort = sourcePort;
            this.destinationPort = destinationPort; this.sessionGeneration = sessionGeneration;
        }
        public byte[] source() { return source.clone(); }
        public byte[] destination() { return destination.clone(); }
    }
    public static final class ConnectionStats {
        public final long seen, blocked, forwardAttempted, forwardConfirmed, unclassified;
        public ConnectionStats(long seen, long blocked, long forwardAttempted, long forwardConfirmed, long unclassified) {
            if (seen < 0 || blocked < 0 || forwardAttempted < 0 || forwardConfirmed < 0 || unclassified < 0
                    || blocked > seen || forwardAttempted > seen || forwardConfirmed > forwardAttempted || unclassified > seen)
                throw new IllegalArgumentException("Inconsistent forwarding counters");
            this.seen = seen; this.blocked = blocked; this.forwardAttempted = forwardAttempted;
            this.forwardConfirmed = forwardConfirmed; this.unclassified = unclassified;
        }
    }
}
