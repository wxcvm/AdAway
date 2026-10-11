# Windows / Android 共享核心契约（F 第一步：先冻结，再迁出）

本文件由脚本从真实源码中抽取生成（不是手写清单）：两个端口的实现若与本文不一致，以代码为准
并立刻更新本文 —— 契约的价值在于可核对。

## 1. block_config.json（阻断响应策略）

权威来源：app/src/main/java/org/adaway/util/WebServerUtils.java（写入）与 webserver/jni/webserver.c（读取）。
两端必须以同一组键名对接；新增键必须同时出现在两侧，否则该功能会在一端静默失效。

共 16 个键：
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

权威来源：webserver/jni/webserver.c 中构造该文档的代码。字段名由脚本从源码抽取，共 68 个。
客户端解析只读需要的字段，允许服务端新增字段（向后兼容），但不得改变既有字段的含义。

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

C 服务端实际引用的文件（宏名与文件名均取自源码）：

| 文件名 | 定义处 |
|---|---|
| `pin_policy.txt` | `PIN_POLICY_FILE` |
| `rule_overrides.txt` | `RULE_OVERRIDE_FILE` |

**待澄清（迁出前必须查清）**：

- `domain_allowlist.txt`：早期文档（含 docs/response-policies.md）点名过它，但 C 源码里找不到。
  需要查清它究竟由谁写入、谁读取（可能是应用侧功能，或文件名与文档不符）；
  在那之前不要把它写进契约，也不要假设 Windows 端有对应实现。

## 4. 规则语义子集（两端一致）

以 docs/response-policies.md 的《规则支持清单》为准：支持 hosts 格式、裸域名、||domain^（取主机名）、
|domain|（头尾锚点都剥）、URL/scheme 剥离；带资源类型修饰符（$…）的规则在两条路径上都跳过并计数，
不降级为整域拦截。两端共用同一份归一化实现（webserver/jni/rule_norm.h），并有宿主机测试
webserver/jni/rule_norm_test.c 在 CI 中执行。

## 5. 迁出时的硬约束（来自用户要求与既有教训）

- 不把 webserver.c 当共享 Core：它同时承担平台分支、监听、代理、统计与系统适配；
- Windows 仓库保留自己的 CI / 版本号 / Release，且 gh release create 必须带 --latest=false
  （否则会抢走 Android 的 latest，曾经真的发生过）；
- 所有 Action 固定到 commit SHA，文件行尾 LF；
- 迁出后原仓库移除 Windows 目录与流水线，README 指向新仓库；
- 上面第 3 节的待澄清项必须先查清，否则不要迁出。
