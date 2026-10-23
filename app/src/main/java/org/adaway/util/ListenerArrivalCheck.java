package org.adaway.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/** Explicit local receipt check; no public-origin connection or TLS trust bypass. */
public final class ListenerArrivalCheck {
    public interface Resolver { String[] resolve(String domain) throws IOException; }
    public interface Prover { boolean received(String address, int port) throws IOException; }
    public interface LocalAddresses { boolean contains(String address) throws IOException; }
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "listener-check-dns"); thread.setDaemon(true); return thread; });

    private ListenerArrivalCheck() { }

    public static String normalizeDomain(String text) {
        String domain = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (domain.length() > 253 || domain.indexOf('.') < 0 || ListenerSettings.isLiteral(domain))
            throw new IllegalArgumentException("请输入完整测试域名，不含 URL、路径或 IP。");
        for (String label : domain.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                throw new IllegalArgumentException("测试域名仅支持 ASCII / xn-- 格式，不含空白、端口或路径。");
        return domain;
    }

    public static String[] resolveSystem(String domain) throws IOException {
        return resolveSystem(domain, new ListenerDiagnosticSession());
    }

    public static String[] resolveSystem(String domain, ListenerDiagnosticSession session) throws IOException {
        String normalized = normalizeDomain(domain);
        return resolveSystem(session, () -> InetAddress.getAllByName(normalized + "."));
    }

    static String[] resolveSystem(ListenerDiagnosticSession session, Callable<InetAddress[]> resolver) throws IOException {
        session.checkCancelled();
        Future<InetAddress[]> pending;
        try { pending = DNS.submit(resolver); }
        catch (java.util.concurrent.RejectedExecutionException exception) { throw new IOException("上次系统 DNS 查询仍未结束，请稍后重试", exception); }
        try {
            InetAddress[] addresses = session.await(pending, 2000, "系统 DNS 未返回有效结果（2 秒预算）");
            return java.util.Arrays.stream(addresses).limit(16).map(InetAddress::getHostAddress).toArray(String[]::new);
        } finally { pending.cancel(true); }
    }

    public static boolean matches(ListenerSettings settings, String address) throws IOException {
        InetAddress value = InetAddress.getByName(address);
        boolean ipv4 = value.getAddress().length == 4;
        String listener = ipv4 ? settings.ipv4 : settings.ipv6;
        return !listener.isEmpty() && (listener.equals(ipv4 ? "0.0.0.0" : "::") || value.equals(InetAddress.getByName(listener)));
    }

    /** DNS is observed separately. Only a matching, locally assigned address is ever contacted. */
    public static String domainResult(ListenerSettings settings, String input, Resolver resolver, Prover prover, LocalAddresses local) {
        try { return domainResult(settings, input, resolver, prover, local, new ListenerDiagnosticSession()); }
        catch (IOException exception) { return "检测未完成：" + exception.getMessage(); }
    }

    public static String domainResult(ListenerSettings settings, String input, Resolver resolver, Prover prover,
            LocalAddresses local, ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        final String domain;
        try { domain = normalizeDomain(input); }
        catch (IllegalArgumentException exception) { return exception.getMessage(); }
        try {
            String[] answers = resolver.resolve(domain);
            session.checkCancelled();
            if (answers.length == 0) return "DNS 没有 A/AAAA 应答；没有发送连接。";
            List<String> detail = new ArrayList<>();
            for (int index = 0; index < Math.min(answers.length, 16); index++) {
                session.checkCancelled();
                String address = answers[index];
                if (!ListenerSettings.isLiteral(address)) continue;
                detail.add(answerResult(settings, address, prover, local, session));
            }
            session.checkCancelled();
            return String.join("；", detail);
        } catch (IOException exception) {
            session.checkCancelled();
            return "检测未完成：" + exception.getMessage();
        }
    }

    private static String answerResult(ListenerSettings settings, String address, Prover prover, LocalAddresses local,
            ListenerDiagnosticSession session) throws IOException {
        boolean assigned = matches(settings, address) && local.contains(address);
        session.checkCancelled();
        if (!assigned) return address + "：未指向当前本机监听，未连接";
        boolean received;
        try { received = prover.received(address, settings.http); }
        catch (IOException exception) { session.checkCancelled(); received = false; }
        session.checkCancelled();
        return address + (received ? "：DNS 已指向，HTTP 请求已到达 AdAway" : "：DNS 已指向，但未收到 AdAway 回执")
                + "（HTTP 端口 " + settings.http + "）";
    }

    public static boolean validReceipt(String response, String nonce) {
        if (nonce == null || !nonce.matches("[0-9a-f]{32}")) return false;
        int boundary = response.indexOf("\r\n\r\n");
        if (boundary < 0 || !response.startsWith("HTTP/1.1 200 ")) return false;
        String headers = response.substring(0, boundary).toLowerCase(Locale.ROOT);
        return java.util.Arrays.asList(headers.split("\r\n")).contains("x-adaway-listener-probe: " + nonce)
                && response.substring(boundary + 4).equals("adaway-listener:" + nonce);
    }

    public static boolean received(String address, int port) throws IOException {
        return received(address, port, "localhost");
    }

    public static boolean received(String address, int port, String host) throws IOException {
        return received(address, port, host, new ListenerDiagnosticSession());
    }

    public static boolean received(String address, int port, String host, ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        if (!ListenerSettings.isLiteral(address)) throw new IOException("拒绝非字面 IP 连接");
        String requestHost = host.equals("localhost") ? host : normalizeDomain(host);
        try (Socket socket = new Socket()) {
            ListenerDiagnosticSession.Registration registered = session.register(socket);
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(address), port), 800);
                return exchange(socket, requestHost, session);
            } catch (IOException failure) { session.checkCancelled(); throw failure; }
            finally { registered.close(); }
        }
    }

    static boolean exchange(Socket socket, String requestHost) throws IOException {
        return exchange(socket, requestHost, new ListenerDiagnosticSession());
    }

    static boolean exchange(Socket socket, String requestHost, ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        byte[] random = new byte[16]; RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder(32);
        for (byte value : random) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        String nonce = token.toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        socket.getOutputStream().write(("GET /adaway-listener-probe/" + nonce + " HTTP/1.1\r\nHost: " + requestHost + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (response.size() < 4096) {
            session.checkCancelled();
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) return false;
            socket.setSoTimeout((int)Math.max(1, remaining));
            int count = socket.getInputStream().read(buffer, 0, Math.min(buffer.length, 4096 - response.size()));
            session.checkCancelled();
            if (count < 0) break;
            response.write(buffer, 0, count);
            if (validReceipt(new String(response.toByteArray(), StandardCharsets.US_ASCII), nonce)) return true;
        }
        return false;
    }
}
