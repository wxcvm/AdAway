package org.adaway.ui.compose

import org.json.JSONObject

data class QueryLogCursor(val generation: String, val anchor: String)
data class QueryLogPage(
    val entries: List<QueryLogEntry>, val total: Int, val nextOffset: Int,
    val hasMore: Boolean, val cursor: QueryLogCursor,
) {
    companion object {
        fun parse(json: JSONObject?, offset: Int, previous: QueryLogCursor?): QueryLogPage {
            require(json != null && !json.has("error")) { "Query log unavailable or cursor expired" }
            val total = json.getInt("total")
            val next = json.getInt("nextOffset")
            val cursor = QueryLogCursor(json.getString("generation"), json.getString("anchor"))
            require(cursor.generation.matches(Regex("[0-9a-f]{32}")) && cursor.anchor.matches(Regex("[0-9]{1,20}")))
            require(previous == null || previous == cursor) { "Query log generation changed" }
            require(json.getInt("offset") == offset && total in 0..10000)
            val array = json.getJSONArray("entries")
            require(array.length() <= 1000 && json.getInt("returned") == array.length() &&
                next == offset + array.length() && next <= total)
            val entries = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                QueryLogEntry(row.getLong("ts"), row.optInt("uid", -1), row.getInt("action"),
                    row.optInt("type", -1), row.optInt("mode", 0), row.getString("host")).also {
                    // The persisted record reserves unsigned type/mode codes.
                    // Unknown legacy codes are displayable, never guessed as a policy.
                    require(it.ts >= 0 && it.uid >= -1 && it.action in 0..2 &&
                        it.type in -1..65534 && it.mode in 0..255)
                    require(it.host.isNotEmpty() && it.host.toByteArray(Charsets.UTF_8).size <= 191 &&
                        it.host.none { c -> c < ' ' || c in '\u007f'..'\u009f' })
                    var index = 0
                    while (index < it.host.length) {
                        val character = it.host[index++]
                        if (character.isHighSurrogate()) {
                            require(index < it.host.length && it.host[index++].isLowSurrogate())
                        } else require(!character.isLowSurrogate())
                    }
                }
            }
            val more = json.getBoolean("hasMore")
            require(more == (next < total) && (!more || entries.isNotEmpty()))
            return QueryLogPage(entries, total, next, more, cursor)
        }
    }
}
