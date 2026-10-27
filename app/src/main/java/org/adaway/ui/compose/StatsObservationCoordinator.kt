package org.adaway.ui.compose

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StatsSource { NONE, HTTP, WEBSOCKET, SNAPSHOT }
enum class StatsPushState { DISABLED, SUSPENDED, CONNECTING, CONNECTED, RETRYING }
enum class StatsReadError { SNAPSHOT, UNREACHABLE, ERROR }
data class StatsObservationState(
    val stats: ServerStats? = null,
    val ranking: TopHostsObservation = TopHostsObservation(),
    val observedAtMillis: Long = 0,
    val source: StatsSource = StatsSource.NONE,
    val error: StatsReadError? = null,
    val refreshing: Boolean = false,
    val revision: Long = 0,
    val push: StatsPushState = StatsPushState.DISABLED,
) {
    val legacyError: String? get() = error?.name?.lowercase(java.util.Locale.ROOT)
}

internal interface StatsTransport : AutoCloseable {
    suspend fun readLive(): ServerStats?
    suspend fun readSnapshot(): ServerStats?
    /** Callbacks run on the collecting coroutine, not the socket callback thread.
     * Return/throw when this connection ends; cancellation must release its socket. */
    suspend fun collectPush(connected: () -> Unit, receive: (ServerStats) -> Unit)
}
internal interface StatsClock {
    fun wallMillis(): Long
    fun monotonicMillis(): Long
    suspend fun pause(millis: Long)
}
internal object SystemStatsClock : StatsClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun monotonicMillis() = System.nanoTime() / 1_000_000
    override suspend fun pause(millis: Long) { delay(millis) }
}

/** All entrypoints and publication are confined to the owning (Main) scope.
 * Transports may suspend on IO. One mutex per transport path prevents overlapping
 * underlying IO when an old cancellation is still unwinding during resume. */
