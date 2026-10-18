package org.adaway.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R

enum class HostsSyncStatus {
    IDLE, RUNNING, SUCCESS, SOURCE_FAILED, APPLY_FAILED, SERVER_REFRESH_FAILED;

    val isFailure: Boolean
        get() = this == SOURCE_FAILED || this == APPLY_FAILED || this == SERVER_REFRESH_FAILED
}

@Composable
internal fun RuleSyncCard(status: HostsSyncStatus, onSync: () -> Unit) {
    val message = when (status) {
        HostsSyncStatus.IDLE -> R.string.compose_sync_hosts_subtitle
        HostsSyncStatus.RUNNING -> R.string.compose_sync_hosts_running
        HostsSyncStatus.SUCCESS -> R.string.compose_sync_complete
        HostsSyncStatus.SOURCE_FAILED -> R.string.compose_sync_source_failed
        HostsSyncStatus.APPLY_FAILED -> R.string.compose_sync_apply_failed
        HostsSyncStatus.SERVER_REFRESH_FAILED -> R.string.compose_sync_server_failed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.compose_sync_hosts), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.isFailure) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onSync,
                enabled = status != HostsSyncStatus.RUNNING,
                modifier = Modifier.align(Alignment.End),
            ) {
                if (status == HostsSyncStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (status.isFailure) R.string.compose_sync_retry else R.string.compose_sync_hosts))
            }
        }
    }
}
