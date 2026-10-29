package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.ListType
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Tests only the rule-type navigation UI, using no network or rule mutations.
 */
class RulesTypeTabsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowScreenLargeTextKeepsAllThreeRuleTypesReachable() {
        var selected = -1
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                AdAwayTheme(dynamicColor = false) {
                    Surface(Modifier.width(320.dp)) {
                        RulesTypeTabs(
                            tabs = listOf(
                                RuleTab(R.string.compose_rules_whitelist, ListType.ALLOWED.value, R.string.compose_rules_empty_whitelist),
                                RuleTab(R.string.compose_rules_blacklist, ListType.BLOCKED.value, R.string.compose_rules_empty_blacklist),
                                RuleTab(R.string.compose_rules_redirect, ListType.REDIRECTED.value, R.string.compose_rules_empty_redirect),
                            ),
                            selectedTab = 0,
                            onSelect = { selected = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("rules-type-tab-2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, selected) }
    }
}
