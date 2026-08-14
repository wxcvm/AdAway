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

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import org.adaway.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val PREFS_MONITOR = "compose_app_monitor"
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

/* ── General settings (logs + chart options) ──────────────────── */

/** Max log entries shown in the Logs screen. Default 500. */
internal fun logLimit(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("log_limit", 500)
}

internal fun setLogLimit(context: Context, limit: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("log_limit", limit).apply()
}

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

/* ── Log retention: 0 = forever, else hours ────────────────────── */

internal fun logRetentionHours(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("log_retention", 0)
}

internal fun setLogRetentionHours(context: Context, hours: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("log_retention", hours).apply()
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
                    // SHA-256 指纹（可展开查看）
                    val fingerprint = remember {
                        org.adaway.util.WebServerUtils.getCertificateFingerprint(context)
                    }
                    if (fingerprint != null) {
                        Text(
                            stringResource(R.string.compose_settings_cert_fingerprint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            fingerprint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 安装证书按钮
                    Button(
                        onClick = {
                            org.adaway.util.WebServerUtils.installUserCertificate(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compose_settings_cert_install))
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_logs_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_logs_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var limit by remember { mutableStateOf(logLimit(context)) }
                    // Sync from preferences on composition start
                    LaunchedEffect(Unit) { limit = logLimit(context) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 500, 1000, 2000).forEach { l ->
                            FilterChip(
                                selected = limit == l,
                                onClick = {
                                    limit = l
                                    setLogLimit(context, l)
                                },
                                label = { Text(stringResource(R.string.compose_settings_logs_limit, l)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.compose_settings_logs_retention),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var retention by remember { mutableStateOf(logRetentionHours(context)) }
                    LaunchedEffect(Unit) { retention = logRetentionHours(context) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(0, R.string.compose_settings_logs_retention_forever, 0),
                            Triple(24, R.string.compose_settings_logs_retention_1d, 24),
                            Triple(168, R.string.compose_settings_logs_retention_7d, 168),
                            Triple(720, R.string.compose_settings_logs_retention_30d, 720),
                        ).forEach { (hours, labelRes, _) ->
                            FilterChip(
                                selected = retention == hours,
                                onClick = {
                                    retention = hours
                                    setLogRetentionHours(context, hours)
                                },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
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
                            },
                        )
                    }
                }
            }

            // ── App monitoring ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
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
                    }
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
                    Text(
                        stringResource(
                            R.string.compose_settings_about_version,
                            pkgInfo?.versionName ?: "?",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.compose_settings_about_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ── 备份 / 恢复 ──────────────────────────────────────────────── */

/**
 * 导出备份：把所有偏好（compose_general / compose_app_monitor /
 * compose_webserver）序列化为 JSON 写入 Download/adblock-backup.json，
 * 并提示用户。
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
        val file = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "adblock-backup.json",
        )
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
        Toast.makeText(context, "Backup saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        // 简化：直接读取固定的 Download 路径（避免文件选择器回调复杂度）
        val file = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "adblock-backup.json",
        )
        if (!file.exists()) {
            Toast.makeText(context, "No backup file found in Downloads", Toast.LENGTH_LONG).show()
            return
        }
        val json = org.json.JSONObject(file.readText())
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