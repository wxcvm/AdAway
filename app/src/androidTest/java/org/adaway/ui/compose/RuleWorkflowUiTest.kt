package org.adaway.ui.compose

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual production components with fixed data; never launches root or network operations. */
class RuleWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun runningSyncCannotBeStartedTwiceAndFailureCanBeRetried() {
        var calls = 0
        val status = androidx.compose.runtime.mutableStateOf(HostsSyncStatus.RUNNING)
        compose.setContent { AdAwayTheme(dynamicColor = false) { RuleSyncCard(status.value) { calls++ } } }
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { status.value = HostsSyncStatus.APPLY_FAILED }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_sync_apply_failed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_sync_retry)).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun subscriptionRuleOffersManagementAndShowsScope() {
        val item = HostListItem().apply {
            id = 42; host = "example.com"; type = ListType.BLOCKED; sourceId = 2; matchScope = 1
        }
        var managed = false
        compose.setContent { AdAwayTheme(dynamicColor = false) { RuleRow(item, "Sample source") { managed = true } } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_scope_suffix)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.compose_rules_delete)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.compose_rules_manage_source)).performClick()
        compose.runOnIdle { assertEquals(true, managed) }
    }

    @Test fun browserFiltersAndPagerKeepNavigationBoundaries() {
        var filter = -1
        var page = -1
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Column {
                    RuleBrowserFilters(0) { filter = it }
                    RuleBrowserPager(0, 2, false) { page = it }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_filter_disabled)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_previous_page)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_next_page)).performClick()
        compose.runOnIdle { assertEquals(2, filter); assertEquals(1, page) }
    }

    @Test fun disabledSourceIsNotShownAsEnabled() {
        val item = HostListItem().apply {
            id = 43; host = "disabled.test"; type = ListType.BLOCKED; sourceId = 3; matchScope = 1; isEnabled = true
        }
        compose.setContent { AdAwayTheme(dynamicColor = false) { RuleRow(item, "Paused source", active = false) {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_disabled, "Paused source")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_enabled, "Paused source")).assertDoesNotExist()
    }

    @Test fun browserRowsScreenshot() {
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                        RuleBrowserFilters(0) {}
                        repeat(3) { index ->
                            val item = HostListItem().apply {
                                id = 50 + index; host = "ads.example.com"; type = ListType.BLOCKED
                                sourceId = index + 2; matchScope = if (index == 0) 0 else 1; isEnabled = true
                            }
                            RuleRow(item, "Source ${index + 2}", active = index != 2) {}
                        }
                        RuleBrowserPager(0, 2, false) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        saveScreenshot("rules-browser-light")
    }

    @Test fun lightOverviewScreenshot() = overviewScreenshot(false, 1f, "overview-light")
    @Test fun darkLargeTextOverviewScreenshot() = overviewScreenshot(true, 1.6f, "overview-dark-large-text")

    private fun overviewScreenshot(dark: Boolean, scale: Float, name: String) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                AdAwayTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusCard(true, "Hosts")
                            AdaptiveHostSummary(300000, 24, 8)
                            RuleSyncCard(HostsSyncStatus.IDLE) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_status_active)).assertIsDisplayed()
        compose.onNodeWithText("300,000").assertExists()
        compose.waitForIdle()
        saveScreenshot(name)
        compose.onNode(hasClickAction()).performScrollTo().assertIsEnabled()
        if (scale > 1f) saveScreenshot("$name-actions")
    }

    private fun saveScreenshot(name: String) {
        // AGP uninstalls test APKs after the run. MediaStore output survives that
        // cleanup on the disposable emulator and needs no storage permission.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AdAwayUiReview")
        }
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
