package org.adaway.model.rules

import android.content.Context
import org.adaway.util.BlockMode
import timber.log.Timber
import java.io.File

/**
 * 解除过滤（豁免）规则：单独一份给原生服务器读的名单。
 *
 * <p>为什么需要它：[org.adaway.util.BlockMode] 的放行名单（allowlist.txt）是按 <b>uid</b>
 * 的，粒度太粗——一个应用被整条放行往往过头，而它真正离不开的可能只是某一个域。
 * 这份名单按 <b>域名</b> 放行，原生服务器在每次请求时重新读取，所以改动立即生效、
 * 不需要重启进程。</p>
 *
 * <p>为什么只在劫持过滤模式才有意义：只有该模式服务器才会连回真实源站
 * （--proxy-filter）。普通 127.0.0.1 模式下服务器拿不到内容，把域放行只是"不再拦"
 * 而已，因此界面上该功能仅在 [BlockMode.HIJACK] 时启用。</p>
 *
 * <p>文件里的行由本应用写入；原生侧也接受多种语法（AdGuard/uBlock、hosts 行、
 * 完整 URL、通配、带端口），所以用户直接粘贴自己已有的规则也能用。</p>
 */
object ExemptionRules {

    /** 文件名，必须与原生 webserver.c 读取的名字一致。 */
    const val FILE_NAME = "domain_allowlist.txt"

    /** 该功能（以及编辑它的页面）是否可用：仅劫持过滤模式。 */
    fun isEnabled(context: Context): Boolean =
        BlockMode.current(context) == BlockMode.HIJACK

    /** 规则文件（可能不存在）。 */
    fun file(context: Context): File =
        File(org.adaway.util.WebServerUtils.getResourcePath(context).toFile(), FILE_NAME)

    /**
     * 归一化一条豁免规则，规则与原生 `domain_rule_normalize()` **逐条对齐**，
     * 因此界面预览的结果就是服务器实际生效的结果：
     *
     * - `example.com` / `.example.com` / `*.example.com`
     * - `@@||example.com^` / `||example.com^`（AdGuard / uBlock，含误贴的拦截语法）
     * - `0.0.0.0 example.com` / `:: example.com`（hosts 行，任意前置 IP）
     * - `https://example.com/path?x=1`（取主机）、`example.com:8443`（去端口）
     * - `#` 与 `!` 开头是注释
     *
     * @param raw 原始行。
     * @return 规范化后的域；`null` 表示该行不可用（注释、空行、或过宽规则）。
     */
    fun normalize(raw: String): String? {
        var p = raw.trim()
        if (p.isEmpty() || p.startsWith("#") || p.startsWith("!")) return null
        p = p.trimStart('@', '|', ' ', '\t')
        // hosts 行：空白之后的字段才是主机
        val sp = p.indexOfFirst { it == ' ' || it == '\t' }
        if (sp >= 0) {
            val tail = p.substring(sp).trim()
            if (tail.isNotEmpty()) p = tail
        }
        val scheme = p.indexOf("://")
        if (scheme >= 0) p = p.substring(scheme + 3)
        p = p.trimStart('*', '.')
        val cut = p.indexOfFirst {
            it == '/' || it == '?' || it == '#' || it == ':' || it == '^' || it == ' ' || it == '\t'
        }
        if (cut >= 0) p = p.substring(0, cut)
        p = p.lowercase()
        // 与原生一致的三重拒绝：太短 / 不含点（裸 TLD）/ 含通配 —— 否则一行 "com" 等于放行半个互联网
        if (p.length < 4 || !p.contains('.') || p.contains('*')) return null
        return p
    }

