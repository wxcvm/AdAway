/*
 * _GNU_SOURCE is required for bionic to expose backtrace() and
 * backtrace_symbols_fd() (used by the native crash handler below). It has to
 * be defined before the first system header is included.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <stdint.h>       /* intptr_t (conn_uid) */
#include <stdarg.h>       /* va_list - webserver.log mirror */
#include <time.h>         /* timestamps in webserver.log */
#if !defined(_WIN32)
#include <fcntl.h>        /* open() in the async-signal-safe crash handler */
#endif
#include <pthread.h>


/*
 * Portability shims.
 *
 * Android (NDK build) provides android/log.h + linux/limits.h and a logcat
 * backend. The Windows build provides win32_dirent.h (opendir/readdir/
 * closedir/rewinddir via FindFirstFile) and PATH_MAX, and degrades logcat
 * to a no-op (the LOG_* macros still write to stderr/stdout, which is what
 * a console/daemon needs). Per-app stats intentionally degrade to
 * "unknown" (uid -1) on Windows: the /proc/net/tcp and /proc/<pid> lookups
 * simply fail and are handled as "not found".
 */
#ifdef __ANDROID__
#include <sys/socket.h>
#include <android/log.h>
#include <linux/limits.h>
#define LOG_LOGCAT(prio, fmt, ...) __android_log_print(prio, THIS_FILE, fmt, ##__VA_ARGS__)
#elif defined(_WIN32)
#include <winsock2.h>   /* before windows.h; base for mongoose sockets */
#include <direct.h>     /* _mkdir */
#include <limits.h>
#include <stdbool.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#include "win32_dirent.h"   /* opendir/readdir/closedir/rewinddir shim */
#include "gui_win32.h"      /* native dashboard + autostart (Windows) */
/* mingw-w64 has no POSIX uid_t (it only defines pid_t). */
typedef unsigned int uid_t;
#define ANDROID_LOG_FATAL 0
#define ANDROID_LOG_WARN  1
#define ANDROID_LOG_INFO  2
#define ANDROID_LOG_DEBUG 3
#define LOG_LOGCAT(prio, fmt, ...) do { } while (0)
/* mingw-w64 has no POSIX strcasecmp/strncasecmp. */
#define strcasecmp _stricmp
#define strncasecmp _strnicmp
#else
#include <sys/socket.h>
#include <limits.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#include <dirent.h>
#define ANDROID_LOG_FATAL 0
#define ANDROID_LOG_WARN  1
#define ANDROID_LOG_INFO  2
#define ANDROID_LOG_DEBUG 3
#define LOG_LOGCAT(prio, fmt, ...) do { } while (0)
#endif

/*
 * Autostart / registry paths must be UTF-8. GetModuleFileNameA() returns the
 * ANSI code page path (GBK on a Chinese system) which the rest of the code
 * decodes as UTF-8, so the Run value pointed at a non-existent U+FFFD path
 * (phase-2 audit, P1-3). Returns the length, like GetModuleFileNameA().
 */
#ifdef _WIN32
static int get_module_path_utf8(char *out, size_t cap) {
    wchar_t wide[MAX_PATH];
    if (out == NULL || cap == 0) return 0;
    out[0] = 0;
    if (GetModuleFileNameW(NULL, wide, MAX_PATH) == 0) return 0;
    if (WideCharToMultiByte(CP_UTF8, 0, wide, -1, out, (int) cap, NULL, NULL) <= 0) {
        out[0] = 0;
        return 0;
    }
    return (int) strlen(out);
}
#else
/* Never called outside the Windows-only autostart code. */
static int get_module_path_utf8(char *out, size_t cap) { (void) out; (void) cap; return 0; }
#endif
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/ec.h>
#include <openssl/pem.h>

/*
 * Compatibility shim: Google's "com.android.ndk.thirdparty:openssl" AAR
 * (OpenSSL 1.1.1q build) does not export the deprecated
 * SSL_CTX_callback_ctrl() symbol that mongoose.c (MG_TLS_OPENSSL backend)
 * references -- on-device this caused:
 *   CANNOT LINK EXECUTABLE ... "cannot locate symbol SSL_CTX_callback_ctrl"
 * when the binary was launched directly from the app's native lib dir.
 * This is exactly what OpenSSL itself implements it as: a thin wrapper
 * over SSL_CTX_ctrl(). Providing our own definition keeps the same ABI
 * and removes the missing-symbol dependency on the system/NDK libssl.
 * The return type is long to match SSL_CTX_ctrl(); mongoose only checks
 * for zero (failure), so the semantics are preserved.
 */
#if defined(MG_TLS_OPENSSL)
long SSL_CTX_callback_ctrl(SSL_CTX *ctx, int cmd, void (*fp) (void)) {
    return SSL_CTX_ctrl(ctx, cmd, 0, (void *) fp);
}
#endif
#include <openssl/bio.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include "mongoose/mongoose.h"

#define THIS_FILE "WebServer"
/*
 * Listen addresses are configurable at runtime via CLI flags:
 *   --bind all     → 0.0.0.0 / [::]  (all interfaces)
 *   --bind loop    → 127.0.0.1 / [::1]  (loopback only, default)
 *   --http-port N  → HTTP listen port (default 80)
 *   --https-port N → HTTPS listen port (default 443)
 * Defaults keep the historical loopback-only behaviour; the app passes
 * --bind all when the user enables LAN access in settings.
 */
#define HTTP_URL_DEFAULT  "http://127.0.0.1:80"
#define HTTPS_URL_DEFAULT "https://127.0.0.1:443"
#define HTTP_URL_IPV6_DEFAULT  "http://[::1]:80"
#define HTTPS_URL_IPV6_DEFAULT "https://[::1]:443"

/* BUG FIX: __android_log_print() only reaches logcat, never the process's
   own stdout/stderr. The Java side (ShellUtils.runBundledExecutable)
   launches this binary with `> logfile 2>&1`, expecting to capture
   startup failures (bad args, CA generation failure, port bind failure,
   etc.) in that file — but since nothing was ever written to
   stdout/stderr, that file was always empty regardless of what actually
   went wrong. Log to both destinations for anything that matters for
   diagnosing a failed/successful startup. */
/* ── webserver.log mirror ────────────────────────────────────────
 * The Windows build is a GUI-subsystem app (-mwindows): there is no console,
 * so stdout/stderr go nowhere and a failed bind / autostart problem left no
 * trace at all except native_crash.log. Mirror every LOG_* line into
 * <resource_dir>/webserver.log (rotated at 1 MB, one previous file kept) so a
 * user can simply send the file when something goes wrong. */
#define LOG_FILE_MAX_BYTES (1024 * 1024)
static FILE *s_log_fp = NULL;
/* One log writer at a time (audit I-1): the GUI thread appends heartbeat
   lines through win32_log_line() while the server thread logs. */
static pthread_mutex_t s_log_mutex = PTHREAD_MUTEX_INITIALIZER;
static long  s_log_bytes = 0;
static char  s_log_path[PATH_MAX];

static void log_file_line(const char *level, const char *fmt, ...);

static void log_file_open(const char *dir) {
    if (!dir || !dir[0] || s_log_fp) return;
    snprintf(s_log_path, sizeof(s_log_path), "%s/webserver.log", dir);
    s_log_fp = fopen(s_log_path, "a");
    if (s_log_fp) {
        if (fseek(s_log_fp, 0, SEEK_END) == 0) s_log_bytes = ftell(s_log_fp);
    }
}

/* Public wrapper used by the Windows dashboard to append heartbeat lines to
 * webserver.log (the GUI carries the resource numbers). */
void win32_log_line(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    log_file_line("INFO", "%s", buf);
}

static void log_file_rotate(void) {
    char prev[PATH_MAX + 4];
    if (s_log_fp) { fclose(s_log_fp); s_log_fp = NULL; }
    snprintf(prev, sizeof(prev), "%s.1", s_log_path);
    remove(prev);
    rename(s_log_path, prev);
    s_log_fp = fopen(s_log_path, "a");
    s_log_bytes = 0;
}

static void log_file_line(const char *level, const char *fmt, ...);

#ifdef _WIN32
/* ── CA private key hardening (audit D2) ──────────────────────────────
 * localhost-2410.key is the trust anchor for every intercepted connection.
 * With the inherited ACL any other account on the machine could read it and
 * impersonate every site, so the ACL is reduced to the three principals that
 * legitimately need it. icacls is used (instead of SetNamedSecurityInfo) so
 * the effective rights stay easy to inspect and repair by hand. Failure is
 * never fatal: the server keeps running, it only logs the exit code. */
static void harden_ca_key_acl(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s\\localhost-2410.key", resource_dir);
    if (GetFileAttributesA(path) == INVALID_FILE_ATTRIBUTES) return;
    wchar_t wpath[PATH_MAX], cmd[PATH_MAX + 256];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, PATH_MAX) <= 0) return;
    _snwprintf(cmd, PATH_MAX + 256,
               L"cmd.exe /c icacls \"%ls\" /inheritance:r "
               L"/grant:r \"%%USERNAME%%\":F /grant:r *S-1-5-18:F /grant:r *S-1-5-32-544:F",
               wpath);
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    memset(&pi, 0, sizeof(pi));
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        /* log_file_line() is declared above this point; the LOG_* macros are
           not (they live further down), which is why this uses it directly. */
        log_file_line("WARN", "CA key ACL: could not run icacls");
        return;
    }
    WaitForSingleObject(pi.hProcess, 10000);
    DWORD rc = (DWORD) -1;
    GetExitCodeProcess(pi.hProcess, &rc);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    if (rc == 0) log_file_line("INFO", "CA key ACL hardened (%s)", path);
    else log_file_line("WARN", "CA key ACL: icacls rc=%lu (key keeps the inherited ACL)", (unsigned long) rc);
}

/* ── crash forensics ────────────────────────────────────────────────
 * The Windows build is a GUI-subsystem process: when it dies there is no
 * console and no dialog, so a crash left no trace at all. Record what
 * happened (exception code, faulting address, module, and the tail of
 * webserver.log for context) in <resources>/crash.log. */
static char s_crash_dir[PATH_MAX];

static LONG WINAPI crash_filter(EXCEPTION_POINTERS *ep) {
    const char *dir = s_crash_dir[0] ? s_crash_dir : ".";
    char path[PATH_MAX + 32];
    void *addr = (void *)ep->ExceptionRecord->ExceptionAddress;
    char modname[MAX_PATH] = "?";
    HMODULE mod = NULL;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCSTR)addr, &mod))
        GetModuleFileNameA(mod, modname, sizeof(modname));
    snprintf(path, sizeof(path), "%s/crash.log", dir);
    FILE *f = fopen(path, "a");
    if (f) {
        SYSTEMTIME st;
        GetLocalTime(&st);
        fprintf(f, "[%04d-%02d-%02d %02d:%02d:%02d] EXCEPTION code=0x%08lX addr=%p module=%s pid=%lu\n",
                st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond,
                (unsigned long)ep->ExceptionRecord->ExceptionCode, addr, modname,
                (unsigned long)GetCurrentProcessId());
        fflush(f);
        char lp[PATH_MAX + 32];
        snprintf(lp, sizeof(lp), "%s/webserver.log", dir);
        FILE *l = fopen(lp, "r");
        if (l) {
            static char ring[40][256];
            int n = 0;
            while (fgets(ring[n % 40], sizeof(ring[0]), l) != NULL) n++;
            fclose(l);
            int start = n > 40 ? n - 40 : 0;
            fprintf(f, "---- webserver.log tail (%d lines) ----\n", n - start);
            for (int i = start; i < n; i++) fputs(ring[i % 40], f);
        }
        fclose(f);
        log_file_line("FATAL", "Unhandled exception 0x%08lX at %p (%s) - details in crash.log",
                      (unsigned long)ep->ExceptionRecord->ExceptionCode, addr, modname);
    }
    return EXCEPTION_EXECUTE_HANDLER;
}
#endif

static void log_file_line(const char *level, const char *fmt, ...) {
    /* Audit I-1: serialise writers, and never let a rotation close the FILE*
       while another thread is inside vfprintf(). */
    pthread_mutex_lock(&s_log_mutex);
    if (!s_log_fp) { pthread_mutex_unlock(&s_log_mutex); return; }
    if (s_log_bytes > LOG_FILE_MAX_BYTES) {
        log_file_rotate();
        if (!s_log_fp) { pthread_mutex_unlock(&s_log_mutex); return; }
    }
    int n = 0;
#ifdef _WIN32
    {
        SYSTEMTIME st;
        GetLocalTime(&st);
        n = fprintf(s_log_fp, "[%04d-%02d-%02d %02d:%02d:%02d] [%s] ",
                    st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, level);
    }
#else
    {
        time_t now = time(NULL);
        struct tm tmv;
        struct tm *lt = localtime(&now);
        if (lt) tmv = *lt; else memset(&tmv, 0, sizeof(tmv));
        n = fprintf(s_log_fp, "[%04d-%02d-%02d %02d:%02d:%02d] [%s] ",
                    tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                    tmv.tm_hour, tmv.tm_min, tmv.tm_sec, level);
    }
#endif
    if (n > 0) s_log_bytes += n;
    {
        va_list ap;
        va_start(ap, fmt);
        n = vfprintf(s_log_fp, fmt, ap);
        va_end(ap);
        if (n > 0) s_log_bytes += n;
    }
    if (fputc('\n', s_log_fp) != EOF) s_log_bytes += 1;
    fflush(s_log_fp);
    pthread_mutex_unlock(&s_log_mutex);
}

