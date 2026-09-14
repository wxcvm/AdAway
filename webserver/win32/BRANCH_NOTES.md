# Windows 层分支说明（feat/win11-webserver-release）

## 交付模型
- **Windows 11 x64 独立版从本分支发布**，与 master 上的 Android 主线互不影响。
- 工作流 `.github/workflows/windows-release.yml` 在本分支 push 时运行：
  编译 → HTTP/HTTPS 冒烟 + 监听表断言 → GUI 存活 → 端口冲突处理 → 自启动注册表 → 打包 → 发布 Release。
- 发布标签规则：`win11-webserver-v<major>.<minor>`（当前 `win11-webserver-v1.11`）。
  程序内版本号见 `gui_win32.h` 的 `ADBLOCK_APP_VERSION`。

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
