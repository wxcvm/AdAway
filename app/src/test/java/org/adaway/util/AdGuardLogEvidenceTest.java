package org.adaway.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class AdGuardLogEvidenceTest {
    private static final String DOMAIN = "ad.doubleclick.net";

    private static JSONObject row(String host, int action, long timestamp) throws Exception {
        return new JSONObject().put("host", host).put("action", action).put("ts", timestamp);
    }

    private static JSONObject page(int total, boolean hasMore, JSONObject... entries) throws Exception {
        JSONArray rows = new JSONArray();
        for (JSONObject entry : entries) rows.put(entry);
        return new JSONObject().put("filterVersion", 1).put("total", total)
                .put("hasMore", hasMore).put("entries", rows);
    }

    @Test public void distinguishesUnavailableFromNoMatchingWebHistory() throws Exception {
        assertTrue(AdGuardLogEvidence.summarize(DOMAIN, null).contains("暂不可读取"));
        assertTrue(AdGuardLogEvidence.summarize(DOMAIN,
                new JSONObject().put("error", "invalid_filter")).contains("未提供完整"));
        String empty = AdGuardLogEvidence.summarize(DOMAIN, page(0, false));
        assertTrue(empty.contains("未找到该域名"));
        assertTrue(empty.contains("非 AdGuard 日志"));
        assertFalse(empty.contains("AdGuard 未拦截"));
    }

    @Test public void countsOnlyExactWebHostsAndTheirActualActions() throws Exception {
        String result = AdGuardLogEvidence.summarize(DOMAIN, page(5, false,
                row("ad.doubleclick.net", 1, 1700000001L),
                row("AD.DOUBLECLICK.NET.", 2, 1700000002L),
                row("not-ad.doubleclick.net", 1, 1700000003L),
                row("another.example", 3, 1700000004L),
                row("ad.doubleclick.net", 0, 1700000005L)));
        assertTrue(result, result.contains("确认该域名 3 条"));
        assertTrue(result, result.contains("阻断 1，放行 1，代理 1，拒绝 0"));
        assertTrue(result, result.contains("2023-11-14T22:13:25Z"));
        assertTrue(result, result.contains("不能证明由 AdGuard 转发"));
    }

    @Test public void partialQueryNeverClaimsAllHistoryWasChecked() throws Exception {
        String result = AdGuardLogEvidence.summarize(DOMAIN, page(152, true,
                row("sub.ad.doubleclick.net", 1, 1700000000L)));
        assertTrue(result, result.contains("没有确认域名完全一致"));
        assertTrue(result, result.contains("仍有未读取的关键词记录"));
        assertFalse(result.contains("未找到该域名的关键词记录"));
    }

    @Test public void ignoresLegacyResponsesWithoutServerSideFilter() throws Exception {
        JSONObject legacy = new JSONObject().put("total", 0).put("entries", new JSONArray());
        assertTrue(AdGuardLogEvidence.summarize(DOMAIN, legacy).contains("未提供完整"));
    }
}
