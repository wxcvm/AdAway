
/*
 * gui_win32.c - native Windows 11 dashboard for the ADBlock web server.
 * Windows-only: this file is not part of the Android build.
 *
 * UI: AdGuard-style - dark sidebar navigation + light content area,
 * rounded cards, flat modern palette, DPI-aware (PerMonitorV2) and
 * fully Chinese labels (Microsoft YaHei UI).
 */
#define _WIN32_WINNT 0x0A00   /* PerMonitorV2 / GetDpiForWindow */
#define UNICODE
#define _UNICODE
#include <winsock2.h>
#include "update_win32.h"
#include <windows.h>
#include <shellapi.h>
#include <iphlpapi.h>
#include <wincrypt.h>
#include <dwmapi.h>
#include <uxtheme.h>
#include <psapi.h>
#include <tlhelp32.h>

#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <stdint.h>
#include <wchar.h>

#include <openssl/x509.h>
#include <openssl/pem.h>

#include "gui_win32.h"

/* --- theme (persisted in webserver.ini as theme=0/1/2) ---------------- */
#define THEME_SYSTEM (-1)
static int g_theme_pref = THEME_SYSTEM;   /* -1 system, 0 light, 1 dark */
static int g_dark = 0;                    /* resolved theme actually drawn */

#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif

/* Windows 11 rounded corners + immersive dark title bar (no-op on older
 * builds: DwmSetWindowAttribute simply fails). */
static void apply_window_chrome(HWND hwnd, int dark) {
    BOOL d = dark ? TRUE : FALSE;
    DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, &d, sizeof(d));
    DWORD pref = 2;   /* DWMWCP_ROUND */
    DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &pref, sizeof(pref));
}

/* Best-effort dark styling for the native child controls (edit boxes and
 * check boxes); custom-drawn areas always honour the palette. */
static void apply_control_theme(HWND hwnd) {
    static const wchar_t *classes[2] = { L"EDIT", L"BUTTON" };
    for (int c = 0; c < 2; c++) {
        HWND w = FindWindowExW(hwnd, NULL, classes[c], NULL);
        while (w != NULL) {
            SetWindowTheme(w, g_dark ? L"DarkMode_Explorer" : L"Explorer", NULL);
            w = FindWindowExW(hwnd, w, classes[c], NULL);
        }
    }
}

/* --- restart coordination (old instance stops server, then relaunches) --- */
static bool g_restart_requested = false;
void win32_notify_restart(void) { g_restart_requested = true; }
bool win32_restart_requested(void) { return g_restart_requested; }

/* ── scale helpers (all layout is in 96-DPI logical units) ─────── */
static double g_scale = 1.0;
#define S(v) ((int)((double)(v) * g_scale))

static HFONT mfont(int size, int weight) {
    return CreateFontW(S(size), 0, 0, 0, weight, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_SWISS, L"Microsoft YaHei UI");
}

static void utf8_to_wide(const char *src, wchar_t *dst, size_t n) {
    if (n == 0) return;
    MultiByteToWideChar(CP_UTF8, 0, src, -1, dst, (int)n);
    dst[n - 1] = L'\0';
}

/* ── HTTP statistics polling ────────────────────────────────────── */

#define HIST_MAX 48
#define STEAL_JSON_BUF 16384
#define LISTENER_MAX_GUI 14

/* One socket the server reported through /internal-stats. */
struct listener_info {
    wchar_t name[20];
    wchar_t addr[96];
    int bound, ipv6, loopback;
};

struct histogram {
    long long requests;
    long long blocked;
};

struct snapshot {
    long long uptime_seconds;
    long long total_requests;
    long long total_connections;
    long long tls_handshakes;
    long long sni_certs_issued;
    long long sni_cache_hits;
    double block_rate;
    double sni_hit_rate;
    struct histogram hist[HIST_MAX];
    int hist_count;
    struct histogram daily[HIST_MAX];
    int daily_count;
    int valid;
    /* v1.11 listener diagnostics (absent -> zeros) */
    int bind_ok;
    int stats_port;
    int listener_count;
    struct listener_info listeners[LISTENER_MAX_GUI];
};

/* ── shared dashboard state ─────────────────────────────────────────
 * The statistics and certificate helpers below are defined before the GUI
 * state block, so the state they touch is declared here as tentative
 * definitions (the initialised definitions live further down the file). */
#ifndef WM_APP_STATS
#define WM_APP_STATS (WM_USER + 3)
#endif
#define CERT_CACHE_MS 30000
static char g_res[512];
static int  g_http_port;
static int  g_https_port;
static bool g_bind_all;
static bool g_start_minimized;
static int  g_tab;
static int  g_stats_port;
static int  g_active_port;
static int  g_poll_started;
static volatile int g_poll_stop;
static pthread_t g_poll_thread;
static pthread_mutex_t g_snap_mutex;
static struct snapshot g_snap_next;
static struct snapshot *g_sn;
static long long g_cert_days;
static int  g_cert_trusted_flag;
static DWORD g_cert_checked_tick;
static long long cert_days_left(const char *cert_path);
static int  cert_trusted(const char *cert_path);
/* Polling + self-measurement state (definitions with initialisers live in the
 * GUI state block further down; these tentative declarations let the helpers
 * that appear above it compile). */
static long long g_poll_sig;
static DWORD g_poll_last_post;
static int  g_hb_ticks;
static bool g_owns_server;
static volatile int g_ui_visible;
static int  g_hidden_trimmed;
static double g_cpu_pct;
static unsigned long long g_ws_kb;
static DWORD g_gdi_obj, g_user_obj, g_thread_count, g_handle_count;
static ULONGLONG g_cpu_prev_ms;
static DWORD g_cpu_prev_tick;

static long long json_num(const char *body, const char *key) {
    char pat[64];
    int pl = snprintf(pat, sizeof(pat), "\"%s\":", key);
    if (pl <= 0 || (size_t)pl >= sizeof(pat)) return 0;
    const char *p = strstr(body, pat);
    if (!p) return 0;
    p += pl;
    while (*p == ' ' || *p == '\t') p++;
    return strtoll(p, NULL, 10);
}

static double json_double(const char *body, const char *key) {
    char pat[64];
    int pl = snprintf(pat, sizeof(pat), "\"%s\":", key);
    if (pl <= 0 || (size_t)pl >= sizeof(pat)) return 0.0;
    const char *p = strstr(body, pat);
    if (!p) return 0.0;
    p += pl;
    while (*p == ' ' || *p == '\t') p++;
    return strtod(p, NULL);
}

static int json_hist(const char *body, const char *key,
                     struct histogram *out, int max) {
    char pat[64];
    int pl = snprintf(pat, sizeof(pat), "\"%s\":[", key);
    if (pl <= 0 || (size_t)pl >= sizeof(pat)) return 0;
    const char *p = strstr(body, pat);
    if (!p) return 0;
    p += pl;
    int count = 0;
    while (count < max && *p) {
        const char *open = strchr(p, '{');
        if (!open) break;
        const char *close = strchr(open, '}');
        if (!close) break;
        char item[512];
        size_t il = (size_t)(close - open + 1);
        if (il >= sizeof(item)) il = sizeof(item) - 1;
        memcpy(item, open, il);
        item[il] = '\0';
        out[count].requests = json_num(item, "requests");
        out[count].blocked = json_num(item, "blocked");
        count++;
        p = close + 1;
    }
    return count;
}

/* Parses stats_port / bind_ok / listeners[] from the /internal-stats JSON so
 * the dashboard can show exactly which sockets the server owns.  Tolerates an
 * older server that does not emit the fields (they simply stay zero). */
static void json_listeners(const char *body, struct snapshot *sn) {
    sn->listener_count = 0;
    sn->bind_ok = strstr(body, "\"bind_ok\":true") != NULL;
    sn->stats_port = (int)json_num(body, "stats_port");
    const char *p = strstr(body, "\"listeners\":[");
    if (p == NULL) return;
    p = strchr(p, '[');
    if (p == NULL) return;
    p++;
    while (sn->listener_count < LISTENER_MAX_GUI) {
        const char *open = strchr(p, '{');
        if (open == NULL) break;
        const char *close = strchr(open, '}');
        if (close == NULL) break;
        char item[384];
        size_t il = (size_t)(close - open) + 1;
        if (il >= sizeof(item)) il = sizeof(item) - 1;
        memcpy(item, open, il);
        item[il] = '\0';
        struct listener_info *li = &sn->listeners[sn->listener_count];
        memset(li, 0, sizeof(*li));
        const char *q = strstr(item, "\"name\":\"");
        if (q != NULL) {
            q += 8;
            const char *e = strchr(q, '"');
            if (e != NULL && e > q)
                MultiByteToWideChar(CP_UTF8, 0, q, (int)(e - q), li->name, 19);
        }
        q = strstr(item, "\"addr\":\"");
        if (q != NULL) {
            q += 8;
            const char *e = strchr(q, '"');
            if (e != NULL && e > q)
                MultiByteToWideChar(CP_UTF8, 0, q, (int)(e - q), li->addr, 95);
        }
        li->bound = strstr(item, "\"bound\":true") != NULL;
        li->ipv6 = strstr(item, "\"ipv6\":true") != NULL;
        li->loopback = strstr(item, "\"loopback\":true") != NULL;
        sn->listener_count++;
        p = close + 1;
    }
}

static int http_get(int port, const char *path, char *out, size_t outsz) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return -1;
    SOCKADDR_IN addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    int rc = -1;
    if (connect(s, (SOCKADDR *)&addr, sizeof(addr)) == 0) {
        char req[256];
        int rl = snprintf(req, sizeof(req),
            "GET %s HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            path);
        if (rl > 0 && send(s, req, rl, 0) == rl) {
            size_t got = 0;
            while (got + 1 < outsz) {
                fd_set fds;
                FD_ZERO(&fds); FD_SET(s, &fds);
                struct timeval tv; tv.tv_sec = 1; tv.tv_usec = 0;
                if (select(0, &fds, NULL, NULL, &tv) <= 0) break;
                int n = recv(s, out + got, (int)(outsz - got - 1), 0);
                if (n <= 0) break;
                got += (size_t)n;
            }
            out[got] = '\0';
            rc = (got > 0) ? 0 : -1;
        }
    }
    closesocket(s);
    return rc;
}

static void snapshot_fetch(int port, struct snapshot *sn) {
    static char buf[STEAL_JSON_BUF];
    memset(sn, 0, sizeof(*sn));
    if (http_get(port, "/internal-stats", buf, sizeof(buf)) != 0) return;
    sn->uptime_seconds = json_num(buf, "uptime_seconds");
    sn->total_requests = json_num(buf, "total_requests");
    sn->total_connections = json_num(buf, "total_connections");
    sn->tls_handshakes = json_num(buf, "tls_handshakes");
    sn->sni_certs_issued = json_num(buf, "sni_certs_issued");
    sn->sni_cache_hits = json_num(buf, "sni_cache_hits");
    sn->block_rate = json_double(buf, "block_rate");
    sn->sni_hit_rate = json_double(buf, "sni_hit_rate");
    sn->hist_count = json_hist(buf, "history", sn->hist, HIST_MAX);
    sn->daily_count = json_hist(buf, "daily", sn->daily, HIST_MAX);
    json_listeners(buf, sn);
    sn->valid = 1;
}

