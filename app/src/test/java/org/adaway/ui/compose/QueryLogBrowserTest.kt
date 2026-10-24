package org.adaway.ui.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class QueryLogBrowserTest {
    @Test fun deniedFilterAndPathOrStatusSearchKeepRequestsSeparate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = listOf(
            QueryLogEntry(host = "same.example", action = 3, path = "/internal-test", httpStatus = 403),
            QueryLogEntry(host = "same.example", action = 1, path = "/favicon.ico", httpStatus = 200),
        )
        try {
            val browser = QueryLogBrowser(scope, { _, _, _ -> QueryLogPage(rows, 2, 2, false, cursor) })
            browser.refresh()
            browser.setFilter(3)
            assertEquals("/internal-test", browser.state.value.shown.single().path)
            browser.setFilter(-1)
            browser.setQuery("favicon")
            assertEquals(200, browser.state.value.shown.single().httpStatus)
            browser.setQuery("403")
            assertEquals(3, browser.state.value.shown.single().action)
        } finally { scope.cancel() }
    }
    @Test fun addressFamilyAndResponseTypeFiltersUseRecordedMetadataOnly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val rows = listOf(QueryLogEntry(host = "same.example", family = 4, type = 1),
                QueryLogEntry(host = "same.example", family = 6, type = 1),
                QueryLogEntry(host = "literal:looking", family = 0, type = -1))
            val browser = QueryLogBrowser(scope, { _, _, _ -> QueryLogPage(rows, 3, 3, false, cursor) })
            browser.refresh()
            browser.setFamily(6)
            browser.setType(1)
            assertEquals(6, browser.state.value.shown.single().family)
            browser.setFamily(0)
            assertTrue(browser.state.value.shown.isEmpty())
            browser.setType(-1)
            assertEquals("literal:looking", browser.state.value.shown.single().host)
            browser.setFamily(null)
            browser.setType(null)
            assertEquals(3, browser.state.value.shown.size)
        } finally { scope.cancel() }
    }
    private val cursor = QueryLogCursor("0123456789abcdef0123456789abcdef", "3")
    private fun page(offset: Int) = QueryLogPage(
        listOf(QueryLogEntry(ts = 10, uid = offset, action = offset % 3, host = "Host$offset.example")),
        3, offset + 1, offset < 2, cursor,
    )

    @Test fun historyPinsCursorPausesAndFiltersLoadedRowsWithoutExtraRequests() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = mutableListOf<Pair<Int, QueryLogCursor?>>()
        var now = 0L
        try {
            val browser = QueryLogBrowser(scope, { offset, _, previous ->
                requests += offset to previous
                page(offset)
            }, { now })
            browser.refreshIfLive()
            repeat(20) { now++; browser.refreshIfLive() }
            assertEquals(1, requests.size) // WS updates do not multiply the existing poll.
            browser.more()
            assertEquals(1 to cursor, requests.last())
            assertFalse(browser.state.value.live)
            assertEquals(2, browser.state.value.entries.size)
            browser.setQuery("HOST1")
            browser.setFilter(1)
            browser.setUid(1)
            assertEquals("Host1.example", browser.state.value.shown.single().host)
            now = 20_000_000_000L
            browser.refreshIfLive()
            assertEquals(2, requests.size)
            browser.setUid(null)
            browser.setQuery("absent")
            assertTrue(browser.state.value.shown.isEmpty())
            assertTrue(browser.state.value.page!!.hasMore)
            browser.setLive(true)
            assertEquals(0 to null, requests.last())
            assertEquals("absent", browser.state.value.query)
            assertEquals(1, browser.state.value.filter)
        } finally { scope.cancel() }
    }

    @Test fun expiredPageAndRefreshFailureKeepTheLastSuccessfulHistory() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = false
        try {
            val browser = QueryLogBrowser(scope, { offset, _, _ ->
                if (fail) throw IllegalArgumentException("cursor_expired")
                page(offset)
            })
            browser.refresh()
            val original = browser.state.value.entries
            fail = true
            browser.more()
            assertEquals(original, browser.state.value.entries)
            assertTrue(browser.state.value.failed)
            assertFalse(browser.state.value.busy)
            browser.refresh()
            assertEquals(original, browser.state.value.entries)
            fail = false
            browser.refresh()
            assertFalse(browser.state.value.failed)
            assertEquals(original, browser.state.value.entries)
        } finally { scope.cancel() }
    }

    @Test fun responseAlreadyInFlightCannotOverwritePausedOrNewerPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<QueryLogPage>()
        var first = true
        try {
            val browser = QueryLogBrowser(scope, { _, _, _ ->
                if (first) {
                    first = false
                    // Simulate an HTTP reader that cannot interrupt its blocking operation.
                    withContext(NonCancellable) { old.await() }
                } else page(1)
            })
            browser.refresh()
            assertTrue(browser.state.value.busy)
            browser.setLive(false)
            browser.refresh()
            assertEquals("Host1.example", browser.state.value.entries.single().host)
            old.complete(page(0))
            assertEquals("Host1.example", browser.state.value.entries.single().host)
            assertFalse(browser.state.value.busy)
        } finally { old.cancel(); scope.cancel() }
    }

    @Test fun filteringDuringInitialLoadKeepsTheFirstResponseThenPinsHistory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = CompletableDeferred<QueryLogPage>()
        try {
            val browser = QueryLogBrowser(scope, { _, _, _ -> first.await() })
            browser.refreshIfLive()
            browser.setQuery("HOST0")
            assertFalse(browser.state.value.live)
            assertTrue(browser.state.value.busy)
            first.complete(page(0))
            assertEquals("Host0.example", browser.state.value.shown.single().host)
            assertFalse(browser.state.value.busy)
            assertFalse(browser.state.value.live)
        } finally { first.cancel(); scope.cancel() }
    }
}
