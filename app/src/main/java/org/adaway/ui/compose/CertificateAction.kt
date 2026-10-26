package org.adaway.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ViewModel-owned action gate; page navigation must not start a second certificate operation. */
class CertificateAction(
    private val scope: CoroutineScope,
    private val observation: CertificateObservation,
) {
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy

    fun run(action: suspend () -> Boolean, onResult: (Boolean) -> Unit) {
        if (!mutableBusy.compareAndSet(false, true)) return
        observation.invalidate()
        scope.launch {
            try {
                val verified = try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                onResult(verified)
            } finally {
                mutableBusy.value = false
                observation.refresh()
            }
        }.invokeOnCompletion { mutableBusy.value = false }
    }
}