/* Try the loopback management port first (it exists exactly so statistics
   stay readable even when the user facing ports are taken or bound to LAN),
   then fall back to the main HTTP port. */
static void snapshot_fetch_any(struct snapshot *sn) {
    if (g_stats_port > 0 && g_stats_port != g_http_port) {
        snapshot_fetch(g_stats_port, sn);
        if (sn->valid) { g_active_port = g_stats_port; return; }
    }
    snapshot_fetch(g_http_port, sn);
    if (sn->valid) g_active_port = g_http_port;
}

/* One cheap self-measurement per timer tick. */
static void diag_sample(void) {
    FILETIME ct, et, kt, ut;
    if (GetProcessTimes(GetCurrentProcess(), &ct, &et, &kt, &ut)) {
        ULARGE_INTEGER K, U;
        K.LowPart = kt.dwLowDateTime; K.HighPart = kt.dwHighDateTime;
        U.LowPart = ut.dwLowDateTime; U.HighPart = ut.dwHighDateTime;
        ULONGLONG cpu_ms = (K.QuadPart + U.QuadPart) / 10000ULL;
        DWORD now = GetTickCount();
        if (g_cpu_prev_tick != 0 && now != g_cpu_prev_tick) {
            double wall = (double)(now - g_cpu_prev_tick);
            double used = (double)(cpu_ms - g_cpu_prev_ms);
            SYSTEM_INFO si;
            GetSystemInfo(&si);
            double ncpu = si.dwNumberOfProcessors > 0 ? (double)si.dwNumberOfProcessors : 1.0;
            double pct = used / (wall * ncpu) * 100.0;
            g_cpu_pct = (pct < 0.0 || pct > 100.0) ? 0.0 : pct;
        }
        g_cpu_prev_ms = cpu_ms;
        g_cpu_prev_tick = now;
    }
    PROCESS_MEMORY_COUNTERS pmc;
    memset(&pmc, 0, sizeof(pmc));
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc)))
        g_ws_kb = (unsigned long long)pmc.WorkingSetSize / 1024ULL;
    g_gdi_obj = GetGuiResources(GetCurrentProcess(), GR_GDIOBJECTS);
    g_user_obj = GetGuiResources(GetCurrentProcess(), GR_USEROBJECTS);
    g_handle_count = 0;
    GetProcessHandleCount(GetCurrentProcess(), &g_handle_count);
    g_thread_count = 0;
    {
        HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
        if (snap != INVALID_HANDLE_VALUE) {
            THREADENTRY32 te;
            memset(&te, 0, sizeof(te));
            te.dwSize = sizeof(te);
            DWORD pid = GetCurrentProcessId();
            if (Thread32First(snap, &te)) {
                do { if (te.th32OwnerProcessID == pid) g_thread_count++; } while (Thread32Next(snap, &te));
            }
            CloseHandle(snap);
        }
    }
}

/* Writes diagnose.txt (system + process + listener + log tail) so a user can
 * send one file instead of describing the problem. */
static void diag_write_file(HWND hwnd) {
    char path[MAX_PATH + 32];
    if (GetModuleFileNameA(NULL, path, MAX_PATH) <= 0) return;
    char *slash = strrchr(path, '\\');
    if (slash) *slash = '\0';
    char out[MAX_PATH + 48];
    snprintf(out, sizeof(out), "%s\\diagnose.txt", path);
    FILE *f = fopen(out, "w");
    if (!f) {
        MessageBoxW(hwnd, L"无法写入 diagnose.txt（目录不可写）。", L"诊断", MB_OK | MB_ICONWARNING);
        return;
    }
    fprintf(f, "ADBlock Web Server v%s - diagnose.txt\n", ADBLOCK_APP_VERSION);
    SYSTEMTIME st;
    GetLocalTime(&st);
    fprintf(f, "time: %04d-%02d-%02d %02d:%02d:%02d\n", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
    OSVERSIONINFOEXW os;
    memset(&os, 0, sizeof(os));
    os.dwOSVersionInfoSize = sizeof(os);
    if (GetVersionExW((OSVERSIONINFOW *)&os))
        fprintf(f, "os: %lu.%lu build %lu\n", (unsigned long)os.dwMajorVersion,
                (unsigned long)os.dwMinorVersion, (unsigned long)os.dwBuildNumber);
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    fprintf(f, "cpu: %lu cores\n", (unsigned long)si.dwNumberOfProcessors);
    MEMORYSTATUSEX ms;
    memset(&ms, 0, sizeof(ms));
    ms.dwLength = sizeof(ms);
    if (GlobalMemoryStatusEx(&ms))
        fprintf(f, "memory: %llu MB total, %llu MB free\n",
                (unsigned long long)ms.ullTotalPhys / 1048576ULL,
                (unsigned long long)ms.ullAvailPhys / 1048576ULL);
    diag_sample();
    fprintf(f, "process: cpu=%.2f%% working_set=%llu MB gdi=%lu user=%lu threads=%lu handles=%lu alive=%d\n",
            g_cpu_pct, g_ws_kb / 1024ULL, (unsigned long)g_gdi_obj, (unsigned long)g_user_obj,
            (unsigned long)g_thread_count, (unsigned long)g_handle_count,
            win32_server_alive() ? 1 : 0);
    fprintf(f, "ports: http=%d https=%d stats=%d active=%d bind_all=%d theme_dark=%d minimized=%d\n",
            g_http_port, g_https_port, g_stats_port, g_active_port, g_bind_all ? 1 : 0,
            g_dark, g_start_minimized ? 1 : 0);
    fprintf(f, "server: valid=%d bind_ok=%d listeners=%d cert_days=%lld cert_trusted=%d\n",
            (g_sn && g_sn->valid) ? 1 : 0, (g_sn && g_sn->bind_ok) ? 1 : 0,
            g_sn ? g_sn->listener_count : 0, g_cert_days, g_cert_trusted_flag);
    if (g_sn) {
        for (int i = 0; i < g_sn->listener_count; i++)
            fprintf(f, "  listener %-8ls %-28ls bound=%d ipv6=%d loopback=%d\n",
                    g_sn->listeners[i].name, g_sn->listeners[i].addr,
                    g_sn->listeners[i].bound, g_sn->listeners[i].ipv6, g_sn->listeners[i].loopback);
    }
    const char *tails[2] = { "webserver.log", "crash.log" };
    for (int t = 0; t < 2; t++) {
        char lp[MAX_PATH + 48];
        snprintf(lp, sizeof(lp), "%s\\%s", g_res, tails[t]);
        FILE *l = fopen(lp, "r");
        fprintf(f, "---- %s ----\n", tails[t]);
        if (l) {
            static char ring[80][300];
            int n = 0;
            while (fgets(ring[n % 80], sizeof(ring[0]), l) != NULL) n++;
            fclose(l);
            int start = n > 80 ? n - 80 : 0;
            for (int i = start; i < n; i++) fputs(ring[i % 80], f);
        } else {
            fprintf(f, "(not present)\n");
        }
    }
    fclose(f);
    wchar_t wout[MAX_PATH + 48];
    MultiByteToWideChar(CP_ACP, 0, out, -1, wout, MAX_PATH + 48);
    MessageBoxW(hwnd, L"诊断信息已导出，点确定后将打开该文件。", L"诊断", MB_OK | MB_ICONINFORMATION);
    ShellExecuteW(NULL, L"open", wout, NULL, NULL, SW_SHOWNORMAL);
}

static void cert_refresh(bool force) {
    DWORD now = GetTickCount();
    if (!force && g_cert_checked_tick != 0 && now - g_cert_checked_tick < CERT_CACHE_MS)
        return;
    char cert_path[1024];
    snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
    g_cert_days = cert_days_left(cert_path);
    g_cert_trusted_flag = cert_trusted(cert_path);
    g_cert_checked_tick = now;
}

static void *stats_poll_thread(void *arg) {
    HWND hwnd = (HWND)arg;
    while (!g_poll_stop) {
        struct snapshot tmp;
        snapshot_fetch_any(&tmp);
        pthread_mutex_lock(&g_snap_mutex);
        g_snap_next = tmp;
        pthread_mutex_unlock(&g_snap_mutex);
        /* Wake the UI only when something it draws actually changed
         * (seconds-level uptime is ignored) - idle CPU drops to ~0; a
         * 60 s keep-alive still refreshes the clock line. */
        long long sig = tmp.valid
            ? (tmp.total_requests * 131 + tmp.total_connections * 17 +
               tmp.sni_certs_issued * 7 + (tmp.uptime_seconds / 60) +
               tmp.listener_count * 3 + tmp.bind_ok + tmp.stats_port)
            : -1;
        DWORD now = GetTickCount();
        if ((sig != g_poll_sig || now - g_poll_last_post > 60000) && !g_poll_stop) {
            g_poll_sig = sig;
            g_poll_last_post = now;
            PostMessageW(hwnd, WM_APP_STATS, 0, 0);
        }
        /* 2 s while the dashboard is on screen, 10 s while it lives in the
         * tray only: a hidden window needs no fresh charts. */
        for (int i = 0; i < 20 && !g_poll_stop; i++) Sleep(g_ui_visible ? 100 : 500);
    }
    return NULL;
}

#define IDC_POL0      1110

/* Block reply policy toggles: label, block_config.json key, control id. */
struct policy_item { const wchar_t *label; const char *key; int id; };
static const struct policy_item g_policy[] = {
    { L"图片",       "reply_images",     IDC_POL0 + 0 },
    { L"脚本",       "reply_scripts",    IDC_POL0 + 1 },
    { L"样式表",     "reply_styles",     IDC_POL0 + 2 },
    { L"字体",       "reply_fonts",      IDC_POL0 + 3 },
    { L"媒体/音频",  "reply_media",      IDC_POL0 + 4 },
    { L"页面结构",   "reply_structures", IDC_POL0 + 5 },
    { L"API 请求",   "reply_api",        IDC_POL0 + 6 },
    { L"遥测/统计",  "reply_telemetry",  IDC_POL0 + 7 },
    { L"配置文件",   "reply_config",     IDC_POL0 + 8 },
    { L"WebSocket",  "reply_ws_sse",     IDC_POL0 + 9 },
};
#define POLICY_COUNT ((int)(sizeof(g_policy) / sizeof(g_policy[0])))

/* ── settings persistence (webserver.ini next to the exe) ─────── */
static void ini_file_path(char *out, size_t n) {
    char exe[MAX_PATH];
    if (GetModuleFileNameA(NULL, exe, sizeof(exe)) <= 0) {
        snprintf(out, n, "webserver.ini");
        return;
    }
    char *slash = strrchr(exe, '\\');
    if (slash) *slash = '\0';
    snprintf(out, n, "%s\\webserver.ini", exe);
}

static void ini_save(int http_port, int https_port, bool bind_all) {
    char path[1024];
    ini_file_path(path, sizeof(path));
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "http_port=%d\n", http_port);
    fprintf(f, "https_port=%d\n", https_port);
    fprintf(f, "bind_all=%d\n", bind_all ? 1 : 0);
    fprintf(f, "theme=%d\n", g_theme_pref);
    fclose(f);
}

