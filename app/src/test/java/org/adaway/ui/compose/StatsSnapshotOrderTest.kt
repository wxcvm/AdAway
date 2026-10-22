package org.adaway.ui.compose

import org.junit.Assert.*
import org.junit.Test

class StatsSnapshotOrderTest {
    @Test fun delayedPushCannotUndoNewerHttpButClockCorrectionStillWorks() {
        assertFalse(shouldReplacePushedStats(110, 20, 100, 10))
        assertTrue(shouldReplacePushedStats(110, 20, 100, 21))
        assertTrue(shouldReplacePushedStats(110, 20, 100, 20))
        assertTrue(shouldReplacePushedStats(null, null, 100, 1))
        assertTrue(shouldReplacePushedStats(100, 20, 110, 1))
    }
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
        assertTrue(shouldReplaceStats(100, 100_500, 99, false))
    }
    @Test fun liveObservationRecoversImmediatelyAfterClockCorrectionOrRestart() {
        // A phone can correct its clock after boot. Fresh HTTP/WS observations
        // must not wait hours or days for that earlier wall clock to catch up.
        assertTrue(shouldReplaceStats(1_800_000_000, 1_800_000_000_500, 1_700_000_000, false))
        assertTrue(shouldReplaceStats(1_800_000_000, 1_800_000_000_500, 0, false))
        // An on-disk fallback is not evidence that a server restarted.
        assertFalse(shouldReplaceStats(1_800_000_000, 1_800_000_000_500, 1_700_000_000, true))
    }
}
