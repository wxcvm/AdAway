package org.adaway.ui.compose

import org.json.JSONObject

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
     * only attributes them when the connection already knew its uid).
     * Failures alone do not identify certificate pinning or a retry cause.
     */
    val tlsOk: Long = 0,
    val tlsFail: Long = 0,
    /**
     * True while the server is refusing this uid's CONNECT requests without
     * interception under the user-enabled TLS retry policy. Its heuristic
     * does not prove which certificate or network condition caused failures.
     */
    val pinRefused: Boolean = false,
    /**
     * Requests per second measured by the server between two stats snapshots
     * (i.e. the observation window). A high rate is an activity signal, not
     * proof of a blocked-request retry loop.
     */
    val tps: Int = 0,
    val hasMeasuredRate: Boolean = false,
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
        if (hasMeasuredRate || tps > 0) tps.coerceAtLeast(0).toLong() else requestsPerSecond(uptimeSeconds)

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
 * did with it (0 = forwarding started, 1 = filtered, 2 = local exemption,
 * 3 = access rejected). A queued response is not proof of client receipt.
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
    /** Observed socket family; 0 means not recorded, including legacy rows. */
    val family: Int = 0,
    /** Origin-form path only, without query/fragment. Empty means not recorded. */
    val path: String = "",
    /** Local HTTP response queued by the server; 0 means not recorded/pending. */
    val httpStatus: Int = 0,
    val pathTruncated: Boolean = false,
    /** Ephemeral observation identity; never inferred from host/time. */
    val sequence: String = "",
    val generation: String = "",
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
    val topHostsRead: TopHostsRead = TopHostsRead.VALID,
    val queryLog: List<QueryLogEntry> = emptyList(),
    val history: List<HistPoint> = emptyList(),
    val daily: List<HistPoint> = emptyList(),
    val reportedTotalBlocked: Long? = null,
) {
    val totalBlocked: Long get() = reportedTotalBlocked ?: categorizedBlocked
    val uncategorizedBlocked: Long get() = (totalBlocked - categorizedBlocked).coerceAtLeast(0)
    val categorizedBlocked: Long
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
                        hasMeasuredRate = o.has("tps"),
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
                    // A corrupt optional recent row must not discard valid counters.
                    runCatching { QueryLogPage.parseEntry(o) }.getOrNull()?.let(queryLog::add)
                }
            }
            val topBlocked = mutableListOf<BlockedHost>()
            val topArray = json.optJSONArray("top_blocked")
            var topRead = when {
                !json.has("top_blocked") -> TopHostsRead.MISSING
                topArray == null -> TopHostsRead.MALFORMED
                else -> TopHostsRead.VALID
            }
            if (topArray != null) {
                for (i in 0 until topArray.length()) {
                    val row = topArray.optJSONObject(i)
                    val host = row?.opt("host") as? String
                    val count = parseRankingCount(row?.opt("count"))
                    if (host.isNullOrBlank() || count == null) {
                        topRead = TopHostsRead.MALFORMED
                        break
                    }
                    topBlocked += BlockedHost(host, count)
                }
            }
            val parsed = ServerStats(
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
                topHostsRead = topRead,
                queryLog = queryLog,
                history = history,
                daily = daily,
                reportedTotalBlocked = if (json.has("total_blocked")) json.optLong("total_blocked", -1) else null,
            )
            var classified = 0L
            for (count in listOf(parsed.blockedImages, parsed.blockedScripts, parsed.blockedStyles,
                parsed.blockedFonts, parsed.blockedMedia, parsed.blockedApi, parsed.blockedTelemetry,
                parsed.blockedHeartbeat, parsed.blockedConfig, parsed.blockedWsSse, parsed.blockedOther,
                parsed.blockedCrypto, parsed.blockedClickbait)) {
                if (count < 0 || count > Long.MAX_VALUE - classified) return null
                classified += count
            }
            if (parsed.reportedTotalBlocked != null && parsed.reportedTotalBlocked < parsed.categorizedBlocked) return null
            return parsed
        }
    }
}

