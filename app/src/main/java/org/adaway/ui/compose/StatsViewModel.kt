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
) {
    val totalBlocked: Long
        get() = blockedImages + blockedScripts + blockedStyles + blockedFonts +
            blockedMedia + blockedApi + blockedTelemetry + blockedHeartbeat +
            blockedConfig + blockedWsSse + blockedOther

    companion object {
        fun fromJson(json: JSONObject?): ServerStats? {
            if (json == null) return null
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

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refreshServerStats()
            }
        }
    }

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

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}