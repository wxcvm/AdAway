package org.adaway.model.traffic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.InetAddress;
import static org.junit.Assert.*;

/** Only the disposable CI emulator grants VPN consent; never runs on a user phone. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticTunSessionDeviceTest {
    private static void active(boolean expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (DiagnosticVpnService.isActive()!=expected && System.nanoTime()<deadline) Thread.sleep(25);
        assertEquals("Diagnostic service did not reach requested state",expected,DiagnosticVpnService.isActive());
    }
    private static boolean hasRoute(LinkProperties link,String address,int prefix) throws Exception {
        InetAddress target=InetAddress.getByName(address);
        return link.getRoutes().stream().anyMatch(route->route.getDestination().getAddress().equals(target)
                && route.getDestination().getPrefixLength()==prefix);
    }
    private static void assertScopedNetwork(ConnectivityManager manager) throws Exception {
        boolean found=false;
        for (Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if (caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                LinkProperties link=manager.getLinkProperties(network);assertNotNull(link);
                assertFalse("Default route introduced",link.getRoutes().stream().anyMatch(android.net.RouteInfo::isDefaultRoute));
                assertTrue(hasRoute(link,DiagnosticTunPlan.DIAGNOSTIC_TARGET,32));
                assertTrue(hasRoute(link,DiagnosticTunPlan.IPV6_TARGET,128));
                assertTrue(link.getDnsServers().isEmpty());found=true;
            }
        }
        assertTrue("Scoped VPN LinkProperties not found",found);
    }
    @Test public void realTunBothFamiliesAndStopRestartRestoreUnderlyingNetwork() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        Context app=ApplicationProvider.getApplicationContext();
        ConnectivityManager manager=app.getSystemService(ConnectivityManager.class);
        Network underlying=manager.getActiveNetwork();assertNotNull(underlying);
        NetworkCapabilities caps=manager.getNetworkCapabilities(underlying);assertNotNull(caps);
        assertFalse(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        LinkProperties before=manager.getLinkProperties(underlying);assertNotNull(before);
        assertNull("CI did not grant consent to this disposable emulator",VpnService.prepare(app));
        long previous=0;
        for (int cycle=0;cycle<3;cycle++) {
            try {
                DiagnosticVpnService.start(app);active(true);
                long generation=DiagnosticVpnService.getSessionId();assertTrue(generation>previous);previous=generation;
                assertScopedNetwork(manager);
                Thread.sleep(225);assertTrue("Actual IPv4 TUN relay failed",DiagnosticTunProbe.relayRoundTrip(false));
                Thread.sleep(225);assertTrue("Actual IPv6 TUN relay failed",DiagnosticTunProbe.relayRoundTrip(true));
                assertTrue(DiagnosticVpnService.getPacketCount()>=2);
            } finally { DiagnosticVpnService.stop(app);active(false); }
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!underlying.equals(manager.getActiveNetwork()) && System.nanoTime()<deadline) Thread.sleep(25);
            assertEquals(underlying,manager.getActiveNetwork());
            LinkProperties after=manager.getLinkProperties(underlying);assertNotNull(after);
            assertEquals(before.getRoutes(),after.getRoutes());assertEquals(before.getDnsServers(),after.getDnsServers());
            assertEquals(0,DiagnosticVpnService.getSessionId());
        }
    }
}
