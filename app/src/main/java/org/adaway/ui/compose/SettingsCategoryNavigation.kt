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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
 * A search word may occur in the title, description or aliases. Treat
 * whitespace-separated terms as AND so "AdGuard DNS" finds the same
 * section without depending on either literal word order or translation.
 */
internal fun settingsCategoryMatches(
    title: String, description: String, keywords: String, search: String,
): Boolean {
    val tokens = search.trim().lowercase(Locale.ROOT)
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    val text = "$title $description $keywords".lowercase(Locale.ROOT)
    return tokens.all(text::contains)
}

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
    val query = search.trim()
    val filtered = titles.indices.filter { index ->
        settingsCategoryMatches(titles[index], descriptions[index], keywords[index], query)
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
                modifier = Modifier.fillMaxWidth().testTag("settings-search-field"),
                singleLine = true,
                label = { Text(stringResource(R.string.compose_settings_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.testTag("settings-clear-search"),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.button_close))
                        }
                    }
                },
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
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth().testTag("settings-category-$index"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    ListItem(
                        leadingContent = {
                            // Stable section numbers are easier to scan than a wall of
                            // same-looking cards, and require no new icon dependency.
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = (index + 1).toString().padStart(2, '0'),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        },
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
