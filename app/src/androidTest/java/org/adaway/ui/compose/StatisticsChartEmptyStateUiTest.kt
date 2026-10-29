package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Rule
import org.junit.Test

/**
 * Statistics rendering regression tests using read-only, local snapshots.
 * No Root, certificate, network or preference mutation.
 */
class StatisticsChartEmptyStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyHistoryShowsExplanationForEveryStoredRange() {
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                TrafficTrendCard(
                    hourly = emptyList(),
                    daily = emptyList(),
                    showConnections = true,
                )
            }
        }

        compose.onNodeWithTag("traffic-history-empty").assertIsDisplayed()
    }
}
