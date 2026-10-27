package org.adaway.ui.compose

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** No sleeps: wall time, monotonic time, IO completion and socket callbacks are controlled. */
class StatsObservationCoordinatorTest {
    private class Clock : StatsClock {
        var now = 0L
        var wall = 100_000L
        private data class Wait(val at: Long, val gate: CompletableDeferred<Unit>)
        private val waits = mutableListOf<Wait>()
        override fun wallMillis() = wall
        override fun monotonicMillis() = now
        override suspend fun pause(millis: Long) {
            val wait = Wait(now + millis, CompletableDeferred())
            waits += wait
            try { wait.gate.await() } finally { waits.remove(wait) }
        }
        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val next = waits.filter { it.at <= target }.minByOrNull { it.at } ?: break
                wall += next.at - now; now = next.at
                waits.remove(next); next.gate.complete(Unit)
            }
            wall += target - now; now = target
        }
        val pending get() = waits.size
    }
    private class Transport(val clock: Clock) : StatsTransport {
        var live: suspend () -> ServerStats? = { stats() }
        var snapshot: suspend () -> ServerStats? = { null }
        var liveReads = 0; var fileReads = 0; var activeReads = 0; var maxReads = 0
        var activeSockets = 0; var maxSockets = 0; var closed = false
        val attempts = mutableListOf<Long>()
        data class Socket(val open: () -> Unit, val push: (ServerStats) -> Unit,
            val end: CompletableDeferred<Unit> = CompletableDeferred())
        val sessions = mutableListOf<Socket>()
        var failImmediately = false
        override suspend fun readLive(): ServerStats? {
            liveReads++; activeReads++; maxReads = maxOf(maxReads, activeReads)
            try { return live() } finally { activeReads-- }
        }
        override suspend fun readSnapshot(): ServerStats? { fileReads++; return snapshot() }
        override suspend fun collectPush(connected: () -> Unit, receive: (ServerStats) -> Unit) {
            attempts += clock.now
            activeSockets++; maxSockets = maxOf(maxSockets, activeSockets)
            try {
                if (failImmediately) error("offline")
                val socket = Socket(connected, receive); sessions += socket
                socket.open(); socket.end.await()
            } finally { activeSockets-- }
        }
        override fun close() { closed = true }
    }
    private class Fixture : AutoCloseable {
        val clock = Clock()
        val transport = Transport(clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val observer = StatsObservationCoordinator(scope, transport, clock)
        override fun close() { observer.close(); scope.cancel() }
    }
    companion object {
        private fun stats(ts: Long = 100, count: Long = 7, uptime: Long = 10,
            hosts: List<BlockedHost> = listOf(BlockedHost("ads.example", count)),
            read: TopHostsRead = TopHostsRead.VALID) =
            ServerStats(snapshotTs = ts, totalRequests = count, uptimeSeconds = uptime,
                topBlocked = hosts, topHostsRead = read)
    }

    @Test fun pushAndLateHttpPublishOneCoherentObservation() = runBlocking {
        Fixture().use { f ->
            val gate = CompletableDeferred<ServerStats?>()
            f.transport.live = { gate.await() }
            f.observer.setForeground(true, true)
            val push = stats(ts = 110, count = 22)
            f.transport.sessions.last().push(push)
            val accepted = f.observer.state.value
            assertEquals(push, accepted.stats)
            assertEquals(StatsSource.WEBSOCKET, accepted.source)
            assertEquals(accepted.observedAtMillis, accepted.ranking.observedAt)
            gate.complete(stats(ts = 90, count = 1))
            assertEquals(accepted.copy(refreshing = false), f.observer.state.value)
            assertEquals(2L, f.observer.refreshTick.value)
        }
    }

    @Test fun lateFailureCannotMarkNewPushStaleAndLogsStillWake() = runBlocking {
        Fixture().use { f ->
            val gate = CompletableDeferred<ServerStats?>()
            f.transport.live = { gate.await() }
            f.observer.setForeground(true, true)
            f.transport.sessions.last().push(stats(ts = 120))
            gate.completeExceptionally(IllegalStateException("late read"))
            assertNull(f.observer.state.value.error)
            assertFalse(f.observer.state.value.ranking.stale)
            assertEquals(2L, f.observer.refreshTick.value)
        }
    }

    @Test fun fallbackCannotRollBackLiveAndFailureDoesNotClearKnownValues() {
        Fixture().use { f ->
            f.observer.setForeground(true)
            val good = f.observer.state.value
            f.transport.live = { null }
            f.transport.snapshot = { stats(ts = 99, count = 1) }
            f.observer.refresh()
            assertEquals(good.stats, f.observer.state.value.stats)
            assertEquals(good.observedAtMillis, f.observer.state.value.observedAtMillis)
            assertEquals(StatsReadError.UNREACHABLE, f.observer.state.value.error)
            assertTrue(f.observer.state.value.ranking.stale)
            f.transport.snapshot = { stats(ts = 101, count = 8) }
            f.observer.refresh()
            assertEquals(StatsSource.SNAPSHOT, f.observer.state.value.source)
            assertEquals(101_000L, f.observer.state.value.observedAtMillis)
            assertEquals(StatsReadError.SNAPSHOT, f.observer.state.value.error)
            f.transport.live = { stats(ts = 102, hosts = emptyList(), read = TopHostsRead.MISSING) }
            f.observer.refresh()
            assertEquals(listOf(BlockedHost("ads.example", 8)), f.observer.state.value.ranking.rows)
            assertTrue(f.observer.state.value.ranking.stale)
            f.transport.live = { stats(ts = 103, hosts = emptyList()) }
            f.observer.refresh()
            assertEquals(emptyList<BlockedHost>(), f.observer.state.value.ranking.rows)
            assertFalse(f.observer.state.value.ranking.stale)
        }
    }

    @Test fun unknownIsNotZeroAndFailedPollingStillWakesLogs() {
        Fixture().use { f ->
            f.transport.live = { null }
            f.observer.setForeground(true)
            assertNull(f.observer.state.value.stats)
            assertNull(f.observer.state.value.ranking.rows)
            assertEquals(0L, f.observer.state.value.observedAtMillis)
            assertEquals(1L, f.observer.refreshTick.value)
            f.clock.advance(10_000)
            assertEquals(2L, f.observer.refreshTick.value)
        }
    }

    @Test fun foregroundIsSingleFlightAndQuietConnectedSocketDoesNotStopPolling() {
        Fixture().use { f ->
            repeat(20) { f.observer.setForeground(true, true); f.observer.setRealtime(true) }
            assertEquals(1, f.transport.liveReads)
            assertEquals(1, f.transport.sessions.size)
            f.clock.advance(30_000)
            assertEquals(4, f.transport.liveReads)
            assertEquals(1, f.transport.activeSockets)
            assertEquals(StatsPushState.CONNECTED, f.observer.state.value.push)
            assertEquals(1, f.transport.maxReads)
            f.observer.setForeground(false)
            assertEquals(0, f.transport.activeSockets)
            assertEquals(0, f.clock.pending)
        }
    }

    @Test fun retriesFollowCappedBackoffWithoutDisablingPolling() {
        Fixture().use { f ->
            f.transport.failImmediately = true
            f.observer.setForeground(true, true)
            f.clock.advance(91_000)
            assertEquals(listOf(0L,1000L,3000L,7000L,15000L,31000L,61000L,91000L), f.transport.attempts)
            assertEquals(10, f.transport.liveReads)
            assertEquals(StatsPushState.RETRYING, f.observer.state.value.push)
            f.observer.setForeground(false)
            val attempts = f.transport.attempts.size
            f.clock.advance(120_000)
            assertEquals(attempts, f.transport.attempts.size)
            assertEquals(0, f.clock.pending)
        }
    }

    @Test fun validPushResetsRetryDelay() {
        Fixture().use { f ->
            f.transport.failImmediately = true
            f.observer.setForeground(true, true)
            f.clock.advance(3000)
            f.transport.failImmediately = false
            f.clock.advance(4000)
            f.transport.sessions.last().push(stats(ts = 200))
            f.transport.sessions.last().end.complete(Unit)
            f.clock.advance(999)
            assertEquals(4, f.transport.attempts.size)
            f.clock.advance(1)
            assertEquals(8000L, f.transport.attempts.last())
        }
    }

    @Test fun thirtySecondQuietConnectionResetsRetryDelay() {
        Fixture().use { f ->
            f.transport.failImmediately = true
            f.observer.setForeground(true, true)
            f.clock.advance(3000)
            f.transport.failImmediately = false
            f.clock.advance(4000)
            f.clock.advance(30_000)
            f.transport.sessions.last().end.complete(Unit)
            f.clock.advance(1000)
            assertEquals(38000L, f.transport.attempts.last())
        }
    }

    @Test fun disabledAndOldSocketCallbacksCannotPublishOrScheduleRetry() {
        Fixture().use { f ->
            f.observer.setForeground(true, true)
            val old = f.transport.sessions.last()
            f.observer.setRealtime(false)
            val before = f.observer.state.value
            old.open(); old.push(stats(ts = 999)); old.end.complete(Unit)
            f.clock.advance(2000)
            assertEquals(before, f.observer.state.value)
            assertEquals(1, f.transport.attempts.size)
            f.observer.setRealtime(true)
            old.push(stats(ts = 999))
            assertEquals(2, f.transport.sessions.size)
            assertEquals(7L, f.observer.state.value.stats!!.totalRequests)
        }
    }

    @Test fun cancelledSlowReadCannotPublishOrOverlapNewRead() = runBlocking {
        Fixture().use { f ->
            val gate = CompletableDeferred<Unit>()
            f.transport.live = { withContext(NonCancellable) { gate.await(); null } }
            f.observer.setForeground(true, true)
            val old = f.transport.sessions.last()
            f.observer.setForeground(false)
            f.transport.live = { stats(ts = 200, count = 42) }
            f.observer.setForeground(true, true)
            old.push(stats(ts = 999, count = 99))
            assertEquals(1, f.transport.activeReads)
            gate.complete(Unit)
            assertEquals(0, f.transport.fileReads)
            assertEquals(42L, f.observer.state.value.stats!!.totalRequests)
            assertEquals(1, f.transport.maxReads)
            assertEquals(1, f.transport.maxSockets)
        }
    }

    @Test fun clockRollbackAndRestartAreAcceptedByOrderedHttpNotOldFile() {
        Fixture().use { f ->
            f.observer.setForeground(true, true)
            f.clock.wall = 50_000
            f.transport.live = { stats(ts = 50, count = 2, uptime = 1) }
            f.observer.refresh()
            assertEquals(2L, f.observer.state.value.stats!!.totalRequests)
            assertEquals(50_000L, f.observer.state.value.observedAtMillis)
            f.transport.live = { null }; f.transport.snapshot = { stats(ts = 49) }
            f.observer.refresh()
            assertEquals(2L, f.observer.state.value.stats!!.totalRequests)
        }
    }

    @Test fun lifecycleChurnLeavesNoAccumulatingTasksOrConnections() {
        Fixture().use { f ->
            repeat(30) {
                f.observer.setForeground(true, true)
                f.transport.sessions.last().end.complete(Unit)
                f.clock.advance(1000)
                f.observer.setForeground(false)
                assertEquals(0, f.transport.activeSockets)
                assertEquals(0, f.transport.activeReads)
                assertEquals(0, f.clock.pending)
            }
            assertEquals(1, f.transport.maxReads)
            assertEquals(1, f.transport.maxSockets)
            f.observer.close()
            assertTrue(f.transport.closed)
        }
    }
}
