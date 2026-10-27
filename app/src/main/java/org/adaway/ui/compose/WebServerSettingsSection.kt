package org.adaway.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.WebServerSettingsSection() {
    val context = LocalContext.current
    // ── Web server settings ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_ws_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_settings_ws_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ListenerSettingsCard()
            Spacer(Modifier.height(8.dp))
            // ── 轻量模式（省内存）──
            var lightMode by remember { mutableStateOf(isLightMode(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_light_mode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_light_mode_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = lightMode,
                    onCheckedChange = { v ->
                        lightMode = v
                        setLightMode(context, v)
                        // Apply the cadence change immediately: light mode
                        // switches the recovery check between 6 h and the
                        // 5 min / 15 min pair.
                        org.adaway.broadcast.ServerWatchdogWorker.ensureScheduled(context)
                        if (!v) {
                            // Leaving light mode: make sure it is running.
                            // startWebServer 会走 root shell，放到后台线程执行。
                            Thread {
                                org.adaway.util.WebServerUtils.startWebServer(context)
                            }.start()
                        }
                    },
                )
            }

        }
    }
}
