package org.adaway.ui.compose

/**
 * 规则页面：管理 hosts 黑白名单与订阅源。
 *
 * 三个标签页（Tab）：
 *  - 拦截（BLOCKED）   被重定向到本机 webserver 的广告域名
 *  - 放行（ALLOWED）   豁免拦截的域名
 *  - 重定向（REDIRECTED）重定向到自定义地址的域名
 *
 * 底部 SourcesCard：规则订阅源列表（名称/条数/开关/添加按钮），
 * 空状态时订阅卡片仍保留以便用户添加第一个订阅源。
 */

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    // 添加用户规则对话框状态
    var ruleHostInput by remember { mutableStateOf("") }
    var ruleType by remember { mutableIntStateOf(ListType.BLOCKED.value) }
    var ruleRedirectInput by remember { mutableStateOf("") }
    // 订阅源规则删除提示
    var deleteSourceHint by remember { mutableStateOf<org.adaway.db.entity.HostsSource?>(null) }

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
                topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_rules_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                    modifier = Modifier.weight(1f),
                )
                // 添加用户规则按钮（当前标签页类型）
                TextButton(onClick = { showAddRuleDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.compose_rules_add_rule))
                }
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
                            onDelete = { source ->
                                deleteSourceHint = source
                            },
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
                        RuleRow(
                            item = item,
                            sourceLabel = sourceLabels[item.sourceId],
                            onDelete = {
                                if (item.sourceId == 1) {
                                    // 用户自定义规则：直接删除
                                    viewModel.removeRule(item.host) {
                                        viewModel.loadRulesByType(tabs[selectedTab].type, RULES_PAGE_SIZE) { items2, total2 ->
                                            rules = items2
                                            totalCount = total2
                                        }
                                    }
                                } else {
                                    // 订阅源规则：提示可在订阅源中管理
                                    // （该条目来自订阅，删除后会随下次同步恢复）
                                    rules = rules.filterNot { it.host == item.host }
                                }
                            },
                        )
                    }
                    item(key = "sources") {
                        SourcesCard(
                            sources = sources,
                            onToggle = { source ->
                                viewModel.toggleSource(source) { reloadSources() }
                            },
                            onAddClick = { showAddDialog = true },
                            onDelete = { source ->
                                deleteSourceHint = source
                            },
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

    // ── 添加用户规则对话框 ──
    if (showAddRuleDialog) {
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text(stringResource(R.string.compose_rules_add_rule_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = ruleHostInput,
                        onValueChange = { ruleHostInput = it },
                        label = { Text(stringResource(R.string.compose_rules_rule_host_hint)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    // 规则类型选择：拦截 / 放行 / 重定向
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(ListType.BLOCKED.value, R.string.compose_logs_filter_blocked, 0),
                            Triple(ListType.ALLOWED.value, R.string.compose_logs_filter_allowed, 1),
                            Triple(ListType.REDIRECTED.value, R.string.compose_logs_filter_redirected, 2),
                        ).forEach { (value, labelRes, _) ->
                            FilterChip(
                                selected = ruleType == value,
                                onClick = { ruleType = value },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }
                    if (ruleType == ListType.REDIRECTED.value) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ruleRedirectInput,
                            onValueChange = { ruleRedirectInput = it },
                            label = { Text(stringResource(R.string.compose_rules_rule_redirect_hint)) },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ruleHostInput.isNotBlank() &&
                        (ruleType != ListType.REDIRECTED.value || ruleRedirectInput.isNotBlank()),
                    onClick = {
                        val host = ruleHostInput
                        val type = ruleType
                        val redirect = ruleRedirectInput
                        showAddRuleDialog = false
                        ruleHostInput = ""
                        ruleRedirectInput = ""
                        viewModel.addUserRule(host, type, redirect) {
                            viewModel.loadRulesByType(tabs[selectedTab].type, RULES_PAGE_SIZE) { items2, total2 ->
                                rules = items2
                                totalCount = total2
                            }
                        }
                    },
                ) { Text(stringResource(R.string.compose_rules_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text(stringResource(R.string.compose_rules_cancel))
                }
            },
        )
    }

    // ── 订阅源规则删除提示对话框 ──
    deleteSourceHint?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteSourceHint = null },
            title = { Text(stringResource(R.string.compose_rules_delete_source_title)) },
            text = { Text(stringResource(R.string.compose_rules_delete_source_confirm, source.label)) },
            confirmButton = {
                TextButton(onClick = {
                    val s = source
                    deleteSourceHint = null
                    viewModel.removeSource(s) {
                        reloadSources()
                        viewModel.syncHosts()
                    }
                }) {
                    Text(stringResource(R.string.compose_rules_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSourceHint = null }) {
                    Text(stringResource(R.string.compose_rules_cancel))
                }
            },
        )
    }
}

@Composable
/**
 * 规则订阅源卡：列出所有 hosts 订阅源（名称/条数/启用开关），
 * 提供“添加订阅源”按钮。空列表时仍显示以便添加首个订阅。
 */
private fun SourcesCard(
    sources: List<org.adaway.db.entity.HostsSource>,
    onToggle: (org.adaway.db.entity.HostsSource) -> Unit,
    onAddClick: () -> Unit,
    onDelete: (org.adaway.db.entity.HostsSource) -> Unit,
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
                        Spacer(Modifier.width(4.dp))
                        // 删除订阅源（确认对话框在 RulesScreen 弹出）
                        IconButton(onClick = { onDelete(source) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.compose_rules_delete_source),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    item: HostListItem,
    sourceLabel: String?,
    onDelete: (() -> Unit)? = null,
) {
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
            // 用户自定义规则（source_id == 1）可删除；订阅源规则也可触发提示
            if (onDelete != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.compose_rules_delete),
                        tint = if (item.sourceId == 1) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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