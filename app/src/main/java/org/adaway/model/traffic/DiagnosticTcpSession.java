package org.adaway.model.traffic;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded authenticated TCP half-close transaction, entirely on loopback. */
public final class DiagnosticTcpSession implements AutoCloseable {
    public enum Status { OK, CANCELLED, TIMEOUT, REJECTED, FAILED, CLEANUP_FAILED, UNAVAILABLE }
    enum Fault { NONE, SILENT, STALE, TRUNCATED, BAD_AUTH, BAD_TARGET, OVERSIZE }
    @FunctionalInterface public interface Protector { boolean protect(Socket socket); }
    public static final class Result {
        public final Status status;
        public final int family, opened, closed, protectedSockets, stage;
        public final long elapsedMs;
        public final boolean cleanup;
        Result(Status status, int family, int opened, int closed, int protectedSockets, long elapsedMs, boolean cleanup, int stage) {
            this.status=status;this.family=family;this.opened=opened;this.closed=closed;
            this.protectedSockets=protectedSockets;this.elapsedMs=elapsedMs;this.cleanup=cleanup;this.stage=stage;
        }
        static Result unavailable(boolean ipv6) { return new Result(Status.UNAVAILABLE,ipv6?6:4,0,0,0,0,true,0); }
        @Override public String toString() { return status+" stage="+stage+" sockets="+closed+"/"+opened+" protected="+protectedSockets; }
    }
    private final boolean ipv6;
    private final Protector protector;
    private final int budgetMs;
    private final Fault fault;
    private final Resources resources = new Resources();
    private final CountDownLatch halt = new CountDownLatch(1);
    private final CountDownLatch backendReady = new CountDownLatch(1);
    private final AtomicReference<Status> serverFailure = new AtomicReference<>();
    private final byte[] token = new byte[16];
    private volatile boolean stopped;
    private final java.util.concurrent.atomic.AtomicInteger protectedSockets = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
    private long deadline;
    private int stage;
    private Thread proxyThread, backendThread;

