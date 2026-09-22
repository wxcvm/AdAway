/*
 * In-app update for the Windows dashboard.
 *
 * Updating by hand (download a zip, extract it, replace the files) is what this
 * replaces: the dashboard checks the public release feed, downloads the new zip,
 * extracts it and restarts through a small helper batch file - a running .exe
 * cannot replace itself.
 *
 * The feed is the public repository also used by the Android build, because the
 * app repository itself is private and its release API cannot be read without a
 * token.
 */
#include "update_win32.h"
#include "gui_win32.h"

#include <winhttp.h>
#include <bcrypt.h>   /* SHA-256 of the downloaded update package */
/* Posted by the dashboard to really quit (defined in gui_win32.c). */
#ifndef WM_APP_EXIT
#define WM_APP_EXIT (WM_USER + 2)
#endif
#include <wintrust.h>
#include <softpub.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define UPDATE_API_HOST L"api.github.com"
/* 100 instead of 30: the repository also carries the Android releases, and a
   Windows release that fell outside the first page was simply "not found",
   which the dashboard then reported as "already up to date". */
#define UPDATE_API_PATH L"/repos/wxcvm/AdAway/releases?per_page=100"
#define UPDATE_MAX_BYTES (8u * 1024u * 1024u)   /* API JSON answer only */
/* Hard cap for the downloaded update package: without it a hostile or broken
   release could stream until the disk is full (phase-2 audit, item A). */
#define UPDATE_DOWNLOAD_MAX_BYTES (64u * 1024u * 1024u)
/* Extraction budget (entries / total bytes) - see the staging check. */
#define UPDATE_EXTRACT_MAX_ENTRIES 4000u
#define UPDATE_EXTRACT_MAX_BYTES (256u * 1024u * 1024u)

static HWND s_update_hwnd;

/*
 * What the route probe asks for: the exact manifest the update check needs.
 * Asking for this URL validates github.com *and* the release-assets host the
 * 302 points at, in one request (audit item 41). Defined here because the probe
 * is far above UPDATE_MANIFEST_PATH.
 */
#define UPDATE_PROBE_PATH L"/wxcvm/AdAway/releases/download/win11-latest/windows-manifest.json"

/*
 * ── update state (audit item 36) ───────────────────────────────────
 * The updater had no busy flag, so every click started another check thread -
 * and those threads share s_proxy, s_proxy_mode, s_proxy_resolved,
 * s_manifest_status and s_force_check without any synchronisation: one click
 * could reset the route another click was using. The state below serialises
 * the whole pipeline; the dashboard button follows it.
 */
#define UPDATE_STATE_IDLE        0
#define UPDATE_STATE_CHECKING    1
#define UPDATE_STATE_DOWNLOADING 2
#define UPDATE_STATE_INSTALLING  3
static volatile LONG s_update_state = UPDATE_STATE_IDLE;

/* Current pipeline state (see update_state() in update_win32.h). */
int update_state(void) {
    return (int) InterlockedCompareExchange(&s_update_state, 0, 0);
}

/* ── proxy selection ────────────────────────────────────────────────
 * GitHub is not reachable at all on some networks: measured from the
 * maintainer's line github.com times out (21 s, no route) while
 * api.github.com answers in 1.7 s - and a local Clash-style proxy on the same
 * machine does work. WinHTTP's "automatic proxy" only honours the *system*
 * proxy setting, which is regularly switched off even while such a proxy
 * runs, so the update path picks its route itself:
 *   1. no proxy, when github.com answers directly (the normal case),
 *   2. the system proxy setting (what the dashboard always used before),
 *   3. webserver.ini [settings] proxy=host:port (manual override),
 *   4. HTTPS_PROXY / HTTP_PROXY from the environment,
 *   5. the usual local proxy ports (7890, 7891, 10809, 10808, 1080, 8888).
 * Every candidate is verified with a real HTTPS request (not just a TCP
 * connect) and the chosen route is written to webserver.log.
 *
 * This file is its own translation unit: the LOG_* macros live inside
 * webserver.c, so log through the dashboard wrapper declared in gui_win32.h.
 */
#define LOG_INFO(...) win32_log_line(__VA_ARGS__)
#define LOG_WARN(...) win32_log_line(__VA_ARGS__)

#define PROXY_MODE_DIRECT 0   /* straight to the internet */
#define PROXY_MODE_AUTO   1   /* the system proxy setting (WinHTTP automatic) */
#define PROXY_MODE_NAMED  2   /* s_proxy below */

static wchar_t s_proxy[64];
static int s_proxy_mode = PROXY_MODE_DIRECT;
static int s_proxy_resolved;

/*
 * One real HTTPS request over `access_type`/`proxy`. 1 = the request the update
 * check actually needs answered.
 *
 * It asks for the *manifest asset itself*, not github.com/robots.txt: that
 * single request covers both hosts the update really uses, because GitHub
 * answers with a 302 to release-assets.githubusercontent.com and WinHTTP
 * follows it. Probing /robots.txt only ever proved that github.com answered -
 * a route that then died on the asset host was still reported as healthy
 * (audit item 41).
 */
static int https_probe(DWORD access_type, const wchar_t *proxy) {
    HINTERNET session, connect, request;
    int ok = 0;
    session = WinHttpOpen(L"ADBlock-WebServer", WINHTTP_ACCESS_TYPE_NO_PROXY,
                          WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (session == NULL) return 0;
    WinHttpSetTimeouts(session, 2000, 2000, 2000, 3000);
    if (access_type != WINHTTP_ACCESS_TYPE_NO_PROXY) {
        WINHTTP_PROXY_INFO pi;
        memset(&pi, 0, sizeof(pi));
        pi.dwAccessType = access_type;
        /* WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY requires both to stay NULL. */
        pi.lpszProxy = (LPWSTR) proxy;
        pi.lpszProxyBypass = proxy != NULL ? L"<local>" : NULL;
        WinHttpSetOption(session, WINHTTP_OPTION_PROXY, &pi, sizeof(pi));
    }
    connect = WinHttpConnect(session, L"github.com", INTERNET_DEFAULT_HTTPS_PORT, 0);
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
    request = connect != NULL
        ? WinHttpOpenRequest(connect, L"HEAD", UPDATE_PROBE_PATH, NULL, WINHTTP_NO_REFERER,
                             WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE)
        : NULL;
    if (request != NULL)
        WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy));
    if (request != NULL &&
        WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                           WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
        WinHttpReceiveResponse(request, NULL)) {
        ok = 1;
    }
    if (request != NULL) WinHttpCloseHandle(request);
    if (connect != NULL) WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return ok;
}

/* 1 when the system proxy setting alone reaches github.com. */
static int https_probe_system(void) {
    return https_probe(WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, NULL);
}

/* webserver.ini next to the exe (the dashboard writes it). */
static int ini_path(wchar_t *out, size_t cap) {
    wchar_t exe[MAX_PATH];
    if (!GetModuleFileNameW(NULL, exe, MAX_PATH)) return 0;
    wchar_t *slash = wcsrchr(exe, L'\\');
    if (slash != NULL) *slash = 0;
    swprintf(out, cap, L"%s\\webserver.ini", exe);
    return 1;
}

/* [settings] proxy=... from webserver.ini ("" when unset). */
static void proxy_from_ini(wchar_t *out, size_t cap) {
    wchar_t ini[MAX_PATH];
    out[0] = 0;
    if (!ini_path(ini, MAX_PATH)) return;
    GetPrivateProfileStringW(L"settings", L"proxy", L"", out, (DWORD) cap, ini);
}

/* ── optional download mirror ───────────────────────────────────────
 * Some networks cannot reach github.com or its asset hosts at all, while a
 * public GitHub accelerator (ghfast.top, gh-proxy.com, ...) can. webserver.ini
 * may therefore name one:
 *     [settings]
 *     mirror=https://ghfast.top/
 * Every github.com URL is then fetched as <mirror>/https://github.com/... .
 * A mirror cannot inject a different binary: the published SHA-256 is still
 * verified before anything is executed.
 */
static wchar_t s_mirror_host[128];
static int s_mirror_loaded;

static void mirror_from_ini(void) {
    wchar_t ini[MAX_PATH], raw[512];
    if (s_mirror_loaded) return;
    s_mirror_loaded = 1;
    s_mirror_host[0] = 0;
    if (!ini_path(ini, MAX_PATH)) return;
    GetPrivateProfileStringW(L"settings", L"mirror", L"", raw, 512, ini);
    if (raw[0] == 0) return;
    const wchar_t *p = wcsstr(raw, L"://");
    p = p != NULL ? p + 3 : raw;
    wcsncpy(s_mirror_host, p, 127);
    s_mirror_host[127] = 0;
    for (wchar_t *q = s_mirror_host; *q != 0; q++) {
        if (*q == L'/') {                 /* keep only "host[:port]" */
            *q = 0;
            break;
        }
    }
    if (s_mirror_host[0] != 0)
        LOG_INFO("update: release downloads go through the mirror from webserver.ini");
}

/* Rewrite a github.com request for the mirror; other hosts are left alone.
   Returns the host to connect to and fills path_out. */
