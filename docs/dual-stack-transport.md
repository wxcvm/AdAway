# 隔离双栈转发与恢复验证

本阶段验证 UDP 数据链路、停止和重启。不会配置默认路由、系统 DNS、TUN/WFP 全机接管、AdGuard 或证书。

Android 专用诊断 VPN 仅允许本应用，使用 198.18.0.1/32 和 2001:2::1/128 两条测试路由；接口地址分别为 198.18.0.2/32 和 2001:2::2/128。UDP 53536 经 protect 后分别发往 127.0.0.1 / ::1 的临时端点，返回随机请求的变换回执，再封装为 TUN 回包。原 IPv4 入口保留，设置页增加按协议分开的测试按钮。测试服务仍需用户显式授权，外部 VPN、Root 重定向、Always-on / Lockdown 冲突时拒绝启动。

Windows 网络中心提供“测试双栈转发与恢复”。用户点击后才创建四个临时、互相连接的本机 UDP 套接字，逐项验证：原始 IP 报文入口 → 解析 → 固定本机后端 → 响应验证 → IP 回包。入口是 mock TUN 数据报夹具，**没有创建 Windows TUN 或系统路由**。每条路径共享 900 ms 总预算，取消每 20 ms 检查，关闭前清理本次全部套接字。窗口提供停止、重试及按协议的清理结果；不将本机结果标为全局接管成功。

共享固定原始报文向量比较 Java 和 C 的完整 IP/UDP 回复。只支持固定测试地址与 53536，不允许任意目的地；IPv6 仅接受直接 UDP 下一头，拒绝扩展头、分片、零校验和、错误端口、长度或来源。保留 256 字节转发载荷上限。Windows 通过独立固定提交与 SHA-256 获取共享头、测试和向量，生产 portable core 固定版本保持不变。

每次测试使用新的套接字和随机载荷，迟到或内容不符的回执拒绝。Android 关闭会话时同时关闭正在接收的上游、两个本机后端和有界 packet pump；已关闭 pump 不允许写入旧结果。探针比较当前服务 session ID，停止／重启后不接受旧会话的成功。Windows 每次运行同步完成自己的套接字清理，下一次使用新的会话资源。

验证包括共享报文、严格 C 编译及 ASan/UBSan；Windows 实际双栈套接字、真实接收超时、错误／迟到回执、在途取消、重复重启和句柄清理；Android API30/35 实际 packet pump、双栈后端、超时、在途关闭、丢弃旧回复和新会话恢复。专用 CI 脚本先确认是 emulator，才临时授予测试包 VPN 同意；实际建立专用 TUN，验证两种 IP 转发、无默认路由／DNS、三次停止重启及底层路由和 DNS 恢复，结束撤销测试授权。真实手机、Windows 驱动接管、外部网络、TCP/QUIC 与长时间性能验收仍单独进行。

库选择：复用平台 DatagramSocket / VpnService.protect 与 Winsock / BCrypt；本阶段没有新增加通用用户态 TCP/IP 栈或驱动依赖。

参考：[Android VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder)、[IPv6 benchmark prefix](https://www.rfc-editor.org/rfc/rfc8219.html)、[IPv6 UDP checksum](https://www.rfc-editor.org/rfc/rfc8200.html)、[Winsock 非阻塞 I/O](https://learn.microsoft.com/en-us/windows/win32/winsock/nonblocking-i-o-2)。
