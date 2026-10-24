package org.adaway.util;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.adaway.BuildConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static org.junit.Assert.*;

/** Actual packaged executables on a disposable emulator; no root, route or trust-store changes. */
@RunWith(AndroidJUnit4.class)
public class NativeAbiDeviceTest {
    @Test public void networkPolicyAllowsBothLoopbacksAndKeepsPublicHttpDisabled() {
        android.security.NetworkSecurityPolicy policy = android.security.NetworkSecurityPolicy.getInstance();
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"));
        assertTrue(policy.isCleartextTrafficPermitted("::1"));
        assertFalse(policy.isCleartextTrafficPermitted("198.51.100.1"));
    }

    private static int port(Set<Integer> used) throws Exception {
        for (;;) try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int candidate = socket.getLocalPort();
            if (used.add(candidate)) return candidate;
        }
    }

    @Test public void packagedX86ExecutablesServeRealDualStackImagesAndTrustedTls() throws Exception {
        assertEquals("This job must build real x86 natives, not omit the webserver", "x86_64", BuildConfig.NATIVE_ABI);
        Context context = ApplicationProvider.getApplicationContext();
        File resources = new File(context.getCacheDir(), "native-abi-" + UUID.randomUUID());
        assertTrue(resources.mkdir());
        byte[] image;
        try (InputStream input = context.getAssets().open("img_01.webp")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
            image = output.toByteArray();
        }
        assertEquals(109828, image.length);
        Files.write(new File(resources, "img_01.webp").toPath(), image);
        Files.write(new File(resources, "test.html").toPath(), "native ABI fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File library = new File(context.getApplicationInfo().nativeLibraryDir);
        File captureLog = new File(resources, "tcpdump.log");
        Process capture = new ProcessBuilder(new File(library, "libtcpdump_exec.so").getAbsolutePath(), "--version")
                .redirectErrorStream(true).redirectOutput(captureLog).start();
        try {
            assertTrue("Packaged tcpdump did not exit", capture.waitFor(5, TimeUnit.SECONDS));
            assertEquals("Packaged tcpdump failed to load", 0, capture.exitValue());
        } finally { if (capture.isAlive()) capture.destroyForcibly(); }
        Set<Integer> ports = new HashSet<>();
        int http = port(ports), https = port(ports), stats = port(ports);
        File log = new File(resources, "server.log");
        Process server = new ProcessBuilder(new File(library, "libwebserver_exec.so").getAbsolutePath(),
                "--resources", resources.getAbsolutePath(), "--http-port", Integer.toString(http),
                "--https-port", Integer.toString(https), "--stats-port", Integer.toString(stats),
                "--listen-ipv4", "127.0.0.1", "--listen-ipv6", "::1")
                .redirectErrorStream(true).redirectOutput(log).start();
        OkHttpClient client = WebServerNet.newLocalClient().newBuilder()
                .callTimeout(2, TimeUnit.SECONDS).build();
        try {
            boolean ready = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline && server.isAlive()) {
                try (Response response = client.newCall(new Request.Builder()
                        .url("http://127.0.0.1:" + stats + "/internal-stats").build()).execute()) {
                    if (response.code() == 200) { ready = true; break; }
                } catch (java.io.IOException retry) { Thread.sleep(50); }
            }
            if (!ready) fail("Actual native server failed startup: " + new String(Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            for (String host : new String[]{"127.0.0.1", "[::1]"}) {
                for (int i = 0; i < 2; i++) try (Response response = client.newCall(new Request.Builder()
                        .url("http://" + host + ":" + http + "/tile.png")
                        .header("Host", "ads.native-abi.invalid").build()).execute()) {
                    assertEquals(200, response.code());
                    assertNotNull(response.body());
                    assertEquals("image/webp", response.header("Content-Type"));
                    assertArrayEquals(image, response.body().bytes());
                }
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
            try (InputStream input = Files.newInputStream(new File(resources, "localhost-2410.crt").toPath())) {
                trust.setCertificateEntry("isolated-server", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
            for (String ip : new String[]{"127.0.0.1", "::1"}) {
                ListenerTlsCheck.Result receipt = ListenerTlsCheck.check(ip, https, "localhost", address -> true, tls.getSocketFactory());
                assertEquals(ip + ": native OpenSSL failed encrypted receipt", ListenerTlsCheck.State.RECEIPT, receipt.state);
            }
            try (Response response = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + http + "/control")
                    .post(new FormBody.Builder().add("cmd", "shutdown").build()).build()).execute()) {
                assertEquals("Response port must reject control commands", 403, response.code());
            }
            try (Response response = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + stats + "/control")
                    .header("Origin", "https://ads.native-abi.invalid")
                    .post(new FormBody.Builder().add("cmd", "shutdown").build()).build()).execute()) {
                assertEquals("Web origins must not control the server", 403, response.code());
            }
            try (Response response = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + stats + "/tile.png").build()).execute()) {
                assertEquals("Management port must not serve arbitrary resources", 404, response.code());
            }
            try (Response response = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + stats + "/control")
                    .post(new FormBody.Builder().add("cmd", "shutdown").build()).build()).execute()) {
                assertEquals(200, response.code());
            }
            assertTrue("Actual native server failed normal shutdown", server.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, server.exitValue());
        } finally {
            if (server.isAlive()) { server.destroy(); if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); }
            client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
        }
    }
}
