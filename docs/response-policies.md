# 响应策略、判定流水线与观测（AdBlock 内置 web server）

> 这份文档描述**当前代码的真实行为**，并把"计划中/尚未实现"的部分单独标注。
> 每个结论都对应仓库里的提交；不确定的地方宁可写"未实现"，不要写成"已支持"。
>
> 适用范围：`webserver/jni/webserver.c`（Android，master）与
> `webserver/win32/gui_win32.c`（Windows，feat/win11-webserver-release）。

## 1. 请求判定流水线（顺序即优先级）

一个请求到达后按下表从上到下判定，**第一个命中者决定结果**：

| 顺序 | 判定 | 命中后行为 | 开关/来源 |
|---|---|---|---|
| 1 | 管理路径（`/internal-stats`、`/internal-qlog`、`/internal-test`、`/internal-ws`…） | 直接应答，且**不计入**请求统计 | 仅回环管理端口可达 |
| 2 | 证书固定（pin）策略 | `mode=deny` 且该 uid 处于 bypass 期 → **CONNECT 直接 403**，不做握手、不签证书 | `pin_policy.txt`，默认 `off` |
| 3 | 按域名豁免 | 命中 `domain_allowlist.txt` → 不再拦截，回 **204**，记 `ALLOW` | 仅劫持模式提供编辑入口 |
| 4 | 按 uid 放行 | 命中 `allowlist.txt` → 同上（204 + `ALLOW`） | 应用内"应用放行"开关 |
| 5 | 强制门户（captive portal）探测 | 命中则按门户逻辑应答 | 内置白名单 |
| 6 | 代理分支 | 劫持模式下转发/替换上游响应 | `--proxy-filter`（仅劫持模式） |
| 7 | 分类与拦截 | 按 3 级判定得到请求类型 → 按该类型的响应策略作答 | 见 §2、§3 |

> 顺序 3、4 都在"分类/拦截"之前：**放行优先于拦截**。以前这两步回的是伪造的
> `200 "ok"`（空正文），在"AdGuard 把拦截 IP 指向本服务器"的部署下等于骗客户端；
> 现在统一回 **204 No Content**（`ce9a940` / `a25789e`）。要让某个域真正可用，
> 需在 AdGuard 侧同步豁免——应用内「解除过滤」页有常驻说明。

## 2. 请求分类（3 级判定，行为保持不变）

分类只回答"这是什么类型的请求"，供响应策略选择使用：

1. **扩展名**（`.png`、`.js`、`.css`、`.woff2`、`.mp4`、`.m3u8`…）；
2. `Sec-Fetch-Dest`（`image` / `script` / `style` / `font` / `video` / `audio`…）；
3. `Accept` 头（`image/*`、`text/css`、`application/javascript`…）。

判定结果与 `LT_*` 类型一一对应，并累加 `blocked_*` 计数（`/internal-stats` 的
`blocked_images`…`blocked_clickbait`，Windows GUI 的"拦截类型分布"直接读这些键）。
**没有** `document` / `iframe` / `other_visible` 这些"可见内容"类型——这正是
§6 中"可见型占位"尚未实现的原因。

## 3. 响应策略：现状与差距

计划中的统一策略名与当前实现状态：

| 策略 | 含义 | 状态 |
|---|---|---|
| `no_content` | 204 | ✅ 分类命中时的既有行为之一 |
| `not_found` | 404 | ✅ |
| `gone` | 410 | ✅ |
| `forbidden` | 403 | ✅（pin 策略拒绝 CONNECT 时使用） |
| `service_unavailable` | 503 | ✅ |
| `compatibility_200` | 200 + 空正文（兼容某些客户端） | ✅ 保留，**但放行路径不再使用它** |
| `image_placeholder` | 返回占位图 | ✅（既有：按类型回内置占位图） |
| `compatibility` | 按类型自适应（默认） | ✅ 默认档 |
| **`visible_image_placeholder`** | 对"可见内容"请求也回占位图（而不是让页面塌陷） | ✅ 已实现（`reply_visible`，**默认关**；见 §4.1） |
| **`rules` / `rule_override`** | 用户按域/类型覆盖策略 | 🟡 服务端已实现（`rule_override_enabled` + `rule_overrides.txt`，默认关）；**App 内编辑界面未做** |
| **`request_dedup`** | 短窗口内相同请求只处理一次 | ✅ 已实现（`dedup_enabled`，默认关；1s 窗口） |
| **`retry_guard`** | 同上请求高频重试时降级应答 | ✅ 已实现（`retry_guard_enabled`，默认关；10s/40 次 → 冷却 30s） |
| **`cache_reuse`** | 同域同类型的响应短期复用 | ✅ 已实现（`cache_reuse_enabled`，默认关；2s TTL，复用判定而非响应体） |
| **`circuit_breaker`** | 某域连续失败后短期直接快速失败 | ✅ 已实现（`circuit_breaker_enabled`，默认关；10s/60 次 → 冷却 20s，按"整个域"计） |
| **有界并发/队列** | 限制同时在处理的请求数 | ❌ **未实现**（当前为单线程事件循环） |

