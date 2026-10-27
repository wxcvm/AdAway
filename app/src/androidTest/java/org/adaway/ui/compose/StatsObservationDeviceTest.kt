package org.adaway.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.adaway.ui.compose.theme.AdAwayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real app nc/HTTP + OkHttp/WebSocket against an ephemeral local fixture, no preference edits. */
class StatsObservationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Clock : StatsClock {
        var now = 0L
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        override fun wallMillis() = 100_000L + now
        override fun monotonicMillis() = now
        override suspend fun pause(millis: Long) {
            val wait = (now + millis) to CompletableDeferred<Unit>()
            waits += wait
            try { wait.second.await() } finally { waits.remove(wait) }
        }
        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val next = waits.filter { it.first <= target }.minByOrNull { it.first } ?: break
                now = next.first; waits.remove(next); next.second.complete(Unit)
            }
            now = target
        }
    }
    private class Service : AutoCloseable {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val http = AtomicInteger(); val upgrades = AtomicInteger()
        val active = AtomicInteger(); val maxActive = AtomicInteger()
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val pool = Executors.newFixedThreadPool(4)
        val accept = Thread {
            try { while (!listener.isClosed) {
                val socket = listener.accept(); sockets += socket
                pool.execute { serve(socket) }
            } } catch (_: Exception) {}
        }.apply { start() }
        fun json() = org.json.JSONObject().put("uptime_seconds", 10)
            .put("total_requests", http.get()+upgrades.get()).put("top_blocked", org.json.JSONArray()).toString()
        fun serve(socket: Socket) {
            var websocket = false
            try { socket.use {
                it.soTimeout = 10_000
                val reader = it.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return
                val headers = mutableMapOf<String,String>()
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val output = it.getOutputStream()
                if (first.contains("/internal-ws")) {
                    websocket = true
                    val n = active.incrementAndGet(); maxActive.updateAndGet { old -> maxOf(old,n) }
                    val attempt = upgrades.incrementAndGet()
                    val key = headers["sec-websocket-key"]!! + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                    val answer = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(key.toByteArray()))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + answer + "\r\n\r\n").toByteArray())
                    val bytes = json().toByteArray()
                    output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte())); output.write(bytes); output.flush()
                    if (attempt == 1) return // Deliberate abrupt close, not a simulated callback.
                    while (it.getInputStream().read() != -1) { /* Wait for observer cancellation. */ }
                } else {
                    http.incrementAndGet()
                    val body = json().toByteArray()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                    output.write(body); output.flush()
                }
            } } catch (_: Exception) {} finally {
                if (websocket) active.decrementAndGet()
                sockets.remove(socket)
            }
        }
        override fun close() {
            listener.close(); sockets.forEach { runCatching { it.close() } }
            accept.join(2000); pool.shutdownNow()
        }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(15_000) { while (!condition()) delay(10) } }

    @Test fun realDisconnectReconnectQuietPollingAndSharedStatus() = runBlocking {
        Service().use { service ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val clock = Clock()
            val observer = withContext(Dispatchers.Main) {
                StatsObservationCoordinator(scope, AndroidStatsTransport(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    { service.listener.localPort }, { null }), clock)
            }
            try {
                compose.setContent { AdAwayTheme {
                    val state by observer.state.collectAsState()
                    StatsObservationStatus(state)
                } }
                val started = System.nanoTime()
                withContext(Dispatchers.Main) { observer.setForeground(true, true) }
                await { observer.state.value.push == StatsPushState.RETRYING && !observer.state.value.refreshing }
                assertEquals(1, service.upgrades.get())
                withContext(Dispatchers.Main) { clock.advance(1000) }
                await { service.upgrades.get() == 2 && observer.state.value.push == StatsPushState.CONNECTED }
                val previous = service.http.get()
                withContext(Dispatchers.Main) { clock.advance(9000) }
                await { service.http.get() > previous && !observer.state.value.refreshing }
                assertEquals(2, service.upgrades.get())
                assertEquals(StatsSource.HTTP, observer.state.value.source)
                assertNull(observer.state.value.error)
                assertNotNull(observer.state.value.stats)
                compose.onNodeWithTag("stats-observation-time").assertIsDisplayed()
                compose.onNodeWithTag("stats-observation-push").assertIsDisplayed()
                withContext(Dispatchers.Main) { observer.setForeground(false) }
                await { service.active.get() == 0 }
                val reads = service.http.get()
                withContext(Dispatchers.Main) { clock.advance(60_000) }
                assertEquals(reads, service.http.get())
                assertEquals(0, clock.waits.size)
                assertEquals(1, service.maxActive.get())
                println("STATS_LOOPBACK elapsedMs=" + (System.nanoTime()-started)/1_000_000 +
                    " http=" + reads + " upgrades=" + service.upgrades.get() + " maxSockets=" + service.maxActive.get())
            } finally {
                withContext(Dispatchers.Main) { observer.close(); scope.cancel() }
            }
        }
    }

    @Test fun unavailableAndUnknownStatusDoesNotDisappearOrBecomeZero() {
        val state = mutableStateOf(StatsObservationState())
        compose.setContent { AdAwayTheme { StatsObservationStatus(state.value) } }
        compose.onNodeWithTag("stats-observation-time").assertIsDisplayed()
        compose.runOnIdle { state.value = StatsObservationState(
            stats=ServerStats(totalRequests=42), observedAtMillis=100_000,
            push=StatsPushState.RETRYING, error=StatsReadError.UNREACHABLE) }
        compose.onNodeWithTag("stats-observation-push").assertIsDisplayed()
        compose.onNodeWithTag("stats-observation-status").assertIsDisplayed()
        compose.runOnIdle { assertEquals(42L, state.value.stats!!.totalRequests) }
    }
}
