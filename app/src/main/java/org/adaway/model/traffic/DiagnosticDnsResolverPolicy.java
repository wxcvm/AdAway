package org.adaway.model.traffic;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/** Selects only OS-supplied addresses; no lookup, persistence or network changes. */
public final class DiagnosticDnsResolverPolicy {
    private DiagnosticDnsResolverPolicy() { }

    public static InetAddress select(List<InetAddress> servers) {
        if (servers == null) return null;
        InetAddress ipv6 = null;
        for (InetAddress server : servers) {
            if (server == null || server.isAnyLocalAddress() || server.isLoopbackAddress()
                    || server.isMulticastAddress()) continue;
            // Keep the existing IPv4 preference when both families are present.
            if (server instanceof Inet4Address) return server;
            if (server instanceof Inet6Address && ipv6 == null) {
                Inet6Address address = (Inet6Address)server;
                // Do not guess a link-local zone or remove its OS-provided scope.
                if (!address.isLinkLocalAddress() || address.getScopeId() > 0) ipv6 = address;
            }
        }
        return ipv6;
    }
}
