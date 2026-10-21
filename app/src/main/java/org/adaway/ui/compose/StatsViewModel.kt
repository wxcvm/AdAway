package org.adaway.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.db.AppDatabase
import org.adaway.db.entity.ListType
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.util.WebServerUtils
import org.json.JSONObject
import timber.log.Timber

/**
 * Per-app statistics from /internal-stats: which uid connected, made
 * requests, got blocked, and asked for SNI certificates.
 */
data class AppStat(
    val uid: Int,
    val connections: Long = 0,
    val requests: Long = 0,
    val blocked: Long = 0,
    val tlsHosts: Long = 0,
    /**
     * Completed / failed TLS handshakes for this uid (best effort: the server
     * only attributes them when the connection already knew its uid). A client
     * that pins certificates shows tlsFail >> tlsOk - that is the "who is
     * ignoring the block page and retrying forever" signal.
     */
    val tlsOk: Long = 0,
    val tlsFail: Long = 0,
    /**
     * True while the server is refusing this uid's CONNECT requests without
     * interception because it was detected pinning certificates (only ever set
     * when the user enabled that policy in the settings).
     */
    val pinRefused: Boolean = false,
    /**
     * Requests per second measured by the server between two stats snapshots
     * (i.e. the UI refresh period). This is the storm signal: a healthy app is
     * far below [STORM_TPS], while an app that is blocked and retries in a loop
     * sits far above it.
     */
    val tps: Int = 0,
    /**
     * What this uid is actually hitting (server-side, up to three hosts with
     * counts) - the missing half of "there is a storm": which host it hammers.
     */
    val hosts: List<Pair<String, Int>> = emptyList(),
) {
    /** Requests per second since the server started (0 when unknown). */
    fun requestsPerSecond(uptimeSeconds: Long): Long =
        if (uptimeSeconds > 0) requests / uptimeSeconds else 0L

    /**
     * Rate to display: the measured window rate when the server reported one,
     * otherwise the average since start (older servers had no tps field).
     */
    fun ratePerSecond(uptimeSeconds: Long): Long =
        if (tps > 0) tps.toLong() else requestsPerSecond(uptimeSeconds)

    /** Whether this app is retrying fast enough to be worth showing as a storm. */
    fun isStorming(uptimeSeconds: Long): Boolean = ratePerSecond(uptimeSeconds) >= STORM_TPS

    companion object {
        /**
         * Requests per second above which an app counts as storming.
         *
         * Measured on a real device: a system component hit ~240 req/s against a
         * host it could never reach, which is what this threshold is sized for -
         * 20/s is already ~1700 requests per minute, i.e. clearly a retry loop.
         */
        const val STORM_TPS = 20L
    }
}

/** A TLS (SNI) hostname requested by a uid — i.e. a per-domain cert effectively issued. */
data class TlsHost(
    val uid: Int,
    val host: String,
)

/**
 * One entry of the server's "top blocked hosts" ranking: how often the filter
 * actually blocked this host. (The old card reused [TlsHost] — the SNI names
 * apps asked certificates for — which is a different thing entirely.)
 */
data class BlockedHost(
    val host: String = "",
    val count: Long = 0,
)

/**
 * One entry of the server's query log: what was requested and what the filter
 * did with it (0 = proxied to the real server, 1 = blocked, 2 = allowed).
 */
data class QueryLogEntry(
    val ts: Long = 0,
    val uid: Int = -1,
    val action: Int = 0,
    /** Matched policy type (LT_* order), -1 = unknown / not a blocked request. */
    val type: Int = -1,
    /** How that type answers: 0 = placeholder, 1 = 204, 2 = passthrough. */
    val mode: Int = 0,
    val host: String = "",
)

/** One time bucket of web server activity (hourly or daily series). */
data class HistPoint(
    val ts: Long = 0,
    val requests: Long = 0,
    val blocked: Long = 0,
    val connections: Long = 0,
    val certs: Long = 0,
)

/** Parse a history/daily JSON array ({"ts":…,"requests":…, …}) into [HistPoint]s. */
private fun parseHistArray(json: JSONObject, key: String): List<HistPoint> {
    val list = mutableListOf<HistPoint>()
    val arr = json.optJSONArray(key) ?: return list
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        list += HistPoint(
            ts = o.optLong("ts", 0),
            requests = o.optLong("requests", 0),
            blocked = o.optLong("blocked", 0),
            connections = o.optLong("connections", 0),
            certs = o.optLong("certs", 0),
        )
    }
    return list
}

/**
 * Snapshot of the native web server statistics (from /internal-stats).
 */