### 3.1 每类拦截方式的三种"回法"

`mode_*` 配置决定某类型如何作答：`0 = 占位图`、`1 = 204`、`2 = 透传（passthrough）`。
Windows GUI 的「拦截策略（勾选后立即生效）」表就是这份配置的界面（`g_policy[]`）。

### 3.2 F5：四个默认关闭的"降载"开关（互不影响，可单独打开）

四个开关都只在**被拦流量**的应答代价上做文章：**不改变**拦截与否的结论、
不改变放行/豁免的优先级，关闭时相关代码路径完全不会走到。

| 键（`block_config.json`） | 默认 | 语义 | 计数（`/internal-stats`） |
|---|---|---|---|
| `dedup_enabled` | `false` | 同一 `(uid, 域)` 1s 内重复 → 直接快速拒绝 | `dedup_hits` |
| `retry_guard_enabled` | `false` | 同一 **uid×域** 10s 内 >40 次 → 冷却 30s 快速失败 | `guard_hits` |
| `cache_reuse_enabled` | `false` | 同一域 2s 内重复 → 复用上次判定，跳过分类 | `cache_hits` |
| `circuit_breaker_enabled` | `false` | 同一**域**（不限 uid）10s 内 >60 次 → 冷却 20s | `breaker_hits` |

分工：`retry_guard` 看"某个应用 × 某个域"；`circuit_breaker` 看"整个域" —— 同一个域被
多个应用一起打成死循环时，只有熔断能兜住。四张表都是固定大小（64 / 16 / 32），
无分配、无锁（单线程事件循环），并把触发写进日志（`LOG_WARN`）。

### 3.3 请求判定流水线里的位置

这四类判断都在**分类之前**、同一个挂钩点内完成（`reply_blocked_by_type()` 开头），
因此它们只有"提前快速失败"这一种效果，不会与 §2 的三级分类或 §3.1 的每类回法冲突。

## 4. 证书固定（pin）策略状态机

```
        pin_policy.txt: mode=off（默认）
                    │
                    │ 用户改成 mode=deny
                    ▼
   ┌─────────── 监听该 uid 的 TLS 握手失败 ───────────┐
   │  窗口 PIN_FAIL_WINDOW_MS = 10s                  │
   │  阈值 PIN_FAIL_THRESHOLD = 5 次                  │
   └───────────────┬─────────────────────────────────┘
                   │ 达到阈值
                   ▼
       该 uid 进入 bypass：PIN_BYPASS_MS = 600s
       · CONNECT → 403（不做握手、不签证书）
       · apps[] 里 pin_refused = true
       · 查询日志仍记 BLOCK（拦截结果不变）
                   │ 10 分钟后
                   ▼
             自动回到"正常拦截"重新探测
```

- 这是**用户可决定**的行为：默认 `off` 时一切照旧。
- 判定复用已有的 per-uid 握手失败计数，**不额外扫描 /proc**。
- 观测：`/internal-stats` 的 `tls_failures`（全局）+ `apps[].tls_fail`（按 uid）；
  Windows GUI 显示为「证书被拒（握手失败）N 次 · 握手成功 M 次」+ 活跃应用行的
  **`(仅拒绝)`** 标记。

## 5. 配置与观测清单

### 5.1 资源目录下的文件（`<resources>/`）

| 文件 | 作用 | 缺省行为 |
|---|---|---|
| `pin_policy.txt` | `mode=off｜deny` | 视为 `off` |
| `rule_overrides.txt` | 按域覆盖响应：`<域规则> = <策略>`，策略 ∈ `placeholder｜204｜403｜404｜410｜503｜200`；域规则与豁免同语法、同"过宽即忽略"标准；认不出的策略名忽略该行 | 文件不存在 = 无覆盖 |
| `block_config.json` | 每类型的 `reply_*` + 五个降载/覆盖开关（`reply_visible`、`dedup_enabled`、`retry_guard_enabled`、`cache_reuse_enabled`、`circuit_breaker_enabled`、`rule_override_enabled`） | 全部按"关闭/默认档"处理 |
| `domain_allowlist.txt` | 按域名豁免（多语法） | 不存在 = 无豁免 |
| `allowlist.txt` | 按 uid 放行（每行一个十进制 uid） | 不存在 = 无放行 |
| 拦截策略配置 | 每类型的 `reply_*` / `mode_*` | 使用内置默认档 |
| `query_log.dat` / `apps.dat` / `stats.dat` / `hist.dat` | 持久化快照 | 首次运行自动创建 |

