package org.adaway.util;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import static org.junit.Assert.*;

/** Public fixture keys are test-only; no operating-system trust or network settings are changed. */
public class ListenerResponseCheckTest {
    private static final String[] FAMILIES = {"127.0.0.1", "::1"};
    private static SSLContext fixture(String resource) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = ListenerResponseCheckTest.class.getResourceAsStream(resource)) {
            assertNotNull(resource, in); store.load(in, "test-fixture-only".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-fixture-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return tls;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static String readRequest(Socket socket) throws Exception {
        socket.setSoTimeout(3000);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int tail = 0;
        while (out.size() < 4096) {
            int next = socket.getInputStream().read(); if (next < 0) break;
            out.write(next); tail = tail << 8 | next; if (tail == 0x0d0a0d0a) break;
        }
        String request = out.toString("US-ASCII");
        assertTrue(request, request.startsWith("GET / HTTP/1.1\r\n"));
        assertFalse("Normal check must not use management/receipt bypass", request.contains("adaway-listener-probe"));
        return request;
    }
    private static ListenerResponseCheck.Observation exchange(String ip, byte[] response, SSLContext tls, boolean trusted) throws Exception {
        ServerSocket server = tls == null ? new ServerSocket(0, 1, InetAddress.getByName(ip)) :
            tls.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName(ip));
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (server) {
            server.setSoTimeout(4000);
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readRequest(socket); socket.getOutputStream().write(response);
                } catch (SSLException rejected) { /* Expected in strict negative cases. */ }
                catch (Throwable failure) { error.set(failure); }
            });
            peer.setDaemon(true); peer.start();
            SSLSocketFactory factory = tls != null && trusted ? tls.getSocketFactory() : (SSLSocketFactory)SSLSocketFactory.getDefault();
            ListenerResponseCheck.Observation result = ListenerResponseCheck.checkEndpoint(ip, server.getLocalPort(), tls != null,
                address -> true, new ListenerDiagnosticSession(), factory);
            peer.join(4500); assertFalse("Fixture did not terminate", peer.isAlive());
            if (error.get() != null && result.state != ListenerResponseCheck.State.CERTIFICATE_REJECTED)
                throw new AssertionError(result.describe(), error.get());
            return result;
        }
    }
    private static byte[] response(int status, String type, String body) {
        return bytes("HTTP/1.1 " + status + " Fixture\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes(body).length + "\r\n\r\n" + body);
    }
    @Test public void actualTextImageAndDeniedStatusesAreSeparateForBothFamilies() throws Exception {
        for (String ip : FAMILIES) {
            for (int code : new int[]{200, 403, 404}) {
                ListenerResponseCheck.Observation result = exchange(ip, response(code,"text/plain", "forbidden"), null, false);
                assertEquals(ListenerResponseCheck.State.RESPONSE, result.state);
                assertEquals(code, result.status); assertEquals("text/plain", result.contentType);
                assertEquals(9, result.declaredLength); assertTrue(result.bodyComplete); assertEquals("forbidden", result.sample);
                assertEquals(ip.contains(":") ? 6 : 4, result.family);
            }
            ListenerResponseCheck.Observation image = exchange(ip, response(200,"image/webp", "RIFF1234WEBPpayload"), null, false);
            assertEquals("WebP prefix only", image.sampleKind);
            assertTrue(image.describe().contains("not proof that a browser rendered"));
        }
    }
    @Test public void allowanceIncludesHeadersAndNeverDeclaresTruncatedBodyComplete() throws Exception {
        String big = "x".repeat(12000);
        ListenerResponseCheck.Observation result = exchange("::1", response(200,"text/plain",big), null, false);
        assertEquals(4096, result.observedBytes); assertEquals(12000, result.declaredLength);
        assertTrue(result.readLimitReached); assertTrue(result.sampleTruncated); assertFalse(result.bodyComplete);
        assertEquals(ListenerResponseCheck.State.INCOMPLETE, result.state);
        result = exchange("127.0.0.1", bytes("HTTP/1.1 200 OK\r\nX-Large: " + big + "\r\n\r\n"), null, false);
        assertEquals(4096, result.observedBytes); assertFalse(result.headersComplete); assertEquals(0, result.bodyBytes);
    }
    @Test public void malformedLengthsChunkingAndPrematureEofAreNotSuccessfulResponses() throws Exception {
        String[] inputs = {"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n",
            "HTTP/1.1 103 Early Hints\r\n\r\n"};
        for (String input : inputs) {
            ListenerResponseCheck.Observation result = exchange("127.0.0.1", bytes(input), null, false);
            assertFalse(result.describe(), result.bodyComplete);
            assertNotEquals(ListenerResponseCheck.State.RESPONSE, result.state);
        }
    }
    @Test public void strictHttpsChecksActualIpSanAndRejectsUntrustedOrWrongIdentity() throws Exception {
        SSLContext tls = fixture("/arrival-test-only.p12");
        for (String ip : FAMILIES) {
            ListenerResponseCheck.Observation result = exchange(ip, response(200,"text/plain","ok"), tls, true);
            assertEquals(result.describe(), ListenerResponseCheck.State.RESPONSE, result.state);
            assertTrue(result.protocol.startsWith("TLS")); assertTrue(result.url.contains(ip.contains(":") ? "[::1]" : ip));
        }
        assertEquals(ListenerResponseCheck.State.CERTIFICATE_REJECTED,
            exchange("127.0.0.1", response(200,"text/plain","ok"), tls, false).state);
        assertEquals(ListenerResponseCheck.State.CERTIFICATE_REJECTED, exchange("127.0.0.1", response(200,"text/plain","ok"),
            fixture("/response-name-only-test.p12"), true).state);
    }
    @Test public void nonexistentLocalAddressIsSkippedAndWildcardUsesSameFamilyLoopback() throws Exception {
        ListenerResponseCheck.Observation skipped = ListenerResponseCheck.checkEndpoint("203.0.113.9",80,false,
            address -> false,new ListenerDiagnosticSession());
        assertEquals(ListenerResponseCheck.State.SKIPPED, skipped.state); assertFalse(skipped.local);
        assertEquals("http://[::1]:8080/", ListenerResponseCheck.browserUrl("::1",8080,false));
        assertArrayEquals(FAMILIES,new ListenerSettings("0.0.0.0","::",8080,8443).diagnosticAddresses());
        assertThrows(IllegalArgumentException.class, () -> ListenerResponseCheck.browserUrl("localhost",80,false));
    }
    @Test public void mappedLiteralCannotMasqueradeAsAnIpv6Observation() throws Exception {
        for (String address : new String[]{"::ffff:127.0.0.1", "::ffff:7f00:1"}) {
            ListenerResponseCheck.Observation skipped = ListenerResponseCheck.checkEndpoint(address,80,false,
                candidate -> { fail("Mapped transport must be rejected before connecting"); return true; },
                new ListenerDiagnosticSession());
            assertEquals(ListenerResponseCheck.State.SKIPPED, skipped.state);
            assertFalse(skipped.local); assertEquals(0,skipped.status);
            assertTrue(skipped.detail.contains("no IPv6 request was attempted"));
        }
    }
    @Test public void heldResponseCancellationClosesOnlyThisSessionsSocket() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) { socket.setSoTimeout(3000); accepted.countDown(); release.await(5,TimeUnit.SECONDS); }
                catch (Exception ignored) { }
            }); peer.setDaemon(true); peer.start();
            ListenerDiagnosticSession session = new ListenerDiagnosticSession();
            Future<?> pending = worker.submit(() -> ListenerResponseCheck.checkEndpoint("127.0.0.1",server.getLocalPort(),false,address -> true,session));
            assertTrue(accepted.await(2,TimeUnit.SECONDS)); session.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(1,TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedIOException);
            release.countDown(); peer.join(2000); assertFalse(peer.isAlive());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
