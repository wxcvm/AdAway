package org.adaway.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A request workbench; data and cursor survive screen navigation in the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryLogScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val browser = viewModel.queryLogBrowser
    val state by browser.state.collectAsStateWithLifecycle()
    val refreshTick by viewModel.statsRefreshTick.collectAsStateWithLifecycle()
    // Reuse the existing statistics poll. No second timer or background reader.
    LaunchedEffect(refreshTick) { browser.refreshIfLive() }
    val shown = remember(state.entries, state.filter, state.query, state.uid, state.family, state.type) { state.shown }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.compose_logs_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.compose_back)) } },
            actions = {
                IconButton(enabled = !state.busy, onClick = browser::refresh) { Icon(Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.compose_logs_refresh)) }
                IconButton(enabled = shown.isNotEmpty(), onClick = { shareQueryLogCsv(context, shown) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.compose_logs_export))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }) { padding ->
        QueryLogContent(shown, state.entries.size, state.page?.total, state.filter, state.busy, state.failed,
            state.page == null || state.page?.hasMore == true, browser::setFilter,
            browser::more, browser::refresh, Modifier.padding(padding),
            query = state.query, onQuery = browser::setQuery, live = state.live,
            onLive = browser::setLive, uid = state.uid, onUid = browser::setUid,
            family = state.family, onFamily = browser::setFamily, type = state.type, onType = browser::setType,
            onInspect = { browser.setLive(false) })
    }
}

@Composable
internal fun QueryLogContent(
    shown: List<QueryLogEntry>, loaded: Int, total: Int?, filter: Int, busy: Boolean,
    failed: Boolean, hasMore: Boolean, onFilter: (Int) -> Unit, onMore: () -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier,
    query: String = "", onQuery: (String) -> Unit = {}, live: Boolean? = null,
    onLive: (Boolean) -> Unit = {}, uid: Int? = null, onUid: (Int?) -> Unit = {},
    onInspect: () -> Unit = {},
    family: Int? = null, onFamily: (Int?) -> Unit = {},
    type: Int? = null, onType: (Int?) -> Unit = {},
) {
    var selected by remember { mutableStateOf<QueryLogEntry?>(null) }
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(live) {
        if (live == true) listState.scrollToItem(0)
    }
    LaunchedEffect(live, dragging) {
        if (live == true && dragging) onLive(false)
    }
    Column(modifier.fillMaxSize()) {
        // Keep Resume reachable while inspecting older records, below the header.
        if (live != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (live) R.string.compose_logs_live else R.string.compose_logs_paused),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { onLive(!live) }) {
                Text(stringResource(if (live) R.string.compose_logs_pause else R.string.compose_logs_resume))
            }
        }
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("query-log-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compose_logs_scope),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.compose_logs_search)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.compose_logs_search_scope)) })
                QueryLogFilters(filter, onFilter)
                QueryLogClassification(family, onFamily, type, onType)
                if (uid != null) InputChip(selected = true, onClick = { onUid(null) },
                    label = { Text(stringResource(R.string.compose_logs_uid_filter, uid)) })
                Text(stringResource(R.string.compose_logs_loaded_count, shown.size, loaded, total ?: loaded),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) {
                    Text(stringResource(R.string.compose_logs_read_failed), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = onRefresh) { Text(stringResource(R.string.compose_logs_refresh)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (shown.isEmpty()) item {
            Text(stringResource(if (loaded > 0) R.string.compose_logs_no_match else R.string.compose_logs_empty),
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        }
        items(shown) { entry -> QueryLogRow(entry) { selected = entry; onInspect() } }
        if (hasMore && !failed) item {
            TextButton(enabled = !busy, onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.compose_rules_loading else R.string.compose_logs_more))
            }
        }
    }
    }
    selected?.let { entry -> QueryLogDetailsDialog(entry, { selected = null }, {
        onUid(entry.uid); selected = null
    }) }
}

@Composable
private fun QueryLogFilters(filter: Int, onFilter: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in listOf(FILTER_ALL to R.string.compose_logs_filter_all,
            ACTION_BLOCK to R.string.compose_stats_recent_action_blocked,
            ACTION_ALLOW to R.string.compose_logs_local_exemption,
            ACTION_PROXY to R.string.compose_stats_recent_action_proxied)) {
            FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = { Text(stringResource(label)) })
        }
    }
}

private const val ACTION_PROXY = 0
private const val ACTION_BLOCK = 1
private const val ACTION_ALLOW = 2
private const val FILTER_ALL = -1

@Composable
internal fun queryLogFamily(family: Int): String = when (family) {
    4 -> "IPv4"
    6 -> "IPv6"
    else -> stringResource(R.string.compose_logs_family_unknown)
}

