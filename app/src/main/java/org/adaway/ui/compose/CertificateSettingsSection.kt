package org.adaway.ui.compose

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.CertificateSettingsSection(viewModel: StatsViewModel, certificateObservation: CertificateObservation) {
    val context = LocalContext.current
    val certificateState by certificateObservation.state.collectAsStateWithLifecycle()
    val certificateLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(certificateLifecycle, certificateObservation) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                !viewModel.certificateAction.busy.value)
                certificateObservation.refresh()
        }
        // Replays ON_RESUME once when this category enters an already resumed owner.
        certificateLifecycle.lifecycle.addObserver(observer)
        onDispose { certificateLifecycle.lifecycle.removeObserver(observer) }
    }
    val certificateActionBusy by viewModel.certificateAction.busy.collectAsStateWithLifecycle()
    CertificateObservationCard(certificateState,
        onRefresh = certificateObservation::refresh,
        onTrust = {
            val applicationContext = context.applicationContext
            viewModel.certificateAction.run(action = {
                withContext(Dispatchers.IO) {
                    org.adaway.util.WebServerUtils.installCertificateToUserStore(applicationContext) &&
                        org.adaway.util.WebServerUtils.isUserCertificateInstalled(applicationContext)
                }
            }, onResult = { verified ->
                Toast.makeText(applicationContext,
                    if (verified) R.string.compose_settings_cert_trust_ok
                    else R.string.compose_settings_cert_trust_fail, Toast.LENGTH_LONG).show()
            })
        },
        onManualInstall = {
            certificateObservation.invalidate()
            org.adaway.util.WebServerUtils.installUserCertificate(context)
        },
        onExport = { exportCertificate(context) },
        actionBusy = certificateActionBusy,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {                    // ── 固定证书的应用：由用户决定怎么处理 ──
            Spacer(Modifier.height(8.dp))
            var pinDeny by remember {
                mutableStateOf(
                    org.adaway.util.WebServerUtils.getPinPolicyMode(context) == "deny",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_pin_policy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_pin_policy_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = pinDeny,
                    onCheckedChange = { v ->
                        pinDeny = v
                        org.adaway.util.WebServerUtils.setPinPolicyMode(
                            context,
                            if (v) "deny" else "off",
                        )
                    },
                )
            }

        }
    }
}

/**
 * 导出 CA 证书到 Download/adblock-ca.crt，供其他设备/模块（如 Magisk 证书模块）使用。
 * Android 11+ scoped storage：通过 MediaStore 写入公共 Download 目录（无需存储权限）。
 */
private fun exportCertificate(context: Context) {
    try {
        val src = java.io.File(context.filesDir, "webserver/localhost-2410.crt")
        if (!src.exists()) {
            Toast.makeText(context, context.getString(R.string.compose_export_cert_missing), Toast.LENGTH_LONG).show()
            return
        }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adblock-ca.crt")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.compose_export_no_slot), Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, context.getString(R.string.compose_export_cert_ok), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export certificate")
        Toast.makeText(context, context.getString(R.string.compose_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}
