package org.adaway.ui.compose

/**
 * 统计页面：ADBlock 的核心数据展示。
 *
 * 图表组成（均可通过设置页开关控制）：
 *  - LifetimeCard    累计统计（请求/拦截/连接/证书，重启不丢失）
 *  - DonutChart      环形图（按类型展示拦截占比，中心显示总数）
 *  - BlockRateCard   拦截率环（拦截/请求百分比）
 *  - TrafficTrendCard 趋势图（24h/7d/30d/永久，line/area/bars 三种样式）
 *  - ActiveAppsCard  活跃应用（per-app 连接/请求/拦截统计）
 *  - RecentCertsCard 最近签发的 SNI 证书列表
 *  - ServerDetailsCard 服务器详情（uptime/类型计数）
 *
 * 数据来源：StatsViewModel 每 10s 轮询 webserver /internal-stats。
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import java.util.Locale

/**
 * Per-type blocked request category with label and color.
 */
internal data class BlockCategory(
    val label: String,
    val count: Long,
    val color: Color,
)

/**
 * Build the ordered category list from a server stats snapshot.
 * Categories with zero count are omitted from the chart.
 */
@Composable
internal fun buildCategories(stats: ServerStats): List<BlockCategory> {
    val theme = MaterialTheme.colorScheme
    return listOf(
        BlockCategory(stringResource(R.string.compose_stats_type_images), stats.blockedImages, theme.primary),
        BlockCategory(stringResource(R.string.compose_stats_type_scripts), stats.blockedScripts, theme.secondary),
        BlockCategory(stringResource(R.string.compose_stats_type_styles), stats.blockedStyles, theme.tertiary),
        BlockCategory(stringResource(R.string.compose_stats_type_fonts), stats.blockedFonts, theme.primary.copy(alpha = 0.7f)),
        BlockCategory(stringResource(R.string.compose_stats_type_media), stats.blockedMedia, theme.secondary.copy(alpha = 0.7f)),
        BlockCategory(stringResource(R.string.compose_stats_type_api), stats.blockedApi, theme.tertiary.copy(alpha = 0.7f)),
        BlockCategory(stringResource(R.string.compose_stats_type_telemetry), stats.blockedTelemetry, Color(0xFFE6A23C)),
        BlockCategory(stringResource(R.string.compose_stats_type_heartbeat), stats.blockedHeartbeat, Color(0xFF67C23A)),
        BlockCategory(stringResource(R.string.compose_stats_type_config), stats.blockedConfig, Color(0xFF909399)),
        BlockCategory(stringResource(R.string.compose_stats_type_ws), stats.blockedWsSse, Color(0xFF9C27B0)),
        BlockCategory(stringResource(R.string.compose_stats_type_crypto), stats.blockedCrypto, Color(0xFFBBDEFB)),
        BlockCategory(stringResource(R.string.compose_stats_type_clickbait), stats.blockedClickbait, Color(0xFFFFCCBC)),
        BlockCategory(stringResource(R.string.compose_stats_type_other), stats.blockedOther, Color(0xFF795548)),
    ).filter { it.count > 0 }
}

