package org.adaway.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.adaway.R
import timber.log.Timber
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.ToolsSettingsSection(viewModel: StatsViewModel) {
    val context = LocalContext.current
// ── Data management ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_data_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_settings_data_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val prefs = context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
            /*
             * 二次确认 + 真正调用原生重置。
             *
             * 之前这里只删了 s_hist_pos / s_daily_pos 两个设置键 —— 真正的计数、历史曲线、
             * 应用统计与查询日志都在原生进程内存里（并各自有 .dat 文件），所以按钮点了
             * 等于没点、也没有任何反馈。现在：第一次点击进入确认态，第二次才真正清除；
             * 清除走回环管理端口（8686）的 /internal-reset?what=all，并如实反馈结果。
             * 刻意不用协程/新 import：后台线程 + Handler 回主线程即可。
             */
            var clearArmed by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    if (!clearArmed) {
                        clearArmed = true
                    } else {
                        clearArmed = false
                        Thread {
                            val done = org.adaway.util.WebServerUtils.resetStatistics(context)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(
                                    context,
                                    if (done) R.string.compose_settings_clear_stats_ok
                                    else R.string.compose_settings_clear_stats_fail,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.start()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    stringResource(
                        if (clearArmed) R.string.compose_settings_clear_stats_confirm
                        else R.string.compose_settings_clear_stats,
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            BackupActions(viewModel)

        }
    }

    // ── 调试（崩溃日志 / 遥测）──
    // old-pref sharing (Constants.PREFS_NAME)
    val legacyPrefs = context.getSharedPreferences(org.adaway.util.Constants.PREFS_NAME, Context.MODE_PRIVATE)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_debug_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_settings_debug_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 旧版偏好键共享：使用 Constants.PREFS_NAME（preferences），与 PreferenceHelper/ApplicationLog 联动
            var debugLog by remember { mutableStateOf(legacyPrefs.getBoolean("debugEnabled", false)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_debug_log),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_debug_log_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugLog,
                    onCheckedChange = { v ->
                        debugLog = v
                        legacyPrefs.edit().putBoolean("debugEnabled", v).apply()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            var telemetry by remember { mutableStateOf(legacyPrefs.getBoolean("enableTelemetry", false)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_debug_telemetry),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_debug_telemetry_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = telemetry,
                    onCheckedChange = { v ->
                        telemetry = v
                        legacyPrefs.edit().putBoolean("enableTelemetry", v).apply()
                        org.adaway.util.log.SentryLog.setEnabled(context.applicationContext as android.app.Application, v)
                    },
                )
            }
        }
    }

    // ── 更多设置（IPv6 / 自动更新 / 关于）──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_more_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            var ipv6Enabled by remember { mutableStateOf(legacyPrefs.getBoolean("enableIpv6", false)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_ipv6),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_ipv6_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ipv6Enabled,
                    onCheckedChange = { v ->
                        ipv6Enabled = v
                        legacyPrefs.edit().putBoolean("enableIpv6", v).apply()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 自动更新（旧键共享，直接生效）
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "automaticAppUpdate",
                def = true,
                title = stringResource(R.string.compose_settings_update_automatic),
                hint = stringResource(R.string.compose_settings_update_automatic_hint),
            )
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "updateCheckAppStartup",
                def = true,
                title = stringResource(R.string.compose_settings_update_app_startup),
                hint = stringResource(R.string.compose_settings_update_app_startup_hint),
            )
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "updateCheckAppDaily",
                def = true,
                title = stringResource(R.string.compose_settings_update_app_daily),
                hint = stringResource(R.string.compose_settings_update_app_daily_hint),
            )
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "includeBetaReleases",
                def = false,
                title = stringResource(R.string.compose_settings_update_beta),
                hint = stringResource(R.string.compose_settings_update_beta_hint),
            )
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "updateCheckHostsDaily",
                def = true,
                title = stringResource(R.string.compose_settings_update_hosts),
                hint = stringResource(R.string.compose_settings_update_hosts_hint),
            )
            UpdateSwitchRow(
                prefs = legacyPrefs,
                key = "updateOnlyOnWifi",
                def = false,
                title = stringResource(R.string.compose_settings_update_wifi),
                hint = stringResource(R.string.compose_settings_update_wifi_hint),
            )
            // 关于：GitHub / 问题反馈
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/wxcvm/AdAway")))
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.compose_settings_about_repo),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/wxcvm/AdAway/issues")))
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.compose_settings_about_issues),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 版本
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) {
                    "unknown"
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.compose_settings_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 检查更新（更新源：GitHub Releases / wxcvm/AdAway）
            UpdateCheckRow(viewModel = viewModel, currentVersion = versionName)
        }
    }

    // ── About ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_about_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            val pkgInfo = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (e: Exception) {
                    null
                }
            }
            // 连点版本号 5 次解锁隐藏的开发者选项（拦截占位图设置）
            var aboutTaps by remember { mutableIntStateOf(0) }
            var hiddenUnlocked by remember { mutableStateOf(false) }
            Text(
                stringResource(
                    R.string.compose_settings_about_version,
                    pkgInfo?.versionName ?: "?",
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        aboutTaps++
                        if (aboutTaps >= 5) hiddenUnlocked = true
                    },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.content_native_abi, org.adaway.BuildConfig.NATIVE_ABI),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.compose_settings_about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 隐藏的开发者选项：自定义拦截占位图（连点版本号解锁）
            if (hiddenUnlocked) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.compose_settings_block_image_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val imageScope = rememberCoroutineScope()
                var imageBusy by remember { mutableStateOf(false) }
                fun changeImage(uri: Uri?) {
                    if (imageBusy) return
                    imageBusy = true
                    imageScope.launch {
                        try {
                            val message = withContext(Dispatchers.IO) {
                                val saved = if (uri == null)
                                    org.adaway.util.WebServerUtils.resetBlockImagesToDefault(context)
                                else org.adaway.util.WebServerUtils.setCustomBlockImage(context, uri)
                                when {
                                    !saved -> R.string.pref_webserver_block_image_failed
                                    org.adaway.util.WebServerUtils.reloadImages() -> R.string.block_image_reloaded
                                    else -> R.string.block_image_saved_pending
                                }
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        } finally { imageBusy = false }
                    }
                }
                Text(stringResource(R.string.block_image_scope), style = MaterialTheme.typography.bodySmall)
                val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    if (uri != null) changeImage(uri)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickImageLauncher.launch("image/*")
                        },
                        enabled = !imageBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.compose_settings_block_image_choose))
                    }
                    OutlinedButton(
                        onClick = {
                            changeImage(null)
                        },
                        enabled = !imageBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.compose_settings_block_image_reset))
                    }
                }
            }
        }
    }
}

