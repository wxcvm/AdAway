package org.adaway.ui.compose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueryLogPageTest {
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
}
