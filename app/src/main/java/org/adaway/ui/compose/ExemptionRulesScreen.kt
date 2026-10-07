package org.adaway.ui.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType
import org.adaway.model.rules.ExemptionRules

/**
 * 规则页的「解除过滤」二级页：按**域名**放行（uid 放行在设置页的应用监控里）。
 *
 * <p>为什么只在劫持过滤模式可用：只有该模式服务器才连回真实源站
 * （`--proxy-filter`），放行一个域才真的把它恢复；普通 127.0.0.1 模式下服务器
 * 拿不到内容，放行只是"不再拦"而已。因此非劫持模式里入口仍然可见，但输入与按钮
 * 禁用并写明原因——藏起来会让用户以为功能坏了。</p>
 *
 * <p>规则只写规则库（单一事实来源），`domain_allowlist.txt` 由
 * `WebServerUtils.exportBlockList()` 派生；原生服务器每次请求都重读该文件，
 * 所以改完立即生效、不需要重启。</p>
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExemptionRulesScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled = remember { ExemptionRules.isEnabled(context) }
    var input by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    var rules by remember { mutableStateOf<List<HostListItem>>(emptyList()) }
    val allowedType = ListType.ALLOWED.value

    fun reload() {
        viewModel.loadRulesByType(allowedType, 500) { items, _ -> rules = items }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_exempt_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.compose_subpage_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_exempt_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!enabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.compose_exempt_needs_hijack),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            parsed = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        enabled = enabled,
                        label = { Text(stringResource(R.string.compose_exempt_input_label)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = enabled && input.isNotBlank(),
                            onClick = { parsed = ExemptionRules.parseAll(input) },
                        ) { Text(stringResource(R.string.compose_exempt_preview)) }
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            enabled = enabled && input.isNotBlank(),
                            onClick = {
                                val (accepted, rejected) = ExemptionRules.parseAll(input)
                                if (accepted.isEmpty()) {
                                    parsed = accepted to rejected
                                } else {
                                    viewModel.addExemptionRules(accepted) { added ->
                                        parsed = emptyList<String>() to rejected
                                        input = ""
                                        reload()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_exempt_added, added),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.compose_exempt_apply)) }
                    }
                    parsed?.let { (accepted, rejected) ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.compose_exempt_preview_ok, accepted.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (rejected.isNotEmpty()) {
                            Text(
                                stringResource(R.string.compose_exempt_preview_bad, rejected.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            rejected.take(5).forEach { line ->
                                Text(
                                    "· $line",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.compose_exempt_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(rules, key = { it.host ?: "" }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                item.host ?: "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(
                                enabled = enabled,
                                onClick = { viewModel.removeRule(item.host ?: "") { reload() } },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.compose_exempt_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