data class ServerStats(
    val snapshotTs: Long = 0,
    val uptimeSeconds: Long = 0,
    val uptimeDays: Double = 0.0,
    val totalRequests: Long = 0,
    val totalConnections: Long = 0,
    val activeConnections: Int = 0,
    val tlsHandshakes: Long = 0,
    val tlsFailures: Long = 0,
    val sniCacheHits: Long = 0,
    val sniHitRate: Double = 0.0,
    val blockRate: Double = 0.0,
    val dailyPeak: Long = 0,
    val blockedImages: Long = 0,
    val blockedScripts: Long = 0,
    val blockedStyles: Long = 0,
    val blockedFonts: Long = 0,
    val blockedMedia: Long = 0,
    val blockedApi: Long = 0,
    val blockedTelemetry: Long = 0,
    val blockedHeartbeat: Long = 0,
    val blockedConfig: Long = 0,
    val blockedWsSse: Long = 0,
    val blockedOther: Long = 0,
    val blockedCrypto: Long = 0,
    val blockedClickbait: Long = 0,
    val sniCertsIssued: Long = 0,
    val blockImageCount: Int = 0,
    val apps: List<AppStat> = emptyList(),
    val recentTls: List<TlsHost> = emptyList(),
    val topBlocked: List<BlockedHost> = emptyList(),
    val queryLog: List<QueryLogEntry> = emptyList(),
    val history: List<HistPoint> = emptyList(),
    val daily: List<HistPoint> = emptyList(),
) {
    val totalBlocked: Long
        get() = blockedImages + blockedScripts + blockedStyles + blockedFonts +
            blockedMedia + blockedApi + blockedTelemetry + blockedHeartbeat +
            blockedConfig + blockedWsSse + blockedOther +
            blockedCrypto + blockedClickbait

    companion object {
        /**
         * Parse `hosts:[{h,n},…]` of one app: the domains the native server
         * attributed to that uid (see app_note_host() in webserver.c).
         */
        private fun parseAppHosts(o: JSONObject): List<Pair<String, Int>> {
            val arr = o.optJSONArray("hosts") ?: return emptyList()
            val out = ArrayList<Pair<String, Int>>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val name = h.optString("h", "")
                if (name.isEmpty()) continue
                out += name to h.optInt("n", 0)
            }
            return out
        }

        fun fromJson(json: JSONObject?): ServerStats? {
            if (json == null || !json.has("uptime_seconds") || !json.has("total_requests") ||
                json.optLong("uptime_seconds", -1) < 0 || json.optLong("total_requests", -1) < 0) return null
            val apps = mutableListOf<AppStat>()
            val appsArray = json.optJSONArray("apps")
            if (appsArray != null) {
                for (i in 0 until appsArray.length()) {
                    val o = appsArray.optJSONObject(i) ?: continue
                    apps += AppStat(
                        uid = o.optInt("uid", -1),
                        connections = o.optLong("connections", 0),
                        requests = o.optLong("requests", 0),
                        blocked = o.optLong("blocked", 0),
                        tlsHosts = o.optLong("tls_hosts", 0),
                        tlsOk = o.optLong("tls_ok", 0),
                        tlsFail = o.optLong("tls_fail", 0),
                        pinRefused = o.optBoolean("pin_refused", false),
                        tps = o.optInt("tps", 0),
                        hosts = parseAppHosts(o),
                    )
                }
            }
            val recentTls = mutableListOf<TlsHost>()
            val tlsArray = json.optJSONArray("recent_tls")
            if (tlsArray != null) {
                for (i in 0 until tlsArray.length()) {
                    val o = tlsArray.optJSONObject(i) ?: continue
                    recentTls += TlsHost(
                        uid = o.optInt("uid", -1),
                        host = o.optString("host", ""),
                    )
                }
            }
            val history = parseHistArray(json, "history")
            val daily = parseHistArray(json, "daily")
            val queryLog = mutableListOf<QueryLogEntry>()
            val qlogArray = json.optJSONArray("query_log")
            if (qlogArray != null) {
                for (i in 0 until qlogArray.length()) {
                    val o = qlogArray.optJSONObject(i) ?: continue
                    queryLog += QueryLogEntry(
                        ts = o.optLong("ts", 0),
                        uid = o.optInt("uid", -1),
                        action = o.optInt("action", 0),
                        // An older server publishes neither field: keep -1 so the
                        // UI shows "unknown" rather than guessing "图片".
                        type = if (o.has("type")) o.optInt("type", -1) else -1,
                        mode = o.optInt("mode", 0),
                        host = o.optString("host", ""),
                    )
                }
            }
            val topBlocked = mutableListOf<BlockedHost>()
            val topArray = json.optJSONArray("top_blocked")
            if (topArray != null) {
                for (i in 0 until topArray.length()) {
                    val o = topArray.optJSONObject(i) ?: continue
                    topBlocked += BlockedHost(
                        host = o.optString("host", ""),
                        count = o.optLong("count", 0),
                    )
                }
            }
            return ServerStats(
                snapshotTs = json.optLong("snapshot_ts", 0),
                uptimeSeconds = json.optLong("uptime_seconds", 0),
                uptimeDays = json.optDouble("uptime_days", 0.0),
                totalRequests = json.optLong("total_requests", 0),
                totalConnections = json.optLong("total_connections", 0),
                activeConnections = json.optInt("active_connections", 0),
                tlsHandshakes = json.optLong("tls_handshakes", 0),
                tlsFailures = json.optLong("tls_failures", 0),
                sniCacheHits = json.optLong("sni_cache_hits", 0),
                sniHitRate = json.optDouble("sni_hit_rate", 0.0),
                blockRate = json.optDouble("block_rate", 0.0),
                dailyPeak = json.optLong("daily_peak", 0),
                blockedImages = json.optLong("blocked_images", 0),
                blockedScripts = json.optLong("blocked_scripts", 0),
                blockedStyles = json.optLong("blocked_styles", 0),
                blockedFonts = json.optLong("blocked_fonts", 0),
                blockedMedia = json.optLong("blocked_media", 0),
                blockedApi = json.optLong("blocked_api", 0),
                blockedTelemetry = json.optLong("blocked_telemetry", 0),
                blockedHeartbeat = json.optLong("blocked_heartbeat", 0),
                blockedConfig = json.optLong("blocked_config", 0),
                blockedWsSse = json.optLong("blocked_ws_sse", 0),
                blockedOther = json.optLong("blocked_other", 0),
                blockedCrypto = json.optLong("blocked_crypto", 0),
                blockedClickbait = json.optLong("blocked_clickbait", 0),
                sniCertsIssued = json.optLong("sni_certs_issued", 0),
                blockImageCount = json.optInt("block_image_count", 0),
                apps = apps,
                recentTls = recentTls,
                topBlocked = topBlocked,
                queryLog = queryLog,
                history = history,
                daily = daily,
            )
        }
    }
}

