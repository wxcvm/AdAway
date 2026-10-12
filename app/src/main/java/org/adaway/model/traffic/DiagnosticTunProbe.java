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
    private DiagnosticTunProbe() {}

    public static boolean roundTrip() {
        if (!DiagnosticVpnService.isActive()) return false;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
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
