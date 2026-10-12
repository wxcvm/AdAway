package org.adaway.model.traffic;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Consent boundary only: never requests consent, establishes TUN or changes routes. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticConsentBoundaryTest {
    @Test public void operatingSystemPermissionGuardsTheExportedVpnService() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ServiceInfo info = context.getPackageManager().getServiceInfo(
                new ComponentName(context, DiagnosticVpnService.class), android.content.pm.PackageManager.GET_META_DATA);
        assertEquals("android.permission.BIND_VPN_SERVICE", info.permission);
        assertFalse(info.metaData.getBoolean("android.net.VpnService.SUPPORTS_ALWAYS_ON", true));
    }
    @Test public void inactiveServiceCannotIssueAnInProcessDnsPermit() {
        assumeFalse("requires a fresh app with no user-started diagnostic", DiagnosticVpnService.isActive());
        assertNull(DiagnosticVpnService.authorizeDnsProbe(4000, DiagnosticDnsCodec.query(7)));
    }
}
