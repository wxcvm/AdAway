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
)

/** A TLS (SNI) hostname requested by a uid — i.e. a per-domain cert effectively issued. */
data class TlsHost(
    val uid: Int,
    val host: String,
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
    val uptimeSeconds: Long = 0,
    val totalRequests: Long = 0,
    val totalConnections: Long = 0,
    val activeConnections: Int = 0,
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
    val sniCertsIssued: Long = 0,
    val blockImageCount: Int = 0,
    val apps: List<AppStat> = emptyList(),
    val recentTls: List<TlsHost> = emptyList(),
    val history: List<HistPoint> = emptyList(),
    val daily: List<HistPoint> = emptyList(),
) {
    val totalBlocked: Long
        get() = blockedImages + blockedScripts + blockedStyles + blockedFonts +
            blockedMedia + blockedApi + blockedTelemetry + blockedHeartbeat +
            blockedConfig + blockedWsSse + blockedOther

    companion object {
        fun fromJson(json: JSONObject?): ServerStats? {
            if (json == null) return null
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
            return ServerStats(
                uptimeSeconds = json.optLong("uptime_seconds", 0),
                totalRequests = json.optLong("total_requests", 0),
                totalConnections = json.optLong("total_connections", 0),
                activeConnections = json.optInt("active_connections", 0),
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
                sniCertsIssued = json.optLong("sni_certs_issued", 0),
                blockImageCount = json.optInt("block_image_count", 0),
                apps = apps,
                recentTls = recentTls,
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

    private var pollingJob: Job? = null

    /** True when the web server executable is running. */
    val webServerRunning: Boolean
        get() = org.adaway.util.WebServerUtils.isWebServerRunning()

    init {
        refreshServerStats()
        startPolling()
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

    /**
     * 拉取一次 webserver 统计并更新 UI 状态。
     *
     * 通过 WebServerUtils.getStats()（toybox nc + v4-mapped）
     * 获取 JSON 快照，解析为 ServerStats 数据类后推送到
     * serverStats StateFlow，驱动统计页/概览页重组。
     */
fun refreshServerStats() {
        viewModelScope.launch {
            try {
                // OkHttp request must not run on the main dispatcher
                _serverStats.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ServerStats.fromJson(WebServerUtils.getStats())
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh server stats")
                _serverStats.value = null
            }
        }
    }

    /**
     * Load DNS log entries with their block-list type on a background
     * thread (Room forbids main-thread queries), then invoke [onResult].
     */
    fun refreshLogEntries(onResult: (List<Pair<String, ListType?>>) -> Unit) {
        viewModelScope.launch {
            val logs = withContext(kotlinx.coroutines.Dispatchers.IO) {
                adBlockModel.getLogs().map { host -> host to hostEntryDao.getTypeOfHost(host) }
            }
            onResult(logs)
        }
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

    /** Load all rule sources (id != 1, i.e. everything but the user list). */
    fun loadSources(onResult: (List<org.adaway.db.entity.HostsSource>) -> Unit) {
        viewModelScope.launch {
            val sources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.hostsSourceDao().getAll()
            }
            onResult(sources)
        }
    }

    /** Toggle a subscription's enabled state (source + its items). */
    fun toggleSource(source: org.adaway.db.entity.HostsSource, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dao = database.hostsSourceDao()
                dao.setSourceEnabled(source.id, !source.isEnabled())
                dao.setSourceItemsEnabled(source.id, !source.isEnabled())
            }
            onDone()
        }
    }

    /**
     * 删除一条用户自定义规则（source_id == 1 的手动规则）。
     * 使用 HostListItemDao.deleteUserFromHost() 清理 hosts_lists，
     * 并用 HostEntryDao.allowHost() 从生效的 host_entries 中移除该域名，
     * 随后重新同步 hosts 文件使变更生效。
     */
    fun removeRule(host: String, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.deleteUserFromHost(host)
                hostEntryDao.allowHost(host)
            }
            onDone()
        }
    }

    /** Add a new subscription from a hosts URL; returns false on failure. */
    fun addSource(url: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dao = database.hostsSourceDao()
                    val source = org.adaway.db.entity.HostsSource()
                    source.setUrl(url.trim())
                    source.setLabel(deriveSourceLabel(url))
                    dao.insert(source)
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to add source %s", url)
                    false
                }
            }
            onDone(ok)
        }
    }

    /** Best-effort human label for a subscription URL (host of the URL). */
    private fun deriveSourceLabel(url: String): String {
        return try {
            val u = java.net.URI(url)
            u.host?.substringBefore('.') ?: url.take(24)
        } catch (e: Exception) {
            url.take(24)
        }
    }

    /**
     * Synchronize hosts: enable all sources, download them and apply the
     * hosts file. Mirrors the legacy HomeViewModel.sync() flow, kept
     * off the main thread. Errors are logged; the UI reflects failure by
     * clearing the syncing flag (the overview row stays actionable).
     */
    fun syncHosts() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val application = getApplication<org.adaway.AdAwayApplication>()
                    val sourceModel = application.getSourceModel()
                    sourceModel.enableAllSources()
                    sourceModel.retrieveHostsSources()
                    adBlockModel.apply()
                }
                Timber.i("Hosts sync completed")
            } catch (e: Exception) {
                Timber.w(e, "Hosts sync failed")
            } finally {
                _syncing.value = false
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}