/**
 * Statistics screen: hosts totals, web server blocked-request donut
 * chart with per-type breakdown, and server details. AdGuard-style
 * data density without third-party chart dependencies.
 *
 * 页面布局自上而下：
 *  1. hosts 列表总量卡（拦截/放行/重定向数量）
 *  2. 累计统计卡（LifetimeCard，重启不丢失）
 *  3. 拦截率环（BlockRateCard）
 *  4. 分类环形图（DonutChart，中心显示拦截总数）
 *  5. 趋势图（TrafficTrendCard，24h/7d/30d/永久 + 三样式）
 *  6. 活跃应用（ActiveAppsCard）
 *  7. 最近签发证书（RecentCertsCard）
 *  8. 服务器详情（ServerDetailsCard）
 *
 * 每个卡片的显隐均由设置页 isChartEnabled(key) 控制，
 * 用户可高度自定义统计页内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(viewModel: StatsViewModel) {
    // ── 从 ViewModel 收集数据流（每 10s 轮询一次 webserver）──
    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedHostCount.observeAsStateCompat(0)
    val allowedCount by viewModel.allowedHostCount.observeAsStateCompat(0)
    val redirectCount by viewModel.redirectHostCount.observeAsStateCompat(0)
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── 读取设置页的图表开关（键值见 SettingsScreen.kt）──
    val showLifetime = isChartEnabled(context, "chart_lifetime")
    val showRate = isChartEnabled(context, "chart_rate")
    val showDonut = isChartEnabled(context, "chart_donut")
    val showTrend = isChartEnabled(context, "chart_trend")
    val showBars = isChartEnabled(context, "chart_bars")
    val showApps = isChartEnabled(context, "chart_apps")
    val showCerts = isChartEnabled(context, "chart_certs")
    val showConn = isChartEnabled(context, "chart_conn")
    val showQlog = isChartEnabled(context, "chart_qlog")

    Scaffold(
                topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_stats_title)) },
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
            // Hosts list totals
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_stats_hosts_list),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CountLabel(stringResource(R.string.compose_hosts_blocked), blockedCount)
                        CountLabel(stringResource(R.string.compose_hosts_allowed), allowedCount)
                        CountLabel(stringResource(R.string.compose_hosts_redirected), redirectCount)
                    }
                }
            }

            // 统计状态横幅：更新时间 + 状态（hosts 统计不依赖 webserver）
            val updatedAt by viewModel.statsUpdatedAt.collectAsStateWithLifecycle()
            val statsErr by viewModel.statsError.collectAsStateWithLifecycle()
            val statsTime = remember(updatedAt) {
                if (updatedAt > 0L) {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(updatedAt))
                } else null
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (statsErr == null && statsTime != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(R.string.compose_stats_status_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                statsTime != null && statsErr == null ->
                                    stringResource(R.string.compose_stats_status_ok, statsTime)
                                statsTime != null ->
                                    stringResource(R.string.compose_stats_status_stale, statsTime)
                                else -> stringResource(R.string.compose_stats_status_waiting)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── KPI 卡片行（参考 AdGuard Home：数值 + 迷你趋势线）──
            if (serverStats != null) {
                KpiCardRow(stats = serverStats!!)
            } else {
                /*
                 * 服务器未运行/不可达：不显示空白，改显示本机规则统计
                 * （Room 直接读取，无网络与进程开销）。
                 */
                var localCounts by remember { mutableStateOf(Triple(0, 0, 0)) }
                LaunchedEffect(statsErr) {
                    viewModel.loadLocalHostCounts { blocked, allowed, redirected ->
                        localCounts = Triple(blocked, allowed, redirected)
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.compose_stats_status_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                stringResource(R.string.compose_hosts_blocked) + ": " + localCounts.first,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.compose_hosts_allowed) + ": " + localCounts.second,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.compose_hosts_redirected) + ": " + localCounts.third,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            // Blocked-request chart
            if (showDonut) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.compose_stats_blocked_by_type),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Start),
                        )
                        Spacer(Modifier.height(12.dp))
                        if (serverStats == null || serverStats!!.totalBlocked == 0L) {
                            EmptyChartPlaceholder()
                        } else {
                            val stats = serverStats!!
                            DonutChart(stats = stats)
                            Spacer(Modifier.height(12.dp))
                            val categories = buildCategories(stats)
                            categories.forEach { CategoryRow(it, stats.totalBlocked) }
                        }
                    }
                }
            }

            // Lifetime totals
            if (showLifetime && serverStats != null) {
                LifetimeCard(serverStats!!)
            }

            // Block rate ring
            if (showRate && serverStats != null && serverStats!!.totalRequests > 0) {
                BlockRateCard(serverStats!!)
            }

            // Trend + traffic chart (AdGuard Home style)
            if (showTrend && serverStats != null &&
                (serverStats!!.history.isNotEmpty() || serverStats!!.daily.isNotEmpty())
            ) {
                TrafficTrendCard(
                    hourly = serverStats!!.history,
                    daily = serverStats!!.daily,
                    showConnections = showConn,
                )
            }

            // Per-app activity
            if (showApps && serverStats != null && serverStats!!.apps.isNotEmpty()) {
                ActiveAppsCard(serverStats!!.apps)
            }

            // Recent requests: what was asked for and what the filter did with it
            if (showQlog && serverStats != null && serverStats!!.queryLog.isNotEmpty()) {
                RecentRequestsCard(serverStats!!.queryLog)
            }

            // Top intercepted domains (ranked)
            if (showCerts && serverStats != null && serverStats!!.recentTls.isNotEmpty()) {
                TopHostsCard(serverStats!!.recentTls)
            }
            // Recently issued SNI certs
            if (showCerts && serverStats != null && serverStats!!.recentTls.isNotEmpty()) {
                RecentCertsCard(serverStats!!.recentTls)
            }

            // Web server details
            if (serverStats != null) {
                ServerDetailsCard(serverStats!!)
            }
        }
    }
}

