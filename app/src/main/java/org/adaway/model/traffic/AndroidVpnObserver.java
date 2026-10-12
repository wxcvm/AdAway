package org.adaway.model.traffic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Read-only Android network probe. This does not inspect AdGuard packages,
 * acquire a VPN slot, or modify the network.
 *
 * <p>Only pass {@code ownTunnelConfirmed = true} when the app's VPN service
 * has independently verified its own active TUN session. A stale persisted
 * preference is not sufficient proof of ownership.</p>
 */
public final class AndroidVpnObserver {
    private AndroidVpnObserver() {
    }

    public static TrafficModePolicy.VpnOwner read(Context context,
                                                    boolean ownTunnelConfirmed) {
        if (context == null) {
            return TrafficModePolicy.VpnOwner.UNKNOWN;
        }
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return TrafficModePolicy.VpnOwner.UNKNOWN;
            }
            Network[] networks = manager.getAllNetworks();
            if (networks == null || networks.length == 0) {
                return TrafficModePolicy.VpnOwner.UNKNOWN;
            }

            boolean vpnSeen = false;
            boolean missingCapabilities = false;
            boolean ourOwner = false;
            boolean foreignOwner = false;
            for (Network network : networks) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null) {
                    missingCapabilities = true;
                    continue;
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnSeen = true;
                    int owner = capabilities.getOwnerUid();
                    if (owner == android.os.Process.myUid()) ourOwner = true;
                    else if (owner >= 0) foreignOwner = true;
                    else missingCapabilities = true;
                }
            }

            boolean consentCurrent = !ownTunnelConfirmed || android.net.VpnService.prepare(context) == null;
            return VpnOwnershipEvidence.decide(vpnSeen, ownTunnelConfirmed,
                    ourOwner && consentCurrent, foreignOwner, missingCapabilities);
        } catch (SecurityException | IllegalStateException e) {
            return TrafficModePolicy.VpnOwner.UNKNOWN;
        }
    }
}
