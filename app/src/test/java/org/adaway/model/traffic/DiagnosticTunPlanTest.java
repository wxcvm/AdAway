package org.adaway.model.traffic;

import static org.junit.Assert.*;
import org.junit.Test;

/** Regression guard: the diagnostic cannot accidentally become default VPN. */
public final class DiagnosticTunPlanTest {
    @Test public void diagnosticTargetUsesIsolatedBenchmarkAddress() {
        assertEquals("198.18.0.1", DiagnosticTunPlan.DIAGNOSTIC_TARGET);
        assertEquals(32, DiagnosticTunPlan.TARGET_PREFIX);
        assertTrue(DiagnosticTunPlan.isSafeRoute("198.18.0.1", 32));
    }

    @Test public void refuseDefaultOrBroadRoutes() {
        assertFalse(DiagnosticTunPlan.isSafeRoute("0.0.0.0", 0));
        assertFalse(DiagnosticTunPlan.isSafeRoute("::", 0));
        assertFalse(DiagnosticTunPlan.isSafeRoute("198.18.0.0", 15));
        assertFalse(DiagnosticTunPlan.isSafeRoute("198.18.0.1", 24));
    }

    @Test public void diagnosticAddressIsOnTheSameSmallSubnet() {
        assertEquals("198.18.0.2", DiagnosticTunPlan.LOCAL_ADDRESS);
        assertEquals(30, DiagnosticTunPlan.LOCAL_PREFIX);
        assertEquals(1500, DiagnosticTunPlan.MTU);
    }
}
