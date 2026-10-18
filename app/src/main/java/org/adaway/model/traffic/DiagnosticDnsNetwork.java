package org.adaway.model.traffic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * One-shot real upstream DNS transport for the fixed example.com A question.
 *
 * No resolver addresses or user queries are stored or sent to telemetry.
 * Only an OS-provided DNS server of a validated, non-VPN network is used.
 * Strict private DNS / blocked UDP 53 may cause a normal, safe test failure.
 */
public final class DiagnosticDnsNetwork {
    private DiagnosticDnsNetwork() { }

    private static final class Destination {
        final Network network;
        final Inet4Address resolver;
        Destination(Network network, Inet4Address resolver) {
            this.network = network;
            this.resolver = resolver;
        }
    }

    private static Destination resolver(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IOException("Network manager unavailable");
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                continue;
            }
            LinkProperties props = manager.getLinkProperties(network);
            if (props == null) continue;
            for (InetAddress server : props.getDnsServers()) {
                if (server instanceof Inet4Address
                        && !server.isAnyLocalAddress() && !server.isLoopbackAddress()
                        && !server.isMulticastAddress()) {
                    return new Destination(network, (Inet4Address) server);
                }
            }
        }
        throw new IOException("No validated non-VPN IPv4 DNS network");
    }

    public static byte[] exchange(VpnService service, byte[] question,
                                  DiagnosticSockets sockets) throws IOException {
        if (!DiagnosticDnsCodec.isAllowedQuery(question))
            throw new IOException("DNS diagnostic query not allowed");

        Destination target = resolver(service);
        return DiagnosticDnsTransport.exchange(target.resolver, 53, question, sockets,
                new DiagnosticDnsTransport.Hooks() {
                    @Override public void prepare(DatagramSocket socket) throws IOException {
                        if (!service.protect(socket)) throw new IOException("VPN protect failed");
                        target.network.bindSocket(socket);
                    }
                    @Override public void prepare(java.net.Socket socket) throws IOException {
                        if (!service.protect(socket)) throw new IOException("VPN protect failed");
                        target.network.bindSocket(socket);
                    }
                });
    }
}
