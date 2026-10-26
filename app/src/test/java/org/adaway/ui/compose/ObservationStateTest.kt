package org.adaway.ui.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationStateTest {
    private fun parsed(suffix: String): ServerStats = ServerStats.fromJson(
        JSONObject("""{"uptime_seconds":1,"total_requests":0$suffix}"""))!!

    @Test fun missingMalformedAndValidEmptyAreDifferentEvidence() {
        assertEquals(TopHostsRead.MISSING, parsed("").topHostsRead)
        for (bad in listOf("null", "{}", "false", "[{}]", "[null]",
            "[{\"host\":\"ok\",\"count\":-1}]", "[{\"host\":\"ok\",\"count\":\"4\"}]")) {
            assertEquals(bad, TopHostsRead.MALFORMED, parsed(",\"top_blocked\":$bad").topHostsRead)
        }
        assertEquals(TopHostsRead.VALID, parsed(",\"top_blocked\":[]").topHostsRead)
        assertEquals(listOf(BlockedHost("ads.example", 4)),
            parsed(",\"top_blocked\":[{\"host\":\"ads.example\",\"count\":4}]").topBlocked)
    }

    @Test fun rankingCountsRejectOverflowFractionsAndNonFiniteNumbers() {
        for (bad in listOf<Any?>(null, "4", true, -1L, 0.5, Double.NaN,
            Double.POSITIVE_INFINITY, 9223372036854775808.0,
            java.math.BigInteger("9223372036854775808"))) {
            assertNull("Rejected count: $bad", parseRankingCount(bad))
        }
        assertEquals(Long.MAX_VALUE, parseRankingCount(Long.MAX_VALUE))
        assertEquals(0L, parseRankingCount(0))
        assertEquals(4L, parseRankingCount(4.0))
        for (bad in listOf("9223372036854775808", "9.223372036854776E18", "1e100", "0.5")) {
            assertEquals(TopHostsRead.MALFORMED,
                parsed(",\"top_blocked\":[{\"host\":\"ok\",\"count\":$bad}]").topHostsRead)
        }
    }

    @Test fun certificateActionIsRetainedSingleFlightAndRecoversFromFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val observation = CertificateObservation(scope, { CertificateSnapshot(7, 2, 90) })
            val action = CertificateAction(scope, observation)
            val gate = CompletableDeferred<Boolean>()
            var starts = 0
            var result: Boolean? = null
            action.run({ starts++; gate.await() }) { result = it }
            repeat(10) { action.run({ starts++; true }) { result = it } }
            assertEquals(1, starts)
            assertTrue(action.busy.value)
            gate.complete(true)
            assertEquals(true, result)
            assertFalse(action.busy.value)
            action.run({ error("certificate I/O failed") }) { result = it }
            assertEquals(false, result)
            assertFalse(action.busy.value)
            assertNotNull(observation.state.value.snapshot)
        } finally { scope.cancel() }
    }

    @Test fun rankRetainsKnownRowsOnMissingAndFailureButNewValidEmptyIsEmpty() {
        val good = TopHostsObservation().accept(listOf(BlockedHost("ads.example", 9)), TopHostsRead.VALID, 123, false)
        val missing = good.accept(emptyList(), TopHostsRead.MISSING, 456, false)
        assertEquals(good.rows, missing.rows)
        assertEquals(123L, missing.observedAt)
        assertTrue(missing.stale)
        assertEquals(missing.rows, missing.unavailable().rows)
        val empty = missing.accept(emptyList(), TopHostsRead.VALID, 789, false)
        assertEquals(emptyList<BlockedHost>(), empty.rows)
        assertFalse(empty.stale)
        assertEquals(789L, empty.observedAt)
        assertTrue(empty.accept(emptyList(), TopHostsRead.VALID, 800, true).stale)
    }

    @Test fun newObserverIsUnknownNotFalseStoppedOrUntrusted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<CertificateSnapshot>()
            var reads = 0
            val observation = CertificateObservation(scope, { reads++; gate.await() }, { 90 })
            assertNull(observation.state.value.snapshot)
            repeat(10) { observation.refresh() }
            assertEquals(1, reads)
            assertTrue(observation.state.value.checking)
            assertNull(observation.state.value.snapshot)
            val good = CertificateSnapshot(7, 2, 800)
            gate.complete(good)
            assertEquals(good, observation.state.value.snapshot)
            assertEquals(90L, observation.state.value.observedAt)
        } finally { scope.cancel() }
    }

    @Test fun failedRecheckKeepsGoodSnapshotAndTimeWithExplicitStaleState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var fail = false
            val good = CertificateSnapshot(7, 2, 800)
            val observation = CertificateObservation(scope, {
                if (fail) error("bounded read timeout") else good
            }, { 90 })
            observation.refresh()
            fail = true
            observation.refresh()
            assertEquals(good, observation.state.value.snapshot)
            assertEquals(90L, observation.state.value.observedAt)
            assertTrue(observation.state.value.stale)
            assertFalse(observation.state.value.checking)
        } finally { scope.cancel() }
    }

    @Test fun invalidatedLateReadCannotReplaceNewObservation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val oldRead = CompletableDeferred<CertificateSnapshot>()
            val newRead = CompletableDeferred<CertificateSnapshot>()
            var reads = 0
            val observation = CertificateObservation(scope, {
                val gate = if (++reads == 1) oldRead else newRead
                withContext(NonCancellable) { gate.await() }
            }, { 90 })
            observation.refresh()
            observation.invalidate()
            observation.refresh()
            val good = CertificateSnapshot(8, 2, 90)
            newRead.complete(good)
            oldRead.complete(CertificateSnapshot(0, 0, null))
            assertEquals(good, observation.state.value.snapshot)
            assertFalse(observation.state.value.stale)
        } finally { scope.cancel() }
    }
}
