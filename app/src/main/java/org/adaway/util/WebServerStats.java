package org.adaway.util;

import androidx.annotation.Nullable;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import timber.log.Timber;

/**
 * AdBlock 内置 web server 统计快照（/internal-stats）拉取与解析。
 *
 * 历史踩坑（务必保留）：
 *  1. 必须使用 toybox nc 子进程而非 Java Socket：Java 会把 v4-mapped
 *     地址折叠回纯 IPv4，导致 /proc/net/tcp 中 uid 被某些内核清零，
 *     per-app 统计失效。nc 子进程继承 app uid，内核在 tcp6 表中保留真实 uid。
 *  2. 目标地址 ::1（IPv6 loopback）优先——本机实测部分 ROM 残留 DNAT
 *     规则劫持 127.0.0.1:80（-> 127.0.0.1:12121），v4 直连 refused；
 *     ::1 不受影响。其次 ::ffff:127.0.0.1（v4-mapped，保留 uid），最后纯 IPv4。
 *  3. 响应体可能超过 4KB（history+daily+apps），必须 ByteArrayOutputStream
 *     累积读取，避免截断导致 JSON 解析失败。
 */
public final class WebServerStats {
    private static final String SYSTEM_NC = "/system/bin/nc";

    private WebServerStats() {}

    /** Read the same local stats endpoint on an explicit port (including isolated fixtures). */
    @Nullable
    public static JSONObject getStats(int port) {
        return getJson("/internal-stats", port);
    }

    /**
     * @return 解析后的 JSONObject；webserver 未运行或响应不可解析时返回 null。
     */
    @Nullable
    public static JSONObject getStats() {
        return getJson("/internal-stats");
    }

    /**
     * 分页读取服务端的明细日志。
     *
     * <p>服务端保留最近 10000 条（QLOG_MAX），offset 从"最新"往回数；连续浏览
     * 必须使用下面带 generation/anchor 的重载固定历史，否则新请求会移动 offset。
     * 单页最多 1000 条，实际返回还受既有 JSON 字节预算约束。</p>
     *
     * @param offset 跳过最新的多少条。
     * @param limit  本次最多取多少条。
     * @return {"offset","limit","total","entries":[…]}；不可达时为 null。
     */
    @Nullable
    public static JSONObject getQueryLogPage(int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = Math.max(1, limit);
        return getJson("/internal-qlog?offset=" + off + "&limit=" + lim);
    }

    @Nullable
    public static JSONObject getQueryLogPage(int offset, int limit, String generation, String anchor) {
        String cursor = "";
        if (generation != null) {
            if (!generation.matches("[0-9a-f]{32}") || anchor == null || !anchor.matches("[0-9]{1,20}"))
                throw new IllegalArgumentException("Invalid query log cursor");
            cursor = "&generation=" + generation + "&anchor=" + anchor;
        }
        return getJson("/internal-qlog?offset=" + Math.max(0, offset) + "&limit=" + Math.min(1000, Math.max(1, limit)) + cursor);
    }

    /** V1 always requires server-side full retained-history filtering, even with empty conditions. */
    @Nullable
    public static JSONObject getFilteredQueryLogPage(int offset, int limit, String generation, String anchor,
            String queryKey, Integer action, Integer family, Integer type, Integer uid, String query) {
        return getJson(QueryLogRequest.path(offset, limit, generation, anchor, queryKey,
                action, family, type, uid, query));
    }

    /**
     * 按路径取 JSON：nc 子进程（保留 v4-mapped socket 的真实 uid）优先，OkHttp 兜底。
     * getStats()/getQueryLogPage() 都走这里，保证两者取数栈完全一致。
     */
    @Nullable
    private static JSONObject getJson(String path) {
        return getJson(path, WebServerUtils.getStatsHttpPort());
    }

    @Nullable
    static JSONObject getJson(String path, int port) {
        return getJson(path, port, new LocalTransport() {
            @Override public String nc(String executable, String applet, String host, int port, String request, long timeoutMs) {
                return WebServerNet.ncRequest(executable, applet, host, port, request, timeoutMs);
            }
            @Override public String http(int port, String target, long timeoutMs) {
                return WebServerNet.httpGetFirst(WebServerNet.newLocalClient(), WebServerNet.HTTP_HOSTS, port, target, timeoutMs);
            }
        });
    }

    interface LocalTransport {
        String nc(String executable, String applet, String host, int port, String request, long timeoutMs);
        String http(int port, String path, long timeoutMs);
    }

    @Nullable
    static JSONObject getJson(String path, int port, LocalTransport transport) {
        // One total deadline, not nine independent four-second waits. Reserve
        // the final second for the existing direct HTTP fallback.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: adaway\r\n" +
                "Connection: close\r\n\r\n";
        JSONObject parsed = readNcJson(path, port, transport, request, deadline);
        if (parsed != null) return parsed;
        // A response from nc may be truncated on older ROMs despite exit=0.
        // Re-read through HTTP immediately instead of repeating the same nc bug.
        try {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return null;
            String body = transport.http(port, path, remaining);
            if (body != null) return new JSONObject(body);
        } catch (Exception error) {
            Timber.w(error, "Failed to fetch %s (OkHttp)", path);
        }
        return null;
    }

    @Nullable
    private static JSONObject readNcJson(String path, int port, LocalTransport transport, String request, long deadline) {
        // 9 组合：3 种 nc 二进制 x 3 种宿主（::1 / v4-mapped / v4）
        String[][] attempts = {
            {"/system/bin/toybox", "nc", "::1"},
            {SYSTEM_NC, null, "::1"},
            {"nc", null, "::1"},
            {"/system/bin/toybox", "nc", "::ffff:127.0.0.1"},
            {"/system/bin/toybox", "nc", "127.0.0.1"},
            {SYSTEM_NC, null, "::ffff:127.0.0.1"},
            {SYSTEM_NC, null, "127.0.0.1"},
            {"nc", null, "::ffff:127.0.0.1"},
            {"nc", null, "127.0.0.1"},
        };
        for (String[] a : attempts) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) - 1000;
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return null;
            String response = transport.nc(a[0], a[1], a[2], port, request, Math.min(1000, remaining));
            if (response != null) return parseNcResponse(response, a[2], path);
        }
        return null;
    }

    @Nullable
    private static JSONObject parseNcResponse(String response, String host, String path) {
        String json = WebServerNet.bodyFromResponse(response);
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (Exception error) {
            Timber.w(error, "Failed to parse nc response from %s (%s)", host, path);
            return null;
        }
    }
}
