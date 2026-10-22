package org.adaway.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Private ephemeral sockets only; no root, external traffic, or server reconfiguration. */
@RunWith(AndroidJUnit4.class)
public class WebServerStatsDeviceTest {
    @Test public void actualNcStatsFallsBackFromMissingIpv6ListenerToIpv4() throws Exception {
        JSONObject body = new JSONObject().put("uptime_seconds", 1).put("total_requests", 17)
                .put("padding", new String(new char[8192]).replace('\0', 'x'));
        assertIpv4Response("/internal-stats", body, false);
    }

    @Test public void actualNcQueryLogFallsBackFromMissingIpv6ListenerToIpv4() throws Exception {
        JSONObject body = new JSONObject().put("total", 1).put("returned", 1)
                .put("generation", "0123456789abcdef0123456789abcdef");
        assertIpv4Response("/internal-qlog?offset=0&limit=300", body, false);
    }

    @Test public void actualHttpFallbackCanReachAnIpv4OnlyListener() throws Exception {
        assertIpv4Response("/internal-stats", new JSONObject().put("total_requests", 23), true);
    }

    private static void assertIpv4Response(String path, JSONObject body, boolean forceHttp) throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            Future<String> request = server.submit(() -> reply(listener, body.toString()));
            JSONObject actual = forceHttp ? WebServerStats.getJson(path, listener.getLocalPort(),
                    new WebServerStats.LocalTransport() {
                        @Override public String nc(String executable, String applet, String host, int port, String text, long timeout) {
                            return null;
                        }
                        @Override public String http(int port, String target, long timeout) {
                            return WebServerNet.httpGetFirst(WebServerNet.newLocalClient(), WebServerNet.HTTP_HOSTS, port, target, timeout);
                        }
                    }) : WebServerStats.getJson(path, listener.getLocalPort());
            assertNotNull("IPv4-only management response was not reached", actual);
            assertEquals(body.toString(), actual.toString());
            assertTrue(request.get(5, TimeUnit.SECONDS).startsWith("GET " + path + " HTTP/1.1\r\n"));
        } finally {
            server.shutdownNow();
            assertTrue(server.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static String reply(ServerSocket listener, String body) throws Exception {
        try (Socket client = listener.accept()) {
            client.setSoTimeout(3000);
            InputStream input = client.getInputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            int next;
            while (request.size() < 8192 && (next = input.read()) != -1) {
                request.write(next);
                if (request.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) break;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(bytes);
            client.getOutputStream().flush();
            return request.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
