package org.adaway.ui.compose

import org.json.JSONObject

data class QueryLogCursor(val generation: String, val anchor: String, val queryKey: String = "")
data class QueryLogPage(
    val entries: List<QueryLogEntry>, val total: Int, val nextOffset: Int,
    val hasMore: Boolean, val cursor: QueryLogCursor,
    val retainedTotal: Int = total, val filterVersion: Int = 0,
) {
    companion object {
        fun parse(json: JSONObject?, offset: Int, previous: QueryLogCursor?, filter: QueryLogFilter? = null): QueryLogPage {
            require(json != null && !json.has("error")) { "Query log unavailable or cursor expired" }
            if (json.has("filterVersion")) require(json.get("filterVersion") is Int)
            val version = json.optInt("filterVersion", 0)
            if (filter != null && version != 1) throw QueryLogUpgradeRequired()
            val key = if (version == 1) json.getString("queryKey") else ""
            if (filter != null) require(key == filter.queryKey()) { "Query conditions changed" }
            if (version == 1) require(key.matches(Regex("[0-9a-f]{64}")))
            val total = integer(json, "total", 0, 10000).toInt()
            val retained = if (version == 1) integer(json, "retainedTotal", 0, 10000).toInt() else total
            require(retained in total..10000)
            val next = integer(json, "nextOffset", 0, 10000).toInt()
            val cursor = QueryLogCursor(json.getString("generation"), json.getString("anchor"), key)
            require(cursor.generation.matches(Regex("[0-9a-f]{32}")) &&
                (cursor.anchor == "0" || validSequence(cursor.anchor)))
            require(previous == null || previous == cursor) { "Query log generation changed" }
            require(integer(json, "offset", 0, 10000) == offset.toLong() && total in 0..10000)
            val array = json.getJSONArray("entries")
            require(array.length() <= 1000 && integer(json, "returned", 0, 1000) == array.length().toLong() &&
                next == offset + array.length() && next <= total)
            val entries = readEntries(array, version, cursor)
            val more = json.get("hasMore").also { require(it is Boolean) } as Boolean
            require(more == (next < total) && (!more || entries.isNotEmpty()))
            return QueryLogPage(entries, total, next, more, cursor, retained, version)
        }

        private fun readEntries(array: org.json.JSONArray, version: Int, cursor: QueryLogCursor): List<QueryLogEntry> {
            val entries = (0 until array.length()).map { index ->
                parseEntry(array.getJSONObject(index)).let { entry ->
                    if (version == 1) {
                        require(validSequence(entry.sequence) && compareSequence(entry.sequence, cursor.anchor) <= 0)
                        entry.copy(generation = cursor.generation)
                    } else entry
                }
            }
            if (version == 1) entries.zipWithNext().forEach { (a, b) ->
                require(compareSequence(a.sequence, b.sequence) > 0) { "Repeated or reordered observations" }
            }
            return entries
        }

        internal fun parseEntry(row: JSONObject): QueryLogEntry =
            QueryLogEntry(integer(row, "ts", 0, Long.MAX_VALUE),
                integer(row, "uid", -1, Int.MAX_VALUE.toLong(), -1).toInt(),
                integer(row, "action", 0, 3).toInt(),
                integer(row, "type", -1, 65534, -1).toInt(),
                integer(row, "mode", 0, 255, 0).toInt(), row.getString("host"),
                integer(row, "family", 0, 6, 0).toInt(),
                if (row.has("path")) row.get("path").also { require(it is String) } as String else "",
                responseStatus(row),
                pathTruncated(row),
                if (row.has("sequence")) row.get("sequence").also { require(it is String) } as String else "").also {
                // Unknown persisted policy codes remain inspectable, never guessed.
                require(it.ts >= 0 && it.uid >= -1 && it.action in 0..3 &&
                    it.type in -1..65534 && it.mode in 0..255)
                require(it.family == 0 || it.family == 4 || it.family == 6)
                require(it.host.isNotEmpty() && it.host.toByteArray(Charsets.UTF_8).size <= 191 &&
                    it.host.none { c -> c < ' ' || c in '\u007f'..'\u009f' })
                validateSurrogates(it.host)
                require(it.path.toByteArray(Charsets.UTF_8).size <= 159 &&
                    (it.path.isEmpty() || it.path.startsWith("/")) &&
                    it.path.none { c -> c < ' ' || c in '\u007f'..'\u009f' || c == '?' || c == '#' })
                require(it.path.isNotEmpty() || !it.pathTruncated)
                validateSurrogates(it.path)
                require(it.sequence.isEmpty() || validSequence(it.sequence))
            }

        internal fun validSequence(value: String): Boolean =
            value.matches(Regex("[1-9][0-9]{0,19}")) &&
                (value.length < 20 || value <= "18446744073709551615")

        internal fun compareSequence(a: String, b: String): Int =
            if (a.length == b.length) a.compareTo(b) else a.length.compareTo(b.length)

        private fun integer(row: JSONObject, key: String, minimum: Long, maximum: Long,
            fallback: Long? = null): Long {
            if (!row.has(key) && fallback != null) return fallback
            val raw = row.get(key)
            require(raw is Int || raw is Long) { "Invalid integer: $key" }
            return (raw as Number).toLong().also { require(it in minimum..maximum) }
        }

        private fun responseStatus(row: JSONObject): Int {
            if (!row.has("status")) return 0
            val status = row.get("status")
            require(status is Int || status is Long)
            val value = (status as Number).toLong()
            require(value == 0L || value in 100L..599L)
            return value.toInt()
        }

        private fun pathTruncated(row: JSONObject): Boolean = when (val value = row.opt("pathTruncated")) {
            null -> false
            is Boolean -> value
            else -> throw IllegalArgumentException("Invalid path truncation metadata")
        }

        private fun validateSurrogates(host: String) {
            var index = 0
            while (index < host.length) {
                val character = host[index++]
                if (character.isHighSurrogate()) {
                    require(index < host.length && host[index++].isLowSurrogate())
                } else require(!character.isLowSurrogate())
            }
        }
    }
}
