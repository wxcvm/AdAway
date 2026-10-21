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
    private static void unsupportedPacketPrecedesValidProbe() throws Exception {
        long before = DiagnosticVpnService.unsupportedPacketCount();
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            byte[] body = {1};
            socket.send(new java.net.DatagramPacket(body, body.length,
                    InetAddress.getByName(DiagnosticTunPlan.IPV6_TARGET), 53538));
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (DiagnosticVpnService.unsupportedPacketCount() == before && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue("Unsupported datagram did not reach TUN", DiagnosticVpnService.unsupportedPacketCount() > before);
    }
    private static void active(boolean expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (DiagnosticVpnService.isActive()!=expected && System.nanoTime()<deadline) Thread.sleep(25);
        assertEquals("Diagnostic service did not reach requested state",expected,DiagnosticVpnService.isActive());
    }
    private static void assertSystemVpnReleased(Context app) throws Exception {
        // Closing the descriptor precedes ConnectivityService's asynchronous network removal.
        // The underlying default network never changed for our host routes, so equality with
        // getActiveNetwork() alone cannot prove that stop completed.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (AndroidVpnObserver.read(app, false) != TrafficModePolicy.VpnOwner.NONE
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("Stopped VPN network still registered", TrafficModePolicy.VpnOwner.NONE,
                AndroidVpnObserver.read(app, false));
    }
    private static boolean hasRoute(LinkProperties link,String address,int prefix) throws Exception {
        InetAddress target=InetAddress.getByName(address);
        return link.getRoutes().stream().anyMatch(route->route.getDestination().getAddress().equals(target)
                && route.getDestination().getPrefixLength()==prefix);
    }
    private static void assertScopedNetwork(ConnectivityManager manager) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!DiagnosticTunReadiness.ready(manager) && System.nanoTime()<deadline) Thread.sleep(25);
        assertTrue("Scoped routes were not published",DiagnosticTunReadiness.ready(manager));
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
        for (int cycle=0;cycle<6;cycle++) {
            try {
                DiagnosticVpnService.start(app);active(true);
                long generation=DiagnosticVpnService.getSessionId();assertTrue(generation>previous);previous=generation;
                assertScopedNetwork(manager);
                assertEquals(generation,DiagnosticVpnService.getReadySessionId());
                Thread.sleep(225);unsupportedPacketPrecedesValidProbe();
                boolean ipv4=DiagnosticTunProbe.relayRoundTrip(false);
                assertTrue("IPv4 cycle="+cycle+" "+DiagnosticVpnService.diagnosticState(),ipv4);
                Thread.sleep(225);boolean ipv6=DiagnosticTunProbe.relayRoundTrip(true);
                assertTrue("IPv6 cycle="+cycle+" "+DiagnosticVpnService.diagnosticState(),ipv6);
                assertTrue(DiagnosticVpnService.getPacketCount()>=2);
                assertEquals("Both family responses must be counted",2,DiagnosticVpnService.getRelayCount());
                for(boolean family:new boolean[]{false,true}) {
                    DiagnosticTcpSession.Result tcp=DiagnosticVpnService.probeTcp(family);
                    assertEquals(tcp.toString(),DiagnosticTcpSession.Status.OK,tcp.status);
                    assertEquals(1,tcp.protectedSockets);assertEquals(6,tcp.opened);
                    assertTrue(tcp.cleanup);assertEquals(tcp.opened,tcp.closed);
                }
            } finally {
                DiagnosticVpnService.stop(app);active(false);
                assertSystemVpnReleased(app);
            }
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!underlying.equals(manager.getActiveNetwork()) && System.nanoTime()<deadline) Thread.sleep(25);
            assertEquals(underlying,manager.getActiveNetwork());
            LinkProperties after=manager.getLinkProperties(underlying);assertNotNull(after);
            assertEquals(before.getRoutes(),after.getRoutes());assertEquals(before.getDnsServers(),after.getDnsServers());
            assertEquals(0,DiagnosticVpnService.getSessionId());
        }
    }
}