/**
 * ViewModel for the new Compose UI: hosts-list statistics (Room) plus
 * native web server statistics polled from /internal-stats every 5s.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)

    // Hosts-list statistics (Room LiveData)
    val blockedHostCount: LiveData<Int> = database.hostsListItemDao().getBlockedHostCount()
    val allowedHostCount: LiveData<Int> = database.hostsListItemDao().getAllowedHostCount()
    val redirectHostCount: LiveData<Int> = database.hostsListItemDao().getRedirectHostCount()

    // Host entries DAO (for log type lookup)
    val hostEntryDao = database.hostEntryDao()

    // Hosts list DAO (for user-defined rules)
    val hostsListItemDao = database.hostsListItemDao()

    // Ad block model (state, web server enabled, etc.)
    val adBlockModel: AdBlockModel = (application as org.adaway.AdAwayApplication).getAdBlockModel()

    // Web server statistics (polled)
    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats: StateFlow<ServerStats?> = _serverStats
    // 统计获取状态：更新时间戳与错误标记（供 UI 状态横幅展示）
    val statsUpdatedAt = MutableStateFlow<Long>(0L)
    val statsError = MutableStateFlow<String?>(null)

    private var pollingJob: Job? = null
    private var statsRefreshJob: Job? = null
    private val lifecycleObserver = object : androidx.lifecycle.LifecycleEventObserver {
        override fun onStateChanged(
            source: androidx.lifecycle.LifecycleOwner,
            event: androidx.lifecycle.Lifecycle.Event,
        ) {
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    startPolling()
                    val app = getApplication<org.adaway.AdAwayApplication>()
                    if (org.adaway.ui.compose.isRealtimeEnabled(app)) startRealtime()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    pollingJob?.cancel()
                    statsRefreshJob?.cancel()
                    wsJob?.cancel()
                    wsJob = null
                }
                else -> {}
            }
        }
    }

    init {
        refreshServerStats()
        startPolling()
        // 若用户在设置页启用了实时推送，则自动连接 WebSocket
        val app = getApplication<org.adaway.AdAwayApplication>()
        if (org.adaway.ui.compose.isRealtimeEnabled(app)) {
            startRealtime()
        }
        // Pause background stats collection while app is backgrounded.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    /**
     * 启动周期性轮询（默认每 10s）。
     *
     * 轮询间隔从 5s 调到 10s 以降低内存/CPU 压力
     * （该应用曾因高频轮询被 ColorOS LMK 杀进程）。
     * 页面不可见时协程自动暂停（lifecycle-aware）。
     */
