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
| **`visible_image_placeholder`** | 对"可见内容"请求也回占位图（而不是让页面塌陷） | ❌ **未实现**（见 §6） |
| **`rules` / `rule_override`** | 用户按域/类型覆盖策略 | ❌ **未实现** |
| **`request_dedup`** | 短窗口内相同请求只处理一次 | ❌ **未实现** |
| **`retry_guard`** | 同上请求高频重试时降级应答 | ❌ **未实现** |
| **`cache_reuse`** | 同域同类型的响应短期复用 | ❌ **未实现** |
| **`circuit_breaker`** | 某域连续失败后短期直接快速失败 | ❌ **未实现** |
| **有界并发/队列** | 限制同时在处理的请求数 | ❌ **未实现**（当前为单线程事件循环） |

### 3.1 每类拦截方式的三种"回法"

`mode_*` 配置决定某类型如何作答：`0 = 占位图`、`1 = 204`、`2 = 透传（passthrough）`。
Windows GUI 的「拦截策略（勾选后立即生效）」表就是这份配置的界面（`g_policy[]`）。

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
| `req_class_t` 纯函数抽取 | 把 §2 的 3 级判定抽成可单测的纯函数（**行为不变** + 回归快照） | F3 |
| 可见型占位 `visible_image_placeholder` | 新增 `document`/`iframe`/`other_visible` 分类，对其返回占位图 | F4 |
| `rules` / `rule_override` | 用户按域/类型覆盖策略（默认关） | F4 |
| `request_dedup` / `retry_guard` / `cache_reuse` / `circuit_breaker` | 各自独立开关，默认关 | F5 |
| 有界并发与队列 | 当前单线程事件循环 | F5 |
| 风暴阶梯（自动降级） | 目前只有"识别与提示"（`tps` + `(风暴)` 标记） | F5 |
| 真正的源站转发 | 见 §1 的说明：转发式部署下不可靠，改为诚实的 204 + 提示 | 已定论 |

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
| 13 | 可见内容请求（`document`） | 占位图而非塌陷 | ❌ F4 |
| 14 | 某域请求风暴 | 阶梯降级（去重/熔断） | ❌ F5（现仅识别 + 提示） |
| 15 | 规则覆盖 | 按域/类型自定义策略 | ❌ F4 |

## 8. 相关提交（可核对）

| 主题 | 提交 |
|---|---|
| 放行改 204 + 文案 | master `ce9a940`；Windows `a25789e` |
| 日志 10000 条 + `/internal-qlog` | master 原生侧；Android 取数层与分页 UI |
| pin 策略 + 域名豁免（Windows 移植） | Windows 分支（`F2 ④`） |
| 证书被拒 / (风暴) / (仅拒绝) 显示 | Windows 分支 GUI |
| SonarCloud 配置 | `sonar-project.properties` + 两个仓库的排除项 |
