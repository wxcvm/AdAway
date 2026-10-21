package org.adaway.ui.compose

import org.junit.Assert.*
import org.junit.Test

class StatsSnapshotOrderTest {
    @Test fun fallbackCannotReplaceNewerLiveObservation() {
        assertFalse(shouldReplaceStats(100, 100_500, 99, true))
        assertFalse(shouldReplaceStats(100, 100_500, 100, true))
        assertFalse(shouldReplaceStats(0, 100_500, 0, true))
        assertFalse(shouldReplaceStats(0, 100_500, 99, true))
        assertTrue(shouldReplaceStats(100, 100_500, 101, true))
    }
    @Test fun startupLegacyServersAndLiveRestartRemainSupported() {
        assertTrue(shouldReplaceStats(null, 0, 0, true))
        assertTrue(shouldReplaceStats(null, 0, 99, true))
        assertTrue(shouldReplaceStats(100, 100_500, 0, false))
        assertTrue(shouldReplaceStats(100, 100_500, 101, false))
        assertFalse(shouldReplaceStats(100, 100_500, 99, false))
    }
}
