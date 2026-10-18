package org.adaway.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.RuleListRow

internal const val RULES_PAGE_SIZE = 300
data class RuleBrowserPage(val rows: List<RuleListRow>, val total: Int, val index: Int) {
    val pageCount: Int get() = if (total == 0) 1 else (total - 1) / RULES_PAGE_SIZE + 1
}

@Composable
internal fun RuleBrowserFilters(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.compose_rules_filter_all, R.string.compose_rules_filter_enabled,
        R.string.compose_rules_filter_disabled, R.string.compose_rules_filter_personal)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { onSelect(index) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
internal fun RuleBrowserPager(index: Int, pageCount: Int, loading: Boolean, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = !loading && index > 0, onClick = { onPage(index - 1) }) {
            Text(stringResource(R.string.compose_rules_previous_page))
        }
        Text(stringResource(R.string.compose_rules_page_number, index + 1, pageCount),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = !loading && index + 1 < pageCount, onClick = { onPage(index + 1) }) {
            Text(stringResource(R.string.compose_rules_next_page))
        }
    }
}