    /**
     * 解析多行输入，用于页面预览。
     *
     * @return 第一项是可用规则（去重、保持顺序），第二项是被拒绝的原始行。
     */
    fun parseAll(text: String): Pair<List<String>, List<String>> {
        val accepted = LinkedHashSet<String>()
        val rejected = ArrayList<String>()
        text.lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("!")) return@forEach
            val normalized = normalize(raw)
            if (normalized == null) rejected += raw else accepted += normalized
        }
        return accepted.toList() to rejected
    }

    /** 当前生效的域（已去重、已排序，注释行忽略）。 */
    fun list(context: Context): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .distinct()
                .sorted()
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot read %s", f.absolutePath)
            emptyList()
        }
    }

    /**
     * 追加一条按域名的豁免。
     *
     * @param host 域名或用户粘贴的规则原文（原生侧会归一化）。
     * @return 实际写入的域（已归一化为小写、去空白）；空字符串表示未写入。
     */
    fun addRaw(context: Context, host: String): String {
        val cleaned = host.trim().lowercase()
        if (cleaned.isEmpty()) return ""
        val f = file(context)
        return try {
            if (!f.parentFile.exists()) f.parentFile.mkdirs()
            val existing = list(context)
            if (existing.contains(cleaned)) return cleaned
            if (!f.exists()) {
                f.writeText("# ADBlock 解除过滤规则（按域名放行，原生服务器每次请求都会重读）\n")
            }
            f.appendText(cleaned + "\n")
            cleaned
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot write %s", f.absolutePath)
            ""
        }
    }

    /** 删除一条豁免。 */
    fun remove(context: Context, host: String) {
        val f = file(context)
        if (!f.exists()) return
        val target = host.trim().lowercase()
        try {
            val kept = f.readLines().filter { it.trim().lowercase() != target }
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot rewrite %s", f.absolutePath)
        }
    }

    // ─────────────────── 按域覆盖响应（rule_overrides.txt） ───────────────────

    /** 覆盖规则文件名，必须与原生 webserver.c 的 RULE_OVERRIDE_FILE 一致。 */
    const val FILE_OVERRIDES = "rule_overrides.txt"

    /**
     * 可选的覆盖策略，与原生 `rule_status_from_name()` 认的名字完全一致。
     *
     * <p>`placeholder` 不在列表里：它等于"这条不生效"，界面上没有意义。</p>
     */
    val OVERRIDE_POLICIES = listOf("403", "404", "410", "503", "204", "200")

    /** 覆盖规则文件（可能不存在）。 */
    fun overridesFile(context: Context): File =
        File(org.adaway.util.WebServerUtils.getResourcePath(context).toFile(), FILE_OVERRIDES)

    /** 当前覆盖规则原文（忽略注释与空行）。 */
    fun listOverrides(context: Context): List<String> {
        val f = overridesFile(context)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot read %s", f.absolutePath)
            emptyList()
        }
    }

    /**
     * 写入一条覆盖规则：`<域> = <策略>`。
     *
     * <p>同一个域已存在时**先删旧行再写新行**（覆盖语义），否则用户改策略会留下两条
     * 互相矛盾的规则，而原生侧是按文件顺序取第一个命中的。</p>
     *
     * @param host   用户输入的域（与豁免共用同一套归一化与"过宽即拒绝"标准）。
     * @param policy 必须是 [OVERRIDE_POLICIES] 之一。
     * @return 实际写入的域；空字符串表示没有写入。
     */
    fun addOverride(context: Context, host: String, policy: String): String {
        val domain = normalize(host) ?: return ""
        if (policy !in OVERRIDE_POLICIES) return ""
        val f = overridesFile(context)
        return try {
            if (!f.parentFile.exists()) f.parentFile.mkdirs()
            val kept = if (f.exists()) {
                f.readLines().filter { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@filter true
                    /* 保留域不同的行；同一个域的旧行被丢掉，由下面重写 —— 覆盖语义 */
                    t.substringBefore('=').trim().lowercase() != domain
                }
            } else {
                listOf("# ADBlock 按域覆盖响应规则（<域> = <策略>，原生服务器每次请求都会重读）")
            }
            f.writeText(kept.joinToString("\n", postfix = "\n") + "$domain = $policy\n")
            domain
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot write %s", f.absolutePath)
            ""
        }
    }

    /** 删除一条覆盖规则（按域匹配，忽略策略部分）。 */
    fun removeOverride(context: Context, line: String) {
        val f = overridesFile(context)
        if (!f.exists()) return
        val domain = line.substringBefore('=').trim().lowercase()
        if (domain.isEmpty()) return
        try {
            val kept = f.readLines().filter { it.substringBefore('=').trim().lowercase() != domain }
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Timber.w(e, "ExemptionRules: cannot rewrite %s", f.absolutePath)
        }
    }
}