@Composable
private fun CountLabel(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            String.format(Locale.US, "%,d", value),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * KPI 卡片行（AdGuard Home 风格）：4 个关键指标 + 迷你趋势线。
 * 请求总数 / 拦截总数 / 拦截率 / SNI 证书数。
 * 趋势线取自最近 12 个 hourly 桶，让用户一眼感知走势。
 */
@Composable
private fun KpiCardRow(stats: ServerStats) {
    // ADGuard Home 风格 Hero KPI：左侧拦截率大环形，右侧 2x2 指标
    val rate = if (stats.totalRequests > 0) stats.totalBlocked * 100.0 / stats.totalRequests else 0.0
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
            // 拦截率环形
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                val arcBg = MaterialTheme.colorScheme.surfaceVariant
                val arcFg = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = arcBg,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = arcFg,
                        startAngle = -90f,
                        sweepAngle = (rate / 100.0 * 360.0).toFloat().coerceIn(0f, 360f),
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        String.format(Locale.US, "%.1f%%", rate),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.compose_stats_kpi_rate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            // 右侧 2x2 指标
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    HeroMini(
                        stringResource(R.string.compose_stats_kpi_requests),
                        ChartUtils.compactNumber(stats.totalRequests),
                        MaterialTheme.colorScheme.primary,
                    )
                    HeroMini(
                        stringResource(R.string.compose_stats_kpi_blocked),
                        ChartUtils.compactNumber(stats.totalBlocked),
                        MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    HeroMini(
                        stringResource(R.string.compose_stats_kpi_connections),
                        ChartUtils.compactNumber(stats.totalConnections),
                        MaterialTheme.colorScheme.tertiary,
                    )
                    HeroMini(
                        stringResource(R.string.compose_stats_kpi_certs),
                        ChartUtils.compactNumber(stats.sniCertsIssued),
                        Color(0xFFE6A23C),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMini(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 单个 KPI 卡：数字 + 标签 + 迷你趋势线（sparkline）。 */
@Composable
private fun KpiCard(
    label: String,
    value: String,
    points: List<Long>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = color,
                maxLines = 1,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            // 迷你趋势线
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val n = points.size
                if (n < 2) {
                    drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
                    return@Canvas
                }
                val maxV = (points.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
                val stepX = size.width / (n - 1)
                val path = androidx.compose.ui.graphics.Path()
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - (v.toFloat() / maxV) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
                // 面积填充
                val fill = androidx.compose.ui.graphics.Path()
                fill.moveTo(0f, size.height)
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - (v.toFloat() / maxV) * size.height
                    fill.lineTo(x, y)
                }
                fill.lineTo(size.width, size.height)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.12f))
            }
        }
    }
}

/**
 * Donut chart drawn with Canvas. Center shows the total.
 */
/**
 * 将大数字格式化为紧凑形式：1_234 → "1.2k"，3_456_789 → "3.5M"。
 * 用于图表 Y 轴刻度，避免超长数字溢出绘图区域。
 */
@Suppress("DEPRECATION") // keep for backward-compat callers; use ChartUtils.compactNumber
private fun compactNumber(value: Long): String = ChartUtils.compactNumber(value)

/**
 * 环形图（Canvas 手绘，无第三方图表库）。
 *
 * 绘制逻辑：
 *  - 每个拦截类型（图片/脚本/样式/API/遥测等）占一段圆弧；
 *  - 弧长 = 该类型拦截数 / 总拦截数 * 360°，段间留 1° 空隙；
 *  - 中心文字显示拦截总数（Monospace 字体）。
 *  - 颜色取自 MaterialTheme 主题色 + 固定语义色。
 */
