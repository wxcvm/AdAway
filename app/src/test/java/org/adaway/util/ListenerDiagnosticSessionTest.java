package org.adaway.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ListenerDiagnosticSessionTest {
    @Test public void changedSavedTargetCancelsSocketsAndStopsFurtherDnsEndpoints() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>("old");
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        session.requireCurrentConfiguration(() -> "old".equals(saved.get()));
        Socket socket = new Socket();
        session.register(socket);
        AtomicInteger probes = new AtomicInteger();
        InterruptedIOException failure = assertThrows(InterruptedIOException.class, () -> ListenerArrivalCheck.domainResult(
                new ListenerSettings("127.0.0.1", "::1", 80, 443), "ads.example.test",
                domain -> new String[]{"127.0.0.1", "::1"},
                (ip, port) -> { probes.incrementAndGet(); saved.set("new"); return true; }, ip -> true, session));
        assertTrue(failure.getMessage().contains("监听配置已改变"));
        assertTrue(socket.isClosed());
        assertTrue(session.isCancelled());
        assertEquals(1, probes.get());
    }

    @Test public void unchangedSavedTargetDoesNotCancelAnUnsavedDraft() throws Exception {
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        String saved = new ListenerSettings("127.0.0.1", "::1", 80, 443).arguments();
        session.requireCurrentConfiguration(() -> saved.equals(new ListenerSettings("127.0.0.1", "::1", 80, 443).arguments()));
        new ListenerSettings("127.0.0.2", "", 28080, 28443); // Draft construction has no side effects.
        session.checkCancelled();
        assertFalse(session.isCancelled());
    }

    @Test public void sessionIsSingleUseAndStopsPublishingAfterCancel() throws Exception {
        List<String> progress = new ArrayList<>();
        ListenerDiagnosticSession session = new ListenerDiagnosticSession(progress::add);
        session.begin(); session.progress("HTTP"); session.finish();
        assertThrows(IllegalStateException.class, session::begin);
        session.cancel();
        assertTrue(session.isCancelled());
        assertThrows(InterruptedIOException.class, () -> session.progress("late"));
        assertEquals(List.of("HTTP"), progress);
    }

    @Test public void registrationRacingCancellationCannotLeakTheSocket() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < 100; index++) {
                ListenerDiagnosticSession session = new ListenerDiagnosticSession();
                Socket socket = new Socket();
                CountDownLatch go = new CountDownLatch(1);
                Future<?> registered = worker.submit(() -> {
                    try { go.await(); session.register(socket); }
                    catch (InterruptedIOException expected) { /* cancel won */ }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                });
                go.countDown(); session.cancel(); registered.get(1, TimeUnit.SECONDS);
                assertTrue(socket.isClosed());
            }
        } finally { worker.shutdownNow(); }
    }

    @Test public void actualBlockedHttpAndTlsReadsAreClosedByOnlyTheirSession() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch request = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ListenerDiagnosticSession session = new ListenerDiagnosticSession();
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(2000);
                Future<?> peer = workers.submit(() -> {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(2000);
                        assertTrue(socket.getInputStream().read() >= 0); // HTTP request or TLS ClientHello has started.
                        request.countDown(); release.await(3, TimeUnit.SECONDS);
                    } catch (IOException error) { throw new AssertionError(error); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                });
                Future<?> checking = workers.submit(() -> {
                    if (tls) return ListenerTlsCheck.check("127.0.0.1", listener.getLocalPort(), "localhost", ip -> true, session);
                    return ListenerArrivalCheck.received("127.0.0.1", listener.getLocalPort(), "localhost", session);
                });
                assertTrue(request.await(2, TimeUnit.SECONDS));
                session.cancel();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> checking.get(1, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof InterruptedIOException);
                release.countDown(); peer.get(1, TimeUnit.SECONDS);
            } finally { release.countDown(); workers.shutdownNow(); }
        }
    }

    @Test public void cancellationStopsBeforeFollowingDnsAddresses() {
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        AtomicInteger probes = new AtomicInteger();
        assertThrows(InterruptedIOException.class, () -> ListenerArrivalCheck.domainResult(
                new ListenerSettings("127.0.0.1", "::1", 80, 443), "ads.example.test",
                domain -> new String[]{"127.0.0.1", "::1"},
                (ip, port) -> { probes.incrementAndGet(); session.cancel(); return true; }, ip -> true, session));
        assertEquals(1, probes.get());
    }

    @Test public void cancellationDuringLocalAddressObservationIsNotReportedAsTlsFailure() {
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        assertThrows(InterruptedIOException.class, () -> ListenerTlsCheck.check("127.0.0.1", 443, "localhost",
                ip -> { session.cancel(); return false; }, session));
    }

    @Test public void cancelStopsDnsWaitButDoesNotSpawnMoreUnfinishedResolvers() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch resolverFinished = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        try {
            Future<?> waiting = worker.submit(() -> ListenerArrivalCheck.resolveSystem(session, () -> {
                resolutions.incrementAndGet(); entered.countDown();
                awaitIgnoringInterrupts(release);
                resolverFinished.countDown();
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            session.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> waiting.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedIOException);
            for (int attempt = 0; attempt < 10; attempt++) {
                assertThrows(IOException.class, () -> ListenerArrivalCheck.resolveSystem(new ListenerDiagnosticSession(), () -> {
                    resolutions.incrementAndGet(); return new InetAddress[0];
                }));
            }
            assertEquals(1, resolutions.get());
        } finally {
            release.countDown();
            assertTrue(resolverFinished.await(1, TimeUnit.SECONDS));
            worker.shutdownNow();
        }
    }

    @Test public void cancellingOneSessionDoesNotCancelAnotherActualReceipt() throws Exception {
        ListenerDiagnosticSession cancelled = new ListenerDiagnosticSession();
        Socket owned = new Socket(); cancelled.register(owned); cancelled.cancel();
        ExecutorService peer = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<?> served = peer.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    String request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    String nonce = request.substring(request.indexOf("/adaway-listener-probe/") + 23, request.indexOf(" HTTP/"));
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nX-AdAway-Listener-Probe: " + nonce
                            + "\r\n\r\nadaway-listener:" + nonce).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException error) { throw new AssertionError(error); }
            });
            assertTrue(ListenerArrivalCheck.received("127.0.0.1", listener.getLocalPort(), "localhost", new ListenerDiagnosticSession()));
            served.get(1, TimeUnit.SECONDS);
            assertTrue(owned.isClosed());
        } finally { peer.shutdownNow(); }
    }

    @Test public void sharedOperationCanFinishAfterCancelledWaitingWithoutPublishingProgress() throws Exception {
        ListenerDiagnosticSession session = new ListenerDiagnosticSession();
        CompletableFuture<String> underlying = new CompletableFuture<>();
        session.cancel();
        assertThrows(InterruptedIOException.class, () -> session.await(underlying, 2000, "timeout"));
        assertFalse(underlying.isCancelled());
        underlying.complete("late");
        assertEquals("late", underlying.get());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (latch.getCount() != 0) {
                try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out"); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
