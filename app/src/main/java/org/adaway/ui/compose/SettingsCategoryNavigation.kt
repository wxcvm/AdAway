package org.adaway.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import java.util.Locale

/**
 * A settings landing page instead of seven horizontally swiped tabs.
 *
 * Every match is explicitly a category destination. Querying a setting title
 * narrows the categories and shows the matching name, but never pretends to
 * jump to an exact switch when its position has not been resolved.
 */
@Composable
internal fun SettingsCategoryHome(
    titles: List<String>,
    descriptions: List<String>,
    searchHints: List<List<String>>,
    onSelect: (Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim().lowercase(Locale.ROOT)
    val matches = titles.indices.filter { index ->
        term.isEmpty() || titles[index].lowercase(Locale.ROOT).contains(term)
            || descriptions[index].lowercase(Locale.ROOT).contains(term)
            || searchHints[index].any { it.lowercase(Locale.ROOT).contains(term) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.compose_settings_home_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )

        if (term.isEmpty()) {
            Text(
                stringResource(R.string.compose_settings_home_frequent),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    0 to stringResource(R.string.compose_settings_block_mode_title),
                    0 to "AdGuard",
                    1 to "HTTP / HTTPS",
                    2 to stringResource(R.string.compose_settings_cert_title),
                    5 to stringResource(R.string.compose_settings_theme_title),
                ).forEach { (section, label) ->
                    AssistChip(
                        onClick = { onSelect(section) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                stringResource(R.string.compose_settings_home_categories),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(
                stringResource(R.string.compose_settings_home_matches, matches.size),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (matches.isEmpty()) {
            Text(
                stringResource(R.string.compose_settings_home_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                matches.forEachIndexed { position, index ->
                    val keywordMatches = if (term.isEmpty()) emptyList() else
                        searchHints[index].filter { it.lowercase(Locale.ROOT).contains(term) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(titles[index], style = MaterialTheme.typography.titleMedium)
                            Text(
                                keywordMatches.take(2).takeIf { it.isNotEmpty() }
                                    ?.joinToString(" · ") ?: descriptions[index],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                    if (position < matches.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** Simple and scannable detail heading: no nested tabs or pager state. */
@Composable
internal fun SettingsCategoryIntroduction(
    index: Int,
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "$index / 7 · $title",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
