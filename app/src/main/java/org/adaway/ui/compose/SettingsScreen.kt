package org.adaway.ui.compose

/**
 * 设置页面：高度自定义的偏好中心。
 *
 * 卡片分组：
 *  - 外观    主题切换（跟随系统/浅色/深色）
 *  - 数据管理  清除累计统计与历史
 *  - Web 服务器 监听范围（LAN/回环）+ HTTP/HTTPS 端口
 *  - 日志    条数上限 + 保留时间（永久/1天/7天/30天）
 *  - 图表    各类统计卡开关 + 柱状图样式
 *  - 应用监控  每个已识别 uid 的独立监控开关
 *  - 关于    版本号 + 开源声明
 *
 * 偏好存储：compose_general / compose_app_monitor / compose_webserver。
 */

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.adaway.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

private const val PREFS_MONITOR = "compose_app_monitor"
/** 旧版偏好键共享的开关行（自动更新等）：简化旧版 PrefsActivity 页面的写法。 */
@Composable
private fun UpdateSwitchRow(
    prefs: android.content.SharedPreferences,
    key: String,
    def: Boolean,
    title: String,
    hint: String,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(key, def)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { v ->
                checked = v
                prefs.edit().putBoolean(key, v).apply()
            },
        )
    }
}
private const val PREFS_GENERAL = "compose_general"

/** Whether stats should track uid. Default true. */
internal fun isAppMonitored(context: Context, uid: Int): Boolean {
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .getBoolean("monitor_$uid", true)
}

internal fun setAppMonitored(context: Context, uid: Int, monitored: Boolean) {
    context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .edit().putBoolean("monitor_$uid", monitored).apply()
}

/* ── Per-app allowlist: bypass blocking for this uid (allow_<uid>) ── */

internal fun isAppAllowed(context: Context, uid: Int): Boolean {
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .getBoolean("allow_$uid", false)
}

internal fun setAppAllowed(context: Context, uid: Int, allowed: Boolean) {
    context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .edit().putBoolean("allow_$uid", allowed).apply()
    // 同步写 allowlist.txt 供 webserver 实时读取
    syncAllowlistFile(context)
}

/** 把所有 allow_<uid>=true 的 uid 写入 webserver 目录 allowlist.txt。 */
private fun syncAllowlistFile(context: Context) {
    try {
        val prefs = context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        val uids = prefs.all
            .filterKeys { it.startsWith("allow_") }
            .filterValues { it == true }
            .keys
            .mapNotNull { it.removePrefix("allow_").toIntOrNull() }
            .toMutableSet()
        /*
         * Keep the captive-portal defaults in the file.
         *
         * This used to write the user's entries only, so flipping any "放行"
         * switch silently dropped com.android.captiveportallogin and
         * com.google.android.captiveportallogin (and on ColorOS the Wi-Fi
         * dialog) until the next server start re-added them. Without their
         * allowlist entry those components fall back to a blocked-request retry
         * loop - measured at ~240 requests/second from one of them - so the
         * "fix" of allowing one app quietly re-created the retry storm.
         */
        org.adaway.util.WebServerUtils.CAPTIVE_PORTAL_PACKAGES.forEach { pkg ->
            runCatching { uids.add(context.packageManager.getPackageUid(pkg, 0)) }
        }
        val content = uids.sorted().joinToString("\n")
        val dir = java.io.File(context.filesDir, "webserver")
        dir.mkdirs()
        java.io.File(dir, "allowlist.txt").writeText(content)
    } catch (e: Exception) {
        Timber.w(e, "Failed to sync allowlist")
    }
}

/* ── Allowlist inspection (default + user entries) ───────────────── */

/** Read all uid entries from the webserver's allowlist.txt. */
internal fun readAllowlistUids(context: Context): List<Int> {
    return try {
        val f = java.io.File(context.filesDir, "webserver/allowlist.txt")
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
    } catch (e: Exception) {
        Timber.w(e, "Failed to read allowlist")
        emptyList()
    }
}

/**
 * Default allowlist entries (captive portal login packages).
 * These are written automatically on every web server start and are
 * required for the system "Sign in to network" page to work.
 */
internal fun defaultAllowlistUids(context: Context): List<Int> {
    return try {
        val pm = context.packageManager
        org.adaway.util.WebServerUtils.CAPTIVE_PORTAL_PACKAGES.mapNotNull { pkg ->
            try {
                pm.getPackageUid(pkg, 0)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Failed to resolve captive portal uids")
        emptyList()
    }
}

/** User-allowed uids (allow_<uid> = true), excluding default entries. */
internal fun userAllowedUids(context: Context): List<Int> {
    val defaults = defaultAllowlistUids(context).toSet()
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .all
        .filterKeys { it.startsWith("allow_") }
        .filterValues { it == true }
        .keys
        .mapNotNull { it.removePrefix("allow_").toIntOrNull() }
        .filter { it > 0 && it !in defaults }
        .sorted()
}

/* ── General settings (logs + chart options) ──────────────────── */


/** Whether a statistics card is enabled. Keys: chart_donut, chart_trend,
 *  chart_bars, chart_apps, chart_certs. All default true. */
internal fun isChartEnabled(context: Context, key: String): Boolean {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getBoolean(key, true)
}

internal fun setChartEnabled(context: Context, key: String, enabled: Boolean) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putBoolean(key, enabled).apply()
}

/** Bar chart style: 0 = side-by-side, 1 = stacked, 2 = area. Default 0. */
/*
 * 趋势图样式的唯一映射 —— 设置页与趋势图共用（此前设置页的排列是
 * 柱状=0/堆叠=1/面积=2，而 StatisticsScreen 按 0=折线/1=面积/2=柱状绘制，
 * 于是用户选"柱状图"实际看到的是折线图）。
 */
internal const val CHART_STYLE_LINE = 0
internal const val CHART_STYLE_AREA = 1
internal const val CHART_STYLE_BARS = 2

internal fun chartStyle(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("chart_style", 0)
}

internal fun setChartStyle(context: Context, style: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("chart_style", style).apply()
}

/* ── Trend chart range: 0=24h 1=7d 2=30d 3=all (persisted) ────── */

internal fun chartRange(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("chart_range", 0)
}

internal fun setChartRange(context: Context, range: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("chart_range", range).apply()
}

/* ── Real-time WebSocket push toggle ──────────────────────────── */

internal fun isRealtimeEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getBoolean("realtime_enabled", false)
}

internal fun setRealtimeEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putBoolean("realtime_enabled", enabled).apply()
}

