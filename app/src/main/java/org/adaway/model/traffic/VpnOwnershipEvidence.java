package org.adaway.model.traffic;

/** A VPN transport bit alone never proves an owner. Unknown evidence fails closed. */
public final class VpnOwnershipEvidence {
    private VpnOwnershipEvidence() { }
    public static TrafficModePolicy.VpnOwner decide(boolean vpnSeen, boolean ownTunnel,
            boolean ownOwnerConfirmed, boolean foreignOwnerConfirmed, boolean incomplete) {
        if (foreignOwnerConfirmed) return TrafficModePolicy.VpnOwner.OTHER;
        if (incomplete) return TrafficModePolicy.VpnOwner.UNKNOWN;
        if (vpnSeen && ownTunnel && ownOwnerConfirmed) return TrafficModePolicy.VpnOwner.OURS;
        if (vpnSeen || ownTunnel) return TrafficModePolicy.VpnOwner.UNKNOWN;
        return TrafficModePolicy.VpnOwner.NONE;
    }
}
