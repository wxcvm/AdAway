package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI-only regression checks; no Root, network or certificate actions. */
class SettingsCategoryNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearingSearchRestoresCategoriesAndCardsRemainClickable() {
        var selected = -1
        compose.setContent {
            var search by remember { mutableStateOf("") }
            AdAwayTheme(dynamicColor = false) {
                SettingsCategoryNavigation(
                    titles = listOf("Network", "Server", "Certificates", "Response", "Statistics", "App", "Tools"),
                    descriptions = List(7) { "Description $it" },
                    keywords = List(7) { "keyword-$it" },
                    search = search,
                    onSearchChange = { search = it },
                    onSelect = { selected = it },
                )
            }
        }

        compose.onNodeWithTag("settings-category-0").assertExists()
        compose.onNodeWithTag("settings-search-field").performTextInput("no-matching-section")
        compose.onNodeWithTag("settings-category-0").assertDoesNotExist()
        compose.onNodeWithTag("settings-clear-search").performClick()
        compose.onNodeWithTag("settings-category-0").assertExists()
        compose.onNodeWithTag("settings-category-0").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }
}
