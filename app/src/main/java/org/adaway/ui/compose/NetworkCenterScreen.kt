package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import org.adaway.R

/**
 * Reserved integration center for the traffic-capture roadmap.
 * This screen ONLY displays implementation status and navigates to working
 * screens. It must not claim all traffic is captured or offer dead toggles.
 */
private data class PlannedCapability(
    @StringRes val title: Int,
    @StringRes val description: Int,
)

private val capabilities = listOf(
    PlannedCapability(R.string.compose_network_capture_title, R.string.compose_network_capture_description),
    PlannedCapability(R.string.compose_network_adguard_title, R.string.compose_network_adguard_description),
    PlannedCapability(R.string.compose_network_dns_title, R.string.compose_network_dns_description),
    PlannedCapability(R.string.compose_network_https_title, R.string.compose_network_https_description),
    PlannedCapability(R.string.compose_network_recovery_title, R.string.compose_network_recovery_description),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkCenterScreen(
    onOpenSettings: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit = {},
) {
    var showRoadmap by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_network_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("network-center-back"),
                    ) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.compose_settings_home_back),
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("integration-center-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Shield, contentDescription = null)
                            Text(
                                stringResource(R.string.compose_network_status_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            stringResource(R.string.compose_network_status_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                        ) {
                            Text(
                                stringResource(R.string.compose_network_not_active),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.compose_network_available_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.compose_network_available_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Three full-width, minimum-touch-size actions avoid
                        // clipped localized text at 320dp and large font scale.
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("integration-open-settings"),
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.compose_nav_settings))
                        }
                        OutlinedButton(
                            onClick = onOpenRules,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("integration-open-rules"),
                        ) {
                            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.compose_nav_rules))
                        }
                        OutlinedButton(
                            onClick = onOpenStats,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("integration-open-stats"),
                        ) {
                            Text(stringResource(R.string.compose_nav_stats))
                        }
                    }
                }
            }
            item(key = "roadmap-toggle") {
                Card(onClick = { showRoadmap = !showRoadmap },
                    modifier = Modifier.fillMaxWidth().testTag("integration-roadmap-toggle"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.compose_network_roadmap_heading)) },
                        supportingContent = { Text(stringResource(R.string.compose_network_planned)) },
                        trailingContent = {
                            Icon(if (showRoadmap) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null)
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            if (showRoadmap) items(capabilities, key = { it.title }) { capability ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("integration-planned-${capability.title}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(capability.title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(capability.description))
                                Text(
                                    stringResource(R.string.compose_network_planned),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Info, contentDescription = null)
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }

        }
    }
}
