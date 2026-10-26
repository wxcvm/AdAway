package org.adaway.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.adaway.R
import java.util.Locale

/**
 * Settings home -> category detail. Unlike seven horizontal tabs, every
 * destination is visible and searchable from one stable landing page.
 *
 * This is strictly navigation; it does not read or write any preference,
 * change a listener, or alter the active VPN/Root interception mode.
 */
@Composable
internal fun SettingsCategoryNavigation(
    titles: List<String>,
    descriptions: List<String>,
    keywords: List<String>,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (Int) -> Unit,
) {
    require(titles.size == 7 && descriptions.size == 7 && keywords.size == 7)
    val query = search.trim().lowercase(Locale.ROOT)
    val filtered = titles.indices.filter { index ->
        query.isEmpty() || listOf(titles[index], descriptions[index], keywords[index])
            .any { it.lowercase(Locale.ROOT).contains(query) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-categories"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.compose_settings_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
        }
        if (query.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.compose_settings_frequent_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // One scrollable quick-access row instead of two fixed rows:
                    // this frees ~50dp on phones and prevents large-font chips
                    // from pushing the category list below the fold.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { onSelect(0) },
                            label = { Text(stringResource(R.string.compose_settings_frequent_blocking)) },
                        )
                        AssistChip(
                            onClick = { onSelect(2) },
                            label = { Text(stringResource(R.string.compose_settings_frequent_certificate)) },
                        )
                        AssistChip(
                            onClick = { onSelect(1) },
                            label = { Text(stringResource(R.string.compose_settings_frequent_ports)) },
                        )
                        AssistChip(
                            onClick = { onSelect(6) },
                            label = { Text(stringResource(R.string.compose_settings_frequent_backup)) },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.compose_settings_all_categories),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
        } else {
            item {
                Text(
                    stringResource(R.string.compose_settings_search_results),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.compose_settings_search_empty),
                    modifier = Modifier.padding(vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(filtered, key = { _, index -> index }) { _, index ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("settings-category-$index").clickable { onSelect(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    ListItem(
                        headlineContent = {
                            Text(titles[index], fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(descriptions[index], style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }
        }
    }
}

/** The category title is in the app bar; detail content gets a short hint. */
@Composable
internal fun SettingsCategoryIntroduction(
    index: Int,
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