#define LOG_FATAL(fmt, ...) do { \
    LOG_LOGCAT(ANDROID_LOG_FATAL, fmt, ##__VA_ARGS__); \
    fprintf(stderr, "[FATAL] " fmt "\n", ##__VA_ARGS__); fflush(stderr); \
    log_file_line("FATAL", fmt, ##__VA_ARGS__); \
} while (0)
#define LOG_WARN(fmt, ...) do { \
    LOG_LOGCAT(ANDROID_LOG_WARN, fmt, ##__VA_ARGS__); \
    fprintf(stderr, "[WARN] " fmt "\n", ##__VA_ARGS__); fflush(stderr); \
    log_file_line("WARN", fmt, ##__VA_ARGS__); \
} while (0)
#define LOG_INFO(fmt, ...) do { \
    LOG_LOGCAT(ANDROID_LOG_INFO, fmt, ##__VA_ARGS__); \
    fprintf(stdout, "[INFO] " fmt "\n", ##__VA_ARGS__); fflush(stdout); \
    log_file_line("INFO", fmt, ##__VA_ARGS__); \
} while (0)

#define OOM_ADJ_PATH   "/proc/self/oom_score_adj"
#define OOM_ADJ_NOKILL -1000   /* OOM_SCORE_ADJ_MIN */

#define MAX_CONNECTIONS   256
#define IDLE_TIMEOUT_MS   10000
/* CORS: ad SDKs usually issue cross-origin XHR/fetch/beacon requests
   from the page origin to their ad endpoints. Those requests are
   redirected here by the hosts-file block, so this server IS the
   "remote" origin from the browser's point of view. Without
   Access-Control-Allow-Origin the browser blocks the response and the
   SDK sees a failed/errored request (and may retry or log errors).
   Serving these headers makes the SDK believe its call succeeded. */
#define CORS_HDR "Access-Control-Allow-Origin: *\r\n"
/*
 * BUG FIX (usability): the sequential img_00.webp, img_01.webp, ...
 * scheme (see git history for count_block_images(), the previous
 * approach) required contiguous, zero-padded numbering - deleting one
 * image meant renaming every image after it to close the gap, since
 * scanning stopped at the first missing index. Scan the actual
 * directory contents instead and remember whichever filenames are
 * really there: delete any image, rename none of the others, add a
 * new one under any name matching the pattern - all just work. Also no
 * longer bound to the "two-digit index" naming scheme this session's
 * earlier fixes needed, since actual filenames (not reconstructed
 * ones) are what get served.
 */
#define BLOCK_IMAGE_MAX_COUNT 100
#define BLOCK_IMAGE_NAME_MAX  128

/* Scan resource_dir for files matching img_*.webp (case-sensitive,
   any suffix - "img_00.webp", "img_cat.webp", "img_2024-ad.webp" all
   match), storing up to BLOCK_IMAGE_MAX_COUNT filenames into out[].
   Returns the count found; always >= 1 (falls back to "img_00.webp"
   even if nothing was found, so the modulo below never divides by
   zero - the subsequent mg_http_serve_file() call will just 404 on a
   missing file in that degenerate case, rather than crash). */
static int scan_block_images(const char *resource_dir,
                             char out[][BLOCK_IMAGE_NAME_MAX]) {
    int count = 0;
    DIR *dir = opendir(resource_dir);
    if (!dir) {
        LOG_INFO("Could not open resource dir %s to scan for block images", resource_dir);
        snprintf(out[0], BLOCK_IMAGE_NAME_MAX, "img_00.webp");
        return 1;
    }
    struct dirent *entry;
    while (count < BLOCK_IMAGE_MAX_COUNT && (entry = readdir(dir)) != NULL) {
        const char *name = entry->d_name;
        size_t len = strlen(name);
        /* Match "img_*.webp" - starts with "img_", ends with ".webp",
           and has at least one character in between. */
        if (len > 9 /* strlen("img_x.webp") */
                && strncmp(name, "img_", 4) == 0
                && strcmp(name + len - 5, ".webp") == 0) {
            snprintf(out[count], BLOCK_IMAGE_NAME_MAX, "%s", name);
            count++;
        }
    }
    closedir(dir);
    if (count == 0) {
        LOG_INFO("No block placeholder images found in %s", resource_dir);
        snprintf(out[0], BLOCK_IMAGE_NAME_MAX, "img_00.webp");
        return 1;
    }
    LOG_INFO("Found %d block placeholder image(s) in %s", count, resource_dir);
    return count;
}

/* SNI per-domain certificate cache.
   OPTIMIZATION: 48 was tight for real browsing sessions — a single page
   with a dozen distinct ad/tracker domains, multiplied across a few
   tabs/apps, evicts entries before they get reused, forcing needless
   re-generation (even with the EC speedup above, still not free).
   256 entries at ~272 bytes each (hostname[256] + pointer + timestamp)
   is ~68KB — negligible for a process that otherwise stays resident.

   NOTE (threading): Mongoose 7.x is a single-threaded event loop —
   mg_mgr_poll() drives everything via poll()/epoll on one thread, so
   the SNI callback and all MG_EV handlers below run on that same
   thread and there is no real concurrency to guard against in this
   build (no pthread_create anywhere in mongoose.c). The mutex/atomics
   are nevertheless kept as defensive code: they cost ~nothing and
   keep this file correct if a threaded TLS dispatch or a different
   network-stack configuration is ever introduced.

   BUG FIX (cert expiry): per-domain leaf certs used to be issued with
   a 1-day validity and the cache had no expiry awareness at all. The
   web server is a long-running daemon (started at boot via
   BootReceiver), so after 24h of uptime every cache hit would present
   an already-expired certificate and browsers would refuse the
   connection with ERR_CERT_DATE_INVALID, with no way to recover short
   of restarting the server. Certs are now issued for
   SNI_CERT_VALIDITY_DAYS and each cache entry records when it was
   issued; a hit whose cert is past half its validity is treated as a
   miss and re-issued (see sni_callback()). */
#define SNI_CACHE_SIZE 1024  /* bigger cache = more hits, fewer re-issues */
#define SNI_CERT_VALIDITY_DAYS 60  /* longer validity = fewer re-issues */
#define SNI_CERT_RENEW_MS ((uint64_t) SNI_CERT_VALIDITY_DAYS * 86400000ULL / 2)
struct sni_entry { char hostname[256]; SSL_CTX *ctx; uint64_t issued_at; };
static struct sni_entry s_sni_cache[SNI_CACHE_SIZE];
/* BUG FIX (integer overflow): s_sni_pos used to be a plain signed int
   incremented without bound (s_sni_pos++ on every SNI cache miss, even
   after the ring wrapped). A boot-time daemon that keeps issuing
   per-domain certs for months would eventually push this past INT_MAX,
   at which point it wraps negative and `pos = s_sni_pos % SNI_CACHE_SIZE`
   indexes the array with a negative offset — an out-of-bounds WRITE on
   the next cache miss. Make it an unsigned 64-bit monotonic counter: it
   can never overflow in practice, the modulo stays non-negative, and the
   persistence logic (min(count, SNI_CACHE_SIZE)) is unchanged. */
static uint64_t         s_sni_pos = 0;
static uint64_t         s_sni_hits = 0;
static uint64_t         s_sni_misses = 0;
static pthread_mutex_t  s_sni_mutex = PTHREAD_MUTEX_INITIALIZER;

/* SNI cache persistence: dump hostnames + issued_at to sni_cache.dat so
   a restart doesn't flush the whole cache (re-issue = slow EC keygen +
   new TLS handshakes). On load, entries are re-keyed lazily: the SSL_CTX
   is rebuilt only on the next hit that finds the entry expired, but the
   hostname is remembered so a "miss" is not counted as a fresh issue. */
#define SNI_CACHE_MAGIC 0x534E4943u  /* "SNIC" */
struct sni_cache_file {
    uint32_t magic;
    uint32_t count;
    struct { char hostname[256]; uint64_t issued_at; } entries[SNI_CACHE_SIZE];
};
static void sni_cache_save(const char *resource_dir);
static void sni_cache_load(const char *resource_dir);

/* CA state shared with the SNI callback */
struct ca_state { X509 *cert; EVP_PKEY *key; };

/* Mirror of Mongoose 7.21 mg_tls struct for MG_TLS_OPENSSL.
   Matches mongoose.h: struct mg_tls { BIO_METHOD *bm; SSL_CTX *ctx; SSL *ssl; } */
struct mg_tls_openssl { BIO_METHOD *bm; SSL_CTX *ctx; SSL *ssl; };

static SSL_CTX *mg_conn_ssl_ctx(struct mg_connection *c) {
    if (!c->tls) return NULL;
    return ((struct mg_tls_openssl *)c->tls)->ctx;
}

/* ── Settings ─────────────────────────────────────────────────── */
struct settings {
    bool              init;
    struct mg_tls_opts tls_opts;
    char              resource_dir[PATH_MAX];
    char              test_path[PATH_MAX];
    struct ca_state   ca;
    bool              debug;
    bool              bind_all;   /* listen on all interfaces */
    int               http_port;  /* HTTP listen port (default 80) */
    int               https_port; /* HTTPS listen port (default 443) */
    bool              proxy_filter; /* transparent hijack proxy (--proxy-filter) */
    int               stats_port;   /* loopback management port (default 8686) */
    bool              no_gui;     /* Windows: skip the dashboard window */
    bool              minimized;  /* Windows: GUI started to tray (autostart) */
    int               autostart;  /* Windows: 1=install, 2=uninstall Run key */
    bool              cli_bind_set;      /* --bind given on the command line */
    bool              cli_stats_port_set;/* --stats-port given */
    bool              cli_http_port_set; /* --http-port given */
    bool              cli_https_port_set;/* --https-port given */
    int               block_image_count;
    char              block_images[BLOCK_IMAGE_MAX_COUNT][BLOCK_IMAGE_NAME_MAX];
};

/* ── Statistics ───────────────────────────────────────────────── */
/*
 * Per-process counters, exposed via the /internal-stats endpoint.
 * Mongoose 7.x drives every connection from a single mg_mgr_poll()
 * event loop, so all increments below happen on one thread and plain
 * (non-atomic) counters are safe; this mirrors the existing comments on
 * s_active_connections / s_sni_cache about the defensive atomics.
 */
struct webstats {
    uint64_t start_time_ms;      /* mg_millis() at startup          */
    uint64_t total_requests;     /* every MG_EV_HTTP_MSG seen       */
    uint64_t total_connections;  /* MG_EV_ACCEPT count              */
    uint64_t tls_handshakes;     /* TLS handshakes completed        */
    uint64_t tls_failures;       /* TLS handshake failures          */
    uint64_t blocked_images;     /* placeholder image served        */
    uint64_t blocked_scripts;    /* empty JS                        */
    uint64_t blocked_styles;     /* empty CSS                       */
    uint64_t blocked_fonts;      /* 204 fonts                       */
    uint64_t blocked_media;      /* 204 media/streams               */
    uint64_t blocked_api;        /* {} JSON replies                 */
    uint64_t blocked_telemetry;  /* 204 analytics                   */
    uint64_t blocked_heartbeat;  /* 204 probes                      */
    uint64_t blocked_config;     /* {} config                       */
    uint64_t blocked_ws_sse;     /* 204 websocket/SSE               */
    uint64_t blocked_other;      /* fell through to image fallback  */
    uint64_t blocked_crypto;     /* mining pool requests (204)      */
    uint64_t blocked_clickbait;  /* tracker/click/pixel (204)       */
    uint64_t sni_certs_issued;   /* SNI per-domain certs generated  */
    uint64_t sni_cache_hits;     /* SNI cache hits (avoid re-issue) */
};
static struct webstats s_stats = {0};
/* Verbose (per-connection/request) logging is opt-in: with the hijack mode the
   server sees the whole device traffic, and logging every connection to a file
   costs real CPU and flash I/O. Enabled by --debug only. */
static bool s_verbose;
/* Block-reply policy gates - loaded from block_config.json, hot-reloadable. */
static bool cfg_images = true;
static bool cfg_scripts = true;
static bool cfg_styles = true;
static bool cfg_fonts = true;
static bool cfg_media = true;
static bool cfg_struct = true;
static bool cfg_api = true;
static bool cfg_tele = true;
static bool cfg_conf = true;
static bool cfg_ws = true;

/* ── Per-type blocking method (dashboard selectable) ────────────────
 *  BM_REPLY 0 = 占位/静默响应（按类型回空图片、空脚本、204 等，默认）
 *  BM_DENY  1 = 204 快速拒绝（带 CORS，页面立即拿到“没有内容”）
 *  BM_ALLOW 2 = 本类型不拦截（只统计，不返回占位资源）
 * The legacy reply_* booleans keep working and act as the default. */
#define BM_REPLY 0
#define BM_DENY  1
#define BM_ALLOW 2
static int  mode_images = BM_REPLY, mode_scripts = BM_REPLY, mode_styles = BM_REPLY;
static int  mode_fonts  = BM_REPLY, mode_media   = BM_REPLY, mode_struct = BM_REPLY;
static int  mode_api    = BM_REPLY, mode_tele    = BM_REPLY, mode_conf   = BM_REPLY;
static int  mode_ws     = BM_REPLY;
/* Set by reply_blocked_by_type() when the request's type is BM_ALLOW: the
   caller then skips the block reply (and the blocked counter) entirely. */
static bool s_rb_allow = false;

/* ── Query log (audit/statistics) ───────────────────────────────────
 * A small ring of the most recent REAL requests with the decision that was
 * taken for them, so the dashboard and the Android app can show what actually
 * happened (the counters alone cannot answer "why was this blocked?").
 * Only the newest entries are exported in /internal-stats (bounded so the
 * JSON always stays well below the smallest client buffer); the whole ring is
 * persisted to <resources>/query_log.dat next to the other .dat files. */
#define QLOG_MAX 4096
#define QLOG_RENDER_MAX 24
#define QLOG_JSON_MAX 4096
enum { QLOG_PROXY = 0, QLOG_BLOCK = 1, QLOG_ALLOW = 2 };
struct qlog_entry {
    uint64_t ts;          /* epoch seconds */
    uint32_t uid;         /* 0xFFFFFFFF when unknown */
    uint16_t rtype;       /* blocked_* category hint (0 = unknown) */
    uint8_t  action;      /* QLOG_* */
    uint8_t  pad;
    char     host[192];
};
static struct qlog_entry s_qlog[QLOG_MAX];
static uint32_t s_qlog_pos;    /* next slot to write */
static uint32_t s_qlog_count;  /* valid entries (<= QLOG_MAX) */

static void qlog_clear(void) {
    s_qlog_pos = 0;
    s_qlog_count = 0;
    memset(s_qlog, 0, sizeof(s_qlog));
}

static void qlog_add(uid_t uid, int action, struct mg_http_message *hm) {
    struct qlog_entry *e = &s_qlog[s_qlog_pos % QLOG_MAX];
    memset(e, 0, sizeof(*e));
    e->ts = (uint64_t) time(NULL);
    e->uid = (uid == (uid_t) -1) ? 0xFFFFFFFFu : (uint32_t) uid;
    e->action = (uint8_t) action;
    struct mg_str *hh = hm ? mg_http_get_header(hm, "Host") : NULL;
    if (hh && hh->len) {
        size_t n = hh->len < sizeof(e->host) - 1 ? hh->len : sizeof(e->host) - 1;
        memcpy(e->host, hh->buf, n);
        e->host[n] = 0;
        char *colon = strchr(e->host, ':');
        if (colon) *colon = 0;
    } else if (hm && hm->uri.len) {
        size_t n = hm->uri.len < sizeof(e->host) - 1 ? hm->uri.len : sizeof(e->host) - 1;
        memcpy(e->host, hm->uri.buf, n);
        e->host[n] = 0;
    }
    s_qlog_pos = (s_qlog_pos + 1) % QLOG_MAX;
    if (s_qlog_count < QLOG_MAX) s_qlog_count++;
}

/* Newest first, bounded (QLOG_RENDER_MAX entries / QLOG_JSON_MAX bytes). */
static void qlog_render(char *out, size_t cap) {
    if (!out || cap == 0) return;
    out[0] = 0;
    size_t off = 0;
    uint32_t total = s_qlog_count < QLOG_RENDER_MAX ? s_qlog_count : QLOG_RENDER_MAX;
    for (uint32_t k = 0; k < total; k++) {
        uint32_t idx = (s_qlog_pos + QLOG_MAX - 1 - k) % QLOG_MAX;
        struct qlog_entry *e = &s_qlog[idx];
        char host[400];
        size_t h = 0;
        for (size_t i = 0; e->host[i] && h + 1 < sizeof(host); i++) {
            char c = e->host[i];
            host[h++] = (c == 0x22 || c == 0x5c) ? 0x5f : c;
        }
        host[h] = 0;
        int n = snprintf(out + off, cap - off,
                         "%s{\"ts\":%llu,\"uid\":%d,\"action\":%d,\"host\":\"%s\"}",
                         off ? "," : "", (unsigned long long) e->ts,
                         (e->uid == 0xFFFFFFFFu) ? -1 : (int) e->uid,
                         (int) e->action, host);
        if (n <= 0 || off + (size_t) n >= cap) { out[off] = 0; break; }
        off += (size_t) n;
    }
}

#define QLOG_MAGIC 0x514C4F47u   /* "QLOG" */
static void qlog_save(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", resource_dir);
    FILE *fp = fopen(path, "wb");
    if (!fp) return;
    uint32_t magic = QLOG_MAGIC;
    fwrite(&magic, sizeof(magic), 1, fp);
    fwrite(&s_qlog_pos, sizeof(s_qlog_pos), 1, fp);
    fwrite(&s_qlog_count, sizeof(s_qlog_count), 1, fp);
    fwrite(s_qlog, sizeof(s_qlog[0]), s_qlog_count < QLOG_MAX ? s_qlog_count : QLOG_MAX, fp);
    fclose(fp);
}

static void qlog_load(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", resource_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) return;
    uint32_t magic = 0, pos = 0, count = 0;
    if (fread(&magic, sizeof(magic), 1, fp) == 1 && magic == QLOG_MAGIC &&
        fread(&pos, sizeof(pos), 1, fp) == 1 &&
        fread(&count, sizeof(count), 1, fp) == 1 &&
        count <= QLOG_MAX && pos < QLOG_MAX) {
        size_t got = fread(s_qlog, sizeof(s_qlog[0]), count, fp);
        if (got == count) { s_qlog_pos = pos ? pos : (uint32_t) (count % QLOG_MAX); s_qlog_count = count; }
    }
    fclose(fp);
}

/* Sum of every blocked_* counter — used for block_rate metrics. */
static uint64_t stats_total_blocked(void) {
    return s_stats.blocked_images + s_stats.blocked_scripts + s_stats.blocked_styles +
           s_stats.blocked_fonts + s_stats.blocked_media + s_stats.blocked_api +
           s_stats.blocked_telemetry + s_stats.blocked_heartbeat + s_stats.blocked_config +
           s_stats.blocked_ws_sse + s_stats.blocked_other + s_stats.blocked_crypto +
           s_stats.blocked_clickbait;
}

/* ── Persistent lifetime counters ─────────────────────────────── */
/*
 * The cumulative counters in s_stats are saved to
 * <resource_dir>/stats.dat every time /internal-stats is polled (the
 * app polls every 5 s) and loaded again at startup, so totals survive
 * web server restarts ("all-time" statistics). Binary layout: magic +
 * 16 uint64 values; the ring histories intentionally start fresh on
 * each boot (they are time-series of the current session).
 */
#define STATS_MAGIC 0xAD574159u  /* "ADWAY" */
struct stats_file {
    uint32_t magic;
    uint64_t total_requests;
    uint64_t total_connections;
    uint64_t blocked_images;
    uint64_t blocked_scripts;
    uint64_t blocked_styles;
    uint64_t blocked_fonts;
    uint64_t blocked_media;
    uint64_t blocked_api;
    uint64_t blocked_telemetry;
    uint64_t blocked_heartbeat;
    uint64_t blocked_config;
    uint64_t blocked_ws_sse;
    uint64_t blocked_other;
    uint64_t blocked_crypto;
    uint64_t blocked_clickbait;
    uint64_t sni_certs_issued;
    /* New metrics (appended to keep old stats.dat files readable:
       fread() reads only what the current struct needs; the file has
       extra trailing bytes that are ignored). */
    uint64_t tls_handshakes;
    uint64_t tls_failures;
    uint64_t sni_cache_hits;
};

static void save_stats(const struct settings *s) {
    if (!s || !s->resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/stats.dat", s->resource_dir);
    struct stats_file f;
    memset(&f, 0, sizeof(f));
    f.magic = STATS_MAGIC;
    f.total_requests    = s_stats.total_requests;
    f.total_connections = s_stats.total_connections;
    f.blocked_images    = s_stats.blocked_images;
    f.blocked_scripts   = s_stats.blocked_scripts;
    f.blocked_styles    = s_stats.blocked_styles;
    f.blocked_fonts     = s_stats.blocked_fonts;
    f.blocked_media     = s_stats.blocked_media;
    f.blocked_api       = s_stats.blocked_api;
    f.blocked_telemetry = s_stats.blocked_telemetry;
    f.blocked_heartbeat = s_stats.blocked_heartbeat;
    f.blocked_config    = s_stats.blocked_config;
    f.blocked_ws_sse    = s_stats.blocked_ws_sse;
    f.blocked_other     = s_stats.blocked_other;
    f.blocked_crypto    = s_stats.blocked_crypto;
    f.blocked_clickbait = s_stats.blocked_clickbait;
    f.sni_certs_issued  = s_stats.sni_certs_issued;
    f.tls_handshakes    = s_stats.tls_handshakes;
    f.tls_failures      = s_stats.tls_failures;
    f.sni_cache_hits    = s_stats.sni_cache_hits;
    FILE *fp = fopen(path, "wb");
    if (fp) {
        fwrite(&f, sizeof(f), 1, fp);
        fclose(fp);
    }
}

static void load_stats(const struct settings *s) {
    if (!s || !s->resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/stats.dat", s->resource_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) return;
    struct stats_file f;
    if (fread(&f, sizeof(f), 1, fp) == 1 && f.magic == STATS_MAGIC) {
        s_stats.total_requests    += f.total_requests;
        s_stats.total_connections += f.total_connections;
        s_stats.blocked_images    += f.blocked_images;
        s_stats.blocked_scripts   += f.blocked_scripts;
        s_stats.blocked_styles    += f.blocked_styles;
        s_stats.blocked_fonts     += f.blocked_fonts;
        s_stats.blocked_media     += f.blocked_media;
        s_stats.blocked_api       += f.blocked_api;
        s_stats.blocked_telemetry += f.blocked_telemetry;
        s_stats.blocked_heartbeat += f.blocked_heartbeat;
        s_stats.blocked_config    += f.blocked_config;
        s_stats.blocked_ws_sse    += f.blocked_ws_sse;
        s_stats.blocked_other     += f.blocked_other;
        s_stats.blocked_crypto    += f.blocked_crypto;
        s_stats.blocked_clickbait += f.blocked_clickbait;
        s_stats.sni_certs_issued  += f.sni_certs_issued;
        s_stats.tls_handshakes    += f.tls_handshakes;
        s_stats.tls_failures      += f.tls_failures;
        s_stats.sni_cache_hits    += f.sni_cache_hits;
    }
    fclose(fp);
}

static uint64_t uptime_seconds(void) {
    return (mg_millis() - s_stats.start_time_ms) / 1000ULL;
}

/*
 * PER-APP ALLOWLIST lookup: reads <resource_dir>/allowlist.txt
 * (one decimal uid per line) and returns true if uid is present.
 * The file is re-read on every call (it's tiny; the app rewrites it
 * only when the user toggles a switch, so a few extra syscalls are
 * cheaper than keeping an in-memory copy in sync).
 */
static bool uid_is_allowed(uid_t uid, const char *resource_dir) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/allowlist.txt", resource_dir);
    FILE *fp = fopen(path, "r");
    if (!fp) return false;
    char line[32];
    bool allowed = false;
    while (fgets(line, sizeof(line), fp) != NULL) {
        char *end = NULL;
        long v = strtol(line, &end, 10);
        if (end != line && v >= 0 && (uid_t)v == uid) { allowed = true; break; }
    }
    fclose(fp);
    return allowed;
}

/*
 * WEBVIEW ISOLATED UID RESOLUTION (allowlist fix):
 * Android runs WebView renderers as "isolated" processes with uids in
 * 99000-99999 that are allocated dynamically per renderer. A static
 * allowlist.txt entry can never match them, so a user-allowed host app
 * (e.g. a browser the user switched "Allow" on) still had its WebView
 * requests blocked -> blank pages / broken portal login.
 *
 * Fix: when a request comes from an isolated uid, walk /proc to find
 * the process with that uid, then follow PPid links upward until a
 * non-isolated uid is reached (the host app). The allowlist decision is
 * then made on the host app uid. We run as root, so all /proc entries
 * are readable. If the chain cannot be resolved the original uid is
 * returned unchanged (conservative: the request stays blocked unless
 * the host app is explicitly allowlisted).
 */
#define ISOLATED_UID_MIN 99000
#define ISOLATED_UID_MAX 99999

static bool uid_is_isolated(uid_t uid) {
    return uid >= ISOLATED_UID_MIN && uid <= ISOLATED_UID_MAX;
}

/* Read "Uid:" / "PPid:" from /proc/<pid>/status. Returns 0 on success. */
static int proc_status_uid_ppid(const char *pid_str, uid_t *uid_out, int *ppid_out) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%s/status", pid_str);
    FILE *fp = fopen(path, "r");
    if (!fp) return -1;
    char line[256];
    *uid_out = (uid_t)-1;
    *ppid_out = -1;
    while (fgets(line, sizeof(line), fp)) {
        unsigned long u = 0;
        long p = 0;
        if (sscanf(line, "Uid:%lu", &u) == 1) *uid_out = (uid_t)u;
        else if (sscanf(line, "PPid:%ld", &p) == 1) *ppid_out = (int)p;
        if (*uid_out != (uid_t)-1 && *ppid_out != -1) break;
    }
    fclose(fp);
    return (*uid_out == (uid_t)-1) ? -1 : 0;
}

static uid_t resolve_effective_uid(uid_t uid) {
    if (!uid_is_isolated(uid)) return uid;
    DIR *dir = opendir("/proc");
    if (!dir) return uid;
    uid_t cur = uid;
    int guard = 0;
    while (uid_is_isolated(cur) && guard++ < 8) {
        bool advanced = false;
        rewinddir(dir);
        struct dirent *de;
        while ((de = readdir(dir)) != NULL) {
            if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
            uid_t suid;
            int ppid;
            if (proc_status_uid_ppid(de->d_name, &suid, &ppid) != 0) continue;
            if (suid != cur || ppid <= 0) continue;
            char ppid_str[16];
            snprintf(ppid_str, sizeof(ppid_str), "%d", ppid);
            uid_t puid;
            int dummy;
            if (proc_status_uid_ppid(ppid_str, &puid, &dummy) == 0 && puid != (uid_t)-1) {
                cur = puid;
                advanced = true;
                break;
            }
        }
        if (!advanced) break; /* parent chain broken (process exited) */
    }
    closedir(dir);
    return cur;
}

/* ── Hourly history (for the time-series chart) ────────────────── */
/* Ring of 24 hourly buckets + 30 daily buckets: requests / blocked /
   connections (and certs) per hour / per day, exposed via
   /internal-stats "history" (hourly, oldest first) and "daily"
   (oldest first). Bucket timestamps are wall-clock epoch seconds
   (time(NULL)), independent of process uptime. All updates happen on
   the single mongoose event-loop thread. */
#define HIST_SLOTS        24
#define HIST_INTERVAL_S   3600
#define DAILY_SLOTS       30
#define DAILY_INTERVAL_S  86400
struct hist_slot {
    uint64_t requests;
    uint64_t blocked;
    uint64_t connections;
    uint64_t certs;
};
static struct hist_slot s_hist[HIST_SLOTS];
static int      s_hist_pos = 0;       /* current (most recent) slot */
static time_t   s_hist_slot_start = 0;/* epoch seconds of current slot */
static struct hist_slot s_daily[DAILY_SLOTS];
static int      s_daily_pos = 0;
static time_t   s_daily_slot_start = 0;

static void hist_tick(void) {
    time_t now = time(NULL);
    if (s_hist_slot_start == 0) {
        s_hist_slot_start = now;
        s_daily_slot_start = now;
        return;
    }
    while (now - s_hist_slot_start >= HIST_INTERVAL_S) {
        s_hist_pos = (s_hist_pos + 1) % HIST_SLOTS;
        memset(&s_hist[s_hist_pos], 0, sizeof(s_hist[s_hist_pos]));
        s_hist_slot_start += HIST_INTERVAL_S;
    }
    while (now - s_daily_slot_start >= DAILY_INTERVAL_S) {
        s_daily_pos = (s_daily_pos + 1) % DAILY_SLOTS;
        memset(&s_daily[s_daily_pos], 0, sizeof(s_daily[s_daily_pos]));
        s_daily_slot_start += DAILY_INTERVAL_S;
    }
}

enum hist_kind { HIST_CONN, HIST_REQ, HIST_BLOCKED, HIST_CERT };
static void hist_add(enum hist_kind kind) {
    hist_tick();
    struct hist_slot *h = &s_hist[s_hist_pos];
    struct hist_slot *d = &s_daily[s_daily_pos];
    switch (kind) {
        case HIST_CONN:    h->connections++; d->connections++; break;
        case HIST_REQ:     h->requests++;    d->requests++;    break;
        case HIST_BLOCKED: h->blocked++;     d->blocked++;     break;
        case HIST_CERT:    h->certs++;       d->certs++;       break;
    }
}

/*
 * PERSISTENT HISTORY (no reset on reboot):
 * The hourly/daily ring buckets used to live only in RAM, so every
 * webserver restart (boot, app reinstall, crash) zeroed the charts.
 * Now the full ring state is snapshotted to <resource_dir>/hist.dat
 * whenever /internal-stats is polled (same cadence as stats.dat) and
 * loaded again at startup. Wall-clock slot starts are stored too, so
 * hist_tick() simply rolls forward to the correct slot after a long
 * downtime instead of losing the pre-reboot data.
 */
#define HIST_MAGIC 0x48495354u  /* "HIST" */
struct hist_file {
    uint32_t magic;
    int32_t  hist_pos;
    int32_t  daily_pos;
    int64_t  hist_slot_start;
    int64_t  daily_slot_start;
    struct hist_slot hist[HIST_SLOTS];
    struct hist_slot daily[DAILY_SLOTS];
};

static void save_hist(const struct settings *s) {
    if (!s || !s->resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/hist.dat", s->resource_dir);
    struct hist_file f;
    memset(&f, 0, sizeof(f));
    f.magic = HIST_MAGIC;
    f.hist_pos = s_hist_pos;
    f.daily_pos = s_daily_pos;
    f.hist_slot_start = (int64_t)s_hist_slot_start;
    f.daily_slot_start = (int64_t)s_daily_slot_start;
    memcpy(f.hist, s_hist, sizeof(s_hist));
    memcpy(f.daily, s_daily, sizeof(s_daily));
    FILE *fp = fopen(path, "wb");
    if (fp) { fwrite(&f, sizeof(f), 1, fp); fclose(fp); }
}

static void load_hist(const struct settings *s) {
    if (!s || !s->resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/hist.dat", s->resource_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) return;
    struct hist_file f;
    if (fread(&f, sizeof(f), 1, fp) == 1 && f.magic == HIST_MAGIC) {
        s_hist_pos = f.hist_pos;
        s_daily_pos = f.daily_pos;
        s_hist_slot_start = (time_t)f.hist_slot_start;
        s_daily_slot_start = (time_t)f.daily_slot_start;
        memcpy(s_hist, f.hist, sizeof(s_hist));
        memcpy(s_daily, f.daily, sizeof(s_daily));
        /* Roll forward to now so buckets tick into the correct slots. */
        hist_tick();
    }
    fclose(fp);
}

/* Serialize the last N buckets (oldest first) into out[]. */
static int buckets_to_json(char *out, size_t out_sz,
                           const struct hist_slot *buckets, int slots,
                           int pos, time_t slot_start, int interval_s,
                           int max) {
    int off = 0;
    int n_out = slots < max ? slots : max;
    for (int i = 1; i <= n_out; i++) {
        int idx = (pos - (slots - i) + slots * 2) % slots;
        const struct hist_slot *s = &buckets[idx];
        time_t ts = slot_start - (time_t)(slots - i) * interval_s;
        int n = snprintf(out + off, out_sz - (size_t)off,
                         "%s{\"ts\":%lld,\"requests\":%llu,\"blocked\":%llu,"
                         "\"connections\":%llu,\"certs\":%llu}",
                         off ? "," : "",
                         (long long)ts,
                         (unsigned long long)s->requests,
                         (unsigned long long)s->blocked,
                         (unsigned long long)s->connections,
                         (unsigned long long)s->certs);
        if (n <= 0 || off + n >= (int)out_sz) break;
        off += n;
    }
    return off;
}

/* ── Per-app statistics ───────────────────────────────────────── */
/*
 * Which app (uid) is connecting to the block server, making requests,
 * getting blocked, and which SNI hostnames it is asking us to sign
 * certificates for. The server runs as root, so SO_PEERCRED on each
 * loopback connection yields the requesting app's uid; the Android
 * side maps uid → package name via PackageManager.
 *
 * Same threading note as s_stats: Mongoose 7.x drives everything from
 * a single mg_mgr_poll() event loop, so all updates below happen on
 * one thread and plain counters are safe.
 */
#define APP_STATS_MAX  32   /* distinct uids tracked */
#define RECENT_TLS_MAX 64   /* distinct (uid, host) pairs remembered */
#define TLS_HOST_MAX   128

struct appstat {
    uid_t    uid;            /* Android app uid (AID_APP_*) */
    uint64_t connections;    /* accepted connections        */
    uint64_t requests;       /* HTTP requests seen          */
    uint64_t blocked;        /* requests answered as blocked */
    uint64_t tls_hosts;      /* distinct SNI hosts requested */
};
static struct appstat s_apps[APP_STATS_MAX];
static int s_app_count = 0;

/* Per-app stats persistence: s_apps is otherwise RAM-only and would be
   zeroed on every webserver restart. Save to <resource_dir>/apps.dat at
   the same cadence as stats.dat (each /internal-stats poll + on exit)
   and load at startup, so the per-app list also survives reboots. */
#define APPS_MAGIC 0x41505053u  /* "APPS" */
struct apps_file {
    uint32_t magic;
    uint32_t count;
    struct appstat entries[APP_STATS_MAX];
};
static void apps_save(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/apps.dat", resource_dir);
    struct apps_file f;
    memset(&f, 0, sizeof(f));
    f.magic = APPS_MAGIC;
    f.count = (uint32_t)s_app_count;
    for (int i = 0; i < s_app_count && i < APP_STATS_MAX; i++)
        f.entries[i] = s_apps[i];
    FILE *fp = fopen(path, "wb");
    if (fp) { fwrite(&f, sizeof(f), 1, fp); fclose(fp); }
}

/* ── throttled persistence ───────────────────────────────────────
 * /internal-stats is polled by the dashboard every couple of seconds. Flushing
 * stats.dat + hist.dat + apps.dat + sni_cache.dat (~270 KB) on every single
 * poll was pure flash/disk churn. Write on change at a bounded rate instead;
 * /control?cmd=flush_stats and shutdown still force a full flush. */
#define PERSIST_MIN_INTERVAL_MS 15000ULL
#define PERSIST_SNI_INTERVAL_MS 60000ULL

static uint64_t s_persist_last_ms = 0;
static uint64_t s_persist_sni_last_ms = 0;
static uint64_t s_persist_sni_certs = 0;

static void persist_dat_files(struct settings *st, bool force) {
    uint64_t now = mg_millis();
    if (force || now - s_persist_last_ms >= PERSIST_MIN_INTERVAL_MS) {
        save_stats(st);
        save_hist(st);
        apps_save(st->resource_dir);
        s_persist_last_ms = now;
    }
    bool sni_dirty = (s_stats.sni_certs_issued != s_persist_sni_certs);
    if (force || now - s_persist_sni_last_ms >= PERSIST_SNI_INTERVAL_MS ||
        (sni_dirty && now - s_persist_sni_last_ms >= PERSIST_MIN_INTERVAL_MS)) {
        sni_cache_save(st->resource_dir);
        s_persist_sni_last_ms = now;
        s_persist_sni_certs = s_stats.sni_certs_issued;
    }
}

static void apps_load(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/apps.dat", resource_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) return;
    struct apps_file f;
    if (fread(&f, sizeof(f), 1, fp) == 1 && f.magic == APPS_MAGIC) {
        int n = f.count < APP_STATS_MAX ? (int)f.count : APP_STATS_MAX;
        for (int i = 0; i < n; i++) s_apps[i] = f.entries[i];
        s_app_count = n;
        LOG_INFO("Per-app stats loaded: %d uids", n);
    }
    fclose(fp);
}

/* Distinct (uid, hostname) pairs seen on TLS connections — i.e. the
   per-domain leaf certs effectively issued per app. Ring buffer. */
struct tls_host_rec {
    uid_t    uid;
    char     host[TLS_HOST_MAX];
    uint64_t at_ms;          /* last seen */
};
static struct tls_host_rec s_recent_tls[RECENT_TLS_MAX];
static int s_tls_pos = 0;    /* next write slot (ring) */
static int s_tls_count = 0;  /* distinct pairs recorded so far */

/* Resolve the uid owning this connection by matching its (local,
   remote, ESTABLISHED) 4-tuple against /proc/net/tcp[/tcp6]. Only
   called when a request is being processed, i.e. the connection is
   definitely alive and present in /proc — no accept-time races.
   We run as root, so every row is visible. */
#ifndef _WIN32   /* only the Android/Linux uid lookup below uses these */
/* Format an mg_addr as /proc/net/tcp (IPv4) would print it. */
static void addr_to_proc_v4(const struct mg_addr *a, char *out, size_t sz) {
    snprintf(out, sz, "%02X%02X%02X%02X:%04X",
             (unsigned)a->addr.ip[3], (unsigned)a->addr.ip[2],
             (unsigned)a->addr.ip[1], (unsigned)a->addr.ip[0],
             ((unsigned)(a->port & 0xFF) << 8) | ((unsigned)a->port >> 8));
}

/* Format an mg_addr as /proc/net/tcp6 would print it - works for BOTH
   real IPv6 addresses and IPv4-mapped ones (::ffff:a.b.c.d): the kernel
   prints each 32-bit word as a host-endian hex number, i.e. the bytes of
   every 32-bit group appear reversed (e.g. the ff:ff word of an
   IPv4-mapped address shows as "FFFF0000" on little-endian devices).
   BUG FIX: the previous version only handled the IPv4-mapped form, so
   genuine IPv6 loopback clients ([::1]) never matched their /proc/net/tcp6
   row and per-app statistics fell back to the socket-inode path - which
   reports the SERVER's own uid (0, root), attributing app traffic to the
   root package. */
static void addr_to_proc_v6(const struct mg_addr *a, char *out, size_t sz) {
    char *d = out;
    for (int g = 0; g < 4; g++)
        d += snprintf(d, 9, "%02X%02X%02X%02X",
                      (unsigned)a->addr.ip[g * 4 + 3], (unsigned)a->addr.ip[g * 4 + 2],
                      (unsigned)a->addr.ip[g * 4 + 1], (unsigned)a->addr.ip[g * 4 + 0]);
    snprintf(out + 32, sz - 32, ":%04X",
             ((unsigned)(a->port & 0xFF) << 8) | ((unsigned)a->port >> 8));
}

/* Resolve the uid owning this connection by matching its (local,
   remote) 4-tuple against /proc/net/tcp[/tcp6]. Only called while a
   request is being processed (connection is alive). The dual-stack
   listener accepts IPv4 clients two ways: plain IPv4 (row in
   /proc/net/tcp, uid may be zeroed on some kernels) and IPv4-mapped
   (row in /proc/net/tcp6, uid preserved) — so we try both formats.
   We run as root, so every row is visible. */
#endif  /* !_WIN32 - /proc address formatters above */

static uid_t conn_uid_by_tuple(struct mg_connection *c) {
#ifdef _WIN32
    /* Windows has no /proc: the tuple and socket-inode lookups below cannot
       work there, and probing them costs several failing fopen()/fstat()
       syscalls on EVERY request. Per-app statistics are Android-only by
       design (an unknown uid is dropped by app_find_or_add()). */
    (void) c;
    return (uid_t) -1;
#else
    char loc_v4[64], rem_v4[64];
    char loc_m6[64], rem_m6[64];
    addr_to_proc_v4(&c->loc, loc_v4, sizeof(loc_v4));
    addr_to_proc_v4(&c->rem, rem_v4, sizeof(rem_v4));
    addr_to_proc_v6(&c->loc, loc_m6, sizeof(loc_m6));
    addr_to_proc_v6(&c->rem, rem_m6, sizeof(rem_m6));

    /* Pass 0: /proc/net/tcp6 with the v4-mapped form (real uid).
       NOTE: /proc rows are client-first (local = the connecting end,
       remote = our listener), while c->loc is our listener and c->rem
       the client — so compare l against rem_* and r against loc_*. */
    FILE *f = fopen("/proc/net/tcp6", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            char l[64] = "", r[64] = "";
            unsigned int state;
            unsigned long uid = 0;
            if (sscanf(line, "%*s %63s %63s %X %*s %*s %*s %lu %*s %*s",
                       l, r, &state, &uid) == 4) {
                if (state == 1 /* ESTABLISHED */ &&
                    strcmp(l, rem_m6) == 0 && strcmp(r, loc_m6) == 0) {
                    fclose(f);
                    if (s_verbose)
                        LOG_INFO("conn_uid: %s <-> %s -> uid=%d (tcp6/mapped)",
                                 loc_m6, rem_m6, (int)uid);
                    return (uid_t)uid;
                }
            }
        }
        fclose(f);
    }

    /* Pass 1: /proc/net/tcp with the plain IPv4 form. */
    f = fopen("/proc/net/tcp", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            char l[64] = "", r[64] = "";
            unsigned int state;
            unsigned long uid = 0;
            if (sscanf(line, "%*s %63s %63s %X %*s %*s %*s %lu %*s %*s",
                       l, r, &state, &uid) == 4) {
                if (state == 1 /* ESTABLISHED */ &&
                    strcmp(l, rem_v4) == 0 && strcmp(r, loc_v4) == 0) {
                    fclose(f);
                    if (s_verbose)
                        LOG_INFO("conn_uid: %s <-> %s -> uid=%d (tcp)",
                                 loc_v4, rem_v4, (int)uid);
                    return (uid_t)uid;
                }
            }
        }
        fclose(f);
    }

    /* Pass 2 (fallback): socket-inode match. The 4-tuple can miss when
       the client closes the connection right after its request (the
       kernel drops the ESTABLISHED row before we scan), but the fd is
       still ours; fstat() gives the socket inode, which /proc lists in
       the last column while the socket exists (including TIME_WAIT-ish
       states is fine: any row with our inode and its uid is the peer).
       We accept state 0x01 (ESTABLISHED) or 0x06 (TIME_WAIT). */
    {
        int sfd = (int)(intptr_t)c->fd;
        struct stat st;
        if (sfd > 0 && fstat(sfd, &st) == 0) {
            unsigned long sock_ino = (unsigned long)st.st_ino;
            for (int pass = 0; pass < 2; pass++) {
                const char *path = pass == 0 ? "/proc/net/tcp6" : "/proc/net/tcp";
                f = fopen(path, "r");
                if (!f) continue;
                char line[512];
                while (fgets(line, sizeof(line), f)) {
                    unsigned int state;
                    unsigned long uid = 0, line_ino = 0;
                    if (sscanf(line, "%*s %*s %*s %X %*s %*s %*s %lu %*s %lu",
                               &state, &uid, &line_ino) == 3) {
                        if ((state == 1 || state == 6) && line_ino == sock_ino) {
                            fclose(f);
                            if (s_verbose)
                                LOG_INFO("conn_uid: ino=%lu -> uid=%d (%s)",
                                         sock_ino, (int)uid, path);
                            return (uid_t)uid;
                        }
                    }
                }
                fclose(f);
            }
        }
    }
    if (s_verbose)
        LOG_INFO("conn_uid: %s <-> %s / %s <-> %s -> NOT FOUND",
                 loc_v4, rem_v4, loc_m6, rem_m6);
    return (uid_t)-1;
#endif  /* !_WIN32 */
}

/* Store the resolved uid in c->data (accepted-at timestamp + flag
   occupy bytes 0-8; uid at offset 9, see MG_DATA_SIZE=32). */
#define UID_OFFSET (sizeof(uint64_t) + 1)
#define REC_OFFSET (UID_OFFSET + sizeof(uid_t))
/* Set once this connection has carried a real (non-management) request; the
   connection counters are fed from there, so the app's/dashboard's 1 Hz
   /internal-stats polls are not counted as user traffic. */
#define CONN_REQ_OFFSET (REC_OFFSET + 1)
static void conn_store_uid(struct mg_connection *c, uid_t uid) {
    memcpy(c->data + UID_OFFSET, &uid, sizeof(uid));
}
static uid_t conn_load_uid(struct mg_connection *c) {
    uid_t uid;
    memcpy(&uid, c->data + UID_OFFSET, sizeof(uid));
    return uid;
}

static struct appstat *app_find_or_add(uid_t uid) {
    if (uid == (uid_t)-1 || uid == 0) return NULL;  /* unknown/root */
    for (int i = 0; i < s_app_count; i++)
        if (s_apps[i].uid == uid) return &s_apps[i];
    if (s_app_count < APP_STATS_MAX) {
        struct appstat *a = &s_apps[s_app_count++];
        memset(a, 0, sizeof(*a));
        a->uid = uid;
        return a;
    }
    return NULL;  /* table full — drop counters for new uids */
}

/* Remember that uid asked for a TLS cert for host (SNI). */
static void app_record_tls_host(uid_t uid, const char *host) {
    if (uid == (uid_t)-1 || !host || !*host) return;
    for (int i = 0; i < RECENT_TLS_MAX; i++) {
        if (s_recent_tls[i].host[0] && s_recent_tls[i].uid == uid &&
            strcmp(s_recent_tls[i].host, host) == 0) {
            s_recent_tls[i].at_ms = mg_millis();  /* refresh timestamp */
            return;
        }
    }
    struct tls_host_rec *r = &s_recent_tls[s_tls_pos % RECENT_TLS_MAX];
    r->uid = uid;
    snprintf(r->host, sizeof(r->host), "%s", host);
    r->at_ms = mg_millis();
    s_tls_pos++;
    if (s_tls_count < RECENT_TLS_MAX) s_tls_count++;
    struct appstat *a = app_find_or_add(uid);
    if (a) a->tls_hosts++;
}

/* ── Resilience: stay alive, and leave evidence when we do crash ──
 *
 * The server is a detached root process; aggressive OEM memory managers
 * otherwise reap it and the device silently loses its blocker. A very low
 * oom_score_adj makes the kernel pick almost any other process first.
 * The crash handler appends the signal and a backtrace to the log the app
 * displays (/data/local/tmp/webserver_start.log), so "the server often stops"
 * finally becomes diagnosable instead of silent.
 */
#ifndef _WIN32
static char s_crash_dir[PATH_MAX];

/*
 * backtrace()/backtrace_symbols_fd() are only exposed from API 33 on, so they
 * are declared weak here: the crash log keeps working everywhere and simply
 * omits the backtrace on older platforms.
 */
extern int backtrace(void **, int) __attribute__((weak));
extern void backtrace_symbols_fd(void *const *, int, int) __attribute__((weak));

static void crash_handler(int sig) {
    /* Async-signal-safe only: open()/write()/backtrace_symbols_fd(). */
    char header[192];
    int n = snprintf(header, sizeof(header),
                     "\n[CRASH] webserver killed by signal %d\n", sig);
    if (n > 0) {
        int fd = open("/data/local/tmp/webserver_start.log",
                      O_WRONLY | O_APPEND | O_CREAT, 0644);
        if (fd >= 0) { ssize_t w = write(fd, header, (size_t) n); (void) w; close(fd); }
        if (s_crash_dir[0]) {
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "%s/native_crash.log", s_crash_dir);
            fd = open(path, O_WRONLY | O_APPEND | O_CREAT, 0644);
            if (fd >= 0) {
                ssize_t w = write(fd, header, (size_t) n);
                (void) w;
                if (backtrace != 0 && backtrace_symbols_fd != 0) {
                    void *frames[32];
                    int count = backtrace(frames, 32);
                    backtrace_symbols_fd(frames, count, fd);
                }
                close(fd);
            }
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}
#endif

static void setup_crash_handler(const char *resource_dir) {
#ifndef _WIN32
    if (resource_dir && *resource_dir) {
        snprintf(s_crash_dir, sizeof(s_crash_dir), "%s", resource_dir);
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crash_handler;
    sa.sa_flags = SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
#else
    (void) resource_dir;
#endif
}

static void harden_process(void) {
#ifndef _WIN32
    /* -900 keeps the blocker alive when the system runs out of memory. */
    FILE *f = fopen("/proc/self/oom_score_adj", "w");
    if (f) {
        fputs("-900", f);
        fclose(f);
        LOG_INFO("oom_score_adj set to -900 (resistant to low-memory kills)");
    }
    signal(SIGPIPE, SIG_IGN);
#endif
}

/* ── Signal handling ──────────────────────────────────────────── */
static volatile sig_atomic_t s_sig_num = 0;
static void signal_handler(int n) { s_sig_num = n; }
static void setup_signal_handler(void) {
#ifdef _WIN32
    /* Windows CRT signal(): SIGINT/SIGTERM only (no SIGPIPE/SIGHUP). */
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
#else
    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_IGN; sigaction(SIGPIPE, &sa, NULL);
    sa.sa_handler = signal_handler;
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP,  &sa, NULL);
#endif
}

/* ── OOM killer ───────────────────────────────────────────────── */
static void oom_adjust_setup(void) {
    FILE *fp = fopen(OOM_ADJ_PATH, "r+");
    if (!fp) return;
    char buf[32]; int n = fread(buf, 1, sizeof(buf)-1, fp); buf[n] = '\0';
    char *e; long cur = strtol(buf, &e, 10);
    if (e == buf) { fclose(fp); return; }
    if (OOM_ADJ_NOKILL < cur) {
        rewind(fp);
        if (fprintf(fp, "%d\n", OOM_ADJ_NOKILL) > 0)
            LOG_LOGCAT(ANDROID_LOG_INFO,
                "OOM score: %ld → %d", cur, OOM_ADJ_NOKILL);
    }
    fclose(fp);
}

/* ── Certificate generation ───────────────────────────────────── */

/* Generate a certificate signed by ca_cert/ca_key for 'hostname'.
   If ca_cert==NULL the cert is self-signed (used for the root CA itself).
   san_override, if non-NULL, replaces the default "DNS:hostname" SAN
   string — used for the localhost leaf cert, which needs both
   "DNS:localhost" and "IP:127.0.0.1" since clients connecting to the
   literal loopback address check the IP SAN, not just the CN.
   use_ec selects EC (P-256) instead of RSA-2048 for the generated key.
   OPTIMIZATION: make_domain_ctx() calls this on every SNI cache miss —
   i.e. once per distinct ad/tracker domain the device visits that isn't
   already in the SNI cache (see SNI_CACHE_SIZE below). RSA-2048 keygen costs tens to
   low-hundreds of milliseconds on a phone CPU; EC P-256 keygen is
   consistently sub-millisecond, and P-256 server certs are supported by
   effectively every modern TLS client. Only the hot path (per-domain
   leaf certs) is switched — the root CA and the localhost leaf cert are
   each generated at most once per process start, so there's nothing to
   gain there and no reason to touch what's already known-working. */
static int make_cert(const char  *hostname,
                     X509        *ca_cert,   /* NULL → self-signed root CA */
                     EVP_PKEY    *ca_key,    /* signing key                */
                     int          is_ca,     /* 1 → add CA extensions      */
                     int          validity_days,
                     const char  *san_override,
                     int          use_ec,
                     int          rsa_bits,  /* RSA key size (0 → 2048)    */
                     X509       **out_cert,
                     EVP_PKEY   **out_key) {
    int ret = EXIT_FAILURE;
    EVP_PKEY_CTX *pctx = NULL;
    EVP_PKEY     *pkey = NULL;
    X509         *x    = NULL;
    X509_NAME    *name = NULL;

    if (use_ec) {
        pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, NULL);
        if (!pctx || EVP_PKEY_keygen_init(pctx) <= 0 ||
            EVP_PKEY_CTX_set_ec_paramgen_curve_nid(pctx, NID_X9_62_prime256v1) <= 0 ||
            EVP_PKEY_keygen(pctx, &pkey) <= 0) goto done;
    } else {
        int bits = rsa_bits > 0 ? rsa_bits : 2048;
        pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, NULL);
        if (!pctx || EVP_PKEY_keygen_init(pctx) <= 0 ||
            EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, bits) <= 0 ||
            EVP_PKEY_keygen(pctx, &pkey) <= 0) goto done;
    }
    LOG_INFO("make_cert(%s): %s key generated", hostname, use_ec ? "EC" : "RSA");

    x = X509_new();
    if (!x) goto done;
    X509_set_version(x, 2);

    uint64_t serial = 0;
    if (RAND_bytes((unsigned char *)&serial, sizeof(serial)) != 1)
        serial = (uint64_t)mg_millis();
    serial &= 0x7FFFFFFFFFFFFFFFULL;
    ASN1_INTEGER_set_uint64(X509_get_serialNumber(x), serial);

    X509_gmtime_adj(X509_get_notBefore(x), -60);
    X509_gmtime_adj(X509_get_notAfter(x), (long)60*60*24*validity_days);
    X509_set_pubkey(x, pkey);

    name = X509_get_subject_name(x);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               (const unsigned char *)hostname, -1, -1, 0);

    /* If self-signed: issuer == subject.  Otherwise use the CA's subject. */
    if (ca_cert)
        X509_set_issuer_name(x, X509_get_subject_name(ca_cert));
    else
        X509_set_issuer_name(x, name);

    {
        X509V3_CTX ctx;
        X509V3_set_ctx_nodb(&ctx);
        X509V3_set_ctx(&ctx, ca_cert ? ca_cert : x, x, NULL, NULL, 0);

        /* SAN string: "DNS:hostname" unless the caller supplied an
           explicit override (see san_override doc comment above). */
        char san[288];
        if (san_override) {
            snprintf(san, sizeof(san), "%s", san_override);
        } else {
            snprintf(san, sizeof(san), "DNS:%s", hostname);
        }

        /* Key usage depends on role */
        const char *ku = is_ca
            ? "critical,digitalSignature,keyCertSign,cRLSign"
            : "critical,digitalSignature,keyEncipherment";

        struct { int nid; const char *val; } exts[] = {
            { NID_subject_alt_name,       san                     },
            { NID_key_usage,              ku                      },
            { NID_ext_key_usage,          "serverAuth"            },
            { NID_subject_key_identifier, "hash"                  },
            /* CA-only extensions */
            { is_ca ? NID_basic_constraints : 0,
              is_ca ? "critical,CA:TRUE"     : NULL               },
        };
        for (int i = 0; i < (int)(sizeof(exts)/sizeof(exts[0])); i++) {
            if (!exts[i].nid || !exts[i].val) continue;
            X509_EXTENSION *ext = X509V3_EXT_conf_nid(
                NULL, &ctx, exts[i].nid, exts[i].val);
            if (!ext) { LOG_FATAL("Ext %d failed", exts[i].nid); goto done; }
            int ok = X509_add_ext(x, ext, -1);
            X509_EXTENSION_free(ext);
            if (!ok) goto done;
        }
        LOG_INFO("make_cert(%s): extensions added", hostname);
    }

    EVP_PKEY *sign_key = ca_key ? ca_key : pkey;
    if (X509_sign(x, sign_key, EVP_sha256()) == 0) goto done;
    LOG_INFO("make_cert(%s): signed OK", hostname);

    *out_cert = x;  x = NULL;
    *out_key  = pkey; pkey = NULL;
    ret = EXIT_SUCCESS;

done:
    if (x)    X509_free(x);
    if (pkey) EVP_PKEY_free(pkey);
    if (pctx) EVP_PKEY_CTX_free(pctx);
    return ret;
}

/* Generate root CA cert + key and write as PEM files */
static int generate_root_ca(const char *cert_path, const char *key_path) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    /* Root CA: RSA-3072 for a stronger trust anchor (Android system
       trust store fully supports 3072-bit keys; keygen happens only
       once per CA life). */
    int ret = make_cert("ADBlock Root CA", NULL, NULL, 1, 3650, NULL, /*use_ec=*/0, /*rsa_bits=*/3072, &cert, &key);
    if (ret != EXIT_SUCCESS) return ret;
    ret = EXIT_FAILURE;
    FILE *f = NULL;
    /* BUG FIX: the previous code leaked the FILE* when PEM_write_*
       failed and never checked fclose()'s return value, so a full
       disk or I/O error could leave a truncated CA file on disk that
       would then be loaded (and trusted) on the next start. Fail
       loudly instead, and always release the handle. */
    f = fopen(key_path, "wb");
    if (!f) goto done;
    if (PEM_write_PrivateKey(f, key, NULL, NULL, 0, NULL, NULL) == 0) {
        fclose(f); f = NULL; goto done;
    }
    if (fclose(f) != 0) { f = NULL; goto done; }
    f = NULL;
    chmod(key_path, S_IRUSR|S_IWUSR);

    f = fopen(cert_path, "wb");
    if (!f) goto done;
    if (PEM_write_X509(f, cert) == 0) { fclose(f); f = NULL; goto done; }
    if (fclose(f) != 0) { f = NULL; goto done; }
    f = NULL;
    ret = EXIT_SUCCESS;
done:
    if (f) fclose(f);
    X509_free(cert); EVP_PKEY_free(key);
    return ret;
}

/* Load CA cert + key from PEM files into memory */
static int load_ca(const char *cert_path, const char *key_path,
                   struct ca_state *out) {
    FILE *fc = fopen(cert_path, "rb");
    FILE *fk = fopen(key_path,  "rb");
    if (!fc || !fk) { if (fc) fclose(fc); if (fk) fclose(fk); return EXIT_FAILURE; }
    out->cert = PEM_read_X509(fc, NULL, NULL, NULL);
    out->key  = PEM_read_PrivateKey(fk, NULL, NULL, NULL);
    fclose(fc); fclose(fk);
    return (out->cert && out->key) ? EXIT_SUCCESS : EXIT_FAILURE;
}

/*
 * CERT ROTATION (enhancement): check the loaded CA's remaining validity.
 * If it expires within 30 days (or is already expired), regenerate the
 * root CA in place. The app detects the change via the cert-hash
 * comparison in WebServerUtils.getWebServerState() and shows
 * "certificate has changed — tap to reinstall", so the user is guided
 * to reinstall the new CA. Returns 1 if regenerated, 0 otherwise.
 */
static int maybe_rotate_ca(const char *cert_path, const char *key_path,
                           struct ca_state *ca) {
    if (!ca->cert) return 0;
    const ASN1_TIME *not_after = X509_get0_notAfter(ca->cert);
    if (!not_after) return 0;
    /* Parse ASN1_TIME (YYYYMMDDHHMMSSZ) into a time_t. */
    int y, M, d, h, m, s;
    if (sscanf((const char *)not_after->data, "%4d%2d%2d%2d%2d%2d",
               &y, &M, &d, &h, &m, &s) != 6) return 0;
    struct tm tm = {0};
    tm.tm_year = y - 1900; tm.tm_mon = M - 1; tm.tm_mday = d;
    tm.tm_hour = h; tm.tm_min = m; tm.tm_sec = s;
    time_t expiry = mktime(&tm);
    time_t now = time(NULL);
    if (expiry - now > 30L * 24 * 3600) return 0; /* still valid */
    LOG_WARN("CA expires within 30 days (%s) — regenerating", not_after->data);
    X509_free(ca->cert); ca->cert = NULL;
    EVP_PKEY_free(ca->key); ca->key = NULL;
    if (generate_root_ca(cert_path, key_path) != EXIT_SUCCESS) {
        LOG_FATAL("CA rotation failed");
        return 0;
    }
    if (load_ca(cert_path, key_path, ca) != EXIT_SUCCESS) {
        LOG_FATAL("Failed to reload rotated CA");
        return 0;
    }
    LOG_INFO("CA rotated; user must reinstall the new certificate");
    return 1;
}

/* Serialize an X509 cert / private key to PEM into a freshly malloc'd
   buffer (via a memory BIO, then copied out so the result is owned by
   plain malloc/free like the rest of this file — not OpenSSL's
   allocator). Returns a zeroed mg_str on failure. */
static struct mg_str cert_to_pem_mgstr(X509 *cert) {
    struct mg_str out = {0};
    BIO *bio = BIO_new(BIO_s_mem());
    if (!bio) return out;
    if (PEM_write_bio_X509(bio, cert) != 1) { BIO_free(bio); return out; }
    char *data; long len = BIO_get_mem_data(bio, &data);
    char *buf = malloc((size_t)len);
    if (buf) { memcpy(buf, data, (size_t)len); out.buf = buf; out.len = (size_t)len; }
    BIO_free(bio);
    return out;
}
static struct mg_str key_to_pem_mgstr(EVP_PKEY *key) {
    struct mg_str out = {0};
    BIO *bio = BIO_new(BIO_s_mem());
    if (!bio) return out;
    if (PEM_write_bio_PrivateKey(bio, key, NULL, NULL, 0, NULL, NULL) != 1) {
        BIO_free(bio); return out;
    }
    char *data; long len = BIO_get_mem_data(bio, &data);
    char *buf = malloc((size_t)len);
    if (buf) { memcpy(buf, data, (size_t)len); out.buf = buf; out.len = (size_t)len; }
    BIO_free(bio);
    return out;
}

/* Issue the leaf cert used as the *default* TLS identity for direct
   connections to this device (https://localhost/..., https://127.0.0.1/...,
   https://[::1]/...) — i.e. anything that doesn't send SNI for a blocked
   ad domain and so never reaches sni_callback()/make_domain_ctx().
   BUG FIX: this used to be the raw root CA cert itself (CN "AdAway Root
   CA", no SAN matching "localhost" or "127.0.0.1" at all). Any client
   that checks the presented cert's SAN against the hostname/IP it
   dialed — which is effectively all of them — would fail to validate
   it even though the CA is trusted, since the CA's own identity isn't
   a valid SAN for the server it's terminating TLS for. Sign a proper
   short-lived leaf cert for "localhost" with DNS + IPv4 loopback +
   IPv6 loopback SANs (the server also listens on ::1) and use *that*
   as the default, matching how every other hostname already gets a
   purpose-issued leaf cert via make_domain_ctx(). */
static int make_localhost_leaf(struct ca_state *ca, struct mg_tls_opts *out_opts) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    if (make_cert("localhost", ca->cert, ca->key, 0, 397,
                  "DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1", /*use_ec=*/0, /*rsa_bits=*/0, &cert, &key) != EXIT_SUCCESS)
        return EXIT_FAILURE;
    out_opts->cert = cert_to_pem_mgstr(cert);
    out_opts->key  = key_to_pem_mgstr(key);
    X509_free(cert);
    EVP_PKEY_free(key);
    return (out_opts->cert.buf && out_opts->key.buf) ? EXIT_SUCCESS : EXIT_FAILURE;
}

/* ── SNI per-domain certificate ───────────────────────────────── */

static SSL_CTX *make_domain_ctx(const char *hostname, struct ca_state *ca) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    if (make_cert(hostname, ca->cert, ca->key, 0, SNI_CERT_VALIDITY_DAYS,
                  NULL, /*use_ec=*/1, /*rsa_bits=*/0, &cert, &key) != EXIT_SUCCESS)
        return NULL;
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (!ctx) goto fail;
    /*
     * BUG FIX: SSL_CTX_add_extra_chain_cert() takes ownership of the
     * passed X509 (it bumps the refcount), but only when it succeeds.
     * The previous inline X509_dup(ca->cert) leaked that dup on the
     * (rare) failure path — use_certificate/use_PrivateKey short-circuit
     * before the dup runs, but if add_extra_chain_cert itself fails the
     * dup was allocated and nobody would ever free it. Track it
     * explicitly so every failure path releases it exactly once, while
     * the success path leaves ownership with the SSL_CTX.
     */
    X509 *chain = X509_dup(ca->cert);
    if (!chain) { SSL_CTX_free(ctx); ctx = NULL; goto fail; }
    if (SSL_CTX_use_certificate(ctx, cert) != 1 ||
        SSL_CTX_use_PrivateKey(ctx, key)   != 1) {
        X509_free(chain);
        SSL_CTX_free(ctx); ctx = NULL; goto fail;
    }
    if (SSL_CTX_add_extra_chain_cert(ctx, chain) != 1) {
        X509_free(chain);
        SSL_CTX_free(ctx); ctx = NULL; goto fail;
    }
fail:
    if (cert) X509_free(cert);
    if (key)  EVP_PKEY_free(key);
    return ctx;
}

