package org.adaway.ui.compose

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.adaway.ui.compose.theme.AdAwayTheme
import org.adaway.util.ListenerResponseCheck
import org.adaway.util.ListenerSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResponseCheckUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AdAwayUiReview")
        }
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun content(check: @Composable () -> Unit) {
        compose.setContent { AdAwayTheme(dynamicColor = false) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { check() } } } }
    }
    @Test fun realHomepageResponseShowsStatusAndLiteralBrowserAddress() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2500
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals("GET / HTTP/1.1", reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 9\r\n\r\nforbidden".toByteArray())
                }
            }.apply { isDaemon = true; start() }
            val closedPort = ServerSocket(0).use { it.localPort }
            content {
                ResponseCheckCard(true, runCheck = { _, _, session ->
                    ListenerResponseCheck.check(ListenerSettings("127.0.0.1", "", server.localPort, closedPort), { true }, session) { true }
                })
            }
            compose.onNodeWithTag("response-check-start").performScrollTo().performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("response-check-report").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("response-check-report").assertTextContains("HTTP: 403", substring = true)
            compose.onNodeWithTag("response-browser-4-false").assertTextContains("http://127.0.0.1:${server.localPort}/", substring = true)
            compose.onNodeWithTag("response-check-report").performScrollTo()
            capture("response-homepage-403-not-placeholder-success")
            worker.join(3500); assertFalse(worker.isAlive)
        }
    }
    @Test fun cancellationAndChangedConfigurationNeverPublishLateObservation() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = CountDownLatch(1); val release = CountDownLatch(1)
            val worker = Thread {
                server.accept().use { socket ->
                    accepted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
                }
            }.apply { isDaemon = true; start() }
            var revision by mutableLongStateOf(0)
            val admission = ListenerDiagnosticAdmission()
            val closedPort = ServerSocket(0).use { it.localPort }
            content {
                ResponseCheckCard(!admission.busy, revision, admission::claim, admission::release,
                    runCheck = { _, _, session -> ListenerResponseCheck.check(
                        ListenerSettings("127.0.0.1", "", server.localPort, closedPort), { true }, session) { true } })
            }
            compose.onNodeWithTag("response-check-start").performScrollTo().performClick()
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            compose.runOnIdle { revision++ }
            compose.waitUntil(5000) { !admission.busy }
            release.countDown(); worker.join(3500)
            compose.onNodeWithTag("response-check-report").assertDoesNotExist()
            compose.onNodeWithTag("response-check-start").assertIsEnabled()
            capture("response-configuration-cancelled-no-late-report")
        }
    }
}
