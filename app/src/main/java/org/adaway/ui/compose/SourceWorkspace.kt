package org.adaway.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.HostsSource
import org.adaway.model.source.SourcePresentation

@Composable
internal fun RulesWorkspaceSelector(sources: Boolean, onSelect: (Boolean) -> Unit,
    onExempt: () -> Unit, onOverrides: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = sources, onClick = { onSelect(true) }, label = { Text(stringResource(R.string.compose_rules_sources)) })
        FilterChip(selected = !sources, onClick = { onSelect(false) }, label = { Text(stringResource(R.string.compose_rules_browser)) })
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.compose_rules_tools)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.compose_exempt_entry)) }, onClick = { menu = false; onExempt() })
                DropdownMenuItem(text = { Text(stringResource(R.string.compose_settings_rule_override)) }, onClick = { menu = false; onOverrides() })
            }
        }
    }
}

@Composable
internal fun SourcesWorkspace(sources: List<HostsSource>, enabled: Boolean,
    onToggle: (HostsSource, Boolean) -> Unit, onAddClick: () -> Unit,
    onDelete: (HostsSource) -> Unit, onInspect: (HostsSource) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compose_rules_sources_overview, sources.count { it.isEnabled }, sources.size),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.compose_rules_source_help), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(enabled = enabled, onClick = onAddClick) { Text(stringResource(R.string.compose_rules_add_source)) }
            }
        }
        if (sources.isEmpty()) Text(stringResource(R.string.compose_rules_sources_empty))
        for (source in sources) key(source.id) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(SourcePresentation.name(source.label, source.url), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Switch(checked = source.isEnabled, enabled = enabled, onCheckedChange = { onToggle(source, it) })
                    }
                    Text(SourcePresentation.address(source.url), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (source.localModificationDate == null) stringResource(R.string.compose_rules_source_not_imported)
                        else stringResource(R.string.compose_rules_source_size, source.size),
                        style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onInspect(source) }) { Text(stringResource(R.string.compose_rules_source_details)) }
                        TextButton(enabled = enabled, onClick = { onDelete(source) }) { Text(stringResource(R.string.compose_rules_delete_source)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceDetailsDialog(source: HostsSource, busy: Boolean, failed: Boolean,
    onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(source.id) { mutableStateOf(SourcePresentation.name(source.label, source.url)) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.compose_rules_source_details)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.compose_rules_source_name)) }, modifier = Modifier.fillMaxWidth())
                SelectionContainer { Text(SourcePresentation.address(source.url), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.compose_rules_source_size, source.size))
                Text(source.localModificationDate?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    ?: stringResource(R.string.compose_rules_source_not_imported), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.compose_rules_source_rename_hint), style = MaterialTheme.typography.bodySmall)
                if (failed) Text(stringResource(R.string.compose_rules_source_rename_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.trim().isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() },
                onClick = { onRename(name) }) { Text(stringResource(R.string.compose_rules_source_save_name)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.compose_rules_cancel)) } })
}
