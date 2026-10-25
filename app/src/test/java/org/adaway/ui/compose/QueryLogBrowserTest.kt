package org.adaway.ui.compose

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QueryLogBrowserTest {
    private val filter = QueryLogFilter()
    private fun cursor(f: QueryLogFilter = filter) = QueryLogCursor("0123456789abcdef0123456789abcdef", "1000", f.queryKey())
    private fun page(offset: Int, f: QueryLogFilter = filter, total: Int = 3): QueryLogPage {
        val row = QueryLogEntry(ts=10, uid=offset, action=offset%3, host="Host$offset.example",
            sequence=(1000-offset).toString(), generation=cursor(f).generation)
        return QueryLogPage(if(total==0) emptyList() else listOf(row), total,
            if(total==0) 0 else offset+1, total>0 && offset+1<total, cursor(f), 1000, 1)
    }
    @Test fun filtersQueryAllRetainedHistoryInsteadOfOnlyLoadedRows() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<QueryLogFilter>()
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,f -> requests+=f
                if(f.family==6) page(0,f,1).copy(entries=listOf(QueryLogEntry(host="older-than-first-page.example",family=6,
                    sequence="7",generation=cursor(f).generation))) else page(0,f)
            },filterDelayMs=0)
            browser.refresh()
            browser.setFamily(6)
            assertEquals(6,requests.last().family)
            assertEquals("older-than-first-page.example",browser.state.value.shown.single().host)
            assertEquals(1000,browser.state.value.page!!.retainedTotal)
            assertFalse(browser.state.value.live)
        } finally {scope.cancel()}
    }
    @Test fun changesDebounceAndDoNotRelabelPreviouslyLoadedRows()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<QueryLogFilter>()
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,f -> requests+=f;page(0,f)})
            browser.refresh()
            browser.setQuery("a");browser.setQuery("absent");browser.setFilter(3)
            assertEquals(1,requests.size)
            assertTrue(browser.state.value.previousConditions)
            assertEquals("Host0.example",browser.state.value.shown.single().host)
            delay(350)
            assertEquals(2,requests.size)
            assertEquals(QueryLogFilter(action=3,query="absent"),requests.last())
            assertFalse(browser.state.value.previousConditions)
        } finally {scope.cancel()}
    }
    @Test fun overlongUtf8InputIsNotTruncatedOrSent() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var calls=0
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,f -> calls++;page(0,f)},filterDelayMs=0)
            browser.refresh()
            val query="中".repeat(64)
            browser.setQuery(query)
            assertEquals(query,browser.state.value.query)
            assertTrue(browser.state.value.invalidQuery)
            browser.refresh();browser.more();assertEquals(1,calls)
        } finally {scope.cancel()}
    }
    @Test fun historyPinsQueryCursorAndDoesNotMultiplyExistingPoll() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<Pair<Int,QueryLogCursor?>>()
        var now=0L
        try {
            val browser=QueryLogBrowser(scope,{offset,_,previous,f ->requests+=offset to previous;page(offset,f)},{now},0)
            browser.refreshIfLive();repeat(20){now++;browser.refreshIfLive()}
            assertEquals(1,requests.size)
            browser.more();assertEquals(1 to cursor(),requests.last())
            assertEquals(2,browser.state.value.entries.size)
            browser.setQuery("older");assertEquals(0 to null,requests.last())
            assertEquals("older",browser.state.value.loadedFilter!!.query)
            now=20_000_000_000L;browser.refreshIfLive();assertEquals(3,requests.size)
        } finally {scope.cancel()}
    }
    @Test fun expiredPageAndRefreshFailureKeepLastSuccessfulHistory() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var fail=false
        try {
            val browser=QueryLogBrowser(scope,{o,_,_,f ->if(fail)error("expired");page(o,f)})
            browser.refresh();val original=browser.state.value.entries;fail=true;browser.more()
            assertEquals(original,browser.state.value.entries);assertTrue(browser.state.value.failed)
            browser.refresh();assertEquals(original,browser.state.value.entries)
            fail=false;browser.refresh();assertFalse(browser.state.value.failed)
        } finally {scope.cancel()}
    }
    @Test fun lateNoncancellableReaderCannotOverwriteChangedConditions()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val old=CompletableDeferred<QueryLogPage>();var first=true
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,f ->if(first){first=false;withContext(NonCancellable){old.await()}}else page(1,f)},filterDelayMs=0)
            browser.refresh();browser.setQuery("new");old.complete(page(0))
            assertEquals("Host1.example",browser.state.value.entries.single().host)
            assertEquals("new",browser.state.value.loadedFilter!!.query)
        } finally {old.cancel();scope.cancel()}
    }
    @Test fun oldServerIsExplicitlyUpgradeRequiredNotCurrentPageFallback() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,_ ->throw QueryLogUpgradeRequired()})
            browser.refresh();assertTrue(browser.state.value.upgradeRequired);assertTrue(browser.state.value.failed)
            assertTrue(browser.state.value.entries.isEmpty())
        } finally {scope.cancel()}
    }
    @Test fun exportReadsEveryPageFromSameSnapshotBeforePublishing()=runBlocking {
        val progress=mutableListOf<Int>();val offsets=mutableListOf<Int>()
        val rows=collectQueryLogSnapshot(cursor(),filter,3,{o,_,c,f ->
            offsets+=o;assertEquals(cursor(),c);page(o,f)
        },progress::add)
        assertEquals(listOf(0,1,2),offsets);assertEquals(listOf(1,2,3),progress)
        assertEquals(listOf("1000","999","998"),rows.map{it.sequence})
    }
    @Test fun exportExpiryAndCancellationNeverPublishPartialRows()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var fail=false;var shared=false
        try {
            val browser=QueryLogBrowser(scope,{o,_,_,f ->if(fail&&o>0)error("expired");page(o,f)})
            browser.refresh();fail=true;browser.exportAll{shared=true}
            assertFalse(shared);assertTrue(browser.state.value.exportFailed);assertFalse(browser.state.value.exporting)
            val blocked=CompletableDeferred<QueryLogPage>()
            val cancelled=QueryLogBrowser(scope,{o,_,_,f ->if(o>0)blocked.await() else page(o,f)})
            cancelled.refresh();cancelled.exportAll{shared=true};assertTrue(cancelled.state.value.exporting)
            cancelled.cancelExport();blocked.complete(page(1));assertFalse(shared);assertFalse(cancelled.state.value.exporting)
        } finally {scope.cancel()}
    }
    @Test fun exportRejectsDuplicateOrReorderedObservation()=runBlocking {
        assertThrows(IllegalArgumentException::class.java){runBlocking {
            collectQueryLogSnapshot(cursor(),filter,3,{o,_,_,f ->page(o,f).let {
                if(o==1)it.copy(entries=it.entries.map{r->r.copy(sequence="1000")}) else it
            }},{})
        }}
        Unit
    }
    @Test fun emptyPreviousPageCannotBeRelabeledAsNewConditionsDuringFailure()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var fail=false
        try {
            val browser=QueryLogBrowser(scope,{_,_,_,f ->
                if(fail)error("unavailable")
                page(0,f).copy(entries=emptyList(),total=0,nextOffset=0,hasMore=false)
            },filterDelayMs=0)
            browser.refresh()
            assertEquals(0,browser.state.value.page!!.total)
            fail=true;browser.setQuery("new-query")
            assertTrue(browser.state.value.previousConditions)
            assertTrue(browser.state.value.failed)
            assertEquals(QueryLogFilter(),browser.state.value.loadedFilter)
        } finally {scope.cancel()}
    }

}
