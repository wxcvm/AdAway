package org.adaway.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Server-filtered history, independent of recomposition and navigation. */
internal data class QueryLogBrowserState(
    val entries: List<QueryLogEntry> = emptyList(), val page: QueryLogPage? = null,
    val busy: Boolean = false, val failed: Boolean = false, val live: Boolean = true,
    val filter: Int = -1, val query: String = "", val uid: Int? = null,
    val family: Int? = null, val type: Int? = null,
    val loadedFilter: QueryLogFilter? = null, val previousConditions: Boolean = false,
    val upgradeRequired: Boolean = false, val exporting: Boolean = false,
    val exported: Int = 0, val exportTotal: Int = 0, val exportFailed: Boolean = false,
) {
    val conditions: QueryLogFilter get() = QueryLogFilter(filter.takeIf { it != -1 }, family, type, uid, query)
    val invalidQuery: Boolean get() = conditions.validationError
    // Never filter an already-loaded page locally: these rows belong to loadedFilter.
    val shown: List<QueryLogEntry> get() = entries
}

internal typealias QueryLogLoader = suspend (Int, Int, QueryLogCursor?, QueryLogFilter) -> QueryLogPage

internal class QueryLogBrowser(
    private val scope: CoroutineScope,
    private val load: QueryLogLoader,
    private val nanoTime: () -> Long = System::nanoTime,
    private val filterDelayMs: Long = 300,
) {
    private val mutable = MutableStateFlow(QueryLogBrowserState())
    val state: StateFlow<QueryLogBrowserState> = mutable
    private var job: Job? = null
    private var exportJob: Job? = null
    private var exportRevision = 0L
    private var revision = 0L
    private var lastRefreshAt: Long? = null

    fun setFilter(value: Int) { if (value in -1..3 && value != mutable.value.filter) change(mutable.value.copy(filter = value)) }
    fun setQuery(value: String) { if (value != mutable.value.query) change(mutable.value.copy(query = value)) }
    fun setUid(value: Int?) { if ((value == null || value >= -1) && value != mutable.value.uid) change(mutable.value.copy(uid = value)) }
    fun setFamily(value: Int?) { if ((value == null || value in listOf(0, 4, 6)) && value != mutable.value.family) change(mutable.value.copy(family = value)) }
    fun setType(value: Int?) { if ((value == null || value in -1..65534) && value != mutable.value.type) change(mutable.value.copy(type = value)) }

    private fun change(next: QueryLogBrowserState) {
        revision++
        job?.cancel()
        cancelExport()
        val before = next.copy(live = false, busy = !next.invalidQuery, failed = false,
            upgradeRequired = false, previousConditions = next.page != null, exporting = false)
        mutable.value = before
        if (before.invalidQuery) return
        val token = revision
        job = scope.launch {
            delay(filterDelayMs)
            if (token == revision) readPage(before, false, token)
        }
    }

    fun setLive(value: Boolean) {
        revision++
        job?.cancel()
        mutable.value = mutable.value.copy(live = value, busy = false)
        if (value) refresh()
    }
    fun refreshIfLive() {
        val last = lastRefreshAt
        if (mutable.value.live && !mutable.value.busy &&
            (last == null || nanoTime() - last >= 10_000_000_000L)) fetch(false)
    }
    fun refresh() { fetch(false) }
    fun more() {
        val current = mutable.value
        if (current.busy || current.previousConditions || current.page?.hasMore == false) return
        mutable.value = current.copy(live = false)
        fetch(current.page != null)
    }
    private fun fetch(older: Boolean) {
        val before = mutable.value
        if (before.invalidQuery || (older && before.busy)) return
        val token = ++revision
        lastRefreshAt = nanoTime()
        job?.cancel()
        mutable.value = before.copy(busy = true, failed = false, upgradeRequired = false)
        job = scope.launch { readPage(before, older, token) }
    }
    private suspend fun readPage(before: QueryLogBrowserState, older: Boolean, token: Long) {
        try {
            val conditions = before.conditions
            val page = load(if (older) before.page!!.nextOffset else 0, 300,
                if (older) before.page!!.cursor else null, conditions)
            require(page.filterVersion == 1) { "Filtered history capability missing" }
            require(page.cursor.queryKey == conditions.queryKey()) { "Query conditions changed" }
            if (older) {
                require(page.cursor == before.page!!.cursor && page.total == before.page.total && page.retainedTotal == before.page.retainedTotal)
                require(before.entries.lastOrNull()?.let { old -> page.entries.firstOrNull()?.let {
                    QueryLogPage.compareSequence(old.sequence, it.sequence) > 0
                } } != false) { "Repeated observations" }
            }
            if (token == revision) mutable.value = mutable.value.copy(
                entries = if (older) before.entries + page.entries else page.entries,
                page = page, loadedFilter = conditions, previousConditions = false,
                busy = false, failed = false, upgradeRequired = false)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (token == revision) mutable.value = mutable.value.copy(busy = false, failed = true,
                upgradeRequired = error is QueryLogUpgradeRequired)
        } finally { if (token == revision) mutable.value = mutable.value.copy(busy = false) }
    }

    fun exportAll(onComplete: suspend (List<QueryLogEntry>) -> Unit) {
        val before = mutable.value
        val page = before.page ?: return
        if (before.busy || before.previousConditions || before.invalidQuery || before.exporting || page.filterVersion != 1) return
        mutable.value = before.copy(exporting = true, exported = 0, exportTotal = page.total, exportFailed = false)
        val exportToken = ++exportRevision
        exportJob = scope.launch {
            try {
                val rows = collectQueryLogSnapshot(page.cursor, before.conditions, page.total, load) { count ->
                    if (exportToken == exportRevision) mutable.value = mutable.value.copy(exported = count)
                }
                coroutineContext.ensureActive()
                onComplete(rows)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (exportToken == exportRevision) mutable.value = mutable.value.copy(exportFailed = true)
            } finally { if (exportToken == exportRevision) mutable.value = mutable.value.copy(exporting = false) }
        }
    }
    fun cancelExport() { exportRevision++; exportJob?.cancel(); mutable.value = mutable.value.copy(exporting = false) }
}

/** No output is publishable before all pages of the same snapshot have completed. */
internal suspend fun collectQueryLogSnapshot(cursor: QueryLogCursor, filter: QueryLogFilter,
    total: Int, load: QueryLogLoader, onProgress: (Int) -> Unit): List<QueryLogEntry> {
    require(total in 0..10000 && cursor.queryKey == filter.queryKey())
    val rows = ArrayList<QueryLogEntry>(total)
    var offset = 0
    var retained: Int? = null
    do {
        coroutineContext.ensureActive()
        val page = load(offset, 300, cursor, filter)
        require(page.filterVersion == 1 && page.cursor == cursor && page.total == total &&
            page.nextOffset == offset + page.entries.size && page.nextOffset <= total &&
            page.hasMore == (page.nextOffset < total) && (!page.hasMore || page.entries.isNotEmpty()))
        require(retained == null || retained == page.retainedTotal)
        retained = page.retainedTotal
        for (entry in page.entries) {
            require(entry.generation == cursor.generation && QueryLogPage.validSequence(entry.sequence))
            require(rows.lastOrNull()?.let { QueryLogPage.compareSequence(it.sequence, entry.sequence) > 0 } != false)
            rows.add(entry)
        }
        offset = page.nextOffset
        onProgress(offset)
    } while (page.hasMore)
    coroutineContext.ensureActive()
    require(rows.size == total)
    return rows
}
