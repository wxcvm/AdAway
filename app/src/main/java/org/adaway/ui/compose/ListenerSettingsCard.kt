package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.util.ListenerSettings
import org.adaway.util.WebServerUtils

@Composable
internal fun ListenerSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(WebServerUtils.getBindAddress(context)) }
    var all by remember { mutableStateOf(WebServerUtils.isBindAll(context)) }
    var http by remember { mutableStateOf(WebServerUtils.getHttpPort(context).toString()) }
    var https by remember { mutableStateOf(WebServerUtils.getHttpsPort(context).toString()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("填写本机 IP；留空使用回环或所有网卡。自定义 IP 优先于网卡开关。") }
    // Probe automatically only when root is already granted; opening settings must not prompt for root.
    LaunchedEffect(address, all, http, https) {
        delay(400)
        val candidate = runCatching { ListenerSettings(address, all, http.toInt(), https.toInt()) }
        message = candidate.exceptionOrNull()?.message ?: "配置尚未应用；正在检查监听地址。"
        if (candidate.isSuccess && !busy) {
            message = if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                withContext(Dispatchers.IO) { WebServerUtils.checkListenerSettings(context, candidate.getOrThrow()) }
            } else "配置尚未应用；点击检测可检查本机地址、权限及端口占用。管理端口固定为回环 8686。"
        }
    }
    fun act(apply: Boolean) {
        val settings = runCatching { ListenerSettings(address, all, http.toInt(), https.toInt()) }
            .getOrElse { message = it.message ?: "端口必须为数字。"; return }
        busy = true
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) {
                if (apply) WebServerUtils.applyListenerSettings(context, settings)
                else WebServerUtils.checkListenerSettings(context, settings)
            } }.getOrElse { "操作失败：${it.message ?: "请检查 Root 授权"}" }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(address, { address = it }, enabled = !busy, singleLine = true,
            label = { Text("监听 IP（IPv4 / IPv6）") }, placeholder = { Text("例如 127.0.0.1 或 ::1；留空使用下方设置") },
            modifier = Modifier.fillMaxWidth())
        Row {
            Text("所有网卡（IP 留空时）", modifier = Modifier.weight(1f))
            Switch(all, { all = it }, enabled = !busy && address.isBlank())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(http, { http = it }, enabled = !busy, singleLine = true,
                label = { Text("HTTP 端口") }, modifier = Modifier.weight(1f))
            OutlinedTextField(https, { https = it }, enabled = !busy, singleLine = true,
                label = { Text("HTTPS 端口") }, modifier = Modifier.weight(1f))
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ act(false) }, enabled = !busy) { Text("检测冲突") }
            Button({ act(true) }, enabled = !busy) { Text(if (busy) "正在处理…" else "应用并重启") }
        }
    }
}