/* SNI cache persistence — save/load hostname+issued_at so restarts
   don't flush the whole cache (maximises hit rate). */
static void sni_cache_save(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/sni_cache.dat", resource_dir);
    pthread_mutex_lock(&s_sni_mutex);
    struct sni_cache_file f;
    memset(&f, 0, sizeof(f));
    f.magic = SNI_CACHE_MAGIC;
    f.count = (uint32_t)(s_sni_pos < SNI_CACHE_SIZE ? s_sni_pos : SNI_CACHE_SIZE);
    for (uint32_t i = 0; i < f.count; i++) {
        strncpy(f.entries[i].hostname, s_sni_cache[i].hostname, 255);
        f.entries[i].hostname[255] = '\0';
        f.entries[i].issued_at = s_sni_cache[i].issued_at;
    }
    pthread_mutex_unlock(&s_sni_mutex);
    FILE *fp = fopen(path, "wb");
    if (fp) { fwrite(&f, sizeof(f), 1, fp); fclose(fp); }
}

static void sni_cache_load(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/sni_cache.dat", resource_dir);
    FILE *fp = fopen(path, "rb");
    if (!fp) return;
    struct sni_cache_file f;
    if (fread(&f, sizeof(f), 1, fp) == 1 && f.magic == SNI_CACHE_MAGIC) {
        pthread_mutex_lock(&s_sni_mutex);
        uint32_t n = f.count < SNI_CACHE_SIZE ? f.count : SNI_CACHE_SIZE;
        for (uint32_t i = 0; i < n; i++) {
            strncpy(s_sni_cache[i].hostname, f.entries[i].hostname, 255);
            s_sni_cache[i].hostname[255] = '\0';
            s_sni_cache[i].issued_at = f.entries[i].issued_at;
            s_sni_cache[i].ctx = NULL;  /* re-keyed lazily on next hit */
        }
        s_sni_pos = (uint64_t)n;
        pthread_mutex_unlock(&s_sni_mutex);
        LOG_INFO("SNI cache loaded: %u hostnames", n);
    }
    fclose(fp);
}

static int sni_callback(SSL *ssl, int *ad, void *arg) {
    (void)ad;  /* unused: required by OpenSSL callback signature */
    const char *host = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
    if (!host || strcmp(host, "localhost") == 0)
        return SSL_TLSEXT_ERR_OK;  /* keep default localhost cert */

    struct ca_state *ca = (struct ca_state *)arg;

    /*
     * NOTE (threading): Mongoose 7.x drives all connections from a
     * single mg_mgr_poll() event loop, so in this build the SNI
     * callback cannot actually run concurrently with itself. The mutex
     * is kept as cheap defensive code (see the cache definition above)
     * so this path stays correct if a threaded TLS dispatch is ever
     * introduced; it is not protecting against a real race today.
     *
     * BUG FIX (cert expiry): a cache hit is only reused while its cert
     * is still comfortably within validity (half of
     * SNI_CERT_VALIDITY_DAYS). Once past that, treat it as a miss so a
     * fresh cert gets issued — otherwise a long-running daemon would
     * keep serving expired certificates to returning clients.
     */

    /* Fast path: cache lookup under lock */
    int expired_slot = -1;
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++) {
        if (s_sni_cache[i].ctx && strcmp(s_sni_cache[i].hostname, host) == 0) {
            if (mg_millis() - s_sni_cache[i].issued_at < SNI_CERT_RENEW_MS) {
                SSL_set_SSL_CTX(ssl, s_sni_cache[i].ctx);
                pthread_mutex_unlock(&s_sni_mutex);
                s_stats.sni_cache_hits++;  /* avoid re-issuing */
                s_sni_hits++;
                return SSL_TLSEXT_ERR_OK;
            }
            /* Cert is near/at expiry: fall through and re-issue below.
               The stale entry stays until evicted by the insert (if
               that slot happens to be this one) - a later hit would simply
               find it expired again (audit D3: it is now replaced in place). */
            expired_slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&s_sni_mutex);

    /* Slow path: generate new cert outside the lock so we don't
       hold it through OpenSSL keygen (EC P-256 is fast, but still). */
    SSL_CTX *ctx = make_domain_ctx(host, ca);
    if (!ctx) {
        LOG_WARN("SNI: failed to create ctx for %s", host);
        return SSL_TLSEXT_ERR_NOACK;
    }

    /* Insert into cache under lock */
    pthread_mutex_lock(&s_sni_mutex);
    int pos;
    if (expired_slot >= 0) {
        pos = expired_slot;          /* refresh the expired entry in place (D3) */
    } else {
        pos = (int) (s_sni_pos % SNI_CACHE_SIZE);
    }
    if (s_sni_cache[pos].ctx) SSL_CTX_free(s_sni_cache[pos].ctx);
    strncpy(s_sni_cache[pos].hostname, host, 255);
    s_sni_cache[pos].issued_at = mg_millis();
    s_sni_cache[pos].hostname[255] = '\0';  /* BUG FIX: strncpy doesn't
        NUL-terminate when truncating; guarantee it here.  A hostname >255
        bytes would leave the buffer unterminated, causing strcmp() above to
        read past the array boundary on subsequent lookups. */
    s_sni_cache[pos].ctx = ctx;
    if (expired_slot < 0) s_sni_pos++;   /* an in-place refresh uses no new slot */
    s_sni_misses++;
    s_stats.sni_certs_issued++;
    hist_add(HIST_CERT);
    pthread_mutex_unlock(&s_sni_mutex);

    SSL_set_SSL_CTX(ssl, ctx);
    LOG_LOGCAT(ANDROID_LOG_DEBUG,
        "SNI: issued cert for %s (cache[%d])", host, pos);
    return SSL_TLSEXT_ERR_OK;
}

/* ── Connection counting ──────────────────────────────────────── */
/*
 * NOTE (threading): as with the SNI cache, Mongoose 7.x is a
 * single-threaded event loop, so this plain counter can never actually
 * tear in this build. The __atomic builtins below are kept as
 * defensive code (they compile to single instructions on ARM64 and
 * cost nothing) so the cap stays correct if a threaded dispatch is
 * ever introduced; they do not fix a real race today.
 */
static int s_active_connections = 0;

/* Helper: atomic fetch-and-add, returns the value *before* the add */
static inline int atomic_add_fetch(int *ptr, int val) {
    return __atomic_add_fetch(ptr, val, __ATOMIC_SEQ_CST);
}
static inline int atomic_sub_fetch(int *ptr, int val) {
    return __atomic_sub_fetch(ptr, val, __ATOMIC_SEQ_CST);
}
static inline int atomic_load(int *ptr) {
    return __atomic_load_n(ptr, __ATOMIC_SEQ_CST);
}

/* ── Blocked-request classification ───────────────────────────── */
/*
 * Classify a blocked request by its URI and reply with the most
 * realistic "empty" resource for that type, so ad SDKs think the
 * request succeeded (instead of retrying or erroring out).
 * Returns true if a reply was sent, false if the caller should fall
 * through to the default placeholder-image path (image requests keep
 * serving the user-configured block images).
 */
static bool uri_ends_with_ci(const struct mg_str uri, const char *suffix) {
    size_t n = strlen(suffix);
    if (uri.len < n) return false;
    return mg_strcasecmp(mg_str_n(uri.buf + uri.len - n, n), mg_str(suffix)) == 0;
}

static bool uri_contains_ci(const struct mg_str uri, const char *needle) {
    size_t n = strlen(needle);
    if (n == 0 || uri.len < n) return false;
    for (size_t i = 0; i + n <= uri.len; i++) {
        if (mg_strcasecmp(mg_str_n(uri.buf + i, n), mg_str(needle)) == 0) return true;
    }
    return false;
}

/* Minimal JSON integer reader (same style as jbool below). */
static int jint(const char *s, const char *k, int d) {
    char p[88];
    size_t n = strlen(k);
    if (n > 80) return d;
    p[0] = 0x22;
    memcpy(p + 1, k, n);
    p[1 + n] = 0x22;
    p[2 + n] = 0;
    const char *q = strstr(s, p);
    if (!q) return d;
    q += 2 + n;
    while (*q && *q != 0x3a) q++;
    if (*q != 0x3a) return d;
    q++;
    while (*q == 0x20 || *q == 0x09 || *q == 0x0a || *q == 0x0d) q++;
    if (*q < 0x30 || *q > 0x39) return d;
    return atoi(q);
}

/* Per-type blocking method: "mode_*" wins, the legacy reply_* boolean is the
   fallback (false = 204 deny, true = placeholder), default = placeholder. */
static void load_block_modes(const char *dir) {
    mode_images = cfg_images ? BM_REPLY : BM_DENY;
    mode_scripts = cfg_scripts ? BM_REPLY : BM_DENY;
    mode_styles = cfg_styles ? BM_REPLY : BM_DENY;
    mode_fonts = cfg_fonts ? BM_REPLY : BM_DENY;
    mode_media = cfg_media ? BM_REPLY : BM_DENY;
    mode_struct = cfg_struct ? BM_REPLY : BM_DENY;
    mode_api = cfg_api ? BM_REPLY : BM_DENY;
    mode_tele = cfg_tele ? BM_REPLY : BM_DENY;
    mode_conf = cfg_conf ? BM_REPLY : BM_DENY;
    mode_ws = cfg_ws ? BM_REPLY : BM_DENY;
    char path[512], b[4096];
    snprintf(path, sizeof(path), "%s/block_config.json", dir);
    FILE *f = fopen(path, "r");
    if (!f) return;
    size_t n = fread(b, 1, sizeof(b) - 1, f);
    b[n] = 0;
    fclose(f);
    int m;
    if ((m = jint(b, "mode_images", -1)) >= 0 && m <= BM_ALLOW) mode_images = m;
    if ((m = jint(b, "mode_scripts", -1)) >= 0 && m <= BM_ALLOW) mode_scripts = m;
    if ((m = jint(b, "mode_styles", -1)) >= 0 && m <= BM_ALLOW) mode_styles = m;
    if ((m = jint(b, "mode_fonts", -1)) >= 0 && m <= BM_ALLOW) mode_fonts = m;
    if ((m = jint(b, "mode_media", -1)) >= 0 && m <= BM_ALLOW) mode_media = m;
    if ((m = jint(b, "mode_struct", -1)) >= 0 && m <= BM_ALLOW) mode_struct = m;
    if ((m = jint(b, "mode_api", -1)) >= 0 && m <= BM_ALLOW) mode_api = m;
    if ((m = jint(b, "mode_tele", -1)) >= 0 && m <= BM_ALLOW) mode_tele = m;
    if ((m = jint(b, "mode_conf", -1)) >= 0 && m <= BM_ALLOW) mode_conf = m;
    if ((m = jint(b, "mode_ws", -1)) >= 0 && m <= BM_ALLOW) mode_ws = m;
    /* Keep the legacy booleans meaningful for anything else that reads them. */
    cfg_images = mode_images != BM_ALLOW;
    cfg_scripts = mode_scripts != BM_ALLOW;
    cfg_styles = mode_styles != BM_ALLOW;
    cfg_fonts = mode_fonts != BM_ALLOW;
    cfg_media = mode_media != BM_ALLOW;
    cfg_struct = mode_struct != BM_ALLOW;
    cfg_api = mode_api != BM_ALLOW;
    cfg_tele = mode_tele != BM_ALLOW;
    cfg_conf = mode_conf != BM_ALLOW;
    cfg_ws = mode_ws != BM_ALLOW;
    LOG_INFO("block modes: img=%d js=%d css=%d font=%d media=%d struct=%d api=%d tele=%d conf=%d ws=%d",
             mode_images, mode_scripts, mode_styles, mode_fonts, mode_media,
             mode_struct, mode_api, mode_tele, mode_conf, mode_ws);
}

/* Minimal JSON bool reader: looks for quoted key, then colon, then 0/1. */
static bool jbool(const char *s, const char *k, bool d) {
    char p[88];
    size_t n = strlen(k);
    if (n > 80) return d;
    p[0] = 0x22;
    memcpy(p + 1, k, n);
    p[1 + n] = 0x22;
    p[2 + n] = 0;
    const char *q = strstr(s, p);
    if (!q) return d;
    q += 2 + n;
    while (*q && *q != 0x3a) q++;
    if (*q != 0x3a) return d;
    q++;
    while (*q == 0x20 || *q == 0x09 || *q == 0x0a || *q == 0x0d) q++;
    if (*q == 0x30) return false;
    if (*q == 0x31) return true;
    return d;
}

static void load_block_cfg(const char *dir) {
    char path[512];
    snprintf(path, sizeof(path), "%s/block_config.json", dir);
    FILE *f = fopen(path, "r");
    if (!f) {
        cfg_images = true; cfg_scripts = true; cfg_styles = true; cfg_fonts = true;
        cfg_media = true; cfg_struct = true; cfg_api = true; cfg_tele = true;
        cfg_conf = true; cfg_ws = true;
        return;
    }
    char b[2048];
    size_t n = fread(b, 1, sizeof(b) - 1, f);
    b[n] = 0;
    fclose(f);
    cfg_images = jbool(b, "reply_images", true);
    cfg_scripts = jbool(b, "reply_scripts", true);
    cfg_styles = jbool(b, "reply_styles", true);
    cfg_fonts = jbool(b, "reply_fonts", true);
    cfg_media = jbool(b, "reply_media", true);
    cfg_struct = jbool(b, "reply_structures", true);
    cfg_api = jbool(b, "reply_api", true);
    cfg_tele = jbool(b, "reply_telemetry", true);
    cfg_conf = jbool(b, "reply_config", true);
    cfg_ws = jbool(b, "reply_ws_sse", true);
}

/* Fast 204 rejection used when a reply policy is turned OFF. */
static bool deny_quick(uint64_t *c, struct mg_connection *co) {
    (*c)++;
    char hdr[160];
    int hl = snprintf(hdr, sizeof(hdr),
        "Access-Control-Allow-Origin: *%c%cCache-Control: no-store%c%c",
        0x0d, 0x0a, 0x0d, 0x0a);
    (void) hl;
    mg_http_reply(co, 204, hdr, "");
    return true;
}

