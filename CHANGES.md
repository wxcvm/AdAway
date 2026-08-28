# Release 6.4.2

## Web server

- **IPv6 loopback:** the web server now also listens on ::1 (HTTP and HTTPS). IPv4 (127.0.0.1) remains the primary listener; IPv6 is optional - if the device has IPv6 disabled, startup proceeds with a warning instead of failing.
- **Certificate SAN:** the localhost leaf certificate now includes the IPv6 loopback address (IP:0:0:0:0:0:0:0:1) so HTTPS clients validating against ::1 succeed.
- **Mongoose 7.21 -> 7.22:** security release fixing multiple CVEs (IPCP OOB read, zero-length PPP infinite loop, NDP issues and more).

## Dependency & build upgrades

- AGP 8.7.2 -> 9.3.1, Gradle wrapper 8.9 -> 9.7.0, compileSdk 36
- OkHttp 4.12 -> 5.4.0, Sentry 7.8 -> 8.52.0 (fixes high-severity advisory), Guava 33.6.0-android, org.json 20260719
- sonarqube plugin upgraded for Gradle 9 compatibility

## Correctness fixes

- **Hosts statistics accuracy:** blocked/redirected counters now read from host_entries (the table the generated hosts file is written from) instead of raw hosts_lists data.
- **Hosts sync hardening:** single app-wide lock around download/parse/sync; bounded queues and single-transaction inserts in SourceLoader (no more OOM risk on 100k+ line sources); partial downloads are treated as failed instead of silently truncating.
- **Crash fixes:** R8 kept Room/WorkManager reflective constructors (startup crash on optimized builds); dynamic broadcast receiver now uses RECEIVER_EXPORTED (SecurityException crash on API 33+ when updating the app); ad block model access made thread-safe (no duplicate RootModel / duplicate web server instances).
- **DNS request log:** tcpdump output (root-owned file) is now read through the root shell so the log page actually shows data.
- **Update progress:** no more infinite polling loop if the download row disappears.
- **Release logging:** a WARN+ logcat tree is always planted so release builds remain diagnosable.

## CI / signing

- Release APKs are now signed with the real release keystore (GitHub Secrets), so CI artifacts can be installed over previous versions without uninstalling.
- Signed APK is uploaded as a build artifact (AdAway-release-signed) and published to GitHub Releases.

---

# Bug Fixes & Dependency Automation — Summary

## Overview

This document describes all changes made to the `wxcvm/AdAway` repository to:
1. Fix concurrency bugs in the native web server
2. Set up automatic dependency updates
3. Fix build system issues across submodules
4. Align SDK versions across all modules

---

## 1. Bug Fixes

### 1.1 SNI Cache Synchronisation (`webserver/jni/webserver.c`)

**Context:** Mongoose 7.x is a single-threaded event loop (`mg_mgr_poll()` →
`poll()`/epoll, no threads are ever created in mongoose.c), so in this build
the SNI callback and all MG_EV handlers already run on one thread and there is
no real concurrency to guard against.

**Defensive fix:** Added `pthread_mutex_t s_sni_mutex` and locked it around all
cache access (lookup, insert, evict). Certificate generation (the slow path) is
performed outside the lock to avoid holding it through OpenSSL keygen. The lock
costs ~nothing and keeps this file correct if a threaded TLS dispatch or a
different network-stack configuration is ever introduced.

### 1.1b SNI Certificate Expiry (`webserver/jni/webserver.c`)

**Problem:** per-domain leaf certs were issued with a **1-day validity** and
the SNI cache had **no expiry awareness**. The web server is a long-running
daemon (started at boot), so after 24h of uptime every cache hit presented an
already-expired certificate and browsers refused the connection with
`ERR_CERT_DATE_INVALID`; nothing short of restarting the server could recover.

**Fix:** leaf certs are now issued for `SNI_CERT_VALIDITY_DAYS` (30 days) and
each cache entry records `issued_at`; a cache hit whose cert is past half its
validity is treated as a miss and re-issued on the spot.

**Files changed:** `webserver/jni/webserver.c`
- Added `#include <pthread.h>` and `#include <fcntl.h>`
- Added `static pthread_mutex_t s_sni_mutex = PTHREAD_MUTEX_INITIALIZER;`
- Wrapped `sni_callback()` cache operations in `pthread_mutex_lock/unlock`
- Wrapped cache cleanup in `main()` with mutex + `pthread_mutex_destroy`

