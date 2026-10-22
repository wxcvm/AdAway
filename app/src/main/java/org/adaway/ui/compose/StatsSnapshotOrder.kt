package org.adaway.ui.compose

/** File fallback cannot roll a newer published observation back. Live responses
 * are ordered by the ViewModel's observation revision, not wall-clock time:
 * a corrected device clock or restarted daemon must not freeze the counters.
 * Zero means legacy/unknown time; it is never evidence of file freshness. */
internal fun shouldReplaceStats(currentTs: Long?, currentUpdatedAt: Long,
    incomingTs: Long, fromSnapshot: Boolean): Boolean {
    if (currentTs == null || !fromSnapshot) return true
    if (incomingTs <= 0 || incomingTs <= currentUpdatedAt / 1000) return false
    return currentTs <= 0 || incomingTs >= currentTs
}

/** A delayed push from before a newer HTTP read must not roll it back.
 * Nondecreasing daemon uptime distinguishes a clock correction from an old
 * message. A restarted daemon is confirmed by the next ordered HTTP read. */
internal fun shouldReplacePushedStats(currentTs: Long?, currentUptime: Long?,
    incomingTs: Long, incomingUptime: Long): Boolean =
    currentTs == null || currentUptime == null || currentTs <= 0 || incomingTs <= 0 ||
        incomingTs >= currentTs || incomingUptime >= currentUptime
