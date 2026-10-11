package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AdGuardCoexistence 的单元测试：把三个坑与验收清单固定成回归用例。 */
class AdGuardCoexistenceTest {

    @Test
    fun ipv4RecommendationIsTheGivenReachableAddress() {
        assertEquals("192.168.1.10", AdGuardCoexistence.recommendedBlockingIpv4("192.168.1.10"))
    }

    @Test
    fun ipv6OnlyRecommendedWhenThereIsAnIpv6Listener() {
        assertNull(AdGuardCoexistence.recommendedBlockingIpv6("192.168.1.10", false))
        assertEquals("2001:db8::1", AdGuardCoexistence.recommendedBlockingIpv6("2001:db8::1", true))
    }

    @Test
    fun portMismatchIsBlocking() {
        assertTrue(AdGuardCoexistence.hasBlockingIssue(8080, 8443, true))
        assertFalse(AdGuardCoexistence.hasBlockingIssue(80, 443, true))
    }

    @Test
    fun remoteClientWarnsAboutLoopback() {
        val remote = AdGuardCoexistence.pitfalls(80, 443, clientIsThisDevice = false)
        assertTrue(remote.any { it.blocking && it.detail.contains("127.0.0.1") })
        val local = AdGuardCoexistence.pitfalls(80, 443, clientIsThisDevice = true)
        assertFalse(local.any { it.blocking && it.detail.contains("永远连不到本服务") })
    }

    @Test
    fun dnsPointingIsNotHttpsFiltering() {
        val advices = AdGuardCoexistence.pitfalls(80, 443, clientIsThisDevice = true)
        val warn = advices.first { it.title.contains("不等于 HTTPS 已过滤") }
        assertFalse("这条只是提醒，不应阻塞协同配置完成", warn.blocking)
    }

    @Test
    fun checklistHasTheThreeVerificationSteps() {
        val list = AdGuardCoexistence.acceptanceChecklist()
        assertEquals(3, list.size)
        assertTrue(list[0].contains("DNS"))
        assertTrue(list[1].contains("连接"))
        assertTrue(list[2].contains("issuer"))
    }
}
