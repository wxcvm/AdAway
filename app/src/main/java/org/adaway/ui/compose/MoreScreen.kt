package org.adaway.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R

/**
 * Discoverability hub for existing secondary destinations. Only actual routes
 * have click handlers. Future capture features stay on NetworkCenterScreen,
 * prominently labeled as unavailable until their backend is verified.
 */
private data class MoreAction(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenLogs: () -> Unit,
    onOpenExempt: () -> Unit,
    onOpenOverrides: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tools = listOf(
        MoreAction(R.string.compose_more_logs, R.string.compose_more_logs_desc, onOpenLogs),
        MoreAction(R.string.compose_more_exempt, R.string.compose_more_exempt_desc, onOpenExempt),
        MoreAction(R.string.compose_more_overrides, R.string.compose_more_overrides_desc, onOpenOverrides),
        MoreAction(R.string.compose_more_network, R.string.compose_more_network_desc, onOpenNetwork),
        MoreAction(R.string.compose_more_settings, R.string.compose_more_settings_desc, onOpenSettings),
    )
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.compose_nav_more)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.compose_more_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            items(tools.size) { i ->
                val action = tools[i]
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = action.onClick),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(action.title)) },
                        supportingContent = { Text(stringResource(action.description)) },
                        trailingContent = {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.compose_more_planned),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.compose_more_planned_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}
