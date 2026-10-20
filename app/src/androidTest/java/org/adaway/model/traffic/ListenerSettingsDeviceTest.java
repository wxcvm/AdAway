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
