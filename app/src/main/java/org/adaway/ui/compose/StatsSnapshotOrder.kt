package org.adaway.ui.compose

/** File fallback cannot roll a newer published observation back. Zero means
 * legacy/unknown time; it is usable on startup, never evidence of freshness. */
internal fun shouldReplaceStats(currentTs: Long?, currentUpdatedAt: Long,
    incomingTs: Long, fromSnapshot: Boolean): Boolean {
    if (currentTs == null) return true
    if (fromSnapshot && (incomingTs <= 0 || incomingTs <= currentUpdatedAt / 1000)) return false
    return currentTs <= 0 || incomingTs <= 0 || incomingTs >= currentTs
}