/* Reads theme= from webserver.ini; returns THEME_SYSTEM when absent. */
static int ini_read_theme(void) {
    char path[1024], line[256];
    ini_file_path(path, sizeof(path));
    FILE *f = fopen(path, "r");
    if (!f) return THEME_SYSTEM;
    int theme = THEME_SYSTEM;
    while (fgets(line, sizeof(line), f) != NULL) {
        int v = 99;
        if (sscanf(line, "theme=%d", &v) == 1 && v >= -1 && v <= 1) theme = v;
    }
    fclose(f);
    return theme;
}

/* Follow the Windows "app mode" setting when no preference was stored. */
static int system_prefers_dark(void) {
    DWORD val = 1, sz = sizeof(val);
    if (RegGetValueW(HKEY_CURRENT_USER,
            L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            L"AppsUseLightTheme", RRF_RT_REG_DWORD, NULL, &val, &sz) == ERROR_SUCCESS)
        return val == 0;
    return 0;
}

/* ── block_config.json read/write (applied via /control reload_config) ── */
static bool policy_get(const char *dir, const char *key, bool def) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/block_config.json", dir);
    FILE *f = fopen(path, "r");
    if (!f) return def;
    char buf[2048];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    buf[n] = '\0';
    fclose(f);
    char pat[96];
    int pl = snprintf(pat, sizeof(pat), "\"%s\":", key);
    if (pl <= 0 || (size_t)pl >= sizeof(pat)) return def;
    const char *p = strstr(buf, pat);
    if (!p) return def;
    p += pl;
    while (*p == ' ') p++;
    return strncmp(p, "true", 4) == 0;
}

static void policy_read(const char *dir, bool *vals) {
    for (int i = 0; i < POLICY_COUNT; i++)
        vals[i] = policy_get(dir, g_policy[i].key, true);
}

static void policy_save(const char *dir, const bool *vals) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/block_config.json", dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "{\n");
    for (int i = 0; i < POLICY_COUNT; i++)
        fprintf(f, "  \"%s\": %s%s\n", g_policy[i].key,
                vals[i] ? "true" : "false", i + 1 < POLICY_COUNT ? "," : "");
    fprintf(f, "}\n");
    fclose(f);
}

/* POST /control (cmd=reload_config | flush_stats | shutdown) */
static void control_post(int port, const char *cmd, wchar_t *status, size_t statusn) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        wchar_t w[16];
        utf8_to_wide(cmd, w, 16);
        if (statusn) swprintf(status, statusn, L"控制命令 %ls: 连接失败", w);
        return;
    }
    SOCKADDR_IN addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    int ok = -1;
    if (connect(s, (SOCKADDR *)&addr, sizeof(addr)) == 0) {
        char body[64];
        int bl = snprintf(body, sizeof(body), "cmd=%s", cmd);
        char req[512];
        int rl = snprintf(req, sizeof(req),
            "POST /control HTTP/1.0\r\nHost: 127.0.0.1\r\n"
            "Content-Type: application/x-www-form-urlencoded\r\n"
            "Content-Length: %d\r\nConnection: close\r\n\r\n%s",
            bl, body);
        if (rl > 0 && send(s, req, rl, 0) == rl) ok = 0;
    }
    closesocket(s);
    wchar_t w[16];
    utf8_to_wide(cmd, w, 16);
    if (statusn) swprintf(status, statusn, L"控制命令 %ls: %s",
                          w, ok == 0 ? L"成功" : L"失败");
}

/* ── certificate helpers ────────────────────────────────────────── */

static X509 *load_pem_cert(const char *cert_path) {
    FILE *f = fopen(cert_path, "rb");
    if (!f) return NULL;
    X509 *x = PEM_read_X509(f, NULL, NULL, NULL);
    fclose(f);
    return x;
}

/* days left; -100001 = file missing, -100002 = unreadable/bad cert */
static long long cert_days_left(const char *cert_path) {
    FILE *f = fopen(cert_path, "rb");
    if (!f) return -100001;
    X509 *x = PEM_read_X509(f, NULL, NULL, NULL);
    fclose(f);
    if (!x) return -100002;
    const ASN1_TIME *na = X509_get0_notAfter(x);
    if (!na) { X509_free(x); return -100002; }
    struct tm tm = {0};
    if (ASN1_TIME_to_tm(na, &tm) != 1) { X509_free(x); return -100002; }
    long long expiry = (long long)mktime(&tm);
    X509_free(x);
    return (expiry - (long long)time(NULL)) / 86400LL;
}

/* First non-loopback IPv4 (for LAN access hints); returns 0 on success. */
static int first_lan_ip(char *out, size_t n) {
    ULONG buflen = 0;
    if (GetAdaptersAddresses(AF_INET,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, NULL, &buflen) != ERROR_BUFFER_OVERFLOW || buflen == 0) return -1;
    PIP_ADAPTER_ADDRESSES buf = (PIP_ADAPTER_ADDRESSES)malloc(buflen);
    if (!buf) return -1;
    ULONG rc = GetAdaptersAddresses(AF_INET,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, buf, &buflen);
    if (rc != NO_ERROR) { free(buf); return -1; }
    for (PIP_ADAPTER_ADDRESSES a = buf; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        for (PIP_ADAPTER_UNICAST_ADDRESS u = a->FirstUnicastAddress; u; u = u->Next) {
            SOCKADDR_IN *sin = (SOCKADDR_IN *)u->Address.lpSockaddr;
            if (sin->sin_family == AF_INET &&
                sin->sin_addr.s_addr != htonl(INADDR_LOOPBACK) &&
                sin->sin_addr.s_addr != htonl(INADDR_ANY)) {
                strncpy(out, inet_ntoa(sin->sin_addr), n - 1);
                out[n - 1] = '\0';
                free(buf);
                return 0;
            }
        }
    }
    free(buf);
    return -1;
}

/* DER of the PEM cert (malloc'd; caller frees). */
static unsigned char *pem_to_der(const char *cert_path, size_t *derlen) {
    X509 *x = load_pem_cert(cert_path);
    if (!x) return NULL;
    int n = i2d_X509(x, NULL);
    if (n <= 0) { X509_free(x); return NULL; }
    unsigned char *der = (unsigned char *)malloc((size_t)n);
    if (!der) { X509_free(x); return NULL; }
    unsigned char *p = der;
    i2d_X509(x, &p);
    *derlen = (size_t)n;
    X509_free(x);
    return der;
}

static int same_cert(PCCERT_CONTEXT a, PCCERT_CONTEXT b) {
    BYTE ha[64], hb[64];
    DWORD la = sizeof(ha), lb = sizeof(hb);
    if (!CertGetCertificateContextProperty(a, CERT_SHA1_HASH_PROP_ID, ha, &la)) return 0;
    if (!CertGetCertificateContextProperty(b, CERT_SHA1_HASH_PROP_ID, hb, &lb)) return 0;
    return la == lb && memcmp(ha, hb, la) == 0;
}

static int cert_trusted(const char *cert_path) {
    size_t derlen = 0;
    unsigned char *der = pem_to_der(cert_path, &derlen);
    if (!der) return 0;
    PCERT_CONTEXT ours = (PCERT_CONTEXT)CertCreateCertificateContext(
        X509_ASN_ENCODING, der, (DWORD)derlen);
    int found = 0;
    if (ours) {
        int tries = 0;
        while (!found && tries < 2) {
            HCERTSTORE h = CertOpenStore(CERT_STORE_PROV_SYSTEM, 0, 0,
                (tries == 0) ? CERT_SYSTEM_STORE_CURRENT_USER : CERT_SYSTEM_STORE_LOCAL_MACHINE,
                L"Root");
            if (h) {
                PCCERT_CONTEXT ctx = NULL;
                while ((ctx = CertEnumCertificatesInStore(h, ctx)) != NULL) {
                    if (same_cert((PCCERT_CONTEXT)ours, ctx)) {
                        found = 1;
                        break;
                    }
                }
                CertCloseStore(h, 0);
            }
            tries++;
        }
        CertFreeCertificateContext(ours);
    }
    free(der);
    return found;
}

static int cert_trust_set(const char *cert_path, int enable) {
    size_t derlen = 0;
    unsigned char *der = pem_to_der(cert_path, &derlen);
    if (!der) return -1;
    int rc = -1;
    PCERT_CONTEXT ours = (PCERT_CONTEXT)CertCreateCertificateContext(
        X509_ASN_ENCODING, der, (DWORD)derlen);
    if (ours) {
        HCERTSTORE h = CertOpenStore(CERT_STORE_PROV_SYSTEM, 0, 0,
            CERT_SYSTEM_STORE_CURRENT_USER, L"Root");
        if (h) {
            if (enable) {
                if (CertAddCertificateContextToStore(h, ours,
                        CERT_STORE_ADD_REPLACE_EXISTING, NULL)) rc = 0;
            } else {
                PCCERT_CONTEXT ctx = NULL;
                while ((ctx = CertEnumCertificatesInStore(h, ctx)) != NULL) {
                    if (same_cert((PCCERT_CONTEXT)ours, ctx)) {
                        PCCERT_CONTEXT dup = CertDuplicateCertificateContext(ctx);
                        if (dup && CertDeleteCertificateFromStore(dup)) rc = 0;
                        if (dup) CertFreeCertificateContext(dup);
                    }
                }
            }
            CertCloseStore(h, 0);
        }
        CertFreeCertificateContext(ours);
    }
    free(der);
    return rc;
}

/* ── autostart (HKCU Run) ───────────────────────────────────────── */

#define RUN_KEY L"Software\\Microsoft\\Windows\\CurrentVersion\\Run"
#define RUN_VALUE L"ADBlockWebServer"

int win32_autostart_set(bool enable, const char *cmdline) {
    HKEY hk;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, NULL, 0,
            KEY_SET_VALUE | KEY_QUERY_VALUE, NULL, &hk, NULL) != ERROR_SUCCESS) {
        return -1;
    }
    int rc = 0;
    if (enable) {
        wchar_t wide[2048];
        utf8_to_wide(cmdline, wide, sizeof(wide) / sizeof(wide[0]));
        if (RegSetValueExW(hk, RUN_VALUE, 0, REG_SZ, (const BYTE *)wide,
                (DWORD)((wcslen(wide) + 1) * sizeof(wchar_t))) != ERROR_SUCCESS) rc = -1;
    } else {
        if (RegDeleteValueW(hk, RUN_VALUE) != ERROR_SUCCESS &&
            RegQueryValueExW(hk, RUN_VALUE, NULL, NULL, NULL, NULL) == ERROR_SUCCESS) {
            rc = -1;
        }
    }
    RegCloseKey(hk);
    return rc;
}

bool win32_autostart_installed(void) {
    LONG r = RegGetValueW(HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE,
        RRF_RT_REG_SZ, NULL, NULL, NULL);
    return r == ERROR_SUCCESS;
}

/* ── dashboard window ───────────────────────────────────────────── */

#define IDM_TRUST    1001
#define IDM_UNTRUST  1002
#define IDM_TEST     1003
#define IDM_AUTOSTART 1004
#define IDM_EXIT     1005
#define IDM_DARK     1006
#define IDM_UPDATE   1007
#define IDC_FIXPORT  1107
#define IDC_DIAG     1108
#define IDC_HTTP_EDIT 1101
#define IDC_HTTPS_EDIT 1102
#define IDC_BIND_CHK  1103
#define IDC_SAVE      1104
#define IDC_RESTART   1105
#define IDC_FLUSH     1106

