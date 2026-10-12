package org.adaway.model.traffic;

/**
 * Small, pure transition filter for scheduling a conflict recheck.
 *
 * <p>One callback reporting an active VPN is enough to schedule a verification
 * when our Root hijack mode was selected. Repeated callbacks should not spawn
 * repeated WorkManager jobs or root shells. The platform supplies monotonic
 * elapsed time so no wall-clock jumps affect cooldown.</p>
 */
public final class VpnConflictDebouncer {
    private static final long MIN_RECHECK_INTERVAL_MS = 30_000L;
    private boolean previouslyVpn;
    private long lastScheduledMs;
    private boolean everScheduled;

    public synchronized boolean onCapabilities(boolean vpnTransport,
                                                boolean hijackSelected,
                                                long elapsedMs) {
        if (!vpnTransport) {
            previouslyVpn = false;
            return false;
        }
        if (previouslyVpn) {
            return false;
        }
        previouslyVpn = true;
        if (!hijackSelected) {
            return false;
        }
        if (everScheduled && elapsedMs >= lastScheduledMs
                && elapsedMs - lastScheduledMs < MIN_RECHECK_INTERVAL_MS) {
            return false;
        }
        lastScheduledMs = elapsedMs;
        everScheduled = true;
        return true;
    }
}
