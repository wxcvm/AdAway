package org.adaway.model.traffic;

/** Reader-thread admission: unsupported control traffic must not consume the diagnostic budget. */
public final class DiagnosticPacketAdmission {
    private long lastAcceptedMs = -200;
    private final java.util.concurrent.atomic.AtomicLong unsupported = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong limited = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong accepted = new java.util.concurrent.atomic.AtomicLong();

    public boolean allow(byte[] packet, int length, long nowMs) {
        byte[] relay = DiagnosticUdpEcho.payload(packet, length, DiagnosticUdpRelay.PORT);
        boolean supported = relay != null && relay.length > 0 && relay.length <= DiagnosticUdpRelay.MAX_PAYLOAD;
        if (!supported) supported = DiagnosticDnsCodec.isAllowedQuery(
                DiagnosticUdpEcho.payload(packet, length, DiagnosticDnsCodec.PORT));
        if (!supported) { unsupported.incrementAndGet(); return false; }
        if (nowMs - lastAcceptedMs < 200) { limited.incrementAndGet(); return false; }
        lastAcceptedMs = nowMs;
        accepted.incrementAndGet();
        return true;
    }

    public String counts() {
        return "accepted=" + accepted.get() + ", unsupported=" + unsupported.get() + ", limited=" + limited.get();
    }

    public long unsupported() { return unsupported.get(); }
}