/* AdGuard-inspired palette.
 *
 * The surface colours live in a switchable palette so the dashboard can be
 * rendered in a light or a dark theme (the accent colours are shared). */
struct palette {
    COLORREF sidebar, nav_active, nav_text, content_bg, card, border,
             text, muted, gridline, strip;
};
static const struct palette P_LIGHT = {
    RGB(30, 37, 55), RGB(42, 54, 84), RGB(147, 163, 190),
    RGB(243, 245, 248), RGB(255, 255, 255), RGB(229, 232, 238),
    RGB(31, 41, 55), RGB(100, 116, 139), RGB(237, 240, 244),
    RGB(255, 255, 255)
};
static const struct palette P_DARK = {
    RGB(17, 21, 32), RGB(38, 46, 66), RGB(148, 163, 190),
    RGB(22, 26, 36), RGB(31, 36, 49), RGB(48, 55, 71),
    RGB(226, 232, 240), RGB(148, 163, 184), RGB(44, 51, 66),
    RGB(31, 36, 49)
};
/* Active theme: starts as a literal copy of the light palette so it is valid
 * even before theme_apply() has run. */
static struct palette g_pal = {
    RGB(30, 37, 55), RGB(42, 54, 84), RGB(147, 163, 190),
    RGB(243, 245, 248), RGB(255, 255, 255), RGB(229, 232, 238),
    RGB(31, 41, 55), RGB(100, 116, 139), RGB(237, 240, 244),
    RGB(255, 255, 255)
};
static void theme_apply(int dark) {
    g_pal = dark ? P_DARK : P_LIGHT;
}
#define C_ACCENT     RGB(59, 130, 246)
#define C_BLUE_SOFT  RGB(219, 234, 254)
#define C_BLUE       RGB(59, 130, 246)
#define C_RED        RGB(239, 68, 68)
#define C_GREEN      RGB(34, 197, 94)
#define C_GREEN_TXT  RGB(22, 163, 74)

static const wchar_t *g_title = L"ADBlock 拦截服务器 v" ADBLOCK_APP_VERSION;

static struct snapshot *g_sn = NULL;
static char g_res[512] = {0};
static int g_http_port = 8080;
static int g_https_port = 8443;
static bool g_bind_all = false;
static bool g_start_minimized = false;
static int g_tab = 0;               /* 0 = statistics, 1 = settings */
static wchar_t g_status[4096] = L"";
static HWND s_ctrls[64];
static int s_ctrl_count = 0;
static HFONT g_ctl_font = NULL;

/* ── polling / caching state ─────────────────────────────────────
 * The statistics fetch runs on its own thread so a slow or unreachable
 * server can never freeze the window; the certificate files and the
 * Root-store lookup are cached with a TTL instead of being repeated on
 * every (2 s) repaint. */
static int  g_stats_port = 8686;     /* loopback management port (preferred) */
static int  g_active_port = 0;       /* port that answered the last poll */
static pthread_t g_poll_thread;
static volatile int g_poll_stop = 0;
static int  g_poll_started = 0;
static pthread_mutex_t g_snap_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct snapshot g_snap_next;
static long long g_cert_days = -100000;
static int  g_cert_trusted_flag = 0;
static DWORD g_cert_checked_tick = 0;
static long long g_poll_sig = -1;    /* signature of the last drawn snapshot */
static DWORD g_poll_last_post = 0;   /* keep-alive post (clock) */

/* ── resource accounting (shown in the dashboard + diagnose.txt) ────
 * "高消耗" was reported without numbers, so the app now measures itself:
 * CPU% (from GetProcessTimes deltas), working set, GDI/USER objects, threads
 * and handles. Sampling happens once per timer tick, which is cheap. */
static bool g_owns_server = false;       /* this process runs the server thread */
static volatile int g_ui_visible = 1;    /* window shown (not only tray) */
static int  g_hidden_trimmed = 0;        /* working set trimmed while hidden */
static double g_cpu_pct = 0.0;
static unsigned long long g_ws_kb = 0;
static DWORD g_gdi_obj = 0, g_user_obj = 0, g_thread_count = 0, g_handle_count = 0;
static ULONGLONG g_cpu_prev_ms = 0;
static DWORD g_cpu_prev_tick = 0;
#define CERT_CACHE_MS 30000

static int mgmt_port(void) {
    return g_active_port > 0 ? g_active_port : g_stats_port;
}

/* rounded card */
static void rounded_card(HDC hdc, int x, int y, int w, int h, int radius,
                         COLORREF fill, COLORREF border) {
    int X = S(x), Y = S(y), W = S(w), H = S(h), R = S(radius);
    HRGN rgn = CreateRoundRectRgn(X, Y, X + W, Y + H, R * 2, R * 2);
    HBRUSH b = CreateSolidBrush(fill);
    int saved = SaveDC(hdc);
    SelectClipRgn(hdc, rgn);
    RECT rc = {X, Y, X + W, Y + H};
    FillRect(hdc, &rc, b);
    SelectClipRgn(hdc, NULL);
    RestoreDC(hdc, saved);
    DeleteObject(b);
    DeleteObject(rgn);
    HPEN pen = CreatePen(PS_SOLID, 1, border);
    HGDIOBJ op = SelectObject(hdc, pen);
    HGDIOBJ ob = SelectObject(hdc, GetStockObject(NULL_BRUSH));
    RoundRect(hdc, X, Y, X + W, Y + H, R * 2, R * 2);
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(pen);
}

static void fmt_num(wchar_t *dst, size_t n, long long v) {
    if (v >= 1000000) swprintf(dst, n, L"%.1fM", (double)v / 1000000.0);
    else if (v >= 1000) swprintf(dst, n, L"%.1fK", (double)v / 1000.0);
    else swprintf(dst, n, L"%lld", v);
}

static void draw_kpi(HDC hdc, int x, int y, int w, int h,
                     const wchar_t *label, const wchar_t *value,
                     COLORREF accent) {
    rounded_card(hdc, x + 1, y + 2, w, h, 12, g_pal.gridline, g_pal.gridline);  /* soft shadow */
    rounded_card(hdc, x, y, w, h, 12, g_pal.card, g_pal.border);
    HBRUSH ab = CreateSolidBrush(accent);
    HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
    HGDIOBJ ob = SelectObject(hdc, ab);
    RoundRect(hdc, S(x + 3), S(y + 14), S(x + 7), S(y + h - 14), S(4), S(4));  /* accent bar */
    Ellipse(hdc, S(x + w - 15), S(y + 13), S(x + w - 8), S(y + 20));           /* corner dot */
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(ab);
    HFONT vf = mfont(20, FW_SEMIBOLD);
    HFONT lf = mfont(12, FW_NORMAL);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, g_pal.text);
    HFONT old = (HFONT)SelectObject(hdc, vf);
    TextOutW(hdc, S(x + 16), S(y + 16), value, (int)wcslen(value));
    SetTextColor(hdc, g_pal.muted);
    SelectObject(hdc, lf);
    TextOutW(hdc, S(x + 16), S(y + 46), label, (int)wcslen(label));
    SelectObject(hdc, old);
    DeleteObject(vf);
    DeleteObject(lf);
}

static void draw_chart(HDC hdc, RECT panel, const wchar_t *title,
                       const struct histogram *h, int count) {
    rounded_card(hdc, panel.left, panel.top,
                 panel.right - panel.left, panel.bottom - panel.top, 12, g_pal.card, g_pal.border);
    HFONT tf = mfont(14, FW_SEMIBOLD);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, g_pal.text);
    HFONT old = (HFONT)SelectObject(hdc, tf);
    TextOutW(hdc, S(panel.left + 16), S(panel.top + 12), title, (int)wcslen(title));
    SelectObject(hdc, old);
    DeleteObject(tf);

    int ax = panel.left + 20, ay = panel.top + 42;
    int aw = panel.right - panel.left - 34, ah = panel.bottom - panel.top - 62;
    if (count <= 0 || aw < 10 || ah < 10) {
        SetTextColor(hdc, g_pal.muted);
        TextOutW(hdc, S(ax + 4), S(ay + 12), L"暂无数据", 4);
        return;
    }
    long long maxv = 1;
    for (int i = 0; i < count; i++) {
        if (h[i].requests > maxv) maxv = h[i].requests;
        if (h[i].blocked > maxv) maxv = h[i].blocked;
    }
    HPEN gp = CreatePen(PS_SOLID, 1, g_pal.gridline);
    HGDIOBJ gop = SelectObject(hdc, gp);
    for (int g = 0; g <= 3; g++) {
        int gy = S(ay) + (int)((double)(S(ah)) * g / 3.0);
        MoveToEx(hdc, S(ax), gy, NULL);
        LineTo(hdc, S(ax) + S(aw), gy);
    }
    SelectObject(hdc, gop);
    DeleteObject(gp);

    wchar_t numbuf[64];
    HFONT lf = mfont(11, FW_NORMAL);
    SelectObject(hdc, lf);
    SetTextColor(hdc, g_pal.muted);
    swprintf(numbuf, 64, L"%lld", maxv);
    TextOutW(hdc, S(ax), S(ay) - S(18), numbuf, (int)wcslen(numbuf));
    TextOutW(hdc, S(ax), S(ay) + S(ah) + S(4), L"0", 1);
    SelectObject(hdc, old);
    DeleteObject(lf);

    int slotw = aw / count;
    if (slotw < 4) slotw = 4;
    HBRUSH blue = CreateSolidBrush(C_BLUE);
    HBRUSH red = CreateSolidBrush(C_RED);
    HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
    for (int i = 0; i < count; i++) {
        int bh1 = (int)((double)ah * (double)h[i].requests / (double)maxv);
        int bh2 = (int)((double)ah * (double)h[i].blocked / (double)maxv);
        int bx = ax + i * slotw;
        if (bh1 > 1) {
            SelectObject(hdc, blue);
            RoundRect(hdc, S(bx + 1), S(ay + ah) - S(bh1),
                      S(bx + (int)(slotw * 0.38)), S(ay + ah), S(5), S(5));
        }
        if (bh2 > 1) {
            SelectObject(hdc, red);
            RoundRect(hdc, S(bx + (int)(slotw * 0.55)), S(ay + ah) - S(bh2),
                      S(bx + slotw - 1), S(ay + ah), S(5), S(5));
        }
    }
    SelectObject(hdc, op);
    DeleteObject(blue);
    DeleteObject(red);
}

static void draw_legend(HDC hdc, int x, int y) {
    HBRUSH b = CreateSolidBrush(C_BLUE);
    HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
    HGDIOBJ ob = SelectObject(hdc, b);
    Ellipse(hdc, S(x), S(y + 3), S(x + 8), S(y + 11));
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(b);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, g_pal.muted);
    TextOutW(hdc, S(x + 14), S(y), L"请求", 2);
    HBRUSH r = CreateSolidBrush(C_RED);
    ob = SelectObject(hdc, r);
    Ellipse(hdc, S(x + 76), S(y + 3), S(x + 84), S(y + 11));
    SelectObject(hdc, ob);
    DeleteObject(r);
    TextOutW(hdc, S(x + 90), S(y), L"拦截", 2);
}

