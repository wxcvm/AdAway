# 跨平台全流量过滤：第一阶段实施规范

状态：M1 决策和 Root 冲突防护已实现并通过 CI；Android VpnService 全路由与 Windows TUN/WFP 尚未接入。Windows 已迁移至独立仓库 wxcvm/AdAway-Windows，开发见其 PR #1。本 PR 不安装新的网络驱动，也不修改第三方设置。

## 当前实现进度（2026-10-12）

- Android 端加入 `TrafficModePolicy`、`AndroidVpnObserver`：分别判断建议模式与只读 VPN 信号，不将 AdGuard 安装误判为已确认接管。
- Root `HijackModel.enable` 在安装规则前检查 VPN。检测到外部 VPN 时撤销本产品自己的重定向链并保留监听服务器；VPN 状态未知时不安装新的重定向。
- `StatsViewModel` 在持久化 HIJACK 偏好前检查归属；设置页显示真实 iptables 规则是否存在，而不只显示选择过的模式。
- `AndroidVpnConflictMonitor` 在应用进程存活时监听 VPN 默认网络变化，节流后交由现有看门狗复核，另有 5 项防抖单测。
- 本地服务器被关闭时，看门狗会尝试清理本产品自身遗留的透明重定向。
- AdGuard DNS custom IP 使用提示已纠正：仅 DNS 层规则命中、80/443 可达和 HTTPS CA 信任均需分别验证；不能仅凭 DNS 返回 IP 宣布“全部流量已过滤”。
- Windows 独立仓库已含 AdGuard Home 协同配置向导与只读进程探测，相关 PR 通过 CI；**尚无** Windows TUN/WFP 全局接管。

## 目标和非目标

- 默认意图：在用户授权后，尽可能过滤设备经过本产品网络路径的 IPv4/IPv6、TCP/UDP 和 DNS 流量。
- Android：规划 VpnService 默认模式，保留 Root 高级模式；两者不能同时重复劫持同一流量。
- Windows：先评估 TUN + 用户态转接，再决定是否需要 WFP。
- 与 AdGuard 等过滤器冲突时，停止争抢网络接管权，切换为可验证的协同模式。
- **不承诺**无条件解密全部 HTTPS、绕过证书固定或接管其他 VPN 已独占的流量。
- 不在检测到应用已安装时直接改变模式；只有**活动接管路径冲突**才可触发自动切换。

## 统一模式状态机

| 模式 | 接管行为 | 进入条件 |
| --- | --- | --- |
| STANDALONE | 本产品全局接管 | 用户授权，健康检查通过，无已确认冲突 |
| COEXIST_LISTEN | 仅启动本地响应监听和可用的非冲突能力 | 已确认冲突且用户允许协同 |
| OTHER_VPN | 不争抢 VPN | 发现其他 VPN 且协同入口尚未确认 |
| UNKNOWN | 保持原网络配置 | 探测结果不足或缺少权限 |
| DEGRADED | 撤销本产品新增的路由/过滤设置 | 启动、健康检查或恢复失败 |

### 转移约束

1. 所有接管操作先保存系统原有配置；失败必须尝试恢复。
2. **检测到 AdGuard 安装 ≠ 检测到冲突**；不能仅凭包名/进程名关闭接管。
3. 从 STANDALONE 进入 COEXIST_LISTEN 时先撤销本产品的接管，再开启监听，避免双重劫持。
4. COEXIST_LISTEN 不代表已收到流量：必须分别检测 DNS 解析、目标 IP 可达、80/443 端口和 HTTPS 信任。
5. 网络状态变化后重测，但限制重试频率，禁止反复切换导致断网。
6. 用户显式选定的模式优先于自动建议；不得自动更改第三方应用的配置。

## AdGuard 集成边界

区分 AdGuard Home、AdGuard for Android 和 AdGuard for Windows。只对已验证支持的产品版本展示具体配置步骤。

AdGuard Home 自定义阻断 IP 的基本要求：
- DNS A/AAAA 返回的地址必须对**请求设备**可达；127.0.0.1 仅适用于请求设备本机。
- DNS 不携带 HTTP 端口；拦截响应服务必须能接收目标设备对 80/443 的连接，或有明确且安全的端口转接。
- HTTPS 内容替代依赖客户端对证书链的信任；证书固定应用不保证可用。
- 启用前验证、变更前备份、退出时提供回滚，不默默写入 AdGuard 配置。

## 组件边界

- **Platform Capture**：Android VpnService / Root，Windows TUN 或 WFP；管理路由、排除、权限和回滚。
- **DNS Transport**：上游解析、缓存、加密 DNS；不直接决定 UI 或网络接管模式。
- **Rule Engine**：Hosts/ABP 规则匹配、例外和动作判定；以一致性测试验证替换可行性。
- **Response Server**：保留现有 Mongoose/OpenSSL 本地响应能力，默认仅回环监听；LAN 访问必须显式授权。
- **Coexistence Detector**：给出 evidence、confidence、conflictType，不把单个信号当作确认。
- **UI**：显示“接管中 / 仅监听 / 未确认 / 已回滚”，避免把服务启动误报为全局生效。

## 实施顺序和验收标准

### M1：模式判定与测试
- 实现独立、无系统副作用的模式决策函数和测试。
- 输入至少覆盖：用户选择、VPN 状态、AdGuard 安装、AdGuard 活动接管证据、端口冲突、权限。
- 验证“仅安装 AdGuard”不会触发切换；“不确定”不会覆盖系统设置。

### M2：Android 流量接管
- 引入 VpnService 所需权限、前台服务与用户授权流程。
- 先支持 IPv4/IPv6 路由和 DNS，再验证 TCP/UDP、应用排除、VPN 环路与网络切换。
- 与 Root 模式互斥，切换失败可恢复。

### M3：Windows 独立仓库
- 从经过核实的 Windows 分支提交迁移历史、构建脚本、安装器、CI。
- 保留独立版本号与发布流程；不要在此阶段直接复制并长期分叉规则引擎。
- 对 TUN、路由恢复、休眠唤醒、DNS 泄漏、IPv6、UDP 做端到端测试。

### M4：AdGuard 协同
- 优先实现 AdGuard Home 的配置向导、连通性验证、备份与回滚。
- 其他 AdGuard 客户端仅提供经过版本核实的手动引导；不假定存在公开稳定配置 API。

### M5：共享核心
- 先冻结跨平台规则行为测试，再评估 adblock-rust、dnsproxy 和协议转接库的 ABI、许可证、体积与性能。
- 稳定后抽离独立核心仓库；禁止在两个产品中长期复制共享逻辑。

## 安全验收底线

- 默认不开放未经鉴权的 LAN 代理；特别禁止任意 Host 转发与 bind-all 组合暴露开放代理。
- 不默认开启 HTTPS 中间人；CA 私钥应限制访问、可撤销并明确提示用户。
- 网络接管和第三方配置变更均可撤销；失败不得遗留失效 DNS、路由或防火墙规则。
- 不以“所有流量已过滤”描述无法验证的 QUIC、证书固定、VPN 绕行或系统特权流量。
