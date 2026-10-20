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
}