private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                refreshServerStats()
            }
        }
    }

    /*
     * WebSocket 实时推送（可选）：当用户在设置页启用 realtime_enabled
     * 时，连到 webserver 的 /internal-ws，服务器在每次拦截事件后推送
     * JSON 快照，替代/补充 10s 轮询。连接失败时自动回退轮询。
     */
    private var wsJob: Job? = null

    fun startRealtime() {
        if (wsJob?.isActive == true) return
        wsJob = viewModelScope.launch {
            val connectionJob = kotlinx.coroutines.currentCoroutineContext()[Job]!!
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url("http://[::ffff:127.0.0.1]:${org.adaway.util.WebServerUtils.getStatsHttpPort()}/internal-ws")
                .build()
            try {
                val ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        // NOTE: the native server only pushes after blocking events;
                        // with no traffic the WS stays silent and stats would freeze
                        // at null forever. Keep the 10s polling as a safety net
                        // (cheap nc probe); WS messages simply update faster.
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        try {
                            val parsed = ServerStats.fromJson(org.json.JSONObject(text)) ?: return
                            viewModelScope.launch {
                                if (wsJob === connectionJob && connectionJob.isActive &&
                                    shouldReplaceStats(_serverStats.value?.snapshotTs, statsUpdatedAt.value, parsed.snapshotTs, false)) {
                                    _serverStats.value = parsed
                                    statsUpdatedAt.value = System.currentTimeMillis()
                                    statsError.value = null
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Bad WS payload")
                        }
                    }

                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        // Polling already runs while visible; closing a background socket must not restart it.
                    }
                    override fun onFailure(
                        webSocket: okhttp3.WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        Timber.w(t, "WebSocket failed — falling back to polling")
                    }
                })
                // 保持连接直到协程取消
                try {
                    while (isActive) delay(1000)
                } finally {
                    ws.close(1000, null)
                }
            } catch (e: Exception) {
                Timber.w(e, "WebSocket connect failed")
            }
        }
    }

    fun stopRealtime() {
        wsJob?.cancel()
        wsJob = null
    }

    /**
     * 拉取一次 webserver 统计并更新 UI 状态。
     *
     * 通过 WebServerUtils.getStats()（toybox nc + v4-mapped）
     * 获取 JSON 快照，解析为 ServerStats 数据类后推送到
     * serverStats StateFlow，驱动统计页/概览页重组。
     */
    fun refreshServerStats() {
        if (statsRefreshJob?.isActive == true) return
        statsRefreshJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // OkHttp request must not run on the main dispatcher
                var fromSnapshot = false
                // 自己的 try：服务器不可达时可能抛异常，不能因此跳过快照回退
                var stats = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        ServerStats.fromJson(WebServerUtils.getStats())
                    } catch (e: Exception) {
                        Timber.w(e, "Live statistics unavailable")
                        null
                    }
                }
                if (stats == null) {
                    /*
                     * 统计不再依赖 web 服务器端口可用：服务器每 5 秒会把同一份
                     * JSON 落到 <files>/webserver/stats.json，端口被 AdGuard/系统
                     * 占用或服务器刚好没在跑时，直接读这份快照。
                     */
                    stats = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ServerStats.fromJson(WebServerUtils.readStatsSnapshot(context))
                    }
                    fromSnapshot = stats != null
                }
                if (stats != null) {
                    val replace = shouldReplaceStats(_serverStats.value?.snapshotTs, statsUpdatedAt.value, stats.snapshotTs, fromSnapshot)
                    if (replace) {
                        _serverStats.value = stats
                        statsUpdatedAt.value = if (fromSnapshot) stats.snapshotTs * 1000 else System.currentTimeMillis()
                    }
                    // "snapshot" 会让状态栏显示“最后一次更新 xx:xx（可能已过期）”
                    statsError.value = if (fromSnapshot) { if (replace) "snapshot" else "unreachable" } else null
                } else {
                    statsError.value = "unreachable"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh server stats")
                statsError.value = "error"
            }
        }
    }

    

    /** Cursor pins the original history; expired generations fail without clearing the UI. */
    suspend fun loadQueryLogPage(offset: Int, limit: Int, cursor: QueryLogCursor?): QueryLogPage =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val json = org.adaway.util.WebServerStats.getQueryLogPage(offset, limit, cursor?.generation, cursor?.anchor)
            QueryLogPage.parse(json, offset, cursor)
        }

    /**
     * Load user-defined rule entries (source_id == 1: manual whitelist,
     * blacklist and redirect rules) on a background thread.
     */
    fun loadUserRules(onResult: (List<org.adaway.db.entity.HostListItem>) -> Unit) {
        viewModelScope.launch {
            val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.getUserList()
            }
            onResult(items)
        }
    }

    /** A bounded page and its count share one committed database view. */
    suspend fun loadRulePage(type: Int, query: String, filter: Int, requestedPage: Int): RuleBrowserPage =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(filter in 0..3 && requestedPage >= 0)
            database.runInTransaction(java.util.concurrent.Callable {
                val total = hostsListItemDao.countBrowserRules(type, query, filter)
                val page = requestedPage.coerceAtMost(if (total == 0) 0 else (total - 1) / RULES_PAGE_SIZE)
                RuleBrowserPage(hostsListItemDao.browseRules(type, query, filter, RULES_PAGE_SIZE,
                    page * RULES_PAGE_SIZE), total, page)
            })
        }

    /**
     * Load hosts-list entries of the given type (0=BLOCKED, 1=ALLOWED,
     * 2=REDIRECTED), capped at [limit] rows, plus the total count.
     */
    fun loadRulesByType(
        type: Int,
        limit: Int,
        onResult: (items: List<org.adaway.db.entity.HostListItem>, total: Int) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.getListByType(type, limit) to hostsListItemDao.getCountByType(type)
            }
            onResult(result.first, result.second)
        }
    }

    /**
     * Search hosts-list entries of a type by substring (case-insensitive LIKE),
     * capped at [limit] rows plus the number of matches.
     */
    fun searchRules(
        type: Int,
        query: String,
        limit: Int,
        onResult: (items: List<org.adaway.db.entity.HostListItem>, total: Int) -> Unit,
    ) {
        val pattern = "%" + query.trim() + "%"
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.searchByType(type, pattern, limit) to
                    hostsListItemDao.countSearchByType(type, pattern)
            }
            onResult(result.first, result.second)
        }
    }

    /**
     * Load a map sourceId -> label for rule rows (1 = "user").
     */
    fun loadSourceLabels(onResult: (Map<Int, String>) -> Unit) {
        viewModelScope.launch {
            val labels = withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.hostsSourceDao().getAll().associate { it.id to it.label }
            }
            onResult(labels)
        }
    }

    /** True while a hosts sync (sources + apply) is in progress. */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _syncStatus = MutableStateFlow(HostsSyncStatus.IDLE)
    val syncStatus: StateFlow<HostsSyncStatus> = _syncStatus

    /** Load all rule sources (id != 1, i.e. everything but the user list). */
    fun loadSources(onResult: (List<org.adaway.db.entity.HostsSource>) -> Unit) {
        viewModelScope.launch {
            val sources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    database.hostsSourceDao().getAll()
                } catch (failure: Exception) {
                    Timber.w("Source list unavailable (%s)", failure.javaClass.simpleName)
                    null
                }
            }
            if (sources != null) onResult(sources)
        }
    }

    /** One-time local label repair; later user choices such as "raw" stay verbatim. */
    private fun migrateSourceNames() = synchronized(org.adaway.model.source.SourcePresentation::class.java) {
        val preferences = getApplication<Application>().getSharedPreferences("source-presentation", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("legacy-labels-repaired", false)) {
            database.runInTransaction {
                val dao = database.hostsSourceDao()
                for (source in dao.getAll()) if (source.id != 1) {
                    val label = org.adaway.model.source.SourcePresentation.legacyName(source.label, source.url)
                    if (label != source.label) dao.rename(source.id, label)
                }
            }
            check(preferences.edit().putBoolean("legacy-labels-repaired", true).commit())
        }
    }

    /** Local edits rebuild the active snapshot without requiring a source download. */
    fun setSourceEnabled(id: Int, enabled: Boolean, onDone: (Boolean) -> Unit) = editLocalRules({
        val dao = database.hostsSourceDao()
        dao.setSourceEnabled(id, enabled)
        dao.setSourceItemsEnabled(id, enabled)
    }, onDone)

    fun removeSource(source: org.adaway.db.entity.HostsSource, onDone: (Boolean) -> Unit) = editLocalRules({
        require(source.id != 1)
        database.hostsSourceDao().delete(source)
    }, onDone)

    fun removeRule(host: String, onDone: (Boolean) -> Unit) = editLocalRules({
        hostsListItemDao.deleteUserFromHost(host)
    }, onDone)

    private fun editLocalRules(edit: () -> Unit, onDone: (Boolean) -> Unit) {
        if (!_syncing.compareAndSet(false, true)) { onDone(false); return }
        _syncStatus.value = HostsSyncStatus.RUNNING
        viewModelScope.launch {
            var committed = false
            var failure = HostsSyncStatus.SAVE_FAILED
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val app = getApplication<org.adaway.AdAwayApplication>()
                    app.getSourceModel().editRules { edit() }
                    committed = true
                    failure = HostsSyncStatus.APPLY_FAILED
                    adBlockModel.apply()
                    failure = HostsSyncStatus.SERVER_REFRESH_FAILED
                    check(WebServerUtils.exportBlockList(app) >= 0)
                    exportExemptions(app)
                    if (WebServerUtils.isWebServerReachable(app)) check(WebServerUtils.reloadConfig())
                }
                _syncStatus.value = HostsSyncStatus.LOCAL_SUCCESS
            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncStatus.value = HostsSyncStatus.IDLE
                throw e
            } catch (e: Exception) {
                _syncStatus.value = failure
                Timber.w(e, "Local rule edit or application failed")
            } finally {
                _syncing.value = false
                onDone(committed)
            }
        }
    }

    /**
     * 添加一条用户自定义规则（source_id == 1）。
     * @param host    域名（不带通配符）
     * @param type    0=拦截 1=放行 2=重定向
     * @param redirection 重定向目标（type==2 时使用）
     */
    /**
     * 放行整个应用：与设置页"应用监控"里的放行开关共用同一状态，写进
     * allowlist.txt，服务器随后不再拦截该 uid。
     */
    fun allowAppForever(uid: Int) {
        setAppAllowed(getApplication<Application>(), uid, true)
    }

    /**
     * 把规则库里的 ALLOWED 条目导出成原生读取的 `domain_allowlist.txt`。
     *
     * <p>放在 ViewModel 而不是 WebServerUtils：后者是纯 JVM 工具类，一旦在那里引用
     * Room 的 DAO，JVM 单元测试环境里没有生成的实现，抛出的往往是 `Error` 而不是
     * `Exception`（普通 catch 兜不住），会把与导出无关的测试一起带崩。这里在协程的
     * IO 上下文里调用，并显式兜住 `Throwable`：导出失败只是豁免名单不刷新，不该影响
     * 规则本身的写入。</p>
     */
    private suspend fun exportExemptions(app: Application) {
        try {
            val allowed = hostsListItemDao.getListByType(
                org.adaway.db.entity.ListType.ALLOWED.value,
                10000,
            )
            val file = java.io.File(
                org.adaway.util.WebServerUtils.getResourcePath(app).toFile(),
                "domain_allowlist.txt",
            )
            file.parentFile?.mkdirs()
            org.adaway.model.source.AtomicRuleFile.write(file) { writer ->
                writer.write("# 由 ADBlock 生成：按域名解除过滤（原生服务器每次请求都会重读）\n")
                for (item in allowed) {
                    val host = org.adaway.model.rules.ExemptionRules.normalize(item.host.orEmpty()) ?: continue
                    if (item.isEnabled && host.isNotEmpty() && !host.startsWith("#")) {
                        writer.write(host)
                        writer.write("\n")
                    }
                }
            }
            Timber.i("Exported %d exemption(s) to %s", allowed.size, file.absolutePath)
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Failed to export the domain allow list")
        }
    }

    /**
     * 只放行一个域：写规则库（ALLOWED，单一事实来源），随后导出豁免名单。
     * 原生服务器每请求重读 domain_allowlist.txt，所以立即生效。
     */
    fun allowHost(host: String, onDone: () -> Unit = {}) {
        addExemptionRules(listOf(host)) { onDone() }
    }

    /**
     * 批量加入"解除过滤"规则（多语法输入已由 ExemptionRules.parseAll 归一化）。
     *
     * @param hosts  规范化后的域列表。
     * @param onDone 实际写入的条数。
     */
    fun addExemptionRules(hosts: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val added = withContext(kotlinx.coroutines.Dispatchers.IO) {
                var count = 0
                for (host in hosts) {
                    try {
                        val item = org.adaway.db.entity.HostListItem()
                        item.host = host
                        item.type = org.adaway.db.entity.ListType.ALLOWED
                        item.sourceId = 1
                        hostsListItemDao.insert(item)
                        hostEntryDao.allowHost(host)
                        count++
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to add exemption %s", host)
                    }
                }
                if (count > 0) {
                    val app = getApplication<Application>()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    exportExemptions(app)
                }
                count
            }
            onDone(added)
        }
    }

    /**
     * 应用自定义重定向地址（hosts 档）：写偏好 → 重新生成 hosts → 导出规则 → 重载
     * 服务器配置，并撤销劫持规则（自定义地址不依赖劫持，也就不会占用整机流量）。
     */
    fun applyCustomAddresses(ipv4: String, ipv6: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    org.adaway.util.BlockMode.applyCustom(app, ipv4.trim(), ipv6.trim())
                    adBlockModel.apply()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    org.adaway.util.WebServerUtils.reloadConfig()
                    org.adaway.model.root.HijackModel.disable()
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to apply the custom redirection target")
                    false
                }
            }
            onDone(ok)
        }
    }

    /**
     * 新增用户规则，并把成功/失败交给调用方。
     *
     * <p>此前回调不带结果：重复规则、域名格式非法或数据库写入失败时，界面只会看到对话框
     * 关闭（用户以为添加成功）。改签名而不是复制一份实现 —— Kotlin 允许把无参 lambda 传给
     * 单参函数类型（忽略 it 即可），所以既有调用点不需要改动。</p>
     */
    fun addUserRule(host: String, type: Int, redirection: String?, onDone: (Boolean) -> Unit) = editLocalRules({
        val normalized = host.trim().lowercase(java.util.Locale.ROOT).removeSuffix(".")
        val wildcard = normalized.contains('*') || normalized.contains('?')
        require(if (wildcard) type != ListType.BLOCKED.value && org.adaway.util.RegexUtils.isValidWildcardHostname(normalized)
            else org.adaway.util.RegexUtils.isValidHostname(normalized))
        val item = org.adaway.db.entity.HostListItem().apply {
            this.host = normalized
            this.type = ListType.fromValue(type)
            sourceId = 1
            isEnabled = true
            if (type == ListType.REDIRECTED.value) {
                require(!redirection.isNullOrBlank() && org.adaway.util.RegexUtils.isValidIP(redirection.trim()))
                this.redirection = redirection.trim()
            }
        }
        // One personal action per name: replacing it retains the documented later-write priority.
        hostsListItemDao.deleteUserFromHost(normalized)
        hostsListItemDao.insert(item)
    }, onDone)

    /**
     * 添加一个规则源：远程订阅链接（https://…）或本地规则文件（content://…）。
     *
     * 本地文件通过 SAF 选择，调用方必须已调用 takePersistableUriPermission()
     * 获得持久化读权限，否则同步时会读取失败。
     *
     * @param url   订阅链接或本地文件 URI
     * @param label 显示名称（本地文件使用文件名；为空时按 URL 推导）
     * @param onDone 结果回调（false = 链接非法、已存在或未能写入）
     */
    fun addSource(url: String, label: String? = null, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    val normalized = url.trim()
                    if (label != null && (label.trim().length > 80 || label.any { it.isISOControl() })) {
                        return@withContext false
                    }
                    if (!org.adaway.db.entity.HostsSource.isValidUrl(normalized)) {
                        Timber.w("Rejected hosts source: unsupported URL format")
                        return@withContext false
                    }
                    val dao = database.hostsSourceDao()
                    val source = org.adaway.db.entity.HostsSource()
                    source.setUrl(normalized)
                    source.setLabel(
                        label?.takeIf { it.isNotBlank() } ?: deriveSourceLabel(normalized),
                    )
                    dao.insert(source) > 0
                } catch (e: Exception) {
                    // 唯一索引冲突（源已存在）等：返回 false 由界面提示
                    Timber.w("Failed to add source (%s)", e.javaClass.simpleName)
                    false
                }
            }
            onDone(ok)
        }
    }

    private fun deriveSourceLabel(url: String) = org.adaway.model.source.SourcePresentation.name(null, url)

    fun renameSource(id: Int, label: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val name = label.trim()
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() } &&
                        database.hostsSourceDao().rename(id, name) == 1
                } catch (failure: Exception) {
                    Timber.w("Source rename failed (%s)", failure.javaClass.simpleName)
                    false
                }
            }
            onDone(ok)
        }
    }

    /** Synchronize enabled sources and report the phase that failed to the UI. */
    fun syncHosts() {
        if (!_syncing.compareAndSet(false, true)) return
        _syncStatus.value = HostsSyncStatus.RUNNING
        viewModelScope.launch {
            var failure = HostsSyncStatus.SOURCE_FAILED
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val application = getApplication<org.adaway.AdAwayApplication>()
                    val sourceModel = application.getSourceModel()
                    sourceModel.retrieveHostsSources()
                    failure = HostsSyncStatus.APPLY_FAILED
                    adBlockModel.apply()
                    failure = HostsSyncStatus.SERVER_REFRESH_FAILED
                    check(WebServerUtils.exportBlockList(application) >= 0)
                    // A stopped optional server does not make a Hosts sync fail.
                    if (WebServerUtils.isWebServerReachable(application)) {
                        check(WebServerUtils.reloadConfig())
                    }
                }
                _syncStatus.value = HostsSyncStatus.SUCCESS
                Timber.i("Hosts sync completed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncStatus.value = HostsSyncStatus.IDLE
                throw e
            } catch (e: Exception) {
                _syncStatus.value = failure
                Timber.w(e, "Hosts sync failed")
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 切换拦截模式（[org.adaway.util.BlockMode.LOCALHOST] = 127.0.0.1 本机拦截页，
     * [org.adaway.util.BlockMode.NULL_ROUTE] = 0.0.0.0 空路由），并立即重新生成
     * hosts 文件使其生效。
     */
    fun applyBlockMode(mode: Int, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    if (mode == org.adaway.util.BlockMode.HIJACK) {
                        /*
                         * Refuse a new transparent hijack before changing
                         * persisted block mode or rewriting the hosts file.
                         * VPN ownership is independent of whether AdGuard is
                         * merely installed. Unknown is not authorization.
                         */
                        val owner = org.adaway.model.traffic.AndroidVpnObserver.read(app, false)
                        if (owner != org.adaway.model.traffic.TrafficModePolicy.VpnOwner.NONE) {
                            Timber.w("Cannot enable hijack while VPN owner is %s", owner)
                            return@withContext false
                        }
                    }
                    org.adaway.util.BlockMode.apply(app, mode)
                    // 1) 先把新目标地址写进 hosts 并生效，再导出规则文件让
                    //    服务器重读（透明代理直接按导出规则过滤）
                    adBlockModel.apply()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    org.adaway.util.WebServerUtils.reloadConfig()
                    if (mode == org.adaway.util.BlockMode.HIJACK) {
                        // 2) 劫持模式：确保服务器（--proxy-filter）在运行，
                        //    再挂上 iptables 重定向规则；服务器不在就不劫持，
                        //    避免把整机流量导到没人监听的口上。
                        if (!org.adaway.util.WebServerUtils.isWebServerRunning()) {
                            org.adaway.util.WebServerUtils.startWebServer(app)
                            kotlinx.coroutines.delay(2000)
                        }
                        if (!org.adaway.util.WebServerUtils.isWebServerRunning()) {
                            Timber.w("Hijack mode: web server is not running, rules not installed")
                            false
                        } else {
                            org.adaway.model.root.HijackModel.enable(app)
                        }
                    } else {
                        // 其它模式：立刻撤销劫持规则，恢复正常上网
                        org.adaway.model.root.HijackModel.disable()
                        true
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to apply blocking mode %d", mode)
                    false
                }
            }
            onDone(ok)
        }
    }

    /**
     * 本机规则统计（拦截/放行/重定向 条数）。
     *
     * 服务器没在运行时用它在统计页兜底显示，页面不会因为“拿不到服务器数据”
     * 而完全空白；数据直接来自 Room，不产生任何网络/进程开销。
     */
    fun loadLocalHostCounts(onResult: (Int, Int, Int) -> Unit) {
        viewModelScope.launch {
            val counts = withContext(kotlinx.coroutines.Dispatchers.IO) {
                Triple(
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.BLOCKED.value),
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.ALLOWED.value),
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.REDIRECTED.value),
                )
            }
            onResult(counts.first, counts.second, counts.third)
        }
    }

    /** 当前拦截模式：0=127.0.0.1 本机拦截，1=0.0.0.0 空路由，2=自定义。 */
    fun currentBlockMode(): Int = org.adaway.util.BlockMode.current(getApplication())

    /**
     * 检查应用更新（更新源：GitHub Releases / wxcvm/AdAway）。
     *
     * @param force 为 true 时忽略 30 分钟的结果缓存
     * @param onResult 回调（新版本对象, 失败原因）；失败时对象为 null
     */
    fun checkUpdate(
        force: Boolean,
        onResult: (org.adaway.model.update.Manifest?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val model = (app as org.adaway.AdAwayApplication).getUpdateModel()
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    model.checkForUpdate(force)
                } catch (e: Exception) {
                    Timber.w(e, "Update check failed")
                    null
                }
            }
            onResult(result, if (result == null) model.lastError else null)
        }
    }

    /** 下载最新版本 APK（校验签名/版本后由系统安装器安装）。 */
    fun downloadUpdate(): Long {
        val app = getApplication<Application>()
        return (app as org.adaway.AdAwayApplication).getUpdateModel().update()
    }

    override fun onCleared() {
        pollingJob?.cancel()
        wsJob?.cancel()
        wsJob = null
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onCleared()
    }
}
