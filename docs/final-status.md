# 收口总账（ADBlock 双端收口与 AdGuard 协同实施计划）

> 本文件是**状态清单**，不是宣传稿：**做完并验证的**与**未做的**分开列，每条尽量给可核对的证据
> （提交号 / CI 运行号 / 日志行）。最后更新：2026-10-15，master = `e24f065`。

## 1. 做完并验证

### A 证书真相（P0）
- `certificateTrustLevel()` 四库探测（用户库、系统库、APEX、apexdata）+ `installCertificateToUserStore()` 正名 —— 见早期提交。
- 恢复 A3 那批 `profileable`/失败语义时一并修正（提交 `37e63cb2` 起）。
- **静态验证**：Android CI 绿（见 §3 运行记录）。
- **未做**：A4 真机实测（需要设备）—— 见 §2。

### B 语义修复（P0）
- B1 `cache_reuse` 键改为 `(uid, host_hash, request_type, response_mode)` + 有界表（64 项，LRU 淘汰）：见 `webserver.c` 的 `s_f5_cache` 与 `f5_cache_slot()`。
- B2 降载快速失败按类别分别计数：`deny_fast()` 携带各自的计数器（去重/护栏/熔断/缓存/按域覆盖），不再统一计入 `blocked_other`。

### C 看得见的谎（P0/P1，C1–C8）
- C1 原生 `/internal-reset?what=stats|qlog|apps|all`（仅回环管理端口、1 秒节流、`no-store`、清 `stats.dat`/`hist.dat`/`apps.dat`/`query_log.dat`）+ 设置页二次确认与结果反馈。
- C2 图表样式常量统一（`CHART_STYLE_LINE/AREA/BARS`，设置页与趋势图共用）；C3 `showCerts` 拆为 `chart_top_hosts` 与 `chart_certs`。
- C4 查询日志末页态、空状态合并 `shown+shownOlder`、CSV 导出含 `type_code/type_name/mode_code/mode_name`。
- C5 规则/覆盖保存反馈（`addUserRule(..., onDone)`；`addOverride` 非空才清空；提示 `rule_override_enabled`）。
- C6 模式/端口切换统一进 `Dispatchers.IO` 并显示"配置值/生效中/成功/失败(原因)"，失败回读真实状态。
- C7 设置页硬编码英文全部进 `strings_compose.xml` + `values-zh`。
- C8 无障碍：应用监控第二个开关标注「在统计中显示」；放行首次解释 + 一键撤销。

### D 规则语义（P1）
- 支持清单写进 `docs/response-policies.md`（含尾锚点 `|domain|` 的真实语义与"宿主机测试跑出来的修复"）。
- 含 `$` 资源类型修饰符的规则在**两条路径上都跳过并计数**，不降级为整域拦截（`RuleModifiers` + `rule_norm.h`）。
- **跨端规则测试向量**：`webserver/jni/rule_vectors.txt` 单一来源，C 宿主测试与 Android 单测（`RuleVectorsTest`）读同一份。
- Cosmetic CSS：① 导入规则原先被注入在 `</style>` **之后**（浏览器不当 CSS，等于从未生效）→ 改为注入样式元素**内部**；② 新增**域名作用域**（`cosmetic_rules.txt`，`domain<TAB>selector`；缺失即维持旧行为）；③ 含 `<` 的规则整条丢弃并计数。
- 契约补上第六个被 C 端读取的文件 `cosmetic.css`。

### E AdGuard 协同（无 VPN，P1）
- `NetMode` 五态状态机（`standalone/adguard_coexist/other_vpn/unknown/degraded`）+ `NetModeDetector.classify()` 纯函数 + 5 项单测。
- `AdGuardCoexistence` 顾问：三个坑（DNS 不带端口 / `127.0.0.1` 指客户端自身 / DNS 已指向 ≠ HTTPS 已过滤）**落成阻塞判定**，验收清单三项（DNS 应答 → 实际连接 → issuer）；**刻意不写 AdGuard 配置文件**（格式版本未知时写入等于破坏用户设置）。
- `AdGuardClientGuide`：三条产品线的手动指引 + 配置值复制，版本提示写"代"而非精确版本号。
- **未做**：向导的**界面**（与 C9 一起，需要能看界面）。

