package org.adaway.util;

import androidx.annotation.Nullable;
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

    private WebServerStats() {}

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
     * <p>服务端保留最近 10000 条（QLOG_MAX），offset 从"最新"往回数，所以翻页期间
     * 新请求到达不会让已翻过的页内容漂移。单页上限 1000 条（服务端会夹紧）。</p>
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

    /**
     * 按路径取 JSON：nc 子进程（保留 v4-mapped socket 的真实 uid）优先，OkHttp 兜底。
     * getStats()/getQueryLogPage() 都走这里，保证两者取数栈完全一致。
     */
    @Nullable
    private static JSONObject getJson(String path) {
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: adaway\r\n" +
                "Connection: close\r\n\r\n";
        // 9 组合：3 种 nc 二进制 x 3 种宿主（::1 / v4-mapped / v4）
        String[][] attempts = {
            {"/system/bin/toybox", "nc", "::1"},
            {"/system/bin/nc", "::1"},
            {"nc", "::1"},
            {"/system/bin/toybox", "nc", "::ffff:127.0.0.1"},
            {"/system/bin/toybox", "nc", "127.0.0.1"},
            {"/system/bin/nc", "::ffff:127.0.0.1"},
            {"/system/bin/nc", "127.0.0.1"},
            {"nc", "::ffff:127.0.0.1"},
            {"nc", "127.0.0.1"},
        };
        for (String[] a : attempts) {
            String response = WebServerNet.ncRequest(
                    a[0], a[1], a[2], WebServerUtils.getStatsHttpPort(), request);
            String json = WebServerNet.bodyFromResponse(response);
            if (json == null) continue;
            try {
                return new JSONObject(json);
            } catch (Exception e) {
                Timber.w(e, "Failed to parse nc response from %s (%s)", a[2], path);
            }
        }
        // 终极兜底：OkHttp 双栈直连（与探活同栈，进程存活则必可达）
        try {
            String body = WebServerNet.httpGetFirst(
                    WebServerNet.newLocalClient(),
                    WebServerNet.HTTP_HOSTS,
                    WebServerUtils.getStatsHttpPort(),
                    path);
            if (body != null) {
                return new JSONObject(body);
            }
        } catch (Exception e) {
            Timber.w(e, "Failed to fetch %s (OkHttp)", path);
        }
        return null;
    }
}