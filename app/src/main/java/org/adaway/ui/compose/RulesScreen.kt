package org.adaway.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType

/**
 * Rules screen: whitelist / blacklist / redirect tabs backed by the
 * hosts lists table. Blacklist and redirect show real entries from all
 * enabled sources (capped at [RULES_PAGE_SIZE] rows with a total), so
 * the page is never empty even when the user has no manual rules.
 */
private const val RULES_PAGE_SIZE = 300

private data class RuleTab(
    @StringRes val labelRes: Int,
    val type: Int,          // ListType value
    @StringRes val emptyRes: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: StatsViewModel) {
    val blockedCount by viewModel.blockedHostCount.observeAsStateCompat(0)
    val allowedCount by viewModel.allowedHostCount.observeAsStateCompat(0)
    val redirectCount by viewModel.redirectHostCount.observeAsStateCompat(0)

    var selectedTab by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<List<HostListItem>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var sourceLabels by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var sources by remember { mutableStateOf<List<org.adaway.db.entity.HostsSource>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val tabs = listOf(
        RuleTab(R.string.compose_rules_whitelist, ListType.ALLOWED.value, R.string.compose_rules_empty_whitelist),
        RuleTab(R.string.compose_rules_blacklist, ListType.BLOCKED.value, R.string.compose_rules_empty_blacklist),
        RuleTab(R.string.compose_rules_redirect, ListType.REDIRECTED.value, R.string.compose_rules_empty_redirect),
    )

    fun reloadSources() {
        viewModel.loadSources { sources = it }
    }

    LaunchedEffect(Unit) {
        viewModel.loadSourceLabels { sourceLabels = it }
        reloadSources()
    }
    LaunchedEffect(selectedTab) {
        viewModel.loadRulesByType(tabs[selectedTab].type, RULES_PAGE_SIZE) { items, total ->
            rules = items
            totalCount = total
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_rules_title)) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Hosts statistics summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(stringResource(R.string.compose_hosts_blocked), blockedCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.compose_hosts_allowed), allowedCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.compose_hosts_redirected), redirectCount, Modifier.weight(1f))
            }

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }

            // Total count for the active tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.compose_rules_total, totalCount),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (rules.isEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    item(key = "empty") {
                        EmptyRulesPlaceholder(tabs[selectedTab].emptyRes)
                    }
                    item(key = "sources") {
                        SourcesCard(
                            sources = sources,
                            onToggle = { source ->
                                viewModel.toggleSource(source) { reloadSources() }
                            },
                            onAddClick = { showAddDialog = true },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    items(rules, key = { "${it.host}:${it.sourceId}" }) { item ->
                        RuleRow(item = item, sourceLabel = sourceLabels[item.sourceId])
                    }
                    item(key = "sources") {
                        SourcesCard(
                            sources = sources,
                            onToggle = { source ->
                                viewModel.toggleSource(source) { reloadSources() }
                            },
                            onAddClick = { showAddDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.compose_rules_add_source)) },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.compose_rules_source_url_hint)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = urlInput.isNotBlank(),
                    onClick = {
                        val url = urlInput
                        showAddDialog = false
                        urlInput = ""
                        viewModel.addSource(url) { ok ->
                            if (ok) {
                                reloadSources()
                                viewModel.syncHosts()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.compose_rules_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.compose_rules_cancel))
                }
            },
        )
    }
}

@Composable
private fun SourcesCard(
    sources: List<org.adaway.db.entity.HostsSource>,
    onToggle: (org.adaway.db.entity.HostsSource) -> Unit,
    onAddClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.compose_rules_sources),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddClick) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.compose_rules_add_source))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (sources.isEmpty()) {
                Text(
                    stringResource(R.string.compose_rules_sources_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                source.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.compose_rules_source_size, source.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = source.isEnabled(),
                            onCheckedChange = { onToggle(source) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(item: HostListItem, sourceLabel: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                modifier = Modifier.width(20.dp),
                tint = ruleTint(item.type),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.host,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.type == ListType.REDIRECTED && !item.redirection.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.compose_rules_redirect_to, item.redirection),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!sourceLabel.isNullOrEmpty()) {
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                when (item.type) {
                    ListType.BLOCKED -> stringResource(R.string.compose_logs_filter_blocked)
                    ListType.ALLOWED -> stringResource(R.string.compose_logs_filter_allowed)
                    ListType.REDIRECTED -> stringResource(R.string.compose_logs_filter_redirected)
                },
                style = MaterialTheme.typography.labelSmall,
                color = ruleTint(item.type),
            )
        }
    }
}

@Composable
private fun EmptyRulesPlaceholder(@StringRes emptyRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(emptyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ruleTint(type: ListType): androidx.compose.ui.graphics.Color = when (type) {
    ListType.BLOCKED -> MaterialTheme.colorScheme.error
    ListType.ALLOWED -> MaterialTheme.colorScheme.primary
    ListType.REDIRECTED -> MaterialTheme.colorScheme.tertiary
}