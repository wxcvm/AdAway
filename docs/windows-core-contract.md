# Windows / Android 共享核心契约（F 第一步：先冻结，再迁出）

## M1：版本化规则决策接口与跨平台固定测试

Android M1 提供 `TrafficCoreContract`、`RuleSnapshotRepository` 与原生 `traffic_core_contract.h` 的共享协议和测试向量。Windows CI 可独立获取固定提交下的契约文件，但这不意味着 Windows 的整个 `webserver.c` 已经与 Android 合并。
生产捕获、自动修改 DNS/路由和系统回滚仍须分别实现及验收。

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

权威来源：webserver/jni/webserver.c 与 query_log_store.inc 中构造 JSON 的代码；本清单涵盖统计、日志及控制响应，共 74 个字段。抽取 C 字符串中的转义 JSON 键（包括短键和大小写），不把条件表达式中的普通字符串当作字段。
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
- `entries`
- `error`
- `family`
- `guard_hits`
- `h`
- `hasMore`
- `history`
- `host`
- `hosts`
- `limit`
- `mode`
- `n`
- `nextOffset`
- `offset`
- `ok`
- `path`
- `pathTruncated`
- `pin_refused`
- `qlog`
- `query_log`
- `recent_tls`
- `requests`
- `sni_cache_hits`
- `sni_certs_issued`
- `sni_hit_rate`
- `anchor`
- `generation`
- `returned`
- `snapshot_ts`
- `stats`
- `status`
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
- `ts`
- `type`
- `uid`
- `uptime_days`
- `uptime_seconds`

family 表示实际接收 HTTP 连接的地址族：0 为未知，4 为 IPv4，6 为 IPv6。旧历史不补推地址族；不表示原始 DNS 查询类型或订阅来源。

## 3. 规则与策略文件（以源码为准）

C 服务端读取的文件（名称取自源码的三种写法：宏定义、裸字面量、内联路径，含 .txt 与 .css "%s/xxx.txt"）：

| 文件 | 用途 | 写入方 | C 端读取处 |
|---|---|---|---|
| `pin_policy.txt` | 固定证书（pin）策略：域名与模式 | 应用/用户 | PIN_POLICY_FILE 宏 |
| `rule_overrides.txt` | 按域覆盖响应（自定义格式） | 应用按域覆盖页 | RULE_OVERRIDE_FILE 宏 |
| `cosmetic.css` | 元素隐藏样式表（AdGuard/ABP 的 `##selector` 收集结果），注入被过滤页面的 `<style>` **内部**（此前被注入在 `</style>` 之后，等于从未生效，已修） | 应用 `CosmeticRules.toCss()`（经 `WebServerUtils` 落盘） | `webserver.c` 的 `load_cosmetic_css()`（`%s/cosmetic.css`） |
| `cosmetic_rules.txt` | **域名限定**的元素隐藏规则（`domain<TAB>selector`，`*` = 全局）；服务端按当前页面主机筛选后注入 `<style>` 内部（精确主机或子域，大小写不敏感）。**文件不存在时行为与没有本功能时完全一致** | 应用 `CosmeticRules.toScopedRules()`（经 `WebServerUtils` 落盘） | `webserver.c` 的 `load_cosmetic_rules()`（`%s/cosmetic_rules.txt`） |
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

### 6.1 核心已分叉：`webserver.c` 需要一次真正的合并（2026-10-15 核实）

**结论：不能靠"改一行 pin"同步两端。** Windows 仓库的 `fetch-upstream.sh` 目前 pin 在
`feat/win11-webserver-release` 的提交 `4470606…`，那份 `webserver/jni` 只有 8 个文件，而 master 有
**19 个**；同名文件 `webserver.c` 两侧也不同：

| | files in jni | webserver.c | `_WIN32` 出现 | 预处理条件 | `cosmetic` | 纯函数头引用 |
|---|---|---|---|---|---|---|
| `master`（Android） | 19 | 218,142 B | 8 | 10 | 76 | 65 |
| `feat/win11-webserver-release`（Windows） | 8 | 250,980 B | 38 | 37 | 19 | 2 |

逐行差分：**2435 行新增 / 1620 行删除**（相对 master）—— 两侧各自演进了很久：

- **只有 Windows 侧有**：`win32_dirent.h`（opendir/readdir shim）、`winsock2.h`/`_stricmp` 等
  `_WIN32` 分支、以及"没有 `/proc` 时 uid 视为 unknown"这类 Windows 适配；
- **只有 master 有**：G1 抽出的纯函数头与宿主测试（`rule_norm.h`、`pin_window.h`、`f5_guard.h`、
  `cosmetic_sanitize.h`、`cosmetic_scope.h` 及各自测试、`rule_vectors.txt`），以及 cosmetic 的两处修复
  （`<style>` 上下文与域名作用域）。

**因此目前的状态是"漂移"而不是"故障"**：Windows CI 仍然绿（它 pin 的旧提交里有那 8 个文件），
但它**拿不到** Android 侧的新修复。

### 6.2 结论修订（2026-10-15 实测后）：这是**特性分家**，不是行级漂移

上一节按「行数差异」描述（master↔Windows 之间 2435/1620 行），据此得出的「逐段合并」设想**经实测被否定**：

- 以共同祖先 `13cdcdf3`（2026-09-03）为基准做三方比对：**master 相对基准 115 个 hunk**、
  **Windows 相对基准 148 个 hunk**；两侧**同一基准行号上已是完全不同的内容**（例：基准第 265 行是
  `cfg_images` 声明，master 同处是 `s_sni_mutex`，Windows 同处是 `GetExitCodeProcess` 调用）。
  因此按行号做的「冲突计数」是**度量假象**，不能据此逐段合并。
- Windows 相对基准的 148 个 hunk 里，带 Windows 标记的 **30 个**（+2350 / -19 行）——其中**多数是
  Windows 独有功能**（webserver.log 镜像、面板可选的按类型处置、监听器注册表、`img_mode_name()` 等），
  只有少数是编译/系统垫片（`win32_dirent.h`、`winsock2.h`/`_stricmp`、无 `/proc` 时的 uid 处理、
  CA 私钥用 `icacls` 收紧、`GetExitCodeProcess` 等）。

**决定（与迁出计划一致）**：本计划原本就写明「**不抽取共享 webserver.c**」，因此**接受两端核心不同源**
（特性分家），不做逐段合并。对用户的影响必须在 UI/文档里讲清：

- 两端的**规则语义、配置键名、`/internal-stats` 字段**以本契约为准（这部分**是对齐的**）；
- 但 **Windows 端另有若干独有功能**（上列），Android 端没有；反之 Android 端的近期修复
  （cosmetic 的 `<style>` 上下文、`##` 域名作用域、G1 的纯函数校验）**Windows 端尚未包含**。

**若将来要真正合一**（需另行决策，不在本计划内）：工作清单已生成（Windows 相对基准的 30 个区域），
做法是**在独立分支上把 Windows 功能与适配搬进 master 并全部 `#ifdef` 守卫**，由**两端 CI**共同验收；
`webserver.c` 在 CI 通过前不得改动（上一节的红线仍然有效）。

**合并方案（未执行）**：以共同祖先为基准做三方比对，把差量分成「Windows 专属 / Android 新增 /
双方都改」三类，冲突段逐个判定（依据写进提交信息），在**独立分支**上重建一份 = master 全部 +
Windows 专属增量，让 Windows 仓库 pin 到**新提交**，并让它的 CI（编译 + 冒烟 + 打包 + 发布 +
`--latest=false`）通过后才算完成。**在独立分支与 CI 通过之前，不要动 `webserver.c`。**


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
