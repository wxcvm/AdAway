package org.adaway.ui.compose

import android.content.Context
import android.content.SharedPreferences
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
    configurationRevision: Long = 0L,
    claimDiagnostic: (Any) -> Boolean = { true },
    releaseDiagnostic: (Any) -> Unit = {},
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
    var resultStale by remember { mutableStateOf(false) }
    var configurationInvalidated by remember { mutableStateOf(false) }
    var savedTarget by remember { mutableStateOf(WebServerUtils.getListenerSettings(context).arguments()) }
    var generation by remember { mutableStateOf(0L) }
    var activeSession by remember { mutableStateOf<ListenerDiagnosticSession?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var activeClaim by remember { mutableStateOf<Any?>(null) }
    var result by remember { mutableStateOf("先按域名比对 AdAway 的已保留 Web 历史，再检查已保存的监听地址、HTTP、HTTPS 和系统 DNS。回执不代表所有应用的流量，检测不会修改 AdGuard。") }
    // A draft edit is not an applied target change. Also observe commits made outside this card.
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("compose_webserver", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("listen_ipv4", "listen_ipv6", "bind_ip", "bind_all", "http_port", "https_port")) {
                val target = WebServerUtils.getListenerSettings(context).arguments()
                scope.launch(Dispatchers.Main.immediate) { savedTarget = target }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        savedTarget = WebServerUtils.getListenerSettings(context).arguments()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(savedTarget, configurationRevision) {
        if (hasResult) resultStale = true
        if (activeSession != null) {
            configurationInvalidated = true
            cancelling = true
            status = "监听配置正在应用或已改变；正在取消旧目标检测…"
            activeSession?.cancel()
        } else if (hasResult) status = "监听配置发生应用操作或变更；保留的旧结果未复核当前服务，请重新检测。"
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            generation++
            activeSession?.cancel()
            job?.cancel()
            activeClaim?.let(releaseDiagnostic)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AdGuard 请求到达检测", style = MaterialTheme.typography.titleSmall)
        Text("先从 AdGuard 最近活动里选择一个实际显示“已拦截”的域名，记下时间，再输入下方。检测会先对照 AdAway 已保留的 Web 日志，再检查 DNS、HTTP 与 HTTPS 到达情况。",
            style = MaterialTheme.typography.bodySmall)
        Text("AdGuard 的 DNS / HTTPS / 页面元素拦截不一定经过 AdAway。日志为空只代表当前保留历史没有对应 Web 请求，不能判定 AdGuard 失效或 AdAway 漏记；预设域名也不保证被你的规则拦截。",
            style = MaterialTheme.typography.bodySmall)
        Text("HTTPS 严格校验证书及名称，再确认加密回执。QUIC / HTTP/3 当前未实现；TCP/443 监听不代表 UDP/443 可用。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(adblock, { adblock = it.take(254) }, enabled = enabled && !running, singleLine = true,
            label = { Text("广告规则测试域名（可修改或留空）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hosts, { hosts = it.take(254) }, enabled = enabled && !running, singleLine = true,
            label = { Text("Hosts 规则测试域名（可选）") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(start@{
                val claim = Any()
                if (!claimDiagnostic(claim)) return@start
                activeClaim = claim
                val id = ++generation
                val adblockInput = adblock
                val hostsInput = hosts
                val targetAtStart = WebServerUtils.getListenerSettings(context).arguments()
                running = true
                cancelling = false
                configurationInvalidated = false
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
                        if (generation == id && !session.isCancelled && targetAtStart == WebServerUtils.getListenerSettings(context).arguments()) {
                            result = report
                            hasResult = true
                            resultStale = false
                            status = "检测完成；结果仅对应本次测试路径。"
                        } else if (generation == id && !cancelling) {
                            resultStale = hasResult
                            status = "监听配置已改变；旧目标结果未发布，请重新检测。"
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        if (generation == id && !session.isCancelled)
                            status = "检测未完成：${exception.message ?: "请稍后重试"}"
                        else if (generation == id && !cancelling)
                            status = "检测已取消或监听配置已改变，请重新检测。"
                    } finally {
                        session.cancel()
                        releaseDiagnostic(claim)
                        if (generation == id) {
                            activeClaim = null
                            if (cancelling) status = if (configurationInvalidated)
                                "监听配置发生应用操作或变更；旧目标检测已取消，请重新检测。旧结果仅作历史记录。"
                            else "检测已取消，可重新检测；上次完成的结果保留。"
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
                OutlinedButton({ clipboard.setText(AnnotatedString(if (resultStale)
                    "历史结果：应用操作或配置变更后未复核，不代表当前服务。\n$result" else result)) }, modifier = Modifier.testTag("arrival-copy")) {
                    Text("复制结果")
                }
            }
        }
        if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("arrival-progress"))
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arrival-status"))
        if (hasResult) Text(if (resultStale) "历史结果（应用操作或配置变更后未复核）"
            else if (running) "上次完成的结果" else "检测结果", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arrival-report")) }
    }
}