`domain_allowlist.txt` 接受的写法（照抄用户手里的规则即可）：
`example.com`、`.example.com`、`*.example.com`、`@@||example.com^`、`||example.com^`、
`0.0.0.0 example.com`、`:: example.com`、`https://example.com/path`、`example.com:8443`、
`#`/`!` 注释。语义是"该域本身及其子域"；**过宽的规则一律拒绝**（长度<4、不含点、含 `*`），
否则一行 `com` 等于放行半个互联网。

### 5.2 只读观测接口

| 接口 | 内容 |
|---|---|
| `GET /internal-stats` | 全部统计 + `apps[]` + `top_blocked` + `query_log`（**最新 400 条**）+ 历史曲线 |
| `GET /internal-qlog?offset=&limit=` | 明细日志分页（服务端保留 **10000 条**，单页上限 1000，offset 从"最新"往回数） |
| `GET /internal-test` | 诊断页 |

`apps[]` 每项字段：`uid`、`name`、`connections`、`requests`、`blocked`、`tls_hosts`、
`tls_ok`、`tls_fail`、`tps`、`hosts:[{h,n}]`、`pin_refused`。

### 5.3 日志容量与持久化

- 内存环 **10000 条**（`QLOG_MAX`）；渲染进 `/internal-stats` 的仍是最新 400 条
  （体积与旧客户端兼容）。
- 落盘**只写最新 4096 条**（`QLOG_PERSIST_MAX`）：1 万条全量落盘每次要写 ≈2 MB 闪存，
  代价远大于历史本身的价值；保存时按时间顺序紧凑拷到 slot 0，加载端据此重建连续环。

## 6. 尚未实现（与计划的差距）

| 项目 | 说明 | 计划阶段 |
|---|---|---|
| `req_class_t` 纯函数抽取 | 把 §2 的 3 级判定抽成可单测的纯函数（**行为不变** + 回归快照） | F3（未做） |
| ~~可见型占位~~ | ✅ 已实现：`Sec-Fetch-Dest` 为 `document`/`iframe`/`embed`/`object` 时回占位图 | F4（完成） |
| `rules` / `rule_override` | 用户按域/类型覆盖策略（默认关） | F4（未做） |
| ~~`request_dedup` / `retry_guard` / `cache_reuse` / `circuit_breaker`~~ | ✅ 已实现，四个独立开关，默认关（见 §3.2） | F5（完成） |
| 有界并发与队列 | 当前单线程事件循环；改动涉及事件循环结构，需单独评估 | F5（未做） |
| ~~风暴阶梯~~ | ✅ 识别（`tps`/`(风暴)`/`(仅拒绝)`）+ 四级降载开关（默认关） | F5（完成） |
| ~~真正的源站转发~~ | 已定论：转发式部署下不可靠，改为诚实的 204 + AdGuard 侧豁免提示 | 已定论 |

## 7. 15 项场景测试矩阵（现状）

| # | 场景 | 期望 | 现状 |
|---|---|---|---|
| 1 | 未启动劫持模式 | **不发生任何劫持** | ✅ 已核对 |
| 2 | 劫持模式 + 拦截域 | 按类型返回占位/204 | ✅ |
| 3 | 放行 uid | 204 + `ALLOW` 日志 | ✅ |
| 4 | 豁免域名（多语法） | 同上 | ✅ |
| 5 | 过宽规则（`com`、`*`） | **不被接受** | ✅ |
| 6 | pin 客户端（mode=off） | 与旧版完全一致 | ✅ |
| 7 | pin 客户端（mode=deny） | 10s 内 5 次失败 → 403 廉价拒绝 10 分钟 | ✅ |
| 8 | 服务器未运行时取统计 | 回退到 `stats.json` 快照 | ✅ |
| 9 | 日志超过 400 条 | 分页可取到更早的（Android「加载更早的记录」/ Windows 滚轮 1000 行） | ✅ |
| 10 | 日志超过 10000 条 | 只保留最近 10000 条 | ✅ |
| 11 | 落盘 | 只写最新 4096 条，重启后连续 | ✅ |
| 12 | 被 AdGuard 拦截的域 | 不再伪造 200，回 204 + 应用内提示 | ✅ |
| 13 | 可见内容请求（`document`） | 占位图而非塌陷 | ✅ 开关打开后生效（默认关） |
| 14 | 某域请求风暴 | 阶梯降级（去重/缓存/护栏/熔断） | ✅ 四个开关（默认关）+ 识别与提示（`tps`、`(风暴)`） |
| 15 | 规则覆盖 | 按域/类型自定义策略 | ❌ F4 剩余 |

