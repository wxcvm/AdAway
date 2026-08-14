package org.adaway.ui.compose

/**
 * 日志页面：实时 DNS 请求日志（基于 tcpdump 管道）。
 *
 * 功能：
 *  - 录制开关（记录/暂停）、手动刷新、清空
 *  - 类型过滤（全部/拦截/放行/重定向）
 *  - 按设置截断：log_limit（条数上限）+ log_retention（保留时间）
 *  - 录制时每 3s 自动刷新
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.adaway.R
import org.adaway.db.entity.ListType

/**
 * Logs screen: live DNS request log (tcpdump-backed), with a record
 * toggle, refresh, clear and type filter. Reuses the existing
 * AdBlockModel.getLogs() pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: StatsViewModel) {
    val model = viewModel.adBlockModel
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf<List<Pair<String, ListType?>>>(emptyList()) }
    var recording by remember { mutableStateOf(model.isRecordingLogs()) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    val limit = logLimit(context)

    /**
 * 刷新日志：从 AdBlockModel 拉取最新条目，
 * 按设置截断（log_limit 条数上限 + log_retention 保留时间）。
 *
 * 注意：刷新在 IO 线程执行，回调切回主线程更新 Compose 状态。
 */
fun refresh() {
        viewModel.refreshLogEntries { newEntries ->
            entries = if (newEntries.size > limit) newEntries.takeLast(limit) else newEntries
        }
    }

    // Auto-refresh every 3s while recording
    LaunchedEffect(recording) {
        if (recording) {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
    }

    val filters = listOf(
        FilterOption(R.string.compose_logs_filter_all, null),
        FilterOption(R.string.compose_logs_filter_blocked, ListType.BLOCKED),
        FilterOption(R.string.compose_logs_filter_allowed, ListType.ALLOWED),
        FilterOption(R.string.compose_logs_filter_redirected, ListType.REDIRECTED),
    )
    val activeFilter = filters[filter]
    val visibleEntries = remember(entries, filter) {
        entries.filter { activeFilter.type == null || it.second == activeFilter.type }
    }

    Scaffold(
                topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_logs_title)) },
                actions = {
                    FilledTonalIconButton(onClick = {
                        model.setRecordingLogs(!model.isRecordingLogs())
                        recording = model.isRecordingLogs()
                        refresh()
                    }) {
                        Icon(
                            if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (recording) R.string.compose_logs_pause else R.string.compose_logs_start,
                            ),
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        model.clearLogs()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.compose_logs_clear))
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
            // Recording status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (recording) stringResource(R.string.compose_logs_recording)
                    else stringResource(R.string.compose_logs_paused),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (recording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.compose_logs_entries, entries.size),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Type filter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEachIndexed { index, option ->
                    FilterChip(
                        selected = index == filter,
                        onClick = { filter = index },
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }

            if (visibleEntries.isEmpty()) {
                if (entries.isEmpty()) {
                    EmptyLogsPlaceholder(recording)
                } else {
                    // Entries exist but none match the active filter
                    Text(
                        stringResource(R.string.compose_logs_empty_filtered),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    items(visibleEntries, key = { it.first }) { (host, type) ->
                        LogRow(host = host, type = type, onClick = {
                            // TODO: open host action (whitelist etc.) in a later step
                        })
                    }
                }
            }
        }
    }
}

private data class FilterOption(
    @androidx.annotation.StringRes val labelRes: Int,
    val type: ListType?,
)

@Composable
private fun LogRow(host: String, type: ListType?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeDot(type)
            Spacer(Modifier.width(12.dp))
            Text(
                host,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (type) {
                    ListType.BLOCKED -> stringResource(R.string.compose_logs_filter_blocked)
                    ListType.ALLOWED -> stringResource(R.string.compose_logs_filter_allowed)
                    ListType.REDIRECTED -> stringResource(R.string.compose_logs_filter_redirected)
                    null -> stringResource(R.string.compose_logs_filter_unknown)
                },
                style = MaterialTheme.typography.labelSmall,
                color = typeColor(type),
            )
        }
    }
}

@Composable
private fun TypeDot(type: ListType?) {
    val color = typeColor(type)
    androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = color)
    }
}

@Composable
private fun typeColor(type: ListType?): androidx.compose.ui.graphics.Color = when (type) {
    ListType.BLOCKED -> MaterialTheme.colorScheme.error
    ListType.ALLOWED -> MaterialTheme.colorScheme.primary
    ListType.REDIRECTED -> MaterialTheme.colorScheme.tertiary
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun EmptyLogsPlaceholder(recording: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (recording) stringResource(R.string.compose_logs_empty_recording)
            else stringResource(R.string.compose_logs_empty_paused),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (recording) stringResource(R.string.compose_logs_empty_recording_hint)
            else stringResource(R.string.compose_logs_empty_paused_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}