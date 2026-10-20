package org.adaway.model.traffic;

import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/** A TUN descriptor can be established before ConnectivityService publishes its routes. */
final class DiagnosticTunReadiness {
    private DiagnosticTunReadiness() { }
    private static boolean scoped(LinkProperties link) {
        if (link == null) return false;
        IpPrefix four = new IpPrefix(DiagnosticTunPlan.DIAGNOSTIC_TARGET + "/32");
        IpPrefix six = new IpPrefix(DiagnosticTunPlan.IPV6_TARGET + "/128");
        boolean hasFour = false, hasSix = false;
        for (android.net.RouteInfo route : link.getRoutes()) {
            if (route.isDefaultRoute()) return false;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && route.getType() != android.net.RouteInfo.RTN_UNICAST) continue;
            hasFour |= four.equals(route.getDestination());
            hasSix |= six.equals(route.getDestination());
        }
        return hasFour && hasSix;
    }
    static boolean ready(ConnectivityManager manager) {
        if (manager == null) return false;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && scoped(manager.getLinkProperties(network))) return true;
            }
        } catch (RuntimeException unavailable) { return false; }
        return false;
    }
}