/* "监听状态" card: lists every socket the server reported through
 * /internal-stats so a port that failed to bind is visible inside the app
 * instead of only in webserver.log. */
static void draw_listener_card(HDC hdc, int x, int y, int w, int h,
                               const struct snapshot *sn, int two_col) {
    rounded_card(hdc, x + 1, y + 2, w, h, 12, g_pal.gridline, g_pal.gridline);
    rounded_card(hdc, x, y, w, h, 12, g_pal.card, g_pal.border);

    bool live = (sn != NULL && sn->valid);
    int total = 0, bound = 0;
    if (live) {
        total = sn->listener_count;
        for (int i = 0; i < total; i++)
            if (sn->listeners[i].bound) bound++;
    }
    bool ok = live && sn->bind_ok != 0;

    HFONT tf = mfont(14, FW_SEMIBOLD);
    HFONT old = (HFONT)SelectObject(hdc, tf);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, g_pal.text);
    TextOutW(hdc, S(x + 16), S(y + 12), L"监听状态", 4);

    wchar_t pill[160];
    if (live)
        swprintf(pill, 160, ok ? L"绑定正常 · %d/%d 个端口在监听"
                               : L"有端口绑定失败 · %d/%d 已监听", bound, total);
    else
        swprintf(pill, 160, L"服务器暂不可达，无法确认监听状态");
    HFONT pf = mfont(12, FW_NORMAL);
    SelectObject(hdc, pf);
    SetTextColor(hdc, !live ? g_pal.muted : (ok ? C_GREEN_TXT : RGB(200, 60, 60)));
    SIZE ps;
    GetTextExtentPoint32W(hdc, pill, (int)wcslen(pill), &ps);
    int px = x + w - 18 - ps.cx;
    if (px < x + 110) px = x + 110;
    TextOutW(hdc, S(px), S(y + 14), pill, (int)wcslen(pill));
    SelectObject(hdc, old);
    DeleteObject(tf);
    DeleteObject(pf);

    int shown = 0;
    int cols = two_col ? 2 : 1;
    int maxrow = two_col ? 4 : 8;
    HFONT nf = mfont(12, FW_NORMAL);
    old = (HFONT)SelectObject(hdc, nf);
    for (int i = 0; i < total && shown < cols * maxrow; i++) {
        const struct listener_info *li = &sn->listeners[i];
        int col = shown % cols, row = shown / cols;
        int lx = x + 16 + col * ((w - 32) / cols + 8);
        int ly = y + 40 + row * 19;
        HBRUSH db = CreateSolidBrush(li->bound
                                     ? (li->loopback ? C_BLUE : C_GREEN)
                                     : RGB(200, 60, 60));
        HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
        HGDIOBJ ob = SelectObject(hdc, db);
        Ellipse(hdc, S(lx), S(ly + 4), S(lx + 7), S(ly + 11));
        SelectObject(hdc, op);
        SelectObject(hdc, ob);
        DeleteObject(db);
        SetTextColor(hdc, li->bound ? g_pal.text : RGB(200, 60, 60));
        TextOutW(hdc, S(lx + 13), S(ly), li->name, (int)wcslen(li->name));
        SetTextColor(hdc, g_pal.muted);
        TextOutW(hdc, S(lx + 80), S(ly), li->addr, (int)wcslen(li->addr));
        shown++;
    }
    if (total == 0) {
        SetTextColor(hdc, g_pal.muted);
        const wchar_t *msg = live ? L"服务器未上报监听列表（可能是旧版服务器进程）"
                                  : L"正在连接服务器 ...";
        TextOutW(hdc, S(x + 16), S(y + 44), msg, (int)wcslen(msg));
    } else if (!ok && h > 130) {
        SetTextColor(hdc, RGB(200, 60, 60));
        const wchar_t *msg =
            L"提示：切到“设置”页点击“改用备用端口 18080/18443”即可避开被占用的端口。";
        TextOutW(hdc, S(x + 16), S(y + h - 44), msg, (int)wcslen(msg));
    }
    /* self-measurement: the numbers behind "它很占资源" */
    if (h > 120) {
        wchar_t diag[260];
        swprintf(diag, 260,
                 L"进程：CPU %.1f%% · 内存 %llu MB · 句柄 %lu · 线程 %lu · GDI %lu · 服务器线程 %s",
                 g_cpu_pct, g_ws_kb / 1024ULL, (unsigned long)g_handle_count,
                 (unsigned long)g_thread_count, (unsigned long)g_gdi_obj,
                 g_owns_server ? (win32_server_alive() ? L"运行中" : L"已停止") : L"外部进程");
        SetTextColor(hdc, g_pal.muted);
        TextOutW(hdc, S(x + 16), S(y + h - 24), diag, (int)wcslen(diag));
    }
    SelectObject(hdc, old);
    DeleteObject(nf);
}

static void draw_statusbar(HDC hdc) {
    RECT strip = {0, 630, 1000, 678};
    HBRUSH sbg = CreateSolidBrush(g_pal.strip);
    FillRect(hdc, &strip, sbg);
    DeleteObject(sbg);
    HPEN tp = CreatePen(PS_SOLID, 1, g_pal.border);
    HGDIOBJ tpold = SelectObject(hdc, tp);
    MoveToEx(hdc, 0, S(630), NULL);
    LineTo(hdc, S(1000), S(630));
    SelectObject(hdc, tpold);
    DeleteObject(tp);

    bool up = (g_sn != NULL && g_sn->valid);
    HBRUSH dotb = CreateSolidBrush(up ? C_GREEN : C_RED);
    HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
    HGDIOBJ ob = SelectObject(hdc, dotb);
    Ellipse(hdc, S(16), S(647), S(28), S(659));
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(dotb);

    HFONT f = mfont(12, FW_NORMAL);
    HFONT old = (HFONT)SelectObject(hdc, f);
    SetBkMode(hdc, TRANSPARENT);
    wchar_t main[256];
    swprintf(main, 256, L"服务器运行中 · http://localhost:%d  |  https://localhost:%d",
             g_http_port, g_https_port);
    SetTextColor(hdc, up ? C_GREEN_TXT : RGB(190, 70, 70));
    TextOutW(hdc, S(40), S(643), main, (int)wcslen(main));

    char cert_path[1024];
    snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
    long long days = g_cert_days;   /* cached: see cert_refresh() */
    wchar_t cert[200];
    if (days > 0 && days < 200000)
        swprintf(cert, 200, L"CA 证书剩余 %lld 天 · %s", days,
                 g_cert_trusted_flag ? L"已信任" : L"未信任");
    else if (days == -100001)
        swprintf(cert, 200, L"CA 证书文件缺失: %hs", cert_path);
    else if (days == -100002)
        swprintf(cert, 200, L"CA 证书解析失败: %hs", cert_path);
    else
        swprintf(cert, 200, L"CA 证书已过期 - 删除 %hs 后重启", cert_path);
    SetTextColor(hdc, g_pal.muted);
    TextOutW(hdc, S(430), S(643), cert, (int)wcslen(cert));

    SYSTEMTIME st;
    GetLocalTime(&st);
    wchar_t tm[64];
    swprintf(tm, 64, L"%02d:%02d:%02d", st.wHour, st.wMinute, st.wSecond);
    SetTextColor(hdc, g_pal.muted);
    TextOutW(hdc, S(920), S(643), tm, (int)wcslen(tm));

    SetTextColor(hdc, g_pal.muted);
    TextOutW(hdc, S(16), S(661), g_status, (int)wcslen(g_status));

    SelectObject(hdc, old);
    DeleteObject(f);
}

/* sidebar nav hit areas (logical units; callers pass physical coords) */
static bool nav_hit(int x, int y, int which) {
    int top = which == 0 ? 96 : 148;
    return x >= S(16) && x <= S(176) && y >= S(top) && y <= S(top + 40);
}

static void draw_sidebar(HDC hdc) {
    RECT sb = {0, 0, 190, 680};
    HBRUSH bg = CreateSolidBrush(g_pal.sidebar);
    FillRect(hdc, &sb, bg);
    DeleteObject(bg);

    HFONT lf = mfont(17, FW_SEMIBOLD);
    HFONT sf = mfont(11, FW_NORMAL);
    HFONT old = (HFONT)SelectObject(hdc, lf);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, RGB(255, 255, 255));
    TextOutW(hdc, S(22), S(24), L"ADBlock", 7);
    SelectObject(hdc, sf);
    SetTextColor(hdc, RGB(143, 163, 192));
    wchar_t ver[64];
    swprintf(ver, 64, L"拦截服务器 v" ADBLOCK_APP_VERSION);
    TextOutW(hdc, S(22), S(48), ver, (int)wcslen(ver));
    {
        bool live = (g_sn != NULL && g_sn->valid);
        HBRUSH nb = CreateSolidBrush(live ? C_GREEN : RGB(120, 135, 160));
        HGDIOBJ nop = SelectObject(hdc, GetStockObject(NULL_PEN));
        HGDIOBJ nob = SelectObject(hdc, nb);
        Ellipse(hdc, S(22), S(70), S(31), S(79));
        SelectObject(hdc, nop);
        SelectObject(hdc, nob);
        DeleteObject(nb);
        SetTextColor(hdc, live ? RGB(110, 231, 183) : RGB(143, 163, 192));
        TextOutW(hdc, S(37), S(67), live ? L"服务器运行中" : L"等待服务器 ...", live ? 6 : 9);
    }
    SelectObject(hdc, old);
    DeleteObject(lf);
    DeleteObject(sf);

    /* nav items */
    for (int t = 0; t < 2; t++) {
        int ny = t == 0 ? 96 : 148;
        bool active = (g_tab == t);
        rounded_card(hdc, 16, ny, 160, 40, 10,
                     active ? g_pal.nav_active : g_pal.sidebar,
                     active ? g_pal.nav_active : g_pal.sidebar);
        if (active) {
            HBRUSH ab = CreateSolidBrush(C_ACCENT);
            RECT bar = {S(16), S(ny + 10), S(20), S(ny + 30)};
            FillRect(hdc, &bar, ab);
            DeleteObject(ab);
        }
        /* icon: bars (statistics) / sliders (settings) */
        if (t == 0) {
            HBRUSH ib = CreateSolidBrush(active ? C_ACCENT : g_pal.nav_text);
            HGDIOBJ op = SelectObject(hdc, GetStockObject(NULL_PEN));
            HGDIOBJ ob = SelectObject(hdc, ib);
            RoundRect(hdc, S(34), S(ny + 14), S(40), S(ny + 30), S(3), S(3));
            RoundRect(hdc, S(43), S(ny + 8),  S(49), S(ny + 30), S(3), S(3));
            RoundRect(hdc, S(52), S(ny + 20), S(58), S(ny + 30), S(3), S(3));
            SelectObject(hdc, op);
            SelectObject(hdc, ob);
            DeleteObject(ib);
        } else {
            HPEN ip = CreatePen(PS_SOLID, S(2), active ? C_ACCENT : g_pal.nav_text);
            HGDIOBJ op = SelectObject(hdc, ip);
            HGDIOBJ ob2 = SelectObject(hdc, GetStockObject(NULL_BRUSH));
            for (int k = 0; k < 3; k++) {
                int ly = S(ny + 12 + k * 7);
                MoveToEx(hdc, S(34), ly, NULL);
                LineTo(hdc, S(58), ly);
                Ellipse(hdc, S(40 + k * 6) - S(2), ly - S(2),
                        S(40 + k * 6) + S(2), ly + S(2));
            }
            SelectObject(hdc, ob2);
            SelectObject(hdc, op);
            DeleteObject(ip);
        }
        HFONT nf = mfont(14, active ? FW_SEMIBOLD : FW_NORMAL);
        SelectObject(hdc, nf);
        SetTextColor(hdc, active ? RGB(255, 255, 255) : g_pal.nav_text);
        TextOutW(hdc, S(70), S(ny + 10), t == 0 ? L"统计" : L"设置", 2);
        SelectObject(hdc, old);
        DeleteObject(nf);
    }

    /* exit at sidebar bottom */
    rounded_card(hdc, 16, 576, 160, 40, 10, g_pal.sidebar, g_pal.sidebar);
    HFONT xf = mfont(13, FW_NORMAL);
    SelectObject(hdc, xf);
    SetTextColor(hdc, g_pal.nav_text);
    TextOutW(hdc, S(70), S(586), L"退出", 2);
    SelectObject(hdc, old);
    DeleteObject(xf);
}