### F Windows 独立仓库（P1）
- 契约冻结（`docs/windows-core-contract.md`：`block_config.json` 键、`/internal-stats` 字段、五个+一个规则/策略文件、规则语义子集、迁出硬约束）。
- 独立仓库 `wxcvm/AdAway-Windows`（public，`main`）：12 个文件 + 发布流水线；CI 跑通完整发布 ——
  `win11-webserver-v1.74`（`adblock-webserver-setup.exe` + `adblock-webserver-win64.zip`）与固定标签
  `win11-latest` 的 `windows-manifest.json`；Action 固定 SHA；`gh release create --latest=false` 且**已从外部核实**
  `wxcvm/AdAway` 的 `latest` 仍是 Android 的发布。
- 原仓库已移除 `webserver/win32/` 与 `windows-release.yml`；`webserver/jni` 保留。
- **核心关系已如实定性**（契约 §6.1/§6.2）：两端**特性分家**，不做逐段合并（与计划"不抽取共享 `webserver.c`"一致）。

### G 工程护栏（P1）
- G1 纯函数 + **六个宿主测试**在 CI 中运行：`rule_norm`(44) / `pin_window`(21) / `f5_guard`(26) /
  `cosmetic_sanitize`(18) / `cosmetic_scope`(20) / `lt_map`(119) = **248 项断言**，全部 stdlib-only、不依赖 mongoose。
  这些护栏在本轮开发中**实际抓出过三处错误**（凭印象填的期望值、两端剥注释不一致、域名比较用 `strlen` 导致 FQDN 不命中）。
- G3 SonarCloud：15 条未解决项全部处置（8 个 BUG 修复、7 条设计使然项标注理由，不假装修复）；`LOG_LIMIT`/`LOG_RETENTION` 死键已删。
- G4 文档与版本对齐（minSdk 30、仅 arm64、当前 targetSdk；README 说明 Windows 端不接管全流量）。

## 2. 未做 / 待条件（附原因与做法）

| 项 | 状态 | 原因 | 做法 |
|---|---|---|---|
| **A4 真机实测** | ⏳ 需设备 | 我无法操作你的手机（且不写设备启动脚本） | 三条命令 + 预期输出对照表（§4） |
| **G2 真机/仪器回归** | ⏳ 需设备 | 同上 | 拦截命中 / 204 放行 / 查询日志 / 证书状态四项，逐条对照（§4） |
| **C9 设置页 7 组拆分** | ⏳ 需界面 | 盲改 UI 风险高 | 需要设备或截图；模板：名称→作用→当前状态→控件→生效方式 |
| **E 的向导界面** | ⏳ 需界面 | 同上 | 展示/复制/实测/回滚入口 |
| **`a.jpg?v=1` 不匹配** | ⚠️ 已知缺口 | 比较对象是含查询串的完整 URI（**与抽取前一致**，修它会改变线上行为） | 记录在 `lt_map.h` 注释与本节；要改需单独决策 |
| **按域熔断未迁移** | ⚠️ 有意保留 | 与 `f5_guard.h` 结构相同但**键不同**（只看域，不看 uid） | 迁移前需先有等价测试（已写进头文件） |
| **`RB_TYPE` 宏表未重构** | ⚠️ 有意保留 | 它做的是**分发**，不是分类；分类已由 `lt_map.h` 覆盖 | 不动 |
| **CRLF（7 个文本文件）** | ⚠️ 已知无害 | 其中 **仅 2 个与本次工作有关**（API 推送脚本读工作区字节造成）：`OverviewScreen.kt` 与 `util/WebServerStats.java`；另外 5 个是**上游 libpcap 的 `msdos/` DOS 移植文件**，预先存在、本应与本次工作无关 | 详见 §2 的「CRLF 全量复核」小节 |
| **`sonar-project.properties` 惰性** | ⚠️ 已定性 | 项目用的是 SonarCloud **Automatic Analysis**，该文件**不被读取**（文件第 3 行自己写着这一点）→ 其中的排除项/测试标记**都没生效** | 两条路：改成 **CI 内分析**（配置才生效，也仍满足"每次推送都触发"）或把文件改成纯说明；本次未擅自选，留给决策 |

