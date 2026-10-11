package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AdGuardClientGuide 的回归测试：指引可以改，但两条底线不能被改掉。 */
class AdGuardClientGuideTest {

    @Test
    fun everyGuideCarriesTheThreePitfallNotes() {
        val notes = AdGuardClientGuide.guides().flatMap { it.notes }
        // 端口坑：DNS 不带端口
        assertTrue(notes.any { it.contains("不带端口") })
        // 回环坑：127.0.0.1 只在客户端是本机时可用
        assertTrue(notes.any { it.contains("127.0.0.1") } ||
            AdGuardClientGuide.guides().flatMap { it.steps }.any { it.contains("127.0.0.1") })
        // DNS 指向不等于 HTTPS 已过滤
        assertTrue(notes.any { it.contains("不等于") || it.contains("只看") || it.contains("issuer") })
    }

    @Test
    fun noTextClaimsDnsRewriteIsEnough() {
        // 不允许出现"改完 DNS 就完成"这类说法
        val all = AdGuardClientGuide.allNotes()
        assertFalse(all.any { it.contains("就完成了") && !it.contains("只完成") })
        assertTrue(all.any { it.contains("issuer") })
    }

    @Test
    fun valuesUseTheAdvisorOutput() {
        val v4 = AdGuardCoexistence.recommendedBlockingIpv4("192.168.1.10")
        val v6 = AdGuardCoexistence.recommendedBlockingIpv6("192.168.1.10", false)
        val values = AdGuardClientGuide.valuesToCopy(v4, v6, 80, 443).toMap()
        assertEquals("custom_ip", values["blocking_mode"])
        assertEquals("192.168.1.10", values["blocking_ipv4"])
        // 没有 IPv6 监听时不应出现 blocking_ipv6
        assertFalse(values.containsKey("blocking_ipv6"))
        assertEquals("80 / 443", values["被拦域名需要连到的端口"])
    }

    @Test
    fun ipv6IsIncludedOnlyWhenAvailable() {
        val values = AdGuardClientGuide
            .valuesToCopy("10.0.0.2", "2001:db8::1", 80, 443)
            .toMap()
        assertEquals("2001:db8::1", values["blocking_ipv6"])
    }

    @Test
    fun everyGuideSaysHowToRollBackOrKeepOriginal() {
        val steps = AdGuardClientGuide.guides().flatMap { it.steps }
        assertTrue(steps.any { it.contains("回滚") })
    }

    @Test
    fun guidesCoverHomeAndBothClients() {
        val products = AdGuardClientGuide.guides().map { it.product }
        assertTrue(products.any { it.contains("Home") })
        assertTrue(products.any { it.contains("Windows") })
        assertTrue(products.any { it.contains("Android") })
    }
}
