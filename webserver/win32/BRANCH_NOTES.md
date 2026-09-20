# Windows 层分支说明（feat/win11-webserver-release）

## 交付模型
- **Windows 11 x64 独立版从本分支发布**，与 master 上的 Android 主线互不影响。
- 工作流 `.github/workflows/windows-release.yml` 在本分支 push 时运行：
  编译 → HTTP/HTTPS 冒烟 + 监听表断言 → GUI 存活 → 端口冲突处理 → 自启动注册表 → 打包 → 发布 Release。
- 发布标签规则：`win11-webserver-v<major>.<minor>`（当前 `win11-webserver-v1.29`）；
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
- 清单必须能被严格 JSON 解析：CI 用 Python 生成并校验后才上传（历史缺陷：`sha256sum` 对含反斜杠的
  Windows 路径会在行首加 `\`，`cut -f1` 把这个标记带进了摘要，清单因此不是合法 JSON）。

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