static const wchar_t *mirror_map(const wchar_t *host, const wchar_t *path,
                                 wchar_t *path_out, size_t cap) {
    mirror_from_ini();
    if (s_mirror_host[0] == 0) return host;
    /*
     * The release *asset* hosts matter as much as github.com: the manifest is a
     * plain github.com URL, but the multi-megabyte package is served from
     * release-assets.githubusercontent.com (or objects.githubusercontent.com
     * for older releases), and a mirror that only rewrote github.com left the
     * package download on the blocked host (audit item 42).
     */
    if (_wcsicmp(host, L"github.com") != 0 &&
        _wcsicmp(host, L"api.github.com") != 0 &&
        _wcsicmp(host, L"release-assets.githubusercontent.com") != 0 &&
        _wcsicmp(host, L"objects.githubusercontent.com") != 0 &&
        _wcsicmp(host, L"raw.githubusercontent.com") != 0)
        return host;
    swprintf(path_out, cap, L"/https://%ls%ls", host, path);
    return s_mirror_host;
}

/* The log wrapper is a char-based printf, and MinGW's runtime does not
   reliably understand %ls - convert the route to UTF-8 instead. */
static void proxy_log(const char *what, const wchar_t *proxy) {
    char narrow[128];
    if (WideCharToMultiByte(CP_UTF8, 0, proxy, -1, narrow, (int) sizeof(narrow),
                            NULL, NULL) <= 0) {
        narrow[0] = 0;
    }
    LOG_INFO("update: %s (%s)", what, narrow);
}

/* Remember a verified route (always NUL-terminated, whatever the length). */
static void proxy_set(const wchar_t *value) {
    wcsncpy(s_proxy, value, 63);
    s_proxy[63] = 0;
    s_proxy_mode = PROXY_MODE_NAMED;
}

static void resolve_update_proxy(void) {
    static const wchar_t *candidates[] = {
        L"127.0.0.1:7890", L"127.0.0.1:7891", L"127.0.0.1:10809",
        L"127.0.0.1:10808", L"127.0.0.1:1080", L"127.0.0.1:8888"
    };
    wchar_t configured[64] = L"";
    if (s_proxy_resolved) return;
    s_proxy_resolved = 1;
    s_proxy_mode = PROXY_MODE_DIRECT;
    s_proxy[0] = 0;
    /* 1) direct: the common case, one quick probe. */
    if (https_probe(WINHTTP_ACCESS_TYPE_NO_PROXY, NULL)) {
        /* Logged even on success: the route a check used is the first thing
           anyone asks about when an update fails, and this line used to be
           missing for the direct case. */
        LOG_INFO("update: github.com is reachable directly");
        return;
    }
    LOG_INFO("update: github.com is not reachable directly, looking for a proxy");
    /* 2) the system proxy setting, i.e. exactly what earlier versions used. */
    if (https_probe_system()) {
        s_proxy_mode = PROXY_MODE_AUTO;
        LOG_INFO("update: using the system proxy setting");
        return;
    }
    /* 3) manual override. */
    proxy_from_ini(configured, 64);
    if (configured[0] != 0 && https_probe(WINHTTP_ACCESS_TYPE_NAMED_PROXY, configured)) {
        proxy_set(configured);
        proxy_log("using the proxy from webserver.ini", s_proxy);
        return;
    }
    /* 4) environment (HTTPS_PROXY / HTTP_PROXY). */
    {
        const wchar_t *env = _wgetenv(L"HTTPS_PROXY");
        if (env == NULL || env[0] == 0) env = _wgetenv(L"HTTP_PROXY");
        if (env != NULL && env[0] != 0) {
            const wchar_t *p = wcsstr(env, L"://");
            wchar_t candidate[64] = L"";
            p = p != NULL ? p + 3 : env;
            wcsncpy(candidate, p, 63);
            candidate[63] = 0;
            size_t n = wcslen(candidate);
            while (n > 0 && candidate[n - 1] == L'/') candidate[--n] = 0;
            if (candidate[0] != 0 &&
                https_probe(WINHTTP_ACCESS_TYPE_NAMED_PROXY, candidate)) {
                proxy_set(candidate);
                proxy_log("using the proxy from the environment", s_proxy);
                return;
            }
        }
    }
    /* 5) the usual local proxy ports. */
    for (int i = 0; i < (int) (sizeof(candidates) / sizeof(candidates[0])); i++) {
        if (https_probe(WINHTTP_ACCESS_TYPE_NAMED_PROXY, candidates[i])) {
            proxy_set(candidates[i]);
            proxy_log("using a detected local proxy", s_proxy);
            return;
        }
    }
    LOG_WARN("update: no working proxy found - downloads will use a direct connection");
}

/* Apply the resolved route to a freshly opened WinHTTP session. */
static void apply_update_proxy(HINTERNET session) {
    WINHTTP_PROXY_INFO pi;
    resolve_update_proxy();
    if (s_proxy_mode == PROXY_MODE_DIRECT) return;
    memset(&pi, 0, sizeof(pi));
    pi.dwAccessType = s_proxy_mode == PROXY_MODE_AUTO ? WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY
                                                      : WINHTTP_ACCESS_TYPE_NAMED_PROXY;
    pi.lpszProxy = s_proxy_mode == PROXY_MODE_AUTO ? NULL : s_proxy;
    pi.lpszProxyBypass = s_proxy_mode == PROXY_MODE_AUTO ? NULL : L"<local>";
    WinHttpSetOption(session, WINHTTP_OPTION_PROXY, &pi, sizeof(pi));
}

/* ── helpers ──────────────────────────────────────────────────── */

/* Declared here because http_get() below logs its own outcome (the request
   helpers themselves come right after it). */
static void log_http_line(const char *what, const wchar_t *host, const wchar_t *path,
                          DWORD status, unsigned long long ms, int attempt);
static void log_http_failure(const wchar_t *host, const wchar_t *path, DWORD status);
static char *http_get_retry(const wchar_t *host, const wchar_t *path, size_t *out_len,
                            DWORD *out_status, int attempts, const char *what);

static void utf8_to_wide(const char *in, wchar_t *out, size_t cap) {
    if (cap == 0) return;
    out[0] = 0;
    MultiByteToWideChar(CP_UTF8, 0, in, -1, out, (int) cap);
    out[cap - 1] = 0;
}

/* Value of "key": "..." at or after `from`. */
static int json_string(const char *json, const char *key, const char *from, char *out, size_t cap) {
    const char *p = strstr(from, key);
    if (!p) return 0;
    p = strchr(p + strlen(key), ':');
    if (!p) return 0;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return 0;
    p++;
    size_t n = 0;
    while (*p && *p != '"' && n + 1 < cap) {
        if (*p == '\\' && p[1]) p++;
        out[n++] = *p++;
    }
    out[n] = 0;
    return n > 0;
}

/* Dotted version compare: > 0 when a is newer than b. */
static int version_cmp(const char *a, const char *b) {
    for (;;) {
        int va = 0, vb = 0;
        while (*a >= '0' && *a <= '9') va = va * 10 + (*a++ - '0');
        while (*b >= '0' && *b <= '9') vb = vb * 10 + (*b++ - '0');
        if (va != vb) return va - vb;
        while (*a && (*a < '0' || *a > '9')) a++;
        while (*b && (*b < '0' || *b > '9')) b++;
        if (!*a && !*b) return 0;
        if (!*a) return -1;
        if (!*b) return 1;
    }
}

static char *http_get(const wchar_t *host, const wchar_t *path, size_t *out_len,
                      DWORD *out_status);

/* ── Zero-API update path + on-disk answer cache ────────────────────
 * The unauthenticated REST API allows 60 requests/hour PER IP and a PC behind
 * NAT shares that budget, so 检查更新 returned 403 although the network was
 * fine. Fix: (1) the fixed tag win11-latest always carries
 * windows-manifest.json, fetched as a plain release download (no API at all);
 * (2) if that is unreachable, the API answer is cached on disk - a successful
 * one is reused for UPDATE_CACHE_TTL_SEC and a 403/429 is not repeated before
 * UPDATE_COOLDOWN_SEC. */
#define UPDATE_MANIFEST_HOST L"github.com"
#define UPDATE_MANIFEST_PATH L"/wxcvm/AdAway/releases/download/win11-latest/windows-manifest.json"
#define UPDATE_CACHE_TTL_SEC   (6u * 3600u)
#define UPDATE_COOLDOWN_SEC    (30u * 60u)

struct update_cache {
    int   rc;
    DWORD status;
    DWORD checked_at;
    char  tag[128];
    char  url[1024];
    char  api_url[1024];
    char  sha256[128];
};

static void update_cache_path(char *out, size_t cap) {
    wchar_t exe[MAX_PATH] = L"";
    out[0] = 0;
    if (!GetModuleFileNameW(NULL, exe, MAX_PATH)) return;
    wchar_t *slash = wcsrchr(exe, L'\\');
    if (slash) *slash = 0;
    char dir[MAX_PATH * 3] = "";
    WideCharToMultiByte(CP_UTF8, 0, exe, -1, dir, sizeof(dir), NULL, NULL);
    snprintf(out, cap, "%s/resources/update_cache.json", dir);
}