### CRLF 全量复核（2026-10-16，zipball 一次下载后本地扫描 1951 个文本类文件）

- 远端 master 上含 CRLF 的**文本类文件共 7 个**；其中**只有 2 个与本次工作有关**（由 API 推送脚本读取工作区字节造成）：
  `app/src/main/java/org/adaway/ui/compose/OverviewScreen.kt` 与 `app/src/main/java/org/adaway/util/WebServerStats.java`。
- **更正**：此前本节把后者的目录写成 `ui/compose/`，**那是错的** —— 它在 `util/` 下。
- 另外 5 个是**上游 libpcap 自带的 DOS 移植文件**（`tcpdump/jni/libpcap/msdos/` 下的 `bin2c.c`、`ndis2.c`、`ndis2.h`、`pktdrvr.c`、`pktdrvr.h`）：**预先存在、本应使用 CRLF、与本次工作无关**。
- 修复方式（如需）：让推送脚本读取 **git 对象**而不是工作区字节，或做一次有意识的重规范化；
  **不要**用 `core.autocrlf` 做全局重规范化（会弄脏约 1000 个文件）。


## 3. 可核对的证据（截至本次更新）

- master：`e24f065`
- Android CI 绿运行（已完成的最近几次）：#705(b9a84db)、#703(7340369)、#700(8b41ec9)、#696(22de337)
- 应用内更新发布：6.7.5.705(2026-10-15T14:43)、6.7.5.703(2026-10-15T14:31)、6.7.5.700(2026-10-15T14:18)、6.7.5.696(2026-10-15T14:05)
- Windows 仓库发布：progress-win-20261015.1(2026-10-15T11:42) ← adblock-preview-machine-x64.exe,adblock-preview-user-x64；win11-webserver-v1.74(2026-10-11T14:24) ← adblock-webserver-setup.exe,adblock-webserver-win64.zip
- 说明：Run #704 是 `cancelled`（被后一笔推送按 `concurrency: cancel-in-progress` 取消）—— 该提交的**内容**由
  #705 验证（#705 的提交包含它），但**那一次运行本身**不构成验收。

## 4. 需要你执行的部分（我只给命令与预期，不写设备脚本）

**A4-1 证书四态**：设置页应显示证书状态（未安装/用户级/系统级/已变化），点"重新计算"后仍然正确。
**A4-2 issuer**：
```
openssl s_client -connect <被拦域名>:443 -servername <被拦域名> 2>/dev/null | openssl x509 -noout -issuer -subject
```
预期：issuer 是本地 CA 的 subject（若仍是公网 CA，说明该应用的流量没走到本服务）。
**A4-3 固定证书应用**：访问一个做证书固定的应用，`/internal-stats` 的 `tls_failures` 应上升，且界面必须表达为
"（仅拒绝）"，**不得**显示成功。
**G2**：拦截命中、`204`（放行）、查询日志记录、证书状态刷新 —— 四项各截一次日志/界面即可。

## 5. G3 收口结果（2026-10-15，全部来自 SonarCloud 只读 API）

**"每次推送都触发分析"——已核实**：`wxcvm_AdAway` 的分析记录与我的提交逐一对应
（例如 `75efcbd`→14:56:26、`cb2eb9b`→14:55:12、`e24f065`→14:52:16、`b9a84db`→14:36:43、
`1eedcae`→14:34:47、`7340369`→14:25:10）；走的是 **Automatic Analysis**（服务端触发），
所以它**不会**出现在 GitHub Actions 的运行列表里 —— 这一点曾让我一度以为"没跑"。

**开放问题：6 → 1**

| 规则 | 位置 | 处置 | 结果 |
|---|---|---|---|
| `c:S1764` ×2 | `f5_guard_test.c` | 原文是 `false == false`（恒真常量，**等于没测**冷却期）→ 改为真实期望 `true`；CI #711 六项宿主测试全绿 → **守卫行为本来就正确，只是原先没被检查** | ✅ 清除 |
| `java:S2142` | `WebServerNet.java` | **真缺陷**：内层已恢复中断标志，但**外层** `catch (Exception e)` 会吞掉 `in.read`/`process.waitFor` 抛出的 `InterruptedException` → 外层也恢复标志 | ✅ 清除 |
| `c:S5332` ×2 | `webserver.c` | 本机监听地址的明文 URL → 按既有做法标注"设计使然（仅回环）"，**标注而非假装修复** | ✅ 清除 |
| `xml:S5332` | `AndroidManifest.xml` | 抑制标记已按要求紧贴 `<application` 上一行，**仍不被识别**（该规则在 XML 中不支持这种抑制） | ⚠️ 保留 |

