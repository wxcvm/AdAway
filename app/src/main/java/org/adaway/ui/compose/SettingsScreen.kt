package org.adaway.ui.compose

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                }
            }

            // ── Logs settings ──
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
                    val limits = listOf(100, 500, 1000, 2000)
                    var limit by remember { mutableStateOf(logLimit(context)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        limits.forEach { l ->
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
                        Triple("chart_donut", R.string.compose_settings_chart_donut, R.string.compose_settings_chart_donut_hint),
                        Triple("chart_trend", R.string.compose_settings_chart_trend, R.string.compose_settings_chart_trend_hint),
                        Triple("chart_bars", R.string.compose_settings_chart_bars, R.string.compose_settings_chart_bars_hint),
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
}