static bool update_cache_load(struct update_cache *c) {
    char path[MAX_PATH * 3];
    memset(c, 0, sizeof(*c));
    update_cache_path(path, sizeof(path));
    if (!path[0]) return false;
    FILE *f = fopen(path, "r");
    if (!f) return false;
    char buf[4096];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    buf[n] = 0;
    fclose(f);
    char tmp[32] = "";
    if (json_string(buf, "\"rc\"", buf, tmp, sizeof(tmp))) c->rc = atoi(tmp);
    if (json_string(buf, "\"status\"", buf, tmp, sizeof(tmp))) c->status = (DWORD) atoi(tmp);
    if (json_string(buf, "\"checked_at\"", buf, tmp, sizeof(tmp))) c->checked_at = (DWORD) atoi(tmp);
    json_string(buf, "\"tag\"", buf, c->tag, sizeof(c->tag));
    json_string(buf, "\"url\"", buf, c->url, sizeof(c->url));
    json_string(buf, "\"api_url\"", buf, c->api_url, sizeof(c->api_url));
    json_string(buf, "\"sha256\"", buf, c->sha256, sizeof(c->sha256));
    return c->checked_at != 0;
}

static void update_cache_save(const struct update_cache *c) {
    char path[MAX_PATH * 3];
    update_cache_path(path, sizeof(path));
    if (!path[0]) return;
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "{\n  \"rc\": \"%d\",\n  \"status\": \"%lu\",\n  \"checked_at\": \"%lu\",\n"
               "  \"tag\": \"%s\",\n  \"url\": \"%s\",\n  \"api_url\": \"%s\",\n"
               "  \"sha256\": \"%s\"\n}\n",
            c->rc, (unsigned long) c->status, (unsigned long) c->checked_at,
            c->tag, c->url, c->api_url, c->sha256);
    fclose(f);
}

/* One release as published: the installer is the normal package, the portable
   zip is both the fallback and the in-place update of a hand-extracted copy. */
struct release_pick {
    char tag[128];
    char ver[64];
    char exe_url[1024];
    char exe_sha[128];
    char exe_api_url[512];   /* /releases/assets/<id> endpoint (API answer only) */
    char zip_url[1024];
    char zip_sha[128];
    char zip_api_url[512];
};

/* Folder the running executable lives in. */
static int exe_dir(wchar_t *out, size_t cap) {
    wchar_t exe[MAX_PATH];
    if (cap == 0) return 0;
    out[0] = 0;
    if (!GetModuleFileNameW(NULL, exe, MAX_PATH)) return 0;
    wchar_t *slash = wcsrchr(exe, L'\\');
    if (slash) *slash = 0;
    wcsncpy(out, exe, cap - 1);
    out[cap - 1] = 0;
    return out[0] != 0;
}

/* Compare two folders: case-insensitive, '/' == '\', trailing separators
   ignored (Inno records the folder without one, GetModuleFileName has none
   either, but a hand-edited registry value may). */
static int dir_same(const wchar_t *a, const wchar_t *b) {
    for (;;) {
        while (*a == L'/') a++;
        while (*b == L'/') b++;
        wchar_t ca = *a, cb = *b;
        if (ca == L'\\') ca = 0;
        if (cb == L'\\') cb = 0;
        if (ca >= L'a' && ca <= L'z') ca = (wchar_t) (ca - 32);
        if (cb >= L'a' && cb <= L'z') cb = (wchar_t) (cb - 32);
        if (ca != cb) return 0;
        if (ca == 0) return 1;
        a++;
        b++;
    }
}

static int read_install_location(REGSAM view, wchar_t *out, size_t cap) {
    static const wchar_t *sub =
        L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\"
        L"{8F2A61D4-6C0B-4B3E-9E77-ADB10C1A5F27}_is1";
    HKEY key = NULL;
    DWORD type = 0, size = (DWORD) (cap * sizeof(wchar_t));
    LONG rc;
    out[0] = 0;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_QUERY_VALUE | view, &key) != ERROR_SUCCESS)
        return 0;
    rc = RegQueryValueExW(key, L"InstallLocation", NULL, &type, (LPBYTE) out, &size);
    RegCloseKey(key);
    if (rc != ERROR_SUCCESS || type != REG_SZ || size < sizeof(wchar_t)) {
        out[0] = 0;
        return 0;
    }
    out[cap - 1] = 0;
    return out[0] != 0;
}

/*
 * Did the Inno Setup installer create the copy we are running from?
 *
 * The installer records its destination in
 * HKCU\...\Uninstall\<AppId>_is1\InstallLocation. When the running exe lives
 * somewhere else the user is using the portable zip: letting the installer
 * "update" would install a SECOND copy under %LOCALAPPDATA%\Programs\ADBlock
 * and leave the folder the user actually runs completely untouched - the
 * classic "updated but still the old version" report.
 */
static int running_copy_is_installed(void) {
    wchar_t installed[MAX_PATH] = L"", here[MAX_PATH] = L"";
    if (!exe_dir(here, MAX_PATH)) return 1;   /* cannot tell: keep the old path */
    if (!read_install_location(0, installed, MAX_PATH) &&
        !read_install_location(KEY_WOW64_64KEY, installed, MAX_PATH) &&
        !read_install_location(KEY_WOW64_32KEY, installed, MAX_PATH)) {
        return 0;                              /* no install record: portable */
    }
    return dir_same(installed, here);
}

/*
 * Pick the package for THIS machine and hand back the plain download URL, the
 * published digest and (when the API answered) the /releases/assets/<id>
 * fallback URL.
 */
static void release_pick_choose(const struct release_pick *p, wchar_t *url, size_t url_cap,
                                char *sha, size_t sha_cap, wchar_t *api_url, size_t api_cap,
                                wchar_t *alt_url, size_t alt_url_cap,
                                char *alt_sha, size_t alt_sha_cap,
                                wchar_t *alt_api, size_t alt_api_cap) {
    int portable = p->zip_url[0] && !running_copy_is_installed();
    const char *u = portable ? p->zip_url : (p->exe_url[0] ? p->exe_url : p->zip_url);
    const char *s = portable ? p->zip_sha : (p->exe_url[0] ? p->exe_sha : p->zip_sha);
    const char *a = portable ? p->zip_api_url : (p->exe_url[0] ? p->exe_api_url : p->zip_api_url);
    /* The other package of the same release, as a second chance when the first
       one cannot be fetched at all. */
    int alt_is_exe = portable && p->exe_url[0];
    const char *au = alt_is_exe ? p->exe_url : (!portable && p->zip_url[0] ? p->zip_url : NULL);
    const char *as = alt_is_exe ? p->exe_sha : (!portable && p->zip_url[0] ? p->zip_sha : NULL);
    const char *aa = alt_is_exe ? p->exe_api_url : (!portable && p->zip_url[0] ? p->zip_api_url : NULL);
    if (url && url_cap) url[0] = 0;
    if (api_url && api_cap) api_url[0] = 0;
    if (sha && sha_cap) sha[0] = 0;
    if (alt_url && alt_url_cap) alt_url[0] = 0;
    if (alt_api && alt_api_cap) alt_api[0] = 0;
    if (alt_sha && alt_sha_cap) alt_sha[0] = 0;
    if (u && u[0] && url && url_cap) utf8_to_wide(u, url, url_cap);
    if (a && a[0] && api_url && api_cap) utf8_to_wide(a, api_url, api_cap);
    if (s && s[0] && sha && sha_cap) snprintf(sha, sha_cap, "%s", s);
    if (au && au[0] && alt_url && alt_url_cap) utf8_to_wide(au, alt_url, alt_url_cap);
    if (aa && aa[0] && alt_api && alt_api_cap) utf8_to_wide(aa, alt_api, alt_api_cap);
    if (as && as[0] && alt_sha && alt_sha_cap) snprintf(alt_sha, alt_sha_cap, "%s", as);
}

/* Fixed-tag manifest: 1 newer, 0 up to date (no API needed), -1 unavailable. */
static int manifest_check(struct release_pick *p, DWORD *status) {
    size_t len = 0;
    DWORD st = 0;
    memset(p, 0, sizeof(*p));
    /* Three attempts: one hiccup on the asset host must not push the check onto
       the rate-limited REST API. */
    char *json = http_get_retry(UPDATE_MANIFEST_HOST, UPDATE_MANIFEST_PATH, &len, &st,
                                3, "manifest");
    if (status) *status = st;
    if (!json) {
        /* The route may have gone stale (proxy started/stopped since the
           probe); re-resolve it on the next attempt. */
        s_proxy_resolved = 0;
        return -1;
    }
    json_string(json, "\"tag\"", json, p->tag, sizeof(p->tag));
    json_string(json, "\"version\"", json, p->ver, sizeof(p->ver));
    json_string(json, "\"exe_url\"", json, p->exe_url, sizeof(p->exe_url));
    json_string(json, "\"exe_sha256\"", json, p->exe_sha, sizeof(p->exe_sha));
    json_string(json, "\"zip_url\"", json, p->zip_url, sizeof(p->zip_url));
    json_string(json, "\"zip_sha256\"", json, p->zip_sha, sizeof(p->zip_sha));
    free(json);
    if (!p->ver[0]) return -1;
    /* Neither package published: treat the manifest as unusable so the REST
       API (with its cache) still gets a chance to answer. */
    if (!p->exe_url[0] && !p->zip_url[0]) return -1;
    if (version_cmp(p->ver, ADBLOCK_APP_VERSION) > 0) return 1;
    return 0;
}

