ADBlock 拦截服务器 - Windows 11 (x64) 独立版
================================================

这是什么：
  取自 ADBlock（AdAway 分支）项目中的 C 语言 Web 服务器组件
  （webserver/jni/webserver.c + mongoose + OpenSSL），打包为原生
  Windows 11 x64 可执行文件。功能：
    * 拦截占位图 / 空 JS / CSS / 字体 / 媒体 / API 请求（与 Android 相同的
      CORS 与缓存策略）；
    * 每个域名使用自有 CA 签发的 HTTPS(SNI) 证书；
    * 原生仪表盘：实时 KPI、按小时/按天柱状图、证书状态、"信任 CA"、
      "开机自启动"、"深色外观"；
    * "监听状态"卡片：每个端口是否绑定成功一目了然；
    * 热加载 block_config.json / allowlist.txt；
    * /internal-stats（JSON）、/internal-ws（实时）、/internal-test。

快速开始（双击即可）：
  1. 解压到任意目录（例如 C:\ADBlock），所有文件放在一起。
  2. 双击 start.bat。
     - 弹出仪表盘窗口，显示实时统计与图表。
     - 服务器监听 http://localhost:8080 与 https://localhost:8443
       （resources 目录位于 exe 同级，缺失时自动创建）。
  3. 在仪表盘中：
     - "信任 CA" - 把本机 CA 装入 Windows 受信任根，https 显示安全锁；
     - "打开测试页" - 打开 https://localhost:8443/internal-test；
     - "开机自启动" - 注册 HKCU Run（无需管理员；命令行带 --minimized，
       开机后常驻托盘）；
     - "深色外观" - 立即切换深色主题并记住选择（未勾选时跟随 Windows）；
     - 关闭窗口 = 最小化到托盘（双击托盘图标恢复，右键菜单可退出）。
  4. 端口被占用时：仪表盘照常打开，"监听状态"卡片会标出失败的端口，
     点"改用备用端口 18080/18443"即可自动换端口并重启。

命令行：
  webserver.exe [选项]
    --resources <dir>  资源目录（img_*.webp、test.html、allowlist、
                       block_config.json）。默认 exe 同级的 resources。
    --http-port N      HTTP 端口（默认 8080）
    --https-port N     HTTPS 端口（默认 8443）
    --stats-port N     仅回环的管理端口（默认 8686）：提供 /internal-stats
                       与 /control。即使 8080/8443 被占用或绑定失败，
                       仪表盘仍能读取统计并控制服务器。
    --bind all         监听所有网卡（默认仅回环）
    --minimized        启动后直接进托盘（开机自启动使用）
    --debug            输出 mongoose 详细日志
    --no-gui           无界面运行（纯服务）
    --install-autostart / --uninstall-autostart   注册/移除开机自启动后退出

日志与配置：
  * webserver.log      与 exe 同目录（超过 1 MB 自动轮转）：绑定失败、
                       证书生成、配置重载等事件都会记录；
  * webserver.ini      保存 http/https 端口、bind_all、theme；
                       也可手工写 proxy=127.0.0.1:7890 指定更新用的代理；
                       不写时更新器按顺序自动选线路：直连 → 系统代理设置
                       → 上面这个 proxy → HTTPS_PROXY/HTTP_PROXY 环境变量
                       → 本机常用代理端口（7890/7891/10809/10808/1080/
                       8888），每条线路都真的发一次 HTTPS 请求验证，
                       选中的线路会写进 webserver.log；
  * block_config.json  拦截策略（界面里勾选即时生效）。

使用 80/443 端口：
  需要管理员权限：以管理员身份运行终端，并加 --http-port 80 --https-port 443。

性能说明：
  * 统计采集在独立线程中完成，界面不会因服务器无响应而卡顿；
  * 证书状态与受信任根查询有 30 秒缓存；
  * 统计 / SNI 缓存落盘做了节流（最多每 15 秒 / 60 秒一次），
    避免无意义的磁盘写入。

Windows SmartScreen / 杀毒软件：
  未签名的 exe 可能提示"Windows 已保护你的电脑" → 更多信息 → 仍要运行。
  仓库配置 WINDOWS_CERT_PFX_B64 / WINDOWS_CERT_PASSWORD 后会自动签名。

平台差异：
  * 透明代理（--proxy-filter，依赖 iptables 重定向）只在 Android/Linux 上
    生效，Windows 上不会启用。

故障排查：
  * 更新很慢或者一直失败：这条线路到 GitHub 资源节点（release-assets.githubusercontent.com）
    的下载速度实测可能只有十几 KB/s，4 MB 的包需要四分钟左右。更新器现在会
    自动重试最多 5 次、并且断点续传（仪表盘状态栏显示百分比），请让它跑完；
    主包（安装器）实在取不回来时会自动改用体积更小的便携包。
  * "无法创建 resources 目录" / "CA 生成失败" - 目录不可写：换到用户目录，
    或用 --resources 指定其它位置。
  * 仪表盘一直显示"等待服务器 ..." - 等几秒；仍不行请查看 webserver.log，
    并看"监听状态"卡片（红色 = 该端口没有绑定成功）。
* 按应用统计：Windows 没有 /proc，改用 IP Helper 的 TCP 表（GetExtendedTcpTable）
  把每条连接映射到所属进程，再取进程名。侧边栏"应用日志"页显示
  「进程 / 连接数 / 请求数 / 拦截数」排行与最近请求（拦截 / 放行 / 代理 + 主机名）；
  查不到所属进程时显示 `id <PID>`。
* "应用日志"页的"方式"列回答"为什么被拦、怎么拦的"：命中的策略类型
  （图片/脚本/样式表/字体/媒体/页面结构/API/遥测/配置/WebSocket）+ 该类型当前的处理方式
  （占位 / 204 / 放行）。设置页的"检查更新"按钮在"导出诊断信息"下方。
