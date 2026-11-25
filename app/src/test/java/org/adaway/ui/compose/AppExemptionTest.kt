package org.adaway.ui.compose

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppExemptionTest {
    @Test fun successfulWritePreservesDefaultsAndThenUpdatesPreference() {
        val events = mutableListOf<String>()
        assertTrue(publishAppExemption(setOf(10001), setOf(10002), 10003, true,
            { events.add(it) }, { events.add("saved") }))
        assertEquals(listOf("10001\n10002\n10003", "saved"), events)
    }

    @Test fun failedWriteDoesNotUpdatePreference() {
        var saved = false
        assertFalse(publishAppExemption(setOf(10001), setOf(10002), 10001, false,
            { throw IOException("write failed") }, { saved = true }))
        assertFalse(saved)
    }

    @Test fun invalidAndDefaultUidsAreNotEditable() {
        for (uid in listOf(-1, 0, 10002)) {
            assertFalse(publishAppExemption(emptySet(), setOf(10002), uid, false,
                { fail("Unexpected file write") }, { fail("Unexpected preference change") }))
        }
    }

    @Test fun removalKeepsOtherEntries() {
        assertTrue(publishAppExemption(setOf(10001, 10003), setOf(10002), 10001, false,
            { assertEquals("10002\n10003", it) }, {}))
    }
}
