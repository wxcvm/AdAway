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
        val content = uids.joinToString("\n")
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

/* ── Light mode: keep webserver running only while app is foregrounded ── */

internal fun isLightMode(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getBoolean("light_mode", false)
}

internal fun setLightMode(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putBoolean("light_mode", enabled).apply()
}


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
                    // 与 AdGuard 等其它过滤工具搭配使用的提示
                    Text(
                        stringResource(R.string.compose_settings_block_mode_adguard_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    var blockMode by remember { mutableStateOf(viewModel.currentBlockMode()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.LOCALHOST,
                            onClick = {
                                blockMode = org.adaway.util.BlockMode.LOCALHOST
                                viewModel.applyBlockMode(org.adaway.util.BlockMode.LOCALHOST)
                            },
                            label = {
                                Text(stringResource(R.string.compose_settings_block_mode_localhost))
                            },
                        )
                        FilterChip(
                            selected = blockMode == org.adaway.util.BlockMode.NULL_ROUTE,
                            onClick = {
                                blockMode = org.adaway.util.BlockMode.NULL_ROUTE
                                viewModel.applyBlockMode(org.adaway.util.BlockMode.NULL_ROUTE)
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
                    Button(
                        onClick = {
                            prefs.edit().remove("s_hist_pos").remove("s_daily_pos").apply()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(stringResource(R.string.compose_settings_clear_stats))
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
                                if (!v) {
                                    // 恢复常驻：确保 webserver 正在运行
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                }
                            },
                        )
                    }
                    // ── 开机自动启动 + 诊断 ──
                    Spacer(Modifier.height(8.dp))
                    var autostart by remember {
                        mutableStateOf(
                            org.adaway.helper.PreferenceHelper.getWebServerEnabled(context),
                        )
                    }
                    var showBootDiag by remember { mutableStateOf(false) }
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
                                    enableBootReceiver(context)
                                    org.adaway.broadcast.BootReceiver
                                        .scheduleStart(context, "user enabled autostart")
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                } else {
                                    org.adaway.util.WebServerUtils.stopWebServer()
                                }
                            },
                        )
                    }
                    TextButton(onClick = { showBootDiag = !showBootDiag }) {
                        Icon(
                            if (showBootDiag) Icons.Outlined.ExpandLess
                            else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compose_settings_autostart_diag))
                    }
                    AnimatedVisibility(visible = showBootDiag) {
                        Column {
                            val bootTime = org.adaway.helper.PreferenceHelper
                                .getLastBootTime(context)
                            val bootAction = org.adaway.helper.PreferenceHelper
                                .getLastBootAction(context)
                            val bootResult = org.adaway.helper.PreferenceHelper
                                .getLastBootResult(context)
                            DiagLine(
                                stringResource(R.string.compose_settings_autostart_event),
                                if (bootTime == 0L) {
                                    stringResource(R.string.compose_settings_autostart_never)
                                } else {
                                    "${bootActionLabel(bootAction)} · ${formatDiagTime(bootTime)}"
                                },
                            )
                            DiagLine(
                                stringResource(R.string.compose_settings_autostart_result),
                                bootResult.ifEmpty {
                                    stringResource(R.string.compose_settings_autostart_never)
                                },
                            )
                            DiagLine(
                                stringResource(R.string.compose_settings_autostart_receiver),
                                if (isBootReceiverEnabled(context)) {
                                    stringResource(R.string.compose_settings_autostart_on)
                                } else {
                                    stringResource(R.string.compose_settings_autostart_off)
                                },
                            )
                            DiagLine(
                                stringResource(R.string.compose_settings_autostart_battery),
                                if (isBatteryRestricted(context)) {
                                    stringResource(R.string.compose_settings_autostart_battery_limited)
                                } else {
                                    stringResource(R.string.compose_settings_autostart_battery_ok)
                                },
                            )
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
                                        enableBootReceiver(context)
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
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.compose_settings_autostart_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    var certStateRes by remember { mutableIntStateOf(R.string.pref_webserver_state_not_running) }
                    LaunchedEffect(Unit) {
                        certStateRes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            org.adaway.util.WebServerUtils.getWebServerState(context)
                        }
                    }
                    Text(
                        stringResource(certStateRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (certStateRes == R.string.pref_webserver_state_running_and_installed ||
                            certStateRes == R.string.pref_webserver_state_running_and_installed_system)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    // ── 一键信任（root 系统信任库）/ 手动安装 ──
                    var certTrusted by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        certTrusted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            org.adaway.util.WebServerUtils.isUserCertificateInstalled(context)
                        }
                    }
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
                            /* The app could only ever *add* the CA; removing it
                               meant deleting /data/misc/user/0/cacerts-added/
                               <hash>.0 by hand over a root shell. */
                            TextButton(
                                onClick = {
                                    val removed = org.adaway.util.WebServerUtils
                                        .uninstallCertificateFromSystemStore(context)
                                    Toast.makeText(
                                        context,
                                        if (removed) R.string.compose_settings_cert_remove_ok
                                        else R.string.compose_settings_cert_remove_fail,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    certTrusted = !removed
                                },
                            ) {
                                Text(stringResource(R.string.compose_settings_cert_remove))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val ok = org.adaway.util.WebServerUtils
                                        .installCertificateToSystemStore(context)
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
                                    certTrusted = verified
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
                            R.string.compose_stats_style_bars to 0,
                            R.string.compose_stats_style_stacked to 1,
                            R.string.compose_stats_style_area to 2,
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
                            contentDescription = if (appsExpanded) "Collapse" else "Expand",
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
                                // 放行开关：该应用流量完全绕过拦截
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Switch(
                                        checked = allowed,
                                        onCheckedChange = { v ->
                                            setAppAllowed(context, app.uid, v)
                                            refreshKey++
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
                                Switch(
                                    checked = monitored,
                                    onCheckedChange = { newValue ->
                                        setAppMonitored(context, app.uid, newValue)
                                        refreshKey++
                                    },
                                )
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
            Toast.makeText(context, "Certificate not found — start the web server first", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "Export failed: no MediaStore slot", Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, "Certificate exported: Download/adblock-ca.crt", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export certificate")
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "Backup failed: no MediaStore slot", Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, "Backup saved: Download/adblock-backup.json", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export backup")
        Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "No backup file found in Downloads", Toast.LENGTH_LONG).show()
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
        Toast.makeText(context, "Backup restored — restarting…", Toast.LENGTH_LONG).show()
        onDone()
    } catch (e: Exception) {
        Timber.w(e, "Failed to import backup")
        Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
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

/* ── 开机自启动诊断 ───────────────────────────────────────────────
 * 手机上没法看 logcat，“重启后服务器有时不启动”只能靠记录：
 * BootReceiver / ServerStartWorker 把每次开机事件与结果写进偏好，
 * 这里读出来显示。下面几个函数只服务设置页的这一块。 */

/**
 * 诊断里的一行：左边名称、右边状态。
 */
@Composable
private fun DiagLine(name: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 把开机广播的 action 翻译成用户看得懂的名字。
 */
@Composable
private fun bootActionLabel(action: String): String = when {
    action.isEmpty() -> stringResource(R.string.compose_settings_autostart_never)
    action.contains("QUICKBOOT") ->
        stringResource(R.string.compose_settings_autostart_action_quickboot)
    action.endsWith("MY_PACKAGE_REPLACED") ->
        stringResource(R.string.compose_settings_autostart_action_replaced)
    action.endsWith("BOOT_COMPLETED") ->
        stringResource(R.string.compose_settings_autostart_action_boot)
    else -> action
}

/**
 * 诊断行里的时间：只显示“月-日 时:分”。
 */
private fun formatDiagTime(millis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

/**
 * 开机接收器是否启用。有些系统/清理软件会把组件整个禁掉，此时开机广播
 * 根本不会送到应用（“自启动失败”最常见的原因之一）。
 */
private fun isBootReceiverEnabled(context: Context): Boolean {
    val component = android.content.ComponentName(
        context,
        org.adaway.broadcast.BootReceiver::class.java,
    )
    return context.packageManager.getComponentEnabledSetting(component) !=
        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

/**
 * 重新启用开机接收器（用户打开“开机自动启动”或点重试时调用）。
 */
private fun enableBootReceiver(context: Context) {
    try {
        val component = android.content.ComponentName(
            context,
            org.adaway.broadcast.BootReceiver::class.java,
        )
        if (!isBootReceiverEnabled(context)) {
            context.packageManager.setComponentEnabledSetting(
                component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not re-enable the boot receiver")
    }
}

/**
 * 本应用是否仍受电池优化限制（受限时后台任务/开机广播会被推迟）。
 */
private fun isBatteryRestricted(context: Context): Boolean {
    return try {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        // .not() instead of a leading "!": Kotlin's parser rejects a statement
        // that starts with a prefix operator (CI build #541).
        power.isIgnoringBatteryOptimizations(context.packageName).not()
    } catch (e: Exception) {
        false
    }
}

/**
 * 打开系统的电池优化列表，让用户把本应用设为“不优化”。
 */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Timber.w(e, "No battery optimization settings activity")
        Toast.makeText(context, e.message ?: "unavailable", Toast.LENGTH_SHORT).show()
    }
}
