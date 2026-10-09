package org.adaway.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import org.adaway.util.WebServerUtils

/**
 * 三级页：单个应用的明细（从统计页「活跃应用」里点某一项进入）。
 *
 * <p>层级：一级「统计」→ 二级「活跃应用」卡片 → 三级本页；返回键/按钮逐级退回。</p>
 *
 * <p>只展示已经在活跃应用列表里验证过、确实存在的字段（连接/请求/拦截、每秒速率、
 * 握手失败、是否被改为廉价拒绝），不猜测数据形状 —— 宁可少显示，也不要编译不过或显示错值。</p>
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(viewModel: StatsViewModel, uid: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val stats by viewModel.serverStats.collectAsStateWithLifecycle()
    val app = stats?.apps?.firstOrNull { it.uid == uid }
    val uptime = stats?.uptimeSeconds ?: 0L
    val allowed = isAppAllowed(context, uid)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appNameForUid(uid)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.compose_subpage_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.compose_settings_allowlist_uid, uid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (app == null) {
                /* 应用列表是按需采集的：还没采集到这个 uid 时给出明确说明，而不是空页面 */
                Text(
                    stringResource(R.string.compose_settings_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.compose_stats_history_requests),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            app.requests.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.compose_stats_history_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            app.blocked.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.compose_stats_history_connections),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            app.connections.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.compose_stats_app_rate),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(
                                R.string.compose_stats_app_storm,
                                app.requestsPerSecond(uptime),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            /*
             * 它命中最多的域（原生侧 app_note_host 记录，最多 3 个）——
             * 这才是"这个应用到底在打哪里"，也是判断"是不是在重试同一个域"的依据。
             */
            if (app.hosts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.compose_stats_app_hosts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        app.hosts.forEach { (host, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    host,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        /* 服务端实测速率（两次统计快照之间），不是界面自己估算的 */
                        if (app.tps > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.compose_stats_app_storm, app.tps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            /* 只在有话说的时候才显示：避免把"0 次"这种噪音塞给用户 */
            if (app.tlsFail > 0L || app.pinRefused || allowed) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (app.tlsFail > 0L) {
                            Text(
                                stringResource(R.string.compose_stats_app_tls_fail, app.tlsFail),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (app.pinRefused) {
                            Text(
                                stringResource(R.string.compose_stats_app_pin_refused),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (allowed) {
                            Text(
                                stringResource(R.string.compose_settings_app_allow),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.compose_stats_app_detail_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
