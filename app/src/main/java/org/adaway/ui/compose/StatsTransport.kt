package org.adaway.ui.compose

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.*
import org.adaway.util.WebServerStats
import org.adaway.util.WebServerUtils
import org.json.JSONObject
import java.io.IOException

/** One client for the observer lifetime; socket callbacks feed one bounded latest-value slot. */
internal class AndroidStatsTransport(
    context: Context,
    private val port: () -> Int = { WebServerUtils.getStatsHttpPort() },
    private val snapshot: () -> JSONObject? = { WebServerUtils.readStatsSnapshot(context.applicationContext) },
) : StatsTransport {
    private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build()
    override suspend fun readLive() = withContext(Dispatchers.IO) {
        ServerStats.fromJson(WebServerStats.getStats(port()))
    }
    override suspend fun readSnapshot() = withContext(Dispatchers.IO) {
        ServerStats.fromJson(snapshot())
    }
    private sealed interface Push {
        data object Open : Push
        data class Text(val value: String) : Push
    }
    override suspend fun collectPush(connected: () -> Unit, receive: (ServerStats) -> Unit) {
        val events = Channel<Push>(Channel.CONFLATED)
        val request = Request.Builder()
            .url("http://[::ffff:127.0.0.1]:" + port() + "/internal-ws").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Push.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Push.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                events.close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.close(IOException("Statistics push disconnected", t))
            }
        })
        try {
            for (event in events) when (event) {
                Push.Open -> connected()
                is Push.Text -> {
                    val parsed = runCatching { ServerStats.fromJson(JSONObject(event.value)) }.getOrNull()
                    if (parsed != null) receive(parsed)
                }
            }
        } finally { events.cancel(); socket.cancel() }
    }
    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
