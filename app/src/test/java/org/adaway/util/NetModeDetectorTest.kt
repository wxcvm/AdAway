package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NetModeDetector.classify] 的穷举测试（普通 JVM 单元测试，CI 的 ./gradlew test 会跑）。
 *
 * 这些用例同时是"商业规则"的文档：谁优先、什么情况下不下结论。
 */
class NetModeDetectorTest {

    @Test
    fun degradedWinsOverEverything() {
        // 自身服务端不可达时，无论别人装没装、有没有 VPN，都先算 DEGRADED
        assertEquals(NetMode.DEGRADED, NetModeDetector.classify(false, true, true, true))
        assertEquals(NetMode.DEGRADED, NetModeDetector.classify(false, false, false, true))
        assertEquals(NetMode.DEGRADED, NetModeDetector.classify(false, false, false, false))
    }

    @Test
    fun uncertainDetectionAsksTheUser() {
        // 探测不可信（权限缺失/异常）时不下结论
        assertEquals(NetMode.UNKNOWN, NetModeDetector.classify(true, true, true, false))
        assertEquals(NetMode.UNKNOWN, NetModeDetector.classify(true, false, false, false))
    }

    @Test
    fun otherVpnTakesPrecedenceOverAdGuard() {
        // 其它 VPN 已经在接管：不叠加第二套接管路径
        assertEquals(NetMode.OTHER_VPN, NetModeDetector.classify(true, true, true, true))
        assertEquals(NetMode.OTHER_VPN, NetModeDetector.classify(true, false, true, true))
    }

    @Test
    fun adguardAloneMeansCoexist() {
        assertEquals(NetMode.ADGUARD_COEXIST, NetModeDetector.classify(true, true, false, true))
    }

    @Test
    fun cleanDeviceIsStandalone() {
        assertEquals(NetMode.STANDALONE, NetModeDetector.classify(true, false, false, true))
    }
}