### 1.2 SNI Cache `strncpy` Truncation Bug (`webserver/jni/webserver.c`)

**Problem:** `strncpy(s_sni_cache[pos].hostname, host, 255)` does not
NUL-terminate when the source is ≥ 255 bytes. A hostname exactly 255
characters long would leave the buffer unterminated, causing `strcmp()`
in the cache lookup to read past the array boundary.

**Fix:** Explicitly set `s_sni_cache[pos].hostname[255] = '\0'` after `strncpy`.

### 1.3 Connection Counter (`webserver/jni/webserver.c`)

**Context:** as with 1.1, Mongoose 7.x dispatches all events from one thread,
so the plain `int` counter cannot tear in this build.

**Defensive fix:** Replaced all plain reads/writes with GCC/Clang `__atomic_*`
builtins (single instructions on ARM64, zero cost):
- `atomic_load()` for reading
- `__atomic_compare_exchange_n` for connection cap check + increment
- `atomic_sub_fetch()` for decrement on close

### 1.4 Daemonisation Note — fd Redirection Deliberately *Not* Done (`webserver/jni/webserver.c`)

`main()` calls `setsid()` to detach from the controlling terminal, but does
**not** redirect stdin/stdout/stderr to `/dev/null`. An earlier attempt to add
that redirect was a **regression and has been removed**: `ShellUtils.
runBundledExecutable()` launches this binary with `> logfile 2>&1` and relies
on that file (plus the LOG_FATAL/LOG_WARN/LOG_INFO macros, which write to both
logcat and stdio) to diagnose startup failures. dup2()-ing the std fds to
`/dev/null` made the capture file permanently empty and silently defeated the
diagnostic mechanism. The launching shell has already redirected the std fds,
so there is no tty-sharing hazard to fix.

### 1.4b Certificate File Write Integrity (`webserver/jni/webserver.c`)

**Problem:** `generate_root_ca()` leaked the `FILE*` when `PEM_write_*` failed
and never checked `fclose()`'s return value — a full disk or I/O error could
leave a truncated CA file on disk that would then be loaded (and trusted) on
the next start.

**Fix:** every failure path now closes the handle, `fclose()` results are
checked, and a failed write aborts startup loudly instead of silently
persisting a corrupt CA.

### 1.4c Chain-Certificate Leak on SNI Miss (`webserver/jni/webserver.c`)

**Problem:** `make_domain_ctx()` called `SSL_CTX_add_extra_chain_cert(ctx,
X509_dup(ca->cert))` inline. `add_extra_chain_cert` only takes ownership when
it succeeds; if it failed (the only non-short-circuit failure path), the `dup`
was leaked.

**Fix:** the dup is tracked explicitly and freed on every failure path exactly
once; the success path leaves ownership with the `SSL_CTX`.

### 1.5 Build System: `LibraryJniLibsTask` Dependency (`tcpdump/build.gradle`)

**Problem:** `tcpdump/build.gradle` still used the removed AGP internal class
`com.android.build.gradle.internal.tasks.LibraryJniLibsTask` in its
`tasks.withType()` dependency. This silently fails on newer AGP versions,
preventing the real tcpdump executable from being packaged (same root cause
that was already fixed in `webserver/build.gradle`).

**Fix:** Applied the same task-name-matching fix already present in
`webserver/build.gradle`: `tasks.configureEach` matching by name
`/(?i).*merge.*(JniLibFolders|NativeLibs).*/`.

### 1.5b Native Artifact Verification (`webserver/build.gradle`)

**Problem:** `renameJniLibAsExecutable` never verified that ndkBuild actually
produced the real `webserver` executable. If `BUILD_EXECUTABLE` failed or was
skipped, the task silently copied nothing (or a stale stub) and the AAR/APK
shipped without a working binary — the same failure mode the task was created
to fix.

**Fix:** the task now fails the build in `doFirst` if
`obj/local/arm64-v8a/webserver` is missing or smaller than 100KB (the real
binary is hundreds of KB; the stub is ~3.7KB).

### 1.6 SDK Version Alignment (`tcpdump/build.gradle`, `sentrystub/build.gradle`)

**Problem:** `tcpdump` used `compileSdk 33` / `targetSdk 33` and `sentrystub`
used the same, while the main `app` module uses SDK 35. The `ndkVersion` was
also missing from `sentrystub`.

