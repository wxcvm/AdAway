package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.model.rules.ExemptionRules

/**
 * 二级页：按域覆盖响应（`rule_overrides.txt`）。
 *
 * <p>层级：一级（底部导航「规则」）→ 二级（本页）→ 返回键/按钮回到规则页。
 * 之所以从「解除过滤」页里拆出来：那是"放行"（不需要拦截），这里是"换个方式拦"
 * （仍然拦截，但改用指定状态码回答），两件事的目标相反，放在同一页容易误操作。</p>
 *
 * <p>只写规则文件；服务端要打开「按域覆盖响应」开关（默认关闭）才会读它。</p>
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleOverridesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(ExemptionRules.OVERRIDE_POLICIES.first()) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { lines = ExemptionRules.listOverrides(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_settings_rule_override)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.compose_subpage_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
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
                        stringResource(R.string.compose_settings_rule_override_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        /* 策略直接用状态码当标签：与原生认的名字同一套，不需要新增文案 */
                        ExemptionRules.OVERRIDE_POLICIES.forEach { p ->
                            FilterChip(
                                selected = policy == p,
                                onClick = { policy = p },
                                label = { Text(p) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            /*
                             * 保存要按结果决定后续动作：addOverride() 在域名无效或写文件失败时
                             * 返回空串，旧代码不看返回值就清空输入，用户会以为已经保存成功。
                             */
                            val saved = ExemptionRules.addOverride(context, input, policy)
                            if (saved.isNotEmpty()) {
                                input = ""
                                lines = ExemptionRules.listOverrides(context)
                                /*
                                 * 文件里有规则不等于服务端会应用：真正生效还要靠总开关
                                 * rule_override_enabled，所以提示里必须把这条依赖说清楚。
                                 */
                                /*
                                 * 按真实状态提示：规则写进文件不等于生效，生效还要看总开关。
                                 * isRuleOverrideEnabled() 读的就是写进 block_config.json 的那个键，
                                 * 所以这里给出的是当前事实，而不是一句泛泛的说明。
                                 */
                                val overrideOn =
                                    org.adaway.util.WebServerUtils.isRuleOverrideEnabled(context)
                                android.widget.Toast.makeText(
                                    context,
                                    if (overrideOn) R.string.compose_override_saved
                                    else R.string.compose_override_saved_disabled,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    R.string.compose_override_invalid,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        enabled = input.isNotBlank(),
                    ) { Text(stringResource(R.string.compose_exempt_apply)) }

                    Spacer(Modifier.height(10.dp))
                    if (lines.isEmpty()) {
                        Text(
                            stringResource(R.string.compose_settings_allowlist_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        lines.forEach { line ->
                            Row {
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    ExemptionRules.removeOverride(context, line)
                                    lines = ExemptionRules.listOverrides(context)
                                }) { Text(stringResource(R.string.compose_settings_allowlist_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
