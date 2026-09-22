# Windows 层分支说明（feat/win11-webserver-release）

## 交付模型
- **Windows 11 x64 独立版从本分支发布**，与 master 上的 Android 主线互不影响。
- 工作流 `.github/workflows/windows-release.yml` 在本分支 push 时运行：
  编译 → HTTP/HTTPS 冒烟 + 监听表断言 → GUI 存活 → 端口冲突处理 → 自启动注册表 → 打包 → 发布 Release。
- 发布标签规则：`win11-webserver-v<major>.<minor>`（当前 `win11-webserver-v1.40`）；
  工作流顶部的 `TAG` 是唯一来源，`gui_win32.h` 与 `installer.iss` 的版本号必须与它一致（CI 会断言）。
 程序内版本号见 `gui_win32.h` 的 `ADBLOCK_APP_VERSION`。

## 应用内更新链路（2026-09-19 起）
- 检查更新：优先读固定标签 `win11-latest` 上的 `windows-manifest.json`（纯下载链接，不经过 REST API，
  因此不受 60 次/小时限流）；失败才回退 API，并带本地答案缓存与限流冷却。
- 装机优先级：清单里有 `exe_url` 时下载 Inno 安装器，缺失时自动回退 `zip_url` 便携包。
- 便携副本就地更新：运行目录与安装器记录的 `InstallLocation` 不一致时（用户自己解压 zip），
  改用 zip 就地覆盖，避免安装器在 `%LOCALAPPDATA%\Programs\ADBlock` 装出第二份、
  而用户实际运行的那个目录永远停在旧版本。
- 静默安装必须自己拉起程序：安装器给更新用的 `/VERYSILENT` 会跳过 `[Run]` 里带 `skipifsilent` 的条目，
  而 `RestartApplications=no` 也不会替我们重启，所以 `installer.iss` 增加了一条 `Check: WizardSilent` 的启动项。
- 慢线路必须能下载完（v1.32）：实测本机到 GitHub 资源节点只有约 16 KB/s（4 MB 包 ≈ 4 分钟）。
  更新包下载改为**断点续传 + 最多 5 次重试 + 20s/120s 超时**，仪表盘状态栏显示百分比；
  主包（安装器 5.3 MB）取不回来时自动改用便携包（4.1 MB），并用对应包的摘要校验。
- 清单必须能被严格 JSON 解析：CI 用 Python 生成并校验后才上传（历史缺陷：`sha256sum` 对含反斜杠的
  Windows 路径会在行首加 `\`，`cut -f1` 把这个标记带进了摘要，清单因此不是合法 JSON）。
- 线路必须自己选（v1.33）：WinHTTP 的“自动代理”只认**系统代理设置**，而本机实测
  `github.com` 直连完全不通（21 s 超时）、`api.github.com` 1.7 s 就答、本机 Clash 可用，
  用户却常常没打开系统代理开关，于是更新永远下载不了。现在更新器按
  直连 → 系统代理 → `webserver.ini` 的 `proxy=` → `HTTPS_PROXY/HTTP_PROXY` →
  本机常用代理端口（7890/7891/10809/10808/1080/8888）的顺序，**每条都真的发一次 HTTPS 请求**
  验证（只测 TCP 连通会把“能连上但不转发”的端口当成可用线路），选中的线路写进 `webserver.log`；
  下载或检查失败后清除缓存，下次重新探测（代理是刚开/刚关时不再卡在旧结论）。
- 失败必须能自证 + 清单必须重试（v1.34）：实测“网络正常仍更新失败”的真身是
  **GitHub 匿名接口限流**——`api.github.com` 返回 `403 rate limit exceeded`
  （`X-RateLimit-Remaining: 0`，每小时每 IP 60 次，共享/代理出口几乎必然用完），
  而更新检查只要清单那一步失败就会掉到这条被限流的接口上，界面上就只剩
  “检查更新失败：GitHub API 限流”。改动：
  1. 清单下载重试 3 次（1 s / 2 s 退避），不再一次失败就转去 API；
  2. 每次请求都写日志（线路、URL、HTTP 状态、耗时、WinHTTP 错误码），
     直连成功也写 —— 之前失败时日志里一个字都没有，无法排查；
  3. 用户点“检查更新”一律重新探测线路并忽略 403 冷却缓存，点击一定会真的重试；
  4. 失败文案同时给出清单线路与接口的状态，不再只报“限流”；
  5. 新增 `webserver.ini` 的 `mirror=`（GitHub 加速站，如 `https://ghfast.top/`）：
     清单与更新包都改从加速站取，下载后仍强制校验发布方 SHA-256，加速站无法掉包。
- “最近请求”能看到全部历史（v1.35）：服务端 `/internal-stats` 只发最新 **24** 条
  （`QLOG_RENDER_MAX`），仪表盘表里又只画 11 行 —— 环形缓冲其实存了 4096 条，
  用户看到的却只有几秒钟。现在发 400 条（`QLOG_RENDER_MAX 400`、
  `QLOG_JSON_MAX 48 KB`、统计文档缓冲 96 KB），仪表盘保留 400 条并用**滚轮翻页**，
  卡片右上角显示“共 N 条”。
