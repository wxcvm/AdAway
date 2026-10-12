package org.adaway.model.traffic;

import java.util.Objects;

/**
 * Pure decision logic for the proposed traffic capture modes.
 *
 * <p>This class does not enable a VPN, alter routes, modify a third-party app,
 * or claim that any traffic is already being intercepted. Callers must
 * independently verify every requested transition before applying it.</p>
 */
public final class TrafficModePolicy {
    private TrafficModePolicy() {
    }

    public enum Preference {
        AUTO,
        CAPTURE,
        LISTEN_ONLY
    }

    /** An observed VPN owner, not an installed application. */
    public enum VpnOwner {
        NONE,
        OURS,
        OTHER,
        UNKNOWN
    }

    /** A recommendation, not proof of an active operating mode. */
    public enum Target {
        START_CAPTURE,
        START_COEXIST_LISTENER,
        NO_CHANGE,
        RESTORE_NETWORK
    }

    public enum Reason {
        CAPTURE_READY,
        CAPTURE_AUTHORIZATION_NEEDED,
        USER_REQUESTED_LISTENER,
        VERIFIED_ADGUARD_CONFLICT,
        OTHER_VPN_ACTIVE,
        LISTENER_PATH_UNVERIFIED,
        VPN_OWNERSHIP_UNKNOWN,
        CAPTURE_FAILED
    }

    /** Immutable inputs provided by platform-specific probes and user settings. */
    public static final class Evidence {
        public final Preference preference;
        public final VpnOwner vpnOwner;
        public final boolean adguardInstalled;
        public final boolean adguardInterceptionConfirmed;
        public final boolean listenerPathVerified;
        public final boolean captureAuthorized;
        public final boolean captureFailureDetected;

        public Evidence(Preference preference,
                        VpnOwner vpnOwner,
                        boolean adguardInstalled,
                        boolean adguardInterceptionConfirmed,
                        boolean listenerPathVerified,
                        boolean captureAuthorized,
                        boolean captureFailureDetected) {
            this.preference = Objects.requireNonNull(preference, "preference");
            this.vpnOwner = Objects.requireNonNull(vpnOwner, "vpnOwner");
            this.adguardInstalled = adguardInstalled;
            this.adguardInterceptionConfirmed = adguardInterceptionConfirmed;
            this.listenerPathVerified = listenerPathVerified;
            this.captureAuthorized = captureAuthorized;
            this.captureFailureDetected = captureFailureDetected;
        }
    }

    public static final class Decision {
        public final Target target;
        public final Reason reason;

        private Decision(Target target, Reason reason) {
            this.target = target;
            this.reason = reason;
        }
    }

    public static Decision decide(Evidence e) {
        Objects.requireNonNull(e, "evidence");

        // A failed system capture takes precedence even over a manual listener
        // preference: undo our own changes before attempting another mode.
        if (e.captureFailureDetected) {
            return new Decision(Target.RESTORE_NETWORK, Reason.CAPTURE_FAILED);
        }
        if (e.vpnOwner == VpnOwner.UNKNOWN) {
            return new Decision(Target.NO_CHANGE, Reason.VPN_OWNERSHIP_UNKNOWN);
        }

        if (e.preference == Preference.LISTEN_ONLY) {
            return e.listenerPathVerified
                    ? new Decision(Target.START_COEXIST_LISTENER, Reason.USER_REQUESTED_LISTENER)
                    : new Decision(Target.NO_CHANGE, Reason.LISTENER_PATH_UNVERIFIED);
        }

        // A confirmed foreign VPN always wins the ownership contest. If it is
        // specifically an AdGuard capture with an independently verified
        // traffic path to us, offer coexistence; otherwise do not take over.
        if (e.vpnOwner == VpnOwner.OTHER) {
            if (e.adguardInterceptionConfirmed && e.listenerPathVerified) {
                return new Decision(Target.START_COEXIST_LISTENER,
                        Reason.VERIFIED_ADGUARD_CONFLICT);
            }
            return new Decision(Target.NO_CHANGE, Reason.OTHER_VPN_ACTIVE);
        }

        // Root/transparent filtering might conflict even without an Android
        // VPN. Installed-only is NEVER a sufficient signal.
        if (e.adguardInterceptionConfirmed) {
            return e.listenerPathVerified
                    ? new Decision(Target.START_COEXIST_LISTENER,
                            Reason.VERIFIED_ADGUARD_CONFLICT)
                    : new Decision(Target.NO_CHANGE, Reason.LISTENER_PATH_UNVERIFIED);
        }

        if (!e.captureAuthorized) {
            return new Decision(Target.NO_CHANGE, Reason.CAPTURE_AUTHORIZATION_NEEDED);
        }
        return new Decision(Target.START_CAPTURE, Reason.CAPTURE_READY);
    }
}
