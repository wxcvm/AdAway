package org.adaway.util;

import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only comparison with AdAway's retained Web request log.
 * These records do not include AdGuard's DNS or cosmetic-blocking events.
 */
public final class AdGuardLogEvidence {
    private static final int SAMPLE_LIMIT = 100;

    private AdGuardLogEvidence() { }

    /** A user-triggered snapshot taken before sending any domain probes. */
    public static String inspect(String domain) {
        String normalized = ListenerArrivalCheck.normalizeDomain(domain);
        JSONObject page = WebServerStats.getFilteredQueryLogPage(
                0, SAMPLE_LIMIT, null, null, null, null, null, null, null, normalized);
        return summarize(normalized, page);
    }

    /** Interpret source evidence; a missing or partial page is never treated as zero requests. */
    static String summarize(String domain, JSONObject page) {
        if (page == null) {
            return "AdAway Web 日志暂不可读取；不能因此判断 AdGuard 的请求未到达。";
        }
        JSONArray entries = page.optJSONArray("entries");
        int total = page.optInt("total", -1);
        if (page.optInt("filterVersion", 0) != 1 || entries == null || total < 0 ||
                page.has("error")) {
            return "当前服务器未提供完整的 Web 历史筛选结果，请更新服务并重试；不能当作零条请求。";
        }

        int exact = 0, blocked = 0, allowed = 0, proxied = 0, denied = 0;
        long newest = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject row = entries.optJSONObject(i);
            if (row == null) continue;
            String host = row.optString("host", "");
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            // The server's q= filter also matches paths and partial hostnames.
            // Only an exact Host match proves this domain was observed.
            if (!host.equalsIgnoreCase(domain)) continue;
            exact++;
            switch (row.optInt("action", -1)) {
                case 0: proxied++; break;
                case 1: blocked++; break;
                case 2: allowed++; break;
                case 3: denied++; break;
                default: break;
            }
            newest = Math.max(newest, row.optLong("ts", 0));
        }

        StringBuilder report = new StringBuilder("AdAway 已保留的 Web 请求（非 AdGuard 日志）：");
        if (exact > 0) {
            report.append("本次读取的 ").append(entries.length())
                    .append(" 条关键词结果中，确认该域名 ").append(exact)
                    .append(" 条；AdAway 阻断 ").append(blocked)
                    .append("，放行 ").append(allowed)
                    .append("，代理 ").append(proxied)
                    .append("，拒绝 ").append(denied).append("。");
            if (newest > 0) report.append("最近一条：").append(Instant.ofEpochSecond(newest)).append("（UTC）。");
        } else if (total == 0) {
            report.append("当前保留历史中未找到该域名的关键词记录。");
        } else {
            report.append("服务器找到 ").append(total)
                    .append(" 条关键词记录，但本次返回的 ").append(entries.length())
                    .append(" 条里没有确认域名完全一致的记录（可能是路径或相似域名）。");
        }

        if (page.optBoolean("hasMore", false) || total > entries.length())
            report.append("仍有未读取的关键词记录，本次结论不覆盖全部历史。");
        report.append("只表示 AdAway 实际接收的 Web 请求；不能证明由 AdGuard 转发，也不能反推出 AdGuard 的全部 DNS、HTTPS 或元素拦截。");
        return report.toString();
    }
}