internal class StatsObservationCoordinator(
    private val scope: CoroutineScope,
    private val transport: StatsTransport,
    private val clock: StatsClock = SystemStatsClock,
) : AutoCloseable {
    private val mutable = MutableStateFlow(StatsObservationState())
    val state: StateFlow<StatsObservationState> = mutable.asStateFlow()
    private val tick = MutableStateFlow(0L)
    val refreshTick: StateFlow<Long> = tick.asStateFlow()
    private val reads = Mutex()
    private val sockets = Mutex()
    private var foreground = false
    private var realtime = false
    private var closed = false
    private var session = 0L
    private var socketRevision = 0L
    private var refreshRevision = 0L
    private var pollJob: Job? = null
    private var refreshJob: Job? = null
    private var pushJob: Job? = null

    fun setForeground(visible: Boolean, realtimeEnabled: Boolean = realtime) {
        if (closed) return
        realtime = realtimeEnabled
        if (visible == foreground) { ensurePush(); return }
        foreground = visible
        session++
        if (!visible) {
            pollJob?.cancel(); refreshJob?.cancel()
            stopPush()
            mutable.value = mutable.value.copy(refreshing = false)
            return
        }
        refresh()
        pollJob = scope.launch {
            while (isActive) { clock.pause(10_000); refresh() }
        }
        ensurePush()
    }

    fun setRealtime(enabled: Boolean) {
        if (closed) return
        realtime = enabled
        ensurePush()
    }

    private fun stopPush() {
        socketRevision++
        pushJob?.cancel(); pushJob = null
        mutable.value = mutable.value.copy(push = if (realtime) StatsPushState.SUSPENDED else StatsPushState.DISABLED)
    }

    private fun currentPush(requested: Long) =
        !closed && foreground && realtime && requested == socketRevision

    private inner class PushAttempt(val revision: Long) {
        private var connectedAt: Long? = null
        private var received = false
        fun connected() {
            if (!currentPush(revision)) return
            connectedAt = clock.monotonicMillis()
            mutable.value = mutable.value.copy(push = StatsPushState.CONNECTED)
        }
        fun receive(incoming: ServerStats) {
            if (!currentPush(revision)) return
            if (connectedAt == null) connectedAt = clock.monotonicMillis()
            received = true
            mutable.value = mutable.value.copy(push = StatsPushState.CONNECTED)
            acceptPush(incoming)
        }
        fun healthy() = received ||
            connectedAt?.let { clock.monotonicMillis() - it >= 30_000 } == true
    }

    private fun acceptPush(incoming: ServerStats) {
        val known = mutable.value.stats
        if (shouldReplacePushedStats(known?.snapshotTs, known?.uptimeSeconds,
                incoming.snapshotTs, incoming.uptimeSeconds)) {
            publish(incoming, StatsSource.WEBSOCKET, clock.wallMillis())
            tick.value++
        }
    }

    private suspend fun collectAttempt(attempt: PushAttempt) {
        try {
            sockets.withLock { transport.collectPush(attempt::connected, attempt::receive) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* HTTP polling remains authoritative and active. */ }
    }

    private fun ensurePush() {
        if (!foreground || !realtime) { stopPush(); return }
        if (pushJob?.isActive == true) return
        val requested = ++socketRevision
        pushJob = scope.launch { reconnect(requested) }
    }

    private suspend fun reconnect(requested: Long) {
        var failures = 0
        val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        try {
            while (currentCoroutineContext().isActive && currentPush(requested)) {
                val attempt = PushAttempt(requested)
                mutable.value = mutable.value.copy(push = StatsPushState.CONNECTING)
                collectAttempt(attempt)
                if (!currentPush(requested)) break
                if (attempt.healthy()) failures = 0
                val wait = backoff[failures.coerceAtMost(backoff.lastIndex)]
                failures = (failures + 1).coerceAtMost(backoff.lastIndex)
                mutable.value = mutable.value.copy(push = StatsPushState.RETRYING)
                clock.pause(wait)
            }
        } finally {
            if (requested == socketRevision) mutable.value = mutable.value.copy(
                push = if (realtime) StatsPushState.SUSPENDED else StatsPushState.DISABLED)
        }
    }

    private data class ReadToken(val session: Long, val read: Long, val publication: Long)
    private data class ReadResult(val stats: ServerStats?, val source: StatsSource)
    private fun currentRead(token: ReadToken) =
        !closed && foreground && token.session == session && token.read == refreshRevision
    private fun currentPublication(token: ReadToken) =
        currentRead(token) && token.publication == mutable.value.revision

    fun refresh() {
        if (closed || !foreground || refreshJob?.isActive == true) return
        val token = ReadToken(session, ++refreshRevision, mutable.value.revision)
        mutable.value = mutable.value.copy(refreshing = true)
        refreshJob = scope.launch { refreshOnce(token) }
    }

    private suspend fun readStats(): ReadResult = reads.withLock {
        val live = try { transport.readLive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (live != null) ReadResult(live, StatsSource.HTTP)
        else ReadResult(transport.readSnapshot(), StatsSource.SNAPSHOT)
    }

    private suspend fun refreshOnce(token: ReadToken) {
        try {
            val result = readStats()
            if (currentPublication(token)) acceptRead(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (currentPublication(token)) unavailable(StatsReadError.ERROR)
        } finally {
            if (currentRead(token)) mutable.value = mutable.value.copy(refreshing = false)
            // Logs must not depend on successful statistics, including discarded/cancelled reads.
            tick.value++
        }
    }

    private fun acceptRead(result: ReadResult) {
        val incoming = result.stats ?: return unavailable(StatsReadError.UNREACHABLE)
        val snapshot = result.source == StatsSource.SNAPSHOT
        if (!shouldReplaceStats(mutable.value.stats?.snapshotTs, mutable.value.observedAtMillis,
                incoming.snapshotTs, snapshot)) return unavailable(StatsReadError.UNREACHABLE)
        val observed = if (snapshot) {
            incoming.snapshotTs.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000) ?: 0
        } else clock.wallMillis()
        publish(incoming, result.source, observed)
    }

    private fun publish(stats: ServerStats, source: StatsSource, observed: Long) {
        val previous = mutable.value
        mutable.value = previous.copy(stats = stats, source = source, observedAtMillis = observed,
            error = if (source == StatsSource.SNAPSHOT) StatsReadError.SNAPSHOT else null,
            ranking = previous.ranking.accept(stats.topBlocked, stats.topHostsRead, observed, source == StatsSource.SNAPSHOT),
            revision = previous.revision + 1)
    }
    private fun unavailable(error: StatsReadError) {
        mutable.value = mutable.value.copy(error = error, ranking = mutable.value.ranking.unavailable())
    }
    override fun close() {
        if (closed) return
        setForeground(false)
        closed = true
        transport.close()
    }
}