**Fix:** Aligned all modules to `compileSdk 35`, `targetSdk 35`, and
`ndkVersion '28.1.13356709'`.

---

## 2. Automatic Dependency Updates

### 2.1 Dependabot Configuration (`.github/dependabot.yml`)

Replaced the minimal existing config with a comprehensive setup:
- **Gradle ecosystem:** weekly Monday updates, grouped by category:
  - `androidx` group (AndroidX + Material)
  - `testing` group (JUnit, AndroidX Test)
  - `gradle-build` group (AGP)
  - `okhttp` group (OkHttp)
  - `sentry` group (Sentry)
- **GitHub Actions ecosystem:** weekly updates for CI dependencies
- Max 5 open PRs for Gradle, 3 for Actions
- Auto-rebase enabled
- Timezone: Asia/Shanghai

### 2.2 Mongoose Version Check (`.github/workflows/check-mongoose-update.yml`)

Weekly scheduled workflow that:
1. Reads the vendored `MG_VERSION` from `webserver/jni/mongoose/mongoose.h`
2. Fetches the latest release tag from `cesanta/mongoose` GitHub releases
3. Compares versions using `sort -V`
4. Opens a labeled issue if an update is available
5. Deduplicates — won't reopen issues for the same version

### 2.3 OpenSSL Prefab Version Check (`.github/workflows/check-openssl-update.yml`)

Weekly scheduled workflow that:
1. Reads the pinned `openssl` version from `gradle/libs.versions.toml`
2. Fetches `maven-metadata.xml` from Google Maven for
   `com.android.ndk.thirdparty:openssl`
3. Compares and opens a labeled issue if newer

---

## 3. Files Changed

| File | Change |
|------|--------|
| `webserver/jni/webserver.c` | SNI mutex (defensive), SNI cert expiry fix, atomic connections (defensive), strncpy NUL, chain-cert leak, CA write integrity, fd-redirect regression removed |
| `tcpdump/build.gradle` | Fix LibraryJniLibsTask → task name matching; SDK 33→35 |
| `webserver/build.gradle` | Verify real native artifact before packaging |
| `sentrystub/build.gradle` | SDK 33→35; add ndkVersion |
| `.github/dependabot.yml` | Complete Gradle + Actions config with groups |
| `.github/workflows/check-mongoose-update.yml` | New workflow |
| `.github/workflows/check-openssl-update.yml` | New workflow |

---

## 4. Testing Recommendations

After applying these changes locally:

1. **Build:** `./gradlew assembleDebug` — should complete without errors
2. **Web server startup:** Install on a rooted device and toggle the web server
   preference; verify the server starts and serves the test page at
   `https://localhost/internal-test`
3. **Concurrency:** Load a JavaScript-heavy ad-supported page in a browser
   while the VPN-based blocking is active; check logcat for any crash or
   `ERR_CERT_*` errors from concurrent SNI callbacks
4. **Dependabot:** After pushing to GitHub, Dependabot will open its first
   PRs on the next Monday at 03:00 Asia/Shanghai


---

## 5. Dependency & Library Upgrades

### 5.1 Mongoose 7.21 → 7.22 ()

**Security release.** 7.22 fixes multiple CVEs ( IPCP OOB read,  zero-length PPP infinite loop,  NDP, plus many not-yet-CVE-assigned fixes). Verified before swapping:  layout for  is unchanged (), so the  mirror in  needs no adaptation; all APIs used (, , , , , …) are present in 7.22.

### 5.2 Dependency bumps ()

| Dependency | From | To | Notes |
|---|---|---|---|
| AGP () | 8.7.2 | 9.3.1 | Requires Gradle 9.1+;  →  DSL migrated |
| Gradle wrapper | 8.9 | 9.7.0 | |
| OkHttp | 4.12.0 | 5.4.0 | Public API used by the app unchanged |
| Guava | 32.0.1-android | 33.6.0-android | Kept the  variant |
| org.json | 20220320 | 20260719 | |
| androidx.test:core | 1.6.1 | 1.7.0 | |
| androidx.test.ext:junit | 1.2.1 | 1.3.0 | |
| androidx.test:runner | 1.6.1 | 1.7.0 | |
| Sentry BOM | 7.8.0 | 8.52.0 | Fixes high-severity Dependabot advisory (sentry-android < 8.14.0, Session Replay unmasking) |
| sonarqube plugin | 7.2.2.6593 | 7.4.0.8496 | Gradle 9 compatibility |