@Composable
private fun DonutChart(stats: ServerStats) {
    val categories = buildCategories(stats)
    val total = stats.totalBlocked
    val strokeWidth = 28.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
                val inset = strokeWidth.toPx() / 2
                val arcSize = Size(
                    size.width - strokeWidth.toPx(),
                    size.height - strokeWidth.toPx(),
                )
                var startAngle = -90f
                categories.forEach { cat ->
                    val sweep = 360f * cat.count.toFloat() / total.toFloat()
                    drawArc(
                        color = cat.color,
                        startAngle = startAngle,
                        sweepAngle = sweep - 1f, // small gap between segments
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke,
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.US, "%,d", total),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    stringResource(R.string.compose_stats_total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(category: BlockCategory, total: Long) {
    val fraction = if (total > 0) category.count.toFloat() / total.toFloat() else 0f
    // Capture colors before the Canvas draw block (not @Composable context)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // 点击分类行时展开/收起说明文字（telemetry/heartbeat 等专业术语释义）
    var expanded by remember { mutableStateOf(false) }
    val description = categoryDescription(category.label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = description != null) { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = category.color)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                category.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.US, "%,d", category.count),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.width(12.dp))
            // Mini progress bar
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(4.dp),
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    )
                    drawRoundRect(
                        color = category.color,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        size = Size(size.width * fraction, size.height),
                    )
                }
            }
        }
        // 展开的分类说明
        if (expanded && description != null) {
            androidx.compose.animation.AnimatedVisibility(visible = true) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 返回拦截分类的简要说明（点击分类行时展示）。
 * 未知分类返回 null（不可点击）。
 */
@Composable
private fun categoryDescription(label: String): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val res = when (label) {
        context.getString(R.string.compose_stats_type_telemetry) -> R.string.compose_stats_type_telemetry_desc
        context.getString(R.string.compose_stats_type_heartbeat) -> R.string.compose_stats_type_heartbeat_desc
        context.getString(R.string.compose_stats_type_scripts) -> R.string.compose_stats_type_scripts_desc
        context.getString(R.string.compose_stats_type_images) -> R.string.compose_stats_type_images_desc
        context.getString(R.string.compose_stats_type_api) -> R.string.compose_stats_type_api_desc
        context.getString(R.string.compose_stats_type_crypto) -> R.string.compose_stats_type_crypto_desc
        context.getString(R.string.compose_stats_type_clickbait) -> R.string.compose_stats_type_clickbait_desc
        else -> null
    }
    return res?.let { context.getString(it) }
}

@Composable
private fun EmptyChartPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.compose_stats_no_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerDetailsCard(stats: ServerStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_stats_details),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DetailRow(stringResource(R.string.compose_stats_uptime), formatUptime(stats.uptimeSeconds))
            DetailRow(stringResource(R.string.compose_stats_requests), stats.totalRequests)
            DetailRow(
                stringResource(R.string.compose_stats_connections),
                stringResource(R.string.compose_stats_connections_active, stats.totalConnections, stats.activeConnections),
            )
            DetailRow(stringResource(R.string.compose_stats_sni), stats.sniCertsIssued)
            DetailRow(stringResource(R.string.compose_stats_images), stats.blockImageCount)
            // 新增指标：拦截率 / TLS 会话复用率 / 运行天数 / 每日峰值
            DetailRow(
                stringResource(R.string.compose_stats_block_rate),
                String.format(Locale.US, "%.1f%%", stats.blockRate),
            )
            DetailRow(
                stringResource(R.string.compose_stats_sni_hit_rate),
                String.format(Locale.US, "%.1f%%", stats.sniHitRate),
            )
            DetailRow(
                stringResource(R.string.compose_stats_uptime_days),
                String.format(Locale.US, "%.1f d", stats.uptimeDays),
            )
            DetailRow(
                stringResource(R.string.compose_stats_daily_peak),
                ChartUtils.compactNumber(stats.dailyPeak),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: Any) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/** Map an Android uid to its (first) package label; fall back to "UID n". */
/** 拦截域名排行 Top 卡：由 recentTls (uid, host) 按域名聚合，取出现最多的域名。 */
@Composable
private fun TopHostsCard(hosts: List<TlsHost>) {
    val counts = hosts.groupingBy { it.host }.eachCount()
    val top = counts.entries.sortedByDescending { it.value }.take(10)
    if (top.isEmpty()) return
    val maxCount = top.first().value.coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_stats_top_hosts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val barBg = MaterialTheme.colorScheme.surfaceVariant
            val barFg = MaterialTheme.colorScheme.primary
            top.forEachIndexed { index, (host, count) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        String.format(Locale.US, "%d", index + 1),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            host,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        // 占比条（Canvas 绘制，避免额外 import）
                        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                            drawRoundRect(
                                color = barBg,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            )
                            val fraction = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                            drawRoundRect(
                                color = barFg,
                                size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ChartUtils.compactNumber(count.toLong()),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (top.size < counts.size) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.compose_stats_top_hosts_more, counts.size - top.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun appNameForUid(uid: Int): String {
    if (uid <= 0) return stringResource(R.string.compose_stats_app_unknown, uid)
    val context = androidx.compose.ui.platform.LocalContext.current
    val name = remember(uid) {
        try {
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid)
            if (pkgs.isNullOrEmpty()) {
                null
            } else {
                val info = pm.getApplicationInfo(pkgs[0], 0)
                pm.getApplicationLabel(info).toString()
            }
        } catch (e: Exception) {
            null
        }
    }
    return name ?: stringResource(R.string.compose_stats_app_unknown, uid)
}

@Composable
/**
 * 累计统计卡：请求/拦截/连接/证书四个自 webserver 启动以来
 * 的累计计数（持久化于 stats.dat，重启不丢失）。
 */
private fun LifetimeCard(stats: ServerStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_stats_lifetime_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.compose_stats_lifetime_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CountLabel(stringResource(R.string.compose_stats_lifetime_requests), stats.totalRequests)
                CountLabel(stringResource(R.string.compose_stats_lifetime_blocked), stats.totalBlocked)
                CountLabel(stringResource(R.string.compose_stats_lifetime_connections), stats.totalConnections)
                CountLabel(stringResource(R.string.compose_stats_lifetime_certs), stats.sniCertsIssued)
            }
        }
    }
}

