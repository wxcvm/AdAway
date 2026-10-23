package org.adaway.util;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServerStatsTransportTest {
    private static final String BODY = "{\"uptime_seconds\":1,\"total_requests\":4}";
    private static final String RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: " + BODY.length() + "\r\n\r\n" + BODY;

    @Test public void failedIpv6ToyboxContinuesThroughStandaloneNcToIpv4() {
        List<String> tried = new ArrayList<>();
        JSONObject actual = WebServerStats.getJson("/internal-stats", 8686, new WebServerStats.LocalTransport() {
            @Override public String nc(String executable, String applet, String host, int port, String request, long timeout) {
                tried.add(host);
                assertEquals(8686, port);
                assertTrue(request.startsWith("GET /internal-stats HTTP/1.1\r\n"));
                if (!executable.endsWith("toybox")) assertNull(applet);
                return host.equals("127.0.0.1") ? RESPONSE : null;
            }
            @Override public String http(int port, String path, long timeout) { fail("IPv4 nc should succeed"); return null; }
        });
        assertNotNull(actual);
        assertEquals(4, actual.optInt("total_requests"));
        assertTrue(tried.contains("::1"));
        assertTrue(tried.contains("::ffff:127.0.0.1"));
        assertEquals("127.0.0.1", tried.get(tried.size() - 1));
    }

    @Test public void allNineNcFailuresReachHttpInsteadOfThrowingArrayBounds() {
        int[] count = {0};
        JSONObject actual = WebServerStats.getJson("/internal-qlog?offset=0&limit=300", 8686, new WebServerStats.LocalTransport() {
            @Override public String nc(String executable, String applet, String host, int port, String request, long timeout) {
                count[0]++;
                return null;
            }
            @Override public String http(int port, String path, long timeout) {
                assertEquals(9, count[0]);
                assertTrue(path.startsWith("/internal-qlog?"));
                assertTrue(timeout > 0 && timeout <= 4000);
                return BODY;
            }
        });
        assertNotNull(actual);
        assertEquals(9, count[0]);
    }

    @Test public void rawHttpErrorsAndTruncatedHeadersAreNotSuccessfulBodies() {
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 500 Error\r\n\r\n" + BODY));
        assertNull(WebServerNet.bodyFromResponse(BODY));
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\n"));
        assertEquals(BODY, WebServerNet.bodyFromResponse(RESPONSE));
    }

    @Test public void declaredByteLengthMustBeCompleteUniqueBoundedAndNotChunked() {
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\n\r\n{}"));
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\n\r\n{}"));
        for (String length : new String[]{"-1", "+2", "two", "1048577", "99999999"}) {
            assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nContent-Length: " + length + "\r\n\r\n{}"));
        }
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}"));
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}"));
        String unicode = "{\"host\":\"例子.test\"}";
        assertEquals(unicode, WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nContent-Length: "
                + unicode.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + unicode));
        assertNull(WebServerNet.bodyFromResponse("HTTP/1.1 200 OK\r\nContent-Length: " + unicode.length() + "\r\n\r\n" + unicode));
    }

    @Test public void truncatedNcImmediatelyUsesHttpWithoutRepeatingTheSameBrokenNc() {
        int[] calls = {0};
        JSONObject actual = WebServerStats.getJson("/internal-stats", 8686, new WebServerStats.LocalTransport() {
            @Override public String nc(String executable, String applet, String host, int port, String request, long timeout) {
                calls[0]++;
                return "HTTP/1.1 200 OK\r\nContent-Length: 8192\r\n\r\n{}";
            }
            @Override public String http(int port, String path, long timeout) {
                assertEquals(1, calls[0]);
                return BODY;
            }
        });
        assertNotNull(actual);
        assertEquals(4, actual.optInt("total_requests"));
    }
}