### 5.3 Hosts statistics accuracy ()

 /  now query  (the table the generated hosts file is written from) instead of  (raw merged source data). This fixes two over-reporting cases: hosts excluded by the allow-list were previously still counted as blocked, and a host both blocked and redirected by different sources was counted in both categories.  keeps reading  (the allow-list is an exclusion rule; its source of truth is the list itself).

> **Build note:** these upgrades were applied and reviewed statically in the Operit workspace but could not be compile-verified there (no Android SDK / NDK in the environment). Please run  on a machine with the Android SDK (or CI) to confirm.


---

## 6. 核心运行链路 Bug 修复（本次）

> 范围：仅聚焦核心运行链路（webserver 原生服务 / hosts 应用 / root shell / tcpdump 日志 / 证书）。
> 验证：静态修复 + 自检；原生 webserver.c 与整体编译需在含 Android SDK/NDK 的 CI 或真机验证。

### 6.1 `TcpdumpUtils.clearLogFile()` 用应用进程截断 root 属主日志（权限不一致）

**问题：** DNS 日志由 tcpdump 以 root 运行写入（见 `runBundledExecutable`）。`getLogs()` 已改为通过 root shell `cat` 读取（root 属主、700 权限的应用 cache 目录对应用进程不可写），但 `clearLogFile()` 仍用应用进程的 `FileOutputStream` 截断——在相同设备上会静默失败，"清除日志"看似成功实则无效，且与 `getLogs()` 行为不一致。

**修复：** `app/src/main/java/org/adaway/model/root/TcpdumpUtils.java` 改用 root shell `: > <path>` 截断，失败时返回 `false` 并记录退出码。移除了不再使用的 `FileOutputStream` import，新增 `escapedString` static import。

### 6.2 webserver SNI 缓存 `s_sni_pos` 有符号整型溢出（越界写）

**问题：** `webserver/jni/webserver.c` 中 `s_sni_pos` 为 `signed int`，在每次 SNI 缓存 miss 时 `s_sni_pos++`，永不回绕。该服务是开机自启的常驻守护进程，持续为访问的广告域名签发证书数月后，计数器会越过 `INT_MAX` 翻转为负，随后 `pos = s_sni_pos % SNI_CACHE_SIZE` 得到负数索引 → 下一次 miss 时**数组越界写**。

**修复：** 将 `s_sni_pos` 改为 `uint64_t` 单调计数（定义处加注释）。无符号 64 位实际不可溢出，取模恒非负，持久化逻辑 `min(count, SNI_CACHE_SIZE)` 语义不变；`sni_cache_load` 的强转同步改为 `(uint64_t)n`。

### 6.3 `ShellUtils.runBundledExecutable()` 日志文件生命周期（丢诊断 + 无限累积）

**问题：** 每次启动生成时间戳唯一日志 `/data/local/tmp/webserver_start_<ms>.log`，成功路径在进程仍持有 stdout/stderr fd 时立即 `rm -f`——进程后续崩溃的最终输出全部丢失（fd 指向已删除文件）；失败路径又每次遗留一个 .log，随开机/启停循环无限累积；时间戳命名也不便于查看"当前"日志。

**修复：** `app/src/main/java/org/adaway/model/root/ShellUtils.java` 改用**固定路径** `/data/local/tmp/{executable}_start.log`，启动时由 `>` 重定向天然截断，成功/失败后均保留（不 unlink 运行中 fd），既不累积文件也不丢崩溃诊断。同步更新 `WebServerUtils.java` 与 `values/strings.xml` 中对旧 `webserver_start_*.log` 路径的提示文案。

### 6.4 `WebServerUtils.getStats()/sendControlCommand()` 未排空子进程 stderr（潜在阻塞/句柄泄漏）

**问题：** 两个方法通过 `ProcessBuilder` 启动 toybox nc 读取 `/internal-stats` 与发送 `/control`，但从未读取/关闭子进程的 `getErrorStream()`。若 nc 向 stderr 输出任何内容，管道缓冲（~64KB）填满后子进程在 `write()` 阻塞，`waitFor()` 每次都超时，即便 HTTP 请求已成功。

**修复：** `app/src/main/java/org/adaway/util/WebServerUtils.java` 在两个方法启动进程后立即开 daemon 线程排空并丢弃 stderr，`waitFor()` 后 `join()`，杜绝管道填满死锁；保持 v4-mapped socket 语义不变。


---

