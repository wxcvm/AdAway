package org.adaway.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.ListType

@Composable
internal fun SourceEditorFields(
    fileSource: Boolean, url: String, fileName: String, busy: Boolean, failed: Boolean,
    onTypeChange: (Boolean) -> Unit, onUrlChange: (String) -> Unit, onPickFile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !fileSource, enabled = !busy, onClick = { onTypeChange(false) },
                label = { Text(stringResource(R.string.compose_rules_source_type_url)) })
            FilterChip(selected = fileSource, enabled = !busy, onClick = { onTypeChange(true) },
                label = { Text(stringResource(R.string.compose_rules_source_type_file)) })
        }
        if (fileSource) {
            OutlinedButton(enabled = !busy, onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.compose_rules_pick_file))
            }
            Text(fileName.ifBlank { stringResource(R.string.compose_rules_pick_file_hint) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedTextField(value = url, onValueChange = onUrlChange, enabled = !busy,
                label = { Text(stringResource(R.string.compose_rules_source_url_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        }
        if (failed) Text(stringResource(R.string.compose_rules_add_source_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PersonalRuleEditorFields(
    host: String, type: Int, redirect: String, busy: Boolean, failed: Boolean,
    onHostChange: (String) -> Unit, onTypeChange: (Int) -> Unit, onRedirectChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = host, onValueChange = onHostChange, enabled = !busy,
            label = { Text(stringResource(R.string.compose_rules_rule_host_hint)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ListType.BLOCKED to R.string.compose_logs_filter_blocked,
                ListType.ALLOWED to R.string.compose_logs_filter_allowed,
                ListType.REDIRECTED to R.string.compose_logs_filter_redirected).forEach { (action, label) ->
                FilterChip(selected = type == action.value, enabled = !busy, onClick = { onTypeChange(action.value) },
                    label = { Text(stringResource(label)) })
            }
        }
        if (type == ListType.REDIRECTED.value) {
            OutlinedTextField(value = redirect, onValueChange = onRedirectChange, enabled = !busy,
                label = { Text(stringResource(R.string.compose_rules_rule_redirect_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.compose_rules_personal_write_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text(stringResource(R.string.compose_rules_add_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