**指标变化**：`alert_status` **ERROR → OK**；`bugs` **3 → 0**；`vulnerabilities` **3 → 1**；`code_smells` 227。

**关于最后这一项，明确定下边界**：**不会**为了让扫描数字变好看去真的声明
`android:usesCleartextTraffic="true"` —— 那会在 API 28+ 上**真的放开对外明文**，是安全上的实质退步。
现状（保持平台默认、只与本地回环服务通信）才是正确形态，该 MINOR 项保留并在此记录。

### 5.1 `block_config.json` 键清单：契约与代码**逐一相同**（已做成 CI 守卫）

**提取方法（可复现）**：代码侧 = `webserver/jni/webserver.c` 里 `jbool(...)` 的**第二个参数**
（那才是服务端真正读取的键名）；契约侧 = `docs/windows-core-contract.md` 第 1 节表格里反引号包裹的键。
两侧各自 `sort -u` 后 `diff`。

**结果**：**16 个 == 16 个，逐项相同** —— 既无"代码在读、契约没记"，也无"契约记了、代码没读"。
这 16 个是：`cache_reuse_enabled`、`circuit_breaker_enabled`、`dedup_enabled`、`reply_api`、
`reply_config`、`reply_fonts`、`reply_images`、`reply_media`、`reply_scripts`、`reply_structures`、
`reply_styles`、`reply_telemetry`、`reply_visible`、`reply_ws_sse`、`retry_guard_enabled`、
`rule_override_enabled`。

**已升级为常驻守卫**：Android CI 的步骤 `Config key contract guard` 每次推送都做同一比对，
不一致就 `::error::` 并失败 —— 于是"加了键却不更新契约"或"契约写了代码没有"会**当场被挡住**。
首次运行（CI #717 / `9e80c9d`）即 **success**（见 §3 与 CI 步骤清单）。

> 过程记录：本项最初用一条**过宽的正则**抽取，得出"代码里有 38 个键未记录"——那是**误报**
> （把 `/internal-stats` 的计数器、函数名前缀、普通单词都算了进去）。收窄到 `jbool` 后为 16==16。
> 该误报**没有**写进任何文档或提交。

### 5.2 `/internal-reset` 的两条边界：**节流**与**原子写**（均已实现并 CI 验收）

计划边界原文：「`/internal-reset` 与轮询并发时加节流与原子写」。两条分别核实如下。

**节流（原本就成立）**：处理器先判节流、后动数据 —— 1 秒内重复调用返回 `429` 与
`{"ok":false,"error":"throttled"}`，注释写明理由是"避免与每秒一次的 `/internal-stats` 轮询打架"。
另外重置**只清内存**，磁盘快照由随后的轮询按既有节奏写出。重置的语义也做了正确取舍：
计数整体清零但**保留 `start_time_ms`**（否则"已运行"会变成天文数字），历史曲线与 F5 各类计数一并清零。

**原子写（本轮补齐）**：原先五份 `.dat` 中只有 SNI 缓存用了 `tmp + rename`，其余四份
（`stats.dat`/`hist.dat`/`apps.dat`/`query_log.dat`）是 `fopen(path, "wb")` **直接截断** ——
而 App 会并发读取这些文件，存在"读到写了一半"的窗口。现补成一对辅助：

```c
static FILE *open_atomic_write(const char *path, char *tmp, size_t tmp_cap);   /* 写 <path>.tmp */
static void  finish_atomic_write(FILE *fp, const char *path, const char *tmp); /* fclose 成功→rename，失败→remove(tmp) */
```

