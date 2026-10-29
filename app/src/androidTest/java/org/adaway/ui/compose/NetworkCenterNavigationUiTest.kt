package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
}
