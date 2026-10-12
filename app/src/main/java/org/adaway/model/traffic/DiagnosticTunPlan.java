package org.adaway.model.traffic;

/**
 * Deliberately scoped TUN diagnostic configuration.
 *
 * This is NOT a general VPN backend. Its route must never be widened to
 * 0.0.0.0/0 or ::/0 until TCP/UDP forwarding, DNS and rollback are tested.
 * 198.18.0.0/15 is reserved for benchmark/interconnect testing (RFC 2544).
 */
public final class DiagnosticTunPlan {
    public static final String LOCAL_ADDRESS = "198.18.0.2";
    public static final int LOCAL_PREFIX = 30;
    public static final String DIAGNOSTIC_TARGET = "198.18.0.1";
    public static final int TARGET_PREFIX = 32;
    public static final int MTU = 1500;

    private DiagnosticTunPlan() {}

    public static boolean isSafeRoute(String address, int prefix) {
        return DIAGNOSTIC_TARGET.equals(address) && prefix == TARGET_PREFIX;
    }
}
