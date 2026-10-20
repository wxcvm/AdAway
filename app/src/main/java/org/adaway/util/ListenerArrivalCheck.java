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
        Future<InetAddress[]> pending;
        try { pending = DNS.submit(() -> InetAddress.getAllByName(normalizeDomain(domain) + ".")); }
        catch (java.util.concurrent.RejectedExecutionException exception) { throw new IOException("上次系统 DNS 查询仍未结束，请稍后重试", exception); }
        try {
            InetAddress[] addresses = pending.get(2, TimeUnit.SECONDS);
            return java.util.Arrays.stream(addresses).limit(16).map(InetAddress::getHostAddress).toArray(String[]::new);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IOException("检测已取消", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IOException("系统 DNS 未返回有效结果（2 秒预算）", exception);
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
        final String domain;
        try { domain = normalizeDomain(input); }
        catch (IllegalArgumentException exception) { return exception.getMessage(); }
        try {
            String[] answers = resolver.resolve(domain);
            if (answers.length == 0) return "DNS 没有 A/AAAA 应答；没有发送连接。";
            List<String> detail = new ArrayList<>();
            for (int index = 0; index < Math.min(answers.length, 16); index++) {
                String address = answers[index];
                if (!ListenerSettings.isLiteral(address)) continue;
                detail.add(answerResult(settings, address, prover, local));
            }
            return String.join("；", detail);
        } catch (IOException exception) { return "检测未完成：" + exception.getMessage(); }
    }

    private static String answerResult(ListenerSettings settings, String address, Prover prover, LocalAddresses local) throws IOException {
        if (!matches(settings, address) || !local.contains(address)) return address + "：未指向当前本机监听，未连接";
        boolean received;
        try { received = prover.received(address, settings.http); }
        catch (IOException exception) { received = false; }
        return address + (received ? "：DNS 已指向，HTTP 请求已到达 AdAway" : "：DNS 已指向，但未收到 AdAway 回执");
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
        if (!ListenerSettings.isLiteral(address)) throw new IOException("拒绝非字面 IP 连接");
        String requestHost = host.equals("localhost") ? host : normalizeDomain(host);
        byte[] random = new byte[16]; RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder(32);
        for (byte value : random) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        String nonce = token.toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(address), port), 800);
            socket.getOutputStream().write(("GET /adaway-listener-probe/" + nonce + " HTTP/1.1\r\nHost: " + requestHost + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            while (response.size() < 4096) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) return false;
                socket.setSoTimeout((int)Math.max(1, remaining));
                int count = socket.getInputStream().read(buffer, 0, Math.min(buffer.length, 4096 - response.size()));
                if (count < 0) break;
                response.write(buffer, 0, count);
                if (validReceipt(new String(response.toByteArray(), StandardCharsets.US_ASCII), nonce)) return true;
            }
            return false;
        }
    }
}
