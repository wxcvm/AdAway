package org.adaway.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.util.WebServerUtils
import org.adaway.util.ListenerDiagnosticSession

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AdGuardArrivalCard(
    enabled: Boolean,
    runDiagnostic: (Context, String, String, ListenerDiagnosticSession) -> String = WebServerUtils::checkArrivalDiagnostics
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var adblock by remember { mutableStateOf("ad.doubleclick.net") }
    var hosts by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var hasResult by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0L) }
    var activeSession by remember { mutableStateOf<ListenerDiagnosticSession?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf("按已保存的监听地址与端口检查 HTTP、HTTPS 与系统 DNS；回执确认本次检测路径，不代表所有应用。不会保存草稿或更改 AdGuard。") }
    DisposableEffect(Unit) {
        onDispose {
            generation++
            activeSession?.cancel()
            job?.cancel()
        }
    }
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                val id = ++generation
                val adblockInput = adblock
                val hostsInput = hosts
                running = true
                cancelling = false
                status = "正在准备检测…"
                val session = ListenerDiagnosticSession { message ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == id && running && !cancelling) status = message
                    }
                }
                activeSession = session
                job = scope.launch {
                    try {
                        val report = withContext(Dispatchers.IO) {
                            runDiagnostic(context.applicationContext, adblockInput, hostsInput, session)
                        }
                        if (generation == id && !session.isCancelled) {
                            result = report
                            hasResult = true
                            status = "检测完成；结果仅对应本次测试路径。"
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        if (generation == id && !session.isCancelled)
                            status = "检测未完成：${exception.message ?: "请稍后重试"}"
                    } finally {
                        session.cancel()
                        if (generation == id) {
                            if (cancelling) status = "检测已取消，可重新检测；上次完成的结果保留。"
                            running = false
                            cancelling = false
                            activeSession = null
                            job = null
                        }
                    }
                }
            }, enabled = enabled && !running, modifier = Modifier.testTag("arrival-start")) {
                Text(if (running) "正在检查请求路径…" else "检测 DNS 与请求到达")
            }
            if (running) {
                OutlinedButton({
                    cancelling = true
                    status = "正在取消并清理本次连接…"
                    activeSession?.cancel()
                }, enabled = !cancelling, modifier = Modifier.testTag("arrival-cancel")) { Text("取消检测") }
            }
            if (hasResult) {
                OutlinedButton({ clipboard.setText(AnnotatedString(result)) }, modifier = Modifier.testTag("arrival-copy")) {
                    Text("复制结果")
                }
            }
        }
        if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("arrival-progress"))
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arrival-status"))
        if (hasResult) Text(if (running) "上次完成的结果" else "检测结果", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arrival-report")) }
    }
}
