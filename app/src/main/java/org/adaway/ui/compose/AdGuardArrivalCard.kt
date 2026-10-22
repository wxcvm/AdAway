package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.util.WebServerUtils

@Composable
internal fun AdGuardArrivalCard(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adblock by remember { mutableStateOf("ad.doubleclick.net") }
    var hosts by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("按已保存的监听地址与端口检查 HTTP、HTTPS 与系统 DNS；回执确认本次检测路径，不代表所有应用。不会保存草稿或更改 AdGuard。") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AdGuard 请求到达检测", style = MaterialTheme.typography.titleSmall)
        Text("将 AdGuard 两类 DNS 拦截模式的自定义 IP 指向本机监听地址。可分别填写测试域名；预设域名不保证在你的规则中被拦截，命中类别以 AdGuard 过滤日志为准。",
            style = MaterialTheme.typography.bodySmall)
        Text("HTTPS 严格校验证书及名称，再确认加密回执。QUIC / HTTP/3 当前未实现；TCP/443 监听不代表 UDP/443 可用。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(adblock, { adblock = it.take(254) }, enabled = enabled && !running, singleLine = true,
            label = { Text("广告规则测试域名（可修改或留空）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hosts, { hosts = it.take(254) }, enabled = enabled && !running, singleLine = true,
            label = { Text("Hosts 规则测试域名（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton({
            running = true
            scope.launch {
                result = runCatching { withContext(Dispatchers.IO) { WebServerUtils.checkArrivalDiagnostics(context, adblock, hosts) } }
                    .getOrElse { "检测未完成：${it.message ?: "请稍后重试"}" }
                running = false
            }
        }, enabled = enabled && !running) { Text(if (running) "正在检查请求路径…" else "检测 DNS 与请求到达") }
        Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
