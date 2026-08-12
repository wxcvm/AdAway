package org.adaway.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(viewModel: StatsViewModel) {
    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedHostCount.observeAsStateCompat(0)
    val allowedCount by viewModel.allowedHostCount.observeAsStateCompat(0)
    val redirectCount by viewModel.redirectHostCount.observeAsStateCompat(0)

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.compose_stats_title)) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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

            // Web server blocked-request chart
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