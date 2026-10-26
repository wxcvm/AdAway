package org.adaway.ui.compose

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.R
import org.adaway.util.ListenerDiagnosticSession
import org.adaway.util.ListenerResponseCheck
import org.adaway.util.ListenerSettings
import org.adaway.util.RootListenerAddresses
import org.adaway.util.WebServerUtils

/** Manual normal requests, separate from nonce receipts; never modifies the listener or trust store. */
@Composable
internal fun ResponseCheckCard(
    enabled: Boolean,
    configurationRevision: Long = 0L,
    claimDiagnostic: (Any) -> Boolean = { true },
    releaseDiagnostic: (Any) -> Unit = {},
    runCheck: (Context, ListenerSettings, ListenerDiagnosticSession) -> ListenerResponseCheck.Report = { context, saved, session ->
        ListenerResponseCheck.check(saved, RootListenerAddresses()::exists, session) {
            saved.arguments() == WebServerUtils.getListenerSettings(context).arguments()
        }
    },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ListenerResponseCheck.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var savedTarget by remember { mutableStateOf(WebServerUtils.getListenerSettings(context).arguments()) }
    var generation by remember { mutableLongStateOf(0L) }
    var session by remember { mutableStateOf<ListenerDiagnosticSession?>(null) }
    var worker by remember { mutableStateOf<Job?>(null) }
    var claim by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("compose_webserver", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("listen_ipv4", "listen_ipv6", "bind_ip", "bind_all", "http_port", "https_port")) {
                val target = WebServerUtils.getListenerSettings(context).arguments()
                scope.launch(Dispatchers.Main.immediate) { savedTarget = target }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(savedTarget, configurationRevision) {
        if (report != null) stale = true
        if (session != null) {
            cancelling = true
            session?.cancel()
            status = context.getString(R.string.response_check_cancelling)
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            generation++
            session?.cancel()
            worker?.cancel()
            claim?.let(releaseDiagnostic)
        }
    }
    fun start() {
        val token = Any()
        if (running || !claimDiagnostic(token)) return
        claim = token
        val id = ++generation
        val saved = WebServerUtils.getListenerSettings(context)
        val currentSession = ListenerDiagnosticSession()
        session = currentSession
        running = true
        cancelling = false
        status = context.getString(R.string.response_check_running)
        worker = scope.launch {
            try {
                val observed = withContext(Dispatchers.IO) { runCheck(context, saved, currentSession) }
                if (id == generation && !currentSession.isCancelled && saved.arguments() == savedTarget) {
                    report = observed
                    stale = false
                    status = context.getString(R.string.response_check_finished)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (id == generation) status = context.getString(R.string.response_check_failed,
                    failure.message ?: failure.javaClass.simpleName)
            } finally {
                currentSession.cancel()
                releaseDiagnostic(token)
                if (id == generation) {
                    if (cancelling) status = context.getString(R.string.response_check_cancelled)
                    running = false
                    cancelling = false
                    session = null
                    claim = null
                }
            }
        }
    }
    fun openBrowser(address: String, url: String) {
        val observedTarget = report?.savedTarget ?: return
        scope.launch {
            val stillLocal = withContext(Dispatchers.IO) {
                runCatching { RootListenerAddresses().exists(address) }.getOrDefault(false)
            }
            if (!stillLocal || stale || observedTarget != WebServerUtils.getListenerSettings(context).arguments()) {
                status = context.getString(R.string.response_check_browser_failed)
                return@launch
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                status = context.getString(R.string.response_check_browser_missing)
            } catch (_: SecurityException) {
                status = context.getString(R.string.response_check_browser_failed)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("response-check-card")) {
        Text(stringResource(R.string.response_check_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.response_check_notice), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(::start, enabled = enabled && !running, modifier = Modifier.testTag("response-check-start")) {
            Text(stringResource(R.string.response_check_start))
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("response-check-progress"))
            OutlinedButton({ cancelling = true; session?.cancel() }, enabled = !cancelling,
                modifier = Modifier.testTag("response-check-cancel")) { Text(stringResource(R.string.response_check_cancel)) }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("response-check-status"))
        if (stale) Text(stringResource(R.string.response_check_stale), style = MaterialTheme.typography.labelMedium)
        else if (running && report != null) Text(stringResource(R.string.response_check_previous))
        val known = report
        if (known == null) Text(stringResource(R.string.response_check_idle)) else {
            SelectionContainer { Text(known.describe(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("response-check-report")) }
            OutlinedButton({ clipboard.setText(AnnotatedString(known.describe())) }) {
                Text(stringResource(R.string.response_check_copy))
            }
            for (observation in known.observations.filter { it.local }) {
                OutlinedButton({ openBrowser(observation.address, observation.url) }, enabled = !stale && !running,
                    modifier = Modifier.testTag("response-browser-${observation.family}-${observation.https}")) {
                    Text(stringResource(R.string.response_check_browser, observation.url))
                }
            }
        }
        Text(stringResource(R.string.response_check_boundary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.response_check_no_log_link), style = MaterialTheme.typography.bodySmall)
    }
}