五处写入点（`stats.dat`/`hist.dat`/`apps.dat`/`query_log.dat`/`sni_cache.dat`）全部改为这一对，
**写入内容与时机不变**；失败时临时文件被删除、**旧文件原样保留**。写 CA 私钥/证书的两处 PEM 调用未动。
现状核对：全文件已无 `fopen(path, "wb")` 直接截断，`open_atomic_write` 调用 5 处、`finish_atomic_write` 5 处。

**CI 证据**：Android CI **#721**（`548efa5`）**success** —— 六个宿主测试 248 项断言、契约键守卫、
**NDK 编译** `webserver.c`、签名与发布全部通过；应用内发布 `6.7.5.721`。

> 过程记录（我自己的两个失误，均已在上游修正或拦下）：① 插入辅助函数与原型时脚本跑了两次，
> 造成**重复定义**（会编译不过）—— 提交前的计数断言抓出并去重；
> ② 我此前有一条"整文件圆括号平衡"的断言本身是错的（注释与字符串里也有括号），已弃用，
> 改为与上一提交对比的结构不变量与"只断言最终状态、可重复执行"的写法。



| **MoveCertificate** | ⛔ 移出范围 | 用户指示"这个仓库就别管了" | 其 `main` 上有一个我推的未发版加固提交（不影响任何东西） |
| **两端核心合一** | ⛔ 不在计划内 | 计划明写"不抽取共享 `webserver.c`" | 契约 §6.2 给了工作清单与红线（独立分支 + 两端 CI） |

## 6. 计划「边界」四条：逐条实现与证据（2026-10-16 核实）

| 边界 | 实现 | 证据 |
|---|---|---|
| ① 无 Root 只承诺 DNS/hosts 层，且**在 UI 明示** | 空路由模式文案说明"不占用任何端口、无拦截页、无逐应用统计"，本机模式说明需占用 80/443 | `values` / `values-zh` 的 `compose_settings_block_mode_null_hint`、`compose_settings_block_mode_localhost_hint` |
| ② 证书固定的应用用 `tls_failures` 与**（仅拒绝）**表达，**不谎报成功** | 计数器通道：服务端字段 → 汇总 → `/internal-stats` 的 `tls_failures` → App 读取；**用户可见措辞**：每个应用的 TLS 失败计数后追加"（仅拒绝：…不是拦截成功）"与英文 "(rejected only: … - not a successful block)" | `webserver.c` 的 `tls_failures`（声明/汇总/JSON 导出）、`StatsViewModel.kt` 的 `tlsFailures`、`AppDetailScreen.kt` / `StatisticsScreen.kt` 的展示位、`compose_stats_app_tls_fail`（中英各一份），提交 `4dfc493`，发布 `6.7.5.723` |
| ③ `/internal-reset` 与轮询并发时加**节流**与**原子写** | 节流：1 秒内返回 429；原子写：五份 `.dat` 全部 tmp+rename（见 §6 边界四条的表） | 处理器与辅助函数见 `webserver.c`；CI #721 编译与测试全绿 |
| ④ 端口冲突**显示来源**、**不静默改动** | 文案点出"可能被 AdGuard 等占用"、说明"没有监听"这一事实、让用户自行改用 8080/8443（并注明劫持模式不依赖 80/443） | `compose_settings_block_mode_hijack_failed`（中英各一份） |

**如实记录（这一条曾是半成品）**：边界 ② 起初**只做了一半** —— `tls_failures` 的计数器通道是完整的，
但"（仅拒绝）"这句用户可见的表达在全仓**为 0 处**；也就是说用户看到失败数字时**仍可能以为通过了**，
正是这条边界要防的"谎报成功"。该缺口是在本次收口核对时**查出来**并补上的（`4dfc493`），不是一开始就对。

**如实记录（一次 CI 失败的处理）**：`4dfc493` 的首次 CI 结论是 **failure**，但失败点在
`Publish to the in-app update feed` 这一步，而 `Run tests & build release` / 签名 / 上传**均已通过** ——
即代码与资源都没有问题，是发布环节的偶发失败。处理方式是**用 API 重跑失败作业**
（`POST /actions/runs/{id}/rerun-failed-jobs`），重跑后 **success**，`6.7.5.723` 正常出现在发布列表里。
**经验**：`conclusion = failure` 不等于代码有问题，必须看清**失败在哪一步**；否则要么改坏好代码，
要么漏掉"用户其实没收到更新"。

