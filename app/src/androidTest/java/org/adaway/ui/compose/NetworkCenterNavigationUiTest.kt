package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import org.adaway.R
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Network center is navigation-only until its planned integrations exist. */
class NetworkCenterNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolbarBackInvokesOnlyItsNavigationCallback() {
        var backCount = 0
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                NetworkCenterScreen(
                    onOpenSettings = {},
                    onOpenRules = {},
                    onOpenStats = {},
                    onBack = { backCount++ },
                )
            }
        }

        compose.onNodeWithTag("network-center-back").performClick()
        compose.runOnIdle {
            assertEquals(1, backCount)
        }
    }
    @Test
    fun workingActionsPrecedeOptionalRoadmapAndRemainNavigationOnly() {
        var settings = 0
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                NetworkCenterScreen({ settings++ }, {}, {})
            }
        }
        val list = compose.onNodeWithTag("integration-center-list")
        val planned = "integration-planned-${R.string.compose_network_capture_title}"
        compose.onNodeWithTag(planned).assertDoesNotExist()
        list.performScrollToNode(hasTestTag("integration-open-settings"))
        compose.onNodeWithTag("integration-open-settings").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
        list.performScrollToNode(hasTestTag("integration-roadmap-toggle"))
        compose.onNodeWithTag("integration-roadmap-toggle").performClick()
        list.performScrollToNode(hasTestTag(planned))
        compose.onNodeWithTag(planned).assertExists()
        list.performScrollToNode(hasTestTag("integration-roadmap-toggle"))
        compose.onNodeWithTag("integration-roadmap-toggle").performClick()
        compose.onNodeWithTag(planned).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settings) }
    }

}
