package org.adaway.ui.compose

import org.junit.Assert.*
import org.junit.Test

class AppRateTest {
    @Test fun currentMeasuredWindowWinsOverLargeHistoricalAverage() {
        val app = AppStat(uid = 42, requests = 100_000, tps = 2, hasMeasuredRate = true)
        assertEquals(2L, app.ratePerSecond(1))
        assertFalse(app.isStorming(1))
    }

    @Test fun zeroWindowAfterResetIsNotReplacedWithLifetimeAverage() {
        val app = AppStat(uid = 42, requests = 100_000, tps = 0, hasMeasuredRate = true)
        assertEquals(0L, app.ratePerSecond(1))
        assertFalse(app.isStorming(1))
        assertEquals(0L, AppStat(uid = 42, hasMeasuredRate = true).ratePerSecond(0))
    }

    @Test fun legacyFallbackRequiresKnownPositiveUptime() {
        val app = AppStat(uid = 42, requests = 100)
        assertEquals(5L, app.ratePerSecond(20))
        assertEquals(0L, app.ratePerSecond(0))
    }
}