## 7. 逐项亲验（不是"我做过所以应该对"）

收口阶段对若干"早已完成"的项做了**重新打开源码核对**，结果与证据如下。

**C1：`/internal-reset` 的界面二次确认与结果反馈 —— 完整** ✓
`SettingsScreen.kt` 的清除按钮实现了两段式确认（`clearArmed`：第一次点击只进入确认态、按钮文案切到
`compose_settings_clear_stats_confirm`；第二次才执行），确认后在后台线程调用
`WebServerUtils.resetStatistics()`（走回环管理端口的 `/internal-reset?what=all`），再用主线程 `Toast`
反馈结果：成功用 `compose_settings_clear_stats_ok`、失败用 `compose_settings_clear_stats_fail` ——
**失败时如实提示失败**，不是"点了就假装成功"。注释也记录了被修掉的历史缺陷：
此前只删了两个设置键，而计数/历史曲线/应用统计/查询日志都在原生进程内存里，
「按钮点了等于没点、也没有任何反馈」。

**G3：`LOG_LIMIT` / `LOG_RETENTION` 死键 —— 已删净** ✓
全仓检索：**代码里零引用**，仅 `PrefsKeys.kt` 留一条注释说明"这里原本有这两个键（日志条数上限）"，
以及本文件 §1 的一行记录。即"删除死键 + 留一句话解释历史"的正确收尾方式。

**方法说明**：以上均为**只读核对**（读源码/读资源），未修改任何行为；核对它们的动机是前面几轮
反复出现的教训 —— "声称已做"与"亲眼看过"是两件事（例如边界 ② 就曾只做了一半）。

### 7.1 C5 的再次亲验（规则/覆盖的保存反馈）

**`addUserRule` 会把结果反馈给调用方** —— 实现形态是**回调**而不是 `Result` 类型：
`StatsViewModel.addUserRule(host, type, redirection, onDone: (Boolean) -> Unit)`，
调用处（`RulesScreen`）接收该布尔值并据此给出界面反馈。**意图（让调用方知道成功/失败）满足**；
但如实说明：它**不是**计划措辞里的 sealed `Result`。此处按实情记录，不写成"返回 Result"。

**`addOverride` 有返回值，界面按结果决定后续动作**：`ExemptionRules.addOverride(context, host, policy): String`；
`RuleOverridesScreen` 用 `val saved = …` 接收，注释写明"保存要按结果决定后续动作：
`addOverride()` 在**域名无效或写文件失败**时…"。即"非空才清空/失败要如实反馈"的要害成立。

**`rule_override_enabled` 的依赖被明确提示**：该键确实由服务端写入配置
（`WebServerUtils` 的 `appendBlockReplyField(sb, "rule_override_enabled", …)`；
`RuleOverridesScreen` 的注释写明"所以**提示里必须把这条依赖说清楚**"）。
## 8. 一批「用户可见文案 vs 实现」的一手核对（2026-10-16）

收口核对时逐条打印了设置项自己的文案，作为「用户看到的语义」与实现是否一致的正面证据：

| 设置项 | 应用文案（要点，均可在 values-zh 中检索到） | 与实现的关系 |
|---|---|---|
| 请求去重 | 同一个应用在 1 秒内重复请求同一个域时只处理一次，其余快速失败；正常请求不受影响；默认关闭 | 与 B1 的 (uid, 域, 类型, 模式) 键 + 有界表一致 |
| 重试风暴护栏 | 同一个应用在 10 秒内对同一个域超过 40 次判为风暴，随后 30 秒快速失败并记日志，之后自动恢复探测；默认关闭 | 与 f5_guard 的窗口/阈值/冷却一致 |
| 按域覆盖响应 | 让指定域名改用所选状态码回答（403/404/410/503/204/200），而不是默认占位；请求仍然算被拦；默认关闭 | 与「按域覆盖」实现一致 |
| 固定证书的应用：改为廉价拒绝 | 被检测出证书固定（反复握手失败）的应用，其 CONNECT 请求直接拒绝而不再做中间人；拦截效果不变，每次重试只花一行 HTTP | 与边界 ② 的「仅拒绝」表达一致 |
| 解除过滤的真实效果 | 本服务器不再拦截该域名（回 204 No Content）；在 AdGuard 指向本服务器的部署里服务器拿不到真实内容 | 主动说明真实效果，属「不谎报」的正确示范 |

