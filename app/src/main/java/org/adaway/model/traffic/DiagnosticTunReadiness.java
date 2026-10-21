package org.adaway.model.traffic;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/** A TUN descriptor can be established before ConnectivityService publishes its routes. */
final class DiagnosticTunReadiness {
    private static final byte[] FOUR = {(byte)198, 18, 0, 1};
    private static final byte[] SIX = {0x20, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    private DiagnosticTunReadiness() { }
    private static boolean scoped(LinkProperties link) {
        if (link == null) return false;
        boolean hasFour = false;
        boolean hasSix = false;
        for (android.net.RouteInfo route : link.getRoutes()) {
            if (route.isDefaultRoute()) return false;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && route.getType() != android.net.RouteInfo.RTN_UNICAST) continue;
            android.net.IpPrefix prefix = route.getDestination();
            hasFour |= prefix.getPrefixLength() == 32 && java.util.Arrays.equals(FOUR, prefix.getRawAddress());
            hasSix |= prefix.getPrefixLength() == 128 && java.util.Arrays.equals(SIX, prefix.getRawAddress());
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