/* HTTP(S) GET (system proxy aware, follows redirects). Caller frees. */
static char *http_get(const wchar_t *host, const wchar_t *path, size_t *out_len,
                      DWORD *out_status) {
    HINTERNET session = NULL, connect = NULL, request = NULL;
    char *body = NULL;
    size_t total = 0, cap = 0;
    DWORD status = 0, slen = sizeof(status);
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
    wchar_t mapped[1200];
    const wchar_t *connect_host = mirror_map(host, path, mapped, 1200);
    const wchar_t *connect_path = connect_host == host ? path : mapped;

    session = WinHttpOpen(L"ADBlock-WebServer", WINHTTP_ACCESS_TYPE_NO_PROXY,
                          WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return NULL;
    apply_update_proxy(session);
    WinHttpSetTimeouts(session, 8000, 8000, 10000, 30000);
    connect = WinHttpConnect(session, connect_host, INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!connect) goto done;
    request = WinHttpOpenRequest(connect, L"GET", connect_path, NULL, WINHTTP_NO_REFERER,
                                 WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    if (!request) goto done;
    WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy));
    if (!WinHttpSendRequest(request,
                            L"User-Agent: ADBlock-WebServer\r\nAccept: application/vnd.github+json\r\n",
                            (DWORD) -1L, WINHTTP_NO_REQUEST_DATA, 0, 0, 0))
        goto done;
    if (!WinHttpReceiveResponse(request, NULL)) goto done;
    if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                             WINHTTP_HEADER_NAME_BY_INDEX, &status, &slen, WINHTTP_NO_HEADER_INDEX))
        goto done;
    if (out_status) *out_status = status;   /* tell "403 rate limit" from "no update" */
    if (status != 200) goto done;
    /*
     * A truncated body must not look like a complete answer (audit item 45):
     * the old loop broke on a read error and still returned whatever had
     * arrived, so half a JSON document was parsed as a manifest. Both failure
     * kinds mark the result as failed now.
     */
    int read_error = 0;
    unsigned long long declared = 0;
    DWORD declared_size = (DWORD) sizeof(declared);
    WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &declared, &declared_size,
                        WINHTTP_NO_HEADER_INDEX);
    for (;;) {
        DWORD avail = 0, read = 0;
        if (!WinHttpQueryDataAvailable(request, &avail)) { read_error = 1; break; }
        if (avail == 0) break;                      /* clean end of body */
        if (total + avail + 1 > UPDATE_MAX_BYTES) { read_error = 1; break; }
        if (total + avail + 1 > cap) {
            size_t want = (total + avail + 1) * 2;
            char *grown = (char *) realloc(body, want);
            if (!grown) { free(body); body = NULL; goto done; }
            body = grown;
            cap = want;
        }
        if (!WinHttpReadData(request, body + total, avail, &read)) { read_error = 1; break; }
        if (read == 0) { read_error = 1; break; }
        total += read;
    }
    if (read_error || (declared > 0 && total < declared)) {
        free(body);
        body = NULL;
    } else if (body) {
        body[total] = 0;
        if (out_len) *out_len = total;
    }
done:
    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    if (session) WinHttpCloseHandle(session);
    if (body == NULL) log_http_failure(host, path, status);
    return body;
}

/* One log line per request outcome. Both this and the failure line below are
   needed: "检查更新失败" used to leave nothing at all in webserver.log, so a
   failed check could not be explained afterwards. */
static void log_http_line(const char *what, const wchar_t *host, const wchar_t *path,
                          DWORD status, unsigned long long ms, int attempt) {
    char narrow[512];
    wchar_t wide[512];
    swprintf(wide, 512, L"https://%ls%ls", host, path);
    if (WideCharToMultiByte(CP_UTF8, 0, wide, -1, narrow, (int) sizeof(narrow),
                            NULL, NULL) <= 0) {
        narrow[0] = 0;
    }
    win32_log_line("update: %s %s -> HTTP %lu in %llu ms (attempt %d)",
                   what, narrow, (unsigned long) status, ms, attempt);
}

static void log_http_failure(const wchar_t *host, const wchar_t *path, DWORD status) {
    log_http_line("GET failed", host, path, status, 0ULL, 0);
    win32_log_line("update: last WinHTTP error %lu", (unsigned long) GetLastError());
}

/*
 * http_get with retries. The release asset host occasionally answers 503/429 or
 * drops the connection; the old code treated the very first failure as "no
 * manifest", fell through to the REST API and - because that one is limited to
 * 60 requests/hour per IP - reported a rate limit as the reason for an update
 * failure, with nothing in the log explaining it.
 */
static char *http_get_retry(const wchar_t *host, const wchar_t *path, size_t *out_len,
                            DWORD *out_status, int attempts, const char *what) {
    char *body = NULL;
    if (attempts < 1) attempts = 1;
    for (int attempt = 1; attempt <= attempts; attempt++) {
        DWORD st = 0;
        ULONGLONG started = GetTickCount64();
        body = http_get(host, path, out_len, &st);
        unsigned long long ms = (unsigned long long) (GetTickCount64() - started);
        if (body != NULL) {
            log_http_line(what, host, path, st, ms, attempt);
            if (out_status) *out_status = st;
            return body;
        }
        if (out_status) *out_status = st;
        if (attempt < attempts) Sleep(1000u * (unsigned) attempt);
    }
    return NULL;
}

/* Bytes already sitting in an interrupted download (0 when there is none). */
static unsigned long long partial_size(const wchar_t *path) {
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &fad)) return 0;
    return ((unsigned long long) fad.nFileSizeHigh << 32) | (unsigned long long) fad.nFileSizeLow;
}

/* Tell the dashboard how far the download got (wParam = percent). */
static void post_progress(int percent) {
    if (s_update_hwnd != NULL) PostMessageW(s_update_hwnd, WM_APP_UPDATE_PROGRESS, (WPARAM) percent, 0);
}

/*
 * Download a (redirecting) release asset to a local file.
 *
 * This is the step that decides whether "update" works at all on a slow line.
 * Measured from the maintainer's network GitHub's asset CDN delivered ~16 KB/s,
 * i.e. four minutes for the 4 MB portable package; the old code opened a fresh
 * connection, always wrote from byte zero and gave up on the first stall, so a
 * slow line ended in "download failed" no matter how often the user retried.
 * Now a partial file is kept and continued with a Range request (the CDN does
 * answer 206 for those), with up to UPDATE_DOWNLOAD_ATTEMPTS tries, 20 s
 * connect / 120 s receive timeouts and a percentage in the dashboard instead of
 * a frozen status line. The caller still verifies the published SHA-256 of the
 * finished file, so a resumed download is never trusted on size alone.
 */
#define UPDATE_DOWNLOAD_ATTEMPTS 5
static int http_download_to_file(const wchar_t *url, const wchar_t *file,
                                 const wchar_t *extra_headers) {
    wchar_t host[256] = L"", path[1024] = L"", mapped[1200] = L"";
    const wchar_t *p = wcsstr(url, L"://");
    p = p ? p + 3 : url;
    const wchar_t *slash = wcschr(p, L'/');
    if (!slash) return 0;
    size_t hostlen = (size_t) (slash - p);
    if (hostlen == 0 || hostlen >= 256) return 0;
    wcsncpy(host, p, hostlen);
    host[hostlen] = 0;
    wcsncpy(path, slash, 1023);
    path[1023] = 0;
    const wchar_t *connect_host = mirror_map(host, path, mapped, 1200);
    const wchar_t *connect_path = connect_host == host ? path : mapped;

    unsigned long long have = partial_size(file);
    for (int attempt = 1; attempt <= UPDATE_DOWNLOAD_ATTEMPTS; attempt++) {
        HINTERNET session = NULL, connect = NULL, request = NULL;
        FILE *out = NULL;
        unsigned long long declared = 0;
        int complete = 0;

        session = WinHttpOpen(L"ADBlock-WebServer", WINHTTP_ACCESS_TYPE_NO_PROXY,
                              WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (session == NULL) return 0;
        apply_update_proxy(session);
        WinHttpSetTimeouts(session, 20000, 20000, 30000, 120000);
        connect = WinHttpConnect(session, connect_host, INTERNET_DEFAULT_HTTPS_PORT, 0);
        request = connect != NULL
            ? WinHttpOpenRequest(connect, L"GET", connect_path, NULL, WINHTTP_NO_REFERER,
                                 WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE)
            : NULL;
        if (request != NULL) {
            DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
            wchar_t hdrs[512] = L"";
            WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy));
            if (have > 0) swprintf(hdrs, 512, L"Range: bytes=%llu-\r\n", have);
            if (extra_headers != NULL && extra_headers[0] != 0) {
                size_t n = wcslen(hdrs);
                if (n < 480) wcsncat(hdrs + n, extra_headers, 511 - n);
            }
            if (WinHttpSendRequest(request, hdrs[0] ? hdrs : WINHTTP_NO_ADDITIONAL_HEADERS,
                                   hdrs[0] ? (DWORD) -1L : 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
                WinHttpReceiveResponse(request, NULL)) {
                DWORD status = 0, slen = sizeof(status);
                DWORD clen_size = sizeof(declared);
                WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                    WINHTTP_HEADER_NAME_BY_INDEX, &status, &slen, WINHTTP_NO_HEADER_INDEX);
                WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                                    WINHTTP_HEADER_NAME_BY_INDEX, &declared, &clen_size,
                                    WINHTTP_NO_HEADER_INDEX);
                /* A server that ignores our Range header restarts the file. */
                if (status == 200) have = 0;
                if ((status == 200 || status == 206) &&
                    have + declared <= (unsigned long long) UPDATE_DOWNLOAD_MAX_BYTES) {
                    out = _wfopen(file, have > 0 ? L"ab" : L"wb");
                    if (out != NULL) {
                        /* When the server omits Content-Length, "have" alone has
                           to represent the total, so a clean EOF means done. */
                        unsigned long long total = declared > 0 ? have + declared : 0;
                        int io_ok = 1;
                        for (;;) {
                            DWORD avail = 0, read = 0;
                            char buffer[65536];
                            if (!WinHttpQueryDataAvailable(request, &avail) || avail == 0) break;
                            if (avail > sizeof(buffer)) avail = (DWORD) sizeof(buffer);
                            if (!WinHttpReadData(request, buffer, avail, &read) || read == 0) { io_ok = 0; break; }
                            have += read;
                            if (have > (unsigned long long) UPDATE_DOWNLOAD_MAX_BYTES ||
                                fwrite(buffer, 1, read, out) != read) { io_ok = 0; break; }
                            if (total > 0) post_progress((int) (have * 100ULL / total));
                        }
                        complete = io_ok && (declared == 0 || have >= total);
                    }
                }
            }
        }
        if (out != NULL) fclose(out);
        if (request != NULL) WinHttpCloseHandle(request);
        if (connect != NULL) WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);

        if (complete) {
            post_progress(100);
            win32_log_line("update: package downloaded (%llu bytes, attempt %d)",
                           have, attempt);
            return 1;
        }
        /* Keep whatever arrived and try to continue it after a short pause. */
        have = partial_size(file);
        win32_log_line("update: download attempt %d/%d incomplete (%llu bytes on disk)",
                       attempt, UPDATE_DOWNLOAD_ATTEMPTS, have);
        if (attempt < UPDATE_DOWNLOAD_ATTEMPTS) Sleep(1000 * attempt);
    }
    post_progress(-1);   /* -1 = give up (dashboard clears the percentage) */
    /* A failed download also invalidates the route decision: the proxy may have
       been started or stopped since it was probed. */
    s_proxy_resolved = 0;
    return 0;
}