- 证书信任变可信、可解释（v1.35）：原来只写“当前用户根”，且状态只有“已信任/未信任”
  一个布尔值 —— 用其它账户或系统服务身份运行的程序照样报警。现在
  `cert_trust_state()` 区分**用户根/本机根**，安装时两个都试（本机根需要管理员，
  失败会明说而不是静默忽略），改完再回读存储校验一次，状态栏与弹窗显示
   “已信任（用户根 + 本机根）”这类精确结果，过程写进 webserver.log。
- 拦截方式可按类型自定义（v1.36）：原来每类型只有三个状态（占位 / 204 / 放行），
  而且用一个三态复选框表达，谁也看不懂。现在四种方式：
  `占位响应`（图片/媒体/页面结构：看得见占位图）、`空响应`（200 + 0 字节，
  脚本/样式/字体默认用它——204 会让 Chromium 对 <script>/<link> 打印控制台报错）、
  `204 快速拒绝`（API/遥测/配置/WebSocket 默认）、`不拦截`（只统计）。
  推荐默认由 `g_policy[].def` 与 `webserver.c` 的 `mode_*` 初值两处保持一致；
  面板里每个类型是一个按钮，点击在四种方式之间循环，另有“恢复推荐默认”。
  查询日志会显示“拦截 · 脚本 · 空响应”，不再只有一个“拦截”。
- 按外部审计（70 条）修更新链路（v1.37）：
  1. 并发（第36条）：新增 `s_update_state`（空闲/检查中/下载中/安装中），
     第二次点击不会另起线程；面板“检查更新”按钮在忙时禁用并显示阶段名；
  2. 线路探测测错目标（第41条）：探测请求改成真正要取的
     `win11-latest/windows-manifest.json`（会 302 到 release-assets 主机），
     一次请求同时验证两个主机，不再用 /robots.txt 假装健康；
  3. mirror 覆盖不全（第42条）：`mirror_map()` 同时改写
     `release-assets.githubusercontent.com` / `objects.githubusercontent.com` /
     `raw.githubusercontent.com`，否则清单能过、5 MB 更新包仍走被墙主机；
  4. 半截响应当成功（第45条）：`http_get()` 区分“读完 / 读失败 /
     长度不足”，只有完整读完才返回内容；
  5. ShellExecute 未检查（第37条）：安装器没起来时**不再退出进程**，
     弹窗给出返回码与临时包路径；
  6. 无摘要不再安装（第48条）：发布信息没有 SHA-256 时直接拒绝自动更新，
     不再“任意有效 Authenticode 签名都算可信”；
  7. 证书文案（第5/29条）：不再宣称“浏览器显示安全锁”，改为明确说明
     Chrome/Edge 立即信任、Firefox 需单独导入，并且只写进用户根时给出
     “其它账户/系统服务仍会报警 + 用管理员再点一次”的提示。
- 便携更新的数据安全（v1.38，审计第38/39/40条）：原来的脚本用
  `xcopy /E /I /Y staging appdir` 直接盖正在运行的目录 —— 中途失败会留下
  半新半旧且无法回滚，还会覆盖 Inno 安装器明确保留的用户数据
  （localhost-2410.*、*.dat、allowlist.txt、blocklist.txt、cosmetic.css、
  webserver.ini 以及用户自己换的占位图）。现在：
  等待本进程退出 → **把旧目录整名改走**（改名不会半成功）→ 新构建就位 →
  把用户数据与自定义占位图回填 → 启动并观察 5 秒（进程必须存活）→
  失败则杀掉、删掉新目录、把备份名改回并启动旧版；旧目录被占用时
  直接放弃（什么都不改）。成功后才删除备份与 staging。
- /control 加身份认证（v1.38，审计第33条）：仅回环还不够，本机任何进程都能
  改配置/关服务。服务器每次启动生成随机令牌，写到 `resources/control_token.txt`，
  `/control` 必须带 `X-ADBlock-Token`（或 query 里的 `token=`）否则 403 并记日志；
  仪表盘每次调用前都读该文件（服务器重启后自动跟上）。
- 证书 Profile 规范化（v1.39，审计第11/12/13/14/15条）：
  根 CA 不再带 `serverAuth` 这类 EKU（根证书带 EKU 既限制了用途，也让部分校验器
  不满）；根 CA 的 `basicConstraints` 增加 `pathlen:0`（被窃取的 CA 私钥也无法
  再签出下级 CA）；叶子证书显式写 `critical,CA:FALSE` 并补上
  `authorityKeyIdentifier=keyid,issuer:always`；EC 叶子的 KeyUsage 改为
  `digitalSignature,keyAgreement`（ECDSA 证书声称 keyEncipherment 是错的，
  RSA 叶子仍用 `digitalSignature,keyEncipherment`）。
  注意：已存在的 `localhost-2410.crt` 不会被自动替换（避免打断已信任的 CA），
  新版只对“新生成/新轮换”的 CA 与之后签发的 SNI 叶子生效。