static bool reply_blocked_by_type(struct mg_connection *c, struct mg_http_message *hm) {
    struct mg_str u = hm->uri;

    /* Images & video thumbnails: fall through to the user-configured
       placeholder images (they're meant to be seen). */
    if (uri_ends_with_ci(u, ".jpg") || uri_ends_with_ci(u, ".jpeg") ||
        uri_ends_with_ci(u, ".png") || uri_ends_with_ci(u, ".gif") ||
        uri_ends_with_ci(u, ".webp") || uri_ends_with_ci(u, ".avif") ||
        uri_ends_with_ci(u, ".svg") || uri_ends_with_ci(u, ".ico") ||
        uri_ends_with_ci(u, ".bmp")) {
        if (mode_images == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_images == BM_DENY) return deny_quick(&s_stats.blocked_images, c);
        return false;
    }

    /* JavaScript: empty script body, HTTP 200. Cached - the empty
       response never changes at runtime, so the client stops
       re-requesting it after the first time (saves battery/bandwidth
       on every page load). */
    if (uri_ends_with_ci(u, ".js") || uri_ends_with_ci(u, ".mjs")) {
        if (mode_scripts == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_scripts == BM_DENY) return deny_quick(&s_stats.blocked_scripts, c);
        s_stats.blocked_scripts++;
        mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                              CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Stylesheets: empty CSS, HTTP 200. Cached like JS above. */
    if (uri_ends_with_ci(u, ".css")) {
        if (mode_styles == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_styles == BM_DENY) return deny_quick(&s_stats.blocked_styles, c);
        s_stats.blocked_styles++;
        mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                              CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Fonts: HTTP 204 (no content needed). */
    if (uri_ends_with_ci(u, ".woff") || uri_ends_with_ci(u, ".woff2") ||
        uri_ends_with_ci(u, ".ttf") || uri_ends_with_ci(u, ".otf") ||
        uri_ends_with_ci(u, ".eot")) {
        if (mode_fonts == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_fonts == BM_DENY) return deny_quick(&s_stats.blocked_fonts, c);
        s_stats.blocked_fonts++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Media & streams: video/audio players don't handle an image body
       gracefully (they may show a broken frame or try to probe it as a
       stream). A 204 (no content) is the closest thing to "stream
       ended / nothing to play" and keeps the player quiet. Covers
       MP4/WebM audio+video containers, HLS (.m3u8/.ts) and DASH
       (.mpd) manifests, and legacy formats. */
    if (uri_ends_with_ci(u, ".mp4") || uri_ends_with_ci(u, ".webm") ||
        uri_ends_with_ci(u, ".m4a") || uri_ends_with_ci(u, ".m4v") ||
        uri_ends_with_ci(u, ".mp3") || uri_ends_with_ci(u, ".aac") ||
        uri_ends_with_ci(u, ".ogg") || uri_ends_with_ci(u, ".oga") ||
        uri_ends_with_ci(u, ".opus") || uri_ends_with_ci(u, ".flac") ||
        uri_ends_with_ci(u, ".ts") || uri_ends_with_ci(u, ".m3u8") ||
        uri_ends_with_ci(u, ".mpd") || uri_ends_with_ci(u, ".flv") ||
        uri_ends_with_ci(u, ".mov") || uri_ends_with_ci(u, ".wav")) {
        if (mode_media == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_media == BM_DENY) return deny_quick(&s_stats.blocked_media, c);
        s_stats.blocked_media++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Structured/text assets that must look "loaded": source maps,
       WebAssembly, XML configs, manifests. Empty body, HTTP 200. */
    if (uri_ends_with_ci(u, ".xml") || uri_ends_with_ci(u, ".txt") ||
        uri_ends_with_ci(u, ".map") || uri_ends_with_ci(u, ".wasm") ||
        uri_ends_with_ci(u, ".webmanifest") || uri_ends_with_ci(u, ".jsonp")) {
        if (mode_struct == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_struct == BM_DENY) return deny_quick(&s_stats.blocked_other, c);
        s_stats.blocked_other++;
        mg_http_reply(c, 200, "Content-Type: application/octet-stream\r\n"
                              CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* ── Extended classification (new categories) ──────────────────
       Crypto-mining: known mining pool paths (stratum, worker, hash).
       Clickbait/trackers: generic tracker pixel & click-redirect paths.
       These get counted separately so the chart shows more detail. */
    if (uri_contains_ci(u, "/stratum") || uri_contains_ci(u, "/worker") ||
        uri_contains_ci(u, "/mining") || uri_contains_ci(u, "/hashrate") ||
        uri_contains_ci(u, "/pool")) {
        if (mode_struct == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_struct == BM_DENY) return deny_quick(&s_stats.blocked_crypto, c);
        s_stats.blocked_crypto++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }
    if (uri_contains_ci(u, "/click") || uri_contains_ci(u, "/track") ||
        uri_contains_ci(u, "/pixel") || uri_contains_ci(u, "/beacon") ||
        uri_contains_ci(u, "/impression")) {
        if (mode_tele == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_tele == BM_DENY) return deny_quick(&s_stats.blocked_clickbait, c);
        s_stats.blocked_clickbait++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* WebSocket upgrades: some SDKs open a WS channel to their ad
       gateway. Decline politely with 204 instead of serving an image. */
    struct mg_str *upgrade = mg_http_get_header(hm, "Upgrade");
    if (upgrade != NULL && mg_strcasecmp(*upgrade, mg_str("websocket")) == 0) {
        if (mode_ws == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_ws == BM_DENY) return deny_quick(&s_stats.blocked_ws_sse, c);
        s_stats.blocked_ws_sse++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Server-Sent Events: SDKs subscribing to an event stream get a
       clean "closed" stream (204) rather than a corrupt body. */
    struct mg_str *accept_hdr = mg_http_get_header(hm, "Accept");
    if (accept_hdr != NULL && uri_contains_ci(*accept_hdr, "text/event-stream")) {
        if (mode_ws == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_ws == BM_DENY) return deny_quick(&s_stats.blocked_ws_sse, c);
        s_stats.blocked_ws_sse++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: no-cache\r\n", "");
        return true;
    }

    /* No file extension - the browser's Sec-Fetch-Dest header is the
       most precise signal for what kind of resource this is (sent by
       all modern browsers, and more accurate than the URI for
       extension-less endpoints like /banner?id=123). AdGuard cannot
       annotate these requests itself - it redirects at the DNS layer,
       so the HTTP request never passes through it - but the browser
       already tells us the type. */
    struct mg_str *dest = mg_http_get_header(hm, "Sec-Fetch-Dest");
    if (dest != NULL && dest->len > 0) {
        if (mg_strcasecmp(*dest, mg_str("image")) == 0) {
        if (mode_images == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_images == BM_DENY) return deny_quick(&s_stats.blocked_images, c);
            return false;  /* image request without extension → placeholder image */
        }
        if (mg_strcasecmp(*dest, mg_str("script")) == 0) {
        if (mode_scripts == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_scripts == BM_DENY) return deny_quick(&s_stats.blocked_scripts, c);
            s_stats.blocked_scripts++;
            mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                                  CORS_HDR
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (mg_strcasecmp(*dest, mg_str("style")) == 0) {
        if (mode_styles == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_styles == BM_DENY) return deny_quick(&s_stats.blocked_styles, c);
            s_stats.blocked_styles++;
            mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                                  CORS_HDR
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (mg_strcasecmp(*dest, mg_str("font")) == 0) {
        if (mode_fonts == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_fonts == BM_DENY) return deny_quick(&s_stats.blocked_fonts, c);
            s_stats.blocked_fonts++;
            mg_http_reply(c, 204, CORS_HDR
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        /* "empty" (XHR/fetch/beacon), "document", others: fall through
           to path-keyword matching below. */
    }

    /* Ad API endpoints: empty JSON object, HTTP 200. */
    if (uri_ends_with_ci(u, ".json") ||
        uri_contains_ci(u, "/ad") || uri_contains_ci(u, "/ads") ||
        uri_contains_ci(u, "/banner") || uri_contains_ci(u, "/feed") ||
        uri_contains_ci(u, "/recommend")) {
        if (mode_api == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_api == BM_DENY) return deny_quick(&s_stats.blocked_api, c);
        s_stats.blocked_api++;
        mg_http_reply(c, 200, "Content-Type: application/json\r\n"
                              CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "{}");
        return true;
    }

    /* Telemetry / analytics endpoints: HTTP 204. */
    if (uri_contains_ci(u, "/track") || uri_contains_ci(u, "/event") ||
        uri_contains_ci(u, "/log") || uri_contains_ci(u, "/collect") ||
        uri_contains_ci(u, "/pixel")) {
        if (mode_tele == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_tele == BM_DENY) return deny_quick(&s_stats.blocked_telemetry, c);
        s_stats.blocked_telemetry++;
        mg_http_reply(c, 204, CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Heartbeats & connectivity probes: HTTP 204. /generate_204 and
       /204 are used by YouTube/Google to probe connectivity - they
       must return 204 (empty body) or the client thinks the network
       is broken. Also covers vendor-specific probes:
       /gen_204, /generate_204.php, /connecttest.txt (Windows),
       /hotspot-detect.html (iOS/macOS).
       CMCC/China Mobile: /wlan/userip, /wlan/ac_portal, /wlan/login,
       /portal/*, /eportal/*, /cmcc/* */
    if (uri_contains_ci(u, "/ping") || uri_contains_ci(u, "/heartbeat") ||
        uri_contains_ci(u, "/generate_204") || uri_contains_ci(u, "/204") ||
        uri_contains_ci(u, "/gen_204") || uri_contains_ci(u, "/generate_204.php") ||
        uri_contains_ci(u, "/connecttest.txt") || uri_contains_ci(u, "/hotspot-detect.html") ||
        uri_contains_ci(u, "/wlan/userip") || uri_contains_ci(u, "/wlan/ac_portal") ||
        uri_contains_ci(u, "/wlan/login") || uri_contains_ci(u, "/portal/") ||
        uri_contains_ci(u, "/eportal/") || uri_contains_ci(u, "/cmcc/")) {
        if (mode_tele == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_tele == BM_DENY) return deny_quick(&s_stats.blocked_heartbeat, c);
        s_stats.blocked_heartbeat++;
        mg_http_reply(c, 204, CORS_HDR
                      "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Config endpoints: empty JSON, HTTP 200. */
    if (uri_contains_ci(u, "/config") || uri_contains_ci(u, "/settings")) {
        if (mode_conf == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_conf == BM_DENY) return deny_quick(&s_stats.blocked_config, c);
        s_stats.blocked_config++;
        mg_http_reply(c, 200, "Content-Type: application/json\r\n"
                              CORS_HDR
                              "Cache-Control: public, max-age=86400\r\n", "{}");
        return true;
    }

    /* No Sec-Fetch-Dest header (older clients) - fall back to the
       Accept header, which browsers still send for every request:
       image/* → placeholder image, text/css → empty CSS,
       application/javascript → empty JS. This is the last chance to
       classify before the generic placeholder-image fallback. */
    struct mg_str *accept = mg_http_get_header(hm, "Accept");
    if (accept != NULL && accept->len > 0) {
        if (uri_contains_ci(*accept, "image/") ||
            uri_contains_ci(*accept, "image/*")) {
        if (mode_images == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_images == BM_DENY) return deny_quick(&s_stats.blocked_images, c);
            return false;  /* image request → placeholder image */
        }
        if (uri_contains_ci(*accept, "text/css")) {
        if (mode_styles == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_styles == BM_DENY) return deny_quick(&s_stats.blocked_styles, c);
        s_stats.blocked_styles++;
            mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                                  CORS_HDR
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (uri_contains_ci(*accept, "application/javascript") ||
            uri_contains_ci(*accept, "text/javascript")) {
        if (mode_scripts == BM_ALLOW) { s_rb_allow = true; return true; }
        if (mode_scripts == BM_DENY) return deny_quick(&s_stats.blocked_scripts, c);
        s_stats.blocked_scripts++;
            mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                                  CORS_HDR
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
    }

    /* Unknown request type: fall through to the placeholder image
       (better than a bare 204 - the user sees the block placeholder
       instead of a blank/broken slot, and it costs nothing extra). */
    return false;
}

/* ── HTTP event handler ───────────────────────────────────────── */

/* Forward declaration (ws_push_broadcast below calls it). */
/* ── listener registry ───────────────────────────────────────────
 * Records every listen attempt so the dashboard can show, in-app, which
 * endpoints are actually bound (and which one failed and why). Exposed as
 * "listeners" in /internal-stats (appended field, old keys unchanged). */
#define LISTENER_MAX 12
struct listener_state {
    char name[16];
    char addr[80];
    bool bound;
    bool ipv6;
    bool loopback;
};
static struct listener_state s_listeners[LISTENER_MAX];
static int  s_listener_count = 0;
static bool s_main_http_bound = false;

static void listener_record(const char *name, const char *url, bool bound) {
    if (s_listener_count >= LISTENER_MAX) return;
    struct listener_state *l = &s_listeners[s_listener_count++];
    snprintf(l->name, sizeof(l->name), "%s", name);
    snprintf(l->addr, sizeof(l->addr), "%s", url);
    l->bound = bound;
    l->ipv6 = (strchr(url, '[') != NULL);
    l->loopback = (strstr(url, "127.0.0.1") != NULL || strstr(url, "[::1]") != NULL);
    if (bound) LOG_INFO("listening: %s (%s)", url, name);
    else       LOG_WARN("NOT listening: %s (%s)", url, name);
}

/* Bind with a few retries: an instance that was just closed can leave the
   port in TIME_WAIT, where the first bind fails even though nothing is
   listening any more. */
static struct mg_connection *bind_try(struct mg_mgr *mgr, const char *url,
                                      mg_event_handler_t handler, void *fn_data,
                                      const char *name) {
    struct mg_connection *c = NULL;
    for (int attempt = 1; attempt <= 3; attempt++) {
        c = mg_http_listen(mgr, url, handler, fn_data);
        if (c != NULL) break;
        if (attempt < 3) {
            LOG_WARN("bind %s failed (attempt %d/3), retrying", url, attempt);
#ifdef _WIN32
            Sleep(400);
#else
            usleep(400 * 1000);
#endif
        }
    }
    listener_record(name, url, c != NULL);
    return c;
}

#ifdef _WIN32
/* Is another instance already running? Ask the loopback MANAGEMENT port and
   require our own JSON back, so a foreign process squatting on the user facing
   HTTP port is never mistaken for a running ADBlock instance. */
static bool adblock_instance_running(int stats_port) {
    if (stats_port <= 0) return false;
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) return false;
    SOCKADDR_IN a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons((u_short)stats_port);
    a.sin_addr.s_addr = inet_addr("127.0.0.1");
    bool found = false;
    if (connect(sock, (SOCKADDR *)&a, sizeof(a)) == 0) {
        const char *req = "GET /internal-stats HTTP/1.0\r\nHost: 127.0.0.1\r\n"
                          "Connection: close\r\n\r\n";
        if (send(sock, req, (int)strlen(req), 0) > 0) {
            char buf[512];
            int n = recv(sock, buf, sizeof(buf) - 1, 0);
            if (n > 0) {
                buf[n] = '\0';
                if (strstr(buf, "\"uptime_seconds\"") && strstr(buf, "\"total_requests\""))
                    found = true;
            }
        }
    }
    closesocket(sock);
    return found;
}
#endif

static int build_stats_json(struct settings *s, char *out, size_t out_sz);

/* WebSocket push subscribers: connections that upgraded to /internal-ws.
   Registered on MG_EV_WS_OPEN, removed on MG_EV_CLOSE; broadcast after
   every counted request so clients get real-time updates. */
#define WS_PUSH_MAX 16
static struct mg_connection *ws_clients[WS_PUSH_MAX] = {0};


/*
 * Broadcast the current stats snapshot to every registered WebSocket
 * subscriber. Call after each request is counted (blocked or served).
 * Must not be called from inside mg_mgr_poll() while holding locks that
 * the event handler also takes — we only read the pointer array and
 * mg_ws_send() is async (appends to c->send), safe from the event loop.
 */
static void ws_push_broadcast(struct settings *s) {
    if (!s || !s->init) return;
    char body[16384];
    int n = build_stats_json(s, body, sizeof(body));
    if (n <= 0) return;
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < WS_PUSH_MAX; i++) {
        struct mg_connection *cl = ws_clients[i];
        if (cl && !cl->is_closing && !cl->is_draining) {
            mg_ws_send(cl, body, (size_t)n, WEBSOCKET_OP_TEXT);
        }
    }
    pthread_mutex_unlock(&s_sni_mutex);
}

/*
 * Build the /internal-stats JSON snapshot into out[]. Shared by the
 * HTTP endpoint and (optionally) the WebSocket push so both always
 * serve identical data. Adds derived metrics:
 *   - block_rate:      blocked/total as percent (0-100)
 *   - sni_hit_rate:    SNI cache hits / handshakes as percent
 *   - uptime_days:     uptime in days (for long-running daemons)
 *   - daily_peak:      max blocked in any single daily bucket
 */
static int build_stats_json(struct settings *s, char *out, size_t out_sz) {
    char apps_json[4096] = "";
    /* static: a few KB that must never sit on the 16 KB event-loop stack */
    static char qlog_json[QLOG_JSON_MAX];
    qlog_render(qlog_json, sizeof(qlog_json));
    int off = 0;
    for (int i = 0; i < s_app_count && off < (int)sizeof(apps_json) - 96; i++) {
        int n = snprintf(apps_json + off, sizeof(apps_json) - (size_t)off,
            "%s{\"uid\":%d,\"connections\":%llu,\"requests\":%llu,"
            "\"blocked\":%llu,\"tls_hosts\":%llu}",
            off ? "," : "",
            (int)s_apps[i].uid,
            (unsigned long long)s_apps[i].connections,
            (unsigned long long)s_apps[i].requests,
            (unsigned long long)s_apps[i].blocked,
            (unsigned long long)s_apps[i].tls_hosts);
        if (n > 0) off += n;
    }
    char tls_json[2048] = "";
    off = 0;
    int total = s_tls_count < RECENT_TLS_MAX ? s_tls_count : RECENT_TLS_MAX;
    int start = (s_tls_pos - total + RECENT_TLS_MAX) % RECENT_TLS_MAX;
    for (int k = 0; k < total && k < 20 && off < (int)sizeof(tls_json) - 256; k++) {
        struct tls_host_rec *r = &s_recent_tls[(start + k) % RECENT_TLS_MAX];
        if (!r->host[0]) continue;
        int n = snprintf(tls_json + off, sizeof(tls_json) - (size_t)off,
            "%s{\"uid\":%d,\"host\":\"%s\"}",
            off ? "," : "", (int)r->uid, r->host);
        if (n > 0) off += n;
    }
    char hist_json[4096] = "";
    buckets_to_json(hist_json, sizeof(hist_json),
                    s_hist, HIST_SLOTS, s_hist_pos,
                    s_hist_slot_start, HIST_INTERVAL_S, 24);
    char daily_json[6144] = "";
    buckets_to_json(daily_json, sizeof(daily_json),
                    s_daily, DAILY_SLOTS, s_daily_pos,
                    s_daily_slot_start, DAILY_INTERVAL_S, 30);
    char listeners_json[1024] = "";
    {
        int loff = 0;
        for (int i = 0; i < s_listener_count; i++) {
            int n = snprintf(listeners_json + loff,
                             sizeof(listeners_json) - (size_t)loff,
                             "%s{\"name\":\"%s\",\"addr\":\"%s\",\"bound\":%s,"
                             "\"ipv6\":%s,\"loopback\":%s}",
                             loff ? "," : "",
                             s_listeners[i].name, s_listeners[i].addr,
                             s_listeners[i].bound ? "true" : "false",
                             s_listeners[i].ipv6 ? "true" : "false",
                             s_listeners[i].loopback ? "true" : "false");
            if (n <= 0 || loff + n >= (int)sizeof(listeners_json)) break;
            loff += n;
        }
    }

    uint64_t uptime = uptime_seconds();
    uint64_t req = s_stats.total_requests;
    uint64_t blk = stats_total_blocked();
    uint64_t hs  = s_stats.tls_handshakes;
    uint64_t hits = s_stats.sni_cache_hits;
    if (blk > req) blk = req;   /* lifetime counters can be inconsistent */
    double block_rate = req > 0 ? (double)blk * 100.0 / (double)req : 0.0;
    double hit_rate   = hs  > 0 ? (double)hits * 100.0 / (double)hs  : 0.0;
    uint64_t daily_peak = 0;
    for (int i = 0; i < DAILY_SLOTS; i++)
        if (s_daily[i].blocked > daily_peak) daily_peak = s_daily[i].blocked;

    /* SECURITY/robustness: snprintf returns the WOULD-BE length, which callers
       ("%.*s", mg_ws_send, fwrite) would happily use - clamp it to the buffer. */
    {
        int n = snprintf(out, out_sz,
        "{\"uptime_seconds\":%llu,"
        "\"uptime_days\":%.1f,"
        "\"total_requests\":%llu,"
        "\"total_connections\":%llu,"
        "\"active_connections\":%d,"
        "\"tls_handshakes\":%llu,"
        "\"tls_failures\":%llu,"
        "\"sni_cache_hits\":%llu,"
        "\"sni_hit_rate\":%.1f,"
        "\"block_rate\":%.1f,"
        "\"total_blocked\":%llu,"
        "\"daily_peak\":%llu,"
        "\"blocked_images\":%llu,"
        "\"blocked_scripts\":%llu,"
        "\"blocked_styles\":%llu,"
        "\"blocked_fonts\":%llu,"
        "\"blocked_media\":%llu,"
        "\"blocked_api\":%llu,"
        "\"blocked_telemetry\":%llu,"
        "\"blocked_heartbeat\":%llu,"
        "\"blocked_config\":%llu,"
        "\"blocked_ws_sse\":%llu,"
        "\"blocked_other\":%llu,"
        "\"blocked_crypto\":%llu,"
        "\"blocked_clickbait\":%llu,"
        "\"sni_certs_issued\":%llu,"
        "\"block_image_count\":%d,"
        "\"stats_port\":%d,"
        "\"bind_ok\":%s,"
        "\"listeners\":[%s],"
        "\"apps\":[%s],"
        "\"recent_tls\":[%s],"
        "\"history\":[%s],"
        "\"daily\":[%s],"
        "\"query_log\":[%s]}",
        (unsigned long long)uptime,
        (double)uptime / 86400.0,
        (unsigned long long)req,
        (unsigned long long)s_stats.total_connections,
        atomic_load(&s_active_connections),
        (unsigned long long)hs,
        (unsigned long long)s_stats.tls_failures,
        (unsigned long long)hits,
        hit_rate,
        block_rate,
        (unsigned long long)blk,
        (unsigned long long)daily_peak,
        (unsigned long long)s_stats.blocked_images,
        (unsigned long long)s_stats.blocked_scripts,
        (unsigned long long)s_stats.blocked_styles,
        (unsigned long long)s_stats.blocked_fonts,
        (unsigned long long)s_stats.blocked_media,
        (unsigned long long)s_stats.blocked_api,
        (unsigned long long)s_stats.blocked_telemetry,
        (unsigned long long)s_stats.blocked_heartbeat,
        (unsigned long long)s_stats.blocked_config,
        (unsigned long long)s_stats.blocked_ws_sse,
        (unsigned long long)s_stats.blocked_other,
        (unsigned long long)s_stats.blocked_crypto,
        (unsigned long long)s_stats.blocked_clickbait,
        (unsigned long long)s_stats.sni_certs_issued,
        s->block_image_count,
        s->stats_port,
        s_main_http_bound ? "true" : "false",
        listeners_json,
        apps_json, tls_json, hist_json, daily_json, qlog_json);
        if (n < 0) return 0;
        return n < (int) out_sz ? n : (int) out_sz - 1;
    }
}

/* ── Transparent filtering proxy (hijack mode) ───────────────────
 *
 * In "hijack" mode the Android app installs iptables REDIRECT rules so
 * that the whole TCP 80/443 traffic of the device lands on these very
 * listeners (the HTTPS side is already terminated with a per-domain
 * certificate issued by our CA, so the request is readable here). Every
 * request for a host that is NOT in the block list is forwarded to the
 * real origin server and the answer is filtered on the way back:
 *
 *   - HTML: external <script>/<iframe>/<link>/<img>/... tags whose host
 *     is blocked are removed and a small element-hiding stylesheet is
 *     injected (AdGuard-like cosmetic filtering);
 *   - every other response is streamed through untouched;
 *   - hosts that ARE in the block list keep the usual local placeholder
 *     replies (reply_blocked_by_type), i.e. requests stay blocked.
 *
 * The block list is the system hosts file (that is what actually blocks
 * on Android, written by the app), so it is always in sync with the
 * app's rules. Only enabled with --proxy-filter.
 */
/* Concurrent proxied requests. Each in-flight request may hold a response
   buffer, so this bounds the worst-case memory of the hijack mode. */
#define PROXY_MAX 64
/* Only pages up to this size are buffered for rewriting; bigger responses are
   streamed through untouched, which keeps the peak memory small
   (worst case: PROXY_MAX x PROXY_BUF_MAX). */
#define PROXY_BUF_MAX (512u * 1024u)
#define PROXY_TIMEOUT_MS 60000u
#define PROXY_CONNECT_TIMEOUT_MS 15000u

struct proxy_state {
    struct mg_connection *client;
    struct mg_connection *up;
    char       host[256];
    int        port;
    bool       tls;
    char      *req;
    size_t     req_len;
    char      *buf;
    size_t     buf_len;
    size_t     buf_cap;
    size_t     head_len;      /* response header block length in buf */
    bool       headers_done;
    bool       filter_body;   /* HTML: buffer + rewrite */
    bool       replied;
    bool       connected;
    uint64_t   started_ms;
};

static struct mg_mgr *s_mgr;
static struct proxy_state *s_proxies[PROXY_MAX];

/* Number of in-flight proxy requests: lets the (very hot) poll path skip the
   lookup entirely while no proxy request is active. */
static int s_proxy_active;

/* ── blocked-host table (64-bit FNV-1a hashes, open addressing) ──
 * Grown on demand (512 KB at first, doubling up to 16 MB) so the common case
 * of a few thousand rules costs almost no memory.
 */
#define BLOCK_HASH_MIN_SLOTS (1u << 16)
#define BLOCK_HASH_MAX_SLOTS (1u << 21)
static uint64_t *s_block_slots;
static size_t s_block_cap;    /* allocated slots (power of two, 0 = none) */
static size_t s_block_used;
static bool s_block_grow(void);

static uint64_t fnv1a_lower(const char *s, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) s[i];
        if (c >= 'A' && c <= 'Z') c = (unsigned char) (c + 32);
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h ? h : 1;
}

/* Double the table (rehashing in place); keeps the load factor below 50%. */
static bool s_block_grow(void) {
    size_t newCap = s_block_cap ? s_block_cap * 2 : BLOCK_HASH_MIN_SLOTS;
    if (newCap > BLOCK_HASH_MAX_SLOTS) return false;
    uint64_t *slots = (uint64_t *) calloc(newCap, sizeof(uint64_t));
    if (!slots) return false;
    for (size_t i = 0; i < s_block_cap; i++) {
        uint64_t h = s_block_slots ? s_block_slots[i] : 0;
        if (!h) continue;
        size_t k = (size_t) (h & (newCap - 1));
        while (slots[k]) k = (k + 1) & (newCap - 1);
        slots[k] = h;
    }
    free(s_block_slots);
    s_block_slots = slots;
    s_block_cap = newCap;
    return true;
}

static void block_set_add_hash(uint64_t h) {
    if (!s_block_slots || s_block_used * 2 >= s_block_cap) {
        if (!s_block_grow() && !s_block_slots) return;
    }
    size_t i = (size_t) (h & (s_block_cap - 1));
    for (size_t probe = 0; probe < 128; probe++) {
        size_t k = (i + probe) & (s_block_cap - 1);
        if (s_block_slots[k] == 0) { s_block_slots[k] = h; s_block_used++; return; }
        if (s_block_slots[k] == h) return;
    }
}

static bool block_set_contains(const char *host, size_t len) {
    if (!s_block_slots || len == 0 || len > 255) return false;
    uint64_t h = fnv1a_lower(host, len);
    size_t i = (size_t) (h & (s_block_cap - 1));
    for (size_t probe = 0; probe < 128; probe++) {
        size_t k = (i + probe) & (s_block_cap - 1);
        if (s_block_slots[k] == 0) return false;
        if (s_block_slots[k] == h) return true;
    }
    return false;
}

#ifndef _WIN32
/* Parse one rules file ("<ip> <host>") and return the number of entries added. */
static size_t block_set_load_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[512];
    size_t n = 0;
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\n' || *p == '\r' || *p == '\0') continue;
        char *ip = p;
        while (*p && *p != ' ' && *p != '\t') p++;
        if (!*p) continue;
        *p++ = '\0';
        if (strcmp(ip, "127.0.0.1") != 0 && strcmp(ip, "0.0.0.0") != 0 &&
            strcmp(ip, "::1") != 0 && strcmp(ip, "::") != 0) continue;
        while (*p == ' ' || *p == '\t') p++;
        char *h = p;
        while (*p && *p != ' ' && *p != '\t' && *p != '\n' && *p != '\r') p++;
        *p = '\0';
        if (*h) { block_set_add_hash(fnv1a_lower(h, strlen(h))); n++; }
    }
    fclose(f);
    return n;
}
#endif

/*
 * Load the blocked hosts from the rules file the app exports
 * (<resource>/blocklist.txt) and fall back to the system hosts file when it is
 * missing OR empty - the export is written before the first sync, and an empty
 * rule set must never silently disable filtering. Only entries pointing at
 * 127.0.0.1, 0.0.0.0, ::1 or :: count as blocked.
 */
/* Extra rule source: <resources>/subscriptions.txt (one domain per line,
   "0.0.0.0 domain" / "||domain^" accepted, # and ! comments skipped). The
   Windows dashboard fetches the URLs and rewrites this file; the Android app
   writes it from its rule sources. Loaded first so blocklist.txt still wins
   on duplicates. */
static void block_set_load_subscriptions(const char *resource_dir) {
    if (!resource_dir || !resource_dir[0]) return;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/subscriptions.txt", resource_dir);
    FILE *fp = fopen(path, "r");
    if (!fp) return;
    /* Self-contained parser: block_set_load_file() is platform-specific and is
       not visible in every build, and the subscription file only ever holds
       one domain per line. */
    char line[512];
    size_t n = 0;
    while (fgets(line, sizeof(line), fp)) {
        char *s = line;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == 0 || *s == '#' || *s == '!' || *s == '\r' || *s == '\n') continue;
        if (s[0] == '|' && s[1] == '|') {
            s += 2;                                   /* AdGuard/ABP: ||domain^ */
        } else {
            char *sp = strpbrk(s, " \t");            /* hosts: 0.0.0.0 domain */
            if (sp) { while (*sp == ' ' || *sp == '\t') sp++; s = sp; }
        }
        char *end = s;
        while (*end && *end != ' ' && *end != '\t' && *end != '\r' && *end != '\n' &&
               *end != '^' && *end != '/' && *end != '

static void block_set_load(const char *resource_dir) {
    block_set_load_subscriptions(resource_dir);
    free(s_block_slots);
    s_block_slots = NULL;
    s_block_cap = 0;
    s_block_used = 0;
#ifndef _WIN32
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/blocklist.txt", resource_dir);
    size_t n = block_set_load_file(path);
    if (n == 0) {
        snprintf(path, sizeof(path), "/system/etc/hosts");
        n = block_set_load_file(path);
    }
    LOG_INFO("proxy: %zu blocked hosts loaded from %s", n, path);
#else
    (void) resource_dir;
#endif
}

static bool host_is_local(const char *h) {
    if (!h || !*h) return true;
    if (strcasecmp(h, "localhost") == 0) return true;
    if (strcasecmp(h, "localhost.localdomain") == 0) return true;
    if (strncmp(h, "127.", 4) == 0) return true;
    if (strcmp(h, "::1") == 0) return true;
    if (strncmp(h, "::ffff:127.", 11) == 0) return true;
    return false;
}

/* Element-hiding stylesheet injected into proxied HTML pages. */
static const char kHideCss[] =
    "<style id=\"adblock-hide\">"
    ".adsbygoogle,ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],"
    "[class^=\"ad-\"],[class^=\"ads-\"],[class^=\"advert\"],[id^=\"ad-\"],[id^=\"ads-\"],"
    "[class*=\" ad-\"],[class*=\" ads-\"],[data-ad-slot],[data-ad-client],"
    "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],"
    "iframe[src*=\"adsystem\"],[class$=\"-ad\"],[class$=\"-ads\"]"
    "{display:none!important;visibility:hidden!important}"
    "</style>";

/* Extract the host of an URL and test it against the block list. */
static bool url_host_blocked(const char *u, size_t n) {
    size_t i = 0;
    while (i < n && (u[i] == ' ' || u[i] == '\t')) i++;
    if (i + 1 < n && u[i] == '/' && u[i + 1] == '/') {
        i += 2;
    } else {
        size_t s = i;
        while (i < n && u[i] != ':' && u[i] != '/' && u[i] != '?' && u[i] != '#') i++;
        if (i < n && u[i] == ':') {
            i++;
            while (i < n && u[i] == '/') i++;
        } else {
            i = s;
        }
    }
    size_t start = i;
    while (i < n && u[i] != '/' && u[i] != ':' && u[i] != '?' && u[i] != '#' &&
           u[i] != '"' && u[i] != '\'' && u[i] != ' ' && u[i] != '\t') i++;
    size_t len = i - start;
    for (size_t k = start; k < start + len; k++) {
        if (u[k] == '@') { len -= (k - start + 1); start = k + 1; break; }
    }
    return len > 0 && block_set_contains(u + start, len);
}

/*
 * Rewrite an HTML body: drop external resource tags that point at a
 * blocked host and inject the element-hiding stylesheet after <head>.
 * Returns the new length (may exceed the input by the stylesheet size).
 */
/*
 * Cosmetic filtering: the app exports the AdGuard/adblock element hiding
 * rules (##selector) it collected from the sources to <resource>/cosmetic.css;
 * they are injected into every filtered page next to the built-in stylesheet.
 */
static char  *s_cosmetic_css;
static size_t s_cosmetic_len;

static void load_cosmetic_css(const char *resource_dir) {
    free(s_cosmetic_css);
    s_cosmetic_css = NULL;
    s_cosmetic_len = 0;
#ifndef _WIN32
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/cosmetic.css", resource_dir);
    FILE *f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > (long) (512 * 1024)) { fclose(f); return; }
    char *buffer = (char *) malloc((size_t) size + 1);
    if (!buffer) { fclose(f); return; }
    size_t got = fread(buffer, 1, (size_t) size, f);
    fclose(f);
    buffer[got] = '\0';
    s_cosmetic_css = buffer;
    s_cosmetic_len = got;
    LOG_INFO("cosmetic filter: %zu bytes loaded from %s", got, path);
#else
    (void) resource_dir;
#endif
}

static size_t html_filter(const char *in, size_t n, char *out, size_t cap) {
    size_t i = 0, o = 0;
    bool injected = false;
#define AB_PUT(ch) do { if (o < cap) out[o] = (char) (ch); o++; } while (0)
    while (i < n) {
        if (in[i] != '<') { AB_PUT(in[i]); i++; continue; }
        size_t j = i + 1;
        bool closing = false;
        if (j < n && in[j] == '/') { closing = true; j++; }
        size_t name_at = j;
        while (j < n && ((in[j] >= 'a' && in[j] <= 'z') || (in[j] >= 'A' && in[j] <= 'Z'))) j++;
        size_t name_len = j - name_at;
        if (name_len == 0) { AB_PUT(in[i]); i++; continue; }
        size_t k = j;
        bool inq = false;
        char q = 0;
        while (k < n) {
            char ch = in[k];
            if (inq) { if (ch == q) inq = false; }
            else if (ch == '"' || ch == '\'') { inq = true; q = ch; }
            else if (ch == '>') break;
            k++;
        }
        size_t tag_end = (k < n) ? k + 1 : n;
        if (!closing) {
            static const char *kRes[] = {"script", "iframe", "img", "link", "embed",
                                         "object", "source", "ins", "video", "audio", NULL};
            bool resource = false;
            for (int t = 0; kRes[t]; t++) {
                if (name_len == strlen(kRes[t]) &&
                    strncasecmp(in + name_at, kRes[t], name_len) == 0) { resource = true; break; }
            }
            if (resource) {
                static const char *kAttrs[] = {"src", "href", "data-src", "data-original", "poster", NULL};
                bool blocked = false;
                for (int t = 0; kAttrs[t] && !blocked; t++) {
                    size_t alen = strlen(kAttrs[t]);
                    for (size_t p = j; p + alen < tag_end; p++) {
                        if (strncasecmp(in + p, kAttrs[t], alen) != 0) continue;
                        if (p > j && in[p - 1] != ' ' && in[p - 1] != '\t' && in[p - 1] != '\n' && in[p - 1] != '\r') continue;
                        size_t v = p + alen;
                        while (v < tag_end && (in[v] == ' ' || in[v] == '\t')) v++;
                        if (v >= tag_end || in[v] != '=') continue;
                        v++;
                        while (v < tag_end && (in[v] == ' ' || in[v] == '\t')) v++;
                        char quote = 0;
                        if (v < tag_end && (in[v] == '"' || in[v] == '\'')) { quote = in[v]; v++; }
                        size_t vs = v;
                        while (v < tag_end && (quote ? in[v] != quote
                                                     : (in[v] != ' ' && in[v] != '>' && in[v] != '\t' && in[v] != '\n'))) v++;
                        if (url_host_blocked(in + vs, v - vs)) { blocked = true; break; }
                    }
                }
                if (blocked) { i = tag_end; continue; }   /* drop the whole tag */
            }
            if (!injected && name_len == 4 && strncasecmp(in + name_at, "head", 4) == 0) {
                for (size_t p = i; p < tag_end; p++) AB_PUT(in[p]);
                for (size_t p = 0; p < sizeof(kHideCss) - 1; p++) AB_PUT(kHideCss[p]);
                for (size_t p = 0; p < s_cosmetic_len; p++) AB_PUT(s_cosmetic_css[p]);
                injected = true;
                i = tag_end;
                continue;
            }
        }
        for (size_t p = i; p < tag_end; p++) AB_PUT(in[p]);
        i = tag_end;
    }
    if (!injected) {
        for (size_t p = 0; p < sizeof(kHideCss) - 1; p++) AB_PUT(kHideCss[p]);
        for (size_t p = 0; p < s_cosmetic_len; p++) AB_PUT(s_cosmetic_css[p]);
    }
#undef AB_PUT
    return o;
}

/*
 * Persist the very same JSON snapshot the app polls from /internal-stats, so
 * the statistics screens keep working when no HTTP port can be reached at all
 * (30xx port taken, server not running for a moment, ...).
 */
static void write_stats_json_file(struct settings *s) {
    if (!s || !s->init || !s->resource_dir[0]) return;
    static char body[16384];
    int n = build_stats_json(s, body, sizeof(body));
    if (n <= 0) return;
    char path[PATH_MAX], tmp[PATH_MAX];
    snprintf(path, sizeof(path), "%s/stats.json", s->resource_dir);
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    FILE *fp = fopen(tmp, "wb");
    if (!fp) return;
    fwrite(body, 1, (size_t) n, fp);
    fclose(fp);
    remove(path);
    rename(tmp, path);
}

/* ── proxy plumbing ── */
static void proxy_state_free(struct proxy_state *st) {
    if (!st) return;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] == st) { s_proxies[i] = NULL; s_proxy_active--; break; }
    }
    if (s_proxy_active < 0) s_proxy_active = 0;
    free(st->req);
    free(st->buf);
    free(st);
}

static void proxy_drop_for_client(struct mg_connection *c) {
    for (int i = 0; i < PROXY_MAX; i++) {
        struct proxy_state *st = s_proxies[i];
        if (st && st->client == c) {
            st->client = NULL;
            if (st->up) st->up->is_closing = 1;
        }
    }
}

static void proxy_send_raw(struct proxy_state *st, const char *data, size_t n) {
    if (st->client && n) mg_send(st->client, data, n);
}

static void proxy_fail(struct proxy_state *st, int code, const char *msg) {
    if (st->replied || !st->client) return;
    char head[320];
    int n = snprintf(head, sizeof(head),
                     "HTTP/1.1 %d %s\r\nContent-Type: text/plain; charset=utf-8\r\n"
                     "Content-Length: %u\r\nConnection: close\r\n\r\n%s",
                     code, msg, (unsigned) strlen(msg), msg);
    if (n > 0) mg_send(st->client, head, (size_t) n);
    st->replied = true;
    st->client->is_draining = 1;
}

static bool proxy_buf_append(struct proxy_state *st, const char *d, size_t n) {
    size_t need = st->buf_len + n;
    if (need > PROXY_BUF_MAX) return false;
    if (need > st->buf_cap) {
        size_t cap = st->buf_cap ? st->buf_cap : 65536;
        while (cap < need) cap *= 2;
        char *p = (char *) realloc(st->buf, cap);
        if (!p) return false;
        st->buf = p;
        st->buf_cap = cap;
    }
    memcpy(st->buf + st->buf_len, d, n);
    st->buf_len += n;
    return true;
}

/* Rebuild an HTML response with the filtered body. */
static void proxy_finish_filtered(struct proxy_state *st) {
    if (!st->client || !st->buf || !st->headers_done) return;
    size_t body_off = st->head_len;
    size_t body_len = st->buf_len > body_off ? st->buf_len - body_off : 0;
    size_t cap = body_len + 2048 + s_cosmetic_len;
    char *filtered = (char *) malloc(cap);
    if (!filtered) { proxy_send_raw(st, st->buf, st->buf_len); st->replied = true; return; }
    size_t flen = html_filter(st->buf + body_off, body_len, filtered, cap);
    /* status line first */
    size_t line_end = 0;
    while (line_end + 1 < body_off &&
           !(st->buf[line_end] == '\r' && st->buf[line_end + 1] == '\n')) line_end++;
    if (line_end + 2 > body_off) line_end = body_off - 2;
    mg_send(st->client, st->buf, line_end + 2);
    /* keep the origin headers except the ones we have to own */
    static const char *kDrop[] = {"content-length:", "transfer-encoding:", "content-encoding:",
                                  "connection:", "keep-alive:", NULL};
    size_t p = line_end + 2;
    while (p + 1 < body_off) {
        size_t e = p;
        while (e + 1 < body_off && !(st->buf[e] == '\r' && st->buf[e + 1] == '\n')) e++;
        size_t llen = e - p;
        if (llen == 0) break;
        bool skip = false;
        for (int t = 0; kDrop[t]; t++) {
            size_t dl = strlen(kDrop[t]);
            if (llen >= dl && strncasecmp(st->buf + p, kDrop[t], dl) == 0) { skip = true; break; }
        }
        if (!skip) mg_send(st->client, st->buf + p, llen + 2);
        p = e + 2;
    }
    char head[96];
    int hl = snprintf(head, sizeof(head),
                      "Content-Length: %u\r\nConnection: close\r\n\r\n", (unsigned) flen);
    if (hl > 0) mg_send(st->client, head, (size_t) hl);
    mg_send(st->client, filtered, flen);
    st->replied = true;
    st->client->is_draining = 1;
    free(filtered);
}

static void proxy_on_data(struct proxy_state *st, const char *data, size_t n) {
    if (st->headers_done) {
        if (!st->filter_body) { proxy_send_raw(st, data, n); st->replied = true; return; }
        if (!proxy_buf_append(st, data, n)) {
            /* response grew too large: stop filtering, stream what we have */
            st->filter_body = false;
            proxy_send_raw(st, st->buf, st->buf_len);
            st->replied = true;
            free(st->buf); st->buf = NULL; st->buf_len = 0; st->buf_cap = 0;
        }
        return;
    }
    if (!proxy_buf_append(st, data, n)) {
        proxy_fail(st, 502, "Response too large");
        if (st->up) st->up->is_closing = 1;
        return;
    }
    size_t hend = 0;
    for (size_t i = 0; i + 3 < st->buf_len; i++) {
        if (st->buf[i] == '\r' && st->buf[i + 1] == '\n' &&
            st->buf[i + 2] == '\r' && st->buf[i + 3] == '\n') { hend = i + 4; break; }
    }
    if (hend == 0) return;   /* headers still incomplete */
    char ctype[64];
    ctype[0] = '\0';
    bool encoded = false;
    for (size_t i = 0; i + 13 < hend; i++) {
        if (ctype[0] == '\0' && strncasecmp(st->buf + i, "content-type:", 13) == 0) {
            size_t v = i + 13;
            while (v < hend && (st->buf[v] == ' ' || st->buf[v] == '\t')) v++;
            size_t c = 0;
            while (v < hend && c < sizeof(ctype) - 1 &&
                   st->buf[v] != '\r' && st->buf[v] != '\n' && st->buf[v] != ';') ctype[c++] = st->buf[v++];
            ctype[c] = '\0';
        } else if (strncasecmp(st->buf + i, "content-encoding:", 17) == 0 ||
                   strncasecmp(st->buf + i, "transfer-encoding:", 18) == 0) {
            /* Compressed or chunked bodies must not be rewritten: stream them. */
            encoded = true;
        }
    }
    st->headers_done = true;
    st->head_len = hend;
    bool html = !encoded &&
                (strncasecmp(ctype, "text/html", 9) == 0 ||
                 strncasecmp(ctype, "application/xhtml", 17) == 0);
    if (html) {
        st->filter_body = true;
        return;
    }
    /* non-HTML: relay headers + body as they are */
    proxy_send_raw(st, st->buf, st->buf_len);
    st->replied = true;
    free(st->buf);
    st->buf = NULL;
    st->buf_len = 0;
    st->buf_cap = 0;
}

static void proxy_fn(struct mg_connection *c, int ev, void *ev_data) {
    (void) ev_data;
    struct proxy_state *st = (struct proxy_state *) c->fn_data;
    if (!st) return;
    if (ev == MG_EV_CONNECT) {
        if (st->tls) {
            struct mg_tls_opts o = {0};
            o.name = mg_str(st->host);
            o.skip_verification = true;   /* this server is a MITM by design */
            mg_tls_init(c, &o);
        }
        st->connected = true;
        return;
    }
    if (ev == MG_EV_ERROR) {
        proxy_fail(st, 502, "Bad Gateway");
        return;
    }
    if (ev == MG_EV_READ) {
        proxy_on_data(st, (const char *) c->recv.buf, c->recv.len);
        c->recv.len = 0;
        return;
    }
    if (ev == MG_EV_POLL) {
        uint64_t now = mg_millis();
        uint64_t limit = st->connected ? PROXY_TIMEOUT_MS : PROXY_CONNECT_TIMEOUT_MS;
        if (!st->replied && now - st->started_ms > limit) {
            proxy_fail(st, 504, "Gateway Timeout");
            c->is_closing = 1;
        }
        return;
    }
    if (ev == MG_EV_CLOSE) {
        if (st->filter_body && !st->replied && st->buf) proxy_finish_filtered(st);
        if (!st->replied) proxy_fail(st, 502, "Bad Gateway");
        if (st->client) st->client->is_draining = 1;
        proxy_state_free(st);
        c->fn_data = NULL;
    }
}

/* Is a proxy request currently in flight for this client connection? */
static bool proxy_busy(struct mg_connection *c) {
    if (s_proxy_active == 0) return false;   /* fast path: nothing to scan */
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] && s_proxies[i]->client == c) return true;
    }
    return false;
}

static bool proxy_start(struct mg_connection *c, struct settings *s,
                        struct mg_http_message *hm, const char *host) {
    (void) s;
    if (!s_mgr) return false;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] && s_proxies[i]->client == c) return false;
    }
    struct proxy_state *st = (struct proxy_state *) calloc(1, sizeof(*st));
    if (!st) return false;
    st->client = c;
    snprintf(st->host, sizeof(st->host), "%s", host);
    st->tls = c->is_tls ? true : false;
    st->port = st->tls ? 443 : 80;
    st->started_ms = mg_millis();

    size_t cap = 2048 + hm->head.len + hm->body.len;
    st->req = (char *) malloc(cap);
    if (!st->req) { free(st); return false; }
    int n = snprintf(st->req, cap, "%.*s %.*s HTTP/1.0\r\nHost: %s\r\n",
                     (int) hm->method.len, hm->method.buf,
                     (int) hm->uri.len, hm->uri.buf, st->host);
    if (n < 0) { free(st->req); free(st); return false; }
    size_t off = (size_t) n;
    /* Forward the client headers except hop-by-hop ones and
       Accept-Encoding (identity keeps HTML filterable). */
    const char *hdrs = hm->head.buf;
    size_t hlen = hm->head.len;
    size_t first_eol = 0;
    while (first_eol + 1 < hlen && !(hdrs[first_eol] == '\r' && hdrs[first_eol + 1] == '\n')) first_eol++;
    size_t p = (first_eol + 2 <= hlen) ? first_eol + 2 : hlen;
    static const char *kSkip[] = {"host:", "connection:", "proxy-connection:", "keep-alive:",
                                  "transfer-encoding:", "te:", "trailer:", "upgrade:",
                                  "accept-encoding:", "proxy-authorization:", "content-length:", NULL};
    while (p + 1 < hlen) {
        size_t e = p;
        while (e + 1 < hlen && !(hdrs[e] == '\r' && hdrs[e + 1] == '\n')) e++;
        size_t llen = e - p;
        if (llen == 0) break;
        bool skip = false;
        for (int t = 0; kSkip[t]; t++) {
            size_t sl = strlen(kSkip[t]);
            if (llen >= sl && strncasecmp(hdrs + p, kSkip[t], sl) == 0) { skip = true; break; }
        }
        if (!skip && off + llen + 2 < cap) {
            memcpy(st->req + off, hdrs + p, llen + 2);
            off += llen + 2;
        }
        p = e + 2;
    }
    if (hm->body.len > 0) {
        int bl = snprintf(st->req + off, cap - off, "Content-Length: %u\r\n\r\n",
                          (unsigned) hm->body.len);
        if (bl < 0) { free(st->req); free(st); return false; }
        off += (size_t) bl;
        memcpy(st->req + off, hm->body.buf, hm->body.len);
        off += hm->body.len;
    } else {
        memcpy(st->req + off, "\r\n", 2);
        off += 2;
    }
    st->req_len = off;

    char url[320];
    snprintf(url, sizeof(url), "tcp://%s:%d", st->host, st->port);
    struct mg_connection *up = mg_connect(s_mgr, url, proxy_fn, st);
    if (!up) { free(st->req); free(st); return false; }
    st->up = up;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (!s_proxies[i]) { s_proxies[i] = st; s_proxy_active++; break; }
    }
    mg_send(up, st->req, st->req_len);
    if (s->debug) LOG_INFO("[proxy] %s%s", st->tls ? "https://" : "http://", st->host);
    return true;
}

static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        /* Atomic cap check + increment (see the note on
           s_active_connections above — defensive, not a live race). */
        for (;;) {
            int cur = atomic_load(&s_active_connections);
            if (cur >= MAX_CONNECTIONS) {
                c->is_closing = 1; return;
            }
            if (__atomic_compare_exchange_n(&s_active_connections, &cur,
                    cur + 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
                break;
        }
        /* Connections are counted when the first REAL request arrives (see the
           accounting block in fn()): the app and the dashboard poll
           /internal-stats once a second, and counting those accept()s made the
           connection totals and the hourly chart look like permanent traffic. */
        uint64_t t = mg_millis();
        memcpy(c->data, &t, sizeof(t));
        c->data[sizeof(t)] = 1;

        /* Per-app: the uid is resolved when the first request arrives
           (the connection is alive and present in /proc then). We just
           initialize the cached uid to -1 here. */
        conn_store_uid(c, (uid_t)-1);

        if (c->is_tls && c->fn_data) {
            struct settings *s = (struct settings *)c->fn_data;
            mg_tls_init(c, &s->tls_opts);

            /* Register SNI callback so we can issue per-domain certs */
            SSL_CTX *ctx = mg_conn_ssl_ctx(c);
            if (ctx) {
                SSL_CTX_set_tlsext_servername_callback(ctx, sni_callback);
                SSL_CTX_set_tlsext_servername_arg(ctx, &s->ca);
            }
        }
        return;
    }

    if (ev == MG_EV_CLOSE) {
        /* Drop any in-flight transparent-proxy request of this client. */
        proxy_drop_for_client(c);
        /* Unregister from the WS push list. */
        pthread_mutex_lock(&s_sni_mutex);
        for (int i = 0; i < WS_PUSH_MAX; i++) {
            if (ws_clients[i] == c) { ws_clients[i] = NULL; break; }
        }
        pthread_mutex_unlock(&s_sni_mutex);
        if (c->data[sizeof(uint64_t)]) atomic_sub_fetch(&s_active_connections, 1);
        return;
    }

    /* WebSocket: keep the connection open (client pulls or we push). */
    if (ev == MG_EV_WS_OPEN) {
        /* Register this connection as a push subscriber. */
        pthread_mutex_lock(&s_sni_mutex);  /* reuse cache mutex as a cheap lock */
        for (int i = 0; i < WS_PUSH_MAX; i++) {
            if (ws_clients[i] == NULL) { ws_clients[i] = c; break; }
        }
        pthread_mutex_unlock(&s_sni_mutex);
        /* Send a first snapshot immediately so the UI has data. */
        struct settings *ws_s = (struct settings *)c->fn_data;
        if (ws_s && ws_s->init) {
            char body[16384];
            int n = build_stats_json(ws_s, body, sizeof(body));
            if (n > 0) mg_ws_send(c, body, (size_t)n, WEBSOCKET_OP_TEXT);
        }
        return;
    }
    if (ev == MG_EV_WS_MSG) {
        return;
    }

    /* TLS handshake outcome statistics (new metric). */
    if (ev == MG_EV_TLS_HS) {
        s_stats.tls_handshakes++;
        return;
    }
    if (ev == MG_EV_ERROR && c->is_tls) {
        s_stats.tls_failures++;
        return;
    }

    /* Idle-timeout enforcement */
    if (ev == MG_EV_POLL && c->data[sizeof(uint64_t)]) {
        /* A transparent-proxy request may legitimately take longer than the
           idle timeout, so it is exempt while it is in flight. */
        if (proxy_busy(c)) return;
        uint64_t accepted_at; memcpy(&accepted_at, c->data, sizeof(accepted_at));
        if (mg_millis() - accepted_at > IDLE_TIMEOUT_MS) {
            c->is_draining = 1; return;
        }
    }

    if (ev != MG_EV_HTTP_MSG) return;
    struct mg_http_message *hm = (struct mg_http_message *)ev_data;
    struct settings *s = (struct settings *)c->fn_data;

    /* The management port only answers the internal endpoints. */
    if (s->stats_port != 0 && c->loc.port == (uint16_t) s->stats_port) {
        bool internal = mg_match(hm->uri, mg_str("/internal-stats"), NULL) ||
                        mg_match(hm->uri, mg_str("/internal-ws"), NULL) ||
                        mg_match(hm->uri, mg_str("/internal-test"), NULL) ||
                        mg_match(hm->uri, mg_str("/control"), NULL);
        if (!internal) {
            mg_http_reply(c, 404, "Content-Type: text/plain\r\n", "not found");
            return;
        }
    }

    /*
     * CAPTIVE PORTAL PROTECTION (Android connectivity check):
     * Android (and iOS/Windows) periodically probe well-known URLs to
     * decide whether the current network has real Internet access or is
     * a captive portal ("Sign in to network" notification). When the
     * hosts file redirects those probe domains to us, a non-204 reply
     * would make the OS believe a login page is being served, popping
     * the "network requires authentication" banner. Answer with 204 No
     * Content exactly like a healthy network would.
     *
     * Probes covered (host match OR path match):
     *  - connectivitycheck.gstatic.com/generate_204
     *  - clients3.google.com/generate_204
     *  - connectivitycheck.android.com/generate_204
     *  - /generate_204 /gen_204 /generate_204.php etc.
     *  - www.msftconnecttest.com/connecttest.txt (Windows)
     *  - captive.apple.com/hotspot-detect.html (iOS/macOS)
     * The hostname may arrive in the Host header, the SNI name (already
     * in s_stats), or both; matching by suffix keeps it robust.
     */
    static const char *kCaptiveHosts[] = {
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "connectivitycheck.oppomobile.com",
        "connectivitycheck.platform.hicloud.com",
        "connectivitycheck.miui.com",
        "connectivitycheck.vivoglobal.com",
        "connectivitycheck.vivo.com.cn",
        "connectivitycheck.samsung.com",
        "clients3.google.com",
        "www.msftconnecttest.com",
        "connecttest.com",
        "captive.apple.com",
        "gstatic.com",
        "detectportal.firefox.com",
        /* OPPO/ColorOS captive portal probe domains (conn-service-*.allawntech.com) */
        "allawntech.com",
        /* Chinese carriers captive portal domains */
        "wifi.cmcc.com",
        "portal.cmcc.com",
        "cmccwifi.com",
        "wlan.cmcc.com",
        "wifi.chinaunicom.cn",
        "portal.chinaunicom.cn",
        "wifi.189.cn",
        "portal.189.cn",
        "wifi.ctc.com.cn",
        "portal.ctc.com.cn",
        "cmcc.com",
        "chinaunicom.cn",
        "189.cn",
        "ctc.com.cn",
        NULL,
    };
    static const char *kCaptivePaths[] = {
        "/generate_204", "/generate204", "/gen_204", "/generate_204.php",
        "/connecttest.txt", "/hotspot-detect.html", "/hotspot-detect.html",
        /* CMCC/China Mobile specific captive portal paths */
        "/wlan/userip", "/wlan/ac_portal", "/wlan/login",
        "/portal/index.jsp", "/portal/auth.jsp", "/portal/login.jsp",
        "/eportal/index.jsp", "/eportal/auth.jsp", "/eportal/login.jsp",
        "/cmcc/wlan", "/cmcc/portal", "/cmcc/login",
        NULL,
    };
    /* Portal hosts that should be FULLY ALLOWED (not blocked) so the
       actual portal page can load (HTML, CSS, JS, images).
       These are the domains users visit when they click "Sign in to network". */
    static const char *kPortalHosts[] = {
        "wifi.cmcc.com",
        "portal.cmcc.com",
        "cmccwifi.com",
        "wlan.cmcc.com",
        "wifi.chinaunicom.cn",
        "portal.chinaunicom.cn",
        "wifi.189.cn",
        "portal.189.cn",
        "wifi.ctc.com.cn",
        "portal.ctc.com.cn",
        NULL,
    };
    bool captive = false;
    if (hm->uri.len > 0) {
        for (int i = 0; kCaptivePaths[i]; i++) {
            size_t plen = strlen(kCaptivePaths[i]);
            if (hm->uri.len >= plen &&
                mg_strcasecmp(mg_str_n(hm->uri.buf, plen), mg_str(kCaptivePaths[i])) == 0) {
                captive = true; break;
            }
        }
    }
    if (!captive) {
        struct mg_str *host_hdr = mg_http_get_header(hm, "Host");
        if (host_hdr != NULL && host_hdr->len > 0) {
            char host[256];
            size_t hl = host_hdr->len < sizeof(host) - 1 ? host_hdr->len : sizeof(host) - 1;
            memcpy(host, host_hdr->buf, hl); host[hl] = '\0';
            /* Strip an optional ":port" suffix from the Host header so
               "connectivitycheck.gstatic.com:80" still matches. */
            char *colon = strchr(host, ':');
            if (colon != NULL) *colon = '\0';
            for (int i = 0; kCaptiveHosts[i]; i++) {
                size_t klen = strlen(kCaptiveHosts[i]);
                size_t hlen = strlen(host);
                if (hlen >= klen && strcasecmp(host + hlen - klen, kCaptiveHosts[i]) == 0) {
                    captive = true; break;
                }
            }
        }
    }
    if (!captive && c->is_tls && c->tls) {
        /* SNI fallback: some HTTPS clients probe with a missing or
           odd Host header; the SNI name is authoritative here. */
        SSL *ssl = ((struct mg_tls_openssl *)c->tls)->ssl;
        if (ssl) {
            const char *sni = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
            if (sni && *sni) {
                for (int i = 0; kCaptiveHosts[i]; i++) {
                    size_t klen = strlen(kCaptiveHosts[i]);
                    size_t hlen = strlen(sni);
                    if (hlen >= klen && strcasecmp(sni + hlen - klen, kCaptiveHosts[i]) == 0) {
                        captive = true; break;
                    }
                }
            }
        }
    }
    if (captive) {
        /* 204 = "network is fine, no portal". Add CORS + no-store.
           total_requests / hist_add are only incremented after this
           early-return block, so no counter rollback is needed. */
        mg_http_reply(c, 204,
                      "Cache-Control: no-store, max-age=0\r\n"
                      "Access-Control-Allow-Origin: *\r\n", "");
        return;
    }

    /* Per-app request counter + record which TLS (SNI) hostname this
       app asked us to sign a certificate for. The uid is resolved once
       per connection (cached in c->data) to avoid /proc scans on every
       request of a keep-alive connection. */
    /* Only the uid cached when the connection was first seen. Resolving it
       scans /proc/net/tcp* for EVERY connection, which under the hijack mode
       (the whole device traffic) was the dominant CPU cost; blocked requests
       resolve it further down, where the allowlist decision needs it. */
    uid_t req_uid = conn_load_uid(c);

    /*
     * PER-APP ALLOWLIST: the app writes <resource_dir>/allowlist.txt
     * (one decimal uid per line, from the Settings > App monitoring
     * "allow" switches). A uid on the list has its traffic forwarded
     * as-is (200 OK with a tiny body) instead of being blocked, so
     * e.g. banking apps that need ads SDKs for auth still work.
     *
     * WebView renderers run under isolated uids (99000-99999) that are
     * allocated per renderer instance; map them to their host app first
     * so an "Allow"-ed app's WebView requests also pass through.
     */
    /* Blocked/local request: this is the minority path, so resolving the uid
       here keeps the per-connection /proc scan out of the proxy hot path. */
    if (req_uid == (uid_t)-1) {
        req_uid = conn_uid_by_tuple(c);
        conn_store_uid(c, req_uid);
    }
    uid_t chk_uid = req_uid;
    if (uid_is_isolated(req_uid)) {
        uid_t eff = resolve_effective_uid(req_uid);
        if (eff != req_uid && s_verbose) {
            LOG_INFO("allowlist: isolated uid %d -> host uid %d",
                     (int)req_uid, (int)eff);
        }
        chk_uid = eff;
    }

    /*
     * REQUEST / CONNECTION ACCOUNTING - exactly once per real request, and only
     * for real traffic. The app and the dashboard poll /internal-stats (plus
     * /internal-test and /control) once a second; counting that as user traffic
     * made 请求量 and 连接数 grow on a completely idle device.
     */
    bool internal_path = mg_match(hm->uri, mg_str("/internal-*"), NULL) ||
                         mg_match(hm->uri, mg_str("/control"), NULL);
    if (!internal_path) {
        if (c->data[CONN_REQ_OFFSET] == 0) {
            c->data[CONN_REQ_OFFSET] = 1;
            s_stats.total_connections++;
            hist_add(HIST_CONN);
        }
        s_stats.total_requests++;
        hist_add(HIST_REQ);
    }

    /*
     * TRANSPARENT FILTERING PROXY (hijack mode, --proxy-filter):
     * the app redirects the whole TCP 80/443 traffic here, so a request for
     * a host that is NOT in the block list has to be forwarded to the real
     * origin server instead of being answered locally. Blocked hosts keep
     * the placeholder replies below - except for uids the user put on the
     * per-app allowlist, whose traffic must never be blocked and is proxied
     * as well (previously such a request got a useless 2-byte "ok" reply).
     */
    if (s->proxy_filter) {
        struct mg_str *phdr = mg_http_get_header(hm, "Host");
        if (phdr != NULL && phdr->len > 0) {
            char phost[256];
            size_t pl = phdr->len < sizeof(phost) - 1 ? phdr->len : sizeof(phost) - 1;
            memcpy(phost, phdr->buf, pl);
            phost[pl] = '\0';
            if (phost[0] == '[') {                 /* IPv6 literal: [::1]:443 */
                char *b = strrchr(phost, ']');
                if (b != NULL) *b = '\0';
                memmove(phost, phost + 1, strlen(phost) + 1);
            } else {
                char *colon = strchr(phost, ':');
                if (colon != NULL) *colon = '\0';
            }
            /* Single-label hosts ("adaway", "localhost", printer names) are
               never proxied: they are local names or simply broken. */
            if (phost[0] != '\0' && strchr(phost, '.') != NULL && !host_is_local(phost)) {
                /* Allowlisted apps need no uid lookup here: their traffic is
                   proxied like every other non-blocked host. */
                if (!block_set_contains(phost, strlen(phost))) {
                    /* Already counted above: proxied requests are real traffic,
                       and counting them here too double-counted every request
                       whose proxy_start() failed. */
                    /* A pipelined request on a connection that is already
                       proxying is dropped (the first reply is still
                       streaming back to the client). */
                    if (proxy_busy(c)) return;
                    if (proxy_start(c, s, hm, phost)) {
                        qlog_add(req_uid, QLOG_PROXY, hm);
                        return;
                    }
                }
            }
        }
    }

    if (req_uid != (uid_t)-1 && uid_is_allowed(chk_uid, s->resource_dir)) {
        mg_http_reply(c, 200, "Content-Type: text/plain\r\n"
                              "Cache-Control: no-store\r\n", "ok");
        return;
    }

    /* (Request and connection counters are maintained further up, before the
       proxy branch, so every real request is counted exactly once.) */

    /* Check if this is a known portal host (user clicked "Sign in to network").
       If so, allow the request through so the portal page loads properly
       (HTML, CSS, JS, images) instead of being blocked by reply_blocked_by_type. */
    struct mg_str *host_hdr = mg_http_get_header(hm, "Host");
    if (host_hdr != NULL && host_hdr->len > 0) {
        char host[256];
        size_t hl = host_hdr->len < sizeof(host) - 1 ? host_hdr->len : sizeof(host) - 1;
        memcpy(host, host_hdr->buf, hl); host[hl] = '\0';
        char *colon = strchr(host, ':');
        if (colon != NULL) *colon = '\0';
        for (int i = 0; kPortalHosts[i]; i++) {
            size_t klen = strlen(kPortalHosts[i]);
            size_t hlen = strlen(host);
            if (hlen >= klen && strcasecmp(host + hlen - klen, kPortalHosts[i]) == 0) {
                /* Known portal host - serve a minimal page instead of blocking */
                mg_http_reply(c, 200,
                              "Content-Type: text/html; charset=utf-8\r\n"
                              "Cache-Control: no-store\r\n",
                              "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                              "<body style=\"font-family:sans-serif;padding:20px;text-align:center;\">"
                              "<h2>ADBlock: Portal Page Allowed</h2>"
                              "<p>This is a carrier portal domain. The page should load normally.</p>"
                              "<p>If you see this, the portal page resources are being allowed through.</p>"
                              "</body></html>");
                return;
            }
        }
    }

    /* Management traffic is not user traffic: attributing the app's own
       /internal-stats polls to its uid made the app itself show up as the
       busiest entry in the per-app statistics. */
    struct appstat *ra = internal_path ? NULL : app_find_or_add(req_uid);
    if (ra) {
        if (!c->data[REC_OFFSET]) {
            ra->connections++;
            c->data[REC_OFFSET] = 1;
        }
        ra->requests++;
    }
    if (c->is_tls && c->tls) {
        SSL *ssl = ((struct mg_tls_openssl *)c->tls)->ssl;
        if (ssl) {
            const char *sni = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
            if (sni && *sni) app_record_tls_host(req_uid, sni);
        }
    }

    /* Debug: log every request so blocked-domain traffic can be
       inspected (only when started with --debug). */
    if (s->debug) {
        LOG_INFO("[req] %.*s %.*s", (int)hm->method.len, hm->method.buf,
                 (int)hm->uri.len, hm->uri.buf);
    }

    /* CORS preflight: browsers send OPTIONS + Access-Control-Request-*
       before any cross-origin XHR with non-simple headers (e.g.
       Authorization). Answer 204 with permissive CORS headers so the
       subsequent real request proceeds and the SDK sees a successful
       exchange. */
    if (mg_strcasecmp(hm->method, mg_str("OPTIONS")) == 0 &&
        mg_http_get_header(hm, "Access-Control-Request-Method") != NULL) {
        mg_http_reply(c, 204,
                      "Access-Control-Allow-Origin: *\r\n"
                      "Access-Control-Allow-Methods: GET, HEAD, POST, PUT, DELETE, OPTIONS\r\n"
                      "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, Accept, Origin\r\n"
                      "Access-Control-Max-Age: 86400\r\n"
                      "Cache-Control: public, max-age=86400\r\n", "");
        return;
    }

    if (mg_match(hm->uri, mg_str("/internal-test"), NULL)) {
        struct mg_http_serve_opts o = {0};
        o.mime_types = "html=text/html";
        mg_http_serve_file(c, hm, s->test_path, &o);
        return;
    }

    /* Real-time WebSocket endpoint: upgrade to WS. The app may push
       requests here to receive a snapshot; on open we send one
       immediately, then the client keeps polling as fallback. */
    if (mg_match(hm->uri, mg_str("/internal-ws"), NULL)) {
        /*
         * SECURITY (audit H-1): the WS snapshot carries recent_tls[] (browsing
         * history). WebSocket is not subject to CORS, so a random web page could
         * read it; only the management port and non-browser callers (the app's
         * OkHttp client sends no Origin) may connect.
         */
        if (mg_http_get_header(hm, "Origin") != NULL ||
            s->stats_port == 0 || c->loc.port != (uint16_t) s->stats_port) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        mg_ws_upgrade(c, hm, NULL);
        return;
    }

    /* Internal statistics endpoint: JSON snapshot of the per-process
       counters (uptime, request totals, blocked-by-type breakdown,
       SNI certs issued). Like /internal-test it is only reachable on
       loopback; no auth needed since 127.0.0.1 is this device. */
    if (mg_match(hm->uri, mg_str("/internal-stats"), NULL)) {
        char body[16384];
        int n = build_stats_json(s, body, sizeof(body));
        mg_http_reply(c, 200,
                      "Content-Type: application/json\r\n"
                      "Cache-Control: no-store\r\n", "%.*s", n, body);
        /* Throttled: see persist_dat_files(). The dashboard polls this
           endpoint every few seconds, so an unconditional flush here used to
           rewrite ~270 KB (sni_cache.dat) plus three more files per poll. */
        persist_dat_files(s, false);
        qlog_save(s->resource_dir);
        return;
    }

    /* Control endpoint: reload resources, flush stats, shutdown. */
    if (mg_match(hm->uri, mg_str("/control"), NULL)) {
        /*
         * SECURITY: /control can reconfigure and shut the server down, and its
         * replies carry permissive CORS headers. Requests coming from a web
         * page (any Origin header) are therefore refused, which closes the
         * drive-by CSRF hole (a plain local caller sends no Origin).
         */
        if (mg_http_get_header(hm, "Origin") != NULL) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        /*
         * ... and it is only served on the loopback management port, so a LAN
         * client of "--bind all" cannot reconfigure or shut the server down.
         * Both clients (Windows dashboard and the Android app) already talk to
         * /control on that port.
         */
        if (s->stats_port == 0 || c->loc.port != (uint16_t) s->stats_port) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        char cmd_buf[32];
        mg_http_get_var(&hm->body, "cmd", cmd_buf, sizeof(cmd_buf));
        struct mg_str cmd = mg_str(cmd_buf);
        if (mg_strcmp(cmd, mg_str("reload_config")) == 0) {
            load_block_cfg(s->resource_dir);
            load_block_modes(s->resource_dir);   /* per-type blocking method (mode_* keys) */
            /* Rules changed: refresh the transparent-proxy block set too. */
            if (s->proxy_filter) {
                block_set_load(s->resource_dir);
                load_cosmetic_css(s->resource_dir);
            }
            char hdr[96];
            int hl = snprintf(hdr, sizeof(hdr), "Content-Type: text/plain%c%c", 0x0d, 0x0a);
            (void) hl;
            mg_http_reply(c, 200, hdr, "OK: block config reloaded");
        } else if (mg_strcmp(cmd, mg_str("reload_images")) == 0) {
            s->block_image_count = scan_block_images(s->resource_dir, s->block_images);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: reloaded %d images", s->block_image_count);
        } else if (mg_strcmp(cmd, mg_str("flush_stats")) == 0) {
            persist_dat_files(s, true);   /* forced full flush */
        qlog_save(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: stats flushed");
        } else if (mg_strcmp(cmd, mg_str("reload_subscriptions")) == 0) {
            block_set_load(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: subscriptions reloaded");
        } else if (mg_strcmp(cmd, mg_str("clear_query_log")) == 0) {
            qlog_clear();
            qlog_save(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: query log cleared");
        } else if (mg_strcmp(cmd, mg_str("shutdown")) == 0) {
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: shutting down");
            s_sig_num = SIGTERM;  /* trigger main loop exit */
        } else {
            mg_http_reply(c, 400, "Content-Type: text/plain\r\n", "Usage: cmd=reload_images|flush_stats|shutdown");
        }
        return;
    }

    /* Classify blocked requests by type and reply with the most
       realistic "empty" resource - see reply_blocked_by_type(). */
    if (reply_blocked_by_type(c, hm)) {
        if (s_rb_allow) {
            qlog_add(req_uid, QLOG_ALLOW, hm);
            /* 该类型被设为 BM_ALLOW（放行）：不拦截、不计入拦截统计、不返回
               占位资源，只回一个空的 200，由客户端自行处理。 */
            s_rb_allow = false;
            mg_http_reply(c, 200, CORS_HDR "Cache-Control: no-store\r\n", "");
            return;
        }
        qlog_add(conn_load_uid(c), QLOG_BLOCK, hm);
        hist_add(HIST_BLOCKED);
        struct appstat *ba = app_find_or_add(conn_load_uid(c));
        if (ba) ba->blocked++;
        ws_push_broadcast(s);  /* real-time push to WS subscribers */
        return;
    }


    /* Random block image - serve whichever actual filename was found at
       scan time (see scan_block_images()), not a reconstructed
       img_%02d.webp - deleting/adding images doesn't require renaming
       the rest to stay contiguous. */
    uint64_t t; memcpy(&t, c->data, sizeof(t));
    int idx = (int)(t % (uint64_t)s->block_image_count);
    char img_path[PATH_MAX];
    snprintf(img_path, sizeof(img_path), "%s/%s", s->resource_dir, s->block_images[idx]);
    s_stats.blocked_images++;
    hist_add(HIST_BLOCKED);
    struct appstat *ba = app_find_or_add(conn_load_uid(c));
    if (ba) ba->blocked++;
    ws_push_broadcast(s);  /* real-time push to WS subscribers */
    struct mg_http_serve_opts o = {0};
    o.mime_types = "webp=image/webp";
    /*
     * OPTIMIZATION: every blocked ad slot on every page load re-requests
     * this same placeholder image from the local server with no caching
     * hint at all, so the client re-fetches it every single time instead
     * of ever reusing a cached copy — needless disk I/O and CPU work on
     * a mobile device that may be handling this dozens of times a
     * minute. These images are static build resources that never change
     * at runtime, so let clients cache them.
     *
     * BUG FIX: this used to be "public, max-age=86400" - fine for the
     * built-in defaults, which really don't change at runtime, but the
     * app also lets a user replace them at any time via
     * WebServerUtils#setCustomBlockImage()/resetBlockImagesToDefault(),
     * which overwrite the same filenames in place. A client that had
     * already cached the old bytes under max-age=86400 won't even send
     * a new request - let alone a conditional one - for up to a day,
     * so a picked custom image (or a reset back to the default) could
     * silently not show up for hours. mg_http_serve_file() already
     * generates an ETag from each file's size+mtime and honors
     * If-None-Match (see mg_http_etag() in mongoose.c), so "no-cache"
     * keeps the win this header was added for - clients still cache the
     * bytes and, on every use, get back a cheap 304 with no body as
     * long as the file is actually unchanged - while making sure a
     * genuine content change (different size/mtime → different ETag) is
     * always picked up on the very next request instead of being stuck
     * behind a stale cache.
     */
    o.extra_headers = "Cache-Control: no-cache\r\n";
    mg_http_serve_file(c, hm, img_path, &o);
}

/* ── CLI parsing ──────────────────────────────────────────────── */
/*
 * Prepare the resource directory (CA, localhost leaf cert, block
 * images, paths). Creates the directory when it does not exist, so a
 * plain double-click works out of the box, and logs a clear reason
 * when the directory cannot be used. Returns true on success.
 */
static bool setup_resources_dir(struct settings *s, const char *rpath) {
    LOG_INFO("Resources dir: %s", rpath);
#ifdef _WIN32
    snprintf(s_crash_dir, sizeof(s_crash_dir), "%s", rpath);
    SetUnhandledExceptionFilter(crash_filter);   /* crash.log forensics */
#endif

    struct stat st;
    if (stat(rpath, &st) != 0 || (st.st_mode & S_IFDIR) == 0) {
        LOG_INFO("Resources dir '%s' not present — creating it…", rpath);
#ifdef _WIN32
        if (_mkdir(rpath) != 0) {
#else
        if (mkdir(rpath, 0755) != 0) {
#endif
            LOG_FATAL("Cannot create resources dir '%s' (errno %d). "
                      "Run: webserver --resources <writable directory>", rpath, errno);
            return false;
        }
    }

    char cert_path[PATH_MAX], key_path[PATH_MAX];
    snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", rpath);
    snprintf(key_path,  sizeof(key_path),  "%s/localhost-2410.key", rpath);

    /* Generate CA cert on first use */
    bool missing = (access(cert_path, F_OK) != 0 || access(key_path, F_OK) != 0);
    LOG_INFO("CA cert missing=%d (cert=%s key=%s)", missing, cert_path, key_path);
    if (missing) {
        LOG_INFO("Generating root CA…");
        if (generate_root_ca(cert_path, key_path) != EXIT_SUCCESS) {
            LOG_FATAL("CA generation failed (dir '%s'): make sure the directory is writable", rpath);
            return false;
        }
        LOG_INFO("Root CA generated OK");
    }

    /* Load CA into memory for SNI signing */
    if (load_ca(cert_path, key_path, &s->ca) != EXIT_SUCCESS) {
        LOG_FATAL("Failed to load CA");
        return false;
    }

    /* Cert rotation: regenerate when the CA nears expiry (30d). */
    if (maybe_rotate_ca(cert_path, key_path, &s->ca)) {
        LOG_INFO("CA rotated — app will prompt to reinstall");
    }
    LOG_INFO("CA loaded OK");

    /* TLS opts for the localhost listener: a leaf cert issued
       specifically for "localhost"/127.0.0.1, not the raw CA
       cert (see make_localhost_leaf() for why). */
    if (make_localhost_leaf(&s->ca, &s->tls_opts) != EXIT_SUCCESS) {
        LOG_FATAL("Failed to issue localhost leaf cert");
        return false;
    }
    LOG_INFO("localhost leaf cert issued OK");
    snprintf(s->resource_dir, sizeof(s->resource_dir), "%s", rpath);
    snprintf(s->test_path,    sizeof(s->test_path),    "%s/test.html", rpath);
    s->block_image_count = scan_block_images(rpath, s->block_images);
    s->init = true;
    return true;
}

static struct settings parse_cli_parameters(int argc, char *argv[]) {
    struct settings s = {0};
    /* Double-click friendly defaults (unprivileged ports; the Android
       app always passes --http-port/--https-port explicitly). */
    s.http_port = 8080;
    s.https_port = 8443;
    s.bind_all = false;
    const char *rpath = NULL;
    char resolved[PATH_MAX];
    memset(resolved, 0, sizeof(resolved));
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--resources") == 0 && i < argc-1) {
            rpath = argv[++i];
        } else if (strcmp(argv[i], "--debug") == 0) {
            s.debug = true;
        } else if (strcmp(argv[i], "--proxy-filter") == 0) {
            /* Transparent hijack proxy: forward non-blocked hosts to the
               real origin and filter the answer (see proxy_start()). */
            s.proxy_filter = true;
        } else if (strcmp(argv[i], "--stats-port") == 0 && i < argc-1) {
            /* Loopback management port: /internal-stats and /control are
               served there too, so the app can always read statistics even
               when the user facing 80/443 ports are taken by another app. */
            s.stats_port = atoi(argv[++i]);
            s.cli_stats_port_set = true;
        } else if (strcmp(argv[i], "--bind") == 0 && i < argc-1) {
            s.bind_all = strcmp(argv[++i], "all") == 0;
            s.cli_bind_set = true;
            LOG_INFO("Bind mode: %s", s.bind_all ? "all interfaces" : "loopback");
        } else if (strcmp(argv[i], "--http-port") == 0 && i < argc-1) {
            s.http_port = atoi(argv[++i]);
            s.cli_http_port_set = true;
            LOG_INFO("HTTP port: %d", s.http_port);
        } else if (strcmp(argv[i], "--https-port") == 0 && i < argc-1) {
            s.https_port = atoi(argv[++i]);
            s.cli_https_port_set = true;
            LOG_INFO("HTTPS port: %d", s.https_port);
        } else if (strcmp(argv[i], "--no-gui") == 0) {
            s.no_gui = true;
        } else if (strcmp(argv[i], "--minimized") == 0) {
            s.minimized = true;
        } else if (strcmp(argv[i], "--install-autostart") == 0) {
            s.autostart = 1;
        } else if (strcmp(argv[i], "--uninstall-autostart") == 0) {
            s.autostart = 2;
        }
    }

    /* Default / path-resolution: a plain double-click must work, so
       fall back to a 'resources' folder next to the executable and
       resolve relative --resources paths against the exe directory. */
    char default_dir[PATH_MAX];
    memset(default_dir, 0, sizeof(default_dir));
#ifdef _WIN32
    {
        char exe_path[PATH_MAX];
        memset(exe_path, 0, sizeof(exe_path));
        if (get_module_path_utf8(exe_path, (DWORD)sizeof(exe_path) - 1) > 0) {
            char *slash = strrchr(exe_path, '\\');
            if (slash) *slash = '\0';
            if (rpath != NULL && rpath[0] != '\\' && rpath[0] != '/' && strchr(rpath, ':') == NULL) {
                /* relative --resources: resolve against the exe dir */
                snprintf(resolved, sizeof(resolved), "%s\\%s", exe_path, rpath);
                rpath = resolved;
            } else if (rpath == NULL) {
                snprintf(default_dir, sizeof(default_dir), "%s\\resources", exe_path);
                rpath = default_dir;
                LOG_INFO("No --resources given — using %s", rpath);
            }
        }
    }
#endif
    if (rpath == NULL) {
        snprintf(default_dir, sizeof(default_dir), "resources");
        rpath = default_dir;
        LOG_INFO("No --resources given — using %s", rpath);
    }

    setup_resources_dir(&s, rpath);
    return s;
}

#ifndef ADBLOCK_APP_VERSION
#define ADBLOCK_APP_VERSION "1.15"
#endif

/* autostart entry location (same key as gui_win32.c) */
#define RUN_KEY_W L"Software\\Microsoft\\Windows\\CurrentVersion\\Run"
#define RUN_VALUE_W L"ADBlockWebServer"

/* ── main ─────────────────────────────────────────────────────── */
static int server_loop_and_cleanup(struct mg_mgr *mgr, struct settings *s);

/* Read by the Windows dashboard: 0 = the server loop has returned. */
static volatile int s_server_alive = 1;
bool win32_server_alive(void) { return s_server_alive != 0; }

#ifdef _WIN32
struct server_thread_arg { struct mg_mgr *mgr; struct settings *s; };
static void *server_thread_main(void *p) {
    struct server_thread_arg *a = (struct server_thread_arg *)p;
    server_loop_and_cleanup(a->mgr, a->s);
    return NULL;
}
#endif

int main(int argc, char *argv[]) {
#ifndef _WIN32
    setsid();   /* no-op on Windows: there is no controlling session */
#endif
    /* NOTE: do NOT redirect stdin/stdout/stderr to /dev/null here even
       though setsid() detaches us from the controlling terminal.
       ShellUtils.runBundledExecutable() launches this binary with
       `> logfile 2>&1` and relies on that file to diagnose startup
       failures (bad args, CA generation failure, port bind failure,
       ...) via LOG_FATAL/LOG_WARN/LOG_INFO, which write to both logcat
       and stdio. dup2()-ing the std fds to /dev/null would make that
       capture file permanently empty and silently defeat the
       diagnostic mechanism. The launching shell has already redirected
       our std fds, so there is no tty-sharing hazard to fix here. */

    /* DIAGNOSTIC CHECKPOINT 1: if this line never shows up in the log,
       the process is crashing during dynamic linking / static
       initialization (loading libssl.so/libcrypto.so/libc++_shared.so)
       before main() itself ever runs any of our code — a completely
       different class of bug than anything inside main()'s own logic. */
    LOG_INFO("main() entered, argc=%d", argc);

#ifdef _WIN32
    /*
     * Single instance. Autostart (HKCU Run) plus a manual start - or a previous
     * instance that has not exited yet - used to leave two identical
     * webserver.exe processes fighting for the same ports. The duplicate asks
     * the running instance to show its dashboard and exits immediately.
     */
    HANDLE single = CreateMutexW(NULL, FALSE, L"Local\\ADBlockWebServer_Singleton");
    if (single != NULL && GetLastError() == ERROR_ALREADY_EXISTS) {
        UINT show = RegisterWindowMessageW(L"ADBlockShowDashboard");
        if (show != 0) PostMessageW(HWND_BROADCAST, show, 0, 0);
        LOG_INFO("ADBlock: another instance is already running - exiting.");
        CloseHandle(single);
        return 0;
    }
#endif
    struct settings s = parse_cli_parameters(argc, argv);
    if (!s.init) {
        LOG_FATAL("Bad parameters.");
        return EXIT_FAILURE;
    }

    /* Mirror every LOG_* line into <resources>/webserver.log. The Windows
       build is a GUI-subsystem app (no console), so this file is the only place
       a failed bind / autostart problem can be diagnosed after the fact. */
    log_file_open(s.resource_dir);
    if (s.stats_port == 0) s.stats_port = 8686;

#ifdef _WIN32
    /* Settings persistence: webserver.ini next to the exe (written by
       the dashboard settings page); command-line flags still win. */
    {
        char exe[MAX_PATH];
        if (get_module_path_utf8(exe, sizeof(exe)) > 0) {
            char *slash = strrchr(exe, '\\');
            if (slash) *slash = '\0';
            char path[MAX_PATH + 32];
            snprintf(path, sizeof(path), "%s\\webserver.ini", exe);
            FILE *f = fopen(path, "r");
            if (f) {
                char line[128];
                while (fgets(line, sizeof(line), f)) {
                    int v;
                    if (sscanf(line, "http_port=%d", &v) == 1 && !s.cli_http_port_set)
                        s.http_port = v;
                    else if (sscanf(line, "https_port=%d", &v) == 1 && !s.cli_https_port_set)
                        s.https_port = v;
                    else if (sscanf(line, "bind_all=%d", &v) == 1 && !s.cli_bind_set)
                        s.bind_all = (v != 0);
                    else if (sscanf(line, "stats_port=%d", &v) == 1 && !s.cli_stats_port_set)
                        s.stats_port = v;
                }
                fclose(f);
                LOG_INFO("Loaded webserver.ini settings (ports %d/%d, bind_all=%d, stats_port=%d).",
                         s.http_port, s.https_port, s.bind_all ? 1 : 0, s.stats_port);
            }
        }
    }

    /* Autostart entry self-heal: older versions registered
       "--no-gui" (headless - no tray icon at all). Rewrite such entries
       to "--minimized" so the tray icon appears after logon. */
    {
        DWORD sz = 0;
        if (RegGetValueW(HKEY_CURRENT_USER, RUN_KEY_W, RUN_VALUE_W,
                RRF_RT_REG_SZ, NULL, NULL, &sz) == ERROR_SUCCESS && sz > 4) {
            wchar_t *val = (wchar_t *)malloc(sz);
            if (val) {
                if (RegGetValueW(HKEY_CURRENT_USER, RUN_KEY_W, RUN_VALUE_W,
                        RRF_RT_REG_SZ, NULL, val, &sz) == ERROR_SUCCESS) {
                    wchar_t *p = wcsstr(val, L"--no-gui");
                    if (p) {
                        wchar_t *newVal = (wchar_t *)malloc((wcslen(val) + 8) * sizeof(wchar_t));
                        if (newVal) {
                            wchar_t *d = newVal;
                            size_t pre = (size_t)(p - val);
                            memcpy(d, val, pre * sizeof(wchar_t));
                            d += pre;
                            wcscpy(d, L"--minimized");
                            d += wcslen(L"--minimized");
                            wcscpy(d, p + wcslen(L"--no-gui"));
                            HKEY hk2 = NULL;
                            if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY_W, 0,
                                    KEY_SET_VALUE, &hk2) == ERROR_SUCCESS) {
                                RegSetValueExW(hk2, RUN_VALUE_W, 0, REG_SZ,
                                    (const BYTE *)newVal,
                                    (DWORD)((wcslen(newVal) + 1) * sizeof(wchar_t)));
                                RegCloseKey(hk2);
                            }
                            LOG_INFO("Autostart entry upgraded: --no-gui -> --minimized (tray icon after logon).");
                            free(newVal);
                        }
                    }
                }
                free(val);
            }
        }
    }

    /* One-shot autostart registration (GUI toggle / CI) - do it before
       binding so the command never needs a port to be free. */
    if (s.autostart != 0) {
        char exe[MAX_PATH];
        if (get_module_path_utf8(exe, sizeof(exe)) == 0) {
            LOG_FATAL("Cannot resolve executable path.");
            return EXIT_FAILURE;
        }
        char cmd[2048];
        snprintf(cmd, sizeof(cmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d "
                 "--stats-port %d --minimized",
                 exe, s.resource_dir, s.http_port, s.https_port, s.stats_port);
        bool enable = (s.autostart == 1);
        int rc = win32_autostart_set(enable, cmd);
        LOG_INFO("Autostart %s %s (entry=%s).", enable ? "enabled" : "disabled",
                 rc == 0 ? "OK" : "FAILED",
                 win32_autostart_installed() ? "installed" : "absent");
        return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
    }
#endif

    /* If a server instance is already listening on the HTTP port (a
       previous launch, autostart, ...) do not bind again - open only the
       dashboard and poll the running instance, instead of silently
       exiting. */
    bool server_already_running = false;
#ifdef _WIN32
    if (s.stats_port == 0) s.stats_port = 8686;
    server_already_running = adblock_instance_running(s.stats_port);
    if (server_already_running) {
        if (s.no_gui) {
            LOG_INFO("Web server already running on port %d - nothing to do.", s.http_port);
            return EXIT_SUCCESS;
        }
        struct adblock_gui_args args;
        args.resource_dir = s.resource_dir;
        args.http_port = s.http_port;
        args.https_port = s.https_port;
        args.bind_all = s.bind_all;
        args.start_minimized = s.minimized;
        args.stats_port = s.stats_port;
        args.owns_server = false;    /* attached to the instance above */
        args.startup_warning = NULL;
        LOG_INFO("A web server is already running on port %d - dashboard only mode.", s.http_port);
        int rc = adblock_gui_run(&args);
        if (rc != 0)
            LOG_FATAL("Dashboard window could not be created (exit code %d).", rc);
        return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
    }
#endif

    s_verbose = s.debug;
    /* Without --debug, keep only errors: mongoose would otherwise log every
       connection/read/send, which is the main CPU cost under hijack mode. */
    mg_log_set(s.debug ? MG_LL_DEBUG : MG_LL_ERROR);

    s_stats.start_time_ms = mg_millis();
    load_stats(&s);  /* lifetime counters survive restarts */
    qlog_load(s.resource_dir);   /* recent request/decision history */
#ifdef _WIN32
    harden_ca_key_acl(s.resource_dir);   /* audit D2: key readable by the owner only */
#endif
    load_hist(&s);   /* chart buckets survive restarts (reboot-proof) */
    sni_cache_load(s.resource_dir);  /* SNI cert cache survives restarts */
    apps_load(s.resource_dir);       /* per-app stats survive restarts */

    oom_adjust_setup();

    struct mg_mgr mgr;
    mg_mgr_init(&mgr);
    /* Transparent proxy support: remember the event loop and load the
       blocked-host set from the system hosts file (Android). */
    s_mgr = &mgr;
    if (s.proxy_filter) {
        block_set_load(s.resource_dir);
        load_cosmetic_css(s.resource_dir);
    }
    /* Dedicated loopback management listener (statistics + control). */
    if (s.stats_port == 0) s.stats_port = 8686;
    {
        char su[64], su6[64];
        snprintf(su, sizeof(su), "http://127.0.0.1:%d", s.stats_port);
        snprintf(su6, sizeof(su6), "http://[::1]:%d", s.stats_port);
        if (bind_try(&mgr, su, fn, &s, "mgmt") == NULL) {
            LOG_FATAL("Management port %d is not available on 127.0.0.1 - "
                      "the dashboard cannot read statistics.", s.stats_port);
        }
        bind_try(&mgr, su6, fn, &s, "mgmt6");
    }

    /* Build listen URLs from the configured bind mode + ports. */
    char http_url[128], https_url[128], http_url6[128], https_url6[128];
    const char *v4 = s.bind_all ? "0.0.0.0" : "127.0.0.1";
    const char *v6 = s.bind_all ? "[::]" : "[::1]";
    snprintf(http_url, sizeof(http_url), "http://%s:%d", v4, s.http_port);
    snprintf(https_url, sizeof(https_url), "https://%s:%d", v4, s.https_port);
    snprintf(http_url6, sizeof(http_url6), "http://%s:%d", v6, s.http_port);
    snprintf(https_url6, sizeof(https_url6), "https://%s:%d", v6, s.https_port);

    char startup_warning[512] = {0};
    bool http_ok = (bind_try(&mgr, http_url, fn, &s, "http") != NULL);
    s_main_http_bound = http_ok;
    if (!http_ok) {
        LOG_FATAL("HTTP bind failed (%s): the port is probably already in use.", http_url);
        snprintf(startup_warning, sizeof(startup_warning),
                 "Port %d is already in use - the server could not bind. "
                 "Showing the dashboard anyway; close other instances or "
                 "change the port in Settings (Apply & Restart).", s.http_port);
#ifdef _WIN32
        if (s.no_gui) {
            mg_mgr_free(&mgr);
            return EXIT_FAILURE;
        }
#else
        mg_mgr_free(&mgr);
        return EXIT_FAILURE;
#endif
    }
    if (bind_try(&mgr, https_url, fn, &s, "https") == NULL) {
        LOG_WARN("HTTPS bind failed (%s) - continuing without HTTPS.", https_url);
    }
    /*
     * IPv6 loopback listeners (::1) - optional. Devices with IPv6
     * disabled fail to bind these; that is fine, the IPv4 listeners
     * above still serve. Both must succeed for ipv6_ok so the ready
     * log reflects the actual state.
     */
    bool ipv6_ok = true;
    if (bind_try(&mgr, http_url6, fn, &s, "http6") == NULL) {
        LOG_WARN("HTTP IPv6 bind failed (%s) — continuing with IPv4 only.", http_url6);
        ipv6_ok = false;
    }
    if (bind_try(&mgr, https_url6, fn, &s, "https6") == NULL) {
        LOG_WARN("HTTPS IPv6 bind failed (%s) — continuing with IPv4 only.", https_url6);
        ipv6_ok = false;
    }

    /* Extra ad-block monitoring: the hosts-file redirect targets ports
       80 (HTTP) and 443 (HTTPS). Always try to monitor them in addition
       to the configured ports. Binding them needs Administrator on
       Windows (or the port must be free); failures are only warnings -
       the configured ports keep working. */
    {
        char u[128];
        if (s.http_port != 80) {
            snprintf(u, sizeof(u), "http://127.0.0.1:80");
            if (bind_try(&mgr, u, fn, &s, "mon80") == NULL)
                LOG_WARN("Monitoring http://127.0.0.1:80 failed (admin needed or port in use).");
            snprintf(u, sizeof(u), "http://[::1]:80");
            if (bind_try(&mgr, u, fn, &s, "mon80-6") == NULL)
                LOG_WARN("Monitoring http://[::1]:80 failed (admin needed or port in use).");
        }
        if (s.https_port != 443) {
            snprintf(u, sizeof(u), "https://127.0.0.1:443");
            if (bind_try(&mgr, u, fn, &s, "mon443") == NULL)
                LOG_WARN("Monitoring https://127.0.0.1:443 failed (admin needed or port in use).");
            snprintf(u, sizeof(u), "https://[::1]:443");
            if (bind_try(&mgr, u, fn, &s, "mon443-6") == NULL)
                LOG_WARN("Monitoring https://[::1]:443 failed (admin needed or port in use).");
        }
    }

    load_block_cfg(s.resource_dir);
    load_block_modes(s.resource_dir);   /* per-type blocking method (mode_* keys) */
    setup_signal_handler();
    /* Stay alive under memory pressure and log native crashes. */
    setup_crash_handler(s.resource_dir);
    harden_process();

    LOG_INFO("ADBlock Web Server v" ADBLOCK_APP_VERSION " ready — Mongoose " MG_VERSION
        ", SNI cert issuance enabled, IPv6 loopback %s.",
        ipv6_ok ? "on" : "off");

#ifdef _WIN32
    if (!s.no_gui) {
        /* Native dashboard: the server runs on a worker thread and the
           main thread hosts the dashboard window. */
        struct adblock_gui_args args;
        args.resource_dir = s.resource_dir;
        args.http_port = s.http_port;
        args.https_port = s.https_port;
        args.bind_all = s.bind_all;
        args.start_minimized = s.minimized;
        args.stats_port = s.stats_port;
        args.owns_server = true;     /* this process runs the server thread */
        args.startup_warning = startup_warning[0] ? startup_warning : NULL;
        struct server_thread_arg targ;
        targ.mgr = &mgr;
        targ.s = &s;
        pthread_t srv_thread;
        if (pthread_create(&srv_thread, NULL, server_thread_main, &targ) != 0) {
            LOG_FATAL("Failed to start server thread.");
            mg_mgr_free(&mgr);
            return EXIT_FAILURE;
        }
        LOG_INFO("Dashboard opened — close the window to shut down.");
        int rc = adblock_gui_run(&args);
        if (rc != 0) {
            /* Window creation failed (rare): keep the server running
               headless instead of dying silently. */
            LOG_FATAL("Dashboard window failed to start (exit code %d) - running headless. Press Ctrl+C to stop.", rc);
            while (s_sig_num == 0) Sleep(500);
            pthread_join(srv_thread, NULL);
            return 0;
        }
        s_sig_num = 1;   /* stop the poll loop */
        pthread_join(srv_thread, NULL);
        LOG_INFO("Server shut down (exit code %d).", rc);
#ifdef _WIN32
        if (win32_restart_requested()) {
            LOG_INFO("Restart requested - relaunching with the new settings...");
            wchar_t exeW[MAX_PATH], argsW[2048];
            if (GetModuleFileNameW(NULL, exeW, MAX_PATH) > 0) {
                swprintf(argsW, 2048, L"--resources \"%hs\"", s.resource_dir);
                ShellExecuteW(NULL, L"open", exeW, argsW, NULL, SW_SHOWNORMAL);
            }
        }
#endif
        return 0;
    }
#endif
    return server_loop_and_cleanup(&mgr, &s);
}

/* Run the mongoose poll loop until a stop signal, then persist + free.
   Used directly by the Android/headless builds and from a worker thread
   when the Windows dashboard window is shown. */
static int server_loop_and_cleanup(struct mg_mgr *mgr, struct settings *s) {
    /* Snapshot once at startup, then only when something actually changed and
       at most every 20 s (5 min when idle): writing 16 KB every few seconds for
       nothing would cost flash I/O and CPU on a phone. */
    write_stats_json_file(s);
    uint64_t last_json_ms = mg_millis();
    uint64_t last_json_counter = s_stats.total_requests + s_stats.total_connections;
    while (s_sig_num == 0) {
        mg_mgr_poll(mgr, 1000);
        uint64_t now_ms = mg_millis();
        uint64_t counter = s_stats.total_requests + s_stats.total_connections;
        if (now_ms - last_json_ms > 300000 ||
            (counter != last_json_counter && now_ms - last_json_ms > 20000)) {
            last_json_ms = now_ms;
            last_json_counter = counter;
            write_stats_json_file(s);
        }
    }
    save_stats(s);
    save_hist(s);   /* final flush of chart buckets on exit */
    sni_cache_save(s->resource_dir);  /* persist SNI cache (max hit rate) */
    apps_save(s->resource_dir);       /* persist per-app stats on exit */
    s_server_alive = 0;
    LOG_INFO("ADBlock webserver exiting (signal %d), stats saved", s_sig_num);

    LOG_INFO("Signal %d — shutting down.", s_sig_num);
    mg_mgr_free(mgr);

    /* Free SNI cache.
       BUG FIX: take the mutex before freeing so no concurrent
       sni_callback() thread is still walking the cache while we destroy
       it. After mg_mgr_free() all connections are closed and no new
       callbacks can be dispatched, but an abundance of caution is cheap. */
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++)
        if (s_sni_cache[i].ctx) SSL_CTX_free(s_sni_cache[i].ctx);
    pthread_mutex_unlock(&s_sni_mutex);
    pthread_mutex_destroy(&s_sni_mutex);

    /* Free CA in-memory objects */
    if (s->ca.cert) X509_free(s->ca.cert);
    if (s->ca.key)  EVP_PKEY_free(s->ca.key);

    free((void *)s->tls_opts.cert.buf);
    free((void *)s->tls_opts.key.buf);

    LOG_LOGCAT(ANDROID_LOG_INFO, "Clean shutdown.");
    return EXIT_SUCCESS;
}

 && *end != '|') end++;
        size_t len = (size_t) (end - s);
        if (len == 0 || len > 253 || s[0] == '.' ) continue;
        if (memchr(s, '.', len) == NULL) continue;    /* single labels are not real hosts */
        block_set_add_hash(fnv1a_lower(s, len));
        n++;
    }
    fclose(fp);
    if (n > 0) LOG_INFO("block set: %zu subscription domains loaded", n);
}

static void block_set_load(const char *resource_dir) {
    block_set_load_subscriptions(resource_dir);
    free(s_block_slots);
    s_block_slots = NULL;
    s_block_cap = 0;
    s_block_used = 0;
#ifndef _WIN32
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/blocklist.txt", resource_dir);
    size_t n = block_set_load_file(path);
    if (n == 0) {
        snprintf(path, sizeof(path), "/system/etc/hosts");
        n = block_set_load_file(path);
    }
    LOG_INFO("proxy: %zu blocked hosts loaded from %s", n, path);
#else
    (void) resource_dir;
#endif
}

static bool host_is_local(const char *h) {
    if (!h || !*h) return true;
    if (strcasecmp(h, "localhost") == 0) return true;
    if (strcasecmp(h, "localhost.localdomain") == 0) return true;
    if (strncmp(h, "127.", 4) == 0) return true;
    if (strcmp(h, "::1") == 0) return true;
    if (strncmp(h, "::ffff:127.", 11) == 0) return true;
    return false;
}

/* Element-hiding stylesheet injected into proxied HTML pages. */
static const char kHideCss[] =
    "<style id=\"adblock-hide\">"
    ".adsbygoogle,ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],"
    "[class^=\"ad-\"],[class^=\"ads-\"],[class^=\"advert\"],[id^=\"ad-\"],[id^=\"ads-\"],"
    "[class*=\" ad-\"],[class*=\" ads-\"],[data-ad-slot],[data-ad-client],"
    "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],"
    "iframe[src*=\"adsystem\"],[class$=\"-ad\"],[class$=\"-ads\"]"
    "{display:none!important;visibility:hidden!important}"
    "</style>";

/* Extract the host of an URL and test it against the block list. */
static bool url_host_blocked(const char *u, size_t n) {
    size_t i = 0;
    while (i < n && (u[i] == ' ' || u[i] == '\t')) i++;
    if (i + 1 < n && u[i] == '/' && u[i + 1] == '/') {
        i += 2;
    } else {
        size_t s = i;
        while (i < n && u[i] != ':' && u[i] != '/' && u[i] != '?' && u[i] != '#') i++;
        if (i < n && u[i] == ':') {
            i++;
            while (i < n && u[i] == '/') i++;
        } else {
            i = s;
        }
    }
    size_t start = i;
    while (i < n && u[i] != '/' && u[i] != ':' && u[i] != '?' && u[i] != '#' &&
           u[i] != '"' && u[i] != '\'' && u[i] != ' ' && u[i] != '\t') i++;
    size_t len = i - start;
    for (size_t k = start; k < start + len; k++) {
        if (u[k] == '@') { len -= (k - start + 1); start = k + 1; break; }
    }
    return len > 0 && block_set_contains(u + start, len);
}

/*
 * Rewrite an HTML body: drop external resource tags that point at a
 * blocked host and inject the element-hiding stylesheet after <head>.
 * Returns the new length (may exceed the input by the stylesheet size).
 */
/*
 * Cosmetic filtering: the app exports the AdGuard/adblock element hiding
 * rules (##selector) it collected from the sources to <resource>/cosmetic.css;
 * they are injected into every filtered page next to the built-in stylesheet.
 */
static char  *s_cosmetic_css;
static size_t s_cosmetic_len;

static void load_cosmetic_css(const char *resource_dir) {
    free(s_cosmetic_css);
    s_cosmetic_css = NULL;
    s_cosmetic_len = 0;
#ifndef _WIN32
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/cosmetic.css", resource_dir);
    FILE *f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > (long) (512 * 1024)) { fclose(f); return; }
    char *buffer = (char *) malloc((size_t) size + 1);
    if (!buffer) { fclose(f); return; }
    size_t got = fread(buffer, 1, (size_t) size, f);
    fclose(f);
    buffer[got] = '\0';
    s_cosmetic_css = buffer;
    s_cosmetic_len = got;
    LOG_INFO("cosmetic filter: %zu bytes loaded from %s", got, path);
#else
    (void) resource_dir;
#endif
}

