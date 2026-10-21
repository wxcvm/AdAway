package org.adaway.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

/** Server request logs use one immutable cursor while browsing history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryLogScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val stats by viewModel.serverStats.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(FILTER_ALL) }
    var page by remember { mutableStateOf<QueryLogPage?>(null) }
    var loaded by remember { mutableStateOf<List<QueryLogEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var browsingOlder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun fetch(older: Boolean) {
        if (busy) return
        busy = true
        if (older) browsingOlder = true
        scope.launch {
            try {
                val next = viewModel.loadQueryLogPage(if (older) page?.nextOffset ?: 0 else 0,
                    300, if (older) page?.cursor else null)
                loaded = if (older) loaded + next.entries else next.entries
                page = next
                failed = false
                if (!older) browsingOlder = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed = true
            } finally { busy = false }
        }
    }
    LaunchedEffect(stats?.totalRequests) { if (!browsingOlder) fetch(false) }
    val entries = if (page == null) stats?.queryLog.orEmpty() else loaded
    val shown = if (filter == FILTER_ALL) entries else entries.filter { it.action == filter }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.compose_logs_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.compose_back)) } },
            actions = {
                IconButton(enabled = !busy, onClick = { fetch(false) }) { Icon(Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.compose_logs_refresh)) }
                IconButton(enabled = shown.isNotEmpty(), onClick = { shareQueryLogCsv(context, shown) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.compose_logs_export))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }) { padding ->
        QueryLogContent(shown, entries.size, page?.total, filter, busy, failed,
            page == null || page?.hasMore == true, { filter = it },
            { fetch(page != null) }, { fetch(false) }, Modifier.padding(padding))
    }
}

@Composable
internal fun QueryLogContent(
    shown: List<QueryLogEntry>, loaded: Int, total: Int?, filter: Int, busy: Boolean,
    failed: Boolean, hasMore: Boolean, onFilter: (Int) -> Unit, onMore: () -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Text(stringResource(R.string.compose_logs_scope), modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf(FILTER_ALL to R.string.compose_logs_filter_all,
                ACTION_BLOCK to R.string.compose_stats_recent_action_blocked,
                ACTION_ALLOW to R.string.compose_stats_recent_action_allowed,
                ACTION_PROXY to R.string.compose_stats_recent_action_proxied)) {
                FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.compose_logs_loaded_count, shown.size, loaded, total ?: loaded),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
        if (failed) {
            Text(stringResource(R.string.compose_logs_read_failed), Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = onRefresh) { Text(stringResource(R.string.compose_logs_refresh)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (shown.isEmpty()) item { Text(stringResource(R.string.compose_logs_empty), Modifier.padding(vertical = 24.dp)) }
            items(shown) { entry -> QueryLogRow(entry) }
            if (hasMore && !failed) item {
                TextButton(enabled = !busy, onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (busy) R.string.compose_rules_loading else R.string.compose_logs_more))
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
        Text(
            entry.host.ifEmpty { "-" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = actionColor.copy(alpha = 0.10f),
            ) {
                Text(
                    queryActionSummary(entry),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = actionColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                appNameForUid(entry.uid),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
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
/*
 * CSV 用的可读名称。
 *
 * 这里刻意不用 stringResource：导出函数不是 Composable，而且导出的文件更适合语言无关的
 * 稳定标识（同事/工具拿到 CSV 时不会因为系统语言不同而对不上）。数字编码仍然保留在
 * 单独的 type_code / mode_code 列里，方便机器处理。
 */
private fun csvTypeName(type: Int): String = when (type) {
    0 -> "images"
    1 -> "scripts"
    2 -> "styles"
    3 -> "fonts"
    4 -> "media"
    5 -> "other"
    6 -> "api"
    7 -> "telemetry"
    8 -> "config"
    9 -> "ws_sse"
    else -> ""
}

private fun csvModeName(mode: Int): String = when (mode) {
    1 -> "204"
    2 -> "allowed"
    else -> "placeholder"
}

private fun shareQueryLogCsv(context: Context, entries: List<QueryLogEntry>) {
    if (entries.isEmpty()) return
    try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = java.io.File(context.cacheDir, "adblock-query-log-$stamp.csv")
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            /* 同时给可读名与机器用的编码，两个受众都不吃亏 */
            out.write("time,result,type_code,type_name,mode_code,mode_name,uid,host\n")
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
                        csvTypeName(entry.type),
                        entry.mode.toString(),
                        csvModeName(entry.mode),
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
