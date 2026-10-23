package org.adaway.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One pinned history, independent of recomposition and navigation. All calls use the UI dispatcher. */
internal data class QueryLogBrowserState(
    val entries: List<QueryLogEntry> = emptyList(),
    val page: QueryLogPage? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val live: Boolean = true,
    val filter: Int = -1,
    val query: String = "",
    val uid: Int? = null,
    val family: Int? = null,
    val type: Int? = null,
) {
    val shown: List<QueryLogEntry>
        get() = entries.filter {
            (filter == -1 || it.action == filter) && (uid == null || it.uid == uid) &&
                (family == null || it.family == family) && (type == null || it.type == type) &&
                it.host.contains(query.trim(), ignoreCase = true)
        }
}

internal class QueryLogBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (Int, Int, QueryLogCursor?) -> QueryLogPage,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutable = MutableStateFlow(QueryLogBrowserState())
    val state: StateFlow<QueryLogBrowserState> = mutable
    private var job: Job? = null
    private var revision = 0L
    private var lastRefreshAt: Long? = null

    fun setFilter(value: Int) {
        if (value !in -1..2 || value == mutable.value.filter) return
        pauseForFiltering()
        mutable.value = mutable.value.copy(filter = value)
    }
    fun setQuery(value: String) {
        val bounded = value.take(191)
        if (bounded == mutable.value.query) return
        pauseForFiltering()
        mutable.value = mutable.value.copy(query = bounded)
    }
    fun setUid(value: Int?) {
        if (value == mutable.value.uid || (value != null && value < 0)) return
        pauseForFiltering()
        mutable.value = mutable.value.copy(uid = value)
    }

    fun setFamily(value: Int?) {
        if (value == mutable.value.family || (value != null && value !in listOf(0, 4, 6))) return
        pauseForFiltering()
        mutable.value = mutable.value.copy(family = value)
    }

    fun setType(value: Int?) {
        if (value == mutable.value.type || (value != null && value !in -1..9)) return
        pauseForFiltering()
        mutable.value = mutable.value.copy(type = value)
    }

    private fun pauseForFiltering() {
        // Keep the first response: typing while the initial page loads must not
        // strand an empty workbench. Later pages are already inspectable.
        if (mutable.value.page == null && mutable.value.busy)
            mutable.value = mutable.value.copy(live = false)
        else setLive(false)
    }

    fun setLive(value: Boolean) {
        // A response already in flight must not replace the page after Pause.
        revision++
        job?.cancel()
        mutable.value = mutable.value.copy(live = value, busy = false)
        if (value) refresh()
    }

    fun refreshIfLive() {
        val last = lastRefreshAt
        if (mutable.value.live && !mutable.value.busy &&
            (last == null || nanoTime() - last >= 10_000_000_000L)) fetch(older = false)
    }

    fun refresh() { fetch(older = false) }

    fun more() {
        val current = mutable.value
        if (current.busy || current.page?.hasMore == false) return
        mutable.value = current.copy(live = false)
        fetch(older = current.page != null)
    }

    private fun fetch(older: Boolean) {
        val before = mutable.value
        if (older && before.busy) return
        val token = ++revision
        lastRefreshAt = nanoTime()
        job?.cancel()
        mutable.value = before.copy(busy = true, failed = false)
        job = scope.launch { readPage(before, older, token) }
    }

    private suspend fun readPage(before: QueryLogBrowserState, older: Boolean, token: Long) {
        try {
            val page = load(if (older) before.page!!.nextOffset else 0, 300,
                if (older) before.page!!.cursor else null)
            if (token == revision) {
                mutable.value = mutable.value.copy(
                    entries = if (older) before.entries + page.entries else page.entries,
                    page = page, busy = false, failed = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (token == revision) mutable.value = mutable.value.copy(busy = false, failed = true)
        } finally {
            if (token == revision) mutable.value = mutable.value.copy(busy = false)
        }
    }
}