/* Decimal id after ".../releases/assets/" (the /releases/assets/<id> endpoint). */
static void asset_id_from(const char *marker, char *out, size_t cap) {
    size_t k = 0;
    if (!out || cap == 0) return;
    out[0] = 0;
    while (marker && marker[k] >= '0' && marker[k] <= '9' && k + 1 < cap) {
        out[k] = marker[k];
        k++;
    }
    out[k] = 0;
}

/* Newest release publishing a Windows package, when it is newer than this
   build. 1 = newer release found, 0 = the API answered and there is none,
   -1 = the check itself failed (network/TLS/proxy/rate limit). The old code
   returned 0 for both, which is why a failed check looked like "up to date". */
static int find_latest_update(struct release_pick *pick, DWORD *http_status) {
    size_t len = 0;
    DWORD st = 0;
    int found = 0;
    memset(pick, 0, sizeof(*pick));
    char *json = http_get(UPDATE_API_HOST, UPDATE_API_PATH, &len, &st);
    if (http_status) *http_status = st;
    if (!json) {
        s_proxy_resolved = 0;   /* let the next check re-probe the route */
        return -1;
    }
    const char *p = json;
    while (!found && (p = strstr(p, "\"tag_name\"")) != NULL) {
        char tag[128] = "";
        const char *next = strstr(p + 10, "\"tag_name\"");
        if (json_string(json, "\"tag_name\"", p, tag, sizeof(tag))) {
            /* Walk every asset of this release: the Inno Setup installer is
               preferred, the portable zip is the fallback. */
            /*
             * Walk the assets of THIS release. An asset object starts at its
             * "url": ".../releases/assets/<id>" and the API puts the download URL
             * LAST inside that object (name, ..., digest, ... and
             * browser_download_url). The old code searched for "digest" AFTER
             * the download URL, so it always picked up the digest of the NEXT
             * asset: the downloaded installer then failed the SHA-256 check and
             * the dashboard reported "network is fine but it will not update".
             * name + digest + url are now read from the same asset object.
             */
            char exe_url[1024] = "", exe_sha[128] = "", exe_api[512] = "";
            char zip_url[1024] = "", zip_sha[128] = "", zip_api[512] = "";
            const char *chunk = p;
            for (;;) {
                const char *a = strstr(chunk, "releases/assets/");
                if (a == NULL || (next != NULL && a >= next)) break;
                const char *b = strstr(a + 16, "releases/assets/");
                if (b == NULL || (next != NULL && b > next)) b = next ? next : (a + strlen(a));
                size_t span = (size_t) (b - a);
                char region[8192];
                if (span >= sizeof(region)) span = sizeof(region) - 1;
                memcpy(region, a, span);
                region[span] = 0;
                char one[1024] = "", dig[128] = "";
                json_string(region, "\"browser_download_url\"", region, one, sizeof(one));
                json_string(region, "\"digest\"", region, dig, sizeof(dig));
                size_t l = strlen(one);
                int is_exe = l > 4 && _stricmp(one + l - 4, ".exe") == 0;
                int is_zip = l > 4 && _stricmp(one + l - 4, ".zip") == 0;
                if (is_exe && exe_url[0] == 0) {
                    char id[32] = "";
                    snprintf(exe_url, sizeof(exe_url), "%s", one);
                    snprintf(exe_sha, sizeof(exe_sha), "%s", dig);
                    asset_id_from(a + 16, id, sizeof(id));
                    if (id[0])
                        snprintf(exe_api, sizeof(exe_api),
                                 "https://api.github.com/repos/wxcvm/AdAway/releases/assets/%s", id);
                } else if (is_zip && zip_url[0] == 0) {
                    char id[32] = "";
                    snprintf(zip_url, sizeof(zip_url), "%s", one);
                    snprintf(zip_sha, sizeof(zip_sha), "%s", dig);
                    asset_id_from(a + 16, id, sizeof(id));
                    if (id[0])
                        snprintf(zip_api, sizeof(zip_api),
                                 "https://api.github.com/repos/wxcvm/AdAway/releases/assets/%s", id);
                }
                chunk = a + 16;
            }
            if (exe_url[0] || zip_url[0]) {
                const char *v = strrchr(tag, 'v');
                v = v ? v + 1 : tag;
                /*
                 * Only the Windows zip of this program is a valid update: the
                 * same feed also carries the Android APK, so the tag has to be
                 * the Windows one and the download must really be a .zip (this
                 * is what made an earlier build offer the Android APK).
                 */
                int is_win_release = strncmp(tag, "win11-webserver-", 16) == 0 ||
                                     strstr(exe_url, "adblock-webserver") != NULL ||
                                     strstr(zip_url, "adblock-webserver") != NULL;
                if (is_win_release && version_cmp(v, ADBLOCK_APP_VERSION) > 0) {
                    snprintf(pick->tag, sizeof(pick->tag), "%s", tag);
                    snprintf(pick->ver, sizeof(pick->ver), "%s", v);
                    snprintf(pick->exe_url, sizeof(pick->exe_url), "%s", exe_url);
                    snprintf(pick->exe_sha, sizeof(pick->exe_sha), "%s", exe_sha);
                    snprintf(pick->exe_api_url, sizeof(pick->exe_api_url), "%s", exe_api);
                    snprintf(pick->zip_url, sizeof(pick->zip_url), "%s", zip_url);
                    snprintf(pick->zip_sha, sizeof(pick->zip_sha), "%s", zip_sha);
                    snprintf(pick->zip_api_url, sizeof(pick->zip_api_url), "%s", zip_api);
                    found = 1;
                }
            }
        }
        p += 10;
    }
    free(json);
    return found ? 1 : 0;
}

/* Last failure reason, shown by the dashboard (see update_last_error()). */
static wchar_t s_check_error[192] = L"";

const wchar_t *update_last_error(void) { return s_check_error; }

/* Set by update_check_async(): a check the user asked for must not be answered
   from the "HTTP 403, do not ask again for 30 minutes" cooldown cache. */
static int s_force_check;

/* HTTP status of the last manifest attempt (0 = the download itself failed);
   the dashboard message names both routes, which is what made "检查更新失败"
   impossible to explain before. */
static DWORD s_manifest_status;

