package org.adaway.util;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real IPv4-only HTTP server exercises the production control fallback and generation check. */
public class WebServerControlTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    @Test public void actualIpv4OnlyControlAcknowledgesMatchingGeneration() throws Exception {
        try (ControlFixture fixture = new ControlFixture(TOKEN)) {
            assertTrue(WebServerControl.reloadConfig(TOKEN, fixture.port()));
            assertTrue(fixture.correctRequests.get() > 0);
        }
    }
    @Test public void oldServerBodyAloneCannotClaimCurrentConfigurationApplied() throws Exception {
        try (ControlFixture fixture = new ControlFixture(null)) {
            assertFalse(WebServerControl.reloadConfig(TOKEN, fixture.port()));
            assertTrue(fixture.correctRequests.get() > 0);
        }
    }
    @Test public void staleAcknowledgementCannotConfirmAnotherGeneration() throws Exception {
        try (ControlFixture fixture = new ControlFixture("11111111111111111111111111111111")) {
            assertFalse(WebServerControl.reloadConfig(TOKEN, fixture.port()));
            assertTrue(fixture.correctRequests.get() > 0);
        }
    }
    private static final class ControlFixture implements AutoCloseable {
        final AtomicInteger correctRequests = new AtomicInteger();
        final AtomicBoolean running = new AtomicBoolean(true);
        final ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Future<?> served;
        ControlFixture(String generation) throws Exception {
            listener.setSoTimeout(100);
            served = worker.submit(() -> {
                int connections = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                while (running.get() && connections < 8 && System.nanoTime() < deadline) {
                    try (Socket client = listener.accept()) {
                        connections++;
                        client.setSoTimeout(1500);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        assertEquals("POST /control HTTP/1.1", reader.readLine());
                        int length = 0;
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty())
                            if (line.startsWith("Content-Length:")) length = Integer.parseInt(line.substring(15).trim());
                        assertTrue(length > 0 && length < 128);
                        char[] body = new char[length];
                        int read = 0;
                        while (read < length) {
                            int next = reader.read(body, read, length - read);
                            if (next < 0) throw new AssertionError("Truncated control request");
                            read += next;
                        }
                        assertEquals("cmd=reload_config", new String(body));
                        correctRequests.incrementAndGet();
                        String response = "OK: block config reloaded";
                        String header = generation == null ? "" : "X-AdAway-Config-Generation: " + generation + "\r\n";
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + header + "Content-Length: " + response.length()
                                + "\r\nConnection: close\r\n\r\n" + response).getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                    } catch (SocketTimeoutException timeout) {
                        // A short accept timeout lets fixture cancellation finish without an unbounded join.
                    } catch (java.io.IOException error) { throw new AssertionError(error); }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            running.set(false);
            try { served.get(2, TimeUnit.SECONDS); }
            finally { listener.close(); worker.shutdownNow(); }
        }
    }
}
