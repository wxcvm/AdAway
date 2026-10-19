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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType
import org.adaway.db.entity.RuleListRow
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual production components with fixed data; never launches root or network operations. */
class RuleWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun listenerDraftDoesNotSaveOrRestartBeforeApplyAndRejectsHostnames() {
        val previous = org.adaway.util.WebServerUtils.getHttpPort(compose.activity)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ListenerSettingsCard() } }
            }
        }
        compose.onNodeWithText("HTTP 端口").performTextReplacement("20080")
        compose.runOnIdle { assertEquals(previous, org.adaway.util.WebServerUtils.getHttpPort(compose.activity)) }
        compose.onNodeWithText("仅本机（推荐）").performClick()
        compose.onNodeWithText("IPv6 监听地址").assertExists()
        compose.onNodeWithText("IPv4 监听地址").performTextReplacement("localhost")
        compose.onNodeWithText("应用并重启").performScrollTo().performClick()
        compose.onNodeWithText("请输入完整 IPv4", substring = true).performScrollTo().assertIsDisplayed()
        saveScreenshot("listener-invalid-address")
    }

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

    @Test fun rowOpensFullDetailsWithoutDeletingAndCopiesCompleteDomain() {
        val fullDomain = "a-very-long-subdomain.for-rule-inspection.ads.example.com"
        val address = "2001:db8:1234:5678:90ab:cdef:1234:5678"
        val row = RuleListRow().apply {
            item = HostListItem().apply {
                id = 80; host = fullDomain; type = ListType.REDIRECTED; redirection = address
                sourceId = 4; matchScope = 1; isEnabled = true
            }
            sourceLabel = "Paused test source"; sourceEnabled = false
        }
        val show = androidx.compose.runtime.mutableStateOf(false)
        var deleted = false
        var copied = ""
        var managed = false
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                RuleRow(row.item, row.sourceLabel, active = row.isActive,
                    onInspect = { show.value = true }, onDelete = { deleted = true })
                if (show.value) RuleDetailsDialog(row, { show.value = false }, { copied = it }, { managed = true })
            }
        }
        compose.onNodeWithText(fullDomain).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_details)).assertIsDisplayed()
        compose.onNodeWithText(address).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_filter_disabled)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_copy_domain)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_manage_source)).performClick()
        compose.runOnIdle { assertEquals(fullDomain, copied); assertEquals(true, managed); assertEquals(false, deleted) }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_details_close)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_details)).assertDoesNotExist()
    }

    @Test fun personalRuleDetailsRemainReadableAtLargeText() {
        val row = RuleListRow().apply {
            item = HostListItem().apply {
                id = 81; host = "a-long-personal-rule.for-large-text.ads.example.com"
                type = ListType.BLOCKED; sourceId = 1; matchScope = 0; isEnabled = true
            }
            sourceLabel = "ignored"; sourceEnabled = true
        }
        var copied = ""
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                AdAwayTheme(darkTheme = true, dynamicColor = false) {
                    RuleDetailsDialog(row, {}, { copied = it })
                }
            }
        }
        compose.onNodeWithText(row.item.host).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_manage_source)).assertDoesNotExist()
        saveScreenshot("rule-details-dark-large-text", dialog = true)
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_copy_domain)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(row.item.host, copied) }
        saveScreenshot("rule-details-dark-large-text-copy", dialog = true)
    }

    @Test fun personalDeletionRequiresConfirmationAndCanBeCancelled() {
        val item = HostListItem().apply {
            id = 90; host = "personal.example.com"; sourceId = 1; type = ListType.BLOCKED
        }
        val showing = androidx.compose.runtime.mutableStateOf(false)
        var removals = 0
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                RuleRow(item, "Personal", onDelete = { showing.value = true })
                if (showing.value) RuleRemovalDialog(
                    title = compose.activity.getString(R.string.compose_rules_remove_personal_title),
                    target = item.host,
                    explanation = compose.activity.getString(R.string.compose_rules_remove_personal_effect),
                    busy = false, failed = false, canConfirm = true,
                    onConfirm = { removals++; showing.value = false },
                    onDismiss = { showing.value = false },
                )
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.compose_rules_delete)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.compose_rules_delete)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_remove_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test fun removalFailureKeepsTargetAndBusyWorkCannotBeRepeated() {
        val busy = androidx.compose.runtime.mutableStateOf(false)
        val failed = androidx.compose.runtime.mutableStateOf(false)
        var removals = 0
        compose.setContent {
            AdAwayTheme(darkTheme = true, dynamicColor = false) {
                RuleRemovalDialog(
                    title = compose.activity.getString(R.string.compose_rules_delete_source_title),
                    target = "Paused test source",
                    explanation = compose.activity.getString(R.string.compose_rules_remove_source_effect),
                    busy = busy.value, failed = failed.value, canConfirm = true,
                    onConfirm = { removals++; busy.value = true }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_remove_confirm)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_removing)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_cancel)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, removals); busy.value = false; failed.value = true }
        compose.onNodeWithText("Paused test source").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_remove_failed)).assertIsDisplayed()
        saveScreenshot("rule-removal-failed-dark", dialog = true)
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_remove_retry)).performClick()
        compose.runOnIdle { assertEquals(2, removals) }
    }

    @Test fun sourceFieldsFreezeLinkTypeAndFilePickerWhileSaving() {
        val fileMode = androidx.compose.runtime.mutableStateOf(false)
        val busy = androidx.compose.runtime.mutableStateOf(true)
        var picked = 0
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                SourceEditorFields(fileMode.value, "https://example.test/hosts", "local-hosts.txt", busy.value, false,
                    onTypeChange = { fileMode.value = it }, onUrlChange = {}, onPickFile = { picked++ })
            }
        }
        compose.onNodeWithText("https://example.test/hosts").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_type_url)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_type_file)).assertIsNotEnabled()
        compose.runOnIdle { fileMode.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_pick_file)).assertIsNotEnabled()
        compose.onNodeWithText("local-hosts.txt").assertIsDisplayed()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_pick_file)).performClick()
        compose.runOnIdle { assertEquals(1, picked) }
    }

    @Test fun personalFieldsKeepFailedInputAndUnlockOnlyAfterSaving() {
        val busy = androidx.compose.runtime.mutableStateOf(true)
        val failed = androidx.compose.runtime.mutableStateOf(false)
        val host = androidx.compose.runtime.mutableStateOf("ads.example.com")
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                PersonalRuleEditorFields(host.value, ListType.REDIRECTED.value, "2001:db8::1", busy.value, failed.value,
                    onHostChange = { host.value = it; failed.value = false }, onTypeChange = {}, onRedirectChange = {})
            }
        }
        compose.onNodeWithText("ads.example.com").assertIsNotEnabled()
        compose.onNodeWithText("2001:db8::1").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_filter_allowed)).assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; failed.value = true }
        compose.onNodeWithText("ads.example.com").assertIsEnabled()
        compose.onNodeWithText("2001:db8::1").assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_add_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ads.example.com").performScrollTo().performTextReplacement("fixed.example.com")
        compose.runOnIdle { assertEquals("fixed.example.com", host.value) }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_add_failed)).assertDoesNotExist()
    }

    @Test fun largeTextRuleEditorScrollsToPersistentErrorInSmallViewport() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                AdAwayTheme(darkTheme = true, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            Box(Modifier.width(280.dp).height(200.dp)) {
                                PersonalRuleEditorFields("ads.example.com", ListType.REDIRECTED.value, "2001:db8::1", false, true,
                                    onHostChange = {}, onTypeChange = {}, onRedirectChange = {})
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("2001:db8::1").performScrollTo().assertIsDisplayed()
        val error = compose.activity.getString(R.string.compose_rules_add_failed)
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(error)))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue("Form must actually scroll in the constrained viewport", scroll.value() > 0f) }
        saveScreenshot("rule-editor-dark-large-text-error")
    }

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

    private fun saveScreenshot(name: String, dialog: Boolean = false) {
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
            val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            check(root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