这些文案**主动写明默认关闭、写完真实效果与不变项**（正常请求不受影响 / 请求仍然算被拦 / 拦截效果不变），与实现一致；也是本项目「不谎报」要求在 UI 上的体现。
## 9. 收口期新增的三块（E 的两层 + 验收单 + 三坑亲验）

### 9.1 E（AdGuard Home custom_ip 向导）补上两层，均已 CI 验证

| 层 | 做什么 | 证据 |
|---|---|---|
| 改动计划核心 `AdGuardHomeCustomIp` | 只认已知格式（找不到 `dns:` 或 `blocking_mode:` 就拒绝）；保存每一处原文以支持字节级回滚；绝不部分写入；**本类不写文件** | 5 个单测 |
| 文件层 `AdGuardHomeConfigFile` | **先备份**（备份失败即放弃应用）→ 写 `.tmp` 后 `rename`（原子写）→ 写后**读回校验**→ 任何一步失败**用备份回滚**并如实回报 `rolledBack`；格式不认则**一字不动** | 3 个单测；Android CI **#736** success |
| 实测判定 `AdGuardHomeVerification` | DNS / 连接 / issuer 三步判定；**DNS 已指向但 issuer 仍是公网 CA ⇒ 判 DNS_ONLY_NOT_FILTERED 并必须回滚**；`shouldRollback(v) = v != OK` | 4 个单测；Android CI **#737** success |

**尚未接线**：界面（展示/复制/一键应用与回滚）需要看到真实界面再动手，属于待你提供截图后的工作。

### 9.2 真机验收单（把 A4 与 G2 合成一次运行）

`docs/device-acceptance.md`：cmd 可直接粘贴的四库命令与 issuer 两条等价命令；**判定规则在运行之前写死**
（四态判定与 `certificateTrustLevel()` 实现逐条对齐；无 root 则判定降级、不得断言没有系统级；
公网 issuer 属「该应用流量没走到本服务」，如实记录、不算失败）；并写明设置页状态与 `ls` 结果不一致即为我方 bug。

### 9.3 「三坑」逐坑亲验（均已在代码中存在）

- 坑①（DNS 不带端口需 80/443）：HijackModel 的 nat 链说明与 ServerWatchdogWorker 的黑洞风险说明；
- 坑②（127.0.0.1 指客户端自身）：HijackModel 明确「Leave loopback, LAN and carrier-grade NAT destinations alone」；
- 坑③（DNS 已指向 ≠ HTTPS 已过滤）：**三重独立保障** —— `AdGuardCoexistence` 的阻塞判定；
  `AdGuardClientGuide` 中由单测固定的规则（任何版本都不允许出现「DNS 已指向即完成」的说法）；
  以及 `AdGuardHomeVerification` 的机器判定 + 自动回滚。
## 10. G2 的两条路线：为什么只做了「验收单」而没写「仪器测试」

