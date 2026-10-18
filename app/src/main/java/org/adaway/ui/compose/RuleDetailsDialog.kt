package org.adaway.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.ListType
import org.adaway.db.entity.RuleListRow

@StringRes
internal fun ruleScopeLabel(scope: Int): Int = when (scope) {
    0 -> R.string.compose_rules_scope_exact
    1 -> R.string.compose_rules_scope_suffix
    2 -> R.string.compose_rules_scope_wildcard
    else -> R.string.compose_rules_scope_legacy
}

/** Read-only inspection keeps full names/addresses reachable without changing a rule. */
@Composable
internal fun RuleDetailsDialog(
    row: RuleListRow,
    onDismiss: () -> Unit,
    onCopyHost: (String) -> Unit,
    onManageSource: (() -> Unit)? = null,
) {
    val item = row.item
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_rules_details)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RuleDetailField(R.string.compose_rules_detail_domain, item.host, monospace = true)
                        RuleDetailField(R.string.compose_rules_detail_action, stringResource(when (item.type) {
                            ListType.BLOCKED -> R.string.compose_logs_filter_blocked
                            ListType.ALLOWED -> R.string.compose_logs_filter_allowed
                            ListType.REDIRECTED -> R.string.compose_logs_filter_redirected
                        }))
                        RuleDetailField(R.string.compose_rules_detail_scope, stringResource(ruleScopeLabel(item.matchScope)))
                        if (item.type == ListType.REDIRECTED && !item.redirection.isNullOrEmpty()) {
                            RuleDetailField(R.string.compose_rules_detail_address, item.redirection, monospace = true)
                        }
                        val label = if (item.sourceId == 1) stringResource(R.string.compose_rules_personal)
                            else row.sourceLabel.orEmpty().ifBlank {
                                stringResource(R.string.compose_rules_detail_source_id, item.sourceId)
                            }
                        RuleDetailField(R.string.compose_rules_detail_source, label)
                        RuleDetailField(R.string.compose_rules_detail_state, stringResource(
                            if (row.isActive) R.string.compose_rules_filter_enabled else R.string.compose_rules_filter_disabled))
                    }
                }
                OutlinedButton(onClick = { onCopyHost(item.host) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.compose_rules_copy_domain))
                }
                if (item.sourceId != 1) {
                    Text(stringResource(R.string.compose_rules_source_rule_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_rules_details_close)) }
        },
        dismissButton = {
            if (item.sourceId != 1 && onManageSource != null) {
                TextButton(onClick = onManageSource) { Text(stringResource(R.string.compose_rules_manage_source)) }
            }
        },
    )
}

@Composable
private fun RuleDetailField(@StringRes label: Int, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}
