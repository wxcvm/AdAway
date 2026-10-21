package org.adaway.util;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded monotonic readiness wait; a slow cold start must not be killed after one second. */
public final class ListenerStartupWait {
    private ListenerStartupWait() { }

    public static boolean await(BooleanSupplier ready, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!Thread.currentThread().isInterrupted()) {
            if (ready.getAsBoolean()) return true;
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            try { TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100))); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
