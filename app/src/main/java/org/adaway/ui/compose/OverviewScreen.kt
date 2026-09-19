package org.adaway.ui.compose

/**
 * 概览页面：信息优先布局。
 *
 * 卡片顺序：
 *  1. StatusCard         hosts 拦截服务状态（启用/运行中/规则数）
 *  2. StatTile x3        拦截/放行/重定向 hosts 数量摘要
 *  3. WebServerCard      Web 服务器运行状态摘要
 *  4. WebServerControlCard Web 服务器开关/端口/测试入口（设置项移入概览）
 *  5. Quick actions      同步 hosts 等快捷操作
 */

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.R
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
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    // 进程检测会执行 root shell（su pgrep / ps / grep），既不能在组合期做，
    // 也不能在主线程做：旧代码是 remember { isWebServerRunning() }，进页面
    // 就阻塞主线程，慢机器上还会 ANR。这里只给保守初值，真实状态在 IO 线程解析。
    var wsEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val running = withContext(Dispatchers.IO) {
            org.adaway.util.WebServerUtils.isWebServerRunning()
        }
        // 进程检测可能假阴性：探活成功即视为运行中（后台线程，避免主线程网络）
        val reachable = running || withContext(Dispatchers.IO) {
            org.adaway.util.WebServerUtils.isWebServerReachable(context)
        }
        wsEnabled = reachable
    }
    // 证书状态：getWebServerState() 内部含 OkHttp HTTP 探活，
    // 禁止在主线程调用（否则 NetworkOnMainThreadException 崩溃），
    // 因此放到 LaunchedEffect 后台线程获取。
    var certStateRes by remember { mutableIntStateOf(R.string.pref_webserver_state_not_running) }
    LaunchedEffect(wsEnabled) {
        certStateRes = withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.adaway.util.WebServerUtils.getWebServerState(context)
        }
    }
    // 启动失败原因：未运行时读取 root 启动日志尾部（仅一次，避免重复 root 调用）
    var startLog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wsEnabled) {
        startLog = if (!wsEnabled) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.adaway.model.root.ShellUtils.readBundledExecutableStartLog("webserver").ifBlank { null }
            }
        } else null
    }

    Scaffold(
                topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_overview_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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

            // Web server quick stats row（利用空位：证书/请求/拦截率）
            if (serverStats != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        stringResource(R.string.compose_ws_control_certs),
                        serverStats!!.sniCertsIssued.toInt(),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        stringResource(R.string.compose_stats_kpi_requests),
                        serverStats!!.totalRequests.toInt(),
                        Modifier.weight(1f),
                    )
                    val rate = if (serverStats!!.totalRequests > 0)
                        (serverStats!!.totalBlocked * 100 / serverStats!!.totalRequests).toInt() else 0
                    StatTile(
                        stringResource(R.string.compose_stats_kpi_rate),
                        rate,
                        Modifier.weight(1f),
                    )
                }
            }

            // Web server card
            WebServerCard(
                running = wsEnabled,
                stats = serverStats,
            )

                        // Web server control card
            WebServerControlCard(
                enabled = wsEnabled,
                stats = serverStats,
                httpPort = org.adaway.util.WebServerUtils.getHttpPort(context),
                httpsPort = org.adaway.util.WebServerUtils.getHttpsPort(context),
                certStateRes = certStateRes,
                startLog = startLog,
                onToggle = { enable ->
                    // 乐观更新：start/stop 之后的进程检测同样要走 root shell，
                    // 放后台复核，避免点一下卡一下。
                    wsEnabled = enable
                    if (enable) {
                        org.adaway.util.WebServerUtils.startWebServer(context)
                    } else {
                        org.adaway.util.WebServerUtils.stopWebServer()
                    }
                    uiScope.launch {
                        val running = withContext(Dispatchers.IO) {
                            org.adaway.util.WebServerUtils.isWebServerRunning() ||
                                org.adaway.util.WebServerUtils.isWebServerReachable(context)
                        }
                        wsEnabled = running
                    }
                },
                onTest = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(org.adaway.util.WebServerUtils.getTestUrl(context))))
                    } catch (e: Exception) {
                        // no browser available; ignore
                    }
                },
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
                }
            }
        }
    }
}

@Composable
/**
 * Web 服务器控制卡：概览页的核心交互区。
 *
 * 功能：
 *  - 启停开关（调用 WebServerUtils.start/stopWebServer）
 *  - 显示当前 HTTP/HTTPS 端口与累计统计摘要
 *  - “打开测试页”按钮（https://localhost:<port>/internal-test）
 *  - “设置”按钮（跳转 PrefsActivity 旧版设置页）
 *
 * 设计意图：把用户最常用的 webserver 操作从设置页
 * 提升到首页，利用概览页空位，减少跳转层级。
 */
private fun WebServerControlCard(
    enabled: Boolean,
    stats: ServerStats?,
    httpPort: Int,
    httpsPort: Int,
    @androidx.annotation.StringRes certStateRes: Int,
    startLog: String?,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val certInstalled = certStateRes == R.string.pref_webserver_state_running_and_installed ||
        certStateRes == R.string.pref_webserver_state_running_and_installed_system
    val certNeedsAction = enabled && !certInstalled
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_ws_control_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            if (enabled) R.string.compose_ws_control_running
                            else R.string.compose_ws_control_stopped,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DetailChip(
                    stringResource(R.string.compose_ws_control_ports),
                    "$httpPort / $httpsPort",
                )
                DetailChip(
                    stringResource(R.string.compose_ws_control_images),
                    "${stats?.blockImageCount ?: 0}",
                )
                DetailChip(
                    stringResource(R.string.compose_ws_control_certs),
                    "${stats?.sniCertsIssued ?: 0}",
                )
            }
            Spacer(Modifier.height(8.dp))
            // 证书状态行：未安装/已变更时高亮提示 + 一键安装按钮 + 剩余天数
            val daysLeft = org.adaway.util.WebServerUtils.getCertificateDaysLeft(context)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(certStateRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (certNeedsAction) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (daysLeft != null && enabled) {
                    Text(
                        stringResource(R.string.compose_ws_control_cert_days, daysLeft),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysLeft < 30) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onTest, enabled = enabled) {
                    Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.compose_ws_control_test))
                }
            }
            // 启动失败原因透出：展示 root 启动日志尾部
            if (!enabled && !startLog.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.compose_ws_control_log_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    startLog!!,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 10,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
/**
 * 服务状态卡：显示 hosts 拦截服务是否已应用、
 * 当前状态文本与规则条数。颜色语义：运行=主色，停止=错误色。
 */
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
/**
 * Web 服务器状态摘要卡：运行状态点 + 关键指标
 * （请求数/拦截数/SNI 证书数，取自最近一次统计快照）。
 */
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