static size_t html_filter(const char *in, size_t n, char *out, size_t cap) {
    size_t i = 0, o = 0;
    bool injected = false;
#define AB_PUT(ch) do { if (o < cap) out[o] = (char) (ch); o++; } while (0)
    while (i < n) {
        if (in[i] != '<') { AB_PUT(in[i]); i++; continue; }
        size_t j = i + 1;
        bool closing = false;
        if (j < n && in[j] == '/') { closing = true; j++; }
        size_t name_at = j;
        while (j < n && ((in[j] >= 'a' && in[j] <= 'z') || (in[j] >= 'A' && in[j] <= 'Z'))) j++;
        size_t name_len = j - name_at;
        if (name_len == 0) { AB_PUT(in[i]); i++; continue; }
        size_t k = j;
        bool inq = false;
        char q = 0;
        while (k < n) {
            char ch = in[k];
            if (inq) { if (ch == q) inq = false; }
            else if (ch == '"' || ch == '\'') { inq = true; q = ch; }
            else if (ch == '>') break;
            k++;
        }
        size_t tag_end = (k < n) ? k + 1 : n;
        if (!closing) {
            static const char *kRes[] = {"script", "iframe", "img", "link", "embed",
                                         "object", "source", "ins", "video", "audio", NULL};
            bool resource = false;
            for (int t = 0; kRes[t]; t++) {
                if (name_len == strlen(kRes[t]) &&
                    strncasecmp(in + name_at, kRes[t], name_len) == 0) { resource = true; break; }
            }
            if (resource) {
                static const char *kAttrs[] = {"src", "href", "data-src", "data-original", "poster", NULL};
                bool blocked = false;
                for (int t = 0; kAttrs[t] && !blocked; t++) {
                    size_t alen = strlen(kAttrs[t]);
                    for (size_t p = j; p + alen < tag_end; p++) {
                        if (strncasecmp(in + p, kAttrs[t], alen) != 0) continue;
                        if (p > j && in[p - 1] != ' ' && in[p - 1] != '\t' && in[p - 1] != '\n' && in[p - 1] != '\r') continue;
                        size_t v = p + alen;
                        while (v < tag_end && (in[v] == ' ' || in[v] == '\t')) v++;
                        if (v >= tag_end || in[v] != '=') continue;
                        v++;
                        while (v < tag_end && (in[v] == ' ' || in[v] == '\t')) v++;
                        char quote = 0;
                        if (v < tag_end && (in[v] == '"' || in[v] == '\'')) { quote = in[v]; v++; }
                        size_t vs = v;
                        while (v < tag_end && (quote ? in[v] != quote
                                                     : (in[v] != ' ' && in[v] != '>' && in[v] != '\t' && in[v] != '\n'))) v++;
                        if (url_host_blocked(in + vs, v - vs)) { blocked = true; break; }
                    }
                }
                if (blocked) { i = tag_end; continue; }   /* drop the whole tag */
            }
            if (!injected && name_len == 4 && strncasecmp(in + name_at, "head", 4) == 0) {
                for (size_t p = i; p < tag_end; p++) AB_PUT(in[p]);
                for (size_t p = 0; p < sizeof(kHideCss) - 1; p++) AB_PUT(kHideCss[p]);
                for (size_t p = 0; p < s_cosmetic_len; p++) AB_PUT(s_cosmetic_css[p]);
                injected = true;
                i = tag_end;
                continue;
            }
        }
        for (size_t p = i; p < tag_end; p++) AB_PUT(in[p]);
        i = tag_end;
    }
    if (!injected) {
        for (size_t p = 0; p < sizeof(kHideCss) - 1; p++) AB_PUT(kHideCss[p]);
        for (size_t p = 0; p < s_cosmetic_len; p++) AB_PUT(s_cosmetic_css[p]);
    }
#undef AB_PUT
    return o;
}

