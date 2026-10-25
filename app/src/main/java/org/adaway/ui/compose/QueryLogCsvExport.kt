package org.adaway.ui.compose

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Owns both staging and final paths before suspension: cancellation cannot orphan a completed CSV. */
internal suspend fun withQueryLogCsvExport(cacheDirectory: File, entries: List<QueryLogEntry>,
    share: suspend (File) -> Unit) {
    require(entries.size <= 10000)
    var temporary: File? = null
    var completed: File? = null
    var published = false
    var shared = false
    try {
        withContext(Dispatchers.IO) {
            val stage = File.createTempFile("adblock-query-log-", ".csv.part", cacheDirectory)
            temporary = stage
            val output = File(stage.parentFile, stage.name.removeSuffix(".part"))
            completed = output
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            stage.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("time,result,type_code,type_name,mode_code,mode_name,uid,host,ip_family,path,http_status,path_truncated,generation,sequence\n")
                entries.forEach { entry ->
                    coroutineContext.ensureActive()
                    writer.write(queryLogCsvRow(entry, timeFmt))
                }
            }
            coroutineContext.ensureActive()
            check(!output.exists() && stage.renameTo(output)) { "Could not publish completed export" }
            published = true
        }
        coroutineContext.ensureActive()
        share(checkNotNull(completed))
        shared = true
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            val stageRemoved = temporary?.let { !it.exists() || it.delete() } ?: true
            val completedRemoved = if (published && !shared) completed?.let { !it.exists() || it.delete() } ?: true else true
            check(stageRemoved && completedRemoved) { "Could not remove incomplete export" }
        }
    }
}

private const val ACTION_PROXY = 0
private const val ACTION_BLOCK = 1
private const val ACTION_ALLOW = 2
private const val ACTION_REJECT = 3

private fun csvTypeName(type: Int): String = when (type) {
    0 -> "images"
    1 -> "scripts"
    2 -> "styles"
    3 -> "fonts"
    4 -> "media"
    5 -> "structure"
    6 -> "api"
    7 -> "telemetry"
    8 -> "config"
    9 -> "ws_sse"
    else -> ""
}

private fun csvModeName(mode: Int): String = when (mode) {
    0 -> "placeholder"
    1 -> "204"
    2 -> "allowed"
    3 -> "empty_response"
    4 -> "domain_response"
    else -> "unknown"
}

internal fun queryLogCsvRow(entry: QueryLogEntry, formatter: SimpleDateFormat): String {
    val time = if (entry.ts in 1..Long.MAX_VALUE / 1000) formatter.format(Date(entry.ts * 1000L)) else ""
    val result = when (entry.action) {
        ACTION_BLOCK -> "blocked"
        ACTION_ALLOW -> "local_handled"
        ACTION_PROXY -> "forwarding_started"
        ACTION_REJECT -> "access_denied"
        else -> "unknown"
    }
    return listOf(time, result, if (entry.type >= 0) entry.type.toString() else "", csvTypeName(entry.type),
        if (entry.action == ACTION_BLOCK) entry.mode.toString() else "",
        if (entry.action == ACTION_BLOCK) csvModeName(entry.mode) else "",
        if (entry.uid >= 0) entry.uid.toString() else "", entry.host,
        if (entry.family == 4 || entry.family == 6) "IPv${entry.family}" else "unknown", entry.path,
        if (entry.httpStatus in 100..599) entry.httpStatus.toString() else "", entry.pathTruncated.toString(), entry.generation, entry.sequence)
        .joinToString(",") { csvField(it) } + "\n"
}

/** CSV 里引号、逗号和换行都要转义，否则主机名带逗号就会错列。 */
private fun csvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}