static DWORD WINAPI check_thread(LPVOID param) {
    HWND hwnd = (HWND) param;
    struct release_pick pick;
    wchar_t tag[128] = L"", url[1024] = L"", api_url[1024] = L"";
    wchar_t alt_url[1024] = L"", alt_api[1024] = L"";
    char digest[128] = "";
    char alt_digest[128] = "";
    struct update_info *info = NULL;
    DWORD st = 0;
    int force = s_force_check;
    s_force_check = 0;
    /* 1) fixed-tag manifest: no REST API, therefore no rate limit at all. */
    int rc = manifest_check(&pick, &st);
    s_manifest_status = st;
    if (rc > 0) {
        /* The manifest itself is the release: choose the package for this
           machine (installer copy vs. hand-extracted portable copy). */
        utf8_to_wide(pick.tag[0] ? pick.tag : pick.ver, tag, 128);
        release_pick_choose(&pick, url, 1024, digest, sizeof(digest), api_url, 1024,
                            alt_url, 1024, alt_digest, sizeof(alt_digest), alt_api, 1024);
    }
    if (rc < 0) {
        /* 2) API fallback, always mediated by the on-disk answer cache. */
        struct update_cache c;
        bool have = update_cache_load(&c);
        DWORD now = (DWORD) (GetTickCount64() / 1000ULL);
        bool fresh = !force && have && c.rc >= 0 &&
                     (now - c.checked_at) < UPDATE_CACHE_TTL_SEC;
        bool cooling = !force && have && (c.status == 403 || c.status == 429) &&
                       (now - c.checked_at) < UPDATE_COOLDOWN_SEC;
        if (fresh || cooling) {
            st = c.status;
            rc = c.rc;
            utf8_to_wide(c.tag, tag, 128);
            utf8_to_wide(c.url, url, 1024);
            utf8_to_wide(c.api_url, api_url, 1024);
            snprintf(digest, sizeof(digest), "%s", c.sha256);
        } else {
            st = 0;
            rc = find_latest_update(&pick, &st);
            if (rc < 0 && st != 403 && st != 429) {
                /* retry only non-rate-limit failures: a 403 already consumed one
                   of the 60 requests/hour, a retry would waste another. */
                Sleep(1500);
                st = 0;
                rc = find_latest_update(&pick, &st);
            }
            if (rc > 0) {
                utf8_to_wide(pick.tag[0] ? pick.tag : pick.ver, tag, 128);
                release_pick_choose(&pick, url, 1024, digest, sizeof(digest), api_url, 1024,
                                    alt_url, 1024, alt_digest, sizeof(alt_digest), alt_api, 1024);
            }
            struct update_cache nc;
            char narrow[1024] = "";
            memset(&nc, 0, sizeof(nc));
            nc.rc = rc;
            nc.status = st;
            nc.checked_at = (DWORD) (GetTickCount64() / 1000ULL);
            /* Cache the decision, not the raw answer: it already says which
               package this copy of the program needs. */
            if (tag[0]) { WideCharToMultiByte(CP_UTF8, 0, tag, -1, narrow, sizeof(narrow), NULL, NULL); snprintf(nc.tag, sizeof(nc.tag), "%s", narrow); }
            if (url[0]) { WideCharToMultiByte(CP_UTF8, 0, url, -1, narrow, sizeof(narrow), NULL, NULL); snprintf(nc.url, sizeof(nc.url), "%s", narrow); }
            if (api_url[0]) { WideCharToMultiByte(CP_UTF8, 0, api_url, -1, narrow, sizeof(narrow), NULL, NULL); snprintf(nc.api_url, sizeof(nc.api_url), "%s", narrow); }
            if (digest[0]) snprintf(nc.sha256, sizeof(nc.sha256), "%s", digest);
            update_cache_save(&nc);
        }
    }
    if (rc > 0) {
        info = (struct update_info *) calloc(1, sizeof(*info));
        if (info) {
            wcsncpy(info->tag, tag, 127);
            wcsncpy(info->url, url, 1023);
            wcsncpy(info->api_url, api_url, 1023);
            utf8_to_wide(digest, info->sha256, 128);
            wcsncpy(info->alt_url, alt_url, 1023);
            wcsncpy(info->alt_api_url, alt_api, 1023);
            utf8_to_wide(alt_digest, info->alt_sha256, 128);
        }
    } else if (rc < 0) {
        if (st == 403 || st == 429) {
            /* Rate limited: the manifest line is the one that must carry the
               check, so say which of the two failed and how. */
            if (s_manifest_status == 0)
                swprintf(s_check_error, 192,
                         L"更新清单下载失败（网络/代理），GitHub 接口也已限流（HTTP %lu）；"
                         L"稍后重试，或在 webserver.ini 里加 proxy= / mirror=",
                         (unsigned long) st);
            else
                swprintf(s_check_error, 192,
                         L"更新清单返回 HTTP %lu，GitHub 接口限流（HTTP %lu）；%lu 分钟后自动重试",
                         (unsigned long) s_manifest_status, (unsigned long) st,
                         (unsigned long) (UPDATE_COOLDOWN_SEC / 60));
        } else if (st > 0) {
            swprintf(s_check_error, 192, L"更新清单返回 HTTP %lu，GitHub 接口返回 HTTP %lu",
                     (unsigned long) s_manifest_status, (unsigned long) st);
        } else {
            swprintf(s_check_error, 192,
                     L"无法连接 GitHub（清单 HTTP %lu）：可在 webserver.ini 写 "
                     L"proxy=127.0.0.1:7890 或 mirror=https://ghfast.top/，详见 webserver.log",
                     (unsigned long) s_manifest_status);
        }
    } else {
        s_check_error[0] = 0;
    }
    PostMessageW(hwnd, WM_APP_UPDATE_FOUND, rc > 0 ? 1 : (rc == 0 ? 0 : 2), (LPARAM) info);
    /* The check is over; the GUI may now start a download (see apply_async). */
    InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);
    return 0;
}

void update_check_async(HWND hwnd) {
    /*
     * This is always a user action (the menu item and the toolbar button both
     * call it). Reset the route decision so the proxy is probed again, and let
     * the thread ignore the "HTTP 403, wait 30 minutes" cooldown: a click that
     * silently answers from a cached failure is indistinguishable from "the
     * update check is broken".
     */
    s_proxy_resolved = 0;
    s_mirror_loaded = 0;
    s_force_check = 1;
    /*
     * One pipeline at a time: a second click while a check (or a download) is
     * running is ignored instead of starting a competing thread.
     */
    if (InterlockedCompareExchange(&s_update_state, UPDATE_STATE_CHECKING,
                                   UPDATE_STATE_IDLE) != UPDATE_STATE_IDLE) {
        win32_log_line("update: %s is already running (state=%d) - click ignored",
                       update_state() == UPDATE_STATE_CHECKING ? "a check"
                       : (update_state() == UPDATE_STATE_DOWNLOADING ? "a download"
                                                                     : "an installation"),
                       update_state());
        s_force_check = 0;
        return;
    }
    CloseHandle(CreateThread(NULL, 0, check_thread, (LPVOID) hwnd, 0, NULL));
}

void update_info_free(struct update_info *info) {
    free(info);
}

/* ── applying an update ───────────────────────────────────────── */

static int run_and_wait(const wchar_t *cmdline) {
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    wchar_t mutable_cmd[4096];
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    memset(&pi, 0, sizeof(pi));
    wcsncpy(mutable_cmd, cmdline, 4095);
    mutable_cmd[4095] = 0;
    if (!CreateProcessW(NULL, mutable_cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi))
        return -1;
    WaitForSingleObject(pi.hProcess, 180000);
    DWORD code = (DWORD) -1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return (int) code;
}

/* Write a text file in the ANSI code page (cmd.exe reads .bat that way). */
static int write_ansi_file(const wchar_t *path, const wchar_t *text) {
    int need = WideCharToMultiByte(CP_ACP, 0, text, -1, NULL, 0, NULL, NULL);
    if (need <= 0) return 0;
    char *bytes = (char *) malloc((size_t) need);
    if (!bytes) return 0;
    WideCharToMultiByte(CP_ACP, 0, text, -1, bytes, need, NULL, NULL);
    FILE *fp = _wfopen(path, L"wb");
    int ok = 0;
    if (fp) {
        ok = fwrite(bytes, 1, (size_t) need - 1, fp) == (size_t) need - 1;
        fclose(fp);
    }
    free(bytes);
    return ok;
}

/* SHA-256 of a file, lowercase hex (Bcrypt). Returns 0 on failure. */
static int file_sha256_hex(const wchar_t *path, char *out, size_t cap) {
    BCRYPT_ALG_HANDLE alg = NULL;
    BCRYPT_HASH_HANDLE hash = NULL;
    unsigned char *obj = NULL, *buf = NULL, digest[32];
    DWORD obj_len = 0, got = 0, hash_len = 0;
    int ok = 0;
    FILE *fp = _wfopen(path, L"rb");
    if (!fp) return 0;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0) != 0) goto done;
    if (BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, (PUCHAR) &obj_len, sizeof(obj_len), &got, 0) != 0) goto done;
    obj = (unsigned char *) malloc(obj_len);
    buf = (unsigned char *) malloc(64 * 1024);
    if (!obj || !buf) goto done;
    if (BCryptCreateHash(alg, &hash, obj, obj_len, NULL, 0, 0) != 0) goto done;
    for (;;) {
        size_t n = fread(buf, 1, 64 * 1024, fp);
        if (n == 0) break;
        if (BCryptHashData(hash, buf, (ULONG) n, 0) != 0) goto done;
    }
    if (BCryptFinishHash(hash, digest, sizeof(digest), 0) != 0) goto done;
    if (BCryptGetProperty(alg, BCRYPT_HASH_LENGTH, (PUCHAR) &hash_len, sizeof(hash_len), &got, 0) != 0) goto done;
    if (hash_len != 32 || cap < 65) goto done;
    for (int i = 0; i < 32; i++) snprintf(out + i * 2, cap - i * 2, "%02x", digest[i]);
    ok = 1;
done:
    if (hash) BCryptDestroyHash(hash);
    if (alg) BCryptCloseAlgorithmProvider(alg, 0);
    free(obj);
    free(buf);
    fclose(fp);
    return ok;
}