## 8. 相关提交（可核对）

| 主题 | 提交 |
|---|---|
| 放行改 204 + 文案 | master `ce9a940`；Windows `a25789e` |
| 日志 10000 条 + `/internal-qlog` | master 原生侧；Android 取数层与分页 UI |
| pin 策略 + 域名豁免（Windows 移植） | Windows 分支（`F2 ④`） |
| 证书被拒 / (风暴) / (仅拒绝) 显示 | Windows 分支 GUI |
| SonarCloud 配置 | `sonar-project.properties` + 两个仓库的排除项 |
| 可见型占位（F4） | 服务端 `36b4628`、App 管道 `404713f`、设置开关 `c3a868e` |
| 去重与护栏（F5） | 服务端 `1dd4cfb`、App 开关 `8e5da5b` |
| 决策缓存与熔断（F5 续） | 服务端 `7510123`、App 开关 `3842eb6` |

---

## 规则支持清单（按实现核实，不是承诺）

本节描述的是**当前实现真实支持**的语法，依据是 `webserver/jni/webserver.c` 里的
`domain_rule_normalize()`（规则归一化，L531）、`domain_is_allowed()`（放行匹配，L563）与
`block_set_contains()`（拦截匹配，L3271）。两端（Android 原生服务端与 Windows 服务端）共用同一份
C 代码，因此下面的语义在两端一致；跨端测试向量应钉住这些行为。

### 支持（会被正确归一化）

| 写法 | 归一化结果 | 说明 |
|---|---|---|
| `0.0.0.0 ads.example.com` / `:: ads.example.com` | `ads.example.com` | hosts 格式：取空白之后的那个字段 |
| `ads.example.com` | `ads.example.com` | 裸域名 |
| `\|\|ads.example.com^` | `ads.example.com` | 取主机名部分（`^` 之后的内容一律忽略） |
| `\|ads.example.com\|` | `ads.example.com` | 前导 `@@`、`\|`、空白与前导 `.`、`*` 会被剥掉 |
| `https://ads.example.com/path` | `ads.example.com` | scheme 与路径被剥掉 |

比较是**大小写不敏感**的。

### 有限支持 / 与浏览器扩展语义不同（重要）

1. **子域匹配在两侧不一致。**
   放行侧（`domain_is_allowed`）的语义是"该域**及其子域**"；拦截侧（`block_set_contains`）是
   **主机名哈希精确匹配**，**不覆盖子域**。因此 `\|\|example.com^` 在拦截侧**不会**顺带拦住
   `a.example.com`。这是已知差异，不要按 ABP 的"主域覆盖子域"来预期。
2. **含资源类型修饰符的规则会退化为整域拦截。**
   归一化在 `^`、`$`、`/` 等处截断，只保留主机名。例如 `\|\|cdn.example.com^$script` 会变成
   "整个 `cdn.example.com` 都被拦"，而不是"只拦脚本请求"。**这属于静默改变规则含义**，
   是当前最需要修的一处。
3. **`@@` 例外前缀只被当作装饰剥掉**，不实现例外语义：`@@\|\|example.com^` 与
   `\|\|example.com^` 归一化结果相同。
4. **`##` / `#@#` / `#$#` 这类元素隐藏与脚本注入规则不参与网络层匹配**（`#` 是截断字符）。
   元素隐藏在服务端另有一条 cosmetic CSS 通道，其域名作用域问题单独记录。

### 不支持（既不应静默降级，也不应声称兼容）

正则规则、`$` 修饰符的完整语义（`$script`、`$image`、`$third-party` 等）、
`#@#` 例外、`#$#` 样式注入、路径与查询串匹配。

### 处理原则

对**不支持的写法**，正确做法是**跳过该规则并计数**，而不是把它降级成语义不同的主机名拦截 ——
后者会同时造成"该拦的没拦"和"不该拦的被拦"。上面第 2 条正是这条原则的待修项。
