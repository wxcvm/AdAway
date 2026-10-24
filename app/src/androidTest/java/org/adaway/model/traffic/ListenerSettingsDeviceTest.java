package org.adaway.model.traffic;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.adaway.util.ListenerSettings;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ListenerSettingsDeviceTest {
    @Test public void graphicsPathNativeConversionWorksAcrossApiLevels() {
        org.adaway.util.NativeAbiDeviceTest.assertGraphicsPathSupport();
    }
    @Test public void managementCleartextPolicyPermitsOnlyBothLoopbackFamilies() {
        android.security.NetworkSecurityPolicy policy = android.security.NetworkSecurityPolicy.getInstance();
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"));
        assertTrue(policy.isCleartextTrafficPermitted("::1"));
        assertFalse(policy.isCleartextTrafficPermitted("198.51.100.1"));
    }
    @Test public void delayedColdStartWaitsForRealBothFamilyListeners() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "::1"}) {
            InetAddress address = InetAddress.getByName(ip);
            try (ServerSocket listener = new ServerSocket()) {
                long start = android.os.SystemClock.elapsedRealtime();
                assertTrue("Cold listener timed out for " + ip, org.adaway.util.ListenerStartupWait.await(() -> {
                    if (android.os.SystemClock.elapsedRealtime() - start < 1300) return false;
                    try {
                        if (!listener.isBound()) listener.bind(new java.net.InetSocketAddress(address, 0));
                        try (java.net.Socket client = new java.net.Socket()) {
                            client.connect(new java.net.InetSocketAddress(address, listener.getLocalPort()), 100);
                            return true;
                        }
                    } catch (java.io.IOException error) { return false; }
                }, 3500));
            }
        }
    }
    @Test public void startupCreationPreferencePreservesLegacyOwnershipAndExplicitDisable() {
        Context application = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = application.getSharedPreferences("listener-startup-fixture", Context.MODE_PRIVATE);
        Context isolated = new android.content.ContextWrapper(application) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        try {
            assertTrue(prefs.edit().clear().commit());
            assertFalse(org.adaway.util.WebServerUtils.isListenerAutoCreate(isolated));
            assertTrue(prefs.edit().putStringSet("managed_listener_addresses", java.util.Collections.singleton("fd6b:2a91:84f0::73")).commit());
            assertTrue(org.adaway.util.WebServerUtils.isListenerAutoCreate(isolated));
            assertTrue(prefs.edit().putBoolean("listener_auto_create", false).commit());
            assertFalse(org.adaway.util.WebServerUtils.isListenerAutoCreate(isolated));
            assertTrue(org.adaway.util.WebServerUtils.getManagedListenerAddresses(isolated).contains("fd6b:2a91:84f0::73"));
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }

    @Test public void oldResponseShortcutPreferenceCannotRemainEnabled() {
        Context application = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = application.getSharedPreferences("retired-response-shortcut-fixture", Context.MODE_PRIVATE);
        Context isolated = new android.content.ContextWrapper(application) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        try {
            assertTrue(prefs.edit().clear().putBoolean("block_reply_cache_reuse", true).commit());
            assertFalse(org.adaway.util.WebServerUtils.isCacheReuseEnabled(isolated));
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }
    @Test public void actualBundledReceiptHandlerAndJavaClientAgreeForBothFamilies() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (String ip : new String[]{"127.0.0.1", "::1"}) {
            int port;
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName(ip))) { port = reservation.getLocalPort(); }
            Process process = new ProcessBuilder(context.getApplicationInfo().nativeLibraryDir + "/liblistener_arrival_probe.so", ip, "" + port).redirectErrorStream(true).start();
            try {
                boolean received = false;
                for (int attempt = 0; attempt < 20 && !received; attempt++) {
                    assertTrue("Receipt fixture exited", process.isAlive());
                    try { received = org.adaway.util.ListenerArrivalCheck.received(ip, port); }
                    catch (java.io.IOException ignored) { Thread.sleep(50); }
                }
                assertTrue("Actual native handler did not return a fresh receipt for " + ip, received);
            } finally { process.destroy(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
    }
    @Test public void createdLoopbackAliasIsVisibleAndCanBindBothResponsePorts() throws Exception {
        String alias = "fd6b:2a91:84f0::73";
        assertTrue(new org.adaway.util.RootListenerAddresses().exists(alias));
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getByName(alias));
             ServerSocket second = new ServerSocket(0, 1, InetAddress.getByName(alias))) {
            int http = first.getLocalPort(), https = second.getLocalPort();
            first.close(); second.close();
            Process process = probe("--listen-ipv4", "-", "--listen-ipv6", alias,
                    "--http-port", "" + http, "--https-port", "" + https);
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(com.google.common.io.ByteStreams.toByteArray(process.getInputStream()), StandardCharsets.UTF_8).trim();
            assertEquals(output, 0, process.exitValue());
            assertEquals(output, 0, new JSONObject(output).getInt("http6"));
            assertEquals(output, 0, new JSONObject(output).getInt("https6"));
        }
    }
    @Test public void savedLegacyAddressAndExplicitPairRebuildWithDifferentSemantics() {
        Context application = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = application.getSharedPreferences("listener-migration-fixture", Context.MODE_PRIVATE);
        Context isolated = new android.content.ContextWrapper(application) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        try {
            assertTrue(prefs.edit().clear().putString("bind_ip", "::1").commit());
            ListenerSettings legacy = org.adaway.util.WebServerUtils.getListenerSettings(isolated);
            assertFalse(legacy.explicitPair);
            assertEquals("", legacy.ipv4);
            assertEquals("::1", legacy.ipv6);
            assertTrue(prefs.edit().putString("listen_ipv4", "127.0.0.2").putString("listen_ipv6", "::1").commit());
            ListenerSettings rebuilt = org.adaway.util.WebServerUtils.getListenerSettings(isolated);
            assertTrue(rebuilt.explicitPair);
            assertTrue(rebuilt.arguments().contains("--listen-ipv4 127.0.0.2 --listen-ipv6 ::1"));
        } finally { assertTrue(prefs.edit().clear().commit()); }
    }

    @Test public void concurrentSavedConfigurationsNeverMixListenerAndPorts() throws Exception {
        Context application = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = application.getSharedPreferences("listener-snapshot-fixture", Context.MODE_PRIVATE);
        Context isolated = new android.content.ContextWrapper(application) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(prefs.edit().clear().putString("listen_ipv4", "127.0.0.2").putString("listen_ipv6", "")
                    .putInt("http_port", 28080).putInt("https_port", 28443).commit());
            java.util.concurrent.Future<?> writer = worker.submit(() -> {
                try { assertTrue(go.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                for (int index = 0; index < 200; index++) {
                    boolean first = index % 2 == 0;
                    prefs.edit().putString("listen_ipv4", first ? "127.0.0.2" : "127.0.0.3")
                            .putInt("http_port", first ? 28080 : 38080).putInt("https_port", first ? 28443 : 38443).apply();
                }
            });
            go.countDown();
            for (int index = 0; index < 500; index++) {
                ListenerSettings observed = org.adaway.util.WebServerUtils.getListenerSettings(isolated);
                boolean first = observed.ipv4.equals("127.0.0.2");
                assertTrue(first || observed.ipv4.equals("127.0.0.3"));
                assertEquals(first ? 28080 : 38080, observed.http);
                assertEquals(first ? 28443 : 38443, observed.https);
                assertEquals("", observed.ipv6);
            }
            writer.get(3, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); assertTrue(prefs.edit().clear().commit()); }
    }
    @Test public void contentSettingsAtomicFailureKeepsLegacyPreferencesAndPublishedFiles() throws Exception {
        Context application = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = application.getSharedPreferences("content-settings-fixture", Context.MODE_PRIVATE);
        File directory = java.nio.file.Files.createTempDirectory(application.getCacheDir().toPath(), "content-settings-").toFile();
        Context isolated = new android.content.ContextWrapper(application) {
            @Override public File getFilesDir() { return directory; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
        File resource = org.adaway.util.WebServerUtils.getResourcePath(isolated).toFile();
        File target = new File(resource, org.adaway.util.ContentResponseSettings.FILE_NAME);
        File marker = new File(target, "previous");
        try {
            assertTrue(prefs.edit().clear().putBoolean("block_reply_images", true).putBoolean("block_reply_cache_reuse", true).commit());
            org.adaway.util.ContentResponseSettings settings = org.adaway.util.WebServerUtils.loadContentResponseSettings(isolated);
            assertTrue(settings.enabled("images"));
            assertTrue(target.mkdirs());
            java.nio.file.Files.write(marker.toPath(), new byte[]{42});
            assertThrows(java.io.IOException.class, () -> org.adaway.util.WebServerUtils.saveContentResponseSettings(isolated, settings.with("images", false)));
            assertTrue(prefs.getBoolean("block_reply_images", false));
            assertEquals(42, java.nio.file.Files.readAllBytes(marker.toPath())[0]);
            assertTrue(marker.delete()); assertTrue(target.delete());
            org.adaway.util.ContentResponseSettings.Saved saved = settings.with("images", false).publish(target, generation -> false);
            assertFalse(saved.confirmed);
            assertFalse(org.adaway.util.WebServerUtils.loadContentResponseSettings(isolated).enabled("images"));
            assertEquals(0, new JSONObject(new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)).getInt("cache_reuse_enabled"));
            File rules = new File(resource, org.adaway.util.ContentResponseRules.FILE_NAME);
            org.adaway.util.WebServerUtils.saveContentResponseRules(isolated, "example.com=404");
            assertThrows(java.io.IOException.class, () -> org.adaway.util.WebServerUtils.saveContentResponseRules(isolated, "example.com/path=204"));
            assertEquals("example.com = 404\n", org.adaway.util.WebServerUtils.loadContentResponseRules(isolated));
            assertTrue(rules.delete());
        } finally {
            marker.delete(); target.delete(); resource.delete(); directory.delete();
            assertTrue(prefs.edit().clear().commit());
        }
    }

    @Test public void actualIpv4OnlyControlRequiresCurrentGenerationAndRejectsLegacyAcknowledgement() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        for (String acknowledged : new String[]{token, "11111111111111111111111111111111", ""}) {
            try (ControlAckFixture fixture = new ControlAckFixture(acknowledged)) {
                assertEquals(token.equals(acknowledged), org.adaway.util.WebServerControl.reloadConfig(token, fixture.listener.getLocalPort()));
                assertTrue("Production transport did not reach IPv4-only control", fixture.correct.get() > 0);
            }
        }
    }

    private static final class ControlAckFixture implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicInteger correct = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.Future<?> served;
        ControlAckFixture(String token) throws Exception {
            listener.setSoTimeout(100);
            served = worker.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                int count = 0;
                while (running.get() && count < 8 && System.nanoTime() < deadline) {
                    try (java.net.Socket client = listener.accept()) {
                        count++;
                        client.setSoTimeout(1500);
                        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        assertEquals("POST /control HTTP/1.1", input.readLine());
                        String line; int length = 0;
                        while ((line = input.readLine()) != null && !line.isEmpty())
                            if (line.startsWith("Content-Length:")) length = Integer.parseInt(line.substring(15).trim());
                        assertTrue(length > 0 && length < 128);
                        char[] body = new char[length]; int read = 0;
                        while (read < length) {
                            int next = input.read(body, read, length - read);
                            if (next < 0) throw new AssertionError("Truncated control request");
                            read += next;
                        }
                        assertEquals("cmd=reload_config", new String(body));
                        correct.incrementAndGet();
                        String response = "OK: block config reloaded";
                        String header = token.isEmpty() ? "" : "X-AdAway-Config-Generation: " + token + "\r\n";
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + header + "Content-Length: " + response.length()
                                + "\r\nConnection: close\r\n\r\n" + response).getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                    } catch (java.net.SocketTimeoutException timeout) {
                        // Bounded accept lets close stop retries without blocking the device suite.
                    } catch (java.io.IOException error) { throw new AssertionError(error); }
                }
            });
        }
        @Override public void close() throws Exception {
            running.set(false);
            try { served.get(2, TimeUnit.SECONDS); }
            finally { listener.close(); worker.shutdownNow(); }
        }
    }

    private Process probe(String... arguments) throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        String directory=context.getApplicationInfo().nativeLibraryDir;
        ArrayList<String> command=new ArrayList<>();
        command.add(directory+"/liblistener_probe.so");
        command.add("--check-listen"); command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH",directory);
        return builder.start();
    }
    @Test public void sharedNativeStartupProbeDetectsBothFamiliesWithoutRoot() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        File resource=new File(context.getCacheDir(),"listener-probe-must-not-create");
        assertFalse(resource.exists());
        for (String ip : new String[]{"127.0.0.1","::1"}) {
            try (ServerSocket occupied=new ServerSocket(0,1,InetAddress.getByName(ip));
                 ServerSocket other=new ServerSocket(0,1,InetAddress.getByName(ip))) {
                int http=occupied.getLocalPort(),https=other.getLocalPort();
                other.close();
                Process process=probe("--bind-ip",ip,"--http-port",""+http,"--https-port",""+https);
                assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                String output=new String(com.google.common.io.ByteStreams.toByteArray(process.getInputStream()),StandardCharsets.UTF_8).trim();
                JSONObject report=new JSONObject(output);
                assertEquals(output,3,process.exitValue());
                assertEquals(output,1,report.getInt("http"));
                assertEquals(output,0,report.getInt("https"));
            }
        }
        Process invalid=probe("--bind-ip","localhost");
        assertTrue(invalid.waitFor(10,TimeUnit.SECONDS));
        assertEquals(2,invalid.exitValue());
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
            Process dual = probe("--listen-ipv4", "127.0.0.1", "--listen-ipv6", "::1",
                    "--http-port", "" + occupied.getLocalPort(), "--https-port", "65535");
            assertTrue(dual.waitFor(10, TimeUnit.SECONDS));
            String output = new String(com.google.common.io.ByteStreams.toByteArray(dual.getInputStream()), StandardCharsets.UTF_8).trim();
            assertEquals(output, 3, dual.exitValue());
            assertEquals(output, 1, new JSONObject(output).getInt("http6"));
        }
        assertFalse(resource.exists());
        assertFalse(ListenerSettings.isLiteral("127.0.0.1; touch /tmp/unwanted"));
    }
}
