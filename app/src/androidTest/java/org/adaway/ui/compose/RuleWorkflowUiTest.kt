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
import androidx.compose.ui.graphics.toArgb
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

    @Test fun deniedDocumentAndPlaceholderImageHaveDifferentPathsAndStatuses() {
        val rows = listOf(
            QueryLogEntry(ts = 1234, host = "fd00:7::2", family = 6, action = 3, path = "/internal-test", httpStatus = 403),
            QueryLogEntry(ts = 1234, host = "fd00:7::2", family = 6, action = 1, type = 0, mode = 0,
                path = "/favicon.ico", httpStatus = 200),
        )
        val filter = androidx.compose.runtime.mutableIntStateOf(-1)
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
            QueryLogContent(rows.filter { filter.intValue == -1 || it.action == filter.intValue },
                2, 2, filter.intValue, false, false, false, { filter.intValue = it }, {}, {})
        } } }
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText("/internal-test"))
        compose.onNodeWithText("HTTP 403").assertExists()
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText("/favicon.ico"))
        compose.onNodeWithText("HTTP 200").assertExists()
        saveScreenshot("logs-same-host-different-response")
        compose.onNodeWithTag("query-log-list").performScrollToIndex(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.compose_logs_access_denied))[0]
            .performScrollTo().performClick()
        compose.onNodeWithText("/favicon.ico").assertDoesNotExist()
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText("/internal-test"))
        compose.onNodeWithText("/internal-test").performClick()
        compose.onNode(hasText("HTTP 403") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_rejection_detail)).assertExists()
        saveScreenshot("logs-access-denied-details", dialog = true)
    }

    @Test fun oldLogDoesNotInventRequestPathOrSuccessfulHttpStatus() {
        compose.setContent { AdAwayTheme(dynamicColor = false) {
            QueryLogDetailsDialog(QueryLogEntry(ts = 1234, host = "legacy.example", action = 1), {}, {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_path_unknown)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_status_unknown)).assertExists()
        compose.onNodeWithText("HTTP 200").assertDoesNotExist()
    }

    private class ContentBackend : ContentResponseBackend {
        @Volatile var policy = org.adaway.util.ContentResponseSettings.fromPreferences(emptyMap<String, Any>())
        var ruleText = ""
        val saves = java.util.concurrent.atomic.AtomicInteger()
        val ruleSaves = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var fail = false
        @Volatile var acknowledged = false
        @Volatile var failRules = false
        override fun load() = policy
        override fun loadRules(): String {
            if (failRules) throw java.io.IOException("fixture rule file unreadable")
            return ruleText
        }
        override fun save(settings: org.adaway.util.ContentResponseSettings): Boolean {
            saves.incrementAndGet()
            if (fail) throw java.io.IOException("fixture storage unavailable")
            policy = settings
            return acknowledged
        }
        override fun saveRules(text: String): String {
            ruleSaves.incrementAndGet()
            ruleText = org.adaway.util.ContentResponseRules.validate(text)
            return ruleText
        }
    }

    private fun showContentSettings(backend: ContentBackend) {
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) { ContentResponseSettingsCard(backend) }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("content-toggle-images").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun contentCategoriesStayDraftUntilSaveAndLegacyAckIsNotApplied() {
        val backend = ContentBackend()
        showContentSettings(backend)
        listOf("images", "scripts", "styles", "fonts", "media", "structures", "api", "telemetry", "config", "ws_sse").forEach {
            compose.onNodeWithTag("content-toggle-$it").assertExists()
        }
        compose.onNodeWithTag("content-toggle-images").performScrollTo().performClick().assertIsOff()
        assertEquals(0, backend.saves.get())
        compose.onNodeWithTag("content-status").assertTextEquals(compose.activity.getString(R.string.content_status_draft))
        compose.onNodeWithTag("content-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { backend.saves.get() == 1 && !backend.policy.enabled("images") }
        compose.waitForIdle()
        compose.onNodeWithTag("content-status").assertTextEquals(compose.activity.getString(R.string.content_status_saved_unknown))
        compose.onNodeWithTag("content-toggle-images").performScrollTo()
        saveScreenshot("content-response-categories")
    }

    @Test fun unreadableRulesDoNotPreventDisablingOverrideOrOverwriteTheFile() {
        val backend = ContentBackend().apply {
            failRules = true
            policy = policy.with("rule_override", true)
            ruleText = "unreadable legacy rules retained"
        }
        showContentSettings(backend)
        compose.onNodeWithTag("content-rules-load-error").assertExists()
        compose.onNodeWithTag("content-edit-rules").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("content-toggle-rule_override").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("content-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { backend.saves.get() == 1 && !backend.policy.enabled("rule_override") }
        assertEquals(0, backend.ruleSaves.get())
        assertEquals("unreadable legacy rules retained", backend.ruleText)
    }

    @Test fun domainResponseLogDoesNotClaim204OrAStructuredResource() {
        compose.setContent { AdAwayTheme(dynamicColor = false) {
            androidx.compose.material3.Text(queryActionSummary(QueryLogEntry(action = 1, type = -1, mode = 4)))
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.content_log_domain_response), substring = true).assertExists()
        compose.onNodeWithText("204", substring = true).assertDoesNotExist()
    }

    @Test fun contentSaveFailureKeepsDraftAndRetryRequiresMatchingServerAck() {
        val backend = ContentBackend().apply { fail = true }
        showContentSettings(backend)
        compose.onNodeWithTag("content-toggle-images").performScrollTo().performClick()
        compose.onNodeWithTag("content-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("content-save-error").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(backend.policy.enabled("images"))
        compose.onNodeWithTag("content-toggle-images").assertIsOff()
        backend.fail = false
        backend.acknowledged = true
        compose.onNodeWithTag("content-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { backend.saves.get() == 2 && !backend.policy.enabled("images") }
        compose.waitForIdle()
        compose.onNodeWithTag("content-status").assertTextEquals(compose.activity.getString(R.string.content_status_confirmed))
    }

    @Test fun domainResponseEditorRejectsPathsAndDoesNotEnableDraftOnSave() {
        val backend = ContentBackend()
        showContentSettings(backend)
        compose.onNodeWithTag("content-toggle-rule_override").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("content-edit-rules").performScrollTo().performClick()
        compose.onNodeWithTag("content-rules-text").performTextInput("example.com/path=204")
        compose.onNodeWithTag("content-rules-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("content-rules-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, backend.ruleSaves.get())
        compose.onNodeWithTag("content-rules-text").performTextReplacement("example.com=404")
        compose.onNodeWithTag("content-rules-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("content-rules-saved").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("example.com = 404\n", backend.ruleText)
        assertEquals(0, backend.saves.get())
        compose.onNodeWithText(compose.activity.getString(R.string.content_close)).performClick()
        compose.onNodeWithTag("content-toggle-rule_override").performScrollTo().assertIsEnabled().assertIsOff()
        compose.onNodeWithTag("content-rule-count").assertTextEquals(compose.activity.getString(R.string.content_rules_count, 1))
    }

    @Test fun sourceCardsRepairRawNamesAndHideCredentials() {
        val source = org.adaway.db.entity.HostsSource().apply {
            id = 2
            url = "https://user:password@raw.githubusercontent.com/owner/rules/main/hosts?token=secret"
            label = org.adaway.model.source.SourcePresentation.legacyName("raw", url)
        }
        var inspected = false
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                SourcesWorkspace(listOf(source), true, { _, _ -> }, {}, {}, { inspected = true })
            } }
        }
        compose.onNodeWithText("owner/rules · hosts").assertIsDisplayed()
        compose.onNodeWithText("password", substring = true).assertDoesNotExist()
        compose.onNodeWithText("token=", substring = true).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_details)).performClick()
        compose.runOnIdle { assertTrue(inspected) }
        saveScreenshot("source-workspace-readable-name")
    }

    @Test fun sourceActionsRemainAccessibleOnNarrowLargeText() {
        val source = org.adaway.db.entity.HostsSource().apply {
            id = 2; label = "Example rules"; url = "https://example.test/hosts"
        }
        var inspected = 0
        var deleted = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                AdAwayTheme(dynamicColor = false) { Surface {
                    Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        SourcesWorkspace(listOf(source), true, { _, _ -> }, {}, { deleted++ }, { inspected++ })
                    }
                } }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_details)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_delete_source)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, inspected); assertEquals(1, deleted) }
        saveScreenshot("source-actions-narrow-large-text")
    }

    @Test fun failedRenameRetainsInputAndIsRetryable() {
        val source = org.adaway.db.entity.HostsSource().apply {
            id = 2; label = "raw"; url = "https://raw.githubusercontent.com/owner/rules/main/hosts"
        }
        val failed = androidx.compose.runtime.mutableStateOf(false)
        var attempts = 0
        compose.setContent { AdAwayTheme(dynamicColor = false) {
            SourceDetailsDialog(source, false, failed.value, {}, { attempts++; failed.value = true })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_name)).performTextReplacement("My rules")
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_save_name)).performClick()
        compose.onNodeWithText("My rules").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_rename_failed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_rules_source_save_name)).performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
        saveScreenshot("source-rename-failed-input-retained", dialog = true)
    }

    @Test fun logProgrammaticScrollKeepsLiveAndDragPinsWithResumeVisible() {
        val live = androidx.compose.runtime.mutableStateOf(true)
        val rows = (1..40).map { QueryLogEntry(ts = it.toLong(), host = "request$it.example") }
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
            QueryLogContent(rows, rows.size, rows.size, -1, false, false, false,
                {}, {}, {}, live = live.value, onLive = { live.value = it })
        } } }
        compose.onNodeWithTag("query-log-list").performScrollToIndex(15)
        compose.runOnIdle { assertTrue("Layout/programmatic scrolling is not a request to pause", live.value) }
        compose.onNodeWithTag("query-log-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Dragging history must pin it", !live.value) }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_resume)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(live.value) }
        saveScreenshot("logs-history-resume-remains-visible")
    }

    @Test fun logFamilyAndTypeControlsFilterRecordedMetadata() {
        val family = androidx.compose.runtime.mutableStateOf<Int?>(null)
        val type = androidx.compose.runtime.mutableStateOf<Int?>(null)
        val rows = listOf(QueryLogEntry(host = "v4-image.example", family = 4, type = 0, action = 1),
            QueryLogEntry(host = "v6-script.example", family = 6, type = 1, action = 1),
            QueryLogEntry(host = "legacy.example", family = 0, type = -1, action = 1))
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
            QueryLogContent(rows.filter { (family.value == null || family.value == it.family) &&
                (type.value == null || type.value == it.type) }, 3, 3, -1, false, false, false, {}, {}, {},
                family = family.value, onFamily = { family.value = it }, type = type.value, onType = { type.value = it })
        } } }
        compose.onNodeWithTag("query-family-6").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(6, family.value) }
        compose.onNodeWithText("v4-image.example").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_type_all)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_type_scripts)).performClick()
        compose.runOnIdle { assertEquals(1, type.value) }
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText("v6-script.example"))
        compose.onNodeWithText("v6-script.example").assertIsDisplayed()
        saveScreenshot("logs-ipv6-script-classification")
    }

    @Test fun emptyLogFilterStillAllowsHistoryAndReadFailureAllowsRefresh() {
        val failed = androidx.compose.runtime.mutableStateOf(false)
        var older = 0
        var refreshed = 0
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
            QueryLogContent(emptyList(), 300, 600, 1, false, failed.value, true, {}, { older++ }, { refreshed++ })
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_more)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, older); failed.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_read_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_refresh)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
        saveScreenshot("logs-page-failure-refresh")
    }

    @Test fun logWorkbenchSearchPauseAndDetailsRemainUsableOnNarrowLargeText() {
        val rows = listOf(QueryLogEntry(ts = 1234, uid = -1, action = 1, type = 1, mode = 1,
            host = "blocked.example"), QueryLogEntry(ts = 1234, uid = 42, action = 2, host = "other.example"))
        val query = androidx.compose.runtime.mutableStateOf("")
        val live = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                AdAwayTheme(dynamicColor = false) { Surface(Modifier.widthIn(max = 320.dp)) {
                    QueryLogContent(rows.filter { it.host.contains(query.value, ignoreCase = true) },
                        2, 2, -1, false, false, false, {}, {}, {}, query = query.value,
                        onQuery = { query.value = it }, live = live.value, onLive = { live.value = it },
                        onInspect = { live.value = false })
                } }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_pause)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(!live.value) }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_search)).performScrollTo().performTextInput("BLOCKED")
        compose.onNodeWithText("other.example").assertDoesNotExist()
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText("blocked.example"))
        compose.onNodeWithText("blocked.example").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_details)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_only_app)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_details_scope)).performScrollTo().assertIsDisplayed()
        saveScreenshot("logs-workbench-narrow-details", dialog = true)
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_close)).performClick()
        compose.onNodeWithTag("query-log-list").performScrollToIndex(0)
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_search)).performScrollTo().performTextReplacement("absent")
        compose.onNodeWithTag("query-log-list").performScrollToNode(hasText(compose.activity.getString(R.string.compose_logs_no_match)))
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_no_match)).assertIsDisplayed()
        saveScreenshot("logs-workbench-filter-empty")
    }

    @Test fun localExemptionDetailsDoNotClaimSuccessfulUpstreamAccess() {
        var filtered = false
        compose.setContent { AdAwayTheme(dynamicColor = false) {
            QueryLogDetailsDialog(QueryLogEntry(uid = 42, action = 2, host = "exempt.example"), {}, { filtered = true })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_local_exemption)).assertIsDisplayed()
        compose.onAllNodesWithText("UID 42").assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_exemption_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.compose_logs_only_app)).performClick()
        compose.runOnIdle { assertTrue(filtered) }
        saveScreenshot("logs-local-exemption-details", dialog = true)
    }

    @Test fun connectionOnlyTrafficIsVisibleInTheActualBarChart() {
        val prefs = compose.activity.getSharedPreferences("compose_general", android.content.Context.MODE_PRIVATE)
        val before = prefs.all
        try {
            check(prefs.edit().putInt("chart_range", 0).putInt("chart_style", CHART_STYLE_BARS).commit())
            var connectionColor = 0
            compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
                connectionColor = MaterialTheme.colorScheme.tertiary.toArgb()
                TrafficTrendCard(listOf(HistPoint(ts = 1234, connections = 100)), emptyList(), true)
            } } }
            // Inspect only the production Canvas, not its legend. Previously
            // connections affected the scale but no connection bar was drawn.
            val bitmap = compose.onNodeWithTag("traffic-trend-chart").captureToImage().asAndroidBitmap()
            var painted = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == connectionColor) painted++
            }
            assertTrue("Connection series must paint a visible bar, got $painted pixels", painted > bitmap.width)
            saveScreenshot("statistics-connection-only-bars")
        } finally {
            val edit = prefs.edit()
            for (key in listOf("chart_range", "chart_style")) {
                val old = before[key]
                if (old is Int) edit.putInt(key, old) else edit.remove(key)
            }
            check(edit.commit())
        }
    }

    @Test fun legacyAllTimeTrendMapsToAvailableWindowAndEmptyWindowCanBeChanged() {
        val prefs = compose.activity.getSharedPreferences("compose_general", android.content.Context.MODE_PRIVATE)
        val hadRange = prefs.contains("chart_range")
        val previousRange = prefs.getInt("chart_range", 0)
        try {
            check(prefs.edit().putInt("chart_range", 3).commit())
            assertEquals(2, chartRange(compose.activity))
            compose.setContent { AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrafficTrendCard(listOf(HistPoint(ts = 1234, requests = 3, blocked = 1)), emptyList(), false)
                }
            } } }
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_range_all)).assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_range_30d)).assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_history_empty)).assertIsDisplayed()
            saveScreenshot("statistics-empty-real-window")
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_range_24h)).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_range_24h)).assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.compose_stats_history_empty)).assertDoesNotExist()
        } finally {
            val edit = prefs.edit()
            if (hadRange) edit.putInt("chart_range", previousRange) else edit.remove("chart_range")
            check(edit.commit())
        }
    }

    @Test fun startupAddressCreationIsAnUnsavedExplicitOption() {
        val previous = org.adaway.util.WebServerUtils.isListenerAutoCreate(compose.activity)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ListenerSettingsCard() } }
            }
        }
        compose.onNodeWithText("随服务器启动创建缺失地址").performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(previous, org.adaway.util.WebServerUtils.isListenerAutoCreate(compose.activity)) }
        saveScreenshot("listener-startup-option-draft")
    }

    @Test fun creatingListenerAddressesRequiresConfirmationAndCancelKeepsSettings() {
        val previous = org.adaway.util.WebServerUtils.getListenerSettings(compose.activity)
        val managed = org.adaway.util.WebServerUtils.getManagedListenerAddresses(compose.activity)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ListenerSettingsCard() } }
            }
        }
        compose.onNodeWithText("IPv6 监听地址").performTextReplacement("fd6b:2a91:84f0::73")
        compose.onNodeWithText("地址不存在？创建并应用…").performScrollTo().performClick()
        compose.onNodeWithText("创建缺失的本机地址？").assertIsDisplayed()
        compose.onNodeWithText("仅在 lo 回环接口", substring = true).assertIsDisplayed()
        saveScreenshot("listener-create-confirmation", dialog = true)
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(previous.ipv6, org.adaway.util.WebServerUtils.getListenerSettings(compose.activity).ipv6)
            assertEquals(managed, org.adaway.util.WebServerUtils.getManagedListenerAddresses(compose.activity))
        }
    }

    @Test fun arrivalCheckDomainsCanBeCustomizedWithoutStartingDetection() {
        compose.setContent {
            AdAwayTheme(dynamicColor = false) {
                Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { AdGuardArrivalCard(enabled = true) } }
            }
        }
        compose.onNodeWithText("广告规则测试域名（可修改或留空）").performTextReplacement("ads.custom.example")
        compose.onNodeWithText("Hosts 规则测试域名（可选）").performTextReplacement("hosts.custom.example")
        compose.onNodeWithText("ads.custom.example").assertExists()
        compose.onNodeWithText("hosts.custom.example").assertExists()
        compose.onNodeWithText("检测 DNS 与请求到达").performScrollTo().assertIsEnabled()
        saveScreenshot("listener-arrival-drafts")
    }

    @Test fun arrivalCancellationWaitsForCleanupThenAllowsFreshRetry() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AdGuardArrivalCard(true) { _, _, _, session ->
                        if (calls.incrementAndGet() == 1) {
                            session.progress("正在检查 IPv6 HTTP 回执…")
                            started.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            "已取消任务的迟到结果"
                        } else "第二轮有效结果"
                    }
                }
            } }
        }
        compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
        compose.waitUntil(5000) { started.count == 0L }
        compose.onNodeWithTag("arrival-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("arrival-start").assertIsNotEnabled()
        compose.onNodeWithTag("arrival-status").assertTextContains("正在取消", substring = true)
        release.countDown()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("检测已取消", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("第二轮有效结果").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已取消任务的迟到结果").assertDoesNotExist()
        compose.onNodeWithTag("arrival-copy").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, calls.get()) }
        saveScreenshot("listener-arrival-cancel-retry")
    }

    @Test fun leavingArrivalCardCancelsItsBlockingSession() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val started = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val sessionRef = java.util.concurrent.atomic.AtomicReference<org.adaway.util.ListenerDiagnosticSession>()
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                if (visible.value) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AdGuardArrivalCard(true) { _, _, _, session ->
                            sessionRef.set(session)
                            started.countDown()
                            try {
                                val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
                                while (!session.isCancelled && System.nanoTime() < until) Thread.sleep(10)
                                session.checkCancelled()
                                "不应显示的离页结果"
                            } finally { finished.countDown() }
                        }
                    }
                } else androidx.compose.material3.Text("已离开诊断页面")
            } }
        }
        compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
        compose.waitUntil(5000) { started.count == 0L }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5000) { finished.count == 0L }
        compose.runOnIdle { assertTrue(sessionRef.get().isCancelled) }
        compose.onNodeWithText("已离开诊断页面").assertIsDisplayed()
        compose.onNodeWithText("不应显示的离页结果").assertDoesNotExist()
    }

    @Test fun arrivalProgressAndPreviousReportRemainVisibleDuringAnotherRun() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AdGuardArrivalCard(true) { _, _, _, session ->
                        if (calls.incrementAndGet() == 1) "上次有效检测结果"
                        else {
                            session.progress("正在检查 HTTPS 证书与回执…")
                            started.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            session.checkCancelled()
                            "新检测结果"
                        }
                    }
                }
            } }
        }
        compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("上次有效检测结果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
        compose.waitUntil(5000) { started.count == 0L }
        compose.waitUntil(5000) { compose.onAllNodesWithText("正在检查 HTTPS 证书与回执…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arrival-progress").assertExists()
        compose.onNodeWithTag("arrival-report").assertTextEquals("上次有效检测结果")
        compose.onNodeWithTag("arrival-cancel").performScrollTo()
        saveScreenshot("listener-arrival-progress-cancel")
        compose.onNodeWithTag("arrival-cancel").performClick()
        release.countDown()
        compose.waitUntil(5000) { compose.onAllNodesWithText("检测已取消", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arrival-report").assertTextEquals("上次有效检测结果")
    }

    @Test fun lateAutomaticProbeCannotOverwriteAnAppliedConfigurationResult() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ListenerSettingsCard(
                        checkSettings = { _, _ ->
                            started.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            finished.countDown()
                            "过期自动检测结果"
                        },
                        applySettings = { _, _, _, _ -> "已应用本轮监听配置" },
                        rootGranted = { true }
                    )
                }
            } }
        }
        try {
            compose.waitUntil(5000) { started.count == 0L }
            compose.onNodeWithText("应用并重启").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("已应用本轮监听配置").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            compose.waitUntil(5000) { finished.count == 0L }
            compose.waitForIdle()
            compose.onNodeWithTag("listener-message").assertTextEquals("已应用本轮监听配置")
            compose.onNodeWithText("过期自动检测结果").assertDoesNotExist()
        } finally { release.countDown() }
    }

    @Test fun applyingListenerConfigurationCancelsOldSessionAndMarksPreservedReportHistorical() {
        val revision = androidx.compose.runtime.mutableStateOf(0L)
        val enabled = androidx.compose.runtime.mutableStateOf(true)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sessionRef = java.util.concurrent.atomic.AtomicReference<org.adaway.util.ListenerDiagnosticSession>()
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AdGuardArrivalCard(enabled.value, revision.value) { _, _, _, session ->
                        if (calls.incrementAndGet() == 1) "应用前已完成的报告"
                        else {
                            sessionRef.set(session)
                            started.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            "旧配置任务的迟到结果"
                        }
                    }
                }
            } }
        }
        try {
            compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("应用前已完成的报告").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
            compose.waitUntil(5000) { started.count == 0L }
            compose.runOnIdle { enabled.value = false; revision.value++ }
            compose.waitUntil(5000) { sessionRef.get()?.isCancelled == true }
            compose.onNodeWithTag("arrival-report").assertTextEquals("应用前已完成的报告")
            compose.onNodeWithText("历史结果（应用操作或配置变更后未复核）").assertExists()
            release.countDown()
            compose.waitUntil(5000) { compose.onAllNodesWithText("旧目标检测已取消", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("旧配置任务的迟到结果").assertDoesNotExist()
            compose.runOnIdle { enabled.value = true }
            compose.onNodeWithTag("arrival-start").performScrollTo().assertIsEnabled()
            saveScreenshot("listener-arrival-configuration-history")
        } finally { release.countDown() }
    }

    @Test fun committedListenerPortChangeInvalidatesHistoryButDomainDraftDoesNot() {
        val preferences = compose.activity.getSharedPreferences("compose_webserver", android.content.Context.MODE_PRIVATE)
        val hadPort = preferences.contains("http_port")
        val previousPort = org.adaway.util.WebServerUtils.getHttpPort(compose.activity)
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { Surface {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AdGuardArrivalCard(true) { _, _, _, _ -> "原保存目标的报告" }
                }
            } }
        }
        try {
            compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("原保存目标的报告").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("广告规则测试域名（可修改或留空）").performScrollTo().performTextReplacement("other.example.test")
            compose.onNodeWithText("历史结果（应用操作或配置变更后未复核）").assertDoesNotExist()
            compose.runOnIdle { check(preferences.edit().putInt("http_port", if (previousPort == 28080) 28081 else 28080).commit()) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("历史结果（应用操作或配置变更后未复核）").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("arrival-report").assertTextEquals("原保存目标的报告")
            compose.onNodeWithTag("arrival-start").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("历史结果（应用操作或配置变更后未复核）").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("arrival-report").assertTextEquals("原保存目标的报告")
        } finally {
            compose.runOnIdle {
                val edit = preferences.edit()
                if (hadPort) edit.putInt("http_port", previousPort) else edit.remove("http_port")
                check(edit.commit())
            }
        }
    }

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

    @Test fun dualStackRelayActionsRequireActiveSessionAndDisableWhileBusy() {
        val active = androidx.compose.runtime.mutableStateOf(false)
        val busy = androidx.compose.runtime.mutableStateOf(false)
        val probes = mutableListOf<Boolean>()
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { DiagnosticRelayActions(active.value, busy.value) { probes.add(it) } }
        }
        val four = compose.activity.getString(R.string.compose_tun_diagnostic_test_relay_family, "IPv4")
        val six = compose.activity.getString(R.string.compose_tun_diagnostic_test_relay_family, "IPv6")
        compose.onNodeWithText(four).assertIsNotEnabled()
        compose.onNodeWithText(six).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(probes.isEmpty()); active.value = true }
        compose.onNodeWithText(four).performClick()
        compose.onNodeWithText(six).performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), probes); busy.value = true }
        compose.onNodeWithText(four).assertIsNotEnabled()
        compose.onNodeWithText(six).assertIsNotEnabled()
        saveScreenshot("dual-stack-relay-actions")
    }

    @Test fun tcpBackendActionsRequireActiveSessionAndDisableWhileBusy() {
        val active = androidx.compose.runtime.mutableStateOf(false)
        val busy = androidx.compose.runtime.mutableStateOf(false)
        val probes = mutableListOf<Boolean>()
        compose.setContent {
            AdAwayTheme(dynamicColor = false) { DiagnosticTcpActions(active.value, busy.value) { probes.add(it) } }
        }
        val four = compose.activity.getString(R.string.compose_tcp_test, "IPv4")
        val six = compose.activity.getString(R.string.compose_tcp_test, "IPv6")
        compose.onNodeWithText(four).assertIsNotEnabled()
        compose.onNodeWithText(six).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(probes.isEmpty()); active.value = true }
        compose.onNodeWithText(four).performClick()
        compose.onNodeWithText(six).performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), probes) }
        saveScreenshot("tcp-backend-actions-ready")
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText(four).assertIsNotEnabled()
        compose.onNodeWithText(six).assertIsNotEnabled()
        saveScreenshot("tcp-backend-actions-busy")
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
