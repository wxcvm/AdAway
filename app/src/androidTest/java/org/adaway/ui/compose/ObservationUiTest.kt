package org.adaway.ui.compose

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.adaway.R
import org.adaway.ui.compose.theme.AdAwayTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real settings branches; fake read-only observation, no root/network/certificate mutation. */
class ObservationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frames = JSONArray()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun close() { scope.cancel(); if (frames.length() > 0) save("frames-${System.nanoTime()}.json", "application/json", frames.toString(2).toByteArray()) }

    private fun save(name: String, mime: String, bytes: ByteArray) {
        val image = mime == "image/png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/AdAwayUiReview" else "Download/AdAwayUiReview")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    private fun frame(name: String, tag: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val bytes = java.io.ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bitmap.recycle()
        save("$name.png", "image/png", bytes.toByteArray())
        frames.put(JSONObject().put("name", name).put("clockMs", compose.mainClock.currentTime)
            .put("tag", tag).put("bounds", "$bounds").put("semantics", node.config.toString()))
        assertTrue("Blank/unlaid-out card: $name", bounds.width > 0 && bounds.height > 0)
    }
    private fun section(index: Int) = compose.onNodeWithTag("settings-section-scroll-$index", useUnmergedTree = true)
    private fun scroll(index: Int): Float = section(index).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun enter(index: Int) {
        // Lazy rows below the viewport do not exist in the semantics tree yet.
        compose.onNodeWithTag("settings-categories").performScrollToNode(hasTestTag("settings-category-$index"))
        compose.onNodeWithTag("settings-category-$index").performClick()
    }
    private fun back() { compose.onNodeWithTag("settings-section-back").performClick() }
    private fun vm() = StatsViewModel(ApplicationProvider.getApplicationContext<Application>(), false)

    @Test fun tenCertificateEntriesRetainKnownStateAndStableFirstFramesAcrossThemes() {
        val first = CompletableDeferred<CertificateSnapshot>()
        var fail = false
        val known = CertificateSnapshot(R.string.pref_webserver_state_running_and_installed_system, 2, 365)
        val observation = CertificateObservation(scope, { first.await(); if (fail) error("probe failed") else known })
        val model = vm()
        var dark by mutableStateOf(false)
        compose.setContent { AdAwayTheme(dynamicColor = false, darkTheme = dark) { SettingsScreen(model, observation) } }
        enter(3)
        section(3).performTouchInput { swipeUp() }
        assertTrue("Deep category fixture must actually scroll", scroll(3) > 0)
        back()
        compose.mainClock.autoAdvance = false
        repeat(10) { entry ->
            if (entry == 5) compose.runOnIdle { dark = true }
            enter(2)
            repeat(3) { sample ->
                frame("certificate-entry-$entry-frame-$sample", "certificate-observation-card")
                assertEquals("Independent first-frame scroll", 0f, scroll(2), 1f)
                if (entry == 0) {
                    compose.onNodeWithTag("certificate-server-status").assertTextEquals(context.getString(R.string.observation_checking))
                    compose.onNodeWithTag("certificate-trust-action").assertIsNotEnabled()
                } else compose.onNodeWithTag("certificate-trust-status").assertTextEquals(context.getString(R.string.compose_settings_cert_level_system))
            }
            if (entry == 0) {
                compose.runOnIdle { first.complete(known) }
                frame("certificate-first-result", "certificate-observation-card")
            }
            if (entry == 7) compose.runOnIdle { fail = true }
            back()
        }
        assertTrue("Failure must be explicit, not a false untrusted result", observation.state.value.stale)
    }

    @Test fun sectionScrollAndSelectionRestoreWithoutPostDrawReset() {
        val model = vm()
        val observation = CertificateObservation(scope, { CertificateSnapshot(R.string.observation_unknown, 1, null) })
        val restore = StateRestorationTester(compose)
        restore.setContent { AdAwayTheme(dynamicColor = false) { SettingsScreen(model, observation) } }
        enter(3); section(3).performTouchInput { swipeUp() }
        val moved = scroll(3); assertTrue(moved > 0)
        back(); enter(2); assertEquals(0f, scroll(2), 1f)
        back(); enter(3); assertEquals(moved, scroll(3), 1f)
        restore.emulateSavedInstanceStateRestore()
        assertEquals(moved, scroll(3), 1f)
        frame("category-state-restored", "settings-section-scroll-3")
    }

    @Test fun rankingCardRemainsMountedForMissingFailedAndValidEmptyAtLargeText() {
        var observation by mutableStateOf(TopHostsObservation())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                AdAwayTheme(dynamicColor = false, darkTheme = true) { Surface { Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState())) { TopHostsCard(observation) } } }
            }
        }
        frame("rank-loading", "top-hosts-card")
        val good = observation.accept(listOf(BlockedHost("ads.example", 4)), TopHostsRead.VALID, 123, false)
        compose.runOnIdle { observation = good }; frame("rank-known", "top-hosts-card")
        compose.onNodeWithText("ads.example").assertExists()
        compose.runOnIdle { observation = observation.accept(emptyList(), TopHostsRead.MISSING, 456, false) }
        frame("rank-missing-historical", "top-hosts-card"); compose.onNodeWithText("ads.example").assertExists()
        compose.runOnIdle { observation = observation.unavailable() }; frame("rank-failed-historical", "top-hosts-card")
        compose.runOnIdle { observation = observation.accept(emptyList(), TopHostsRead.VALID, 789, false) }
        frame("rank-current-empty", "top-hosts-card")
        compose.onNodeWithText("ads.example").assertDoesNotExist()
        compose.onNodeWithTag("top-hosts-state").assertTextEquals(context.getString(R.string.observation_rank_empty))
    }
}
