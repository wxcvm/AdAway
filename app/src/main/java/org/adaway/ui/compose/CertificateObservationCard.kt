package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CertificateObservationCard(
    state: CertificateObservationState,
    onRefresh: () -> Unit,
    onTrust: () -> Unit,
    onManualInstall: () -> Unit,
    onExport: () -> Unit,
    actionBusy: Boolean = false,
) {
    val known = state.snapshot
    Card(Modifier.fillMaxWidth().testTag("certificate-observation-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.compose_settings_cert_title), style = MaterialTheme.typography.titleSmall)
            // These fields and action area remain mounted even while the first read is pending.
            Text(if (known == null) stringResource(if (state.stale) R.string.observation_unknown else R.string.observation_checking)
                else stringResource(known.serverState),
                Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("certificate-server-status"),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (known?.trust) {
                2 -> R.string.compose_settings_cert_level_system
                1 -> R.string.compose_settings_cert_level_user
                0 -> R.string.observation_cert_untrusted
                else -> R.string.observation_cert_trust_unknown
            }), Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("certificate-trust-status"),
                style = MaterialTheme.typography.bodySmall)
            Text(known?.daysLeft?.let { stringResource(R.string.compose_settings_cert_days, it) }
                ?: stringResource(R.string.observation_cert_expiry_unknown),
                Modifier.fillMaxWidth().heightIn(min = 36.dp), style = MaterialTheme.typography.bodySmall)
            Text(if (state.observedAt > 0) stringResource(
                if (state.stale) R.string.observation_unverified_at else R.string.observation_observed_at,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(state.observedAt)))
                else stringResource(if (state.stale) R.string.observation_unknown else R.string.observation_checking),
                Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("certificate-observed-at"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRefresh, enabled = !state.checking && !actionBusy,
                modifier = Modifier.testTag("certificate-refresh")) {
                Text(stringResource(if (state.checking) R.string.observation_checking else R.string.observation_refresh))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTrust,
                    enabled = known?.trust == 0 && !state.checking && !state.stale && !actionBusy,
                    modifier = Modifier.weight(1f).testTag("certificate-trust-action")) {
                    Text(stringResource(R.string.compose_settings_cert_trust))
                }
                OutlinedButton(onClick = onManualInstall,
                    enabled = known != null && !state.checking && !actionBusy,
                    modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.compose_settings_cert_install_manual))
                }
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth(), enabled = !actionBusy) {
                Text(stringResource(R.string.compose_settings_cert_export))
            }
        }
    }
}
