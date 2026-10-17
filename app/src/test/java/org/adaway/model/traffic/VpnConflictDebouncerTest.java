package org.adaway.model.traffic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnConflictDebouncerTest {
    @Test
    public void onlyTransitionIntoVpnSchedulesRecheck() {
        VpnConflictDebouncer signal = new VpnConflictDebouncer();
        assertTrue(signal.onCapabilities(true, true, 1000));
        assertFalse(signal.onCapabilities(true, true, 2000));
        assertFalse(signal.onCapabilities(true, true, 60000));
    }

    @Test
    public void vpnPresenceWithoutRootHijackSelectedIsNotEnough() {
        VpnConflictDebouncer signal = new VpnConflictDebouncer();
        assertFalse(signal.onCapabilities(true, false, 1000));
        assertFalse(signal.onCapabilities(true, true, 2000));
    }

    @Test
    public void shortReconnectDoesNotFloodWorkers() {
        VpnConflictDebouncer signal = new VpnConflictDebouncer();
        assertTrue(signal.onCapabilities(true, true, 1000));
        signal.onCapabilities(false, true, 2000);
        assertFalse(signal.onCapabilities(true, true, 2500));
    }

    @Test
    public void reconnectAfterCooldownSchedulesAgain() {
        VpnConflictDebouncer signal = new VpnConflictDebouncer();
        assertTrue(signal.onCapabilities(true, true, 1000));
        signal.onCapabilities(false, true, 1500);
        assertTrue(signal.onCapabilities(true, true, 31001));
    }

    @Test
    public void firstSignalWorksAtZeroElapsedTime() {
        assertTrue(new VpnConflictDebouncer().onCapabilities(true, true, 0));
    }
}