/*
 * Persist the very same JSON snapshot the app polls from /internal-stats, so
 * the statistics screens keep working when no HTTP port can be reached at all
 * (30xx port taken, server not running for a moment, ...).
 */
static void write_stats_json_file(struct settings *s) {
    if (!s || !s->init || !s->resource_dir[0]) return;
    static char body[16384];
    int n = build_stats_json(s, body, sizeof(body));
    if (n <= 0) return;
    char path[PATH_MAX], tmp[PATH_MAX];
    snprintf(path, sizeof(path), "%s/stats.json", s->resource_dir);
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    FILE *fp = fopen(tmp, "wb");
    if (!fp) return;
    fwrite(body, 1, (size_t) n, fp);
    fclose(fp);
    remove(path);
    rename(tmp, path);
}

/* ── proxy plumbing ── */
static void proxy_state_free(struct proxy_state *st) {
    if (!st) return;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] == st) { s_proxies[i] = NULL; s_proxy_active--; break; }
    }
    if (s_proxy_active < 0) s_proxy_active = 0;
    free(st->req);
    free(st->buf);
    free(st);
}

static void proxy_drop_for_client(struct mg_connection *c) {
    for (int i = 0; i < PROXY_MAX; i++) {
        struct proxy_state *st = s_proxies[i];
        if (st && st->client == c) {
            st->client = NULL;
            if (st->up) st->up->is_closing = 1;
        }
    }
}

