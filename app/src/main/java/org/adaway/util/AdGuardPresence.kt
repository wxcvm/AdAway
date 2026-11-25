package org.adaway.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 与 AdGuard 共存时的检测与建议（S4）。
 *
 * <p>为什么值得单独做一块：AdGuard 自己就把拦截结果送到「拦截响应 → 自定义 IP」，
 * 把它设成本机服务器（127.0.0.1 / ::1）时，**被 AdGuard 判定的域会全部由本应用应答**
 * ——统一拦截页 + 完整的逐应用统计，而本应用不需要安装任何劫持规则。这一步只要一句
 * 提示就能配好，但用户通常不知道。</p>
 *
 * <p>检测分两半：装了 AdGuard（包名匹配）**且** 有一条 VPN 在跑（AdGuard 的本地过滤
 * 就是一条 VPN）。只装不用时不该打扰用户。</p>
 */
object AdGuardPresence {

    /** AdGuard 各发行版的包名，做成列表便于扩展。 */
    private val PACKAGES = listOf(
        "com.adguard.android",
        "com.adguard.android.contentblocker",
        "com.adguard.android.lite",
    )

    private const val PREF_HINT_DISMISSED = "adguard_hint_dismissed"

    /** AdGuard 是否已安装。 */
    fun isInstalled(context: Context): Boolean = PACKAGES.any { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (exception: Exception) {
            false
        }
    }

    /** 是否有 VPN 在运行（AdGuard 的本地过滤即一条 VPN）。 */
    fun isVpnActive(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (exception: Exception) {
        false
    }

    /** 是否值得显示提示：两者都成立。 */
    fun shouldSuggest(context: Context): Boolean = isInstalled(context) && isVpnActive(context)

    /** Saved listener candidates, not proof of reachability or active DNS filtering. */
    data class ListenerSuggestion(val ipv4: String, val ipv6: String, val standardPorts: Boolean) {
        fun copyText(): String = listOfNotNull(
            ipv4.takeIf { it.isNotEmpty() }?.let { "A = $it" },
            ipv6.takeIf { it.isNotEmpty() }?.let { "AAAA = $it" },
        ).joinToString("\n")
    }

    fun listenerSuggestion(settings: ListenerSettings): ListenerSuggestion =
        ListenerSuggestion(
            if (ListenerSettings.isAnyAddress(settings.ipv4)) "127.0.0.1" else settings.ipv4,
            if (ListenerSettings.isAnyAddress(settings.ipv6)) "::1" else settings.ipv6,
            settings.http == 80 && settings.https == 443,
        )

    /** 用户是否已经点过「不再提示」。 */
    fun isHintDismissed(context: Context): Boolean =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HINT_DISMISSED, false)

    /** 记下「不再提示」。 */
    fun dismissHint(context: Context) {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_HINT_DISMISSED, true)
            .apply()
    }
}
