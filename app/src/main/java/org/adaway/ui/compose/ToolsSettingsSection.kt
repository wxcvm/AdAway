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
            // 备份/恢复：导出规则+设置到文件，或从文件恢复
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        exportBackup(context)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_settings_backup))
                }
                OutlinedButton(
                    onClick = {
                        importBackup(context, viewModel) {
                            // 恢复后重启应用使全部设置生效
                            val pm = context.packageManager
                            val launch = pm.getLaunchIntentForPackage(context.packageName)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                context.startActivity(launch)
                            }
                            kotlin.system.exitProcess(0)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_settings_restore))
                }
            }
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
                val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    if (uri != null) {
                        val success = org.adaway.util.WebServerUtils.setCustomBlockImage(context, uri)
                        android.widget.Toast.makeText(
                            context,
                            if (success) R.string.pref_webserver_block_image_success
                            else R.string.pref_webserver_block_image_failed,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickImageLauncher.launch("image/*")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.compose_settings_block_image_choose))
                    }
                    OutlinedButton(
                        onClick = {
                            // 恢复默认
                            org.adaway.util.WebServerUtils.resetBlockImagesToDefault(context)
                            android.widget.Toast.makeText(
                                context,
                                R.string.pref_webserver_block_image_reset_success,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
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
    var checked by remember { mutableStateOf(prefs.getBoolean(key, def)) }
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
            onCheckedChange = { v ->
                if (prefs.edit().putBoolean(key, v).commit()) {
                    checked = v
                    org.adaway.model.update.ApkUpdateService.preferencesChanged(updateContext)
                    org.adaway.model.source.SourceUpdateService.preferencesChanged(updateContext)
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

/**
 * 导出备份：把所有偏好（compose_general / compose_app_monitor /
 * compose_webserver）序列化为 JSON 写入 Download/adblock-backup.json，
 * 并提示用户。Android 11+ 通过 MediaStore 写入（无需存储权限）。
 */
private fun exportBackup(context: Context) {
    try {
        val prefsNames = listOf(PREFS_GENERAL, PREFS_MONITOR, "compose_webserver")
        val json = org.json.JSONObject()
        prefsNames.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val section = org.json.JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> section.put(k, v)
                    is Int -> section.put(k, v)
                    is Long -> section.put(k, v)
                    is Float -> section.put(k, v)
                    is String -> section.put(k, v)
                    is java.util.Set<*> -> section.put(k, org.json.JSONArray(v.toList()))
                }
            }
            json.put(name, section)
        }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adblock-backup.json")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.compose_backup_no_slot), Toast.LENGTH_LONG).show()
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        } ?: throw java.io.IOException("cannot open output stream")
        Toast.makeText(context, context.getString(R.string.compose_backup_ok), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Timber.w(e, "Failed to export backup")
        Toast.makeText(context, context.getString(R.string.compose_backup_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/**
 * 导入备份：用文件选择器挑选 adblock-backup.json，
 * 解析后逐项写回偏好，完成后回调（重启应用使全部生效）。
 */
private fun importBackup(
    context: Context,
    viewModel: StatsViewModel,
    onDone: () -> Unit,
) {
    try {
        // 通过 MediaStore 查询 Download 中的 adblock-backup.json（scoped storage 兼容）
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        var text: String? = null
        resolver.query(
            collection,
            projection,
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("adblock-backup.json"),
            "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }
        if (text == null) {
            Toast.makeText(context, context.getString(R.string.compose_backup_none), Toast.LENGTH_LONG).show()
            return
        }
        val json = org.json.JSONObject(text)
        val prefsNames = listOf(PREFS_GENERAL, PREFS_MONITOR, "compose_webserver")
        prefsNames.forEach { name ->
            val section = json.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // 先清空再写入
            prefs.all.keys.forEach { editor.remove(it) }
            val it = section.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = section.get(k)
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putInt(k, v.toInt())
                    is String -> editor.putString(k, v)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<String>()
                        for (i in 0 until v.length()) list.add(v.getString(i))
                        editor.putStringSet(k, list.toSet())
                    }
                }
            }
            editor.apply()
        }
        Toast.makeText(context, context.getString(R.string.compose_backup_restored), Toast.LENGTH_LONG).show()
        onDone()
    } catch (e: Exception) {
        Timber.w(e, "Failed to import backup")
        Toast.makeText(context, context.getString(R.string.compose_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}
