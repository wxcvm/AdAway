# Windows / Android 共享核心契约（F 第一步：先冻结，再迁出）

## M0/M1 草稿升级说明

Android #45 与 Windows #3 是 M0 安全修复草稿；Android #46 提出新的域名决策/数据接口。
Windows M1 草稿用 `fetch-core-contract.sh` 独立固定契约与测试文件（`c2bff508a1607c2f7a0ce19ef96109a50fa202b2`），保存在验证过的 `shared-contract/` 缓存中。
`fetch-upstream.sh` 的服务端 pin 保持 `4470606366e614abdb308fdfe94734d28167439c`。尝试整体升级时，真实 Windows CI 发现新核心直接依赖 Android 头文件，因此未采用该升级；服务端跨平台兼容改造需要单独审查。默认发布分支仍未改变。
M0 新脚本直接 fetch 提交对象，不依赖移动分支当前 HEAD；下面的“分支前移即失败”是旧脚本的历史行为。
升级须通过固定对象/缓存哈希校验、规则与核心契约测试、完整 Windows 编译和冒烟；不得自动合并或发布。
详情：[M1 统一接口与决策](traffic-core-contract-v1.md)。

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

## 6. 迁出状态（F 项）

**已完成**（2026-10-11）：

- 独立仓库：https://github.com/wxcvm/AdAway-Windows （public，默认分支 main），保存 Windows 端的
  12 个文件 + 发布流水线；版本号仍三处一致（gui_win32.h / installer.iss / windows-release.yml 的 TAG）。
- 独立 CI 已跑通一次完整发布：`win11-webserver-v1.74` 产出 `adblock-webserver-win64.zip` 与
  `adblock-webserver-setup.exe`，并发布固定标签 `win11-latest` 的 `windows-manifest.json`（应用内更新入口）。
- 已从 AdAway 仓库的 `feat/win11-webserver-release` 分支移除 `webserver/win32/`（12 个文件）与
  `.github/workflows/windows-release.yml`；`webserver/jni`（C 服务端核心）**保留**，未抽成共享 Core。
- Windows 仓库的 CI 按**固定提交** `4470606366e614abdb308fdfe94734d28167439c`（该分支**迁出后**的 HEAD，即移除 webserver/win32 与 windows-release.yml 的那次提交）
  取出 `webserver/jni` 与 `app/src/main/assets`（webroot 资源），目的路径与 AdAway 内一致，因此两端
  引用同一份源码；取完立即 `rev-parse` 断言取到的就是被 pin 的那一个提交 —— 分支前移会**大声失败**，
  强制有意识地重新 pin（改 pin 时请同步更新本节的提交号）。
- **pin 的更新记录**：最初 pin 的是迁出前的 HEAD `147f9a10…`；随后从该分支移除 Windows 目录的那次提交
  把分支推进到 `4470606366e614abdb308fdfe94734d28167439c`（仍保留 `webserver/jni`，jni 9 个文件、assets 13 个
  文件），新仓库 CI 的 pin 断言因此**大声失败**并要求重新 pin —— 这正是期望行为：分支一动就停止构建，而不是
  悄悄换上别的代码。重新 pin 前已核实新 HEAD 仍含 C 核心与 webroot 资源。
- `gh release create` 一律带 `--latest=false`：已从外部核实 AdAway 仓库的 latest 仍是 Android 的发布，
  Windows 的发布不会抢走它（历史上真的发生过，这条是硬约束）。

**尚未完成 / 已知事项**（如实记录，不要当成已完成）：

- ~~Code-sign 步骤缺 shell~~ **已核实为误报，无需修改**：Windows 仓库里每个含 `run` 的步骤都显式声明了
  解释器 —— `shell: bash`（版本断言 / 静态检查 / 取上游 / 打包 / 发布 / 清单 / 清理）、
  `shell: msys2 {0}`（编译、PE 加固断言、**Code-sign**，即 MSYS2 的 bash）、
  `shell: pwsh`（冒烟测试、端口冲突、自启动、Inno Setup 打包与挂载）。
  这条曾被我写成"已知问题"，原因是我的检查只认 `shell == "bash"`，把 `msys2 {0}` 误判为"未声明"；核实后撤回。
- Windows 仓库的 `Check wide-printf specifiers` 静态检查跑在"取上游"之前，因此暂时**不覆盖** C 核心文件
  （只覆盖 gui_win32.c / update_win32.c）；覆盖由"取上游"步骤自身的存在性与大小断言（>100KB）兜底。
- ~~原仓库 README 的 Windows 段落未改写~~ **已处理，且与我原来的判断不同**：README 原本**完全没有**
  提到 Windows（1764 字符里没有任何 Windows/win11 字样），所以不是「改写段落」而是**新增一节**，
  指向独立仓库并写明「不接管全流量：只用 DNS/hosts/系统代理，不安装 VPN 或 TUN」。
