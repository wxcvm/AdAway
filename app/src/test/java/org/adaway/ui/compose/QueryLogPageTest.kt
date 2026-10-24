package org.adaway.ui.compose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueryLogPageTest {
    @Test fun localStatusAndPathDistinguishRejectedDocumentFromSameHostImage() {
        val json = page()
        val row = json.getJSONArray("entries").getJSONObject(0)
        val legacy = QueryLogPage.parse(json, 300, null).entries.single()
        assertEquals("", legacy.path)
        assertEquals(0, legacy.httpStatus)
        assertFalse(legacy.pathTruncated)
        row.put("action", 3).put("path", "/internal-test").put("status", 403).put("pathTruncated", false)
        val rejected = QueryLogPage.parse(json, 300, null).entries.single()
        assertEquals(3, rejected.action)
        assertEquals("/internal-test", rejected.path)
        assertEquals(403, rejected.httpStatus)
        row.put("action", 1).put("path", "/favicon.ico").put("status", 200)
        val image = QueryLogPage.parse(json, 300, null).entries.single()
        assertEquals(rejected.host, image.host)
        assertNotEquals(rejected.path, image.path)
        assertNotEquals(rejected.httpStatus, image.httpStatus)
    }

    @Test fun malformedOrSensitiveResponseMetadataCannotReplaceHistory() {
        val invalid = listOf(
            "status" to -1, "status" to 600, "status" to "200", "status" to 200.5,
            "path" to "https://user:password@example.test/", "path" to "/?token=secret",
            "path" to "/#secret", "path" to "/a\nb", "path" to ("/" + "a".repeat(159)),
            "path" to "/a\ud800", "pathTruncated" to "false",
        )
        for ((field, value) in invalid) {
            val json = page()
            json.getJSONArray("entries").getJSONObject(0).put(field, value)
            assertThrows("$field=$value", IllegalArgumentException::class.java) { QueryLogPage.parse(json, 300, null) }
        }
    }

    @Test fun optionalBrokenLogMetadataDoesNotDiscardAuthoritativeStatistics() {
        val json = JSONObject("""{"uptime_seconds":20,"total_requests":100,"total_blocked":40,
            "query_log":[{"ts":100,"uid":-1,"host":"example.test","action":1,"status":"invalid"}]}""")
        val stats = ServerStats.fromJson(json)!!
        assertEquals(100L, stats.totalRequests)
        assertEquals(40L, stats.totalBlocked)
        assertTrue(stats.queryLog.isEmpty())
    }

    @Test fun csvRetainsRejectionStatusAndEscapesTheRecordedPath() {
        val row = queryLogCsvRow(QueryLogEntry(action = 3, host = "local.example", family = 6,
            path = "/item,\"x\"", httpStatus = 403), java.text.SimpleDateFormat("yyyy-MM-dd"))
        assertTrue(row.contains(",access_denied,"))
        assertTrue(row.endsWith(",IPv6,\"/item,\"\"x\"\"\",403,false\n"))
        assertFalse(row.contains("forwarding_started"))
        assertFalse("A rejected endpoint has no placeholder policy", row.contains("placeholder"))
    }

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