/* ── system tray icon ──────────────────────────────────────────── */
#define WM_APP_TRAY (WM_USER + 1)
#define WM_APP_EXIT (WM_USER + 2)

static NOTIFYICONDATAW g_nid;
static bool g_tray_initialized = false;

static UINT g_taskbar_created = 0;   /* "TaskbarCreated" */
static bool g_tray_ok = false;

static bool tray_add(HWND hwnd) {
    memset(&g_nid, 0, sizeof(g_nid));
    g_nid.cbSize = sizeof(g_nid);
    g_nid.hWnd = hwnd;
    g_nid.uID = 1;
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    {
        /* Use the embedded application icon for the tray, too. */
        HICON ti = (HICON)LoadImageW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(1),
                                     IMAGE_ICON, GetSystemMetrics(SM_CXSMICON),
                                     GetSystemMetrics(SM_CYSMICON), LR_SHARED);
        g_nid.hIcon = (ti != NULL) ? ti : LoadIconW(NULL, IDI_APPLICATION);
    }
    swprintf(g_nid.szTip, 128, L"ADBlock 拦截服务器 - http://localhost:%d", g_http_port);
    BOOL ok = Shell_NotifyIconW(NIM_ADD, &g_nid);
    g_tray_ok = (ok == TRUE);
    g_tray_initialized = g_tray_ok;
    if (g_tray_ok) {
        g_nid.uFlags |= NIF_INFO;
        wcscpy(g_nid.szInfoTitle, L"ADBlock 拦截服务器");
        wcscpy(g_nid.szInfo, L"运行中 - 双击此图标可重新打开窗口。");
        Shell_NotifyIconW(NIM_MODIFY, &g_nid);
        g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    }
    return g_tray_ok;
}

static void tray_remove(void) {
    if (g_tray_initialized) {
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        g_tray_initialized = false;
    }
}

static void tray_menu(HWND hwnd) {
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING | MF_ENABLED, 3001, L"打开仪表盘");
    AppendMenuW(m, MF_STRING | MF_ENABLED, 3002, L"开机自启动");
    AppendMenuW(m, MF_STRING | MF_ENABLED, 3004, L"检查更新");
    AppendMenuW(m, MF_STRING | MF_ENABLED, 3004, L"检查更新");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING | MF_ENABLED, 3003, L"退出");
    POINT pt;
    GetCursorPos(&pt);
    SetForegroundWindow(hwnd);
    int cmd = (int)TrackPopupMenuEx(m, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, hwnd, NULL);
    DestroyMenu(m);
    if (cmd == 3001) {
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    } else if (cmd == 3002) {
        HWND chk = GetDlgItem(hwnd, IDM_AUTOSTART);
        bool on = SendMessageW(chk, BM_GETCHECK, 0, 0) != BST_CHECKED;
        char ccmd[2048], exe[MAX_PATH];
        GetModuleFileNameA(NULL, exe, sizeof(exe));
        snprintf(ccmd, sizeof(ccmd), "\"%s\" --resources \"%s\" --http-port %d --https-port %d --minimized",   /* --no-gui started without a tray icon after logon */
                 exe, g_res, g_http_port, g_https_port);
        win32_autostart_set(on, ccmd);
        SendMessageW(chk, BM_SETCHECK, on ? BST_CHECKED : BST_UNCHECKED, 0);
        swprintf(g_status, 4096, L"开机自启动已%s", on ? L"启用" : L"关闭");
        InvalidateRect(hwnd, NULL, FALSE);
    } else if (cmd == 3004) {
            swprintf(g_status, 4096, L"正在检查更新…");
            update_check_async(hwnd);
        } else if (cmd == 3003) {
        PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
    }
}

/* control layout descriptors (logical units) */
struct ctl_desc { int id; int x, y, w, h; const wchar_t *cls, *text; int style; };
static const struct ctl_desc g_clayout[] = {
    { IDC_HTTP_EDIT,  300, 54, 80, 24, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | ES_NUMBER },
    { IDC_HTTPS_EDIT, 590, 54, 80, 24, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | ES_NUMBER },
    { IDC_BIND_CHK,   210, 96, 260, 22, L"BUTTON", L"监听所有网卡（局域网）", BS_AUTOCHECKBOX },
    { IDC_SAVE,       210, 130, 110, 28, L"BUTTON", L"保存设置", BS_PUSHBUTTON },
    { IDC_RESTART,    330, 130, 120, 28, L"BUTTON", L"保存并重启", BS_PUSHBUTTON },
    { IDC_FIXPORT,    460, 130, 190, 28, L"BUTTON", L"改用备用端口 18080/18443", BS_PUSHBUTTON },
    { IDM_DARK,       664, 132, 170, 24, L"BUTTON", L"深色外观", BS_AUTOCHECKBOX },
    { IDM_TRUST,      210, 424, 96, 28, L"BUTTON", L"信任 CA", BS_PUSHBUTTON },
    { IDM_UNTRUST,    316, 424, 96, 28, L"BUTTON", L"撤销 CA", BS_PUSHBUTTON },
    { IDM_TEST,       422, 424, 110, 28, L"BUTTON", L"打开测试页", BS_PUSHBUTTON },
    { IDC_FLUSH,      542, 424, 96, 28, L"BUTTON", L"清空统计", BS_PUSHBUTTON },
    { IDC_DIAG,       844, 434, 170, 26, L"BUTTON", L"导出诊断信息", BS_PUSHBUTTON },
    { IDM_AUTOSTART,  648, 426, 180, 24, L"BUTTON", L"开机自启动", BS_AUTOCHECKBOX },
    /* Below the "导出诊断信息" button (which sits at 844,434): the two used to
       overlap in x 844-948 / y 434-452. */
    { IDM_UPDATE,     844, 466, 170, 26, L"BUTTON", L"检查更新", BS_PUSHBUTTON },
};
#define CL_MAIN 14

static void layout_controls(HWND hwnd) {
    for (int i = 0; i < CL_MAIN; i++)
        SetWindowPos(GetDlgItem(hwnd, g_clayout[i].id), NULL,
                     S(g_clayout[i].x), S(g_clayout[i].y),
                     S(g_clayout[i].w), S(g_clayout[i].h), SWP_NOZORDER | SWP_NOACTIVATE);
    /* policy checkboxes */
    for (int i = 0; i < POLICY_COUNT; i++) {
        int cx = (i % 2 == 0) ? 210 : 440;
        int cy = 208 + (i / 2) * 28;
        HWND w = GetDlgItem(hwnd, IDC_POL0 + i);
        if (w) SetWindowPos(w, NULL, S(cx), S(cy), S(200), S(24), SWP_NOZORDER | SWP_NOACTIVATE);
    }
}

static void show_controls(HWND hwnd, int tab) {
    bool show = (tab == 1);
    for (int i = 0; i < CL_MAIN; i++)
        ShowWindow(GetDlgItem(hwnd, g_clayout[i].id), show ? SW_SHOW : SW_HIDE);
    for (int i = 0; i < POLICY_COUNT; i++)
        ShowWindow(GetDlgItem(hwnd, IDC_POL0 + i), show ? SW_SHOW : SW_HIDE);
}

static void policy_apply(HWND hwnd) {
    bool vals[POLICY_COUNT];
    for (int i = 0; i < POLICY_COUNT; i++)
        vals[i] = SendMessageW(GetDlgItem(hwnd, IDC_POL0 + i), BM_GETCHECK, 0, 0) == BST_CHECKED;
    policy_save(g_res, vals);
    wchar_t st[128];
    control_post(mgmt_port(), "reload_config", st, 128);
    swprintf(g_status, 4096, L"拦截策略已更新（%ls）", st);
    InvalidateRect(hwnd, NULL, FALSE);
}