@Composable
private fun CountLabel(label: String, value: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            String.format(Locale.US, "%,d", value),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
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
 * 趋势图（AdGuard Home 风格）：支持 4 个时间范围与 3 种样式。
 *
 * 时间范围（mode）：
 *  0 = 24h（hourly 桶）  1 = 7d（daily 取最近 7 天）
 *  2 = 30d（daily 全部） 3 = 永久（所有历史数据）
 *
 * 样式（style，取自偏好 chart_style）：
 *  0 = 折线（平滑 Catmull-Rom 曲线 + 渐变面积）
 *  1 = 堆叠柱状图  2 = 面积图
 *
 * 绘制细节：网格线 + Y 轴刻度 + 图例 + 三条数据系列
 * （请求=primary、拦截=error、连接=tertiary，连接线可开关）。
 */
private fun TrafficTrendCard(
    hourly: List<HistPoint>,
    daily: List<HistPoint>,
    showConnections: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 范围与样式均从偏好恢复（用户上次选择不丢失）
    var mode by remember { mutableIntStateOf(chartRange(context)) }  // 0=24h 1=7d 2=30d 3=all
    var style by remember { mutableIntStateOf(chartStyle(context)) } // 0=line 1=area 2=bars
    fun setMode(m: Int) { mode = m; setChartRange(context, m) }
    fun setStyle(s: Int) { style = s; setChartStyle(context, s) }
    val data = when (mode) {
        0 -> hourly
        1 -> daily.takeLast(7)
        2 -> daily
        else -> hourly + daily // 永久：全部历史（小时 + 日）
    }
    if (data.isEmpty()) return

    val maxReq = (data.maxOfOrNull { it.requests } ?: 0L).coerceAtLeast(1L)
    val maxBlocked = (data.maxOfOrNull { it.blocked } ?: 0L).coerceAtLeast(1L)
    val maxConn = (data.maxOfOrNull { it.connections } ?: 0L).coerceAtLeast(1L)
    val maxAll = maxOf(maxReq, maxBlocked, if (showConnections) maxConn else 0L).coerceAtLeast(1L)
    val reqColor = MaterialTheme.colorScheme.primary
    val blockColor = MaterialTheme.colorScheme.error
    val connColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

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
                Text(
                    stringResource(R.string.compose_stats_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(selected = mode == 0, onClick = { setMode(0) },
                    label = { Text(stringResource(R.string.compose_stats_range_24h)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == 1, onClick = { setMode(1) },
                    label = { Text(stringResource(R.string.compose_stats_range_7d)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == 2, onClick = { setMode(2) },
                    label = { Text(stringResource(R.string.compose_stats_range_30d)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == 3, onClick = { setMode(3) },
                    label = { Text(stringResource(R.string.compose_stats_range_all)) })
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = style == 0, onClick = { setStyle(0) },
                    label = { Text(stringResource(R.string.compose_stats_style_line)) })
                FilterChip(selected = style == 1, onClick = { setStyle(1) },
                    label = { Text(stringResource(R.string.compose_stats_style_area)) })
                FilterChip(selected = style == 2, onClick = { setStyle(2) },
                    label = { Text(stringResource(R.string.compose_stats_style_bars)) })
            }
            Spacer(Modifier.height(12.dp))

            // 触摸交互：点击图表显示最近数据点的具体数值（tooltip）
            var selectedIndex by remember { mutableIntStateOf(-1) }
            val tipReq = if (selectedIndex in data.indices) data[selectedIndex].requests else 0L
            val tipBlk = if (selectedIndex in data.indices) data[selectedIndex].blocked else 0L
            val tipConn = if (selectedIndex in data.indices) data[selectedIndex].connections else 0L
            val tipTs = if (selectedIndex in data.indices) data[selectedIndex].ts else 0L

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)  // +20dp 为 X 轴时间标签预留空间
                    .pointerInput(data.size) {
                        detectTapGestures { offset ->
                            // 把点击 x 坐标映射到最近的数据点索引
                            val n = data.size
                            if (n == 0) return@detectTapGestures
                            val leftPad = 34.dp.toPx()
                            val chartW = size.width - leftPad - 6.dp.toPx()
                            val idx = if (n == 1) 0 else {
                                val raw = ((offset.x - leftPad) / chartW * (n - 1)).toInt()
                                raw.coerceIn(0, n - 1)
                            }
                            selectedIndex = if (selectedIndex == idx) -1 else idx
                        }
                    },
            ) {
                val n = data.size
                if (n == 0) return@Canvas
                val leftPad = 34.dp.toPx()
                val chartW = size.width - leftPad - 6.dp.toPx()
                val base = size.height - 22.dp.toPx()  // 底部预留 X 轴标签
                val topPad = 8.dp.toPx()
                val chartH = base - topPad - 4.dp.toPx()

                fun xAt(i: Int): Float = leftPad + (if (n == 1) chartW / 2 else chartW * i / (n - 1))
                fun yReq(v: Long): Float = base - (v.toFloat() / maxAll) * chartH

                // 选中点高亮（垂直参考线 + 圆点）
                if (selectedIndex in data.indices) {
                    val sx = xAt(selectedIndex)
                    drawLine(
                        axisColor.copy(alpha = 0.4f),
                        Offset(sx, topPad), Offset(sx, base),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawCircle(reqColor, radius = 4.dp.toPx(), center = Offset(sx, yReq(data[selectedIndex].requests)))
                    drawCircle(blockColor, radius = 4.dp.toPx(), center = Offset(sx, yReq(data[selectedIndex].blocked)))
                }

                // Y-axis grid (dashed, so lines don't visually cut through bars) + labels
                // with a translucent background pill so text never overlaps data.
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 128, 128, 128)
                    textSize = 9.dp.toPx()
                }
                val pillPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 250, 250, 250)
                }
                for (g in 0..4) {
                    val frac = g / 4f
                    val y = base - chartH * frac
                    // dashed grid line: draw small segments with gaps
                    val dashLen = 4.dp.toPx()
                    val gapLen = 3.dp.toPx()
                    var xDash = leftPad
                    while (xDash < size.width) {
                        drawLine(
                            gridColor.copy(alpha = 0.5f),
                            Offset(xDash, y), Offset(xDash + dashLen, y),
                            strokeWidth = 1f,
                        )
                        xDash += dashLen + gapLen
                    }
                    val raw = when (g) {
                        0 -> 0L
                        1 -> maxAll / 4
                        2 -> maxAll / 2
                        3 -> maxAll * 3 / 4
                        else -> maxAll
                    }
                    val label = compactNumber(raw)
                    // background pill behind the label (avoid "text through line")
                    val tw = labelPaint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        1.dp.toPx(), y - 7.dp.toPx(),
                        1.dp.toPx() + tw + 8.dp.toPx(), y + 7.dp.toPx(),
                        3.dp.toPx(), 3.dp.toPx(), pillPaint,
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        label, 5.dp.toPx(), y + 4.dp.toPx(), labelPaint,
                    )
                }
                // Baseline
                drawLine(axisColor, Offset(leftPad, base), Offset(size.width, base), strokeWidth = 1.5f)

                // X 轴时间标签（参考 AdGuard Home：24h 用 HH:mm，7d+ 用 MM/dd）
                val timePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(170, 128, 128, 128)
                    textSize = 9.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val labelStep = when {
                    n <= 6 -> 1
                    n <= 12 -> 2
                    n <= 24 -> 4
                    else -> n / 6
                }
                for (i in 0 until n step labelStep) {
                    val ts = data[i].ts * 1000L
                    val text = if (mode == 0) {
                        // 24h: HH:mm
                        java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(ts))
                    } else {
                        // 7d/30d/all: MM/dd
                        java.text.SimpleDateFormat("MM/dd", Locale.US).format(java.util.Date(ts))
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        text, xAt(i), base + 14.dp.toPx(), timePaint,
                    )
                }
                // 最后一个点也标注（避免标签缺失）
                if (n > 1 && (n - 1) % labelStep != 0) {
                    val ts = data[n - 1].ts * 1000L
                    val text = if (mode == 0) {
                        java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(ts))
                    } else {
                        java.text.SimpleDateFormat("MM/dd", Locale.US).format(java.util.Date(ts))
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        text, xAt(n - 1), base + 14.dp.toPx(), timePaint,
                    )
                }

                when (style) {
                    2 -> { // bars — grouped bars (requests + blocked side by side)
                        val slotW = chartW / n
                        val barW = (slotW * 0.45f).coerceAtMost(14f)
                        val gap = 2.dp.toPx()
                        data.forEachIndexed { i, p ->
                            val cx = xAt(i)
                            val hReq = (p.requests.toFloat() / maxAll) * chartH
                            val hBlk = (p.blocked.toFloat() / maxAll) * chartH
                            // Requests bar (left)
                            if (hReq > 0f) drawRoundRect(
                                color = reqColor,
                                topLeft = Offset(cx - barW - gap / 2, base - hReq),
                                size = Size(barW, hReq),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                            // Blocked bar (right)
                            if (hBlk > 0f) drawRoundRect(
                                color = blockColor,
                                topLeft = Offset(cx + gap / 2, base - hBlk),
                                size = Size(barW, hBlk),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                            // highlight the selected bar group
                            if (i == selectedIndex) {
                                drawRoundRect(
                                    color = axisColor.copy(alpha = 0.35f),
                                    topLeft = Offset(cx - barW - gap / 2 - 2.dp.toPx(), base - kotlin.math.max(hReq, hBlk) - 2.dp.toPx()),
                                    size = Size(barW * 2 + gap + 4.dp.toPx(), kotlin.math.max(hReq, hBlk) + 4.dp.toPx()),
                                    cornerRadius = CornerRadius(4.dp.toPx()),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                        }
                    }
                    else -> { // line / area — smooth curves
                        val reqPts = data.mapIndexed { i, p -> Offset(xAt(i), yReq(p.requests)) }
                        val blkPts = data.mapIndexed { i, p -> Offset(xAt(i), yReq(p.blocked)) }
                        val connPts = if (showConnections)
                            data.mapIndexed { i, p -> Offset(xAt(i), yReq(p.connections)) } else emptyList()

                        if (style == 1) {
                            val fill = Path()
                            fill.moveTo(reqPts.first().x, base)
                            reqPts.forEach { fill.lineTo(it.x, it.y) }
                            fill.lineTo(reqPts.last().x, base)
                            fill.close()
                            drawPath(fill, reqColor.copy(alpha = 0.25f))
                            val fillB = Path()
                            fillB.moveTo(blkPts.first().x, base)
                            blkPts.forEach { fillB.lineTo(it.x, it.y) }
                            fillB.lineTo(blkPts.last().x, base)
                            fillB.close()
                            drawPath(fillB, blockColor.copy(alpha = 0.20f))
                        } else {
                            val fill = Path()
                            fill.moveTo(reqPts.first().x, base)
                            reqPts.forEach { fill.lineTo(it.x, it.y) }
                            fill.lineTo(reqPts.last().x, base)
                            fill.close()
                            drawPath(
                                fill,
                                Brush.verticalGradient(
                                    listOf(reqColor.copy(alpha = 0.18f), reqColor.copy(alpha = 0.0f)),
                                    startY = topPad, endY = base,
                                ),
                            )
                        }

                        drawPath(smoothPath(reqPts), reqColor, style = Stroke(width = 2.dp.toPx()))
                        drawPath(smoothPath(blkPts), blockColor, style = Stroke(width = 2.dp.toPx()))
                        if (connPts.isNotEmpty()) {
                            drawPath(smoothPath(connPts), connColor, style = Stroke(width = 2.dp.toPx()))
                        }
                        drawCircle(reqColor, 3.5.dp.toPx(), reqPts.last())
                        drawCircle(blockColor, 3.5.dp.toPx(), blkPts.last())
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(reqColor, stringResource(R.string.compose_stats_history_requests))
                LegendDot(blockColor, stringResource(R.string.compose_stats_history_blocked))
                if (showConnections) {
                    LegendDot(connColor, stringResource(R.string.compose_stats_history_connections))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        when (mode) {
                            0 -> R.string.compose_stats_history_window
                            1 -> R.string.compose_stats_7d_window
                            2 -> R.string.compose_stats_daily_window
                            else -> R.string.compose_stats_range_all
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 选中数据点 tooltip（点击图表触发）──
            if (selectedIndex in data.indices) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.animation.AnimatedVisibility(visible = true) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                java.text.SimpleDateFormat(
                                    if (mode <= 0) "MM-dd HH:mm" else "yyyy-MM-dd",
                                    Locale.US,
                                ).format(java.util.Date(tipTs * 1000L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendDot(reqColor, stringResource(R.string.compose_stats_history_requests) + ": " + compactNumber(tipReq))
                                LegendDot(blockColor, stringResource(R.string.compose_stats_history_blocked) + ": " + compactNumber(tipBlk))
                                if (showConnections) {
                                    LegendDot(connColor, stringResource(R.string.compose_stats_history_connections) + ": " + compactNumber(tipConn))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Catmull-Rom -> cubic Bézier smoothing through the given points. */
private fun smoothPath(points: List<Offset>): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path
    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

@Composable
/**
 * 拦截率环：环形进度条展示 拦截数/请求数 的百分比。
 * 中心大字显示百分比，下方显示 拦截/请求 计数。
 */
private fun BlockRateCard(stats: ServerStats) {
    val total = stats.totalRequests.coerceAtLeast(1L)
    val rate = stats.totalBlocked.toFloat() / total
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
            // Rate ring
            val ringColor = MaterialTheme.colorScheme.error
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            Canvas(modifier = Modifier.size(72.dp)) {
                val stroke = Stroke(width = 8.dp.toPx())
                drawArc(trackColor, 0f, 360f, false, style = stroke)
                drawArc(ringColor, -90f, 360f * rate, false, style = stroke)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.compose_stats_rate_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.compose_stats_rate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "%.1f%%", rate * 100f),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.compose_stats_rate_blocked_of, stats.totalBlocked, stats.totalRequests),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 查询日志的动作名（0 = 代理到真实服务器，1 = 拦截，2 = 放行）。 */
@Composable
private fun queryActionLabel(action: Int): String = when (action) {
    1 -> stringResource(R.string.compose_stats_recent_action_blocked)
    2 -> stringResource(R.string.compose_stats_recent_action_allowed)
    else -> stringResource(R.string.compose_stats_recent_action_proxied)
}

private fun queryTimeText(ts: Long): String =
    if (ts <= 0L) "--:--:--"
    else java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(ts * 1000L))

/**
 * 最近请求卡（服务器查询日志环形缓冲）：按时间倒序显示"谁请求了什么、
 * 被怎么处理"，用来回答"为什么这个请求被拦截"。数据来自
 * /internal-stats 的 query_log[]，字段为 ts / uid / action / host。
 */
@Composable
private fun RecentRequestsCard(entries: List<QueryLogEntry>) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) entries else entries.take(5)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    stringResource(R.string.compose_stats_recent_requests),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.compose_stats_recent_requests_count, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) androidx.compose.material.icons.Icons.Outlined.ExpandLess
                    else androidx.compose.material.icons.Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.compose_stats_recent_requests_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            shown.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        queryTimeText(entry.ts),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        queryActionLabel(entry.action),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.action) {
                            1 -> MaterialTheme.colorScheme.error
                            2 -> Color(0xFF16A34A)
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.host.ifEmpty { "-" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!expanded && entries.size > shown.size) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.compose_stats_recent_requests_more, entries.size - shown.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
/**
 * 活跃应用卡：按 拦截+请求 总数降序展示各 uid 的
 * 连接数/请求数/拦截数。uid → 应用名通过 PackageManager 解析。
 */
private fun ActiveAppsCard(apps: List<AppStat>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val monitoredApps = apps.filter { isAppMonitored(context, it.uid) }
    if (monitoredApps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    stringResource(R.string.compose_stats_active_apps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.compose_stats_active_apps_count, monitoredApps.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) androidx.compose.material.icons.Icons.Outlined.ExpandLess
                    else androidx.compose.material.icons.Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    monitoredApps.sortedByDescending { it.blocked + it.requests }.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                appNameForUid(app.uid),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(
                                    R.string.compose_stats_app_sub,
                                    app.connections,
                                    app.requests,
                                    app.blocked,
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
/**
 * 最近签发证书卡：展示 webserver 为各广告域名（SNI）
 * 动态签发的叶子证书列表（域名 + 时间）。
 */
private fun RecentCertsCard(hosts: List<TlsHost>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    stringResource(R.string.compose_stats_recent_certs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.compose_stats_certs_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) androidx.compose.material.icons.Icons.Outlined.ExpandLess
                    else androidx.compose.material.icons.Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    hosts.take(10).forEach { host ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        host.host,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        appNameForUid(host.uid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
            }
        }
    }
}
    }
