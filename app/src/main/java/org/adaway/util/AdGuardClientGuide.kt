package org.adaway.util

/**
 * AdGuard 客户端（Windows / Android）的协同指引。
 *
 * 为什么只给"手动指引 + 配置值复制"而不做自动写入：
 * - AdGuard 客户端是闭源产品，其配置存储与界面在不同版本间会变，不存在可长期依赖的公开写入接口；
 * - 猜测并写入别人的配置，最坏结果是把用户原有的过滤规则弄坏 —— 这与"检测到冲突只切换模式、
 *   不替用户改系统设置"的原则冲突。
 * 所以这里做的是：把"该改哪一项、填什么值、怎么验证改成功了"讲清楚，并提供一键复制。
 *
 * 数据是结构化的，因此可以被单元测试固定住（例如：任何版本都不允许出现"DNS 已指向即完成"的说法）。
 */
object AdGuardClientGuide {

    /** 一个产品线在某代界面上的操作步骤。 */
    data class Guide(
        val product: String,
        val versionHint: String,
        val steps: List<String>,
        val notes: List<String>,
    )

    private const val NOT_DONE_NOTE =
        "把 DNS 指向本服务只完成了第一步：HTTPS 侧是否被接管要用 openssl s_client 看 issuer 才能确认。"

    /**
     * 按产品线给出指引。版本提示刻意写成"代"而不是精确版本号 ——
     * 精确版本号会过期，而过期的指引比没有指引更糟。
     */
    fun guides(): List<Guide> = listOf(
        Guide(
            product = "AdGuard Home",
            versionHint = "带 DNS 设置页的版本（可直接指定被拦域返回的地址）",
            steps = listOf(
                "打开 AdGuard Home 的 设置 -> DNS 设置",
                "把 拦截模式 改为 自定义 IP（custom_ip）",
                "把 IPv4 地址填成下面复制的值（不是 127.0.0.1，除非客户端就是这台机器）",
                "只有服务端确实在 IPv6 上监听时，才填 IPv6 地址",
                "保存后按下面的验收清单逐项核对",
            ),
            notes = listOf(
                "DNS 只给出 IP、不带端口：被拦域名会被连到 80/443，请确认服务端确实在这两个端口上监听。",
                NOT_DONE_NOTE,
            ),
        ),
        Guide(
            product = "AdGuard for Windows",
            versionHint = "使用可自定义被拦响应的版本（界面里通常没有直接选项）",
            steps = listOf(
                "先确认 AdGuard 是否在用系统代理或 WFP 接管流量；若已接管，本应用不要叠加第二套接管路径",
                "在 AdGuard 的过滤设置里为被拦域添加自定义响应（或把本应用的地址加入其上游/例外）",
                "把下面复制的地址作为被拦域的自定义响应目标",
                "改完按验收清单核对；失败就回滚到原来的设置",
            ),
            notes = listOf(
                "AdGuard for Windows 的公开仓库是问题跟踪器，不是完整实现，因此不假设存在稳定写入接口，全程手动。",
                NOT_DONE_NOTE,
            ),
        ),
        Guide(
            product = "AdGuard for Android",
            versionHint = "使用本地 VPN 模式的版本",
            steps = listOf(
                "Android 上同一时间只允许一个应用建立 VPN：若 AdGuard 已在接管，本应用不启动自己的接管路径",
                "在 AdGuard 的 DNS 过滤里把被拦域指向下面复制的地址（需要其版本支持自定义响应）",
                "若该版本不支持自定义响应，则改用本应用的其它模式，不要强行改它的配置",
            ),
            notes = listOf(
                "检测到 AdGuard 占用 VPN 时，模式状态机会给出 ADGUARD_COEXIST，不会去抢 VPN。",
                NOT_DONE_NOTE,
            ),
        ),
    )

    /** 需要复制给用户的配置值（键值对，便于一键复制与核对）。 */
    fun valuesToCopy(
        blockingIpv4: String,
        blockingIpv6: String?,
        httpPort: Int,
        httpsPort: Int,
    ): List<Pair<String, String>> = buildList {
        add("blocking_mode" to "custom_ip")
        add("blocking_ipv4" to blockingIpv4)
        if (blockingIpv6 != null) add("blocking_ipv6" to blockingIpv6)
        add("被拦域名需要连到的端口" to ("$httpPort / $httpsPort"))
    }

    /** 任何指引都不得暗示"DNS 改完就成功了" —— 这条被测试固定住。 */
    fun allNotes(): List<String> = guides().flatMap { it.notes } + guides().flatMap { it.steps }
}