/** 旧版偏好键共享的开关行（自动更新等）：简化旧版 PrefsActivity 页面的写法。 */
@Composable
private fun UpdateSwitchRow(
    prefs: android.content.SharedPreferences,
    key: String,
    def: Boolean,
    title: String,
    hint: String,
) {
    val updateContext = LocalContext.current.applicationContext
    var checked by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
    var saving by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = !saving,
            onCheckedChange = { v ->
                saving = true
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            if (!prefs.edit().putBoolean(key, v).commit()) false
                            else {
                                // Only the settings used by the WorkManager request
                                // itself require rescheduling. Workers read other
                                // preferences at execution time.
                                when (key) {
                                    "updateCheckAppDaily" ->
                                        org.adaway.model.update.ApkUpdateService.preferencesChanged(updateContext)
                                    "updateCheckHostsDaily", "updateOnlyOnWifi" ->
                                        org.adaway.model.source.SourceUpdateService.preferencesChanged(updateContext)
                                }
                                true
                            }
                        }
                        checked = prefs.getBoolean(key, def)
                        if (!saved) Toast.makeText(updateContext, R.string.settings_update_save_failed, Toast.LENGTH_LONG).show()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Timber.w(failure, "Update preference operation failed")
                        checked = prefs.getBoolean(key, def)
                        Toast.makeText(updateContext, R.string.settings_update_save_failed, Toast.LENGTH_LONG).show()
                    } finally { saving = false }
                }
            },
        )
    }
}
/**
 * 检查更新行：更新源为 GitHub Releases（wxcvm/AdAway）。
 *
 * 显示当前版本与检查结果；发现新版本时提供下载按钮（下载完成后由
 * ApkDownloadReceiver 校验签名/版本再交给系统安装器）。
 */