static void proxy_send_raw(struct proxy_state *st, const char *data, size_t n) {
    if (st->client && n) mg_send(st->client, data, n);
}

static void proxy_fail(struct proxy_state *st, int code, const char *msg) {
    if (st->replied || !st->client) return;
    char head[320];
    int n = snprintf(head, sizeof(head),
                     "HTTP/1.1 %d %s\r\nContent-Type: text/plain; charset=utf-8\r\n"
                     "Content-Length: %u\r\nConnection: close\r\n\r\n%s",
                     code, msg, (unsigned) strlen(msg), msg);
    if (n > 0) mg_send(st->client, head, (size_t) n);
    st->replied = true;
    st->client->is_draining = 1;
}

static bool proxy_buf_append(struct proxy_state *st, const char *d, size_t n) {
    size_t need = st->buf_len + n;
    if (need > PROXY_BUF_MAX) return false;
    if (need > st->buf_cap) {
        size_t cap = st->buf_cap ? st->buf_cap : 65536;
        while (cap < need) cap *= 2;
        char *p = (char *) realloc(st->buf, cap);
        if (!p) return false;
        st->buf = p;
        st->buf_cap = cap;
    }
    memcpy(st->buf + st->buf_len, d, n);
    st->buf_len += n;
    return true;
}

/* Rebuild an HTML response with the filtered body. */
static void proxy_finish_filtered(struct proxy_state *st) {
    if (!st->client || !st->buf || !st->headers_done) return;
    size_t body_off = st->head_len;
    size_t body_len = st->buf_len > body_off ? st->buf_len - body_off : 0;
    size_t cap = body_len + 2048 + s_cosmetic_len;
    char *filtered = (char *) malloc(cap);
    if (!filtered) { proxy_send_raw(st, st->buf, st->buf_len); st->replied = true; return; }
    size_t flen = html_filter(st->buf + body_off, body_len, filtered, cap);
    /* status line first */
    size_t line_end = 0;
    while (line_end + 1 < body_off &&
           !(st->buf[line_end] == '\r' && st->buf[line_end + 1] == '\n')) line_end++;
    if (line_end + 2 > body_off) line_end = body_off - 2;
    mg_send(st->client, st->buf, line_end + 2);
    /* keep the origin headers except the ones we have to own */
    static const char *kDrop[] = {"content-length:", "transfer-encoding:", "content-encoding:",
                                  "connection:", "keep-alive:", NULL};
    size_t p = line_end + 2;
    while (p + 1 < body_off) {
        size_t e = p;
        while (e + 1 < body_off && !(st->buf[e] == '\r' && st->buf[e + 1] == '\n')) e++;
        size_t llen = e - p;
        if (llen == 0) break;
        bool skip = false;
        for (int t = 0; kDrop[t]; t++) {
            size_t dl = strlen(kDrop[t]);
            if (llen >= dl && strncasecmp(st->buf + p, kDrop[t], dl) == 0) { skip = true; break; }
        }
        if (!skip) mg_send(st->client, st->buf + p, llen + 2);
        p = e + 2;
    }
    char head[96];
    int hl = snprintf(head, sizeof(head),
                      "Content-Length: %u\r\nConnection: close\r\n\r\n", (unsigned) flen);
    if (hl > 0) mg_send(st->client, head, (size_t) hl);
    mg_send(st->client, filtered, flen);
    st->replied = true;
    st->client->is_draining = 1;
    free(filtered);
}

static void proxy_on_data(struct proxy_state *st, const char *data, size_t n) {
    if (st->headers_done) {
        if (!st->filter_body) { proxy_send_raw(st, data, n); st->replied = true; return; }
        if (!proxy_buf_append(st, data, n)) {
            /* response grew too large: stop filtering, stream what we have */
            st->filter_body = false;
            proxy_send_raw(st, st->buf, st->buf_len);
            st->replied = true;
            free(st->buf); st->buf = NULL; st->buf_len = 0; st->buf_cap = 0;
        }
        return;
    }
    if (!proxy_buf_append(st, data, n)) {
        proxy_fail(st, 502, "Response too large");
        if (st->up) st->up->is_closing = 1;
        return;
    }
    size_t hend = 0;
    for (size_t i = 0; i + 3 < st->buf_len; i++) {
        if (st->buf[i] == '\r' && st->buf[i + 1] == '\n' &&
            st->buf[i + 2] == '\r' && st->buf[i + 3] == '\n') { hend = i + 4; break; }
    }
    if (hend == 0) return;   /* headers still incomplete */
    char ctype[64];
    ctype[0] = '\0';
    bool encoded = false;
    for (size_t i = 0; i + 13 < hend; i++) {
        if (ctype[0] == '\0' && strncasecmp(st->buf + i, "content-type:", 13) == 0) {
            size_t v = i + 13;
            while (v < hend && (st->buf[v] == ' ' || st->buf[v] == '\t')) v++;
            size_t c = 0;
            while (v < hend && c < sizeof(ctype) - 1 &&
                   st->buf[v] != '\r' && st->buf[v] != '\n' && st->buf[v] != ';') ctype[c++] = st->buf[v++];
            ctype[c] = '\0';
        } else if (strncasecmp(st->buf + i, "content-encoding:", 17) == 0 ||
                   strncasecmp(st->buf + i, "transfer-encoding:", 18) == 0) {
            /* Compressed or chunked bodies must not be rewritten: stream them. */
            encoded = true;
        }
    }
    st->headers_done = true;
    st->head_len = hend;
    bool html = !encoded &&
                (strncasecmp(ctype, "text/html", 9) == 0 ||
                 strncasecmp(ctype, "application/xhtml", 17) == 0);
    if (html) {
        st->filter_body = true;
        return;
    }
    /* non-HTML: relay headers + body as they are */
    proxy_send_raw(st, st->buf, st->buf_len);
    st->replied = true;
    free(st->buf);
    st->buf = NULL;
    st->buf_len = 0;
    st->buf_cap = 0;
}

static void proxy_fn(struct mg_connection *c, int ev, void *ev_data) {
    (void) ev_data;
    struct proxy_state *st = (struct proxy_state *) c->fn_data;
    if (!st) return;
    if (ev == MG_EV_CONNECT) {
        if (st->tls) {
            struct mg_tls_opts o = {0};
            o.name = mg_str(st->host);
            o.skip_verification = true;   /* this server is a MITM by design */
            mg_tls_init(c, &o);
        }
        st->connected = true;
        return;
    }
    if (ev == MG_EV_ERROR) {
        proxy_fail(st, 502, "Bad Gateway");
        return;
    }
    if (ev == MG_EV_READ) {
        proxy_on_data(st, (const char *) c->recv.buf, c->recv.len);
        c->recv.len = 0;
        return;
    }
    if (ev == MG_EV_POLL) {
        uint64_t now = mg_millis();
        uint64_t limit = st->connected ? PROXY_TIMEOUT_MS : PROXY_CONNECT_TIMEOUT_MS;
        if (!st->replied && now - st->started_ms > limit) {
            proxy_fail(st, 504, "Gateway Timeout");
            c->is_closing = 1;
        }
        return;
    }
    if (ev == MG_EV_CLOSE) {
        if (st->filter_body && !st->replied && st->buf) proxy_finish_filtered(st);
        if (!st->replied) proxy_fail(st, 502, "Bad Gateway");
        if (st->client) st->client->is_draining = 1;
        proxy_state_free(st);
        c->fn_data = NULL;
    }
}

/* Is a proxy request currently in flight for this client connection? */
static bool proxy_busy(struct mg_connection *c) {
    if (s_proxy_active == 0) return false;   /* fast path: nothing to scan */
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] && s_proxies[i]->client == c) return true;
    }
    return false;
}

static bool proxy_start(struct mg_connection *c, struct settings *s,
                        struct mg_http_message *hm, const char *host) {
    (void) s;
    if (!s_mgr) return false;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (s_proxies[i] && s_proxies[i]->client == c) return false;
    }
    struct proxy_state *st = (struct proxy_state *) calloc(1, sizeof(*st));
    if (!st) return false;
    st->client = c;
    snprintf(st->host, sizeof(st->host), "%s", host);
    st->tls = c->is_tls ? true : false;
    st->port = st->tls ? 443 : 80;
    st->started_ms = mg_millis();

    size_t cap = 2048 + hm->head.len + hm->body.len;
    st->req = (char *) malloc(cap);
    if (!st->req) { free(st); return false; }
    int n = snprintf(st->req, cap, "%.*s %.*s HTTP/1.0\r\nHost: %s\r\n",
                     (int) hm->method.len, hm->method.buf,
                     (int) hm->uri.len, hm->uri.buf, st->host);
    if (n < 0) { free(st->req); free(st); return false; }
    size_t off = (size_t) n;
    /* Forward the client headers except hop-by-hop ones and
       Accept-Encoding (identity keeps HTML filterable). */
    const char *hdrs = hm->head.buf;
    size_t hlen = hm->head.len;
    size_t first_eol = 0;
    while (first_eol + 1 < hlen && !(hdrs[first_eol] == '\r' && hdrs[first_eol + 1] == '\n')) first_eol++;
    size_t p = (first_eol + 2 <= hlen) ? first_eol + 2 : hlen;
    static const char *kSkip[] = {"host:", "connection:", "proxy-connection:", "keep-alive:",
                                  "transfer-encoding:", "te:", "trailer:", "upgrade:",
                                  "accept-encoding:", "proxy-authorization:", "content-length:", NULL};
    while (p + 1 < hlen) {
        size_t e = p;
        while (e + 1 < hlen && !(hdrs[e] == '\r' && hdrs[e + 1] == '\n')) e++;
        size_t llen = e - p;
        if (llen == 0) break;
        bool skip = false;
        for (int t = 0; kSkip[t]; t++) {
            size_t sl = strlen(kSkip[t]);
            if (llen >= sl && strncasecmp(hdrs + p, kSkip[t], sl) == 0) { skip = true; break; }
        }
        if (!skip && off + llen + 2 < cap) {
            memcpy(st->req + off, hdrs + p, llen + 2);
            off += llen + 2;
        }
        p = e + 2;
    }
    if (hm->body.len > 0) {
        int bl = snprintf(st->req + off, cap - off, "Content-Length: %u\r\n\r\n",
                          (unsigned) hm->body.len);
        if (bl < 0) { free(st->req); free(st); return false; }
        off += (size_t) bl;
        memcpy(st->req + off, hm->body.buf, hm->body.len);
        off += hm->body.len;
    } else {
        memcpy(st->req + off, "\r\n", 2);
        off += 2;
    }
    st->req_len = off;

    char url[320];
    snprintf(url, sizeof(url), "tcp://%s:%d", st->host, st->port);
    struct mg_connection *up = mg_connect(s_mgr, url, proxy_fn, st);
    if (!up) { free(st->req); free(st); return false; }
    st->up = up;
    for (int i = 0; i < PROXY_MAX; i++) {
        if (!s_proxies[i]) { s_proxies[i] = st; s_proxy_active++; break; }
    }
    mg_send(up, st->req, st->req_len);
    if (s->debug) LOG_INFO("[proxy] %s%s", st->tls ? "https://" : "http://", st->host);
    return true;
}

