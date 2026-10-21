# 隔离双栈 TCP 后端（2026-10-21）

本阶段在设置中的专用诊断 VPN 运行时提供 TCP IPv4、IPv6 两个手动按钮。它验证真实平台 TCP 套接字和受保护的 SOCKS5 后端，为用户态传输库适配做准备；**TCP 报文尚未经过 TUN**，也没有启用通用转发、默认路由或生产代理。已有 UDP/TUN 路径继续独立验证。

每次运行创建两个临时回环监听和两对 TCP 连接，正常结束关闭 6/6 个套接字。仅接受用户名 `adaway`、每次新生成的 16 字节随机凭据，以及固定 `198.18.0.1:53539` 或 `[2001:2::1]:53539` 的 CONNECT 子集；实际目标映射到同地址族的私有临时回环端点。失败认证或目标校验不创建上游连接。随机凭据不持久化、不输出日志；未实现域名目标、BIND、UDP ASSOCIATE 或任意外部目标。

上游 Socket 在 connect 前调用当前 VpnService.protect。请求最多 256 字节，逐次最多写 3 字节、读 7 字节，完整读取并验证协议帧，使用一次 1200 ms 总预算。客户端半关闭后，后端收齐 EOF 再返回变换回执；截断、过大及错误回执失败。取消关闭本次所有资源，停止服务同时撤销当前 TCP 会话；旧会话不能报告新会话成功。工作线程清理有独立 500 ms 上限并报告失败。

共享 Java/C 的 46 条固定向量覆盖认证、CONNECT、IPv4/IPv6、返回码、实际绑定地址/端口和损坏帧。JVM 与 API 30/35 测试使用实际套接字覆盖正常收发、重复启动、错误认证/目标、截断/损坏/超限、超时与在途取消后恢复。专用模拟器的六轮真实 VPN 生命周期中额外调用两族 TCP 后端，断言每次 protect 一次且清理 6/6；这仍是 VPN 服务关联的后端测试，不是 TUN TCP 报文转发。UI 回归检查未启动、可运行及忙碌状态。

库契合度：本阶段复用平台 Socket、SecureRandom、VpnService.protect，未引入新 TCP/IP 实现。后续 TUN TCP 适配候选为 MIT 的 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)，其 Android/Windows 支持和可配置 JNI 入口适合继续评估；旧固定提交的校验、全局生命周期、FD 所有权与保护回调仍需逐项审查，不能由新版 README 推断已经解决。另评估 [tun2socks](https://github.com/xjasonlyu/tun2socks) 的 gVisor/Go 栈，但其 Go 工具链、运行时和 JNI 桥接增加当前双端构建成本，本轮不打包。旧评估记录见 isolated-transport-evaluation.md，属于历史固定提交证据。

能力边界：真实手机及厂商系统、外部 IPv6、网络切换、通用并发双向流、TLS 证书信任、QUIC/HTTP3、Windows TUN/WFP 驱动与恢复均需独立验收。TCP 回环成功不等于 HTTPS 或 QUIC 通过。日常 Windows 主机独立 Java/Python 半关闭探针也出现异常 EOF，保留为环境证据，不改系统网络，不把本机失败改成通过。

协议依据：[RFC 1928](https://www.rfc-editor.org/rfc/rfc1928.html)、[RFC 1929](https://www.rfc-editor.org/rfc/rfc1929.html)、[VpnService.protect](https://developer.android.com/reference/android/net/VpnService#protect(java.net.Socket))。
