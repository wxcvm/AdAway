package org.adaway.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 与本机其它过滤器（尤其 AdGuard）共处时的网络模式。
 *
 * 设计原则（写进代码而不是只写进文档）：
 * - 检测到冲突**只切换模式并说明原因**，绝不自动修改系统设置、绝不禁用别人的 VPN、绝不抢路由；
 * - 判定不确定时归为 [UNKNOWN]，由界面要求用户选择，而不是替用户决定；
 * - 本应用自己不使用 VPN/TUN（硬约束）：这里的 VPN 检测只用于"发现别人已经在接管流量"。
 */
enum class NetMode {
    /** 没有发现其它过滤器，本机由我们的服务端处理已到达它的请求。 */
    STANDALONE,

    /** 检测到 AdGuard 存在：进入协同模式，引导把被拦域指向本服务，不与其争抢接管路径。 */
    ADGUARD_COEXIST,

    /** 检测到其它 VPN 正在接管流量：默认不叠加第二套接管，交给用户选择。 */
    OTHER_VPN,

    /** 证据不足（既没有明确冲突，也不能确认独占）。 */
    UNKNOWN,

    /** 本应用自己的服务端没有在预期端口监听：先修自身，再谈协同。 */
    DEGRADED,
}

/**
 * 网络模式探测。
 *
 * [classify] 是纯函数，所有输入都由调用方给出，因此可以在普通单元测试里穷举组合；
 * [detect] 只负责把 Android 的事实采集出来交给它。
 */
object NetModeDetector {

    /**
     * 纯分类逻辑。
     *
     * @param serverReachable 本应用服务端是否已在预期端口可达
     * @param adguardInstalled 设备上是否安装了 AdGuard（任一已知包名）
     * @param otherVpnActive 是否有**其它** VPN 正在接管流量
     * @param detectCertain 探测本身是否可信（权限缺失/异常时为 false）
     */
    fun classify(
        serverReachable: Boolean,
        adguardInstalled: Boolean,
        otherVpnActive: Boolean,
        detectCertain: Boolean,
    ): NetMode = when {
        // 自身都没起来，先别谈协同
        !serverReachable -> NetMode.DEGRADED
        // 探测不可信时不下结论，交给用户
        !detectCertain -> NetMode.UNKNOWN
        // 别人已经在接管：不叠加第二套接管路径
        otherVpnActive -> NetMode.OTHER_VPN
        adguardInstalled -> NetMode.ADGUARD_COEXIST
        else -> NetMode.STANDALONE
    }

    /** 已知的 AdGuard 包名（Windows/Android 客户端与 Home 的控制端都列上，找不到就返回 false）。 */
    private val ADGUARD_PACKAGES = listOf(
        "com.adguard.android",
        "com.adguard.android.contentblocker",
        "org.adguard.dns",
    )

    /** 采集设备事实并分类；任何异常都降级为"探测不可信"，不抛出。 */
    fun detect(context: Context): NetMode {
        var certain = true
        var adguard = false
        var vpn = false
        try {
            val pm = context.packageManager
            adguard = ADGUARD_PACKAGES.any { pkg ->
                try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        } catch (e: Exception) {
            certain = false
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (e: Exception) {
            certain = false
        }
        val reachable = try {
            WebServerUtils.isWebServerReachable(context)
        } catch (e: Exception) {
            false
        }
        return classify(
            serverReachable = reachable,
            adguardInstalled = adguard,
            otherVpnActive = vpn,
            detectCertain = certain,
        )
    }
}