@Composable
private fun QueryLogClassification(family: Int?, onFamily: (Int?) -> Unit, type: Int?, onType: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in listOf(null, 4, 6, 0)) FilterChip(modifier = Modifier.testTag("query-family-${value ?: "all"}"),
            selected = family == value, onClick = { onFamily(value) },
            label = { Text(if (value == null) stringResource(R.string.compose_logs_family_all) else queryLogFamily(value)) })
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (type == null) stringResource(R.string.compose_logs_type_all) else queryLogType(type))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.compose_logs_type_all)) }, onClick = { onType(null); expanded = false })
            for (value in -1..9) DropdownMenuItem(text = { Text(queryLogType(value)) }, onClick = { onType(value); expanded = false })
        }
    }
    Text(stringResource(R.string.compose_logs_classification_scope), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun QueryLogRow(entry: QueryLogEntry, onClick: () -> Unit) {
    val actionColor = when (entry.action) {
        ACTION_BLOCK -> MaterialTheme.colorScheme.error
        ACTION_ALLOW -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.host.ifEmpty { "-" }, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(queryActionSummary(entry), style = MaterialTheme.typography.labelMedium, color = actionColor)
            Text(queryLogFamily(entry.family), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(appNameForUid(entry.uid), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(queryLogTime(entry.ts, false), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun QueryLogDetailsDialog(entry: QueryLogEntry, onDismiss: () -> Unit, onUid: () -> Unit) {
    val appLabel = appNameForUid(entry.uid)
    val uidFallback = stringResource(R.string.compose_stats_app_unknown, entry.uid)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.compose_logs_details)) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.host.ifEmpty { "-" }, style = MaterialTheme.typography.titleMedium)
                    Text(queryLogTime(entry.ts, true), style = MaterialTheme.typography.bodySmall)
                    Text(queryActionSummary(entry), style = MaterialTheme.typography.bodyMedium)
                    Text(queryLogFamily(entry.family), style = MaterialTheme.typography.bodySmall)
                    if (appLabel != uidFallback) Text(appLabel, style = MaterialTheme.typography.bodySmall)
                    if (entry.uid >= 0) Text("UID ${entry.uid}", style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(if (entry.action == ACTION_ALLOW) R.string.compose_logs_exemption_detail
                        else if (entry.action == ACTION_PROXY) R.string.compose_logs_proxy_detail
                        else R.string.compose_logs_block_detail), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.compose_logs_details_scope), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_logs_close)) } },
        dismissButton = { if (entry.uid >= 0) TextButton(onClick = onUid) { Text(stringResource(R.string.compose_logs_only_app)) } })
}

internal fun queryLogTime(timestamp: Long, full: Boolean): String =
    if (timestamp <= 0 || timestamp > Long.MAX_VALUE / 1000) "--:--:--"
    else SimpleDateFormat(if (full) "yyyy-MM-dd HH:mm:ss z" else "HH:mm:ss", Locale.getDefault()).format(Date(timestamp * 1000))

@Composable
internal fun queryActionSummary(entry: QueryLogEntry): String {
    if (entry.action == ACTION_ALLOW) return stringResource(R.string.compose_logs_local_exemption)
    if (entry.action == ACTION_PROXY) return stringResource(R.string.compose_stats_recent_action_proxied)
    val action = stringResource(R.string.compose_stats_recent_action_blocked)
    val type = queryLogType(entry.type)
    val mode = when (entry.mode) {
        1 -> stringResource(R.string.compose_stats_mode_204)
        2 -> stringResource(R.string.compose_stats_mode_allowed)
        0 -> stringResource(R.string.compose_stats_mode_placeholder)
        3 -> stringResource(R.string.compose_stats_mode_empty)
        else -> stringResource(R.string.compose_logs_unknown_code, entry.mode)
    }
    return "$action · $type · $mode"
}

@Composable
private fun queryLogType(type: Int): String = when (type) {
        0 -> stringResource(R.string.compose_stats_type_images)
        1 -> stringResource(R.string.compose_stats_type_scripts)
        2 -> stringResource(R.string.compose_stats_type_styles)
        3 -> stringResource(R.string.compose_stats_type_fonts)
        4 -> stringResource(R.string.compose_stats_type_media)
        5 -> stringResource(R.string.compose_logs_type_structure)
        6 -> stringResource(R.string.compose_stats_type_api)
        7 -> stringResource(R.string.compose_stats_type_telemetry)
        8 -> stringResource(R.string.compose_stats_type_config)
        9 -> stringResource(R.string.compose_stats_type_ws)
        -1 -> stringResource(R.string.compose_logs_type_unknown)
        else -> stringResource(R.string.compose_logs_unknown_code, type)
    }

private fun csvTypeName(type: Int): String = when (type) {
    0 -> "images"
    1 -> "scripts"
    2 -> "styles"
    3 -> "fonts"
    4 -> "media"
    5 -> "structure"
    6 -> "api"
    7 -> "telemetry"
    8 -> "config"
    9 -> "ws_sse"
    else -> ""
}

private fun csvModeName(mode: Int): String = when (mode) {
    0 -> "placeholder"
    1 -> "204"
    2 -> "allowed"
    3 -> "empty_response"
    else -> "unknown"
}

private fun shareQueryLogCsv(context: Context, entries: List<QueryLogEntry>) {
    if (entries.isEmpty()) return
    try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = java.io.File(context.cacheDir, "adblock-query-log-$stamp.csv")
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            /* 同时给可读名与机器用的编码，两个受众都不吃亏 */
            out.write("time,result,type_code,type_name,mode_code,mode_name,uid,host,ip_family\n")
            entries.forEach { entry ->
                out.write(queryLogCsvRow(entry, timeFmt))
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

private fun queryLogCsvRow(entry: QueryLogEntry, formatter: SimpleDateFormat): String {
    val time = if (entry.ts in 1..Long.MAX_VALUE / 1000) formatter.format(Date(entry.ts * 1000L)) else ""
    val result = when (entry.action) {
        ACTION_BLOCK -> "blocked"
        ACTION_ALLOW -> "local_exemption"
        else -> "forwarding_started"
    }
    return listOf(time, result, if (entry.type >= 0) entry.type.toString() else "", csvTypeName(entry.type),
        entry.mode.toString(), csvModeName(entry.mode), if (entry.uid >= 0) entry.uid.toString() else "", entry.host,
        if (entry.family == 4 || entry.family == 6) "IPv${entry.family}" else "unknown")
        .joinToString(",") { csvField(it) } + "\n"
}

/** CSV 里引号、逗号和换行都要转义，否则主机名带逗号就会错列。 */
private fun csvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}