    public DiagnosticTcpSession(boolean ipv6, Protector protector) { this(ipv6,protector,1200,Fault.NONE); }
    DiagnosticTcpSession(boolean ipv6, Protector protector, int budgetMs, Fault fault) {
        if (protector==null || budgetMs<1 || budgetMs>1200 || fault==null) throw new IllegalArgumentException("Invalid TCP diagnostic");
        this.ipv6=ipv6;this.protector=protector;this.budgetMs=budgetMs;this.fault=fault;
        new SecureRandom().nextBytes(token);
    }
    private int remaining() throws SocketTimeoutException {
        long left=deadline-System.nanoTime();
        if (left<=0) throw new SocketTimeoutException("TCP diagnostic deadline");
        return (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(left));
    }
    private ServerSocket listen(InetAddress address) throws IOException {
        ServerSocket server=resources.add(new ServerSocket());
        server.bind(new InetSocketAddress(address,0),1);server.setSoTimeout(remaining());return server;
    }
    private Socket connect(InetAddress address, int port, boolean protect) throws IOException {
        Socket socket=resources.add(new Socket(java.net.Proxy.NO_PROXY));
        socket.setTcpNoDelay(true);
        socket.bind(new InetSocketAddress(address,0));
        if (protect) {
            if (!protector.protect(socket)) throw new IOException("TCP protection refused");
            protectedSockets.incrementAndGet();
        }
        socket.connect(new InetSocketAddress(address,port),remaining());return socket;
    }
    private byte[] read(Socket socket, int size) throws IOException {
        byte[] bytes=new byte[size];int at=0;InputStream input=socket.getInputStream();
        while (at<size) {
            socket.setSoTimeout(remaining());int count=input.read(bytes,at,Math.min(7,size-at));
            if (count<0) throw new java.io.EOFException("Incomplete TCP diagnostic frame");
            at+=count;
        }
        return bytes;
    }
    private byte[] untilEof(Socket socket) throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buffer=new byte[7];
        for (;;) {
            socket.setSoTimeout(remaining());int count=socket.getInputStream().read(buffer);
            if (count<0) return body.toByteArray();
            if (body.size()+count>DiagnosticSocks5.LIMIT) throw new ProtocolFailure("oversize "+body.size()+"+"+count);
            body.write(buffer,0,count);
        }
    }
    private void write(Socket socket, byte[] bytes) throws IOException {
        for (int at=0;at<bytes.length;at+=3) {
            remaining();socket.getOutputStream().write(bytes,at,Math.min(3,bytes.length-at));
        }
    }
    private static void halfClose(Socket socket) {
        if (socket!=null) try { socket.shutdownOutput(); } catch (IOException ignored) { /* cancellation */ }
    }
    private boolean authenticate(Socket client) throws IOException {
        byte[] prefix=read(client,2);
        if (prefix[0]!=5 || prefix[1]!=1) { write(client,new byte[]{5,(byte)255});return false; }
        int greeting=DiagnosticSocks5.greetingCode(new byte[]{prefix[0],prefix[1],read(client,1)[0]});
        write(client,new byte[]{5,(byte)(greeting==0?2:255)});
        if (greeting!=0) return false;
        byte[] auth=read(client,2);
        if (auth[0]!=1 || auth[1]!=6) { write(client,new byte[]{1,1});return false; }
        byte[] middle=read(client,7);
        if (middle[6]!=16) { write(client,new byte[]{1,1});return false; }
        byte[] frame=new byte[25];System.arraycopy(auth,0,frame,0,2);System.arraycopy(middle,0,frame,2,7);
        System.arraycopy(read(client,16),0,frame,9,16);
        int code=DiagnosticSocks5.authenticationCode(frame,token);write(client,new byte[]{1,(byte)code});return code==0;
    }
    private boolean request(Socket client) throws IOException {
        byte[] header=read(client,4);
        if (header[3]!=1 && header[3]!=4) { write(client,DiagnosticSocks5.reply(8));return false; }
        int tail=header[3]==1?6:18;
        byte[] bytes=Arrays.copyOf(header,4+tail);System.arraycopy(read(client,tail),0,bytes,4,tail);
        int code=DiagnosticSocks5.requestCode(bytes,ipv6);
        if (code!=0) write(client,DiagnosticSocks5.reply(code));return code==0;
    }
    private void serveProxy(ServerSocket listener, InetAddress address, int backendPort) {
        Socket client=null;
        try {
            client=resources.add(listener.accept());
            client.setTcpNoDelay(true);
            if (!client.getInetAddress().isLoopbackAddress() || !authenticate(client) || !request(client)) {
                serverFailure.compareAndSet(null,Status.REJECTED);return;
            }
            Socket upstream=connect(address,backendPort,true);
            write(client,DiagnosticSocks5.reply(0));
            byte[] body=untilEof(client);
            if (body.length==0) throw new ProtocolFailure();
            write(upstream,body);upstream.shutdownOutput();
            byte[] answer=untilEof(upstream);write(client,answer);
        } catch (IOException | RuntimeException failure) { serverFailure.compareAndSet(null,classify(failure)); }
        finally { halfClose(client); }
    }
    private void serveBackend(ServerSocket listener) {
        Socket client=null;
        try {
            client=resources.add(listener.accept());
            client.setTcpNoDelay(true);
            if (!client.getInetAddress().isLoopbackAddress()) throw new ProtocolFailure();
            byte[] bytes=untilEof(client);backendReady.countDown();
            if (fault==Fault.SILENT) { halt.await(remaining(),TimeUnit.MILLISECONDS);return; }
            if (bytes.length==0) throw new ProtocolFailure();
            bytes[0]^=0x5a;
            if (fault==Fault.STALE) bytes[0]^=1;
            if (fault==Fault.TRUNCATED) bytes=Arrays.copyOf(bytes,bytes.length-1);
            write(client,bytes);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException failure) { serverFailure.compareAndSet(null,classify(failure)); }
        finally { halfClose(client); }
    }
    private Status classify(Exception failure) {
        if (stopped) return Status.CANCELLED;
        if (failure instanceof SocketTimeoutException || System.nanoTime()>=deadline) return Status.TIMEOUT;
        if (failure instanceof ProtocolFailure) return Status.REJECTED;
        Status status=serverFailure.get();return status==null?Status.FAILED:status;
    }
    public Result run() {
        if (!started.compareAndSet(false,true)) return Result.unavailable(ipv6);
        long started=System.nanoTime();deadline=started+TimeUnit.MILLISECONDS.toNanos(budgetMs);Status status=Status.FAILED;
        try {
            InetAddress address=InetAddress.getByName(ipv6?"::1":"127.0.0.1");
            ServerSocket backend=listen(address),proxy=listen(address);
            backendThread=new Thread(()->serveBackend(backend),"diagnostic-tcp-backend");
            proxyThread=new Thread(()->serveProxy(proxy,address,backend.getLocalPort()),"diagnostic-tcp-proxy");
            backendThread.setDaemon(true);proxyThread.setDaemon(true);backendThread.start();proxyThread.start();
            exchange(address,proxy.getLocalPort());status=Status.OK;
        } catch (IOException | RuntimeException failure) { status=classify(failure); }
        finally { close(); }
        boolean cleanup=awaitStopped();
        if (!cleanup) status=Status.CLEANUP_FAILED;
        return new Result(status,ipv6?6:4,resources.opened,resources.closed,protectedSockets.get(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),cleanup,stage);
    }
    private void exchange(InetAddress address, int port) throws IOException {
        Socket client=connect(address,port,false);
        stage=1;
        write(client,DiagnosticSocks5.greeting());
        if (!Arrays.equals(read(client,2),new byte[]{5,2})) throw new ProtocolFailure();
        stage=2;
        byte[] auth=DiagnosticSocks5.authentication(token);
        if (fault==Fault.BAD_AUTH) auth[24]^=1;
        write(client,auth);
        if (!Arrays.equals(read(client,2),new byte[]{1,0})) throw new ProtocolFailure();
        stage=3;
        byte[] request=DiagnosticSocks5.request(ipv6);
        if (fault==Fault.BAD_TARGET) request[request.length-1]^=1;
        write(client,request);
        if (!Arrays.equals(read(client,10),DiagnosticSocks5.reply(0))) throw new ProtocolFailure();
        stage=4;
        byte[] bytes=new byte[fault==Fault.OVERSIZE?DiagnosticSocks5.LIMIT+1:DiagnosticSocks5.LIMIT];
        new SecureRandom().nextBytes(bytes);write(client,bytes);client.shutdownOutput();
        stage=5;
        byte[] expected=bytes.clone();expected[0]^=0x5a;
        byte[] received=untilEof(client);
        if (!Arrays.equals(received,expected)) throw new ProtocolFailure("reply length "+received.length);
        remaining();
        stage=6;
    }
    boolean awaitBackend(long timeout) throws InterruptedException { return backendReady.await(timeout,TimeUnit.MILLISECONDS); }
    private boolean awaitStopped() {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
        try {
            for (Thread thread:new Thread[]{proxyThread,backendThread}) if (thread!=null) {
                long left=until-System.nanoTime();if(left>0) thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
                if(thread.isAlive()) return false;
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();return false; }
        return resources.cleanup();
    }
    @Override public void close() { stopped=true;halt.countDown();resources.close(); }
    private static final class ProtocolFailure extends IOException { private static final long serialVersionUID=1;ProtocolFailure(){}ProtocolFailure(String text){super(text);} }
    private static final class Resources implements AutoCloseable {
        private final Set<Closeable> owned=new HashSet<>();
        private boolean stopped,failed;
        private int opened,closed;
        synchronized <T extends Closeable> T add(T socket) throws IOException {
            opened++;
            if (stopped) { dispose(socket);throw new IOException("TCP diagnostic stopped"); }
            owned.add(socket);return socket;
        }
        private void dispose(Closeable socket) {
            try { socket.close();closed++; } catch (IOException failure) { failed=true; }
        }
        @Override public synchronized void close() { stopped=true;for(Closeable socket:owned)dispose(socket);owned.clear(); }
        synchronized boolean cleanup() { return !failed && opened==closed && owned.isEmpty(); }
    }
}