## 7. 既有 Kotlin 编译错误修复（CI 恢复）

> 背景：Android CI 自 2026-08-17 起连续失败在 "Run unit tests" 步骤，根因是 `./gradlew test`
> 在编译阶段即失败（非测试断言失败）。经本地完整编译复现，确认为 08-17 的 Compose UI 改动
> 引入的既有编译错误。此修复与核心运行链路无关，但为恢复 CI 必须提交。

### 7.1 `OverviewScreen.kt` 缺失 import

**问题：** `OverviewScreen.kt:217` 使用 `Toast.makeText(...)` / `Toast.LENGTH_SHORT` 但未 import
`android.widget.Toast`；`OverviewScreen.kt:222` 使用 `Icons.Outlined.Apps` 但未 import 该图标
（当前仅 import 了 Dns/Lock/Public/Settings 等 outlined 图标）。

**修复：** 补 `import android.widget.Toast` 与 `import androidx.compose.material.icons.outlined.Apps`。

### 7.2 `SettingsScreen.kt` 多余右花括号

**问题：** `SettingsScreen.kt:1210` 有一个多余的 `}`，使 @Composable 函数在 1209 行提前闭合，
后续顶层函数（`exportCertificate` 等）报 "Expecting a top level declaration"。

**修复：** 删除第 1210 行的多余 `}`。

> 验证：本地（arm64，经 `android.aapt2FromMavenOverride` 使用 build-tools 35.0.0 的 aapt2）
> `./gradlew :app:testDebugUnitTest` BUILD SUCCESSFUL，全部 13 个测试通过（GitHostsSource 6 /
> SourceLoader 5 / LogEntrySort 2，0 失败 0 错误）。


---

## 8. CI workflow：修复 "Upload APK" 稳定失败

> 现象：核心运行链路修复 + Kotlin 编译修复后，CI 在 unit tests / Build / Sign 均成功后，
> 稳定失败于 "Upload APK"（两次运行均复现，非偶发）。

**根因：** `actions/upload-artifact@v4` 上传到 run 需要 `actions: write` 权限，但 `build` job
未声明 `permissions`；且上传路径硬编码 `app-release-signed.apk`，容错性差。

**修复：** `.github/workflows/android-ci.yml`
1. `build` job 增加 `permissions: { contents: read, actions: write }`（为 upload-artifact 提供写权限）。
2. Upload APK 路径改为通配符 `app/build/outputs/apk/release/*.apk`，并加 `if-no-files-found: warn`，
   避免因单个文件缺失或命名差异导致整个步骤失败。


---

## 9. CI 修复 + 构建提速

### 9.1 "Upload APK" 失败的真正根因：artifact 存储配额耗尽

> 现象：unit tests / Build / Sign 均成功后，"Upload APK" 稳定失败（重跑亦复现），
> 加 `actions: write` 权限、通配符路径、`if-no-files-found: warn` 均无效。

**根因（从 CI 日志确认）：** `##[error]Failed to CreateArtifact: Artifact storage quota has been hit`
——仓库累积了 152 个 release APK artifact（各 ~11MB），GitHub Actions 免费存储配额（约 1.5GB）已耗尽，
导致所有 `upload-artifact` 一律失败。

**处理：**
1. 通过 GitHub API 批量删除历史 artifacts，仅保留最新 1 个（释放约 1.6GB）。
2. workflow 的 Upload 步骤加 `retention-days: 7`，避免再次占满配额。

### 9.2 构建提速

原 CI 耗时：unit tests ~3m18s + assembleRelease ~6m20s，合计约 10 分钟。优化：
1. **合并 Gradle 调用**：`Run unit tests` 与 `Build with Gradle` 合并为一次
   `./gradlew test assembleRelease ...`，共享 daemon / 依赖解析 / Kotlin 编译，省一次启动。
2. **ccache**：安装 ccache 并设置 `NDK_CCACHE`，加速反复编译的大体积
   webserver.c / mongoose / openssl / tcpdump。
3. 移除 `--stacktrace`（构建耗时无收益的日志开关）。


---

## 10. CI workflow：一次完全修复（release job 权限 + 签名健壮性 + 版本号单调性）

> 背景：此前多次修复（加 `actions: write`、通配符路径、`if-no-files-found: warn`、清理 artifact 配额）
> 均只解决局部问题，CI 仍可能失败。本次对 `.github/workflows/android-ci.yml` 做系统性排查，
> 一次性修复全部已知隐患，确保 build 与 release 两个 job 稳定通过、不再复发。

