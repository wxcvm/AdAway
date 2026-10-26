package org.adaway.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Claimed synchronously by the click handler, before launching IO or waiting for recomposition. */
internal class ListenerDiagnosticAdmission {
    private var owner by mutableStateOf<Any?>(null)
    val busy: Boolean get() = owner != null
    fun claim(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    fun release(token: Any) {
        if (owner === token) owner = null
    }
}
