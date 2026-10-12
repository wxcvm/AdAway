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
            for (Network network : networks) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null) {
                    missingCapabilities = true;
                    continue;
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnSeen = true;
                }
            }

            if (vpnSeen) {
                return ownTunnelConfirmed
                        ? TrafficModePolicy.VpnOwner.OURS
                        : TrafficModePolicy.VpnOwner.OTHER;
            }

            // A service-reported tunnel without an OS-visible VPN is not
            // enough to conclude that we actually own the network.
            if (ownTunnelConfirmed || missingCapabilities) {
                return TrafficModePolicy.VpnOwner.UNKNOWN;
            }
            return TrafficModePolicy.VpnOwner.NONE;
        } catch (SecurityException | IllegalStateException e) {
            return TrafficModePolicy.VpnOwner.UNKNOWN;
        }
    }
}
