package org.adaway.ui.compose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PortableUiBackupTest {
    @Test fun legacyBackupRestoresOnlyPortableFields() {
        val values = PortableUiBackup.decode(JSONObject("""{
            "compose_general":{"theme_mode":2,"chart_rate":false,"realtime_enabled":true},
            "compose_app_monitor":{"allow_10001":true},
            "compose_webserver":{"http_port":8080}
        }"""))
        assertEquals(mapOf("theme_mode" to 2, "chart_rate" to false), values)
    }

    @Test fun invalidTypesAndRangesAreRejected() {
        for (value in listOf("3", "-1", "1.5", "true", "\"1\"")) {
            try {
                PortableUiBackup.decode(JSONObject("""{"compose_general":{"theme_mode":$value}}"""))
                fail("Invalid setting accepted: $value")
            } catch (_: IOException) {}
        }
    }

    @Test fun ruleBackupIsNotTreatedAsUiSettings() {
        try {
            PortableUiBackup.decode(JSONObject("""{"sources":[],"blocked":[],"allowed":[],"redirected":[]}"""))
            fail("Wrong backup type accepted")
        } catch (_: IOException) {}
    }
}