- 单例跨会话 + 日志写入（v1.40，审计第35/61条）：
  互斥体从 `Local\` 改到 `Global\`（快速切换用户 / RDP 与控制台会话并存时，
  `Local\` 各管一套，两个实例会抢端口），普通用户令牌建不了全局对象时回退
  `Local\`；若全局名已存在但当前令牌无权打开（ERROR_ACCESS_DENIED），也判定为
  “已有实例在跑”，不会重复启动。日志不再每行 `fflush()`——只有 WARN/ERROR/FATAL
  立即落盘，INFO（含心跳与每条请求）交给缓冲、轮转和退出路径，磁盘写入明显减少。

## 为什么没有合并回 master（2026-09 决策）
- master 已前进 17 个提交（劫持过滤、AdGuard 规则语法、服务器自愈看护、统计与端口解耦等），
  其中同样大改了 `webserver/jni/webserver.c`（+844 行）。
- 本分支对同一文件加入 Windows 层（+1534 行：可移植 shim、webserver.log、监听注册表、
  bind_try 重试、--stats-port 管理端口、落盘节流等）。
- 两侧改动在同一批函数里交叠（文件头 include 区、`struct settings`、`addr_to_proc_*`、
  信号处理、连接空闲超时、`build_stats_json()`、`main()` 绑定流程），逐块合并属于专门的移植工作，
  不适合在发布流程里顺手做（程序内三方合并实测有数十处交叠区域）。
- 结论：**Windows 层留在本分支**，master 保持 Android 主线干净。将来若要合并，
  按下面的清单逐个函数移植，并用两个 CI（android-ci + windows-release）验证编译。

## Windows 层改动清单（便于将来一次性移植）
`webserver/jni/webserver.c`
0. 按应用归属（v1.29）：`conn_uid_by_tuple()` 在 Windows 上不再直接返回 -1，而是用
   `GetExtendedTcpTable(TCP_TABLE_OWNER_PID_ALL)` 找到客户端 socket 的所属 PID
   （行匹配方向与 /proc 扫描一致：行的 local/remote 对调），PID 放进原来 uid 的字段；
   `win32_process_name()` 再把 PID 变成进程名并加进 /internal-stats 的 apps[]。
   仪表盘新增"应用日志"页（第 3 个页签）展示 apps[] 与 query_log[]。
1. 可移植性 shim：`#ifdef __ANDROID__` 包裹 logcat/linux 头；Windows 走 `win32_dirent.h`、
   `strcasecmp -> _stricmp`、`uid_t` 定义；`conn_uid_by_tuple()` 在 Windows 返回 -1（无 /proc）。
2. `webserver.log` 镜像：`log_file_line()`，1 MB 轮转，所有 LOG_* 同步落盘（GUI 子系统无控制台）。
3. 落盘节流：`persist_dat_files()`（统计 15 s / SNI 60 s），`/internal-stats` 不再每次全量写盘。
4. 监听注册表 + `bind_try()`（最多 3 次、间隔 400 ms）：记录 name/addr/bound/ipv6/loopback；
   `/internal-stats` 追加 `bind_ok` / `stats_port` / `listeners[]`（只追加字段，Android 解析不受影响）。
5. 管理端口 `--stats-port`（默认 8686，仅回环）与 `adblock_instance_running()` 单实例探测。
6. `--minimized` 与自启动命令行（带 `--stats-port`、去掉旧的 `--no-gui`）。
7. 构建修复：`build_stats_json()` 的格式参数表、`write_stats_json_file()` 调用签名。

`webserver/win32/`
- `gui_win32.c` / `gui_win32.h`：原生仪表盘（浅/深主题、KPI、图表、监听状态卡片、托盘、自启动、证书信任）。
- `build.sh`：mingw64 构建，嵌入 manifest + app.ico，链接 dwmapi/uxtheme。
- `app.rc` / `app.manifest`（comctl32 v6 + PerMonitorV2）、`win32_dirent.h`、`start.bat`、`README.txt`。

## 已知取舍
- 透明代理（`--proxy-filter`）为 Android/Linux 专属：Windows 没有 iptables 等价机制，跑的是常规拦截。
- 深色主题下原生子控件（EDIT/BUTTON）用 `SetWindowTheme` 尽力而为，个别系统版本仍可能显示浅色边框。
- GDI 字体/画刷未做对象缓存：重绘只在数据更新或 2 秒定时器时发生，证书存储与文件 I/O 已缓存/节流，收益有限。
- 按应用统计在 Windows 上显示 unknown（没有 /proc 等价物）。

## CI 时长（2026-09-19 优化）
- MSYS2 步骤去掉 `update: true`（每次 `pacman -Syu` 约 56 s），依赖动作自带的缓存 +
  `--needed` 安装；Pillow 只在真的缺失时才 pip 安装。
- 冒烟/GUI/端口冲突/自启动各测试改成轮询就绪状态，不再用固定 `Start-Sleep`
  （原来共约 49 s 的死等）。
- 发布类步骤（Release、公共更新源、安装器上传、清单）加 `if: github.event_name != 'pull_request'`，
  PR 构建只做验证，绝不发布。
