package org.adaway.util;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import static org.junit.Assert.*;

/** Fixture key is intentionally public, test-only, and never a production anchor. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
public class ListenerTlsDeviceTest {
    private SSLContext fixture() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("arrival-test-only.p12")) {
            assertNotNull(input); store.load(input, "test-fixture-only".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-fixture-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private ListenerTlsCheck.Result probe(String ip, String identity, boolean trusted, boolean receipt) throws Exception {
        SSLContext context = fixture();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (SSLServerSocket server = (SSLServerSocket)context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName(ip))) {
            server.setSoTimeout(4000);
            Thread responder = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket)server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    if (request == null) return;
                    String nonce = request.substring(request.indexOf("/adaway-listener-probe/") + 23, request.indexOf(" HTTP/"));
                    String body = receipt ? "adaway-listener:" + nonce : "foreign";
                    String headers = receipt ? "X-AdAway-Listener-Probe: " + nonce + "\r\n" : "";
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + headers + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                } catch (SSLException expectedRejection) {
                    // Strict client refuses the fixture CA or reference name in negative tests.
                } catch (Throwable exception) { failure.set(exception); }
            });
            responder.setDaemon(true); responder.start();
            SSLSocketFactory factory = trusted ? context.getSocketFactory() : (SSLSocketFactory)SSLSocketFactory.getDefault();
            ListenerTlsCheck.Result result = ListenerTlsCheck.check(ip, server.getLocalPort(), identity, address -> true, factory);
            responder.join(4500); assertFalse("TLS fixture still running", responder.isAlive());
            if (failure.get() != null && result.state != ListenerTlsCheck.State.CERTIFICATE_REJECTED) throw new AssertionError(ip + " / " + identity + " / " + result.state, failure.get());
            return result;
        }
    }

    @Test public void trustedDualStackTlsRequiresFreshEncryptedReceipt() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "::1"}) {
            ListenerTlsCheck.Result result = probe(ip, "listener.arrival-fixture.invalid", true, true);
            assertEquals(ListenerTlsCheck.State.RECEIPT, result.state);
            assertTrue(result.protocol.startsWith("TLS"));
        }
    }
    @Test public void untrustedAndWrongNameCertificatesAreRejectedWithoutRetry() throws Exception {
        assertEquals(ListenerTlsCheck.State.CERTIFICATE_REJECTED, probe("127.0.0.1", "localhost", false, true).state);
        assertEquals(ListenerTlsCheck.State.CERTIFICATE_REJECTED, probe("127.0.0.1", "wrong.arrival-fixture.invalid", true, true).state);
    }
    @Test public void trustedGenericHttpsIsNotAnAdAwayReceipt() throws Exception {
        assertEquals(ListenerTlsCheck.State.NO_RECEIPT, probe("127.0.0.1", "localhost", true, false).state);
    }
    @Test public void nonlocalAddressIsRefusedBeforeConnecting() {
        assertEquals(ListenerTlsCheck.State.UNAVAILABLE,
                ListenerTlsCheck.check("203.0.113.9", 443, "listener.arrival-fixture.invalid", address -> false).state);
    }

    @Test public void cancellationInterruptsRealDualStackHttpAndTlsWithoutWaitingForTimeout() throws Exception {
        for (String address : new String[]{"127.0.0.1", "::1"}) {
            cancelledSocket(address, false);
            cancelledSocket(address, true);
        }
    }

    private static void cancelledSocket(String address, boolean tls) throws Exception {
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, InetAddress.getByName(address))) {
            listener.setSoTimeout(2000);
            java.util.concurrent.Future<?> peer = workers.submit(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    assertTrue(socket.getInputStream().read() >= 0);
                    started.countDown();
                    release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            });
            java.util.concurrent.Future<?> checking = workers.submit(() -> {
                if (tls) return ListenerTlsCheck.check(address, listener.getLocalPort(), "localhost", ip -> true, session);
                return ListenerArrivalCheck.received(address, listener.getLocalPort(), "localhost", session);
            });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            session.cancel();
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> checking.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.io.InterruptedIOException);
            release.countDown(); peer.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void cancelledWaitDoesNotAdmitAnotherUnfinishedResolver() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        try {
            java.util.concurrent.Future<?> waiting = worker.submit(() -> ListenerArrivalCheck.resolveSystem(session, () -> {
                calls.incrementAndGet(); started.countDown();
                awaitResolverRelease(release);
                ended.countDown();
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }));
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            session.cancel();
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.io.InterruptedIOException);
            assertThrows(java.io.IOException.class, () -> ListenerArrivalCheck.resolveSystem(new ListenerDiagnosticSession(), () -> {
                calls.incrementAndGet(); return new InetAddress[0];
            }));
            assertEquals(1, calls.get());
        } finally {
            release.countDown(); assertTrue(ended.await(1, java.util.concurrent.TimeUnit.SECONDS));
            worker.shutdownNow();
        }
    }

    private static void awaitResolverRelease(java.util.concurrent.CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (release.getCount() != 0) {
                try { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("DNS fixture release timed out"); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