static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        /* Atomic cap check + increment (see the note on
           s_active_connections above — defensive, not a live race). */
        for (;;) {
            int cur = atomic_load(&s_active_connections);
            if (cur >= MAX_CONNECTIONS) {
                c->is_closing = 1; return;
            }
            if (__atomic_compare_exchange_n(&s_active_connections, &cur,
                    cur + 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
                break;
        }
        /* Connections are counted when the first REAL request arrives (see the
           accounting block in fn()): the app and the dashboard poll
           /internal-stats once a second, and counting those accept()s made the
           connection totals and the hourly chart look like permanent traffic. */
        uint64_t t = mg_millis();
        memcpy(c->data, &t, sizeof(t));
        c->data[sizeof(t)] = 1;

        /* Per-app: the uid is resolved when the first request arrives
           (the connection is alive and present in /proc then). We just
           initialize the cached uid to -1 here. */
        conn_store_uid(c, (uid_t)-1);

        if (c->is_tls && c->fn_data) {
            struct settings *s = (struct settings *)c->fn_data;
            mg_tls_init(c, &s->tls_opts);

            /* Register SNI callback so we can issue per-domain certs */
            SSL_CTX *ctx = mg_conn_ssl_ctx(c);
            if (ctx) {
                SSL_CTX_set_tlsext_servername_callback(ctx, sni_callback);
                SSL_CTX_set_tlsext_servername_arg(ctx, &s->ca);
            }
        }
        return;
    }

    if (ev == MG_EV_CLOSE) {
        /* Drop any in-flight transparent-proxy request of this client. */
        proxy_drop_for_client(c);
        /* Unregister from the WS push list. */
        pthread_mutex_lock(&s_sni_mutex);
        for (int i = 0; i < WS_PUSH_MAX; i++) {
            if (ws_clients[i] == c) { ws_clients[i] = NULL; break; }
        }
        pthread_mutex_unlock(&s_sni_mutex);
        if (c->data[sizeof(uint64_t)]) atomic_sub_fetch(&s_active_connections, 1);
        return;
    }

    /* WebSocket: keep the connection open (client pulls or we push). */
    if (ev == MG_EV_WS_OPEN) {
        /* Register this connection as a push subscriber. */
        pthread_mutex_lock(&s_sni_mutex);  /* reuse cache mutex as a cheap lock */
        for (int i = 0; i < WS_PUSH_MAX; i++) {
            if (ws_clients[i] == NULL) { ws_clients[i] = c; break; }
        }
        pthread_mutex_unlock(&s_sni_mutex);
        /* Send a first snapshot immediately so the UI has data. */
        struct settings *ws_s = (struct settings *)c->fn_data;
        if (ws_s && ws_s->init) {
            char body[16384];
            int n = build_stats_json(ws_s, body, sizeof(body));
            if (n > 0) mg_ws_send(c, body, (size_t)n, WEBSOCKET_OP_TEXT);
        }
        return;
    }
    if (ev == MG_EV_WS_MSG) {
        return;
    }

    /* TLS handshake outcome statistics (new metric). */
    if (ev == MG_EV_TLS_HS) {
        s_stats.tls_handshakes++;
        return;
    }
    if (ev == MG_EV_ERROR && c->is_tls) {
        s_stats.tls_failures++;
        return;
    }

    /* Idle-timeout enforcement */
    if (ev == MG_EV_POLL && c->data[sizeof(uint64_t)]) {
        /* A transparent-proxy request may legitimately take longer than the
           idle timeout, so it is exempt while it is in flight. */
        if (proxy_busy(c)) return;
        uint64_t accepted_at; memcpy(&accepted_at, c->data, sizeof(accepted_at));
        if (mg_millis() - accepted_at > IDLE_TIMEOUT_MS) {
            c->is_draining = 1; return;
        }
    }

    if (ev != MG_EV_HTTP_MSG) return;
    struct mg_http_message *hm = (struct mg_http_message *)ev_data;
    struct settings *s = (struct settings *)c->fn_data;

    /* The management port only answers the internal endpoints. */
    if (s->stats_port != 0 && c->loc.port == (uint16_t) s->stats_port) {
        bool internal = mg_match(hm->uri, mg_str("/internal-stats"), NULL) ||
                        mg_match(hm->uri, mg_str("/internal-ws"), NULL) ||
                        mg_match(hm->uri, mg_str("/internal-test"), NULL) ||
                        mg_match(hm->uri, mg_str("/control"), NULL);
        if (!internal) {
            mg_http_reply(c, 404, "Content-Type: text/plain\r\n", "not found");
            return;
        }
    }

    /*
     * CAPTIVE PORTAL PROTECTION (Android connectivity check):
     * Android (and iOS/Windows) periodically probe well-known URLs to
     * decide whether the current network has real Internet access or is
     * a captive portal ("Sign in to network" notification). When the
     * hosts file redirects those probe domains to us, a non-204 reply
     * would make the OS believe a login page is being served, popping
     * the "network requires authentication" banner. Answer with 204 No
     * Content exactly like a healthy network would.
     *
     * Probes covered (host match OR path match):
     *  - connectivitycheck.gstatic.com/generate_204
     *  - clients3.google.com/generate_204
     *  - connectivitycheck.android.com/generate_204
     *  - /generate_204 /gen_204 /generate_204.php etc.
     *  - www.msftconnecttest.com/connecttest.txt (Windows)
     *  - captive.apple.com/hotspot-detect.html (iOS/macOS)
     * The hostname may arrive in the Host header, the SNI name (already
     * in s_stats), or both; matching by suffix keeps it robust.
     */
    static const char *kCaptiveHosts[] = {
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "connectivitycheck.oppomobile.com",
        "connectivitycheck.platform.hicloud.com",
        "connectivitycheck.miui.com",
        "connectivitycheck.vivoglobal.com",
        "connectivitycheck.vivo.com.cn",
        "connectivitycheck.samsung.com",
        "clients3.google.com",
        "www.msftconnecttest.com",
        "connecttest.com",
        "captive.apple.com",
        "gstatic.com",
        "detectportal.firefox.com",
        /* OPPO/ColorOS captive portal probe domains (conn-service-*.allawntech.com) */
        "allawntech.com",
        /* Chinese carriers captive portal domains */
        "wifi.cmcc.com",
        "portal.cmcc.com",
        "cmccwifi.com",
        "wlan.cmcc.com",
        "wifi.chinaunicom.cn",
        "portal.chinaunicom.cn",
        "wifi.189.cn",
        "portal.189.cn",
        "wifi.ctc.com.cn",
        "portal.ctc.com.cn",
        "cmcc.com",
        "chinaunicom.cn",
        "189.cn",
        "ctc.com.cn",
        NULL,
    };
    static const char *kCaptivePaths[] = {
        "/generate_204", "/generate204", "/gen_204", "/generate_204.php",
        "/connecttest.txt", "/hotspot-detect.html", "/hotspot-detect.html",
        /* CMCC/China Mobile specific captive portal paths */
        "/wlan/userip", "/wlan/ac_portal", "/wlan/login",
        "/portal/index.jsp", "/portal/auth.jsp", "/portal/login.jsp",
        "/eportal/index.jsp", "/eportal/auth.jsp", "/eportal/login.jsp",
        "/cmcc/wlan", "/cmcc/portal", "/cmcc/login",
        NULL,
    };
    /* Portal hosts that should be FULLY ALLOWED (not blocked) so the
       actual portal page can load (HTML, CSS, JS, images).
       These are the domains users visit when they click "Sign in to network". */
    static const char *kPortalHosts[] = {
        "wifi.cmcc.com",
        "portal.cmcc.com",
        "cmccwifi.com",
        "wlan.cmcc.com",
        "wifi.chinaunicom.cn",
        "portal.chinaunicom.cn",
        "wifi.189.cn",
        "portal.189.cn",
        "wifi.ctc.com.cn",
        "portal.ctc.com.cn",
        NULL,
    };
    bool captive = false;
    if (hm->uri.len > 0) {
        for (int i = 0; kCaptivePaths[i]; i++) {
            size_t plen = strlen(kCaptivePaths[i]);
            if (hm->uri.len >= plen &&
                mg_strcasecmp(mg_str_n(hm->uri.buf, plen), mg_str(kCaptivePaths[i])) == 0) {
                captive = true; break;
            }
        }
    }
    if (!captive) {
        struct mg_str *host_hdr = mg_http_get_header(hm, "Host");
        if (host_hdr != NULL && host_hdr->len > 0) {
            char host[256];
            size_t hl = host_hdr->len < sizeof(host) - 1 ? host_hdr->len : sizeof(host) - 1;
            memcpy(host, host_hdr->buf, hl); host[hl] = '\0';
            /* Strip an optional ":port" suffix from the Host header so
               "connectivitycheck.gstatic.com:80" still matches. */
            char *colon = strchr(host, ':');
            if (colon != NULL) *colon = '\0';
            for (int i = 0; kCaptiveHosts[i]; i++) {
                size_t klen = strlen(kCaptiveHosts[i]);
                size_t hlen = strlen(host);
                if (hlen >= klen && strcasecmp(host + hlen - klen, kCaptiveHosts[i]) == 0) {
                    captive = true; break;
                }
            }
        }
    }
    if (!captive && c->is_tls && c->tls) {
        /* SNI fallback: some HTTPS clients probe with a missing or
           odd Host header; the SNI name is authoritative here. */
        SSL *ssl = ((struct mg_tls_openssl *)c->tls)->ssl;
        if (ssl) {
            const char *sni = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
            if (sni && *sni) {
                for (int i = 0; kCaptiveHosts[i]; i++) {
                    size_t klen = strlen(kCaptiveHosts[i]);
                    size_t hlen = strlen(sni);
                    if (hlen >= klen && strcasecmp(sni + hlen - klen, kCaptiveHosts[i]) == 0) {
                        captive = true; break;
                    }
                }
            }
        }
    }
    if (captive) {
        /* 204 = "network is fine, no portal". Add CORS + no-store.
           total_requests / hist_add are only incremented after this
           early-return block, so no counter rollback is needed. */
        mg_http_reply(c, 204,
                      "Cache-Control: no-store, max-age=0\r\n"
                      "Access-Control-Allow-Origin: *\r\n", "");
        return;
    }

    /* Per-app request counter + record which TLS (SNI) hostname this
       app asked us to sign a certificate for. The uid is resolved once
       per connection (cached in c->data) to avoid /proc scans on every
       request of a keep-alive connection. */
    /* Only the uid cached when the connection was first seen. Resolving it
       scans /proc/net/tcp* for EVERY connection, which under the hijack mode
       (the whole device traffic) was the dominant CPU cost; blocked requests
       resolve it further down, where the allowlist decision needs it. */
    uid_t req_uid = conn_load_uid(c);

    /*
     * PER-APP ALLOWLIST: the app writes <resource_dir>/allowlist.txt
     * (one decimal uid per line, from the Settings > App monitoring
     * "allow" switches). A uid on the list has its traffic forwarded
     * as-is (200 OK with a tiny body) instead of being blocked, so
     * e.g. banking apps that need ads SDKs for auth still work.
     *
     * WebView renderers run under isolated uids (99000-99999) that are
     * allocated per renderer instance; map them to their host app first
     * so an "Allow"-ed app's WebView requests also pass through.
     */
    /* Blocked/local request: this is the minority path, so resolving the uid
       here keeps the per-connection /proc scan out of the proxy hot path. */
    if (req_uid == (uid_t)-1) {
        req_uid = conn_uid_by_tuple(c);
        conn_store_uid(c, req_uid);
    }
    uid_t chk_uid = req_uid;
    if (uid_is_isolated(req_uid)) {
        uid_t eff = resolve_effective_uid(req_uid);
        if (eff != req_uid && s_verbose) {
            LOG_INFO("allowlist: isolated uid %d -> host uid %d",
                     (int)req_uid, (int)eff);
        }
        chk_uid = eff;
    }

    /*
     * REQUEST / CONNECTION ACCOUNTING - exactly once per real request, and only
     * for real traffic. The app and the dashboard poll /internal-stats (plus
     * /internal-test and /control) once a second; counting that as user traffic
     * made 请求量 and 连接数 grow on a completely idle device.
     */
    bool internal_path = mg_match(hm->uri, mg_str("/internal-*"), NULL) ||
                         mg_match(hm->uri, mg_str("/control"), NULL);
    if (!internal_path) {
        if (c->data[CONN_REQ_OFFSET] == 0) {
            c->data[CONN_REQ_OFFSET] = 1;
            s_stats.total_connections++;
            hist_add(HIST_CONN);
        }
        s_stats.total_requests++;
        hist_add(HIST_REQ);
    }

    /*
     * TRANSPARENT FILTERING PROXY (hijack mode, --proxy-filter):
     * the app redirects the whole TCP 80/443 traffic here, so a request for
     * a host that is NOT in the block list has to be forwarded to the real
     * origin server instead of being answered locally. Blocked hosts keep
     * the placeholder replies below - except for uids the user put on the
     * per-app allowlist, whose traffic must never be blocked and is proxied
     * as well (previously such a request got a useless 2-byte "ok" reply).
     */
    if (s->proxy_filter) {
        struct mg_str *phdr = mg_http_get_header(hm, "Host");
        if (phdr != NULL && phdr->len > 0) {
            char phost[256];
            size_t pl = phdr->len < sizeof(phost) - 1 ? phdr->len : sizeof(phost) - 1;
            memcpy(phost, phdr->buf, pl);
            phost[pl] = '\0';
            if (phost[0] == '[') {                 /* IPv6 literal: [::1]:443 */
                char *b = strrchr(phost, ']');
                if (b != NULL) *b = '\0';
                memmove(phost, phost + 1, strlen(phost) + 1);
            } else {
                char *colon = strchr(phost, ':');
                if (colon != NULL) *colon = '\0';
            }
            /* Single-label hosts ("adaway", "localhost", printer names) are
               never proxied: they are local names or simply broken. */
            if (phost[0] != '\0' && strchr(phost, '.') != NULL && !host_is_local(phost)) {
                /* Allowlisted apps need no uid lookup here: their traffic is
                   proxied like every other non-blocked host. */
                if (!block_set_contains(phost, strlen(phost))) {
                    /* Already counted above: proxied requests are real traffic,
                       and counting them here too double-counted every request
                       whose proxy_start() failed. */
                    /* A pipelined request on a connection that is already
                       proxying is dropped (the first reply is still
                       streaming back to the client). */
                    if (proxy_busy(c)) return;
                    if (proxy_start(c, s, hm, phost)) {
                        qlog_add(req_uid, QLOG_PROXY, hm);
                        return;
                    }
                }
            }
        }
    }

    if (req_uid != (uid_t)-1 && uid_is_allowed(chk_uid, s->resource_dir)) {
        mg_http_reply(c, 200, "Content-Type: text/plain\r\n"
                              "Cache-Control: no-store\r\n", "ok");
        return;
    }

    /* (Request and connection counters are maintained further up, before the
       proxy branch, so every real request is counted exactly once.) */

    /* Check if this is a known portal host (user clicked "Sign in to network").
       If so, allow the request through so the portal page loads properly
       (HTML, CSS, JS, images) instead of being blocked by reply_blocked_by_type. */
    struct mg_str *host_hdr = mg_http_get_header(hm, "Host");
    if (host_hdr != NULL && host_hdr->len > 0) {
        char host[256];
        size_t hl = host_hdr->len < sizeof(host) - 1 ? host_hdr->len : sizeof(host) - 1;
        memcpy(host, host_hdr->buf, hl); host[hl] = '\0';
        char *colon = strchr(host, ':');
        if (colon != NULL) *colon = '\0';
        for (int i = 0; kPortalHosts[i]; i++) {
            size_t klen = strlen(kPortalHosts[i]);
            size_t hlen = strlen(host);
            if (hlen >= klen && strcasecmp(host + hlen - klen, kPortalHosts[i]) == 0) {
                /* Known portal host - serve a minimal page instead of blocking */
                mg_http_reply(c, 200,
                              "Content-Type: text/html; charset=utf-8\r\n"
                              "Cache-Control: no-store\r\n",
                              "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                              "<body style=\"font-family:sans-serif;padding:20px;text-align:center;\">"
                              "<h2>ADBlock: Portal Page Allowed</h2>"
                              "<p>This is a carrier portal domain. The page should load normally.</p>"
                              "<p>If you see this, the portal page resources are being allowed through.</p>"
                              "</body></html>");
                return;
            }
        }
    }

    /* Management traffic is not user traffic: attributing the app's own
       /internal-stats polls to its uid made the app itself show up as the
       busiest entry in the per-app statistics. */
    struct appstat *ra = internal_path ? NULL : app_find_or_add(req_uid);
    if (ra) {
        if (!c->data[REC_OFFSET]) {
            ra->connections++;
            c->data[REC_OFFSET] = 1;
        }
        ra->requests++;
    }
    if (c->is_tls && c->tls) {
        SSL *ssl = ((struct mg_tls_openssl *)c->tls)->ssl;
        if (ssl) {
            const char *sni = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
            if (sni && *sni) app_record_tls_host(req_uid, sni);
        }
    }

    /* Debug: log every request so blocked-domain traffic can be
       inspected (only when started with --debug). */
    if (s->debug) {
        LOG_INFO("[req] %.*s %.*s", (int)hm->method.len, hm->method.buf,
                 (int)hm->uri.len, hm->uri.buf);
    }

    /* CORS preflight: browsers send OPTIONS + Access-Control-Request-*
       before any cross-origin XHR with non-simple headers (e.g.
       Authorization). Answer 204 with permissive CORS headers so the
       subsequent real request proceeds and the SDK sees a successful
       exchange. */
    if (mg_strcasecmp(hm->method, mg_str("OPTIONS")) == 0 &&
        mg_http_get_header(hm, "Access-Control-Request-Method") != NULL) {
        mg_http_reply(c, 204,
                      "Access-Control-Allow-Origin: *\r\n"
                      "Access-Control-Allow-Methods: GET, HEAD, POST, PUT, DELETE, OPTIONS\r\n"
                      "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, Accept, Origin\r\n"
                      "Access-Control-Max-Age: 86400\r\n"
                      "Cache-Control: public, max-age=86400\r\n", "");
        return;
    }

    if (mg_match(hm->uri, mg_str("/internal-test"), NULL)) {
        struct mg_http_serve_opts o = {0};
        o.mime_types = "html=text/html";
        mg_http_serve_file(c, hm, s->test_path, &o);
        return;
    }

    /* Real-time WebSocket endpoint: upgrade to WS. The app may push
       requests here to receive a snapshot; on open we send one
       immediately, then the client keeps polling as fallback. */
    if (mg_match(hm->uri, mg_str("/internal-ws"), NULL)) {
        /*
         * SECURITY (audit H-1): the WS snapshot carries recent_tls[] (browsing
         * history). WebSocket is not subject to CORS, so a random web page could
         * read it; only the management port and non-browser callers (the app's
         * OkHttp client sends no Origin) may connect.
         */
        if (mg_http_get_header(hm, "Origin") != NULL ||
            s->stats_port == 0 || c->loc.port != (uint16_t) s->stats_port) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        mg_ws_upgrade(c, hm, NULL);
        return;
    }

    /* Internal statistics endpoint: JSON snapshot of the per-process
       counters (uptime, request totals, blocked-by-type breakdown,
       SNI certs issued). Like /internal-test it is only reachable on
       loopback; no auth needed since 127.0.0.1 is this device. */
    if (mg_match(hm->uri, mg_str("/internal-stats"), NULL)) {
        char body[16384];
        int n = build_stats_json(s, body, sizeof(body));
        mg_http_reply(c, 200,
                      "Content-Type: application/json\r\n"
                      "Cache-Control: no-store\r\n", "%.*s", n, body);
        /* Throttled: see persist_dat_files(). The dashboard polls this
           endpoint every few seconds, so an unconditional flush here used to
           rewrite ~270 KB (sni_cache.dat) plus three more files per poll. */
        persist_dat_files(s, false);
        qlog_save(s->resource_dir);
        return;
    }

    /* Control endpoint: reload resources, flush stats, shutdown. */
    if (mg_match(hm->uri, mg_str("/control"), NULL)) {
        /*
         * SECURITY: /control can reconfigure and shut the server down, and its
         * replies carry permissive CORS headers. Requests coming from a web
         * page (any Origin header) are therefore refused, which closes the
         * drive-by CSRF hole (a plain local caller sends no Origin).
         */
        if (mg_http_get_header(hm, "Origin") != NULL) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        /*
         * ... and it is only served on the loopback management port, so a LAN
         * client of "--bind all" cannot reconfigure or shut the server down.
         * Both clients (Windows dashboard and the Android app) already talk to
         * /control on that port.
         */
        if (s->stats_port == 0 || c->loc.port != (uint16_t) s->stats_port) {
            mg_http_reply(c, 403, "Content-Type: text/plain\r\n", "forbidden");
            return;
        }
        char cmd_buf[32];
        mg_http_get_var(&hm->body, "cmd", cmd_buf, sizeof(cmd_buf));
        struct mg_str cmd = mg_str(cmd_buf);
        if (mg_strcmp(cmd, mg_str("reload_config")) == 0) {
            load_block_cfg(s->resource_dir);
            load_block_modes(s->resource_dir);   /* per-type blocking method (mode_* keys) */
            /* Rules changed: refresh the transparent-proxy block set too. */
            if (s->proxy_filter) {
                block_set_load(s->resource_dir);
                load_cosmetic_css(s->resource_dir);
            }
            char hdr[96];
            int hl = snprintf(hdr, sizeof(hdr), "Content-Type: text/plain%c%c", 0x0d, 0x0a);
            (void) hl;
            mg_http_reply(c, 200, hdr, "OK: block config reloaded");
        } else if (mg_strcmp(cmd, mg_str("reload_images")) == 0) {
            s->block_image_count = scan_block_images(s->resource_dir, s->block_images);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: reloaded %d images", s->block_image_count);
        } else if (mg_strcmp(cmd, mg_str("flush_stats")) == 0) {
            persist_dat_files(s, true);   /* forced full flush */
        qlog_save(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: stats flushed");
        } else if (mg_strcmp(cmd, mg_str("reload_subscriptions")) == 0) {
            block_set_load(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: subscriptions reloaded");
        } else if (mg_strcmp(cmd, mg_str("clear_query_log")) == 0) {
            qlog_clear();
            qlog_save(s->resource_dir);
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: query log cleared");
        } else if (mg_strcmp(cmd, mg_str("shutdown")) == 0) {
            mg_http_reply(c, 200, "Content-Type: text/plain\r\n", "OK: shutting down");
            s_sig_num = SIGTERM;  /* trigger main loop exit */
        } else {
            mg_http_reply(c, 400, "Content-Type: text/plain\r\n", "Usage: cmd=reload_images|flush_stats|shutdown");
        }
        return;
    }

    /* Classify blocked requests by type and reply with the most
       realistic "empty" resource - see reply_blocked_by_type(). */
    if (reply_blocked_by_type(c, hm)) {
        if (s_rb_allow) {
            qlog_add(req_uid, QLOG_ALLOW, hm);
            /* 该类型被设为 BM_ALLOW（放行）：不拦截、不计入拦截统计、不返回
               占位资源，只回一个空的 200，由客户端自行处理。 */
            s_rb_allow = false;
            mg_http_reply(c, 200, CORS_HDR "Cache-Control: no-store\r\n", "");
            return;
        }
        qlog_add(conn_load_uid(c), QLOG_BLOCK, hm);
        hist_add(HIST_BLOCKED);
        struct appstat *ba = app_find_or_add(conn_load_uid(c));
        if (ba) ba->blocked++;
        ws_push_broadcast(s);  /* real-time push to WS subscribers */
        return;
    }


    /* Random block image - serve whichever actual filename was found at
       scan time (see scan_block_images()), not a reconstructed
       img_%02d.webp - deleting/adding images doesn't require renaming
       the rest to stay contiguous. */
    uint64_t t; memcpy(&t, c->data, sizeof(t));
    int idx = (int)(t % (uint64_t)s->block_image_count);
    char img_path[PATH_MAX];
    snprintf(img_path, sizeof(img_path), "%s/%s", s->resource_dir, s->block_images[idx]);
    s_stats.blocked_images++;
    hist_add(HIST_BLOCKED);
    struct appstat *ba = app_find_or_add(conn_load_uid(c));
    if (ba) ba->blocked++;
    ws_push_broadcast(s);  /* real-time push to WS subscribers */
    struct mg_http_serve_opts o = {0};
    o.mime_types = "webp=image/webp";
    /*
     * OPTIMIZATION: every blocked ad slot on every page load re-requests
     * this same placeholder image from the local server with no caching
     * hint at all, so the client re-fetches it every single time instead
     * of ever reusing a cached copy — needless disk I/O and CPU work on
     * a mobile device that may be handling this dozens of times a
     * minute. These images are static build resources that never change
     * at runtime, so let clients cache them.
     *
     * BUG FIX: this used to be "public, max-age=86400" - fine for the
     * built-in defaults, which really don't change at runtime, but the
     * app also lets a user replace them at any time via
     * WebServerUtils#setCustomBlockImage()/resetBlockImagesToDefault(),
     * which overwrite the same filenames in place. A client that had
     * already cached the old bytes under max-age=86400 won't even send
     * a new request - let alone a conditional one - for up to a day,
     * so a picked custom image (or a reset back to the default) could
     * silently not show up for hours. mg_http_serve_file() already
     * generates an ETag from each file's size+mtime and honors
     * If-None-Match (see mg_http_etag() in mongoose.c), so "no-cache"
     * keeps the win this header was added for - clients still cache the
     * bytes and, on every use, get back a cheap 304 with no body as
     * long as the file is actually unchanged - while making sure a
     * genuine content change (different size/mtime → different ETag) is
     * always picked up on the very next request instead of being stuck
     * behind a stale cache.
     */
    o.extra_headers = "Cache-Control: no-cache\r\n";
    mg_http_serve_file(c, hm, img_path, &o);
}

/* ── CLI parsing ──────────────────────────────────────────────── */
/*
 * Prepare the resource directory (CA, localhost leaf cert, block
 * images, paths). Creates the directory when it does not exist, so a
 * plain double-click works out of the box, and logs a clear reason
 * when the directory cannot be used. Returns true on success.
 */
static bool setup_resources_dir(struct settings *s, const char *rpath) {
    LOG_INFO("Resources dir: %s", rpath);
#ifdef _WIN32
    snprintf(s_crash_dir, sizeof(s_crash_dir), "%s", rpath);
    SetUnhandledExceptionFilter(crash_filter);   /* crash.log forensics */
#endif

    struct stat st;
    if (stat(rpath, &st) != 0 || (st.st_mode & S_IFDIR) == 0) {
        LOG_INFO("Resources dir '%s' not present — creating it…", rpath);
#ifdef _WIN32
        if (_mkdir(rpath) != 0) {
#else
        if (mkdir(rpath, 0755) != 0) {
#endif
            LOG_FATAL("Cannot create resources dir '%s' (errno %d). "
                      "Run: webserver --resources <writable directory>", rpath, errno);
            return false;
        }
    }

    char cert_path[PATH_MAX], key_path[PATH_MAX];
    snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", rpath);
    snprintf(key_path,  sizeof(key_path),  "%s/localhost-2410.key", rpath);

    /* Generate CA cert on first use */
    bool missing = (access(cert_path, F_OK) != 0 || access(key_path, F_OK) != 0);
    LOG_INFO("CA cert missing=%d (cert=%s key=%s)", missing, cert_path, key_path);
    if (missing) {
        LOG_INFO("Generating root CA…");
        if (generate_root_ca(cert_path, key_path) != EXIT_SUCCESS) {
            LOG_FATAL("CA generation failed (dir '%s'): make sure the directory is writable", rpath);
            return false;
        }
        LOG_INFO("Root CA generated OK");
    }

    /* Load CA into memory for SNI signing */
    if (load_ca(cert_path, key_path, &s->ca) != EXIT_SUCCESS) {
        LOG_FATAL("Failed to load CA");
        return false;
    }

    /* Cert rotation: regenerate when the CA nears expiry (30d). */
    if (maybe_rotate_ca(cert_path, key_path, &s->ca)) {
        LOG_INFO("CA rotated — app will prompt to reinstall");
    }
    LOG_INFO("CA loaded OK");

    /* TLS opts for the localhost listener: a leaf cert issued
       specifically for "localhost"/127.0.0.1, not the raw CA
       cert (see make_localhost_leaf() for why). */
    if (make_localhost_leaf(&s->ca, &s->tls_opts) != EXIT_SUCCESS) {
        LOG_FATAL("Failed to issue localhost leaf cert");
        return false;
    }
    LOG_INFO("localhost leaf cert issued OK");
    snprintf(s->resource_dir, sizeof(s->resource_dir), "%s", rpath);
    snprintf(s->test_path,    sizeof(s->test_path),    "%s/test.html", rpath);
    s->block_image_count = scan_block_images(rpath, s->block_images);
    s->init = true;
    return true;
}

static struct settings parse_cli_parameters(int argc, char *argv[]) {
    struct settings s = {0};
    /* Double-click friendly defaults (unprivileged ports; the Android
       app always passes --http-port/--https-port explicitly). */
    s.http_port = 8080;
    s.https_port = 8443;
    s.bind_all = false;
    const char *rpath = NULL;
    char resolved[PATH_MAX];
    memset(resolved, 0, sizeof(resolved));
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--resources") == 0 && i < argc-1) {
            rpath = argv[++i];
        } else if (strcmp(argv[i], "--debug") == 0) {
            s.debug = true;
        } else if (strcmp(argv[i], "--proxy-filter") == 0) {
            /* Transparent hijack proxy: forward non-blocked hosts to the
               real origin and filter the answer (see proxy_start()). */
            s.proxy_filter = true;
        } else if (strcmp(argv[i], "--stats-port") == 0 && i < argc-1) {
            /* Loopback management port: /internal-stats and /control are
               served there too, so the app can always read statistics even
               when the user facing 80/443 ports are taken by another app. */
            s.stats_port = atoi(argv[++i]);
            s.cli_stats_port_set = true;
        } else if (strcmp(argv[i], "--bind") == 0 && i < argc-1) {
            s.bind_all = strcmp(argv[++i], "all") == 0;
            s.cli_bind_set = true;
            LOG_INFO("Bind mode: %s", s.bind_all ? "all interfaces" : "loopback");
        } else if (strcmp(argv[i], "--http-port") == 0 && i < argc-1) {
            s.http_port = atoi(argv[++i]);
            s.cli_http_port_set = true;
            LOG_INFO("HTTP port: %d", s.http_port);
        } else if (strcmp(argv[i], "--https-port") == 0 && i < argc-1) {
            s.https_port = atoi(argv[++i]);
            s.cli_https_port_set = true;
            LOG_INFO("HTTPS port: %d", s.https_port);
        } else if (strcmp(argv[i], "--no-gui") == 0) {
            s.no_gui = true;
        } else if (strcmp(argv[i], "--minimized") == 0) {
            s.minimized = true;
        } else if (strcmp(argv[i], "--install-autostart") == 0) {
            s.autostart = 1;
        } else if (strcmp(argv[i], "--uninstall-autostart") == 0) {
            s.autostart = 2;
        }
    }

    /* Default / path-resolution: a plain double-click must work, so
       fall back to a 'resources' folder next to the executable and
       resolve relative --resources paths against the exe directory. */
    char default_dir[PATH_MAX];
    memset(default_dir, 0, sizeof(default_dir));
#ifdef _WIN32
    {
        char exe_path[PATH_MAX];
        memset(exe_path, 0, sizeof(exe_path));
        if (get_module_path_utf8(exe_path, (DWORD)sizeof(exe_path) - 1) > 0) {
            char *slash = strrchr(exe_path, '\\');
            if (slash) *slash = '\0';
            if (rpath != NULL && rpath[0] != '\\' && rpath[0] != '/' && strchr(rpath, ':') == NULL) {
                /* relative --resources: resolve against the exe dir */
                snprintf(resolved, sizeof(resolved), "%s\\%s", exe_path, rpath);
                rpath = resolved;
            } else if (rpath == NULL) {
                snprintf(default_dir, sizeof(default_dir), "%s\\resources", exe_path);
                rpath = default_dir;
                LOG_INFO("No --resources given — using %s", rpath);
            }
        }
    }
#endif
    if (rpath == NULL) {
        snprintf(default_dir, sizeof(default_dir), "resources");
        rpath = default_dir;
        LOG_INFO("No --resources given — using %s", rpath);
    }

    setup_resources_dir(&s, rpath);
    return s;
}

#ifndef ADBLOCK_APP_VERSION
#define ADBLOCK_APP_VERSION "1.15"
#endif

/* autostart entry location (same key as gui_win32.c) */
#define RUN_KEY_W L"Software\\Microsoft\\Windows\\CurrentVersion\\Run"
#define RUN_VALUE_W L"ADBlockWebServer"

/* ── main ─────────────────────────────────────────────────────── */
static int server_loop_and_cleanup(struct mg_mgr *mgr, struct settings *s);

/* Read by the Windows dashboard: 0 = the server loop has returned. */
static volatile int s_server_alive = 1;
bool win32_server_alive(void) { return s_server_alive != 0; }

#ifdef _WIN32
struct server_thread_arg { struct mg_mgr *mgr; struct settings *s; };
static void *server_thread_main(void *p) {
    struct server_thread_arg *a = (struct server_thread_arg *)p;
    server_loop_and_cleanup(a->mgr, a->s);
    return NULL;
}
#endif

int main(int argc, char *argv[]) {
#ifndef _WIN32
    setsid();   /* no-op on Windows: there is no controlling session */
#endif
    /* NOTE: do NOT redirect stdin/stdout/stderr to /dev/null here even
       though setsid() detaches us from the controlling terminal.
       ShellUtils.runBundledExecutable() launches this binary with
       `> logfile 2>&1` and relies on that file to diagnose startup
       failures (bad args, CA generation failure, port bind failure,
       ...) via LOG_FATAL/LOG_WARN/LOG_INFO, which write to both logcat
       and stdio. dup2()-ing the std fds to /dev/null would make that
       capture file permanently empty and silently defeat the
       diagnostic mechanism. The launching shell has already redirected
       our std fds, so there is no tty-sharing hazard to fix here. */

    /* DIAGNOSTIC CHECKPOINT 1: if this line never shows up in the log,
       the process is crashing during dynamic linking / static
       initialization (loading libssl.so/libcrypto.so/libc++_shared.so)
       before main() itself ever runs any of our code — a completely
       different class of bug than anything inside main()'s own logic. */
    LOG_INFO("main() entered, argc=%d", argc);

#ifdef _WIN32
    /*
     * Single instance. Autostart (HKCU Run) plus a manual start - or a previous
     * instance that has not exited yet - used to leave two identical
     * webserver.exe processes fighting for the same ports. The duplicate asks
     * the running instance to show its dashboard and exits immediately.
     */
    HANDLE single = CreateMutexW(NULL, FALSE, L"Local\\ADBlockWebServer_Singleton");
    if (single != NULL && GetLastError() == ERROR_ALREADY_EXISTS) {
        UINT show = RegisterWindowMessageW(L"ADBlockShowDashboard");
        if (show != 0) PostMessageW(HWND_BROADCAST, show, 0, 0);
        LOG_INFO("ADBlock: another instance is already running - exiting.");
        CloseHandle(single);
        return 0;
    }
#endif
    struct settings s = parse_cli_parameters(argc, argv);
    if (!s.init) {
        LOG_FATAL("Bad parameters.");
        return EXIT_FAILURE;
    }

    /* Mirror every LOG_* line into <resources>/webserver.log. The Windows
       build is a GUI-subsystem app (no console), so this file is the only place
       a failed bind / autostart problem can be diagnosed after the fact. */
    log_file_open(s.resource_dir);
    if (s.stats_port == 0) s.stats_port = 8686;

#ifdef _WIN32
    /* Settings persistence: webserver.ini next to the exe (written by
       the dashboard settings page); command-line flags still win. */
    {
        char exe[MAX_PATH];
        if (get_module_path_utf8(exe, sizeof(exe)) > 0) {
            char *slash = strrchr(exe, '\\');
            if (slash) *slash = '\0';
            char path[MAX_PATH + 32];
            snprintf(path, sizeof(path), "%s\\webserver.ini", exe);
            FILE *f = fopen(path, "r");
            if (f) {
                char line[128];
                while (fgets(line, sizeof(line), f)) {
                    int v;
                    if (sscanf(line, "http_port=%d", &v) == 1 && !s.cli_http_port_set)
                        s.http_port = v;
                    else if (sscanf(line, "https_port=%d", &v) == 1 && !s.cli_https_port_set)
                        s.https_port = v;
                    else if (sscanf(line, "bind_all=%d", &v) == 1 && !s.cli_bind_set)
                        s.bind_all = (v != 0);
                    else if (sscanf(line, "stats_port=%d", &v) == 1 && !s.cli_stats_port_set)
                        s.stats_port = v;
                }
                fclose(f);
                LOG_INFO("Loaded webserver.ini settings (ports %d/%d, bind_all=%d, stats_port=%d).",
                         s.http_port, s.https_port, s.bind_all ? 1 : 0, s.stats_port);
            }
        }
    }

    /* Autostart entry self-heal: older versions registered
       "--no-gui" (headless - no tray icon at all). Rewrite such entries
       to "--minimized" so the tray icon appears after logon. */
    {
        DWORD sz = 0;
        if (RegGetValueW(HKEY_CURRENT_USER, RUN_KEY_W, RUN_VALUE_W,
                RRF_RT_REG_SZ, NULL, NULL, &sz) == ERROR_SUCCESS && sz > 4) {
            wchar_t *val = (wchar_t *)malloc(sz);
            if (val) {
                if (RegGetValueW(HKEY_CURRENT_USER, RUN_KEY_W, RUN_VALUE_W,
                        RRF_RT_REG_SZ, NULL, val, &sz) == ERROR_SUCCESS) {
                    wchar_t *p = wcsstr(val, L"--no-gui");
                    if (p) {
                        wchar_t *newVal = (wchar_t *)malloc((wcslen(val) + 8) * sizeof(wchar_t));
                        if (newVal) {
                            wchar_t *d = newVal;
                            size_t pre = (size_t)(p - val);
                            memcpy(d, val, pre * sizeof(wchar_t));
                            d += pre;
                            wcscpy(d, L"--minimized");
                            d += wcslen(L"--minimized");
                            wcscpy(d, p + wcslen(L"--no-gui"));
                            HKEY hk2 = NULL;
                            if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY_W, 0,
                                    KEY_SET_VALUE, &hk2) == ERROR_SUCCESS) {
                                RegSetValueExW(hk2, RUN_VALUE_W, 0, REG_SZ,
                                    (const BYTE *)newVal,
                                    (DWORD)((wcslen(newVal) + 1) * sizeof(wchar_t)));
                                RegCloseKey(hk2);
                            }
                            LOG_INFO("Autostart entry upgraded: --no-gui -> --minimized (tray icon after logon).");
                            free(newVal);
                        }
                    }
                }
                free(val);
            }
        }
    }

    /* One-shot autostart registration (GUI toggle / CI) - do it before
       binding so the command never needs a port to be free. */
    if (s.autostart != 0) {
        char exe[MAX_PATH];
        if (get_module_path_utf8(exe, sizeof(exe)) == 0) {
            LOG_FATAL("Cannot resolve executable path.");
            return EXIT_FAILURE;
        }
        char cmd[2048];
        snprintf(cmd, sizeof(cmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d "
                 "--stats-port %d --minimized",
                 exe, s.resource_dir, s.http_port, s.https_port, s.stats_port);
        bool enable = (s.autostart == 1);
        int rc = win32_autostart_set(enable, cmd);
        LOG_INFO("Autostart %s %s (entry=%s).", enable ? "enabled" : "disabled",
                 rc == 0 ? "OK" : "FAILED",
                 win32_autostart_installed() ? "installed" : "absent");
        return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
    }
#endif

    /* If a server instance is already listening on the HTTP port (a
       previous launch, autostart, ...) do not bind again - open only the
       dashboard and poll the running instance, instead of silently
       exiting. */
    bool server_already_running = false;
#ifdef _WIN32
    if (s.stats_port == 0) s.stats_port = 8686;
    server_already_running = adblock_instance_running(s.stats_port);
    if (server_already_running) {
        if (s.no_gui) {
            LOG_INFO("Web server already running on port %d - nothing to do.", s.http_port);
            return EXIT_SUCCESS;
        }
        struct adblock_gui_args args;
        args.resource_dir = s.resource_dir;
        args.http_port = s.http_port;
        args.https_port = s.https_port;
        args.bind_all = s.bind_all;
        args.start_minimized = s.minimized;
        args.stats_port = s.stats_port;
        args.owns_server = false;    /* attached to the instance above */
        args.startup_warning = NULL;
        LOG_INFO("A web server is already running on port %d - dashboard only mode.", s.http_port);
        int rc = adblock_gui_run(&args);
        if (rc != 0)
            LOG_FATAL("Dashboard window could not be created (exit code %d).", rc);
        return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
    }
#endif

    s_verbose = s.debug;
    /* Without --debug, keep only errors: mongoose would otherwise log every
       connection/read/send, which is the main CPU cost under hijack mode. */
    mg_log_set(s.debug ? MG_LL_DEBUG : MG_LL_ERROR);

    s_stats.start_time_ms = mg_millis();
    load_stats(&s);  /* lifetime counters survive restarts */
    qlog_load(s.resource_dir);   /* recent request/decision history */
#ifdef _WIN32
    harden_ca_key_acl(s.resource_dir);   /* audit D2: key readable by the owner only */
#endif
    load_hist(&s);   /* chart buckets survive restarts (reboot-proof) */
    sni_cache_load(s.resource_dir);  /* SNI cert cache survives restarts */
    apps_load(s.resource_dir);       /* per-app stats survive restarts */

    oom_adjust_setup();

    struct mg_mgr mgr;
    mg_mgr_init(&mgr);
    /* Transparent proxy support: remember the event loop and load the
       blocked-host set from the system hosts file (Android). */
    s_mgr = &mgr;
    if (s.proxy_filter) {
        block_set_load(s.resource_dir);
        load_cosmetic_css(s.resource_dir);
    }
    /* Dedicated loopback management listener (statistics + control). */
    if (s.stats_port == 0) s.stats_port = 8686;
    {
        char su[64], su6[64];
        snprintf(su, sizeof(su), "http://127.0.0.1:%d", s.stats_port);
        snprintf(su6, sizeof(su6), "http://[::1]:%d", s.stats_port);
        if (bind_try(&mgr, su, fn, &s, "mgmt") == NULL) {
            LOG_FATAL("Management port %d is not available on 127.0.0.1 - "
                      "the dashboard cannot read statistics.", s.stats_port);
        }
        bind_try(&mgr, su6, fn, &s, "mgmt6");
    }

    /* Build listen URLs from the configured bind mode + ports. */
    char http_url[128], https_url[128], http_url6[128], https_url6[128];
    const char *v4 = s.bind_all ? "0.0.0.0" : "127.0.0.1";
    const char *v6 = s.bind_all ? "[::]" : "[::1]";
    snprintf(http_url, sizeof(http_url), "http://%s:%d", v4, s.http_port);
    snprintf(https_url, sizeof(https_url), "https://%s:%d", v4, s.https_port);
    snprintf(http_url6, sizeof(http_url6), "http://%s:%d", v6, s.http_port);
    snprintf(https_url6, sizeof(https_url6), "https://%s:%d", v6, s.https_port);

    char startup_warning[512] = {0};
    bool http_ok = (bind_try(&mgr, http_url, fn, &s, "http") != NULL);
    s_main_http_bound = http_ok;
    if (!http_ok) {
        LOG_FATAL("HTTP bind failed (%s): the port is probably already in use.", http_url);
        snprintf(startup_warning, sizeof(startup_warning),
                 "Port %d is already in use - the server could not bind. "
                 "Showing the dashboard anyway; close other instances or "
                 "change the port in Settings (Apply & Restart).", s.http_port);
#ifdef _WIN32
        if (s.no_gui) {
            mg_mgr_free(&mgr);
            return EXIT_FAILURE;
        }
#else
        mg_mgr_free(&mgr);
        return EXIT_FAILURE;
#endif
    }
    if (bind_try(&mgr, https_url, fn, &s, "https") == NULL) {
        LOG_WARN("HTTPS bind failed (%s) - continuing without HTTPS.", https_url);
    }
    /*
     * IPv6 loopback listeners (::1) - optional. Devices with IPv6
     * disabled fail to bind these; that is fine, the IPv4 listeners
     * above still serve. Both must succeed for ipv6_ok so the ready
     * log reflects the actual state.
     */
    bool ipv6_ok = true;
    if (bind_try(&mgr, http_url6, fn, &s, "http6") == NULL) {
        LOG_WARN("HTTP IPv6 bind failed (%s) — continuing with IPv4 only.", http_url6);
        ipv6_ok = false;
    }
    if (bind_try(&mgr, https_url6, fn, &s, "https6") == NULL) {
        LOG_WARN("HTTPS IPv6 bind failed (%s) — continuing with IPv4 only.", https_url6);
        ipv6_ok = false;
    }

    /* Extra ad-block monitoring: the hosts-file redirect targets ports
       80 (HTTP) and 443 (HTTPS). Always try to monitor them in addition
       to the configured ports. Binding them needs Administrator on
       Windows (or the port must be free); failures are only warnings -
       the configured ports keep working. */
    {
        char u[128];
        if (s.http_port != 80) {
            snprintf(u, sizeof(u), "http://127.0.0.1:80");
            if (bind_try(&mgr, u, fn, &s, "mon80") == NULL)
                LOG_WARN("Monitoring http://127.0.0.1:80 failed (admin needed or port in use).");
            snprintf(u, sizeof(u), "http://[::1]:80");
            if (bind_try(&mgr, u, fn, &s, "mon80-6") == NULL)
                LOG_WARN("Monitoring http://[::1]:80 failed (admin needed or port in use).");
        }
        if (s.https_port != 443) {
            snprintf(u, sizeof(u), "https://127.0.0.1:443");
            if (bind_try(&mgr, u, fn, &s, "mon443") == NULL)
                LOG_WARN("Monitoring https://127.0.0.1:443 failed (admin needed or port in use).");
            snprintf(u, sizeof(u), "https://[::1]:443");
            if (bind_try(&mgr, u, fn, &s, "mon443-6") == NULL)
                LOG_WARN("Monitoring https://[::1]:443 failed (admin needed or port in use).");
        }
    }

    load_block_cfg(s.resource_dir);
    load_block_modes(s.resource_dir);   /* per-type blocking method (mode_* keys) */
    setup_signal_handler();
    /* Stay alive under memory pressure and log native crashes. */
    setup_crash_handler(s.resource_dir);
    harden_process();

    LOG_INFO("ADBlock Web Server v" ADBLOCK_APP_VERSION " ready — Mongoose " MG_VERSION
        ", SNI cert issuance enabled, IPv6 loopback %s.",
        ipv6_ok ? "on" : "off");

#ifdef _WIN32
    if (!s.no_gui) {
        /* Native dashboard: the server runs on a worker thread and the
           main thread hosts the dashboard window. */
        struct adblock_gui_args args;
        args.resource_dir = s.resource_dir;
        args.http_port = s.http_port;
        args.https_port = s.https_port;
        args.bind_all = s.bind_all;
        args.start_minimized = s.minimized;
        args.stats_port = s.stats_port;
        args.owns_server = true;     /* this process runs the server thread */
        args.startup_warning = startup_warning[0] ? startup_warning : NULL;
        struct server_thread_arg targ;
        targ.mgr = &mgr;
        targ.s = &s;
        pthread_t srv_thread;
        if (pthread_create(&srv_thread, NULL, server_thread_main, &targ) != 0) {
            LOG_FATAL("Failed to start server thread.");
            mg_mgr_free(&mgr);
            return EXIT_FAILURE;
        }
        LOG_INFO("Dashboard opened — close the window to shut down.");
        int rc = adblock_gui_run(&args);
        if (rc != 0) {
            /* Window creation failed (rare): keep the server running
               headless instead of dying silently. */
            LOG_FATAL("Dashboard window failed to start (exit code %d) - running headless. Press Ctrl+C to stop.", rc);
            while (s_sig_num == 0) Sleep(500);
            pthread_join(srv_thread, NULL);
            return 0;
        }
        s_sig_num = 1;   /* stop the poll loop */
        pthread_join(srv_thread, NULL);
        LOG_INFO("Server shut down (exit code %d).", rc);
#ifdef _WIN32
        if (win32_restart_requested()) {
            LOG_INFO("Restart requested - relaunching with the new settings...");
            wchar_t exeW[MAX_PATH], argsW[2048];
            if (GetModuleFileNameW(NULL, exeW, MAX_PATH) > 0) {
                swprintf(argsW, 2048, L"--resources \"%hs\"", s.resource_dir);
                ShellExecuteW(NULL, L"open", exeW, argsW, NULL, SW_SHOWNORMAL);
            }
        }
#endif
        return 0;
    }
#endif
    return server_loop_and_cleanup(&mgr, &s);
}

/* Run the mongoose poll loop until a stop signal, then persist + free.
   Used directly by the Android/headless builds and from a worker thread
   when the Windows dashboard window is shown. */
static int server_loop_and_cleanup(struct mg_mgr *mgr, struct settings *s) {
    /* Snapshot once at startup, then only when something actually changed and
       at most every 20 s (5 min when idle): writing 16 KB every few seconds for
       nothing would cost flash I/O and CPU on a phone. */
    write_stats_json_file(s);
    uint64_t last_json_ms = mg_millis();
    uint64_t last_json_counter = s_stats.total_requests + s_stats.total_connections;
    while (s_sig_num == 0) {
        mg_mgr_poll(mgr, 1000);
        uint64_t now_ms = mg_millis();
        uint64_t counter = s_stats.total_requests + s_stats.total_connections;
        if (now_ms - last_json_ms > 300000 ||
            (counter != last_json_counter && now_ms - last_json_ms > 20000)) {
            last_json_ms = now_ms;
            last_json_counter = counter;
            write_stats_json_file(s);
        }
    }
    save_stats(s);
    save_hist(s);   /* final flush of chart buckets on exit */
    sni_cache_save(s->resource_dir);  /* persist SNI cache (max hit rate) */
    apps_save(s->resource_dir);       /* persist per-app stats on exit */
    s_server_alive = 0;
    LOG_INFO("ADBlock webserver exiting (signal %d), stats saved", s_sig_num);

    LOG_INFO("Signal %d — shutting down.", s_sig_num);
    mg_mgr_free(mgr);

    /* Free SNI cache.
       BUG FIX: take the mutex before freeing so no concurrent
       sni_callback() thread is still walking the cache while we destroy
       it. After mg_mgr_free() all connections are closed and no new
       callbacks can be dispatched, but an abundance of caution is cheap. */
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++)
        if (s_sni_cache[i].ctx) SSL_CTX_free(s_sni_cache[i].ctx);
    pthread_mutex_unlock(&s_sni_mutex);
    pthread_mutex_destroy(&s_sni_mutex);

    /* Free CA in-memory objects */
    if (s->ca.cert) X509_free(s->ca.cert);
    if (s->ca.key)  EVP_PKEY_free(s->ca.key);

    free((void *)s->tls_opts.cert.buf);
    free((void *)s->tls_opts.key.buf);

    LOG_LOGCAT(ANDROID_LOG_INFO, "Clean shutdown.");
    return EXIT_SUCCESS;
}

