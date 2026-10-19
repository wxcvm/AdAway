# 自定义响应服务器监听地址

HTTP / HTTPS 设置分别提供 IPv4 和 IPv6 输入框，支持同时监听。某个输入框留空表示关闭该协议；两者不能同时关闭。
“仅本机（推荐）”填入 127.0.0.1 和 ::1，适合设备自身的广告响应。选择网卡可列出当前已分配的本机地址；选择只修改对应协议的草稿。
所有网卡选项填入 0.0.0.0 和 ::，会允许局域网访问响应端口，需要明确选择；管理与控制入口仍仅允许回环来源。
旧版本的单一 bind_ip、回环或所有网卡配置按原语义读取，不自动打开原来关闭的协议；首次成功应用后才保存独立字段。
输入只允许字面 IP；不解析主机名，不支持方括号、IPv6 zone、路径或额外 shell 参数。HTTP、HTTPS、管理端口必须不同。
已有 Root 授权时，编辑后自动检测；否则由“检测冲突”请求诊断，不会因打开设置弹出 Root 请求。
诊断通过原生 socket bind 检查本机地址、占用和权限。运行中的本应用也可能显示占用；应用时停止旧服务后复检。
应用失败恢复原偏好和开机脚本，重启原服务，并报告恢复是否就绪。不会更改系统 DNS、路由或其他 VPN 配置。
Root 透明重定向仍需要 IPv4 回环监听；不兼容的自定义地址会拒绝应用。VPN 是否活跃与端口是否冲突分别处理。
管理端口继续固定回环 8686；远程来源无法访问内部诊断或透明转发。独立配置中启用的两个协议都必须通过检测并成功启动，否则恢复原配置。

2026-10-20 连接的 Android 17 设备证实，6.7.6-preview.16 的 APK 与暂存服务器完全相同，均为 3816 字节、ELF entry=0 的占位共享库。
旧 progress-android-20261019.1 预览已转为草稿。原因是旧任务通过全局命令中的 Debug 字样选择变体，混合 Debug 测试和 Release 打包时可能仅替换 Debug 占位库。
现在删除占位库构建，按 AndroidComponents 公共生成源码接口给每个变体独立提供真实可执行文件。
最终 APK 必须通过 ARM64 ELF 入口、可执行加载区段和本次变体代码一致性检查；运行时在停止原服务前再次拒绝无效安装包。

依赖选择：保留现有 Mongoose 的 mg_http_listen 和事件循环，分别创建 IPv4 / IPv6 监听；不整体更换服务器或引入第二套 TLS/生命周期。
本机地址使用 Android 的 java.net.NetworkInterface 枚举，排除回环、链路本地、多播、点对点和虚拟接口；兼容 API 30 虚拟网卡异常。
参考：https://mongoose.ws/docs/api/http/ 、https://developer.android.com/reference/java/net/NetworkInterface 、https://github.com/android/gradle-recipes/tree/agp-9.0/addGeneratedSourceFolder 。

默认预览发行使用正式包名 com.wxcvm.Adaway 和现有生产签名，覆盖升级并保留应用数据。
Android 稳定版和预览版现在共用从 2020-01-01 起的 UTC 秒数作为 versionCode，避免不同工作流计数器造成预览无法升级或以后无法回到稳定版。
发布预览时同时校验生产 APK 的签名和 versionCode；不修改稳定更新源。
卸载后降级会丢失数据；正常后续更新应使用更大的 versionCode。更高版本号不代表已经完成真实 Root 设备验收。

API 30/35 模拟器验证共享原生诊断入口的 IPv4/IPv6 占用；完整 ARM64 NDK/APK 构建单独检查实际服务。
Root 启动、SELinux、开机服务和第三方 VPN 联动仍需真机单独验收。
