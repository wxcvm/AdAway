package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.StatisticsSettingsSection(viewModel: StatsViewModel) {
    val context = LocalContext.current
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
}
