package org.adaway.model.traffic;

import static org.adaway.model.traffic.TrafficModePolicy.Preference.AUTO;
import static org.adaway.model.traffic.TrafficModePolicy.Preference.CAPTURE;
import static org.adaway.model.traffic.TrafficModePolicy.Preference.LISTEN_ONLY;
import static org.adaway.model.traffic.TrafficModePolicy.Reason.*;
import static org.adaway.model.traffic.TrafficModePolicy.Target.*;
import static org.adaway.model.traffic.TrafficModePolicy.VpnOwner.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM-only regression tests: the decision function has no Android dependencies. */
public class TrafficModePolicyTest {
    private static TrafficModePolicy.Evidence evidence(
            TrafficModePolicy.Preference preference,
            TrafficModePolicy.VpnOwner vpnOwner,
            boolean adguardInstalled,
            boolean adguardInterceptionConfirmed,
            boolean listenerPathVerified,
            boolean captureAuthorized,
            boolean captureFailureDetected) {
        return new TrafficModePolicy.Evidence(preference, vpnOwner, adguardInstalled,
                adguardInterceptionConfirmed, listenerPathVerified, captureAuthorized,
                captureFailureDetected);
    }

    @Test
    public void installedAdguardAloneMustNotTriggerCoexistence() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(AUTO, NONE, true, false, false, true, false));
        assertEquals(START_CAPTURE, decision.target);
        assertEquals(CAPTURE_READY, decision.reason);
    }

    @Test
    public void unknownVpnOwnershipMustFailClosed() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(AUTO, UNKNOWN, false, false, true, true, false));
        assertEquals(NO_CHANGE, decision.target);
        assertEquals(VPN_OWNERSHIP_UNKNOWN, decision.reason);
    }

    @Test
    public void externalVpnNeverGetsReplacedWithoutVerifiedCoexistence() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(CAPTURE, OTHER, false, false, true, true, false));
        assertEquals(NO_CHANGE, decision.target);
        assertEquals(OTHER_VPN_ACTIVE, decision.reason);
    }

    @Test
    public void verifiedAdguardAndListenerCanCoexist() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(AUTO, OTHER, true, true, true, true, false));
        assertEquals(START_COEXIST_LISTENER, decision.target);
        assertEquals(VERIFIED_ADGUARD_CONFLICT, decision.reason);
    }

    @Test
    public void adguardWithoutTrafficPathCannotBeDeclaredCompatible() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(AUTO, NONE, true, true, false, true, false));
        assertEquals(NO_CHANGE, decision.target);
        assertEquals(LISTENER_PATH_UNVERIFIED, decision.reason);
    }

    @Test
    public void listenerManualChoiceNeedsReachableTrafficPath() {
        TrafficModePolicy.Decision notReady =
                TrafficModePolicy.decide(evidence(LISTEN_ONLY, NONE, false, false, false, true, false));
        assertEquals(NO_CHANGE, notReady.target);
        assertEquals(LISTENER_PATH_UNVERIFIED, notReady.reason);

        TrafficModePolicy.Decision ready =
                TrafficModePolicy.decide(evidence(LISTEN_ONLY, NONE, false, false, true, false, false));
        assertEquals(START_COEXIST_LISTENER, ready.target);
        assertEquals(USER_REQUESTED_LISTENER, ready.reason);
    }

    @Test
    public void missingPermissionNeverTriggersCapture() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(CAPTURE, NONE, false, false, false, false, false));
        assertEquals(NO_CHANGE, decision.target);
        assertEquals(CAPTURE_AUTHORIZATION_NEEDED, decision.reason);
    }

    @Test
    public void healthyOwnedVpnCanStayInCaptureMode() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(AUTO, OURS, false, false, false, true, false));
        assertEquals(START_CAPTURE, decision.target);
    }

    @Test
    public void failedCaptureMustRestoreRegardlessOfUserPreference() {
        TrafficModePolicy.Decision decision =
                TrafficModePolicy.decide(evidence(LISTEN_ONLY, OTHER, true, true, true, true, true));
        assertEquals(RESTORE_NETWORK, decision.target);
        assertEquals(CAPTURE_FAILED, decision.reason);
    }
}
