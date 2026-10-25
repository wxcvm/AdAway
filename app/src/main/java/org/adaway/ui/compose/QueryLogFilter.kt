package org.adaway.ui.compose

import java.security.MessageDigest

/** Immutable server-side query. Null numeric fields mean all, not an unknown observation. */
data class QueryLogFilter(
    val action: Int? = null, val family: Int? = null, val type: Int? = null,
    val uid: Int? = null, val query: String = "",
) {
    val validationError: Boolean get() = query.toByteArray(Charsets.UTF_8).size > 191 ||
        query.any { it < ' ' || it in '\u007f'..'\u009f' } || !validSurrogates(query)

    fun requireValid() {
        require(!validationError) { "Query must contain valid text of at most 191 UTF-8 bytes" }
        require(action == null || action in 0..3)
        require(family == null || family == 0 || family == 4 || family == 6)
        require(type == null || type in -1..65534)
        require(uid == null || uid >= -1)
    }

    fun queryKey(): String {
        requireValid()
        val folded = query.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
        val canonical = "qlog-filter-v1\naction=${action ?: "*"}\nfamily=${family ?: "*"}\ntype=${type ?: "*"}\nuid=${uid ?: "*"}\nq=$folded"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }
}

internal class QueryLogUpgradeRequired : IllegalArgumentException("Server does not support full-history filters")
