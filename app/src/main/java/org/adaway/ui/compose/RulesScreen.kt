package org.adaway.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
 * Rules screen: manual whitelist / blacklist / redirect rules in three
 * tabs, backed by the user-defined hosts list (source_id == 1) in Room.
 */
private data class RuleTab(
    @StringRes val labelRes: Int,
    val type: ListType,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: StatsViewModel) {
    val blockedCount by viewModel.blockedHostCount.observeAsStateCompat(0)
    val allowedCount by viewModel.allowedHostCount.observeAsStateCompat(0)
    val redirectCount by viewModel.redirectHostCount.observeAsStateCompat(0)

    var selectedTab by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<List<HostListItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        viewModel.loadUserRules { rules = it }
    }

    val tabs = listOf(
        RuleTab(R.string.compose_rules_whitelist, ListType.ALLOWED),
        RuleTab(R.string.compose_rules_blacklist, ListType.BLOCKED),
        RuleTab(R.string.compose_rules_redirect, ListType.REDIRECTED),
    )
    val activeTab = tabs[selectedTab]
    val filtered = remember(rules, selectedTab) {
        rules.filter { it.type == activeTab.type }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
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

            if (filtered.isEmpty()) {
                EmptyRulesPlaceholder(tabs[selectedTab].labelRes)
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
                    items(filtered, key = { it.host }) { item ->
                        RuleRow(item)
                    }
                }
            }

            // Rule sources card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.compose_rules_sources),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.compose_rules_sources_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.compose_rules_user_total, rules.size),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleRow(item: HostListItem) {
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
private fun EmptyRulesPlaceholder(@StringRes labelRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (labelRes) {
                R.string.compose_rules_whitelist -> stringResource(R.string.compose_rules_empty_whitelist)
                R.string.compose_rules_blacklist -> stringResource(R.string.compose_rules_empty_blacklist)
                else -> stringResource(R.string.compose_rules_empty_redirect)
            },
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
