package org.adaway.ui.compose

/** Parsing failure is not evidence that the current daemon has an empty ranking. */
enum class TopHostsRead { VALID, MISSING, MALFORMED }

data class TopHostsObservation(
    val rows: List<BlockedHost>? = null,
    val observedAt: Long = 0,
    val checking: Boolean = true,
    val stale: Boolean = false,
    val problem: TopHostsRead? = null,
) {
    fun accept(rows: List<BlockedHost>, read: TopHostsRead, at: Long, historical: Boolean): TopHostsObservation =
        if (read == TopHostsRead.VALID) TopHostsObservation(rows.toList(), at, false, historical)
        else copy(checking = false, stale = true, problem = read)

    fun unavailable(): TopHostsObservation = copy(checking = false, stale = true)
}

/** Counts must be exact non-negative signed longs, never saturated floating-point conversions. */
internal fun parseRankingCount(value: Any?): Long? {
    if (value !is Number) return null
    return try {
        java.math.BigDecimal(value.toString()).longValueExact().takeIf { it >= 0 }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
