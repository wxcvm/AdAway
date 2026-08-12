package org.adaway.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.adaway.db.entity.ListType

/**
 * Logs screen: live DNS request log (tcpdump-backed), with a record
 * toggle, refresh and clear actions. Reuses the existing
 * AdBlockModel.getLogs() pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: StatsViewModel) {
    val model = viewModel.adBlockModel
    var entries by remember { mutableStateOf<List<Pair<String, ListType?>>>(emptyList()) }
    var recording by remember { mutableStateOf(model.isRecordingLogs()) }
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        viewModel.refreshLogEntries { newEntries -> entries = newEntries }
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

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Logs") },
                actions = {
                    FilledTonalIconButton(onClick = {
                        model.setRecordingLogs(!model.isRecordingLogs())
                        recording = model.isRecordingLogs()
                        refresh()
                    }) {
                        Icon(
                            if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (recording) "Pause recording" else "Start recording",
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        model.clearLogs()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                    if (recording) "● Recording DNS queries" else "○ Recording paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (recording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${entries.size} entries",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entries.isEmpty()) {
                EmptyLogsPlaceholder(recording)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    items(entries, key = { it.first }) { (host, type) ->
                        LogRow(host = host, type = type, onClick = {
                            // TODO: open host action (whitelist etc.) in a later step
                        })
                    }
                }
            }
        }
    }
}

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
                typeLabel(type),
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

private fun typeLabel(type: ListType?): String = when (type) {
    ListType.BLOCKED -> "blocked"
    ListType.ALLOWED -> "allowed"
    ListType.REDIRECTED -> "redirected"
    null -> "unknown"
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
            if (recording) "No DNS queries captured yet" else "Recording is paused",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (recording) "Browse the web and queries will appear here."
            else "Tap play in the top bar to start capturing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}