/* Expected digest is "sha256:<hex>"; an empty expectation means "not published". */
static int digest_matches(const wchar_t *path, const wchar_t *expected) {
    /* An absent digest is NOT a pass: the caller decides (it then requires a
       valid Authenticode signature). Audit phase 2, item B. */
    if (!expected || !expected[0]) return 0;
    char want[128] = "", got[128] = "";
    {
        char narrow[128] = "";
        WideCharToMultiByte(CP_UTF8, 0, expected, -1, narrow, sizeof(narrow), NULL, NULL);
        snprintf(want, sizeof(want), "%s", narrow);
    }
    const char *hex = strchr(want, ':');
    hex = hex ? hex + 1 : want;
    if (!file_sha256_hex(path, got, sizeof(got))) return 0;
    return _stricmp(got, hex) == 0;
}

/* Authenticode check (best effort: unsigned packages are reported, not fatal,
   because the SHA-256 digest is verified separately). */
static int file_signature_trusted(const wchar_t *path) {
    WINTRUST_FILE_INFO file_info;
    WINTRUST_DATA data;
    GUID policy = WINTRUST_ACTION_GENERIC_VERIFY_V2;
    memset(&file_info, 0, sizeof(file_info));
    file_info.cbStruct = sizeof(file_info);
    file_info.pcwszFilePath = path;
    memset(&data, 0, sizeof(data));
    data.cbStruct = sizeof(data);
    data.dwUIChoice = WTD_UI_NONE;
    data.fdwRevocationChecks = WTD_REVOKE_NONE;
    data.dwUnionChoice = WTD_CHOICE_FILE;
    data.pFile = &file_info;
    data.dwStateAction = WTD_STATEACTION_VERIFY;
    LONG rc = WinVerifyTrust(NULL, &policy, &data);
    data.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(NULL, &policy, &data);
    return rc == ERROR_SUCCESS;
}

/* Is this downloaded package the installer? */
static int file_is_exe(const wchar_t *path) {
    size_t n = wcslen(path);
    if (n < 4) return 0;
    return _wcsicmp(path + n - 4, L".exe") == 0;
}

/* A downloaded portable update must really be a zip (PK\x03\x04) - never an APK. */
static int file_is_zip(const wchar_t *path) {
    FILE *fp = _wfopen(path, L"rb");
    if (!fp) return 0;
    unsigned char magic[4] = {0, 0, 0, 0};
    size_t got = fread(magic, 1, 4, fp);
    fclose(fp);
    return got == 4 && magic[0] == 'P' && magic[1] == 'K';
}

/* ── archive validation (audit A4) ─────────────────────────────────
 * An update archive is untrusted data: before extracting anything, every
 * entry name is checked so that no member can escape the staging directory
 * (absolute path, drive letter, UNC path, "..", alternate data stream) and
 * the number of entries is bounded. */
#define TAR_ENTRY_LIMIT 4000

static int tar_entry_is_safe(const char *line) {
    const char *p = line;
    if (!*p) return 1;                                  /* blank line */
    if (*p == '/' || *p == '\\') return 0;               /* absolute path */
    if (p[0] == '.' && p[1] == '.') return 0;            /* leading ".." */
    while (*p) {
        if (*p == ':') return 0;                        /* C: or file:stream */
        if (p[0] == '.' && p[1] == '.' && (p[2] == '/' || p[2] == '\\')) return 0;
        if (p[0] == '\\' && p[1] == '\\') return 0;       /* UNC inside the name */
        p++;
    }
    return 1;
}

/* 1 = listing is safe, 0 = hostile archive, -1 = could not list at all. */
static int tar_listing_is_safe(const wchar_t *zip, const wchar_t *listfile) {
    wchar_t cmd[4096];
    swprintf(cmd, 4096, L"cmd.exe /c tar.exe -tf \"%s\" > \"%s\"", zip, listfile);
    if (run_and_wait(cmd) != 0) return -1;
    FILE *fp = _wfopen(listfile, L"rb");
    if (!fp) return -1;
    static char line[8192];
    int count = 0, ok = 1;
    while (fgets(line, (int) sizeof(line), fp)) {
        size_t n = strlen(line);
        int complete = n > 0 && line[n - 1] == '\n';
        while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        if (n == (size_t) sizeof(line) - 1 && !complete) { ok = 0; break; }  /* absurdly long name */
        if (line[0] == 0) continue;
        if (++count > TAR_ENTRY_LIMIT || !tar_entry_is_safe(line)) { ok = 0; break; }
    }
    fclose(fp);
    return ok && count > 0 ? 1 : 0;
}

/* Does this directory contain webserver.exe? */
static int dir_has_exe(const wchar_t *dir) {
    wchar_t probe[MAX_PATH];
    swprintf(probe, MAX_PATH, L"%s\\webserver.exe", dir);
    return GetFileAttributesW(probe) != INVALID_FILE_ATTRIBUTES;
}

/* Find the directory holding webserver.exe inside the extracted zip. */
static int find_extracted_dir(const wchar_t *root, wchar_t *out, size_t cap) {
    if (dir_has_exe(root)) {
        wcsncpy(out, root, cap - 1);
        out[cap - 1] = 0;
        return 1;
    }
    wchar_t pattern[MAX_PATH];
    swprintf(pattern, MAX_PATH, L"%s\\*", root);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;
    int found = 0;
    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && fd.cFileName[0] != L'.') {
            wchar_t sub[MAX_PATH];
            swprintf(sub, MAX_PATH, L"%s\\%s", root, fd.cFileName);
            if (dir_has_exe(sub)) {
                wcsncpy(out, sub, cap - 1);
                out[cap - 1] = 0;
                found = 1;
                break;
            }
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return found;
}

/* Unique temp file for one candidate package; the extension must follow the
   URL because it decides later whether the result is run as an installer or
   unpacked as a portable package. */
static void update_temp_file(const wchar_t *temp, DWORD pid, DWORD stamp,
                             const wchar_t *url, wchar_t *out, size_t cap) {
    size_t ulen = wcslen(url);
    const wchar_t *ext = (ulen > 4 && _wcsicmp(url + ulen - 4, L".exe") == 0) ? L".exe" : L".zip";
    swprintf(out, cap, L"%sadblock-update-%lu-%lu%s", temp,
             (unsigned long) pid, (unsigned long) stamp, ext);
}

