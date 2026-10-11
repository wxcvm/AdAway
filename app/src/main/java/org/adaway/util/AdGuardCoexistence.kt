package org.adaway.util

/**
 * AdGuard Home 协同（custom_ip）的顾问：只负责算出该填什么、有哪些坑、怎么算验收通过。
 *
 * 它刻意不做三件事（写进代码而不是只写进文档）：
 * 1. 不写 AdGuard Home 的配置文件 —— 版本与格式未知时写入等于破坏用户的过滤设置；
 * 2. 不假设 127.0.0.1 对客户端可用 —— 只有当客户端就是本机时才成立；
 * 3. 不把 DNS 已指向本服务当成 HTTPS 已过滤 —— 后者还要求本地 CA 被系统信任。
 *
 * 因此输入全是事实（监听地址、端口、客户端位置、是否有 IPv6 监听），输出是推荐值与待确认清单，
 * 可被普通单元测试穷举。
 */
object AdGuardCoexistence {

    /** 一条需要用户做的事，或需要知情的坑。 */
    data class Advice(val title: String, val detail: String, val blocking: Boolean)

    /** 推荐返回给 AdGuard Home 的 IPv4 地址：就是客户端可达的那个地址（本机时为回环地址）。 */
    fun recommendedBlockingIpv4(serverReachableAddress: String): String = serverReachableAddress

    /** 没有 IPv6 监听时不要填 IPv6 地址：填了会让 IPv6 客户端连到一个不存在的服务。 */
    fun recommendedBlockingIpv6(
        serverReachableAddress: String,
        serverHasIpv6Listener: Boolean,
    ): String? = if (serverHasIpv6Listener) serverReachableAddress else null

    private const val LOOPBACK_IS_REMOTE_DETAIL =
        "其它设备解析到 127.0.0.1 会指向它自己，永远连不到本服务；请填服务端的局域网地址，并确认防火墙放行。"

    /** 三个已知的坑；blocking = true 的必须先解决，否则不能宣布协同配置完成。 */
    fun pitfalls(httpPort: Int, httpsPort: Int, clientIsThisDevice: Boolean): List<Advice> = listOf(
        Advice(
            title = "被拦域名必须能连到 80 / 443",
            detail = "DNS 只给出 IP，不带端口。AdGuard 把被拦域解析到本服务后，客户端仍会连 80/443，" +
                "而本服务当前监听 " + httpPort + "/" + httpsPort +
                "。若两者不一致，需要改监听端口，或另外做本机重定向，否则拦到的页面打不开。",
            blocking = !(httpPort == 80 && httpsPort == 443),
        ),
        Advice(
            title = if (clientIsThisDevice) "客户端是本机：可以填回环地址" else "客户端不是本机：不要填 127.0.0.1",
            detail = if (clientIsThisDevice)
                "本机客户端解析到 127.0.0.1 就是本机自己，可用。"
            else LOOPBACK_IS_REMOTE_DETAIL,
            blocking = !clientIsThisDevice,
        ),
        Advice(
            title = "DNS 指向本服务，不等于 HTTPS 已过滤",
            detail = "HTTPS 要被改写，请求端必须信任本地 CA；证书固定的应用即使系统级信任也会拒绝。" +
                "验收时请用 openssl s_client 看 issuer 是否为本地 CA。",
            blocking = false,
        ),
    )

    /** 验收清单：三项都要过才算已生效，缺一项就不能宣布成功。 */
    fun acceptanceChecklist(): List<String> = listOf(
        "DNS 应答：被拦域名解析到本服务地址（注意 DNS 不带端口）",
        "连接：能真的连上该地址的监听端口（不是只在 DNS 层看着对）",
        "证书链：openssl s_client 显示的 issuer 是本地 CA（这一步才证明 HTTPS 侧被接管）",
    )

    /** 是否有阻塞项（有就不能宣布协同配置完成）。 */
    fun hasBlockingIssue(httpPort: Int, httpsPort: Int, clientIsThisDevice: Boolean): Boolean =
        pitfalls(httpPort, httpsPort, clientIsThisDevice).any { it.blocking }
}
