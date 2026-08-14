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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * Donut chart drawn with Canvas. Center shows the total.
 */
@Composable
/**
 * 环形图（Canvas 手绘，无第三方图表库）。
 *
 * 绘制逻辑：
 *  - 每个拦截类型（图片/脚本/样式/API/遥测等）占一段圆弧；
 *  - 弧长 = 该类型拦截数 / 总拦截数 * 360°，段间留 1° 空隙；
 *  - 中心文字显示拦截总数（Monospace 字体）。
 *  - 颜色取自 MaterialTheme 主题色 + 固定语义色。
 */
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
    var mode by remember { mutableIntStateOf(0) }  // 0 = 24h, 1 = 7d, 2 = 30d
    var style by remember { mutableIntStateOf(chartStyle(context)) } // 0 = line, 1 = area, 2 = bars
    val data = when (mode) {
        0 -> hourly
        1 -> daily.takeLast(7)
        else -> daily
    }
    if (data.isEmpty()) return

    val maxReq = (data.maxOfOrNull { it.requests } ?: 0L).coerceAtLeast(1L)
    val maxBlocked = (data.maxOfOrNull { it.blocked } ?: 0L).coerceAtLeast(1L)
    val maxConn = (data.maxOfOrNull { it.connections } ?: 0L).coerceAtLeast(1L)
    val maxAll = (maxReq + maxBlocked).coerceAtLeast(1L)
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
                FilterChip(selected = mode == 0, onClick = { mode = 0 },
                    label = { Text(stringResource(R.string.compose_stats_range_24h)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == 1, onClick = { mode = 1 },
                    label = { Text(stringResource(R.string.compose_stats_range_7d)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == 2, onClick = { mode = 2 },
                    label = { Text(stringResource(R.string.compose_stats_range_30d)) })
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = style == 0, onClick = { style = 0 },
                    label = { Text(stringResource(R.string.compose_stats_style_line)) })
                FilterChip(selected = style == 1, onClick = { style = 1 },
                    label = { Text(stringResource(R.string.compose_stats_style_area)) })
                FilterChip(selected = style == 2, onClick = { style = 2 },
                    label = { Text(stringResource(R.string.compose_stats_style_bars)) })
            }
            Spacer(Modifier.height(12.dp))

            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                val n = data.size
                if (n == 0) return@Canvas
                val leftPad = 34.dp.toPx()
                val chartW = size.width - leftPad - 6.dp.toPx()
                val base = size.height - 8.dp.toPx()
                val topPad = 8.dp.toPx()
                val chartH = base - topPad - 4.dp.toPx()

                fun xAt(i: Int): Float = leftPad + (if (n == 1) chartW / 2 else chartW * i / (n - 1))
                fun yReq(v: Long): Float = base - (v.toFloat() / maxAll) * chartH

                // Y-axis grid + labels
                for (g in 0..4) {
                    val frac = g / 4f
                    val y = base - chartH * frac
                    drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
                    val label = when (g) {
                        0 -> "${maxAll}"
                        1 -> "${maxAll * 3 / 4}"
                        2 -> "${maxAll / 2}"
                        3 -> "${maxAll / 4}"
                        else -> "0"
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label, 2.dp.toPx(), y + 4.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(150, 128, 128, 128)
                            textSize = 9.dp.toPx()
                        },
                    )
                }
                // Baseline
                drawLine(axisColor, Offset(leftPad, base), Offset(size.width, base), strokeWidth = 1.5f)

                when (style) {
                    2 -> { // bars
                        val slotW = chartW / n
                        val barW = (slotW * 0.6f).coerceAtMost(18f)
                        data.forEachIndexed { i, p ->
                            val cx = xAt(i)
                            val hReq = (p.requests.toFloat() / maxAll) * chartH
                            val hBlk = (p.blocked.toFloat() / maxAll) * chartH
                            if (hReq > 0f) drawRoundRect(
                                color = reqColor,
                                topLeft = Offset(cx - barW / 2, base - hReq),
                                size = Size(barW, hReq),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                            if (hBlk > 0f) drawRoundRect(
                                color = blockColor,
                                topLeft = Offset(cx - barW / 2, base - hReq - hBlk),
                                size = Size(barW, hBlk),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
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
                            else -> R.string.compose_stats_daily_window
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
/**
 * 活跃应用卡：按 拦截+请求 总数降序展示各 uid 的
 * 连接数/请求数/拦截数。uid → 应用名通过 PackageManager 解析。
 */
private fun ActiveAppsCard(apps: List<AppStat>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val monitoredApps = apps.filter { isAppMonitored(context, it.uid) }
    if (monitoredApps.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_stats_active_apps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
/**
 * 最近签发证书卡：展示 webserver 为各广告域名（SNI）
 * 动态签发的叶子证书列表（域名 + 时间）。
 */
private fun RecentCertsCard(hosts: List<TlsHost>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_stats_recent_certs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.compose_stats_certs_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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