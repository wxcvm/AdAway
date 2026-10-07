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
}
