package org.adaway.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R

/** Both surfaces describe exactly the same immutable observation, including stale/unknown. */
@Composable
internal fun StatsObservationStatus(observation: StatsObservationState) {
    val time = remember(observation.observedAtMillis) {
        observation.observedAtMillis.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
        }
    }
    Card(Modifier.fillMaxWidth().testTag("stats-observation-status"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.compose_stats_status_title), style = MaterialTheme.typography.labelSmall)
            Text(when {
                time != null && observation.error == null -> stringResource(R.string.compose_stats_status_ok, time)
                time != null -> stringResource(R.string.compose_stats_status_stale, time)
                observation.error == StatsReadError.SNAPSHOT -> stringResource(R.string.compose_stats_snapshot_unknown)
                else -> stringResource(R.string.compose_stats_status_waiting)
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("stats-observation-time"))
            Text(stringResource(when (observation.push) {
                StatsPushState.DISABLED -> R.string.stats_push_disabled
                StatsPushState.SUSPENDED -> R.string.stats_push_suspended
                StatsPushState.CONNECTING -> R.string.stats_push_connecting
                StatsPushState.CONNECTED -> R.string.stats_push_connected
                StatsPushState.RETRYING -> R.string.stats_push_retrying
            }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("stats-observation-push"))
        }
    }
}
