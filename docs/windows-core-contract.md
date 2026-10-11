# Windows / Android 共享核心契约（F 第一步：先冻结，再迁出）

本文件由脚本从真实源码抽取生成（不是手写清单）：两个端口的实现若与本文不一致，以代码为准并立刻更新本文。

## 1. block_config.json（阻断响应策略）

权威来源：app/src/main/java/org/adaway/util/WebServerUtils.java（写入）与 webserver/jni/webserver.c（读取）。
两端必须以同一组键名对接；新增键必须同时出现在两侧，否则该功能会在一端静默失效。共 16 个键：
- `cache_reuse_enabled`
- `circuit_breaker_enabled`
- `dedup_enabled`
- `reply_api`
- `reply_config`
- `reply_fonts`
- `reply_images`
- `reply_media`
- `reply_scripts`
- `reply_structures`
- `reply_styles`
- `reply_telemetry`
- `reply_visible`
- `reply_ws_sse`
- `retry_guard_enabled`
- `rule_override_enabled`

## 2. /internal-stats 的 JSON 字段

权威来源：webserver/jni/webserver.c 中构造该文档的代码；共 68 个字段。
客户端只读需要的字段，允许服务端新增字段（向后兼容），但不得改变既有字段的含义。

- `action`
- `active_connections`
- `apps`
- `block_image_count`
- `block_rate`
- `blocked`
- `blocked_api`
- `blocked_clickbait`
- `blocked_config`
- `blocked_crypto`
- `blocked_fonts`
- `blocked_heartbeat`
- `blocked_images`
- `blocked_media`
- `blocked_other`
- `blocked_scripts`
- `blocked_styles`
- `blocked_telemetry`
- `blocked_ws_sse`
- `breaker_hits`
- `cache_hits`
- `certs`
- `connections`
- `count`
- `daily`
- `daily_peak`
- `dedup_hits`
- `deny`
- `entries`
- `error`
- `guard_hits`
- `h`
- `history`
- `host`
- `hosts`
- `limit`
- `loaded`
- `mode`
- `n`
- `offset`
- `ok`
- `on`
- `pin_refused`
- `qlog`
- `query_log`
- `recent_tls`
- `requests`
- `sni_cache_hits`
- `sni_certs_issued`
- `sni_hit_rate`
- `stats`
- `tls_fail`
- `tls_failures`
- `tls_handshakes`
- `tls_hosts`
- `tls_ok`
- `top_blocked`
- `total`
- `total_blocked`
- `total_connections`
- `total_requests`
- `tps`
- `true`
- `ts`
- `type`
- `uid`
- `uptime_days`
- `uptime_seconds`

## 3. 规则与策略文件（以源码为准）

C 服务端读取的文件（名称取自源码三种写法：宏定义、裸字面量、内联路径 "%s/xxx.txt"）：

| 文件 | 用途 | 写入方 | C 端读取处 |
|---|---|---|---|
| `pin_policy.txt` | 固定证书（pin）策略：域名与模式 | 应用/用户 | PIN_POLICY_FILE 宏 |
| `rule_overrides.txt` | 按域覆盖响应（自定义格式） | 应用按域覆盖页 | RULE_OVERRIDE_FILE 宏 |
| `domain_allowlist.txt` | 按域名豁免（ALLOWED 条目导出） | 应用 ExemptionRules | 内联路径 "%s/domain_allowlist.txt" |
| `allowlist.txt` | 按 uid 放行（每行一个十进制 uid） | 应用 SettingsScreen | 内联路径 "%s/allowlist.txt" |
| `blocklist.txt` | 拦截列表本体（服务端 block_set_contains 检索的主机集合） | 应用导出规则库 | 内联路径 "%s/blocklist.txt" |

**抽取口径的教训**：本节第一版只匹配宏定义与裸字面量，漏掉了内联拼接的路径，导致把真实存在的
domain_allowlist.txt 误标为缺失；补上内联路径写法后，又多抽出了此前从未列出的 allowlist.txt 与
blocklist.txt。结论：文档与契约都必须从代码抽取，并覆盖多种写法。

## 4. 规则语义子集（两端一致）

以 docs/response-policies.md 的《规则支持清单》为准：支持 hosts 格式、裸域名、||domain^（取主机名）、
|domain|（头尾锚点都剥）、URL/scheme 剥离；带资源类型修饰符（$…）的规则在两条路径上都跳过并计数，
不降级为整域拦截。两端共用同一份归一化实现（webserver/jni/rule_norm.h），宿主测试
webserver/jni/rule_norm_test.c 在 CI 中执行。

## 5. 迁出时的硬约束（来自用户要求与既有教训）

- 不把 webserver.c 当共享 Core：它同时承担平台分支、监听、代理、统计与系统适配；
- Windows 仓库保留自己的 CI / 版本号 / Release，且 gh release create 必须带 --latest=false
  （否则会抢走 Android 的 latest，曾经真的发生过）；
- 所有 Action 固定到 commit SHA，文件行尾 LF；
- 迁出后原仓库移除 Windows 目录与流水线，README 指向新仓库。
