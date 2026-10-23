package org.adaway.util;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Private ephemeral sockets only; no root, external traffic, or server reconfiguration. */
@RunWith(AndroidJUnit4.class)
public class WebServerStatsDeviceTest {
    private static final int REAL_NC = 0;
    private static final int HTTP_ONLY = 1;
    private static final int TRUNCATED_NC = 2;
    @Test public void actualNcStatsFallsBackFromMissingIpv6ListenerToIpv4() throws Exception {
        JSONObject body = new JSONObject().put("uptime_seconds", 1).put("total_requests", 17)
                .put("padding", new String(new char[8192]).replace('\0', 'x'));
        assertIpv4Response("/internal-stats", body, REAL_NC);
    }

    @Test public void actualNcQueryLogFallsBackFromMissingIpv6ListenerToIpv4() throws Exception {
        JSONObject body = new JSONObject().put("total", 1).put("returned", 1)
                .put("generation", "0123456789abcdef0123456789abcdef");
        assertIpv4Response("/internal-qlog?offset=0&limit=300", body, REAL_NC);
    }

    @Test public void actualHttpFallbackCanReachAnIpv4OnlyListener() throws Exception {
        assertIpv4Response("/internal-stats", new JSONObject().put("total_requests", 23), HTTP_ONLY);
    }

    @Test public void truncatedNcImmediatelyRetriesThroughActualHttpWithFullLargeBody() throws Exception {
        JSONObject body = new JSONObject().put("total_requests", 29)
                .put("padding", new String(new char[8192]).replace('\0', 'x'));
        assertIpv4Response("/internal-stats", body, TRUNCATED_NC);
    }

    private static void assertIpv4Response(String path, JSONObject body, int mode) throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean();
        try (ServerSocket listener = new ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(200);
            // A real management server accepts retries. Keep this fixture bounded,
            // but do not consume its only connection before an HTTP fallback.
            Future<List<String>> requests = server.submit(() -> serve(listener, body.toString(), finished));
            int[] attempts = {0};
            JSONObject actual = mode != REAL_NC ? WebServerStats.getJson(path, listener.getLocalPort(),
                    new WebServerStats.LocalTransport() {
                        @Override public String nc(String executable, String applet, String host, int port, String text, long timeout) {
                            attempts[0]++;
                            if (mode == TRUNCATED_NC) return "HTTP/1.1 200 OK\r\nContent-Length: 8192\r\n\r\n{}";
                            return null;
                        }
                        @Override public String http(int port, String target, long timeout) {
                            return WebServerNet.httpGetFirst(WebServerNet.newLocalClient(), WebServerNet.HTTP_HOSTS, port, target, timeout);
                        }
                    }) : WebServerStats.getJson(path, listener.getLocalPort());
            assertNotNull("IPv4-only management response was not reached", actual);
            assertEquals(body.toString(), actual.toString());
            finished.set(true);
            List<String> received = requests.get(5, TimeUnit.SECONDS);
            assertTrue(!received.isEmpty() && received.size() <= 12);
            for (String request : received) assertTrue(request.startsWith("GET " + path + " HTTP/1.1\r\n"));
            if (mode == TRUNCATED_NC) assertEquals(1, attempts[0]);
        } finally {
            finished.set(true);
            server.shutdownNow();
            assertTrue(server.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static List<String> serve(ServerSocket listener, String body, AtomicBoolean finished) throws Exception {
        List<String> requests = new ArrayList<>();
        while (!finished.get() && requests.size() < 12) {
            try {
                requests.add(reply(listener, body));
            } catch (SocketTimeoutException idle) {
                // Short accept timeout observes completion without sleeps.
            }
        }
        return requests;
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
