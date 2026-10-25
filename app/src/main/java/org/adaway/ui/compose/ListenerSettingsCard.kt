package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.util.ListenerSettings
import org.adaway.util.WebServerUtils

@Composable
internal fun ListenerSettingsCard(
    checkSettings: (android.content.Context, ListenerSettings) -> String = WebServerUtils::checkListenerSettings,
    applySettings: (android.content.Context, ListenerSettings, Boolean, Boolean) -> String = WebServerUtils::applyListenerSettings,
    rootGranted: () -> Boolean = { com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { WebServerUtils.getListenerSettings(context) }
    var ipv4 by remember { mutableStateOf(saved.ipv4) }
    var ipv6 by remember { mutableStateOf(saved.ipv6) }
    var lan by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<ListenerAddressSuggestion>()) }
    LaunchedEffect(lan) {
        if (lan) suggestions = withContext(Dispatchers.IO) { listenerAddressSuggestions() }
    }
    var http by remember { mutableStateOf(saved.http.toString()) }
    var https by remember { mutableStateOf(saved.https.toString()) }
    var busy by remember { mutableStateOf(false) }
    var probeGeneration by remember { mutableStateOf(0L) }
    var configurationRevision by remember { mutableStateOf(0L) }
    var autoCreate by remember { mutableStateOf(WebServerUtils.isListenerAutoCreate(context)) }
    var createConfirmation by remember { mutableStateOf<ListenerSettings?>(null) }
    var message by remember { mutableStateOf("推荐仅本机访问；IPv4、IPv6 可同时监听，留空关闭对应协议。") }
    // Probe automatically only when root is already granted; opening settings must not prompt for root.
    LaunchedEffect(ipv4, ipv6, http, https) {
        val id = probeGeneration
        delay(400)
        if (busy || id != probeGeneration) return@LaunchedEffect
        val candidate = runCatching { ListenerSettings(ipv4, ipv6, http.toInt(), https.toInt()) }
        message = candidate.exceptionOrNull()?.message ?: "配置尚未应用；正在检查监听地址。"
        if (candidate.isSuccess && !busy) {
            val report = if (rootGranted()) {
                withContext(Dispatchers.IO) { checkSettings(context, candidate.getOrThrow()) }
            } else "配置尚未应用；点击检测可检查本机地址、权限及端口占用。管理端口固定为回环 8686。"
            if (!busy && id == probeGeneration) message = report
        }
    }
    fun act(apply: Boolean, createMissing: Boolean = false, confirmed: ListenerSettings? = null) {
        val settings = confirmed ?: runCatching { ListenerSettings(ipv4, ipv6, http.toInt(), https.toInt()) }
            .getOrElse { message = it.message ?: "端口必须为数字。"; return }
        probeGeneration++
        // Applying may restart and roll back even when the saved values end up unchanged.
        if (apply) configurationRevision++
        busy = true
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) {
                if (apply) applySettings(context, settings, createMissing, autoCreate)
                else checkSettings(context, settings)
            } }.getOrElse { "操作失败：${it.message ?: "请检查 Root 授权"}" }
            busy = false
        }
    }
    createConfirmation?.let { candidate ->
        AlertDialog(onDismissRequest = { createConfirmation = null },
            title = { Text("创建缺失的本机地址？") },
            text = { Text("IPv4：${candidate.ipv4.ifEmpty { "关闭" }}\nIPv6：${candidate.ipv6.ifEmpty { "关闭" }}\n\n仅在 lo 回环接口添加缺失的私有 IPv4 / IPv6 ULA 地址（/32 或 /128），需要 Root。已有地址直接复用，不修改物理网卡、默认网关或 DNS。地址不是公网 IPv6，也不与 IPv4 自动配对。\n\n成功后保存并启动监听；失败清理本次创建的地址。更换监听地址后清理旧的托管地址；自动恢复由下方随启动创建选项控制，开机恢复还需启用开机脚本。") },
            confirmButton = { TextButton({ createConfirmation = null; act(true, true, candidate) }) { Text("创建并应用") } },
            dismissButton = { TextButton({ createConfirmation = null }) { Text("取消") } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("监听地址", style = MaterialTheme.typography.titleSmall)
        Text("推荐使用本机回环地址，适合设备自身的广告响应；管理端口始终仅本机可访问。",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ ipv4 = "127.0.0.1"; ipv6 = "::1" }, enabled = !busy) { Text("仅本机（推荐）") }
            TextButton({ lan = !lan }, enabled = !busy) { Text(if (lan) "收起网卡" else "选择网卡") }
        }
        TextButton({
            runCatching { ListenerSettings(ipv4, ipv6, http.toInt(), https.toInt()) }
                .onSuccess { createConfirmation = it }
                .onFailure { message = it.message ?: "端口必须为数字。" }
        }, enabled = !busy) { Text("地址不存在？创建并应用…") }
        OutlinedTextField(ipv4, { ipv4 = it }, enabled = !busy, singleLine = true,
            label = { Text("IPv4 监听地址") }, placeholder = { Text("127.0.0.1") },
            supportingText = { Text("留空关闭 IPv4；Root 重定向需要 IPv4 回环监听。") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ipv6, { ipv6 = it }, enabled = !busy, singleLine = true,
            label = { Text("IPv6 监听地址") }, placeholder = { Text("::1") },
            supportingText = { Text("留空关闭 IPv6；不需要填写方括号。") }, modifier = Modifier.fillMaxWidth())
        if (lan) {
            Text("以下为当前网卡地址，切换网络后可能变化；选择地址只修改对应协议的草稿。",
                style = MaterialTheme.typography.bodySmall)
            suggestions.forEach { item ->
                OutlinedButton({ if (item.ipv4) ipv4 = item.address else ipv6 = item.address }, enabled = !busy) {
                    Text("${item.network} · ${if (item.ipv4) "IPv4" else "IPv6"} ${item.address}")
                }
            }
            if (suggestions.isEmpty()) Text("未发现可用的局域网地址。", style = MaterialTheme.typography.bodySmall)
            TextButton({ ipv4 = "0.0.0.0"; ipv6 = "::" }, enabled = !busy) { Text("所有网卡（允许局域网访问）") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(http, { http = it }, enabled = !busy, singleLine = true,
                label = { Text("HTTP 端口") }, modifier = Modifier.weight(1f))
            OutlinedTextField(https, { https = it }, enabled = !busy, singleLine = true,
                label = { Text("HTTPS 端口") }, modifier = Modifier.weight(1f))
        }
        Row {
            Checkbox(checked = autoCreate, onCheckedChange = { autoCreate = it }, enabled = !busy)
            Column {
                Text("随服务器启动创建缺失地址", style = MaterialTheme.typography.bodyMedium)
                Text("应用后生效，需要 Root；只创建所填私有地址，不修改网关或 DNS。开机启动需另行启用开机脚本。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("listener-message"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ act(false) }, enabled = !busy) { Text("检测冲突") }
            Button({ act(true) }, enabled = !busy) { Text(if (busy) "正在处理…" else "应用并重启") }
        }
        AdGuardArrivalCard(enabled = !busy, configurationRevision = configurationRevision)
    }
}
