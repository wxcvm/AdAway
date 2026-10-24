package org.adaway.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.R
import org.adaway.util.ContentResponseRules
import org.adaway.util.ContentResponseSettings
import org.adaway.util.WebServerUtils
import java.io.IOException

/** Blocking backend methods run on IO, never from a switch's onClick. */
internal interface ContentResponseBackend {
    fun load(): ContentResponseSettings
    fun save(settings: ContentResponseSettings): Boolean
    fun loadRules(): String
    fun saveRules(text: String): String
    fun rulesExist(): Boolean = true
}

private class AndroidContentResponseBackend(private val context: Context) : ContentResponseBackend {
    override fun load() = WebServerUtils.loadContentResponseSettings(context)
    override fun save(settings: ContentResponseSettings) = WebServerUtils.saveContentResponseSettings(context, settings).confirmed
    override fun loadRules() = WebServerUtils.loadContentResponseRules(context)
    override fun saveRules(text: String) = WebServerUtils.saveContentResponseRules(context, text)
    override fun rulesExist() = java.nio.file.Files.isRegularFile(WebServerUtils.getResourcePath(context).resolve(ContentResponseRules.FILE_NAME))
}

@Composable
internal fun ContentResponseSettingsCard(backend: ContentResponseBackend? = null) {
    val context = LocalContext.current.applicationContext
    val service = backend ?: remember(context) { AndroidContentResponseBackend(context) }
    val scope = rememberCoroutineScope()
    var saved by remember(service) { mutableStateOf<ContentResponseSettings?>(null) }
    var draft by remember(service) { mutableStateOf<ContentResponseSettings?>(null) }
    var rules by remember(service) { mutableStateOf("") }
    var rulesExist by remember(service) { mutableStateOf(false) }
    var rulesReadable by remember(service) { mutableStateOf(false) }
    var busy by remember(service) { mutableStateOf(true) }
    var status by remember(service) { mutableIntStateOf(R.string.content_status_loading) }
    var reload by remember { mutableIntStateOf(0) }
    var showRules by remember { mutableStateOf(false) }
    LaunchedEffect(service, reload) {
        busy = true
        try {
            val loaded = withContext(Dispatchers.IO) { service.load() }
            saved = loaded
            draft = loaded
            status = R.string.content_status_ready
        } catch (_: IOException) { status = R.string.content_status_load_failed }
        try {
            val loaded = withContext(Dispatchers.IO) { service.loadRules() to service.rulesExist() }
            rules = loaded.first; rulesExist = loaded.second; rulesReadable = true
        } catch (_: IOException) { rulesReadable = false }
        finally { busy = false }
    }
    val edited = draft != saved
    val validCount = remember(rules) {
        try { ContentResponseRules.validate(rules).lineSequence().count { it.isNotBlank() } }
        catch (_: IOException) { -1 }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.content_scope), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (edited) R.string.content_status_draft else status),
                modifier = Modifier.testTag("content-status"), color = MaterialTheme.colorScheme.primary)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val current = draft
            if (current != null) {
                ContentPolicyOptions(current, !busy, validCount, rulesExist, rulesReadable,
                    onToggle = { key, enabled -> draft = current.with(key, enabled) }, onEditRules = { showRules = true })
                if (!rulesReadable) {
                    Text(stringResource(R.string.content_rules_read_failed), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("content-rules-load-error"))
                    OutlinedButton(onClick = { scope.launch {
                        busy = true
                        try {
                            val loaded = withContext(Dispatchers.IO) { service.loadRules() to service.rulesExist() }
                            rules = loaded.first; rulesExist = loaded.second; rulesReadable = true
                        } catch (_: IOException) { rulesReadable = false }
                        finally { busy = false }
                    } }, enabled = !busy) { Text(stringResource(R.string.content_rules_retry)) }
                }
                Button(onClick = {
                    val proposed = draft ?: return@Button
                    scope.launch {
                        busy = true
                        try {
                            val confirmed = withContext(Dispatchers.IO) { service.save(proposed) }
                            saved = proposed
                            status = if (confirmed) R.string.content_status_confirmed else R.string.content_status_saved_unknown
                        } catch (_: IOException) { status = R.string.content_status_save_failed }
                        finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.testTag("content-save")) {
                    Text(stringResource(if (edited) R.string.content_save else R.string.content_verify))
                }
                // Errors remain visible even while a failed save leaves an unsaved draft.
                if (edited && status == R.string.content_status_save_failed)
                    Text(stringResource(status), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("content-save-error"))
            } else if (!busy) {
                OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.content_retry_load)) }
            }
            Text(stringResource(R.string.compose_settings_cache_reuse_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showRules) ContentRulesEditor(rules, service, onDismiss = { showRules = false }) { rules = it; rulesExist = true }
}

