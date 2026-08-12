package org.adaway.ui.compose

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import org.adaway.ui.home.HomeActivity
import org.adaway.ui.prefs.PrefsActivity
import java.util.Locale

/**
 * Overview: service status card, hosts statistics summary, web server
 * status and quick actions. Information-first layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(viewModel: StatsViewModel) {
    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedHostCount.observeAsStateCompat(0)
    val allowedCount by viewModel.allowedHostCount.observeAsStateCompat(0)
    val redirectCount by viewModel.redirectHostCount.observeAsStateCompat(0)
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.compose_overview_title)) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Service status card
            StatusCard(
                running = viewModel.adBlockModel.isApplied().value ?: false,
                stateText = viewModel.adBlockModel.getState().value ?: "",
                hostsCount = blockedCount,
            )

            // Hosts statistics summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(stringResource(R.string.compose_hosts_blocked), blockedCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.compose_hosts_allowed), allowedCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.compose_hosts_redirected), redirectCount, Modifier.weight(1f))
            }

            // Web server card
            WebServerCard(
                running = viewModel.webServerRunning,
                stats = serverStats,
            )

            // Quick actions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_actions),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionRow(
                        icon = Icons.Filled.Sync,
                        label = stringResource(R.string.compose_sync_hosts),
                        subtitle = if (syncing) stringResource(R.string.compose_sync_hosts_running)
                        else stringResource(R.string.compose_sync_hosts_subtitle),
                        onClick = { viewModel.syncHosts() },
                    )
                    ActionRow(
                        icon = Icons.Outlined.Dns,
                        label = stringResource(R.string.compose_ws_settings),
                        subtitle = stringResource(R.string.compose_ws_settings_subtitle),
                        onClick = {
                            context.startActivity(Intent(context, PrefsActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, stateText: String, hostsCount: Int) {
    val color = if (running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(color = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.compose_status_ad_blocking),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stateText.ifEmpty {
                        if (running) stringResource(R.string.compose_status_active)
                        else stringResource(R.string.compose_status_not_applied)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                String.format(Locale.US, "%,d", hostsCount),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
internal fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                String.format(Locale.US, "%,d", value),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WebServerCard(running: Boolean, stats: ServerStats?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                tint = if (running) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.compose_web_server),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        !running -> stringResource(R.string.compose_web_server_not_running)
                        stats == null -> stringResource(R.string.compose_web_server_loading)
                        else -> stringResource(
                            R.string.compose_web_server_running,
                            stats.totalBlocked,
                            stats.sniCertsIssued,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running && stats != null) {
                Text(
                    formatUptime(stats.uptimeSeconds),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        else -> String.format(Locale.US, "%ds", secs)
    }
}

// LiveData observe helper
@Composable
internal fun <T> androidx.lifecycle.LiveData<T>.observeAsStateCompat(initial: T) =
    androidx.compose.runtime.produceState(initial, this) {
        value = initial
        val observer = androidx.lifecycle.Observer<T> { value = it }
        observeForever(observer)
        awaitDispose { removeObserver(observer) }
    }