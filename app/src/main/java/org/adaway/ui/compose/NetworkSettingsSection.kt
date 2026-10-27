package org.adaway.ui.compose

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import kotlinx.coroutines.CoroutineScope
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.adaway.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import androidx.compose.foundation.layout.ColumnScope

/** Category content only. Navigation and saveable scroll keys stay in SettingsScreen. */
@Composable
internal fun ColumnScope.NetworkSettingsSection(viewModel: StatsViewModel, actions: NetworkSettingsActions, onPreferencesChanged: () -> Unit) {
    val context = LocalContext.current
    val state = actions.state
    // Explicitly opt-in network-path diagnostic. Routes only one
    // benchmark destination; no default route, DNS or TCP/UDP forwarding.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_tun_diagnostic_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_tun_diagnostic_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Re-evaluate on explicit Refresh or a UI action. The OS
            // notification also provides a persistent Stop control.
            val diagnosticActive = remember(state.refreshKey) {
                org.adaway.model.traffic.DiagnosticVpnService.isActive()
            }
            val packets = remember(state.refreshKey) {
                org.adaway.model.traffic.DiagnosticVpnService.getPacketCount()
            }
            Text(
                stringResource(
                    if (diagnosticActive)
                        R.string.compose_tun_diagnostic_running
                    else
                        R.string.compose_tun_diagnostic_stopped,
                    packets,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !diagnosticActive,
                    onClick = {
                        state.scope.launch {
                            // Root's isEnabled() runs a shell: never do
                            // this blocking check on the Compose UI thread.
                            val noConflict = withContext(Dispatchers.IO) {
                                val owner = org.adaway.model.traffic.AndroidVpnObserver
                                    .read(context, false)
                                owner == org.adaway.model.traffic.TrafficModePolicy.VpnOwner.NONE &&
                                    !org.adaway.model.root.HijackModel.isEnabled()
                            }
                            if (!noConflict) {
                                state.status = context.getString(
                                    R.string.compose_tun_diagnostic_conflict,
                                )
                            } else {
                                try {
                                    val consent = android.net.VpnService.prepare(context)
                                    if (consent == null) {
                                        org.adaway.model.traffic.DiagnosticVpnService.start(context)
                                        state.status = context.getString(
                                            R.string.compose_tun_diagnostic_start_requested,
                                        )
                                        state.refreshKey++
                                    } else {
                                        actions.consentLauncher.launch(consent)
                                    }
                                } catch (exception: RuntimeException) {
                                    Timber.w(exception, "Diagnostic VPN consent failed")
                                    state.status = context.getString(
                                        R.string.compose_tun_diagnostic_start_failed,
                                    )
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.compose_tun_diagnostic_start)) }
                OutlinedButton(
                    enabled = diagnosticActive,
                    onClick = {
                        org.adaway.model.traffic.DiagnosticVpnService.stop(context)
                        state.status = context.getString(R.string.compose_tun_diagnostic_stop_requested)
                        state.refreshKey++
                    },
                ) { Text(stringResource(R.string.compose_tun_diagnostic_stop)) }
                TextButton(onClick = { state.refreshKey++ }) {
                    Text(stringResource(R.string.compose_settings_hijack_refresh_status))
                }
            }
            DiagnosticTcpActions(diagnosticActive, state.tcpBusy) { ipv6 ->
                state.tcpBusy = true
                state.scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            org.adaway.model.traffic.DiagnosticVpnService.probeTcp(ipv6)
                        }
                        val status = when (result.status) {
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.OK -> R.string.compose_tcp_ok
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.CANCELLED -> R.string.compose_tcp_cancelled
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.TIMEOUT -> R.string.compose_tcp_timeout
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.REJECTED -> R.string.compose_tcp_rejected
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.CLEANUP_FAILED -> R.string.compose_tcp_cleanup_failed
                            org.adaway.model.traffic.DiagnosticTcpSession.Status.UNAVAILABLE -> R.string.compose_tcp_unavailable
                            else -> R.string.compose_tcp_failed
                        }
                        state.status = context.getString(R.string.compose_tcp_result, if (ipv6) "IPv6" else "IPv4",
                            context.getString(status), result.elapsedMs, result.closed, result.opened, result.protectedSockets, result.stage)
                        state.refreshKey++
                    } finally { state.tcpBusy = false }
                }
            }
            TextButton(
                enabled = diagnosticActive,
                onClick = {
                    state.scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            org.adaway.model.traffic.DiagnosticTunProbe.roundTrip()
                        }
                        state.status = context.getString(
                            if (ok) R.string.compose_tun_diagnostic_echo_ok
                            else R.string.compose_tun_diagnostic_echo_failed,
                        )
                        state.refreshKey++
                    }
                },
            ) {
                Text(stringResource(R.string.compose_tun_diagnostic_test_echo))
            }
            DiagnosticRelayActions(diagnosticActive, state.relayBusy) { ipv6 ->
                state.relayBusy = true
                state.scope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            org.adaway.model.traffic.DiagnosticTunProbe.relayRoundTrip(ipv6)
                        }
                        state.status = context.getString(
                            if (ok) R.string.compose_tun_diagnostic_relay_family_ok
                            else R.string.compose_tun_diagnostic_relay_family_failed,
                            if (ipv6) "IPv6" else "IPv4",
                        )
                        state.refreshKey++
                    } finally { state.relayBusy = false }
                }
            }
            for (type in listOf(
                org.adaway.model.traffic.DiagnosticDnsCodec.TYPE_A,
                org.adaway.model.traffic.DiagnosticDnsCodec.TYPE_AAAA,
            )) {
                TextButton(
                    enabled = diagnosticActive && !state.dnsBusy,
                    onClick = {
                        state.dnsBusy = true
                        state.scope.launch {
                            try {
                                val rcode = withContext(Dispatchers.IO) {
                                    org.adaway.model.traffic.DiagnosticTunProbe.dnsResponseCode(type)
                                }
                                val result = context.getString(
                                    when (rcode) {
                                        0 -> R.string.compose_tun_diagnostic_dns_ok
                                        3 -> R.string.compose_tun_diagnostic_dns_nxdomain
                                        5 -> R.string.compose_tun_diagnostic_dns_refused
                                        else -> R.string.compose_tun_diagnostic_dns_failed
                                    },
                                )
                                state.status = context.getString(
                                    R.string.compose_tun_diagnostic_dns_result,
                                    if (type == 1) "A" else "AAAA", result,
                                )
                                state.refreshKey++
                            } finally {
                                state.dnsBusy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(
                        if (type == 1) R.string.compose_tun_diagnostic_test_dns
                        else R.string.compose_tun_diagnostic_test_dns_aaaa,
                    ))
                }
            }
            if (state.status.isNotBlank()) {
                Text(state.status, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

// ── 拦截模式：127.0.0.1（本机拦截页）/ 0.0.0.0（空路由，可与 AdGuard 等共存）──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.compose_settings_block_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.compose_settings_block_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // 与 AdGuard 共存：检测到（已安装 + VPN 在跑）就给出可直接照抄的配置
            val adguardActive = remember {
                org.adaway.util.AdGuardPresence.shouldSuggest(context)
            }
            var adguardDismissed by remember {
                mutableStateOf(org.adaway.util.AdGuardPresence.isHintDismissed(context))
            }
            if (adguardActive && !adguardDismissed) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.compose_settings_adguard_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.compose_settings_adguard_steps,
                                org.adaway.util.AdGuardPresence.suggestedIpv4(),
                                org.adaway.util.AdGuardPresence.suggestedIpv6(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(onClick = {
                                val text = org.adaway.util.AdGuardPresence.suggestedIpv4() +
                                    " / " + org.adaway.util.AdGuardPresence.suggestedIpv6()
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("AdGuard", text),
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.compose_settings_adguard_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) { Text(stringResource(R.string.compose_settings_adguard_copy)) }
                            androidx.compose.material3.TextButton(onClick = {
                                org.adaway.util.AdGuardPresence.dismissHint(context)
                                adguardDismissed = true
                            }) { Text(stringResource(R.string.compose_settings_adguard_dismiss)) }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.compose_settings_block_mode_adguard_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            var blockMode by remember { mutableStateOf(viewModel.currentBlockMode()) }
            var modeProbeKey by remember { mutableIntStateOf(0) }
            var observedVpn by remember {
                mutableStateOf(org.adaway.model.traffic.TrafficModePolicy.VpnOwner.UNKNOWN)
            }
            var redirectInstalled by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(blockMode, modeProbeKey) {
                val status = withContext(Dispatchers.IO) {
                    val owner = org.adaway.model.traffic.AndroidVpnObserver.read(context, false)
                    val active = if (blockMode == org.adaway.util.BlockMode.HIJACK) {
                        org.adaway.model.root.HijackModel.isEnabled()
                    } else false
                    owner to active
                }
                observedVpn = status.first
                redirectInstalled = status.second
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = blockMode == org.adaway.util.BlockMode.LOCALHOST,
                    onClick = {
                        val want = org.adaway.util.BlockMode.LOCALHOST
                        blockMode = want
                        viewModel.applyBlockMode(want) { ok ->
                            /*
                             * 失败时不能留着已选中的假象：hosts 应用失败或没有 root 时
                             * 用户会以为已经切过去了。这里统一回读真实模式并如实提示，
                             * 不再只有 HIJACK 才报错。
                             */
                            blockMode = viewModel.currentBlockMode()
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.compose_settings_block_mode_apply_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    label = {
                        // S5：推荐档提到最前并改名——它是"回答被拦域"的最省资源方式
                        Text(stringResource(R.string.compose_settings_block_mode_recommended))
                    },
                )
                FilterChip(
                    selected = blockMode == org.adaway.util.BlockMode.NULL_ROUTE,
                    onClick = {
                        val want = org.adaway.util.BlockMode.NULL_ROUTE
                        blockMode = want
                        viewModel.applyBlockMode(want) { ok ->
                            /*
                             * 失败时不能留着已选中的假象：hosts 应用失败或没有 root 时
                             * 用户会以为已经切过去了。这里统一回读真实模式并如实提示，
                             * 不再只有 HIJACK 才报错。
                             */
                            blockMode = viewModel.currentBlockMode()
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.compose_settings_block_mode_apply_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    label = {
                        Text(stringResource(R.string.compose_settings_block_mode_null))
                    },
                )
                // Root 透明重定向当前仅覆盖符合条件的 TCP 80/443，不包含全部 UDP/QUIC
                FilterChip(
                    selected = blockMode == org.adaway.util.BlockMode.HIJACK,
                    onClick = {
                        blockMode = org.adaway.util.BlockMode.HIJACK
                        viewModel.applyBlockMode(org.adaway.util.BlockMode.HIJACK) { ok ->
                            blockMode = viewModel.currentBlockMode()
                            modeProbeKey++
                            if (!ok) {
                                // VPN 冲突 / 端口 / Root 失败都不能当作已接管
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.compose_settings_block_mode_hijack_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    label = {
                        Text(stringResource(R.string.compose_settings_block_mode_hijack))
                    },
                )
            }
            if (blockMode == org.adaway.util.BlockMode.HIJACK) {
                val stateMessage = when {
                    observedVpn == org.adaway.model.traffic.TrafficModePolicy.VpnOwner.OTHER ->
                        R.string.compose_settings_hijack_paused_for_vpn
                    observedVpn == org.adaway.model.traffic.TrafficModePolicy.VpnOwner.UNKNOWN ->
                        R.string.compose_settings_hijack_owner_unknown
                    redirectInstalled == true ->
                        R.string.compose_settings_hijack_active_verified
                    else ->
                        R.string.compose_settings_hijack_inactive
                }
                Text(
                    stringResource(stateMessage),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { modeProbeKey++ }) {
                    Text(stringResource(R.string.compose_settings_hijack_refresh_status))
                }
            }
            // ── 自定义重定向地址（hosts 档：被拦域指向哪里）──
            var customIpv4 by remember { mutableStateOf(org.adaway.util.BlockMode.ipv4Target(context)) }
            var customIpv6 by remember { mutableStateOf(org.adaway.util.BlockMode.ipv6Target(context)) }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = blockMode == org.adaway.util.BlockMode.CUSTOM,
                    onClick = {
                        blockMode = org.adaway.util.BlockMode.CUSTOM
                        customIpv4 = org.adaway.util.BlockMode.ipv4Target(context)
                        customIpv6 = org.adaway.util.BlockMode.ipv6Target(context)
                    },
                    label = { Text(stringResource(R.string.compose_settings_block_mode_custom)) },
                )
            }
            if (blockMode == org.adaway.util.BlockMode.CUSTOM) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = customIpv4,
                    onValueChange = { customIpv4 = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = customIpv4.isNotBlank() &&
                        !org.adaway.util.BlockMode.isValidTarget(customIpv4),
                    label = { Text(stringResource(R.string.compose_settings_custom_ipv4)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = customIpv6,
                    onValueChange = { customIpv6 = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = customIpv6.isNotBlank() &&
                        !org.adaway.util.BlockMode.isValidTarget(customIpv6),
                    label = { Text(stringResource(R.string.compose_settings_custom_ipv6)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.compose_settings_custom_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    enabled = org.adaway.util.BlockMode.isValidTarget(customIpv4) &&
                        org.adaway.util.BlockMode.isValidTarget(customIpv6),
                    onClick = {
                        viewModel.applyCustomAddresses(customIpv4, customIpv6) { ok ->
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.compose_settings_custom_saved
                                    else R.string.compose_settings_custom_failed,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text(stringResource(R.string.compose_settings_custom_apply)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (blockMode) {
                        org.adaway.util.BlockMode.NULL_ROUTE ->
                            R.string.compose_settings_block_mode_null_hint
                        org.adaway.util.BlockMode.CUSTOM ->
                            R.string.compose_settings_block_mode_custom_hint
                        org.adaway.util.BlockMode.HIJACK ->
                            R.string.compose_settings_block_mode_hijack_hint
                        else -> R.string.compose_settings_block_mode_localhost_hint
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 劫持过滤走 HTTPS 中间人：没有信任本机 CA 时会提示
            if (blockMode == org.adaway.util.BlockMode.HIJACK &&
                !org.adaway.util.WebServerUtils.isUserCertificateInstalled(context) &&
                !org.adaway.util.WebServerUtils.isSystemCertificateInstalled(context)
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.compose_settings_block_mode_hijack_ca),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // ── Allowlist (bypass blocking) ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.compose_settings_allowlist_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.compose_settings_allowlist_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 默认白名单（系统门户登录，自动维护）
            Text(
                stringResource(R.string.compose_settings_allowlist_default),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            defaultAllowlistUids(context).forEach { uid ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        appNameForUid(uid),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.compose_settings_allowlist_uid, uid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 用户放行的应用（可移除）
            Text(
                stringResource(R.string.compose_settings_allowlist_user),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val userUids = userAllowedUids(context)
            if (userUids.isEmpty()) {
                Text(
                    stringResource(R.string.compose_settings_allowlist_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                userUids.forEach { uid ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            appNameForUid(uid),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.compose_settings_allowlist_uid, uid),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = {
                            setAppAllowed(context, uid, false)
                            onPreferencesChanged()
                        }) {
                            Icon(
                                Icons.Outlined.RemoveCircle,
                                contentDescription = stringResource(R.string.compose_settings_allowlist_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticRelayActions(active: Boolean, busy: Boolean, onProbe: (Boolean) -> Unit) {
    Column {
        for (ipv6 in listOf(false, true)) TextButton(enabled = active && !busy, onClick = { onProbe(ipv6) }) {
            Text(stringResource(R.string.compose_tun_diagnostic_test_relay_family, if (ipv6) "IPv6" else "IPv4"))
        }
    }
}

@Composable
internal fun DiagnosticTcpActions(active: Boolean, busy: Boolean, onProbe: (Boolean) -> Unit) {
    Column {
        Text(stringResource(R.string.compose_tcp_description), style = MaterialTheme.typography.bodySmall)
        for (ipv6 in listOf(false, true)) TextButton(enabled = active && !busy, onClick = { onProbe(ipv6) }) {
            Text(stringResource(R.string.compose_tcp_test, if (ipv6) "IPv6" else "IPv4"))
        }
    }
}


/** Retained by SettingsScreen, not by a transient category: pending consent and
 * an already-started diagnostic must not silently lose their result on navigation. */
internal class NetworkSettingsState(val scope: CoroutineScope) {
    var refreshKey by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var relayBusy by mutableStateOf(false)
    var tcpBusy by mutableStateOf(false)
    var dnsBusy by mutableStateOf(false)
}
internal class NetworkSettingsActions(
    val state: NetworkSettingsState,
    val consentLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>,
)
@Composable
internal fun rememberNetworkSettingsActions(): NetworkSettingsActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(scope) { NetworkSettingsState(scope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                org.adaway.model.traffic.DiagnosticVpnService.start(context)
                state.status = context.getString(R.string.compose_tun_diagnostic_start_requested)
            } catch (exception: RuntimeException) {
                Timber.w(exception, "Diagnostic VPN start failed")
                state.status = context.getString(R.string.compose_tun_diagnostic_start_failed)
            }
        } else {
            state.status = context.getString(R.string.compose_tun_diagnostic_not_authorized)
        }
        state.refreshKey++
    }
    return remember(state, launcher) { NetworkSettingsActions(state, launcher) }
}
