package org.adaway.ui.compose

/**
 * 规则页面：管理 hosts 黑白名单与订阅源。
 *
 * 三个标签页（Tab）：
 *  - 拦截（BLOCKED）   被重定向到本机 webserver 的广告域名
 *  - 放行（ALLOWED）   豁免拦截的域名
 *  - 重定向（REDIRECTED）重定向到自定义地址的域名
 *
 * 顶部 SourcesCard：规则订阅源列表（名称/条数/开关/添加按钮），
 * 空状态时订阅卡片仍保留以便用户添加第一个订阅源。
 */

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.adaway.R
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType
import org.adaway.db.entity.RuleListRow
import timber.log.Timber

/**
 * Rules screen: whitelist / blacklist / redirect tabs backed by the
 * hosts lists table. Blacklist and redirect show real entries from all
 * sources (bounded [RULES_PAGE_SIZE]-row pages with a matching total), so
 * the page is never empty even when the user has no manual rules.
 */

private data class RuleTab(
    @StringRes val labelRes: Int,
    val type: Int,          // ListType value
    @StringRes val emptyRes: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    viewModel: StatsViewModel,
    onOpenExempt: () -> Unit = {},
    onOpenOverrides: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var browserPage by remember { mutableStateOf(RuleBrowserPage(emptyList(), 0, 0)) }
    val rules = browserPage.rows
    val totalCount = browserPage.total
    var browseFilter by rememberSaveable { mutableIntStateOf(0) }
    var requestedPage by rememberSaveable { mutableIntStateOf(0) }
    var browseFailed by remember { mutableStateOf(false) }
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
    var deletePersonalRule by remember { mutableStateOf<HostListItem?>(null) }
    var removalSaving by remember { mutableStateOf(false) }
    var removalFailed by remember { mutableStateOf(false) }
    // 添加订阅源：远程链接 or 本地规则文件（SAF）
    var sourceIsFile by remember { mutableStateOf(false) }
    var pickedFileUri by remember { mutableStateOf("") }
    var pickedFileName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sourceAddFailed by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // 持久化读权限：同步（可能发生在几天后）时仍然可读
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Timber.w(e, "Failed to persist read permission for %s", uri)
            }
            pickedFileUri = uri.toString()
            pickedFileName = queryDisplayName(context, uri)
        }
    }

    val tabs = listOf(
        RuleTab(R.string.compose_rules_whitelist, ListType.ALLOWED.value, R.string.compose_rules_empty_whitelist),
        RuleTab(R.string.compose_rules_blacklist, ListType.BLOCKED.value, R.string.compose_rules_empty_blacklist),
        RuleTab(R.string.compose_rules_redirect, ListType.REDIRECTED.value, R.string.compose_rules_empty_redirect),
    )

    // Rule search (a list of thousands without one was unusable).
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    var showSources by rememberSaveable { mutableStateOf(false) }
    var inspectedRule by remember { mutableStateOf<RuleListRow?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var sourceSaving by remember { mutableStateOf(false) }
    var ruleSaving by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var ruleSearchLoading by remember { mutableStateOf(true) }
    var browseRequest by remember { mutableIntStateOf(0) }
    // "View the system hosts file" dialog: the rules screen is exactly where a
    // user asks "what is actually applied on this device?".
    var showHostsDialog by remember { mutableStateOf(false) }
    var systemHosts by remember { mutableStateOf<String?>(null) }

    fun reloadSources() {
        viewModel.loadSources { sources = it }
    }

    LaunchedEffect(selectedTab, searchQuery, browseFilter, requestedPage, refreshVersion, syncing) {
        val request = ++browseRequest
        ruleSearchLoading = true
        browseFailed = false
        reloadSources()
        try {
            val query = searchQuery.trim()
            if (query.isNotEmpty()) kotlinx.coroutines.delay(250)
            val loaded = viewModel.loadRulePage(tabs[selectedTab].type, query, browseFilter, requestedPage)
            // A cancelled old request must never replace the latest query's page.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            browserPage = loaded
            listState.scrollToItem(0)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (request == browseRequest) browseFailed = true
            Timber.w(failure, "Unable to load rule browser page")
        } finally {
            if (request == browseRequest) ruleSearchLoading = false
        }
    }

    Scaffold(
                topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_rules_title)) },
                actions = {
                    IconButton(onClick = {
                        systemHosts = null
                        showHostsDialog = true
                        scope.launch {
                            systemHosts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                org.adaway.model.root.ShellUtils.readSystemHostsFile()
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.Description, contentDescription = stringResource(R.string.compose_rules_view_hosts))
                    }
                    IconButton(enabled = !syncing, onClick = { viewModel.syncHosts() }) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.compose_sync_hosts)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index; requestedPage = 0 },
                        text = { Text(stringResource(tab.labelRes), maxLines = 1) },
                    )
                }
            }

            // Related rule tools share one horizontally scrollable line.
            // Keep both existing working routes, but reclaim space for rows.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showSources = !showSources; if (showSources) scope.launch { listState.scrollToItem(0) } }) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.compose_rules_manage_sources, sources.size))
                }
                TextButton(onClick = onOpenExempt) {
                    Text(stringResource(R.string.compose_exempt_entry))
                }
                TextButton(onClick = onOpenOverrides) {
                    Text(stringResource(R.string.compose_settings_rule_override))
                }
            }

            // Rule search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; requestedPage = 0 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; requestedPage = 0 }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.compose_rules_clear_search))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.compose_rules_search_hint)) },
            )

            RuleBrowserFilters(browseFilter) { browseFilter = it; requestedPage = 0 }

            // Total count for the active tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ruleSearchLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        ruleSearchLoading -> stringResource(R.string.compose_rules_loading)
                        browseFailed -> stringResource(R.string.compose_rules_load_failed)
                        totalCount == 0 -> stringResource(R.string.compose_rules_total, 0)
                        else -> stringResource(R.string.compose_rules_range,
                            browserPage.index * RULES_PAGE_SIZE + 1,
                            browserPage.index * RULES_PAGE_SIZE + rules.size, totalCount)
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 添加用户规则按钮（当前标签页类型）
                TextButton(enabled = !syncing, onClick = { showAddRuleDialog = true; ruleType = tabs[selectedTab].type }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.compose_rules_add_rule))
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
                if (showSources || sources.isEmpty()) {
                    item(key = "sources") {
                        SourcesCard(
                            sources = sources,
                            enabled = !syncing,
                            onToggle = { source, checked ->
                                viewModel.setSourceEnabled(source.id, checked) { saved ->
                                    reloadSources()
                                    if (saved) refreshVersion++
                                }
                            },
                            onAddClick = { showAddDialog = true },
                            onDelete = { deleteSourceHint = it; deletePersonalRule = null; removalFailed = false },
                        )
                    }
                }
                if (syncStatus.isFailure) {
                    item(key = "sync-status") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            RuleSyncCard(syncStatus, viewModel::syncHosts)
                        }
                    }
                }
                if (browseFailed) {
                    item(key = "load-error") {
                        TextButton(onClick = { refreshVersion++ }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.compose_rules_reload))
                        }
                    }
                }
                if (rules.isEmpty() && !ruleSearchLoading && !browseFailed) {
                    item(key = "empty") {
                        EmptyRulesPlaceholder(if (searchQuery.isNotBlank() || browseFilter != 0) R.string.compose_rules_search_empty else tabs[selectedTab].emptyRes)
                    }
                }
                if (!ruleSearchLoading && !browseFailed) {
                    items(rules, key = { it.item.id }) { row ->
                        val item = row.item
                        RuleRow(
                            item = item,
                            sourceLabel = row.sourceLabel,
                            active = row.isActive,
                            enabled = !syncing,
                            onInspect = { inspectedRule = row },
                            onDelete = {
                                if (item.sourceId == 1) {
                                    deletePersonalRule = item
                                    deleteSourceHint = null
                                    removalFailed = false
                                } else {
                                    inspectedRule = row
                                }
                            },
                        )
                    }
                }
            }
            if (browserPage.pageCount > 1 && !browseFailed) {
                RuleBrowserPager(browserPage.index, browserPage.pageCount, ruleSearchLoading) { requestedPage = it }
            }

        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!sourceSaving) showAddDialog = false },
            title = { Text(stringResource(R.string.compose_rules_add_source)) },
            text = {
                Column {
                    // 来源类型：远程订阅链接 / 本地规则文件
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !sourceIsFile,
                            onClick = { sourceIsFile = false },
                            label = { Text(stringResource(R.string.compose_rules_source_type_url)) },
                        )
                        FilterChip(
                            selected = sourceIsFile,
                            onClick = { sourceIsFile = true },
                            label = { Text(stringResource(R.string.compose_rules_source_type_file)) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (sourceIsFile) {
                        // 本地规则：用系统文件选择器挑选 hosts / 规则文本文件
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.compose_rules_pick_file))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pickedFileName.ifBlank {
                                stringResource(R.string.compose_rules_pick_file_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text(stringResource(R.string.compose_rules_source_url_hint)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            ),
                        )
                    }
                    if (sourceAddFailed) {
                        Text(stringResource(R.string.compose_rules_add_source_failed),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                val ready = if (sourceIsFile) pickedFileUri.isNotBlank() else urlInput.isNotBlank()
                TextButton(
                    enabled = ready && !sourceSaving,
                    onClick = {
                        val url = if (sourceIsFile) pickedFileUri else urlInput
                        val label = if (sourceIsFile) pickedFileName else null
                        sourceSaving = true
                        sourceAddFailed = false
                        viewModel.addSource(url, label) { ok ->
                            sourceSaving = false
                            if (ok) {
                                showAddDialog = false
                                showSources = true
                                urlInput = ""
                                pickedFileUri = ""
                                pickedFileName = ""
                                sourceIsFile = false
                                reloadSources()
                                viewModel.syncHosts()
                            } else {
                                sourceAddFailed = true
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.compose_rules_add_source_failed),
                                    )
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.compose_rules_add)) }
            },
            dismissButton = {
                TextButton(enabled = !sourceSaving, onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.compose_rules_cancel))
                }
            },
        )
    }

    // ── 添加用户规则对话框 ──
    if (showAddRuleDialog) {
        AlertDialog(
            onDismissRequest = { if (!ruleSaving) showAddRuleDialog = false },
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
                    enabled = !ruleSaving && ruleHostInput.isNotBlank() &&
                        (ruleType != ListType.REDIRECTED.value || ruleRedirectInput.isNotBlank()),
                    onClick = {
                        val host = ruleHostInput
                        val type = ruleType
                        val redirect = ruleRedirectInput
                        /*
                         * 等结果回来再决定收尾动作：此前是先关对话框、清空输入，再去异步写入 ——
                         * 失败时用户既不知道原因，也丢了刚填的内容（重复规则、域名格式不合法、
                         * 数据库写入失败都表现为对话框关了但列表里没有）。
                         */
                        ruleSaving = true
                        viewModel.addUserRule(host, type, redirect) { added ->
                            ruleSaving = false
                            if (added) {
                                showAddRuleDialog = false
                                ruleHostInput = ""
                                ruleRedirectInput = ""
                                refreshVersion++
                            } else {
                                /* 失败：保留对话框与输入，用户可以直接改一改再试 */
                                Toast.makeText(
                                    context,
                                    R.string.compose_rules_add_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.compose_rules_add)) }
            },
            dismissButton = {
                TextButton(enabled = !ruleSaving, onClick = { showAddRuleDialog = false }) {
                    Text(stringResource(R.string.compose_rules_cancel))
                }
            },
        )
    }

    inspectedRule?.let { row ->
        RuleDetailsDialog(
            row = row,
            onDismiss = { inspectedRule = null },
            onCopyHost = { host ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.compose_rules_detail_domain), host))
            },
            onManageSource = {
                inspectedRule = null
                showSources = true
                scope.launch { listState.scrollToItem(0) }
            },
        )
    }

    if (deleteSourceHint != null || deletePersonalRule != null) {
        val source = deleteSourceHint
        val personal = deletePersonalRule
        RuleRemovalDialog(
            title = stringResource(if (source != null) R.string.compose_rules_delete_source_title
                else R.string.compose_rules_remove_personal_title),
            target = if (source != null) source.label.orEmpty().ifBlank {
                stringResource(R.string.compose_rules_detail_source_id, source.id)
            } else personal!!.host,
            explanation = stringResource(if (source != null) R.string.compose_rules_remove_source_effect
                else R.string.compose_rules_remove_personal_effect),
            busy = removalSaving,
            failed = removalFailed,
            canConfirm = !syncing,
            onConfirm = {
                if (!removalSaving && !syncing) {
                    removalSaving = true
                    removalFailed = false
                    val onDone: (Boolean) -> Unit = { committed ->
                        removalSaving = false
                        removalFailed = !committed
                        reloadSources()
                        if (committed) {
                            deleteSourceHint = null
                            deletePersonalRule = null
                            refreshVersion++
                        }
                    }
                    if (source != null) viewModel.removeSource(source, onDone)
                    else viewModel.removeRule(personal!!.host, onDone)
                }
            },
            onDismiss = { deleteSourceHint = null; deletePersonalRule = null; removalFailed = false },
        )
    }

    // ── 查看系统 hosts（/system/etc/hosts，含 Magisk 系统级覆盖）──
    if (showHostsDialog) {
        AlertDialog(
            onDismissRequest = { showHostsDialog = false },
            title = { Text(stringResource(R.string.compose_rules_view_hosts)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.compose_rules_view_hosts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val body = systemHosts
                    when {
                        body == null -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        body.isEmpty() -> Text(
                            stringResource(R.string.compose_rules_view_hosts_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            body,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !systemHosts.isNullOrEmpty(),
                    onClick = {
                        val body = systemHosts ?: return@TextButton
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("/system/etc/hosts", body))
                        Toast.makeText(context, R.string.compose_rules_view_hosts_copied, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(stringResource(R.string.compose_rules_view_hosts_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHostsDialog = false }) {
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
    enabled: Boolean,
    sources: List<org.adaway.db.entity.HostsSource>,
    onToggle: (org.adaway.db.entity.HostsSource, Boolean) -> Unit,
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
                TextButton(enabled = enabled, onClick = onAddClick) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.compose_rules_add_source))
                }
            }
            Text(stringResource(R.string.compose_rules_source_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (sources.isEmpty()) {
                Text(
                    stringResource(R.string.compose_rules_sources_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sources.forEach { source ->
                    androidx.compose.runtime.key(source.id) {
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
                            enabled = enabled,
                            onCheckedChange = { checked -> onToggle(source, checked) },
                        )
                        Spacer(Modifier.width(4.dp))
                        // 删除订阅源（确认对话框在 RulesScreen 弹出）
                        IconButton(enabled = enabled, onClick = { onDelete(source) }) {
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
}

@Composable
internal fun RuleRow(
    item: HostListItem,
    sourceLabel: String?,
    enabled: Boolean = true,
    active: Boolean = item.isEnabled,
    onInspect: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (onInspect != null) Modifier.clickable(
                onClickLabel = stringResource(R.string.compose_rules_details), onClick = onInspect,
            ) else Modifier),
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
                tint = if (active) ruleTint(item.type) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.host,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
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
                Text(
                    stringResource(ruleScopeLabel(item.matchScope)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val label = if (item.sourceId == 1) stringResource(R.string.compose_rules_personal) else sourceLabel
                if (!label.isNullOrEmpty()) {
                    Text(
                        stringResource(if (active) R.string.compose_rules_source_enabled else R.string.compose_rules_source_disabled, label),
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
                color = if (active) ruleTint(item.type) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 用户自定义规则（source_id == 1）可删除；订阅源规则也可触发提示
            if (onDelete != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(enabled = enabled, onClick = onDelete) {
                    Icon(
                        if (item.sourceId == 1) Icons.Outlined.Delete else Icons.Outlined.Info,
                        contentDescription = stringResource(if (item.sourceId == 1) R.string.compose_rules_delete else R.string.compose_rules_manage_source),
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

/**
 * 查询 SAF 文件显示名，用于订阅源标签（失败时回退为 URI 末段）。
 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    } catch (e: Exception) {
        uri.lastPathSegment ?: uri.toString()
    }
}