计划 G2 写的是「真机/**仪器**回归（拦截、204、日志、证书状态）」。本轮明确记录取舍：

- **已做**：`docs/device-acceptance.md` 的人工四项验收单（拦截命中 / 放行 204 / 查询日志 / 证书状态），
  判定规则在运行前写死，并含与四库输出的交叉核对；
- **未做**：androidTest（仪器测试）。原因不是遗漏，而是**我无法在本环境运行它**：
  仪器测试需要连接真实设备/模拟器（`connectedDebugAndroidTest`），我既不操作你的设备、也不写设备启动脚本；
  在这种前提下写出**从未运行过**的测试代码，属于"看起来有测试、实际没验证"，与本项目一直坚持的标准相反。
- **若你要走仪器测试路线**：需要你在接好设备后运行一次并贴回结果；我可以据其失败信息修——但**不会**先交付未运行的测试。

同类如实说明还有两条（此前已记）：远端有 7 个文本文件是 CRLF，其中 2 个与本次工作有关（功能等价，详见 §2 的「CRLF 全量复核」小节）；SonarCloud 余 1 条 MINOR 的 `xml:S5332`
（已在 XML 中按正确方式抑制但该规则不支持，**拒绝**为让数字好看而真的声明 `usesCleartextTraffic`）。

## 11. 契约的两个接口面：均已由 CI 常驻守卫（不再是"我记得更新过"）

| 接口面 | 数量 | 守卫步骤 | 首次通过 |
|---|---|---|---|
| `block_config.json` 的键 | **16** | `Config key contract guard` | CI #717（提交 9e80c9d） |
| `/internal-stats` 的字段 | **63** | `Stats field contract guard` | CI #744（提交 4f45b05） |

两条守卫做同一件事：把**代码里真实出现的东西**与**契约里记录的东西**各自排序后 `diff`，
不一致就 `::error::` 并让这一步失败。因此：

- 加了键/字段却没更新契约 → **CI 当场红**；
- 契约里写了代码没有的键/字段 → **同样当场红**。

字段那侧的提取曾经差点把我带偏：`webserver.c` 里的 JSON 键是**转义写法**（`\"tls_failures\":%llu`），
第一版提取只认 `"key":`，于是报出「契约记了 60 个、代码一个都没有」——那是**提取器的错**，不是文档的错。
**规则**：当"文档 vs 代码"的比对出现**大面积差异**时，先怀疑提取器；真缺口通常是 1–3 项
（例如契约的文件清单从六个补到七个）。

### 11.1 顺带修掉的三处真实陈旧文档（同一轮排查的产物）

1. `CHANGES.md` 最新条目**停在 Release 6.4.2**，而应用已到 6.7.5.x —— 于是用户看不到
   "隐藏规则以前根本没生效""域名规则以前会在所有站点生效""统计以前清不掉"这些**已修**的问题；现已补上本批条目；
2. `docs/response-policies.md` 指向**已迁出**的 `webserver/win32/` —— 已改为指向独立仓库与契约 §6.1/§6.2；
3. `docs/windows-core-contract.md` §3 的文件清单**少一个**：C 端实际还会读 `cosmetic_rules.txt`（域名作用域），
   现已列为第七个文件，并写明"文件不存在时行为与没有本功能时完全一致"。
## 12. Windows 端巡检（2026-10-16）：pin 有效、两次失败已澄清、一条将来维护项

### 12.1 pin 仍然有效（未前移）

- `fetch-upstream.sh`：`PIN=4470606366e614abdb308fdfe94734d28167439c`，`BRANCH=feat/win11-webserver-release`；
- 该分支**现在的尖端正好就是这个提交**（2026-10-11 14:26）→ 所以脚本里那条
  「取到的提交 != PIN 就 `::error::` 大声失败」的断言**现在是安静的** ✓；
- 被 pin 的树内容与契约一致：`webserver/jni` **8 个文件**（Android.mk、Application.mk、mongoose 四件、stub.c、webserver.c），
  `webserver/win32` **0 个**（已彻底迁出）。

### 12.2 最近 5 次 CI 里有 2 次失败 —— 已查清为历史中间态，不是隐患

| 运行 | 提交信息 | 失败步骤 | 判定 |
|---|---|---|---|
| #52（10-14） | `fix(win): atomic bounded subscription import and frozen-core loader in …` | Build webserver.exe (mingw64 + OpenSSL) | 该轮正在修这件事 |
| #50（10-12） | `ci: install Python for pinned source validation in MSYS2` | **同一步骤** | 同上 |

两次之后 **#51 / #53 / #54 同一构建步骤全部通过** → 结论：**历史中间态、已被后续修复覆盖**，
不是"每隔几次就坏一次"的规律。

### 12.3 一条将来维护项（现在不是问题）

MSYS2 会输出**非致命警告**：`[msystem-mingw64] MINGW64 is deprecated. Migrate to UCRT64 or CLANG64.`
→ 与那两次失败**无关**（失败在编译步骤）。但将来 MSYS2 若移除 MINGW64 环境，Windows 构建会突然断；
迁到 UCRT64/CLANG64 是一次**独立的小改动**（需在独立仓库改 CI 与可能的路径假设），**现在不做**。
