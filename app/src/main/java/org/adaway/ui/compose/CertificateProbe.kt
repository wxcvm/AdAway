package org.adaway.ui.compose

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adaway.util.WebServerUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * At most one unfinished read-only root observation in this process. A UI timeout does not
 * interrupt/close libsu's shared shell; a hung read cannot accumulate more workers on re-entry.
 */
internal object CertificateProbe {
    private val pending = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "AdAway-certificate-observation").apply { isDaemon = true }
    }

    suspend fun read(context: Context): CertificateSnapshot = withContext(Dispatchers.IO) {
        check(pending.compareAndSet(false, true)) { "Previous certificate observation is still pending" }
        val result = CompletableFuture<CertificateSnapshot>()
        try {
            executor.execute {
                try {
                    result.complete(CertificateSnapshot(
                        WebServerUtils.getWebServerState(context),
                        WebServerUtils.certificateTrustLevel(context),
                        WebServerUtils.getCertificateDaysLeft(context),
                    ))
                } catch (failure: Exception) {
                    result.completeExceptionally(failure)
                } finally {
                    pending.set(false)
                }
            }
        } catch (failure: RuntimeException) {
            pending.set(false)
            throw failure
        }
        result.get(3500, TimeUnit.MILLISECONDS)
    }
}
