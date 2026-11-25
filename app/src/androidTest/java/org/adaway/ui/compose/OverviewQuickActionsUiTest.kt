package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.runtime.mutableStateOf
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Shortcut tests do not start or stop any interception service. */
class OverviewQuickActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachShortcutInvokesOnlyItsNavigationCallback() {
        var stats = 0
        var rules = 0
        var logs = 0
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                OverviewQuickActions(
                    onOpenStats = { stats++ },
                    onOpenRules = { rules++ },
                    onOpenLogs = { logs++ },
                )
            }
        }
        compose.onNodeWithTag("overview-shortcut-stats").performClick()
        compose.onNodeWithTag("overview-shortcut-rules").performClick()
        compose.onNodeWithTag("overview-shortcut-logs").performClick()
        compose.runOnIdle {
            assertEquals(1, stats)
            assertEquals(1, rules)
            assertEquals(1, logs)
        }
    }
    @Test
    fun localOverviewDistinguishesUnknownValidAndInconsistentCounters() {
        val stats = mutableStateOf<ServerStats?>(null)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { OverviewWebSummary(stats.value) {} }
        }
        compose.onNodeWithTag("overview-local-rate").assertTextEquals("—")
        compose.onNodeWithTag("overview-local-share").assertDoesNotExist()
        compose.runOnIdle { stats.value = ServerStats(totalRequests = 8, reportedTotalBlocked = 2) }
        compose.onNodeWithTag("overview-blocked-count").assertTextEquals("2")
        compose.onNodeWithTag("overview-local-share").assertExists()
        compose.runOnIdle { stats.value = ServerStats(totalRequests = 1, reportedTotalBlocked = 2) }
        compose.onNodeWithTag("overview-local-rate").assertTextEquals("—")
        compose.onNodeWithTag("overview-local-share").assertDoesNotExist()
    }

}
