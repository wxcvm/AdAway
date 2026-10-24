package org.adaway.util;

import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ListenerArrivalCheckTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static String response(String nonce) { return "HTTP/1.1 200 OK\r\nX-AdAway-Listener-Probe: " + nonce + "\r\n\r\nadaway-listener:" + nonce; }

    @Test public void tcpSuccessGenericPageRedirectAndStaleNonceAreNotReceipts() {
        assertTrue(ListenerArrivalCheck.validReceipt(response(NONCE), NONCE));
        assertFalse(ListenerArrivalCheck.validReceipt(response("ffffffffffffffffffffffffffffffff"), NONCE));
        assertFalse(ListenerArrivalCheck.validReceipt("HTTP/1.1 200 OK\r\n\r\ngeneric block page", NONCE));
        assertFalse(ListenerArrivalCheck.validReceipt(response(NONCE).replace("200 OK", "302 Found"), NONCE));
        assertFalse(ListenerArrivalCheck.validReceipt(response(NONCE) + "extra", NONCE));
    }

    @Test public void publicOrMismatchingDnsAnswersNeverCauseAnOriginConnection() {
        AtomicInteger connections = new AtomicInteger();
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.2", "::1",80,443), "ad.doubleclick.net",
                domain -> new String[]{"203.0.113.9", "127.0.0.1"},
                (address,port) -> { connections.incrementAndGet(); return true; }, address -> true);
        assertEquals(0, connections.get()); assertTrue(result.contains("未指向"));
    }

    @Test public void dnsMatchAndActualArrivalAreReportedSeparately() {
        ListenerSettings settings = new ListenerSettings("127.0.0.2", "::1",80,443);
        String result = ListenerArrivalCheck.domainResult(settings,"ADS.Example.COM.", domain -> new String[]{"127.0.0.2", "::1"},
                (address,port) -> InetAddress.getByName(address).equals(InetAddress.getByName("::1")), address -> true);
        assertTrue(result.contains("DNS 已指向，但未收到")); assertTrue(result.contains("请求已到达"));
        assertTrue(ListenerArrivalCheck.domainResult(settings,"ads.example.com", domain -> { throw new IOException("DNS failed"); },
                (address,port) -> true, address -> true).contains("DNS failed"));
        assertEquals("ads.example.com", ListenerArrivalCheck.normalizeDomain("ADS.Example.COM."));
    }

    @Test public void rejectsUrlsHeadersAndUnicodeWithoutChangingDnsMeaning() {
        for (String domain : new String[]{"https://ads.example.com", "ads.example.com/path", "ads.example.com:80", "localhost", "127.0.0.1", "ads.example.com\r\nHost: evil", "广告.example.com"})
            assertThrows(domain, IllegalArgumentException.class, () -> ListenerArrivalCheck.normalizeDomain(domain));
    }

    @Test public void customPortReceiptNamesThePortActuallyTested() {
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.2", "::1", 28080, 28443),
                "ads.example.com", domain -> new String[]{"127.0.0.2"},
                (address, port) -> { assertEquals(28080, port); return true; }, address -> true);
        assertTrue(result.contains("HTTP 端口 28080"));
    }

    @Test public void expandedIpv6WildcardMatchesAssignedAddressesAndProbesLoopback() throws Exception {
        ListenerSettings settings = new ListenerSettings("", "0:0:0:0:0:0:0:0", 80, 443);
        assertTrue(ListenerArrivalCheck.matches(settings, "::1"));
        assertTrue(ListenerArrivalCheck.matches(settings, "fd00::73"));
        assertArrayEquals(new String[]{"::1"}, settings.probeAddresses());
        assertArrayEquals(new String[]{"::1"}, settings.diagnosticAddresses());
        ListenerSettings concrete = new ListenerSettings("", "0:0:0:0:0:0:0:1", 80, 443);
        assertTrue(ListenerArrivalCheck.matches(concrete, "::1"));
        assertFalse(ListenerArrivalCheck.matches(concrete, "fd00::73"));
    }

    @Test public void duplicateAndMappedAnswersDoNotConsumeEndpointBudgetOrHideIpv6() {
        String[] answers = new String[20];
        java.util.Arrays.fill(answers, "127.0.0.1");
        answers[17] = "::ffff:127.0.0.1";
        answers[18] = "::1";
        answers[19] = "0:0:0:0:0:0:0:1";
        java.util.List<String> connected = new java.util.ArrayList<>();
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.1", "::1", 80, 443),
                "ads.example.test", domain -> answers,
                (address, port) -> { connected.add(address); return true; }, address -> true);
        assertEquals(2, connected.size());
        assertTrue(result.contains("HTTP 请求已到达"));
    }

    @Test public void sixteenUnmatchedARecordsCannotHideMatchingLocalAaaa() {
        String[] answers = new String[17];
        for (int index = 0; index < 16; index++) answers[index] = "203.0.113." + (index + 1);
        answers[16] = "::1";
        AtomicInteger connections = new AtomicInteger();
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.1", "::1", 80, 443),
                "ads.example.test", domain -> answers,
                (address, port) -> { connections.incrementAndGet(); assertTrue(address.contains(":")); return true; },
                address -> true);
        assertEquals(1, connections.get());
        assertTrue(result.contains("请求已到达"));
        assertTrue(result.contains("未测试，不能判定全部路径"));
    }

    @Test public void wildcardSelectionBalancesFamiliesWithinSixteenConnections() {
        String[] answers = new String[40];
        for (int index = 0; index < 39; index++) answers[index] = "127.0.0." + (index + 1);
        answers[39] = "::1";
        java.util.List<String> connected = new java.util.ArrayList<>();
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("0.0.0.0", "::", 80, 443),
                "ads.example.test", domain -> answers,
                (address, port) -> { connected.add(address); return false; }, address -> true);
        assertEquals(16, connected.size());
        assertTrue(connected.get(1).contains(":"));
        assertTrue(result.contains("最多检测 16 个唯一地址"));
    }

    @Test public void hugeAndInvalidDnsSetsHaveExplicitIncompleteOutcomesWithoutConnections() {
        String[] answers = new String[200];
        java.util.Arrays.fill(answers, "203.0.113.1");
        answers[150] = "::1"; // Beyond the bounded scan; never claim this family failed.
        AtomicInteger connections = new AtomicInteger();
        String result = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.1", "::1", 80, 443),
                "ads.example.test", domain -> answers,
                (address, port) -> { connections.incrementAndGet(); return true; }, address -> true);
        assertEquals(0, connections.get());
        assertTrue(result.contains("128 条候选扫描预算"));
        assertTrue(result.contains("未扫描部分未测试"));
        String invalid = ListenerArrivalCheck.domainResult(new ListenerSettings("127.0.0.1", "::1", 80, 443),
                "ads.example.test", domain -> new String[]{null, "not-an-ip", "[::1]"},
                (address, port) -> { fail("Invalid answers must not connect"); return true; }, address -> true);
        assertTrue(invalid.contains("没有有效的字面 A/AAAA"));
        assertTrue(invalid.contains("已忽略 3 条"));
    }

    @Test public void systemResolverRetainsAaaaAndOneOverflowMarkerWithoutExpandingProbeBudget() throws Exception {
        InetAddress[] addresses = new InetAddress[200];
        java.util.Arrays.fill(addresses, InetAddress.getByName("127.0.0.1"));
        addresses[16] = InetAddress.getByName("::1");
        String[] observed = ListenerArrivalCheck.resolveSystem(new ListenerDiagnosticSession(), () -> addresses);
        assertEquals(129, observed.length);
        assertTrue(observed[16].contains(":"));
    }

    @Test public void actualSocketExchangeRequiresFreshMatchingReceipt() throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1000);
                    String request = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                    String nonce = request.substring(request.indexOf("/adaway-listener-probe/") + 23, request.indexOf(" HTTP/"));
                    client.getOutputStream().write(response(nonce).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException exception) { throw new RuntimeException(exception); }
            });
            responder.setDaemon(true); responder.start();
            assertTrue(ListenerArrivalCheck.received("127.0.0.1",server.getLocalPort()));
            responder.join(2000); assertFalse(responder.isAlive());
        }
    }
}
