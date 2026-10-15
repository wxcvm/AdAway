package org.adaway.model.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CosmeticRules 的域名作用域（D 项）：规则要保留域名，导出的 TSV 才能让服务端按页面主机筛选。
 */
class CosmeticRulesScopeTest {

    private fun collected(vararg lines: String): List<String> {
        CosmeticRules.clear()
        lines.forEach { CosmeticRules.collect(it) }
        return CosmeticRules.toScopedRules().lines().filter { it.isNotEmpty() }
    }

    @Test
    fun globalRuleHasStarDomain() {
        val rules = collected("##.ad-banner")
        assertEquals(1, rules.size)
        assertEquals("*\t.ad-banner", rules[0])
    }

    @Test
    fun domainIsKeptAndLowerCased() {
        val rules = collected("Example.COM##.ad-banner")
        assertEquals("example.com\t.ad-banner", rules[0])
    }

    @Test
    fun multipleDomainsProduceOneRuleEach() {
        val rules = collected("a.com,b.com##.ad")
        assertEquals(2, rules.size)
        assertTrue(rules.contains("a.com\t.ad"))
        assertTrue(rules.contains("b.com\t.ad"))
    }

    @Test
    fun exclusionSyntaxIsSkippedNotGuessed() {
        // '~example.com' 是排除语法，我们不支持 —— 整条跳过，而不是猜成 example.com
        val rules = collected("~example.com##.ad")
        assertTrue("排除语法不应产出规则", rules.isEmpty())
    }

    @Test
    fun dangerousSelectorsAreStillRejected() {
        assertTrue(collected(".ok{color:red}##.x").isEmpty())
        assertTrue(collected("a.com##.y{display:block}").isEmpty())
    }

    @Test
    fun legacyCssOutputStaysSelectorOnly() {
        CosmeticRules.clear()
        CosmeticRules.collect("example.com##.ad-banner")
        val css = CosmeticRules.toCss()
        assertTrue(css.contains(".ad-banner{display:none !important;}"))
        assertFalse("旧文件不应包含域名列", css.contains("example.com\t"))
    }
}
