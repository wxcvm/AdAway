package org.adaway.util

/**
 * AdGuard Home custom_ip 向导的「实测」判定（E 项：DNS / 连接 / issuer 三步 + 失败回滚）。
 *
 * 本类**只做判定、不发网络请求、不写文件**：三个探针结果由界面层提供，因此可以完整单测。
 *
 * 计划里点名的第三个坑在这里变成**机器判定**而不是一句提示：
 * **「DNS 已指向 ≠ HTTPS 已过滤」** —— 即使 DNS 已经指向本服务、连接也通，只要 issuer 仍是公网 CA，
 * 就说明流量没有真正经过本服务（例如 AdGuard 的 DNS 与 HTTPS 过滤是两套设置），此时**必须回滚**，
 * 更不能向用户显示成功。
 */
object AdGuardHomeVerification {

    /** 单次实测的结果。 */
    enum class Verdict {
        /** 三步全过：DNS 通、连接通、issuer 已是本地 CA。 */
        OK,
        /** DNS 通、连接通，但 **issuer 仍是公网 CA** —— 就是那个坑：DNS 指向不等于 HTTPS 已过滤。 */
        DNS_ONLY_NOT_FILTERED,
        /** DNS 不通：配置还没生效或地址填错。 */
        DNS_FAILED,
        /** DNS 通但连不上：80/443 未监听，或被别的程序占用。 */
        CONNECTION_FAILED
    }

    /**
     * @param dnsAnswered AdGuard Home 是否对本服务地址给出了应答。
     * @param connected TCP 是否连得上。
     * @param issuer 实际拿到的证书 issuer；null 表示没拿到。
     * @param localCaSubject 本机 CA 的 subject（用于比对）。
     */
    fun judge(dnsAnswered: Boolean, connected: Boolean, issuer: String?, localCaSubject: String): Verdict {
        if (!dnsAnswered) return Verdict.DNS_FAILED
        if (!connected) return Verdict.CONNECTION_FAILED
        if (issuer == null) return Verdict.DNS_ONLY_NOT_FILTERED
        return if (issuer.contains(localCaSubject, ignoreCase = true)) Verdict.OK else Verdict.DNS_ONLY_NOT_FILTERED
    }

    /** 失败是否应当一键回滚（只有全过才保留改动）。 */
    fun shouldRollback(v: Verdict): Boolean = v != Verdict.OK

    /** 给用户看的一句话（中英资源由界面层挑选，这里给出判定码与要点）。 */
    fun summary(v: Verdict): String = when (v) {
        Verdict.OK -> "DNS、连接、issuer 三步全过，改动保留"
        Verdict.DNS_ONLY_NOT_FILTERED ->
            "DNS 已指向本服务，但 issuer 仍是公网 CA：DNS 已指向不等于 HTTPS 已过滤（AdGuard 的 DNS 与 HTTPS 过滤是两套设置），已回滚"
        Verdict.DNS_FAILED -> "DNS 未生效或地址填错，已回滚"
        Verdict.CONNECTION_FAILED -> "DNS 通了但连接不上（80/443 未监听或被占用），已回滚"
    }
}
