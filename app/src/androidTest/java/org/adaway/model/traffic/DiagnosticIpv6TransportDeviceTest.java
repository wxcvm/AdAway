package org.adaway.model.traffic;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Android sockets on ::1 only. No VPN, routes, DNS lookup or external network. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticIpv6TransportDeviceTest {
    private static InetAddress loopback() throws Exception {
        byte[] bytes = new byte[16]; bytes[15] = 1;
        return Inet6Address.getByAddress(null,bytes,0);
    }
    private static final DiagnosticDnsTransport.Hooks HOOKS = new DiagnosticDnsTransport.Hooks() {
        public void prepare(DatagramSocket socket) { assertFalse(socket.isConnected()); }
        public void prepare(Socket socket) { assertFalse(socket.isConnected()); }
    };
    private static final class Fixture implements AutoCloseable {
        final InetAddress address = loopback();
        final ServerSocket tcp; final DatagramSocket udp;
        final boolean truncated, hold;
        final List<Thread> workers = new ArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch tcpRead = new CountDownLatch(1), peerClosed = new CountDownLatch(1);
        final AtomicInteger udpCount = new AtomicInteger(), tcpCount = new AtomicInteger();
        volatile boolean closing; volatile Socket peer;
        Fixture(boolean truncated, boolean hold) throws Exception {
            this.truncated=truncated; this.hold=hold;
            ServerSocket server = null; DatagramSocket datagram = null;
            for (int attempt=0; ; attempt++) {
                server=new ServerSocket();
                try {
                    server.bind(new InetSocketAddress(address,0));
                    datagram=new DatagramSocket(null); datagram.bind(new InetSocketAddress(address,server.getLocalPort()));
                    break;
                } catch (IOException error) {
                    if (datagram != null) datagram.close(); server.close(); datagram=null;
                    if (!(error instanceof BindException) || attempt==15) throw error;
                }
            }
            tcp=server; udp=datagram; tcp.setSoTimeout(2000); udp.setSoTimeout(2000);
            start(() -> {
                byte[] bytes=new byte[513]; DatagramPacket p=new DatagramPacket(bytes,bytes.length);
                udp.receive(p); udpCount.incrementAndGet();
                byte[] answer=Arrays.copyOf(bytes,p.getLength()); assertTrue(DiagnosticDnsCodec.isAllowedQuery(answer));
                answer[2]=(byte)(truncated ? 0x83 : 0x81);
                udp.send(new DatagramPacket(answer,answer.length,p.getSocketAddress()));
            });
            if (truncated) start(() -> {
                try (Socket socket=tcp.accept()) {
                    peer=socket; tcpCount.incrementAndGet(); socket.setSoTimeout(2000);
                    DataInputStream input=new DataInputStream(socket.getInputStream());
                    int size=input.readUnsignedShort(); assertEquals(29,size);
                    byte[] answer=new byte[size]; input.readFully(answer); assertTrue(DiagnosticDnsCodec.isAllowedQuery(answer));
                    tcpRead.countDown();
                    if (hold) {
                        try { assertEquals(-1,input.read()); }
                        catch (SocketException reset) { /* Peer cancellation can reset the socket. */ }
                        peerClosed.countDown(); return;
                    }
                    answer[2]=(byte)0x81;
                    OutputStream output=socket.getOutputStream();
                    output.write(0); output.flush(); output.write(size); output.flush();
                    for (byte b : answer) { output.write(b); output.flush(); }
                }
            });
        }
        interface Task { void run() throws Exception; }
        void start(Task task) {
            Thread worker=new Thread(() -> {
                try { task.run(); }
                catch (Throwable error) {
                    if (!(closing && error instanceof SocketException)) failure.compareAndSet(null,error);
                }
            });
            workers.add(worker); worker.start();
        }
        @Override public void close() throws Exception {
            closing=true; udp.close(); tcp.close(); if (peer != null) peer.close();
            for (Thread worker : workers) { worker.join(2500); assertFalse("IPv6 fixture thread leaked",worker.isAlive()); }
            if (failure.get()!=null) throw new AssertionError(failure.get());
        }
    }
    @Test public void androidIpv6SocketsCarryBothQuestionTypesAndTcpFallback() throws Exception {
        byte[] link=new byte[16]; link[0]=(byte)0xfe; link[1]=(byte)0x80; link[15]=53;
        Inet6Address scoped=Inet6Address.getByAddress(null,link,7);
        assertSame(scoped,DiagnosticDnsResolverPolicy.select(List.of(scoped)));
        assertNull(DiagnosticDnsResolverPolicy.select(List.of(Inet6Address.getByAddress(null,link,0))));
        for (int type : new int[]{1,28}) for (boolean truncated : new boolean[]{false,true}) {
            try (Fixture f=new Fixture(truncated,false); DiagnosticSockets sockets=new DiagnosticSockets()) {
                byte[] query=DiagnosticDnsCodec.query(0x1234,type);
                byte[] answer=DiagnosticDnsTransport.exchange(f.address,f.tcp.getLocalPort(),query,sockets,HOOKS);
                assertTrue(DiagnosticDnsCodec.matchesResponse(query,answer));
                assertEquals(1,f.udpCount.get()); assertEquals(truncated ? 1 : 0,f.tcpCount.get());
            }
        }
    }
    @Test public void stoppingAndroidIpv6TcpClosesTheActualPeerConnection() throws Exception {
        try (Fixture f=new Fixture(true,true); DiagnosticSockets sockets=new DiagnosticSockets()) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> pending=worker.submit(() -> {
                    try { DiagnosticDnsTransport.exchange(f.address,f.tcp.getLocalPort(),DiagnosticDnsCodec.query(42,28),sockets,HOOKS); return false; }
                    catch (IOException expected) { return true; }
                });
                assertTrue(f.tcpRead.await(1,TimeUnit.SECONDS)); assertFalse(pending.isDone());
                sockets.close(); assertTrue(pending.get(1,TimeUnit.SECONDS));
                assertTrue(f.peerClosed.await(1,TimeUnit.SECONDS));
            } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS)); }
        }
    }
}
