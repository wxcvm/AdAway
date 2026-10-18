package org.adaway.model.traffic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * End-to-end local UDP echo diagnostic. Send only to the scoped TUN test IP;
 * no external servers, DNS or default route are involved.
 */
public final class DiagnosticTunProbe {
    private static final SecureRandom RANDOM = new SecureRandom();
    private DiagnosticTunProbe() {}

    /**
     * Public-network diagnostic for exactly example.com A/AAAA via the OS-selected
     * underlying network DNS resolver. Only initiated by the user, and only
     * through the test-only TUN IP and UDP port.
     */
    /** -1 = transport/validation failure; 0..15 = actual DNS RCODE. */
    public static int dnsResponseCode() {
        return dnsResponseCode(DiagnosticDnsCodec.TYPE_A);
    }

    public static int dnsResponseCode(int type) {
        if (!DiagnosticVpnService.isActive()) return -1;
        if (type != DiagnosticDnsCodec.TYPE_A && type != DiagnosticDnsCodec.TYPE_AAAA) return -1;
        byte[] request = DiagnosticDnsCodec.query(RANDOM.nextInt(65536), type);
        try (DatagramSocket socket = new DatagramSocket()) {
            try (DiagnosticDnsAuthorization.Lease permit = DiagnosticVpnService.authorizeDnsProbe(
                    socket.getLocalPort(), request)) {
            if (permit == null) return -1;
            socket.setSoTimeout(2500);
            InetAddress address = InetAddress.getByName(DiagnosticTunPlan.DIAGNOSTIC_TARGET);
            socket.send(new DatagramPacket(request, request.length,
                    address, DiagnosticDnsCodec.PORT));
            byte[] data = new byte[DiagnosticDnsCodec.MAX_DATAGRAM + 1];
            DatagramPacket packet = new DatagramPacket(data, data.length);
            socket.receive(packet);
            if (!address.equals(packet.getAddress())
                    || packet.getPort() != DiagnosticDnsCodec.PORT) return -1;
            byte[] answer = Arrays.copyOf(data, packet.getLength());
            return DiagnosticDnsCodec.matchesResponse(request, answer)
                    ? DiagnosticDnsCodec.responseCode(answer) : -1;
            }
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    /** Retained for callers that need an affirmative NOERROR result only. */
    public static boolean dnsRoundTrip() {
        return dnsResponseCode() == 0;
    }

    public static boolean relayRoundTrip() {
        if (!DiagnosticVpnService.isActive()) return false;
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            InetAddress addr = InetAddress.getByName(DiagnosticTunPlan.DIAGNOSTIC_TARGET);
            socket.send(new DatagramPacket(nonce, nonce.length,
                    addr, DiagnosticUdpRelay.PORT));
            byte[] response = new byte[64];
            DatagramPacket reply = new DatagramPacket(response, response.length);
            socket.receive(reply);
            byte[] expected = nonce.clone();
            expected[0] ^= 0x5a;
            return reply.getLength() == expected.length
                    && reply.getPort() == DiagnosticUdpRelay.PORT
                    && addr.equals(reply.getAddress())
                    && Arrays.equals(expected, Arrays.copyOf(response, expected.length));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public static boolean roundTrip() {
        if (!DiagnosticVpnService.isActive()) return false;
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1800);
            InetAddress addr = InetAddress.getByName(DiagnosticTunPlan.DIAGNOSTIC_TARGET);
            socket.send(new DatagramPacket(nonce, nonce.length,
                    addr, DiagnosticUdpEcho.PORT));
            byte[] response = new byte[64];
            DatagramPacket reply = new DatagramPacket(response, response.length);
            socket.receive(reply);
            return reply.getLength() == nonce.length
                    && DiagnosticUdpEcho.PORT == reply.getPort()
                    && addr.equals(reply.getAddress())
                    && Arrays.equals(nonce, Arrays.copyOf(response, nonce.length));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