@Composable
private fun ContentPolicyOptions(current: ContentResponseSettings, enabled: Boolean, validCount: Int, rulesExist: Boolean, rulesReadable: Boolean,
                                 onToggle: (String, Boolean) -> Unit, onEditRules: () -> Unit) {
                Text(stringResource(R.string.content_types_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.content_types_hint), style = MaterialTheme.typography.bodySmall)
                val categories = listOf(
                    "images" to R.string.compose_stats_type_images, "scripts" to R.string.compose_stats_type_scripts,
                    "styles" to R.string.compose_stats_type_styles, "fonts" to R.string.compose_stats_type_fonts,
                    "media" to R.string.compose_stats_type_media, "structures" to R.string.content_type_structures,
                    "api" to R.string.compose_stats_type_api, "telemetry" to R.string.compose_stats_type_telemetry,
                    "config" to R.string.compose_stats_type_config, "ws_sse" to R.string.compose_stats_type_ws,
                )
                categories.forEach { (key, label) ->
                    ContentSwitch(key, stringResource(label), null, current.enabled(key), enabled) { onToggle(key, it) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.content_protection_title), style = MaterialTheme.typography.titleMedium)
                listOf(
                    Triple("visible", R.string.compose_settings_visible_reply, R.string.compose_settings_visible_reply_hint),
                    Triple("dedup", R.string.compose_settings_dedup, R.string.compose_settings_dedup_hint),
                    Triple("retry_guard", R.string.compose_settings_retry_guard, R.string.compose_settings_retry_guard_hint),
                    Triple("circuit_breaker", R.string.compose_settings_circuit_breaker, R.string.compose_settings_circuit_breaker_hint),
                ).forEach { (key, label, hint) ->
                    ContentSwitch(key, stringResource(label), stringResource(hint), current.enabled(key), enabled) { onToggle(key, it) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.content_rules_hint), style = MaterialTheme.typography.bodySmall)
                Text(if (!rulesReadable) stringResource(R.string.content_rules_read_failed)
                    else if (!rulesExist) stringResource(R.string.content_rules_missing)
                    else if (validCount < 0) stringResource(R.string.content_rules_legacy)
                    else stringResource(R.string.content_rules_count, validCount), Modifier.testTag("content-rule-count"))
                if (current.enabled("rule_override") && validCount <= 0)
                    Text(stringResource(R.string.content_rules_inactive), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { onEditRules() }, enabled = enabled && rulesReadable, modifier = Modifier.testTag("content-edit-rules")) {
                    Text(stringResource(R.string.content_rules_edit))
                }
                ContentSwitch("rule_override", stringResource(R.string.compose_settings_rule_override), null,
                    current.enabled("rule_override"), enabled && ((rulesReadable && validCount > 0) || current.enabled("rule_override"))) {
                    onToggle("rule_override", it)
                }
}

@Composable
private fun ContentSwitch(key: String, title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("content-toggle-$key")
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ContentRulesEditor(initial: String, backend: ContentResponseBackend, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.content_rules_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.content_rules_hint))
                OutlinedTextField(text, onValueChange = {
                    if (it.length <= ContentResponseRules.MAX_CHARACTERS) { text = it; saved = false; error = 0 }
                    else error = R.string.content_rules_invalid
                }, enabled = !busy, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("content-rules-text"),
                    label = { Text(stringResource(R.string.content_rules_format)) }, isError = error != 0)
                if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("content-rules-error"))
                if (saved) Text(stringResource(R.string.content_rules_saved), modifier = Modifier.testTag("content-rules-saved"))
            }
        }, confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    try {
                        val canonical = withContext(Dispatchers.IO) {
                            ContentResponseRules.validate(text)
                            backend.saveRules(text)
                        }
                        text = canonical; onSaved(canonical); saved = true; error = 0
                    } catch (_: IOException) { error = R.string.content_rules_invalid }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("content-rules-save")) { Text(stringResource(R.string.content_rules_save)) }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.content_close)) } })
}
