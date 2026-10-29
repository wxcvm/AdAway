package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.adaway.R

/**
 * Navigation-only actions. No Root, listener or rule state is changed here.
 *
 * The compact layout switches to two columns for accessibility font scaling
 * instead of truncating labels inside three narrow phone-sized buttons.
 */
@Composable
internal fun OverviewQuickActions(
    onOpenStats: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag("overview-quick-actions")) {
        val compact = maxWidth / LocalDensity.current.fontScale < 360.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverviewShortcut(
                        label = stringResource(R.string.compose_nav_stats),
                        icon = Icons.Outlined.DonutLarge,
                        tag = "overview-shortcut-stats",
                        onClick = onOpenStats,
                        modifier = Modifier.weight(1f),
                    )
                    OverviewShortcut(
                        label = stringResource(R.string.compose_nav_rules),
                        icon = Icons.Outlined.Shield,
                        tag = "overview-shortcut-rules",
                        onClick = onOpenRules,
                        modifier = Modifier.weight(1f),
                    )
                }
                OverviewShortcut(
                    label = stringResource(R.string.compose_more_logs),
                    icon = Icons.Outlined.Menu,
                    tag = "overview-shortcut-logs",
                    onClick = onOpenLogs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewShortcut(
                    label = stringResource(R.string.compose_nav_stats),
                    icon = Icons.Outlined.DonutLarge,
                    tag = "overview-shortcut-stats",
                    onClick = onOpenStats,
                    modifier = Modifier.weight(1f),
                )
                OverviewShortcut(
                    label = stringResource(R.string.compose_nav_rules),
                    icon = Icons.Outlined.Shield,
                    tag = "overview-shortcut-rules",
                    onClick = onOpenRules,
                    modifier = Modifier.weight(1f),
                )
                OverviewShortcut(
                    label = stringResource(R.string.compose_more_logs),
                    icon = Icons.Outlined.Menu,
                    tag = "overview-shortcut-logs",
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OverviewShortcut(
    label: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 76.dp)
            .testTag(tag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