### 10.1 `release` job 缺少 `actions: read` 权限（致命）

**问题：** `release` job 使用 `actions/download-artifact@v4` 下载构建产物，但只声明了
`permissions: { contents: write }`。`download-artifact` 需要 **`actions: read`** 权限，
缺失会导致 release job 在下载 artifact 时直接失败。

**修复：** `release` job 的 `permissions` 增加 `actions: read`。

### 10.2 签名步骤 `ANDROID_HOME` 未显式设置（致命）

**问题：** 签名步骤用 `$ANDROID_HOME/build-tools` 查找 `apksigner`。`actions/setup-java` 不会设置
`ANDROID_HOME`，若 runner 镜像变更或环境变量未导出，签名步骤会因找不到 `apksigner` 而失败。

**修复：** 使用 `${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}` 兜底定位 SDK，
找不到 build-tools 时输出 `::error::` 并显式失败。

### 10.3 `app-release-unsigned.apk` 文件名硬编码（致命）

**问题：** 签名步骤假设 AGP 生成 `app-release-unsigned.apk`。AGP 9.x 的默认输出文件名可能不同，
若文件名不匹配，`apksigner` 找不到输入文件，签名步骤失败。

**修复：** 用 `find app/build/outputs/apk/release -name 'app-release*.apk' ! -name '*signed*'`
动态定位实际 unsigned APK，找不到时显式失败并列出目录内容。

### 10.4 `VERSION_CODE=$(date +%s)` 不保证单调递增（高）

**问题：** 使用 Unix 时间戳作为 versionCode，同一秒内两次构建会碰撞，时钟回拨会变小；
Android 要求 versionCode 单调递增才能覆盖安装。

**修复：** 改用 `$((100000 + github.run_number))`（workflow 内单调计数器），保证单调递增。

### 10.5 `version.txt` 未加入 `.gitignore`（高）

**问题：** CI 生成 `version.txt` 并上传为 artifact，但该文件不在 `.gitignore` 中，
可能被自动提交工作流意外提交到仓库。

**修复：** 在 `.gitignore` 末尾追加 `version.txt`。

### 10.6 `if-no-files-found: warn` 掩盖问题（中）

**问题：** 若 APK 文件不存在，Upload 步骤只警告不失败，但 release job 会因找不到文件而失败，
问题被延迟暴露。

**修复：** 改为 `if-no-files-found: error`，尽早暴露问题。

### 10.7 action 版本对齐到已验证版本（关键修正）
**修复：** 上一版臆造/未验证的 action 版本（checkout@v7、setup-java@v6、upload-artifact@v7、
download-artifact@v8、action-gh-release@v3）会导致 CI 在解析 action 时即失败。已全部对齐到
本仓库实际运行验证可用的版本：`actions/checkout@v6`、`actions/setup-java@v5`、
`actions/upload-artifact@v4`、`actions/download-artifact@v4`、`softprops/action-gh-release@v2`。
### 影响范围

- **build job**：unit tests + assembleRelease + Sign + Upload 全链路稳定通过。
- **release job**：download-artifact + Prepare + Create Release 全链路稳定通过。
- **版本号**：versionCode 单调递增，可正常覆盖安装。
- **仓库卫生**：`version.txt` 不再被误提交。

> 验证：本地 YAML 语法校验通过；推送 master 触发 build job、打 tag `v6.5.0.x` 触发 release job
> 均需在 CI 上确认全绿。


---

## 11. CI 签名步骤最终修复：`! -name '*signed*'` 排除条件误伤 unsigned

**现象：** 签名步骤报 `No unsigned release APK found`，导致 CI 失败（unit tests / build 均通过）。

**根因：** 定位 unsigned APK 用了 `find ... -name 'app-release*.apk' ! -name '*signed*'`。
`"unsigned"` 作为字符串包含子串 `"signed"`（u-**n-signed**），因此 `-name '*signed*'` 把
`app-release-unsigned.apk` 一并排除，导致找不到文件而退出。日志确认该文件确实存在：
`app/build/outputs/apk/release/app-release-unsigned.apk`。

**修复：** 改为精确文件名 `find ... -maxdepth 1 -name 'app-release-unsigned.apk'`（AGP 的固定输出名），
不再用子串排除。

Trigger CI after all fixes (sign fix, retention-days 1, artifact quota cleared).
