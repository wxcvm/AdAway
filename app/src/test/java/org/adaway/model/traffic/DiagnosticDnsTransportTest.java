package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Actual loopback UDP/TCP. No DNS lookup, Android service, routes or external resolver. */
public class DiagnosticDnsTransportTest {
    private static final InetAddress LOOPBACK;
    static { try { LOOPBACK = InetAddress.getByAddress(new byte[]{127,0,0,1}); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); } }
    private static final DiagnosticDnsTransport.Hooks HOOKS = new DiagnosticDnsTransport.Hooks() {
        public void prepare(DatagramSocket socket) { assertFalse(socket.isConnected()); }
        public void prepare(Socket socket) { assertFalse(socket.isConnected()); }
    };
    private static final class Fixture implements AutoCloseable {
        final ServerSocket tcp = new ServerSocket(); final DatagramSocket udp;
        final List<Thread> workers = new ArrayList<>(); final List<Socket> accepted = new CopyOnWriteArrayList<>();
        final AtomicInteger udpCount = new AtomicInteger(), tcpCount = new AtomicInteger();
        final CountDownLatch tcpRead = new CountDownLatch(1), release = new CountDownLatch(1);
        final String mode; volatile boolean stop; volatile Throwable failure;
        Fixture(String mode) throws IOException {
            this.mode = mode; tcp.bind(new InetSocketAddress(LOOPBACK,0)); tcp.setSoTimeout(100);
            udp = new DatagramSocket(new InetSocketAddress(LOOPBACK, tcp.getLocalPort())); udp.setSoTimeout(100);
            start(() -> {
                byte[] bytes = new byte[512]; DatagramPacket packet = new DatagramPacket(bytes,bytes.length);
                while (!stop) try {
                    udp.receive(packet); udpCount.incrementAndGet();
                    byte[] answer = Arrays.copyOf(bytes, packet.getLength()); answer[2] = (byte)(mode.equals("UDP") ? 0x81 : 0x83);
                    if (mode.equals("SLOW")) Thread.sleep(140);
                    if (!mode.equals("SILENT")) udp.send(new DatagramPacket(answer,answer.length,packet.getSocketAddress()));
                    return;
                } catch (SocketTimeoutException retry) { }
            });
            start(() -> {
                while (!stop) try (Socket socket = tcp.accept()) {
                    accepted.add(socket); tcpCount.incrementAndGet(); socket.setSoTimeout(1500);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    int n = input.readUnsignedShort(); if (n != 29) throw new AssertionError("query framing");
                    byte[] answer = new byte[n]; input.readFully(answer); tcpRead.countDown();
                    answer[2] = (byte)0x81;
                    if (mode.equals("STOP")) { release.await(2,TimeUnit.SECONDS); return; }
                    if (mode.equals("BAD_ID")) answer[0]++;
                    if (mode.equals("TCP_TC")) answer[2] |= 2;
                    OutputStream out = socket.getOutputStream();
                    if (mode.equals("OVERSIZE")) { out.write(new byte[]{2,1}); return; }
                    if (mode.equals("SLOW")) Thread.sleep(80);
                    out.write(0); out.flush(); out.write(n); out.flush();
                    if (mode.equals("SHORT")) { out.write(answer,0,5); return; }
                    if (mode.equals("SLOW")) Thread.sleep(100);
                    for (byte b : answer) { out.write(b); out.flush(); }
                    return;
                } catch (SocketTimeoutException retry) { }
            });
        }
        interface Task { void run() throws Exception; }
        void start(Task task) {
            Thread thread = new Thread(() -> { try { task.run(); }
                catch (Throwable error) { if (!stop && !(error instanceof SocketException)) failure = error; } });
            workers.add(thread); thread.start();
        }
        public void close() throws Exception {
            stop = true; release.countDown(); udp.close(); tcp.close();
            for (Socket socket : accepted) socket.close();
            for (Thread worker : workers) { worker.join(2000); assertFalse("fixture leaked a thread", worker.isAlive()); }
            if (failure != null) throw new AssertionError(failure);
        }
    }
    private static byte[] exchange(Fixture fixture, DiagnosticSockets registry, int timeout) throws IOException {
        return DiagnosticDnsTransport.exchange(LOOPBACK,fixture.tcp.getLocalPort(),DiagnosticDnsCodec.query(42),registry,HOOKS,timeout);
    }
    @Test public void ordinaryUdpReplyDoesNotOpenTcp() throws Exception {
        try (Fixture f = new Fixture("UDP"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            assertTrue(DiagnosticDnsCodec.matchesResponse(DiagnosticDnsCodec.query(42),exchange(f,sockets,800)));
            assertEquals(1,f.udpCount.get()); assertEquals(0,f.tcpCount.get());
        }
    }
    @Test public void truncationRetriesOnceAndReadsFragmentedTcpFrame() throws Exception {
        try (Fixture f = new Fixture("TCP"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            assertTrue(DiagnosticDnsCodec.matchesResponse(DiagnosticDnsCodec.query(42),exchange(f,sockets,800)));
            assertEquals(1,f.udpCount.get()); assertEquals(1,f.tcpCount.get());
        }
    }
    @Test public void malformedOrTruncatedTcpCannotBecomeSuccess() throws Exception {
        for (String mode : new String[]{"BAD_ID","TCP_TC","OVERSIZE","SHORT"}) {
            try (Fixture f = new Fixture(mode); DiagnosticSockets sockets = new DiagnosticSockets()) {
                try { exchange(f,sockets,800); fail(mode); } catch (IOException expected) { }
            }
        }
    }
    @Test public void udpAndTcpShareOneDeadlineIncludingSlowPartialReads() throws Exception {
        try (Fixture f = new Fixture("SLOW"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            long start = System.nanoTime();
            try { exchange(f,sockets,230); fail("must not reset timeout for TCP"); } catch (SocketTimeoutException expected) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 900);
        }
    }
    @Test public void stoppedRegistryCancelsPendingTcpRead() throws Exception {
        try (Fixture f = new Fixture("STOP"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> pending = worker.submit(() -> { try { exchange(f,sockets,1200); return false; } catch (IOException expected) { return true; } });
                assertTrue(f.tcpRead.await(1,TimeUnit.SECONDS)); sockets.close();
                assertTrue(pending.get(1,TimeUnit.SECONDS));
                Socket late = new Socket();
                try { sockets.register(late); fail(); } catch (IOException expected) { assertTrue(late.isClosed()); }
            } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS)); }
        }
    }
    @Test public void failedTcpProtectionNeverConnects() throws Exception {
        try (Fixture f = new Fixture("TCP"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            DiagnosticDnsTransport.Hooks refused = new DiagnosticDnsTransport.Hooks() {
                public void prepare(DatagramSocket socket) { }
                public void prepare(Socket socket) throws IOException { throw new IOException("protect refused"); }
            };
            try { DiagnosticDnsTransport.exchange(LOOPBACK,f.tcp.getLocalPort(),DiagnosticDnsCodec.query(42),sockets,refused); fail(); }
            catch (IOException expected) { assertEquals(0,f.tcpCount.get()); }
        }
    }
    @Test public void rulesDecideBeforeTheRealLoopbackTransport() throws Exception {
        try (Fixture f = new Fixture("TCP"); DiagnosticSockets sockets = new DiagnosticSockets()) {
            RuleSnapshotRepository rules = new RuleSnapshotRepository();
            assertTrue(rules.publish(1,Map.of("example.com",TrafficCoreContract.Action.NXDOMAIN)));
            byte[] q = DiagnosticDnsCodec.query(42);
            byte[] blocked = DiagnosticDnsRelay.resolveFromSnapshotOrUpstream(q,rules.read(), p -> exchange(f,sockets,800));
            assertEquals(3,DiagnosticDnsCodec.responseCode(blocked)); assertEquals(0,f.udpCount.get());
            assertTrue(rules.publish(2,Map.of("example.com",TrafficCoreContract.Action.PASS)));
            byte[] allowed = DiagnosticDnsRelay.resolveFromSnapshotOrUpstream(q,rules.read(), p -> exchange(f,sockets,800));
            assertEquals(0,DiagnosticDnsCodec.responseCode(allowed)); assertEquals(1,f.tcpCount.get());
        }
    }
}
