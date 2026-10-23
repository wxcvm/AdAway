package org.adaway.ui.compose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueryLogPageTest {
    @Test fun familyIsValidatedAndLegacyMissingFamilyStaysUnknown() {
        val json = page()
        val row = json.getJSONArray("entries").getJSONObject(0)
        assertEquals(0, QueryLogPage.parse(json, 300, null).entries.single().family)
        for (family in listOf(4, 6)) {
            row.put("family", family)
            assertEquals(family, QueryLogPage.parse(json, 300, null).entries.single().family)
        }
        row.put("family", 5)
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(json, 300, null) }
    }
    private fun page() = JSONObject("""{"offset":300,"total":600,"returned":1,"nextOffset":301,
        "hasMore":true,"generation":"0123456789abcdef0123456789abcdef","anchor":"600",
        "entries":[{"ts":100,"uid":-1,"action":1,"host":"blocked.example"}]}""")
    @Test fun shortPageUsesActualProgressRatherThanRequestedLimit() {
        val parsed = QueryLogPage.parse(page(), 300, null)
        assertEquals(301, parsed.nextOffset)
        assertTrue(parsed.hasMore)
        assertEquals("blocked.example", parsed.entries.single().host)
    }
    @Test fun serverErrorsAndGenerationChangesAreNotEmptyHistory() {
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(JSONObject("""{"error":"cursor_expired"}"""), 300, null) }
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(page(), 300, QueryLogCursor("f".repeat(32), "600")) }
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(page().put("nextOffset", 600), 300, null) }
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(page().put("returned", 0), 300, null) }
    }
    @Test fun invalidStatisticsCannotReplaceLastKnownSnapshotWithZeros() {
        assertNull(ServerStats.fromJson(JSONObject()))
        assertNull(ServerStats.fromJson(JSONObject("""{"error":"unavailable"}""")))
        assertNull(ServerStats.fromJson(JSONObject("""{"uptime_seconds":-1,"total_requests":0}""")))
        val parsed = ServerStats.fromJson(JSONObject("""{"snapshot_ts":123,"uptime_seconds":0,"total_requests":0}"""))
        assertEquals(123L, parsed!!.snapshotTs)
    }

    @Test fun statisticsUseAuthoritativeTotalIncludingFastFailAndOverrides() {
        val json = JSONObject("""{"uptime_seconds":20,"total_requests":100,
            "total_blocked":70,"blocked_images":10,"blocked_scripts":20}""")
        val parsed = ServerStats.fromJson(json)!!
        assertEquals(70L, parsed.totalBlocked)
        assertEquals(30L, parsed.categorizedBlocked)
        assertEquals(40L, parsed.uncategorizedBlocked)
        json.remove("total_blocked")
        assertEquals(30L, ServerStats.fromJson(json)!!.totalBlocked)
        assertNull(ServerStats.fromJson(json.put("total_blocked", -1)))
        assertNull(ServerStats.fromJson(json.put("total_blocked", 29)))
    }

    @Test fun measuredZeroRateDoesNotResurrectAnOldRequestStorm() {
        val json = JSONObject("""{"uptime_seconds":10,"total_requests":1000,
            "apps":[{"uid":42,"requests":1000,"tps":0}]}""")
        val idle = ServerStats.fromJson(json)!!.apps.single()
        assertEquals(0L, idle.ratePerSecond(10))
        assertFalse(idle.isStorming(10))
        json.getJSONArray("apps").getJSONObject(0).remove("tps")
        assertEquals(100L, ServerStats.fromJson(json)!!.apps.single().ratePerSecond(10))
    }

    @Test fun invalidActionsAndOversizedHostsDoNotBecomeProxySuccess() {
        val invalid = page()
        invalid.getJSONArray("entries").getJSONObject(0).put("action", 7)
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(invalid, 300, null) }
        invalid.getJSONArray("entries").getJSONObject(0).put("action", 1).put("host", "x".repeat(192))
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(invalid, 300, null) }
    }

    @Test fun legacyPolicyCodesStayInspectableButInvalidAttributionAndTextAreRejected() {
        val json = page()
        val row = json.getJSONArray("entries").getJSONObject(0)
        row.put("type", 65534).put("mode", 255)
        val legacy = QueryLogPage.parse(json, 300, null).entries.single()
        assertEquals(65534, legacy.type)
        assertEquals(255, legacy.mode)
        row.put("uid", -2)
        assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(json, 300, null) }
        row.put("uid", -1)
        for (host in listOf("", "a\u007fb", "a\u0085b", "a\ud800b")) {
            row.put("host", host)
            assertThrows(IllegalArgumentException::class.java) { QueryLogPage.parse(json, 300, null) }
        }
    }
}