static DWORD WINAPI apply_thread(LPVOID param) {
    struct update_info *info = (struct update_info *) param;
    wchar_t temp[MAX_PATH], zip[MAX_PATH], list[MAX_PATH], dir[MAX_PATH], src[MAX_PATH];
    wchar_t appdir[MAX_PATH], exe[MAX_PATH], cmd[4096], script[8192], bat[MAX_PATH];
    DWORD pid = GetCurrentProcessId();
    DWORD stamp = GetTickCount();

    GetTempPathW(MAX_PATH, temp);
    /*
     * Keep the REAL extension of the downloaded asset: the feed ships either
     * the Inno Setup installer (.exe) or the portable zip. Naming everything
     * "adblock-update.zip" made file_is_exe() always false, so an installer
     * download then failed the PK check and reported "download failed" even
     * though the network was fine. The name also has to be unpredictable: a
     * fixed %TEMP% target could be pre-created by another process of the same
     * user (audit P0-1).
     */
    update_temp_file(temp, pid, stamp, info->url, zip, MAX_PATH);
    /* Unique staging paths as well (audit A6). A fixed "%TEMP%\adblock-update"
       could be pre-created by another process, and it was never removed: the
       batch script now deletes it after the file copy. */
    swprintf(dir, MAX_PATH, L"%sadblock-update-%lu-%lu", temp, (unsigned long) pid, (unsigned long) stamp);
    swprintf(list, MAX_PATH, L"%sadblock-update-%lu-%lu.list", temp, (unsigned long) pid, (unsigned long) stamp);
    swprintf(bat, MAX_PATH, L"%sadblock-update-%lu-%lu.bat", temp, (unsigned long) pid, (unsigned long) stamp);

    /*
     * Chance 1: the package this machine normally wants (installer for an
     * installed copy, portable zip for a hand-extracted one), first via the
     * plain release URL and then via the API asset endpoint
     * (/releases/assets/<id> with Accept: application/octet-stream) - the
     * download host (*.githubusercontent.com) is unreachable on some networks
     * while api.github.com answers fine.
     * Chance 2: the release's OTHER package. The installer is ~5.3 MB and the
     * portable zip ~4.1 MB; on a slow line that difference decides whether the
     * download finishes inside the timeout.
     */
    const wchar_t *sha_used = info->sha256;
    int got = 0;
    if (http_download_to_file(info->url, zip, NULL) ||
        (info->api_url[0] &&
         http_download_to_file(info->api_url, zip, L"Accept: application/octet-stream\r\n"))) {
        got = 1;
    } else if (info->alt_url[0]) {
        wchar_t alt[MAX_PATH];
        DeleteFileW(zip);
        update_temp_file(temp, pid, stamp, info->alt_url, alt, MAX_PATH);
        if (http_download_to_file(info->alt_url, alt, NULL) ||
            (info->alt_api_url[0] &&
             http_download_to_file(info->alt_api_url, alt, L"Accept: application/octet-stream\r\n"))) {
            wcscpy(zip, alt);
            sha_used = info->alt_sha256;
            got = 1;
        } else {
            DeleteFileW(alt);
        }
    }
    if (!got) {
        if (MessageBoxW(NULL,
                        L"下载更新失败：已重试 5 次并尝试断点续传，仍无法从 GitHub 的下载服务器取回更新包。\n\n"
                        L"提示：这条线路到 GitHub 资源节点可能只有十几 KB/s，4 MB 的包需要几分钟；\n"
                        L"如果一直失败，请检查代理设置，或改用浏览器手动下载。\n\n"
                        L"是否用浏览器打开下载页，手动下载安装？",
                        L"更新", MB_YESNO | MB_ICONWARNING) == IDYES) {
            ShellExecuteW(NULL, L"open", L"https://github.com/wxcvm/AdAway/releases",
                          NULL, NULL, SW_SHOWNORMAL);
        }
        DeleteFileW(zip);          /* no half-downloaded package in %TEMP% */
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    /*
     * SECURITY: never execute a downloaded file blindly. The API publishes a
     * per-asset "sha256:<hex>" digest and we refuse anything that does not
     * match it (an empty digest - only old releases - falls back to the
     * PK / PE sanity check below).
     */
    /*
     * SECURITY (audit A2/A3): a package is accepted only when the published
     * SHA-256 digest matches, or - if the release publishes no digest - when
     * its Authenticode signature verifies. "No digest" never means "verified",
     * and the signature check is really executed here (it used to be dead
     * code).
     */
    if (sha_used[0] != 0) {
        if (!digest_matches(zip, sha_used)) {
            DeleteFileW(zip);
            MessageBoxW(NULL, L"更新包校验失败（SHA-256 与发布信息不一致），已删除，未执行。",
                        L"更新", MB_OK | MB_ICONERROR);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
            free(info);
            return 0;
        }
    } else {
        /*
         * Audit item 48: without a published digest the old code accepted *any*
         * Authenticode-signed binary as "trusted", which is far weaker than it
         * sounds (any validly signed executable passes). The feed always
         * publishes a SHA-256, so "no digest" now means "do not install
         * automatically" - the user is pointed at the Releases page instead.
         * The signature is still checked and logged when present.
         */
        if (!file_signature_trusted(zip))
            win32_log_line("update: package has no valid Authenticode signature "
                           "(it will not be installed without a digest anyway)");
        DeleteFileW(zip);
        MessageBoxW(NULL, L"发布信息里没有 SHA-256 摘要，已拒绝自动更新。\n\n"
                          L"请在 Releases 页手动下载安装包，并自行核对发布说明里的摘要。",
                    L"更新", MB_OK | MB_ICONERROR);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    /*
     * The preferred package is the Inno Setup installer: it stops the running
     * server (taskkill in its [Code] section), replaces the files, recreates
     * the shortcuts and restarts the server - no batch script needed.
     */
    if (file_is_exe(zip)) {
        wchar_t params[256];
        swprintf(params, 256, L"/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /PID=%lu",
                 (unsigned long) pid);
        /*
         * Audit item 37: only quit when the installer really started. The old
         * code called ShellExecuteW and exited unconditionally, so a failure
         * (blocked by policy, quarantined by antivirus, no shell association)
         * left the user with neither the old nor the new program running.
         */
        HINSTANCE started = ShellExecuteW(NULL, L"open", zip, params, NULL, SW_SHOWNORMAL);
        if ((INT_PTR) started <= 32) {
            wchar_t msg[600];
            swprintf(msg, 600,
                     L"安装程序没能启动（ShellExecute 返回 %d）。\n\n"
                     L"本程序保持运行、没有改动任何文件。\n"
                     L"可以手动解压这个更新包覆盖当前目录：\n%ls",
                     (int) (INT_PTR) started, zip);
            MessageBoxW(NULL, msg, L"更新", MB_OK | MB_ICONERROR);
            win32_log_line("update: ShellExecuteW(installer) failed, code=%d",
                           (int) (INT_PTR) started);
            InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);
            free(info);
            return 0;
        }
        win32_log_line("update: installer started (ShellExecute ok)");
        free(info);
        if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_APP_EXIT, 0, 0);   /* WM_CLOSE only hides the window: a helper batch waiting for the PID would wait forever */
        return 0;
    }
    if (!file_is_zip(zip)) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"下载更新失败，请检查网络后重试（也可手动下载 zip 覆盖）。",
                    L"更新", MB_OK | MB_ICONWARNING);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    /* Audit A4: validate the archive listing before extracting a single byte. */
    /* Digits verified: from here on the package is installed. */
    InterlockedExchange(&s_update_state, UPDATE_STATE_INSTALLING);
    int listing = tar_listing_is_safe(zip, list);
    DeleteFileW(list);
    if (listing < 0) {
        MessageBoxW(NULL, L"无法校验更新包内容（本机缺少 tar.exe，需要 Windows 10 1803 或更高版本）。\n\n请到 Releases 页面手动下载安装包。",
                    L"更新", MB_OK | MB_ICONWARNING);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    if (listing == 0) {
        DeleteFileW(zip);
        MessageBoxW(NULL, L"更新包校验失败：压缩包内含越权路径（绝对路径或 ..），已拒绝执行。",
                    L"更新", MB_OK | MB_ICONERROR);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    swprintf(cmd, 4096, L"cmd.exe /c rmdir /S /Q \"%s\"", dir);
    run_and_wait(cmd);
    swprintf(cmd, 4096, L"cmd.exe /c mkdir \"%s\"", dir);
    run_and_wait(cmd);
    swprintf(cmd, 4096, L"tar.exe -xf \"%s\" -C \"%s\"", zip, dir);
    int rc = run_and_wait(cmd);
    DeleteFileW(zip);              /* the batch script only needs the extracted tree */
    if (rc != 0 || !find_extracted_dir(dir, src, MAX_PATH)) {
        /* The archive must really contain the server: the old fallback copied
           whatever was there and closed the app without installing anything. */
        swprintf(cmd, 4096, L"cmd.exe /c rmdir /S /Q \"%s\"", dir);
        run_and_wait(cmd);
        MessageBoxW(NULL, L"解压更新包失败或包内缺少 webserver.exe，未做任何改动。\n\n请手动下载 zip 覆盖安装。",
                    L"更新", MB_OK | MB_ICONWARNING);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    wcsncpy(appdir, exe, MAX_PATH - 1);
    appdir[MAX_PATH - 1] = 0;
    wchar_t *slash = wcsrchr(appdir, L'\\');
    if (slash) *slash = 0;

    swprintf(script, 8192,
             L"@echo off\r\n"
             L"setlocal\r\n"
             L"set PID=%lu\r\n"
             L":waitloop\r\n"
             L"tasklist /FI \"PID eq %%PID%%\" 2>nul | find \"%%PID%%\" >nul\r\n"
             L"if not errorlevel 1 (\r\n"
             L"  timeout /t 1 /nobreak >nul\r\n"
             L"  goto waitloop\r\n"
             L")\r\n"
             L"xcopy /E /I /Y \"%s\\*\" \"%s\\\" >nul\r\n"
             L"if errorlevel 1 goto :failed\r\n"
             L"rmdir /S /Q \"%s\" >nul 2>&1\r\n"
             L"start \"\" \"%s\\webserver.exe\" --minimized\r\n"
             L"del \"%%~f0\" >nul 2>&1\r\n"
             L":failed\r\n"
             L"rem xcopy failed (locked file or no permission): restart the old build\r\n"
             L"rem instead of leaving a half-updated installation behind.\r\n"
             L"rmdir /S /Q \"%s\" >nul 2>&1\r\n"
             L"start \"\" \"%s\\webserver.exe\" --minimized\r\n"
             L"del \"%%~f0\" >nul 2>&1\r\n",
             (unsigned long) pid, src, appdir, dir, appdir, dir, appdir);
    if (!write_ansi_file(bat, script)) {
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    swprintf(cmd, 4096, L"cmd.exe /c \"%s\"", bat);
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof(si));
    si.cb = sizeof(si);
    memset(&pi, 0, sizeof(pi));
    wchar_t mutable_cmd[4096];
    wcsncpy(mutable_cmd, cmd, 4095);
    mutable_cmd[4095] = 0;
    if (CreateProcessW(NULL, mutable_cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    } else {
        DeleteFileW(bat);
        MessageBoxW(NULL, L"无法启动更新脚本，未做任何改动。", L"更新", MB_OK | MB_ICONERROR);
        InterlockedExchange(&s_update_state, UPDATE_STATE_IDLE);   /* failure: the updater must stay usable */
        free(info);
        return 0;
    }
    free(info);
    if (s_update_hwnd) PostMessageW(s_update_hwnd, WM_APP_EXIT, 0, 0);   /* WM_CLOSE only hides the window: a helper batch waiting for the PID would wait forever */
    return 0;
}

void update_apply_async(HWND hwnd, const struct update_info *info) {
    if (InterlockedCompareExchange(&s_update_state, UPDATE_STATE_DOWNLOADING,
                                   UPDATE_STATE_IDLE) != UPDATE_STATE_IDLE) {
        win32_log_line("update: refusing to start a second download (state=%d)",
                       update_state());
        return;
    }
    s_update_hwnd = hwnd;
    struct update_info *copy = (struct update_info *) calloc(1, sizeof(*copy));
    if (!copy) return;
    *copy = *info;
    CloseHandle(CreateThread(NULL, 0, apply_thread, (LPVOID) copy, 0, NULL));
}
