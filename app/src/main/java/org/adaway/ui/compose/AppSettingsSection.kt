package org.adaway.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.adaway.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.AppSettingsSection(viewModel: StatsViewModel, refreshKey: Int, onPreferencesChanged: () -> Unit) {
    val context = LocalContext.current
    // Only this visible category observes high-frequency server snapshots.
    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()
    val apps = serverStats?.apps.orEmpty()
    // ── App monitoring (可折叠) ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            var appsExpanded by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { appsExpanded = !appsExpanded },
            ) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_apps_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_apps_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (appsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (appsExpanded) R.string.compose_action_collapse else R.string.compose_action_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = appsExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    if (apps.isEmpty()) {
                        Text(
                            stringResource(R.string.compose_settings_apps_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Re-read switch states after toggling (refreshKey changes)
                        apps.sortedByDescending { it.blocked + it.requests }.forEach { app ->
                            val monitored = remember(refreshKey, app.uid) {
                                isAppMonitored(context, app.uid)
                            }
                            val allowed = remember(refreshKey, app.uid) {
                                isAppAllowed(context, app.uid)
                            }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                appNameForUid(app.uid),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(
                                    R.string.compose_settings_app_sub,
                                    app.connections,
                                    app.requests,
                                    app.blocked,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        /*
                         * 放行开关：该应用流量完全绕过拦截 —— 这不是普通显示偏好，而是会改变
                         * 拦截效果的策略变更。首次放行时解释后果，并说明怎么撤销（把开关拨回去
                         * 即可）；解释只弹一次，之后不再打扰。
                         */
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(
                                checked = allowed,
                                onCheckedChange = { v ->
                                    setAppAllowed(context, app.uid, v)
                                    onPreferencesChanged()
                                    val sp = context.getSharedPreferences(
                                        PREFS_GENERAL,
                                        Context.MODE_PRIVATE,
                                    )
                                    if (v) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (sp.getBoolean("app_allow_explained", false)) {
                                                    R.string.compose_settings_app_allow_hint
                                                } else {
                                                    R.string.compose_settings_app_allow_explain
                                                },
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        sp.edit().putBoolean("app_allow_explained", true).apply()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_settings_app_allow_undone),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                            Text(
                                stringResource(R.string.compose_settings_app_allow),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (allowed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        /*
                         * 第二个开关此前是裸 Switch：没有可见文字、也没有无障碍标签，
                         * 读屏只会念"开关，开启/关闭"，用户分不清它和左边的放行开关各管什么。
                         * 这里照放行开关的样式补上文字标签（并被左侧放行开关形成对照）。
                         */
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(
                                checked = monitored,
                                onCheckedChange = { newValue ->
                                    setAppMonitored(context, app.uid, newValue)
                                    onPreferencesChanged()
                                },
                            )
                            Text(
                                stringResource(R.string.compose_settings_app_monitor),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (monitored) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                }
            }
        }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {                    // ── 开机自动启动 ──
            Spacer(Modifier.height(8.dp))
            var autostart by remember {
                mutableStateOf(
                    org.adaway.helper.PreferenceHelper.getWebServerEnabled(context),
                )
            }
            /*
             * 有 Magisk 时，开机由 init 侧直接拉起服务器（service.d 脚本），
             * 不再依赖开机广播——国产 ROM 常把广播延后甚至丢掉，这也是
             * “必须手动打开一次应用才会启动”的根因。0 = 未写入，1 = 已写入，
             * -1 = 本机没有 Magisk。
             */
            val autostartScope = rememberCoroutineScope()
            var bootScriptState by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                bootScriptState = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when {
                        !org.adaway.model.root.MagiskBootScript.isSupported() -> -1
                        org.adaway.model.root.MagiskBootScript.isInstalled() -> 1
                        else -> 0
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_autostart),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_autostart_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autostart,
                    onCheckedChange = { v ->
                        autostart = v
                        org.adaway.helper.PreferenceHelper
                            .setWebServerEnabled(context, v)
                        if (v) {
                            // 打开时顺手修掉两个最常见的“自启动失败”原因：
                            // 接收器被系统/清理软件禁用、启动任务没排队。
                            org.adaway.broadcast.BootReceiver.ensureEnabled(context)
                            org.adaway.broadcast.BootReceiver
                                .scheduleStart(context, "user enabled autostart")
                            /*
                             * 启动服务器本身就会走 root shell、检查进程、拷贝并设置可执行位，
                             * 而此前只有安装开机脚本进了 IO —— 注释写着 root shell 不能跑在主线程
                             * 上，启动调用却还留在主线程。两者互不依赖，一起放进 IO。
                             */
                            autostartScope.launch {
                                val supported = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    org.adaway.util.WebServerUtils.startWebServer(context)
                                    org.adaway.model.root.MagiskBootScript.install(context)
                                }
                                bootScriptState = if (supported) 1 else -1
                            }
                        } else {
                            /* 停止同样会走 root shell，与卸载开机脚本一起放到 IO */
                            autostartScope.launch {
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    org.adaway.util.WebServerUtils.stopWebServer()
                                    org.adaway.model.root.MagiskBootScript.uninstall()
                                }
                                bootScriptState = 0
                            }
                        }
                    },
                )
            }
            /*
             * 自启动失败时真正有用的两个动作（原先藏在“自启动诊断”的展开区里，
             * 只读的诊断行已按反馈移除）。MIUI/EMUI/ColorOS 除了电池优化之外
             * 还会单独拦截开机广播，需要在系统设置里再允许“自启动/后台运行”。
             */
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openBatterySettings(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_settings_autostart_battery_button))
                }
                OutlinedButton(
                    onClick = {
                        org.adaway.broadcast.BootReceiver.ensureEnabled(context)
                        org.adaway.broadcast.BootReceiver
                            .scheduleStart(context, "user retried")
                        /*
                         * 启动服务器会走 root shell、检查进程、拷贝并设置可执行位 ——
                         * 放到后台线程；提示放在启动动作之后回主线程弹，避免"提示说已重试、
                         * 实际还没开始"这种早于事实的反馈。最后两处 C6 收尾。
                         */
                        Thread {
                            org.adaway.util.WebServerUtils.startWebServer(context)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(
                                    context,
                                    R.string.compose_settings_autostart_retry_toast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }.start()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.compose_settings_autostart_retry))
                }
            }
            Spacer(Modifier.height(6.dp))
            /*
             * 可选的常驻守护：前台服务不被系统延迟调度，服务器被杀后能在
             * 数秒内拉起来（WorkManager 兜底要等十几分钟）。代价是一条常驻
             * 通知，所以默认关闭、由用户自己决定。
             */
            var keepAlive by remember {
                mutableStateOf(
                    org.adaway.helper.PreferenceHelper.getKeepAliveEnabled(context),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.compose_settings_keepalive),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_keepalive_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = keepAlive,
                    onCheckedChange = { v ->
                        keepAlive = v
                        org.adaway.helper.PreferenceHelper
                            .setKeepAliveEnabled(context, v)
                        if (v) {
                            org.adaway.broadcast.ServerKeepAliveService.start(context)
                        } else {
                            org.adaway.broadcast.ServerKeepAliveService.stop(context)
                        }
                    },
                )
            }
            if (bootScriptState != 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        if (bootScriptState == 1) {
                            R.string.compose_settings_autostart_bootscript_on
                        } else {
                            R.string.compose_settings_autostart_bootscript_na
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bootScriptState == 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            /*
             * 一行诊断（不是以前那个多行只读面板）：上次开机到底是哪条路跑的、
             * 结果如何。失败时点开详情能看到环形记录、开机脚本状态和脚本自己的
             * 日志 —— “打开 App 才自启”这类问题只能靠这些定位，靠猜没有意义。
             */
            var bootDiag by remember { mutableStateOf("") }
            var bootDetail by remember { mutableStateOf("") }
            var bootDialog by remember { mutableStateOf(false) }
            var bootTestResult by remember { mutableStateOf("") }
            var bootTesting by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                bootDiag = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    bootDiagSummary(context)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (bootDiag.isEmpty()) {
                    stringResource(R.string.compose_settings_boot_diag_never)
                } else {
                    bootDiag
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    bootTesting = false
                    bootTestResult = ""
                    bootDialog = true
                    autostartScope.launch {
                        bootDetail = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO,
                        ) {
                            buildBootDetail(context)
                        }
                    }
                },
            )
            if (bootDialog) {
                // Hoisted: stringResource() is @Composable and cannot be
                // called from inside an onClick lambda.
                val testResultLabel = stringResource(
                    R.string.compose_settings_boot_test_result_title,
                )
                AlertDialog(
                    onDismissRequest = {
                        bootDialog = false
                        autostartScope.launch {
                            bootDiag = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO,
                            ) {
                                bootDiagSummary(context)
                            }
                        }
                    },
                    title = {
                        Text(stringResource(R.string.compose_settings_boot_diag_title))
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                bootDetail,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (bootTesting) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(
                                        R.string.compose_settings_boot_test_running,
                                    ),
                                )
                            } else if (bootTestResult.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(testResultLabel + "：" + bootTestResult)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                bootTesting = true
                                bootTestResult = ""
                                autostartScope.launch {
                                    bootTestResult = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        org.adaway.model.root.MagiskBootScript
                                            .runNow(context)
                                    }
                                    bootTesting = false
                                    bootDetail = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        buildBootDetail(context)
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.compose_settings_boot_test_script,
                                ),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                                val text = if (bootTestResult.isEmpty()) {
                                    bootDetail
                                } else {
                                    bootDetail + "\n\n" + testResultLabel + "：" +
                                        bootTestResult
                                }
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "adblock-boot-diag", text,
                                    ),
                                )
                                Toast.makeText(
                                    context,
                                    R.string.compose_settings_boot_diag_copied,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ) {
                            Text(stringResource(R.string.compose_settings_boot_diag_copy))
                        }
                    },
                )
            }

        }
    }

    // ── Appearance (theme) ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_theme_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_settings_theme_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var theme by remember { mutableStateOf(themeMode(context)) }
            LaunchedEffect(Unit) { theme = themeMode(context) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple(0, R.string.compose_settings_theme_system, 0),
                    Triple(1, R.string.compose_settings_theme_light, 1),
                    Triple(2, R.string.compose_settings_theme_dark, 2),
                ).forEach { (mode, labelRes, _) ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = {
                            theme = mode
                            setThemeMode(context, mode)
                            // Recreate activity to apply theme
                            (context as? android.app.Activity)?.recreate()
                        },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }
}

