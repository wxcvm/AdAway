package org.adaway.model.traffic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;

public class DiagnosticDnsAuthorizationTest {
    @Test public void aAndAaaaPermitsWithTheSameIdAreNotInterchangeable() {
        DiagnosticDnsAuthorization permits = new DiagnosticDnsAuthorization(1, () -> 0);
        byte[] a = DiagnosticDnsCodec.query(42), aaaa = DiagnosticDnsCodec.query(42, 28);
        try (DiagnosticDnsAuthorization.Lease lease = permits.issue(1, 4000, aaaa)) {
            assertNotNull(lease);
            assertFalse(permits.consume(1, 4000, a));
            assertTrue(permits.consume(1, 4000, aaaa));
            assertFalse(permits.consume(1, 4000, aaaa));
        }
        permits.close();
        assertNull(permits.issue(1, 4000, aaaa));
    }
    @Test public void boundsUidPortQuestionExpiryReplayAndCancellation() {
        AtomicLong now = new AtomicLong(100);
        DiagnosticDnsAuthorization permits = new DiagnosticDnsAuthorization(1000, now::get);
        byte[] query = DiagnosticDnsCodec.query(7);
        assertFalse(permits.consume(1000, 4000, query));
        assertNull(permits.issue(1001, 4000, query));
        DiagnosticDnsAuthorization.Lease lease = permits.issue(1000, 4000, query);
        assertNotNull(lease);
        assertNull(permits.issue(1000, 4001, query));
        assertFalse(permits.consume(1001, 4000, query));
        assertFalse(permits.consume(1000, 4001, query));
        assertFalse(permits.consume(1000, 4000, DiagnosticDnsCodec.query(8)));
        assertTrue(permits.consume(1000, 4000, query));
        assertFalse(permits.consume(1000, 4000, query));
        lease.close();
        lease = permits.issue(1000, 4000, query);
        now.addAndGet(DiagnosticDnsAuthorization.TTL_MS);
        assertFalse(permits.consume(1000, 4000, query));
        DiagnosticDnsAuthorization.Lease next = permits.issue(1000, 4000, query);
        lease.close(); // stale lease cannot revoke a new request
        assertTrue(permits.consume(1000, 4000, query));
        next = permits.issue(1000, 4000, query);
        next.close();
        assertFalse(permits.consume(1000, 4000, query));
        permits.close();
        assertNull(permits.issue(1000, 4000, query));
    }

    @Test public void ownsRequestBytesAndRevokesOnMonotonicClockRegression() {
        AtomicLong now = new AtomicLong(100);
        DiagnosticDnsAuthorization permits = new DiagnosticDnsAuthorization(1, now::get);
        byte[] query = DiagnosticDnsCodec.query(9);
        permits.issue(1, 4000, query);
        query[0] = 1;
        assertTrue(permits.consume(1, 4000, DiagnosticDnsCodec.query(9)));
        permits.issue(1, 4000, DiagnosticDnsCodec.query(9));
        now.set(99);
        assertFalse(permits.consume(1, 4000, DiagnosticDnsCodec.query(9)));
    }
}
