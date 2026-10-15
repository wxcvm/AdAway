package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AdGuardHomeCustomIp 的单元测试：重点在"不猜格式"与"可整体回滚"。 */
class AdGuardHomeCustomIpTest {

    private val yaml = listOf(
        "http:",
        "  address: 0.0.0.0:3000",
        "dns:",
        "  upstream_dns:",
        "    - 1.1.1.1",
        "  blocking_mode: default",
        "  blocking_ipv4: 0.0.0.0",
        "filters:",
        "  - enabled: true",
    ).joinToString("\n")

    @Test
    fun patchesModeAndIpv4() {
        val plan = AdGuardHomeCustomIp.planPatch(yaml, "192.168.1.10", null)
        assertTrue(plan is AdGuardHomeCustomIp.Plan.Ready)
        val ready = plan as AdGuardHomeCustomIp.Plan.Ready
        assertTrue(ready.patched.contains("blocking_mode: custom_ip"))
        assertTrue(ready.patched.contains("blocking_ipv4: 192.168.1.10"))
        assertTrue("未请求 IPv6 时不得写入", !ready.patched.contains("blocking_ipv6"))
        assertTrue("其它行不得被改动", ready.patched.contains("address: 0.0.0.0:3000"))
        assertEquals(2, ready.changes.size)
    }

    @Test
    fun revertRestoresOriginalByteForByte() {
        val ready = AdGuardHomeCustomIp.planPatch(yaml, "192.168.1.10", null) as AdGuardHomeCustomIp.Plan.Ready
        assertEquals(yaml, AdGuardHomeCustomIp.revert(ready))
    }

    @Test
    fun ipv6WrittenOnlyWhenProvided() {
        val ready = AdGuardHomeCustomIp.planPatch(yaml, "10.0.0.2", "fd00::2") as AdGuardHomeCustomIp.Plan.Ready
        assertTrue(ready.patched.contains("blocking_ipv6: fd00::2"))
        assertTrue(ready.patched.contains("blocking_ipv4: 10.0.0.2"))
    }

    @Test
    fun unknownFormatIsRefusedNicely() {
        val noDns = AdGuardHomeCustomIp.planPatch("http:\n  address: x\n", "1.2.3.4", null)
        assertTrue(noDns is AdGuardHomeCustomIp.Plan.Unrecognized)
        val noMode = AdGuardHomeCustomIp.planPatch("dns:\n  upstream_dns: [1.1.1.1]\n", "1.2.3.4", null)
        assertTrue("找不到 blocking_mode 必须拒绝，而不是猜", noMode is AdGuardHomeCustomIp.Plan.Unrecognized)
        val empty = AdGuardHomeCustomIp.planPatch("", "1.2.3.4", null)
        assertTrue(empty is AdGuardHomeCustomIp.Plan.Unrecognized)
        val misordered = AdGuardHomeCustomIp.planPatch("blocking_mode: default\ndns:\n", "1.2.3.4", null)
        assertTrue("结构不符必须拒绝", misordered is AdGuardHomeCustomIp.Plan.Unrecognized)
    }

    @Test
    fun insertsIpv4WhenMissing() {
        val minimal = "dns:\n  blocking_mode: default\n"
        val ready = AdGuardHomeCustomIp.planPatch(minimal, "10.1.1.1", null) as AdGuardHomeCustomIp.Plan.Ready
        assertTrue(ready.patched.contains("blocking_ipv4: 10.1.1.1"))
        assertEquals(minimal, AdGuardHomeCustomIp.revert(ready))
    }
}