/* ── Theme mode: 0 = follow system, 1 = light, 2 = dark ────────── */

internal fun themeMode(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("theme_mode", 0)
}

internal fun setThemeMode(context: Context, mode: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("theme_mode", mode).apply()
}

/* ── Light mode: release the app's RAM once the boot work is done ──
   Single source of truth is PreferenceHelper: the toggle used to write its own
   "light_mode" key in a different preference file, and no code ever read it -
   which is why the switch did nothing. */

internal fun isLightMode(context: Context): Boolean =
    org.adaway.helper.PreferenceHelper.getLightMode(context)

internal fun setLightMode(context: Context, enabled: Boolean) =
    org.adaway.helper.PreferenceHelper.setLightMode(context, enabled)


/**
 * Settings screen: lets the user choose which detected apps are tracked
 * in the per-app statistics (connections / requests / blocked / SNI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: StatsViewModel) {
    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps = serverStats?.apps.orEmpty()
    var refreshKey by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Appearance (theme) ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_theme_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_theme_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var theme by remember { mutableStateOf(themeMode(context)) }
                    LaunchedEffect(Unit) { theme = themeMode(context) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(0, R.string.compose_settings_theme_system, 0),
                            Triple(1, R.string.compose_settings_theme_light, 1),
                            Triple(2, R.string.compose_settings_theme_dark, 2),
                        ).forEach { (mode, labelRes, _) ->
                            FilterChip(
                                selected = theme == mode,
                                onClick = {
                                    theme = mode
                                    setThemeMode(context, mode)
                                    // Recreate activity to apply theme
                                    (context as? android.app.Activity)?.recreate()
                                },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }
                }
            }

// ── 拦截模式：127.0.0.1（本机拦截页）/ 0.0.0.0（空路由，可与 AdGuard 等共存）──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_block_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_block_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 与 AdGuard 共存：检测到（已安装 + VPN 在跑）就给出可直接照抄的配置
                    val adguardActive = remember {
                        org.adaway.util.AdGuardPresence.shouldSuggest(context)
                    }
                    var adguardDismissed by remember {
                        mutableStateOf(org.adaway.util.AdGuardPresence.isHintDismissed(context))
                    }
                    if (adguardActive && !adguardDismissed) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.compose_settings_adguard_title),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(
                                        R.string.compose_settings_adguard_steps,
                                        org.adaway.util.AdGuardPresence.suggestedIpv4(),
                                        org.adaway.util.AdGuardPresence.suggestedIpv6(),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val text = org.adaway.util.AdGuardPresence.suggestedIpv4() +
                                            " / " + org.adaway.util.AdGuardPresence.suggestedIpv6()
                                        val clipboard = context.getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE,
                                        ) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(
                                            android.content.ClipData.newPlainText("AdGuard", text),
                                        )
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_settings_adguard_copied),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }) { Text(stringResource(R.string.compose_settings_adguard_copy)) }
                                    androidx.compose.material3.TextButton(onClick = {
                                        org.adaway.util.AdGuardPresence.dismissHint(context)
                                        adguardDismissed = true
                                    }) { Text(stringResource(R.string.compose_settings_adguard_dismiss)) }
                                }
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.compose_settings_block_mode_adguard_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var blockMode by remember { mutableStateOf(viewModel.currentBlockMode()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.LOCALHOST,
                            onClick = {
                                val want = org.adaway.util.BlockMode.LOCALHOST
                                blockMode = want
                                viewModel.applyBlockMode(want) { ok ->
                                    /*
                                     * 失败时不能留着已选中的假象：hosts 应用失败或没有 root 时
                                     * 用户会以为已经切过去了。这里统一回读真实模式并如实提示，
                                     * 不再只有 HIJACK 才报错。
                                     */
                                    blockMode = viewModel.currentBlockMode()
                                    if (!ok) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_settings_block_mode_apply_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            label = {
                                // S5：推荐档提到最前并改名——它是"回答被拦域"的最省资源方式
                                Text(stringResource(R.string.compose_settings_block_mode_recommended))
                            },
                        )
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.NULL_ROUTE,
                            onClick = {
                                val want = org.adaway.util.BlockMode.NULL_ROUTE
                                blockMode = want
                                viewModel.applyBlockMode(want) { ok ->
                                    /*
                                     * 失败时不能留着已选中的假象：hosts 应用失败或没有 root 时
                                     * 用户会以为已经切过去了。这里统一回读真实模式并如实提示，
                                     * 不再只有 HIJACK 才报错。
                                     */
                                    blockMode = viewModel.currentBlockMode()
                                    if (!ok) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_settings_block_mode_apply_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            label = {
                                Text(stringResource(R.string.compose_settings_block_mode_null))
                            },
                        )
                        // 新增：劫持全部流量并由本机服务器过滤（类 AdGuard）
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.HIJACK,
                            onClick = {
                                blockMode = org.adaway.util.BlockMode.HIJACK
                                viewModel.applyBlockMode(org.adaway.util.BlockMode.HIJACK) { ok ->
                                    if (!ok) {
                                        // 端口没监听 / 无 root：不要静默失败
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_settings_block_mode_hijack_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            label = {
                                Text(stringResource(R.string.compose_settings_block_mode_hijack))
                            },
                        )
                    }
                    // ── 自定义重定向地址（hosts 档：被拦域指向哪里）──
                    var customIpv4 by remember { mutableStateOf(org.adaway.util.BlockMode.ipv4Target(context)) }
                    var customIpv6 by remember { mutableStateOf(org.adaway.util.BlockMode.ipv6Target(context)) }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.CUSTOM,
                            onClick = {
                                blockMode = org.adaway.util.BlockMode.CUSTOM
                                customIpv4 = org.adaway.util.BlockMode.ipv4Target(context)
                                customIpv6 = org.adaway.util.BlockMode.ipv6Target(context)
                            },
                            label = { Text(stringResource(R.string.compose_settings_block_mode_custom)) },
                        )
                    }
                    if (blockMode == org.adaway.util.BlockMode.CUSTOM) {
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = customIpv4,
                            onValueChange = { customIpv4 = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customIpv4.isNotBlank() &&
                                !org.adaway.util.BlockMode.isValidTarget(customIpv4),
                            label = { Text(stringResource(R.string.compose_settings_custom_ipv4)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = customIpv6,
                            onValueChange = { customIpv6 = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customIpv6.isNotBlank() &&
                                !org.adaway.util.BlockMode.isValidTarget(customIpv6),
                            label = { Text(stringResource(R.string.compose_settings_custom_ipv6)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.compose_settings_custom_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(
                            enabled = org.adaway.util.BlockMode.isValidTarget(customIpv4) &&
                                org.adaway.util.BlockMode.isValidTarget(customIpv6),
                            onClick = {
                                viewModel.applyCustomAddresses(customIpv4, customIpv6) { ok ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (ok) R.string.compose_settings_custom_saved
                                            else R.string.compose_settings_custom_failed,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ) { Text(stringResource(R.string.compose_settings_custom_apply)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            when (blockMode) {
                                org.adaway.util.BlockMode.NULL_ROUTE ->
                                    R.string.compose_settings_block_mode_null_hint
                                org.adaway.util.BlockMode.CUSTOM ->
                                    R.string.compose_settings_block_mode_custom_hint
                                org.adaway.util.BlockMode.HIJACK ->
                                    R.string.compose_settings_block_mode_hijack_hint
                                else -> R.string.compose_settings_block_mode_localhost_hint
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 劫持过滤走 HTTPS 中间人：没有信任本机 CA 时会提示
                    if (blockMode == org.adaway.util.BlockMode.HIJACK &&
                        !org.adaway.util.WebServerUtils.isUserCertificateInstalled(context) &&
                        !org.adaway.util.WebServerUtils.isSystemCertificateInstalled(context)
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.compose_settings_block_mode_hijack_ca),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

// ── Data management ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_data_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_data_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val prefs = context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
                    /*
                     * 二次确认 + 真正调用原生重置。
                     *
                     * 之前这里只删了 s_hist_pos / s_daily_pos 两个设置键 —— 真正的计数、历史曲线、
                     * 应用统计与查询日志都在原生进程内存里（并各自有 .dat 文件），所以按钮点了
                     * 等于没点、也没有任何反馈。现在：第一次点击进入确认态，第二次才真正清除；
                     * 清除走回环管理端口（8686）的 /internal-reset?what=all，并如实反馈结果。
                     * 刻意不用协程/新 import：后台线程 + Handler 回主线程即可。
                     */
                    var clearArmed by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            if (!clearArmed) {
                                clearArmed = true
                            } else {
                                clearArmed = false
                                Thread {
                                    val done = org.adaway.util.WebServerUtils.resetStatistics(context)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(
                                            context,
                                            if (done) R.string.compose_settings_clear_stats_ok
                                            else R.string.compose_settings_clear_stats_fail,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }.start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            stringResource(
                                if (clearArmed) R.string.compose_settings_clear_stats_confirm
                                else R.string.compose_settings_clear_stats,
                            )
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 备份/恢复：导出规则+设置到文件，或从文件恢复
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                exportBackup(context)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.compose_settings_backup))
                        }
                        OutlinedButton(
                            onClick = {
                                importBackup(context, viewModel) {
                                    // 恢复后重启应用使全部设置生效
                                    val pm = context.packageManager
                                    val launch = pm.getLaunchIntentForPackage(context.packageName)
                                    if (launch != null) {
                                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        context.startActivity(launch)
                                    }
                                    kotlin.system.exitProcess(0)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.compose_settings_restore))
                        }
                    }
                }
            }

            // ── Web server settings ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_ws_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_ws_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var bindAll by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isBindAll(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_ws_bind),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(
                                    if (bindAll) R.string.compose_settings_ws_bind_all
                                    else R.string.compose_settings_ws_bind_loop,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = bindAll,
                            onCheckedChange = { v ->
                                bindAll = v
                                org.adaway.util.WebServerUtils.setWebServerSettings(
                                    context, v,
                                    org.adaway.util.WebServerUtils.getHttpPort(context),
                                    org.adaway.util.WebServerUtils.getHttpsPort(context),
                                )
                                org.adaway.util.WebServerUtils.stopWebServer()
                                org.adaway.util.WebServerUtils.startWebServer(context)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.compose_settings_ws_http_port),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var httpPort by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.getHttpPort(context))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(80, 8080, 8888).forEach { p ->
                            FilterChip(
                                selected = httpPort == p,
                                onClick = {
                                    httpPort = p
                                    org.adaway.util.WebServerUtils.setWebServerSettings(
                                        context, bindAll, p,
                                        org.adaway.util.WebServerUtils.getHttpsPort(context),
                                    )
                                    org.adaway.util.WebServerUtils.stopWebServer()
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                },
                                label = { Text("$p") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.compose_settings_ws_https_port),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var httpsPort by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.getHttpsPort(context))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(443, 8443).forEach { p ->
                            FilterChip(
                                selected = httpsPort == p,
                                onClick = {
                                    httpsPort = p
                                    org.adaway.util.WebServerUtils.setWebServerSettings(
                                        context, bindAll,
                                        org.adaway.util.WebServerUtils.getHttpPort(context), p,
                                    )
                                    org.adaway.util.WebServerUtils.stopWebServer()
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                },
                                label = { Text("$p") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // ── 轻量模式（省内存）──
                    var lightMode by remember { mutableStateOf(isLightMode(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_light_mode),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_light_mode_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lightMode,
                            onCheckedChange = { v ->
                                lightMode = v
                                setLightMode(context, v)
                                // Apply the cadence change immediately: light mode
                                // switches the recovery check between 6 h and the
                                // 5 min / 15 min pair.
                                org.adaway.broadcast.ServerWatchdogWorker.ensureScheduled(context)
                                if (!v) {
                                    // Leaving light mode: make sure it is running.
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                }
                            },
                        )
                    }
                    // ── 固定证书的应用：由用户决定怎么处理 ──
                    Spacer(Modifier.height(8.dp))
                    var pinDeny by remember {
                        mutableStateOf(
                            org.adaway.util.WebServerUtils.getPinPolicyMode(context) == "deny",
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_pin_policy),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_pin_policy_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = pinDeny,
                            onCheckedChange = { v ->
                                pinDeny = v
                                org.adaway.util.WebServerUtils.setPinPolicyMode(
                                    context,
                                    if (v) "deny" else "off",
                                )
                            },
                        )
                    }
                    // ── 可见型占位（默认关；关闭时行为与历史版本一致）──
                    Spacer(Modifier.height(8.dp))
                    var visibleReply by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isBlockReplyVisible(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_visible_reply),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_visible_reply_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = visibleReply,
                            onCheckedChange = { v ->
                                visibleReply = v
                                org.adaway.util.WebServerUtils.setBlockReplyVisible(context, v)
                            },
                        )
                    }
                    // ── F5：请求去重 / 重试护栏（各自独立，默认都关）──
                    Spacer(Modifier.height(8.dp))
                    var dedupOn by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isDedupEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_dedup),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_dedup_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = dedupOn,
                            onCheckedChange = { v ->
                                dedupOn = v
                                org.adaway.util.WebServerUtils.setDedupEnabled(context, v)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var guardOn by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isRetryGuardEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_retry_guard),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_retry_guard_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = guardOn,
                            onCheckedChange = { v ->
                                guardOn = v
                                org.adaway.util.WebServerUtils.setRetryGuardEnabled(context, v)
                            },
                        )
                    }
                    // ── F5 续：决策缓存 / 按域熔断（各自独立，默认都关）──
                    Spacer(Modifier.height(8.dp))
                    var cacheOn by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isCacheReuseEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_cache_reuse),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_cache_reuse_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cacheOn,
                            onCheckedChange = { v ->
                                cacheOn = v
                                org.adaway.util.WebServerUtils.setCacheReuseEnabled(context, v)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var breakerOn by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isCircuitBreakerEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_circuit_breaker),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_circuit_breaker_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = breakerOn,
                            onCheckedChange = { v ->
                                breakerOn = v
                                org.adaway.util.WebServerUtils.setCircuitBreakerEnabled(context, v)
                            },
                        )
                    }
                    // ── F4 续：按域覆盖响应策略（默认关）──
                    Spacer(Modifier.height(8.dp))
                    var overrideOn by remember {
                        mutableStateOf(org.adaway.util.WebServerUtils.isRuleOverrideEnabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_rule_override),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_rule_override_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = overrideOn,
                            onCheckedChange = { v ->
                                overrideOn = v
                                org.adaway.util.WebServerUtils.setRuleOverrideEnabled(context, v)
                            },
                        )
                    }
                    // ── 开机自动启动 ──
                    Spacer(Modifier.height(8.dp))
                    var autostart by remember {
                        mutableStateOf(
                            org.adaway.helper.PreferenceHelper.getWebServerEnabled(context),
                        )
                    }
                    /*
                     * 有 Magisk 时，开机由 init 侧直接拉起服务器（service.d 脚本），
                     * 不再依赖开机广播——国产 ROM 常把广播延后甚至丢掉，这也是
                     * “必须手动打开一次应用才会启动”的根因。0 = 未写入，1 = 已写入，
                     * -1 = 本机没有 Magisk。
                     */
                    val autostartScope = rememberCoroutineScope()
                    var bootScriptState by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        bootScriptState = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            when {
                                !org.adaway.model.root.MagiskBootScript.isSupported() -> -1
                                org.adaway.model.root.MagiskBootScript.isInstalled() -> 1
                                else -> 0
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_autostart),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_autostart_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autostart,
                            onCheckedChange = { v ->
                                autostart = v
                                org.adaway.helper.PreferenceHelper
                                    .setWebServerEnabled(context, v)
                                if (v) {
                                    // 打开时顺手修掉两个最常见的“自启动失败”原因：
                                    // 接收器被系统/清理软件禁用、启动任务没排队。
                                    org.adaway.broadcast.BootReceiver.ensureEnabled(context)
                                    org.adaway.broadcast.BootReceiver
                                        .scheduleStart(context, "user enabled autostart")
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                    // root shell 不能跑在主线程上
                                    autostartScope.launch {
                                        val supported = kotlinx.coroutines.withContext(
                                            kotlinx.coroutines.Dispatchers.IO,
                                        ) {
                                            org.adaway.model.root.MagiskBootScript.install(context)
                                        }
                                        bootScriptState = if (supported) 1 else -1
                                    }
                                } else {
                                    org.adaway.util.WebServerUtils.stopWebServer()
                                    autostartScope.launch {
                                        kotlinx.coroutines.withContext(
                                            kotlinx.coroutines.Dispatchers.IO,
                                        ) {
                                            org.adaway.model.root.MagiskBootScript.uninstall()
                                        }
                                        bootScriptState = 0
                                    }
                                }
                            },
                        )
                    }
                    /*
                     * 自启动失败时真正有用的两个动作（原先藏在“自启动诊断”的展开区里，
                     * 只读的诊断行已按反馈移除）。MIUI/EMUI/ColorOS 除了电池优化之外
                     * 还会单独拦截开机广播，需要在系统设置里再允许“自启动/后台运行”。
                     */
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openBatterySettings(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.compose_settings_autostart_battery_button))
                        }
                        OutlinedButton(
                            onClick = {
                                org.adaway.broadcast.BootReceiver.ensureEnabled(context)
                                org.adaway.broadcast.BootReceiver
                                    .scheduleStart(context, "user retried")
                                org.adaway.util.WebServerUtils.startWebServer(context)
                                Toast.makeText(
                                    context,
                                    R.string.compose_settings_autostart_retry_toast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.compose_settings_autostart_retry))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    /*
                     * 可选的常驻守护：前台服务不被系统延迟调度，服务器被杀后能在
                     * 数秒内拉起来（WorkManager 兜底要等十几分钟）。代价是一条常驻
                     * 通知，所以默认关闭、由用户自己决定。
                     */
                    var keepAlive by remember {
                        mutableStateOf(
                            org.adaway.helper.PreferenceHelper.getKeepAliveEnabled(context),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_keepalive),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_keepalive_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = keepAlive,
                            onCheckedChange = { v ->
                                keepAlive = v
                                org.adaway.helper.PreferenceHelper
                                    .setKeepAliveEnabled(context, v)
                                if (v) {
                                    org.adaway.broadcast.ServerKeepAliveService.start(context)
                                } else {
                                    org.adaway.broadcast.ServerKeepAliveService.stop(context)
                                }
                            },
                        )
                    }
                    if (bootScriptState != 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                if (bootScriptState == 1) {
                                    R.string.compose_settings_autostart_bootscript_on
                                } else {
                                    R.string.compose_settings_autostart_bootscript_na
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bootScriptState == 1) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    /*
                     * 一行诊断（不是以前那个多行只读面板）：上次开机到底是哪条路跑的、
                     * 结果如何。失败时点开详情能看到环形记录、开机脚本状态和脚本自己的
                     * 日志 —— “打开 App 才自启”这类问题只能靠这些定位，靠猜没有意义。
                     */
                    var bootDiag by remember { mutableStateOf("") }
                    var bootDetail by remember { mutableStateOf("") }
                    var bootDialog by remember { mutableStateOf(false) }
                    var bootTestResult by remember { mutableStateOf("") }
                    var bootTesting by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        bootDiag = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            bootDiagSummary(context)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (bootDiag.isEmpty()) {
                            stringResource(R.string.compose_settings_boot_diag_never)
                        } else {
                            bootDiag
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            bootTesting = false
                            bootTestResult = ""
                            bootDialog = true
                            autostartScope.launch {
                                bootDetail = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    buildBootDetail(context)
                                }
                            }
                        },
                    )
                    if (bootDialog) {
                        // Hoisted: stringResource() is @Composable and cannot be
                        // called from inside an onClick lambda.
                        val testResultLabel = stringResource(
                            R.string.compose_settings_boot_test_result_title,
                        )
                        AlertDialog(
                            onDismissRequest = {
                                bootDialog = false
                                autostartScope.launch {
                                    bootDiag = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        bootDiagSummary(context)
                                    }
                                }
                            },
                            title = {
                                Text(stringResource(R.string.compose_settings_boot_diag_title))
                            },
                            text = {
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        bootDetail,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (bootTesting) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            stringResource(
                                                R.string.compose_settings_boot_test_running,
                                            ),
                                        )
                                    } else if (bootTestResult.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(testResultLabel + "：" + bootTestResult)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        bootTesting = true
                                        bootTestResult = ""
                                        autostartScope.launch {
                                            bootTestResult = kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO,
                                            ) {
                                                org.adaway.model.root.MagiskBootScript
                                                    .runNow(context)
                                            }
                                            bootTesting = false
                                            bootDetail = kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO,
                                            ) {
                                                buildBootDetail(context)
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.compose_settings_boot_test_script,
                                        ),
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(
                                            Context.CLIPBOARD_SERVICE,
                                        ) as android.content.ClipboardManager
                                        val text = if (bootTestResult.isEmpty()) {
                                            bootDetail
                                        } else {
                                            bootDetail + "\n\n" + testResultLabel + "：" +
                                                bootTestResult
                                        }
                                        clipboard.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "adblock-boot-diag", text,
                                            ),
                                        )
                                        Toast.makeText(
                                            context,
                                            R.string.compose_settings_boot_diag_copied,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                ) {
                                    Text(stringResource(R.string.compose_settings_boot_diag_copy))
                                }
                            },
                        )
                    }
                    // ── 证书管理区 ──
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.compose_settings_cert_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 证书状态（后台获取避免主线程网络）
                    /*
                     * 四态 + 可刷新：之前只在进页面时算一次，安装/删除之后不更新，用户看到的是旧结论；
                     * 而且"已安装"只看用户库 —— targetSdk>=24 的 App 默认不信任用户库，必须把
                     * "用户级"与"系统级"分开显示（系统级要写 /system 或 APEX，或 apexdata ——
                     * 后者非空时 Conscrypt 优先读它，只覆盖 APEX 是不够的）。
                     */
                    var certStateRes by remember { mutableIntStateOf(R.string.pref_webserver_state_not_running) }
                    var certTrust by remember { mutableIntStateOf(0) }
                    var certRefresh by remember { mutableIntStateOf(0) }
                    LaunchedEffect(certRefresh) {
                        val probe = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            org.adaway.util.WebServerUtils.getWebServerState(context) to
                                org.adaway.util.WebServerUtils.certificateTrustLevel(context)
                        }
                        certStateRes = probe.first
                        certTrust = probe.second
                    }
                    Text(
                        stringResource(certStateRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (certStateRes == R.string.pref_webserver_state_running_and_installed ||
                            certStateRes == R.string.pref_webserver_state_running_and_installed_system)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    if (certTrust > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(
                                if (certTrust >= org.adaway.util.WebServerUtils.TRUST_SYSTEM)
                                    R.string.compose_settings_cert_level_system
                                else R.string.compose_settings_cert_level_user,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (certTrust >= org.adaway.util.WebServerUtils.TRUST_SYSTEM)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ── 一键信任（写入用户库）/ 手动安装 ──
                    /* 复用同一次探测的结果，避免两处各自查一遍还互相不一致 */
                    val certTrusted = certTrust > 0
                    Spacer(Modifier.height(8.dp))
                    if (certTrusted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.compose_settings_cert_trusted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val ok = org.adaway.util.WebServerUtils
                                        .installCertificateToUserStore(context)
                                    /*
                                     * Verify instead of trusting the return
                                     * value: the root copy can "succeed" while
                                     * the store still does not list the CA
                                     * (wrong hash name, SELinux context, a
                                     * Magisk module that shadows the file).
                                     * The user then wonders why HTTPS still
                                     * warns.
                                     */
                                    val verified = ok && org.adaway.util.WebServerUtils
                                        .isUserCertificateInstalled(context)
                                    Toast.makeText(
                                        context,
                                        if (verified) R.string.compose_settings_cert_trust_ok
                                        else R.string.compose_settings_cert_trust_fail,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    /* 重新探测：状态与信任级别都要按事实刷新，而不是沿用旧结论。
                                       certTrusted 现在是派生值（certTrust > 0），不能再赋值。 */
                                    certRefresh++
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.compose_settings_cert_trust))
                            }
                            OutlinedButton(
                                onClick = {
                                    org.adaway.util.WebServerUtils.installUserCertificate(context)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.compose_settings_cert_install_manual))
                            }
                        }
                    }
                    // 剩余天数
                    val daysLeft = org.adaway.util.WebServerUtils.getCertificateDaysLeft(context)
                    if (daysLeft != null) {
                        Text(
                            stringResource(R.string.compose_settings_cert_days, daysLeft),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (daysLeft < 30) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    /*
                     * NOTE: the SHA-256 fingerprint block (and its QR share
                     * button) used to sit here. It is not part of installing or
                     * trusting the CA - it only helped someone compare the
                     * fingerprint by hand - and it made this card the busiest
                     * part of the screen. Removed on request: the card now ends
                     * with the one action that matters, exporting the
                     * certificate.
                     */
                    Spacer(Modifier.height(4.dp))
                    // 导出证书到 Download 目录（供其他设备/模块使用）
                    OutlinedButton(
                        onClick = { exportCertificate(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compose_settings_cert_export))
                    }
                }
            }
            // ── Statistics chart settings ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_charts_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_charts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var refreshKey by remember { mutableStateOf(0) }
                    val chartToggles = listOf(
                        Triple("chart_lifetime", R.string.compose_settings_chart_lifetime, R.string.compose_settings_chart_lifetime_hint),
                        Triple("chart_rate", R.string.compose_settings_chart_rate, R.string.compose_settings_chart_rate_hint),
                        Triple("chart_donut", R.string.compose_settings_chart_donut, R.string.compose_settings_chart_donut_hint),
                        Triple("chart_trend", R.string.compose_settings_chart_trend, R.string.compose_settings_chart_trend_hint),
                        Triple("chart_conn", R.string.compose_settings_chart_conn, R.string.compose_settings_chart_conn_hint),
                        Triple("chart_apps", R.string.compose_settings_chart_apps, R.string.compose_settings_chart_apps_hint),
                        Triple("chart_top_hosts", R.string.compose_settings_chart_top_hosts, R.string.compose_settings_chart_top_hosts_hint),
                        Triple("chart_certs", R.string.compose_settings_chart_certs, R.string.compose_settings_chart_certs_hint),
                        Triple("chart_qlog", R.string.compose_settings_chart_qlog, R.string.compose_settings_chart_qlog_hint),
                    )
                    chartToggles.forEach { (key, labelRes, hintRes) ->
                        val enabled = remember(refreshKey, key) { isChartEnabled(context, key) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(hintRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { v ->
                                    setChartEnabled(context, key, v)
                                    refreshKey++
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.compose_settings_chart_style),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var style by remember { mutableStateOf(chartStyle(context)) }
                    // Sync from preferences on composition start
                    LaunchedEffect(Unit) { style = chartStyle(context) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            R.string.compose_stats_style_line to CHART_STYLE_LINE,
                            R.string.compose_stats_style_area to CHART_STYLE_AREA,
                            R.string.compose_stats_style_bars to CHART_STYLE_BARS,
                        ).forEach { (labelRes, s) ->
                            FilterChip(
                                selected = style == s,
                                onClick = {
                                    style = s
                                    setChartStyle(context, s)
                                },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 实时推送开关（WebSocket）
                    var realtime by remember { mutableStateOf(isRealtimeEnabled(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_realtime),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_realtime_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = realtime,
                            onCheckedChange = { v ->
                                realtime = v
                                setRealtimeEnabled(context, v)
                                // 实际启停 WebSocket 连接
                                if (v) viewModel.startRealtime() else viewModel.stopRealtime()
                            },
                        )
                    }
                }
            }

            // ── 调试（崩溃日志 / 遥测）──
            // old-pref sharing (Constants.PREFS_NAME)
            val legacyPrefs = context.getSharedPreferences(org.adaway.util.Constants.PREFS_NAME, Context.MODE_PRIVATE)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_debug_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_debug_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 旧版偏好键共享：使用 Constants.PREFS_NAME（preferences），与 PreferenceHelper/ApplicationLog 联动
                    var debugLog by remember { mutableStateOf(legacyPrefs.getBoolean("debugEnabled", false)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_debug_log),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_debug_log_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = debugLog,
                            onCheckedChange = { v ->
                                debugLog = v
                                legacyPrefs.edit().putBoolean("debugEnabled", v).apply()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var telemetry by remember { mutableStateOf(legacyPrefs.getBoolean("enableTelemetry", false)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_debug_telemetry),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_debug_telemetry_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = telemetry,
                            onCheckedChange = { v ->
                                telemetry = v
                                legacyPrefs.edit().putBoolean("enableTelemetry", v).apply()
                                org.adaway.util.log.SentryLog.setEnabled(context.applicationContext as android.app.Application, v)
                            },
                        )
                    }
                }
            }

            // ── 更多设置（IPv6 / 自动更新 / 关于）──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_more_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    var ipv6Enabled by remember { mutableStateOf(legacyPrefs.getBoolean("enableIpv6", false)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_ipv6),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_ipv6_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ipv6Enabled,
                            onCheckedChange = { v ->
                                ipv6Enabled = v
                                legacyPrefs.edit().putBoolean("enableIpv6", v).apply()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 自动更新（旧键共享，直接生效）
                    UpdateSwitchRow(
                        prefs = legacyPrefs,
                        key = "updateCheckAppStartup",
                        def = true,
                        title = stringResource(R.string.compose_settings_update_app_startup),
                        hint = stringResource(R.string.compose_settings_update_app_startup_hint),
                    )
                    UpdateSwitchRow(
                        prefs = legacyPrefs,
                        key = "updateCheckAppDaily",
                        def = true,
                        title = stringResource(R.string.compose_settings_update_app_daily),
                        hint = stringResource(R.string.compose_settings_update_app_daily_hint),
                    )
                    UpdateSwitchRow(
                        prefs = legacyPrefs,
                        key = "includeBetaReleases",
                        def = false,
                        title = stringResource(R.string.compose_settings_update_beta),
                        hint = stringResource(R.string.compose_settings_update_beta_hint),
                    )
                    UpdateSwitchRow(
                        prefs = legacyPrefs,
                        key = "updateCheckHostsDaily",
                        def = true,
                        title = stringResource(R.string.compose_settings_update_hosts),
                        hint = stringResource(R.string.compose_settings_update_hosts_hint),
                    )
                    UpdateSwitchRow(
                        prefs = legacyPrefs,
                        key = "updateOnlyOnWifi",
                        def = false,
                        title = stringResource(R.string.compose_settings_update_wifi),
                        hint = stringResource(R.string.compose_settings_update_wifi_hint),
                    )
                    // 关于：GitHub / 问题反馈
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/wxcvm/AdAway")))
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.compose_settings_about_repo),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/wxcvm/AdAway/issues")))
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.compose_settings_about_issues),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // 版本
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                        } catch (e: Exception) {
                            "unknown"
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.compose_settings_version, versionName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // 检查更新（更新源：GitHub Releases / wxcvm/AdAway）
                    UpdateCheckRow(viewModel = viewModel, currentVersion = versionName)
                }
            }

            // ── Allowlist (bypass blocking) ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.compose_settings_allowlist_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_allowlist_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 默认白名单（系统门户登录，自动维护）
                    Text(
                        stringResource(R.string.compose_settings_allowlist_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    defaultAllowlistUids(context).forEach { uid ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                appNameForUid(uid),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.compose_settings_allowlist_uid, uid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 用户放行的应用（可移除）
                    Text(
                        stringResource(R.string.compose_settings_allowlist_user),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val userUids = userAllowedUids(context)
                    if (userUids.isEmpty()) {
                        Text(
                            stringResource(R.string.compose_settings_allowlist_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        userUids.forEach { uid ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    appNameForUid(uid),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.compose_settings_allowlist_uid, uid),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = {
                                    setAppAllowed(context, uid, false)
                                    refreshKey++
                                }) {
                                    Icon(
                                        Icons.Outlined.RemoveCircle,
                                        contentDescription = stringResource(R.string.compose_settings_allowlist_remove),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── App monitoring (可折叠) ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var appsExpanded by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { appsExpanded = !appsExpanded },
                    ) {
                        Icon(
                            Icons.Outlined.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.compose_settings_apps_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.compose_settings_apps_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (appsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (appsExpanded) R.string.compose_action_collapse else R.string.compose_action_expand,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = appsExpanded) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            if (apps.isEmpty()) {
                                Text(
                                    stringResource(R.string.compose_settings_apps_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                // Re-read switch states after toggling (refreshKey changes)
                                apps.sortedByDescending { it.blocked + it.requests }.forEach { app ->
                                    val monitored = remember(refreshKey, app.uid) {
                                        isAppMonitored(context, app.uid)
                                    }
                                    val allowed = remember(refreshKey, app.uid) {
                                        isAppAllowed(context, app.uid)
                                    }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        appNameForUid(app.uid),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.compose_settings_app_sub,
                                            app.connections,
                                            app.requests,
                                            app.blocked,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                /*
                                 * 放行开关：该应用流量完全绕过拦截 —— 这不是普通显示偏好，而是会改变
                                 * 拦截效果的策略变更。首次放行时解释后果，并说明怎么撤销（把开关拨回去
                                 * 即可）；解释只弹一次，之后不再打扰。
                                 */
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Switch(
                                        checked = allowed,
                                        onCheckedChange = { v ->
                                            setAppAllowed(context, app.uid, v)
                                            refreshKey++
                                            val sp = context.getSharedPreferences(
                                                PREFS_GENERAL,
                                                Context.MODE_PRIVATE,
                                            )
                                            if (v) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        if (sp.getBoolean("app_allow_explained", false)) {
                                                            R.string.compose_settings_app_allow_hint
                                                        } else {
                                                            R.string.compose_settings_app_allow_explain
                                                        },
                                                    ),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                                sp.edit().putBoolean("app_allow_explained", true).apply()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.compose_settings_app_allow_undone),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.compose_settings_app_allow),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allowed) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                /*
                                 * 第二个开关此前是裸 Switch：没有可见文字、也没有无障碍标签，
                                 * 读屏只会念"开关，开启/关闭"，用户分不清它和左边的放行开关各管什么。
                                 * 这里照放行开关的样式补上文字标签（并被左侧放行开关形成对照）。
                                 */
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Switch(
                                        checked = monitored,
                                        onCheckedChange = { newValue ->
                                            setAppMonitored(context, app.uid, newValue)
                                            refreshKey++
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.compose_settings_app_monitor),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (monitored) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        }
                    }
                }
                }
            }
            // ── About ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_about_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val pkgInfo = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    // 连点版本号 5 次解锁隐藏的开发者选项（拦截占位图设置）
                    var aboutTaps by remember { mutableIntStateOf(0) }
                    var hiddenUnlocked by remember { mutableStateOf(false) }
                    Text(
                        stringResource(
                            R.string.compose_settings_about_version,
                            pkgInfo?.versionName ?: "?",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                aboutTaps++
                                if (aboutTaps >= 5) hiddenUnlocked = true
                            },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.compose_settings_about_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 隐藏的开发者选项：自定义拦截占位图（连点版本号解锁）
                    if (hiddenUnlocked) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.compose_settings_block_image_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                            if (uri != null) {
                                val success = org.adaway.util.WebServerUtils.setCustomBlockImage(context, uri)
                                android.widget.Toast.makeText(
                                    context,
                                    if (success) R.string.pref_webserver_block_image_success
                                    else R.string.pref_webserver_block_image_failed,
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pickImageLauncher.launch("image/*")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.compose_settings_block_image_choose))
                            }
                            OutlinedButton(
                                onClick = {
                                    // 恢复默认
                                    org.adaway.util.WebServerUtils.resetBlockImagesToDefault(context)
                                    android.widget.Toast.makeText(
                                        context,
                                        R.string.pref_webserver_block_image_reset_success,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.compose_settings_block_image_reset))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 检查更新行：更新源为 GitHub Releases（wxcvm/AdAway）。
 *
 * 显示当前版本与检查结果；发现新版本时提供下载按钮（下载完成后由
 * ApkDownloadReceiver 校验签名/版本再交给系统安装器）。
 */
@Composable
private fun UpdateCheckRow(viewModel: StatsViewModel, currentVersion: String) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<org.adaway.model.update.Manifest?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.compose_settings_update_check),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    message ?: stringResource(R.string.compose_settings_update_check_hint, currentVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = context.getString(R.string.update_feed_checking)
                    viewModel.checkUpdate(force = true) { manifest, error ->
                        checking = false
                        when {
                            manifest == null -> {
                                available = null
                                message = context.getString(
                                    R.string.update_feed_failed,
                                    error ?: context.getString(R.string.update_feed_unreachable),
                                )
                            }

                            manifest.updateAvailable -> {
                                available = manifest
                                message = context.getString(
                                    R.string.update_feed_available,
                                    manifest.version,
                                )
                            }

                            else -> {
                                available = null
                                message = context.getString(
                                    R.string.update_feed_up_to_date,
                                    manifest.version,
                                )
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.update_feed_button)) }
        }
        available?.let { manifest ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        viewModel.downloadUpdate()
                        message = context.getString(R.string.compose_settings_update_downloading)
                    },
                ) { Text(stringResource(R.string.update_feed_download)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(org.adaway.model.update.UpdateModel.getReleasesPage()),
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.update_feed_manual)) }
            }
            manifest.changelog.takeIf { it.isNotBlank() }?.let { changelog ->
                Text(
                    changelog,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ── 备份 / 恢复 ──────────────────────────────────────────────── */
/**
 * 导出 CA 证书到 Download/adblock-ca.crt，供其他设备/模块（如 Magisk 证书模块）使用。
 * Android 11+ scoped storage：通过 MediaStore 写入公共 Download 目录（无需存储权限）。
 */
private fun exportCertificate(context: Context) {
    try {
        val src = java.io.File(context.filesDir, "webserver/localhost-2410.crt")
        if (!src.exists()) {
            Toast.makeText(context, context.getString(R.string.compose_export_cert_missing), Toast.LENGTH_LONG).show()
            return
        }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adblock-ca.crt")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.compose_export_no_slot), Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, context.getString(R.string.compose_export_cert_ok), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export certificate")
        Toast.makeText(context, context.getString(R.string.compose_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/**
 * 导出备份：把所有偏好（compose_general / compose_app_monitor /
 * compose_webserver）序列化为 JSON 写入 Download/adblock-backup.json，
 * 并提示用户。Android 11+ 通过 MediaStore 写入（无需存储权限）。
 */
private fun exportBackup(context: Context) {
    try {
        val prefsNames = listOf(PREFS_GENERAL, PREFS_MONITOR, "compose_webserver")
        val json = org.json.JSONObject()
        prefsNames.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val section = org.json.JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> section.put(k, v)
                    is Int -> section.put(k, v)
                    is Long -> section.put(k, v)
                    is Float -> section.put(k, v)
                    is String -> section.put(k, v)
                    is java.util.Set<*> -> section.put(k, org.json.JSONArray(v.toList()))
                }
            }
            json.put(name, section)
        }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adblock-backup.json")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.compose_backup_no_slot), Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, context.getString(R.string.compose_backup_ok), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export backup")
        Toast.makeText(context, context.getString(R.string.compose_backup_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/**
 * 导入备份：用文件选择器挑选 adblock-backup.json，
 * 解析后逐项写回偏好，完成后回调（重启应用使全部生效）。
 */
private fun importBackup(
    context: Context,
    viewModel: StatsViewModel,
    onDone: () -> Unit,
) {
    try {
        // 通过 MediaStore 查询 Download 中的 adblock-backup.json（scoped storage 兼容）
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        var text: String? = null
        resolver.query(
            collection,
            projection,
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("adblock-backup.json"),
            "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }
        if (text == null) {
            Toast.makeText(context, context.getString(R.string.compose_backup_none), Toast.LENGTH_LONG).show()
            return
        }
        val json = org.json.JSONObject(text)
        val prefsNames = listOf(PREFS_GENERAL, PREFS_MONITOR, "compose_webserver")
        prefsNames.forEach { name ->
            val section = json.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // 先清空再写入
            prefs.all.keys.forEach { editor.remove(it) }
            val it = section.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = section.get(k)
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putInt(k, v.toInt())
                    is String -> editor.putString(k, v)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<String>()
                        for (i in 0 until v.length()) list.add(v.getString(i))
                        editor.putStringSet(k, list.toSet())
                    }
                }
            }
            editor.apply()
        }
        Toast.makeText(context, context.getString(R.string.compose_backup_restored), Toast.LENGTH_LONG).show()
        onDone()
    } catch (e: Exception) {
        Timber.w(e, "Failed to import backup")
        Toast.makeText(context, context.getString(R.string.compose_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/**
 * 保存 Bitmap 到缓存目录并返回 content:// URI 供分享使用。
 */
private fun saveBitmapToCache(context: Context, bitmap: Bitmap, filename: String): Uri {
    val cacheFile = File(context.cacheDir, filename)
    FileOutputStream(cacheFile).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        cacheFile
    )
}




/**
 * 打开系统的电池优化列表，让用户把本应用设为“不优化”。
 * 这是自启动失败的两大原因之一（另一个是厂商的自启动白名单）。
 */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Timber.w(e, "No battery optimization settings activity")
        Toast.makeText(context, e.message ?: context.getString(R.string.compose_unavailable), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 一行开机诊断摘要：哪条路跑的、结果如何。无记录时返回空串，由界面显示提示文案。
 */
private fun bootDiagSummary(context: Context): String {
    val time = org.adaway.helper.PreferenceHelper.getLastBootTime(context)
    if (time <= 0L) {
        return ""
    }
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    val mechanism = org.adaway.helper.PreferenceHelper.getLastBootMechanism(context)
        .ifEmpty { "—" }
    val result = org.adaway.helper.PreferenceHelper.getLastBootResult(context)
        .ifEmpty { "—" }
    return context.getString(
        R.string.compose_settings_boot_diag_line,
        fmt.format(java.util.Date(time)),
        mechanism,
        result,
    )
}

/**
 * 详情文本：环形开机记录 + 开机脚本（Magisk service.d）状态与脚本日志。
 *
 * <p>“打开 App 才自启”有两种完全不同的原因——广播根本没送到（组件被禁用/OEM 拦截）
 * 与后台任务被系统延后——只有把每条机制的记录和脚本自己的日志都摆出来才能区分。</p>
 */
private fun buildBootDetail(context: Context): String {
    val sb = StringBuilder()
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    sb.append("== 开机尝试记录（新 → 旧）==\n")
    val ring = org.adaway.helper.PreferenceHelper.getBootEventRing(context)
    if (ring.isEmpty()) {
        sb.append("（还没有记录）\n")
    }
    for (line in ring.reversed()) {
        val parts = line.split("|")
        if (parts.size >= 5) {
            val at = parts[0].toLongOrNull() ?: 0L
            sb.append(fmt.format(java.util.Date(at))).append("  ")
                .append(parts[1]).append("  ")
                .append(parts[2]).append("  ")
                .append(parts[3]).append("  ")
                .append(if (parts[4] == "1") "成功" else "未成功")
                .append('\n')
        }
    }
    sb.append("\n== 开机脚本（Magisk/KernelSU service.d）==\n")
    val supported = org.adaway.model.root.MagiskBootScript.isSupported()
    sb.append("service.d 可用：").append(if (supported) "是" else "否（本机没有该目录）").append('\n')
    if (supported) {
        val installed = org.adaway.model.root.MagiskBootScript.isInstalled()
        sb.append("脚本已安装：").append(if (installed) "是" else "否").append('\n')
        sb.append("路径：").append(org.adaway.model.root.MagiskBootScript.scriptPath()).append('\n')
        if (installed) {
            val content = org.adaway.model.root.MagiskBootScript.readScript()
            val complete = content.contains("--http-port") &&
                content.contains("--https-port") &&
                content.contains("--stats-port")
            sb.append("脚本参数校验：")
                .append(if (complete) "完整（含统计端口）" else "不完整，请重新开关一次「开机自动启动」")
                .append('\n')
        }
        sb.append("\n== 脚本日志（").append(org.adaway.model.root.MagiskBootScript.LOG_PATH)
            .append("）==\n")
        sb.append(org.adaway.model.root.MagiskBootScript.readLogTail()).append('\n')
    }
    sb.append("\n== 其他 ==\n")
    sb.append("开机接收器组件：")
        .append(if (org.adaway.broadcast.BootReceiver.ensureEnabled(context)) "已启用" else "无法启用")
        .append('\n')
    sb.append("服务器当前可达：")
        .append(if (org.adaway.util.WebServerUtils.isWebServerReachable(context)) "是" else "否")
        .append('\n')
    return sb.toString()
}

