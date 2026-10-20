package org.adaway.util;

import org.junit.Test;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class ListenerAddressTransactionTest {
    private static final class Fake implements ListenerAddressTransaction.Backend {
        final Set<String> addresses = new HashSet<>();
        String failAdd = "", failRemove = "";
        @Override public boolean exists(String address) { return addresses.contains(address); }
        @Override public void add(String address) throws IOException {
            if (address.equals(failAdd)) throw new IOException("permission denied");
            addresses.add(address);
        }
        @Override public void remove(String address) throws IOException {
            if (address.equals(failRemove)) throw new IOException("cleanup denied");
            addresses.remove(address);
        }
    }

    @Test public void preservesExistingAddressesAndRollsBackOnlyNewAliases() throws Exception {
        Fake backend = new Fake(); backend.addresses.add("10.7.8.9");
        ListenerAddressTransaction transaction = new ListenerAddressTransaction(backend);
        transaction.prepare(new ListenerSettings("10.7.8.9", "fd12:3456::2",80,443));
        assertEquals(java.util.List.of("fd12:3456::2"), transaction.added());
        assertEquals("", transaction.rollback());
        assertEquals(Set.of("10.7.8.9"), backend.addresses);
    }

    @Test public void secondFamilyFailureLeavesFirstAvailableForRollback() {
        Fake backend = new Fake(); backend.failAdd = "fd12:3456::2";
        ListenerAddressTransaction transaction = new ListenerAddressTransaction(backend);
        assertThrows(IOException.class, () -> transaction.prepare(new ListenerSettings("10.7.8.9", "fd12:3456::2",80,443)));
        assertEquals("", transaction.rollback());
        assertTrue(backend.addresses.isEmpty());
    }

    @Test public void cleanupFailureRetainsOwnershipForRetry() throws Exception {
        Fake backend = new Fake(); backend.failRemove = "fd12:3456::2";
        ListenerAddressTransaction transaction = new ListenerAddressTransaction(backend);
        transaction.prepare(new ListenerSettings("", "fd12:3456::2",80,443));
        assertTrue(transaction.rollback().contains("cleanup denied"));
        assertEquals(java.util.List.of("fd12:3456::2"), transaction.added());
    }

    @Test public void doesNotTurnAutomaticCreationIntoArbitraryNetworkConfiguration() {
        for (String value : new String[]{"8.8.8.8", "2001:db8::2", "::1", "::", "0.0.0.0", "fe80::2", "ff02::2", "::ffff:10.2.3.4", "fd12::2;id"})
            assertFalse(value, ListenerAddressTransaction.canCreate(value));
        for (String value : new String[]{"10.7.8.9", "172.16.0.2", "192.168.4.2", "127.0.0.2", "fd12:3456::2"})
            assertTrue(value, ListenerAddressTransaction.canCreate(value));
    }

    @Test public void alternateIpv6SpellingCannotRemoveTheActiveOwnedAlias() {
        assertTrue(ListenerAddressTransaction.isSelected(new ListenerSettings("", "FD12:0:0:0:0:0:0:2",80,443), "fd12::2"));
        assertFalse(ListenerAddressTransaction.isSelected(new ListenerSettings("", "fd12::3",80,443), "fd12::2"));
    }
}
