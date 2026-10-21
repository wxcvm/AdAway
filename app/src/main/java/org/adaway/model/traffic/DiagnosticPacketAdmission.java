package org.adaway.model.traffic;

/** Reader-thread admission: unsupported control traffic must not consume the diagnostic budget. */
public final class DiagnosticPacketAdmission {
    private long lastAcceptedMs = -200;
    private volatile long unsupported;
    private volatile long limited;
    private volatile long accepted;

    public boolean allow(byte[] packet, int length, long nowMs) {
        byte[] relay = DiagnosticUdpEcho.payload(packet, length, DiagnosticUdpRelay.PORT);
        boolean supported = relay != null && relay.length > 0 && relay.length <= DiagnosticUdpRelay.MAX_PAYLOAD;
        if (!supported) supported = DiagnosticDnsCodec.isAllowedQuery(
                DiagnosticUdpEcho.payload(packet, length, DiagnosticDnsCodec.PORT));
        if (!supported) { unsupported++; return false; }
        if (nowMs - lastAcceptedMs < 200) { limited++; return false; }
        lastAcceptedMs = nowMs;
        accepted++;
        return true;
    }

    public String counts() {
        return "accepted=" + accepted + ", unsupported=" + unsupported + ", limited=" + limited;
    }

    public long unsupported() { return unsupported; }
}
