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
