package org.adaway.util

/**
 * AdGuard Home 的 `custom_ip` 配置改动计划（E 项的"逻辑核心"，界面部分待 C9/E-UI）。
 *
 * 设计上的三条硬边界（都写进代码，不只写在文档里）：
 * 1. **只认已知格式，绝不猜**：只有当文本里能同时找到 `dns:` 段与 `blocking_mode:` 时才生成计划；
 *    否则返回 [Plan.Unrecognized]，由界面提示用户手动修改（AdGuard Home 的配置格式随版本变化，
 *    猜错就会破坏用户的过滤设置）。
 * 2. **先备份、可回滚**：计划里保存每一处**原文行**，[revert] 能逐字节还原成改动前的文本。
 * 3. **绝不部分写入**：计划要么给出完整的新文本，要么什么都不给 —— 调用方只有两种选择
 *    （整体采用 / 整体放弃），不存在"改了一半"的状态。
 *
 * 本类**不写文件**：写盘、备份与失败回滚由界面层在用户确认后执行。
 */
object AdGuardHomeCustomIp {

    /** 计划的结果。 */
    sealed interface Plan {
        /** 格式不认识：不生成任何改动。 */
        data class Unrecognized(val reason: String) : Plan

        /** 可以整体采用（也可 [revert] 整体还原）。 */
        data class Ready(
            val original: String,
            val patched: String,
            /** 逐处改动：原文行 → 新行（便于界面展示要改什么）。 */
            val changes: List<Pair<String, String>>,
        ) : Plan
    }

    private const val DNS_SECTION = "dns:"
    private const val MODE_KEY = "blocking_mode:"

    /**
     * 生成把被拦域名指向 `blockingIpv4` /（可选）`blockingIpv6` 的改动计划。
     *
     * @param yaml AdGuard Home 配置文件的**原文**。
     * @param blockingIpv4 客户端可达地址（本机就是回环地址；**不是** 127.0.0.1，除非客户端是本机）。
     * @param blockingIpv6 服务端确实在 IPv6 上监听时才传；否则传 null（不写入该项）。
     */
    fun planPatch(yaml: String, blockingIpv4: String, blockingIpv6: String?): Plan {
        if (yaml.isBlank()) {
            return Plan.Unrecognized("配置内容为空")
        }
        val lines = yaml.split("\n")
        val dnsIndex = lines.indexOfFirst { it.trimEnd().trimStart().startsWith(DNS_SECTION) }
        if (dnsIndex < 0) {
            return Plan.Unrecognized("找不到 dns: 段")
        }
        val modeIndex = lines.indexOfFirst { it.trimStart().startsWith(MODE_KEY) }
        if (modeIndex < 0) {
            return Plan.Unrecognized("找不到 blocking_mode:（该版本可能不支持自定义拦截响应）")
        }
        // 只改 dns: 段内的 blocking_mode（模式键必须出现在 dns 段之后，否则说明结构与预期不符）
        if (modeIndex < dnsIndex) {
            return Plan.Unrecognized("blocking_mode 出现在 dns: 段之前，结构与预期不符，不做改动")
        }
        val indent = lines[modeIndex].takeWhile { it == ' ' || it == '\t' }
        val changes = mutableListOf<Pair<String, String>>()
        changes += lines[modeIndex] to (indent + MODE_KEY + " custom_ip")
        val newLines = lines.toMutableList()
        newLines[modeIndex] = changes.last().second

        val ipv4Key = indent + "blocking_ipv4:"
        val ipv6Key = indent + "blocking_ipv6:"
        val v4Index = lines.indexOfFirst { it.trimStart().startsWith("blocking_ipv4:") }
        if (v4Index >= 0) {
            changes += lines[v4Index] to (ipv4Key + " " + blockingIpv4)
            newLines[v4Index] = changes.last().second
        } else {
            changes += "" to (ipv4Key + " " + blockingIpv4)
            newLines.add(modeIndex + 1, changes.last().second)
        }
        if (blockingIpv6 != null) {
            val v6Index = lines.indexOfFirst { it.trimStart().startsWith("blocking_ipv6:") }
            if (v6Index >= 0) {
                changes += lines[v6Index] to (ipv6Key + " " + blockingIpv6)
                newLines[v6Index] = changes.last().second
            } else {
                changes += "" to (ipv6Key + " " + blockingIpv6)
                newLines.add(modeIndex + 2, changes.last().second)
            }
        }
        return Plan.Ready(original = yaml, patched = newLines.joinToString("\n"), changes = changes)
    }

    /** 整体还原（失败回滚用）：返回改动前的原文。 */
    fun revert(plan: Plan.Ready): String = plan.original
}
