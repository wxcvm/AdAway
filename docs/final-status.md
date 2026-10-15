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
| **MoveCertificate** | ⛔ 移出范围 | 用户指示"这个仓库就别管了" | 其 `main` 上有一个我推的未发版加固提交（不影响任何东西） |
| **两端核心合一** | ⛔ 不在计划内 | 计划明写"不抽取共享 `webserver.c`" | 契约 §6.2 给了工作清单与红线（独立分支 + 两端 CI） |

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
