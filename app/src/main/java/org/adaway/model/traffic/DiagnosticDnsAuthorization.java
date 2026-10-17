package org.adaway.model.traffic;

import java.security.MessageDigest;
import java.util.function.LongSupplier;

/** One outstanding, single-use permit, issued only by the app's explicit probe. */
public final class DiagnosticDnsAuthorization {
    public static final long TTL_MS = 2500;
    private final int ownerUid;
    private final LongSupplier clock;
    private Lease pending;
    private boolean closed;

    public DiagnosticDnsAuthorization(int ownerUid, LongSupplier clock) {
        this.ownerUid = ownerUid;
        this.clock = clock;
    }

    public final class Lease implements AutoCloseable {
        private final int port;
        private final byte[] query;
        private final long issued;
        private Lease(int port, byte[] query, long issued) {
            this.port = port;
            this.query = query.clone();
            this.issued = issued;
        }
        @Override public void close() {
            synchronized (DiagnosticDnsAuthorization.this) {
                if (pending == this) pending = null;
            }
        }
    }

    private boolean expired(long now) {
        return pending != null && (now < pending.issued || now - pending.issued >= TTL_MS);
    }

    public synchronized Lease issue(int uid, int sourcePort, byte[] query) {
        long now = clock.getAsLong();
        if (expired(now)) pending = null;
        if (closed || pending != null || uid != ownerUid || sourcePort < 1 || sourcePort > 65535
                || !DiagnosticDnsCodec.isAllowedQuery(query)) return null;
        return pending = new Lease(sourcePort, query, now);
    }

    /** UID is trustworthy only because VpnService admits exclusively this package. */
    public synchronized boolean consume(int uid, int sourcePort, byte[] query) {
        if (expired(clock.getAsLong())) pending = null;
        if (closed || pending == null || uid != ownerUid || sourcePort != pending.port
                || query == null || !MessageDigest.isEqual(query, pending.query)) return false;
        pending = null; // consume before any I/O: timeout and failure cannot enable a replay
        return true;
    }

    public synchronized void close() {
        closed = true;
        pending = null;
    }
}
