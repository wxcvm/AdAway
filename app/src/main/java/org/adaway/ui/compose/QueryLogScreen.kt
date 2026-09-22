package org.adaway.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请求日志二级页面。
 *
 * 把服务器查询日志（/internal-stats 的 query_log[]）完整列出来，最新在最上，
 * 每条记录给出：时间 · 结果（拦截/放行/代理）· 命中策略类型 · 处理方式 · 来源应用 · 主机名。
 * 可按结果过滤。
 *
 * 入口：统计页「最近请求」卡片的“查看全部”。
 *
 * 说明：服务端在 /internal-stats 里导出最新 400 条（QLOG_RENDER_MAX），
 * 环形缓冲本身保留 4096 条并落盘到 query_log.dat。右上角可把当前筛选结果
 * 导出成 CSV 分享出去（旧版只发 24 条，这一页因此看起来“没几条”）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryLogScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val stats by viewModel.serverStats.collectAsStateWithLifecycle()
    val entries = stats?.queryLog.orEmpty()
    var filter by remember { mutableIntStateOf(FILTER_ALL) }
    val shown = if (filter == FILTER_ALL) entries else entries.filter { it.action == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back),
                        )
                    }
                },
                actions = {
                    /* Share the current list as CSV. There was no way to get the
                       log off the phone at all - the Windows dashboard has an
                       export, the app had nothing. */
                    IconButton(
                        onClick = { shareQueryLogCsv(context, shown) },
                        enabled = shown.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.compose_logs_export),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                stringResource(R.string.compose_logs_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == FILTER_ALL,
                    onClick = { filter = FILTER_ALL },
                    label = { Text(stringResource(R.string.compose_logs_filter_all)) },
                )
                FilterChip(
                    selected = filter == ACTION_BLOCK,
                    onClick = { filter = ACTION_BLOCK },
                    label = { Text(stringResource(R.string.compose_stats_recent_action_blocked)) },
                )
                FilterChip(
                    selected = filter == ACTION_ALLOW,
                    onClick = { filter = ACTION_ALLOW },
                    label = { Text(stringResource(R.string.compose_stats_recent_action_allowed)) },
                )
                FilterChip(
                    selected = filter == ACTION_PROXY,
                    onClick = { filter = ACTION_PROXY },
                    label = { Text(stringResource(R.string.compose_stats_recent_action_proxied)) },
                )
            }
            if (shown.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.compose_logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(shown) { entry -> QueryLogRow(entry) }
                }
            }
        }
    }
}

private const val ACTION_PROXY = 0
private const val ACTION_BLOCK = 1
private const val ACTION_ALLOW = 2
private const val FILTER_ALL = -1

@Composable
private fun QueryLogRow(entry: QueryLogEntry) {
    val actionColor = when (entry.action) {
        ACTION_BLOCK -> MaterialTheme.colorScheme.error
        ACTION_ALLOW -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.primary
    }
    val time = if (entry.ts <= 0L) {
        "--:--:--"
    } else {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.ts * 1000L))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                queryActionSummary(entry),
                style = MaterialTheme.typography.labelMedium,
                color = actionColor,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                appNameForUid(entry.uid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            entry.host.ifEmpty { "-" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "拦截 · 脚本 · 204" / "放行" / "代理" — 与统计页卡片同一套措辞。 */
@Composable
private fun queryActionSummary(entry: QueryLogEntry): String {
    val action = when (entry.action) {
        ACTION_BLOCK -> stringResource(R.string.compose_stats_recent_action_blocked)
        ACTION_ALLOW -> stringResource(R.string.compose_stats_recent_action_allowed)
        else -> stringResource(R.string.compose_stats_recent_action_proxied)
    }
    if (entry.action != ACTION_BLOCK && entry.action != ACTION_ALLOW) return action
    val type = when (entry.type) {
        0 -> stringResource(R.string.compose_stats_type_images)
        1 -> stringResource(R.string.compose_stats_type_scripts)
        2 -> stringResource(R.string.compose_stats_type_styles)
        3 -> stringResource(R.string.compose_stats_type_fonts)
        4 -> stringResource(R.string.compose_stats_type_media)
        5 -> stringResource(R.string.compose_stats_type_other)
        6 -> stringResource(R.string.compose_stats_type_api)
        7 -> stringResource(R.string.compose_stats_type_telemetry)
        8 -> stringResource(R.string.compose_stats_type_config)
        9 -> stringResource(R.string.compose_stats_type_ws)
        else -> ""
    }
    val mode = when (entry.mode) {
        1 -> stringResource(R.string.compose_stats_mode_204)
        2 -> stringResource(R.string.compose_stats_mode_allowed)
        else -> stringResource(R.string.compose_stats_mode_placeholder)
    }
    return if (type.isEmpty()) "$action · $mode" else "$action · $type · $mode"
}

/**
 * 把当前列表导出成 CSV 并交给系统分享（微信/邮件/文件管理器都行）。
 *
 * 之前日志只能在应用里看，出问题没法拿出来对比；Windows 端有“诊断导出”，
 * 这里补上同样的能力。文件写在 cacheDir，通过 FileProvider 授权读取。
 */
private fun shareQueryLogCsv(context: Context, entries: List<QueryLogEntry>) {
    if (entries.isEmpty()) return
    try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = java.io.File(context.cacheDir, "adblock-query-log-$stamp.csv")
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("time,result,type,mode,uid,host\n")
            entries.forEach { entry ->
                val time = if (entry.ts > 0) timeFmt.format(Date(entry.ts * 1000L)) else ""
                val result = when (entry.action) {
                    ACTION_BLOCK -> "blocked"
                    ACTION_ALLOW -> "allowed"
                    else -> "proxied"
                }
                out.write(
                    listOf(
                        time,
                        result,
                        if (entry.type in 0..9) entry.type.toString() else "",
                        entry.mode.toString(),
                        if (entry.uid >= 0) entry.uid.toString() else "",
                        entry.host,
                    ).joinToString(",") { csvField(it) } + "\n",
                )
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.compose_logs_export_failed, e.message ?: ""),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** CSV 里引号、逗号和换行都要转义，否则主机名带逗号就会错列。 */
private fun csvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}
