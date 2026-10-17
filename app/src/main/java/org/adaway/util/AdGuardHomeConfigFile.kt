package org.adaway.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AdGuard Home 配置文件的「备份 → 应用 → 失败回滚」文件层（E 项向导的另一半）。
 *
 * 与 [AdGuardHomeCustomIp] 的分工：那边只负责**算出**改动计划（不碰磁盘），这边负责**安全落盘**。
 *
 * 三条硬边界（都写在实现里）：
 * 1. **格式不认就不写**：计划返回 Unrecognized 时，直接返回 [Outcome.Unrecognized]，**一个字节都不动**；
 * 2. **先备份**：应用前把原文写成 `<name>.bak-<时间戳>`；备份失败就**放弃应用**（不冒风险）；
 * 3. **失败必回滚**：写入采用「写临时文件 + rename」；任何一步失败都把备份内容写回原文件，
 *    并如实返回失败原因 —— 绝不留下"改了一半"的配置。
 *
 * 本类不选路径、不做 UI：路径与两个地址都由调用方给出（因此可用临时目录完整单测）。
 */
object AdGuardHomeConfigFile {

    /** 应用结果 —— 每一种都如实说明发生了什么。 */
    sealed interface Outcome {
        /** 成功：已写入，并留下备份路径。 */
        data class Applied(val backup: File, val changes: Int) : Outcome
        /** 格式不认识：未做任何改动。 */
        data class Unrecognized(val reason: String) : Outcome
        /** 失败：已回滚（原文件内容与调用前一致），reason 说明原因。 */
        data class Failed(val reason: String, val rolledBack: Boolean) : Outcome
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    /**
     * 把 `blocking_mode: custom_ip` 与两个地址写入 [configPath]。
     *
     * @param configPath AdGuard Home 的配置文件路径（由用户/界面提供）。
     * @param blockingIpv4 客户端可达的地址。
     * @param blockingIpv6 服务端确实在 IPv6 上监听时才传；否则 null。
     */
    fun apply(configPath: File, blockingIpv4: String, blockingIpv6: String?): Outcome {
        if (!configPath.isFile) {
            return Outcome.Failed("配置文件不存在：${configPath.path}", rolledBack = true)
        }
        val original = try {
            configPath.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return Outcome.Failed("读取失败：${e.message}", rolledBack = true)
        }
        val plan = AdGuardHomeCustomIp.planPatch(original, blockingIpv4, blockingIpv6)
        if (plan is AdGuardHomeCustomIp.Plan.Unrecognized) {
            return Outcome.Unrecognized(plan.reason)      /* 边界 1：一个字节都不动 */
        }
        val ready = plan as AdGuardHomeCustomIp.Plan.Ready

        /* 边界 2：先备份；备份失败就放弃应用 */
        val backup = File(configPath.parentFile, configPath.name + ".bak-" + stamp())
        try {
            backup.writeText(original, Charsets.UTF_8)
        } catch (e: Exception) {
            return Outcome.Failed("备份失败，已放弃应用：${e.message}", rolledBack = true)
        }

        /* 边界 3：写临时文件 + rename；失败则用备份回滚 */
        val tmp = File(configPath.parentFile, configPath.name + ".tmp")
        return try {
            tmp.writeText(ready.patched, Charsets.UTF_8)
            if (!tmp.renameTo(configPath)) {
                throw IllegalStateException("rename 失败")
            }
            val check = configPath.readText(Charsets.UTF_8)
            if (check != ready.patched) {
                throw IllegalStateException("写入后校验不一致")
            }
            Outcome.Applied(backup, ready.changes.size)
        } catch (e: Exception) {
            tmp.delete()
            val restored = try {
                configPath.writeText(original, Charsets.UTF_8)
                configPath.readText(Charsets.UTF_8) == original
            } catch (e2: Exception) {
                false
            }
            Outcome.Failed("${e.message}（已回滚=$restored）", rolledBack = restored)
        }
    }
}