/**
 * 打开系统的电池优化列表，让用户把本应用设为“不优化”。
 * 这是自启动失败的两大原因之一（另一个是厂商的自启动白名单）。
 */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Timber.w(e, "No battery optimization settings activity")
        Toast.makeText(context, e.message ?: context.getString(R.string.compose_unavailable), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 一行开机诊断摘要：哪条路跑的、结果如何。无记录时返回空串，由界面显示提示文案。
 */
private fun bootDiagSummary(context: Context): String {
    val time = org.adaway.helper.PreferenceHelper.getLastBootTime(context)
    if (time <= 0L) {
        return ""
    }
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    val mechanism = org.adaway.helper.PreferenceHelper.getLastBootMechanism(context)
        .ifEmpty { "—" }
    val result = org.adaway.helper.PreferenceHelper.getLastBootResult(context)
        .ifEmpty { "—" }
    return context.getString(
        R.string.compose_settings_boot_diag_line,
        fmt.format(java.util.Date(time)),
        mechanism,
        result,
    )
}

/**
 * 详情文本：环形开机记录 + 开机脚本（Magisk service.d）状态与脚本日志。
 *
 * <p>“打开 App 才自启”有两种完全不同的原因——广播根本没送到（组件被禁用/OEM 拦截）
 * 与后台任务被系统延后——只有把每条机制的记录和脚本自己的日志都摆出来才能区分。</p>
 */
private fun buildBootDetail(context: Context): String {
    val sb = StringBuilder()
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    sb.append("== 开机尝试记录（新 → 旧）==\n")
    val ring = org.adaway.helper.PreferenceHelper.getBootEventRing(context)
    if (ring.isEmpty()) {
        sb.append("（还没有记录）\n")
    }
    for (line in ring.reversed()) {
        val parts = line.split("|")
        if (parts.size >= 5) {
            val at = parts[0].toLongOrNull() ?: 0L
            sb.append(fmt.format(java.util.Date(at))).append("  ")
                .append(parts[1]).append("  ")
                .append(parts[2]).append("  ")
                .append(parts[3]).append("  ")
                .append(if (parts[4] == "1") "成功" else "未成功")
                .append('\n')
        }
    }
    sb.append("\n== 开机脚本（Magisk/KernelSU service.d）==\n")
    val supported = org.adaway.model.root.MagiskBootScript.isSupported()
    sb.append("service.d 可用：").append(if (supported) "是" else "否（本机没有该目录）").append('\n')
    if (supported) {
        val installed = org.adaway.model.root.MagiskBootScript.isInstalled()
        sb.append("脚本已安装：").append(if (installed) "是" else "否").append('\n')
        sb.append("路径：").append(org.adaway.model.root.MagiskBootScript.scriptPath()).append('\n')
        if (installed) {
            val content = org.adaway.model.root.MagiskBootScript.readScript()
            val complete = content.contains("--http-port") &&
                content.contains("--https-port") &&
                content.contains("--stats-port")
            sb.append("脚本参数校验：")
                .append(if (complete) "完整（含统计端口）" else "不完整，请重新开关一次「开机自动启动」")
                .append('\n')
        }
        sb.append("\n== 脚本日志（").append(org.adaway.model.root.MagiskBootScript.LOG_PATH)
            .append("）==\n")
        sb.append(org.adaway.model.root.MagiskBootScript.readLogTail()).append('\n')
    }
    sb.append("\n== 其他 ==\n")
    sb.append("开机接收器组件：")
        .append(if (org.adaway.broadcast.BootReceiver.ensureEnabled(context)) "已启用" else "无法启用")
        .append('\n')
    sb.append("服务器当前可达：")
        .append(if (org.adaway.util.WebServerUtils.isWebServerReachable(context)) "是" else "否")
        .append('\n')
    return sb.toString()
}