@Composable
private fun UpdateCheckRow(viewModel: StatsViewModel, currentVersion: String) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<org.adaway.model.update.Manifest?>(null) }
    val updateModel = remember(context) {
        (context.applicationContext as org.adaway.AdAwayApplication).getUpdateModel()
    }
    var pendingState by remember {
        mutableStateOf(updateModel.getPendingDownloadState())
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, updateModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                pendingState = updateModel.getPendingDownloadState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.compose_settings_update_check),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    message ?: stringResource(R.string.compose_settings_update_check_hint, currentVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = context.getString(R.string.update_feed_checking)
                    viewModel.checkUpdate(force = true) { manifest, error ->
                        checking = false
                        when {
                            manifest == null -> {
                                available = null
                                message = context.getString(
                                    R.string.update_feed_failed,
                                    error ?: context.getString(R.string.update_feed_unreachable),
                                )
                            }

                            manifest.updateAvailable -> {
                                available = manifest
                                message = context.getString(
                                    R.string.update_feed_available,
                                    manifest.version,
                                )
                            }

                            else -> {
                                available = null
                                message = context.getString(
                                    R.string.update_feed_up_to_date,
                                    manifest.version,
                                )
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.update_feed_button)) }
        }
        available?.let { manifest ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val downloadId = try {
                            viewModel.downloadUpdate()
                        } catch (e: Exception) {
                            Timber.w(e, "Unable to queue APK update")
                            -1L
                        }
                        pendingState = updateModel.getPendingDownloadState()
                        message = if (downloadId >= 0) {
                            context.getString(R.string.compose_settings_update_downloading)
                        } else {
                            context.getString(R.string.compose_update_download_failed)
                        }
                    },
                ) { Text(stringResource(R.string.update_feed_download)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(org.adaway.model.update.UpdateModel.getReleasesPage()),
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.update_feed_manual)) }
            }
            manifest.changelog.takeIf { it.isNotBlank() }?.let { changelog ->
                Text(
                    changelog,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (pendingState != org.adaway.model.update.UpdateModel.DownloadState.NONE) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    pendingState = updateModel.getPendingDownloadState()
                    message = when (pendingState) {
                        org.adaway.model.update.UpdateModel.DownloadState.READY_TO_INSTALL -> {
                            if (updateModel.retryInstallDownloadedUpdate()) {
                                context.getString(R.string.compose_update_install_reopen)
                            } else {
                                context.getString(R.string.compose_update_download_failed)
                            }
                        }
                        org.adaway.model.update.UpdateModel.DownloadState.DOWNLOADING ->
                            context.getString(R.string.compose_update_download_pending)
                        org.adaway.model.update.UpdateModel.DownloadState.FAILED ->
                            context.getString(R.string.compose_update_download_failed)
                        org.adaway.model.update.UpdateModel.DownloadState.NONE ->
                            context.getString(R.string.compose_update_no_download)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (pendingState == org.adaway.model.update.UpdateModel.DownloadState.READY_TO_INSTALL) {
                        stringResource(R.string.compose_update_install_retry)
                    } else {
                        stringResource(R.string.compose_update_check_download)
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupActions(viewModel: StatsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val exportRules = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        org.adaway.model.backup.BackupExporter.exportBackup(context, uri)
                    }
                    Toast.makeText(context, R.string.backup_document_saved, Toast.LENGTH_LONG).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    Timber.w(e, "Rule backup export failed")
                    Toast.makeText(context, context.getString(R.string.compose_backup_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }
    val importRules = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            viewModel.restoreRuleBackup(uri) { saved ->
                busy = false
                Toast.makeText(context, if (saved) R.string.backup_rules_restored else R.string.backup_rules_not_saved,
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportUi = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        PortableUiBackup.export(context, uri)
                    }
                    Toast.makeText(context, R.string.backup_document_saved, Toast.LENGTH_LONG).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.compose_backup_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }
    val importUi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { PortableUiBackup.restore(context, uri) }
                    Toast.makeText(context, R.string.backup_ui_restored, Toast.LENGTH_LONG).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.compose_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }
    Text(stringResource(R.string.backup_rules_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.backup_rules_scope), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { exportRules.launch("adblock-rules.json") }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.compose_settings_backup))
        }
        OutlinedButton(enabled = !busy, onClick = { importRules.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.compose_settings_restore))
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.backup_ui_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.backup_ui_scope), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { exportUi.launch("adblock-ui.json") }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.compose_settings_backup))
        }
        OutlinedButton(enabled = !busy, onClick = { importUi.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.compose_settings_restore))
        }
    }
}