static LRESULT CALLBACK gui_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (g_taskbar_created != 0 && msg == g_taskbar_created) {
        tray_add(hwnd);
        return 0;
    }
    switch (msg) {
    case WM_DPICHANGED:
        g_scale = HIWORD(wp) / 96.0;
        {
            const RECT *r = (const RECT *)lp;
            SetWindowPos(hwnd, NULL, r->left, r->top,
                         r->right - r->left, r->bottom - r->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        layout_controls(hwnd);
        InvalidateRect(hwnd, NULL, TRUE);
        return 0;
    case WM_CREATE: {
        const struct adblock_gui_args *args =
            (const struct adblock_gui_args *)((CREATESTRUCTW *)lp)->lpCreateParams;
        snprintf(g_res, sizeof(g_res), "%s", args->resource_dir);
        g_http_port = args->http_port;
        g_https_port = args->https_port;
        g_bind_all = args->bind_all;
        g_start_minimized = args->start_minimized;
        g_stats_port = args->stats_port > 0 ? args->stats_port : 8686;
        g_owns_server = args->owns_server;
        g_tab = 0;
        g_scale = GetDpiForWindow(hwnd) / 96.0;
        g_theme_pref = ini_read_theme();
        g_dark = (g_theme_pref == THEME_SYSTEM) ? system_prefers_dark()
                                                : (g_theme_pref == 1);
        theme_apply(g_dark);
        apply_window_chrome(hwnd, g_dark);
        g_sn = (struct snapshot *)calloc(1, sizeof(struct snapshot));
        if (args->startup_warning != NULL && args->startup_warning[0] != '\0')
            swprintf(g_status, 4096, L"%hs", args->startup_warning);
        HINSTANCE hinst = GetModuleHandleW(NULL);
        HICON app_icon = (HICON)LoadImageW(hinst, MAKEINTRESOURCEW(1), IMAGE_ICON,
                                           0, 0, LR_DEFAULTSIZE | LR_SHARED);
        if (app_icon == NULL) app_icon = LoadIconW(NULL, IDI_APPLICATION);
        SendMessageW(hwnd, WM_SETICON, ICON_BIG, (LPARAM)app_icon);
        SendMessageW(hwnd, WM_SETICON, ICON_SMALL, (LPARAM)app_icon);
        for (int i = 0; i < CL_MAIN; i++) {
            const struct ctl_desc *d = &g_clayout[i];
            HWND w = CreateWindowExW(0, d->cls, d->text,
                WS_CHILD | d->style, S(d->x), S(d->y), S(d->w), S(d->h),
                hwnd, (HMENU)(INT_PTR)d->id, hinst, NULL);
            s_ctrls[s_ctrl_count++] = w;
        }
        for (int i = 0; i < POLICY_COUNT; i++) {
            int cx = (i % 2 == 0) ? 210 : 440;
            int cy = 208 + (i / 2) * 28;
            s_ctrls[s_ctrl_count++] = CreateWindowExW(0, L"BUTTON", g_policy[i].label,
                WS_CHILD | BS_AUTOCHECKBOX, S(cx), S(cy), S(200), S(24), hwnd,
                (HMENU)(INT_PTR)g_policy[i].id, hinst, NULL);
        }
        g_ctl_font = mfont(13, FW_NORMAL);
        for (int i = 0; i < s_ctrl_count; i++)
            SendMessageW(s_ctrls[i], WM_SETFONT, (WPARAM)g_ctl_font, TRUE);
        SendMessageW(GetDlgItem(hwnd, IDM_DARK), BM_SETCHECK,
                     g_dark ? BST_CHECKED : BST_UNCHECKED, 0);
        apply_control_theme(hwnd);
        wchar_t tmp[32];
        swprintf(tmp, 32, L"%d", g_http_port);
        SetWindowTextW(GetDlgItem(hwnd, IDC_HTTP_EDIT), tmp);
        swprintf(tmp, 32, L"%d", g_https_port);
        SetWindowTextW(GetDlgItem(hwnd, IDC_HTTPS_EDIT), tmp);
        SendMessageW(GetDlgItem(hwnd, IDC_BIND_CHK), BM_SETCHECK,
                     g_bind_all ? BST_CHECKED : BST_UNCHECKED, 0);
        SendMessageW(GetDlgItem(hwnd, IDM_AUTOSTART), BM_SETCHECK,
                     win32_autostart_installed() ? BST_CHECKED : BST_UNCHECKED, 0);
        bool pol[POLICY_COUNT];
        policy_read(g_res, pol);
        for (int i = 0; i < POLICY_COUNT; i++)
            SendMessageW(GetDlgItem(hwnd, IDC_POL0 + i), BM_SETCHECK,
                         pol[i] ? BST_CHECKED : BST_UNCHECKED, 0);
        show_controls(hwnd, 0);
        g_taskbar_created = RegisterWindowMessageW(L"TaskbarCreated");
        tray_add(hwnd);
        cert_refresh(true);
        if (pthread_create(&g_poll_thread, NULL, stats_poll_thread, hwnd) == 0)
            g_poll_started = 1;   /* else: WM_TIMER fallback below */
        SetTimer(hwnd, 1, 2000, NULL);
        swprintf(g_status, 4096, L"等待首次统计（http://127.0.0.1:%d/internal-stats）...", g_http_port);
        return 0;
    }
    case WM_TIMER: {
        bool vis = IsWindowVisible(hwnd) != 0;
        g_ui_visible = vis ? 1 : 0;
        if (!vis && !g_hidden_trimmed) {
            /* Only the tray icon is left: hand the pages back to Windows so a
             * background process does not sit on tens of MB. */
            SetProcessWorkingSetSize(GetCurrentProcess(), (SIZE_T)-1, (SIZE_T)-1);
            g_hidden_trimmed = 1;
        } else if (vis) {
            g_hidden_trimmed = 0;
        }
        if (!g_poll_started && g_sn) {
            /* Fallback when the worker thread could not start. */
            snapshot_fetch_any(g_sn);
        }
        cert_refresh(false);
        diag_sample();
        /* Heartbeat every 5 minutes: after a sudden death the tail of
         * webserver.log then shows the last known CPU/memory/handle trend. */
        if (++g_hb_ticks >= 150) {
            g_hb_ticks = 0;
            win32_log_line("hb cpu=%.1f%% mem=%lluMB handles=%lu threads=%lu gdi=%lu "
                           "visible=%d server=%d valid=%d bind_ok=%d listeners=%d uptime=%llds",
                           g_cpu_pct, g_ws_kb / 1024ULL, (unsigned long)g_handle_count,
                           (unsigned long)g_thread_count, (unsigned long)g_gdi_obj,
                           (int)g_ui_visible, win32_server_alive() ? 1 : 0,
                           (g_sn && g_sn->valid) ? 1 : 0, (g_sn && g_sn->bind_ok) ? 1 : 0,
                           g_sn ? g_sn->listener_count : 0,
                           g_sn ? (long long)g_sn->uptime_seconds : 0LL);
        }
        if (!g_tray_ok) tray_add(hwnd);   /* taskbar missing at logon -> retry */
        if (vis) InvalidateRect(hwnd, NULL, FALSE);   /* never paint while hidden */
        return 0;
    }
    case WM_APP_STATS:
        if (g_sn) {
            pthread_mutex_lock(&g_snap_mutex);
            *g_sn = g_snap_next;
            pthread_mutex_unlock(&g_snap_mutex);
            if (g_sn->valid) {
                SYSTEMTIME st;
                GetLocalTime(&st);
                swprintf(g_status, 4096,
                         L"实时数据 · 管理端口 %d · 更新于 %02d:%02d:%02d",
                         mgmt_port(), st.wHour, st.wMinute, st.wSecond);
            } else {
                swprintf(g_status, 4096,
                         L"服务器暂不可达（已尝试 %d 与 %d 端口）", g_stats_port, g_http_port);
            }
            cert_refresh(false);
            if (g_ui_visible) InvalidateRect(hwnd, NULL, FALSE);
        }
        return 0;
    case WM_LBUTTONUP: {
        int x = (short)LOWORD(lp), y = (short)HIWORD(lp);
        if (nav_hit(x, y, 0) || nav_hit(x, y, 1)) {
            int new_tab = nav_hit(x, y, 0) ? 0 : 1;
            if (new_tab != g_tab) {
                g_tab = new_tab;
                show_controls(hwnd, g_tab);
                InvalidateRect(hwnd, NULL, TRUE);
            }
        } else if (x >= S(16) && x <= S(176) && y >= S(576) && y <= S(616)) {
            PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
        }
        return 0;
    }
    case WM_COMMAND:
        if (HIWORD(wp) == BN_CLICKED) {
            int id = LOWORD(wp);
            if (id == IDM_TRUST || id == IDM_UNTRUST) {
                char cert_path[1024];
                snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
                int rc = cert_trust_set(cert_path, id == IDM_TRUST);
                wchar_t msg[256];
                if (rc == 0)
                    swprintf(msg, 256, L"CA 证书已%s受信任根存储。浏览器访问 https://localhost 将显示安全锁。",
                             id == IDM_TRUST ? L"添加至" : L"移出");
                else
                    swprintf(msg, 256, L"证书操作失败（请检查 resources 目录）。");
                cert_refresh(true);   /* trust state changed - drop the cache */
                MessageBoxW(hwnd, msg, L"证书", MB_OK | MB_ICONINFORMATION);
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id == IDM_UPDATE) {
                swprintf(g_status, 4096, L"正在检查更新…");
                update_check_async(hwnd);
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id == IDM_TEST) {
                wchar_t url[256];
                swprintf(url, 256, L"https://localhost:%d/internal-test", g_https_port);
                ShellExecuteW(NULL, L"open", url, NULL, NULL, SW_SHOWNORMAL);
            } else if (id == IDM_AUTOSTART) {
                HWND chk = GetDlgItem(hwnd, IDM_AUTOSTART);
                bool on = SendMessageW(chk, BM_GETCHECK, 0, 0) == BST_CHECKED;
                char cmd[2048], exe[MAX_PATH];
                GetModuleFileNameA(NULL, exe, sizeof(exe));
                snprintf(cmd, sizeof(cmd),
                         "\"%s\" --resources \"%s\" --http-port %d --https-port %d --stats-port %d --minimized",
                         exe, g_res, g_http_port, g_https_port, g_stats_port);
                win32_autostart_set(on, cmd);
                swprintf(g_status, 4096, L"开机自启动已%s", on ? L"启用" : L"关闭");
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id == IDM_DARK) {
                bool on = SendMessageW(GetDlgItem(hwnd, IDM_DARK), BM_GETCHECK, 0, 0) == BST_CHECKED;
                g_theme_pref = on ? 1 : 0;
                g_dark = on ? 1 : 0;
                theme_apply(g_dark);
                apply_window_chrome(hwnd, g_dark);
                apply_control_theme(hwnd);
                ini_save(g_http_port, g_https_port, g_bind_all);
                swprintf(g_status, 4096, L"已切换为%ls主题（下次启动仍然生效）",
                         g_dark ? L"深色" : L"浅色");
                InvalidateRect(hwnd, NULL, TRUE);
            } else if (id == IDC_DIAG) {
                diag_write_file(hwnd);
                swprintf(g_status, 4096, L"诊断信息已写入程序目录的 diagnose.txt");
                InvalidateRect(hwnd, NULL, TRUE);
            } else if (id == IDC_FIXPORT) {
                /* The default ports were taken (another server is running):
                 * move to 18080/18443 and restart so the dashboard comes back
                 * on a port that is actually free. */
                wchar_t wt[32];
                swprintf(wt, 32, L"18080");
                SetWindowTextW(GetDlgItem(hwnd, IDC_HTTP_EDIT), wt);
                swprintf(wt, 32, L"18443");
                SetWindowTextW(GetDlgItem(hwnd, IDC_HTTPS_EDIT), wt);
                g_http_port = 18080;
                g_https_port = 18443;
                ini_save(g_http_port, g_https_port, g_bind_all);
                swprintf(g_status, 4096, L"已改用 18080/18443，正在重启...");
                win32_notify_restart();
                PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
            } else if (id == IDM_EXIT) {
                PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
            } else if (id == IDC_SAVE || id == IDC_RESTART) {
                wchar_t wt[32];
                int hp = 0, sp = 0;
                GetWindowTextW(GetDlgItem(hwnd, IDC_HTTP_EDIT), wt, 32); hp = _wtoi(wt);
                GetWindowTextW(GetDlgItem(hwnd, IDC_HTTPS_EDIT), wt, 32); sp = _wtoi(wt);
                if (hp < 1 || hp > 65535 || sp < 1 || sp > 65535) {
                    MessageBoxW(hwnd, L"端口范围必须为 1 - 65535。", L"设置", MB_OK | MB_ICONWARNING);
                } else {
                    bool bind = SendMessageW(GetDlgItem(hwnd, IDC_BIND_CHK),
                                             BM_GETCHECK, 0, 0) == BST_CHECKED;
                    ini_save(hp, sp, bind);
                    g_http_port = hp; g_https_port = sp; g_bind_all = bind;
                    if (id == IDC_RESTART) {
                        /* Save first; the old instance stops the server and
                           the main process relaunches a fresh instance. */
                        win32_notify_restart();
                        PostMessageW(hwnd, WM_APP_EXIT, 0, 0);
                    } else {
                        swprintf(g_status, 4096,
                                 L"设置已保存 - 点击\"保存并重启\"应用新端口绑定");
                        InvalidateRect(hwnd, NULL, FALSE);
                    }
                }
            } else if (id == IDC_FLUSH) {
                wchar_t st[128];
                control_post(mgmt_port(), "flush_stats", st, 128);
                swprintf(g_status, 4096, L"%ls", st);
                InvalidateRect(hwnd, NULL, FALSE);
            } else if (id >= IDC_POL0 && id < IDC_POL0 + POLICY_COUNT) {
                policy_apply(hwnd);
            }
        }
        return 0;
    case WM_ERASEBKGND:
        return 1;   /* handled in WM_PAINT - no erase flicker */
    case WM_SIZE:
        /* Maximize / resize: put the controls back where they belong and
           repaint the dashboard. */
        layout_controls(hwnd);
        if (g_ui_visible) InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC real = BeginPaint(hwnd, &ps);
        RECT rc;
        GetClientRect(hwnd, &rc);
        int cw = rc.right - rc.left, chh = rc.bottom - rc.top;
        HDC mem = CreateCompatibleDC(real);
        HBITMAP bmp = CreateCompatibleBitmap(real, cw, chh);
        HGDIOBJ oldbmp = SelectObject(mem, bmp);
        HDC hdc = mem;
        FillRect(hdc, &rc, GetSysColorBrush(COLOR_WINDOW));

        /* content background */
        RECT content = {0, 0, rc.right, rc.bottom};
        HBRUSH cb = CreateSolidBrush(g_pal.content_bg);
        FillRect(hdc, &content, cb);
        DeleteObject(cb);

        draw_sidebar(hdc);

        if (g_tab == 1) {
            /* grouped card backgrounds behind the native controls */
            int pol_bottom = 208 + ((POLICY_COUNT + 1) / 2) * 28;
            rounded_card(hdc, 202, 12, 812, 156, 12, g_pal.card, g_pal.border);
            rounded_card(hdc, 202, 178, 812, pol_bottom - 164, 12, g_pal.card, g_pal.border);
            rounded_card(hdc, 202, 372, 812, 92, 12, g_pal.card, g_pal.border);

            HFONT shf = mfont(15, FW_SEMIBOLD);
            HFONT lf = mfont(12, FW_NORMAL);
            HFONT old = (HFONT)SelectObject(hdc, shf);
            SetBkMode(hdc, TRANSPARENT);
            SetTextColor(hdc, g_pal.text);
            TextOutW(hdc, S(218), S(20), L"服务器", 3);
            SelectObject(hdc, lf);
            SetTextColor(hdc, g_pal.muted);
            wchar_t shint[320];
            swprintf(shint, 320,
                     L"本机访问：http://localhost:%d · 勾选“监听所有网卡”并重启后局域网可访问",
                     g_http_port);
            TextOutW(hdc, S(218), S(44), shint, (int)wcslen(shint));
            TextOutW(hdc, S(218), S(60), L"HTTP 端口:", 9);
            TextOutW(hdc, S(508), S(60), L"HTTPS 端口:", 10);
            SelectObject(hdc, shf);
            SetTextColor(hdc, g_pal.text);
            TextOutW(hdc, S(218), S(184), L"拦截策略（勾选后立即生效）", 12);
            TextOutW(hdc, S(218), S(378), L"证书与维护", 5);
            SelectObject(hdc, lf);
            SetTextColor(hdc, g_pal.muted);
            TextOutW(hdc, S(218), S(400), L"浏览器显示安全锁需要把 CA 证书加入受信任的根证书颁发机构。", 29);
            SelectObject(hdc, old);
            DeleteObject(shf);
            DeleteObject(lf);
            draw_listener_card(hdc, 202, 474, 812, 142, g_sn, 1);
            draw_statusbar(hdc);
            BitBlt(real, 0, 0, cw, chh, mem, 0, 0, SRCCOPY);
            SelectObject(mem, oldbmp);
            DeleteObject(bmp);
            DeleteDC(mem);
            EndPaint(hwnd, &ps);
            return 0;
        }

        /* statistics page */
        const struct snapshot *sn = g_sn;
        wchar_t val[64], txt[256];
        long long req = sn && sn->valid ? sn->total_requests : 0;
        long long blk = 0;
        if (sn && sn->valid) {
            for (int i = 0; i < sn->hist_count; i++) blk += sn->hist[i].blocked;
        }
        fmt_num(val, 64, req);
        draw_kpi(hdc, 210, 24, 120, 72, L"请求量", val, C_BLUE);
        fmt_num(val, 64, blk);
        draw_kpi(hdc, 348, 24, 120, 72, L"拦截量", val, C_RED);
        long long rate = (sn && sn->valid) ? (long long)(sn->block_rate * 100.0) : 0;
        swprintf(val, 64, L"%lld%%", rate);
        draw_kpi(hdc, 486, 24, 120, 72, L"拦截率", val, C_ACCENT);
        fmt_num(val, 64, sn && sn->valid ? sn->total_connections : 0);
        draw_kpi(hdc, 624, 24, 120, 72, L"连接数", val, C_GREEN);
        fmt_num(val, 64, sn && sn->valid ? sn->sni_certs_issued : 0);
        draw_kpi(hdc, 762, 24, 120, 72, L"签发证书", val, RGB(139, 92, 246));
        long long up = sn && sn->valid ? sn->uptime_seconds : 0;
        swprintf(val, 64, L"%lldh %lldm", up / 3600, (up % 3600) / 60);
        draw_kpi(hdc, 900, 24, 120, 72, L"运行时长", val, RGB(20, 184, 166));

        RECT ph = {210, 120, 600, 430};
        draw_chart(hdc, ph, L"最近 24 小时",
                   sn ? sn->hist : NULL, sn ? sn->hist_count : 0);
        RECT pd = {616, 120, 1006, 430};
        draw_chart(hdc, pd, L"最近 30 天",
                   sn ? sn->daily : NULL, sn ? sn->daily_count : 0);
        draw_legend(hdc, 232, 398);
        draw_legend(hdc, 638, 398);

        char cert_path[1024];
        snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", g_res);
        long long days = g_cert_days;          /* cached (30 s TTL) */
        int trusted = g_cert_trusted_flag;
        HFONT lf = mfont(13, FW_NORMAL);
        HFONT old2 = (HFONT)SelectObject(hdc, lf);
        if (days > 0 && days < 200000)
            swprintf(txt, 256, L"证书：剩余 %lld 天 · %s", days,
                     trusted ? L"已在受信任根中（浏览器绿色锁）" : L"未信任 - 左侧\"设置\"→\"信任 CA\"");
        else if (days == -100001)
            swprintf(txt, 256, L"证书文件缺失：%hs（首次运行时会自动生成）", cert_path);
        else if (days == -100002)
            swprintf(txt, 256, L"证书文件解析失败：%hs", cert_path);
        else
            swprintf(txt, 256, L"证书已过期 - 请删除 %hs 后重启", cert_path);
        SetTextColor(hdc, days > 0 && days < 200000 ? C_GREEN_TXT : RGB(190, 70, 70));
        TextOutW(hdc, S(210), S(464), txt, (int)wcslen(txt));
        {
            wchar_t hint[300];
            if (g_bind_all) {
                char lan[64] = "";
                if (first_lan_ip(lan, sizeof(lan)) == 0)
                    swprintf(hint, 300, L"访问地址：本机 http://localhost:%d  ·  局域网 http://%hs:%d",
                             g_http_port, lan, g_http_port);
                else
                    swprintf(hint, 300, L"访问地址：本机 http://localhost:%d", g_http_port);
            } else {
                swprintf(hint, 300, L"访问地址：http://localhost:%d（其他设备：设置→\"监听所有网卡\"）", g_http_port);
            }
            SetTextColor(hdc, g_pal.muted);
            TextOutW(hdc, S(210), S(486), hint, (int)wcslen(hint));
        }
        SelectObject(hdc, old2);
        DeleteObject(lf);

        draw_listener_card(hdc, 210, 502, 796, 130, sn, 1);
        draw_statusbar(hdc);
        BitBlt(real, 0, 0, cw, chh, mem, 0, 0, SRCCOPY);
        SelectObject(mem, oldbmp);
        DeleteObject(bmp);
        DeleteDC(mem);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_APP_UPDATE_FOUND: {
        struct update_info *info = (struct update_info *) lp;
        if (wp && info) {
            wchar_t msg[512];
            swprintf(msg, 512,
                     L"发现新版本 %ls（当前版本 v" L"" ADBLOCK_APP_VERSION L"）。\n\n"
                     L"现在下载并自动更新吗？更新会关闭本窗口，替换文件后自动重启。",
                     info->tag);
            if (MessageBoxW(hwnd, msg, L"检查更新", MB_YESNO | MB_ICONQUESTION) == IDYES) {
                swprintf(g_status, 4096, L"正在下载更新（完成后会自动重启）…");
                update_apply_async(hwnd, info);
            } else {
                swprintf(g_status, 4096, L"已忽略新版本 %ls", info->tag);
            }
        } else {
            swprintf(g_status, 4096, L"已是最新版本（v" L"" ADBLOCK_APP_VERSION L"）");
        }
        update_info_free(info);
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    }
    case WM_APP_TRAY:
        if (lp == WM_RBUTTONUP) {
            tray_menu(hwnd);
        } else if (lp == WM_LBUTTONUP || lp == WM_LBUTTONDBLCLK) {
            ShowWindow(hwnd, SW_SHOW);
            SetForegroundWindow(hwnd);
        }
        return 0;
    case WM_APP_EXIT:
        tray_remove();
        DestroyWindow(hwnd);
        return 0;
    case WM_CLOSE:
        ShowWindow(hwnd, SW_HIDE);   /* minimize to tray */
        return 0;
    case WM_DESTROY:
        KillTimer(hwnd, 1);
        if (g_poll_started) {
            g_poll_stop = 1;
            pthread_join(g_poll_thread, NULL);
            g_poll_started = 0;
        }
        tray_remove();
        if (g_ctl_font) { DeleteObject(g_ctl_font); g_ctl_font = NULL; }
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(hwnd, msg, wp, lp);
    }
}

int adblock_gui_run(const struct adblock_gui_args *args) {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return -1;

    HINSTANCE hinst = GetModuleHandleW(NULL);
    WNDCLASSEXW wc;
    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = gui_proc;
    wc.hInstance = hinst;
    wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = L"ADBlockDashWin11";
    if (!RegisterClassExW(&wc)) {
        WSACleanup();
        return -1;
    }
    {
        double sc = GetDpiForSystem() / 96.0;
        HWND hwnd = CreateWindowExW(0, L"ADBlockDashWin11", g_title,
            (WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX |
             WS_MAXIMIZEBOX | WS_THICKFRAME |
             /* WS_CLIPCHILDREN: without it the parent repaints over the child
                controls on every statistics tick (1 Hz), which shows up as a
                constantly flickering window. */
             WS_CLIPCHILDREN),
            CW_USEDEFAULT, CW_USEDEFAULT,
            (int)(1024 * sc), (int)(678 * sc),
            NULL, NULL, hinst, (LPVOID)args);
        if (!hwnd) {
            WSACleanup();
            return -1;
        }
        ShowWindow(hwnd, g_start_minimized ? SW_HIDE : SW_SHOW);
        UpdateWindow(hwnd);
        MSG msg;
        while (GetMessageW(&msg, NULL, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        WSACleanup();
        return 0;
    }
}
