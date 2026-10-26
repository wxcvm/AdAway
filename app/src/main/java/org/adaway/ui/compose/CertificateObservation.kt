package org.adaway.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One immutable observation: trust, daemon status and expiry are published together. */
data class CertificateSnapshot(val serverState: Int, val trust: Int, val daysLeft: Long?)

data class CertificateObservationState(
    val snapshot: CertificateSnapshot? = null,
    val observedAt: Long = 0,
    val checking: Boolean = false,
    val stale: Boolean = false,
)

/** Retained outside a page; no polling or certificate mutation. The probe must bound blocking I/O. */
class CertificateObservation(
    private val scope: CoroutineScope,
    private val probe: suspend () -> CertificateSnapshot,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(CertificateObservationState())
    val state: StateFlow<CertificateObservationState> = mutable
    private var task: Job? = null
    private var revision = 0L

    fun refresh() {
        if (task?.isActive == true) return
        val requested = ++revision
        mutable.value = mutable.value.copy(checking = true)
        task = scope.launch {
            try {
                val result = probe()
                if (requested == revision) mutable.value = CertificateObservationState(result, now())
            } catch (cancelled: CancellationException) {
                if (requested == revision) mutable.value = mutable.value.copy(checking = false, stale = true)
                throw cancelled
            } catch (_: Exception) {
                if (requested == revision) mutable.value = mutable.value.copy(checking = false, stale = true)
            }
        }
    }

    /** An explicit user operation invalidates any observation started before that operation. */
    fun invalidate() {
        revision++
        task?.cancel()
        task = null
        mutable.value = mutable.value.copy(checking = false, stale = true)
    }
}
