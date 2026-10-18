package org.adaway.ui.compose

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
import androidx.compose.ui.window.DialogProperties
import org.adaway.R

/** A failed transaction keeps the target visible and retryable; busy work cannot be repeated. */
@Composable
internal fun RuleRemovalDialog(
    title: String,
    target: String,
    explanation: String,
    busy: Boolean,
    failed: Boolean,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SelectionContainer {
                    Text(target, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                if (failed) {
                    Text(stringResource(R.string.compose_rules_remove_failed), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && canConfirm, onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(when {
                    busy -> R.string.compose_rules_removing
                    failed -> R.string.compose_rules_remove_retry
                    else -> R.string.compose_rules_remove_confirm
                }))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.compose_rules_cancel)) }
        },
    )
}
